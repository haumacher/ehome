/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.Astro;
import de.haumacher.ehome.rules.Astro.GeoLocation;
import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.Values;

/**
 * {@link Astro}, the port of the controller's sun calculator, against the references its own test
 * (<code>core/test/astro_test.cpp</code>) uses: the US Naval Observatory's events, rounded to the
 * minute, within 60 s.
 */
@SuppressWarnings("javadoc")
public class TestAstro extends TestCase {

	private static long utc(int year, int month, int day, int hour, int minute, int dayShift) {
		return (Values.daysOf(year, month, day) + dayShift) * 86400 + hour * 3600 + minute * 60;
	}

	private static long utc(int year, int month, int day, int hour, int minute) {
		return utc(year, month, day, hour, minute, 0);
	}

	/** Dawn, sunrise, sunset, dusk (UTC seconds), 0 for an event that does not occur. */
	private static void check(String place, double latitude, double longitude, int year, int month, int day,
			long... events) {
		GeoLocation where = new GeoLocation(latitude, longitude, 0);
		SunEvent[] order = { SunEvent.DAWN, SunEvent.SUNRISE, SunEvent.SUNSET, SunEvent.DUSK };
		for (int i = 0; i < order.length; i++) {
			Long at = Astro.sunTime(year, month, day, where, order[i]);
			String what = place + " " + year + "-" + month + "-" + day + " " + order[i].externalName();
			if (events[i] == 0) {
				assertNull(what, at);
				continue;
			}
			assertNotNull(what, at);
			long difference = at.longValue() - events[i];
			assertTrue(what + ": difference " + difference + " s", Math.abs(difference) <= 60);
		}
	}

	public void testUsnoReferences() {
		check("Greenwich", 51.4769, 0.0, 2026, 3, 20, utc(2026, 3, 20, 5, 30), utc(2026, 3, 20, 6, 3),
			utc(2026, 3, 20, 18, 13), utc(2026, 3, 20, 18, 46));
		check("Greenwich", 51.4769, 0.0, 2026, 9, 23, utc(2026, 9, 23, 5, 14), utc(2026, 9, 23, 5, 48),
			utc(2026, 9, 23, 17, 56), utc(2026, 9, 23, 18, 29));
		check("Munich", 48.137, 11.575, 2026, 6, 21, utc(2026, 6, 21, 2, 32), utc(2026, 6, 21, 3, 13),
			utc(2026, 6, 21, 19, 18), utc(2026, 6, 21, 19, 59));
		check("Munich", 48.137, 11.575, 2026, 12, 21, utc(2026, 12, 21, 6, 25), utc(2026, 12, 21, 7, 1),
			utc(2026, 12, 21, 15, 22), utc(2026, 12, 21, 15, 59));
		check("Helsinki", 60.17, 24.94, 2026, 6, 21, utc(2026, 6, 21, 23, 2, -1), utc(2026, 6, 21, 0, 54),
			utc(2026, 6, 21, 19, 50), utc(2026, 6, 21, 21, 42));
		check("Helsinki", 60.17, 24.94, 2026, 12, 21, utc(2026, 12, 21, 6, 25), utc(2026, 12, 21, 7, 24),
			utc(2026, 12, 21, 13, 13), utc(2026, 12, 21, 14, 11));
		check("Sydney", -33.87, 151.21, 2026, 6, 21, utc(2026, 6, 21, 20, 32, -1), utc(2026, 6, 21, 21, 0, -1),
			utc(2026, 6, 21, 6, 54), utc(2026, 6, 21, 7, 22));
		check("Sydney", -33.87, 151.21, 2026, 12, 21, utc(2026, 12, 21, 18, 11, -1), utc(2026, 12, 21, 18, 41, -1),
			utc(2026, 12, 21, 9, 5), utc(2026, 12, 21, 9, 35));
		check("Buenos Aires", -34.6, -58.38, 2026, 3, 20, utc(2026, 3, 20, 9, 32), utc(2026, 3, 20, 9, 57),
			utc(2026, 3, 20, 22, 5), utc(2026, 3, 20, 22, 30));
		check("Quito", -0.18, -78.47, 2026, 1, 15, utc(2026, 1, 15, 10, 57), utc(2026, 1, 15, 11, 19),
			utc(2026, 1, 15, 23, 27), utc(2026, 1, 15, 23, 49));
		check("New York", 40.71, -74.01, 2026, 7, 4, utc(2026, 7, 4, 8, 57), utc(2026, 7, 4, 9, 30),
			utc(2026, 7, 4, 0, 30, 1), utc(2026, 7, 4, 1, 4, 1));
		check("Berlin", 52.52, 13.405, 2026, 3, 29, utc(2026, 3, 29, 4, 14), utc(2026, 3, 29, 4, 48),
			utc(2026, 3, 29, 17, 35), utc(2026, 3, 29, 18, 10));
		check("Tromso", 69.65, 18.96, 2026, 6, 21, 0, 0, 0, 0);
		check("Tromso", 69.65, 18.96, 2026, 12, 21, utc(2026, 12, 21, 8, 31), 0, 0, utc(2026, 12, 21, 12, 53));
	}

	public void testPolarDayAndNight() {
		GeoLocation pole = new GeoLocation(90, 0, 0);
		for (SunEvent event : SunEvent.values()) {
			assertNull(Astro.sunTime(2026, 6, 21, pole, event));
			assertNull(Astro.sunTime(2026, 12, 21, pole, event));
		}
		GeoLocation tromso = new GeoLocation(69.65, 18.96, 0);
		assertNull(Astro.sunTime(2026, 5, 25, tromso, SunEvent.SUNSET));
		assertNotNull(Astro.sunTime(2026, 1, 25, tromso, SunEvent.SUNRISE));
	}

	public void testElevation() {
		GeoLocation ground = new GeoLocation(47.42, 10.98, 0);
		GeoLocation summit = new GeoLocation(47.42, 10.98, 2000);
		long lowRise = Astro.sunTime(2026, 6, 21, ground, SunEvent.SUNRISE).longValue();
		long highRise = Astro.sunTime(2026, 6, 21, summit, SunEvent.SUNRISE).longValue();
		long lowSet = Astro.sunTime(2026, 6, 21, ground, SunEvent.SUNSET).longValue();
		long highSet = Astro.sunTime(2026, 6, 21, summit, SunEvent.SUNSET).longValue();
		assertTrue(lowRise - highRise > 5 * 60);
		assertTrue(highSet - lowSet > 5 * 60);
		assertEquals(Astro.sunTime(2026, 6, 21, ground, SunEvent.DAWN), Astro.sunTime(2026, 6, 21, summit, SunEvent.DAWN));
		assertNull(Astro.sunTime(2026, 6, 21, new GeoLocation(0, 0, 10000), SunEvent.SUNRISE));
	}

	public static Test suite() {
		return new TestSuite(TestAstro.class);
	}

}
