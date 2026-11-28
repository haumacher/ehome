/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.datapoint.DatapointMainType;
import de.haumacher.ehome.model.datapoint.DatapointSubtype;
import de.haumacher.ehome.model.datapoint.DatapointType;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.GroupAddressDefinition;
import de.haumacher.ehome.model.logical.ProjectDatapoint;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.DatapointElement;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.Page;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleState;
import de.haumacher.ehome.model.runtime.RuleTimeline;
import de.haumacher.ehome.model.runtime.TimelineAssignment;
import de.haumacher.ehome.model.runtime.TimelinePlan;
import de.haumacher.ehome.model.runtime.TimelinePoint;
import de.haumacher.ehome.model.runtime.WriteAction;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.ImageComparison;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleExport;
import de.haumacher.ehome.runtime.RuleReferences;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.runtime.StateDatapoints;
import de.haumacher.ehome.simulation.Simulation;

/**
 * The timeline of a rule with states and the datapoint of a rule's state (haumacher/ehome #140,
 * homecontrol <code>spec/rules.md</code> 2.8, 7.13, 8.8): the heating program of the issue built
 * through the model exports as the specification's image <code>heating-timeline.json</code>, each
 * refusal of the timeline shows at its field, the state datapoint is named, referenced by a rule and
 * a control page and simulated, and a deploy that removes a state the deployed timeline names warns.
 *
 * <p>
 * The project is built without an ETS import: two group addresses of DPT 20.102 (HVAC mode) of a
 * datapoint type created for the test.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestTimelines extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-16T04:00:00Z");

	private static final File FIXTURE = new File("src/test/fixtures/homecontrol/images/heating-timeline.json");

	private Project _project;

	private TLObject _main;

	private Rule _rule;

	private RuleState _comfort;

	private RuleState _standby;

	private RuleState _night;

	private RuleTimeline _timeline;

	private TimelinePlan _workday;

	private TimelinePlan _weekend;

	private GroupAddress _living;

	private GroupAddress _bedroom;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			DatapointMainType main = (DatapointMainType) create("ehome.datapoint:DatapointMainType");
			main.setNumber(20);
			main.setExtId("TEST-TIMELINES-20");
			DatapointSubtype hvac = (DatapointSubtype) create("ehome.datapoint:DatapointSubtype");
			hvac.setNumber(102);
			hvac.setExtId("TEST-TIMELINES-20-102");
			main.setSubtypes(List.of(hvac));
			_main = main;

			_project = (Project) create("ehome.project:Project");
			_project.setName("Heating program");
			_project.setExtId("heating-timeline");
			_project.setTimeZone("CET-1CEST,M3.5.0,M10.5.0/3");
			_project.setLatitude(Double.valueOf(48.137));
			_project.setLongitude(Double.valueOf(11.576));
			_living = address("Living room HVAC mode", 1, hvac);
			_bedroom = address("Bedroom HVAC mode", 2, hvac);
			_project.setGroupAddresses(Set.of(_living, _bedroom));

			_rule = (Rule) RuleFunctions.create(_project, "rules", runtime("Rule"));
			_rule.setIdentifier("heating");
			_rule.setNameKey(text("Heating program", "Heizprogramm"));
			_comfort = state("comfort", text("Comfort", "Komfort"), "1");
			_standby = state("standby", text("Standby", "Standby"), "2");
			_night = state("night", text("Night", "Nacht"), "3");
			_rule.setInitialState(_comfort);

			_timeline = (RuleTimeline) RuleFunctions.create(_rule, "timeline", runtime("RuleTimeline"));
			// The new timeline's plan becomes the workday.
			_workday = (TimelinePlan) RuleFunctions.objects(_timeline, "plans").get(0);
			_workday.setIdentifier("workday");
			_workday.setLabel(text("Workday", "Werktag"));
			point(_workday, "06:00", _comfort);
			point(_workday, "08:00", _standby);
			point(_workday, "16:00", _comfort);
			point(_workday, "22:00", _night);
			_weekend = (TimelinePlan) RuleFunctions.create(_timeline, "plans", runtime("TimelinePlan"));
			_weekend.setIdentifier("weekend");
			_weekend.setLabel(text("Weekend", "Wochenende"));
			point(_weekend, "07:30", _comfort);
			point(_weekend, "23:00", _night);
			TimelineAssignment weekend =
				(TimelineAssignment) RuleFunctions.create(_timeline, "assignments", runtime("TimelineAssignment"));
			assertEquals("The second assignment is proposed for the days no other names.", 7,
				RuleFunctions.objects(weekend, "days").size());
			weekend.setPlan(_weekend);
			weekend.setDays(new LinkedHashSet<>(List.of(weekday("saturday"), weekday("sunday"))));
			tx.commit();
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Page page = (Page) Pages.insert(_project, null, Pages.PAGE, null);
			page.setNameKey(text("Heating", "Heizung"));
			page.setIdentifier("heating");
			TLObject section = Pages.insert(_project, page, Pages.SECTION, null);
			Pages.insert(_project, section, Pages.DATAPOINT, List.of(StateDatapoints.of(_rule), _living, _bedroom));
			List<TLObject> elements = RuleFunctions.objects(section, "elements");
			((DatapointElement) elements.get(1)).setReadOnly(true);
			((DatapointElement) elements.get(2)).setReadOnly(true);
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_main.tDelete();
			tx.commit();
		}
		super.tearDown();
	}

	/**
	 * Acceptance of haumacher/ehome #140: the heating program exports as the specification's image,
	 * as JSON values, apart from <code>comment</code>, <code>generatedAt</code> and
	 * <code>generator</code>.
	 */
	public void testHeatingExport() throws Exception {
		assertNotNull("Every rule has its state datapoint.", StateDatapoints.of(_rule));
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());

		Map<String, Object> expected = mutable(Json.parse(Files.readString(FIXTURE.toPath(), StandardCharsets.UTF_8), 100));
		expected.remove("comment");
		Map<String, Object> actual = mutable(image.getJson());
		((Map<?, ?>) actual.get("image")).remove("generatedAt");
		((Map<?, ?>) actual.get("image")).remove("generator");
		assertEquals(Json.write(normalize(expected)), Json.write(normalize(actual)));
	}

	/**
	 * Every refusal of the timeline that E-Home's model can make shows at its field with the
	 * controller's reason (homecontrol <code>spec/rules.md</code> 10.1), as a warning; the image build
	 * refuses the same.
	 */
	public void testRefusalsAtTheField() throws Exception {
		TLObject point = RuleFunctions.objects(_workday, "points").get(1);
		RuleForm form = new RuleForm(point);
		assertEquals(List.of(), form.warnings("time"));
		form.set("sun", sun("sunset"));
		assertWarning(form, "sun", "A switching point has a clock time or a sun event, not both.");
		form.set("time", null);
		assertEquals(List.of(), form.warnings("sun"));
		form.set("sun", null);
		assertWarning(form, "time", "A switching point needs a clock time or a sun event.");
		form.set("time", "08:00");
		form.set("offset", "1h");
		assertWarning(form, "offset", "\"offset\" is only for a switching point at a sun event.");
		form.set("time", null);
		form.set("sun", sun("sunset"));
		assertEquals(List.of(), form.warnings("offset"));
		form.set("offset", "13h");
		assertWarning(form, "offset", "The offset must be whole seconds from -12 h to 12 h.");
		form.set("offset", "2h");
		form.set("notBefore", "22:00");
		form.set("notAfter", "21:00");
		assertWarning(form, "notBefore", "The earliest time 22:00:00 is after the latest time 21:00:00.");
		form.set("notAfter", "23:00");
		assertEquals(List.of(), form.warnings("notBefore"));
		form.set("state", null);
		assertFalse("A point needs its state.", form.errors("state").isEmpty());

		RuleForm planForm = new RuleForm(_weekend);
		planForm.set("identifier", "workday");
		assertWarning(planForm, "identifier", "The plan id \"workday\" is used twice.");
		planForm.set("identifier", "week end");
		assertWarning(planForm, "identifier", "The plan id \"week end\" is not 1 to 24 characters of A-Z a-z 0-9 _ -.");

		TLObject weekendAssignment = RuleFunctions.objects(_timeline, "assignments").get(1);
		RuleForm assignmentForm = new RuleForm(weekendAssignment);
		assignmentForm.set("days", List.of());
		assertWarning(assignmentForm, "days", "This is a second default assignment; assignment 1 is the default already.");
		TLObject third;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			third = RuleFunctions.create(_timeline, "assignments", runtime("TimelineAssignment"));
			tx.commit();
		}
		assertEquals("The free weekdays, Monday to Friday.", 5, RuleFunctions.objects(third, "days").size());
		RuleForm thirdForm = new RuleForm(third);
		assertEquals(List.of(), thirdForm.warnings("days"));
		thirdForm.set("days", List.of(weekday("sunday")));
		assertWarning(thirdForm, "days", "Weekday 7 is already assigned by assignment 2.");
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			third.tDelete();
			tx.commit();
		}

		RuleForm stateForm = new RuleForm(_comfort);
		stateForm.set("label", text("x".repeat(64), "y"));
		assertEquals(1, stateForm.warnings("label").size());

		// Limits and the timeline as a whole, stored: at the field adjustable of the timeline, refused by the image.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (int i = 0; i < 15; i++) {
				point(_weekend, String.format(Locale.ROOT, "%02d:15", Integer.valueOf(i)), _night);
			}
			tx.commit();
		}
		assertStored(_weekend, "identifier", "A day plan has more than 16 switching points.");
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertFalse(report.isValid());
		assertEquals("rules[0].timeline.plans[1].points", report.getRefusals().get(0).path());

		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			List<TLObject> points = RuleFunctions.objects(_weekend, "points");
			_weekend.setPoints(List.of((TimelinePoint) points.get(0), (TimelinePoint) points.get(1)));
			for (TLObject extra : points.subList(2, points.size())) {
				extra.tDelete();
			}
			RuleFunctions.objects(_timeline, "assignments").get(0).tDelete();
			tx.commit();
		}
		assertStored(_timeline, "adjustable", "The timeline has no default assignment (one without weekdays).");

		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			RuleFunctions.create(_timeline, "assignments", runtime("TimelineAssignment"));
			// Without states a rule may have no timeline.
			for (TLObject plan : RuleFunctions.objects(_timeline, "plans")) {
				for (TLObject planPoint : RuleFunctions.objects(plan, "points")) {
					planPoint.tDelete();
				}
			}
			for (TLObject state : List.of(_comfort, _standby, _night)) {
				state.tDelete();
			}
			tx.commit();
		}
		assertStored(_timeline, "adjustable", "A timeline is given in a rule without states.");
	}

	/**
	 * The datapoint of a rule's state: named by the rule and "state", the choices by the states'
	 * labels; a rule triggers on it and a page shows it (both build and export); the simulation starts
	 * in the state due at its start and follows the timeline; a rule may not write it, nor reference
	 * its own; a parameter "state" is refused.
	 */
	public void testStateDatapoint() throws Exception {
		TLObject state = StateDatapoints.of(_rule);
		assertEquals("rule/heating/state", Pages.imageId(state));
		assertEquals("enumeration", Pages.valueType(state));
		assertEquals("Heating program: state (rule/heating/state)", DatapointNames.name(state));
		assertSame(state, RuleReferences.datapoints(_project).get("rule/heating/state"));
		assertTrue(Pages.datapoints(_project).contains(state));

		TLObject follow;
		TLObject lamp;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ApplicationDatapoint created =
				(ApplicationDatapoint) RuleFunctions.create(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			created.setIdentifier("comfort.lamp");
			lamp = created;
			Rule rule = (Rule) RuleFunctions.create(_project, "rules", runtime("Rule"));
			rule.setNameKey(ResKey.text("follow"));
			rule.setIdentifier("follow");
			follow = rule;
			TLObject reaction = RuleFunctions.create(follow, "reactions", runtime("Reaction"));
			((DatapointTrigger) RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger")))
				.setDatapoint((ProjectDatapoint) state);
			WriteAction write = (WriteAction) RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			write.setDatapoint(created);
			write.setValue("@\"rule/heating/state\" == 0");
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());

		// Booted Monday 05:00 local: the program enters night (Sunday's 23:00) with its entry actions.
		Simulation simulation = new Simulation(image.getJson(), NOW.getEpochSecond(), new ParameterStore());
		try {
			assertNull(simulation.start());
			assertEquals("night", simulation.state("heating"));
			assertEquals("2", String.valueOf(simulation.value("rule/heating/state")).replaceAll("[^0-9]", ""));
			simulation.advance(3600_000L);
			assertEquals("comfort", simulation.state("heating"));
			assertTrue(simulation.timeline().stream().anyMatch(e -> e.text().equals("timeline: switching point to comfort")));
			assertEquals("true", String.valueOf(simulation.value("comfort.lamp")).replaceAll("[^a-z]", "").replace("boolean", ""));
		} finally {
			simulation.close();
		}

		// A write to the state and the rule's own reference are refused at their fields.
		TLObject write = RuleFunctions.objects(RuleFunctions.objects(follow, "reactions").get(0), "actions").get(0);
		RuleForm writeForm = new RuleForm(write);
		writeForm.set("datapoint", state);
		assertWarning(writeForm, "datapoint",
			"The datapoint Heating program: state (rule/heating/state) is the state of a rule: no rule writes it.");
		TLObject parameter;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			parameter = RuleFunctions.create(_rule, "parameters", runtime("RuleParameter"));
			tx.commit();
		}
		RuleForm parameterForm = new RuleForm(parameter);
		parameterForm.set("identifier", "state");
		assertWarning(parameterForm, "identifier",
			"The parameter id \"state\" is reserved for the state datapoint of a rule with states.");
	}

	/**
	 * Review probe: a state that a switching point names cannot be deleted while the point names it
	 * (the point's state is mandatory): the commit is refused, and the image stays as it was.
	 */
	public void testProbeDeletedStateOfPoint() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_night.tDelete();
			tx.commit();
			fail("Deleting a state a switching point names must be refused.");
		} catch (com.top_logic.util.error.TopLogicException ex) {
			// Expected: constraints violated.
		}
		assertTrue(RuntimeImage.build(_project, 1, NOW).validate().isValid());
	}

	/** Review probe: the id of the state datapoint follows the rule's id, also on a page. */
	public void testProbeRenamedRule() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_rule.setIdentifier("heat2");
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		String json = Json.write(image.getJson());
		assertTrue(json, json.contains("\"rule/heat2/state\""));
		assertFalse(json, json.contains("rule/heating/state"));
	}

	/**
	 * Review probe: the rule loses its timeline and all its states while a page shows its state
	 * datapoint. The page's reference is not dropped silently: the image is refused with a reason.
	 */
	public void testProbeStatesRemovedWhilePageShowsState() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_timeline.tDelete();
			_rule.setInitialState(null);
			for (TLObject state : List.of(_comfort, _standby, _night)) {
				state.tDelete();
			}
			tx.commit();
		}
		ImageReport report = RuntimeImage.build(_project, 1, NOW).validate();
		String json = Json.write(RuntimeImage.build(_project, 1, NOW).getJson());
		assertTrue("Either the page still names the state and the image is refused, or the element is gone with a reason: "
			+ report + " " + json, !report.isValid());
	}

	/**
	 * haumacher/ehome #153: a write of 0 to a boolean datapoint warns at the value field, stored and in
	 * the create dialog; the field suggests <code>true</code> and <code>false</code>.
	 */
	public void testWriteIntegerToBooleanWarns() throws Exception {
		TLObject main;
		WriteAction write;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			DatapointMainType mainType = (DatapointMainType) create("ehome.datapoint:DatapointMainType");
			mainType.setNumber(1);
			mainType.setExtId("TEST-TIMELINES-1");
			DatapointSubtype sw = (DatapointSubtype) create("ehome.datapoint:DatapointSubtype");
			sw.setNumber(1);
			sw.setExtId("TEST-TIMELINES-1-001");
			mainType.setSubtypes(List.of(sw));
			main = mainType;
			GroupAddress light = address("Light", 3, sw);
			Set<GroupAddressDefinition> addresses = new LinkedHashSet<>(_project.getGroupAddresses());
			addresses.add(light);
			_project.setGroupAddresses(addresses);
			write = (WriteAction) RuleFunctions.create(_comfort, "entry", runtime("WriteAction"));
			write.setDatapoint(light);
			write.setValue("true");
			tx.commit();
		}
		try {
			assertEquals(List.of("true", "false"), suggested(write, ""));
			assertEquals(List.of("false"), suggested(write, "f"));
			assertEquals(List.of(), suggested(write, "0"));
			RuleForm form = new RuleForm(write);
			assertEquals(List.of(), form.warnings("value"));
			form.set("value", "0");
			assertEquals(1, form.warnings("value").size());

			// The same in the create dialog: a draft of a new entry action.
			TLObject draft = de.haumacher.ehome.runtime.RuleDrafts.draft(_standby, "entry", runtime("WriteAction"));
			RuleForm dialog = new RuleForm(draft);
			dialog.set("datapoint", write.getDatapoint());
			dialog.set("value", "1");
			assertEquals(1, dialog.warnings("value").size());
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				write.tDelete();
				tx.commit();
			}
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				main.tDelete();
				tx.commit();
			}
		}
	}

	/**
	 * A deploy that removes a state the timeline deployed last names warns that an inhabitant's
	 * timeline naming it would be dropped.
	 */
	public void testRemovedStateWarns() throws Exception {
		Map<String, Object> stored = RuntimeImage.build(_project, 1, NOW).getJson();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject point : RuleFunctions.objects(_workday, "points")) {
				if (((TimelinePoint) point).getState() == _standby) {
					((TimelinePoint) point).setState(_comfort);
				}
			}
			_standby.tDelete();
			tx.commit();
		}
		Map<String, Object> next = RuntimeImage.build(_project, 2, NOW).getJson();
		List<String> warnings = ImageComparison.droppedTimelines(stored.get("rules"), next.get("rules"));
		assertEquals(warnings.toString(), 1, warnings.size());
		assertTrue(warnings.get(0), warnings.get(0).contains("standby"));
		assertEquals(List.of(), ImageComparison.droppedTimelines(next.get("rules"), next.get("rules")));
	}

	/** The texts the value field of the part suggests for the given input. */
	private static List<Object> suggested(TLObject part, String text) {
		List<Object> result = new ArrayList<>();
		for (Object entry : RuleFunctions.suggestions(part, text, text.length())) {
			result.add(((Map<?, ?>) entry).get("text"));
		}
		return result;
	}

	/** The stored object's field shows the controller's refusal. */
	private static void assertStored(TLObject obj, String attribute, String text) {
		ResKey problem = RuleExport.fieldProblem(obj, attribute, obj.tValueByName(attribute), false);
		assertNotNull(attribute, problem);
		String message = Resources.getInstance().getString(problem);
		assertTrue(message, message.contains(text));
	}

	private static void assertWarning(RuleForm form, String attribute, String text) {
		List<String> warnings = form.warnings(attribute);
		assertEquals(attribute + ": " + warnings, 1, warnings.size());
		assertTrue(warnings.get(0), warnings.get(0).contains(text));
	}

	private RuleState state(String id, ResKey label, String mode) {
		RuleState result = (RuleState) RuleFunctions.create(_rule, "states", runtime("RuleState"));
		result.setIdentifier(id);
		result.setLabel(label);
		for (GroupAddress address : List.of(_living, _bedroom)) {
			WriteAction write = (WriteAction) RuleFunctions.create(result, "entry", runtime("WriteAction"));
			write.setDatapoint(address);
			write.setValue(mode);
		}
		return result;
	}

	private static TimelinePoint point(TLObject plan, String time, RuleState state) {
		TimelinePoint result = (TimelinePoint) RuleFunctions.create(plan, "points", runtime("TimelinePoint"));
		result.setTime(time);
		result.setState(state);
		return result;
	}

	private static GroupAddress address(String name, int sub, TLObject type) {
		GroupAddress address = (GroupAddress) create("ehome.logical:GroupAddress");
		address.setName(name);
		address.setAddress(new GA((1 << 11) | (4 << 8) | sub, GroupAddressStyle.ThreeLevel));
		address.setDatapointTypes(List.of((DatapointType) type));
		address.setRuntimeWritable(Boolean.TRUE);
		return address;
	}

	/** Numbers compared as numbers, maps by their keys. */
	private static Object normalize(Object value) {
		if (value instanceof Number number) {
			return Double.valueOf(number.doubleValue());
		}
		if (value instanceof Map<?, ?> map) {
			Map<Object, Object> result = new java.util.TreeMap<>();
			map.forEach((k, v) -> result.put(k, normalize(v)));
			return result;
		}
		if (value instanceof Collection<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object element : list) {
				result.add(normalize(element));
			}
			return result;
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	private static <T> T mutable(Object json) {
		if (json instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			map.forEach((key, value) -> result.put((String) key, mutable(value)));
			return (T) result;
		}
		if (json instanceof List<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object item : list) {
				result.add(mutable(item));
			}
			return (T) result;
		}
		return (T) json;
	}

	private static ResKey text(String en, String de) {
		return ResKey.builder().add(Locale.ENGLISH, en).add(Locale.GERMAN, de).build();
	}

	private static TLClassifier weekday(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:Weekday")).getClassifier(name);
	}

	private static Object sun(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:SunEvent")).getClassifier(name);
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestTimelines.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			StateDatapoints.Module.INSTANCE));
	}

}
