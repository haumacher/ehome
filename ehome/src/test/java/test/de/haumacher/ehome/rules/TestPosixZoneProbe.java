/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.PosixZone;

/**
 * Probe of the POSIX time zones of the rule engine (haumacher/ehome #44) against the time zone
 * database of the JDK: for zones of both hemispheres, the offset agrees every 15 minutes from 2026 to
 * 2031, transitions included.
 */
@SuppressWarnings("javadoc")
public class TestPosixZoneProbe extends TestCase {

	private static final Map<String, String> ZONES = Map.of(
		"CET-1CEST,M3.5.0,M10.5.0/3", "Europe/Berlin",
		"GMT0BST,M3.5.0/1,M10.5.0", "Europe/London",
		"EST5EDT,M3.2.0,M11.1.0", "America/New_York",
		"AEST-10AEDT,M10.1.0,M4.1.0/3", "Australia/Sydney",
		"NZST-12NZDT,M9.5.0,M4.1.0/3", "Pacific/Auckland",
		"<-03>3", "America/Sao_Paulo");

	public void testAgainstJdk() {
		long from = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
		long to = Instant.parse("2031-01-01T00:00:00Z").getEpochSecond();
		for (Map.Entry<String, String> entry : ZONES.entrySet()) {
			PosixZone zone = PosixZone.parse(entry.getKey());
			ZoneId reference = ZoneId.of(entry.getValue());
			for (long t = from; t < to; t += 900) {
				int expected = reference.getRules().getOffset(Instant.ofEpochSecond(t)).getTotalSeconds();
				assertEquals(entry.getKey() + " at " + Instant.ofEpochSecond(t), expected, zone.offset(t));
			}
		}
	}

	public static Test suite() {
		return new TestSuite(TestPosixZoneProbe.class);
	}

}
