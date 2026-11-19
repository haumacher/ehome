/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.read;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.scan.ReadBack;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceOperations;

/**
 * E-Home's download into the virtual device in its BCU 2 mode (haumacher/ehome #191), over KNXnet/IP
 * tunnelling on the loopback address: the three devices of the app's fixture
 * <code>TestBcu2.knxproj</code> (masks 2.0, 2.1, 2.5; see <code>make-fixtures.py</code>).
 *
 * <p>
 * For each: the trace recorded by the virtual device is the download plan operation by operation
 * (with the authorization of BCU 2); afterwards the device's memory holds what the plan wrote last,
 * its load states are those the plan leaves, its table references and program version are those of
 * the task segments, and it took the task pointer and task control 2 of the plan. The read-out of
 * the device (haumacher/ehome #193) compares identical offline. The device's configuration taken over
 * from the bus into the same device emptied before (no settings, no connections) gives the
 * connections and parameter values it was downloaded with, and an image identical to the memory
 * read (the BCU 2 association table, with its place per object, and the object table decoded).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVirtualBcu2 extends BasicTestCase {

	public void testBcu2() throws Exception {
		TLObject project = TestVirtualDownload.importProject("../ehome/src/test/fixtures/TestBcu2.knxproj");
		try {
			check(TestVirtualDownload.device(project, 0x1001), 0x0020, "bcu2-20");
			check(TestVirtualDownload.device(project, 0x1002), 0x0021, "bcu2-21");
			check(TestVirtualDownload.device(project, 0x1003), 0x0025, "bcu2-25");
		} finally {
			TestVirtualDownload.delete(project);
		}
	}

	private static void check(TLObject device, int mask, String name) throws Exception {
		Program program = DeviceConfiguration.program(device);
		assertEquals(mask, program.getMask().getNumber());
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
		assertTrue(simulated.bcu2());
		assertEquals(SimulatedDevice.LoadControlKind.PROPERTIES, simulated.kind());
		assertNull("A BCU 2 has no object 4.", simulated.property(4, SimulatedDevice.PID_OBJECT_TYPE, 1, 1));

		Endpoint endpoint = loopback();
		VirtualInterfaceManager manager = manager("download-" + name);
		VirtualInterface running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("plan", name));
		File file = running.recorder().file();
		try {
			writing(() -> {
				try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), plan.getIndividualAddress())) {
					DownloadExecutor.Result result = new DownloadExecutor(ms -> {
						// The delays of the procedure need no real time here.
					}).execute(plan, writer, (number, count, operation) -> {
						// No progress.
					});
					assertTrue(name + ": " + result.failure(), result.success());
				}
			});
		} finally {
			manager.stop();
		}

		// The trace is the plan.
		List<Operation> recorded = TraceOperations.operations(read(file).entries());
		assertEquals(name + ": the trace differs from the plan\n" + plan.dump(), List.of(),
			TraceOperations.compare(plan.getOperations(), recorded));
		assertTrue(recorded.get(2) instanceof Operation.Authorize);

		// The memory as the plan wrote it last; the load states, table references and program version
		// as the plan's load controls leave them.
		Map<Integer, Integer> lastWritten = new TreeMap<>();
		Map<Integer, Integer> states = new TreeMap<>();
		Map<Integer, String> references = new TreeMap<>();
		Map<String, String> additional = new TreeMap<>();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.WriteMemory write) {
				for (int n = 0; n < write.data().length; n++) {
					lastWritten.put(write.address() + n, write.data()[n] & 0xFF);
				}
			} else if (operation instanceof Operation.LoadControl control) {
				states.put(control.objectIndex(), control.expectedState());
				byte[] bytes = control.control();
				if (bytes[0] == LoadControls.EVENT_ADDITIONAL) {
					additional.put(control.objectIndex() + "/" + bytes[1], HexFormat.of().formatHex(bytes));
					if (bytes[1] == LoadControls.SEGMENT_TASK) {
						references.put(control.objectIndex(), HexFormat.of().formatHex(bytes, 2, 4));
					}
				}
			}
		}
		byte[] memory = simulated.memorySnapshot();
		for (Map.Entry<Integer, Integer> written : lastWritten.entrySet()) {
			assertEquals(String.format("%s: memory %04X", name, written.getKey()), written.getValue().intValue(),
				memory[written.getKey()] & 0xFF);
		}
		for (Map.Entry<Integer, Integer> state : states.entrySet()) {
			assertEquals(name + ": load state " + state.getKey(), state.getValue().intValue(), simulated.state(state.getKey()));
		}
		for (Map.Entry<Integer, String> reference : references.entrySet()) {
			assertEquals(name + ": table reference " + reference.getKey(), reference.getValue(),
				HexFormat.of().formatHex(simulated.property(reference.getKey(), LoadControls.PID_TABLE_REFERENCE, 1, 1)));
		}
		assertEquals(HexFormat.of().formatHex(DownloadPlanner.applicationId(program)),
			HexFormat.of().formatHex(simulated.property(3, LoadControls.PID_PROGRAM_VERSION, 1, 1)));
		assertTrue(name + ": " + additional.keySet(), additional.containsKey("3/" + LoadControls.SEGMENT_TASK_POINTER));
		assertTrue(name + ": " + additional.keySet(), additional.containsKey("3/" + LoadControls.SEGMENT_TASK_CONTROL_2));
		for (Map.Entry<String, String> control : additional.entrySet()) {
			String[] key = control.getKey().split("/");
			assertEquals(name + ": additional load control " + control.getKey(), control.getValue(), HexFormat.of()
				.formatHex(simulated.additionalControl(Integer.parseInt(key[0]), Integer.parseInt(key[1]))));
		}

		// The read-out compares identical offline.
		manager.start(simulated, endpoint, Map.of("readout", name));
		Snapshot snapshot;
		try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
			snapshot = ReadOut.read(device, reader, (number, count, block) -> {
				// No progress.
			});
		} finally {
			manager.stop();
		}
		assertTrue(snapshot.isComplete());
		assertEquals(String.format("%04X", mask), snapshot.getDescriptor());
		DeviceComparison.Result comparison = DeviceComparison.compare(program, plan, snapshot);
		assertFalse(comparison.blocks().isEmpty());
		assertEquals(name + ": " + comparison.blocks(), comparison.blocks().size(), comparison.identicalBlocks());
		for (Snapshot.LoadState state : snapshot.getLoadStates()) {
			assertEquals(name + ": " + state, Integer.valueOf(simulated.state(state.stateMachine())), state.state());
		}

		// The take-over from the bus into the device emptied before gives what was downloaded.
		TreeSet<String> connections = connections(device);
		Map<String, String> values = values(device);
		assertFalse(connections.isEmpty());
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			DeviceSettings.removeSettings(device, new ArrayList<>(settings(device)));
			for (TLObject connector : new DeviceConfiguration(device).getConnectors()) {
				for (Object connection : new ArrayList<>((Collection<?>) connector.tValueByName("connections"))) {
					DeviceSettings.disconnect((TLObject) connection);
				}
			}
			tx.commit();
		}
		assertTrue(connections(device).isEmpty());
		assertFalse(values.equals(values(device)));
		manager.start(simulated, endpoint, Map.of("readback", name));
		ReadBack.Memory read;
		try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
			read = ReadBack.read(device, reader, (done, total, what) -> {
				// No progress.
			});
		} finally {
			manager.stop();
		}
		ReadBack.Report report = ReadBack.takeOver(device, read);
		assertTrue(name + ": " + report.problems() + " " + report.differences(), report.identical());
		assertEquals(name, kinds(connections), connections(device));
		assertEquals(name, values, values(device));
	}

	/**
	 * The connections as a device reads them back: an object's send address, or the first of its
	 * receive addresses when it sends to none (BCU 2 keeps the address an object sends to in its
	 * place of the association table, see {@link de.haumacher.ehome.programming.GroupTables}).
	 */
	private static TreeSet<String> kinds(TreeSet<String> connections) {
		TreeSet<String> result = new TreeSet<>();
		for (String connection : connections) {
			String object = connection.substring(0, connection.indexOf(' '));
			boolean sends = connections.stream().anyMatch(c -> c.startsWith(object + " ") && c.endsWith(" send"));
			if (!sends && connection.endsWith(" receive")
				&& connections.stream().filter(c -> c.startsWith(object + " ")).findFirst().get().equals(connection)) {
				result.add(connection.replace(" receive", " send"));
			} else {
				result.add(connection);
			}
		}
		return result;
	}

	private static Collection<TLObject> settings(TLObject device) {
		List<TLObject> result = new ArrayList<>();
		for (Object setting : (Collection<?>) device.tValueByName("settings")) {
			result.add((TLObject) setting);
		}
		return result;
	}

	private static TreeSet<String> connections(TLObject device) {
		TreeSet<String> result = new TreeSet<>();
		for (TLObject connector : new DeviceConfiguration(device).getConnectors()) {
			Interface placed = (Interface) connector.tValueByName("port");
			for (Object element : (Collection<?>) connector.tValueByName("connections")) {
				TLObject connection = (TLObject) element;
				GA ga = ((GroupAddress) connection.tValueByName("target")).getAddress();
				result.add(placed.getPort().getIndex() + " " + ga.getValue() + " "
					+ ((TLClassifier) connection.tValueByName("kind")).getName());
			}
		}
		return result;
	}

	private static Map<String, String> values(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Map<String, String> result = new TreeMap<>();
		for (ParameterView view : configuration.getActiveViews().values()) {
			result.put(view.getParameter().getExtId(), ParameterValues.encode(configuration.valueHolder(view)));
		}
		return result;
	}


	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualBcu2.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
