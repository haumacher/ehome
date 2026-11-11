/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleExport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.runtime.RuleReferenceMigration;
import de.haumacher.ehome.runtime.RuleReferences;

/**
 * The references of rules and schedule entries to the objects they name (haumacher/ehome #90), on a
 * project without an ETS import: two group addresses (one commanded, one not) and one without a
 * datapoint type, three application datapoints, a rule with a timer and a parameter, two schedule
 * entries.
 *
 * <ul>
 * <li>The options of each field are the stated set: every datapoint of the image for a trigger, the
 * writable ones for a write, none an application datapoint for a read, the boolean application
 * datapoints not commanded for an output, the timers and parameters of the rule, the entries of the
 * project; each labeled with its id.</li>
 * <li>The image writes the id of the referenced object, also after the id changed.</li>
 * <li>Deleting a referenced object leaves the trigger or action with an empty reference and the
 * controller's refusal at the field.</li>
 * <li>An image naming an id the project does not have is refused with the id named; a matching one is
 * imported with the references set.</li>
 * <li>The migration of the stored id texts sets the references, an unknown id leaves one empty.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestRuleReferences extends BasicTestCase {

	private TLObject _dpt;

	private TLObject _project;

	private TLObject _light;

	private TLObject _sensor;

	private TLObject _untyped;

	private TLObject _flag;

	private TLObject _level;

	private TLObject _switch;

	private TLObject _rule;

	private TLObject _timer;

	private TLObject _parameter;

	private TLObject _weekly;

	private TLObject _astro;

	private TLObject _reaction;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_dpt = create("ehome.datapoint:DatapointMainType");
			_dpt.tUpdateByName("number", Integer.valueOf(1));
			_dpt.tUpdateByName("extId", "TEST-RULE-REFERENCES-1");
			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Rule references");
			_light = address("Licht", 0x0801, _dpt, true);
			_sensor = address("Bewegung", 0x0802, _dpt, false);
			_untyped = address("Ohne Typ", 0x0809, null, false);
			_project.tUpdateByName("groupAddresses", List.of(_light, _sensor, _untyped));

			_flag = datapoint("flag", "boolean", false);
			_level = datapoint("level", "unsigned", true);
			_switch = datapoint("switch", "boolean", true);

			_rule = RuleFunctions.create(_project, "rules", runtime("Rule"));
			_rule.tUpdateByName("identifier", "r");
			_timer = RuleFunctions.create(_rule, "timers", runtime("RuleTimer"));
			_parameter = RuleFunctions.create(_rule, "parameters", runtime("RuleParameter"));
			_reaction = RuleFunctions.create(_rule, "reactions", runtime("Reaction"));

			_weekly = RuleFunctions.create(_project, "schedules", runtime("WeeklySchedule"));
			_weekly.tUpdateByName("identifier", "morning");
			_weekly.tUpdateByName("timeAdjustable", Boolean.TRUE);
			_astro = RuleFunctions.create(_project, "schedules", runtime("AstroSchedule"));
			_astro.tUpdateByName("identifier", "dusk");
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_dpt.tDelete();
			tx.commit();
		}
		super.tearDown();
	}

	public void testOptions() {
		TLObject trigger = part(_reaction, "triggers", "DatapointTrigger");
		TLObject write = part(_reaction, "actions", "WriteAction");
		TLObject read = part(_reaction, "actions", "ReadAction");
		TLObject start = part(_reaction, "actions", "StartTimerAction");
		TLObject set = part(_reaction, "actions", "SetParameterAction");
		TLObject scheduled = part(_reaction, "triggers", "ScheduleTrigger");

		List<TLObject> triggers = RuleReferences.options(trigger, RuleReferences.TRIGGER);
		assertTrue(triggers.toString(), triggers.containsAll(List.of(_light, _sensor, _flag, _level, _switch, _rule,
			_parameter, _weekly)));
		assertFalse("A group address the image cannot carry.", triggers.contains(_untyped));
		assertFalse("An entry without an adjustable time is no datapoint.", triggers.contains(_astro));

		List<TLObject> writes = RuleReferences.options(write, RuleReferences.WRITE);
		assertTrue(writes.toString(), writes.containsAll(List.of(_light, _level, _switch)));
		assertFalse(writes.contains(_sensor));
		// An application datapoint that is not writable: rules write it (haumacher/ehome #138).
		assertTrue(writes.contains(_flag));

		List<TLObject> reads = RuleReferences.options(read, RuleReferences.READ);
		for (TLObject datapoint : List.of(_flag, _level, _switch)) {
			assertFalse("No technology reads an application datapoint.", reads.contains(datapoint));
		}

		assertEquals(List.of(_flag), RuleReferences.options(_astro, RuleReferences.OUTPUT));
		assertEquals(List.of(_timer), RuleReferences.options(start, RuleReferences.TIMER));
		assertEquals(List.of(_parameter), RuleReferences.options(set, RuleReferences.PARAMETER));
		assertEquals(List.of(_weekly, _astro), RuleReferences.options(scheduled, RuleReferences.SCHEDULE));

		// The labels name the id the image uses.
		assertEquals("Licht (1/0/1)", RuleReferences.optionLabel(_light, RuleReferences.TRIGGER));
		String flag = RuleReferences.optionLabel(_flag, RuleReferences.OUTPUT);
		assertTrue(flag, flag.endsWith(" (flag)"));
		assertEquals("t1", RuleReferences.optionLabel(_timer, RuleReferences.TIMER));
		String parameter = RuleReferences.optionLabel(_parameter, RuleReferences.TRIGGER);
		assertTrue(parameter, parameter.endsWith("(rule/r/p1)"));
		String own = RuleReferences.optionLabel(_parameter, RuleReferences.PARAMETER);
		assertTrue(own, own.endsWith("(p1)"));
	}

	public void testExportFollowsIds() {
		TLObject write;
		TLObject start;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			RuleFunctions.create(_reaction, "triggers", runtime("ScheduleTrigger")).tUpdateByName("schedule", _astro);
			write = RuleFunctions.create(_reaction, "actions", runtime("WriteAction"));
			write.tUpdateByName("datapoint", _level);
			write.tUpdateByName("value", "1");
			start = RuleFunctions.create(_reaction, "actions", runtime("StartTimerAction"));
			start.tUpdateByName("timer", _timer);
			_astro.tUpdateByName("output", _flag);
			tx.commit();
		}
		assertEquals(List.of("dusk", "level", "t1"), ids());
		assertEquals("flag", schedule(1).get("output"));

		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_level.tUpdateByName("identifier", "level2");
			_timer.tUpdateByName("identifier", "off");
			_astro.tUpdateByName("identifier", "evening");
			_flag.tUpdateByName("identifier", "flag2");
			tx.commit();
		}
		assertEquals(List.of("evening", "level2", "off"), ids());
		assertEquals("flag2", schedule(1).get("output"));
		assertEquals("write New datapoint (level2) := 1", RuleFunctions.summary(write));
	}

	/** The ids of the schedule trigger, the write and the timer start of the rule's reaction. */
	private List<Object> ids() {
		Map<?, ?> reaction = (Map<?, ?>) ((List<?>) ((Map<?, ?>) new RuleExport().rules(_project).get(0)).get("reactions")).get(0);
		Map<?, ?> trigger = (Map<?, ?>) ((List<?>) reaction.get("triggers")).get(0);
		List<?> actions = (List<?>) reaction.get("actions");
		return List.of(trigger.get("schedule"), ((Map<?, ?>) actions.get(0)).get("datapoint"),
			((Map<?, ?>) actions.get(1)).get("timer"));
	}

	private Map<?, ?> schedule(int index) {
		return (Map<?, ?>) new RuleExport().schedules(_project).get(index);
	}

	public void testDeleteClears() {
		TLObject trigger;
		TLObject start;
		TLObject set;
		TLObject scheduled;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			trigger = RuleFunctions.create(_reaction, "triggers", runtime("DatapointTrigger"));
			trigger.tUpdateByName("datapoint", _light);
			scheduled = RuleFunctions.create(_reaction, "triggers", runtime("ScheduleTrigger"));
			scheduled.tUpdateByName("schedule", _astro);
			start = RuleFunctions.create(_reaction, "actions", runtime("StartTimerAction"));
			start.tUpdateByName("timer", _timer);
			set = RuleFunctions.create(_reaction, "actions", runtime("SetParameterAction"));
			set.tUpdateByName("parameter", _parameter);
			set.tUpdateByName("value", "0");
			tx.commit();
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_light.tDelete();
			_astro.tDelete();
			_timer.tDelete();
			_parameter.tDelete();
			tx.commit();
		}
		for (TLObject part : List.of(trigger, scheduled, start, set)) {
			assertTrue("The part stays.", part.tValid());
		}
		assertNull(trigger.tValueByName("datapoint"));
		assertNull(scheduled.tValueByName("schedule"));
		assertNull(start.tValueByName("timer"));
		assertNull(set.tValueByName("parameter"));
		List<String> warnings = new RuleForm(trigger).warnings("datapoint");
		assertEquals(warnings.toString(), 1, warnings.size());
		assertTrue(warnings.get(0), warnings.get(0).contains("\"datapoint\""));
	}

	public void testImport() throws Exception {
		String unknown = """
			{"rules": [{"id": "copy", "name": "Copy", "reactions": [{
				"triggers": [{"kind": "datapoint", "datapoint": "1/0/2"}],
				"actions": [{"kind": "write", "datapoint": "nope", "value": {"kind": "datapoint", "name": "1/0/2"}},
					{"kind": "startTimer", "timer": "missing"}]}]}]}
			""";
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			new RuleImport().importImage(_project, unknown);
			fail("The image names what the project does not have.");
		} catch (TopLogicException ex) {
			String message = Resources.getInstance().getString(ex.getErrorKey());
			assertTrue(message, message.contains("nope, missing"));
			assertFalse(message, message.contains("1/0/2"));
		}
		assertEquals(1, RuleExport.orderedRules(_project).size());

		String matching = """
			{"rules": [{"id": "copy", "name": "Copy", "timers": [{"id": "off", "duration": 60000}],
				"reactions": [{
				"triggers": [{"kind": "datapoint", "datapoint": "1/0/2"}, {"kind": "schedule", "schedule": "morning"},
					{"kind": "datapoint", "datapoint": "rule/r/enabled"}],
				"actions": [{"kind": "write", "datapoint": "switch", "value": {"kind": "datapoint", "name": "1/0/2"}},
					{"kind": "startTimer", "timer": "off"}]}]}],
			 "schedules": [{"id": "evening", "label": "Evening", "kind": "astro", "sun": "sunset", "output": "flag"}]}
			""";
		RuleImport.Imported imported;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			imported = new RuleImport().importImage(_project, matching);
			tx.commit();
		}
		TLObject reaction = RuleFunctions.objects(imported.rules().get(0), "reactions").get(0);
		List<TLObject> triggers = RuleFunctions.objects(reaction, "triggers");
		assertSame(_sensor, triggers.get(0).tValueByName("datapoint"));
		assertSame(_weekly, triggers.get(1).tValueByName("schedule"));
		assertSame(_rule, triggers.get(2).tValueByName("datapoint"));
		List<TLObject> actions = RuleFunctions.objects(reaction, "actions");
		assertSame(_switch, actions.get(0).tValueByName("datapoint"));
		assertSame(RuleFunctions.objects(imported.rules().get(0), "timers").get(0), actions.get(1).tValueByName("timer"));
		assertSame(_flag, imported.schedules().get(0).tValueByName("output"));
		String exported = Json.write(new RuleExport().rules(_project));
		assertTrue(exported, exported.contains("\"rule/r/enabled\""));
	}

	public void testMigration() {
		TLObject trigger;
		TLObject write;
		TLObject start;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			trigger = RuleFunctions.create(_reaction, "triggers", runtime("DatapointTrigger"));
			legacy(trigger, "datapoint", "1/0/1");
			write = RuleFunctions.create(_reaction, "actions", runtime("WriteAction"));
			legacy(write, "datapoint", "gone");
			start = RuleFunctions.create(_reaction, "actions", runtime("StartTimerAction"));
			legacy(start, "timer", "t1");
			legacy(_astro, "output", "flag");
			tx.commit();
		}
		List<String> unresolved = new ArrayList<>();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			RuleReferenceMigration.migrate(_project, unresolved);
			tx.commit();
		}
		assertSame(_light, trigger.tValueByName("datapoint"));
		assertNull(write.tValueByName("datapoint"));
		assertSame(_timer, start.tValueByName("timer"));
		assertSame(_flag, _astro.tValueByName("output"));
		assertEquals(unresolved.toString(), 1, unresolved.size());
		assertTrue(unresolved.get(0), unresolved.get(0).contains("'gone'") && unresolved.get(0).contains("rule 'r'"));
		for (TLObject obj : List.of(trigger, write, start, _astro)) {
			for (String attribute : RuleReferences.ATTRIBUTES) {
				assertNull(((KnowledgeItem) obj.tHandle()).getAttributeValue(RuleReferenceMigration.PREFIX + attribute));
			}
		}
	}

	/** Stores an id text as the migration's SQL leaves it. */
	private static void legacy(TLObject obj, String attribute, String id) {
		((KnowledgeItem) obj.tHandle()).setAttributeValue(RuleReferenceMigration.PREFIX + attribute, id);
	}

	private static TLObject part(TLObject owner, String reference, String type) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject result = RuleFunctions.create(owner, reference, runtime(type));
			tx.commit();
			return result;
		}
	}

	private TLObject address(String name, int value, TLObject type, boolean writable) {
		TLObject result = create("ehome.logical:GroupAddress");
		result.tUpdateByName("name", name);
		result.tUpdateByName("address", new GA(value, GroupAddressStyle.ThreeLevel));
		if (type != null) {
			result.tUpdateByName("datapointTypes", List.of(type));
		}
		result.tUpdateByName("runtimeWritable", Boolean.valueOf(writable));
		return result;
	}

	private TLObject datapoint(String id, String type, boolean writable) {
		TLObject result = RuleFunctions.create(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
		result.tUpdateByName("identifier", id);
		result.tUpdateByName("type",
			((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(type));
		result.tUpdateByName("writable", Boolean.valueOf(writable));
		return result;
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleReferences.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
