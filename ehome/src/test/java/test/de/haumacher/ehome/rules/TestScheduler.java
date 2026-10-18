/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.Astro;
import de.haumacher.ehome.rules.Astro.GeoLocation;
import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.PosixZone;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Schedule.ClockTime;
import de.haumacher.ehome.rules.Scheduler;
import de.haumacher.ehome.rules.Scheduler.RefusedException;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.Date;
import de.haumacher.ehome.rules.Values;

/**
 * The {@link Scheduler} on a scripted wall clock in central European time with the EU rules
 * (<code>CET-1CEST,M3.5.0,M10.5.0/3</code>): the cases of the controller's own scheduler test
 * (<code>core/test/scheduler_test.cpp</code>) that pin the calendar, the DST rules of
 * <code>spec/schedules.md</code> S1, missed firings, the enable switch and the output datapoint.
 */
@SuppressWarnings("javadoc")
public class TestScheduler extends TestCase {

	private static final int WORKDAYS = 0x1F;

	private static final int MON_WED_FRI = 0x01 | 0x04 | 0x10;

	private static long utc(int year, int month, int day, int hour, int minute, int second) {
		return Values.daysOf(year, month, day) * 86400 + hour * 3600 + minute * 60 + second;
	}

	private static long cet(int year, int month, int day, int hour, int minute) {
		return cet(year, month, day, hour, minute, 0);
	}

	private static long cet(int year, int month, int day, int hour, int minute, int second) {
		return utc(year, month, day, hour, minute, second) - 3600;
	}

	private static long cest(int year, int month, int day, int hour, int minute) {
		return cest(year, month, day, hour, minute, 0);
	}

	private static long cest(int year, int month, int day, int hour, int minute, int second) {
		return utc(year, month, day, hour, minute, second) - 7200;
	}

	private static ClockTime at(int hour, int minute) {
		return new ClockTime(hour, minute, 0);
	}

	/**
	 * The scheduler on a scripted clock: <code>runUntil()</code> drives it like the controller's
	 * event loop and time service do, with an evaluation at each local midnight.
	 */
	private static final class Rig implements Scheduler.TimeSource {
		final PosixZone _zone = PosixZone.parse("CET-1CEST,M3.5.0,M10.5.0/3");

		long _utc;

		boolean _synchronized = true;

		GeoLocation _location;

		final Map<String, String> _records = new HashMap<>();

		final List<String> _log = new ArrayList<>();

		final List<String> _firings = new ArrayList<>();

		DatapointModel _model = new DatapointModel(() -> 0);

		Scheduler _scheduler;

		Rig(long utc) {
			_utc = utc;
			boot();
		}

		@Override
		public Long nowUtc() {
			return _synchronized ? Long.valueOf(_utc) : null;
		}

		@Override
		public int utcOffset(long utc) {
			return _zone.offset(utc);
		}

		@Override
		public GeoLocation location() {
			return _location;
		}

		/** A fresh scheduler: a reboot, the records stay. */
		void boot() {
			_scheduler = new Scheduler(this, _records, _model, _log::add);
			_scheduler.addListener(id -> _firings.add(id + " " + _utc));
		}

		void load(Schedule... entries) throws RefusedException {
			_scheduler.load(List.of(entries));
		}

		private long nextMidnight() {
			Date today = _scheduler.localDateOf(_utc);
			Date tomorrow = Values.civilOf(today.days() + 1);
			long naive = tomorrow.days() * 86400;
			return naive - _zone.offset(naive - 7200);
		}

		void runUntil(long until) {
			while (true) {
				Long wakeup = _synchronized ? _scheduler.wakeup() : null;
				long midnight = nextMidnight();
				long next = wakeup == null ? Long.MAX_VALUE : Math.max(_utc, wakeup.longValue());
				if (Math.min(next, midnight) > until) {
					_utc = until;
					return;
				}
				if (midnight < next) {
					_utc = midnight;
					_scheduler.evaluate();
					continue;
				}
				_utc = next;
				_scheduler.fireDue();
			}
		}

		List<String> take() {
			List<String> result = new ArrayList<>(_firings);
			_firings.clear();
			return result;
		}

		long count(String text) {
			return _log.stream().filter(line -> line.contains(text)).count();
		}
	}

	private static String f(String id, long utc) {
		return id + " " + utc;
	}

	public void testWeeklyFiresOnItsDays() throws RefusedException {
		Rig rig = new Rig(cest(2026, 10, 11, 12, 0));
		rig.load(Schedule.weekly("mwf", MON_WED_FRI, at(7, 0)));
		assertEquals(Long.valueOf(cest(2026, 10, 12, 7, 0)), rig._scheduler.nextFiring(0));
		rig.runUntil(cest(2026, 10, 18, 23, 0));
		assertEquals(List.of(f("mwf", cest(2026, 10, 12, 7, 0)), f("mwf", cest(2026, 10, 14, 7, 0)),
			f("mwf", cest(2026, 10, 16, 7, 0))), rig.take());
		assertEquals(Long.valueOf(cest(2026, 10, 16, 7, 0)), rig._scheduler.lastFired(0));
	}

	public void testYearlyDatesAndOnce() throws RefusedException {
		Rig rig = new Rig(cet(2026, 12, 23, 12, 0));
		rig.load(Schedule.yearly("xmas", 12, 25, at(8, 0)),
			Schedule.dates("holiday", List.of(new Date(2027, 1, 6), new Date(2026, 12, 26), new Date(2026, 12, 24)),
				at(9, 30)),
			Schedule.once("party", new Date(2026, 12, 31), new ClockTime(23, 59, 30)));
		rig.runUntil(cet(2027, 1, 10, 0, 0));
		assertEquals(List.of(f("holiday", cet(2026, 12, 24, 9, 30)), f("xmas", cet(2026, 12, 25, 8, 0)),
			f("holiday", cet(2026, 12, 26, 9, 30)), f("party", cet(2026, 12, 31, 23, 59, 30)),
			f("holiday", cet(2027, 1, 6, 9, 30))), rig.take());
		assertEquals(Long.valueOf(cet(2027, 12, 25, 8, 0)), rig._scheduler.nextFiring(0));
		assertNull(rig._scheduler.nextFiring(1));
		assertNull(rig._scheduler.nextFiring(2));
	}

	public void testIntervalSlotsOnTheGivenDays() throws RefusedException {
		Rig rig = new Rig(cest(2026, 10, 16, 7, 0));
		rig.load(Schedule.interval("pump", WORKDAYS, 20, at(8, 0), at(9, 0)));
		rig.runUntil(cest(2026, 10, 19, 8, 30));
		assertEquals(List.of(f("pump", cest(2026, 10, 16, 8, 0)), f("pump", cest(2026, 10, 16, 8, 20)),
			f("pump", cest(2026, 10, 16, 8, 40)), f("pump", cest(2026, 10, 16, 9, 0)), f("pump", cest(2026, 10, 19, 8, 0)),
			f("pump", cest(2026, 10, 19, 8, 20))), rig.take());
		rig.load(Schedule.interval("odd", Schedule.ALL_DAYS, 25, at(8, 0), at(9, 0)));
		assertEquals(Long.valueOf(cest(2026, 10, 19, 8, 50)), rig._scheduler.nextAfter(0, cest(2026, 10, 19, 8, 40)));
		assertEquals(Long.valueOf(cest(2026, 10, 20, 8, 0)), rig._scheduler.nextAfter(0, cest(2026, 10, 19, 8, 50)));
	}

	public void testAstroWithOffsetAndClampWindow() throws RefusedException {
		Rig rig = new Rig(cest(2026, 6, 20, 12, 0));
		rig._location = new GeoLocation(48.137, 11.575, 0);
		rig.load(Schedule.astro("dusk-30", SunEvent.SUNSET, -30 * 60, null, null),
			Schedule.astro("not-after", SunEvent.SUNSET, 0, null, at(21, 0)),
			Schedule.astro("not-before", SunEvent.SUNRISE, 0, at(6, 30), null),
			Schedule.astro("window", SunEvent.SUNRISE, 0, at(4, 0), at(7, 0)));
		long sunrise = Astro.sunTime(2026, 6, 21, rig._location, SunEvent.SUNRISE).longValue();
		long sunset = Astro.sunTime(2026, 6, 21, rig._location, SunEvent.SUNSET).longValue();
		Scheduler s = rig._scheduler;
		assertEquals(Long.valueOf(sunset - 1800), s.nextAfter(0, cest(2026, 6, 21, 0, 0)));
		assertEquals(Long.valueOf(cest(2026, 6, 21, 21, 0)), s.nextAfter(1, cest(2026, 6, 21, 0, 0)));
		assertEquals(Long.valueOf(cest(2026, 6, 21, 6, 30)), s.nextAfter(2, cest(2026, 6, 21, 0, 0)));
		assertEquals(Long.valueOf(sunrise), s.nextAfter(3, cest(2026, 6, 21, 0, 0)));
		long decemberRise = Astro.sunTime(2026, 12, 21, rig._location, SunEvent.SUNRISE).longValue();
		long decemberSet = Astro.sunTime(2026, 12, 21, rig._location, SunEvent.SUNSET).longValue();
		assertEquals(Long.valueOf(decemberSet), s.nextAfter(1, cet(2026, 12, 21, 0, 0)));
		assertEquals(Long.valueOf(decemberRise), s.nextAfter(2, cet(2026, 12, 21, 0, 0)));
		assertEquals(Long.valueOf(cet(2026, 12, 21, 7, 0)), s.nextAfter(3, cet(2026, 12, 21, 0, 0)));

		rig.runUntil(cest(2026, 6, 21, 0, 0));
		rig.take();
		rig.runUntil(cest(2026, 6, 21, 23, 59));
		assertEquals(List.of(f("window", sunrise), f("not-before", cest(2026, 6, 21, 6, 30)), f("dusk-30", sunset - 1800),
			f("not-after", cest(2026, 6, 21, 21, 0))), rig.take());
	}

	public void testAstroWithoutLocationIsRefusedUntilOneIsSet() throws RefusedException {
		Rig rig = new Rig(cest(2026, 6, 20, 12, 0));
		rig.load(Schedule.astro("eve", SunEvent.SUNSET, 0, null, null), Schedule.weekly("daily", Schedule.ALL_DAYS, at(7, 0)));
		assertEquals(1, rig.count("refused: astronomy needs a location"));
		assertNull(rig._scheduler.nextFiring(0));
		assertNotNull(rig._scheduler.nextFiring(1));
		rig._scheduler.evaluate();
		assertEquals(1, rig.count("refused: astronomy needs a location"));
		rig._location = new GeoLocation(48.137, 11.575, 0);
		rig._scheduler.evaluate();
		assertNotNull(rig._scheduler.nextFiring(0));
	}

	public void testPolarDayAndNightHaveNoAstroFirings() throws RefusedException {
		Rig rig = new Rig(cest(2026, 6, 15, 12, 0));
		rig._location = new GeoLocation(69.65, 18.96, 0);
		rig.load(Schedule.astro("set", SunEvent.SUNSET, 0, null, null), Schedule.astro("dusk", SunEvent.DUSK, 0, null, null));
		assertNull(rig._scheduler.nextFiring(0));
		assertNull(rig._scheduler.nextFiring(1));
		rig.runUntil(cest(2026, 6, 20, 12, 0));
		assertEquals(List.of(), rig.take());
		assertNotNull(rig._scheduler.nextAfter(0, cest(2026, 9, 1, 0, 0)));
		assertNull(rig._scheduler.nextAfter(0, cet(2026, 12, 18, 0, 0)));
		assertNotNull(rig._scheduler.nextAfter(1, cet(2026, 12, 18, 0, 0)));
	}

	public void testSpringForwardFiresInTheGapOnceAtTheEndOfTheGap() throws RefusedException {
		Rig rig = new Rig(cet(2026, 3, 27, 12, 0));
		rig.load(Schedule.weekly("half-past-two", Schedule.ALL_DAYS, at(2, 30)),
			Schedule.weekly("three", Schedule.ALL_DAYS, at(3, 0)),
			Schedule.interval("quarter", Schedule.ALL_DAYS, 15, at(1, 30), at(3, 15)));
		rig.runUntil(cest(2026, 3, 30, 12, 0));
		List<String> got = rig.take();
		assertEquals(List.of(f("half-past-two", cet(2026, 3, 28, 2, 30)), f("half-past-two", cest(2026, 3, 29, 3, 0)),
			f("half-past-two", cest(2026, 3, 30, 2, 30))), got.stream().filter(x -> x.startsWith("half-past-two")).toList());
		assertEquals(List.of(f("three", cet(2026, 3, 28, 3, 0)), f("three", cest(2026, 3, 29, 3, 0)),
			f("three", cest(2026, 3, 30, 3, 0))), got.stream().filter(x -> x.startsWith("three")).toList());
		List<String> sunday = got.stream().filter(x -> x.startsWith("quarter")).filter(x -> {
			long at = Long.parseLong(x.substring("quarter ".length()));
			return at >= cet(2026, 3, 29, 0, 0) && at < cest(2026, 3, 30, 0, 0);
		}).toList();
		assertEquals(List.of(f("quarter", cet(2026, 3, 29, 1, 30)), f("quarter", cet(2026, 3, 29, 1, 45)),
			f("quarter", cest(2026, 3, 29, 3, 0)), f("quarter", cest(2026, 3, 29, 3, 15))), sunday);
	}

	public void testFallBackFiresOnceNotTwice() throws RefusedException {
		Rig rig = new Rig(cest(2026, 10, 24, 12, 0));
		rig.load(Schedule.weekly("half-past-two", Schedule.ALL_DAYS, at(2, 30)),
			Schedule.interval("half-hourly", Schedule.ALL_DAYS, 30, at(2, 0), at(3, 0)));
		rig.runUntil(cet(2026, 10, 26, 12, 0));
		assertEquals(List.of(f("half-hourly", cest(2026, 10, 25, 2, 0)), f("half-past-two", cest(2026, 10, 25, 2, 30)),
			f("half-hourly", cest(2026, 10, 25, 2, 30)), f("half-hourly", cet(2026, 10, 25, 3, 0)),
			f("half-hourly", cet(2026, 10, 26, 2, 0)), f("half-past-two", cet(2026, 10, 26, 2, 30)),
			f("half-hourly", cet(2026, 10, 26, 2, 30)), f("half-hourly", cet(2026, 10, 26, 3, 0))), rig.take());
	}

	public void testNextFiringAcrossMonthAndYearEnds() throws RefusedException {
		Rig rig = new Rig(cet(2026, 12, 1, 12, 0));
		rig.load(Schedule.weekly("monday", 0x01, at(8, 0)), Schedule.yearly("newyear", 1, 1, at(0, 0)),
			Schedule.yearly("leap", 2, 29, at(12, 0)),
			Schedule.dates("list", List.of(new Date(2027, 3, 1), new Date(2026, 12, 31)), at(18, 0)),
			Schedule.interval("workday", WORKDAYS, 60, at(8, 0), at(17, 0)),
			Schedule.yearly("silvester", 12, 31, new ClockTime(23, 59, 59)));
		Scheduler s = rig._scheduler;
		assertEquals(Long.valueOf(cet(2027, 1, 4, 8, 0)), s.nextAfter(0, cet(2026, 12, 30, 12, 0)));
		assertEquals(Long.valueOf(cet(2026, 11, 2, 8, 0)), s.nextAfter(0, cest(2026, 10, 27, 12, 0)));
		assertEquals(Long.valueOf(cet(2027, 1, 1, 0, 0)), s.nextAfter(1, cet(2026, 12, 31, 23, 59, 59)));
		assertEquals(Long.valueOf(cet(2028, 1, 1, 0, 0)), s.nextAfter(1, cet(2027, 1, 1, 0, 0)));
		assertEquals(Long.valueOf(cet(2028, 2, 29, 12, 0)), s.nextAfter(2, cet(2026, 3, 1, 0, 0)));
		assertEquals(Long.valueOf(cet(2032, 2, 29, 12, 0)), s.nextAfter(2, cet(2028, 2, 29, 12, 0)));
		assertEquals(Long.valueOf(cet(2026, 12, 31, 18, 0)), s.nextAfter(3, cet(2026, 12, 1, 0, 0)));
		assertEquals(Long.valueOf(cet(2027, 3, 1, 18, 0)), s.nextAfter(3, cet(2026, 12, 31, 18, 0)));
		assertNull(s.nextAfter(3, cet(2027, 3, 1, 18, 0)));
		assertEquals(Long.valueOf(cet(2026, 11, 2, 8, 0)), s.nextAfter(4, cet(2026, 10, 30, 17, 0)));
		assertEquals(Long.valueOf(cet(2026, 12, 31, 17, 0)), s.nextAfter(4, cet(2026, 12, 31, 16, 30)));
		assertEquals(Long.valueOf(cet(2027, 1, 1, 8, 0)), s.nextAfter(4, cet(2026, 12, 31, 17, 0)));
		assertEquals(Long.valueOf(cet(2026, 12, 31, 23, 59, 59)), s.nextAfter(5, cet(2026, 12, 31, 23, 59, 58)));
		assertEquals(Long.valueOf(cet(2027, 12, 31, 23, 59, 59)), s.nextAfter(5, cet(2026, 12, 31, 23, 59, 59)));
		assertEquals(Long.valueOf(cet(2026, 12, 7, 8, 0)), s.nextFiring(0));
		assertEquals(Long.valueOf(cet(2028, 2, 29, 12, 0)), s.nextFiring(2));
	}

	public void testCatchUpVersusSkipAcrossAReboot() throws RefusedException {
		Rig rig = new Rig(cest(2026, 10, 12, 6, 0));
		Schedule skip = Schedule.weekly("skip", Schedule.ALL_DAYS, at(7, 0));
		Schedule catchUp = Schedule.weekly("catch", Schedule.ALL_DAYS, at(7, 0)).setCatchUp(true);
		rig.load(skip, catchUp);
		rig.runUntil(cest(2026, 10, 13, 6, 0));
		assertEquals(List.of(f("skip", cest(2026, 10, 12, 7, 0)), f("catch", cest(2026, 10, 12, 7, 0))), rig.take());

		// Power off from 06:00 to 09:30 on the 13th: the 07:00 firing falls into the gap.
		rig._utc = cest(2026, 10, 13, 9, 30);
		rig.boot();
		rig.load(skip, catchUp);
		assertEquals(List.of(), rig.take());
		rig.runUntil(cest(2026, 10, 13, 9, 31));
		assertEquals(List.of(f("catch", cest(2026, 10, 13, 9, 30))), rig.take());
		assertEquals(1, rig.count("schedule skip missed its firing of 2026-10-13 07:00:00 (UTC+02:00); skipped"));
		assertEquals(1, rig.count("schedule catch missed its firing of 2026-10-13 07:00:00 (UTC+02:00); catching up once"));

		// Another reboot soon after: nothing is repeated.
		rig.boot();
		rig.load(skip, catchUp);
		long misses = rig.count("missed its firing");
		rig.runUntil(cest(2026, 10, 14, 6, 59));
		assertEquals(List.of(), rig.take());
		assertEquals(misses, rig.count("missed its firing"));
		rig.runUntil(cest(2026, 10, 14, 7, 1));
		assertEquals(List.of(f("skip", cest(2026, 10, 14, 7, 0)), f("catch", cest(2026, 10, 14, 7, 0))), rig.take());

		// A long outage: one catch-up, one log line naming the range.
		rig._utc = cest(2026, 10, 17, 12, 0);
		rig.boot();
		rig.load(skip, catchUp);
		rig.runUntil(cest(2026, 10, 17, 12, 1));
		assertEquals(List.of(f("catch", cest(2026, 10, 17, 12, 0))), rig.take());
		assertEquals(1, rig.count("schedule skip missed its firings from 2026-10-15 07:00:00 (UTC+02:00) to "
			+ "2026-10-17 07:00:00 (UTC+02:00); skipped"));
	}

	public void testClockJumpsForwardAndBack() throws RefusedException {
		Rig rig = new Rig(cest(2026, 10, 12, 6, 0));
		rig.load(Schedule.weekly("skip", Schedule.ALL_DAYS, at(7, 0)),
			Schedule.weekly("catch", Schedule.ALL_DAYS, at(7, 30)).setCatchUp(true));
		rig.runUntil(cest(2026, 10, 12, 6, 30));
		rig._utc = cest(2026, 10, 12, 8, 30);
		rig._scheduler.evaluate();
		rig.runUntil(cest(2026, 10, 12, 8, 31));
		assertEquals(List.of(f("catch", cest(2026, 10, 12, 8, 30))), rig.take());
		assertEquals(1, rig.count("schedule skip missed its firing of 2026-10-12 07:00:00 (UTC+02:00); skipped"));
		rig._utc = cest(2026, 10, 12, 6, 31);
		rig._scheduler.evaluate();
		rig.runUntil(cest(2026, 10, 12, 23, 0));
		assertEquals(List.of(), rig.take());
		rig.runUntil(cest(2026, 10, 13, 8, 0));
		assertEquals(List.of(f("skip", cest(2026, 10, 13, 7, 0)), f("catch", cest(2026, 10, 13, 7, 30))), rig.take());
	}

	public void testWaitsForASynchronizedClock() throws RefusedException {
		Rig rig = new Rig(1000);
		rig._synchronized = false;
		rig.load(Schedule.weekly("daily", Schedule.ALL_DAYS, at(7, 0)));
		assertEquals(1, rig.count("schedules wait for a synchronized wall clock"));
		assertNull(rig._scheduler.nextFiring(0));
		rig._synchronized = true;
		rig._utc = cest(2026, 10, 12, 9, 0);
		rig._scheduler.evaluate();
		assertEquals(Long.valueOf(cest(2026, 10, 13, 7, 0)), rig._scheduler.nextFiring(0));
		rig.runUntil(cest(2026, 10, 13, 7, 0));
		assertEquals(List.of(f("daily", cest(2026, 10, 13, 7, 0))), rig.take());
		assertEquals(0, rig.count("missed"));
	}

	public void testFiredTodayOutputDatapoint() throws RefusedException {
		Rig rig = new Rig(cest(2026, 10, 12, 12, 0));
		rig._model.add("evening.fired", de.haumacher.ehome.rules.ValueType.BOOLEAN, false, rig._scheduler);
		rig._model.add("number", de.haumacher.ehome.rules.ValueType.REAL, false, null);
		Schedule spec = Schedule.weekly("evening", Schedule.ALL_DAYS, at(19, 0)).setOutput("evening.fired");
		rig.load(spec);
		DatapointModel.Datapoint dp = rig._model.get("evening.fired");
		assertEquals(Val.ofBool(false), dp.value());
		rig.runUntil(cest(2026, 10, 12, 19, 0, 30));
		assertEquals(Val.ofBool(true), dp.value());
		rig.runUntil(cest(2026, 10, 12, 23, 59, 59));
		assertEquals(Val.ofBool(true), dp.value());
		rig.runUntil(cest(2026, 10, 13, 0, 0, 5));
		assertEquals(Val.ofBool(false), dp.value());
		assertEquals(DatapointModel.CommandStatus.NOT_WRITABLE, rig._model.command("evening.fired", Val.ofBool(true)));

		assertRefused("unknownReference", rig, spec.setOutput("nothing"));
		assertRefused("typeError", rig, spec.setOutput("number"));
		assertRefused("outputConflict", rig, spec.setOutput("evening.fired"),
			Schedule.weekly("twin", Schedule.ALL_DAYS, at(6, 0)).setOutput("evening.fired"));
		assertEquals(1, rig._scheduler.size());
	}

	private static void assertRefused(String reason, Rig rig, Schedule... entries) {
		try {
			rig.load(entries);
			fail("Expected refusal " + reason);
		} catch (RefusedException ex) {
			assertEquals(reason, ex.getReason());
		}
	}

	public void testEnableSwitchIsPersistedAndRespectsTheImage() throws RefusedException {
		Rig rig = new Rig(cest(2026, 10, 12, 6, 0));
		Schedule adjustable = Schedule.weekly("wake", Schedule.ALL_DAYS, at(7, 0));
		Schedule fixed = Schedule.weekly("fixed", Schedule.ALL_DAYS, at(7, 0)).setEnabled(true, false);
		rig.load(adjustable, fixed);
		assertEquals("the image fixes this schedule's enable switch", rig._scheduler.setEnabled("fixed", false));
		assertEquals("no such schedule", rig._scheduler.setEnabled("nope", false));
		assertNull(rig._scheduler.setEnabled("wake", false));
		assertNull(rig._scheduler.nextFiring(0));
		rig.runUntil(cest(2026, 10, 12, 8, 0));
		assertEquals(List.of(f("fixed", cest(2026, 10, 12, 7, 0))), rig.take());

		rig.boot();
		rig.load(adjustable, fixed);
		assertFalse(rig._scheduler.enabled(0));
		rig.runUntil(cest(2026, 10, 14, 12, 0));
		rig.take();
		assertNull(rig._scheduler.setEnabled("wake", true));
		rig.runUntil(cest(2026, 10, 14, 12, 1));
		assertEquals(List.of(), rig.take());
		assertEquals(0, rig.count("schedule wake missed"));
		assertNull(rig._scheduler.setEnabled("wake", false));

		rig.boot();
		rig.load(Schedule.weekly("wake", Schedule.ALL_DAYS, at(7, 0)).setEnabled(true, false), fixed);
		assertTrue(rig._scheduler.enabled(0));
		assertEquals(1, rig.count("schedule wake: enable override removed: the image fixes the switch"));
	}

	public void testIdleTimeIsNoGap() throws RefusedException {
		Rig rig = new Rig(cest(2026, 10, 12, 6, 0));
		rig.load(Schedule.weekly("wake", Schedule.ALL_DAYS, at(7, 0)).setCatchUp(true).setEnabled(false, true));
		assertEquals("1;;" + (cest(2026, 10, 12, 6, 0) - 1) + ";i;wake", rig._records.get(Scheduler.recordKey("wake")));
		rig.runUntil(cest(2026, 10, 14, 12, 0));
		assertEquals(List.of(), rig.take());
		rig._utc = cest(2026, 10, 15, 12, 0);
		rig.boot();
		rig.load(Schedule.weekly("wake", Schedule.ALL_DAYS, at(7, 0)).setCatchUp(true));
		rig.runUntil(cest(2026, 10, 15, 12, 1));
		assertEquals(List.of(), rig.take());
		assertEquals(0, rig.count("missed"));
		rig.runUntil(cest(2026, 10, 16, 7, 1));
		assertEquals(List.of(f("wake", cest(2026, 10, 16, 7, 0))), rig.take());
	}

	public void testPosixZone() {
		PosixZone zone = PosixZone.parse("CET-1CEST,M3.5.0,M10.5.0/3");
		assertEquals(3600, zone.offset(cet(2026, 3, 29, 1, 59, 59)));
		assertEquals(7200, zone.offset(cet(2026, 3, 29, 2, 0)));
		assertEquals(7200, zone.offset(cest(2026, 10, 25, 2, 59, 59)));
		assertEquals(3600, zone.offset(cest(2026, 10, 25, 3, 0)));
		PosixZone sydney = PosixZone.parse("AEST-10AEDT,M10.1.0,M4.1.0/3");
		assertEquals(11 * 3600, sydney.offset(utc(2026, 1, 15, 0, 0, 0)));
		assertEquals(10 * 3600, sydney.offset(utc(2026, 6, 15, 0, 0, 0)));
		assertEquals(-(3 * 3600 + 1800), PosixZone.parse("<-0330>3:30").offset(0));
		assertEquals(0, PosixZone.parse("UTC0").offset(0));
	}

	public static Test suite() {
		return new TestSuite(TestScheduler.class);
	}

}
