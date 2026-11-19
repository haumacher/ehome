/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.history.OlderView;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.security.AccountFixture;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.WrapperHistoryUtils;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.tool.boundsec.simple.CommandGroupRegistry;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.InstallationKeys;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.GroupAddressDefinition;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.Deployment;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Page;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectOwners;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageComparison;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.runtime.binary.ImageDecoder;

/**
 * "Deploy again" (haumacher/ehome #192): the image of an earlier deployment compiled from the project
 * as of that deployment, out of the versioned knowledge base, on the test home
 * (<code>samples/KNX-Testsystem.knxproj</code> with its rules, schedule, Zigbee plug and application
 * datapoint, the proposed functions and a control page without sign-in, format 1.1), deployed to an
 * in-process {@link FakeController} holding the project's installation key.
 *
 * <ul>
 * <li>The image compiled again from the state of a deployment, with its revision and time, is the
 * image the controller received then, byte for byte with its signature.</li>
 * <li>After the project changed and was deployed, "Deploy again" of the first deployment sends that
 * image with the next revision, signed with the key the controller holds: it reads back as the first
 * image but for its revision. An address added since, a page, a function, a rule and the
 * connections of an address changed or deleted since do not reach it; every object the image stands for is a historic object of
 * that state. The record keeps the commit it was compiled from and refers to the deployment it goes
 * back to, so that deploying it again is the same image once more.</li>
 * <li>The image is compiled the same with the access checks of an editor of the project, as in the
 * browser.</li>
 * <li>A deployment recorded before haumacher/ehome #192 (without <code>sourceRevision</code>) has the
 * revision before its record as its state.</li>
 * <li>Refusals speak: a controller reading an older format than the earlier image's, an unload, an
 * image sent again, an entry of another project.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestRedeployment extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final String NAME = "Fake redeploy";

	private static final Instant T_A = Instant.parse("2026-11-18T08:00:00Z");

	private static final Instant T_B = Instant.parse("2026-11-18T09:00:00Z");

	private static final Instant T_C = Instant.parse("2026-11-18T10:00:00Z");

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

	public void testDeployAgain() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		assertEquals(1, projects.size());
		Project project = (Project) projects.get(0);
		List<TLObject> controllers = new ArrayList<>();
		try {
			// State A: the test home with its functions and a page without sign-in (format 1.1).
			TestTestHome.setUp(project);
			TestTestHome.importHome(project,
				(Map<?, ?>) Json.parse(TestTestHome.read("samples/KNX-Testsystem.home.json"), 100));
			TLObject lab = TestFunctionProposal.createLocation(project, null, "ehome.building:Room", "Test system");
			TestFunctionProposal.update(TestFunctionProposal.device(project, "1.3.5"), "location", lab);
			List<TLObject> functions = TestFunctionProposal.apply(project);
			GroupAddress temperature = (GroupAddress) address(project, "Raumtemperatur");
			Page page;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				page = (Page) Pages.insert(project, null, Pages.PAGE, null);
				page.setIdentifier("home");
				page.setMinimumRole(classifier("PageRole", Pages.ANONYMOUS));
				TLObject section = Pages.insert(project, page, Pages.SECTION, null);
				Pages.insert(project, section, Pages.ROOM, List.of(lab));
				Pages.insert(project, section, Pages.FUNCTION, List.of(functions.get(0)));
				Pages.insert(project, section, Pages.DATAPOINT, List.of(temperature));
				TLObject chart = Pages.insert(project, section, Pages.CHART, null);
				Pages.insert(project, chart, Pages.SERIES, List.of(temperature));
				tx.commit();
			}
			Controller controller = (Controller) controller(NAME, _fake.getPort());
			controllers.add(controller);
			ControllerFixture.deployTo(project, controller);
			Keys key = InstallationKeys.create(project);
			_fake.setKey(key.publicKey());
			_fake.setFormat("1.1");

			Deployment a = (Deployment) Deployer.deploy(project, T_A);
			assertAccepted(a, 1);
			byte[] imageA = _fake.getStored();
			Deployer.State stateA = Deployer.state(a);
			assertEquals(T_A, stateA.generatedAt());
			// The commit the image was compiled from, before the record (the controller was asked in between).
			assertEquals(Long.valueOf(stateA.commit()), a.getSourceRevision());
			assertTrue(stateA.commit() < a.tHandle().getCreateCommitNumber());
			// The image compiled again from the state of the deployment is the one the controller received,
			// byte for byte, the signature included.
			TLObject sourceA = Deployer.source(project, stateA);
			assertFalse(WrapperHistoryUtils.isCurrent(sourceA));
			assertTrue(Arrays.equals(imageA, RuntimeImage.build(sourceA, 1, T_A).compile(key.seed())));
			assertTrue(Arrays.equals(imageA, bytes(a.getImage())));
			String textA = RuntimeImage.build(project, 1, T_A).getText();

			// State B: an address added, an address renamed, a rule deleted, a function and the page changed,
			// the connections of an address deleted, the NTP server set.
			GroupAddress added;
			List<TLObject> connections;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				added = (GroupAddress) ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.logical:GroupAddress"));
				added.setName("Added after A");
				added.setAddress(new GA(61, null));
				added.setDatapointTypes(List.of(GroupAddressTypes.declaredType(temperature)));
				Set<GroupAddressDefinition> addresses = new LinkedHashSet<>(project.getGroupAddresses());
				addresses.add(added);
				project.setGroupAddresses(addresses);
				temperature.setName("Renamed after A");
				project.getRules().get(0).tDelete();
				((Function) functions.get(1)).setIdentifier("changed-after-a");
				page.setIdentifier("changed");
				page.setMinimumRole(classifier("PageRole", Pages.OPERATOR));
				project.setNtpServer("ntp.example.org");
				// The connections of the room temperature (its type is its own): what its objects derive is gone.
				connections = GroupAddressConnections.connections(temperature);
				for (TLObject connection : connections) {
					connection.tDelete();
				}
				tx.commit();
			}
			RuntimeImage imageB = RuntimeImage.build(project, 2, T_B);
			assertTrue(imageB.getDatapointSources().contains(added));
			assertFalse(textA.equals(imageB.getText()));
			assertFalse(connections.isEmpty());
			assertEquals(List.of(), GroupAddressConnections.connections(temperature));
			Deployment b = (Deployment) Deployer.deploy(project, T_B);
			assertAccepted(b, 2);
			// Only the newest accepted deployment keeps its image (haumacher/ehome #176).
			assertNull(a.getImage());

			// The image of A compiled again: the project as of A, nothing of B.
			RuntimeImage again = RuntimeImage.build(Deployer.source(project, stateA), 1, T_A);
			assertEquals(textA, again.getText());
			assertTrue(Arrays.equals(imageA, again.compile(key.seed())));
			for (TLObject source : again.getDatapointSources()) {
				assertFalse("A historic object of A: " + source, WrapperHistoryUtils.isCurrent(source));
				assertFalse(WrapperHistoryUtils.getCurrent(source) == added);
			}
			assertFalse(again.getText().contains("Added after A"));
			// The connections as they were: the referers of a historic object are those of its revision.
			assertEquals(connections.size(), GroupAddressConnections.connections(WrapperHistoryUtils.getWrapper(
				HistoryUtils.getRevision(stateA.commit()), temperature)).size());
			assertTrue(again.getText().contains("Raumtemperatur"));

			// The same with the access checks of an editor of the project, as in the browser.
			Person editor;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				editor = AccountFixture.person("ehomeRedeployEditor", null);
				ProjectMembers.add(project, editor, ProjectMembers.Role.editor);
				tx.commit();
			}
			assertEquals(textA,
				secured(editor, () -> RuntimeImage.build(Deployer.source(project, stateA), 1, T_A).getText()));

			// Deploy again: the question names the deployment it goes back to and what differs from B.
			assertNull(Deployer.redeployVeto(project, a));
			String time = Deployer.time(a.getTime());
			String confirmation = ImageComparison.redeployConfirmation(project, a);
			assertTrue(confirmation, confirmation.startsWith(text(I18NConstants.CONFIRM_REDEPLOY__REVISION_TIME_NEXT_CONTROLLER
				.fill(Long.valueOf(1), time, Long.valueOf(3), NAME))));
			assertTrue(confirmation,
				confirmation.contains(text(I18NConstants.COMPARE_REVISION__STORED_NEXT.fill(Long.valueOf(2), Long.valueOf(3)))));
			String from = text(I18NConstants.REDEPLOY_FROM__REVISION_TIME.fill(Long.valueOf(1), time));

			// A controller reading an older format than A's image (anonymous pages, 1.1): refused before
			// anything is sent, the record names the deployment it goes back to.
			_fake.setFormat("1.0");
			int puts = _fake.getPuts();
			Deployment old = (Deployment) Deployer.redeploy(project, a, T_C);
			assertEquals(Boolean.FALSE, Boolean.valueOf(old.getAccepted()));
			assertEquals(Deployer.CODE_FORMAT, old.getCode());
			assertEquals(puts, _fake.getPuts());
			assertEquals(from + " " + text(I18NConstants.DEPLOY_REFUSED_FORMAT__CONTROLLER_FORMAT_IMAGE.fill(NAME, "1.0", "1.1")),
				old.getMessage());
			assertEquals(stateA, Deployer.state(old));
			_fake.setFormat("1.1");

			// Accepted: A's image with the next revision, signed with the key the controller holds.
			Deployment c = (Deployment) Deployer.redeploy(project, a, T_C);
			assertAccepted(c, 3);
			assertTrue(String.valueOf(c.getMessage()), String.valueOf(c.getMessage()).startsWith(from));
			byte[] imageC = _fake.getStored();
			assertTrue(Arrays.equals(RuntimeImage.build(Deployer.source(project, stateA), 3, T_A).compile(key.seed()), imageC));
			assertEquals(Json.write(withRevision(ImageDecoder.decode(imageA), 3)), Json.write(ImageDecoder.decode(imageC)));
			assertEquals(Long.valueOf(3), project.getImageRevision());
			assertEquals(List.of(c, old, b, a), project.getDeployments());
			assertEquals(stateA, Deployer.state(c));
			// The record keeps the commit it compiled from and the deployment it went back to.
			assertEquals(Long.valueOf(stateA.commit()), c.getSourceRevision());
			assertEquals(a, c.getRedeployedFrom());
			assertNull(a.getRedeployedFrom());
			assertEquals(text(I18NConstants.DEPLOYMENT_LABEL__REVISION_TIME.fill(Long.valueOf(1), time)),
				MetaLabelProvider.INSTANCE.getLabel(a));

			// The controller stores A's image now: nothing differs.
			confirmation = ImageComparison.redeployConfirmation(project, a);
			assertTrue(confirmation,
				confirmation.startsWith(text(I18NConstants.COMPARE_IDENTICAL_EARLIER__REVISION.fill(Long.valueOf(3)))));

			// Deploying the deployment that went back to A again is A's image once more.
			Deployment d = (Deployment) Deployer.redeploy(project, c, T_C);
			assertAccepted(d, 4);
			assertEquals(Json.write(withRevision(ImageDecoder.decode(imageA), 4)),
				Json.write(ImageDecoder.decode(_fake.getStored())));

			// A refusal of the image of A names the objects of the project as they are now.
			_fake.refuseNext(422, "image.invalid", "the image is invalid",
				List.of(new Refusal("datapoints[0].knx.dpt", "refused for the test")));
			Deployment refused = (Deployment) Deployer.redeploy(project, a, T_C);
			assertEquals(Boolean.FALSE, Boolean.valueOf(refused.getAccepted()));
			// A deployment no controller accepted is no state to go back to.
			assertEquals(I18NConstants.ERROR_REDEPLOY_NOT_ACCEPTED__REVISION.fill(refused.getRevision()),
				Deployer.redeployVeto(project, refused));
			TLObject element = refused.getRefusals().get(0).getElement();
			assertNotNull(element);
			assertTrue(WrapperHistoryUtils.isCurrent(element));
			assertEquals(WrapperHistoryUtils.getCurrent(again.getDatapointSources().get(0)), element);
			// The last valid deployment went again, recorded as an entry of its own, which has no state.
			Deployment resent = project.getDeployments().get(0);
			assertEquals(Boolean.TRUE, Boolean.valueOf(resent.getAccepted()));
			assertNull(resent.getSourceRevision());
			assertNull(Deployer.state(resent));
			assertEquals(I18NConstants.ERROR_REDEPLOY_RESENT__REVISION.fill(Long.valueOf(4)),
				Deployer.redeployVeto(project, resent));

			// A deployment recorded before haumacher/ehome #192 (without the commit it was compiled from): its
			// state is the revision before its record.
			String textNow = RuntimeImage.build(project, 9, T_B).getText();
			Deployment legacy = (Deployment) legacy(project, controller, "loaded", I18NConstants.COMMIT_DEPLOYED__PROJECT_CONTROLLER_REVISION
				.fill(MetaLabelProvider.INSTANCE.getLabel(project), NAME, Long.valueOf(9)));
			Deployer.State legacyState = Deployer.state(legacy);
			assertEquals(legacy.tHandle().getCreateCommitNumber() - 1, legacyState.commit());
			assertEquals(T_B, legacyState.generatedAt());
			TestFunctionProposal.update(project, "ntpServer", "ntp.changed.example.org");
			assertFalse(textNow.equals(RuntimeImage.build(project, 9, T_B).getText()));
			assertEquals(textNow, RuntimeImage.build(Deployer.source(project, legacyState), 9, T_B).getText());
			Deployment fromLegacy = (Deployment) Deployer.redeploy(project, legacy, T_C);
			assertAccepted(fromLegacy, 5);
			assertTrue(Arrays.equals(RuntimeImage.build(Deployer.source(project, legacyState), 5, T_B).compile(key.seed()),
				_fake.getStored()));
			// One that sent an earlier image again (it read no status of the controller) has none.
			Deployment legacyResend = (Deployment) legacy(project, controller, null, I18NConstants.COMMIT_DEPLOYED__PROJECT_CONTROLLER_REVISION
				.fill(MetaLabelProvider.INSTANCE.getLabel(project), NAME, Long.valueOf(9)));
			assertNull(Deployer.state(legacyResend));

			// An unload, nothing selected, an entry of another project.
			Deployment unload = (Deployment) Deployer.unload(project, T_C);
			assertEquals(I18NConstants.ERROR_REDEPLOY_UNLOAD, Deployer.redeployVeto(project, unload));
			assertEquals(I18NConstants.ERROR_REDEPLOY_NO_DEPLOYMENT, Deployer.redeployVeto(project, null));
			TLObject other = TestRuleAuthoring.createProject();
			try {
				assertEquals(I18NConstants.ERROR_REDEPLOY_NO_DEPLOYMENT, Deployer.redeployVeto(other, a));
				try {
					Deployer.redeploy(other, a, T_C);
					fail("An entry of another project is refused.");
				} catch (TopLogicException ex) {
					assertEquals(I18NConstants.ERROR_REDEPLOY_NO_DEPLOYMENT, ex.getErrorKey());
				}
			} finally {
				SharedData.deleteProjects(List.of(other));
			}

			// A deployment by a thread whose view is older than the last commit (haumacher/ehome #199): its
			// state is the revision its image was compiled from, resolvable in that thread.
			List<TLObject> elsewhere = new ArrayList<>();
			try {
				OlderView.run(() -> elsewhere.add(TestRuleAuthoring.createProject()), view -> {
					Deployer.State lateState = Deployer.state(Deployer.deploy(project, T_C));
					assertEquals(view, lateState.commit());
					assertNotNull(Deployer.source(project, lateState));
				});
			} finally {
				SharedData.deleteProjects(elsewhere);
			}
		} finally {
			SharedData.deleteProjects(projects);
			SharedData.deleteProjects(controllers);
		}
	}

	/**
	 * A deployment as recorded before haumacher/ehome #192: without the commit it was compiled from,
	 * at {@link #T_B}.
	 *
	 * @param before
	 *        What the controller ran before, <code>null</code> for an image sent again.
	 */
	private static TLObject legacy(TLObject project, TLObject controller, String before, ResKey message) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(message)) {
			Deployment deployment = (Deployment) ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Deployment"));
			deployment.setRevision(Long.valueOf(9));
			deployment.setTime(Date.from(T_B));
			deployment.setController((Controller) controller);
			deployment.setAccepted(Boolean.TRUE);
			deployment.setBefore(before);
			List<Deployment> deployments = new ArrayList<>();
			deployments.add(deployment);
			deployments.addAll(((Project) project).getDeployments());
			((Project) project).setDeployments(deployments);
			tx.commit();
			return deployment;
		}
	}

	/** The decoded image with another revision in its header. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> withRevision(Map<String, Object> image, long revision) {
		Map<String, Object> result = new LinkedHashMap<>(image);
		Map<String, Object> header = new LinkedHashMap<>((Map<String, Object>) image.get("image"));
		header.put("revision", Long.valueOf(revision));
		result.put("image", header);
		return result;
	}

	/** The computation with the access checks on, for the given person. */
	@SuppressWarnings("unchecked")
	private static <T> T secured(Person person, Supplier<T> computation) {
		Object[] result = { null };
		ThreadContext.withSecurityChecks(true, () -> {
			result[0] = TLContextManager.inPersonContext(person, computation::get);
		});
		return (T) result[0];
	}

	private static TLObject address(TLObject project, String name) {
		return SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
			.filter(a -> name.equals(((GroupAddress) a).getName())).findFirst().orElseThrow();
	}

	private static TLClassifier classifier(String enumeration, String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:" + enumeration)).getClassifier(name);
	}

	private static TLObject controller(String name, int port) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Controller controller = (Controller) ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			controller.setName(name);
			controller.setHost("127.0.0.1");
			controller.setPort(Integer.valueOf(port));
			controller.setToken(TOKEN);
			tx.commit();
			return controller;
		}
	}

	private static void assertAccepted(TLObject object, long revision) {
		Deployment deployment = (Deployment) object;
		List<String> refusals = new ArrayList<>();
		for (de.haumacher.ehome.model.runtime.Refusal refusal : deployment.getRefusals()) {
			refusals.add(refusal.getPath() + ": " + refusal.getReason());
		}
		assertEquals(deployment.getMessage() + " " + refusals, Boolean.TRUE, Boolean.valueOf(deployment.getAccepted()));
		assertEquals(Long.valueOf(revision), deployment.getRevision());
		assertEquals(List.of(), deployment.getRefusals());
	}


	private static byte[] bytes(Object data) throws Exception {
		try (InputStream in = ((BinaryData) data).getStream()) {
			return in.readAllBytes();
		}
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRedeployment.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE, SearchBuilder.Module.INSTANCE,
			TLSecurityDeviceManager.Module.INSTANCE, InitialRolesManager.Module.INSTANCE,
			InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE, SecurityConfigurationService.Module.INSTANCE,
			CommandGroupRegistry.Module.INSTANCE, ProjectOwners.Module.INSTANCE));
	}

}
