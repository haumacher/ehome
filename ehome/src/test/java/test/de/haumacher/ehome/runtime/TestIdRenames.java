/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.base.services.simpleajax.HTMLFragment;
import com.top_logic.basic.xml.TagWriter;
import com.top_logic.event.infoservice.DefaultInfoServiceItem;
import com.top_logic.event.infoservice.InfoService;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.DisplayContext;
import com.top_logic.layout.basic.DefaultDisplayContext;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.ExpressionRenames;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.IdRenames;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;

/**
 * A renamed id rewrites the expressions naming it (haumacher/ehome #111): parameters, timers and
 * states of a rule, application datapoints (also one bound to a Zigbee function), the address of a
 * group address. Only the references are rewritten, found in the parsed expression: not a longer
 * id, not a string literal, not a parameter, timer or state of the same id of another rule. The
 * commit tells how many expressions were adjusted and, for a parameter, that the value set on the
 * controller is lost.
 */
@SuppressWarnings("javadoc")
public class TestIdRenames extends BasicTestCase {

	private TLObject _project;

	private TLObject _dimmer;

	private TLObject _other;

	private TLObject _a;

	private TLObject _ab;

	private TLObject _otherA;

	private TLObject _timer;

	private TLObject _state;

	private TLObject _otherState;

	private TLObject _address;

	private TLObject _lamp;

	private TLObject _condition;

	private TLObject _duration;

	private TLObject _log;

	private TLObject _otherCondition;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_project = TestRuleAuthoring.createProject();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeImport.importExport(_project, ZigbeeExport.parse("""
				{"format": "homecontrol-zigbee-export", "version": 1, "devices": [
					{"ieee": "00000000000000b1", "label": "Stehlampe", "interviewed": true, "functions": [
						{"function": "on_off", "endpoint": 11, "cluster": 6, "attribute": 0, "type": "boolean", "writable": true}]}]}"""));
			TLObject device = ZigbeeImport.devices(_project).get(0);
			_lamp = ZigbeeBindings.createDatapoint((TLObject) ((Collection<?>) device.tValueByName("functions")).iterator().next());
			_lamp.tUpdateByName("identifier", "lamp");

			_address = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.logical:GroupAddress"));
			_address.tUpdateByName("name", "Licht");
			_address.tUpdateByName("address", GA.parse("1/2/3", GroupAddressStyle.ThreeLevel));
			_project.tUpdateByName("groupAddresses", List.of(_address));

			_dimmer = RuleFunctions.create(_project, "rules", runtime("Rule"));
			_dimmer.tUpdateByName("identifier", "dimmer");
			_a = parameter(_dimmer, "a");
			_ab = parameter(_dimmer, "ab");
			_timer = RuleFunctions.create(_dimmer, "timers", runtime("RuleTimer"));
			_timer.tUpdateByName("identifier", "t");
			_duration = _timer;
			_state = RuleFunctions.create(_dimmer, "states", runtime("RuleState"));
			_state.tUpdateByName("identifier", "s1");
			TLObject reaction = RuleFunctions.create(_dimmer, "reactions", runtime("Reaction"));
			_condition = reaction;
			_log = RuleFunctions.create(reaction, "actions", runtime("LogAction"));

			_other = RuleFunctions.create(_project, "rules", runtime("Rule"));
			_other.tUpdateByName("identifier", "other");
			_otherA = parameter(_other, "a");
			RuleFunctions.create(_other, "timers", runtime("RuleTimer")).tUpdateByName("identifier", "t");
			_otherState = RuleFunctions.create(_other, "states", runtime("RuleState"));
			_otherState.tUpdateByName("identifier", "s1");
			_otherCondition = RuleFunctions.create(_other, "reactions", runtime("Reaction"));

			_condition.tUpdateByName("condition", canonical("$a > 1 and $ab > 2 and contains(\"$a s1 t\", \"lamp\")"
				+ " and running(t) and state() == \"s1\" and @lamp and @\"1/2/3\" and @\"rule/other/a\" > 0"));
			_duration.tUpdateByName("duration", canonical("$a * 1s + remaining(t)"));
			_log.tUpdateByName("message", "Level {$a} of {@\"rule/dimmer/a\"}, {{$a}} in {state()}");
			_otherCondition.tUpdateByName("condition", canonical("$a > 0 and @\"rule/dimmer/a\" > 0"
				+ " and state(\"dimmer\") != \"s1\" and \"s1\" == state() and running(t)"));
			tx.commit();
		}
		entries().clear();
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		super.tearDown();
	}

	/**
	 * A parameter: <code>$a</code> in its rule (not <code>$ab</code>, not in a string, not the
	 * <code>$a</code> of the other rule) and its datapoint in every rule.
	 */
	public void testParameter() {
		rename(_a, "identifier", "level");
		assertEquals(canonical("$level > 1 and $ab > 2 and contains(\"$a s1 t\", \"lamp\")"
			+ " and running(t) and state() == \"s1\" and @lamp and @\"1/2/3\" and @\"rule/other/a\" > 0"),
			_condition.tValueByName("condition"));
		assertEquals("$level * 1s + remaining(t)", _duration.tValueByName("duration"));
		assertEquals("Level {$level} of {@\"rule/dimmer/level\"}, {{$a}} in {state()}", _log.tValueByName("message"));
		assertEquals(canonical("$a > 0 and @\"rule/dimmer/level\" > 0"
			+ " and state(\"dimmer\") != \"s1\" and \"s1\" == state() and running(t)"),
			_otherCondition.tValueByName("condition"));
		assertEquals(List.of(
			"4 expressions adjusted to the new id.",
			"The parameter a of the rule dimmer is now called level: a value set on the controller is lost, the next image resets it to the default."),
			messages());

		// An id that is no identifier is quoted.
		rename(_a, "identifier", "max-helligkeit");
		assertTrue(String.valueOf(_condition.tValueByName("condition")).startsWith("$\"max-helligkeit\" > 1 and $ab > 2"));
		assertEquals("Level {$\"max-helligkeit\"} of {@\"rule/dimmer/max-helligkeit\"}, {{$a}} in {state()}",
			_log.tValueByName("message"));
	}

	/**
	 * The other rule's parameter of the same id: only its own references follow.
	 */
	public void testParameterOfOtherRule() {
		rename(_otherA, "identifier", "b");
		assertEquals(canonical("$b > 0 and @\"rule/dimmer/a\" > 0"
			+ " and state(\"dimmer\") != \"s1\" and \"s1\" == state() and running(t)"),
			_otherCondition.tValueByName("condition"));
		assertTrue(String.valueOf(_condition.tValueByName("condition")).endsWith("@\"rule/other/b\" > 0"));
		assertTrue(String.valueOf(_condition.tValueByName("condition")).startsWith("$a > 1"));
		assertEquals("$a * 1s + remaining(t)", _duration.tValueByName("duration"));
		assertEquals("2 expressions adjusted to the new id.", messages().get(0));
	}

	/**
	 * Two parameters exchanging their ids in one transaction.
	 */
	public void testExchange() {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_a.tUpdateByName("identifier", "x");
			_ab.tUpdateByName("identifier", "a");
			tx.commit();
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_a.tUpdateByName("identifier", "ab");
			_ab.tUpdateByName("identifier", "a");
			tx.commit();
		}
		assertTrue(String.valueOf(_condition.tValueByName("condition")).startsWith("$ab > 1 and $a > 2 and"));
	}

	/**
	 * An id the model refuses (haumacher/ehome #110) is not stored and rewrites nothing.
	 */
	public void testInvalidParameterId() {
		Object condition = _condition.tValueByName("condition");
		Object otherCondition = _otherCondition.tValueByName("condition");
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_a.tUpdateByName("identifier", "max helligkeit");
			tx.commit();
			fail("The commit must be refused.");
		} catch (RuntimeException ex) {
			// Refused by the constraint of the parameter id.
		}
		assertEquals("a", _a.tValueByName("identifier"));
		assertEquals(condition, _condition.tValueByName("condition"));
		assertEquals(otherCondition, _otherCondition.tValueByName("condition"));
		assertEquals("$a * 1s + remaining(t)", _duration.tValueByName("duration"));
		assertEquals(List.of(), messages());
	}

	/**
	 * A timer: <code>running(t)</code> and <code>remaining(t)</code> of its rule only.
	 */
	public void testTimer() {
		rename(_timer, "identifier", "nachlauf");
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("running(nachlauf)"));
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("\"$a s1 t\""));
		assertEquals("$a * 1s + remaining(nachlauf)", _duration.tValueByName("duration"));
		assertTrue(String.valueOf(_otherCondition.tValueByName("condition")).endsWith("running(t)"));
		assertEquals(List.of("2 expressions adjusted to the new id."), messages());
	}

	/**
	 * A state: compared with <code>state()</code> in its rule and with <code>state("rule")</code>
	 * everywhere; not the state of the same id of the other rule.
	 */
	public void testState() {
		rename(_state, "identifier", "on");
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("state() == \"on\""));
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("\"$a s1 t\""));
		assertEquals(canonical("$a > 0 and @\"rule/dimmer/a\" > 0"
			+ " and state(\"dimmer\") != \"on\" and \"s1\" == state() and running(t)"),
			_otherCondition.tValueByName("condition"));
		assertEquals(List.of("2 expressions adjusted to the new id."), messages());

		rename(_otherState, "identifier", "off");
		assertTrue(String.valueOf(_otherCondition.tValueByName("condition")).contains("\"off\" == state()"));
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("state() == \"on\""));
		assertEquals(List.of("1 expression adjusted to the new id."), messages());
	}

	/**
	 * An application datapoint bound to a Zigbee function.
	 */
	public void testApplicationDatapoint() {
		assertNotNull(ZigbeeBindings.function(_lamp));
		rename(_lamp, "identifier", "stehlampe.ein");
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("@stehlampe.ein and @\"1/2/3\""));
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("\"lamp\""));
		assertEquals(List.of("1 expression adjusted to the new id."), messages());
		assertEquals("stehlampe.ein", ApplicationDatapoints.id(_lamp));
	}

	/**
	 * A group address: its address, not its name.
	 */
	public void testGroupAddress() {
		rename(_address, "name", "Licht Flur");
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("@\"1/2/3\""));
		assertEquals(List.of(), messages());

		rename(_address, "address", GA.parse("1/2/4", GroupAddressStyle.ThreeLevel));
		assertTrue(String.valueOf(_condition.tValueByName("condition")).contains("@lamp and @\"1/2/4\" and"));
		assertEquals(List.of("1 expression adjusted to the new id."), messages());
	}

	/**
	 * The rewrite of a text: by its parsed references, in canonical form; a text that does not parse
	 * stays.
	 */
	public void testRewrite() {
		ExpressionRenames renames = new ExpressionRenames();
		renames.parameter("r", "a", "b");
		assertEquals("$b + $ab", renames.rewriteExpression("r", "$a+$ab"));
		assertNull(renames.rewriteExpression("r", "$ab + \"$a\""));
		assertNull(renames.rewriteExpression("q", "$a"));
		assertEquals("@\"rule/r/b\"", renames.rewriteExpression("q", "@\"rule/r/a\""));
		assertNull(renames.rewriteExpression("r", "$a +"));
		assertEquals("{$b} {{$a}}", renames.rewriteMessage("r", "{$a} {{$a}}"));
	}

	public void testMessages() {
		assertEquals(List.of(), new IdRenames.Report(0, List.of()).notes());
		assertNull(new IdRenames.Report(0, List.of()).adjusted());
		assertEquals(I18NConstants.EXPRESSION_ADJUSTED, new IdRenames.Report(1, List.of()).adjusted());
		assertEquals(I18NConstants.EXPRESSIONS_ADJUSTED__COUNT.fill(Integer.valueOf(3)),
			new IdRenames.Report(3, List.of()).adjusted());
	}

	private static void rename(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static TLObject parameter(TLObject rule, String id) {
		TLObject result = RuleFunctions.create(rule, "parameters", runtime("RuleParameter"));
		result.tUpdateByName("identifier", id);
		return result;
	}

	private static String canonical(String text) {
		try {
			return RuleText.print(RuleText.parse(text));
		} catch (RuleText.SyntaxError ex) {
			throw new AssertionError(ex.describe(), ex);
		}
	}

	/** The messages the commits gave the user since the last call, as texts. */
	private static List<String> messages() {
		List<HTMLFragment> entries = entries();
		List<String> result = new ArrayList<>();
		for (HTMLFragment entry : entries) {
			result.add(text(((DefaultInfoServiceItem) entry).getMessage()));
		}
		entries.clear();
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<HTMLFragment> entries() {
		DisplayContext context = DefaultDisplayContext.getDisplayContext();
		if (!context.isSet(InfoService.INFO_SERVICE_ENTRIES)) {
			context.set(InfoService.INFO_SERVICE_ENTRIES, new ArrayList<>());
		}
		return context.get(InfoService.INFO_SERVICE_ENTRIES);
	}

	private static String text(HTMLFragment fragment) {
		StringWriter buffer = new StringWriter();
		try {
			fragment.write(DefaultDisplayContext.getDisplayContext(), new TagWriter(buffer));
		} catch (IOException ex) {
			throw new AssertionError(ex);
		}
		return buffer.toString();
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestIdRenames.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			IdRenames.Module.INSTANCE));
	}

}
