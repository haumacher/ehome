/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ControllerStatus;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageComparison;
import de.haumacher.ehome.runtime.ImageComparison.Result;
import de.haumacher.ehome.runtime.ImageComparison.State;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The remaining pieces of the deployment protocol (haumacher/ehome #47) against the
 * {@link FakeController}, with <code>samples/Test.knxproj</code>: before a deployment the image the
 * controller stores (<code>GET /api/image</code>) is compared with the one to deploy (no image,
 * nothing to deploy, a changed project, another project's image with rules, an image E-Home cannot
 * read, a controller that does not answer); an unload (<code>DELETE /api/image</code>) is recorded
 * as a deployment marked as one, done, failing to remove the stored copy, refused or unanswered.
 *
 * <p>
 * The project is imported once and all cases run in one test (the import is the cost of the test).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageComparison extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static final Instant LATER = Instant.parse("2026-10-18T13:30:00Z");

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

	public void testCompareAndUnload() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		List<TLObject> controllers = new ArrayList<>();
		try {
			TLObject project = projects.get(0);
			TLObject controller = controller(_fake.getPort());
			controllers.add(controller);
			update(project, "controller", controller);

			// No image stored: the comparison says so and names what the image to deploy holds.
			Result none = ImageComparison.compare(project, NOW);
			assertEquals(State.NONE, none.state());
			assertTrue(none.text(), none.text().startsWith(text(I18NConstants.COMPARE_NO_IMAGE)));
			assertTrue(ImageComparison.confirmation(project, NOW)
				.startsWith(text(I18NConstants.CONFIRM_DEPLOY__REVISION_CONTROLLER.fill(1L, "Fake"))));

			// Deployed: nothing to deploy, however much later (the time and revision of the stored image).
			Deployer.deploy(project, NOW);
			Result identical = ImageComparison.compare(project, LATER);
			assertEquals(State.IDENTICAL, identical.state());
			assertEquals(Long.valueOf(1), identical.revision());
			assertEquals(text(I18NConstants.COMPARE_IDENTICAL__REVISION.fill(1L)), identical.text());
			assertEquals(identical.text() + "\n" + text(I18NConstants.CONFIRM_DEPLOY_AGAIN__REVISION.fill(2L)),
				ImageComparison.confirmation(project, LATER));

			// A changed project: the revisions, the counts and the changed parts.
			TLObject address = RuntimeImage.build(project, 1, NOW).getDatapointSources().get(0);
			Object name = address.tValueByName("name");
			update(address, "name", "Renamed");
			Result changed = ImageComparison.compare(project, LATER);
			assertEquals(State.DIFFERENT, changed.state());
			List<String> lines = List.of(changed.text().split("\n"));
			assertEquals(text(I18NConstants.COMPARE_REVISION__STORED_NEXT.fill(1L, 2L)), lines.get(0));
			int datapoints = RuntimeImage.build(project, 2, LATER).getDatapointSources().size();
			int functions = FunctionProposal.functions(project).size();
			assertEquals(text(I18NConstants.COMPARE_STORED_COUNTS__DATAPOINTS_LOCATIONS_FUNCTIONS.fill(datapoints,
				0, functions)), lines.get(1));
			assertEquals(text(I18NConstants.COMPARE_NEW_COUNTS__DATAPOINTS_LOCATIONS_FUNCTIONS.fill(datapoints,
				0, functions)), lines.get(2));
			assertEquals(text(I18NConstants.COMPARE_CHANGED_PARTS__PARTS.fill("datapoints")), lines.get(3));
			assertEquals(4, lines.size());
			assertTrue(ImageComparison.confirmation(project, LATER)
				.startsWith(text(I18NConstants.CONFIRM_DEPLOY__REVISION_CONTROLLER.fill(2L, "Fake")) + "\n\n"
					+ lines.get(0)));
			update(address, "name", name);
			assertEquals(State.IDENTICAL, ImageComparison.compare(project, LATER).state());

			// The image of another project with rules and schedules the new image does not contain.
			@SuppressWarnings("unchecked")
			Map<String, Object> other = (Map<String, Object>) Json.parse(_fake.getStoredText(), 100);
			Map<String, Object> header = new LinkedHashMap<>((Map<String, Object>) other.get("image"));
			header.put("projectId", "other-house");
			header.put("name", "Other house");
			header.put("revision", Long.valueOf(7));
			other.put("image", header);
			other.put("rules", List.of(Map.of("id", "r1"), Map.of("id", "r2")));
			other.put("schedules", List.of(Map.of("id", "s1")));
			_fake.store(Json.write(other));
			Result foreign = ImageComparison.compare(project, LATER);
			assertEquals(State.DIFFERENT, foreign.state());
			lines = List.of(foreign.text().split("\n"));
			assertEquals(text(I18NConstants.COMPARE_OTHER_PROJECT__PROJECT_NAME_REVISION.fill("other-house",
				"Other house", 7L)), lines.get(0));
			assertEquals(text(I18NConstants.COMPARE_CHANGED_PARTS__PARTS.fill("image, rules, schedules")),
				lines.get(3));
			assertEquals(text(I18NConstants.COMPARE_RULES_REMOVED__RULES_SCHEDULES.fill(2, 1)), lines.get(4));

			// A stored rule the new image has as well is not removed: only r2 and s1 are.
			List<TLObject> own;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				own = new RuleImport().importRules(project, List.of(Map.of("id", "r1", "name", "R1",
					"reactions", List.of())));
				tx.commit();
			}
			lines = List.of(ImageComparison.compare(project, LATER).text().split("\n"));
			assertEquals(text(I18NConstants.COMPARE_RULES_REMOVED__RULES_SCHEDULES.fill(1, 1)), lines.get(4));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				own.forEach(TLObject::tDelete);
				tx.commit();
			}

			// An image E-Home cannot read, a wrong token, no answer.
			_fake.store("[1, 2, 3]");
			assertEquals(text(I18NConstants.COMPARE_UNREADABLE), ImageComparison.compare(project, LATER).text());
			update(controller, "token", "hct_ffffffffffffffffffffffffffffffff");
			Result refused = ImageComparison.compare(project, LATER);
			assertEquals(State.UNKNOWN, refused.state());
			assertTrue(refused.text(), refused.text().contains("401"));
			update(controller, "token", TOKEN);

			// Unload: recorded with the revision the controller ran, the project's revision stays.
			TLObject second = Deployer.deploy(project, LATER);
			assertEquals(Boolean.TRUE, second.tValueByName("accepted"));
			assertEquals(Boolean.FALSE, second.tValueByName("unload"));
			TLObject unload = Deployer.unload(project, LATER);
			assertEquals(Boolean.TRUE, unload.tValueByName("unload"));
			assertEquals(Boolean.TRUE, unload.tValueByName("accepted"));
			assertEquals(Long.valueOf(2), unload.tValueByName("revision"));
			assertEquals(Integer.valueOf(200), unload.tValueByName("status"));
			assertNull(unload.tValueByName("bytes"));
			assertEquals(text(I18NConstants.UNLOAD_DONE__CONTROLLER.fill("Fake")), unload.tValueByName("message"));
			assertTrue(String.valueOf(unload.tValueByName("before")).contains("2"));
			assertNull(_fake.getStoredText());
			assertEquals(1, _fake.getDeletes());
			assertEquals(Long.valueOf(2), project.tValueByName("imageRevision"));
			assertEquals(unload, ((List<?>) project.tValueByName("deployments")).get(0));
			assertEquals(State.NONE, ImageComparison.compare(project, LATER).state());
			assertTrue(ControllerStatus.drift(project)
				.startsWith(text(I18NConstants.DRIFT_NO_IMAGE__DEPLOYED.fill(2L))));

			// The stored copy could not be removed: unloaded anyway, recorded as not done.
			Deployer.deploy(project, LATER);
			_fake.storageErrorOnNextDelete();
			TLObject storageError = Deployer.unload(project, LATER);
			assertEquals(Boolean.FALSE, storageError.tValueByName("accepted"));
			assertEquals(Integer.valueOf(500), storageError.tValueByName("status"));
			assertEquals("image.storage_error", storageError.tValueByName("code"));
			assertEquals(Long.valueOf(3), storageError.tValueByName("revision"));

			// Refused (a wrong token): nothing is unloaded. Unanswered: recorded as unreachable.
			Deployer.deploy(project, LATER);
			update(controller, "token", "hct_ffffffffffffffffffffffffffffffff");
			TLObject unauthorized = Deployer.unload(project, LATER);
			assertEquals(Boolean.FALSE, unauthorized.tValueByName("accepted"));
			assertEquals("auth.token_invalid", unauthorized.tValueByName("code"));
			assertEquals(2, _fake.getDeletes());
			assertNotNull(_fake.getStoredText());
			update(controller, "token", TOKEN);
			_fake.stop();
			TLObject unreachable = Deployer.unload(project, LATER);
			assertEquals(Deployer.CODE_UNREACHABLE, unreachable.tValueByName("code"));
			assertNull(unreachable.tValueByName("revision"));
			assertEquals(State.UNKNOWN, ImageComparison.compare(project, LATER).state());
		} finally {
			SharedData.deleteProjects(projects);
			SharedData.deleteProjects(controllers);
		}
	}

	/**
	 * Probe: functions proposed and deployed; a dismissed function makes the stored image differ in its
	 * functions, a new proposal run does not bring it back, so the project stays different.
	 */
	public void testDismissedFunctionAfterDeploy() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		List<TLObject> controllers = new ArrayList<>();
		try {
			TLObject project = projects.get(0);
			TLObject controller = controller(_fake.getPort());
			controllers.add(controller);
			update(project, "controller", controller);
			TestFunctionProposal.apply(project);
			int functions = FunctionProposal.functions(project).size();
			assertTrue(functions > 2);
			Deployer.deploy(project, NOW);
			assertEquals(State.IDENTICAL, ImageComparison.compare(project, LATER).state());

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertNotNull(FunctionProposal.dismiss(FunctionProposal.functions(project).get(0)));
				tx.commit();
			}
			assertEquals(List.of(), TestFunctionProposal.apply(project));
			Result changed = ImageComparison.compare(project, LATER);
			assertEquals(State.DIFFERENT, changed.state());
			assertTrue(changed.text(), changed.text().contains(
				text(I18NConstants.COMPARE_NEW_COUNTS__DATAPOINTS_LOCATIONS_FUNCTIONS.fill(
					RuntimeImage.build(project, 2, LATER).getDatapointSources().size(), 0, functions - 1))));
			assertTrue(changed.text(), changed.text().contains(
				text(I18NConstants.COMPARE_CHANGED_PARTS__PARTS.fill("functions"))));
		} finally {
			SharedData.deleteProjects(projects);
			SharedData.deleteProjects(controllers);
		}
	}

	private static TLObject controller(int port) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject controller = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			controller.tUpdateByName("name", "Fake");
			controller.tUpdateByName("host", "127.0.0.1");
			controller.tUpdateByName("port", Integer.valueOf(port));
			controller.tUpdateByName("token", TOKEN);
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestImageComparison.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
