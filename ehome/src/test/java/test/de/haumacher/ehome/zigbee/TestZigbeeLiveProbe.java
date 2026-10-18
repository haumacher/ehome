/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.connection.ControllerEndpoint;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.runtime.FakeController;
import test.de.haumacher.ehome.runtime.FakeControllerLink;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Controllers;
import de.haumacher.ehome.connection.Enrollment;
import de.haumacher.ehome.zigbee.I18NConstants;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeLive;
import de.haumacher.ehome.zigbee.ZigbeeStatus;

/**
 * Probe of the live import composed with round one's rules and the connection's life cycle
 * (haumacher/ehome #64): the user's name of a device and the binding of a function the controller
 * no longer exports survive a live import (the function stays as absent); a <code>left</code>
 * event shows in the device state only while the controller is connected, the disconnect falls
 * back to the stored state and cancels the pending read; a controller that connects again is
 * imported again on its next event.
 *
 * <p>
 * Takes about 6 s: two reads separated by the live import's own interval ({@link ZigbeeLive#MIN_INTERVAL}),
 * and one more such interval of silence after the disconnect to prove that nothing is read then.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeLiveProbe extends BasicTestCase {

	private static final String TOKEN = "hct_fedcba9876543210fedcba9876543210";

	private static final String FIXTURE = "src/test/fixtures/zigbee/motion-sensor.export.json";

	private static final String IEEE = "0017880106498a49";

	private static final String EXPORT_PATH = "/api/zigbee/export";

	private ControllerEndpoint _endpoint;

	private FakeController _fake;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_endpoint = new ControllerEndpoint();
		_fake = new FakeController(TOKEN, 262144);
		ZigbeeLive.start();
	}

	@Override
	protected void tearDown() throws Exception {
		ZigbeeLive.stop();
		_fake.stop();
		_endpoint.stop();
		super.tearDown();
	}

	public void testBindingsAndReconnect() throws Exception {
		String export = Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8);
		_fake.setZigbeeExport(export, 16);
		TLObject project = ControllerEndpoint.project("Zigbee live probe");
		TLObject controller = null;
		try {
			Enrolled enrolled = enroll(project, "homecontrol-probe");
			controller = enrolled.controller();
			String id = enrolled.id();
			FakeControllerLink link = connect(enrolled);

			link.send(event("interviewed"));
			awaitRead(id, null);
			TLObject device = ZigbeeImport.devices(project).get(0);
			TLObject occupancy = function(device, "occupancy");
			ZigbeeLive.Read first = ZigbeeLive.lastRead(id);

			// The installer names the device and binds its occupancy; the controller then exports the
			// device with a label and without the occupancy function.
			TLObject occupancyDp;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				device.tUpdateByName("name", "Flur Bewegung");
				occupancyDp = ZigbeeBindings.createDatapoint(occupancy);
				tx.commit();
			}
			String reduced = export.replace("\"label\": \"\"", "\"label\": \"Hallway\"")
				.replace("\"permit_join_s\": 0", "\"permit_join_s\": 60");
			int start = reduced.indexOf("{\n\t\t\t\t\t\"name\": \"zigbee.0017880106498a49.2.occupancy\"");
			int end = reduced.indexOf("{\n\t\t\t\t\t\"name\": \"zigbee.0017880106498a49.2.battery\"");
			assertTrue(start > 0 && end > start);
			reduced = reduced.substring(0, start) + reduced.substring(end);
			_fake.setZigbeeExport(reduced, 16);
			link.send(event("changed"));
			link.send(event("interviewed"));
			awaitRead(id, first);
			assertEquals(2, exportRequests(link).size());

			assertEquals("The user's name survives the live import.", "Flur Bewegung", device.tValueByName("name"));
			assertEquals("The controller's label is taken.", "Hallway", device.tValueByName("label"));
			assertEquals(5, ((Collection<?>) device.tValueByName("functions")).size());
			assertTrue(occupancy.tValid());
			assertEquals(Boolean.TRUE, occupancy.tValueByName("absent"));
			assertEquals(List.of(occupancyDp), ZigbeeBindings.boundDatapoints(occupancy));
			assertEquals(Boolean.FALSE, function(device, "battery").tValueByName("absent"));
			assertEquals(60L, ((Number) controller.tValueByName("zigbeePermitJoin")).longValue());
			// A live read is fresh: its permit time counts from the read, not from the export's own
			// (here: old) time stamp.
			String open = ZigbeeStatus.networkState(project, Instant.now());
			assertTrue(open, open.equals(text(I18NConstants.NETWORK_OPEN__PAN_CHANNEL_SECONDS.fill("0x1234",
				Long.valueOf(15), Long.valueOf(60))))
				|| open.equals(text(I18NConstants.NETWORK_OPEN__PAN_CHANNEL_SECONDS.fill("0x1234", Long.valueOf(15),
					Long.valueOf(59)))));

			// The device leaves: the state says so while the controller is connected. The disconnect
			// falls back to the stored state and drops the read the event scheduled.
			link.send(event("left"));
			await(1000, () -> ZigbeeLive.deviceState(id, IEEE) == ZigbeeLive.DeviceState.LEFT);
			assertEquals(text(I18NConstants.DEVICE_LEFT), ZigbeeStatus.deviceState(device));
			link.close();
			TLObject connected = controller;
			await(3000, () -> ControllerConnections.get(connected) == null);
			assertNull(ZigbeeLive.deviceState(id, IEEE));
			assertEquals(text(I18NConstants.DEVICE_PAIRED), ZigbeeStatus.deviceState(device));
			ZigbeeLive.Read beforeSleep = ZigbeeLive.lastRead(id);
			Thread.sleep(ZigbeeLive.MIN_INTERVAL.toMillis() + 500);
			assertEquals("No read after the disconnect.", 2, exportRequests(link).size());
			assertSame("No import after the disconnect.", beforeSleep, ZigbeeLive.lastRead(id));

			// The controller comes back: its next event is imported over the new socket.
			FakeControllerLink again = connect(enrolled);
			ZigbeeLive.Read second = ZigbeeLive.lastRead(id);
			again.send(event("interviewed"));
			awaitRead(id, second);
			assertEquals(1, exportRequests(again).size());
			assertEquals(List.of(device), ZigbeeImport.devices(project));
			assertEquals("Flur Bewegung", device.tValueByName("name"));
			assertNull(ZigbeeStatus.liveProblem(project));
		} finally {
			if (controller != null) {
				Controllers.forget(controller);
			}
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(List.of(project));
		}
	}

	private record Enrolled(String id, String secret, URI socket, TLObject controller) {
		// A record.
	}

	private Enrolled enroll(TLObject project, String name) throws Exception {
		Enrollment.createCode(project, null, Instant.now());
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("code", project.tValueByName("enrollmentCode"));
		request.put("name", name);
		Enrollment.Answer answer = Enrollment.enroll(request, _endpoint.base(), Instant.now());
		assertEquals(answer.body().toString(), 200, answer.status());
		ControllerConnections.refetch();
		return new Enrolled((String) answer.body().get("controller"), (String) answer.body().get("secret"),
			URI.create((String) answer.body().get("socket")), (TLObject) project.tValueByName("controller"));
	}

	private FakeControllerLink connect(Enrolled enrolled) throws Exception {
		FakeControllerLink link = new FakeControllerLink(_fake, TOKEN, enrolled.id(), enrolled.secret());
		assertEquals("ready", link.connect(enrolled.socket()).get("type"));
		await(3000, () -> link.getReceived().stream().anyMatch(m -> "subscribe".equals(m.get("type"))));
		return link;
	}

	private static Map<String, Object> event(String event) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", "zigbee");
		result.put("event", event);
		result.put("ieee", IEEE);
		result.put("generation", Integer.valueOf(1));
		return result;
	}

	private static TLObject function(TLObject device, String key) {
		for (Object function : (Collection<?>) device.tValueByName("functions")) {
			if (key.equals(((TLObject) function).tValueByName("function"))) {
				return (TLObject) function;
			}
		}
		throw new AssertionError("No function " + key);
	}

	private static List<FakeControllerLink.Request> exportRequests(FakeControllerLink link) {
		return link.getRequests().stream().filter(r -> r.path().startsWith(EXPORT_PATH)).toList();
	}

	private static void awaitRead(String id, ZigbeeLive.Read before) throws InterruptedException {
		await(4000, () -> ZigbeeLive.lastRead(id) != null && ZigbeeLive.lastRead(id) != before);
		ControllerConnections.awaitKnowledgeBase();
	}

	private static void await(long millis, BooleanSupplier condition) throws InterruptedException {
		long end = System.currentTimeMillis() + millis;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				fail("Timed out.");
			}
			Thread.sleep(10);
		}
	}

	private static String text(com.top_logic.basic.util.ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeLiveProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
