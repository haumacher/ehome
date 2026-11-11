/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The control pages of a project (haumacher/ehome #72 with its amendment "charts"): the member
 * <code>ui.pages</code> of the runtime image (schema 1.1), its checks, the editing of the pages and
 * what deleting a referenced object does to them.
 *
 * <p>
 * The project is built without an ETS import: group addresses of two datapoint main types created for
 * the test (9, a real value; 1, a truth value), a building with the rooms Wohnzimmer (with the
 * sub-part Leseecke) and Küche, four functions, an application datapoint and the rule
 * <code>heat</code> with the parameter <code>setpoint</code>. Only {@link #testReimport()} imports a
 * sample (<code>samples/Test.knxproj</code>, one device).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestControlPages extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

	/** The chart of the amendment "charts" of haumacher/ehome #72, verbatim. */
	static final String CHART = """
		{"chart": {"label": {"de": "Temperaturen"}, "width": "full", "height": 4,
		  "range": "24h", "presets": ["24h", "7d", "30d", "1y"],
		  "axes": {"left": {"min": null, "max": null}, "right": {"min": 0, "max": 100}},
		  "series": [{"datapoint": "1/3/1", "label": {"de": "Wohnzimmer"}, "axis": "left", "consolidation": "mean"},
					 {"datapoint": "1/3/5", "axis": "right", "consolidation": "mean"},
					 {"datapoint": "1/1/7", "label": {"de": "Heizung"}}],
		  "references": [{"value": 21, "axis": "left", "label": {"de": "Soll"}},
						 {"datapoint": "rule/heat/setpoint", "axis": "left"}]}}
		""";

	/**
	 * The two pages of the acceptance test: a section with a room (expanded in the order of the
	 * project's functions, the sub-part's function after the room's own) and a function of another
	 * room; a section with a group address shown read-only, an application datapoint, a rule parameter
	 * and a rule's enable switch; on the second page the chart of the amendment and the function of the
	 * first page once more.
	 */
	static final String PAGES = """
		{"pages": [
		  {"id": "evening", "label": {"de": "Abend", "en": "Evening"}, "icon": "moon",
		   "sections": [
			 {"label": {"de": "Wohnzimmer", "en": "Living room"}, "elements": [
			   {"function": "living-lamp"},
			   {"function": "living-light"},
			   {"function": "reading-light"},
			   {"function": "kitchen-light", "label": {"de": "Küche", "en": "Kitchen"}}]},
			 {"elements": [
			   {"datapoint": "1/0/1", "readOnly": true},
			   {"datapoint": "holiday"},
			   {"datapoint": "rule/heat/setpoint", "label": {"de": "Solltemperatur", "en": "Setpoint"}},
			   {"datapoint": "rule/heat/enabled", "label": {"de": "Heizregel"}}]}]},
		  {"id": "klima", "label": {"de": "Klima", "en": "Klima"},
		   "sections": [
			 {"label": {"de": "Temperaturen"}, "elements": [
			   %s,
			   {"function": "kitchen-light"}]}]}]}
		""".formatted(CHART);

	private TLObject _project;

	private final List<TLObject> _types = new ArrayList<>();

	private TLObject _temperature;

	private TLObject _humidity;

	private TLObject _heating;

	private TLObject _light;

	private TLObject _untyped;

	private TLObject _livingRoom;

	private TLObject _kitchenLight;

	private TLObject _holiday;

	private TLObject _rule;

	private TLObject _setpoint;

	private TLObject _evening;

	private TLObject _climate;

	private TLObject _chart;

	private TLObject _roomElement;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject real = mainType(9);
			TLObject bool = mainType(1);
			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Control pages");
			_project.tUpdateByName("extId", "P-TEST-72");
			_temperature = address("Temperatur Wohnzimmer", 1, 3, 1, real, true);
			_humidity = address("Feuchte Wohnzimmer", 1, 3, 5, real, true);
			_heating = address("Heizung", 1, 1, 7, bool, true);
			_heating.tUpdateByName("runtimeWritable", Boolean.TRUE);
			_light = address("Licht Wohnzimmer", 1, 0, 1, bool, false);
			_light.tUpdateByName("runtimeWritable", Boolean.TRUE);
			_untyped = address("Ohne Typ", 1, 0, 9, null, false);
			_project.tUpdateByName("groupAddresses", List.of(_temperature, _humidity, _heating, _light, _untyped));

			TLObject building = part("ehome.building:Building", "Haus", "P-TEST-72-B");
			_livingRoom = part("ehome.building:Room", "Wohnzimmer", "P-TEST-72-R1");
			TLObject corner = part("ehome.building:BuildingPart", "Leseecke", "P-TEST-72-R1a");
			TLObject kitchen = part("ehome.building:Room", "Küche", "P-TEST-72-R2");
			_livingRoom.tUpdateByName("parts", List.of(corner));
			building.tUpdateByName("parts", List.of(_livingRoom, kitchen));
			_project.tUpdateByName("buildings", List.of(building));

			// The project's order: neither by name nor by room.
			TLObject reading = function("Leselicht", "reading-light", corner);
			_kitchenLight = function("Küchenlicht", "kitchen-light", kitchen);
			TLObject lamp = function("Stehlampe", "living-lamp", _livingRoom);
			TLObject ceiling = function("Decke", "living-light", _livingRoom);
			_project.tUpdateByName("functions", List.of(reading, _kitchenLight, lamp, ceiling));

			_holiday = RuleFunctions.create(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			_holiday.tUpdateByName("identifier", "holiday");
			_holiday.tUpdateByName("writable", Boolean.TRUE);

			_rule = RuleFunctions.create(_project, "rules", runtime("Rule"));
			_rule.tUpdateByName("identifier", "heat");
			_setpoint = RuleFunctions.create(_rule, "parameters", runtime("RuleParameter"));
			_setpoint.tUpdateByName("identifier", "setpoint");
			_setpoint.tUpdateByName("default", "21.0");
			TLObject reaction = RuleFunctions.create(_rule, "reactions", runtime("Reaction"));
			RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger")).tUpdateByName("datapoint", _temperature);
			reaction.tUpdateByName("condition", "@\"1/3/1\" < $setpoint");
			TLObject write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			write.tUpdateByName("datapoint", _heating);
			write.tUpdateByName("value", "true");
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject type : _types) {
				type.tDelete();
			}
			tx.commit();
		}
		_types.clear();
		super.tearDown();
	}

	/**
	 * Builds the two pages of {@link #PAGES} through {@link Pages#insert}, as the view's commands do.
	 */
	private void buildPages() {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_evening = Pages.insert(_project, null, Pages.PAGE, null);
			_evening.tUpdateByName("name", text("Abend", "Evening"));
			_evening.tUpdateByName("identifier", "evening");
			_evening.tUpdateByName("icon", "moon");
			TLObject living = Pages.insert(_project, _evening, Pages.SECTION, null);
			living.tUpdateByName("title", text("Wohnzimmer", "Living room"));
			_roomElement = Pages.insert(_project, living, Pages.ROOM, List.of(_livingRoom));
			Pages.insert(_project, _roomElement, Pages.FUNCTION, List.of(_kitchenLight))
				.tUpdateByName("label", text("Küche", "Kitchen"));
			TLObject values = Pages.insert(_project, living, Pages.SECTION, null);
			// One element per target, in their order; the last one is the result.
			TLObject setpoint = Pages.insert(_project, values, Pages.DATAPOINT, List.of(_light, _holiday, _setpoint));
			setpoint.tUpdateByName("label", text("Solltemperatur", "Setpoint"));
			Parts.get(values, "elements", 0).tUpdateByName("readOnly", Boolean.TRUE);
			Pages.insert(_project, setpoint, Pages.RULE, List.of(_rule)).tUpdateByName("label", de("Heizregel"));

			_climate = Pages.insert(_project, _evening, Pages.PAGE, null);
			_climate.tUpdateByName("name", text("Klima", "Klima"));
			TLObject temperatures = Pages.insert(_project, _climate, Pages.SECTION, null);
			temperatures.tUpdateByName("title", de("Temperaturen"));
			_chart = Pages.insert(_project, temperatures, Pages.CHART, null);
			_chart.tUpdateByName("label", de("Temperaturen"));
			_chart.tUpdateByName("width", classifier("ChartWidth", "full"));
			_chart.tUpdateByName("height", classifier("ChartHeight", "four"));
			_chart.tUpdateByName("presets", List.of(classifier("ChartRange", "year"), classifier("ChartRange", "day"),
				classifier("ChartRange", "month"), classifier("ChartRange", "week")));
			_chart.tUpdateByName("rightMin", Double.valueOf(0));
			_chart.tUpdateByName("rightMax", Double.valueOf(100));
			TLObject heatingSeries = Pages.insert(_project, _chart, Pages.SERIES, List.of(_temperature, _humidity, _heating));
			heatingSeries.tUpdateByName("label", de("Heizung"));
			TLObject living1 = Parts.get(_chart, "series", 0);
			living1.tUpdateByName("label", de("Wohnzimmer"));
			living1.tUpdateByName("axis", classifier("ChartAxis", "left"));
			living1.tUpdateByName("consolidation", classifier("ChartConsolidation", "mean"));
			TLObject humidity = Parts.get(_chart, "series", 1);
			humidity.tUpdateByName("axis", classifier("ChartAxis", "right"));
			humidity.tUpdateByName("consolidation", classifier("ChartConsolidation", "mean"));
			TLObject soll = Pages.insert(_project, _chart, Pages.REFERENCE, null);
			soll.tUpdateByName("value", Double.valueOf(21));
			soll.tUpdateByName("axis", classifier("ChartAxis", "left"));
			soll.tUpdateByName("label", de("Soll"));
			Pages.insert(_project, soll, Pages.REFERENCE_DATAPOINT, List.of(_setpoint))
				.tUpdateByName("axis", classifier("ChartAxis", "left"));
			// The same function on the second page.
			Pages.insert(_project, _chart, Pages.FUNCTION, List.of(_kitchenLight));
			tx.commit();
		}
	}

	public void testTwoPages() throws Exception {
		RuntimeImage before = RuntimeImage.build(_project, 1, NOW);
		assertFalse("A project without pages has no ui: the controller shows its overview.",
			before.getJson().containsKey("ui"));
		assertTrue(before.validate().toString(), before.validate().isValid());
		// The address without a datapoint type, as always.
		assertEquals(before.getNotes().toString(), 1, before.getNotes().size());

		buildPages();
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		assertEquals(before.getNotes(), image.getNotes());
		assertEquals("1.6", ((Map<?, ?>) image.getJson().get("image")).get("schemaVersion"));
		assertEquals(Json.write(Json.parse(PAGES, 100)), Json.write(image.getJson().get("ui")));

		// The chart of the amendment, exactly.
		Map<?, ?> ui = (Map<?, ?>) image.getJson().get("ui");
		Object chart = elements(ui, 1, 0).get(0);
		assertEquals(Json.write(Json.parse(CHART, 100)), Json.write(chart));

		// The paths of the image lead to the project's objects.
		assertSame(_evening, image.elementAt("ui.pages[0].label"));
		assertSame(_roomElement, image.elementAt("ui.pages[0].sections[0].elements[2].function"));
		assertSame(_chart, image.elementAt("ui.pages[1].sections[0].elements[0].chart.range"));
		TLObject heatingSeries = Parts.get(_chart, "series", 2);
		assertSame(heatingSeries, image.elementAt("ui.pages[1].sections[0].elements[0].chart.series[2].label"));

		// The seam of haumacher/ehome #73: what the charts ask of each datapoint.
		Map<TLObject, List<Pages.ChartUse>> uses = Pages.chartUses(_project);
		assertEquals(List.of(_temperature, _humidity, _heating), List.copyOf(uses.keySet()));
		Pages.ChartUse use = uses.get(_temperature).get(0);
		assertSame(_chart, use.chart());
		assertEquals(List.of(Long.valueOf(86400), Long.valueOf(7 * 86400), Long.valueOf(30 * 86400),
			Long.valueOf(365 * 86400)), use.ranges());
		assertEquals("full", use.width());
	}

	public void testSameFunctionOnTwoPages() throws Exception {
		buildPages();
		Map<?, ?> ui = (Map<?, ?>) RuntimeImage.build(_project, 1, NOW).getJson().get("ui");
		assertTrue(elements(ui, 0, 0).contains(Map.of("function", "kitchen-light", "label",
			Map.of("de", "Küche", "en", "Kitchen"))));
		assertTrue(elements(ui, 1, 0).contains(Map.of("function", "kitchen-light")));
	}

	public void testSeriesWithoutHistory() throws Exception {
		buildPages();
		TLObject series;
		TLObject parameterSeries;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			// A history set by hand without a raw ring: no history (a derived one would follow the chart, #73).
			_light.tUpdateByName("historyManual", Boolean.TRUE);
			series = Pages.insert(_project, _chart, Pages.SERIES, List.of(_light));
			parameterSeries = Pages.insert(_project, series, Pages.SERIES, List.of(_setpoint));
			tx.commit();
		}
		// At the field and in the tree.
		assertNotNull(Pages.seriesProblem(_light));
		assertNotNull(Pages.problem(series));
		assertNotNull("A rule parameter has no history.", Pages.problem(parameterSeries));
		assertNull(Pages.problem(Parts.get(_chart, "series", 0)));

		// In the image: refused with the element's path.
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertFalse(report.isValid());
		String path = "ui.pages[1].sections[0].elements[0].chart.series[3].datapoint";
		ImageReport.Refusal refusal = refusal(report, path);
		assertTrue(refusal.reason(), refusal.reason().contains("'1/0/1' keeps no history"));
		assertSame(series, image.elementAt(path));
		ImageReport.Refusal parameter = refusal(report, "ui.pages[1].sections[0].elements[0].chart.series[4].datapoint");
		assertTrue(parameter.reason(), parameter.reason().contains("'rule/heat/setpoint' keeps no history"));
		assertSame(parameterSeries, image.elementAt(parameter.path()));
	}

	/**
	 * What the controller refuses of a chart and the project allows (haumacher/ehome #77): presets
	 * without the chart's range are exported with it, a reference line of a truth value is a problem
	 * in the tree and refused by the image's check.
	 */
	public void testPresetsAndReferenceLines() throws Exception {
		buildPages();
		TLObject reference;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_chart.tUpdateByName("range", classifier("ChartRange", "hour"));
			reference = Pages.insert(_project, Parts.get(_chart, "references", 1), Pages.REFERENCE_DATAPOINT,
				List.of(_heating));
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		Map<?, ?> chart = (Map<?, ?>) ((Map<?, ?>) elements((Map<?, ?>) image.getJson().get("ui"), 1, 0).get(0)).get("chart");
		assertEquals("1h", chart.get("range"));
		assertEquals(List.of("1h", "24h", "7d", "30d", "1y"), chart.get("presets"));

		assertNotNull(Pages.problem(reference));
		assertNull(Pages.problem(Parts.get(_chart, "references", 1)));
		ImageReport report = image.validate();
		String path = "ui.pages[1].sections[0].elements[0].chart.references[2].datapoint";
		assertRefused(report, path, "a reference line needs a numeric datapoint; '1/1/7' is boolean");
		assertEquals(report.toString(), 1, report.getRefusals().size());
		assertSame(reference, image.elementAt(path));
	}

	public void testElementNotInImage() throws Exception {
		buildPages();
		List<String> before = RuntimeImage.build(_project, 1, NOW).getNotes();
		TLObject element;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			element = Pages.insert(_project, _evening, Pages.DATAPOINT, List.of(_untyped));
			tx.commit();
		}
		assertNotNull(Pages.problem(element));
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		// Left out with a note naming its place, not dropped silently.
		Map<?, ?> ui = (Map<?, ?>) image.getJson().get("ui");
		assertEquals(4, elements(ui, 0, 1).size());
		List<String> notes = image.getNotes().stream().filter(n -> !before.contains(n)).toList();
		assertEquals(image.getNotes().toString(), 1, notes.size());
		assertTrue(notes.get(0), notes.get(0).contains("\"Abend\"") || notes.get(0).contains("\"Evening\""));
		assertTrue(notes.get(0), notes.get(0).contains("1/0/9") && notes.get(0).contains("2")
			&& notes.get(0).contains("5"));
	}

	public void testDeleteReferencedObjects() throws Exception {
		List<String> before = RuntimeImage.build(_project, 1, NOW).getNotes();
		buildPages();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_kitchenLight.tDelete();
			tx.commit();
		}
		Map<?, ?> ui = (Map<?, ?>) RuntimeImage.build(_project, 1, NOW).getJson().get("ui");
		assertFalse(Json.write(ui).contains("kitchen-light"));
		assertEquals(3, elements(ui, 0, 0).size());
		assertEquals(1, elements(ui, 1, 0).size());

		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_livingRoom.tDelete();
			_humidity.tDelete();
			_rule.tDelete();
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		ui = (Map<?, ?>) image.getJson().get("ui");
		String text = Json.write(ui);
		assertFalse(text, text.contains("rule/heat"));
		assertFalse(text, text.contains("1/3/5"));
		assertEquals(List.of(), elements(ui, 0, 0));
		assertEquals(List.of(Map.of("datapoint", "1/0/1", "readOnly", Boolean.TRUE), Map.of("datapoint", "holiday")),
			elements(ui, 0, 1));
		Map<?, ?> chart = (Map<?, ?>) ((Map<?, ?>) elements(ui, 1, 0).get(0)).get("chart");
		assertEquals(2, ((List<?>) chart.get("series")).size());
		assertEquals(1, ((List<?>) chart.get("references")).size());
		assertEquals(before, image.getNotes());
	}

	public void testEditing() throws Exception {
		buildPages();
		TLObject living = Parts.get(_evening, "sections", 0);
		TLObject values = Parts.get(_evening, "sections", 1);
		TLObject temperatures = Parts.get(_climate, "sections", 0);
		// Nothing to add an element to without a section, a series without a chart.
		assertNotNull(Pages.insertVeto(_project, null, Pages.FUNCTION));
		assertNotNull(Pages.insertVeto(_project, values, Pages.SERIES));
		assertNull(Pages.insertVeto(_project, null, Pages.PAGE));
		// A page selected: its last section.
		assertNull(Pages.insertVeto(_project, _evening, Pages.FUNCTION));

		// Moving: elements between sections, a section into a section refused.
		TLObject holiday = Parts.get(values, "elements", 1);
		assertNotNull(Pages.moveVeto(List.of(values), living, null));
		assertNotNull(Pages.moveVeto(List.of(holiday), _chart, null));
		assertNull(Pages.moveVeto(List.of(holiday), temperatures, _chart));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Pages.move(List.of(holiday), temperatures, _chart);
			Pages.move(List.of(_climate), _project, _evening);
			tx.commit();
		}
		assertSame(_climate, Parts.get(_project, "pages", 0));
		assertSame(holiday, Parts.get(temperatures, "elements", 0));
		assertEquals(3, Parts.list(values, "elements").size());
		Map<?, ?> ui = (Map<?, ?>) RuntimeImage.build(_project, 1, NOW).getJson().get("ui");
		assertEquals(Map.of("datapoint", "holiday"), elements(ui, 0, 0).get(0));

		// The options of a page offer the rule parameters; those of a function's roles do not.
		assertTrue(Pages.options(_chart, Pages.DATAPOINT).contains(_setpoint));
		assertFalse(RuntimeFunctions.datapoints(_project).contains(_setpoint));
		assertEquals("rule/heat/setpoint", Pages.imageId(_setpoint));

		// A duplicate page id.
		assertNotNull(Pages.identifierProblem("evening", _climate));
		assertNull(Pages.identifierProblem("evening", _evening));
		assertNotNull(Pages.identifierProblem("a b", _climate));
	}

	public void testReimport() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> first = SharedData.newProjects(before);
		assertEquals(1, first.size());
		TLObject project = first.get(0);
		List<TLObject> second = List.of();
		try {
			TLObject address = SharedData.within("ehome.logical:GroupAddress", first).get(0);
			TLObject element;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject page = Pages.insert(project, null, Pages.PAGE, null);
				TLObject section = Pages.insert(project, page, Pages.SECTION, null);
				element = Pages.insert(project, section, Pages.DATAPOINT, List.of(address));
				element.tUpdateByName("label", de("Meine Adresse"));
				tx.commit();
			}
			List<TLObject> all = instances("ehome.project:Project");
			importSample("samples/Test.knxproj");
			second = SharedData.newProjects(all);
			assertEquals(1, second.size());
			// The pages of the first project are untouched, the new one has none.
			assertTrue(element.tValid());
			assertSame(address, element.tValueByName("datapoint"));
			assertEquals("Meine Adresse",
				Resources.getInstance(Locale.GERMAN).getString((ResKey) element.tValueByName("label")));
			assertEquals(1, Parts.list(project, "pages").size());
			assertEquals(List.of(), Parts.list(second.get(0), "pages"));
		} finally {
			List<TLObject> projects = new ArrayList<>(first);
			projects.addAll(second);
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * The checks of the controller for <code>ui.pages</code>, each refusal with its path; an image of
	 * schema 1.0 with an empty <code>ui</code> still loads.
	 */
	/**
	 * Review probe: page ids. Pages without an identifier get distinct ids derived from their names
	 * (also for a name with umlauts); two pages given the same identifier make the image invalid with
	 * the path of the second, so the controller never sees a duplicate page id.
	 */
	public void testPageIds() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (int n = 0; n < 2; n++) {
				TLObject page = Pages.insert(_project, null, Pages.PAGE, null);
				page.tUpdateByName("name", de("Küche"));
				TLObject section = Pages.insert(_project, page, Pages.SECTION, null);
				Pages.insert(_project, section, Pages.FUNCTION, List.of(_kitchenLight));
			}
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		List<?> pages = (List<?>) ((Map<?, ?>) image.getJson().get("ui")).get("pages");
		Object first = ((Map<?, ?>) pages.get(0)).get("id");
		Object second = ((Map<?, ?>) pages.get(1)).get("id");
		assertFalse(first + " twice", first.equals(second));
		assertTrue(String.valueOf(first), String.valueOf(first).matches("[a-z0-9][a-z0-9-]*"));

		// An explicit identifier equal to the other page's derived id: the derived one gives way.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Parts.get(_project, "pages", 1).tUpdateByName("identifier", String.valueOf(first));
			tx.commit();
		}
		pages = (List<?>) ((Map<?, ?>) RuntimeImage.build(_project, 1, NOW).getJson().get("ui")).get("pages");
		assertFalse(((Map<?, ?>) pages.get(0)).get("id").equals(((Map<?, ?>) pages.get(1)).get("id")));

		// Two pages given the same identifier: the model refuses the commit.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject page : Parts.list(_project, "pages")) {
				page.tUpdateByName("identifier", "kitchen");
			}
			tx.commit();
			fail("Two pages with the identifier kitchen must be refused.");
		} catch (TopLogicException ex) {
			// expected: the identifier is unique among the pages
		}
	}

	public void testValidator() {
		assertTrue(ImageValidator.validate(image("1.0", "{}")).isValid());
		String page = """
			{"pages": [{"id": "a", "sections": [{"elements": [%s]}]}, {"id": "a", "sections": []}]}
			""";
		ImageReport report = ImageValidator.validate(image("1.1", page.formatted("""
			{"function": "f", "datapoint": "1/0/1"},
			{"function": "nope"},
			{"datapoint": "nope"},
			{"chart": {"width": 5, "height": 3, "range": "2h", "presets": ["24h", "24h"],
			  "axes": {"left": {"min": 10, "max": 0}, "top": {}},
			  "series": [{"datapoint": "1/0/1", "consolidation": "consumption"},
						 {"datapoint": "1/0/2", "consolidation": "median", "axis": "middle"}],
			  "references": [{"value": 1, "datapoint": "1/0/1"}, {"datapoint": "nope"}]}}
			""")));
		assertRefused(report, "ui.pages[0].sections[0].elements[0]", "names exactly one of function, datapoint or chart; this one names several");
		assertRefused(report, "ui.pages[0].sections[0].elements[1].function", "unknown function 'nope'");
		assertRefused(report, "ui.pages[0].sections[0].elements[2].datapoint", "unknown datapoint 'nope'");
		String chart = "ui.pages[0].sections[0].elements[3].chart";
		assertRefused(report, chart + ".width", "width must be 1, 2, 3, 4 (columns) or \"full\"");
		assertRefused(report, chart + ".height", "height must be 2 or 4 (tile rows)");
		assertRefused(report, chart + ".range", "unknown range \"2h\"");
		assertRefused(report, chart + ".presets[1]", "the preset 24h is listed twice");
		assertRefused(report, chart + ".axes.left", "min must be less than max");
		assertNoRefusal(report, chart + ".axes.top");
		assertRefused(report, chart + ".series[0].consolidation", "consumption needs a numeric datapoint; '1/0/1' is boolean");
		assertRefused(report, chart + ".series[1].datapoint", "'1/0/2' keeps no history");
		assertRefused(report, chart + ".series[1].axis", "unknown axis \"middle\"");
		assertRefused(report, chart + ".series[1].consolidation", "unknown consolidation \"median\"");
		assertRefused(report, chart + ".references[0]", "exactly one of value (a constant) or datapoint");
		assertRefused(report, chart + ".references[1].datapoint", "unknown datapoint 'nope'");
		assertRefused(report, "ui.pages[1].id", "duplicate page id 'a'");
	}

	/**
	 * The limits of the controller hold for the whole image (homecontrol #54): sections, chart series
	 * and reference lines are counted over all pages, so an image with each at its limit spread over
	 * two pages is valid, and one more of each is refused at <code>ui.pages</code>.
	 */
	public void testImageWideLimits() {
		assertTrue(ImageValidator.validate(limits(0)).toString(), ImageValidator.validate(limits(0)).isValid());
		ImageReport report = ImageValidator.validate(limits(1));
		assertRefused(report, "ui.pages", (ImageValidator.MAX_PAGE_SECTIONS + 1) + " sections");
		assertEquals(report.toString(), 3, report.getRefusals().size());
		assertTrue(report.toString(), report.toString().contains((ImageValidator.MAX_CHART_SERIES + 1) + " chart series"));
		assertTrue(report.toString(),
			report.toString().contains((ImageValidator.MAX_CHART_REFERENCES + 1) + " reference lines"));
	}

	/** Two pages that hold the image-wide limits of sections, series and reference lines plus <code>more</code>. */
	private static String limits(int more) {
		String series = "{\"datapoint\": \"1/0/1\"}";
		String reference = "{\"value\": 1}";
		StringBuilder pages = new StringBuilder("{\"pages\": [");
		for (int p = 0; p < 2; p++) {
			int sections = ImageValidator.MAX_PAGE_SECTIONS / 2 + (p == 0 ? more : 0);
			int seriesCount = ImageValidator.MAX_CHART_SERIES / 2 + (p == 0 ? more : 0);
			int references = ImageValidator.MAX_CHART_REFERENCES / 2 + (p == 0 ? more : 0);
			pages.append(p == 0 ? "" : ", ").append("{\"id\": \"p").append(p).append("\", \"sections\": [");
			pages.append("{\"elements\": [{\"chart\": {\"series\": [")
				.append(String.join(", ", Collections.nCopies(seriesCount, series)))
				.append("], \"references\": [")
				.append(String.join(", ", Collections.nCopies(references, reference)))
				.append("]}}]}");
			for (int s = 1; s < sections; s++) {
				pages.append(", {\"elements\": []}");
			}
			pages.append("]}");
		}
		return image("1.1", pages.append("]}").toString());
	}

	private static String image(String version, String ui) {
		return """
			{"image": {"schemaVersion": "%s", "projectId": "pages", "revision": 1},
			 "datapoints": [
			   {"id": "1/0/1", "type": "boolean", "history": {"retention": 3600, "spacing": 60}, "knx": {"dpt": "1.001", "listen": ["1/0/1"]}},
			   {"id": "1/0/2", "type": "real", "knx": {"dpt": "9.001", "listen": ["1/0/2"]}}],
			 "functions": [{"id": "f", "label": "F", "kind": "sensor", "datapoints": {"value": "1/0/2"}}],
			 "ui": %s}
			""".formatted(version, ui);
	}

	private static void assertRefused(ImageReport report, String path, String reason) {
		ImageReport.Refusal refusal = refusal(report, path);
		assertTrue(refusal.reason(), refusal.reason().contains(reason));
	}

	private static void assertNoRefusal(ImageReport report, String path) {
		for (ImageReport.Refusal refusal : report.getRefusals()) {
			assertFalse("Refused at " + path + ": " + report, refusal.path().equals(path));
		}
	}

	private static ImageReport.Refusal refusal(ImageReport report, String path) {
		for (ImageReport.Refusal refusal : report.getRefusals()) {
			if (refusal.path().equals(path)) {
				return refusal;
			}
		}
		fail("No refusal at " + path + ": " + report);
		return null;
	}

	private static List<?> elements(Map<?, ?> ui, int page, int section) {
		Map<?, ?> p = (Map<?, ?>) ((List<?>) ui.get("pages")).get(page);
		Map<?, ?> s = (Map<?, ?>) ((List<?>) p.get("sections")).get(section);
		return (List<?>) s.get("elements");
	}

	// ---- building the project ----------------------------------------------------------------

	private TLObject mainType(int number) {
		TLObject type = create("ehome.datapoint:DatapointMainType");
		type.tUpdateByName("number", Integer.valueOf(number));
		type.tUpdateByName("extId", "TEST-CONTROL-PAGES-" + number);
		_types.add(type);
		return type;
	}

	private static TLObject address(String name, int main, int middle, int sub, TLObject type, boolean history) {
		TLObject address = create("ehome.logical:GroupAddress");
		address.tUpdateByName("name", name);
		address.tUpdateByName("address", new GA((main << 11) | (middle << 8) | sub, GroupAddressStyle.ThreeLevel));
		if (type != null) {
			address.tUpdateByName("datapointTypes", List.of(type));
		}
		if (history) {
			// A history set by hand (haumacher/ehome #73).
			address.tUpdateByName("historyManual", Boolean.TRUE);
			address.tUpdateByName("historyRetention", Integer.valueOf(86400));
			address.tUpdateByName("historySpacing", Integer.valueOf(900));
		}
		return address;
	}

	private static TLObject part(String type, String name, String extId) {
		TLObject part = create(type);
		part.tUpdateByName("name", name);
		part.tUpdateByName("extId", extId);
		return part;
	}

	private TLObject function(String name, String identifier, TLObject room) {
		TLObject function = create("ehome.runtime:Function");
		function.tUpdateByName("name", de(name));
		function.tUpdateByName("identifier", identifier);
		function.tUpdateByName("kind", FunctionProposal.classifier("switch"));
		function.tUpdateByName("room", room);
		function.tUpdateByName("command", _light);
		return function;
	}

	private static ResKey de(String text) {
		return ResKey.builder().add(Locale.GERMAN, text).build();
	}

	private static ResKey text(String de, String en) {
		return ResKey.builder().add(Locale.GERMAN, de).add(Locale.ENGLISH, en).build();
	}

	private static Object classifier(String enumeration, String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:" + enumeration)).getClassifier(name);
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	/** The parts of a reference of the model. */
	static final class Parts {

		static List<TLObject> list(TLObject owner, String reference) {
			return RuleFunctions.objects(owner, reference);
		}

		static TLObject get(TLObject owner, String reference, int index) {
			return list(owner, reference).get(index);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestControlPages.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
