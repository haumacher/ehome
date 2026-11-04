/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.blocks.BlockRuntime;
import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.Simulation;

/**
 * The simulation runs the blocks of an image (haumacher/ehome #75): a room controller without a
 * sensor value puts its valve at the fallback position, regulates once the temperature is reported,
 * takes a change of its comfort setpoint through its datapoint at once, and keeps its integral across
 * a restart of the simulation (an orderly reboot: the records are written when it stops). The
 * semantics in detail are pinned by the block corpus ({@link test.de.haumacher.ehome.blocks.TestBlockCorpus}).
 */
@SuppressWarnings("javadoc")
public class TestSimulationBlocks extends TestCase {

	private static final long START = Values.daysOf(2026, 11, 4) * 86400 + 8 * 3600;

	private static final String IMAGE = "{\"settings\": {\"tz\": \"UTC0\"},"
		+ " \"datapoints\": [{\"id\": \"t\", \"type\": \"real\", \"writable\": true, \"virtual\": {}},"
		+ "  {\"id\": \"valve\", \"type\": \"real\", \"writable\": true, \"virtual\": {}}],"
		+ " \"blocks\": [{\"id\": \"wz\", \"type\": \"org.homecontrol.room-controller\", \"version\": \"1.0\","
		+ "  \"label\": \"WZ\", \"inputs\": {\"temperature\": \"t\"}, \"outputs\": {\"valve\": \"valve\"}}]}";

	public void testRoomController() throws Exception {
		BlockRuntime.MemoryRecords records = new BlockRuntime.MemoryRecords();
		ParameterStore store = new ParameterStore();
		double integral;
		try (Simulation simulation = new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, store)) {
			simulation.setRecords(records);
			assertNull(simulation.start());
			assertEquals("The fallback position without a temperature.", Val.ofReal(30), simulation.value("valve"));
			assertEquals(Val.ofReal(21), simulation.value("block/wz/comfort"));
			assertEquals(CommandStatus.OK, simulation.command("t", Val.ofReal(19)));
			simulation.advance(60_000);
			assertEquals("heating", Val.ofEnum(1), simulation.value("block/wz/state"));
			assertEquals(Val.ofReal(2), simulation.value("block/wz/error"));
			double regulated = simulation.value("valve").real();
			assertTrue("Regulating 2 K below the setpoint opens the valve: " + regulated, regulated > 50);
			// A lower setpoint through the parameter's datapoint closes it at once.
			assertEquals(CommandStatus.OK, simulation.command("block/wz/comfort", Val.ofReal(18)));
			assertEquals(Val.ofReal(18), simulation.value("block/wz/activeSetpoint"));
			assertTrue(simulation.value("valve").real() < regulated);
			assertEquals(1, store.overrides().size());
			for (int i = 0; i < 30; i++) {
				simulation.command("t", Val.ofReal(17.5));
				simulation.advance(60_000);
			}
			int b = simulation.blocks().set().find("wz");
			integral = simulation.blocks().state(b)[0];
			assertTrue("The integral grew: " + integral, integral > 0);
		}
		// A restart: the integral comes from the record written when the simulation stopped.
		try (Simulation simulation = new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, store)) {
			simulation.setRecords(records);
			assertNull(simulation.start());
			int b = simulation.blocks().set().find("wz");
			assertEquals(integral, simulation.blocks().state(b)[0], 0.0);
			assertEquals("The override survives.", Val.ofReal(18), simulation.value("block/wz/comfort"));
		}
	}

	public static Test suite() {
		return new TestSuite(TestSimulationBlocks.class);
	}

}
