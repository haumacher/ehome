/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
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
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.LogAction;
import de.haumacher.ehome.model.runtime.ParameterTrigger;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.ReadAction;
import de.haumacher.ehome.model.runtime.RestartTimerAction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.RuleTimer;
import de.haumacher.ehome.model.runtime.ScheduleTrigger;
import de.haumacher.ehome.model.runtime.SetParameterAction;
import de.haumacher.ehome.model.runtime.SilenceTrigger;
import de.haumacher.ehome.model.runtime.StartTimerAction;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.model.runtime.WriteAction;
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

	private Project _project;

	private GroupAddress _light;

	private GroupAddress _brightness;

	private GroupAddress _unnamed;

	private ApplicationDatapoint _night;

	private ApplicationDatapoint _plain;

	private Rule _rule;

	private RuleTimer _timer;

	private RuleParameter _max;

	private RuleParameter _quoted;

	private RuleParameter _raw;

	private Reaction _reaction;

	private WeeklySchedule _morning;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_project = create("ehome.project:Project");
			_project.setName("Datapoint names");
			_light = address("Licht Flur", "11/0/4");
			_brightness = address("Helligkeit Flur", "11/0/6");
			_unnamed = address(null, "11/0/7");
			_project.setGroupAddresses(Set.of(_light, _brightness, _unnamed));

			_night = (ApplicationDatapoint) RuleFunctions.create(_project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			_night.setIdentifier("night");
			_night.setLabel(i18n("Night", "Nacht"));
			_plain = (ApplicationDatapoint) RuleFunctions.create(_project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			_plain.setIdentifier("plain");
			_plain.setLabel(null);
			_plain.setType(
				((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier("unsigned"));

			_rule = (Rule) RuleFunctions.create(_project, "rules", runtime("Rule"));
			_rule.setIdentifier("licht");
			_rule.setNameKey(i18n("Light", "Licht"));
			_timer = (RuleTimer) RuleFunctions.create(_rule, "timers", runtime("RuleTimer"));
			_timer.setIdentifier("t1");
			_max = parameter("maxHelligkeit", i18n("Max. brightness", "Max. Helligkeit"));
			_quoted = parameter("max-helligkeit", i18n("Limit", "Grenze"));
			_raw = parameter("raw", null);
			_reaction = (Reaction) RuleFunctions.create(_rule, "reactions", runtime("Reaction"));

			_morning = (WeeklySchedule) RuleFunctions.create(_project, "schedules", runtime("WeeklySchedule"));
			_morning.setIdentifier("morning");
			_morning.setLabel(i18n("Morning", "Morgens"));
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
		DatapointTrigger named = part(_reaction, "triggers", "DatapointTrigger", (DatapointTrigger p) -> p.setDatapoint(_light));
		DatapointTrigger unnamed = part(_reaction, "triggers", "DatapointTrigger", (DatapointTrigger p) -> p.setDatapoint(_unnamed));
		assertEquals("datapoint Licht Flur (11/0/4)", en(() -> RuleFunctions.summary(named)));
		assertEquals("datapoint 11/0/7", en(() -> RuleFunctions.summary(unnamed)));

		DatapointTrigger night = part(_reaction, "triggers", "DatapointTrigger", (DatapointTrigger p) -> p.setDatapoint(_night));
		assertEquals("datapoint Night (night)", en(() -> RuleFunctions.summary(night)));
		assertEquals("datapoint Nacht (night)", de(() -> RuleFunctions.summary(night)));

		// The enable switch of a rule by the rule's name, the datapoint of a parameter by its title.
		DatapointTrigger enabled = part(_reaction, "triggers", "DatapointTrigger", (DatapointTrigger p) -> p.setDatapoint(_rule));
		assertEquals("datapoint Licht (rule/licht/enabled)", de(() -> RuleFunctions.summary(enabled)));
		DatapointTrigger parameter = part(_reaction, "triggers", "DatapointTrigger", (DatapointTrigger p) -> p.setDatapoint(_max));
		assertEquals("datapoint Max. Helligkeit (rule/licht/maxHelligkeit)", de(() -> RuleFunctions.summary(parameter)));
	}

	public void testSilenceAndRead() {
		SilenceTrigger silence = part(_reaction, "triggers", "SilenceTrigger", (SilenceTrigger p) -> p.setDatapoint(_brightness));
		update(() -> silence.setAfter("$maxHelligkeit"));
		assertEquals("silence Helligkeit Flur (11/0/6) after Max. brightness ($maxHelligkeit)",
			en(() -> RuleFunctions.summary(silence)));
		SilenceTrigger silent = part(_reaction, "triggers", "SilenceTrigger", (SilenceTrigger p) -> p.setDatapoint(_unnamed));
		update(() -> silent.setAfter("10min"));
		assertEquals("silence 11/0/7 after 10min", en(() -> RuleFunctions.summary(silent)));

		ReadAction read = part(_reaction, "actions", "ReadAction", (ReadAction p) -> p.setDatapoint(_light));
		assertEquals("read Licht Flur (11/0/4)", en(() -> RuleFunctions.summary(read)));
		ReadAction readUnnamed = part(_reaction, "actions", "ReadAction", (ReadAction p) -> p.setDatapoint(_unnamed));
		assertEquals("read 11/0/7", en(() -> RuleFunctions.summary(readUnnamed)));
	}

	public void testWrite() {
		WriteAction write = part(_reaction, "actions", "WriteAction", (WriteAction p) -> p.setDatapoint(_light));
		update(() -> write.setValue("true"));
		assertEquals("write Licht Flur (11/0/4) := true", en(() -> RuleFunctions.summary(write)));
		update(() -> write.setValue("@\"11/0/6\" > 100"));
		assertEquals("write Licht Flur (11/0/4) := Helligkeit Flur (11/0/6) > 100", en(() -> RuleFunctions.summary(write)));

		// Without a name: the id alone, the reference in the expression as it is written.
		WriteAction unnamed = part(_reaction, "actions", "WriteAction", (WriteAction p) -> p.setDatapoint(_unnamed));
		update(() -> unnamed.setValue("not @\"11/0/7\""));
		assertEquals("write 11/0/7 := not @\"11/0/7\"", en(() -> RuleFunctions.summary(unnamed)));
		WriteAction plain = part(_reaction, "actions", "WriteAction", (WriteAction p) -> p.setDatapoint(_plain));
		update(() -> plain.setValue("@plain + 1"));
		assertEquals("write plain := @plain + 1", en(() -> RuleFunctions.summary(plain)));
	}

	public void testSetParameter() {
		SetParameterAction set = part(_reaction, "actions", "SetParameterAction", (SetParameterAction p) -> p.setParameter(_max));
		update(() -> set.setValue("$maxHelligkeit + 10"));
		assertEquals("setParameter Max. brightness (maxHelligkeit) := Max. brightness ($maxHelligkeit) + 10",
			en(() -> RuleFunctions.summary(set)));
		assertEquals("setParameter Max. Helligkeit (maxHelligkeit) := Max. Helligkeit ($maxHelligkeit) + 10",
			de(() -> RuleFunctions.summary(set)));

		SetParameterAction untitled = part(_reaction, "actions", "SetParameterAction", (SetParameterAction p) -> p.setParameter(_raw));
		update(() -> untitled.setValue("$raw"));
		assertEquals("setParameter raw := $raw", en(() -> RuleFunctions.summary(untitled)));
	}

	public void testParameterReferences() {
		update(() -> _reaction.setCondition("$\"max-helligkeit\" > $maxHelligkeit and $raw"));
		assertEquals("when - if Grenze ($\"max-helligkeit\") > Max. Helligkeit ($maxHelligkeit) and $raw",
			de(() -> RuleFunctions.summary(_reaction)));
		// The parameter of a rule as a datapoint, by its title: named as the image names it.
		update(() -> _reaction.setCondition("@\"rule/licht/maxHelligkeit\" > 3"));
		assertEquals("when - if Max. brightness (rule/licht/maxHelligkeit) > 3",
			en(() -> RuleFunctions.summary(_reaction)));
	}

	public void testTimers() {
		StartTimerAction start = part(_reaction, "actions", "StartTimerAction", (StartTimerAction p) -> p.setTimer(_timer));
		update(() -> start.setDuration("$maxHelligkeit * 1s"));
		assertEquals("startTimer t1 Max. brightness ($maxHelligkeit) * 1s", en(() -> RuleFunctions.summary(start)));
		RestartTimerAction restart = part(_reaction, "actions", "RestartTimerAction", (RestartTimerAction p) -> p.setTimer(_timer));
		update(() -> restart.setDuration("5min"));
		assertEquals("restartTimer t1 5min", en(() -> RuleFunctions.summary(restart)));
	}

	public void testReactionWithCondition() {
		part(_reaction, "triggers", "DatapointTrigger", (DatapointTrigger p) -> p.setDatapoint(_light));
		update(() -> _reaction.setCondition("@\"11/0/6\" < $maxHelligkeit"));
		assertEquals("when datapoint Licht Flur (11/0/4) if Helligkeit Flur (11/0/6) < Max. brightness ($maxHelligkeit)",
			en(() -> RuleFunctions.summary(_reaction)));
		assertEquals("when datapoint Licht Flur (11/0/4) if Helligkeit Flur (11/0/6) < Max. Helligkeit ($maxHelligkeit)",
			de(() -> RuleFunctions.summary(_reaction)));
	}

	public void testLiteralsAndSyntaxErrors() {
		// A string literal with a reference in it is no reference.
		update(() -> _reaction.setCondition("state() == \"@\\\"11/0/4\\\"\" or @\"11/0/4\""));
		assertEquals("when - if state() == \"@\\\"11/0/4\\\"\" or Licht Flur (11/0/4)", en(() -> RuleFunctions.summary(_reaction)));
		// A text that does not parse (the field refuses to store it) is shown as it is.
		assertEquals("@\"11/0/4\" and (", en(() -> DatapointNames.of(_project).expression(_rule, "@\"11/0/4\" and (")));
	}

	public void testLogMessage() {
		LogAction action = part(_reaction, "actions", "LogAction", (LogAction p) -> p.setMessage("Value {@\"11/0/6\"}, {{literal}}"));
		assertEquals("log info: Value {Helligkeit Flur (11/0/6)}, {{literal}}", en(() -> RuleFunctions.summary(action)));
	}

	public void testScheduleReferences() {
		ScheduleTrigger trigger = part(_reaction, "triggers", "ScheduleTrigger", (ScheduleTrigger p) -> p.setSchedule(_morning));
		assertEquals("schedule Morgens (morning)", de(() -> RuleFunctions.summary(trigger)));
		ParameterTrigger parameter = part(_reaction, "triggers", "ParameterTrigger", (ParameterTrigger p) -> p.setParameter(_max));
		assertEquals("parameter Max. Helligkeit (maxHelligkeit)", de(() -> RuleFunctions.summary(parameter)));

		String without = en(() -> RuleFunctions.summary(_morning));
		assertFalse(without, without.contains("output"));
		update(() -> _morning.setOutput(_night));
		assertEquals(without + " output Night (night)", en(() -> RuleFunctions.summary(_morning)));
		assertEquals(without + " output Nacht (night)", de(() -> RuleFunctions.summary(_morning)));
	}

	// ---- helpers ------------------------------------------------------------------------------

	private RuleParameter parameter(String id, ResKey title) {
		RuleParameter result = (RuleParameter) RuleFunctions.create(_rule, "parameters", runtime("RuleParameter"));
		result.setIdentifier(id);
		result.setTitle(title);
		return result;
	}

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T part(TLObject owner, String reference, String type, Consumer<T> init) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			T result = (T) RuleFunctions.create(owner, reference, runtime(type));
			init.accept(result);
			tx.commit();
			return result;
		}
	}

	private static void update(Runnable change) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			change.run();
			tx.commit();
		}
	}

	private static GroupAddress address(String name, String address) {
		GroupAddress result = create("ehome.logical:GroupAddress");
		result.setName(name);
		result.setAddress(GA.parse(address, GroupAddressStyle.ThreeLevel));
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

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T create(String type) {
		return (T) ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}


	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDatapointNames.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
