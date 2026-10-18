/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import static test.de.haumacher.ehome.connection.TestInstallationKey.await;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.runtime.FakeController;
import test.de.haumacher.ehome.runtime.FakeControllerLink;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Enrollment;
import de.haumacher.ehome.connection.InstallationKeys;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.runtime.Deployer;

/**
 * Probe of the key rotation (homecontrol issue #35) composed with several controllers of one project:
 * the rotation must never leave a device without a key E-Home signs with. Two controllers hold the
 * key; a third enrolled during the rotation gets the new key at once; when the first accepts the new
 * key, the old one stays the installation key while the second still holds it, and each deployment
 * is signed with the key its controller holds; a renewal is refused while a controller holds the
 * pending key; when the second accepts, the old key is forgotten. A device refusing the offered key
 * (<code>ehome.bad_key</code>) is shown with its reason.
 */
@SuppressWarnings("javadoc")
public class TestInstallationKeyProbe extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

	private ControllerEndpoint _endpoint;

	private FakeController _one;

	private FakeController _two;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_endpoint = new ControllerEndpoint();
		_one = new FakeController(TOKEN, 262144);
		_two = new FakeController(TOKEN, 262144);
	}

	@Override
	protected void tearDown() throws Exception {
		_one.stop();
		_two.stop();
		_endpoint.stop();
		super.tearDown();
	}

	public void testRotationWithSeveralControllers() throws Exception {
		TLObject project = ControllerEndpoint.project("Key probe");
		try {
			Map<String, Object> a = enroll(project, "one");
			Map<String, Object> b = enroll(project, "two");
			Keys old = InstallationKeys.key(project);
			_one.setKey((String) a.get("public_key"));
			_two.setKey((String) b.get("public_key"));
			List<?> controllers = (List<?>) project.tValueByName("controllers");
			TLObject one = (TLObject) controllers.get(0);
			TLObject two = (TLObject) controllers.get(1);
			FakeControllerLink linkOne = connect(_one, a);
			FakeControllerLink linkTwo = connect(_two, b);
			ControllerConnection connectionOne = ControllerConnections.get((String) a.get("controller"));
			ControllerConnection connectionTwo = ControllerConnections.get((String) b.get("controller"));

			Keys fresh = InstallationKeys.renew(project);
			await(() -> fresh.publicKey().equals(linkOne.getPendingKey()) && fresh.publicKey().equals(linkTwo.getPendingKey()));

			// Enrolled during the rotation: the new key at once, no acceptance needed.
			Map<String, Object> c = enroll(project, "three");
			assertEquals(fresh.publicKey(), c.get("public_key"));

			// The first accepts: the old key stays while the second holds it.
			linkOne.acceptKey();
			await(() -> fresh.fingerprint().equals(connectionOne.deviceKey()));
			ControllerConnections.awaitKnowledgeBase();
			assertEquals(old, InstallationKeys.key(project));
			assertEquals(fresh, InstallationKeys.newKey(project));
			assertEquals(fresh, InstallationKeys.signer(project, one));
			assertEquals(old, InstallationKeys.signer(project, two));
			assertNotNull("A renewal would orphan the key the first holds.", InstallationKeys.renewVeto(project));
			try {
				InstallationKeys.renew(project);
				fail("Refused while a controller holds the pending key.");
			} catch (TopLogicException ex) {
				assertEquals(InstallationKeys.renewVeto(project), ex.getErrorKey());
			}

			// Each deployment is signed with the key its controller holds, and accepted.
			TLObject toOne = Deployer.deploy(project, NOW);
			assertEquals(toOne.tValueByName("message").toString(), Boolean.TRUE, toOne.tValueByName("accepted"));
			assertEquals(fresh.fingerprint(), toOne.tValueByName("keyFingerprint"));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("controller", two);
				tx.commit();
			}
			TLObject toTwo = Deployer.deploy(project, NOW);
			assertEquals(toTwo.tValueByName("message").toString(), Boolean.TRUE, toTwo.tValueByName("accepted"));
			assertEquals(old.fingerprint(), toTwo.tValueByName("keyFingerprint"));

			// A device refusing the offered key: shown with its reason.
			linkTwo.sendText("{\"type\": \"error\", \"code\": \"ehome.bad_key\", \"error\": \"no room for the key\"}");
			await(() -> connectionTwo.keyError() != null);
			assertTrue(InstallationKeys.controllerState(two), InstallationKeys.controllerState(two).contains("no room for the key"));

			// The second accepts: the old key is forgotten.
			linkTwo.acceptKey();
			await(() -> fresh.fingerprint().equals(connectionTwo.deviceKey()));
			ControllerConnections.awaitKnowledgeBase();
			assertEquals(fresh, InstallationKeys.key(project));
			assertNull(InstallationKeys.newKey(project));
			assertNull(InstallationKeys.renewVeto(project));
			linkOne.close();
			linkTwo.close();
		} finally {
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(List.of(project));
		}
	}

	private FakeControllerLink connect(FakeController fake, Map<String, Object> enrolled) throws Exception {
		FakeControllerLink link = new FakeControllerLink(fake, TOKEN, (String) enrolled.get("controller"),
			(String) enrolled.get("secret"));
		assertEquals("ready", link.connect(URI.create((String) enrolled.get("socket"))).get("type"));
		return link;
	}

	private Map<String, Object> enroll(TLObject project, String name) {
		Enrollment.createCode(project, null, Instant.now());
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("code", project.tValueByName("enrollmentCode"));
		request.put("name", name);
		Enrollment.Answer answer = Enrollment.enroll(request, _endpoint.base(), Instant.now());
		assertEquals(answer.body().toString(), 200, answer.status());
		return answer.body();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestInstallationKeyProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
