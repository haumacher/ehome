/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.rules.TestRuleText;

import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleExport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleImport;

/**
 * The authoring side of HomeControl's rule spec rounds one and two (haumacher/ehome #63): a
 * <code>read</code> action, a <code>silence</code> trigger and labels as language maps brought into
 * a project and exported again with exact values; the datapoints of the rule parameters
 * (<code>rule/&lt;ruleId&gt;/&lt;parameterId&gt;</code>), which the image implies, known to the
 * validator, with the refusals of the loader around them (a read of a datapoint no technology reads,
 * a write to a schedule's output, a rule naming its own parameter datapoints, a declared datapoint
 * with the reserved prefix).
 */
@SuppressWarnings("javadoc")
public class TestRuleSpecRounds extends BasicTestCase {

	private static final String RULES = """
		[{"id": "watch", "name": {"en": "Watch", "de": "Wache", "fr": "Garde"},
			"parameters": [
			{"id": "quiet", "label": {"en": "Quiet period", "de": "Ruhezeit"}, "type": "duration", "default": 600000},
			{"id": "mode", "label": "Mode", "type": "enumeration", "default": 0,
			"choices": [{"value": 0, "label": {"en": "Auto", "de-CH": "Automatik"}}, {"value": 1, "label": "Manual"}]}],
			"reactions": [{
			"triggers": [{"kind": "silence", "datapoint": "1/0/1", "after": {"kind": "parameter", "id": "quiet"}}],
			"actions": [{"kind": "read", "datapoint": "1/0/1"}, {"kind": "log", "level": "warning", "message": ["silent"]}]}]}]
		""";

	/**
		* Import and export of the read action, the silence trigger and language-map labels.
		*/
	public void testImportExport() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			// The datapoint the rule names (haumacher/ehome #90).
			TestRuleAuthoring.createDatapoints(project, List.of(Map.of("name", "1/0/1", "type", "boolean")));
			RuleImport importer = new RuleImport();
			TLObject rule;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				rule = importer.importRules(project, (List<?>) Json.parse(RULES, 100)).get(0);
				tx.commit();
			}
			assertEquals("Wache", text(rule.tValueByName("name"), Locale.GERMAN));
			assertEquals("Watch", text(rule.tValueByName("name"), Locale.ENGLISH));
			assertEquals(List.of("rule watch: the label's texts in fr are not kept (the languages of E-Home: en, de)",
				"choice of parameter mode: the label's texts in de-CH are not kept (the languages of E-Home: en, de)"),
				importer.getNotes().stream().map(note -> note.replace("de, en", "en, de")).toList());
			TLObject reaction = RuleFunctions.objects(rule, "reactions").get(0);
			TLObject silence = RuleFunctions.objects(reaction, "triggers").get(0);
			assertEquals("SilenceTrigger", silence.tType().getName());
			TLObject address = (TLObject) silence.tValueByName("datapoint");
			assertEquals("1/0/1", address.tValueByName("name"));
			assertEquals("$quiet", silence.tValueByName("after"));
			TLObject read = RuleFunctions.objects(reaction, "actions").get(0);
			assertEquals("ReadAction", read.tType().getName());
			assertSame(address, read.tValueByName("datapoint"));
			assertEquals("silence 1/0/1 after $quiet", RuleFunctions.summary(silence));
			assertEquals("read 1/0/1", RuleFunctions.summary(read));
			TLObject mode = RuleFunctions.objects(rule, "parameters").get(1);
			// de-CH is not a language of E-Home: the German text is what a controller in German shows.
			assertEquals("Auto", text(RuleFunctions.objects(mode, "choices").get(0).tValueByName("title"), Locale.GERMAN));

			List<Object> exported = new RuleExport().rules(project);
			Object expected = Json.parse("""
				[{"id": "watch", "name": {"en": "Watch", "de": "Wache"},
					"parameters": [
					{"id": "quiet", "label": {"en": "Quiet period", "de": "Ruhezeit"}, "type": "duration", "default": 600000},
					{"id": "mode", "label": "Mode", "type": "enumeration", "default": 0,
					"choices": [{"value": 0, "label": "Auto"}, {"value": 1, "label": "Manual"}]}],
					"reactions": [{
					"triggers": [{"kind": "silence", "datapoint": "1/0/1", "after": {"kind": "parameter", "id": "quiet"}}],
					"actions": [{"kind": "read", "datapoint": "1/0/1"}, {"kind": "log", "level": "warning", "message": ["silent"]}]}]}]
				""", 100);
			assertTrue(Json.write(exported), TestRuleText.same(expected, exported));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
		* The field of a read action shows the loader's refusal of a datapoint no technology reads (an
		* application datapoint), the field of a write the refusal of a rule's own parameter datapoint.
		*/
	public void testFieldChecks() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject read;
			TLObject write;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject calc = RuleFunctions.create(project, "applicationDatapoints", type("ApplicationDatapoint"));
				calc.tUpdateByName("identifier", "calc");
				TLObject rule = RuleFunctions.create(project, "rules", type("Rule"));
				TLObject reaction = RuleFunctions.create(rule, "reactions", type("Reaction"));
				RuleFunctions.create(reaction, "triggers", type("StartTrigger"));
				read = RuleFunctions.create(reaction, "actions", type("ReadAction"));
				read.tUpdateByName("datapoint", calc);
				write = RuleFunctions.create(reaction, "actions", type("WriteAction"));
				write.tUpdateByName("datapoint", rule);
				write.tUpdateByName("value", "false");
				tx.commit();
			}
			List<String> readWarnings = new RuleForm(read).warnings("datapoint");
			assertEquals(readWarnings.toString(), 1, readWarnings.size());
			assertTrue(readWarnings.get(0), readWarnings.get(0).contains("bound to no technology that reads values"));
			List<String> writeWarnings = new RuleForm(write).warnings("datapoint");
			assertEquals(writeWarnings.toString(), 1, writeWarnings.size());
			assertTrue(writeWarnings.get(0), writeWarnings.get(0).contains("is a parameter of this rule"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static com.top_logic.model.TLClass type(String name) {
		return (com.top_logic.model.TLClass) com.top_logic.model.util.TLModelUtil.findType("ehome.runtime:" + name);
	}

	/**
		* The validator knows the parameter datapoints the image implies and refuses what the loader
		* refuses around them and around reads.
		*/
	public void testParameterDatapointsAndReads() throws Exception {
		assertEquals(List.of(), refusals(""));
		assertEquals(List.of("rules[1].reactions[0].actions[0].datapoint: rule 'other': notReadable"),
			refusals(read("calc")));
		assertEquals(List.of("rules[1].reactions[0].actions[0].datapoint: rule 'other': notReadable"),
			refusals(read("rule/heat/setpoint")));
		assertEquals(List.of("rules[1].reactions[0].actions[0].datapoint: rule 'other': notReadable"),
			refusals(read("sched.out")));
		assertEquals(List.of("rules[1].reactions[0].actions[0].datapoint: rule 'other': notWritable"),
			refusals(write("sched.out", "true")));
		assertEquals(List.of("rules[1].reactions[0].actions[0].datapoint: rule 'other': notWritable"),
			refusals(write("rule/heat/fixed", "1")));
		assertEquals(List.of("rules[1].reactions[0].actions[0].datapoint: rule 'other': forbidden"),
			refusals(write("rule/other/enabled", "false")));
		assertEquals(List.of("datapoints[2].id: id 'rule/x': the prefix 'rule/' is reserved for the datapoints of rule parameters"),
			refusals("", "{\"id\": \"rule/x\", \"type\": \"boolean\", \"virtual\": {}}"));
	}

	private static String read(String datapoint) {
		return "{\"kind\": \"read\", \"datapoint\": \"" + datapoint + "\"}";
	}

	private static String write(String datapoint, String value) {
		return "{\"kind\": \"write\", \"datapoint\": \"" + datapoint + "\", \"value\": " + literal(value) + "}";
	}

	private static String literal(String value) {
		return value.equals("1") ? "{\"kind\": \"literal\", \"type\": \"integer\", \"value\": 1}"
			: "{\"kind\": \"literal\", \"type\": \"boolean\", \"value\": " + value + "}";
	}

	/**
		* The refusals of an image with a KNX datapoint, an application datapoint, a schedule entry with
		* an output and two rules: <code>heat</code> with a fixed parameter, and <code>other</code> that
		* reads and writes the datapoint of <code>heat</code>'s setpoint, reads the KNX datapoint and
		* watches it for silence; the given action is added to <code>other</code>.
		*/
	private static List<String> refusals(String action, String... datapoints) {
		StringBuilder extra = new StringBuilder();
		for (String datapoint : datapoints) {
			extra.append(", ").append(datapoint);
		}
		String image = """
			{"image": {"schemaVersion": "1.0", "projectId": "rounds", "revision": 1},
				"datapoints": [
				{"id": "1/0/1", "type": "real", "readOnStart": true, "knx": {"dpt": "9.001", "listen": ["1/0/1"]}},
				{"id": "calc", "type": "real", "writable": true, "virtual": {}}%s,
				{"id": "sched.out", "type": "boolean", "virtual": {}}],
				"schedules": [{"id": "morning", "label": "Morning", "kind": "weekly", "days": [1],
				"time": {"hour": 6, "minute": 0, "second": 0}, "output": "sched.out"}],
				"rules": [
				{"id": "heat", "name": "Heating", "parameters": [
				{"id": "setpoint", "label": "Setpoint", "type": "real", "default": 21.0},
				{"id": "fixed", "label": "Fixed", "type": "integer", "default": 1, "adjustable": false}],
				"reactions": [{"triggers": [{"kind": "parameter", "parameter": "setpoint"}], "actions": []}]},
				{"id": "other", "name": "Other", "reactions": [{
				"triggers": [{"kind": "silence", "datapoint": "1/0/1", "after": {"kind": "literal", "type": "duration", "value": 60000}},
				{"kind": "datapoint", "datapoint": "rule/heat/setpoint"}],
				"condition": {"kind": "binary", "op": ">", "left": {"kind": "datapoint", "name": "rule/heat/setpoint"},
				"right": {"kind": "literal", "type": "real", "value": 20.0}},
				"actions": [%s{"kind": "read", "datapoint": "1/0/1"},
				{"kind": "write", "datapoint": "rule/heat/setpoint", "value": {"kind": "literal", "type": "real", "value": 22.0}}]}]}]}
			""".formatted(extra, action.isEmpty() ? "" : action + ", ");
		ImageReport report = ImageValidator.validate(image);
		return report.getRefusals().stream().map(r -> {
			String text = r.toString();
			// The path and the reason up to the refusal code: the free text after it may change.
			int code = text.indexOf(": ", text.indexOf("rule '") + 1);
			int end = code >= 0 ? text.indexOf(':', code + 2) : -1;
			return text.contains("rule '") && end > 0 ? text.substring(0, end) : text;
		}).toList();
	}

	private static String text(Object value, Locale locale) {
		return ((LiteralKey) value).getTranslations().get(locale);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleSpecRounds.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
