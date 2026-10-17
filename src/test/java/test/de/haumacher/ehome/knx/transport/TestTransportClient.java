/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx.transport;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.KNXDisconnectException;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;

import de.haumacher.ehome.knx.transport.Tpdu;
import de.haumacher.ehome.knx.transport.TransportClient;
import de.haumacher.ehome.knx.transport.TransportClient.TransportException;

/**
 * E-Home's client side of the KNX transport layer (haumacher/ehome #31, #30) against a scripted
 * device on a link in memory, each timing injected by the script, compared with Calimero's
 * <code>ManagementClientImpl</code> where Calimero 2.6 fails:
 *
 * <ul>
 * <li>The device's answer arriving right after its acknowledgement, in the window in which
 * Calimero's <code>TransportLayerImpl</code> has stopped reading its queue: Calimero misses it, the
 * client of E-Home gets it.</li>
 * <li>An acknowledgement arriving a second time: Calimero ends the connection, the client of E-Home
 * ignores it.</li>
 * <li>A repeated answer, a negative acknowledgement, a missing acknowledgement, a missing answer, and
 * the end of the connection after a restart: the device silent, the device disconnecting first.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestTransportClient extends TestCase {

	private static final HexFormat HEX = HexFormat.of();

	/** The device, 1.1.20. */
	private static final int DEVICE = 0x1114;

	/** The client, 1.1.255. */
	private static final int CLIENT = 0x11FF;

	/** The device descriptor read and its answer (mask 0705). */
	private static final byte[] DESCRIPTOR_READ = DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.DESCRIPTOR_READ, (byte) 0);

	private static final byte[] DESCRIPTOR_ANSWER = DataUnitBuilder.createAPDU(Tpdu.DESCRIPTOR_RESPONSE, (byte) 0x07,
		(byte) 0x05);

	private static final TransportClient.Timing FAST =
		new TransportClient.Timing(Duration.ofMillis(200), 2, Duration.ofMillis(300), Duration.ofSeconds(6));

	private TestLink _link;

	/** What the client sent, as hex. */
	private final List<String> _sent = Collections.synchronizedList(new ArrayList<>());

	/** The next sequence number of the device. */
	private int _deviceSequence;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_link = new TestLink(CLIENT);
	}

	@Override
	protected void tearDown() throws Exception {
		_link.close();
		super.tearDown();
	}

	/**
	 * The device: for each data unit of the client the replies of the script, delivered at once on
	 * the client's sending thread.
	 */
	private void device(Function<byte[], List<byte[]>> script) {
		_link.device(tpdu -> {
			_sent.add(HEX.formatHex(tpdu));
			if (Tpdu.isConnectedData(tpdu)) {
				for (byte[] reply : script.apply(tpdu)) {
					_link.deliver(DEVICE, reply);
				}
			}
		});
	}

	private static byte[] ack(byte[] tpdu) {
		return Tpdu.acknowledgement(Tpdu.ACK, Tpdu.sequence(tpdu));
	}

	private byte[] answer(byte[] apdu) {
		return Tpdu.connected(apdu, _deviceSequence++);
	}

	/**
	 * The answer arrives right after the acknowledgement, after Calimero's transport layer has read
	 * the acknowledgement from its queue: it stays in that queue, and Calimero's management client
	 * waits for it in vain.
	 *
	 * <p>
	 * The frame is placed into the queue as the listener of Calimero's transport layer does for a
	 * frame arriving while it awaits an acknowledgement, once the send has returned. Without this, the
	 * window (between the last read of the queue and the end of the send, a few instructions without
	 * any hook) could only be hit by load. When this test fails, Calimero handles frames arriving in that window: then the
	 * client of E-Home is no longer needed for that reason.
	 * </p>
	 */
	public void testAnswerAfterAckCalimero() throws Exception {
		ManagementClientImpl management = new ManagementClientImpl(_link);
		management.responseTimeout(Duration.ofMillis(500));
		Destination destination = management.createDestination(new IndividualAddress(DEVICE), true);
		Deque<FrameEvent> queue = field(field(management, "tl"), "indications");
		device(tpdu -> {
			byte[] answer = answer(DESCRIPTOR_ANSWER);
			Thread client = Thread.currentThread();
			Thread late = new Thread(() -> {
				// When the send has returned and the management client waits for the answer, the answer
				// is in the queue, as if it had arrived while the acknowledgement was being read.
				long deadline = System.currentTimeMillis() + 2000;
				while (!waitsForAnswer(client) && System.currentTimeMillis() < deadline) {
					Thread.onSpinWait();
				}
				queue.add(new FrameEvent(_link, new CEMILData(CEMILData.MC_LDATA_IND, new IndividualAddress(DEVICE),
					new IndividualAddress(CLIENT), answer, Priority.LOW)));
			});
			late.start();
			return List.of(ack(tpdu));
		});
		try {
			management.readDeviceDesc(destination, 0);
			fail("Calimero's transport layer delivered the answer arriving after the acknowledgement.");
		} catch (KNXTimeoutException ex) {
			// The defect of Calimero 2.6 (haumacher/ehome #31).
		} finally {
			management.detach();
		}
		assertEquals("The answer is left in the queue.", 1, queue.size());
	}

	/**
	 * The same timing for the client of E-Home: the answer arrives right after the acknowledgement,
	 * on another thread, or in the same moment.
	 */
	public void testAnswerAfterAck() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, FAST)) {
			device(tpdu -> {
				byte[] answer = answer(DESCRIPTOR_ANSWER);
				new Thread(() -> _link.deliver(DEVICE, answer)).start();
				return List.of(ack(tpdu));
			});
			assertEquals(HEX.formatHex(Tpdu.connected(DESCRIPTOR_ANSWER, 0)),
				HEX.formatHex(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor)));
			device(tpdu -> List.of(ack(tpdu), answer(DESCRIPTOR_ANSWER)));
			assertNotNull(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor));
			// The answer before the acknowledgement.
			device(tpdu -> List.of(answer(DESCRIPTOR_ANSWER), ack(tpdu)));
			assertNotNull(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor));
			// One connection, three requests, three answers acknowledged.
			assertEquals(List.of("80", "4300", "c2", "4700", "c6", "4b00", "ca"), _sent);
		}
	}

	/**
	 * An acknowledgement arriving a second time (the device received the data unit twice): Calimero
	 * ends the connection and fails the read, although the answer arrived.
	 */
	public void testStaleAckCalimero() throws Exception {
		ManagementClientImpl management = new ManagementClientImpl(_link);
		management.responseTimeout(Duration.ofMillis(500));
		Destination destination = management.createDestination(new IndividualAddress(DEVICE), true);
		device(tpdu -> List.of(ack(tpdu), answer(DESCRIPTOR_ANSWER), ack(tpdu)));
		try {
			management.readDeviceDesc(destination, 0);
			fail("Calimero accepted a second acknowledgement.");
		} catch (KNXDisconnectException ex) {
			// The read fails although its answer arrived.
		} finally {
			management.detach();
		}
		assertTrue("Calimero ends the connection on a second acknowledgement: " + _sent, _sent.contains("81"));
	}

	/**
	 * The client of E-Home ignores the acknowledgement arriving again and keeps the connection.
	 */
	public void testStaleAck() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, FAST)) {
			device(tpdu -> List.of(ack(tpdu), answer(DESCRIPTOR_ANSWER), ack(tpdu)));
			assertNotNull(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor));
			device(tpdu -> List.of(ack(tpdu), answer(DESCRIPTOR_ANSWER)));
			assertNotNull(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor));
			assertEquals(1, client.staleAcknowledgements());
			assertFalse(_sent.toString(), _sent.contains("81"));
			assertEquals("One connection.", 1, Collections.frequency(_sent, "80"));
		}
	}

	/**
	 * A repeated answer (the device missed the acknowledgement): acknowledged again, delivered once.
	 */
	public void testRepeatedAnswer() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, FAST)) {
			device(tpdu -> {
				byte[] answer = answer(DESCRIPTOR_ANSWER);
				return List.of(ack(tpdu), answer, answer);
			});
			assertNotNull(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor));
			device(tpdu -> List.of(ack(tpdu), answer(DESCRIPTOR_ANSWER)));
			byte[] second = client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor);
			assertEquals("The answer with the next sequence number.", 1, Tpdu.sequence(second));
			assertEquals(List.of("80", "4300", "c2", "c2", "4700", "c6"), _sent);
		}
	}

	/**
	 * A negative acknowledgement: the data unit is sent again.
	 */
	public void testNak() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, FAST)) {
			int[] count = { 0 };
			device(tpdu -> count[0]++ == 0 ? List.of(Tpdu.acknowledgement(Tpdu.NAK, Tpdu.sequence(tpdu)))
				: List.of(ack(tpdu), answer(DESCRIPTOR_ANSWER)));
			assertNotNull(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor));
			assertEquals(List.of("80", "4300", "4300", "c2"), _sent);
		}
	}

	/**
	 * No acknowledgement at all: sent again after each timeout, then the connection is ended.
	 */
	public void testNoAck() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, FAST)) {
			device(tpdu -> List.of());
			try {
				client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor);
				fail("No acknowledgement.");
			} catch (TransportException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("did not acknowledge"));
			}
			assertEquals(List.of("80", "4300", "4300", "4300", "81"), _sent);
			assertFalse(client.isConnected());
		}
	}

	/**
	 * No answer: the request fails, the connection stays.
	 */
	public void testNoAnswer() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, FAST)) {
			device(tpdu -> List.of(ack(tpdu)));
			try {
				client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor);
				fail("No answer.");
			} catch (TransportException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("did not answer"));
			}
			assertTrue(client.isConnected());
			device(tpdu -> List.of(ack(tpdu), answer(DESCRIPTOR_ANSWER)));
			assertNotNull(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor));
			assertEquals("One connection.", 1, Collections.frequency(_sent, "80"));
		}
	}

	/**
	 * A restart, the device staying silent after acknowledging it: E-Home ends the connection at
	 * once (haumacher/ehome #30).
	 */
	public void testRestartDeviceSilent() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, TransportClient.Timing.DEFAULT)) {
			device(tpdu -> List.of(ack(tpdu)));
			long start = System.nanoTime();
			assertEquals(TransportClient.End.CLIENT, client.requestAndEnd(restart()));
			long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
			assertTrue("Ended after " + millis + " ms.", millis < 1000);
			assertEquals(List.of("80", "4380", "81"), _sent);
			assertFalse(client.isConnected());
		}
	}

	/**
	 * A restart, the device acknowledging and disconnecting: the connection is ended once, by
	 * whichever comes first; a later disconnect of the device is ignored.
	 */
	public void testRestartDeviceDisconnects() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, TransportClient.Timing.DEFAULT)) {
			device(tpdu -> List.of(ack(tpdu), Tpdu.control(Tpdu.DISCONNECT)));
			assertEquals(TransportClient.End.CLIENT, client.requestAndEnd(restart()));
			assertFalse(client.isConnected());
		}
	}

	/**
	 * A restart, the device disconnecting without acknowledging: the device ended the connection.
	 */
	public void testRestartDeviceDisconnectsFirst() throws Exception {
		try (TransportClient client = new TransportClient(_link, DEVICE, TransportClient.Timing.DEFAULT)) {
			device(tpdu -> List.of(Tpdu.control(Tpdu.DISCONNECT)));
			assertEquals(TransportClient.End.DEVICE, client.requestAndEnd(restart()));
			assertEquals("E-Home sends no disconnect of its own.", List.of("80", "4380"), _sent);
			// The next request opens a new connection.
			device(tpdu -> List.of(ack(tpdu), answer(DESCRIPTOR_ANSWER)));
			_deviceSequence = 0;
			assertNotNull(client.request(DESCRIPTOR_READ, TestTransportClient::isDescriptor));
			assertEquals(2, Collections.frequency(_sent, "80"));
		}
	}

	private static boolean waitsForAnswer(Thread client) {
		if (client.getState() != Thread.State.TIMED_WAITING) {
			return false;
		}
		for (StackTraceElement element : client.getStackTrace()) {
			if ("waitForResponse".equals(element.getMethodName())) {
				return true;
			}
		}
		return false;
	}

	private static byte[] restart() {
		return DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.RESTART);
	}

	private static boolean isDescriptor(byte[] tpdu) {
		return (Tpdu.service(tpdu) & 0x3C0) == Tpdu.DESCRIPTOR_RESPONSE;
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object object, String name) throws ReflectiveOperationException {
		Class<?> type = object.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return (T) field.get(object);
			} catch (NoSuchFieldException ex) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	public static Test suite() {
		return new TestSuite(TestTransportClient.class);
	}

}
