/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.element.config.annotation.TLOptions;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.search.providers.OptionsByExpression;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerServlet;
import de.haumacher.ehome.connection.Enrollment;
import de.haumacher.ehome.connection.RateLimit;

/**
 * The enrollment of controllers with a project's code (haumacher/ehome #54) through
 * <code>POST /api/controllers/enroll</code> of an in-process {@link ControllerEndpoint}: a code is
 * created, used once (the controller under the project with its fields, the code consumed), refused
 * when used again (404), expired (410), presented by a controller E-Home knows (409, the code stays);
 * a name enrolled twice makes two controllers; the code is read in any spelling; the socket's URL
 * follows a reverse proxy's forwarded headers; malformed requests and too many attempts are refused;
 * the deployment target offers only the project's own and the LAN's controllers (#69).
 */
@SuppressWarnings("javadoc")
public class TestControllerEnrollment extends BasicTestCase {

	private ControllerEndpoint _endpoint;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_endpoint = new ControllerEndpoint();
	}

	@Override
	protected void tearDown() throws Exception {
		_endpoint.stop();
		ControllerServlet.setRateLimit(new RateLimit(ControllerServlet.ATTEMPTS_PER_ADDRESS,
			ControllerServlet.ATTEMPTS_TOTAL, 60_000));
		super.tearDown();
	}

	public void testEnrollment() throws Exception {
		TLObject project = ControllerEndpoint.project("Enrollment test");
		try {
			Person root = PersonManager.getManager().getRoot();
			Instant now = Instant.now();
			String code = Enrollment.createCode(project, root, now);
			assertTrue(code, code.matches("[" + Enrollment.ALPHABET + "]{4}-[" + Enrollment.ALPHABET + "]{4}"));
			assertEquals(code, project.tValueByName("enrollmentCode"));
			assertEquals(Date.from(now.plus(Duration.ofMinutes(15))), project.tValueByName("enrollmentExpires"));
			assertEquals(root, project.tValueByName("enrollmentCreatedBy"));

			// The round trip: the controller under the project with its fields, the code consumed.
			Map<String, Object> answer = _endpoint.enroll(
				"{\"code\": \"" + code + "\", \"name\": \"homecontrol-a1b2c3\", \"version\": \"0.1.0\", \"build\": \"a9d0216\"}");
			assertEquals(answer.toString(), Integer.valueOf(200), answer.get("status"));
			String id = (String) answer.get("controller");
			assertTrue(id, id.matches("[0-9a-f]{32}"));
			assertEquals("Enrollment test", answer.get("installation"));
			String secret = (String) answer.get("secret");
			assertTrue(secret.matches("[0-9a-f]{64}"));
			assertEquals(_endpoint.base().replace("http:", "ws:") + "/api/controllers/" + id + "/socket",
				answer.get("socket"));
			List<?> controllers = (List<?>) project.tValueByName("controllers");
			assertEquals(1, controllers.size());
			TLObject controller = (TLObject) controllers.get(0);
			assertEquals("homecontrol-a1b2c3", controller.tValueByName("name"));
			assertEquals(id, controller.tValueByName("controllerId"));
			assertEquals(secret, controller.tValueByName("secret"));
			assertEquals("Enrollment test", controller.tValueByName("installation"));
			assertEquals("0.1.0", controller.tValueByName("firmwareVersion"));
			assertEquals("a9d0216", controller.tValueByName("firmwareBuild"));
			assertNotNull(controller.tValueByName("enrolled"));
			assertNull(controller.tValueByName("lastContact"));
			assertNull(controller.tValueByName("host"));
			assertEquals(root, controller.tValueByName("enrolledBy"));
			assertEquals("The first controller becomes the deployment target.", controller,
				project.tValueByName("controller"));
			assertNull(project.tValueByName("enrollmentCode"));
			assertNull(project.tValueByName("enrollmentExpires"));

			// A used code is unknown.
			assertRefused(_endpoint.enroll("{\"code\": \"" + code + "\", \"name\": \"again\"}"), 404,
				Enrollment.CODE_UNKNOWN);

			// The same name again, the code in another spelling: a second controller.
			String second = Enrollment.createCode(project, root, Instant.now());
			Map<String, Object> again = _endpoint.enroll("{\"code\": \"" + second.toLowerCase().replace("-", " ")
				+ "\", \"name\": \"homecontrol-a1b2c3\"}", "X-Forwarded-Proto", "https", "X-Forwarded-Host",
				"ehome.example");
			assertEquals(again.toString(), Integer.valueOf(200), again.get("status"));
			assertFalse(id.equals(again.get("controller")));
			assertEquals("wss://ehome.example/ehome/api/controllers/" + again.get("controller") + "/socket",
				again.get("socket"));
			assertEquals(2, ((List<?>) project.tValueByName("controllers")).size());
			assertEquals("The deployment target stays.", controller, project.tValueByName("controller"));

			// An expired code.
			Enrollment.createCode(project, root, Instant.now().minus(Duration.ofMinutes(16)));
			String expired = (String) project.tValueByName("enrollmentCode");
			assertRefused(_endpoint.enroll("{\"code\": \"" + expired + "\", \"name\": \"late\"}"), 410,
				Enrollment.CODE_EXPIRED);
			assertEquals("An expired code stays and keeps answering 410.", expired, project.tValueByName("enrollmentCode"));

			// A controller E-Home knows: 409, and the code is not consumed.
			String third = Enrollment.createCode(project, root, Instant.now());
			assertRefused(_endpoint.enroll("{\"code\": \"" + third + "\", \"name\": \"twice\", \"controller\": \"" + id
				+ "\"}"), 409, Enrollment.ENROLLED);
			assertEquals(third, project.tValueByName("enrollmentCode"));
			assertEquals(2, ((List<?>) project.tValueByName("controllers")).size());

			// Malformed requests.
			assertRefused(_endpoint.enroll("no json"), 400, Enrollment.BAD_REQUEST);
			assertRefused(_endpoint.enroll("{\"name\": \"no code\"}"), 400, Enrollment.BAD_REQUEST);
			assertRefused(_endpoint.enroll("{\"code\": \"ABCD-EFG0\"}"), 404, Enrollment.CODE_UNKNOWN);
			assertEquals(404, _endpoint.get(ControllerServlet.PATH + "/other"));
			assertEquals(426, _endpoint.get(ControllerServlet.PATH + "/" + id + "/socket"));

			// The rate limit.
			ControllerServlet.setRateLimit(new RateLimit(2, 100, 60_000));
			assertRefused(_endpoint.enroll("{\"code\": \"ABCD-EFGH\"}"), 404, Enrollment.CODE_UNKNOWN);
			assertRefused(_endpoint.enroll("{\"code\": \"ABCD-EFGH\"}"), 404, Enrollment.CODE_UNKNOWN);
			assertRefused(_endpoint.enroll("{\"code\": \"" + third + "\"}"), 429, "ehome.rate_limited");
			assertEquals("A refused attempt does not consume the code.", third, project.tValueByName("enrollmentCode"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The deployment target of a project offers its own controllers and the controllers on the LAN,
	 * never an enrollment of another project; an enrollment takes over a target that is not one of
	 * the project's own controllers (haumacher/ehome #69).
	 */
	public void testTargetOptions() throws Exception {
		TLObject project = ControllerEndpoint.project("Target test");
		TLObject other = ControllerEndpoint.project("Target test (other)");
		TLObject lan;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			lan = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			lan.tUpdateByName("name", "homecontrol-lan");
			lan.tUpdateByName("host", "192.0.2.1");
			lan.tUpdateByName("port", Integer.valueOf(80));
			project.tUpdateByName("controller", lan);
			tx.commit();
		}
		try {
			Person root = PersonManager.getManager().getRoot();
			TLObject foreign = enroll(other, root, "homecontrol-foreign");
			assertEquals(foreign, other.tValueByName("controller"));

			TLObject own = enroll(project, root, "homecontrol-own");
			assertEquals("An enrollment replaces a LAN controller as the target.", own,
				project.tValueByName("controller"));
			TLObject second = enroll(project, root, "homecontrol-second");
			assertNotSame(second, own);
			assertEquals("The project's own target stays.", own, project.tValueByName("controller"));

			TLStructuredTypePart part = (TLStructuredTypePart) TLModelUtil.findPart("ehome.project:Project#controller");
			OptionsByExpression.Config<?> config =
				(OptionsByExpression.Config<?>) part.getAnnotation(TLOptions.class).getGenerator();
			Collection<?> options = SearchExpression.asList(QueryExecutor.compile(config.getFunction()).execute(project));
			assertTrue(options.contains(own));
			assertTrue(options.contains(second));
			assertTrue(options.contains(lan));
			assertFalse("Another project's controller is no option.", options.contains(foreign));
		} finally {
			SharedData.deleteProjects(List.of(project, other));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				lan.tDelete();
				tx.commit();
			}
		}
	}

	private TLObject enroll(TLObject project, Person root, String name) throws Exception {
		String code = Enrollment.createCode(project, root, Instant.now());
		Map<String, Object> answer = _endpoint.enroll("{\"code\": \"" + code + "\", \"name\": \"" + name + "\"}");
		assertEquals(answer.toString(), Integer.valueOf(200), answer.get("status"));
		List<?> controllers = (List<?>) project.tValueByName("controllers");
		return (TLObject) controllers.get(controllers.size() - 1);
	}

	public void testCodes() {
		for (int n = 0; n < 100; n++) {
			String code = Enrollment.newCode();
			assertEquals(code, Enrollment.normalize(code));
			assertFalse(code, code.matches(".*[01OIL].*"));
		}
		assertEquals("ABCD-EFGH", Enrollment.normalize(" abcd efgh "));
		assertEquals("ABCD-EFGH", Enrollment.normalize("ABCDEFGH"));
		assertNull(Enrollment.normalize("ABCD-EFG"));
		assertNull(Enrollment.normalize("ABCD-EFG1"));
		assertEquals("wss://ehome.example/api/controllers/x/socket", Enrollment.socketUrl("https://ehome.example/", "x"));
	}

	private static void assertRefused(Map<String, Object> answer, int status, String code) {
		assertEquals(answer.toString(), Integer.valueOf(status), answer.get("status"));
		assertEquals(answer.toString(), code, answer.get("code"));
		assertNotNull(answer.get("error"));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestControllerEnrollment.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
