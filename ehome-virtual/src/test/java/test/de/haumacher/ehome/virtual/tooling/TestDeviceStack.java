/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.link.NetworkLinkListener;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.server.VirtualLink;

import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice.LoadControlKind;
import de.haumacher.ehome.virtual.tooling.server.DeviceStack;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;
import de.haumacher.ehome.virtual.tooling.trace.TraceRecorder;

/**
 * The transport layer of the virtual device on a virtual subnet without any KNXnet/IP: a tool that
 * acknowledges twice, repeats a service, does not acknowledge, stays idle, or a second tool
 * connecting. The device keeps the connection where a duplicate does no harm and records every
 * timer and every deviation as an event.
 */
@SuppressWarnings("javadoc")
public class TestDeviceStack extends TestCase {

	private static final HexFormat HEX = HexFormat.of();

	/** 1.1.20. */
	private static final int DEVICE = 0x1114;

	/** 1.1.255. */
	private static final int TOOL = 0x11FF;

	/** 1.1.254. */
	private static final int OTHER = 0x11FE;

	/** A property read of the object type of object 0, connected. */
	private static final String READ_TYPE = "03D5" + "00011001";

	private VirtualLink _subnet;

	private KNXNetworkLink _deviceLink;

	private DeviceStack _stack;

	private TraceRecorder _recorder;

	private final BlockingQueue<CEMILData> _sent = new LinkedBlockingQueue<>();

	private void start(TransportTiming timing) throws Exception {
		_subnet = new VirtualLink("test-subnet", new TPSettings());
		_deviceLink = _subnet.createDeviceLink(new IndividualAddress(DEVICE));
		_subnet.addLinkListener(new NetworkLinkListener() {
			@Override
			public void indication(FrameEvent e) {
				if (e.getFrame() instanceof CEMILData frame && frame.getSource().getRawAddress() == DEVICE) {
					_sent.add(frame);
				}
			}

			@Override
			public void linkClosed(CloseEvent e) {
				// Nothing to do.
			}
		});
		SimulatedDevice device =
			new SimulatedDevice("1.1.20 (test)", DEVICE, 0x0705, LoadControlKind.PROPERTIES, MappedLoadStateRule.ECHO);
		_recorder = new TraceRecorder(null, Map.of());
		_stack = new DeviceStack(device, _deviceLink, _recorder, timing);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_stack != null) {
			_stack.close();
		}
		if (_deviceLink != null) {
			_deviceLink.close();
		}
		if (_subnet != null) {
			_subnet.close();
		}
		super.tearDown();
	}

	public void testDuplicateAckAndRepetition() throws Exception {
		start(TransportTiming.DEFAULT);
		toDevice(TOOL, "80");
		toDevice(TOOL, data(0, READ_TYPE));
		assertEquals("c2", next());
		assertEquals(data(0, "03D6" + "000110010000"), next());
		toDevice(TOOL, "c2");
		// The tool received the answer twice and acknowledges again: ignored, recorded.
		toDevice(TOOL, "c2");
		// The tool repeats the next service (no acknowledgement in time): acknowledged twice,
		// answered once.
		toDevice(TOOL, data(1, READ_TYPE));
		toDevice(TOOL, data(1, READ_TYPE));
		assertEquals("c6", next());
		assertEquals(data(1, "03D6" + "000110010000"), next());
		assertEquals("c6", next());
		toDevice(TOOL, "c6");
		assertNull(_sent.poll(300, TimeUnit.MILLISECONDS));
		assertEquals(List.of(TraceEntry.TL_STALE_ACK), events());
		// The connection is still open.
		toDevice(TOOL, data(2, READ_TYPE));
		assertEquals("ca", next());
	}

	public void testAckTimeout() throws Exception {
		start(new TransportTiming(Duration.ofMillis(100), 2, Duration.ofSeconds(60), false));
		toDevice(TOOL, "80");
		toDevice(TOOL, data(0, READ_TYPE));
		assertEquals("c2", next());
		String answer = data(0, "03D6" + "000110010000");
		assertEquals(answer, next());
		assertEquals(answer, next());
		assertEquals(answer, next());
		assertEquals("81", next());
		assertEquals(List.of(TraceEntry.TL_ACK_TIMEOUT, TraceEntry.TL_ACK_TIMEOUT, TraceEntry.TL_REPETITIONS_EXHAUSTED),
			events());
		assertEquals(List.of("1", "2"), _recorder.entries().stream()
			.filter(e -> TraceEntry.TL_ACK_TIMEOUT.equals(e.service())).map(e -> e.field(TraceEntry.REPEAT)).toList());
	}

	public void testConnectionTimeoutAndRejection() throws Exception {
		start(new TransportTiming(Duration.ofSeconds(3), 3, Duration.ofMillis(300), false));
		toDevice(TOOL, "80");
		// A second tool is refused while the first is connected.
		toDevice(OTHER, "80");
		CEMILData refusal = _sent.poll(5, TimeUnit.SECONDS);
		assertEquals(OTHER, refusal.getDestination().getRawAddress());
		assertEquals("81", HEX.formatHex(refusal.getPayload()));
		// The first one stays idle.
		CEMILData timeout = _sent.poll(5, TimeUnit.SECONDS);
		assertEquals(TOOL, timeout.getDestination().getRawAddress());
		assertEquals("81", HEX.formatHex(timeout.getPayload()));
		assertEquals(List.of(TraceEntry.TL_REJECTED, TraceEntry.TL_CONNECTION_TIMEOUT), events());
	}

	public void testDisconnectOnRestart() throws Exception {
		start(TransportTiming.DEFAULT.withDisconnectOnRestart(true));
		toDevice(TOOL, "80");
		toDevice(TOOL, data(0, "0380"));
		assertEquals("c2", next());
		assertEquals("81", next());
		assertEquals(List.of(), events());
	}

	private List<String> events() {
		return _recorder.entries().stream().filter(e -> e.direction() == TraceEntry.Direction.EVENT)
			.map(TraceEntry::service).toList();
	}

	/**
	 * A data service in a connection: the APDU with the sequence number in the first byte.
	 */
	private static String data(int sequence, String apdu) {
		byte[] tpdu = HEX.parseHex(apdu);
		tpdu[0] = (byte) (tpdu[0] & 0x03 | 0x40 | sequence << 2);
		return HEX.formatHex(tpdu);
	}

	private void toDevice(int source, String tpdu) throws Exception {
		_subnet.send(new CEMILData(CEMILData.MC_LDATA_REQ, new IndividualAddress(source), new IndividualAddress(DEVICE),
			HEX.parseHex(tpdu), Priority.LOW), false);
	}

	private String next() throws InterruptedException {
		CEMILData frame = _sent.poll(5, TimeUnit.SECONDS);
		assertNotNull("The device sent nothing.", frame);
		return HEX.formatHex(frame.getPayload());
	}

	public static Test suite() {
		return new TestSuite(TestDeviceStack.class);
	}

}
