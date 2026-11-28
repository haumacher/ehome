/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import com.top_logic.model.impl.TransientObjectFactory;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.BlockInstance;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.IntervalSchedule;
import de.haumacher.ehome.model.runtime.ObjectFollower;
import de.haumacher.ehome.model.runtime.Page;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleChoice;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.RuleTimeline;
import de.haumacher.ehome.model.runtime.TimelineAssignment;
import de.haumacher.ehome.model.runtime.TimelinePlan;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuleDrafts;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.simulation.SimulationFunctions;

/**
 * The create dialogs store what they show (haumacher/ehome #205): no multilingual text is preset,
 * a name entered in one language is stored in that language only, an optional label left empty
 * stays empty, and a draft that is cancelled stores nothing. On the functions the dialogs call; an
 * empty project serves, the object follower takes a device of the sample with a single device.
 */
@SuppressWarnings("javadoc")
public class TestCreateDialogs extends BasicTestCase {

	/** A name as the dialog's field enters it: in the user's language only. */
	private static final ResKey NAME = ResKey.builder().add(Locale.GERMAN, "Eingegeben").build();

	public void testRuleParts() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			long revision = revision();
			TLObject rule = RuleDrafts.draft(project, "rules", runtime("Rule"));
			assertNull("No name is preset.", rule.tValueByName(Rule.NAME_ATTR));
			TLObject schedule = RuleDrafts.draft(project, "schedules", runtime("WeeklySchedule"));
			assertNull(schedule.tValueByName(WeeklySchedule.LABEL_ATTR));
			assertEquals("The other defaults the dialog shows stay.", "06:00",
				schedule.tValueByName(WeeklySchedule.TIME_ATTR));
			TLObject datapoint = RuleDrafts.draft(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			assertNull(datapoint.tValueByName(ApplicationDatapoint.LABEL_ATTR));
			TLObject unlabelled = RuleDrafts.draft(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			unlabelled.tUpdateByName(ApplicationDatapoint.IDENTIFIER_ATTR, "value2");
			assertEquals("Cancel: nothing stored.", revision, revision());

			rule.tUpdateByName(Rule.NAME_ATTR, NAME);
			schedule.tUpdateByName(WeeklySchedule.LABEL_ATTR, NAME);
			datapoint.tUpdateByName(ApplicationDatapoint.LABEL_ATTR, NAME);
			Rule storedRule;
			WeeklySchedule storedSchedule;
			ApplicationDatapoint storedDatapoint;
			ApplicationDatapoint storedUnlabelled;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				storedRule = (Rule) RuleDrafts.add(rule);
				storedSchedule = (WeeklySchedule) RuleDrafts.add(schedule);
				storedDatapoint = (ApplicationDatapoint) RuleDrafts.add(datapoint);
				storedUnlabelled = (ApplicationDatapoint) RuleDrafts.add(unlabelled);
				tx.commit();
			}
			assertEquals(Set.of("de"), languages(storedRule.getNameKey()));
			assertEquals("rule-1", storedRule.getIdentifier());
			assertEquals(List.of(storedRule), RuleFunctions.objects(project, "rules"));
			assertEquals(Set.of("de"), languages(storedSchedule.getLabel()));
			assertEquals("06:00", storedSchedule.getTime());
			assertEquals(Set.of("de"), languages(storedDatapoint.getLabel()));
			assertNull("An optional label left empty stays empty.", storedUnlabelled.getLabel());

			TLObject parameter = RuleDrafts.draft(storedRule, "parameters", runtime("RuleParameter"));
			assertNull(parameter.tValueByName(RuleParameter.TITLE_ATTR));
			parameter.tUpdateByName(RuleParameter.TITLE_ATTR, NAME);
			RuleParameter storedParameter;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				storedParameter = (RuleParameter) RuleDrafts.add(parameter);
				tx.commit();
			}
			assertEquals(Set.of("de"), languages(storedParameter.getTitle()));
			assertEquals("The default the dialog shows is stored.", "0.0", storedParameter.getDefault());

			TLObject choice = RuleDrafts.draft(storedParameter, "choices", runtime("RuleChoice"));
			assertNull(choice.tValueByName(RuleChoice.TITLE_ATTR));
			choice.tUpdateByName(RuleChoice.TITLE_ATTR, NAME);
			TLObject untitled = RuleDrafts.draft(storedParameter, "choices", runtime("RuleChoice"));
			untitled.tUpdateByName(RuleChoice.VALUE_ATTR, "1.0");
			RuleChoice storedChoice;
			RuleChoice storedUntitled;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				storedChoice = (RuleChoice) RuleDrafts.add(choice);
				storedUntitled = (RuleChoice) RuleDrafts.add(untitled);
				tx.commit();
			}
			assertEquals(Set.of("de"), languages(storedChoice.getTitle()));
			assertNull(storedUntitled.getTitle());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * A field the user empties in the dialog stays empty: "Create" does not fill it again.
	 */
	public void testEmptiedField() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject entry = RuleDrafts.draft(project, "schedules", runtime("IntervalSchedule"));
			assertEquals("22:00", entry.tValueByName(IntervalSchedule.UNTIL_ATTR));
			entry.tUpdateByName(IntervalSchedule.LABEL_ATTR, NAME);
			entry.tUpdateByName(IntervalSchedule.UNTIL_ATTR, null);
			IntervalSchedule stored;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stored = (IntervalSchedule) RuleDrafts.add(entry);
				tx.commit();
			}
			assertNull(stored.getUntil());
			assertEquals("06:00", stored.getFrom());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * "New timeline" asks for the first day plan; timeline, plan and assignment are stored on
	 * "Create" only.
	 */
	public void testTimeline() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			Rule rule;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				rule = (Rule) RuleFunctions.create(project, "rules", runtime("Rule"));
				rule.setNameKey(NAME);
				tx.commit();
			}
			long revision = revision();
			TLObject plan = RuleDrafts.draft(rule, "timeline", runtime("RuleTimeline"));
			assertEquals("TimelinePlan", plan.tType().getName());
			assertTrue(plan.tTransient());
			assertEquals("plan1", plan.tValueByName(TimelinePlan.IDENTIFIER_ATTR));
			assertNull(plan.tValueByName(TimelinePlan.LABEL_ATTR));
			assertEquals(List.of("identifier", "label"), RuleDrafts.fields(plan));
			assertSame("The access of Create is checked on the rule.", rule, RuleDrafts.owner(plan));
			assertEquals("Cancel: nothing stored.", revision, revision());
			assertNull(rule.getTimeline());

			plan.tUpdateByName(TimelinePlan.LABEL_ATTR, NAME);
			TimelinePlan stored;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stored = (TimelinePlan) RuleDrafts.add(plan);
				tx.commit();
			}
			assertFalse(stored.tTransient());
			RuleTimeline timeline = (RuleTimeline) rule.getTimeline();
			assertNotNull(timeline);
			assertEquals(List.of(stored), RuleFunctions.objects(timeline, "plans"));
			assertEquals(Set.of("de"), languages(stored.getLabel()));
			assertEquals("plan1", stored.getIdentifier());
			List<TLObject> assignments = RuleFunctions.objects(timeline, "assignments");
			assertEquals(1, assignments.size());
			assertSame("The assignment names the stored plan.", stored, ((TimelineAssignment) assignments.get(0)).getPlan());
			assertTrue(Boolean.TRUE.equals(timeline.getAdjustable()));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testPage() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject page = RuleDrafts.pageDraft(project, null, Pages.PAGE);
			assertNull(page.tValueByName(Page.NAME_ATTR));
			page.tUpdateByName(Page.NAME_ATTR, NAME);
			Page stored;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stored = (Page) RuleDrafts.add(page);
				tx.commit();
			}
			assertEquals(Set.of("de"), languages(stored.getNameKey()));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * "New function": the draft of the create dialog has neither name nor kind; the stored function
	 * has the name in the language it was entered in.
	 */
	public void testFunction() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			long revision = revision();
			TLObject draft = TransientObjectFactory.INSTANCE.createObject(runtime("Function"), project);
			assertNull(draft.tValueByName(Function.NAME_ATTR));
			assertNull("No kind is preset.", draft.tValueByName(Function.KIND_ATTR));
			assertEquals("Cancel: nothing stored.", revision, revision());

			draft.tUpdateByName(Function.NAME_ATTR, NAME);
			draft.tUpdateByName(Function.KIND_ATTR, FunctionProposal.classifier("switch"));
			Function stored;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stored = (Function) RuleDrafts.persist(draft, project, Project.FUNCTIONS_ATTR).get(draft);
				tx.commit();
			}
			assertEquals(List.of(stored), ((Project) project).getFunctions());
			assertEquals(Set.of("de"), languages(stored.getNameKey()));
			assertEquals("switch", stored.getKind().getName());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * A virtual device has the name entered, no fallback to the type's label.
	 */
	public void testVirtualDevice() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			String type = BlockInstances.typeKey(RoomController.INSTANCE.manifest());
			BlockInstance stored;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stored = (BlockInstance) BlockInstances.create(project, type, null, NAME);
				tx.commit();
			}
			assertEquals(Set.of("de"), languages(stored.getNameKey()));
			assertEquals("eingegeben", stored.getIdentifier());

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				BlockInstances.create(project, type, null, null);
				fail("A virtual device needs a name.");
			} catch (IllegalArgumentException ex) {
				// Expected.
			}
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * "New object follower": stored with device, switching object and state object as entered;
	 * refused without all three.
	 */
	public void testObjectFollower() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject project = projects.get(0);
			TLObject device = SharedData.within("ehome.physical:Device", projects).stream()
				.filter(d -> ((Device) d).getConnectors().size() > 1).findFirst().orElseThrow();
			List<?> connectors = new ArrayList<>(((Device) device).getConnectors());
			long revision = revision();
			TLObject draft = TransientObjectFactory.INSTANCE.createObject(runtime("ObjectFollower"), project);
			draft.tUpdateByName(ObjectFollower.DEVICE_ATTR, device);
			draft.tUpdateByName(ObjectFollower.SOURCE_ATTR, connectors.get(0));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				SimulationFunctions.addFollower(draft);
				fail("Refused without the state object.");
			} catch (TopLogicException ex) {
				assertEquals(de.haumacher.ehome.simulation.I18NConstants.ERROR_FOLLOWER_INCOMPLETE, ex.getErrorKey());
			}
			assertEquals("Refused: nothing stored.", revision, revision());
			assertEquals(List.of(), ((Project) project).getSimulationFollowers());

			draft.tUpdateByName(ObjectFollower.FOLLOWER_ATTR, connectors.get(1));
			ObjectFollower stored;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stored = SimulationFunctions.addFollower(draft);
				tx.commit();
			}
			assertEquals(List.of(stored), ((Project) project).getSimulationFollowers());
			assertSame(device, stored.getDevice());
			assertSame(connectors.get(0), stored.getSource());
			assertSame(connectors.get(1), stored.getFollower());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * The import of a runtime image keeps the languages of its labels: a plain string, which has no
	 * language, in the user's language only; a language map in the application languages it gives
	 * (a regional tag is another language), a map in none of them in the user's language.
	 */
	public void testImportedLabels() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			String image = """
				{"rules": [{"id": "plain", "name": "Licht",
					"parameters": [{"id": "level", "label": {"de": "Helligkeit", "fr": "Luminosité"}, "type": "real",
						"default": "0.0"}],
					"reactions": [{"triggers": [{"kind": "parameter", "parameter": "level"}],
						"actions": [{"kind": "log", "message": "'changed'"}]}]}],
				 "schedules": [{"id": "evening", "label": {"de": "Abend", "de-CH": "Abig"}, "kind": "weekly", "time": "18:00"},
					{"id": "night", "label": {"fr": "Nuit"}, "kind": "weekly", "time": "22:00"}]}
				""";
			RuleImport importer = new RuleImport();
			RuleImport.Imported imported;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				imported = importer.importImage(project, image);
				tx.commit();
			}
			String user = FunctionProposal.userLanguage();
			Rule rule = (Rule) imported.rules().get(0);
			assertEquals(Set.of(user), languages(rule.getNameKey()));
			assertEquals(Set.of("de"), languages(rule.getParameters().get(0).getTitle()));
			assertEquals(Set.of("de"), languages(((WeeklySchedule) imported.schedules().get(0)).getLabel()));
			WeeklySchedule night = (WeeklySchedule) imported.schedules().get(1);
			assertEquals(Set.of(user), languages(night.getLabel()));
			assertEquals("Nuit", ((ResKey.LiteralKey) night.getLabel()).getTranslations().values().iterator().next());
			assertTrue(importer.getNotes().toString(), importer.getNotes().toString().contains("fr"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static Set<String> languages(Object text) {
		assertTrue("A text entered in languages: " + text, text instanceof ResKey.LiteralKey);
		Set<String> result = new java.util.HashSet<>();
		for (Locale locale : ((ResKey.LiteralKey) text).getTranslations().keySet()) {
			result.add(locale.getLanguage());
		}
		return result;
	}

	private static long revision() {
		return PersistencyLayer.getKnowledgeBase().getHistoryManager().getLastRevision();
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestCreateDialogs.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
