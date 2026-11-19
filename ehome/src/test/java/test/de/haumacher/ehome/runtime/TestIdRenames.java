/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.LogAction;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.RuleState;
import de.haumacher.ehome.model.runtime.RuleTimer;
import de.haumacher.ehome.model.zigbee.ZigbeeDevice;
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

	private Project _project;

	private Rule _dimmer;

	private Rule _other;

	private RuleParameter _a;

	private RuleParameter _ab;

	private RuleParameter _otherA;

	private RuleTimer _timer;

	private RuleState _state;

	private RuleState _otherState;

	private GroupAddress _address;

	private ApplicationDatapoint _lamp;

	private Reaction _condition;

	private RuleTimer _duration;

	private LogAction _log;

	private Reaction _otherCondition;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_project = (Project) TestRuleAuthoring.createProject();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeImport.importExport(_project, ZigbeeExport.parse("""
				{"format": "homecontrol-zigbee-export", "version": 1, "devices": [
					{"ieee": "00000000000000b1", "label": "Stehlampe", "interviewed": true, "functions": [
						{"function": "on_off", "endpoint": 11, "cluster": 6, "attribute": 0, "type": "boolean", "writable": true}]}]}"""));
			ZigbeeDevice device = (ZigbeeDevice) ZigbeeImport.devices(_project).get(0);
			_lamp = (ApplicationDatapoint) ZigbeeBindings.createDatapoint(device.getFunctions().iterator().next());
			_lamp.setIdentifier("lamp");

			_address = (GroupAddress) ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.logical:GroupAddress"));
			_address.setName("Licht");
			_address.setAddress(GA.parse("1/2/3", GroupAddressStyle.ThreeLevel));
			_project.setGroupAddresses(Set.of(_address));

			_dimmer = (Rule) RuleFunctions.create(_project, "rules", runtime("Rule"));
			_dimmer.setIdentifier("dimmer");
			_a = parameter(_dimmer, "a");
			_ab = parameter(_dimmer, "ab");
			_timer = (RuleTimer) RuleFunctions.create(_dimmer, "timers", runtime("RuleTimer"));
			_timer.setIdentifier("t");
			_duration = _timer;
			_state = (RuleState) RuleFunctions.create(_dimmer, "states", runtime("RuleState"));
			_state.setIdentifier("s1");
			Reaction reaction = (Reaction) RuleFunctions.create(_dimmer, "reactions", runtime("Reaction"));
			_condition = reaction;
			_log = (LogAction) RuleFunctions.create(reaction, "actions", runtime("LogAction"));

			_other = (Rule) RuleFunctions.create(_project, "rules", runtime("Rule"));
			_other.setIdentifier("other");
			_otherA = parameter(_other, "a");
			((RuleTimer) RuleFunctions.create(_other, "timers", runtime("RuleTimer"))).setIdentifier("t");
			_otherState = (RuleState) RuleFunctions.create(_other, "states", runtime("RuleState"));
			_otherState.setIdentifier("s1");
			_otherCondition = (Reaction) RuleFunctions.create(_other, "reactions", runtime("Reaction"));

			_condition.setCondition(canonical("$a > 1 and $ab > 2 and contains(\"$a s1 t\", \"lamp\")"
				+ " and running(t) and state() == \"s1\" and @lamp and @\"1/2/3\" and @\"rule/other/a\" > 0"));
			_duration.setDuration(canonical("$a * 1s + remaining(t)"));
			_log.setMessage("Level {$a} of {@\"rule/dimmer/a\"}, {{$a}} in {state()}");
			_otherCondition.setCondition(canonical("$a > 0 and @\"rule/dimmer/a\" > 0"
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
		rename(() -> _a.setIdentifier("level"));
		assertEquals(canonical("$level > 1 and $ab > 2 and contains(\"$a s1 t\", \"lamp\")"
			+ " and running(t) and state() == \"s1\" and @lamp and @\"1/2/3\" and @\"rule/other/a\" > 0"),
			_condition.getCondition());
		assertEquals("$level * 1s + remaining(t)", _duration.getDuration());
		assertEquals("Level {$level} of {@\"rule/dimmer/level\"}, {{$a}} in {state()}", _log.getMessage());
		assertEquals(canonical("$a > 0 and @\"rule/dimmer/level\" > 0"
			+ " and state(\"dimmer\") != \"s1\" and \"s1\" == state() and running(t)"),
			_otherCondition.getCondition());
		assertEquals(List.of(
			"4 expressions adjusted to the new id.",
			"The parameter a of the rule dimmer is now called level: a value set on the controller is lost, the next image resets it to the default."),
			messages());

		// An id that is no identifier is quoted.
		rename(() -> _a.setIdentifier("max-helligkeit"));
		assertTrue(String.valueOf(_condition.getCondition()).startsWith("$\"max-helligkeit\" > 1 and $ab > 2"));
		assertEquals("Level {$\"max-helligkeit\"} of {@\"rule/dimmer/max-helligkeit\"}, {{$a}} in {state()}",
			_log.getMessage());
	}

	/**
	 * The other rule's parameter of the same id: only its own references follow.
	 */
	public void testParameterOfOtherRule() {
		rename(() -> _otherA.setIdentifier("b"));
		assertEquals(canonical("$b > 0 and @\"rule/dimmer/a\" > 0"
			+ " and state(\"dimmer\") != \"s1\" and \"s1\" == state() and running(t)"),
			_otherCondition.getCondition());
		assertTrue(String.valueOf(_condition.getCondition()).endsWith("@\"rule/other/b\" > 0"));
		assertTrue(String.valueOf(_condition.getCondition()).startsWith("$a > 1"));
		assertEquals("$a * 1s + remaining(t)", _duration.getDuration());
		assertEquals("2 expressions adjusted to the new id.", messages().get(0));
	}

	/**
	 * Two parameters exchanging their ids in one transaction.
	 */
	public void testExchange() {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_a.setIdentifier("x");
			_ab.setIdentifier("a");
			tx.commit();
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_a.setIdentifier("ab");
			_ab.setIdentifier("a");
			tx.commit();
		}
		assertTrue(String.valueOf(_condition.getCondition()).startsWith("$ab > 1 and $a > 2 and"));
	}

	/**
	 * An id the model refuses (haumacher/ehome #110) is not stored and rewrites nothing.
	 */
	public void testInvalidParameterId() {
		Object condition = _condition.getCondition();
		Object otherCondition = _otherCondition.getCondition();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_a.setIdentifier("max helligkeit");
			tx.commit();
			fail("The commit must be refused.");
		} catch (RuntimeException ex) {
			// Refused by the constraint of the parameter id.
		}
		assertEquals("a", _a.getIdentifier());
		assertEquals(condition, _condition.getCondition());
		assertEquals(otherCondition, _otherCondition.getCondition());
		assertEquals("$a * 1s + remaining(t)", _duration.getDuration());
		assertEquals(List.of(), messages());
	}

	/**
	 * A timer: <code>running(t)</code> and <code>remaining(t)</code> of its rule only.
	 */
	public void testTimer() {
		rename(() -> _timer.setIdentifier("nachlauf"));
		assertTrue(String.valueOf(_condition.getCondition()).contains("running(nachlauf)"));
		assertTrue(String.valueOf(_condition.getCondition()).contains("\"$a s1 t\""));
		assertEquals("$a * 1s + remaining(nachlauf)", _duration.getDuration());
		assertTrue(String.valueOf(_otherCondition.getCondition()).endsWith("running(t)"));
		assertEquals(List.of("2 expressions adjusted to the new id."), messages());
	}

	/**
	 * A state: compared with <code>state()</code> in its rule and with <code>state("rule")</code>
	 * everywhere; not the state of the same id of the other rule.
	 */
	public void testState() {
		rename(() -> _state.setIdentifier("on"));
		assertTrue(String.valueOf(_condition.getCondition()).contains("state() == \"on\""));
		assertTrue(String.valueOf(_condition.getCondition()).contains("\"$a s1 t\""));
		assertEquals(canonical("$a > 0 and @\"rule/dimmer/a\" > 0"
			+ " and state(\"dimmer\") != \"on\" and \"s1\" == state() and running(t)"),
			_otherCondition.getCondition());
		assertEquals(List.of("2 expressions adjusted to the new id."), messages());

		rename(() -> _otherState.setIdentifier("off"));
		assertTrue(String.valueOf(_otherCondition.getCondition()).contains("\"off\" == state()"));
		assertTrue(String.valueOf(_condition.getCondition()).contains("state() == \"on\""));
		assertEquals(List.of("1 expression adjusted to the new id."), messages());
	}

	/**
	 * An application datapoint bound to a Zigbee function.
	 */
	public void testApplicationDatapoint() {
		assertNotNull(ZigbeeBindings.function(_lamp));
		rename(() -> _lamp.setIdentifier("stehlampe.ein"));
		assertTrue(String.valueOf(_condition.getCondition()).contains("@stehlampe.ein and @\"1/2/3\""));
		assertTrue(String.valueOf(_condition.getCondition()).contains("\"lamp\""));
		assertEquals(List.of("1 expression adjusted to the new id."), messages());
		assertEquals("stehlampe.ein", ApplicationDatapoints.id(_lamp));
	}

	/**
	 * A group address: its address, not its name.
	 */
	public void testGroupAddress() {
		rename(() -> _address.setName("Licht Flur"));
		assertTrue(String.valueOf(_condition.getCondition()).contains("@\"1/2/3\""));
		assertEquals(List.of(), messages());

		rename(() -> _address.setAddress(GA.parse("1/2/4", GroupAddressStyle.ThreeLevel)));
		assertTrue(String.valueOf(_condition.getCondition()).contains("@lamp and @\"1/2/4\" and"));
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

	private static void rename(Runnable change) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			change.run();
			tx.commit();
		}
	}


	private static RuleParameter parameter(TLObject rule, String id) {
		RuleParameter result = (RuleParameter) RuleFunctions.create(rule, "parameters", runtime("RuleParameter"));
		result.setIdentifier(id);
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
