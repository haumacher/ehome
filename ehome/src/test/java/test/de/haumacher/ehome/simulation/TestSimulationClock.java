/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import test.de.haumacher.ehome.monitor.MockBusLink;

import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Entry;
import de.haumacher.ehome.simulation.Simulation.Kind;

/**
 * The clock of a {@link Simulation} running by itself (haumacher/ehome #62) and the notification of
 * its changes, on a wall clock the test moves (nothing sleeps for the time of the rules).
 *
 * <ul>
 * <li>At 60 times real time, a second of the wall clock is a minute of the simulation; the timer of
 * a rule (90 s) fires on the way at its own time. At 10 times, half a second is 5 s. Paused, the
 * clock stays. In real time, a fast-forward by hand moves the clock, and it runs on from
 * there.</li>
 * <li>The listener is told about every change: an entry of the timeline, a telegram received, the
 * clock moving by itself.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestSimulationClock extends TestCase {

	/** Monday 2026-10-19 08:00 UTC. */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 8 * 3600;

	private static final String IMAGE = "{\"settings\": {\"tz\": \"UTC0\"},"
		+ " \"datapoints\": [{\"id\": \"flag\", \"type\": \"boolean\", \"writable\": true},"
		+ "  {\"id\": \"light\", \"type\": \"boolean\", \"knx\": {\"dpt\": \"1.001\", \"send\": \"1/1/1\"}}],"
		+ " \"rules\": [{\"id\": \"delay\", \"name\": \"Delay\","
		+ "  \"timers\": [{\"id\": \"t\", \"duration\": {\"kind\": \"literal\", \"type\": \"duration\", \"value\": 90000}}],"
		+ "  \"reactions\": ["
		+ "   {\"triggers\": [{\"kind\": \"start\"}], \"actions\": [{\"kind\": \"startTimer\", \"timer\": \"t\"}]},"
		+ "   {\"triggers\": [{\"kind\": \"timer\", \"timer\": \"t\"}],"
		+ "    \"actions\": [{\"kind\": \"write\", \"datapoint\": \"flag\", \"value\": {\"kind\": \"literal\", \"type\": \"boolean\", \"value\": true}}]}"
		+ "  ]}]}";

	public void testRealTime() throws Exception {
		AtomicLong wall = new AtomicLong(1_000_000);
		AtomicInteger changes = new AtomicInteger();
		MockBusLink link = new MockBusLink();
		try (Simulation simulation =
			new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, new ParameterStore(), wall::get)) {
			simulation.connect(link);
			assertNull(simulation.start());
			simulation.setListener(changes::incrementAndGet);
			assertEquals(0, simulation.speed());

			simulation.run(60);
			assertEquals(60, simulation.speed());
			wall.addAndGet(1000);
			simulation.tick();
			assertEquals(60_000, simulation.now());
			assertFalse(simulation.value("flag").has());
			wall.addAndGet(1000);
			simulation.tick();
			assertEquals(120_000, simulation.now());
			assertEquals(Val.ofBool(true), simulation.value("flag"));
			assertEquals(90_000, command(simulation).ms());

			simulation.run(10);
			wall.addAndGet(500);
			simulation.tick();
			assertEquals(125_000, simulation.now());

			simulation.pause();
			assertEquals(0, simulation.speed());
			wall.addAndGet(5000);
			simulation.tick();
			assertEquals(125_000, simulation.now());

			simulation.run(1);
			wall.addAndGet(2500);
			simulation.tick();
			assertEquals(127_500, simulation.now());
			simulation.advance(1000);
			assertEquals(128_500, simulation.now());
			wall.addAndGet(500);
			simulation.tick();
			assertEquals(129_000, simulation.now());
			assertEquals("Mon 2026-10-19 08:02:09.000", simulation.timeText(simulation.now()));

			// The clock moving by itself is a change.
			int before = changes.get();
			wall.addAndGet(100);
			simulation.tick();
			assertTrue(changes.get() > before);

			// A telegram received is a change.
			simulation.pause();
			before = changes.get();
			link.write(0x1101, 1 << 11 | 1 << 8 | 1, 0x01);
			assertTrue(changes.get() > before);
			assertEquals(Val.ofBool(true), simulation.value("light"));

			// Nothing happens: no change.
			before = changes.get();
			wall.addAndGet(1000);
			simulation.tick();
			assertEquals(before, changes.get());
		}
	}

	public void testSpeedRefused() throws Exception {
		try (Simulation simulation =
			new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, new ParameterStore(), () -> 0)) {
			simulation.connect(new MockBusLink());
			assertNull(simulation.start());
			try {
				simulation.run(0);
				fail("A clock does not run at speed 0.");
			} catch (IllegalArgumentException ex) {
				// Expected.
			}
		}
	}

	private static Entry command(Simulation simulation) {
		Entry result = null;
		for (Entry entry : simulation.timeline()) {
			if (entry.kind() == Kind.COMMAND && entry.source().equals("delay")) {
				assertNull("One command only.", result);
				result = entry;
			}
		}
		assertNotNull(result);
		return result;
	}

	public static Test suite() {
		return new TestSuite(TestSimulationClock.class);
	}

}
