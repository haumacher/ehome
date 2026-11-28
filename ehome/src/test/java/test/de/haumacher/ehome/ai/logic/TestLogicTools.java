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
import com.top_logic.element.accesstoken.AccessTokenPermissions;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.ai.core.agent.AgentApplicationService;
import com.top_logic.ai.core.agent.AgentPolicyService;
import com.top_logic.ai.core.agent.AgentRuntimeService;
import com.top_logic.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.ai.core.security.AiToolAccessService;
import com.top_logic.ai.tool.api.ToolResult;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.ai.logic.BlockTools;
import de.haumacher.ehome.ai.logic.DiagnosisTools;
import de.haumacher.ehome.ai.logic.FunctionTools;
import de.haumacher.ehome.ai.logic.RuleTools;
import de.haumacher.ehome.ai.logic.SimulationTools;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.BlockInstance;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageCache;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.ProposeFunctionsAction;
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
 * <li>a viewer reads, explains and simulates, and is refused saving;</li>
 * <li>a rule writes an application datapoint that is not writable, but no status address;</li>
 * <li>the function proposal with <code>overwrite</code> renames the function of a renamed room controller, as
 * the command does (haumacher/ehome #177).</li>
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
		RuleParameter limit = ((Rule) rule).getParameters().iterator().next();
		assertEquals("5.0", limit.getDefault());
	}

	/**
	 * A check of several rules builds the project's image once (production report of 2026-11-15: every
	 * rule of a check built the whole image anew, configuring every device once per connection, and
	 * the checks of a larger project ran into the MCP client's timeout).
	 */
	public void testCheckBuildsImageOnce() throws Exception {
		TLObject project = _fixture.project();
		String rule = FROST.substring(FROST.indexOf("[{") + 1, FROST.lastIndexOf("]}"));
		String three = "{\"rules\": [" + rule + ", " + rule.replace("\"frost\"", "\"frost2\"") + ", "
			+ rule.replace("\"frost\"", "\"frost3\"") + "]}";
		long before = ImageCache.builds();
		ToolResult checked = turn(_fixture.installer(), c -> _rules.checkAutomation(c, project, definitions(three)));
		assertFalse(checked.text(), checked.failed());
		assertEquals("One image build for the check of three rules.", 1, ImageCache.builds() - before);
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

	/**
	 * A name the assistant passes has no language of its own: it is stored in the language of the
	 * acting user only, not in every language (haumacher/ehome #205) - the name of a function, a
	 * virtual device, a page and of a rule saved as a definition.
	 */
	public void testPlainNamesInUserLanguage() throws Exception {
		TLObject project = _fixture.project();
		String language = read(_fixture.installer(), FunctionProposal::userLanguage);
		assertFalse(turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(FROST), null)).failed());
		assertFalse(turn(_fixture.installer(), c -> new FunctionTools().createFunction(c, project, "sensor",
			"Außen", _fixture.room(), Map.of("value", "1/0/0"), null, null)).failed());
		assertFalse(turn(_fixture.installer(), c -> new BlockTools().createBlock(c, _fixture.room(),
			"org.homecontrol.room-controller 1.0", "Heizung Küche", null, null, null)).failed());
		assertFalse(turn(_fixture.installer(), c -> new FunctionTools().createPage(c, project, "Erdgeschoss",
			List.of(Map.of("title", "Küche")), null)).failed());

		Project typed = (Project) project;
		assertEquals(Map.of(language, "Frostschutz"), texts(((Rule) typed.getRules().get(0)).getNameKey()));
		assertEquals(Map.of(language, "Außen"), texts(((Function) typed.getFunctions().get(0)).getNameKey()));
		assertEquals(Map.of(language, "Heizung Küche"), texts(((BlockInstance) typed.getBlocks().get(0)).getNameKey()));
		assertEquals(Map.of(language, "Erdgeschoss"), texts(typed.getPages().get(0).getNameKey()));
		assertEquals(Map.of(language, "Küche"), texts(typed.getPages().get(0).getSections().get(0).getTitle()));
	}

	private static Map<String, String> texts(Object text) {
		assertTrue(String.valueOf(text), text instanceof com.top_logic.basic.util.ResKey.LiteralKey);
		Map<String, String> result = new java.util.TreeMap<>();
		((com.top_logic.basic.util.ResKey.LiteralKey) text).getTranslations()
			.forEach((locale, value) -> result.put(locale.getLanguage(), value));
		return result;
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

		ToolResult proposal = turn(_fixture.installer(), c -> new FunctionTools().proposeFunctions(c, project, null, null));
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
		assertEquals(1, ((Project) project).getSchedules().size());

		ToolResult page = turn(_fixture.installer(), c -> new FunctionTools().createPage(c, project, "Erdgeschoss",
			List.of(Map.of("title", "Küche", "rooms", List.of(_fixture.room().tId().asString()))), null));
		assertFalse(page.text(), page.failed());
		assertEquals(1, ((Project) project).getPages().size());
	}

	/**
	 * The tool proposing functions with <code>overwrite</code> (haumacher/ehome #177) does what the
	 * command does: the room controller renamed, the climate function its proposal made follows the new
	 * name in place, after a dry run naming the same function and rename.
	 */
	public void testProposalOverwrite() throws Exception {
		TLObject project = _fixture.project();
		ToolResult block = turn(_fixture.installer(), c -> new BlockTools().createBlock(c, _fixture.room(),
			"org.homecontrol.room-controller 1.0", "Heizung Küche",
			Map.of("temperature", List.of("1/2/0"), "valve", List.of("1/2/1")), Map.of("comfort", Double.valueOf(21.5)), null));
		assertFalse(block.text(), block.failed());
		assertFalse(turn(_fixture.installer(), c -> new FunctionTools().proposeFunctions(c, project, null, null)).failed());
		TLObject climate = FunctionProposal.functions(project).stream()
			.filter(f -> "climate".equals(FunctionProposal.kind(f))).findFirst().orElseThrow();
		TLObject instance = BlockInstances.of(project).get(0);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			((BlockInstance) instance).setNameKey(FunctionProposal.text(Map.of("de", "Raumregler Küche", "en", "Raumregler Küche")));
			tx.commit();
		}

		// Without the option nothing is renamed (the name is the user's for the run without it).
		ToolResult plain = turn(_fixture.installer(), c -> new FunctionTools().proposeFunctions(c, project, null, null));
		assertFalse(plain.text(), plain.failed());
		assertFalse(plain.text(), plain.text().contains("Raumregler"));

		ToolResult dry = turn(_fixture.installer(),
			c -> new FunctionTools().proposeFunctions(c, project, Boolean.TRUE, Boolean.TRUE));
		assertFalse(dry.text(), dry.failed());
		assertTrue(dry.text(), dry.text().contains("\"wouldRename\": ["));
		assertTrue(dry.text(), dry.text().contains("\"after\": \"Raumregler Küche\""));
		assertTrue(dry.text(), dry.text().contains(climate.tId().asString()));
		assertEquals(List.of(climate), FunctionProposal.overwrite(project).differing());

		// The command overwrites what the tool named.
		FunctionProposal.Run run = ProposeFunctionsAction.apply(project, true);
		assertEquals(List.of(climate), run.overwritten());
		assertEquals("Raumregler Küche", Resources.getInstance().getString(((Function) climate).getNameKey()));

		// The tool with the option afterwards: nothing left to overwrite.
		ToolResult again = turn(_fixture.installer(),
			c -> new FunctionTools().proposeFunctions(c, project, null, Boolean.TRUE));
		assertFalse(again.text(), again.failed());
		assertTrue(again.text(), again.text().contains("\"overwritten\": []"));
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

	/**
	 * A rule writes an application datapoint the user may not change (haumacher/ehome #138, homecontrol
	 * spec/rules.md 7.3, schema 1.6): checkAutomation takes it; a write to a group address that only
	 * reports a status (not writable) stays refused, with the reason naming the address.
	 */
	public void testRuleWritesApplicationDatapointNotWritable() throws Exception {
		TLObject project = _fixture.project();
		assertFalse(ApplicationDatapoints.writable(_fixture.datapoint("window.open")));
		String mirror = """
			{"rules": [{"id": "valve", "name": "Ventil",
			"reactions": [{"triggers": [{"kind": "datapoint", "datapoint": "1/2/1"}],
			"actions": [{"kind": "write", "datapoint": "window.open", "value": "@\\"1/2/1\\" > 0", "onlyOnChange": true}]}]}]}
			""";
		ToolResult checked = turn(_fixture.installer(), c -> _rules.checkAutomation(c, project, definitions(mirror)));
		assertFalse(checked.text(), checked.failed());

		String status = mirror.replace("\"datapoint\": \"window.open\", \"value\": \"@\\\"1/2/1\\\" > 0\"",
			"\"datapoint\": \"1/2/0\", \"value\": \"21.0\"");
		assertFalse("The write names the status address.", status.equals(mirror));
		ToolResult refused = turn(_fixture.installer(), c -> _rules.checkAutomation(c, project, definitions(status)));
		assertTrue(refused.text(), refused.failed());
		assertTrue(refused.text(), refused.text().contains("1/2/0"));
		assertTrue(refused.text(), refused.text().contains("/ datapoint"));

		// The rule saved; the image takes it (written as schema 1.6).
		assertFalse(turn(_fixture.installer(), c -> _rules.saveAutomation(c, project, definitions(mirror), null)).failed());
		ToolResult image = read(_fixture.installer(), () -> new DiagnosisTools().imageProblems(project));
		assertTrue(image.text(), image.text().contains("\"valid\": true"));
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
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE,
			AccessTokenPermissions.Module.INSTANCE));
	}

}
