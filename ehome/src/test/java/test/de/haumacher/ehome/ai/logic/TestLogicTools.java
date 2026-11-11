/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai.logic;

import static test.de.haumacher.ehome.ai.logic.LogicFixture.read;
import static test.de.haumacher.ehome.ai.logic.LogicFixture.turn;

import java.util.List;
import java.util.Map;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.tl.ai.core.agent.AgentApplicationService;
import com.top_logic.tl.ai.core.agent.AgentPolicyService;
import com.top_logic.tl.ai.core.agent.AgentRuntimeService;
import com.top_logic.tl.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.tl.ai.core.security.AiToolAccessService;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.ai.logic.BlockTools;
import de.haumacher.ehome.ai.logic.DiagnosisTools;
import de.haumacher.ehome.ai.logic.FunctionTools;
import de.haumacher.ehome.ai.logic.RuleTools;
import de.haumacher.ehome.ai.logic.SimulationTools;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleExport;

/**
 * The assistant's tools for E-Home's logic (haumacher/ehome #128) called directly, as a chat run's turn
 * calls them, on a project built by hand ({@link LogicFixture}):
 *
 * <ul>
 * <li>a frost protection on group addresses is checked, saved and tried in the simulation: a low
 * temperature makes the rule write the heating's address;</li>
 * <li>a text of the rule language that does not parse is refused with its position, and nothing is
 * saved; an id naming no datapoint is refused;</li>
 * <li>a saved rule is explained with its trigger and effect named Name (id), and changed in place;</li>
 * <li>the users of a datapoint are a rule and a function;</li>
 * <li>a room controller created in the kitchen and bound has no problem in the image, and the function
 * proposal adds its climate function; a page of the kitchen is created;</li>
 * <li>a rule whose datapoint was deleted is reported by the image's problems;</li>
 * <li>a viewer reads, explains and simulates, and is refused saving.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestLogicTools extends BasicTestCase {

	static final String FROST = """
		{"rules": [{"id": "frost", "name": "Frostschutz",
		"parameters": [{"id": "limit", "label": "Grenze", "type": "real", "default": "3.0", "min": "-20.0", "max": "10.0", "unit": "°C"}],
		"reactions": [{"triggers": [{"kind": "datapoint", "datapoint": "1/0/0"}], "condition": "@\\"1/0/0\\" < $limit",
		"actions": [{"kind": "write", "datapoint": "1/0/1", "value": "true", "onlyOnChange": true}],
		"elseActions": [{"kind": "write", "datapoint": "1/0/1", "value": "false", "onlyOnChange": true}]}]}]}
		""";

	private final LogicFixture _fixture = new LogicFixture("ehomeLogicTools");

	private final RuleTools _rules = new RuleTools();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_fixture.setUp();
	}

	@Override
	protected void tearDown() throws Exception {
		_fixture.tearDown();
		super.tearDown();
	}

	public void testFrostProtection() throws Exception {
		TLObject project = _fixture.project();
		ToolResult checked = turn(_fixture.installer(), c -> _rules.checkAutomation(c, project, definitions(FROST)));
		assertFalse(checked.text(), checked.failed());
		assertTrue("Nothing saved by the check.", RuleExport.orderedRules(project).isEmpty());

		ToolResult tried = turn(_fixture.installer(), c -> new SimulationTools().simulate(c, project, "2026-11-10T06:00",
			List.of(Map.of("set", "1/0/0", "value", "1.5"), Map.of("wait", "1min")), definitions(FROST)));
		assertFalse(tried.text(), tried.failed());
		assertTrue(tried.text(), tried.text().contains("write Frostschutz Heizung (1/0/1) = true"));
		assertTrue("The simulation saved nothing.", RuleExport.orderedRules(project).isEmpty());

		ToolResult saved = turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(FROST), null));
		assertFalse(saved.text(), saved.failed());
		List<TLObject> rules = RuleExport.orderedRules(project);
		assertEquals(1, rules.size());
		TLObject rule = rules.get(0);

		ToolResult explained = read(_fixture.installer(), () -> _rules.explainRule(rule));
		assertTrue(explained.text(), explained.text().contains("Außentemperatur (1/0/0)"));
		assertTrue(explained.text(), explained.text().contains("Frostschutz Heizung (1/0/1)"));

		// The saved rule in the simulation, the temperature above the limit.
		ToolResult warm = read(_fixture.installer(), () -> new SimulationTools().simulate(null, project, "2026-11-10T06:00",
			List.of(Map.of("set", "1/0/0", "value", "1.5"), Map.of("wait", "10s"), Map.of("set", "1/0/0", "value", "8.0")),
			null));
		assertTrue(warm.text(), warm.text().contains("write Frostschutz Heizung (1/0/1) = false"));

		// Changed in place: the limit's default, the rule keeps its identity.
		String changed = FROST.replace("\"default\": \"3.0\"", "\"default\": \"5.0\"");
		ToolResult replaced = turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(changed), null));
		assertFalse(replaced.text(), replaced.failed());
		assertEquals(List.of(rule), RuleExport.orderedRules(project));
		TLObject limit = (TLObject) ((java.util.Collection<?>) rule.tValueByName("parameters")).iterator().next();
		assertEquals("5.0", limit.tValueByName("default"));
	}

	public void testInvalidTextRefused() throws Exception {
		TLObject project = _fixture.project();
		String broken = FROST.replace("< $limit", "< < $limit");
		ToolResult result = turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(broken), null));
		assertTrue(result.text(), result.failed());
		assertTrue(result.text(), result.text().contains("rules[0].reactions[0].condition"));
		assertTrue(result.text(), result.text().contains("\"position\": 12"));
		assertTrue("Nothing saved.", RuleExport.orderedRules(project).isEmpty());

		String unknown = FROST.replace("\"datapoint\": \"1/0/0\"", "\"datapoint\": \"9/9/9\"");
		ToolResult refused = turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(unknown), null));
		assertTrue(refused.text(), refused.failed());
		assertTrue(refused.text(), refused.text().contains("9/9/9"));
		assertTrue("Nothing saved.", RuleExport.orderedRules(project).isEmpty());
	}

	public void testUsageAndFunctionsAndBlocks() throws Exception {
		TLObject project = _fixture.project();
		assertFalse(turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(FROST), null)).failed());
		ToolResult function = turn(_fixture.installer(), c -> new FunctionTools().createFunction(c, project, "sensor",
			"Außen", _fixture.room(), Map.of("value", "1/0/0"), null, null));
		assertFalse(function.text(), function.failed());

		ToolResult usage = read(_fixture.installer(), () -> _rules.datapointUsage(_fixture.address("1/0/0")));
		assertTrue(usage.text(), usage.text().contains("Frostschutz"));
		assertTrue(usage.text(), usage.text().contains("Außen"));

		BlockTools blocks = new BlockTools();
		ToolResult block = turn(_fixture.installer(), c -> blocks.createBlock(c, _fixture.room(),
			"org.homecontrol.room-controller 1.0", "Heizung Küche",
			Map.of("temperature", List.of("1/2/0"), "valve", List.of("1/2/1")), Map.of("comfort", Double.valueOf(21.5)), null));
		assertFalse(block.text(), block.failed());
		assertTrue(block.text(), block.text().contains("\"imageRefuses\": []"));

		ToolResult proposal = turn(_fixture.installer(), c -> new FunctionTools().proposeFunctions(c, project, null));
		assertFalse(proposal.text(), proposal.failed());
		assertTrue("The room controller proposes a climate function: " + proposal.text(),
			FunctionProposal.functions(project).stream().anyMatch(f -> "climate".equals(FunctionProposal.kind(f))));
		// The unused functions apart from those in use, and the warnings about the wiring (haumacher/ehome #134,
		// #135).
		assertTrue(proposal.text(), proposal.text().contains("\"addedUnused\": ["));
		assertTrue(proposal.text(), proposal.text().contains("\"warnings\": ["));

		ToolResult schedule = turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(
			"{\"schedules\": [{\"id\": \"workdayMorning\", \"label\": \"Workday morning\", \"kind\": \"weekly\","
				+ " \"days\": [1, 2, 3, 4, 5], \"time\": \"06:30\"}]}"), null));
		assertFalse(schedule.text(), schedule.failed());
		assertEquals(1, ((java.util.Collection<?>) project.tValueByName("schedules")).size());

		ToolResult page = turn(_fixture.installer(), c -> new FunctionTools().createPage(c, project, "Erdgeschoss",
			List.of(Map.of("title", "Küche", "rooms", List.of(_fixture.room().tId().asString()))), null));
		assertFalse(page.text(), page.failed());
		assertEquals(1, ((java.util.Collection<?>) project.tValueByName("pages")).size());
	}

	public void testBrokenReferenceAndViewer() throws Exception {
		TLObject project = _fixture.project();
		String rule = """
			{"rules": [{"id": "hall", "name": "Hall",
			"reactions": [{"triggers": [{"kind": "datapoint", "datapoint": "hall.presence"}],
			"actions": [{"kind": "write", "datapoint": "hall.light", "value": "@hall.presence"}]}]}]}
			""";
		assertFalse(turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(rule), null)).failed());
		ToolResult valid = read(_fixture.viewer(), () -> new DiagnosisTools().imageProblems(project));
		assertTrue(valid.text(), valid.text().contains("\"valid\": true"));

		ToolResult viewerSave = turn(_fixture.viewer(), c -> _rules.saveAutomation(c, project, definitions(FROST), null));
		assertTrue(viewerSave.text(), viewerSave.failed());
		assertTrue(viewerSave.text(), viewerSave.text().contains("Permission denied"));
		ToolResult viewerSimulates = turn(_fixture.viewer(), c -> new SimulationTools().simulate(c, project,
			"2026-11-10T06:00", List.of(Map.of("set", "hall.presence", "value", "true")), null));
		assertTrue(viewerSimulates.text(), viewerSimulates.text().contains("write hall.light = true"));

		ToolResult deleted = turn(_fixture.installer(),
			c -> _rules.deleteAutomation(c, List.of(_fixture.datapoint("hall.light")), null));
		assertFalse(deleted.text(), deleted.failed());
		assertTrue(deleted.text(), deleted.text().contains("Hall"));
		ToolResult broken = read(_fixture.installer(), () -> new DiagnosisTools().imageProblems(project));
		assertTrue(broken.text(), broken.text().contains("\"valid\": false"));
		assertTrue(broken.text(), broken.text().contains("„hall“"));
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> definitions(String json) {
		try {
			return (Map<String, Object>) Json.parse(json, 100);
		} catch (Json.JsonException ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	public static Test suite() {
		return suite(TestLogicTools.class);
	}

	/**
	 * The suite of a test of the logic tools: the services of a chat run's tool calls.
	 */
	static Test suite(Class<? extends junit.framework.TestCase> testClass) {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(testClass,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, AiToolAccessService.Module.INSTANCE,
			TopLogicAgentDefinitions.Module.INSTANCE, AgentApplicationService.Module.INSTANCE,
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE));
	}

}
