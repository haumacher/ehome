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
 * Probe of the managed block state (haumacher/ehome #210): a change still within its delay is written by
 * an orderly stop, the next start restores it from the record, and a start with unchanged state writes
 * nothing.
 */
public class TestManagedStateProbe extends TestCase {

	private static final long START = Values.daysOf(2026, 11, 4) * 86400 + 8 * 3600;

	private static final String HOUSE = "{\"settings\": {\"tz\": \"UTC0\"},"
		+ " \"datapoints\": [{\"id\": \"hb\", \"type\": \"boolean\", \"writable\": true, \"virtual\": {}},"
		+ "  {\"id\": \"nb\", \"type\": \"boolean\", \"writable\": true, \"virtual\": {}}],"
		+ " \"blocks\": [{\"id\": \"hm\", \"type\": \"org.homecontrol.house-mode\", \"version\": \"1.0\","
		+ "  \"label\": \"House\", \"inputs\": {\"homeButton\": \"hb\", \"nightButton\": \"nb\"}}]}";

	/**
	 * The night button pressed 3 s before an orderly stop; the next start an hour later finds night mode
	 * and writes no record while nothing changes.
	 */
	public void testStopWritesPendingRestartRestores() throws Exception {
		CountingRecords records = new CountingRecords();
		Object since;
		try (Simulation simulation = new Simulation((Map<?, ?>) Json.parse(HOUSE, 100), START, new ParameterStore())) {
			simulation.setRecords(records);
			assertNull(simulation.start());
			simulation.advance(10_000);
			assertEquals(1, records._writes);
			simulation.advance(20_000);
			press(simulation, "nb");
			assertEquals(Val.ofEnum(2), simulation.value("block/hm/mode"));
			simulation.advance(3_000);
			assertEquals("Still within the delay.", 1, records._writes);
		}
		assertEquals("The orderly stop writes the pending record.", 2, records._writes);
		assertEquals(Double.valueOf(2), records.read("hm").fields().get("mode"));
		since = records.read("hm").fields().get("since");

		try (Simulation simulation =
			new Simulation((Map<?, ?>) Json.parse(HOUSE, 100), START + 3600, new ParameterStore())) {
			simulation.setRecords(records);
			assertNull(simulation.start());
			simulation.advance(1_000);
			assertEquals("Restored from the record.", Val.ofEnum(2), simulation.value("block/hm/mode"));
			simulation.advance(60_000);
			assertEquals("Unchanged state writes nothing.", 2, records._writes);
		}
		assertEquals("An orderly stop without a change writes nothing.", 2, records._writes);
		assertEquals(since, records.read("hm").fields().get("since"));
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

	/**
	 * The suite of this test.
	 */
	public static Test suite() {
		return new TestSuite(TestManagedStateProbe.class);
	}

}
