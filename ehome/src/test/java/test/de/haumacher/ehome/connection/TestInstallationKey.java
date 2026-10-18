/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.runtime.FakeController;
import test.de.haumacher.ehome.runtime.FakeControllerLink;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Enrollment;
import de.haumacher.ehome.connection.I18NConstants;
import de.haumacher.ehome.connection.InstallationKeys;
import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.ExportRuntimeImageAction;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The installation key of a project (homecontrol issue #35, <code>spec/api.md</code>, "Signed runtime
 * images") with a {@link FakeController} that checks signatures as the device does.
 *
 * <p>
 * The enrollment answer carries <code>public_key</code> when the project has a key (generated for its
 * first controller) and not for a project whose controllers were enrolled without one. A deployment
 * over the socket carries <code>signature</code> next to <code>length</code> with the image in binary
 * frames, the fake verifies it, and the deployment records the signature and the key; a device holding
 * another key refuses (403 <code>image.bad_signature</code>) and the recorded message names both keys;
 * an unsigned image is refused 403 <code>image.unsigned</code>. The rotation: the new key is offered,
 * the device answers <code>key_pending</code>, images stay signed with the old key, and after
 * <code>key_accepted</code> the new key is the installation key; a later rotation completes through
 * the <code>auth</code> of a new connection naming the new key. Over HTTP on the LAN the signature is the
 * header <code>X-Image-Signature</code>. The export offers the detached <code>.sig</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestInstallationKey extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

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

	public void testEnrollmentAnswer() throws Exception {
		TLObject keyed = ControllerEndpoint.project("Key enrollment");
		TLObject legacy = ControllerEndpoint.project("Key legacy");
		try {
			// The first controller of a project: a key is generated and sent.
			Enrollment.Answer answer = enroll(keyed, "first");
			Keys key = InstallationKeys.key(keyed);
			assertNotNull(key);
			assertEquals(key.publicKey(), answer.body().get("public_key"));
			assertEquals(key.publicKey(), keyed.tValueByName(InstallationKeys.PUBLIC_KEY));
			TLObject first = (TLObject) keyed.tValueByName("controller");
			assertEquals(key.fingerprint(), first.tValueByName(InstallationKeys.DEVICE_KEY));
			// A further controller gets the same key.
			assertEquals(key.publicKey(), enroll(keyed, "second").body().get("public_key"));
			assertEquals(key, InstallationKeys.key(keyed));
			assertEquals(I18NConstants.KEY_EXISTS, InstallationKeys.createVeto(keyed));

			// A project whose controller was enrolled before it had a key stays without one.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject old = ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
				old.tUpdateByName("name", "old");
				old.tUpdateByName("controllerId", "0123456789abcdef0123456789abcdef");
				legacy.tUpdateByName("controllers", List.of(old));
				tx.commit();
			}
			Enrollment.Answer unkeyed = enroll(legacy, "late");
			assertEquals(200, unkeyed.status());
			assertFalse(unkeyed.body().toString(), unkeyed.body().containsKey("public_key"));
			assertNull(InstallationKeys.key(legacy));
			assertEquals(text(I18NConstants.KEY_STATE_NONE), InstallationKeys.state(legacy));
			assertNull(InstallationKeys.signer(legacy, null));
			assertEquals(I18NConstants.KEY_NONE_TO_RENEW, InstallationKeys.renewVeto(legacy));
			Keys created = InstallationKeys.create(legacy);
			assertEquals(created, InstallationKeys.key(legacy));
			try {
				InstallationKeys.create(legacy);
				fail("A second key is refused.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.KEY_EXISTS, ex.getErrorKey());
			}
		} finally {
			SharedData.deleteProjects(List.of(keyed, legacy));
		}
	}

	public void testSignedDeploymentAndRotation() throws Exception {
		TLObject project = ControllerEndpoint.project("Key rotation");
		try {
			Enrollment.Answer enrolled = enroll(project, "homecontrol-fake");
			String id = (String) enrolled.body().get("controller");
			String secret = (String) enrolled.body().get("secret");
			URI socket = URI.create((String) enrolled.body().get("socket"));
			TLObject controller = (TLObject) project.tValueByName("controller");
			Keys first = InstallationKeys.key(project);
			// The device stores the key of the enrollment answer.
			_fake.setKey((String) enrolled.body().get("public_key"));

			FakeControllerLink link = new FakeControllerLink(_fake, TOKEN, id, secret);
			assertEquals("ready", link.connect(socket).get("type"));
			await(() -> link.getReceived().stream().anyMatch(m -> "subscribe".equals(m.get("type"))));
			ControllerConnection connection = ControllerConnections.get(id);
			assertEquals(first.fingerprint(), connection.deviceKey());
			assertEquals(text(I18NConstants.DEVICE_KEY__FINGERPRINT.fill(first.fingerprint())),
				InstallationKeys.controllerState(controller));

			// Signed: the signature next to length, the image in binary frames, verified by the fake.
			TLObject deployment = Deployer.deploy(project, NOW);
			assertEquals(deployment.tValueByName("message").toString(), Boolean.TRUE, deployment.tValueByName("accepted"));
			FakeControllerLink.Request put = last(link.getRequests());
			assertTrue(put.binary());
			assertNotNull(put.signature());
			assertTrue(Signatures.verifyImage(first.publicKey(), put.body(), put.signature()));
			assertEquals(RuntimeImage.build(project, 1, NOW).getText(), new String(put.body(), StandardCharsets.UTF_8));
			assertEquals(put.signature(), deployment.tValueByName("signature"));
			assertEquals(first.fingerprint(), deployment.tValueByName("keyFingerprint"));
			assertFalse("No key is offered to a device holding the project's.",
				link.getReceived().stream().anyMatch(m -> "key".equals(m.get("type"))));

			// A device holding another key refuses; the message names the key E-Home signed with.
			String foreign = Signatures.generate().publicKey();
			_fake.setKey(foreign);
			TLObject refused = Deployer.deploy(project, NOW);
			assertEquals(Boolean.FALSE, refused.tValueByName("accepted"));
			assertEquals(Integer.valueOf(403), refused.tValueByName("status"));
			assertEquals(Deployer.CODE_BAD_SIGNATURE, refused.tValueByName("code"));
			String message = refused.tValueByName("message").toString();
			assertTrue(message, message.contains(Signatures.fingerprint(foreign)));
			assertTrue(message, message.contains(text(
				de.haumacher.ehome.runtime.I18NConstants.DEPLOY_REFUSED_SIGNED__FINGERPRINT.fill(first.fingerprint()))));
			_fake.setKey(first.publicKey());

			// Unsigned: refused while the device holds a key.
			Answer unsigned = ControllerClient.over(connection, "socket", Deployer.TIMEOUT)
				.putImage(RuntimeImage.build(project, 2, NOW).getBytes());
			assertEquals(403, unsigned.status());
			assertEquals(Deployer.CODE_UNSIGNED, unsigned.string("code"));
			assertTrue(unsigned.string("error").contains(first.fingerprint()));

			// The rotation: the new key is offered, the device keeps it pending, images stay signed with the old.
			Keys second = InstallationKeys.renew(project);
			await(() -> second.fingerprint().equals(connection.keyPending()));
			assertEquals(second.publicKey(), link.getPendingKey());
			Map<String, Object> offer = link.getReceived().stream().filter(m -> "key".equals(m.get("type"))).findFirst()
				.get();
			assertEquals(second.publicKey(), offer.get("public_key"));
			assertEquals(second.fingerprint(), offer.get("fingerprint"));
			assertEquals(first, InstallationKeys.key(project));
			assertEquals(second, InstallationKeys.newKey(project));
			assertTrue(InstallationKeys.state(project), InstallationKeys.state(project).contains(text(
				I18NConstants.KEY_STATE_ROTATION__NEW_OLD.fill(second.fingerprint(), first.fingerprint()))));
			assertTrue(InstallationKeys.controllerState(controller).contains(
				text(I18NConstants.DEVICE_KEY_PENDING__FINGERPRINT.fill(second.fingerprint()))));
			TLObject during = Deployer.deploy(project, NOW);
			assertEquals(during.tValueByName("message").toString(), Boolean.TRUE, during.tValueByName("accepted"));
			assertEquals(first.fingerprint(), during.tValueByName("keyFingerprint"));

			// The owner accepts: the new key is the installation key, the old one is forgotten.
			assertEquals(second.fingerprint(), link.acceptKey());
			await(() -> second.fingerprint().equals(connection.deviceKey()));
			ControllerConnections.awaitKnowledgeBase();
			assertEquals(second, InstallationKeys.key(project));
			assertNull(InstallationKeys.newKey(project));
			assertEquals(second.fingerprint(), controller.tValueByName(InstallationKeys.DEVICE_KEY));
			TLObject after = Deployer.deploy(project, NOW);
			assertEquals(after.tValueByName("message").toString(), Boolean.TRUE, after.tValueByName("accepted"));
			assertEquals(second.fingerprint(), after.tValueByName("keyFingerprint"));
			assertTrue(Signatures.verifyImage(second.publicKey(), last(link.getRequests()).body(),
				(String) after.tValueByName("signature")));

			// A later rotation completes through the auth of a new connection naming the new key.
			Keys third = InstallationKeys.renew(project);
			await(() -> third.fingerprint().equals(connection.keyPending()));
			link.close();
			await(() -> ControllerConnections.get(id) == null);
			_fake.setKey(third.publicKey()); // accepted on the device meanwhile (by the console, say)
			FakeControllerLink back = new FakeControllerLink(_fake, TOKEN, id, secret);
			assertEquals("ready", back.connect(socket).get("type"));
			await(() -> back.getReceived().stream().anyMatch(m -> "subscribe".equals(m.get("type"))));
			ControllerConnections.awaitKnowledgeBase();
			assertEquals(third, InstallationKeys.key(project));
			assertNull(InstallationKeys.newKey(project));

			// The export: the image with its detached signature.
			Map<String, Object> export = ExportRuntimeImageAction.export(project, NOW);
			BinaryData file = (BinaryData) export.get("file");
			BinaryData sig = (BinaryData) export.get("signature");
			assertEquals(file.getName() + ".sig", sig.getName());
			String sigText = new String(bytes(sig), StandardCharsets.US_ASCII);
			assertTrue(sigText, sigText.matches("[0-9a-f]{128}\n"));
			assertTrue(Signatures.verifyImage(third.publicKey(), bytes(file), sigText.trim()));

			// Over HTTP on the LAN: the header, verified by the fake.
			back.close();
			await(() -> ControllerConnections.get(id) == null);
			ControllerConnections.awaitKnowledgeBase(); // the last contact of the close is stored first
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				controller.tUpdateByName("host", "127.0.0.1");
				controller.tUpdateByName("port", Integer.valueOf(_fake.getPort()));
				controller.tUpdateByName("token", TOKEN);
				tx.commit();
			}
			TLObject lan = Deployer.deploy(project, NOW);
			assertEquals(lan.tValueByName("message").toString(), Boolean.TRUE, lan.tValueByName("accepted"));
			assertEquals(lan.tValueByName("signature"), _fake.getLastSignature());
			assertEquals(third.fingerprint(), lan.tValueByName("keyFingerprint"));
		} finally {
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(List.of(project));
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

	private static byte[] bytes(BinaryData data) throws Exception {
		try (InputStream in = data.getStream()) {
			return in.readAllBytes();
		}
	}

	private static <T> T last(List<T> list) {
		return list.get(list.size() - 1);
	}

	static void await(BooleanSupplier condition) throws InterruptedException {
		long end = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				fail("Timed out.");
			}
			Thread.sleep(10);
		}
	}

	static String text(com.top_logic.basic.util.ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestInstallationKey.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
