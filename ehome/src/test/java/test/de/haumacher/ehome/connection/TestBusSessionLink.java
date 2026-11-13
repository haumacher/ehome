/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAckTimeoutException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.Priority;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.NetworkLinkListener;

import de.haumacher.ehome.connection.BusSessionLink;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.I18NConstants;
import de.haumacher.ehome.knx.transport.Tpdu;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;

/**
 * The network link through the bus session of a connected controller (haumacher/ehome #148,
 * homecontrol #85 <code>spec/api.md</code> "Bus session"), over a simulated controller connection
 * ({@link FakeBusController}): the frames out and in, the outcomes as confirmations, every refusal,
 * one session per controller, the end of the session by the controller and by the connection, and a
 * device read through it by E-Home's own transport connection ({@link CalimeroReader}).
 */
@SuppressWarnings("javadoc")
public class TestBusSessionLink extends BasicTestCase {

	private static final IndividualAddress DEVICE = new IndividualAddress(1, 3, 5);

	private static final IndividualAddress TUNNEL = new IndividualAddress(1, 1, 250);

	private TLObject _project;

	private FakeBusController _fake;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_project = ControllerEndpoint.project("Bus session test");
		_fake = FakeBusController.connect(_project, "homecontrol-bus", true);
	}

	@Override
	protected void tearDown() throws Exception {
		_fake.stop();
		SharedData.deleteProjects(List.of(_project));
		super.tearDown();
	}

	/**
	 * <code>open</code> answered by <code>opened</code>: the tunnel's address is the link's own; a frame
	 * goes as the hex of its cEMI L_Data.req from that address; the indications come to the listeners
	 * in order.
	 */
	public void testFramesOutAndIn() throws Exception {
		assertNull(BusSessionLink.veto(_fake.controller()));
		Recorder recorder = new Recorder();
		BusSessionLink link = BusSessionLink.open(_fake.connection(), "homecontrol-bus");
		try {
			link.addLinkListener(recorder);
			assertEquals(TUNNEL, link.address());
			assertEquals(TUNNEL, link.getKNXMedium().getDeviceAddress());
			assertEquals(254, link.getKNXMedium().maxApduLength());
			assertEquals(60, link.idleSeconds());
			assertTrue(_fake.sessionOpen());
			assertEquals("open", _fake.busMessages().get(0).get("op"));

			link.sendRequestWait(DEVICE, Priority.SYSTEM, Tpdu.control(Tpdu.CONNECT));
			byte[] expected = new CEMILData(CEMILData.MC_LDATA_REQ, TUNNEL, DEVICE, Tpdu.control(Tpdu.CONNECT),
				Priority.SYSTEM).toByteArray();
			assertEquals(1, _fake.framesSent().size());
			CEMILData sent = new CEMILData(hex(_fake.framesSent().get(0)), 0, expected.length);
			assertEquals(CEMILData.MC_LDATA_REQ, sent.getMessageCode());
			assertEquals(TUNNEL, sent.getSource());
			assertEquals(DEVICE, sent.getDestination());
			assertEquals(List.of(0x80), bytes(sent.getPayload()));

			_fake.frames(indication(Tpdu.acknowledgement(Tpdu.ACK, 0)), indication(Tpdu.control(Tpdu.DISCONNECT)));
			List<CEMILData> received = recorder.await(2);
			assertEquals(DEVICE, received.get(0).getSource());
			assertEquals(TUNNEL, received.get(0).getDestination());
			assertEquals(List.of(0xC2), bytes(received.get(0).getPayload()));
			assertEquals(List.of(0x81), bytes(received.get(1).getPayload()));
		} finally {
			link.close();
		}
		_fake.sync();
		assertFalse("close ends the session on the controller", _fake.sessionOpen());
		List<Map<String, Object>> messages = _fake.busMessages();
		assertEquals("close", messages.get(messages.size() - 1).get("op"));
		assertNull(BusSessionLink.veto(_fake.controller()));
	}

	/**
	 * E-Home's own transport connection and a read of the device descriptor on the link: connect,
	 * data, the device's acknowledgement and answer, E-Home's acknowledgement.
	 */
	public void testReaderThroughController() throws Exception {
		_fake.setBus(new Device(DEVICE, new byte[] { 0x07, 0x05 }));
		BusPath path = new BusPath.Controller("homecontrol-bus", _fake.connection());
		assertEquals("Controller homecontrol-bus", path.text());
		try (CalimeroReader reader = CalimeroReader.open(path, DEVICE.getRawAddress())) {
			assertEquals(List.of(0x07, 0x05), bytes(reader.readDescriptor()));
			reader.disconnect();
		}
		List<Integer> tpci = new ArrayList<>();
		for (String frame : _fake.framesSent()) {
			byte[] bytes = hex(frame);
			tpci.add(Integer.valueOf(new CEMILData(bytes, 0, bytes.length).getPayload()[0] & 0xFF));
		}
		// Connect, data 0 (descriptor read), ACK 0 of the answer, disconnect.
		assertEquals(List.of(0x80, 0x43, 0xC2), tpci.subList(0, 3));
		assertTrue(String.valueOf(tpci), tpci.contains(0x81));
	}

	/**
	 * The outcome of the tunnel is the confirmation of the link: <code>negative</code> and
	 * <code>no_confirmation</code> fail a send waiting for it and pass one that does not wait;
	 * <code>lost</code> ends the link; a full queue is waited out.
	 */
	public void testOutcomes() throws Exception {
		List<String> outcomes = new CopyOnWriteArrayList<>(List.of("negative", "negative", "no_confirmation",
			"no_confirmation", "confirmed", "lost"));
		_fake.setBus((controller, frame) -> outcomes.remove(0));
		Recorder recorder = new Recorder();
		BusSessionLink link = BusSessionLink.open(_fake.connection(), "homecontrol-bus");
		try {
			link.addLinkListener(recorder);
			byte[] tpdu = Tpdu.control(Tpdu.CONNECT);
			assertThrows(KNXAckTimeoutException.class, () -> link.sendRequestWait(DEVICE, Priority.SYSTEM, tpdu));
			link.sendRequest(DEVICE, Priority.SYSTEM, tpdu);
			KNXTimeoutException timeout =
				assertThrows(KNXTimeoutException.class, () -> link.sendRequestWait(DEVICE, Priority.SYSTEM, tpdu));
			assertFalse(timeout instanceof KNXAckTimeoutException);
			link.sendRequest(DEVICE, Priority.SYSTEM, tpdu);

			_fake.refuseNext("send", BusSessionLink.CODE_QUEUE_FULL, "4 frames of the session wait for their outcome");
			link.sendRequestWait(DEVICE, Priority.SYSTEM, tpdu);
			assertEquals("the refused frame is offered again", 6, _fake.framesSent().size());

			assertThrows(KNXLinkClosedException.class, () -> link.sendRequestWait(DEVICE, Priority.SYSTEM, tpdu));
			CloseEvent closed = recorder.awaitClosed();
			assertTrue(closed.getReason(), closed.getReason().contains("the KNX tunnel went down"));
			assertTrue(closed.getReason(), closed.getReason().contains("bus.tunnel_down"));
			awaitClosed(link);
			assertNull("the session ended: another one may open", BusSessionLink.veto(_fake.controller()));
		} finally {
			link.close();
		}
	}

	/**
	 * Every refusal of the controller is shown with its reason: the opening (no KNX, tunnel down,
	 * busy, no memory), a frame (bad frame: the link stays; no session: the link ends).
	 */
	public void testRefusals() throws Exception {
		assertOpenRefused(BusSessionLink.CODE_KNX_UNAVAILABLE, "this controller has no KNX tunnel",
			I18NConstants.ERROR_BUS_NO_KNX__CONTROLLER_REASON.fill("homecontrol-bus", "this controller has no KNX tunnel"));
		assertOpenRefused(BusSessionLink.CODE_TUNNEL_DOWN, "the KNX tunnel is not up (connecting)",
			I18NConstants.ERROR_BUS_TUNNEL_DOWN__CONTROLLER_REASON.fill("homecontrol-bus",
				"the KNX tunnel is not up (connecting)"));
		assertOpenRefused(BusSessionLink.CODE_BUSY, "a bus session is open already: one at a time",
			I18NConstants.ERROR_BUS_SESSION_OPEN__CONTROLLER.fill("homecontrol-bus"));
		assertOpenRefused("bus.no_memory", "no memory for the bus session (3400 bytes)",
			I18NConstants.ERROR_BUS_REFUSED__CONTROLLER_REASON_CODE.fill("homecontrol-bus",
				"no memory for the bus session (3400 bytes)", "bus.no_memory"));

		_fake.setSilent(true);
		assertEquals(I18NConstants.ERROR_BUS_NO_ANSWER__CONTROLLER.fill("homecontrol-bus"),
			refusal(() -> BusSessionLink.open(_fake.connection(), "homecontrol-bus", Duration.ofMillis(300))));
		_fake.setSilent(false);
		assertNull("a session that did not open is not held", BusSessionLink.veto(_fake.controller()));

		BusSessionLink link = BusSessionLink.open(_fake.connection(), "homecontrol-bus");
		try {
			_fake.refuseNext("send", "bus.bad_frame", "the frame is no L_Data.req (message code 11)");
			BusSessionLink.FrameRefusedException refused = assertThrows(BusSessionLink.FrameRefusedException.class,
				() -> link.sendRequestWait(DEVICE, Priority.SYSTEM, Tpdu.control(Tpdu.CONNECT)));
			assertTrue(refused.getMessage(), refused.getMessage().contains("message code 11"));
			assertTrue(link.isOpen());

			_fake.refuseNext("send", BusSessionLink.CODE_NO_SESSION, "no bus session is open");
			assertThrows(KNXLinkClosedException.class,
				() -> link.sendRequestWait(DEVICE, Priority.SYSTEM, Tpdu.control(Tpdu.CONNECT)));
		} finally {
			link.close();
		}
	}

	/**
	 * One session per controller: E-Home refuses a second one before asking the controller; the
	 * veto of the commands says so; after the close the controller is free again.
	 */
	public void testOneSessionAtATime() throws Exception {
		BusSessionLink link = BusSessionLink.open(_fake.connection(), "homecontrol-bus");
		try {
			ResKey open = I18NConstants.ERROR_BUS_SESSION_OPEN__CONTROLLER.fill("homecontrol-bus");
			assertEquals(open, BusSessionLink.veto(_fake.controller()));
			int before = _fake.busMessages().size();
			assertEquals(open, refusal(() -> BusSessionLink.open(_fake.connection(), "homecontrol-bus")));
			assertEquals("refused in E-Home", before, _fake.busMessages().size());
			assertTrue(link.isOpen());
		} finally {
			link.close();
		}
		assertNull(BusSessionLink.veto(_fake.controller()));
		BusSessionLink.open(_fake.connection(), "homecontrol-bus").close();
	}

	/**
	 * The controller ends the session (idle): the link closes with the reason, a send fails, the
	 * controller is free. The connection ends: a read waiting on the link fails, the controller is
	 * offline with its last contact.
	 */
	public void testSessionEnd() throws Exception {
		Recorder recorder = new Recorder();
		BusSessionLink link = BusSessionLink.open(_fake.connection(), "homecontrol-bus");
		link.addLinkListener(recorder);
		_fake.end("bus.idle", "no frame for 60 s");
		CloseEvent closed = recorder.awaitClosed();
		assertEquals("the controller homecontrol-bus ended the bus session: no frame for 60 s (bus.idle)",
			closed.getReason());
		awaitClosed(link);
		assertEquals(closed.getReason(), link.end());
		assertThrows(KNXLinkClosedException.class,
			() -> link.sendRequestWait(DEVICE, Priority.SYSTEM, Tpdu.control(Tpdu.CONNECT)));
		link.close();
		assertNull(BusSessionLink.veto(_fake.controller()));

		// The connection ends during a read: the device never answers, the read fails with the reason.
		_fake.setBus((controller, frame) -> "confirmed");
		BusPath path = new BusPath.Controller("homecontrol-bus", _fake.connection());
		try (CalimeroReader reader = CalimeroReader.open(path, DEVICE.getRawAddress())) {
			CompletableFuture<Object> read = CompletableFuture.supplyAsync(() -> {
				try {
					return reader.readDescriptor();
				} catch (BusException failure) {
					return failure;
				}
			});
			Thread.sleep(300);
			_fake.disconnect("network down");
			Object outcome = read.get(30, TimeUnit.SECONDS);
			assertTrue(String.valueOf(outcome), outcome instanceof BusException);
			String message = ((BusException) outcome).getMessage();
			assertTrue(message, message.contains("through Controller homecontrol-bus"));
			assertTrue(message, message.contains("network down"));
		}
		ControllerConnections.awaitKnowledgeBase();
		assertNull(ControllerConnections.get(_fake.controller()));
		assertEquals(BusSessionLink.offline(_fake.controller()), BusSessionLink.veto(_fake.controller()));
		assertNotNull("the last contact is known", _fake.controller().tValueByName("lastContact"));
	}

	/**
	 * A firmware without the bus session (no <code>ehome.bus</code> in its status) is refused before
	 * anything is sent: it needs an update.
	 */
	public void testFirmwareWithoutBusSession() throws Exception {
		_fake.status(false);
		ResKey firmware = I18NConstants.ERROR_BUS_FIRMWARE__CONTROLLER.fill("homecontrol-bus");
		assertEquals(firmware, BusSessionLink.veto(_fake.controller()));
		assertEquals(firmware, refusal(() -> BusSessionLink.open(_fake.connection(), "homecontrol-bus")));
		assertEquals(List.of(), _fake.busMessages());
		_fake.status(true);
		assertNull(BusSessionLink.veto(_fake.controller()));
	}

	private void assertOpenRefused(String code, String error, ResKey expected) throws Exception {
		_fake.refuseNext("open", code, error);
		assertEquals(expected, refusal(() -> BusSessionLink.open(_fake.connection(), "homecontrol-bus")));
		_fake.sync();
		assertNull("a refused session is not held", BusSessionLink.veto(_fake.controller()));
	}

	private static ResKey refusal(Opening opening) {
		try {
			opening.open().close();
			fail("Refused expected.");
			return null;
		} catch (TopLogicException ex) {
			return ex.getErrorKey();
		}
	}

	private interface Action {
		void run() throws Exception;
	}

	private static <T extends Throwable> T assertThrows(Class<T> type, Action action) {
		try {
			action.run();
		} catch (Throwable ex) {
			if (type.isInstance(ex)) {
				return type.cast(ex);
			}
			throw new AssertionError("Expected " + type.getSimpleName() + ", got " + ex, ex);
		}
		throw new AssertionError("Expected " + type.getSimpleName() + ".");
	}

	private interface Opening {
		BusSessionLink open();
	}

	private static void awaitClosed(BusSessionLink link) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (link.isOpen()) {
			assertTrue("The link closes.", System.currentTimeMillis() < deadline);
			Thread.sleep(10);
		}
	}

	private static byte[] indication(byte[] tpdu) {
		return new CEMILData(CEMILData.MC_LDATA_IND, DEVICE, TUNNEL, tpdu, Priority.SYSTEM).toByteArray();
	}

	static byte[] hex(String text) {
		return java.util.HexFormat.of().parseHex(text);
	}

	static List<Integer> bytes(byte[] data) {
		List<Integer> result = new ArrayList<>();
		for (byte b : data) {
			result.add(Integer.valueOf(b & 0xFF));
		}
		return result;
	}

	/**
	 * The indications and the close the link reports.
	 */
	static final class Recorder implements NetworkLinkListener {

		private final List<CEMILData> _indications = new CopyOnWriteArrayList<>();

		private final CompletableFuture<CloseEvent> _closed = new CompletableFuture<>();

		@Override
		public void indication(FrameEvent e) {
			_indications.add((CEMILData) e.getFrame());
		}

		@Override
		public void linkClosed(CloseEvent e) {
			_closed.complete(e);
		}

		List<CEMILData> await(int count) throws InterruptedException {
			long deadline = System.currentTimeMillis() + 5000;
			while (_indications.size() < count) {
				assertTrue("Indications: " + _indications, System.currentTimeMillis() < deadline);
				Thread.sleep(10);
			}
			return _indications;
		}

		CloseEvent awaitClosed() throws Exception {
			return _closed.get(5, TimeUnit.SECONDS);
		}
	}

	/**
	 * A device behind the tunnel answering the read of its descriptor in a transport connection.
	 */
	static final class Device implements FakeBusController.Bus {

		private final IndividualAddress _address;

		private final byte[] _descriptor;

		private int _sequence;

		Device(IndividualAddress address, byte[] descriptor) {
			_address = address;
			_descriptor = descriptor;
		}

		@Override
		public String send(FakeBusController controller, byte[] frame) throws Exception {
			CEMILData request = new CEMILData(frame, 0, frame.length);
			if (!_address.equals(request.getDestination())) {
				return "confirmed";
			}
			byte[] tpdu = request.getPayload();
			if (Tpdu.is(tpdu, Tpdu.CONNECT)) {
				_sequence = 0;
			} else if (Tpdu.isConnectedData(tpdu) && Tpdu.service(tpdu) == Tpdu.DESCRIPTOR_READ) {
				byte[] answer = new byte[2 + _descriptor.length];
				answer[0] = (byte) (Tpdu.DESCRIPTOR_RESPONSE >>> 8);
				answer[1] = (byte) Tpdu.DESCRIPTOR_RESPONSE;
				System.arraycopy(_descriptor, 0, answer, 2, _descriptor.length);
				controller.frames(
					reply(request, Tpdu.acknowledgement(Tpdu.ACK, Tpdu.sequence(tpdu))),
					reply(request, Tpdu.connected(answer, _sequence++)));
			}
			return "confirmed";
		}

		private byte[] reply(CEMILData request, byte[] tpdu) {
			return new CEMILData(CEMILData.MC_LDATA_IND, _address, request.getSource(), tpdu, Priority.SYSTEM)
				.toByteArray();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBusSessionLink.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
