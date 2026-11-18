/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestSuite;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;
import de.haumacher.ehome.programming.download.I18NConstants;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.programming.download.ProgrammingReport;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.programming.download.SnapshotFile;
import de.haumacher.ehome.programming.download.SnapshotFunctions;
import de.haumacher.ehome.programming.download.Snapshots;
import de.haumacher.ehome.scan.ReadBack;
import de.haumacher.ehome.scan.SnapshotTakeOver;

/**
 * The snapshot of a device (haumacher/ehome #193) on the device of <code>samples/Test.knxproj</code>
 * downloaded into a device in memory ({@link MockDevice}): stored with the device and read back
 * equal, the file round trip and the refusal of a file of another program, the comparison of a
 * snapshot with the plan showing the device's and the plan's bytes of a changed parameter and a
 * changed table, and the take-over from a snapshot giving the project the state the take-over from
 * the bus gives.
 */
@SuppressWarnings("javadoc")
public class TestDeviceSnapshots extends BasicTestCase {

	/** The projects before the test. */
	static List<TLObject> _before;

	/** The device of Test.knxproj, imported once for the tests of this class. */
	static TLObject _device;

	public void testStoredAndFile() throws Exception {
		MockDevice mock = downloaded();
		Snapshot snapshot = ReadOut.read(_device, mock, (number, count, block) -> {
			// No progress.
		});
		assertTrue(snapshot.isComplete());
		assertEquals("0705", snapshot.getDescriptor());
		assertEquals(DeviceConfiguration.program(_device).getExtId(), snapshot.getProgram());
		assertEquals(DownloadImage.individualAddress(_device), snapshot.getAddress());
		assertFalse(snapshot.ranges().isEmpty());
		assertFalse(snapshot.getLoadStates().isEmpty());
		// Every byte of every loaded block of the image is read and equals the device's memory.
		DownloadPlan plan = DownloadPlanner.plan(_device, DownloadPlan.Kind.FULL);
		for (Block block : plan.getImage().getBlocks()) {
			if (plan.getUnloadedBlocks().contains(block) || block.stateMachine() != null) {
				continue;
			}
			for (int n = 0; n < block.data().length; n++) {
				if (block.mask()[n] != 0) {
					assertEquals(Integer.valueOf(mock.memory()[block.address() + n] & 0xFF),
						snapshot.byteAt(block.address() + n));
				}
			}
		}

		TLObject stored = store(snapshot);
		assertTrue("Stored and loaded equal.", snapshot.sameContent(Snapshots.load(stored)));
		assertEquals(List.of(stored), Snapshots.of(_device));

		// The file round trip: download, upload into the same device, an equal snapshot.
		BinaryData file = SnapshotFile.export(Snapshots.load(stored));
		TLObject uploaded;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			uploaded = SnapshotFunctions.upload(_device, file);
			tx.commit();
		}
		assertTrue("Uploaded equal.", snapshot.sameContent(Snapshots.load(uploaded)));
		assertEquals(file.getName(), uploaded.tValueByName("file"));

		// A file of another program is refused, with the reason.
		String other = SnapshotFile.write(snapshot).replace(snapshot.getProgram(), "M-00FA_A-0000-00-0000");
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			SnapshotFunctions.upload(_device, BinaryDataFactory.createBinaryData(other.getBytes(StandardCharsets.UTF_8),
				SnapshotFile.CONTENT_TYPE, "other.json"));
			fail("A snapshot of another program is refused.");
		} catch (TopLogicException ex) {
			String reason = Resources.getInstance().getString(ex.getErrorKey());
			assertTrue(reason, reason.contains("M-00FA_A-0000-00-0000"));
			assertTrue(reason, reason.contains(snapshot.getProgram()));
		}
		// A file of another mask likewise.
		String otherMask = SnapshotFile.write(snapshot).replace("\"mask\": \"0705\"", "\"mask\": \"0701\"");
		assertFalse(SnapshotFile.write(snapshot).equals(otherMask));
		assertEquals(I18NConstants.SNAPSHOT_OTHER_MASK__SNAPSHOT_DEVICE.fill("0701", "0705"),
			SnapshotFile.veto(_device, SnapshotFile.parse(otherMask)));
		delete(stored, uploaded);
	}

	public void testCompareShowsValues() throws Exception {
		MockDevice mock = downloaded();
		Edit edit = edit();
		DownloadPlan plan = DownloadPlanner.plan(_device, DownloadPlan.Kind.FULL);
		TLObject stored = store(ReadOut.read(_device, mock, (number, count, block) -> {
			// No progress.
		}));
		Snapshot snapshot = Snapshots.load(stored);
		DeviceComparison.Result result = DeviceComparison.compare(DeviceConfiguration.program(_device), plan, snapshot);
		List<String> blocksDiffering = new ArrayList<>();
		for (DeviceComparison.BlockResult block : result.blocks()) {
			assertNull(block.name(), block.failure());
			for (DeviceComparison.Difference difference : block.differences()) {
				blocksDiffering.add(block.name());
				String[] device = difference.device().split(" ");
				String[] planned = difference.plan().split(" ");
				assertEquals(difference.length(), device.length);
				for (int n = 0; n < difference.length(); n++) {
					int address = difference.address() + n;
					assertEquals(String.format("%02X", mock.memory()[address] & 0xFF), device[n]);
					assertEquals(String.format("%02X", plan.getImage().byteAt(address)), planned[n]);
				}
			}
		}
		assertTrue("The changed parameter: " + blocksDiffering + ", " + edit,
			blocksDiffering.stream().anyMatch(name -> !name.contains("table")));
		assertTrue("The changed table: " + blocksDiffering + ", " + edit,
			blocksDiffering.stream().anyMatch(name -> name.contains("table")));

		// The view's function: the same differences in the report.
		Map<String, Object> report = SnapshotFunctions.compare(stored);
		List<?> rows = (List<?>) report.get(ProgrammingReport.DIFFERENCES);
		int differences = result.blocks().stream().mapToInt(b -> b.differences().size()).sum();
		assertEquals(differences, rows.size());
		Map<?, ?> first = (Map<?, ?>) rows.get(0);
		assertNotNull(first.get("device"));
		assertNotNull(first.get("plan"));
		assertNotNull(first.get("address"));
		delete(stored);
	}

	public void testTakeOverLikeFromTheBus() throws Exception {
		MockDevice mock = downloaded();
		edit();
		Snapshot snapshot = ReadOut.read(_device, mock, (number, count, block) -> {
			// No progress.
		});
		TLObject stored = store(snapshot);

		ReadBack.Report fromBus = ReadBack.takeOver(_device, ReadBack.read(_device, mock, (done, total, what) -> {
			// No progress.
		}));
		assertTrue(fromBus.problems() + " " + fromBus.differences(), fromBus.identical());
		String busState = state(_device);

		// The project changed again, then taken over from the snapshot: the same state.
		edit();
		assertFalse(busState.equals(state(_device)));
		String text = SnapshotTakeOver.takeOver(stored);
		assertNotNull(text);
		assertEquals(busState, state(_device));
		delete(stored);
	}

	/**
	 * A device in memory into which the current plan of {@link #_device} was downloaded.
	 */
	private static MockDevice downloaded() throws InterruptedException {
		DownloadPlan plan = DownloadPlanner.plan(_device, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		MockDevice mock = new MockDevice().setDescriptor("0705");
		// A device of the program's hardware that takes the program's identity from its task segment: it
		// answers the comparisons of the load procedure.
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.CompareProperty compare) {
				mock.setPropertyElement(compare.objectIndex(), compare.propertyId(), compare.start(), compare.expected());
			}
		}
		// The table pointers (property 7 of the objects 1 and 2) name the tables where the image has them.
		Block addressTable = plan.getImage().getAddressTable();
		Block associationTable = plan.getImage().getAssociationTable();
		mock.setProperty(1, 7, String.format("%04X", addressTable.address()));
		mock.setProperty(2, 7, String.format("%04X", associationTable.address()));
		DownloadExecutor.Result result = DownloadService.download(_device, plan, mock,
			new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
		assertTrue(result.failure(), result.success());
		return mock;
	}

	/**
	 * What {@link #edit()} changed.
	 */
	record Edit(String parameter, String connection) {
		// A value.
	}

	/**
	 * Changes a parameter in effect stored in memory and removes a connection: the image then
	 * differs in a parameter byte and in the tables. The choice depends on the device's state only.
	 */
	private static Edit edit() {
		DeviceConfiguration configuration = new DeviceConfiguration(_device);
		ParameterView view = null;
		Object value = null;
		List<Map.Entry<Parameter, ParameterView>> views = new ArrayList<>(configuration.getActiveViews().entrySet());
		views.sort(Comparator.comparing(e -> String.valueOf(e.getKey().getExtId())));
		for (Map.Entry<Parameter, ParameterView> active : views) {
			if (!DeviceConfiguration.isEditable(active.getValue()) || active.getKey().getAddress() == null) {
				continue;
			}
			String current = configuration.value(active.getValue());
			if (active.getKey().getType() instanceof EnumerationType type) {
				for (EnumerationValue entry : type.getValues()) {
					if (!String.valueOf(entry.getValue()).equals(current)) {
						view = active.getValue();
						value = entry;
						break;
					}
				}
			} else if (active.getKey().getType() instanceof NumericType type && type.getMin() != type.getMax()) {
				view = active.getValue();
				value = String.valueOf(type.getMin()).equals(current) ? type.getMax() : type.getMin();
			}
			if (view != null) {
				break;
			}
		}
		assertNotNull("An editable parameter in memory.", view);
		List<TLObject> connections = new ArrayList<>();
		for (Object connector : (Collection<?>) _device.tValueByName("connectors")) {
			for (Object connection : (Collection<?>) ((TLObject) connector).tValueByName("connections")) {
				connections.add((TLObject) connection);
			}
		}
		assertFalse("A connection of the device.", connections.isEmpty());
		connections.sort(Comparator.comparing(TestDeviceSnapshots::connectionKey));
		TLObject connection = connections.get(0);
		String key = connectionKey(connection);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			// A value equal to the default removes the setting (null).
			DeviceSettings.setValue(_device, view, value);
			DeviceSettings.disconnect(connection);
			tx.commit();
		}
		return new Edit(view.toString(), key);
	}

	private static String connectionKey(TLObject connection) {
		TLObject connector = connection.tContainer();
		return connector.tValueByName("port") + "/" + connection.tValueByName("target") + "/"
			+ connection.tValueByName("kind");
	}

	/**
	 * The configuration of the device as a download writes it, and its parameter settings and
	 * connections.
	 */
	private static String state(TLObject device) {
		List<String> settings = new ArrayList<>();
		for (Object setting : (Collection<?>) device.tValueByName("settings")) {
			TLObject s = (TLObject) setting;
			settings.add(s.tValueByName("parameter") + "@" + s.tValueByName("instance") + "=" + s.tValueByName("choice")
				+ "/" + s.tValueByName("number") + "/" + s.tValueByName("decimal") + "/" + s.tValueByName("text"));
		}
		settings.sort(null);
		List<String> connections = new ArrayList<>();
		for (Object connector : (Collection<?>) device.tValueByName("connectors")) {
			for (Object connection : (Collection<?>) ((TLObject) connector).tValueByName("connections")) {
				connections.add(connectionKey((TLObject) connection));
			}
		}
		connections.sort(null);
		return DownloadImage.compute(device).dump() + "\n" + settings + "\n" + connections;
	}

	private static TLObject store(Snapshot snapshot) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject result = Snapshots.store(_device, snapshot);
			tx.commit();
			return result;
		}
	}

	private static void delete(TLObject... snapshots) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject snapshot : snapshots) {
				snapshot.tDelete();
			}
			tx.commit();
		}
	}

	private static Test withDevice(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestDeviceSnapshots.class, () -> {
					_before = instances("ehome.project:Project");
					importSample("samples/Test.knxproj");
					List<TLObject> projects = SharedData.newProjects(_before);
					assertEquals(1, projects.size());
					_device = SharedData.within("ehome.physical:Device", projects).get(0);
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestDeviceSnapshots.class, () -> {
					SharedData.deleteProjects(SharedData.newProjects(_before));
					return null;
				});
				_device = null;
				_before = null;
			}
		};
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(withDevice(new TestSuite(TestDeviceSnapshots.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
