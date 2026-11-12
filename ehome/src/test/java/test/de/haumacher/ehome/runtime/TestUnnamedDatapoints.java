/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageComparison;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.runtime.UnnamedDatapoints;

/**
 * A deployment warns for every datapoint without a name that the controller shows on the
 * inhabitant's pages (haumacher/ehome #114): a datapoint of a function or of a control page, the
 * output of a schedule entry, a rule parameter without title; each once, without refusing the image.
 */
@SuppressWarnings("javadoc")
public class TestUnnamedDatapoints extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	/**
	 * The issue's case: a function on a group address without name, a schedule output and a parameter
	 * without title give one warning each.
	 */
	public void testOneEach() {
		Map<String, Object> image = image(false);
		assertEquals(List.of("11/0/4", "11/0/5", "rule/licht/raw"), UnnamedDatapoints.ids(image));
		List<ResKey> warnings = UnnamedDatapoints.warnings(image);
		assertEquals(3, warnings.size());
		assertEquals("Datapoint 11/0/4 has no name; the controller shows its id.",
			Resources.getInstance(Locale.ENGLISH).getString(warnings.get(0)));
		assertEquals("Datenpunkt 11/0/4 hat keinen Namen; der Controller zeigt seine ID.",
			Resources.getInstance(Locale.GERMAN).getString(warnings.get(0)));
	}

	/** With the names, no warning. */
	public void testNamed() {
		assertEquals(List.of(), UnnamedDatapoints.ids(image(true)));
	}

	/**
	 * A datapoint used in several places (a function, a page, a schedule) warns once; an unnamed
	 * datapoint the pages do not show warns not at all.
	 */
	public void testOnce() {
		Map<String, Object> image = new LinkedHashMap<>();
		image.put("datapoints", List.of(datapoint("11/0/4", null), datapoint("11/0/9", null)));
		image.put("functions", List.of(Map.of("id", "licht", "label", "Licht", "kind", "switch",
			"datapoints", Map.of("switch", "11/0/4", "state", "11/0/4"))));
		image.put("ui", Map.of("pages", List.of(page(Map.of("datapoint", "11/0/4")))));
		image.put("schedules", List.of(Map.of("id", "morning", "label", "Morgens", "kind", "weekly", "output", "11/0/4")));
		assertEquals(List.of("11/0/4"), UnnamedDatapoints.ids(image));
	}

	/**
	 * On a page, an element, series or reference line with a label of its own shows that label: its
	 * datapoint needs no name there. Without one, it shows the datapoint's name. The datapoints of a
	 * rule (its enable switch, its parameters) and the time of a schedule entry are named by the rule,
	 * the parameter and the entry.
	 */
	public void testPages() {
		Map<String, Object> image = new LinkedHashMap<>();
		image.put("datapoints", List.of(datapoint("1/0/1", null), datapoint("1/0/2", null), datapoint("1/0/3", null),
			datapoint("1/0/4", null), datapoint("1/0/5", null)));
		Map<String, Object> chart = Map.of("series", List.of(Map.of("datapoint", "1/0/3"),
			Map.of("datapoint", "1/0/4", "label", Map.of("de", "Außen"))),
			"references", List.of(Map.of("datapoint", "1/0/5", "label", "Grenze"), Map.of("value", 3.0)));
		image.put("rules", List.of(Map.of("id", "licht", "name", "", "parameters", List.of(Map.of("id", "max", "label",
			Map.of("de", "max", "en", "Maximum"), "type", "real")))));
		image.put("schedules", List.of(Map.of("id", "morning", "label", "Morgens", "kind", "weekly")));
		image.put("ui", Map.of("pages", List.of(page(Map.of("datapoint", "1/0/1"), Map.of("datapoint", "1/0/2", "label",
			"Licht"), Map.of("chart", chart), Map.of("datapoint", "rule/licht/enabled"),
			Map.of("datapoint", "schedule/morning/time"), Map.of("datapoint", "knx.time"),
			Map.of("datapoint", "rule/licht/max")))));
		// The rule without a name shows the id of its enable switch; the controller's own datapoint is
		// named by the controller.
		assertEquals(List.of("1/0/1", "1/0/3", "rule/licht/enabled"), UnnamedDatapoints.ids(image));
	}

	/**
	 * The image of a project: a function on an application datapoint without label, a schedule output
	 * and a rule parameter without title; the deployment confirmation names them and the accepted
	 * deployment warns, without refusing.
	 */
	public void testDeployment() throws Exception {
		FakeController fake = new FakeController(TOKEN, 262144);
		TLObject project;
		TLObject controller;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "Unnamed datapoints");
			project.tUpdateByName("extId", "P-TEST-114");
			TLObject level = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			level.tUpdateByName("identifier", "level");
			level.tUpdateByName("label", null);
			level.tUpdateByName("type", valueType("unsigned"));
			TLObject out = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			out.tUpdateByName("identifier", "out");
			out.tUpdateByName("label", null);
			out.tUpdateByName("type", valueType("boolean"));
			out.tUpdateByName("writable", Boolean.TRUE);
			// Named, written by the rule: no warning.
			TLObject target = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			target.tUpdateByName("identifier", "target");
			target.tUpdateByName("label", ResKey.text("Ziel"));
			target.tUpdateByName("type", valueType("boolean"));
			target.tUpdateByName("writable", Boolean.TRUE);

			TLObject sensor = create("ehome.runtime:Function");
			sensor.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Füllstand").build());
			sensor.tUpdateByName("identifier", "fill");
			sensor.tUpdateByName("kind", FunctionProposal.classifier("sensor"));
			sensor.tUpdateByName("value", level);
			project.tUpdateByName("functions", List.of(sensor));

			TLObject entry = RuleFunctions.create(project, "schedules", runtime("WeeklySchedule"));
			entry.tUpdateByName("identifier", "morning");
			entry.tUpdateByName("label", ResKey.text("Morning"));
			entry.tUpdateByName("time", "06:30");
			TLEnumeration weekday = (TLEnumeration) TLModelUtil.findType("ehome.runtime:Weekday");
			List<TLClassifier> days = new ArrayList<>();
			days.add(weekday.getClassifier("monday"));
			entry.tUpdateByName("days", days);
			entry.tUpdateByName("output", out);

			TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
			rule.tUpdateByName("identifier", "licht");
			rule.tUpdateByName("name", ResKey.text("Licht"));
			TLObject raw = RuleFunctions.create(rule, "parameters", runtime("RuleParameter"));
			raw.tUpdateByName("identifier", "raw");
			raw.tUpdateByName("title", null);
			raw.tUpdateByName("default", "1");
			TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
			RuleFunctions.create(reaction, "triggers", runtime("StartTrigger"));
			TLObject write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			write.tUpdateByName("datapoint", target);
			write.tUpdateByName("value", "true");

			controller = create("ehome.runtime:Controller");
			controller.tUpdateByName("name", "Fake unnamed");
			controller.tUpdateByName("host", "127.0.0.1");
			controller.tUpdateByName("port", Integer.valueOf(fake.getPort()));
			controller.tUpdateByName("token", TOKEN);
			ControllerFixture.own(project, controller);
			tx.commit();
		}
		try {
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			assertEquals(List.of("level", "out", "rule/licht/raw"), UnnamedDatapoints.ids(image.getJson()));
			// The parameter without title is labeled with its id, which the controller requires.
			Map<?, ?> rule = (Map<?, ?>) ((List<?>) image.getJson().get("rules")).get(0);
			assertEquals("raw", ((Map<?, ?>) ((List<?>) rule.get("parameters")).get(0)).get("label"));
			List<String> texts = new ArrayList<>();
			for (ResKey warning : UnnamedDatapoints.warnings(image.getJson())) {
				texts.add(Resources.getInstance().getString(warning));
			}

			String confirmation = ImageComparison.confirmation(project, NOW);
			assertTrue(confirmation, confirmation.contains("\n\n" + String.join("\n", texts)));

			TLObject deployment = Deployer.deploy(project, NOW);
			String message = String.valueOf(deployment.tValueByName("message"));
			assertEquals(message, Boolean.TRUE, deployment.tValueByName("accepted"));
			assertTrue(message, message.contains(" " + String.join(" ", texts)));
			assertTrue(message, message.contains(Resources.getInstance()
				.getString(I18NConstants.DEPLOY_UNNAMED_DATAPOINT__ID.fill("rule/licht/raw"))));
		} finally {
			fake.stop();
			SharedData.deleteProjects(List.of(project));
			SharedData.deleteProjects(List.of(controller));
		}
	}

	// ---- helpers ------------------------------------------------------------------------------

	/**
	 * An image with a function on <code>11/0/4</code>, a schedule entry writing <code>11/0/5</code> and
	 * a rule with the parameters <code>raw</code> and <code>max</code>, named or not.
	 */
	private static Map<String, Object> image(boolean named) {
		Map<String, Object> image = new LinkedHashMap<>();
		image.put("datapoints", List.of(datapoint("11/0/4", named ? "Licht Flur" : null),
			datapoint("11/0/5", named ? Map.of("de", "Flur", "en", "Hall") : null), datapoint("11/0/6", "Status Flur"),
			datapoint("night", null)));
		image.put("functions", List.of(Map.of("id", "licht", "label", "Licht", "kind", "switch",
			"datapoints", Map.of("switch", "11/0/4", "state", "11/0/6"))));
		image.put("schedules", List.of(Map.of("id", "morning", "label", "Morgens", "kind", "weekly", "output", "11/0/5")));
		image.put("rules", List.of(Map.of("id", "licht", "name", "Licht", "parameters", List.of(
			// The image labels a parameter without title with its id.
			Map.of("id", "raw", "label", named ? "Rohwert" : "raw", "type", "real"),
			Map.of("id", "max", "label", "Max. Helligkeit", "type", "real")))));
		return image;
	}

	private static Map<String, Object> datapoint(String id, Object label) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		if (label != null) {
			result.put("label", label);
		}
		result.put("type", "boolean");
		return result;
	}

	@SafeVarargs
	private static Map<String, Object> page(Map<String, Object>... elements) {
		return Map.of("id", "p", "label", "Seite", "sections", List.of(Map.of("elements", List.of(elements))));
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	private static Object valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestUnnamedDatapoints.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
