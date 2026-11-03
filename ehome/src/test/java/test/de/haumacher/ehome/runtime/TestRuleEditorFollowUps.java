/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.util.ValueNormalizer;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleExport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleImport;

/**
 * The follow-ups of the rule editor (haumacher/ehome #60), on a project without an ETS import whose
 * only datapoint is an application datapoint:
 *
 * <ul>
 * <li>Each field is checked on its own: two refused fields of one action (an unknown datapoint and a
 * value naming an unknown parameter; an unknown timer and a duration that is no duration) both show
 * their refusal at once.</li>
 * <li>Reactions, triggers and actions are labeled by their content, the summary of their table.</li>
 * <li>Saving stores the texts of the rule language in their canonical form (a text that does not
 * parse is not stored at all: its syntax check is an error).</li>
 * <li>The choices of a parameter are objects with value and label, exported as the image's
 * <code>choices</code> and checked at their fields.</li>
 * <li>The import of a runtime image from a file (the command of the rules view) yields the same rules
 * and schedule entries as {@link RuleImport} on the parsed image.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestRuleEditorFollowUps extends BasicTestCase {

	/** The image of the HomeControl specification with two rules and two schedule entries. */
	private static final File EXAMPLE = new File(TestImageConformance.IMAGES, "example.json");

	public void testEachFieldOnItsOwn() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject write;
			TLObject start;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject light = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
				light.tUpdateByName("identifier", "light");
				light.tUpdateByName("writable", Boolean.TRUE);
				TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
				RuleFunctions.create(rule, "timers", runtime("RuleTimer"));
				TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				RuleFunctions.create(reaction, "triggers", runtime("StartTrigger"));
				write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
				write.tUpdateByName("datapoint", "nope");
				write.tUpdateByName("value", "$missing");
				start = RuleFunctions.create(reaction, "actions", runtime("StartTimerAction"));
				start.tUpdateByName("timer", "unknownTimer");
				start.tUpdateByName("duration", "true");
				tx.commit();
			}

			RuleForm writeForm = new RuleForm(write);
			assertRefused(writeForm, "datapoint", "'nope'");
			assertRefused(writeForm, "value", "missing");

			RuleForm startForm = new RuleForm(start);
			assertRefused(startForm, "timer", "unknownTimer");
			assertRefused(startForm, "duration", "duration");

			// Fixed, the other field's refusal stays and its own disappears.
			writeForm.set("datapoint", "light");
			assertEquals(List.of(), writeForm.warnings("datapoint"));
			assertRefused(writeForm, "value", "missing");
			writeForm.set("value", "true");
			assertEquals(List.of(), writeForm.warnings("value"));

			startForm.set("duration", "5min");
			assertEquals(List.of(), startForm.warnings("duration"));
			assertRefused(startForm, "timer", "unknownTimer");
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static void assertRefused(RuleForm form, String attribute, String part) {
		List<String> warnings = form.warnings(attribute);
		assertEquals(attribute + ": " + warnings, 1, warnings.size());
		assertTrue(attribute + ": " + warnings, warnings.get(0).contains(part));
	}

	public void testLabels() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject reaction;
			TLObject trigger;
			TLObject action;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
				reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				trigger = RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger"));
				trigger.tUpdateByName("datapoint", "light");
				reaction.tUpdateByName("condition", "@light");
				action = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
				action.tUpdateByName("datapoint", "lamp");
				action.tUpdateByName("value", "true");
				tx.commit();
			}
			assertEquals("datapoint light", derived(trigger, "summary"));
			assertEquals("write lamp := true", derived(action, "summary"));
			assertEquals("when datapoint light if @light", derived(reaction, "summary"));
			for (TLObject obj : List.of(reaction, trigger, action)) {
				assertEquals(RuleFunctions.summary(obj), asRoot(obj));
			}
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static String asRoot(TLObject obj) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		try {
			return MetaLabelProvider.INSTANCE.getLabel(obj);
		} finally {
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	/**
	 * Enters every text of the object again, as a form does: through the value normalizer its
	 * attribute declares in the model.
	 */
	private static void enter(TLObject obj) {
		int normalized = 0;
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (ValueNormalizer.isAnnotated(part)) {
				obj.tUpdate(part, ValueNormalizer.annotated(part).normalize(obj, part, obj.tValue(part)));
				normalized++;
			}
		}
		assertTrue(TLModelUtil.qualifiedName(obj.tType()), normalized > 0);
	}

	public void testNormalizeOnEntry() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject reaction;
			TLObject parameter;
			TLObject timer;
			TLObject log;
			TLObject entry;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
				parameter = RuleFunctions.create(rule, "parameters", runtime("RuleParameter"));
				parameter.tUpdateByName("type", parameterType("duration"));
				parameter.tUpdateByName("default", "180000ms");
				parameter.tUpdateByName("step", "60s");
				timer = RuleFunctions.create(rule, "timers", runtime("RuleTimer"));
				timer.tUpdateByName("duration", "$p1+(2 * 1min)");
				reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				reaction.tUpdateByName("condition", "@\"light\"==true  and not(@door)");
				log = RuleFunctions.create(reaction, "actions", runtime("LogAction"));
				log.tUpdateByName("message", "on: {  @light }");
				entry = RuleFunctions.create(project, "schedules", runtime("DatesSchedule"));
				entry.tUpdateByName("dates", "2027-01-08;2027-01-22");
				entry.tUpdateByName("time", "6:15");
				tx.commit();
			}
			String condition = (String) reaction.tValueByName("condition");
			String duration = (String) timer.tValueByName("duration");
			String message = (String) log.tValueByName("message");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (TLObject obj : List.of(reaction, parameter, timer, log, entry)) {
					enter(obj);
				}
				tx.commit();
			}
			assertEquals(RuleText.print(RuleText.parse(condition)), reaction.tValueByName("condition"));
			assertFalse(condition.equals(reaction.tValueByName("condition")));
			assertEquals(RuleText.print(RuleText.parse(duration)), timer.tValueByName("duration"));
			assertFalse(duration.equals(timer.tValueByName("duration")));
			assertEquals(RuleText.printMessage(RuleText.parseMessage(message)), log.tValueByName("message"));
			assertEquals("3min", parameter.tValueByName("default"));
			assertEquals("1min", parameter.tValueByName("step"));
			assertEquals("2027-01-08, 2027-01-22", entry.tValueByName("dates"));
			assertEquals("06:15", entry.tValueByName("time"));

			// Canonical texts stay as they are.
			String canonical = (String) reaction.tValueByName("condition");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				enter(reaction);
				enter(timer);
				tx.commit();
			}
			assertEquals(canonical, reaction.tValueByName("condition"));
			assertEquals(RuleText.print(RuleText.parse(duration)), timer.tValueByName("duration"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testChoices() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject parameter;
			TLObject eco;
			TLObject comfort;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
				parameter = RuleFunctions.create(rule, "parameters", runtime("RuleParameter"));
				parameter.tUpdateByName("type", parameterType("integer"));
				parameter.tUpdateByName("default", "1");
				eco = RuleFunctions.create(parameter, "choices", runtime("RuleChoice"));
				eco.tUpdateByName("value", "1");
				eco.tUpdateByName("title", FunctionProposal.text(Map.of("de", "Eco", "en", "Eco")));
				comfort = RuleFunctions.create(parameter, "choices", runtime("RuleChoice"));
				assertEquals("0", comfort.tValueByName("value"));
				comfort.tUpdateByName("value", "2");
				comfort.tUpdateByName("title", FunctionProposal.text(Map.of("de", "Comfort", "en", "Comfort")));
				TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				RuleFunctions.create(reaction, "triggers", runtime("StartTrigger"));
				tx.commit();
			}
			List<Object> rules = new RuleExport().rules(project);
			Map<?, ?> exported = (Map<?, ?>) ((List<?>) ((Map<?, ?>) rules.get(0)).get("parameters")).get(0);
			assertEquals(List.of(Map.of("value", Long.valueOf(1), "label", "Eco"),
				Map.of("value", Long.valueOf(2), "label", "Comfort")), exported.get("choices"));

			// A value that is no integer: a syntax error at the choice.
			RuleForm comfortForm = new RuleForm(comfort);
			comfortForm.set("value", "2.5");
			assertEquals(1, comfortForm.errors("value").size());
			comfortForm.set("value", "2");
			assertEquals(List.of(), comfortForm.errors("value"));

			// A default that is not one of the choices: refused at the default.
			RuleForm parameterForm = new RuleForm(parameter);
			parameterForm.set("default", "3");
			assertRefused(parameterForm, "default", "choices");
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testImportFromFile() throws Exception {
		TLObject viaFile = TestRuleAuthoring.createProject();
		TLObject viaImport = TestRuleAuthoring.createProject();
		try {
			byte[] content = Files.readAllBytes(EXAMPLE.toPath());
			String result;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				result = RuleFunctions.importImage(viaFile,
					BinaryDataFactory.createBinaryData(content, "application/json", EXAMPLE.getName()));
				tx.commit();
			}
			assertTrue(result, result.startsWith("2 rules, 2 schedule entries and 2 datapoints imported."));

			Map<?, ?> image = (Map<?, ?>) Json.parse(new String(content, StandardCharsets.UTF_8), 100);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				RuleImport importer = new RuleImport();
				importer.importRules(viaImport, (List<?>) image.get("rules"));
				importer.importSchedules(viaImport, (List<?>) image.get("schedules"));
				tx.commit();
			}
			assertEquals(Json.write(new RuleExport().rules(viaImport)), Json.write(new RuleExport().rules(viaFile)));
			assertEquals(Json.write(new RuleExport().schedules(viaImport)),
				Json.write(new RuleExport().schedules(viaFile)));

			// A file that is no image changes nothing.
			for (String text : List.of("{\"rules\": ", "{\"image\": {}}")) {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					RuleFunctions.importImage(viaFile,
						BinaryDataFactory.createBinaryData(text.getBytes(StandardCharsets.UTF_8), "application/json", "x.json"));
					fail("Refused: " + text);
				} catch (TopLogicException ex) {
					// Expected.
				}
			}
			assertEquals(2, ((java.util.Collection<?>) viaFile.tValueByName("rules")).size());
		} finally {
			SharedData.deleteProjects(List.of(viaFile, viaImport));
		}
	}

	private static TLClassifier parameterType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:RuleParameterType")).getClassifier(name);
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleEditorFollowUps.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
