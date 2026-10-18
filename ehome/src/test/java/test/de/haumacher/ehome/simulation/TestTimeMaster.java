/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import test.de.haumacher.ehome.monitor.MockBusLink;

import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Entry;
import de.haumacher.ehome.simulation.Simulation.Kind;

/**
 * The time master of a runtime image in the simulation (haumacher/ehome #62,
 * {@link de.haumacher.ehome.simulation.TimeMaster}): the broadcasts of <code>knx.time</code> (0/7/1,
 * DPT 10.001), <code>knx.date</code> (0/7/2, 11.001) and <code>knx.datetime</code> (0/7/3, 19.001) on
 * the simulated clock, as homecontrol's <code>doc/knx.md</code> "Time master" specifies them.
 *
 * <ul>
 * <li>At the start (Wednesday 2026-10-14 12:05:00 CEST) and at the tick 12:10:00, the octets of the
 * spec's example (<code>7E 0A 0E 6C 0A 00 21 C0</code>); the time also reaches a datapoint listening
 * on its address; a read of the address is answered; a command is refused; a day of fast-forward
 * sends 144 broadcasts.</li>
 * <li>The end of summer time (2026-10-25): the broadcast at the transition shows 02:00:00 without the
 * summer-time flag; its beginning (2026-03-29): 03:00:00 with it.</li>
 * <li>An interval not dividing the day (50000 s) and no broadcast at the start: the broadcast at
 * midnight and the next tick, 12:40:00.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestTimeMaster extends TestCase {

	private static final String BERLIN = "CET-1CEST,M3.5.0,M10.5.0/3";

	private static final int TIME = 7 << 8 | 1;

	private static final int DATE = 7 << 8 | 2;

	private static final int DATE_TIME = 7 << 8 | 3;

	public void testBroadcasts() throws Exception {
		// Wednesday 2026-10-14 12:05:00 CEST.
		long start = Values.daysOf(2026, 10, 14) * 86400 + 10 * 3600 + 5 * 60;
		String image = "{\"settings\": {\"tz\": \"" + BERLIN + "\"},"
			+ " \"datapoints\": [{\"id\": \"clock\", \"type\": \"timeOfDay\", \"knx\": {\"dpt\": \"10.001\", \"listen\": [\"0/7/1\"]}}],"
			+ " \"knx\": {\"timeMaster\": {\"time\": \"0/7/1\", \"date\": \"0/7/2\", \"dateTime\": \"0/7/3\", \"interval\": 600}}}";
		MockBusLink link = new MockBusLink();
		try (Simulation simulation = simulation(image, start, link)) {
			assertEquals(List.of(TIME + ": 00 80 6C 05 00", DATE + ": 00 80 0E 0A 1A",
				DATE_TIME + ": 00 80 7E 0A 0E 6C 05 00 21 C0"), sent(simulation, link));
			assertEquals(Val.ofTime(new TimeOfDay(12, 5, 0, 3)), simulation.value("knx.time"));
			assertEquals(Val.ofTime(new TimeOfDay(12, 5, 0, 3)), simulation.value("clock"));
			assertEquals("the time master", simulation.owner("knx.time"));

			simulation.advance(5 * 60 * 1000);
			assertEquals(List.of(TIME + ": 00 80 6C 0A 00", DATE + ": 00 80 0E 0A 1A",
				DATE_TIME + ": 00 80 7E 0A 0E 6C 0A 00 21 C0"), sent(simulation, link));
			assertEquals(Val.ofTime(new TimeOfDay(12, 10, 0, 3)), simulation.value("clock"));
			List<Long> times = new ArrayList<>();
			for (Entry entry : simulation.timeline()) {
				if (entry.kind() == Kind.SENT && entry.source().equals("0/7/3")) {
					times.add(Long.valueOf(entry.ms()));
				}
			}
			assertEquals(List.of(Long.valueOf(0), Long.valueOf(300_000)), times);

			assertEquals(CommandStatus.NOT_WRITABLE, simulation.command("knx.time", Val.ofTime(new TimeOfDay(1, 2, 3, 4))));

			link.read(0x1101, TIME);
			assertEquals(List.of(TIME + ": 00 40 6C 0A 00"), sent(simulation, link));

			simulation.advance(86_400_000L);
			assertEquals(146, simulation.timeMaster().broadcasts());
			assertEquals(Val.ofTime(new TimeOfDay(12, 10, 0, 4)), simulation.value("knx.time"));
		}
	}

	public void testSummerTimeEnds() throws Exception {
		// Sunday 2026-10-25 02:55:00 CEST, 00:55 UTC; summer time ends at 01:00 UTC.
		long start = Values.daysOf(2026, 10, 25) * 86400 + 55 * 60;
		MockBusLink link = new MockBusLink();
		try (Simulation simulation = simulation(dateTimeImage(BERLIN, 3600), start, link)) {
			assertEquals(List.of(DATE_TIME + ": 00 80 7E 0A 19 E2 37 00 21 C0"), sent(simulation, link));
			simulation.advance(10 * 60 * 1000);
			assertEquals(List.of(DATE_TIME + ": 00 80 7E 0A 19 E2 00 00 20 C0"), sent(simulation, link));
			simulation.advance(60 * 60 * 1000);
			assertEquals(List.of(DATE_TIME + ": 00 80 7E 0A 19 E3 00 00 20 C0"), sent(simulation, link));
		}
	}

	public void testSummerTimeBegins() throws Exception {
		// Sunday 2026-03-29 01:55:00 CET, 00:55 UTC; summer time begins at 01:00 UTC.
		long start = Values.daysOf(2026, 3, 29) * 86400 + 55 * 60;
		MockBusLink link = new MockBusLink();
		try (Simulation simulation = simulation(dateTimeImage(BERLIN, 3600), start, link)) {
			assertEquals(List.of(DATE_TIME + ": 00 80 7E 03 1D E1 37 00 20 C0"), sent(simulation, link));
			simulation.advance(10 * 60 * 1000);
			assertEquals(List.of(DATE_TIME + ": 00 80 7E 03 1D E3 00 00 21 C0"), sent(simulation, link));
		}
	}

	public void testMidnight() throws Exception {
		// Monday 2026-10-19 23:50:00 UTC; the ticks of 50000 s fall on 12:40:00 on Tuesday.
		long start = Values.daysOf(2026, 10, 19) * 86400 + 23 * 3600 + 50 * 60;
		String image = "{\"settings\": {\"tz\": \"UTC0\"}, \"knx\": {\"timeMaster\": {\"time\": \"0/7/1\","
			+ " \"date\": \"0/7/2\", \"interval\": 50000, \"onSynchronized\": false}}}";
		MockBusLink link = new MockBusLink();
		try (Simulation simulation = simulation(image, start, link)) {
			assertEquals(List.of(), sent(simulation, link));
			simulation.advance(20 * 60 * 1000);
			assertEquals(List.of(TIME + ": 00 80 40 00 00", DATE + ": 00 80 14 0A 1A"), sent(simulation, link));
			simulation.advance((12 * 60 + 30) * 60 * 1000);
			assertEquals(List.of(TIME + ": 00 80 4C 28 00", DATE + ": 00 80 14 0A 1A"), sent(simulation, link));
			assertEquals(2, simulation.timeMaster().broadcasts());
		}
	}

	private static String dateTimeImage(String tz, int interval) {
		return "{\"settings\": {\"tz\": \"" + tz + "\"}, \"knx\": {\"timeMaster\": {\"dateTime\": \"0/7/3\","
			+ " \"interval\": " + interval + "}}}";
	}

	private static Simulation simulation(String image, long start, MockBusLink link) throws Exception {
		Simulation simulation = new Simulation((Map<?, ?>) Json.parse(image, 100), start, new ParameterStore());
		simulation.connect(link);
		assertNull(simulation.start());
		return simulation;
	}

	/**
	 * The telegrams sent since the last call.
	 */
	private static List<String> sent(Simulation simulation, MockBusLink link) throws InterruptedException {
		simulation.awaitSent();
		List<String> result = new ArrayList<>(link.getSent());
		link.getSent().clear();
		return result;
	}

	public static Test suite() {
		return new TestSuite(TestTimeMaster.class);
	}

}
