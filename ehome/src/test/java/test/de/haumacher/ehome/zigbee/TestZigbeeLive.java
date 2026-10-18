/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
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
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeLive;
import de.haumacher.ehome.zigbee.ZigbeeStatus;

/**
 * The live import of Zigbee devices (haumacher/ehome #64): a {@link FakeController} enrolled for a
 * project connects over the in-process {@link ControllerEndpoint} and sends <code>zigbee</code>
 * events; E-Home reads the export over the socket and imports it with the same code as "Read".
 *
 * <p>
 * An <code>interviewed</code> event reads the export (<code>GET /api/zigbee/export?from=0</code>)
 * within a second and the project gets the motion sensor of the fixture with its functions and the
 * controller the network facts; a burst of three events is one further read, which takes the
 * changed permit time; a <code>permit</code> event changes only the live state (the countdown), not
 * the devices; an event of a controller no project deploys to reads nothing; an export refused with
 * 503 leaves the devices as they were and the status line names the failure.
 * </p>
 *
 * <p>
 * Takes about 5 s: the debounce interval of the live import itself ({@link ZigbeeLive#MIN_INTERVAL},
 * 2 s) separates the three reads; the waits are on the outcome, never fixed sleeps longer than that.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeLive extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

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

	public void testLiveImport() throws Exception {
		String export = Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8);
		_fake.setZigbeeExport(export, 16);
		TLObject project = ControllerEndpoint.project("Zigbee live");
		TLObject orphanProject = ControllerEndpoint.project("Zigbee live orphan");
		List<TLObject> controllers = new ArrayList<>();
		try {
			Enrolled enrolled = enroll(project, "homecontrol-live");
			Enrolled orphan = enroll(orphanProject, "homecontrol-orphan");
			controllers.add(enrolled.controller());
			controllers.add(orphan.controller());
			// The orphan stays enrolled under its project, which no longer deploys to it.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				orphanProject.tUpdateByName("controller", null);
				tx.commit();
			}
			FakeControllerLink link = connect(enrolled);
			FakeControllerLink orphanLink = connect(orphan);
			String id = enrolled.id();

			// An interview finished: the export is read over the socket and imported.
			link.send(event("interviewed", IEEE));
			await(3000, () -> exportRequests(link).size() >= 1);
			assertEquals(EXPORT_PATH + "?from=0", exportRequests(link).get(0).path());
			awaitRead(id, null);
			List<TLObject> devices = ZigbeeImport.devices(project);
			assertEquals(1, devices.size());
			TLObject device = devices.get(0);
			assertEquals(IEEE, device.tValueByName("ieee"));
			assertEquals(5, ((List<?>) device.tValueByName("functions")).size());
			TLObject controller = enrolled.controller();
			assertEquals(Boolean.TRUE, controller.tValueByName("zigbeeNetworkUp"));
			assertEquals(4660L, ((Number) controller.tValueByName("zigbeePanId")).longValue());
			assertEquals(15L, ((Number) controller.tValueByName("zigbeeChannel")).longValue());
			assertEquals("00124b0000000001", controller.tValueByName("zigbeeExtendedPanId"));
			assertEquals(0L, ((Number) controller.tValueByName("zigbeePermitJoin")).longValue());
			assertEquals(new Date(1792332189000L), controller.tValueByName("zigbeeExported"));
			assertEquals(Boolean.TRUE, controller.tValueByName("zigbeeReadLive"));
			assertEquals(text(I18NConstants.NETWORK_CLOSED__PAN_CHANNEL.fill("0x1234", Long.valueOf(15))),
				ZigbeeStatus.networkState(project, Instant.now()));
			String live = text(I18NConstants.READ_LIVE__TIME.fill("#"));
			String read = ZigbeeStatus.readState(project);
			assertTrue(read, read.endsWith(live.substring(live.indexOf('#') + 1)));
			assertEquals(text(I18NConstants.DEVICE_PAIRED), ZigbeeStatus.deviceState(device));
			ZigbeeLive.Read first = ZigbeeLive.lastRead(id);

			// A burst of three events within 500 ms is one further read; the controller opened its
			// network meanwhile. A permit event changes only the live state; the orphan's event reads nothing.
			_fake.setZigbeeExport(export.replace("\"permit_join_s\": 0", "\"permit_join_s\": 120"), 16);
			link.send(event("left", IEEE));
			link.send(event("changed", IEEE));
			link.send(event("joined", IEEE));
			orphanLink.send(event("interviewed", IEEE));
			Map<String, Object> permit = new LinkedHashMap<>();
			permit.put("type", "zigbee");
			permit.put("event", "permit");
			permit.put("permit_join_s", Integer.valueOf(200));
			permit.put("generation", Integer.valueOf(7));
			link.send(permit);
			await(1000, () -> ZigbeeLive.permit(id) != null && ZigbeeLive.permit(id).seconds() == 200);
			String open = ZigbeeStatus.networkState(project, Instant.now());
			assertTrue(open, open.equals(text(I18NConstants.NETWORK_OPEN__PAN_CHANNEL_SECONDS.fill("0x1234",
				Long.valueOf(15), Long.valueOf(200))))
				|| open.equals(text(I18NConstants.NETWORK_OPEN__PAN_CHANNEL_SECONDS.fill("0x1234", Long.valueOf(15),
					Long.valueOf(199)))));
			assertEquals("Interviewing after the join.", text(I18NConstants.DEVICE_INTERVIEWING),
				ZigbeeStatus.deviceState(device));
			awaitRead(id, first);
			assertEquals(2, exportRequests(link).size());
			assertEquals(120L, ((Number) controller.tValueByName("zigbeePermitJoin")).longValue());
			assertTrue("No project deploys to the orphan.", exportRequests(orphanLink).isEmpty());

			// The export is refused: the devices stay, the status line names the failure.
			_fake.refuseZigbeeExport(503);
			link.send(event("interviewed", IEEE));
			await(4000, () -> ZigbeeLive.failure(id) != null);
			assertEquals(3, exportRequests(link).size());
			assertEquals("Paired once the interview finished.", text(I18NConstants.DEVICE_PAIRED),
				ZigbeeStatus.deviceState(device));
			ControllerConnections.awaitKnowledgeBase();
			assertEquals(List.of(device), ZigbeeImport.devices(project));
			assertEquals(5, ((List<?>) device.tValueByName("functions")).size());
			String problem = ZigbeeStatus.liveProblem(project);
			assertNotNull(problem);
			assertTrue(problem, problem.contains("503") && problem.contains("homecontrol-live"));
			assertTrue("No project deploys to the orphan.", exportRequests(orphanLink).isEmpty());
		} finally {
			for (TLObject controller : controllers) {
				Controllers.forget(controller);
			}
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(List.of(project, orphanProject));
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

	private static Map<String, Object> event(String event, String ieee) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", "zigbee");
		result.put("event", event);
		result.put("ieee", ieee);
		result.put("generation", Integer.valueOf(3));
		return result;
	}

	private static List<FakeControllerLink.Request> exportRequests(FakeControllerLink link) {
		return link.getRequests().stream().filter(r -> r.path().startsWith(EXPORT_PATH)).toList();
	}

	/** Waits for a read after the given one, then for its commit to be visible. */
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeLive.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
