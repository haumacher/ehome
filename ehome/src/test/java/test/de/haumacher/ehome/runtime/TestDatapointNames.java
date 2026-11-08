/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleReferences;

/**
 * The display texts naming a datapoint as <code>Name (id)</code> (haumacher/ehome #113): the
 * summaries of the triggers, actions, reactions and schedule entries, with the structural references
 * and the references inside their expressions named by their labels, in the language of the reader;
 * the id alone without a name; an expression that does not parse and the string literals of one as
 * they are.
 */
@SuppressWarnings("javadoc")
public class TestDatapointNames extends BasicTestCase {

	private TLObject _project;

	private TLObject _light;

	private TLObject _brightness;

	private TLObject _unnamed;

	private TLObject _night;

	private TLObject _plain;

	private TLObject _rule;

	private TLObject _timer;

	private TLObject _max;

	private TLObject _quoted;

	private TLObject _raw;

	private TLObject _reaction;

	private TLObject _morning;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Datapoint names");
			_light = address("Licht Flur", "11/0/4");
			_brightness = address("Helligkeit Flur", "11/0/6");
			_unnamed = address(null, "11/0/7");
			_project.tUpdateByName("groupAddresses", List.of(_light, _brightness, _unnamed));

			_night = RuleFunctions.create(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			_night.tUpdateByName("identifier", "night");
			_night.tUpdateByName("label", i18n("Night", "Nacht"));
			_plain = RuleFunctions.create(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			_plain.tUpdateByName("identifier", "plain");
			_plain.tUpdateByName("label", null);
			_plain.tUpdateByName("type",
				((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier("unsigned"));

			_rule = RuleFunctions.create(_project, "rules", runtime("Rule"));
			_rule.tUpdateByName("identifier", "licht");
			_rule.tUpdateByName("name", i18n("Light", "Licht"));
			_timer = RuleFunctions.create(_rule, "timers", runtime("RuleTimer"));
			_timer.tUpdateByName("identifier", "t1");
			_max = parameter("maxHelligkeit", i18n("Max. brightness", "Max. Helligkeit"));
			_quoted = parameter("max-helligkeit", i18n("Limit", "Grenze"));
			_raw = parameter("raw", null);
			_reaction = RuleFunctions.create(_rule, "reactions", runtime("Reaction"));

			_morning = RuleFunctions.create(_project, "schedules", runtime("WeeklySchedule"));
			_morning.tUpdateByName("identifier", "morning");
			_morning.tUpdateByName("label", i18n("Morning", "Morgens"));
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		super.tearDown();
	}

	public void testFormat() {
		assertEquals("Licht Flur (11/0/4)", DatapointNames.format("Licht Flur", "11/0/4"));
		assertEquals("11/0/4", DatapointNames.format(null, "11/0/4"));
		assertEquals("11/0/4", DatapointNames.format("11/0/4", "11/0/4"));
		assertEquals("Licht Flur", DatapointNames.format("Licht Flur", null));
		// The option lists show the same format.
		assertEquals("Licht Flur (11/0/4)", RuleReferences.optionLabel(_light, RuleReferences.TRIGGER));
		assertEquals("Max. brightness (maxHelligkeit)", en(() -> RuleReferences.optionLabel(_max, RuleReferences.PARAMETER)));
	}

	public void testDatapointTriggers() {
		TLObject named = part(_reaction, "triggers", "DatapointTrigger", "datapoint", _light);
		TLObject unnamed = part(_reaction, "triggers", "DatapointTrigger", "datapoint", _unnamed);
		assertEquals("datapoint Licht Flur (11/0/4)", en(() -> RuleFunctions.summary(named)));
		assertEquals("datapoint 11/0/7", en(() -> RuleFunctions.summary(unnamed)));

		TLObject night = part(_reaction, "triggers", "DatapointTrigger", "datapoint", _night);
		assertEquals("datapoint Night (night)", en(() -> RuleFunctions.summary(night)));
		assertEquals("datapoint Nacht (night)", de(() -> RuleFunctions.summary(night)));

		// The enable switch of a rule by the rule's name, the datapoint of a parameter by its title.
		TLObject enabled = part(_reaction, "triggers", "DatapointTrigger", "datapoint", _rule);
		assertEquals("datapoint Licht (rule/licht/enabled)", de(() -> RuleFunctions.summary(enabled)));
		TLObject parameter = part(_reaction, "triggers", "DatapointTrigger", "datapoint", _max);
		assertEquals("datapoint Max. Helligkeit (rule/licht/maxHelligkeit)", de(() -> RuleFunctions.summary(parameter)));
	}

	public void testSilenceAndRead() {
		TLObject silence = part(_reaction, "triggers", "SilenceTrigger", "datapoint", _brightness);
		update(silence, "after", "$maxHelligkeit");
		assertEquals("silence Helligkeit Flur (11/0/6) after Max. brightness ($maxHelligkeit)",
			en(() -> RuleFunctions.summary(silence)));
		TLObject silent = part(_reaction, "triggers", "SilenceTrigger", "datapoint", _unnamed);
		update(silent, "after", "10min");
		assertEquals("silence 11/0/7 after 10min", en(() -> RuleFunctions.summary(silent)));

		TLObject read = part(_reaction, "actions", "ReadAction", "datapoint", _light);
		assertEquals("read Licht Flur (11/0/4)", en(() -> RuleFunctions.summary(read)));
		TLObject readUnnamed = part(_reaction, "actions", "ReadAction", "datapoint", _unnamed);
		assertEquals("read 11/0/7", en(() -> RuleFunctions.summary(readUnnamed)));
	}

	public void testWrite() {
		TLObject write = part(_reaction, "actions", "WriteAction", "datapoint", _light);
		update(write, "value", "true");
		assertEquals("write Licht Flur (11/0/4) := true", en(() -> RuleFunctions.summary(write)));
		update(write, "value", "@\"11/0/6\" > 100");
		assertEquals("write Licht Flur (11/0/4) := Helligkeit Flur (11/0/6) > 100", en(() -> RuleFunctions.summary(write)));

		// Without a name: the id alone, the reference in the expression as it is written.
		TLObject unnamed = part(_reaction, "actions", "WriteAction", "datapoint", _unnamed);
		update(unnamed, "value", "not @\"11/0/7\"");
		assertEquals("write 11/0/7 := not @\"11/0/7\"", en(() -> RuleFunctions.summary(unnamed)));
		TLObject plain = part(_reaction, "actions", "WriteAction", "datapoint", _plain);
		update(plain, "value", "@plain + 1");
		assertEquals("write plain := @plain + 1", en(() -> RuleFunctions.summary(plain)));
	}

	public void testSetParameter() {
		TLObject set = part(_reaction, "actions", "SetParameterAction", "parameter", _max);
		update(set, "value", "$maxHelligkeit + 10");
		assertEquals("setParameter Max. brightness (maxHelligkeit) := Max. brightness ($maxHelligkeit) + 10",
			en(() -> RuleFunctions.summary(set)));
		assertEquals("setParameter Max. Helligkeit (maxHelligkeit) := Max. Helligkeit ($maxHelligkeit) + 10",
			de(() -> RuleFunctions.summary(set)));

		TLObject untitled = part(_reaction, "actions", "SetParameterAction", "parameter", _raw);
		update(untitled, "value", "$raw");
		assertEquals("setParameter raw := $raw", en(() -> RuleFunctions.summary(untitled)));
	}

	public void testParameterReferences() {
		update(_reaction, "condition", "$\"max-helligkeit\" > $maxHelligkeit and $raw");
		assertEquals("when - if Grenze ($\"max-helligkeit\") > Max. Helligkeit ($maxHelligkeit) and $raw",
			de(() -> RuleFunctions.summary(_reaction)));
		// The parameter of a rule as a datapoint, by its title: named as the image names it.
		update(_reaction, "condition", "@\"rule/licht/maxHelligkeit\" > 3");
		assertEquals("when - if Max. brightness (rule/licht/maxHelligkeit) > 3",
			en(() -> RuleFunctions.summary(_reaction)));
	}

	public void testTimers() {
		TLObject start = part(_reaction, "actions", "StartTimerAction", "timer", _timer);
		update(start, "duration", "$maxHelligkeit * 1s");
		assertEquals("startTimer t1 Max. brightness ($maxHelligkeit) * 1s", en(() -> RuleFunctions.summary(start)));
		TLObject restart = part(_reaction, "actions", "RestartTimerAction", "timer", _timer);
		update(restart, "duration", "5min");
		assertEquals("restartTimer t1 5min", en(() -> RuleFunctions.summary(restart)));
	}

	public void testReactionWithCondition() {
		part(_reaction, "triggers", "DatapointTrigger", "datapoint", _light);
		update(_reaction, "condition", "@\"11/0/6\" < $maxHelligkeit");
		assertEquals("when datapoint Licht Flur (11/0/4) if Helligkeit Flur (11/0/6) < Max. brightness ($maxHelligkeit)",
			en(() -> RuleFunctions.summary(_reaction)));
		assertEquals("when datapoint Licht Flur (11/0/4) if Helligkeit Flur (11/0/6) < Max. Helligkeit ($maxHelligkeit)",
			de(() -> RuleFunctions.summary(_reaction)));
	}

	public void testLiteralsAndSyntaxErrors() {
		// A string literal with a reference in it is no reference.
		update(_reaction, "condition", "state() == \"@\\\"11/0/4\\\"\" or @\"11/0/4\"");
		assertEquals("when - if state() == \"@\\\"11/0/4\\\"\" or Licht Flur (11/0/4)", en(() -> RuleFunctions.summary(_reaction)));
		// A text that does not parse (the field refuses to store it) is shown as it is.
		assertEquals("@\"11/0/4\" and (", en(() -> DatapointNames.of(_project).expression(_rule, "@\"11/0/4\" and (")));
	}

	public void testLogMessage() {
		TLObject action = part(_reaction, "actions", "LogAction", "message", "Value {@\"11/0/6\"}, {{literal}}");
		assertEquals("log info: Value {Helligkeit Flur (11/0/6)}, {{literal}}", en(() -> RuleFunctions.summary(action)));
	}

	public void testScheduleReferences() {
		TLObject trigger = part(_reaction, "triggers", "ScheduleTrigger", "schedule", _morning);
		assertEquals("schedule Morgens (morning)", de(() -> RuleFunctions.summary(trigger)));
		TLObject parameter = part(_reaction, "triggers", "ParameterTrigger", "parameter", _max);
		assertEquals("parameter Max. Helligkeit (maxHelligkeit)", de(() -> RuleFunctions.summary(parameter)));

		String without = en(() -> RuleFunctions.summary(_morning));
		assertFalse(without, without.contains("output"));
		update(_morning, "output", _night);
		assertEquals(without + " output Night (night)", en(() -> RuleFunctions.summary(_morning)));
		assertEquals(without + " output Nacht (night)", de(() -> RuleFunctions.summary(_morning)));
	}

	// ---- helpers ------------------------------------------------------------------------------

	private TLObject parameter(String id, ResKey title) {
		TLObject result = RuleFunctions.create(_rule, "parameters", runtime("RuleParameter"));
		result.tUpdateByName("identifier", id);
		result.tUpdateByName("title", title);
		return result;
	}

	private static TLObject part(TLObject owner, String reference, String type, String attribute, Object target) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject result = RuleFunctions.create(owner, reference, runtime(type));
			result.tUpdateByName(attribute, target);
			tx.commit();
			return result;
		}
	}

	private static void update(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static TLObject address(String name, String address) {
		TLObject result = create("ehome.logical:GroupAddress");
		result.tUpdateByName("name", name);
		result.tUpdateByName("address", GA.parse(address, GroupAddressStyle.ThreeLevel));
		return result;
	}

	private static ResKey i18n(String en, String de) {
		return ResKey.builder().add(Locale.ENGLISH, en).add(Locale.GERMAN, de).build();
	}

	private static String en(Supplier<String> value) {
		return inLocale(Locale.ENGLISH, value);
	}

	private static String de(Supplier<String> value) {
		return inLocale(Locale.GERMAN, value);
	}

	/** The value as the given person reads it in the given language: labels are resolved in it. */
	private static <T> T inLocale(Locale locale, Supplier<T> value) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return value.get();
		} finally {
			context.setCurrentLocale(localeBefore);
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDatapointNames.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
