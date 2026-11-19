/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.HashSet;
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
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Action;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.AstroSchedule;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.ReadAction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.RuleTimer;
import de.haumacher.ehome.model.runtime.ScheduleEntry;
import de.haumacher.ehome.model.runtime.ScheduleTrigger;
import de.haumacher.ehome.model.runtime.SetParameterAction;
import de.haumacher.ehome.model.runtime.StartTimerAction;
import de.haumacher.ehome.model.runtime.Trigger;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.model.runtime.WriteAction;
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

	private Project _project;

	private GroupAddress _light;

	private GroupAddress _sensor;

	private GroupAddress _untyped;

	private ApplicationDatapoint _flag;

	private ApplicationDatapoint _level;

	private ApplicationDatapoint _switch;

	private Rule _rule;

	private RuleTimer _timer;

	private RuleParameter _parameter;

	private WeeklySchedule _weekly;

	private AstroSchedule _astro;

	private Reaction _reaction;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_dpt = create("ehome.datapoint:DatapointMainType");
			_dpt.tUpdateByName("number", Integer.valueOf(1));
			_dpt.tUpdateByName("extId", "TEST-RULE-REFERENCES-1");
			_project = create("ehome.project:Project");
			_project.setName("Rule references");
			_light = address("Licht", 0x0801, _dpt, true);
			_sensor = address("Bewegung", 0x0802, _dpt, false);
			_untyped = address("Ohne Typ", 0x0809, null, false);
			_project.setGroupAddresses(new HashSet<>(List.of(_light, _sensor, _untyped)));

			_flag = datapoint("flag", "boolean", false);
			_level = datapoint("level", "unsigned", true);
			_switch = datapoint("switch", "boolean", true);

			_rule = add(_project, "rules", runtime("Rule"));
			_rule.setIdentifier("r");
			_timer = add(_rule, "timers", runtime("RuleTimer"));
			_parameter = add(_rule, "parameters", runtime("RuleParameter"));
			_reaction = add(_rule, "reactions", runtime("Reaction"));

			_weekly = add(_project, "schedules", runtime("WeeklySchedule"));
			_weekly.setIdentifier("morning");
			_weekly.setTimeAdjustable(true);
			_astro = add(_project, "schedules", runtime("AstroSchedule"));
			_astro.setIdentifier("dusk");
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
		DatapointTrigger trigger = part(_reaction, "triggers", "DatapointTrigger");
		WriteAction write = part(_reaction, "actions", "WriteAction");
		ReadAction read = part(_reaction, "actions", "ReadAction");
		StartTimerAction start = part(_reaction, "actions", "StartTimerAction");
		SetParameterAction set = part(_reaction, "actions", "SetParameterAction");
		ScheduleTrigger scheduled = part(_reaction, "triggers", "ScheduleTrigger");

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
		WriteAction write;
		StartTimerAction start;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TestRuleReferences.<ScheduleTrigger> add(_reaction, "triggers", runtime("ScheduleTrigger")).setSchedule(_astro);
			write = add(_reaction, "actions", runtime("WriteAction"));
			write.setDatapoint(_level);
			write.setValue("1");
			start = add(_reaction, "actions", runtime("StartTimerAction"));
			start.setTimer(_timer);
			_astro.setOutput(_flag);
			tx.commit();
		}
		assertEquals(List.of("dusk", "level", "t1"), ids());
		assertEquals("flag", schedule(1).get("output"));

		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_level.setIdentifier("level2");
			_timer.setIdentifier("off");
			_astro.setIdentifier("evening");
			_flag.setIdentifier("flag2");
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
		DatapointTrigger trigger;
		StartTimerAction start;
		SetParameterAction set;
		ScheduleTrigger scheduled;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			trigger = add(_reaction, "triggers", runtime("DatapointTrigger"));
			trigger.setDatapoint(_light);
			scheduled = add(_reaction, "triggers", runtime("ScheduleTrigger"));
			scheduled.setSchedule(_astro);
			start = add(_reaction, "actions", runtime("StartTimerAction"));
			start.setTimer(_timer);
			set = add(_reaction, "actions", runtime("SetParameterAction"));
			set.setParameter(_parameter);
			set.setValue("0");
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
		assertNull(trigger.getDatapoint());
		assertNull(scheduled.getSchedule());
		assertNull(start.getTimer());
		assertNull(set.getParameter());
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
		Rule rule = (Rule) imported.rules().get(0);
		Reaction reaction = rule.getReactions().get(0);
		List<? extends Trigger> triggers = reaction.getTriggers();
		assertSame(_sensor, ((DatapointTrigger) triggers.get(0)).getDatapoint());
		assertSame(_weekly, ((ScheduleTrigger) triggers.get(1)).getSchedule());
		assertSame(_rule, ((DatapointTrigger) triggers.get(2)).getDatapoint());
		List<? extends Action> actions = reaction.getActions();
		assertSame(_switch, ((WriteAction) actions.get(0)).getDatapoint());
		assertSame(rule.getTimers().get(0), ((StartTimerAction) actions.get(1)).getTimer());
		assertSame(_flag, ((ScheduleEntry) imported.schedules().get(0)).getOutput());
		String exported = Json.write(new RuleExport().rules(_project));
		assertTrue(exported, exported.contains("\"rule/r/enabled\""));
	}

	public void testMigration() {
		DatapointTrigger trigger;
		WriteAction write;
		StartTimerAction start;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			trigger = add(_reaction, "triggers", runtime("DatapointTrigger"));
			legacy(trigger, "datapoint", "1/0/1");
			write = add(_reaction, "actions", runtime("WriteAction"));
			legacy(write, "datapoint", "gone");
			start = add(_reaction, "actions", runtime("StartTimerAction"));
			legacy(start, "timer", "t1");
			legacy(_astro, "output", "flag");
			tx.commit();
		}
		List<String> unresolved = new ArrayList<>();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			RuleReferenceMigration.migrate(_project, unresolved);
			tx.commit();
		}
		assertSame(_light, trigger.getDatapoint());
		assertNull(write.getDatapoint());
		assertSame(_timer, start.getTimer());
		assertSame(_flag, _astro.getOutput());
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

	private static <T extends TLObject> T part(TLObject owner, String reference, String type) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			T result = add(owner, reference, runtime(type));
			tx.commit();
			return result;
		}
	}

	private GroupAddress address(String name, int value, TLObject type, boolean writable) {
		GroupAddress result = create("ehome.logical:GroupAddress");
		result.setName(name);
		result.setAddress(new GA(value, GroupAddressStyle.ThreeLevel));
		if (type != null) {
			result.setDatapointTypes(List.of(type));
		}
		result.setRuntimeWritable(Boolean.valueOf(writable));
		return result;
	}

	private ApplicationDatapoint datapoint(String id, String type, boolean writable) {
		ApplicationDatapoint result = add(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
		result.setIdentifier(id);
		result.setType(
			((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(type));
		result.setWritable(writable);
		return result;
	}

	/** A new part at the end of the reference of the owner ({@link RuleFunctions#create}), as its type. */
	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T add(TLObject owner, String reference, TLClass type) {
		return (T) RuleFunctions.create(owner, reference, type);
	}

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T create(String type) {
		return (T) ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleReferences.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
