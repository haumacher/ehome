/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.rules.TestRuleCorpus;
import test.de.haumacher.ehome.rules.TestRuleText;

import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleExport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleImport;

/**
 * The rules of the HomeControl conformance corpus (<code>src/test/fixtures/homecontrol/rules/</code>)
 * brought into a project's model (haumacher/ehome #57, {@link RuleImport}: expressions as canonical
 * text) and exported again ({@link RuleExport}): every rule of every case, also those of the image
 * swaps of a case's timeline, gives back the JSON it came from, members at their default aside. The
 * schedule entries of the example of <code>spec/schedules.md</code> make the same round trip. A probe
 * checks fields that need no datapoint in a form.
 *
 * <p>
 * An empty project serves; no ETS import.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRuleAuthoring extends BasicTestCase {

	public void testCorpusRoundTrip() throws Exception {
		List<String> failures = new ArrayList<>();
		List<String> refused = new ArrayList<>();
		int rules = 0;
		File[] files = TestRuleCorpus.CORPUS.listFiles((dir, name) -> name.endsWith(".json"));
		Arrays.sort(files);
		for (File file : files) {
			Map<?, ?> json = (Map<?, ?>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), 100);
			List<Map<?, ?>> images = new ArrayList<>();
			images.add(json);
			if (json.get("timeline") instanceof List<?> timeline) {
				for (Object step : timeline) {
					if (((Map<?, ?>) step).get("image") instanceof Map<?, ?> image) {
						images.add(image);
					}
				}
			}
			for (int i = 0; i < images.size(); i++) {
				Map<?, ?> image = images.get(i);
				List<?> original = (List<?>) image.get("rules");
				rules += original.size();
				// The rules name the datapoints and schedule entries of the case (haumacher/ehome #90): a
				// project of its own has them.
				TLObject project = createProject();
				try {
					// An image of the timeline swaps the rules of the case, its datapoints stay.
					createDatapoints(project, image.containsKey("datapoints") ? image.get("datapoints") : json.get("datapoints"));
					List<Object> exported;
					try {
						exported = roundTrip(project, original,
							image.containsKey("schedules") ? image.get("schedules") : json.get("schedules"));
					} catch (TopLogicException ex) {
						// A case of the loader's refusal of a name the image does not have: the import
						// refuses it already (haumacher/ehome #90).
						refused.add(file.getName() + " image " + i);
						continue;
					}
					Object expected = normalize(original);
					Object actual = normalize(exported);
					if (!TestRuleText.same(expected, actual)) {
						failures.add(file.getName() + " image " + i + ":\nexpected " + Json.write(expected) + "but was "
							+ Json.write(actual));
					}
				} finally {
					SharedData.deleteProjects(List.of(project));
				}
			}
		}
		assertEquals(String.join("\n", failures), List.of(), failures);
		// An unknown timer, an output that is no application datapoint: what a form cannot choose.
		assertEquals(List.of("33-load-refused-unknown-timer.json image 0", "39-load-refused-schedule-output.json image 0"),
			refused);
		assertTrue(String.valueOf(rules), rules >= 40);
	}

	/**
	 * Creates the datapoints of a case of the corpus (its member <code>datapoints</code>, each with a
	 * <code>name</code> and a <code>type</code>) in the project: a group address for a name in three
	 * levels, an application datapoint (writable) otherwise.
	 */
	static void createDatapoints(TLObject project, Object datapoints) {
		if (!(datapoints instanceof List<?> list)) {
			return;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			List<Object> addresses = new ArrayList<>((java.util.Collection<?>) project.tValueByName("groupAddresses"));
			for (Object item : list) {
				Map<?, ?> datapoint = (Map<?, ?>) item;
				String name = String.valueOf(datapoint.get("name") != null ? datapoint.get("name") : datapoint.get("id"));
				if (name.matches("\\d+/\\d+/\\d+")) {
					TLObject address = ModelService.getInstance().getFactory()
						.createObject((TLClass) TLModelUtil.findType("ehome.logical:GroupAddress"));
					address.tUpdateByName("name", name);
					address.tUpdateByName("address", GA.parse(name, GroupAddressStyle.ThreeLevel));
					addresses.add(address);
				} else if (!name.startsWith("rule/") && !name.startsWith("schedule/")) {
					TLObject created = RuleFunctions.create(project, "applicationDatapoints", type("ApplicationDatapoint"));
					created.tUpdateByName("identifier", name);
					if (datapoint.get("type") != null) {
						created.tUpdateByName("type", ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType"))
							.getClassifier(String.valueOf(datapoint.get("type"))));
					}
					created.tUpdateByName("writable", Boolean.TRUE);
				}
			}
			project.tUpdateByName("groupAddresses", addresses);
			tx.commit();
		}
	}

	public void testSchedulesRoundTrip() throws Exception {
		TLObject project = createProject();
		try {
			List<?> original = (List<?>) Json.parse("""
				[
				  {"id": "workdayMorning", "label": "Workday morning", "kind": "weekly",
				   "days": [1, 2, 3, 4, 5], "time": {"hour": 6, "minute": 30, "second": 0}, "missed": "catchUp",
				   "output": "schedule.morning"},
				  {"id": "christmasEve", "label": "Christmas Eve", "kind": "yearly",
				   "month": 12, "day": 24, "time": {"hour": 17, "minute": 0, "second": 0}},
				  {"id": "trash", "label": "Bin collection", "kind": "dates",
				   "dates": [{"year": 2027, "month": 1, "day": 8}, {"year": 2027, "month": 1, "day": 22}],
				   "time": {"hour": 6, "minute": 15, "second": 0}},
				  {"id": "blindsEvening", "label": "Blinds down at dusk", "kind": "astro",
				   "sun": "sunset", "offset": -1800000,
				   "notBefore": {"hour": 17, "minute": 0, "second": 0}, "notAfter": {"hour": 22, "minute": 0, "second": 0}},
				  {"id": "circulation", "label": "Hot water circulation", "kind": "interval",
				   "every": 900000,
				   "from": {"hour": 6, "minute": 0, "second": 0}, "until": {"hour": 22, "minute": 0, "second": 0},
				   "enabledAdjustable": false},
				  {"id": "partyEnd", "label": "Party ends", "kind": "once", "enabled": false,
				   "at": {"year": 2026, "month": 12, "day": 31, "hour": 23, "minute": 59, "second": 30}}
				]""", 100);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				// The output the first entry names (haumacher/ehome #90).
				RuleFunctions.create(project, "applicationDatapoints", type("ApplicationDatapoint"))
					.tUpdateByName("identifier", "schedule.morning");
				new RuleImport().importSchedules(project, original);
				tx.commit();
			}
			List<Object> exported = new RuleExport().schedules(project);
			assertTrue(Json.write(exported), TestRuleText.same(original, exported));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * Probe: the checks of fields that need no datapoint, in a form: a rule id used twice, a
	 * transition with else actions, a timer duration that is no duration, a parameter default
	 * outside its range and one that is no literal, a time function in a match condition, the state
	 * of another rule (known and unknown).
	 */
	/**
	 * The editor's text fields offer the functions of the rule language by the start of their name
	 * (homecontrol #73: <code>sunAzimuth()</code>, <code>sunElevation()</code>), not inside a string,
	 * a parameter or the unit of a number.
	 */
	public void testFunctionSuggestions() {
		List<Object> sun = RuleFunctions.suggestions(null, "@x > 0 and sunE", 15);
		assertEquals(1, sun.size());
		Map<?, ?> elevation = (Map<?, ?>) sun.get(0);
		assertEquals("sunElevation()", elevation.get("text"));
		assertEquals(Integer.valueOf(11), elevation.get("from"));
		assertEquals(Integer.valueOf(15), elevation.get("to"));
		assertTrue(String.valueOf(elevation.get("label")), String.valueOf(elevation.get("label")).startsWith("sunElevation() "));

		Set<Object> texts = new TreeSet<>();
		for (Object entry : RuleFunctions.suggestions(null, "between(su", 10)) {
			texts.add(((Map<?, ?>) entry).get("text"));
		}
		assertEquals(new TreeSet<>(List.of("sunAzimuth()", "sunElevation()", "sunrise()", "sunset()")), texts);
		assertEquals("round(", ((Map<?, ?>) RuleFunctions.suggestions(null, "roun", 4).get(0)).get("text"));

		assertEquals(List.of(), RuleFunctions.suggestions(null, "\"sunE", 5));
		assertEquals(List.of(), RuleFunctions.suggestions(null, "$sunE", 5));
		assertEquals(List.of(), RuleFunctions.suggestions(null, "30mi", 4));
		assertEquals(List.of(), RuleFunctions.suggestions(null, "s", 1));
	}

	public void testChecks() throws Exception {
		TLObject project = createProject();
		try {
			TLObject first;
			TLObject second;
			TLObject reaction;
			TLObject target;
			TLObject timer;
			TLObject parameter;
			TLObject match;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				first = RuleFunctions.create(project, "rules", type("Rule"));
				RuleFunctions.create(first, "reactions", type("Reaction"));
				RuleFunctions.create(RuleFunctions.objects(first, "reactions").get(0), "triggers", type("StartTrigger"));
				second = RuleFunctions.create(project, "rules", type("Rule"));
				RuleFunctions.create(second, "states", type("RuleState"));
				target = RuleFunctions.create(second, "states", type("RuleState"));
				second.tUpdateByName("initialState", RuleFunctions.objects(second, "states").get(0));
				timer = RuleFunctions.create(second, "timers", type("RuleTimer"));
				parameter = RuleFunctions.create(second, "parameters", type("RuleParameter"));
				parameter.tUpdateByName("min", "0.0");
				parameter.tUpdateByName("max", "10.0");
				reaction = RuleFunctions.create(second, "reactions", type("Reaction"));
				match = RuleFunctions.create(reaction, "triggers", type("MatchTrigger"));
				match.tUpdateByName("condition", "$p1 > 5");
				RuleFunctions.create(reaction, "elseActions", type("LogAction")).tUpdateByName("message", "else");
				tx.commit();
			}
			assertEquals("rule-2", second.tValueByName("identifier"));

			RuleForm rule = new RuleForm(second);
			assertEquals(List.of(), rule.warnings("identifier"));
			rule.set("identifier", "rule-1");
			assertWarning(rule, "identifier", "is used twice");

			RuleForm transition = new RuleForm(reaction);
			assertEquals(List.of(), transition.warnings("to"));
			transition.set("to", target);
			assertWarning(transition, "to", "\"elseActions\" cannot be given together with \"to\"");
			transition.set("condition", "state(\"rule-1\") == \"s1\"");
			assertWarning(transition, "condition", "The rule \"rule-1\" has no states.");
			transition.set("condition", "state(\"rule-2\") == \"s1\"");
			assertEquals(List.of(), transition.warnings("condition"));

			RuleForm duration = new RuleForm(timer);
			duration.set("duration", "5");
			assertWarning(duration, "duration", "must be a duration, not of the type integer");
			duration.set("duration", "$p1 * 1min");
			assertEquals(List.of(), duration.warnings("duration"));

			RuleForm value = new RuleForm(parameter);
			value.set("default", "25.0");
			assertWarning(value, "default", "The default lies outside");
			value.set("default", "abc");
			assertEquals(1, value.errors("default").size());
			assertEquals(List.of(), value.warnings("default"));
			value.set("default", "5");
			assertEquals(List.of(), value.errors("default"));
			assertEquals(List.of(), value.warnings("default"));

			RuleForm condition = new RuleForm(match);
			assertEquals(List.of(), condition.warnings("condition"));
			condition.set("condition", "time() > 20:00");
			assertWarning(condition, "condition", "time() cannot be used in a match condition");
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static void assertWarning(RuleForm form, String attribute, String part) {
		List<String> warnings = form.warnings(attribute);
		assertEquals(warnings.toString(), 1, warnings.size());
		assertTrue(warnings.get(0), warnings.get(0).contains(part));
	}

	private static TLClass type(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	private static List<Object> roundTrip(TLObject project, List<?> rules, Object schedules) {
		Map<String, Object> image = new LinkedHashMap<>();
		image.put("rules", rules);
		if (schedules instanceof List<?> list) {
			// A case lists its schedule entries, or only their ids (a schedule the rules name).
			List<Object> entries = new ArrayList<>();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (Object item : list) {
					if (item instanceof String id) {
						RuleFunctions.create(project, "schedules", type("WeeklySchedule")).tUpdateByName("identifier", id);
					} else {
						entries.add(item);
					}
				}
				tx.commit();
			}
			if (!entries.isEmpty()) {
				image.put("schedules", entries);
			}
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			new RuleImport().importImage(project, Json.write(image));
			tx.commit();
		}
		return new RuleExport().rules(project);
	}

	/**
	 * The JSON with the members at their default left out (the export leaves them out, the corpus
	 * sometimes writes them), the start reasons as a set, the expressions in their normalized form
	 * (spec/rules.md 4.8, {@link RuleText#normalize(Object)}: the round trip holds on normalized trees)
	 * and the labels as E-Home keeps them: the text a controller shows in each language of E-Home, a
	 * plain string where they are all the same.
	 */
	@SuppressWarnings("unchecked")
	static Object normalize(Object json) {
		if (json instanceof List<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object element : list) {
				result.add(normalize(element));
			}
			return result;
		}
		if (!(json instanceof Map<?, ?> map)) {
			return json;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String key = (String) entry.getKey();
			Object value = entry.getValue();
			if (EXPRESSIONS.contains(key) && value instanceof Map<?, ?> node && node.containsKey("kind")) {
				result.put(key, RuleText.normalize(node));
				continue;
			}
			if (key.equals("message") && value instanceof List<?> parts) {
				List<Object> message = new ArrayList<>();
				for (Object part : parts) {
					message.add(RuleText.normalize(part));
				}
				result.put(key, message);
				continue;
			}
			if ((key.equals("name") || key.equals("label")) && value instanceof Map<?, ?> languages) {
				result.put(key, keptLabel((Map<String, String>) languages));
				continue;
			}
			boolean dropped = switch (key) {
				case "enabled", "enabledAdjustable", "adjustable" -> Boolean.TRUE.equals(value);
				case "onlyOnChange" -> Boolean.FALSE.equals(value);
				case "on" -> "change".equals(value);
				case "origin" -> "any".equals(value);
				case "parameters", "timers", "states", "elseActions", "from", "entry", "exit" ->
					value instanceof List<?> l && l.isEmpty();
				case "reasons" -> value instanceof List<?> l
					&& new TreeSet<>((List<String>) l).equals(new TreeSet<>(List.of("boot", "image", "enable")));
				default -> false;
			};
			if (dropped) {
				continue;
			}
			if (key.equals("reasons") && value instanceof List<?> l) {
				value = new ArrayList<>(new TreeSet<>((List<String>) l));
			}
			result.put(key, normalize(value));
		}
		return result;
	}

	private static final Set<String> EXPRESSIONS = Set.of("condition", "duration", "value", "after");

	/** A label map as E-Home keeps it: the text in each of its languages. */
	private static Object keptLabel(Map<String, String> languages) {
		Map<String, String> texts = new LinkedHashMap<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			texts.put(locale.getLanguage(), RuleLoader.resolveLabel(languages, locale.getLanguage()));
		}
		return new HashSet<>(texts.values()).size() == 1 ? texts.values().iterator().next() : texts;
	}

	static TLObject createProject() {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", "Rule authoring test");
			tx.commit();
			return project;
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleAuthoring.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
