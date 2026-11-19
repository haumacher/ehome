/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;

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
		Project project = ControllerEndpoint.project("Enrollment test");
		try {
			Person root = PersonManager.getManager().getRoot();
			Instant now = Instant.now();
			String code = Enrollment.createCode(project, root, now);
			assertTrue(code, code.matches("[" + Enrollment.ALPHABET + "]{4}-[" + Enrollment.ALPHABET + "]{4}"));
			assertEquals(code, project.getEnrollmentCode());
			assertEquals(Date.from(now.plus(Duration.ofMinutes(15))), project.getEnrollmentExpires());
			assertEquals(root, project.getEnrollmentCreatedBy());

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
			List<?> controllers = project.getControllers();
			assertEquals(1, controllers.size());
			Controller controller = (Controller) controllers.get(0);
			assertEquals("homecontrol-a1b2c3", controller.getName());
			assertEquals(id, controller.getControllerId());
			assertEquals(secret, controller.getSecret());
			assertEquals("Enrollment test", controller.getInstallation());
			assertEquals("0.1.0", controller.getFirmwareVersion());
			assertEquals("a9d0216", controller.getFirmwareBuild());
			assertNotNull(controller.getEnrolled());
			assertNull(controller.getLastContact());
			assertNull(controller.getHost());
			assertEquals(root, controller.getEnrolledBy());
			assertEquals("The first controller becomes the deployment target.", controller,
				project.getController());
			assertNull(project.getEnrollmentCode());
			assertNull(project.getEnrollmentExpires());

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
			assertEquals(2, project.getControllers().size());
			assertEquals("The deployment target stays.", controller, project.getController());

			// An expired code.
			Enrollment.createCode(project, root, Instant.now().minus(Duration.ofMinutes(16)));
			String expired = project.getEnrollmentCode();
			assertRefused(_endpoint.enroll("{\"code\": \"" + expired + "\", \"name\": \"late\"}"), 410,
				Enrollment.CODE_EXPIRED);
			assertEquals("An expired code stays and keeps answering 410.", expired, project.getEnrollmentCode());

			// A controller E-Home knows: 409, and the code is not consumed.
			String third = Enrollment.createCode(project, root, Instant.now());
			assertRefused(_endpoint.enroll("{\"code\": \"" + third + "\", \"name\": \"twice\", \"controller\": \"" + id
				+ "\"}"), 409, Enrollment.ENROLLED);
			assertEquals(third, project.getEnrollmentCode());
			assertEquals(2, project.getControllers().size());

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
			assertEquals("A refused attempt does not consume the code.", third, project.getEnrollmentCode());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The deployment target of a project offers its own controllers, the enrolled ones and the ones on
	 * the LAN, never a controller of another project (haumacher/ehome #151); an enrollment takes over a
	 * target that is not one of the project's own enrolled controllers (haumacher/ehome #69).
	 */
	public void testTargetOptions() throws Exception {
		Project project = ControllerEndpoint.project("Target test");
		Project other = ControllerEndpoint.project("Target test (other)");
		Controller lan;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			lan = (Controller) ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			lan.setName("homecontrol-lan");
			lan.setHost("192.0.2.1");
			lan.setPort(80);
			project.setControllers(List.of(lan));
			project.setController(lan);
			tx.commit();
		}
		try {
			Person root = PersonManager.getManager().getRoot();
			TLObject foreign = enroll(other, root, "homecontrol-foreign");
			assertEquals(foreign, other.getController());

			TLObject own = enroll(project, root, "homecontrol-own");
			assertEquals("An enrollment replaces a LAN controller as the target.", own,
				project.getController());
			TLObject second = enroll(project, root, "homecontrol-second");
			assertNotSame(second, own);
			assertEquals("The project's own target stays.", own, project.getController());

			TLStructuredTypePart part = (TLStructuredTypePart) TLModelUtil.findPart("ehome.project:Project#controller");
			OptionsByExpression.Config<?> config =
				(OptionsByExpression.Config<?>) part.getAnnotation(TLOptions.class).getGenerator();
			Collection<?> options = SearchExpression.asList(QueryExecutor.compile(config.getFunction()).execute(project));
			assertTrue(options.contains(own));
			assertTrue(options.contains(second));
			assertTrue(options.contains(lan));
			assertFalse("Another project's controller is no option.", options.contains(foreign));
			Collection<?> otherOptions =
				SearchExpression.asList(QueryExecutor.compile(config.getFunction()).execute(other));
			assertEquals("A controller on the LAN is offered to its own project only.", List.of(foreign),
				new ArrayList<>(otherOptions));
		} finally {
			SharedData.deleteProjects(List.of(project, other));
		}
	}

	private TLObject enroll(Project project, Person root, String name) throws Exception {
		String code = Enrollment.createCode(project, root, Instant.now());
		Map<String, Object> answer = _endpoint.enroll("{\"code\": \"" + code + "\", \"name\": \"" + name + "\"}");
		assertEquals(answer.toString(), Integer.valueOf(200), answer.get("status"));
		List<?> controllers = project.getControllers();
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
