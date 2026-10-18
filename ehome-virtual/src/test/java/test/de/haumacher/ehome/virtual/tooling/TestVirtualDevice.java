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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import test.de.haumacher.ehome.programming.MockDevice;

import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice.LoadControlKind;
import de.haumacher.ehome.virtual.tooling.I18NConstants;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;
import de.haumacher.ehome.virtual.tooling.trace.TraceFormat;
import de.haumacher.ehome.virtual.tooling.trace.TraceOperations;
import de.haumacher.ehome.virtual.tooling.trace.TraceRecorder;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;

/**
 * The virtual device without project data (haumacher/ehome #27): its load state machines against
 * the test double of the download tests, the trace format read back, one virtual interface at a
 * time, the memory-mapped load control of mask 0701, and a property the device does not know.
 * Everything on the loopback address.
 */
@SuppressWarnings("javadoc")
public class TestVirtualDevice extends TestCase {

	private static final HexFormat HEX = HexFormat.of();

	/** 1.1.20. */
	private static final int ADDRESS = 0x1114;

	/**
	 * The property-mapped load state machines answer every load event in every state as
	 * {@link MockDevice} does.
	 */
	public void testLoadStatesLikeMockDevice() {
		int[] events = { LoadControls.EVENT_LOAD, LoadControls.EVENT_LOAD_COMPLETED, LoadControls.EVENT_ADDITIONAL,
			LoadControls.EVENT_UNLOAD, 0, 7 };
		for (int state = 0; state <= LoadControls.STATE_LOAD_COMPLETING; state++) {
			for (int event : events) {
				byte[] control = LoadControls.event(event);
				MockDevice mock = new MockDevice().setState(1, state);
				Integer expected = mock.writeLoadControl(1, control);

				SimulatedDevice device = device(0x0705, LoadControlKind.PROPERTIES);
				device.initState(1, state);
				byte[] answer = device.putProperty(1, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1, control);
				assertEquals("state " + state + ", event " + event, expected.intValue(), answer[0] & 0xFF);
				assertEquals(expected.intValue(), device.property(1, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1)[0]);
			}
		}
	}

	private static void control(SimulatedDevice device, int event) {
		device.putProperty(1, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1, LoadControls.event(event));
	}

	/**
	 * The task segment sets the table reference and the program version of its object.
	 */
	public void testTaskSegment() {
		SimulatedDevice device = device(0x0705, LoadControlKind.PROPERTIES);
		control(device, LoadControls.EVENT_LOAD);
		device.putProperty(3, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1, LoadControls.event(LoadControls.EVENT_LOAD));
		device.putProperty(3, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1,
			LoadControls.taskSegment(0x4400, 0, 0x0083, 0x000C, 0x21));
		assertEquals("00004400", HEX.formatHex(device.property(3, LoadControls.PID_TABLE_REFERENCE, 1, 1)));
		assertEquals("0083000c21", HEX.formatHex(device.property(3, LoadControls.PID_PROGRAM_VERSION, 1, 1)));
	}

	/**
	 * Write a trace, read it back: the same services and the same operations.
	 */
	public void testTraceRoundTrip() throws Exception {
		File file = new File(VirtualTesting.OUTPUT, "roundtrip/trace-test.txt");
		file.getParentFile().mkdirs();
		Instant time = Instant.parse("2026-10-17T13:30:01.123456Z");
		int tool = 0xFFFF;
		List<TraceEntry> entries = new ArrayList<>();
		entries.add(entry(time, true, tool, ADDRESS, TraceEntry.T_CONNECT, null, Map.of(), ""));
		entries.add(entry(time.plusMillis(1), true, tool, ADDRESS, TraceEntry.DESCRIPTOR_READ, 0, Map.of("type", "0"), ""));
		entries.add(entry(time.plusMillis(2), false, ADDRESS, tool, TraceEntry.DESCRIPTOR_RESPONSE, 0, Map.of("type", "0"), "0705"));
		entries.add(entry(time.plusMillis(3), true, tool, ADDRESS, TraceEntry.PROPERTY_WRITE, 1,
			fields("object", "1", "property", "5", "start", "1", "count", "1"), "01000000000000000000"));
		entries.add(entry(time.plusMillis(4), false, ADDRESS, tool, TraceEntry.PROPERTY_RESPONSE, 1,
			fields("object", "1", "property", "5", "start", "1", "count", "1"), "02"));
		entries.add(entry(time.plusMillis(5), true, tool, ADDRESS, TraceEntry.MEMORY_WRITE, 2,
			fields("address", "4000", "count", "2"), "0102"));
		entries.add(entry(time.plusMillis(6), true, tool, ADDRESS, TraceEntry.MEMORY_READ, 3,
			fields("address", "4000", "count", "2"), ""));
		entries.add(entry(time.plusMillis(7), false, ADDRESS, tool, TraceEntry.MEMORY_RESPONSE, 2,
			fields("address", "4000", "count", "2"), "0102"));
		entries.add(entry(time.plusMillis(8), true, tool, ADDRESS, TraceEntry.PROPERTY_READ, 4,
			fields("object", "0", "property", "11", "start", "1", "count", "1"), ""));
		entries.add(entry(time.plusMillis(9), false, ADDRESS, tool, TraceEntry.PROPERTY_RESPONSE, 3,
			fields("object", "0", "property", "11", "start", "1", "count", "0"), ""));
		entries.add(entry(time.plusMillis(10), true, tool, ADDRESS, TraceEntry.RESTART, 5, Map.of(), ""));
		entries.add(entry(time.plusMillis(10), true, tool, ADDRESS, TraceEntry.T_DISCONNECT, null, Map.of(), ""));
		entries.add(entry(time.plusMillis(10), true, tool, ADDRESS, TraceEntry.T_CONNECT, null, Map.of(), ""));
		entries.add(entry(time.plusMillis(11), true, tool, 0, TraceEntry.INDIVIDUAL_ADDRESS_WRITE, null,
			fields("individual", "1.1.20"), ""));
		entries.add(entry(time.plusMillis(12), true, tool, ADDRESS, TraceEntry.OTHER, 6, fields("apci", "3D1"), "0A0B"));
		entries.add(entry(time.plusMillis(13), true, tool, ADDRESS, TraceEntry.T_DISCONNECT, null, Map.of(), ""));

		Map<String, String> header = new LinkedHashMap<>();
		header.put("device", "1.1.20 (test)");
		header.put("mask", "0705");
		try (TraceRecorder recorder = new TraceRecorder(file, header)) {
			for (TraceEntry entry : entries) {
				recorder.record(entry);
			}
		}
		TraceFormat.Trace trace = read(file);
		assertEquals(header, trace.header());
		assertEquals(entries, trace.entries());

		List<Operation> operations = TraceOperations.operations(trace.entries());
		assertEquals(List.of(
			"Connect",
			"CheckMask 0705",
			"LoadControl 1 01000000000000000000 -> 2",
			"WriteMemory 4000 0102 verified",
			"CompareProperty 0/11 1+1 ",
			"Restart",
			"Connect",
			"WriteIndividualAddress 1.1.20",
			"Skip the service A_Other {apci=3D1}",
			"Disconnect"), operations.stream().map(TraceOperations::signature).toList());
		assertEquals(TraceOperations.operations(entries).stream().map(TraceOperations::signature).toList(),
			operations.stream().map(TraceOperations::signature).toList());
	}

	/**
	 * A second virtual interface is refused while one runs; stopping frees the port, so that the
	 * same port can be used again.
	 */
	public void testOneAtATime() throws Exception {
		VirtualInterfaceManager manager = manager("one-at-a-time");
		Endpoint endpoint = loopback();
		VirtualInterface first = manager.start(device(0x0705, LoadControlKind.PROPERTIES), endpoint, Map.of());
		try {
			assertTrue(first.isRunning());
			assertFalse(VirtualInterface.portFree(endpoint));
			try {
				manager.start(device(0x0705, LoadControlKind.PROPERTIES), loopback(), Map.of());
				fail("A second virtual interface must be refused.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_ALREADY_RUNNING__NAME_ENDPOINT.fill(first.name(), endpoint.toString()),
					ex.getErrorKey());
			}
			assertSame(first, manager.running());
		} finally {
			manager.stop();
		}
		assertNull(manager.running());
		assertTrue(VirtualInterface.portFree(endpoint));

		VirtualInterface again = manager.start(device(0x0705, LoadControlKind.PROPERTIES), endpoint, Map.of());
		try {
			assertTrue(again.isRunning());
			try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), ADDRESS)) {
				assertEquals("0705", HEX.formatHex(reader.readDescriptor()));
			}
		} finally {
			manager.stop();
		}
		assertTrue(VirtualInterface.portFree(endpoint));
	}

	/**
	 * A port held by another program refuses the start with the port named.
	 */
	public void testPortInUse() throws Exception {
		Endpoint endpoint = loopback();
		try (java.net.DatagramSocket blocker = new java.net.DatagramSocket(
			new java.net.InetSocketAddress(endpoint.address(), endpoint.port()))) {
			VirtualInterfaceManager manager = manager("port-in-use");
			try {
				manager.start(device(0x0705, LoadControlKind.PROPERTIES), endpoint, Map.of());
				manager.stop();
				fail("The start must be refused.");
			} catch (TopLogicException ex) {
				assertEquals(de.haumacher.ehome.virtual.I18NConstants.ERROR_PORT_IN_USE__ENDPOINT.fill(endpoint.toString()), ex.getErrorKey());
			}
			assertNull(manager.running());
		}
	}

	/**
	 * Mask 0701: the load controls written to 0104 are recorded, the load states at B6EA answer the
	 * state the control implies (the default rule) or the configured one.
	 */
	public void testMemoryMappedLoadControl() throws Exception {
		check0701(MappedLoadStateRule.ECHO, "00020000", "00010000");
		check0701(MappedLoadStateRule.LOADED, "01010101", "01010101");
	}

	private void check0701(MappedLoadStateRule rule, String afterLoad, String afterCompleted) throws Exception {
		VirtualInterfaceManager manager = manager("mask-0701-" + rule);
		Endpoint endpoint = loopback();
		SimulatedDevice device = new SimulatedDevice("1.1.20 (mask 0701)", ADDRESS, 0x0701, LoadControlKind.MEMORY, rule);
		VirtualInterface running = manager.start(device, endpoint, Map.of());
		File file = running.recorder().file();
		try {
			writing(() -> {
				try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), ADDRESS)) {
					assertEquals("0701", HEX.formatHex(writer.readDescriptor()));
					// Load state machine 2 (association table): start loading.
					writer.writeMemory(SimulatedDevice.MANAGEMENT_CONTROL, new byte[] { 0x21 });
					assertEquals(afterLoad, HEX.formatHex(writer.readMemory(SimulatedDevice.LOAD_STATES, 4)));
					// Load completed.
					writer.writeMemory(SimulatedDevice.MANAGEMENT_CONTROL, new byte[] { 0x22 });
					assertEquals(afterCompleted, HEX.formatHex(writer.readMemory(SimulatedDevice.LOAD_STATES, 4)));
					writer.disconnect();
				}
			});
			// The tool's disconnect travels through the server asynchronously.
			VirtualTesting.awaitService(running, TraceEntry.T_DISCONNECT);
		} finally {
			manager.stop();
		}
		List<String> signatures =
			TraceOperations.operations(read(file).entries()).stream().map(TraceOperations::signature).toList();
		assertEquals(List.of("Connect", "CheckMask 0701", "WriteMemory 0104 21", "CompareMemory B6EA " + afterLoad.toUpperCase(),
			"WriteMemory 0104 22", "CompareMemory B6EA " + afterCompleted.toUpperCase(), "Disconnect"), signatures);
	}

	/**
	 * A property the device does not know (the serial number) is answered as not present, and the
	 * read shows in the trace and in the device's list of unknown reads.
	 */
	public void testUnknownProperty() throws Exception {
		VirtualInterfaceManager manager = manager("unknown-property");
		Endpoint endpoint = loopback();
		SimulatedDevice device = device(0x0705, LoadControlKind.PROPERTIES);
		VirtualInterface running = manager.start(device, endpoint, Map.of());
		File file = running.recorder().file();
		try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), ADDRESS)) {
			try {
				reader.readProperty(0, 11, 1, 1);
				fail("The serial number is not known.");
			} catch (BusException ex) {
				// Expected: a negative response.
			}
			// The object type of object 4 (PEI program).
			assertEquals("0004", HEX.formatHex(reader.readProperty(4, 1, 1, 1)));
		} finally {
			manager.stop();
		}
		assertEquals(List.of("0/11"), device.unknownReads());
		List<TraceEntry> entries = read(file).entries();
		TraceEntry response = entries.stream()
			.filter(e -> e.outgoing() && TraceEntry.PROPERTY_RESPONSE.equals(e.service())
				&& "11".equals(e.field(TraceEntry.PROPERTY)))
			.findFirst().orElseThrow();
		assertEquals("0", response.field(TraceEntry.COUNT));
		assertEquals(0, response.data().length);
	}

	private static SimulatedDevice device(int mask, LoadControlKind kind) {
		return new SimulatedDevice("1.1.20 (test)", ADDRESS, mask, kind, MappedLoadStateRule.ECHO);
	}

	private static TraceEntry entry(Instant time, boolean incoming, int source, int destination, String service,
			Integer sequence, Map<String, String> fields, String data) {
		return new TraceEntry(time, incoming ? TraceEntry.Direction.RECEIVED : TraceEntry.Direction.SENT, source, destination,
			service, sequence, fields, HEX.parseHex(data));
	}

	private static Map<String, String> fields(String... nameValues) {
		Map<String, String> result = new LinkedHashMap<>();
		for (int n = 0; n < nameValues.length; n += 2) {
			result.put(nameValues[n], nameValues[n + 1]);
		}
		return result;
	}

	public static Test suite() {
		return new TestSuite(TestVirtualDevice.class);
	}

}
