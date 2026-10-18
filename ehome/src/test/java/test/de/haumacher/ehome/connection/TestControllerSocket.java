/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.runtime.FakeController;
import test.de.haumacher.ehome.runtime.FakeControllerLink;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Controllers;
import de.haumacher.ehome.connection.Enrollment;
import de.haumacher.ehome.connection.I18NConstants;
import de.haumacher.ehome.monitor.BusLink;
import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.monitor.LinkEvent;
import de.haumacher.ehome.monitor.SocketBusLink;
import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.ImageComparison;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.ZigbeeController;

/**
 * The socket of an enrolled controller (haumacher/ehome #54, homecontrol <code>spec/api.md</code>, "The
 * socket"): a {@link FakeController} connects to an in-process {@link ControllerEndpoint} with the JDK's
 * WebSocket client ({@link FakeControllerLink}).
 *
 * <p>
 * The authentication: a wrong HMAC, a replayed one (the HMAC of an earlier connection's nonce) and an
 * unknown id are refused with the close code 1008; the right one gets <code>ready</code> and the
 * subscription. The feed: the firmware the controller reports is stored, the controller is shown
 * online. The requests: a status, a 10 KB body as <code>length</code> and binary frames, a streamed
 * response, two concurrent requests one after the other; the deployment, the image comparison and the
 * Zigbee pairing and reading through the connection unchanged; the group monitor's link over it. The
 * end: the controller disconnects and is shown offline with its last contact, and every action refuses
 * naming it; forgetting the controller closes its connection and refuses it from then on.
 * </p>
 *
 * <p>
 * All cases run in one test: they share the enrollment and the connections.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestControllerSocket extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private ControllerEndpoint _endpoint;

	private FakeController _fake;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_endpoint = new ControllerEndpoint();
		_fake = new FakeController(TOKEN, 262144);
	}

	@Override
	protected void tearDown() throws Exception {
		_fake.stop();
		_endpoint.stop();
		super.tearDown();
	}

	public void testSocket() throws Exception {
		TLObject project = ControllerEndpoint.project("Socket test");
		try {
			Enrollment.createCode(project, null, Instant.now());
			Map<String, Object> request = new LinkedHashMap<>();
			request.put("code", project.tValueByName("enrollmentCode"));
			request.put("name", "homecontrol-fake");
			Enrollment.Answer enrolled = Enrollment.enroll(request, _endpoint.base(), Instant.now());
			assertEquals(200, enrolled.status());
			String id = (String) enrolled.body().get("controller");
			String secret = (String) enrolled.body().get("secret");
			URI socket = URI.create((String) enrolled.body().get("socket"));
			TLObject controller = (TLObject) project.tValueByName("controller");
			assertEquals(id, controller.tValueByName("controllerId"));

			// Never connected: every action refuses, naming that.
			assertEquals(de.haumacher.ehome.runtime.I18NConstants.ERROR_NEVER_CONNECTED__CONTROLLER.fill("homecontrol-fake"),
				Deployer.veto(project));
			assertEquals(text(I18NConstants.STATE_NEVER_CONNECTED), Controllers.state(controller));

			// A wrong HMAC.
			FakeControllerLink wrong = link(id, secret);
			wrong.setMac("00".repeat(32));
			assertRefusal(wrong.connect(socket), ControllerConnection.REFUSED_AUTH);
			assertEquals(1008, wrong.awaitClose());
			assertNull(ControllerConnections.get(id));

			// An unknown id.
			FakeControllerLink unknown = link("0".repeat(32), secret);
			assertRefusal(unknown.connect(URI.create(socket.toString().replace(id, "0".repeat(32)))),
				ControllerConnection.REFUSED_UNKNOWN);

			// The right HMAC: ready, the subscription, the firmware stored, online.
			FakeControllerLink link = link(id, secret);
			Map<String, Object> ready = link.connect(socket);
			assertEquals("ready", ready.get("type"));
			assertEquals("Socket test", ready.get("installation"));
			await(() -> link.getReceived().stream().anyMatch(m -> "subscribe".equals(m.get("type"))));
			ControllerConnection connection = ControllerConnections.get(id);
			assertNotNull(connection);
			ControllerConnections.awaitKnowledgeBase();
			assertEquals("0.1.0-fake", controller.tValueByName("firmwareVersion"));
			assertEquals("fake123", controller.tValueByName("firmwareBuild"));
			Date connected = (Date) controller.tValueByName("lastContact");
			assertNotNull(connected);
			assertTrue(Controllers.online(controller));
			assertEquals(text(I18NConstants.STATE_ONLINE__SINCE.fill(format(Date.from(connection.since())))),
				Controllers.state(controller));
			assertNull("Connected: deployable without host and token.", Deployer.veto(project));

			// A status message of the feed: kept, a new firmware stored.
			Map<String, Object> device = new LinkedHashMap<>();
			device.put("name", "homecontrol-fake");
			device.put("version", "0.1.1-fake");
			device.put("build", "fake124");
			Map<String, Object> status = new LinkedHashMap<>();
			status.put("type", "status");
			status.put("device", device);
			link.send(status);
			await(() -> connection.status().get("device") != null);
			ControllerConnections.awaitKnowledgeBase();
			assertEquals("0.1.1-fake", controller.tValueByName("firmwareVersion"));
			assertEquals("fake124", controller.tValueByName("firmwareBuild"));

			// A replayed authentication: the HMAC of the nonce of the connection above.
			FakeControllerLink replay = link(id, secret);
			replay.setMac(link.getLastMac());
			assertRefusal(replay.connect(socket), ControllerConnection.REFUSED_AUTH);
			assertSame("The refused connection leaves the accepted one.", connection, ControllerConnections.get(id));

			// A request.
			Answer answer = connection.request("GET", "/api/status", null, Duration.ofSeconds(5));
			assertEquals(200, answer.status());
			assertEquals("homecontrol-fake", ((Map<?, ?>) answer.body().get("device")).get("name"));

			// A body of 10 KB: length and binary frames, reassembled by the controller.
			String padding = "x".repeat(10 * 1024);
			byte[] large = ("{\"pad\": \"" + padding + "\"}").getBytes(StandardCharsets.UTF_8);
			int framesBefore = link.getBinaryFrames();
			answer = connection.request("PUT", "/api/image", large, Duration.ofSeconds(5));
			FakeControllerLink.Request put = last(link.getRequests());
			assertEquals("PUT", put.method());
			assertTrue(put.binary());
			assertTrue(java.util.Arrays.equals(large, put.body()));
			assertEquals(3, link.getBinaryFrames() - framesBefore);
			assertEquals(422, answer.status());
			assertEquals("image.invalid", answer.string("code"));

			// A streamed response: the stored image byte for byte.
			String stored = "{\"image\": {\"projectId\": \"other\", \"revision\": 3}, \"pad\": \"" + padding + "\"}\n";
			_fake.store(stored);
			answer = connection.request("GET", "/api/image", null, Duration.ofSeconds(5));
			assertEquals(200, answer.status());
			assertEquals(stored, answer.text());
			assertEquals("other", ((Map<?, ?>) answer.body().get("image")).get("projectId"));

			// Two concurrent requests: one after the other.
			link.setDelay(300);
			ExecutorService threads = Executors.newFixedThreadPool(2);
			try {
				Callable<Answer> call = () -> connection.request("GET", "/api/status", null, Duration.ofSeconds(5));
				Future<Answer> first = threads.submit(call);
				Future<Answer> second = threads.submit(call);
				assertEquals(200, first.get().status());
				assertEquals(200, second.get().status());
			} finally {
				threads.shutdown();
			}
			assertEquals(0, link.getOverlaps());
			link.setDelay(0);

			// The deployment, the comparison and the Zigbee coordinator through the connection.
			TLObject deployment = Deployer.deploy(project, NOW);
			assertEquals(deployment.tValueByName("message").toString(), Boolean.TRUE, deployment.tValueByName("accepted"));
			assertEquals(RuntimeImage.build(project, 1, NOW).getText(), _fake.getStoredText());
			assertTrue(last(link.getRequests()).binary());
			assertEquals(ImageComparison.State.IDENTICAL, ImageComparison.compare(project, NOW).state());
			_fake.setZigbeeExport("{\"format\": \"homecontrol-zigbee-export\", \"version\": 1, \"controller\": "
				+ "\"homecontrol-fake\", \"devices\": [], \"next\": null}", 16);
			assertNotNull(ZigbeeController.permit(project, 254));
			assertEquals(Integer.valueOf(254), _fake.getPermitSeconds());
			assertNotNull(ZigbeeController.read(project));

			// The group monitor over the connection.
			List<Object> events = new ArrayList<>();
			SocketBusLink monitor = SocketBusLink.open("homecontrol-fake", id, Duration.ofSeconds(5));
			try {
				monitor.setListener(new BusLink.Listener() {
					@Override
					public void received(BusTelegram telegram) {
						synchronized (events) {
							events.add(telegram);
						}
					}

					@Override
					public void event(LinkEvent event) {
						synchronized (events) {
							events.add(event);
						}
					}

					@Override
					public void closed(String reason) {
						// Not expected.
					}
				});
				link.sendText("{\"type\": \"monitor\", \"now_ms\": 1000, \"next\": 2, \"events\": ["
					+ "{\"seq\": 0, \"ms\": 900, \"kind\": \"knx\", \"dir\": \"rx\", \"src\": \"1.1.10\", \"dst\": \"1/2/3\","
					+ " \"service\": \"write\", \"data\": \"01\", \"short\": true, \"prio\": 3, \"hops\": 6},"
					+ "{\"seq\": 1, \"ms\": 950, \"kind\": \"rule\", \"rule\": \"r\", \"name\": \"hall.light\", \"value\": true}]}");
				await(() -> {
					synchronized (events) {
						return events.size() == 2;
					}
				});
				BusTelegram telegram = (BusTelegram) events.get(0);
				assertEquals((1 << 11) | (2 << 8) | 3, telegram.destination());
				assertEquals(LinkEvent.Kind.RULE, ((LinkEvent) events.get(1)).kind());
				assertNull(monitor.problem());
			} finally {
				monitor.close();
			}

			// The controller disconnects: offline with its last contact, every action refuses naming it.
			link.close();
			await(() -> ControllerConnections.get(id) == null);
			ControllerConnections.awaitKnowledgeBase();
			Date lastContact = (Date) controller.tValueByName("lastContact");
			assertFalse(lastContact.before(connected));
			assertFalse(Controllers.online(controller));
			assertEquals(text(I18NConstants.STATE_OFFLINE__LAST_CONTACT.fill(format(lastContact))),
				Controllers.state(controller));
			ResKey offline = Deployer.offline(controller);
			assertEquals(offline, Deployer.veto(project));
			assertTrue(text(offline), text(offline).contains(format(lastContact)));
			try {
				Deployer.deploy(project, NOW);
				fail("An offline controller is refused.");
			} catch (TopLogicException ex) {
				assertEquals(offline, ex.getErrorKey());
			}
			try {
				ZigbeeController.permit(project, 254);
				fail("An offline controller is refused.");
			} catch (TopLogicException ex) {
				assertEquals(offline, ex.getErrorKey());
			}

			// Forgotten: the connection closes and is refused from then on.
			FakeControllerLink back = link(id, secret);
			assertEquals("ready", back.connect(socket).get("type"));
			Controllers.forget(controller);
			assertEquals(1008, back.awaitClose());
			assertFalse(controller.tValid());
			assertNull(project.tValueByName("controller"));
			assertEquals("The deployment keeps its target text.", Boolean.TRUE, deployment.tValueByName("accepted"));
			assertRefusal(link(id, secret).connect(socket), ControllerConnection.REFUSED_UNKNOWN);
		} finally {
			ControllerConnections.awaitKnowledgeBase(); // the writer records the closes before the project goes
			SharedData.deleteProjects(List.of(project));
		}
	}

	private FakeControllerLink link(String id, String secret) {
		return new FakeControllerLink(_fake, TOKEN, id, secret);
	}

	private static void assertRefusal(Map<String, Object> answer, String code) {
		assertEquals(answer.toString(), "refused", answer.get("type"));
		assertEquals(answer.toString(), code, answer.get("code"));
	}

	private static <T> T last(List<T> list) {
		return list.get(list.size() - 1);
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long end = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				fail("Timed out.");
			}
			Thread.sleep(10);
		}
	}

	private static String format(Date date) {
		return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
			Resources.getInstance().getLocale()).format(date);
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestControllerSocket.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
