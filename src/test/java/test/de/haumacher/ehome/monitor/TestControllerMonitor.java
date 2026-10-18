/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.runtime.FakeController;
import test.de.haumacher.ehome.runtime.FakeMonitorStream;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.ControllerBusLink;
import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.I18NConstants;
import de.haumacher.ehome.monitor.MonitorRow;
import de.haumacher.ehome.monitor.ProjectIndex;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.runtime.ControllerClient;

/**
 * The group monitor attached to a HomeControl controller (haumacher/ehome #41): the monitor stream
 * of a {@link FakeController} over its WebSocket, on the user's test system
 * (<code>samples/KNX-Testsystem.knxproj</code>: the actuator 1.3.5 switches its channel A on 0/0/34,
 * DPT 1.001).
 *
 * <p>
 * The token is refused with a wrong one; the telegrams of the stream fill the state board and the
 * log, decoded by the project, or with the value the controller reports, which wins; the other
 * events are log rows of their own; sending goes through the controller and comes back once, a
 * refusal of the controller is shown; a dropped connection is reconnected with the events in between
 * caught up, a gap reported where the controller's ring overtook the monitor, a restart of the
 * controller reported; a revoked token ends the connection.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestControllerMonitor extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final int SWITCH = 34;

	private static final int UNKNOWN_ADDRESS = 0x7FFF;

	private static final long RETRY_MILLIS = 150;

	public void testMonitorStream() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		FakeController fake = new FakeController(TOKEN, 262144);
		try {
			check(project, fake);
		} finally {
			fake.stop();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private void check(TLObject project, FakeController fake) throws Exception {
		FakeMonitorStream stream = fake.getMonitor();

		// A wrong token is refused when connecting.
		try {
			ControllerBusLink.open("fake", client(fake, "hct_ffffffffffffffffffffffffffffffff"), RETRY_MILLIS,
				RETRY_MILLIS, 0).close();
			fail("A wrong token was accepted.");
		} catch (BusException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("auth.token_invalid"));
		}

		GroupMonitor monitor = new GroupMonitor(ProjectIndex.of(project));
		ControllerBusLink link = ControllerBusLink.open("fake", client(fake, TOKEN), RETRY_MILLIS, RETRY_MILLIS, 0);
		monitor.connect(link);
		try {
			assertTrue(monitor.isConnected());
			assertTrue(monitor.isConnectedTo(link.description()));
			assertEquals(text(I18NConstants.STATUS_CONTROLLER_CONNECTED__CONTROLLER_COUNT.fill(link.description(),
				Long.valueOf(0))), monitor.snapshot().get("status"));

			// A telegram without a datapoint of the controller is decoded by the project.
			stream.emit(FakeMonitorStream.knx("1.3.5", "0/0/34", "write", "01", true, null, null));
			awaitLog(monitor, 1);
			MonitorRow row = board(monitor, SWITCH);
			assertEquals("on", row.get("value"));
			MonitorRow entry = log(monitor).get(0);
			assertEquals("1.3.5", entry.get("source"));
			assertEquals("0/0/34", entry.get("address"));
			assertEquals("KanalA", entry.get("name"));
			assertEquals("on", entry.get("value"));
			assertEquals("01", entry.get("data"));
			assertEquals(text(I18NConstants.EVENT_KIND_KNX), entry.get("kind"));
			assertEquals(Boolean.FALSE, entry.get("own"));

			// The controller's value wins over the decoding by the project; the project's name stays.
			stream.emit(FakeMonitorStream.knx("1.3.5", "0/0/34", "write", "01", true, "living.light", Boolean.FALSE));
			awaitLog(monitor, 2);
			assertEquals("off", log(monitor).get(0).get("value"));
			assertEquals("KanalA", log(monitor).get(0).get("name"));
			assertEquals("off", board(monitor, SWITCH).get("value"));

			// An address the project does not know: named and valued by the controller.
			stream.emit(FakeMonitorStream.knx("1.1.10", "15/7/255", "write", "0C 33", false, "living.temperature",
				Double.valueOf(21.5)));
			awaitLog(monitor, 3);
			assertEquals("living.temperature", log(monitor).get(0).get("name"));
			assertEquals("21.5", log(monitor).get(0).get("value"));
			assertEquals("living.temperature", board(monitor, UNKNOWN_ADDRESS).get("name"));
			assertEquals(3L, monitor.getReceived());

			// Events that are no telegrams: rows of the log of their own, not on the board.
			stream.emit(Map.of("kind", "rule", "rule", "hall-presence-light", "dp", 3, "name", "hall.light", "value",
				Boolean.TRUE));
			stream.emit(Map.of("kind", "zigbee", "event", "report", "ieee", "00124b0001020304", "ep", 11, "cluster", 6,
				"attr", 0, "name", "zigbee.on", "value", Boolean.TRUE));
			stream.emit(Map.of("kind", "image", "event", "load", "project", "example-house", "revision", 7, "source",
				"api"));
			awaitLog(monitor, 6);
			List<MonitorRow> log = log(monitor);
			assertEquals(text(I18NConstants.EVENT_KIND_IMAGE), log.get(0).get("kind"));
			assertEquals(text(I18NConstants.EVENT_IMAGE_LOAD__PROJECT_REVISION_SOURCE.fill("example-house", "7", "api")),
				log.get(0).get("sender"));
			assertEquals(text(I18NConstants.EVENT_KIND_ZIGBEE), log.get(1).get("kind"));
			assertEquals(text(I18NConstants.EVENT_ZIGBEE_REPORT__DEVICE_ENDPOINT_CLUSTER_ATTRIBUTE
				.fill("00124b0001020304", "11", "6", "0")), log.get(1).get("sender"));
			assertEquals("on", log.get(1).get("value"));
			assertEquals(text(I18NConstants.EVENT_KIND_RULE), log.get(2).get("kind"));
			assertEquals(text(I18NConstants.EVENT_RULE__RULE.fill("hall-presence-light")), log.get(2).get("sender"));
			assertEquals("hall.light", log.get(2).get("name"));
			assertEquals(3L, monitor.getReceived());

			// Sending goes through the controller; the telegram comes back once, marked as own.
			monitor.setSending(true);
			monitor.send(Integer.valueOf(SWITCH), Boolean.TRUE, null);
			awaitLog(monitor, 7);
			Map<String, Object> request = stream.getRequests().get(0);
			assertEquals(Map.of("knx", Map.of("address", "0/0/34", "data", "01", "short", Boolean.TRUE)),
				request.get("send"));
			entry = log(monitor).get(0);
			assertEquals(Boolean.TRUE, entry.get("own"));
			assertEquals("on", entry.get("value"));
			assertEquals(FakeMonitorStream.TUNNEL_ADDRESS, entry.get("source"));
			monitor.read(Integer.valueOf(SWITCH));
			awaitLog(monitor, 8);
			assertEquals(Map.of("knx", Map.of("address", "0/0/34")), stream.getRequests().get(1).get("read"));
			assertEquals(text(I18NConstants.STATUS_CONTROLLER_CONNECTED__CONTROLLER_ADDRESS_COUNT
				.fill(link.description(), FakeMonitorStream.TUNNEL_ADDRESS, Long.valueOf(5))),
				monitor.snapshot().get("status"));

			// A refusal of the controller is shown.
			stream.refuseNext("monitor.tunnel_down");
			try {
				monitor.send(Integer.valueOf(SWITCH), Boolean.FALSE, null);
				fail("The refusal of the controller was not reported.");
			} catch (TopLogicException ex) {
				assertTrue(text(ex.getErrorKey()), text(ex.getErrorKey()).contains("monitor.tunnel_down"));
			}
			Thread.sleep(50);
			assertEquals(8, log(monitor).size());

			// The connection drops: the link reconnects and catches up with the events in between.
			stream.dropConnections();
			await(() -> link.problem() != null);
			String problem = link.problem();
			Object status = monitor.snapshot().get("status");
			if (problem != null && problem.equals(link.problem())) {
				assertEquals(text(I18NConstants.STATUS_CONTROLLER_INTERRUPTED__CONTROLLER_REASON.fill(link.description(),
					problem)), status);
			}
			stream.emit(FakeMonitorStream.knx("1.3.5", "0/0/34", "write", "00", true, null, null));
			stream.emit(FakeMonitorStream.knx("1.3.5", "0/0/34", "write", "01", true, null, null));
			awaitLog(monitor, 10);
			assertNull(link.problem());
			// The refused one, the first and the reconnected one.
			assertEquals(3, stream.getConnections());
			assertEquals("on", log(monitor).get(0).get("value"));
			assertEquals("off", log(monitor).get(1).get("value"));

			// More events than the controller holds while the monitor is away: a gap.
			stream.dropConnections();
			for (int n = 0; n < FakeMonitorStream.CAPACITY + 5; n++) {
				stream.emit(FakeMonitorStream.knx("1.3.5", "0/0/34", "write", n % 2 == 0 ? "00" : "01", true, null, null));
			}
			awaitLog(monitor, 10 + 1 + FakeMonitorStream.CAPACITY);
			MonitorRow gap = log(monitor).get(FakeMonitorStream.CAPACITY);
			assertEquals(text(I18NConstants.EVENT_KIND_GAP), gap.get("kind"));
			assertEquals(text(I18NConstants.EVENT_GAP__COUNT.fill("5")), gap.get("sender"));

			// The controller restarts: its sequence starts anew.
			stream.restart();
			stream.emit(FakeMonitorStream.knx("1.3.5", "0/0/34", "write", "00", true, null, null));
			awaitLog(monitor, 10 + 1 + FakeMonitorStream.CAPACITY + 2);
			assertEquals("off", log(monitor).get(0).get("value"));
			assertEquals(text(I18NConstants.EVENT_KIND_RESET), log(monitor).get(1).get("kind"));
			assertTrue(monitor.isConnected());

			// The token is revoked: the connection ends for good.
			stream.revoke();
			await(() -> !monitor.isConnected());
			assertEquals(text(I18NConstants.STATUS_CONTROLLER_LOST__CONTROLLER_REASON.fill(link.description(),
				"The controller ended the connection: auth.session_ended")), monitor.snapshot().get("status"));
			int connections = stream.getConnections();
			Thread.sleep(2 * RETRY_MILLIS);
			assertEquals(connections, stream.getConnections());
		} finally {
			monitor.close();
		}
	}

	private static ControllerClient client(FakeController fake, String token) {
		return new ControllerClient("127.0.0.1", fake.getPort(), token, Duration.ofSeconds(5));
	}

	private static void awaitLog(GroupMonitor monitor, int size) throws InterruptedException {
		await(() -> log(monitor).size() >= size);
		assertEquals(size, log(monitor).size());
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long end = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				fail("Condition not reached within 5 s.");
			}
			Thread.sleep(10);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<MonitorRow> log(GroupMonitor monitor) {
		return (List<MonitorRow>) monitor.snapshot().get("log");
	}

	@SuppressWarnings("unchecked")
	private static MonitorRow board(GroupMonitor monitor, int address) {
		for (MonitorRow row : (List<MonitorRow>) monitor.snapshot().get("board")) {
			if (row.get(MonitorRow.KEY).equals(Integer.valueOf(address))) {
				return row;
			}
		}
		throw new AssertionError("No row for " + address);
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestControllerMonitor.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
