/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.DatapointCodec;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.ProjectIndex;

/**
 * The inputs of the send form of the group monitor by the kind of the datapoint type
 * (haumacher/ehome #24): what {@link GroupMonitor#target(Object, String)} offers for each kind, and
 * the exact frame each input produces through {@link GroupMonitor#send(Object, Object, String)}, read
 * from a mocked bus. The values go to an address the project does not know, with the datapoint
 * type entered.
 */
@SuppressWarnings("javadoc")
public class TestGroupMonitorInputs extends BasicTestCase {

	private static final int ADDRESS = 0x7FFF;

	private static final String APDU = ADDRESS + ": ";

	private GroupMonitor _monitor;

	private MockBusLink _link;

	public void testInputs() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			_monitor = new GroupMonitor(ProjectIndex.of(project));
			_link = new MockBusLink();
			_monitor.connect(_link);
			try {
				checkLocked();
				_monitor.setSending(true);
				checkPercent();
				checkScale();
				checkStep();
				checkTime();
				checkDate();
				checkDateTime();
				checkChoice();
				checkUnchanged();
				checkTexts();
			} finally {
				_monitor.close();
			}
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	/**
	 * Every new input is refused while sending is not allowed.
	 */
	private void checkLocked() {
		for (Object[] input : new Object[][] {
			{ Integer.valueOf(50), "5.001" },
			{ step(DatapointCodec.UP, 1), "3.007" },
			{ time(2, 13, 45, 30), "10.001" },
			{ date(2026, 10, 17), "11.001" },
			{ "comfort", "20.102" } }) {
			try {
				_monitor.send(ADDRESS, input[0], (String) input[1]);
				fail("Sending must be refused while it is not allowed: " + input[1]);
			} catch (TopLogicException ex) {
				// Expected.
			}
		}
		assertTrue(_link.getSent().toString(), _link.getSent().isEmpty());
	}

	/**
	 * DPT 5.001: a slider of 0 to 100 %, scaled to the byte with KNX rounding.
	 */
	private void checkPercent() {
		Map<String, Object> target = _monitor.target(ADDRESS, "5.001");
		assertEquals("percent", target.get("kind"));
		assertEquals(0, target.get("min"));
		assertEquals(100, target.get("max"));
		assertEquals("%", target.get("unit"));

		assertSent("00 80 FF", Integer.valueOf(100), "5.001");
		// 50 * 255 / 100 = 127.5, rounded to 128.
		assertSent("00 80 80", Long.valueOf(50), "5.001");
		assertSent("00 80 00", Double.valueOf(0), "5.001");
		assertRefused(Integer.valueOf(101), "5.001");
		assertRefused(Integer.valueOf(-1), "5.001");
	}

	/**
	 * DPT 5.010 (and the other bytes of 0 to 255): a slider of 0 to 255; an angle (5.003) keeps the
	 * number field.
	 */
	private void checkScale() {
		Map<String, Object> target = _monitor.target(ADDRESS, "5.010");
		assertEquals("scale", target.get("kind"));
		assertEquals(0, target.get("min"));
		assertEquals(255, target.get("max"));
		assertEquals("scale", _monitor.target(ADDRESS, "5.004").get("kind"));
		assertEquals("number", _monitor.target(ADDRESS, "5.003").get("kind"));
		assertNull(_monitor.target(ADDRESS, "5.003").get("min"));

		assertSent("00 80 FF", Integer.valueOf(255), "5.010");
		assertSent("00 80 07", Integer.valueOf(7), "5.010");
		assertRefused(Integer.valueOf(256), "5.010");
	}

	/**
	 * DPT 3: a step up or down with a step size, and a stop. For a dimmer (3.007) up is "increase"
	 * (control bit 1), for a blind (3.008) up is "up" (control bit 0). A stop is the step code 0 with
	 * the control bit 0, as Calimero writes a break.
	 */
	private void checkStep() {
		Map<String, Object> target = inLocale(Locale.ENGLISH, () -> _monitor.target(ADDRESS, "3.007"));
		assertEquals("step", target.get("kind"));
		List<?> steps = (List<?>) target.get("steps");
		assertEquals(7, steps.size());
		assertTrue(steps.get(0).toString(), steps.get(0).toString().startsWith("1: 100"));
		assertTrue(steps.get(6).toString(), steps.get(6).toString().startsWith("7: 1"));
		Map<String, Object> blinds = inLocale(Locale.ENGLISH, () -> _monitor.target(ADDRESS, "3.008"));
		assertEquals("step", blinds.get("kind"));
		assertContains("\"Up\" sends \"up\" (control bit 0), \"Down\" sends \"down\" (control bit 1).",
			blinds.get("label"));

		assertSent("00 89", step(DatapointCodec.UP, 1), "3.007");
		assertSent("00 87", step(DatapointCodec.DOWN, 7), "3.007");
		assertSent("00 80", Map.of(DatapointCodec.DIRECTION, DatapointCodec.STOP), "3.007");
		// The step chosen by its text.
		assertSent("00 8C", step(DatapointCodec.UP, steps.get(3)), "3.007");
		// A blind goes up with the control bit 0.
		assertSent("00 81", step(DatapointCodec.UP, 1), "3.008");
		assertSent("00 89", step(DatapointCodec.DOWN, 1), "3.008");

		assertRefused(Map.of(DatapointCodec.DIRECTION, DatapointCodec.UP), "3.007");
		assertRefused(step(DatapointCodec.UP, 0), "3.007");
		assertRefused(step(DatapointCodec.UP, 8), "3.007");
		assertRefused(step("sideways", 1), "3.007");
	}

	/**
	 * DPT 10: a time of day with the day of the week in the bits 7 to 5 of the first byte.
	 */
	private void checkTime() {
		Map<String, Object> target = _monitor.target(ADDRESS, "10.001");
		assertEquals("time", target.get("kind"));
		List<?> days = (List<?>) target.get("days");
		assertEquals(8, days.size());

		assertSent("00 80 4D 2D 1E", time(2, 13, 45, 30), "10.001");
		// The day chosen by its name.
		assertSent("00 80 4D 2D 1E", Map.of(DatapointCodec.DAY, days.get(2), DatapointCodec.TIME,
			time(13, 45, 30)), "10.001");
		// No day: the day 0.
		assertSent("00 80 0D 2D 1E", time(0, 13, 45, 30), "10.001");
		assertSent("00 80 0D 2D 1E", Map.of(DatapointCodec.DAY, days.get(0), DatapointCodec.TIME,
			time(13, 45, 30)), "10.001");
		assertSent("00 80 0D 2D 1E", time(13, 45, 30), "10.001");

		assertRefused(Map.of(DatapointCodec.DAY, "Someday", DatapointCodec.TIME, time(13, 45, 30)), "10.001");
		assertRefused(Map.of(DatapointCodec.DAY, Integer.valueOf(2)), "10.001");
	}

	/**
	 * DPT 11: a date, the year as offset of 2000.
	 */
	private void checkDate() {
		assertEquals("date", _monitor.target(ADDRESS, "11.001").get("kind"));
		assertSent("00 80 11 0A 1A", date(2026, 10, 17), "11.001");
		assertRefused(date(2100, 1, 1), "11.001");
	}

	/**
	 * DPT 19: a date with a time of day, from the same date and time picker; the day of the week is
	 * the one of the date.
	 */
	private void checkDateTime() {
		assertEquals("datetime", _monitor.target(ADDRESS, "19.001").get("kind"));
		Calendar calendar = calendar();
		calendar.set(2026, Calendar.OCTOBER, 17, 13, 45, 30);
		_monitor.send(ADDRESS, calendar.getTime(), "19.001");
		String sent = last();
		// Year 2026 - 1900, October, 17th, Saturday (6) at 13 h, 45 min, 30 s.
		assertTrue(sent, sent.startsWith(APDU + "00 80 7E 0A 11 CD 2D 1E "));
	}

	/**
	 * DPT 20: a choice of the named values of the enumeration; choosing one sends its number.
	 */
	private void checkChoice() {
		Map<String, Object> target = _monitor.target(ADDRESS, "20.102");
		assertEquals("choice", target.get("kind"));
		assertEquals(List.of("auto", "comfort", "standby", "economy", "building protection"), target.get("options"));
		assertSent("00 80 01", "comfort", "20.102");
		assertSent("00 80 04", "building protection", "20.102");
		assertSent("00 80 03", Integer.valueOf(3), "20.102");
		assertRefused("warm", "20.102");
		assertRefused(Integer.valueOf(9), "20.102");
	}

	/**
	 * The other kinds keep their inputs.
	 */
	private void checkUnchanged() {
		assertEquals("switch", _monitor.target(ADDRESS, "1.001").get("kind"));
		assertEquals("number", _monitor.target(ADDRESS, "9.001").get("kind"));
		assertEquals("text", _monitor.target(ADDRESS, "16.000").get("kind"));
		assertEquals(List.of(), _monitor.target(ADDRESS, "9.001").get("options"));
		assertNull(_monitor.target(ADDRESS, "9.001").get("steps"));
	}

	/**
	 * What the send form shows the user is in the user's language: the words of the step
	 * directions, the units Calimero writes as words, the days of the week in their order, the day
	 * "Now" sets (haumacher/ehome #24, browser check).
	 */
	private void checkTexts() {
		Map<String, Object> dimmingDe = inLocale(Locale.GERMAN, () -> _monitor.target(ADDRESS, "3.007"));
		assertContains("„Auf“ sendet „heller“ (Steuerbit 1), „Ab“ sendet „dunkler“ (Steuerbit 0).",
			dimmingDe.get("label"));
		Map<String, Object> blindsDe = inLocale(Locale.GERMAN, () -> _monitor.target(ADDRESS, "3.008"));
		assertContains("„Auf“ sendet „auf“ (Steuerbit 0), „Ab“ sendet „ab“ (Steuerbit 1).", blindsDe.get("label"));
		assertContains("\"Up\" sends \"brighter\" (control bit 1), \"Down\" sends \"darker\" (control bit 0).",
			inLocale(Locale.ENGLISH, () -> _monitor.target(ADDRESS, "3.007")).get("label"));

		// A unit Calimero writes as a word is translated, or left out.
		Map<String, Object> counterDe = inLocale(Locale.GERMAN, () -> _monitor.target(ADDRESS, "5.010"));
		assertEquals("Impulse", counterDe.get("unit"));
		assertContains("Werte von 0 bis 255 Impulse.", counterDe.get("label"));
		Map<String, Object> counterEn = inLocale(Locale.ENGLISH, () -> _monitor.target(ADDRESS, "5.010"));
		assertEquals("Pulses", counterEn.get("unit"));
		assertContains("Values from 0 to 255 pulses.", counterEn.get("label"));
		Map<String, Object> ratio = inLocale(Locale.GERMAN, () -> _monitor.target(ADDRESS, "5.005"));
		assertEquals("", ratio.get("unit"));
		assertContains("Werte von 0 bis 255.", ratio.get("label"));
		assertEquals("Lüfterstufe", inLocale(Locale.GERMAN, () -> DatapointCodec.unit(new Dpt(5, 100))));
		assertEquals("Impulse", inLocale(Locale.GERMAN, () -> DatapointCodec.unit(new Dpt(7, 1))));
		assertEquals("", DatapointCodec.unit(new Dpt(11, 1)));
		assertEquals("", DatapointCodec.unit(new Dpt(10, 1)));
		// Symbols stay as they are.
		assertEquals("%", inLocale(Locale.GERMAN, () -> _monitor.target(ADDRESS, "5.001")).get("unit"));
		assertEquals("°C", DatapointCodec.unit(new Dpt(9, 1)));
		assertEquals("min", DatapointCodec.unit(new Dpt(7, 6)));
		assertEquals("ppm", DatapointCodec.unit(new Dpt(9, 8)));
		assertEquals("kVARh", DatapointCodec.unit(new Dpt(13, 15)));

		// The days of the week in their order, "no day" first.
		assertEquals(List.of("Kein Tag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag",
			"Sonntag"), inLocale(Locale.GERMAN, () -> _monitor.target(ADDRESS, "10.001")).get("days"));
		assertEquals(List.of("No day", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
			"Sunday"), inLocale(Locale.ENGLISH, () -> _monitor.target(ADDRESS, "10.001")).get("days"));

		// "Now" sets the day of the date it sets as time, in the user's time zone.
		Date saturday = date(2026, 10, 17);
		assertEquals("Samstag", inLocale(Locale.GERMAN, () -> DatapointCodec.day(saturday)));
		assertEquals("Saturday", inLocale(Locale.ENGLISH, () -> DatapointCodec.day(saturday)));
		Calendar lateSunday = calendar();
		lateSunday.set(2026, Calendar.OCTOBER, 18, 23, 59, 59);
		assertEquals("Sunday", inLocale(Locale.ENGLISH, () -> DatapointCodec.day(lateSunday.getTime())));
		// The day it names is one the form sends.
		String day = inLocale(Locale.GERMAN, () -> DatapointCodec.day(saturday));
		inLocale(Locale.GERMAN, () -> {
			_monitor.send(ADDRESS, Map.of(DatapointCodec.DAY, day, DatapointCodec.TIME, time(13, 45, 30)), "10.001");
			return null;
		});
		assertEquals(APDU + "00 80 CD 2D 1E", last());
	}

	private static void assertContains(String expected, Object text) {
		assertTrue(text + " does not contain: " + expected, String.valueOf(text).contains(expected));
	}

	/**
	 * Runs the given function with the given language as the one of the user.
	 */
	private static <T> T inLocale(Locale locale, Supplier<T> function) {
		TLContext context = TLContext.getContext();
		Locale before = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return function.get();
		} finally {
			context.setCurrentLocale(before);
		}
	}

	private void assertSent(String apdu, Object value, String type) {
		_monitor.send(ADDRESS, value, type);
		assertEquals(type + " " + value, APDU + apdu, last());
	}

	private void assertRefused(Object value, String type) {
		int count = _link.getSent().size();
		try {
			_monitor.send(ADDRESS, value, type);
			fail("Value must be refused for " + type + ": " + value);
		} catch (TopLogicException ex) {
			assertNotNull(ex.getErrorKey());
		}
		assertEquals(count, _link.getSent().size());
	}

	private String last() {
		List<String> sent = _link.getSent();
		return sent.get(sent.size() - 1);
	}

	private static Map<String, Object> step(String direction, Object step) {
		return Map.of(DatapointCodec.DIRECTION, direction, DatapointCodec.STEP, step);
	}

	private static Map<String, Object> time(int day, int hour, int minute, int second) {
		return Map.of(DatapointCodec.DAY, Integer.valueOf(day), DatapointCodec.TIME, time(hour, minute, second));
	}

	/**
	 * A time of day as the time picker writes it: on 1970-01-01 in the user's time zone.
	 */
	private static Date time(int hour, int minute, int second) {
		Calendar calendar = calendar();
		calendar.set(1970, Calendar.JANUARY, 1, hour, minute, second);
		return calendar.getTime();
	}

	private static Date date(int year, int month, int day) {
		Calendar calendar = calendar();
		calendar.set(year, month - 1, day, 0, 0, 0);
		return calendar.getTime();
	}

	private static Calendar calendar() {
		Calendar calendar = Calendar.getInstance(ThreadContext.getTimeZone());
		calendar.clear();
		return calendar;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupMonitorInputs.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
