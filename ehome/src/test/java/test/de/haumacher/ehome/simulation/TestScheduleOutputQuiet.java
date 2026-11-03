/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Kind;

/**
 * The output datapoint of a schedule takes its value at the start quietly, as the controller does
 * (homecontrol #67, spec/rules.md 7.6): a rule triggered by the output does not react to the start;
 * the firing and the reset at midnight are events. The simulation loads the schedules before the
 * rules start, so their first values were never events for it; the port of the device's scheduler
 * makes them quiet as the device does (Scheduler#setQuietReports), and this test keeps it so.
 */
@SuppressWarnings("javadoc")
public class TestScheduleOutputQuiet extends TestCase {

	/** Monday 2026-10-19 08:00 UTC. */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 8 * 3600;

	private static final String IMAGE = "{\"settings\": {\"tz\": \"UTC0\"},"
		+ " \"datapoints\": [{\"id\": \"fired\", \"type\": \"boolean\", \"virtual\": {}},"
		+ "  {\"id\": \"count\", \"type\": \"signed\", \"writable\": true, \"virtual\": {}}],"
		+ " \"schedules\": [{\"id\": \"morning\", \"label\": \"Morning\", \"kind\": \"weekly\","
		+ "  \"days\": [1, 2, 3, 4, 5, 6, 7], \"time\": {\"hour\": 9, \"minute\": 0, \"second\": 0}, \"output\": \"fired\"}],"
		+ " \"rules\": [{\"id\": \"watch\", \"name\": \"Watch\","
		+ "  \"reactions\": [{\"triggers\": [{\"kind\": \"datapoint\", \"datapoint\": \"fired\"}],"
		+ "   \"actions\": [{\"kind\": \"write\", \"datapoint\": \"count\", \"value\": {\"kind\": \"binary\", \"op\": \"+\","
		+ "    \"left\": {\"kind\": \"call\", \"function\": \"coalesce\", \"args\": [{\"kind\": \"datapoint\", \"name\": \"count\"},"
		+ "     {\"kind\": \"literal\", \"type\": \"integer\", \"value\": 0}]},"
		+ "    \"right\": {\"kind\": \"literal\", \"type\": \"integer\", \"value\": 1}}}]}]}]}";

	public void testOutputQuietAtStart() throws Exception {
		try (Simulation simulation =
			new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, new ParameterStore())) {
			assertNull(simulation.start());
			assertEquals(Val.ofBool(false), simulation.value("fired"));
			assertEquals("The rule did not react to the output's value at the start.", 0, writes(simulation));
			// The firing at 09:00 and the reset at midnight are events.
			simulation.advance(3600_000 + 1000);
			assertEquals(Val.ofBool(true), simulation.value("fired"));
			assertEquals(Val.ofInt(1), simulation.value("count"));
			assertEquals(1, writes(simulation));
			simulation.advance(15 * 3600_000);
			assertEquals(Val.ofBool(false), simulation.value("fired"));
			assertEquals(Val.ofInt(2), simulation.value("count"));
			assertEquals(2, writes(simulation));
		}
	}

	private static long writes(Simulation simulation) {
		return simulation.timeline().stream()
			.filter(entry -> entry.kind() == Kind.COMMAND && entry.text().startsWith("write count"))
			.count();
	}

	public static Test suite() {
		return new TestSuite(TestScheduleOutputQuiet.class);
	}

}
