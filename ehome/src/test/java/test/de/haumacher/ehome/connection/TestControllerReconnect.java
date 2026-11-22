/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.monitor.SocketBusLink;

/**
 * A controller connecting again while a part of E-Home follows it (haumacher/ehome #188): the
 * connection is established for the listeners of {@link ControllerConnections} only after
 * <code>ready</code> and <code>subscribe</code> went out, so that nothing they send comes before
 * <code>ready</code>, which the firmware takes as a failed attempt (homecontrol
 * <code>spec/api.md</code>, "The socket"). The group monitor open over the connection
 * ({@link SocketBusLink}) subscribes again on the new connection after them.
 */
@SuppressWarnings("javadoc")
public class TestControllerReconnect extends BasicTestCase {

	public void testMonitorOpenWhileReconnecting() throws Exception {
		TLObject project = ControllerEndpoint.project("Reconnect test");
		FakeBusController first = FakeBusController.connect(project, "homecontrol-reconnect", true);
		FakeBusController again = null;
		SocketBusLink monitor = SocketBusLink.open("homecontrol-reconnect", first.connection().controllerId(),
			Duration.ofSeconds(5));
		try {
			again = first.reconnect();
			List<Object> types = again.messages().stream().map(m -> m.get("type")).toList();
			assertEquals(types.toString(), List.of("challenge", "ready", "subscribe"), types.subList(0, 3));

			// The monitor subscribes on the new connection after them.
			FakeBusController current = again;
			await(() -> current.messages().stream().anyMatch(FakeBusController::monitorSubscription));
			List<Map<String, Object>> messages = again.messages();
			int subscription = messages.indexOf(messages.stream().filter(FakeBusController::monitorSubscription)
				.findFirst().orElseThrow());
			assertTrue(messages.toString(), subscription > 2);
			assertSame(again.connection(), ControllerConnections.get(first.connection().controllerId()));
			await(() -> monitor.problem() == null);
		} finally {
			monitor.close();
			if (again != null) {
				again.stop();
			}
			first.stop();
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The controller pauses its connection for a fetch of its own (homecontrol #112, close code 4000): no
	 * problem for the group monitor, which subscribes again when the controller is back; an unplanned
	 * end is a problem as before.
	 */
	public void testPauseIsNoProblem() throws Exception {
		TLObject project = ControllerEndpoint.project("Pause test");
		FakeBusController first = FakeBusController.connect(project, "homecontrol-pause", true);
		FakeBusController again = null;
		String id = first.connection().controllerId();
		SocketBusLink monitor = SocketBusLink.open("homecontrol-pause", id, Duration.ofSeconds(5));
		try {
			await(() -> first.messages().stream().anyMatch(FakeBusController::monitorSubscription));
			first.pause();
			assertTrue(first.connection().isPaused());
			assertNull(ControllerConnections.get(id));
			assertNull(monitor.problem());

			again = first.reconnect();
			FakeBusController current = again;
			await(() -> current.messages().stream().anyMatch(FakeBusController::monitorSubscription));
			assertNull(monitor.problem());

			again.disconnect("closed (1006)");
			assertFalse(again.connection().isPaused());
			await(() -> monitor.problem() != null);
		} finally {
			monitor.close();
			if (again != null) {
				again.stop();
			}
			first.stop();
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * Nothing goes out on a connection before it is accepted: a send there is a programming error.
	 */
	public void testNoSendBeforeReady() throws Exception {
		List<String> sent = new ArrayList<>();
		ControllerConnection connection = new ControllerConnection("0".repeat(32), new ControllerConnection.Peer() {
			@Override
			public void sendText(String text) {
				sent.add(text);
			}

			@Override
			public void sendBinary(byte[] data) {
				fail("No binary message expected.");
			}

			@Override
			public void close(int code, String reason) {
				// Nothing to close.
			}

			@Override
			public String remote() {
				return "test";
			}
		});
		connection.open();
		try {
			connection.send(Map.of("type", "monitor", "subscribe", Boolean.TRUE));
			fail("A send before ready must be refused.");
		} catch (IllegalStateException ex) {
			// Expected.
		}
		assertEquals(1, sent.size());
		assertTrue(sent.get(0), sent.get(0).contains("challenge"));
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long end = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			assertTrue("Timed out.", System.currentTimeMillis() < end);
			Thread.sleep(10);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestControllerReconnect.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
