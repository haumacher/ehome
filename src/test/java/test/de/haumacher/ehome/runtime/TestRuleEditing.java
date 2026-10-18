/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
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
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ExportRuntimeImageAction;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Rules and schedule entries authored in a project (haumacher/ehome #57) the way the views create
 * them ({@link RuleFunctions#create(TLObject, String, TLClass)}), on <code>samples/Test.knxproj</code>:
 * the image carries them and the controller's checks accept it; a field the controller would refuse
 * shows the refusal at the field in a form (the model constraints evaluated by the form's
 * validation over an overlay of the edited object, {@link RuleForm}): a syntax error as an error, a
 * type error, an unknown timer and a datapoint that is not writable as warnings, an hour of 25 in a
 * schedule entry; corrected, the warning disappears. A refusal of the exported image names the action
 * it stems from; a text that does not parse is not stored.
 */
@SuppressWarnings("javadoc")
public class TestRuleEditing extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	public void testAuthoring() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			List<?> datapoints = (List<?>) RuntimeImage.build(project, 0, NOW, false).getJson().get("datapoints");
			String writable = null;
			String readOnly = null;
			for (Object item : datapoints) {
				Map<?, ?> datapoint = (Map<?, ?>) item;
				if (!"boolean".equals(datapoint.get("type"))) {
					continue;
				}
				if (Boolean.TRUE.equals(datapoint.get("writable"))) {
					writable = writable == null ? (String) datapoint.get("id") : writable;
				} else {
					readOnly = readOnly == null ? (String) datapoint.get("id") : readOnly;
				}
			}
			assertNotNull("a writable switch: " + datapoints, writable);
			assertNotNull("a read-only switch: " + datapoints, readOnly);

			TLObject rule;
			TLObject reaction;
			TLObject write;
			TLObject restart;
			TLObject entry;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				rule = RuleFunctions.create(project, "rules", type("Rule"));
				TLObject timer = RuleFunctions.create(rule, "timers", type("RuleTimer"));
				timer.tUpdateByName("duration", "5min");
				reaction = RuleFunctions.create(rule, "reactions", type("Reaction"));
				TLObject trigger = RuleFunctions.create(reaction, "triggers", type("DatapointTrigger"));
				trigger.tUpdateByName("datapoint", writable);
				reaction.tUpdateByName("condition", "@\"" + writable + "\"");
				write = RuleFunctions.create(reaction, "actions", type("WriteAction"));
				write.tUpdateByName("datapoint", writable);
				write.tUpdateByName("value", "not @\"" + writable + "\"");
				restart = RuleFunctions.create(reaction, "actions", type("RestartTimerAction"));
				restart.tUpdateByName("timer", "t1");
				TLObject log = RuleFunctions.create(reaction, "elseActions", type("LogAction"));
				log.tUpdateByName("message", "off at {time()}, {{braces}}");

				entry = RuleFunctions.create(project, "schedules", type("WeeklySchedule"));
				TLObject expiry = RuleFunctions.create(rule, "reactions", type("Reaction"));
				RuleFunctions.create(expiry, "triggers", type("TimerTrigger")).tUpdateByName("timer", "t1");
				RuleFunctions.create(expiry, "triggers", type("ScheduleTrigger")).tUpdateByName("schedule", "entry-1");
				TLObject off = RuleFunctions.create(expiry, "actions", type("WriteAction"));
				off.tUpdateByName("datapoint", writable);
				off.tUpdateByName("value", "false");
				tx.commit();
			}
			assertEquals("rule-1", rule.tValueByName("identifier"));
			assertEquals("entry-1", entry.tValueByName("identifier"));

			// The image carries the rule and the entry, and the controller's checks accept it.
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			Map<String, Object> json = image.getJson();
			List<?> rules = (List<?>) json.get("rules");
			assertEquals(1, rules.size());
			Map<?, ?> exported = (Map<?, ?>) rules.get(0);
			assertEquals("rule-1", exported.get("id"));
			Map<?, ?> firstReaction = (Map<?, ?>) ((List<?>) exported.get("reactions")).get(0);
			assertEquals(List.of("off at ", Map.of("kind", "call", "function", "time", "args", List.of()), ", {braces}"),
				((Map<?, ?>) ((List<?>) firstReaction.get("elseActions")).get(0)).get("message"));
			assertEquals(List.of(Map.of("id", "entry-1", "label", ((Map<?, ?>) ((List<?>) json.get("schedules")).get(0)).get("label"),
				"kind", "weekly", "time", Map.of("hour", 6L, "minute", 0L, "second", 0L))), json.get("schedules"));
			String summary = ExportRuntimeImageAction.summary(image);
			assertTrue(summary, summary.endsWith(Resources.getInstance().getString(
				I18NConstants.EXPORT_AUTOMATION__RULES_SCHEDULES.fill(Integer.valueOf(1), Integer.valueOf(1)))));

			// Refusals at the field of a form.
			RuleForm form = new RuleForm(write);
			assertEquals(List.of(), form.errors("value"));
			assertEquals(List.of(), form.warnings("value"));
			form.set("value", "1 +");
			assertEquals(List.of("Not in the rule language: syntax error at character 4: an operand expected, not end of the text"),
				form.errors("value"));
			assertEquals(List.of(), form.warnings("value"));
			form.set("value", "1.5");
			assertEquals(List.of(), form.errors("value"));
			assertEquals(1, form.warnings("value").size());
			assertTrue(form.warnings("value").get(0), form.warnings("value").get(0).contains("typeError"));
			form.set("value", "");
			assertEquals(1, form.warnings("value").size());
			assertTrue(form.warnings("value").get(0), form.warnings("value").get(0).contains("member 'value' missing"));
			assertEquals("A missing value is shown at the value, not at the datapoint.", List.of(), form.warnings("datapoint"));
			form.set("value", "true");
			assertEquals(List.of(), form.warnings("value"));
			form.set("datapoint", readOnly);
			assertEquals(1, form.warnings("datapoint").size());
			assertTrue(form.warnings("datapoint").get(0), form.warnings("datapoint").get(0).contains("notWritable"));
			assertEquals(List.of(), form.warnings("value"));

			RuleForm restartForm = new RuleForm(restart);
			restartForm.set("timer", "nope");
			assertEquals(1, restartForm.warnings("timer").size());
			assertTrue(restartForm.warnings("timer").get(0), restartForm.warnings("timer").get(0).contains("unknownReference"));
			restartForm.set("timer", "t1");
			assertEquals(List.of(), restartForm.warnings("timer"));
			restartForm.set("duration", "5");
			assertTrue(restartForm.warnings("duration").toString(), restartForm.warnings("duration").get(0).contains("typeError"));

			RuleForm entryForm = new RuleForm(entry);
			assertEquals(List.of(), entryForm.warnings("time"));
			entryForm.set("time", "25:00");
			assertEquals(1, entryForm.warnings("time").size());
			assertTrue(entryForm.warnings("time").get(0), entryForm.warnings("time").get(0).contains("hour"));
			entryForm.set("time", "6:x");
			assertEquals(1, entryForm.errors("time").size());
			entryForm.set("output", writable);
			assertEquals(1, entryForm.warnings("output").size());
			assertTrue(entryForm.warnings("output").get(0), entryForm.warnings("output").get(0).contains("outputConflict"));

			// A stored refused value: the image is refused at the action it stems from.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				write.tUpdateByName("value", "1.5");
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			report = image.validate();
			assertFalse(report.isValid());
			ImageReport.Refusal refusal = report.getRefusals().get(0);
			assertEquals("rules[0].reactions[0].actions[0].value", refusal.path());
			assertSame(write, image.elementAt(refusal.path()));
			assertSame(entry, image.elementAt("schedules[0].time.hour"));
			assertSame(reaction, image.elementAt("rules[0].reactions[0]"));

			// A text that does not parse is not stored at all: the knowledge base checks the model's
			// constraints on commit.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				write.tUpdateByName("value", "1 +");
				tx.commit();
				fail("A text that does not parse is refused.");
			} catch (TopLogicException ex) {
				// Expected.
			}
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static TLClass type(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleEditing.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
