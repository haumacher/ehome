/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.Astro.GeoLocation;
import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.Engine;
import de.haumacher.ehome.rules.Engine.LogCode;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.PosixZone;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleLoader.LoadException;
import de.haumacher.ehome.rules.RuleLoader.Refusal;
import de.haumacher.ehome.rules.RuleSet.Level;
import de.haumacher.ehome.rules.RuleSet.StartReason;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Scheduler;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;

/**
 * Probes of the reference interpreter beyond the corpus: the scheduler of an image driving its rules
 * (the corpus fires schedules by hand), the image validator's rule and schedule checks on the
 * specification's example, and refusal paths of the loader.
 */
@SuppressWarnings("javadoc")
public class TestRulesProbe extends TestCase {

	private static final File EXAMPLE = new File("src/test/fixtures/homecontrol/images/example.json");

	private static Map<String, Object> example() throws Exception {
		@SuppressWarnings("unchecked")
		Map<String, Object> image = (Map<String, Object>) Json.parse(
			Files.readString(EXAMPLE.toPath(), StandardCharsets.UTF_8), 100);
		return image;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return (List<Object>) value;
	}

	/**
	 * The example image run end to end: its schedules (loaded from the image with its time zone and
	 * location) fire into its rules over two days; the workday-morning rule switches the light,
	 * unless away; the output datapoint of the schedule turns true at 06:30 and false at midnight.
	 */
	public void testExampleImageRunsOnItsSchedules() throws Exception {
		Map<String, Object> image = example();
		Map<String, Object> settings = map(image.get("settings"));
		PosixZone zone = PosixZone.parse((String) settings.get("tz"));
		Map<String, Object> location = map(settings.get("location"));
		GeoLocation where = new GeoLocation(((Number) location.get("lat")).doubleValue(),
			((Number) location.get("lon")).doubleValue(), ((Number) location.get("elev")).doubleValue());

		// Friday 2026-10-16 05:00 CEST.
		long[] utc = { Values.daysOf(2026, 10, 16) * 86400 + 3 * 3600 };
		DatapointModel model = new DatapointModel(() -> utc[0] * 1000);
		Scheduler.TimeSource time = new Scheduler.TimeSource() {
			@Override
			public Long nowUtc() {
				return Long.valueOf(utc[0]);
			}

			@Override
			public int utcOffset(long instant) {
				return zone.offset(instant);
			}

			@Override
			public GeoLocation location() {
				return where;
			}
		};
		List<String> schedulerLog = new ArrayList<>();
		Scheduler scheduler = new Scheduler(time, new HashMap<>(), model, schedulerLog::add);
		for (Object item : list(image.get("datapoints"))) {
			Map<String, Object> dp = map(item);
			String id = (String) dp.get("id");
			boolean output = id.equals("schedule.morning");
			model.add(id, ValueType.fromName((String) dp.get("type")), Boolean.TRUE.equals(dp.get("writable")),
				output ? scheduler : (datapoint, value) -> true);
		}
		Schedule.Loaded schedules = Schedule.load(image.get("schedules"), new Schedule.Datapoints() {
			@Override
			public ValueType type(String name) {
				return model.type(name);
			}

			@Override
			public String owner(String name) {
				return null;
			}
		});
		assertTrue(schedules.refusals().toString(), schedules.ok());
		scheduler.load(schedules.entries());

		Engine engine = new Engine(model, () -> utc[0] * 1000, new ParameterStore(), new Engine.TimeSource() {
			@Override
			public DateTime localNow() {
				long local = utc[0] + zone.offset(utc[0]);
				Val.Date date = Values.civilOf(Math.floorDiv(local, 86400L));
				long rest = Math.floorMod(local, 86400L);
				return new DateTime(date.year(), date.month(), date.day(), (int) (rest / 3600), (int) (rest % 3600 / 60),
					(int) (rest % 60), Values.weekdayOf(date.year(), date.month(), date.day()), 0);
			}

			@Override
			public TimeOfDay sunTime(SunEvent event) {
				return scheduler.sunTime(utc[0], event);
			}
		});
		List<String> commands = new ArrayList<>();
		engine.setObserver(new Engine.Observer() {
			@Override
			public void ruleCommanded(String rule, DatapointModel.Datapoint datapoint, Val value) {
				commands.add(utc[0] + " " + rule + " " + datapoint.name() + " = " + Values.format(value));
			}

			@Override
			public void ruleLogged(String rule, LogCode code, Level level, String text) {
				commands.add(utc[0] + " " + rule + " " + code.externalName() + " " + text);
			}
		});
		scheduler.addListener(engine::scheduleFired);
		assertNull(engine.load(image.get("rules"), id -> schedules.ids().contains(id)));
		engine.start(StartReason.BOOT);
		engine.drain();

		// Run to Monday noon: the morning entry fires Friday and Monday 06:30, blindsEvening at dusk.
		long until = Values.daysOf(2026, 10, 19) * 86400 + 10 * 3600;
		while (true) {
			Long wakeup = scheduler.wakeup();
			if (wakeup == null || wakeup.longValue() > until) {
				break;
			}
			utc[0] = Math.max(utc[0], wakeup.longValue());
			scheduler.fireDue();
			engine.drain();
		}
		long friday = Values.daysOf(2026, 10, 16) * 86400 + 4 * 3600 + 1800;
		long monday = Values.daysOf(2026, 10, 19) * 86400 + 4 * 3600 + 1800;
		assertEquals(List.of(friday + " morning-light living.light = true", monday + " morning-light living.light = true"),
			commands);
		assertTrue(schedulerLog.stream().anyMatch(line -> line.equals("I schedule blindsEvening fired")));
		assertEquals(Val.ofBool(true), model.get("schedule.morning").value());

		// Away: the Tuesday morning does not switch.
		model.command("away", Val.ofBool(true));
		engine.drain();
		long tuesday = Values.daysOf(2026, 10, 20) * 86400 + 4 * 3600 + 1800;
		while (scheduler.wakeup() != null && scheduler.wakeup().longValue() <= tuesday) {
			utc[0] = Math.max(utc[0], scheduler.wakeup().longValue());
			scheduler.fireDue();
			engine.drain();
		}
		assertEquals(2, commands.size());
		assertEquals(Long.valueOf(tuesday), scheduler.lastFired(scheduler.find("workdayMorning")));
	}

	private static List<ImageReport.Refusal> refusals(Map<String, Object> image) {
		ImageReport report = ImageValidator.validate(Json.write(image));
		return report.getRefusals();
	}

	public void testValidatorChecksRulesAgainstSchedulesAndDatapoints() throws Exception {
		Map<String, Object> image = example();
		assertEquals(List.of(), refusals(image));

		// A rule naming a schedule the image does not have.
		Map<String, Object> trigger = map(list(map(list(map(list(image.get("rules")).get(1)).get("reactions")).get(0))
			.get("triggers")).get(0));
		trigger.put("schedule", "workdayEvening");
		List<ImageReport.Refusal> refused = refusals(image);
		assertEquals(1, refused.size());
		assertEquals("rules[1].reactions[0].triggers[0].schedule", refused.get(0).path());
		assertTrue(refused.get(0).reason(), refused.get(0).reason().startsWith("rule 'morning-light': unknownReference"));

		// The schedule's id refused: the rules are not checked any more (the controller does the same).
		trigger.put("schedule", "workdayMorning");
		Map<String, Object> morning = map(list(image.get("schedules")).get(0));
		morning.put("id", "workday morning");
		refused = refusals(image);
		assertEquals(1, refused.size());
		assertEquals("schedules[0].id", refused.get(0).path());
		assertTrue(refused.get(0).reason().startsWith("malformed: id 'workday morning'"));

		// An output owned by the KNX adapter, an unknown one, a non-boolean one (the time master's).
		morning.put("id", "workdayMorning");
		morning.put("output", "living.light");
		Map<String, Object> evening = map(list(image.get("schedules")).get(1));
		evening.put("output", "nothing");
		refused = refusals(image);
		assertEquals(refused.toString(), 2, refused.size());
		assertEquals("outputConflict: output 'living.light' is owned by the KNX adapter", refused.get(0).reason());
		assertEquals("schedules[1].output", refused.get(1).path());
		assertTrue(refused.get(1).reason().startsWith("unknownReference"));
		evening.put("output", "knx.time");
		morning.remove("output");
		refused = refusals(image);
		assertEquals("typeError: output 'knx.time' is timeOfDay, not boolean", refused.get(0).reason());
	}

	private static Refusal refusal(String rulesJson, String... datapoints) throws Exception {
		DatapointModel model = new DatapointModel(() -> 0);
		for (String name : datapoints) {
			model.add(name, ValueType.REAL, true, null);
		}
		try {
			RuleLoader.load(Json.parse(rulesJson, 100), model, id -> false);
			return null;
		} catch (LoadException ex) {
			return ex.getRefusal();
		}
	}

	public void testLoaderRefusals() throws Exception {
		String start = "[{\"id\": \"r\", \"name\": \"R\", \"reactions\": [{\"triggers\": [{\"kind\": \"start\"}], \"actions\": [";
		String end = "]}]}]";
		Refusal timer = refusal(start + "{\"kind\": \"write\", \"datapoint\": \"x\", \"value\": {\"kind\": \"timer\", \"id\": \"t\"}}" + end, "x");
		assertEquals("forbidden", timer.error().externalName());
		assertEquals("rules[0].reactions[0].actions[0].value", timer.path());
		assertEquals("r", timer.rule());

		// The limits apply to the normalized tree (spec/rules.md 4.8): a chain of minus signs over a
		// number literal is one literal, a chain of negated sums is not.
		String chain = "{\"kind\": \"literal\", \"type\": \"real\", \"value\": 1}";
		String deep = "{\"kind\": \"datapoint\", \"name\": \"x\"}";
		for (int i = 0; i < 12; i++) {
			chain = "{\"kind\": \"unary\", \"op\": \"-\", \"operand\": " + chain + "}";
			deep = "{\"kind\": \"unary\", \"op\": \"-\", \"operand\": " + deep + "}";
		}
		assertNull(refusal(start + "{\"kind\": \"write\", \"datapoint\": \"x\", \"value\": " + chain + "}" + end, "x"));
		Refusal depth = refusal(start + "{\"kind\": \"write\", \"datapoint\": \"x\", \"value\": " + deep + "}" + end, "x");
		assertEquals("limitExceeded", depth.error().externalName());

		Refusal match = refusal("[{\"id\": \"r\", \"name\": \"R\", \"reactions\": [{\"triggers\": [{\"kind\": \"match\", "
			+ "\"condition\": {\"kind\": \"binary\", \"op\": \">\", \"left\": {\"kind\": \"datapoint\", \"name\": \"x\"}, "
			+ "\"right\": {\"kind\": \"call\", \"function\": \"sinceUpdate\", \"args\": [{\"kind\": \"datapoint\", \"name\": \"x\"}]}}}], "
			+ "\"actions\": []}]}]", "x");
		assertEquals("forbidden", match.error().externalName());
		assertEquals("rules[0].reactions[0].triggers[0].condition.right", match.path());

		Refusal enabled = refusal(start + "{\"kind\": \"setParameter\", \"parameter\": \"enabled\", "
			+ "\"value\": {\"kind\": \"literal\", \"type\": \"boolean\", \"value\": false}}" + end);
		assertEquals("forbidden", enabled.error().externalName());
		assertEquals("rules[0].reactions[0].actions[0].parameter", enabled.path());

		Refusal unknown = refusal(start + "{\"kind\": \"write\", \"datapoint\": \"x\", \"value\": {\"kind\": \"literal\", "
			+ "\"type\": \"real\", \"value\": 1}, \"extra\": 1}" + end, "x");
		assertEquals("malformed", unknown.error().externalName());
		assertEquals("unknown member 'extra'", unknown.text());

		assertNull(refusal(start + "{\"kind\": \"log\", \"level\": \"info\", \"message\": [\"t \", {\"kind\": \"call\", "
			+ "\"function\": \"toString\", \"args\": [{\"kind\": \"datapoint\", \"name\": \"x\"}]}]}" + end, "x"));
	}

	public static Test suite() {
		return new TestSuite(TestRulesProbe.class);
	}

}
