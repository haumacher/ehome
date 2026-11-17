/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.Logger;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.LoadStateMachines;
import de.haumacher.ehome.programming.download.Operation;

/**
 * Test of the download into a device of mask 7.1 (BIM M112, load state machines controlled in
 * memory; haumacher/ehome #22): the program of the actuator 1.3.5 of <code>TestMask71.knxproj</code>
 * (the test system's actuator program turned into one of mask 7.1, see <code>make-fixtures.py</code>)
 * against the same program of mask 7.5 in <code>samples/KNX-Testsystem.knxproj</code>.
 *
 * <ul>
 * <li>The memory record of a load control, against the records the KNX management procedures (3.31.2)
 * and the recordings cited in {@link LoadControls} show.</li>
 * <li>The plan: the same steps, memory writes and load controls as on mask 7.5, the load controls
 * written into memory 0104 of the mask's resources as records of 11 bytes, each checked by the load
 * state of its load state machine (B6EA-B6EC); the task segment of load state machine 5 left
 * out.</li>
 * <li>The download on a {@link MockDevice} of mask 7.1, which decodes the records itself; a device
 * whose load state does not follow refuses the download at that load control, after reading the
 * state again three times.</li>
 * <li>The comparison reads the load states of all four load state machines from memory.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestMask71Download extends BasicTestCase {

	/** The actuator 1.3.5 in both projects. */
	private static final String ACTUATOR = "P-0563-0_DI-3";

	private static final int CONTROL = 0x0104;

	private static final int STATES = 0xB6EA;

	/** The application ID of the test program: MDT (0083), application FF71, version 1. */
	private static final String APPLICATION_ID = "0083ff7101";

	private final List<TLObject> _projects = new ArrayList<>();

	public void testMemoryRecord() {
		// The four unloads (TSSG 2.1: 14, 24, 34, 44 into 0104).
		for (int lsm = 1; lsm <= 4; lsm++) {
			assertEquals(lsm + "400000000000000000000",
				hex(LoadControls.memoryRecord(lsm, LoadControls.event(LoadControls.EVENT_UNLOAD))));
		}
		// The records of the recordings of a mask 7.1 device cited in LoadControls.
		assertEquals("1300004000001df2038000",
			hex(LoadControls.memoryRecord(1, LoadControls.absoluteSegment(0, 0x4000, 0x1D, 0xF2, 3, 0x80))));
		assertEquals("1302004000000048140c14",
			hex(LoadControls.memoryRecord(1, LoadControls.taskSegment(0x4000, 0, 0x0048, 0x140C, 0x14))));
		assertEquals("33040046eb010000000000", hex(LoadControls.memoryRecord(3, LoadControls.taskControl1(0x46EB, 1))));
		assertEquals("2200000000000000000000",
			hex(LoadControls.memoryRecord(2, LoadControls.event(LoadControls.EVENT_LOAD_COMPLETED))));
		// The inverse.
		byte[] control = LoadControls.taskSegment(0x4201, 1, 0x0083, 0xFF71, 1);
		byte[] record = LoadControls.memoryRecord(2, control);
		assertEquals(2, LoadControls.memoryStateMachine(record));
		assertEquals(hex(control), hex(LoadControls.memoryControl(record)));
		try {
			LoadControls.memoryRecord(16, control);
			fail("No load state machine 16 in a nibble.");
		} catch (IllegalArgumentException ex) {
			// Expected.
		}
	}

	public void testDownload() throws Exception {
		TLObject project = importProject("src/test/fixtures/TestMask71.knxproj");
		TLObject reference = importProject("samples/KNX-Testsystem.knxproj");
		try {
			TLObject device = device(project, ACTUATOR);
			checkMask(DeviceConfiguration.program(device).getMask());
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			Logger.info("Download plan of the mask 7.1 actuator:\n" + plan.dump(), TestMask71Download.class);
			checkPlan(plan, DownloadPlanner.plan(device(reference, ACTUATOR), DownloadPlan.Kind.FULL));
			MockDevice mock = checkDownload(plan);
			checkCompare(device, plan, mock);
			checkStateNotFollowing(plan);
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (TLObject p : _projects) {
					p.tDelete();
				}
				tx.commit();
			}
		}
	}

	/**
	 * The load state machines of mask 7.1 from its resources: 1 to 4 in memory, none for 5.
	 */
	private static void checkMask(MaskVersion mask) {
		assertEquals(0x0701, mask.getNumber());
		assertFalse(LoadStateMachines.propertyMapped(mask));
		assertEquals(List.of(new LoadStateMachines.InMemory(1, CONTROL, 0xB6EA), new LoadStateMachines.InMemory(2, CONTROL, 0xB6EB),
			new LoadStateMachines.InMemory(3, CONTROL, 0xB6EC), new LoadStateMachines.InMemory(4, CONTROL, 0xB6ED)),
			LoadStateMachines.inMemory(mask));
		assertNull(LoadStateMachines.inMemory(mask, 5));
	}

	/**
	 * The plan of mask 7.1 is the plan of mask 7.5 with the load controls written into memory: the
	 * same steps, the same memory writes, the same load controls (but for the program's identity in
	 * the task segments).
	 */
	private static void checkPlan(DownloadPlan plan, DownloadPlan reference) {
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		assertEquals(reference.dump(), List.of(), reference.getProblems());
		assertTrue(plan.getOperations().stream().noneMatch(Operation.LoadControl.class::isInstance));

		List<String> records = new ArrayList<>();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.MemoryLoadControl control) {
				assertEquals(CONTROL, control.address());
				assertEquals(STATES + control.stateMachine() - 1, control.status());
				assertEquals(LoadControls.MEMORY_RECORD_LENGTH, control.bytesWritten());
				records.add(hex(control.record()) + " " + control.expectedState());
			}
		}
		assertEquals(List.of(
			"1400000000000000000000 0", "2400000000000000000000 0", "3400000000000000000000 0",
			"1100000000000000000000 2", "1300004000020 1ff038000 2", "13020040000100 83ff7101 2", "1200000000000000000000 1",
			"2100000000000000000000 2", "2300004201 01ffff038000 2", "23020042010100 83ff7101 2", "2200000000000000000000 1",
			"3100000000000000000000 2", "3300000700 0084000200 00 2", "330100078400 01000200 00 2",
			"3300004400 00ecff038000 2", "33000044ec 0130ff038000 2", "33020044000100 83ff7101 2",
			"3200000000000000000000 1").stream().map(s -> s.replace(" ", "")).map(s -> s.substring(0, 22) + " " + s.substring(22))
			.toList(), records);

		// The program's identity checked after the application is loaded.
		int completed = -1;
		for (int n = 0; n < plan.getOperations().size(); n++) {
			if (plan.getOperations().get(n) instanceof Operation.MemoryLoadControl control && control.stateMachine() == 3
				&& control.control()[0] == LoadControls.EVENT_LOAD_COMPLETED) {
				completed = n;
			}
		}
		Operation.CompareProperty identity = (Operation.CompareProperty) plan.getOperations().get(completed + 1);
		assertEquals(3, identity.objectIndex());
		assertEquals(13, identity.propertyId());
		assertEquals(APPLICATION_ID, hex(identity.expected()));
		// Load state machine 5 has no load control on mask 7.1: nothing to load there.
		assertFalse(plan.dump(), plan.dump().contains("LSM 5"));

		// Operation by operation the plan of mask 7.5.
		List<Operation> operations = plan.getOperations();
		List<Operation> expected = reference.getOperations();
		assertEquals(expected.size(), operations.size());
		for (int n = 0; n < operations.size(); n++) {
			Operation operation = operations.get(n);
			Operation other = expected.get(n);
			// The same step, numbered one more after the task segment of load state machine 5 the
			// program of mask 7.1 has in addition.
			assertEquals(number(other.step()), number(operation.step()));
			if (operation instanceof Operation.MemoryLoadControl control) {
				Operation.LoadControl property = (Operation.LoadControl) other;
				assertEquals(property.stateMachine(), control.stateMachine());
				assertEquals(property.expectedState(), control.expectedState());
				boolean task = control.control()[0] == LoadControls.EVENT_ADDITIONAL
					&& control.control()[1] == LoadControls.SEGMENT_TASK;
				int compared = task ? 5 : LoadControls.LENGTH;
				assertEquals(control.step(), hex(property.control()).substring(0, 2 * compared),
					hex(control.control()).substring(0, 2 * compared));
			} else if (operation instanceof Operation.WriteMemory write) {
				Operation.WriteMemory otherWrite = (Operation.WriteMemory) other;
				assertEquals(otherWrite.address(), write.address());
				assertEquals(hex(otherWrite.data()), hex(write.data()));
				assertEquals(otherWrite.verify(), write.verify());
			} else if (operation instanceof Operation.CompareProperty compare && compare.propertyId() == 13) {
				assertEquals(3, ((Operation.CompareProperty) other).objectIndex());
			} else if (!(operation instanceof Operation.CheckMask)) {
				assertEquals(other.describe(), operation.describe());
			}
		}
	}

	/**
	 * The download on a device of mask 7.1: every record written into 0104 and followed by the read of
	 * its load state; the image in the memory afterwards; the load state machines 1 to 3 loaded.
	 */
	private static MockDevice checkDownload(DownloadPlan plan) throws Exception {
		MockDevice mock = mask71Device();
		DownloadExecutor.Result result = new DownloadExecutor(ms -> {
			// No wait.
		}).execute(plan, mock, (number, count, operation) -> {
			// No progress.
		});
		assertTrue(result.failure(), result.success());
		List<String> calls = mock.calls();
		assertEquals(List.of("readDescriptor", "readProperty 0 78 1 1", "writeMemory 0104 1400000000000000000000",
			"readMemory B6EA 1", "writeMemory 0104 2400000000000000000000", "readMemory B6EB 1",
			"writeMemory 0104 3400000000000000000000", "readMemory B6EC 1"), calls.subList(0, 8));
		int records = 0;
		for (int n = 0; n < calls.size(); n++) {
			if (calls.get(n).startsWith("writeMemory 0104 ")) {
				records++;
				int lsm = Integer.parseInt(calls.get(n).substring(17, 18), 16);
				assertEquals(String.format("readMemory %04X 1", STATES + lsm - 1), calls.get(n + 1));
			}
		}
		assertEquals(18, records);
		assertEquals(List.of("readProperty 3 13 1 1", "restart", "disconnect"), calls.subList(calls.size() - 3, calls.size()));
		for (Block block : plan.getImage().getBlocks()) {
			for (int n = 0; n < block.data().length; n++) {
				if (block.mask()[n] != 0) {
					assertEquals(String.format("%04X", block.address() + n), block.data()[n],
						mock.memory()[block.address() + n]);
				}
			}
		}
		assertEquals(LoadControls.STATE_LOADED, mock.state(1));
		assertEquals(LoadControls.STATE_LOADED, mock.state(2));
		assertEquals(LoadControls.STATE_LOADED, mock.state(3));
		assertEquals(LoadControls.STATE_UNLOADED, mock.state(4));
		return mock;
	}

	/**
	 * The comparison of the loaded device: the load states of the four load state machines read from
	 * memory, the memory identical.
	 */
	private static void checkCompare(TLObject device, DownloadPlan plan, MockDevice mock) throws Exception {
		int before = mock.calls().size();
		DeviceComparison.Result result = DeviceComparison.compare(DeviceConfiguration.program(device), plan, mock,
			(number, count, block) -> {
				// No progress.
			});
		assertEquals(List.of("Mask version of the program: 0701 (7.1)", "Program version: 00 83 FF 71 01, the program's: 00 83 FF 71 01",
			"Load state machine 1: Loaded", "Load state machine 2: Loaded", "Load state machine 3: Loaded",
			"Load state machine 4: Unloaded"), result.facts());
		assertEquals(List.of("readMemory B6EA 1", "readMemory B6EB 1", "readMemory B6EC 1", "readMemory B6ED 1"),
			mock.calls().subList(before + 2, before + 6));
		assertEquals(result.blocks().size(), result.identicalBlocks());
	}

	/**
	 * A device whose load state does not follow the load control (still loaded after the unload):
	 * the download stops at the first load control after reading the state four times, nothing after
	 * the record is written.
	 */
	private static void checkStateNotFollowing(DownloadPlan plan) throws Exception {
		MockDevice mock = mask71Device().scriptLoadControl(
			(lsm, control) -> control[0] == LoadControls.EVENT_UNLOAD ? LoadControls.STATE_LOADED : null);
		List<Integer> sleeps = new ArrayList<>();
		DownloadExecutor.Result result = new DownloadExecutor(sleeps::add).execute(plan, mock, (number, count, operation) -> {
			// No progress.
		});
		assertFalse(result.success());
		Operation.MemoryLoadControl failed = (Operation.MemoryLoadControl) result.failed();
		assertEquals(1, failed.stateMachine());
		assertEquals(plan.getOperations().indexOf(failed), result.done());
		assertEquals("Load state machine 1 reports the state \"Loaded\" after the load control \"Unload\", expected \"Unloaded\".",
			result.failure());
		assertEquals(List.of("readDescriptor", "readProperty 0 78 1 1", "writeMemory 0104 1400000000000000000000",
			"readMemory B6EA 1", "readMemory B6EA 1", "readMemory B6EA 1", "readMemory B6EA 1"), mock.calls());
		assertEquals(List.of(250, 250, 250), sleeps);
		assertEquals(1, mock.writes());
	}

	/**
	 * A device of mask 7.1 with the hardware type the program compares and the program version the
	 * task segment of the application sets.
	 */
	private static MockDevice mask71Device() {
		return new MockDevice().setDescriptor("0701").loadControlInMemory(CONTROL, STATES)
			.setProperty(0, 78, "00000000030d").setProperty(3, 13, APPLICATION_ID);
	}

	private TLObject importProject(String file) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(file);
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		_projects.add(projects.get(0));
		return projects.get(0);
	}

	private static TLObject device(TLObject project, String extId) {
		List<TLObject> result = instances("ehome.physical:Device").stream()
			.filter(d -> extId.equals(d.tValueByName("extId")))
			.filter(d -> root(d) == project)
			.toList();
		assertEquals(extId, 1, result.size());
		return result.get(0);
	}

	private static TLObject root(TLObject obj) {
		TLObject result = obj;
		while (result.tContainer() != null) {
			result = result.tContainer();
		}
		return result;
	}

	private static String number(String step) {
		return step.replaceFirst("^#\\d+ ", "");
	}

	private static String hex(byte[] data) {
		return HexFormat.of().formatHex(data);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestMask71Download.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
