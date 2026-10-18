/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import test.de.haumacher.ehome.monitor.MockBusLink;

import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.Simulation;

/**
 * Probe of the clock running by itself (#62) composed with the time master and a repeating rule
 * timer: running at 60 times real time over a summer time change and midnight, in uneven wall clock
 * ticks, sends the same telegrams as fast-forwarding by hand over the same span.
 */
@SuppressWarnings("javadoc")
public class TestSimulationClockProbe extends TestCase {

	/** Saturday 2026-10-24 22:55 UTC, Sunday 00:55 CEST; summer time ends at 01:00 UTC. */
	private static final long START = Values.daysOf(2026, 10, 24) * 86400 + 22 * 3600 + 55 * 60;

	private static final long SPAN = 3 * 3600 * 1000L;

	private static final String IMAGE = "{\"settings\": {\"tz\": \"CET-1CEST,M3.5.0,M10.5.0/3\"},"
		+ " \"knx\": {\"timeMaster\": {\"time\": \"0/7/1\", \"date\": \"0/7/2\", \"dateTime\": \"0/7/3\", \"interval\": 1800}},"
		+ " \"datapoints\": [{\"id\": \"pulse\", \"type\": \"boolean\", \"writable\": true, \"knx\": {\"dpt\": \"1.001\", \"send\": \"1/1/1\"}}],"
		+ " \"rules\": [{\"id\": \"blink\", \"name\": \"Blink\","
		+ "  \"timers\": [{\"id\": \"t\", \"duration\": {\"kind\": \"literal\", \"type\": \"duration\", \"value\": 1700000}}],"
		+ "  \"reactions\": ["
		+ "   {\"triggers\": [{\"kind\": \"start\"}], \"actions\": [{\"kind\": \"startTimer\", \"timer\": \"t\"}]},"
		+ "   {\"triggers\": [{\"kind\": \"timer\", \"timer\": \"t\"}],"
		+ "    \"actions\": [{\"kind\": \"write\", \"datapoint\": \"pulse\", \"value\": {\"kind\": \"literal\", \"type\": \"boolean\", \"value\": true}},"
		+ "     {\"kind\": \"startTimer\", \"timer\": \"t\"}]}"
		+ "  ]}]}";

	public void testRealTimeEqualsFastForward() throws Exception {
		List<String> byHand = run(null, simulation -> simulation.advance(SPAN));
		AtomicLong wall = new AtomicLong(5_000_000);
		List<String> running = run(wall, simulation -> {
			simulation.run(60);
			long[] steps = { 7, 61_000, 1, 33_333, 59_999, 12_345 };
			long done = 0;
			int n = 0;
			while (done < SPAN / 60) {
				long step = Math.min(steps[n++ % steps.length], SPAN / 60 - done);
				wall.addAndGet(step);
				done += step;
				simulation.tick();
			}
			simulation.pause();
		});
		assertTrue(byHand.toString(), byHand.size() > 10);
		assertEquals(byHand, running);
	}

	private static List<String> run(AtomicLong wall, Consumer<Simulation> drive) throws Exception {
		MockBusLink link = new MockBusLink();
		Map<?, ?> image = (Map<?, ?>) Json.parse(IMAGE, 100);
		try (Simulation simulation = wall == null ? new Simulation(image, START, new ParameterStore())
			: new Simulation(image, START, new ParameterStore(), wall::get)) {
			simulation.connect(link);
			assertNull(simulation.start());
			drive.accept(simulation);
			simulation.awaitSent();
			List<String> result = new ArrayList<>(link.getSent());
			result.add("now " + simulation.now());
			return result;
		}
	}

	public static Test suite() {
		return new TestSuite(TestSimulationClockProbe.class);
	}

}
