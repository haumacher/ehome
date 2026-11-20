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

import com.top_logic.basic.util.ResKey;
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

import de.haumacher.ehome.model.logical.ProjectDatapoint;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.LogAction;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.RestartTimerAction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleTimer;
import de.haumacher.ehome.model.runtime.ScheduleTrigger;
import de.haumacher.ehome.model.runtime.TimerTrigger;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.model.runtime.WriteAction;
import de.haumacher.ehome.runtime.ExportRuntimeImageAction;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleReferences;
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
			// The references name the objects (haumacher/ehome #90).
			TLObject writableObject = RuleReferences.datapoints(project).get(writable);
			TLObject readOnlyObject = RuleReferences.datapoints(project).get(readOnly);

			Rule rule;
			Reaction reaction;
			WriteAction write;
			RestartTimerAction restart;
			WeeklySchedule entry;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				rule = (Rule) RuleFunctions.create(project, "rules", type("Rule"));
				rule.setNameKey(ResKey.text("rule"));
				RuleTimer timer = (RuleTimer) RuleFunctions.create(rule, "timers", type("RuleTimer"));
				timer.setDuration("5min");
				reaction = (Reaction) RuleFunctions.create(rule, "reactions", type("Reaction"));
				DatapointTrigger trigger = (DatapointTrigger) RuleFunctions.create(reaction, "triggers", type("DatapointTrigger"));
				trigger.setDatapoint((ProjectDatapoint) writableObject);
				reaction.setCondition("@\"" + writable + "\"");
				write = (WriteAction) RuleFunctions.create(reaction, "actions", type("WriteAction"));
				write.setDatapoint((ProjectDatapoint) writableObject);
				write.setValue("not @\"" + writable + "\"");
				restart = (RestartTimerAction) RuleFunctions.create(reaction, "actions", type("RestartTimerAction"));
				restart.setTimer(timer);
				LogAction log = (LogAction) RuleFunctions.create(reaction, "elseActions", type("LogAction"));
				log.setMessage("off at {time()}, {{braces}}");

				entry = (WeeklySchedule) RuleFunctions.create(project, "schedules", type("WeeklySchedule"));
				((de.haumacher.ehome.model.runtime.ScheduleEntry) entry).setLabel(ResKey.text("entry"));
				TLObject expiry = RuleFunctions.create(rule, "reactions", type("Reaction"));
				((TimerTrigger) RuleFunctions.create(expiry, "triggers", type("TimerTrigger"))).setTimer(timer);
				((ScheduleTrigger) RuleFunctions.create(expiry, "triggers", type("ScheduleTrigger"))).setSchedule(entry);
				WriteAction off = (WriteAction) RuleFunctions.create(expiry, "actions", type("WriteAction"));
				off.setDatapoint((ProjectDatapoint) writableObject);
				off.setValue("false");
				tx.commit();
			}
			assertEquals("rule-1", rule.getIdentifier());
			assertEquals("entry-1", entry.getIdentifier());

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
			assertTrue(form.warnings("value").get(0), form.warnings("value").get(0).contains("cannot be written to the datapoint"));
			form.set("value", "");
			assertEquals(1, form.warnings("value").size());
			assertTrue(form.warnings("value").get(0), form.warnings("value").get(0).contains("The member \"value\" is missing."));
			assertEquals("A missing value is shown at the value, not at the datapoint.", List.of(), form.warnings("datapoint"));
			form.set("value", "true");
			assertEquals(List.of(), form.warnings("value"));
			form.set("datapoint", readOnlyObject);
			assertEquals(1, form.warnings("datapoint").size());
			assertTrue(form.warnings("datapoint").get(0), form.warnings("datapoint").get(0).contains("is not writable"));
			assertEquals(List.of(), form.warnings("value"));

			RuleForm restartForm = new RuleForm(restart);
			restartForm.set("timer", null);
			assertEquals(1, restartForm.warnings("timer").size());
			restartForm.set("timer", restart.getTimer());
			assertEquals(List.of(), restartForm.warnings("timer"));
			restartForm.set("duration", "5");
			assertTrue(restartForm.warnings("duration").toString(), restartForm.warnings("duration").get(0).contains("must be a duration"));

			RuleForm entryForm = new RuleForm(entry);
			assertEquals(List.of(), entryForm.warnings("time"));
			entryForm.set("time", "25:00");
			assertEquals(1, entryForm.warnings("time").size());
			assertTrue(entryForm.warnings("time").get(0), entryForm.warnings("time").get(0).contains("hour"));
			entryForm.set("time", "6:x");
			assertEquals(1, entryForm.errors("time").size());

			// A stored refused value: the image is refused at the action it stems from.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				write.setValue("1.5");
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
				write.setValue("1 +");

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
