/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
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

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.ControllerStatus;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Deployments of a project (<code>samples/Test.knxproj</code>) to an in-process
 * {@link FakeController} speaking the controller's protocol: an accepted image advances the
 * project's revision and the controller's status agrees with it; every refusal code of
 * <code>PUT /api/image</code> is recorded with the deployment (the refusals with the group address
 * their path leads to) and leaves the revision; a wrong token, a body above the controller's limit,
 * an unreachable controller and an image E-Home refuses itself are recorded the same way.
 *
 * <p>
 * The project is imported once and all cases run in one test (the import is the cost of the test).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDeployment extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private FakeController _fake;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_fake = new FakeController(TOKEN, 262144);
	}

	@Override
	protected void tearDown() throws Exception {
		_fake.stop();
		super.tearDown();
	}

	public void testDeployments() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		List<TLObject> controllers = new ArrayList<>();
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);

			// Without a controller and without a token, the deployment is refused before anything is sent.
			assertEquals(I18NConstants.ERROR_NO_CONTROLLER, Deployer.veto(project));
			assertEquals(text(I18NConstants.ERROR_NO_CONTROLLER), ControllerStatus.drift(project));
			TLObject controller = controller("Fake", _fake.getPort(), null);
			controllers.add(controller);
			update(project, "controller", controller);
			assertNotNull(Deployer.veto(project));
			try {
				Deployer.deploy(project, NOW);
				fail("A controller without token is refused.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_NO_TOKEN__CONTROLLER.fill("Fake"), ex.getErrorKey());
			}
			update(controller, "token", TOKEN);
			assertNull(Deployer.veto(project));
			assertEquals(text(I18NConstants.DRIFT_NEVER_DEPLOYED) + "\n" + text(I18NConstants.STATUS_NO_IMAGE),
				ControllerStatus.drift(project));

			// Accepted: the revision advances, the controller runs the image E-Home built.
			TLObject first = Deployer.deploy(project, NOW);
			assertAccepted(first, 1);
			assertEquals(text(I18NConstants.STATUS_NO_IMAGE), first.tValueByName("before"));
			assertEquals(RuntimeImage.build(project, 1, NOW).getText(), _fake.getStoredText());
			assertEquals(Long.valueOf(1), project.tValueByName("imageRevision"));
			assertTrue(ControllerStatus.drift(project),
				ControllerStatus.drift(project).startsWith(text(I18NConstants.DRIFT_IN_SYNC__REVISION.fill(1L))));
			// The controller still stores the previous image (homecontrol #68): the image is sent again after a
			// second and accepted.
			int sent = _fake.getPuts();
			_fake.refuseNext(503, Deployer.CODE_BUSY, "an image is being stored", List.of());
			TLObject second = Deployer.deploy(project, NOW);
			assertEquals(sent + 2, _fake.getPuts());
			assertAccepted(second, 2);
			assertEquals(List.of(second, first), project.tValueByName("deployments"));
			int datapoints = RuntimeImage.build(project, 2, NOW).getDatapointSources().size();
			assertTrue(datapoints > 0);

			// A refusal with refusals: recorded against the group address of the path.
			_fake.refuseNext(422, "image.invalid", "the image is invalid",
				List.of(new Refusal("datapoints[0].knx.dpt", "DPT 9.001 carries real values, the datapoint is boolean"),
					new Refusal("", "something about the whole image")));
			TLObject refused = Deployer.deploy(project, NOW);
			assertRefused(refused, 3, 422, "image.invalid");
			List<?> refusals = (List<?>) refused.tValueByName("refusals");
			assertEquals(2, refusals.size());
			assertEquals(Integer.valueOf(2), refused.tValueByName("total"));
			TLObject refusal = (TLObject) refusals.get(0);
			assertEquals("datapoints[0].knx.dpt", refusal.tValueByName("path"));
			assertEquals("DPT 9.001 carries real values, the datapoint is boolean", refusal.tValueByName("reason"));
			assertEquals(RuntimeImage.build(project, 3, NOW).getDatapointSources().get(0), refusal.tValueByName("element"));
			assertNull(((TLObject) refusals.get(1)).tValueByName("element"));
			assertEquals(text(I18NConstants.DEPLOY_REFUSED__STATUS_ERROR.fill(422, "the image is invalid")),
				refused.tValueByName("message"));

			// Every other refusal code of the protocol.
			Map<String, Integer> codes = Map.of("image.bad_json", 400, "image.version", 422, "image.too_large", 413,
				"image.no_memory", 507, "image.apply_failed", 500);
			for (Map.Entry<String, Integer> code : codes.entrySet()) {
				_fake.refuseNext(code.getValue(), code.getKey(), "refused", List.of());
				assertRefused(Deployer.deploy(project, NOW), 3, code.getValue(), code.getKey());
			}
			assertEquals(Long.valueOf(2), project.tValueByName("imageRevision"));
			// The controller still runs revision 2, the last one deployed.
			assertTrue(ControllerStatus.drift(project).startsWith(text(I18NConstants.DRIFT_IN_SYNC__REVISION.fill(2L))));

			// A wrong token: 401, and nothing is loaded.
			int puts = _fake.getPuts();
			update(controller, "token", "hct_ffffffffffffffffffffffffffffffff");
			assertRefused(Deployer.deploy(project, NOW), 3, 401, "auth.token_invalid");
			assertEquals(puts, _fake.getPuts());
			assertTrue(ControllerStatus.drift(project).contains("401"));
			update(controller, "token", TOKEN);

			// A body above the controller's limit: 413 before the image is read.
			FakeController small = new FakeController(TOKEN, 100);
			try {
				update(controller, "port", small.getPort());
				assertRefused(Deployer.deploy(project, NOW), 3, 413, "api.body_too_large");
			} finally {
				small.stop();
			}
			// Nobody listens any more.
			TLObject unreachable = Deployer.deploy(project, NOW);
			assertRefused(unreachable, 3, null, Deployer.CODE_UNREACHABLE);
			update(controller, "port", _fake.getPort());

			// An image E-Home refuses itself is not sent: two group addresses with the same address.
			TLObject original = RuntimeImage.build(project, 3, NOW).getDatapointSources().get(0);
			TLObject copy;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				copy = ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.logical:GroupAddress"));
				copy.tUpdateByName("name", "Copy");
				copy.tUpdateByName("address", original.tValueByName("address"));
				copy.tUpdateByName("datapointTypes", List.of(GroupAddressTypes.declaredType(original)));
				List<Object> addresses = new ArrayList<>((java.util.Collection<?>) project.tValueByName("groupAddresses"));
				addresses.add(copy);
				project.tUpdateByName("groupAddresses", addresses);
				tx.commit();
			}
			puts = _fake.getPuts();
			TLObject invalid = Deployer.deploy(project, NOW);
			assertRefused(invalid, 3, null, Deployer.CODE_INVALID);
			assertEquals(puts, _fake.getPuts());
			List<?> local = (List<?>) invalid.tValueByName("refusals");
			assertFalse(local.isEmpty());
			TLObject duplicate = (TLObject) local.get(0);
			assertTrue(String.valueOf(duplicate.tValueByName("path")).endsWith(".id"));
			assertTrue(String.valueOf(duplicate.tValueByName("reason")).startsWith("duplicate id"));
			assertTrue(duplicate.tValueByName("element") == copy || duplicate.tValueByName("element") == original);

			// Drift: the project claims a revision the controller does not run.
			update(project, "imageRevision", Long.valueOf(5));
			assertTrue(ControllerStatus.drift(project)
				.startsWith(text(I18NConstants.DRIFT_OTHER_REVISION__LOADED_DEPLOYED.fill(2L, 5L))));
		} finally {
			SharedData.deleteProjects(projects);
			SharedData.deleteProjects(controllers);
		}
	}

	/**
	 * An image with blocks goes only to a controller that reads schema 1.3 (homecontrol
	 * spec/blocks.md B1): refused, recorded with its reason and not sent, for a controller reporting
	 * 1.2 and for one reporting no schema; accepted for 1.3. An image without blocks still goes to 1.2.
	 */
	public void testBlocksNeedSchema13() throws Exception {
		TLObject project;
		TLObject block;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", "Blocks deployment");
			project.tUpdateByName("extId", "P-TEST-75-DEPLOY");
			TLObject temperature = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:ApplicationDatapoint"));
			temperature.tUpdateByName("identifier", "wz.temperature");
			temperature.tUpdateByName("type", ((com.top_logic.model.TLEnumeration) TLModelUtil
				.findType("ehome.runtime:DatapointValueType")).getClassifier("real"));
			project.tUpdateByName("applicationDatapoints", List.of(temperature));
			block = BlockInstances.create(project, BlockInstances.typeKey(RoomController.INSTANCE.manifest()), null,
				ResKey.text("Heizung"));
			BlockInstances.bind(BlockInstances.port(block, "temperature"), temperature);
			tx.commit();
		}
		List<TLObject> controllers = new ArrayList<>();
		try {
			TLObject controller = controller("Fake 1.2", _fake.getPort(), TOKEN);
			controllers.add(controller);
			update(project, "controller", controller);

			_fake.setSchema("1.2");
			int puts = _fake.getPuts();
			TLObject refused = Deployer.deploy(project, NOW);
			assertRefused(refused, 1, null, Deployer.CODE_SCHEMA);
			assertEquals(text(I18NConstants.DEPLOY_REFUSED_BLOCKS_SCHEMA__CONTROLLER_SCHEMA.fill("Fake 1.2", "1.2")),
				refused.tValueByName("message"));
			assertEquals("Nothing is sent.", puts, _fake.getPuts());
			assertNull(project.tValueByName("imageRevision"));

			_fake.setSchema(null);
			refused = Deployer.deploy(project, NOW);
			assertRefused(refused, 1, null, Deployer.CODE_SCHEMA);
			assertEquals(text(I18NConstants.DEPLOY_REFUSED_BLOCKS_NO_SCHEMA__CONTROLLER.fill("Fake 1.2")),
				refused.tValueByName("message"));
			assertEquals(puts, _fake.getPuts());

			_fake.setSchema("1.3");
			assertAccepted(Deployer.deploy(project, NOW), 1);
			assertEquals(puts + 1, _fake.getPuts());

			// Without blocks the image goes to an older controller as before (the confirmation warns).
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				block.tDelete();
				tx.commit();
			}
			_fake.setSchema("1.2");
			assertAccepted(Deployer.deploy(project, NOW), 2);
			assertNull(Deployer.blocksRefusal("1.3", "x"));
			assertNull(Deployer.blocksRefusal("2.0", "x"));
			assertNull(Deployer.blocksRefusal("1.12", "x"));
			assertNotNull(Deployer.blocksRefusal("1.1", "x"));
			assertNotNull(Deployer.blocksRefusal("", "x"));
		} finally {
			SharedData.deleteProjects(List.of(project));
			SharedData.deleteProjects(controllers);
		}
	}

	private static void assertAccepted(TLObject deployment, long revision) {
		assertEquals(String.valueOf(deployment.tValueByName("message")), Boolean.TRUE, deployment.tValueByName("accepted"));
		assertEquals(Long.valueOf(revision), deployment.tValueByName("revision"));
		assertEquals(Integer.valueOf(200), deployment.tValueByName("status"));
		assertNull(deployment.tValueByName("code"));
		assertEquals(List.of(), deployment.tValueByName("refusals"));
	}

	private static void assertRefused(TLObject deployment, long revision, Integer status, String code) {
		assertEquals(String.valueOf(deployment.tValueByName("message")), Boolean.FALSE, deployment.tValueByName("accepted"));
		assertEquals(Long.valueOf(revision), deployment.tValueByName("revision"));
		assertEquals(status, deployment.tValueByName("status"));
		assertEquals(code, deployment.tValueByName("code"));
		assertNotNull(deployment.tValueByName("message"));
	}

	private static TLObject controller(String name, int port, String token) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject controller = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			controller.tUpdateByName("name", name);
			controller.tUpdateByName("host", "127.0.0.1");
			controller.tUpdateByName("port", Integer.valueOf(port));
			controller.tUpdateByName("token", token);
			tx.commit();
			return controller;
		}
	}

	private static void update(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeployment.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
