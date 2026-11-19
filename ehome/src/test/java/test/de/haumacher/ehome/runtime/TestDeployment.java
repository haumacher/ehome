/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.GroupAddressDefinition;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.Deployment;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.runtime.ControllerStatus;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageComparison;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.LastValidImages;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Deployments of a project (<code>samples/Test.knxproj</code>) to an in-process
 * {@link FakeController} speaking the controller's protocol: the binary image compiled from the
 * source is sent (haumacher/ehome #176); an accepted image advances the project's revision and the
 * controller's status agrees with it; every refusal code of <code>PUT /api/image</code> is recorded
 * with the deployment (the refusals with the group address their path leads to) and leaves the
 * revision, and one after which the controller runs no image is followed by the last valid deployment
 * sent again; a wrong token, an image larger than the controller's slot, an unreachable controller, a
 * controller reading no binary image and an image E-Home refuses itself are recorded the same way.
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
			Project project = (Project) projects.get(0);

			// Without a controller and without a token, the deployment is refused before anything is sent.
			assertEquals(I18NConstants.ERROR_NO_CONTROLLER, Deployer.veto(project));
			assertEquals(text(I18NConstants.ERROR_NO_CONTROLLER), ControllerStatus.drift(project));
			Controller controller = (Controller) controller("Fake", _fake.getPort(), null);
			controllers.add(controller);
			ControllerFixture.deployTo(project, controller);
			assertNotNull(Deployer.veto(project));
			try {
				Deployer.deploy(project, NOW);
				fail("A controller without token is refused.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_NO_TOKEN__CONTROLLER.fill("Fake"), ex.getErrorKey());
			}
			update(() -> controller.setToken(TOKEN));
			assertNull(Deployer.veto(project));
			assertEquals(text(I18NConstants.DRIFT_NEVER_DEPLOYED) + "\n" + text(I18NConstants.STATUS_NO_IMAGE),
				ControllerStatus.drift(project));

			// Accepted: the revision advances, the controller runs the image E-Home built.
			Deployment first = (Deployment) Deployer.deploy(project, NOW);
			assertAccepted(first, 1);
			assertEquals(text(I18NConstants.STATUS_NO_IMAGE), first.getBefore());
			// The controller got the binary image compiled from the source (haumacher/ehome #176).
			assertTrue(Arrays.equals(RuntimeImage.build(project, 1, NOW).compile(null), _fake.getStored()));
			assertEquals(Integer.valueOf(_fake.getStored().length), first.getBytes());
			assertEquals(Long.valueOf(1), project.getImageRevision());
			assertTrue(ControllerStatus.drift(project),
				ControllerStatus.drift(project).startsWith(text(I18NConstants.DRIFT_IN_SYNC__REVISION.fill(1L))));
			// The controller still stores the previous image (homecontrol #68): the image is sent again after a
			// second and accepted.
			int sent = _fake.getPuts();
			_fake.refuseNext(503, Deployer.CODE_BUSY, "an image is being stored", List.of());
			Deployment second = (Deployment) Deployer.deploy(project, NOW);
			assertEquals(sent + 2, _fake.getPuts());
			assertAccepted(second, 2);
			assertEquals(List.of(second, first), project.getDeployments());
			int datapoints = RuntimeImage.build(project, 2, NOW).getDatapointSources().size();
			assertTrue(datapoints > 0);

			// A refusal with refusals after the controller erased its slot: recorded against the group address of
			// the path; the controller then runs no image, so the last valid deployment (revision 2) goes again,
			// recorded as an entry of its own (spec/runtime-image.md I9).
			_fake.refuseNext(422, "image.invalid", "the image is invalid",
				List.of(new Refusal("datapoints[0].knx.dpt", "DPT 9.001 carries real values, the datapoint is boolean"),
					new Refusal("", "something about the whole image")));
			Deployment refused = (Deployment) Deployer.deploy(project, NOW);
			assertRefused(refused, 3, 422, "image.invalid");
			List<?> refusals = (List<?>) refused.getRefusals();
			assertEquals(2, refusals.size());
			assertEquals(Integer.valueOf(2), refused.getTotal());
			de.haumacher.ehome.model.runtime.Refusal refusal = (de.haumacher.ehome.model.runtime.Refusal) refusals.get(0);
			assertEquals("datapoints[0].knx.dpt", refusal.getPath());
			assertEquals("DPT 9.001 carries real values, the datapoint is boolean", refusal.getReason());
			assertEquals(RuntimeImage.build(project, 3, NOW).getDatapointSources().get(0), refusal.getElement());
			assertNull(((de.haumacher.ehome.model.runtime.Refusal) refusals.get(1)).getElement());
			// The controller's refusals are English only: no message.
			assertNull(refusal.getMessage());
			assertEquals(text(I18NConstants.DEPLOY_REFUSED__STATUS_ERROR.fill(422, "the image is invalid")) + " "
				+ text(I18NConstants.DEPLOY_RESENT__REVISION.fill(2L)), refused.getMessage());
			List<?> deployments = (List<?>) project.getDeployments();
			Deployment resent = (Deployment) (TLObject) deployments.get(0);
			assertEquals(refused, deployments.get(1));
			assertAccepted(resent, 2);
			assertEquals(text(I18NConstants.RESEND_ACCEPTED__REVISION.fill("2")), resent.getMessage());
			assertTrue(Arrays.equals(RuntimeImage.build(project, 2, NOW).compile(null), _fake.getStored()));

			// Every other refusal code of the protocol: from the header (the image runs on), or after the erase
			// (the last valid deployment goes again).
			Map<String, Integer> codes = Map.of("image.format", 400, "image.version", 422, "image.too_large", 413,
				"image.no_memory", 507, "image.apply_failed", 500);
			for (Map.Entry<String, Integer> code : codes.entrySet()) {
				_fake.refuseNext(code.getValue(), code.getKey(), "refused", List.of());
				assertRefused(Deployer.deploy(project, NOW), 3, code.getValue(), code.getKey());
				assertNotNull(code.getKey(), _fake.getLoaded());
			}
			assertEquals(Long.valueOf(2), project.getImageRevision());
			// The controller still runs revision 2, the last one deployed.
			assertTrue(ControllerStatus.drift(project).startsWith(text(I18NConstants.DRIFT_IN_SYNC__REVISION.fill(2L))));

			// A wrong token: 401, and nothing is loaded.
			int puts = _fake.getPuts();
			update(() -> controller.setToken("hct_ffffffffffffffffffffffffffffffff"));
			assertRefused(Deployer.deploy(project, NOW), 3, 401, "auth.token_invalid");
			assertEquals(puts, _fake.getPuts());
			assertTrue(ControllerStatus.drift(project).contains("401"));
			update(() -> controller.setToken(TOKEN));

			// An image larger than the controller's slot: refused before it is sent.
			FakeController small = new FakeController(TOKEN, 100);
			try {
				update(() -> controller.setPort(small.getPort()));
				Deployment tooLarge = (Deployment) Deployer.deploy(project, NOW);
				assertRefused(tooLarge, 3, null, Deployer.CODE_TOO_LARGE);
				assertEquals(text(I18NConstants.DEPLOY_TOO_LARGE__BYTES_SLOT.fill(tooLarge.getBytes(), 100L)),
					tooLarge.getMessage());
				assertEquals(0, small.getPuts());
			} finally {
				small.stop();
			}
			// Nobody listens any more.
			Deployment unreachable = (Deployment) Deployer.deploy(project, NOW);
			assertRefused(unreachable, 3, null, Deployer.CODE_UNREACHABLE);
			update(() -> controller.setPort(_fake.getPort()));

			// An image E-Home refuses itself is not sent: two group addresses with the same address.
			GroupAddress original = (GroupAddress) RuntimeImage.build(project, 3, NOW).getDatapointSources().get(0);
			GroupAddress copy;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				copy = (GroupAddress) ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.logical:GroupAddress"));
				copy.setName("Copy");
				copy.setAddress(original.getAddress());
				copy.setDatapointTypes(List.of(GroupAddressTypes.declaredType(original)));
				Set<GroupAddressDefinition> addresses = new LinkedHashSet<>(project.getGroupAddresses());
				addresses.add(copy);
				project.setGroupAddresses(addresses);
				tx.commit();
			}
			puts = _fake.getPuts();
			Deployment invalid = (Deployment) Deployer.deploy(project, NOW);
			assertRefused(invalid, 3, null, Deployer.CODE_INVALID);
			assertEquals(puts, _fake.getPuts());
			List<?> local = (List<?>) invalid.getRefusals();
			assertFalse(local.isEmpty());
			de.haumacher.ehome.model.runtime.Refusal duplicate = (de.haumacher.ehome.model.runtime.Refusal) local.get(0);
			assertTrue(String.valueOf(duplicate.getPath()).endsWith(".id"));
			assertTrue(String.valueOf(duplicate.getReason()).startsWith("duplicate id"));
			// The finding for the user, stored in both languages (haumacher/ehome #95).
			ResKey message = (ResKey) duplicate.getMessage();
			assertNotNull(message);
			assertTrue(Resources.getInstance(Locale.GERMAN).getString(message),
				Resources.getInstance(Locale.GERMAN).getString(message).startsWith("Die ID „"));
			assertTrue(Resources.getInstance(Locale.ENGLISH).getString(message),
				Resources.getInstance(Locale.ENGLISH).getString(message).startsWith("The id \""));
			assertTrue(duplicate.getElement() == copy || duplicate.getElement() == original);

			// Drift: the project claims a revision the controller does not run.
			update(() -> project.setImageRevision(Long.valueOf(5)));
			assertTrue(ControllerStatus.drift(project)
				.startsWith(text(I18NConstants.DRIFT_OTHER_REVISION__LOADED_DEPLOYED.fill(2L, 5L))));
		} finally {
			SharedData.deleteProjects(projects);
			SharedData.deleteProjects(controllers);
		}
	}

	/**
	 * A controller whose firmware reads JSON images (it reports no <code>format</code>) or another major
	 * format is refused before anything is sent, with "update the firmware"; there is no JSON fallback.
	 * A controller reporting at its connection that its image did not load (here: as asked directly)
	 * gets the last valid deployment again; without one the refusal is recorded.
	 */
	public void testFormatAndResendAfterRefusal() throws Exception {
		Project project = (Project) TestRuleAuthoring.createProject();
		List<TLObject> controllers = new ArrayList<>();
		try {
			Controller controller = (Controller) controller("Fake format", _fake.getPort(), TOKEN);
			controllers.add(controller);
			ControllerFixture.deployTo(project, controller);

			_fake.setFormat(null);
			Deployment old = (Deployment) Deployer.deploy(project, NOW);
			assertRefused(old, 1, null, Deployer.CODE_FORMAT);
			assertEquals(text(I18NConstants.DEPLOY_REFUSED_NO_FORMAT__CONTROLLER.fill("Fake format")), old.getMessage());
			assertEquals(0, _fake.getPuts());
			String confirmation = ImageComparison.confirmation(project, NOW);
			assertTrue(confirmation, confirmation.contains(text(I18NConstants.DEPLOY_REFUSED_NO_FORMAT__CONTROLLER.fill("Fake format"))));
			_fake.setFormat("2.0");
			assertRefused(Deployer.deploy(project, NOW), 1, null, Deployer.CODE_FORMAT);
			assertEquals(I18NConstants.DEPLOY_REFUSED_FORMAT__CONTROLLER_FORMAT_IMAGE.fill("x", "2.0", "1.0"),
				Deployer.formatRefusal("2.0", "x", 0));
			assertNull(Deployer.formatRefusal("1.0", "x", 0));
			assertNull(Deployer.formatRefusal("1.3", "x", 0));
			// An image with anonymous pages (format 1.1, haumacher/ehome #184) needs a firmware that knows their sections.
			assertEquals(I18NConstants.DEPLOY_REFUSED_FORMAT__CONTROLLER_FORMAT_IMAGE.fill("x", "1.0", "1.1"),
				Deployer.formatRefusal("1.0", "x", 1));
			assertNull(Deployer.formatRefusal("1.1", "x", 1));
			_fake.setFormat("1.0");

			// No last valid image: the refusal the controller reported is recorded, nothing is sent.
			_fake.failAtBoot("image.damaged", "header.crc: the CRC-32 is 59772c42, the bytes give 0d5e8a10");
			Deployment none = (Deployment) Deployer.resendAfterRefusal(controller, "image.damaged", "header.crc: ...", NOW);
			assertEquals(Boolean.FALSE, Boolean.valueOf(none.getAccepted()));
			assertEquals("image.damaged", none.getCode());
			assertTrue(String.valueOf(none.getMessage()).endsWith(text(I18NConstants.DEPLOY_NO_LAST_VALID)));
			assertEquals(0, _fake.getPuts());

			// Deployed, then lost at a boot: E-Home sends the image again by itself, read from the stored
			// deployment (it survives a restart of E-Home: nothing is held in memory).
			Deployment deployed = (Deployment) Deployer.deploy(project, NOW);
			assertAccepted(deployed, 1);
			byte[] image = _fake.getStored();
			assertTrue(Arrays.equals(image, bytes(deployed.getImage())));
			assertTrue(Arrays.equals(image, LastValidImages.get(controller).bytes()));
			_fake.failAtBoot("image.damaged", "header.crc: the CRC-32 is 59772c42, the bytes give 0d5e8a10");
			assertNull(_fake.getLoaded());
			Deployment resent = (Deployment) Deployer.resendAfterRefusal(controller, "image.damaged", "header.crc: ...", NOW);
			assertAccepted(resent, 1);
			assertTrue(String.valueOf(resent.getMessage()).contains(text(I18NConstants.RESEND_ACCEPTED__REVISION.fill("1"))));
			assertTrue(Arrays.equals(image, _fake.getStored()));
			assertEquals(List.of(resent, deployed, none), ((List<?>) project.getDeployments()).subList(0, 3));
			// At most one image per controller: the newest accepted deployment keeps it.
			assertNotNull(resent.getImage());
			assertNull(deployed.getImage());
			Deployment next = (Deployment) Deployer.deploy(project, NOW);
			assertAccepted(next, 2);
			assertNull(resent.getImage());
			assertTrue(Arrays.equals(_fake.getStored(), bytes(next.getImage())));
			assertEquals(Long.valueOf(2), LastValidImages.get(controller).revision());
		} finally {
			_fake.setFormat("1.0");
			SharedData.deleteProjects(List.of(project));
			SharedData.deleteProjects(controllers);
		}
	}

	private static void assertAccepted(TLObject object, long revision) {
		Deployment deployment = (Deployment) object;
		assertEquals(String.valueOf(deployment.getMessage()), Boolean.TRUE, Boolean.valueOf(deployment.getAccepted()));
		assertEquals(Long.valueOf(revision), deployment.getRevision());
		assertEquals(Integer.valueOf(200), deployment.getStatus());
		assertNull(deployment.getCode());
		assertEquals(List.of(), deployment.getRefusals());
	}

	private static void assertRefused(TLObject object, long revision, Integer status, String code) {
		Deployment deployment = (Deployment) object;
		assertEquals(String.valueOf(deployment.getMessage()), Boolean.FALSE, Boolean.valueOf(deployment.getAccepted()));
		assertEquals(Long.valueOf(revision), deployment.getRevision());
		assertEquals(status, deployment.getStatus());
		assertEquals(code, deployment.getCode());
		assertNotNull(deployment.getMessage());
	}

	private static TLObject controller(String name, int port, String token) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Controller controller = (Controller) ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			controller.setName(name);
			controller.setHost("127.0.0.1");
			controller.setPort(Integer.valueOf(port));
			controller.setToken(token);
			tx.commit();
			return controller;
		}
	}

	private static byte[] bytes(Object data) throws Exception {
		try (java.io.InputStream in = ((com.top_logic.basic.io.binary.BinaryData) data).getStream()) {
			return in.readAllBytes();
		}
	}

	private static void update(Runnable change) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			change.run();
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
