/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Controllers;
import de.haumacher.ehome.connection.Enrollment;
import de.haumacher.ehome.connection.InstallationKeys;
import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Probe of the installation key's identity (homecontrol #35): the key belongs to the project, not to a
 * controller, so forgetting the only controller keeps it and the next enrollment hands out the same
 * public key; two projects never share a key; the signature E-Home computes for an exported image
 * verifies under the project's public key and against the controller's fixture vector.
 */
@SuppressWarnings("javadoc")
public class TestInstallationKeyIdentityProbe extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

	private ControllerEndpoint _endpoint;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_endpoint = new ControllerEndpoint();
	}

	@Override
	protected void tearDown() throws Exception {
		_endpoint.stop();
		super.tearDown();
	}

	public void testKeyBelongsToTheProject() throws Exception {
		TLObject one = ControllerEndpoint.project("Key identity one");
		TLObject two = ControllerEndpoint.project("Key identity two");
		try {
			String firstKey = (String) enroll(one, "a").body().get("public_key");
			String otherKey = (String) enroll(two, "b").body().get("public_key");
			assertNotNull(firstKey);
			assertNotNull(otherKey);
			assertFalse("Two projects never share a key.", firstKey.equals(otherKey));
			Keys key = InstallationKeys.key(one);
			assertEquals(firstKey, key.publicKey());

			// Forget the only controller: the project's key stays, and the next controller gets the same one.
			TLObject controller = (TLObject) one.tValueByName("controller");
			Controllers.forget(controller);
			ControllerConnections.awaitKnowledgeBase();
			assertEquals(key, InstallationKeys.key(one));
			assertEquals(firstKey, enroll(one, "a again").body().get("public_key"));

			// The signature over the exported image bytes verifies under the project's key and nothing else.
			RuntimeImage image = RuntimeImage.build(one, 1, NOW);
			byte[] bytes = image.getText().getBytes(StandardCharsets.UTF_8);
			String signature = Signatures.signImage(key.seed(), bytes);
			assertEquals(128, signature.length());
			assertTrue(Signatures.verifyImage(key.publicKey(), bytes, signature));
			assertFalse(Signatures.verifyImage(otherKey, bytes, signature));
			byte[] altered = (image.getText() + " ").getBytes(StandardCharsets.UTF_8);
			assertFalse("A single byte more and the signature is void.", Signatures.verifyImage(key.publicKey(), altered, signature));

			// The controller's own fixture: its signature verifies under the fixture key.
			byte[] fixture = Files.readAllBytes(Path.of("src/test/fixtures/homecontrol/images/example.json"));
			String fixtureSig = Files.readString(Path.of("src/test/fixtures/homecontrol/images/example.json.sig"),
				StandardCharsets.UTF_8).trim();
			assertTrue(Signatures.verifyImage("13833fbfa81080cdb4c1210131a5bb3e98f17675236e66124aed84cc490bccb1", fixture, fixtureSig));
			assertEquals("ce7d 4a41 a476 9ee3",
				Signatures.fingerprint("13833fbfa81080cdb4c1210131a5bb3e98f17675236e66124aed84cc490bccb1"));
		} finally {
			for (TLObject project : List.of(one, two)) {
				for (Object controller : (Iterable<?>) project.tValueByName("controllers")) {
					Controllers.forget((TLObject) controller);
				}
			}
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(List.of(one, two));
		}
	}

	private Enrollment.Answer enroll(TLObject project, String name) {
		Enrollment.createCode(project, null, Instant.now());
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("code", project.tValueByName("enrollmentCode"));
		request.put("name", name);
		Enrollment.Answer answer = Enrollment.enroll(request, _endpoint.base(), Instant.now());
		assertEquals(answer.body().toString(), 200, answer.status());
		return answer;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestInstallationKeyIdentityProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
