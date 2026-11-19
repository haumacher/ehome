/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.Deployment;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.model.runtime.WriteAction;
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
			Project created = create("ehome.project:Project");
			project = created;
			created.setName("Unnamed datapoints");
			created.setExtId("P-TEST-114");
			ApplicationDatapoint level = add(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			level.setIdentifier("level");
			level.setLabel(null);
			level.setType(valueType("unsigned"));
			ApplicationDatapoint out = add(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			out.setIdentifier("out");
			out.setLabel(null);
			out.setType(valueType("boolean"));
			out.setWritable(true);
			// Named, written by the rule: no warning.
			ApplicationDatapoint target = add(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			target.setIdentifier("target");
			target.setLabel(ResKey.text("Ziel"));
			target.setType(valueType("boolean"));
			target.setWritable(true);

			Function sensor = create("ehome.runtime:Function");
			sensor.setNameKey(ResKey.builder().add(Locale.GERMAN, "Füllstand").build());
			sensor.setIdentifier("fill");
			sensor.setKind(FunctionProposal.classifier("sensor"));
			sensor.setValue(level);
			created.setFunctions(List.of(sensor));

			WeeklySchedule entry = add(project, "schedules", runtime("WeeklySchedule"));
			entry.setIdentifier("morning");
			entry.setLabel(ResKey.text("Morning"));
			entry.setTime("06:30");
			TLEnumeration weekday = (TLEnumeration) TLModelUtil.findType("ehome.runtime:Weekday");
			Set<TLClassifier> days = new LinkedHashSet<>();
			days.add(weekday.getClassifier("monday"));
			entry.setDays(days);
			entry.setOutput(out);

			Rule rule = add(project, "rules", runtime("Rule"));
			rule.setIdentifier("licht");
			rule.setNameKey(ResKey.text("Licht"));
			RuleParameter raw = add(rule, "parameters", runtime("RuleParameter"));
			raw.setIdentifier("raw");
			raw.setTitle(null);
			raw.setDefault("1");
			TLObject reaction = add(rule, "reactions", runtime("Reaction"));
			add(reaction, "triggers", runtime("StartTrigger"));
			WriteAction write = add(reaction, "actions", runtime("WriteAction"));
			write.setDatapoint(target);
			write.setValue("true");

			Controller own = create("ehome.runtime:Controller");
			controller = own;
			own.setName("Fake unnamed");
			own.setHost("127.0.0.1");
			own.setPort(fake.getPort());
			own.setToken(TOKEN);
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

			Deployment deployment = (Deployment) Deployer.deploy(project, NOW);
			String message = String.valueOf(deployment.getMessage());
			assertEquals(message, Boolean.TRUE, Boolean.valueOf(deployment.getAccepted()));
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

	/** A new part at the end of the reference of the owner ({@link RuleFunctions#create}), as its type. */
	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T add(TLObject owner, String reference, TLClass type) {
		return (T) RuleFunctions.create(owner, reference, type);
	}

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T create(String type) {
		return (T) ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	private static TLClassifier valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestUnnamedDatapoints.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
