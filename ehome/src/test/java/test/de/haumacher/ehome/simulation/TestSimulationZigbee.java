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

import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Entry;
import de.haumacher.ehome.simulation.Simulation.Kind;

/**
 * The virtual Zigbee devices of a {@link Simulation} (haumacher/ehome #64): an occupancy sensor bound
 * to <code>hall.presence</code>, a light whose switch and level are bound to <code>hall.light</code>
 * and <code>hall.level</code>, a button bound to <code>hall.button</code> (the <code>action</code> of a
 * client binding), a temperature and a humidity sensor. No bus: the image has no KNX datapoints.
 *
 * <ul>
 * <li>The sensor reports presence; the rule switches the light; the light reports its state back:
 * report, command, report in the timeline at the simulated time.</li>
 * <li>A command to the read-only sensor is refused by the model (not writable), one to a writable
 * datapoint whose function takes no command by the device (read-only).</li>
 * <li>Two presses of the same button value fire an <code>update</code> trigger twice, a
 * <code>change</code> trigger once (homecontrol <code>spec/rules.md</code> 2.3).</li>
 * <li>A level outside 0..100 is refused with the range; a commanded level comes back in the device's
 * steps of 1/254.</li>
 * <li>A rule's read of a device without a value leaves the datapoint unknown and says so; after a
 * report the read answers with the value. A value set before the start is reported at the start.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestSimulationZigbee extends TestCase {

	/** Monday 2026-10-19 06:00 in Berlin (CEST, UTC+2), in seconds. */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 4 * 3600;

	private static final String SENSOR = "zigbee.00158d0001020304.1";

	private static final String LIGHT = "zigbee.0017880101020304.11";

	private static final String BUTTON = "zigbee.000b57fffe112233.1";

	private static final String IMAGE = """
		{"image": {"schemaVersion": "1.0", "projectId": "zigbee-simulation", "revision": 1},
			"settings": {"tz": "CET-1CEST,M3.5.0,M10.5.0/3"},
			"datapoints": [
			{"id": "hall.presence", "type": "boolean", "readOnStart": true,
				"zigbee": {"ieee": "00158d0001020304", "endpoint": 1, "cluster": 1030, "attribute": 0}},
			{"id": "hall.temp", "type": "real", "readOnStart": true,
				"zigbee": {"ieee": "00158d0001020304", "endpoint": 1, "cluster": 1026, "attribute": 0}},
			{"id": "hall.humidity", "type": "real", "writable": true,
				"zigbee": {"ieee": "00158d0001020304", "endpoint": 1, "cluster": 1029, "attribute": 0}},
			{"id": "hall.light", "type": "boolean", "writable": true, "transmit": true, "readOnStart": true,
				"zigbee": {"ieee": "00:17:88:01:01:02:03:04", "endpoint": 11, "cluster": 6, "attribute": 0, "command": 6}},
			{"id": "hall.level", "type": "real", "writable": true, "transmit": true, "readOnStart": true,
				"zigbee": {"ieee": "0017880101020304", "endpoint": 11, "cluster": 8, "attribute": 0, "command": 8}},
			{"id": "hall.button", "type": "enumeration",
				"zigbee": {"ieee": "000b57fffe112233", "endpoint": 1, "cluster": 6, "client": true}},
			{"id": "probe", "type": "boolean", "writable": true, "virtual": {}}],
			"rules": [
			{"id": "presence-light", "name": "Light by presence", "reactions": [{
				"triggers": [{"kind": "datapoint", "datapoint": "hall.presence"}],
				"condition": {"kind": "datapoint", "name": "hall.presence"},
				"actions": [{"kind": "write", "datapoint": "hall.light", "value": {"kind": "literal", "type": "boolean", "value": true}}]}]},
			{"id": "every-press", "name": "Every press", "reactions": [{
				"triggers": [{"kind": "datapoint", "datapoint": "hall.button", "on": "update"}],
				"actions": [{"kind": "log", "level": "info", "message": ["pressed"]}]}]},
			{"id": "changed-press", "name": "Changed press", "reactions": [{
				"triggers": [{"kind": "datapoint", "datapoint": "hall.button"}],
				"actions": [{"kind": "log", "level": "info", "message": ["changed"]}]}]},
			{"id": "read-temp", "name": "Read the temperature", "reactions": [{
				"triggers": [{"kind": "datapoint", "datapoint": "probe", "on": "update"}],
				"actions": [{"kind": "read", "datapoint": "hall.temp"}]}]}]}
		""";

	private Simulation _simulation;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_simulation = new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, new ParameterStore());
	}

	@Override
	protected void tearDown() throws Exception {
		_simulation.close();
		super.tearDown();
	}

	public void testPresenceSwitchesLight() {
		assertNull(_simulation.start());
		assertFalse(_simulation.value("hall.presence").has());
		assertFalse(_simulation.value("hall.light").has());
		assertEquals(List.of(), entries(Kind.ZIGBEE_REPORT));

		_simulation.advance(1000);
		assertNull(_simulation.zigbeeReport("hall.presence", Val.ofBool(true)));
		assertEquals(Val.ofBool(true), _simulation.value("hall.presence"));
		assertEquals(Val.ofBool(true), _simulation.value("hall.light"));
		assertEquals(List.of(
			"1000 zigbee report " + SENSOR + " occupancy = true (hall.presence)",
			"1000 zigbee command " + LIGHT + " on_off = true to cluster 6 (hall.light)",
			"1000 command presence-light write hall.light = true",
			"1000 zigbee report " + LIGHT + " on_off = true (hall.light)"),
			entries(Kind.ZIGBEE_REPORT, Kind.ZIGBEE_COMMAND, Kind.COMMAND));
		assertEquals(Long.valueOf(1000), _simulation.zigbee().reported("hall.light"));

		assertEquals(CommandStatus.NOT_WRITABLE, _simulation.command("hall.presence", Val.ofBool(false)));
		assertEquals(CommandStatus.REFUSED, _simulation.command("hall.humidity", Val.ofReal(50)));
		assertEquals(List.of(
			"1000 problem - write hall.presence = false: not writable",
			"1000 problem " + SENSOR + " command of 'hall.humidity' refused: read-only (the humidity of the device takes no command)",
			"1000 problem - write hall.humidity = 50: refused"),
			entries(Kind.PROBLEM));
		assertEquals(Val.ofBool(true), _simulation.value("hall.presence"));
	}

	public void testEveryPressIsAnEvent() {
		assertNull(_simulation.start());
		assertNull(_simulation.zigbeeReport("hall.button", Val.ofEnum(1)));
		assertNull(_simulation.zigbeeReport("hall.button", Val.ofEnum(1)));
		assertEquals(2, _simulation.status("every-press").firings());
		assertEquals(1, _simulation.status("changed-press").firings());
		assertEquals(List.of(
			"0 zigbee action " + BUTTON + " action 1 (on) (hall.button)",
			"0 zigbee action " + BUTTON + " action 1 (on) (hall.button)"),
			entries(Kind.ZIGBEE_ACTION));

		String refusal = _simulation.zigbeeReport("hall.button", Val.ofEnum(6));
		assertEquals("an action takes 0 off, 1 on, 2 toggle, 3 dim up, 4 dim down, 5 dim stop", refusal);
		assertEquals(2, _simulation.status("every-press").firings());
		assertEquals(List.of("0 problem " + BUTTON + " report 6 of 'hall.button' refused: " + refusal),
			entries(Kind.PROBLEM));
	}

	public void testLevelRange() {
		assertNull(_simulation.start());
		assertEquals("a level takes 0..100 %", _simulation.zigbeeReport("hall.level", Val.ofReal(150)));
		assertFalse(_simulation.value("hall.level").has());
		assertNull(_simulation.zigbeeReport("hall.level", Val.ofReal(42.5)));
		assertEquals(Val.ofReal(42.5), _simulation.value("hall.level"));

		// The command goes out as level 84 of 254, which the device reports back.
		assertEquals(CommandStatus.OK, _simulation.command("hall.level", Val.ofReal(33)));
		assertEquals(Val.ofReal(33.1), _simulation.value("hall.level"));
		assertEquals(CommandStatus.REFUSED, _simulation.command("hall.level", Val.ofReal(101)));
		assertEquals(Val.ofReal(33.1), _simulation.value("hall.level"));
		assertTrue(entries(Kind.PROBLEM).toString(),
			entries(Kind.PROBLEM).contains("0 problem " + LIGHT + " command of 'hall.level' refused: a level takes 0..100 %"));
	}

	public void testRead() {
		assertNull(_simulation.start());
		assertEquals(CommandStatus.OK, _simulation.command("probe", Val.ofBool(true)));
		assertFalse(_simulation.value("hall.temp").has());
		assertEquals(List.of(
			"0 command - write probe = true",
			"0 command read-temp read hall.temp",
			"0 problem " + SENSOR + " read of 'hall.temp': the virtual device has no value yet (report one in the "
				+ "simulation); the datapoint stays unknown"),
			entries(Kind.ZIGBEE_REPORT, Kind.PROBLEM, Kind.COMMAND));

		_simulation.advance(5000);
		assertNull(_simulation.zigbeeReport("hall.temp", Val.ofReal(21.5)));
		_simulation.advance(5000);
		assertEquals(CommandStatus.OK, _simulation.command("probe", Val.ofBool(true)));
		assertEquals(Val.ofReal(21.5), _simulation.value("hall.temp"));
		assertEquals(List.of(
			"5000 zigbee report " + SENSOR + " temperature = 21.5 (hall.temp)",
			"10000 zigbee report " + SENSOR + " temperature = 21.5 (read, hall.temp)"),
			entries(Kind.ZIGBEE_REPORT));
		assertEquals(Long.valueOf(10000), _simulation.zigbee().reported("hall.temp"));
	}

	public void testValuesBeforeStart() {
		assertNull(_simulation.zigbeeReport("hall.temp", Val.ofReal(19.5)));
		assertEquals("a press of a button is no state the device keeps; press it while the simulation runs",
			_simulation.zigbeeReport("hall.button", Val.ofEnum(1)));
		assertNull(_simulation.start());
		assertEquals(Val.ofReal(19.5), _simulation.value("hall.temp"));
		assertFalse(_simulation.value("hall.presence").has());
		assertFalse(_simulation.value("hall.button").has());
		assertEquals(List.of("0 zigbee report " + SENSOR + " temperature = 19.5 (hall.temp)"),
			entries(Kind.ZIGBEE_REPORT));
	}

	private List<String> entries(Kind... kinds) {
		List<String> result = new ArrayList<>();
		for (Entry entry : _simulation.timeline()) {
			if (List.of(kinds).contains(entry.kind())) {
				result.add(entry.ms() + " " + entry.kind().externalName() + " " + entry.source() + " " + entry.text());
			}
		}
		return result;
	}

	public static Test suite() {
		return new TestSuite(TestSimulationZigbee.class);
	}

}
