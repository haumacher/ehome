/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import com.top_logic.layout.view.form.TLObjectOverlay;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.RuleDrafts;
import de.haumacher.ehome.runtime.RuleFunctions;

/**
 * The suggestions of the expression fields of a rule (haumacher/ehome #109,
 * {@link RuleFunctions#suggestions(TLObject, String, int)}): after <code>$</code> the parameters
 * of the rule with its enable switch, inside <code>running(</code> its timers, after
 * <code>state() == "</code> its states, inside <code>state("</code> the rules with states; each
 * field only what it may name (a match condition no timers and states, a silence period only
 * parameters, a log message inside a placeholder), nothing after <code>$</code> outside a rule.
 *
 * <p>
 * An empty project serves; no ETS import.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRuleSuggestions extends BasicTestCase {

	private TLObject _project;

	private TLObject _rule;

	private TLObject _reaction;

	private TLObject _match;

	private TLObject _silence;

	private TLObject _log;

	private TLObject _plainReaction;

	private TLObject _schedule;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_project = TestRuleAuthoring.createProject();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_rule = RuleFunctions.create(_project, "rules", type("Rule"));
			_rule.tUpdateByName("identifier", "light");
			_rule.tUpdateByName("name", text("Light"));
			TLObject dark = RuleFunctions.create(_rule, "parameters", type("RuleParameter"));
			dark.tUpdateByName("identifier", "darkBelow");
			dark.tUpdateByName("title", text("Dark below"));
			TLObject max = RuleFunctions.create(_rule, "parameters", type("RuleParameter"));
			max.tUpdateByName("identifier", "max-helligkeit");
			max.tUpdateByName("title", text("Maximale Helligkeit"));
			RuleFunctions.create(_rule, "timers", type("RuleTimer")).tUpdateByName("identifier", "off");
			RuleFunctions.create(_rule, "timers", type("RuleTimer")).tUpdateByName("identifier", "pause");
			RuleFunctions.create(_rule, "states", type("RuleState")).tUpdateByName("identifier", "day");
			RuleFunctions.create(_rule, "states", type("RuleState")).tUpdateByName("identifier", "night");
			_reaction = RuleFunctions.create(_rule, "reactions", type("Reaction"));
			_match = RuleFunctions.create(_reaction, "triggers", type("MatchTrigger"));
			_silence = RuleFunctions.create(_reaction, "triggers", type("SilenceTrigger"));
			_log = RuleFunctions.create(_reaction, "actions", type("LogAction"));

			TLObject plain = RuleFunctions.create(_project, "rules", type("Rule"));
			plain.tUpdateByName("identifier", "plain");
			_plainReaction = RuleFunctions.create(plain, "reactions", type("Reaction"));

			_schedule = RuleFunctions.create(_project, "schedules", type("WeeklySchedule"));
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		super.tearDown();
	}

	/**
	 * After <code>$</code> a reaction's condition offers the parameters of its rule and the enable
	 * switch, matched by id or label; an id that is no identifier is inserted as
	 * <code>$"…"</code>.
	 */
	public void testParameters() {
		String condition = "@x > $";
		List<Object> all = RuleFunctions.suggestions(_reaction, condition, condition.length());
		assertEquals(List.of("$darkBelow", "$\"max-helligkeit\"", "$enabled"), texts(all));
		Map<?, ?> first = (Map<?, ?>) all.get(0);
		assertEquals(Integer.valueOf(5), first.get("from"));
		assertEquals(Integer.valueOf(6), first.get("to"));
		assertEquals("$darkBelow Dark below", first.get("label"));
		assertTrue(String.valueOf(((Map<?, ?>) all.get(2)).get("label")).startsWith("$enabled "));

		assertEquals(List.of("$darkBelow"), texts(_reaction, "$da"));
		assertEquals(List.of("$darkBelow"), texts(_reaction, "$DARK"));
		assertEquals(List.of("$\"max-helligkeit\""), texts(_reaction, "$max-h"));
		assertEquals(List.of("$\"max-helligkeit\""), texts(_reaction, "$\"max-he"));
		assertEquals(List.of("$\"max-helligkeit\""), texts(_reaction, "$maximale"));
		assertEquals(List.of(), texts(_reaction, "\"cost $"));

		// The form's overlay of the reaction finds the rule as well.
		assertEquals(3, texts(new TLObjectOverlay(_reaction), "$").size());

		// A rule without parameters has its enable switch.
		assertEquals(List.of("$enabled"), texts(_plainReaction, "$"));
	}

	/** Outside a rule nothing is suggested after <code>$</code>. */
	public void testNoParametersOutsideRule() {
		assertEquals(List.of(), texts(_schedule, "$"));
		assertEquals(List.of(), texts(_schedule, "$da"));
	}

	/** Inside <code>running(</code> and <code>remaining(</code> the timers of the rule. */
	public void testTimers() {
		assertEquals(List.of("off)", "pause)"), texts(_reaction, "running("));
		List<Object> off = RuleFunctions.suggestions(_reaction, "remaining(o", 11);
		assertEquals(List.of("off)"), texts(off));
		assertEquals(Integer.valueOf(10), ((Map<?, ?>) off.get(0)).get("from"));
		assertEquals(List.of(), texts(_plainReaction, "running("));
	}

	/**
	 * After <code>state() == "</code> the states of the rule, after <code>state("r") == "</code>
	 * those of rule <code>r</code>, inside <code>state("</code> the rules that have states.
	 */
	public void testStates() {
		assertEquals(List.of("\"day\"", "\"night\""), texts(_reaction, "state() == \""));
		List<Object> night = RuleFunctions.suggestions(_reaction, "state() != \"n", 13);
		assertEquals(List.of("\"night\""), texts(night));
		assertEquals(Integer.valueOf(11), ((Map<?, ?>) night.get(0)).get("from"));
		assertEquals(List.of("\"day\""), texts(_plainReaction, "state(\"light\") == \"d"));
		assertEquals(List.of(), texts(_plainReaction, "state() == \""));

		List<Object> rules = RuleFunctions.suggestions(_plainReaction, "state(\"", 7);
		assertEquals(List.of("\"light\")"), texts(rules));
		assertEquals(Integer.valueOf(6), ((Map<?, ?>) rules.get(0)).get("from"));
		assertEquals(List.of(), texts(_plainReaction, "state(\"pl"));
	}

	/** A match condition offers datapoints, parameters and the pure functions, no timers and states. */
	public void testMatchCondition() {
		assertEquals(3, texts(_match, "$").size());
		assertEquals(List.of(), texts(_match, "running("));
		assertEquals(List.of(), texts(_match, "state() == \""));
		assertEquals(List.of(), texts(_match, "state(\""));
		assertFalse(texts(_match, "sta").contains("state("));
		assertTrue(texts(_reaction, "sta").contains("state("));
		assertTrue(texts(_match, "@").contains("@\"rule/light/darkBelow\""));
	}

	/** The period of a silence trigger offers the parameters and the pure functions only. */
	public void testSilencePeriod() {
		assertEquals(3, texts(_silence, "$").size());
		assertEquals(List.of(), texts(_silence, "@"));
		assertEquals(List.of(), texts(_silence, "@\"rule"));
		assertEquals(List.of(), texts(_silence, "running("));
		assertEquals(List.of(), texts(_silence, "ti"));
		assertEquals(List.of("max("), texts(_silence, "ma"));
	}

	/** A log message suggests inside an open placeholder only. */
	public void testLogMessage() {
		String message = "Off after {$";
		List<Object> inside = RuleFunctions.suggestions(_log, message, message.length());
		assertEquals(List.of("$darkBelow", "$\"max-helligkeit\"", "$enabled"), texts(inside));
		assertEquals(Integer.valueOf(11), ((Map<?, ?>) inside.get(0)).get("from"));
		assertEquals(Integer.valueOf(12), ((Map<?, ?>) inside.get(0)).get("to"));
		assertEquals(List.of("off)", "pause)"), texts(_log, "{$x} and {running("));
		assertEquals(List.of("\"day\""), texts(_log, "{state() == \"d"));

		assertEquals(List.of(), texts(_log, "Off after $"));
		assertEquals(List.of(), texts(_log, "{$x} and $"));
		assertEquals(List.of(), texts(_log, "a {{$"));
		assertEquals(List.of(), texts(_log, "{\"}\"} $"));
		assertEquals(List.of(), texts(_log, "sta"));
	}

	/** The draft of a create dialog finds its rule through its container. */
	public void testDraft() {
		TLObject timer = RuleDrafts.draft(_rule, "timers", type("RuleTimer"));
		assertEquals(List.of("$darkBelow"), texts(timer, "$da"));
		assertEquals(List.of("off)", "pause)"), texts(timer, "remaining("));

		TLObject match = RuleDrafts.draft(_reaction, "triggers", type("MatchTrigger"));
		assertEquals(3, texts(match, "$").size());
		assertEquals(List.of(), texts(match, "running("));

		TLObject start = RuleDrafts.draft(_reaction, "actions", type("StartTimerAction"));
		assertEquals(List.of("\"day\"", "\"night\""), texts(start, "state() == \""));
	}

	private static List<Object> texts(TLObject part, String text) {
		return texts(RuleFunctions.suggestions(part, text, text.length()));
	}

	private static List<Object> texts(List<Object> suggestions) {
		List<Object> result = new ArrayList<>();
		for (Object entry : suggestions) {
			result.add(((Map<?, ?>) entry).get("text"));
		}
		return result;
	}

	private static ResKey text(String text) {
		return ResKey.builder().add(Locale.GERMAN, text).add(Locale.ENGLISH, text).build();
	}

	private static TLClass type(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleSuggestions.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
