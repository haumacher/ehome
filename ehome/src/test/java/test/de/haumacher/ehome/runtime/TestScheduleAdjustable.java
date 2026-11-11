/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.rules.TestRuleText;

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

import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Scheduler;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageComparison;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuleExport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Entry;

/**
 * Adjustable schedule members (haumacher/ehome #74, homecontrol #58, schema 1.2): the entry of the
 * editor exported as the device's conformance case <code>accept-schedule-adjustable</code> has it,
 * the import of that case and its export again, the refusals at the fields of the entry, the datapoint
 * <code>schedule/&lt;id&gt;/time</code> in a rule and the simulation, the warnings of the deploy
 * confirmation about the inhabitant's values a new image would drop and about a controller reading
 * an older schema.
 */
@SuppressWarnings("javadoc")
public class TestScheduleAdjustable extends BasicTestCase {

	private static final File ACCEPT = new File(TestImageConformance.IMAGES, "cases/accept-schedule-adjustable.json");

	private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

	private static final long START = Values.daysOf(2026, 11, 2) * 86400 + 4 * 3600;

	/**
	 * A weekly entry edited with an adjustable time (bounds and step) and adjustable days is exported
	 * as the device's case has it.
	 */
	public void testExportWeekly() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject entry = workdayMorning(project);
				entry.tUpdateByName("timeMin", "05:00");
				entry.tUpdateByName("timeMax", "09:00");
				entry.tUpdateByName("timeStep", "5min");
				tx.commit();
			}
			List<Object> exported = new RuleExport().schedules(project);
			assertEquals(1, exported.size());
			Object expected = fixtureSchedules().get(0);
			assertTrue(Json.write(exported.get(0)), TestRuleText.same(expected, exported.get(0)));

			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertEquals("1.6", ((Map<?, ?>) image.getJson().get("image")).get("schemaVersion"));
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The schedules of the device's case (every member each kind allows, bounds and steps) are imported
	 * and exported again unchanged.
	 */
	public void testImportExport() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			List<?> schedules = fixtureSchedules();
			RuleImport importer = new RuleImport();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				importer.importSchedules(project, schedules);
				tx.commit();
			}
			assertEquals(List.of(), importer.getNotes());
			List<Object> exported = new RuleExport().schedules(project);
			assertTrue(Json.write(exported), TestRuleText.same(schedules, exported));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * A default outside its bounds is refused at the field of the default and in the image, at the
	 * entry; an adjustable member a kind does not have the editor cannot express (the yearly entry has
	 * no attribute for adjustable days), the validator refuses it in an image naming the entry.
	 */
	public void testRefusals() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject entry;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				entry = workdayMorning(project);
				entry.tUpdateByName("timeMin", "07:00");
				tx.commit();
			}
			List<String> warnings = new RuleForm(entry).warnings("time");
			assertEquals(warnings.toString(), 1, warnings.size());
			assertTrue(warnings.get(0), warnings.get(0).contains("The value of the image lies outside its bounds."));
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertFalse(report.isValid());
			Refusal refusal = report.getRefusals().get(0);
			assertEquals("schedules[0].bounds", refusal.path());
			assertSame(entry, image.elementAt(refusal.path()));

			TLClass yearly = runtime("YearlySchedule");
			assertNull(yearly.getPart("daysAdjustable"));
			assertNotNull(yearly.getPart("timeAdjustable"));
			assertNull(runtime("OnceSchedule").getPart("atStep"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}

		ImageReport report = ImageValidator.validate("""
			{"image": {"schemaVersion": "1.2", "projectId": "case", "revision": 1},
			"schedules": [{"id": "eve", "label": "Eve", "kind": "yearly", "month": 12, "day": 24,
			"time": {"hour": 17, "minute": 0, "second": 0}, "adjustable": ["time", "days"]}]}
			""");
		assertEquals(List.of(new Refusal("schedules[0].adjustable[1]",
			"malformed: 'days' cannot be adjustable in a yearly entry (time)").toString()),
			report.getRefusals().stream().map(Refusal::toString).toList());
		assertEquals("„days“ kann in einem Eintrag der Art yearly nicht einstellbar sein (time).",
			Resources.getInstance(Locale.GERMAN).getString(report.getRefusals().get(0).message()));
	}

	/**
	 * A rule reads the datapoint of an adjustable time: the image is valid, and in the simulation a
	 * command to the datapoint moves the schedule and fires the rule.
	 */
	public void testTimeDatapointInRule() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject entry = workdayMorning(project);
				entry.tUpdateByName("timeMin", "05:00");
				entry.tUpdateByName("timeMax", "09:00");
				TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
				TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger"))
					.tUpdateByName("datapoint", entry);
				reaction.tUpdateByName("condition", "@\"schedule/workdayMorning/time\" > 07:00");
				RuleFunctions.create(reaction, "actions", runtime("LogAction")).tUpdateByName("message", "late riser");
				tx.commit();
			}
			assertTrue(RuleFunctions.datapoints(project).stream()
				.anyMatch(dp -> "schedule/workdayMorning/time".equals(((Map<?, ?>) dp).get("id"))));
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());

			Simulation simulation = new Simulation(image.getJson(), START, new ParameterStore());
			try {
				assertNull(simulation.start());
				assertEquals(Val.ofTime(new TimeOfDay(6, 30, 0, 0)), simulation.value("schedule/workdayMorning/time"));
				assertEquals(CommandStatus.OK,
					simulation.command("schedule/workdayMorning/time", Val.ofTime(new TimeOfDay(7, 15, 0, 0))));
				assertEquals(Val.ofTime(new TimeOfDay(7, 15, 0, 0)), simulation.value("schedule/workdayMorning/time"));
				assertEquals(Schedule.Member.TIME.bit(), simulation.scheduler().overridden(0));
				assertTrue(logged(simulation).toString(), logged(simulation).contains("log (info): late riser"));

				// Off its bounds (05:00 to 09:00): refused, the time stays.
				assertEquals(Scheduler.Change.OUT_OF_BOUNDS,
					simulation.setSchedule("workdayMorning", Map.of(Schedule.Member.TIME, Long.valueOf(10 * 3600))));
				assertEquals(Val.ofTime(new TimeOfDay(7, 15, 0, 0)), simulation.value("schedule/workdayMorning/time"));
			} finally {
				simulation.close();
			}
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The deploy confirmation names the inhabitant's values a new image would drop: a member no longer
	 * adjustable, and bounds that no longer allow every value the stored image allowed.
	 */
	public void testDroppedOverrides() throws Exception {
		List<?> stored = fixtureSchedules();
		List<Object> next = new ArrayList<>();
		for (Object item : stored) {
			@SuppressWarnings("unchecked")
			Map<String, Object> entry = (Map<String, Object>) Json.parse(Json.write(item), 100);
			switch (String.valueOf(entry.get("id"))) {
				// Days no longer adjustable.
				case "workdayMorning" -> entry.put("adjustable", List.of("time"));
				// The step of 15 min becomes 10 min: 17:15 is off it.
				case "christmasEve" -> entry.put("bounds", Json.parse("{\"time\": {\"step\": 600}}", 100));
				// Wider bounds: every value still fits.
				case "trash" -> entry.remove("bounds");
				default -> {
					// Unchanged.
				}
			}
			next.add(entry);
		}
		assertEquals(List.of(), ImageComparison.droppedOverrides(stored, stored));
		assertEquals(List.of(
			text(I18NConstants.COMPARE_OVERRIDE_NOT_ADJUSTABLE__ENTRY_MEMBER.fill("workdayMorning", "days")),
			text(I18NConstants.COMPARE_OVERRIDE_NARROWED__ENTRY_MEMBER.fill("christmasEve", "time"))),
			ImageComparison.droppedOverrides(stored, next));
	}

	/**
	 * The deploy confirmation warns of a controller reading an older schema, or reporting none.
	 */
	public void testSchemaWarning() throws Exception {
		FakeController fake = new FakeController(TestImageComparison.TOKEN, 262144);
		TLObject project = TestRuleAuthoring.createProject();
		List<TLObject> controllers = new ArrayList<>();
		try {
			TLObject controller = TestImageComparison.controller(fake.getPort());
			controllers.add(controller);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("controller", controller);
				tx.commit();
			}
			String older = text(I18NConstants.CONFIRM_SCHEMA_OLDER__READS_IMAGE.fill("1.1", "1.5"));
			assertEquals("The controller reads the image schema up to 1.1; this image is 1.5: update the controller's "
				+ "firmware first, or what is newer in the image (adjustable schedules) is refused.", older);
			String unknown = text(I18NConstants.CONFIRM_SCHEMA_UNKNOWN__IMAGE.fill("1.5"));

			String current = ImageComparison.confirmation(project, NOW);
			assertFalse(current, current.contains(older) || current.contains(unknown));

			fake.setSchema("1.1");
			String confirmation = ImageComparison.confirmation(project, NOW);
			assertTrue(confirmation, confirmation.endsWith("\n\n" + older));

			fake.setSchema(null);
			confirmation = ImageComparison.confirmation(project, NOW);
			assertTrue(confirmation, confirmation.endsWith("\n\n" + unknown));

			fake.setSchema("1.4");
			assertNull(ImageComparison.schemaWarning("1.4"));
			assertEquals(current, ImageComparison.confirmation(project, NOW));

			// Unreachable: the comparison says so, no schema line.
			fake.stop();
			confirmation = ImageComparison.confirmation(project, NOW);
			assertFalse(confirmation, confirmation.contains(older) || confirmation.contains(unknown));
		} finally {
			fake.stop();
			SharedData.deleteProjects(List.of(project));
			SharedData.deleteProjects(controllers);
		}
	}

	/**
	 * Probe of the review: the adjustable time of a schedule placed on a control page (#72) is a
	 * datapoint element of <code>schedule/&lt;id&gt;/time</code> in a valid image; without
	 * <code>timeAdjustable</code> the datapoint is not in the image, and the element is left out with a
	 * note instead of making the image invalid.
	 */
	public void testProbeTimeOnControlPage() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject entry;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				entry = workdayMorning(project);
				TLObject page = Pages.insert(project, null, Pages.PAGE, null);
				TLObject section = Pages.insert(project, page, Pages.SECTION, null);
				Pages.insert(project, section, Pages.DATAPOINT, List.of(entry));
				tx.commit();
			}
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			Map<?, ?> ui = (Map<?, ?>) image.getJson().get("ui");
			Map<?, ?> page = (Map<?, ?>) ((List<?>) ui.get("pages")).get(0);
			Map<?, ?> section = (Map<?, ?>) ((List<?>) page.get("sections")).get(0);
			assertEquals(Map.of("datapoint", "schedule/workdayMorning/time"), ((List<?>) section.get("elements")).get(0));

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				entry.tUpdateByName("timeAdjustable", Boolean.FALSE);
				tx.commit();
			}
			RuntimeImage fixed = RuntimeImage.build(project, 2, NOW);
			assertTrue(fixed.validate().toString(), fixed.validate().isValid());
			assertFalse(fixed.getNotes().toString(), fixed.getNotes().isEmpty());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/** The entry <code>workdayMorning</code> of the device's case, without its bounds. */
	private static TLObject workdayMorning(TLObject project) {
		TLObject entry = RuleFunctions.create(project, "schedules", runtime("WeeklySchedule"));
		entry.tUpdateByName("identifier", "workdayMorning");
		entry.tUpdateByName("label", ResKey.text("Workday morning"));
		entry.tUpdateByName("time", "06:30");
		TLEnumeration weekday = (TLEnumeration) TLModelUtil.findType("ehome.runtime:Weekday");
		List<TLClassifier> days = new ArrayList<>();
		for (String day : List.of("monday", "tuesday", "wednesday", "thursday", "friday")) {
			days.add(weekday.getClassifier(day));
		}
		entry.tUpdateByName("days", days);
		entry.tUpdateByName("timeAdjustable", Boolean.TRUE);
		entry.tUpdateByName("daysAdjustable", Boolean.TRUE);
		return entry;
	}

	private static List<?> fixtureSchedules() throws Exception {
		Map<?, ?> json = (Map<?, ?>) Json.parse(Files.readString(ACCEPT.toPath(), StandardCharsets.UTF_8), 100);
		return (List<?>) json.get("schedules");
	}

	private static List<String> logged(Simulation simulation) {
		List<String> result = new ArrayList<>();
		for (Entry entry : simulation.timeline()) {
			result.add(entry.text());
		}
		return result;
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestScheduleAdjustable.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
