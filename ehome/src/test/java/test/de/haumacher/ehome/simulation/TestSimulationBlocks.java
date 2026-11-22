/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.util.Collection;
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
 * takes a change of its comfort setpoint through its datapoint at once, and starts fresh after a
 * restart of the simulation (an orderly reboot): its integral is internal state, which no record
 * keeps (homecontrol #102, <code>spec/blocks.md</code> B4); the override survives. The
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
		assertTrue("The room controller keeps no managed state: no record.", records.blocks().isEmpty());
		// A restart: the integral starts again from 0.
		try (Simulation simulation = new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, store)) {
			simulation.setRecords(records);
			assertNull(simulation.start());
			int b = simulation.blocks().set().find("wz");
			assertEquals(0.0, simulation.blocks().state(b)[0], 0.0);
			assertEquals("The override survives.", Val.ofReal(18), simulation.value("block/wz/comfort"));
		}
	}

	private static final String HOUSE = "{\"settings\": {\"tz\": \"UTC0\"},"
		+ " \"datapoints\": [{\"id\": \"hb\", \"type\": \"boolean\", \"writable\": true, \"virtual\": {}},"
		+ "  {\"id\": \"nb\", \"type\": \"boolean\", \"writable\": true, \"virtual\": {}}],"
		+ " \"blocks\": [{\"id\": \"hm\", \"type\": \"org.homecontrol.house-mode\", \"version\": \"1.0\","
		+ "  \"label\": \"House\", \"inputs\": {\"homeButton\": \"hb\", \"nightButton\": \"nb\"}}]}";

	/**
	 * The house mode's managed state (homecontrol <code>spec/blocks.md</code> B4, B13, 8.1): its record
	 * is written 10 s after a change of the mode and not before; the guard holds a 31st write within
	 * the hour until the hour of the first has passed, with a warning, and then writes the latest
	 * state once.
	 */
	public void testManagedStateRecord() throws Exception {
		CountingRecords records = new CountingRecords();
		try (Simulation simulation = new Simulation((Map<?, ?>) Json.parse(HOUSE, 100), START, new ParameterStore())) {
			simulation.setRecords(records);
			assertNull(simulation.start());
			// The start sets since: the first record is due 10 s later.
			simulation.advance(9_999);
			assertNull("Not before 10 s.", records.read("hm"));
			simulation.advance(1);
			assertEquals(1, records._writes);
			assertEquals(Double.valueOf(0), records.read("hm").fields().get("mode"));
			assertEquals(Double.valueOf(START / 60), records.read("hm").fields().get("since"));
			// Presses every 20 s, night and home in turn: each changes the mode, each record is written
			// 10 s later; the periods in between write nothing.
			for (int press = 1; press <= 29; press++) {
				simulation.advance(10_000);
				press(simulation, press % 2 == 1 ? "nb" : "hb");
				simulation.advance(9_999);
				assertEquals("Press " + press + ": not yet written.", press, records._writes);
				simulation.advance(1);
				assertEquals("Press " + press + ": written.", press + 1, records._writes);
				assertEquals(Double.valueOf(press % 2 == 1 ? 2 : 0), records.read("hm").fields().get("mode"));
			}
			// 30 writes within the hour (the first at 10 s): the next waits until 3 610 s.
			simulation.advance(10_000);
			press(simulation, "hb");
			assertEquals(Val.ofEnum(0), simulation.value("block/hm/mode"));
			simulation.advance(3_609_999 - 600_000);
			assertEquals("Held by the guard.", 30, records._writes);
			assertEquals(Double.valueOf(2), records.read("hm").fields().get("mode"));
			simulation.advance(1);
			assertEquals("Written once the hour of the first write has passed.", 31, records._writes);
			assertEquals(Double.valueOf(0), records.read("hm").fields().get("mode"));
			long warnings = simulation.timeline().stream()
				.filter(entry -> entry.source().equals("blocks") && entry.text().contains("spec/blocks.md B13")).count();
			assertEquals("One warning.", 1, warnings);
		}
	}

	private static void press(Simulation simulation, String button) {
		assertEquals(CommandStatus.OK, simulation.command(button, Val.ofBool(false)));
		assertEquals(CommandStatus.OK, simulation.command(button, Val.ofBool(true)));
	}

	/** A {@link BlockRuntime.MemoryRecords} that counts the writes. */
	private static final class CountingRecords implements BlockRuntime.RecordStore {

		private final BlockRuntime.MemoryRecords _records = new BlockRuntime.MemoryRecords();

		int _writes;

		@Override
		public BlockRuntime.StateRecord read(String block) {
			return _records.read(block);
		}

		@Override
		public void write(String block, BlockRuntime.StateRecord record) {
			_writes++;
			_records.write(block, record);
		}

		@Override
		public void remove(String block) {
			_records.remove(block);
		}

		@Override
		public Collection<String> blocks() {
			return _records.blocks();
		}
	}

	public static Test suite() {
		return new TestSuite(TestSimulationBlocks.class);
	}

}
