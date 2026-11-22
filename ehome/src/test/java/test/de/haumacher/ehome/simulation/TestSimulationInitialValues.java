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
 * The simulation starts the application datapoints with their initial values as the controller does
 * (haumacher/ehome #217, homecontrol #107, <code>spec/runtime-image.md</code> 3.3): with quality good
 * before the rules start, so the house's sun shading rule, whose condition reads a datapoint nobody
 * has set, does not log <code>conditionNoValue</code> at its firing; without an initial value it
 * does, as before.
 */
@SuppressWarnings("javadoc")
public class TestSimulationInitialValues extends TestCase {

	/** Monday 2026-11-23 08:00 UTC. */
	private static final long START = Values.daysOf(2026, 11, 23) * 86400 + 8 * 3600;

	private static String image(String initial) {
		return "{\"settings\": {\"tz\": \"UTC0\"},"
			+ " \"datapoints\": [{\"id\": \"sonnenschutz\", \"type\": \"boolean\", \"writable\": true, \"persist\": true,"
			+ "   \"virtual\": {}" + initial + "},"
			+ "  {\"id\": \"sun\", \"type\": \"boolean\", \"writable\": true, \"virtual\": {}, \"initial\": false},"
			+ "  {\"id\": \"setpoint\", \"type\": \"real\", \"virtual\": {}, \"initial\": 21.5},"
			+ "  {\"id\": \"wake\", \"type\": \"timeOfDay\", \"virtual\": {}, \"initial\": {\"hour\": 6, \"minute\": 30, \"second\": 0}},"
			+ "  {\"id\": \"blinds\", \"type\": \"boolean\", \"writable\": true, \"virtual\": {}}],"
			+ " \"rules\": [{\"id\": \"rule-sonnenschutz\", \"name\": \"Sonnenschutz\","
			+ "  \"reactions\": [{\"triggers\": [{\"kind\": \"datapoint\", \"datapoint\": \"sun\"}],"
			+ "   \"condition\": {\"kind\": \"datapoint\", \"name\": \"sonnenschutz\"},"
			+ "   \"actions\": [{\"kind\": \"write\", \"datapoint\": \"blinds\", \"value\": {\"kind\": \"datapoint\", \"name\": \"sun\"}}]}]}]}";
	}

	public void testInitialValues() throws Exception {
		try (Simulation simulation =
			new Simulation((Map<?, ?>) Json.parse(image(", \"initial\": true"), 100), START, new ParameterStore())) {
			assertNull(simulation.start());
			assertEquals(Val.ofBool(true), simulation.value("sonnenschutz"));
			assertEquals(Val.ofBool(false), simulation.value("sun"));
			assertEquals(Val.ofReal(21.5), simulation.value("setpoint"));
			assertEquals("06:30", Values.literalText(simulation.value("wake")));
			assertFalse(simulation.value("blinds").has());
			simulation.command("sun", Val.ofBool(true));
			simulation.advance(1000);
			assertEquals(Val.ofBool(true), simulation.value("blinds"));
			assertEquals(0, noValue(simulation));
		}
	}

	public void testWithoutInitialValue() throws Exception {
		try (Simulation simulation = new Simulation((Map<?, ?>) Json.parse(image(""), 100), START, new ParameterStore())) {
			assertNull(simulation.start());
			assertFalse(simulation.value("sonnenschutz").has());
			simulation.command("sun", Val.ofBool(true));
			simulation.advance(1000);
			assertEquals(1, noValue(simulation));
		}
	}

	private static long noValue(Simulation simulation) {
		return simulation.timeline().stream()
			.filter(entry -> entry.kind() == Kind.RULE && entry.text().startsWith("conditionNoValue"))
			.count();
	}

	public static Test suite() {
		return new TestSuite(TestSimulationInitialValues.class);
	}

}
