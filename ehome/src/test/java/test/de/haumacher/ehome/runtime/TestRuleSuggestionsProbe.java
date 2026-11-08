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

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.RuleFunctions;

/**
 * Probe of the suggestions of #109 in positions the delivery's own test does not cover: a caret in
 * the middle of the text, a second reference after a complete one, a <code>$</code> inside a quoted
 * datapoint name, a timer function after a closed one, and the braces of a log message.
 */
@SuppressWarnings("javadoc")
public class TestRuleSuggestionsProbe extends BasicTestCase {

	private TLObject _project;

	private TLObject _reaction;

	private TLObject _log;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_project = TestRuleAuthoring.createProject();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject rule = RuleFunctions.create(_project, "rules", type("Rule"));
			rule.tUpdateByName("identifier", "probe");
			RuleFunctions.create(rule, "parameters", type("RuleParameter")).tUpdateByName("identifier", "darkBelow");
			RuleFunctions.create(rule, "parameters", type("RuleParameter")).tUpdateByName("identifier", "max-helligkeit");
			RuleFunctions.create(rule, "timers", type("RuleTimer")).tUpdateByName("identifier", "off");
			_reaction = RuleFunctions.create(rule, "reactions", type("Reaction"));
			_log = RuleFunctions.create(_reaction, "actions", type("LogAction"));
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		super.tearDown();
	}

	public void testCaretInTheMiddle() {
		List<Object> result = RuleFunctions.suggestions(_reaction, "$da > 1", 3);
		assertEquals(List.of("$darkBelow"), texts(result));
		assertEquals(Integer.valueOf(0), ((Map<?, ?>) result.get(0)).get("from"));
	}

	public void testSecondReference() {
		assertEquals(List.of("$darkBelow", "$\"max-helligkeit\"", "$enabled"),
			texts(_reaction, "$darkBelow > 1 and $\"max-helligkeit\" < $"));
	}

	public void testDollarInQuotedDatapoint() {
		for (Object text : texts(_reaction, "@\"a$")) {
			assertFalse("No parameter inside a datapoint name: " + text, String.valueOf(text).startsWith("$"));
		}
	}

	public void testTimerAfterClosedOne() {
		assertEquals(List.of("off)"), texts(_reaction, "running(off) and remaining("));
		assertEquals(List.of(), texts(_reaction, "running(off) and "));
	}

	public void testLogBraces() {
		assertEquals(List.of("$darkBelow"), texts(_log, "Level {$da"));
		assertEquals(List.of(), texts(_log, "Level {{$da"));
		assertEquals(List.of(), texts(_log, "Level {$darkBelow} and $"));
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

	private static TLClass type(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleSuggestionsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
