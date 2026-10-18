/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import static test.de.haumacher.ehome.simulation.LoopbackEndpoints.gateway;
import static test.de.haumacher.ehome.simulation.LoopbackEndpoints.loopback;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.monitor.BusLink;
import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.monitor.CalimeroBusLink;
import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.simulation.KnxBinding;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Entry;
import de.haumacher.ehome.simulation.Simulation.Kind;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupObject;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.server.VirtualInstallation;

/**
 * Probe of the virtual Zigbee devices composed with the simulated KNX bus (haumacher/ehome #64): a
 * KNX wall switch (1/0/1) switches a Zigbee light through a rule, and a Zigbee button's toggle
 * switches a KNX actuator (1/1/1) through another rule, so one simulation crosses the technologies
 * in both directions; a colour temperature outside the Kelvin range is refused, a commanded one
 * comes back in the device's mired steps.
 */
@SuppressWarnings("javadoc")
public class TestSimulationZigbeeProbe extends TestCase {

	/** Monday 2026-10-19 06:00 in Berlin (CEST, UTC+2), in seconds. */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 4 * 3600;

	private static final long TIMEOUT = 5000;

	private static final byte[] ON = { 1 };

	private static final String LIGHT = "zigbee.0017880101020304.11";

	private static final String BUTTON = "zigbee.000b57fffe112233.1";

	private static final String IMAGE = """
		{"image": {"schemaVersion": "1.0", "projectId": "zigbee-knx-probe", "revision": 1},
			"settings": {"tz": "CET-1CEST,M3.5.0,M10.5.0/3"},
			"datapoints": [
			{"id": "wall.switch", "type": "boolean", "knx": {"dpt": "1.001", "listen": ["1/0/1"]}},
			{"id": "living.light", "type": "boolean", "writable": true, "transmit": true, "knx": {"dpt": "1.001", "send": "1/1/1"}},
			{"id": "hall.light", "type": "boolean", "writable": true, "transmit": true,
				"zigbee": {"ieee": "0017880101020304", "endpoint": 11, "cluster": 6, "attribute": 0, "command": 6}},
			{"id": "hall.ct", "type": "unsigned", "writable": true, "transmit": true,
				"zigbee": {"ieee": "0017880101020304", "endpoint": 11, "cluster": 768, "attribute": 7, "command": 768}},
			{"id": "hall.button", "type": "enumeration",
				"zigbee": {"ieee": "000b57fffe112233", "endpoint": 1, "cluster": 6, "client": true}}],
			"rules": [
			{"id": "wall-to-zigbee", "name": "Wall switch to the Zigbee light", "reactions": [{
				"triggers": [{"kind": "datapoint", "datapoint": "wall.switch", "on": "update"}],
				"actions": [{"kind": "write", "datapoint": "hall.light", "value": {"kind": "datapoint", "name": "wall.switch"}}]}]},
			{"id": "button-to-knx", "name": "Zigbee toggle to the KNX light", "reactions": [{
				"triggers": [{"kind": "datapoint", "datapoint": "hall.button", "on": "update"}],
				"condition": {"kind": "binary", "op": "==", "left": {"kind": "datapoint", "name": "hall.button"},
					"right": {"kind": "literal", "type": "enumeration", "value": 2}},
				"actions": [{"kind": "write", "datapoint": "living.light", "value": {"kind": "literal", "type": "boolean", "value": true}}]}]}]}
		""";

	public void testBothDirections() throws Exception {
		Map<?, ?> image = (Map<?, ?>) Json.parse(IMAGE, 100);
		GroupDevice actuator = new GroupDevice("Switch actuator", 0x1101)
			.add(GroupObject.of(0, "Channel A switching", 1, GroupObject.INPUT, address("1/1/1")));
		GroupDevice wall = new GroupDevice("Wall switch", 0x1102)
			.add(GroupObject.of(0, "Switch", 1, GroupObject.OUTPUT, address("1/0/1")));

		Endpoint endpoint = loopback();
		try (VirtualInstallation installation =
			VirtualInstallation.start(endpoint, "E-Home Zigbee probe", 3, new File("target/virtual/zigbee-probe"))) {
			installation.attach(actuator);
			installation.attach(wall);
			Simulation simulation = new Simulation(image, START, new ParameterStore());
			try {
				simulation.connect(CalimeroBusLink.open(gateway(endpoint)));
				BlockingQueue<String> bus = new LinkedBlockingQueue<>();
				try (BusLink observer = CalimeroBusLink.open(gateway(endpoint))) {
					observer.setListener(new BusLink.Listener() {
						@Override
						public void received(BusTelegram telegram) {
							bus.add(telegram.sourceText() + " " + telegram.service().classifier() + " "
								+ RuntimeImage.threeLevel(telegram.destination()) + " " + telegram.hex());
						}

						@Override
						public void closed(String reason) {
							bus.add("closed " + reason);
						}
					});
					assertNull(simulation.start());
					long received = simulation.received();

					// KNX to Zigbee: the wall switch's telegram switches the Zigbee light.
					simulation.advance(1000);
					installation.set(wall, 0, ON);
					assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
					assertEquals("1.1.2 write 1/0/1 01", poll(bus));
					assertEquals(Val.ofBool(true), simulation.value("wall.switch"));
					assertEquals(Val.ofBool(true), simulation.value("hall.light"));
					assertEquals(List.of(
						"1000 zigbee command " + LIGHT + " on_off = true to cluster 6 (hall.light)",
						"1000 command wall-to-zigbee write hall.light = true",
						"1000 zigbee report " + LIGHT + " on_off = true (hall.light)"),
						entries(simulation, Kind.ZIGBEE_COMMAND, Kind.COMMAND, Kind.ZIGBEE_REPORT));
					assertEquals(1, simulation.status("wall-to-zigbee").firings());

					// Zigbee to KNX: a toggle press switches the KNX actuator; another press (on) does not.
					simulation.advance(1000);
					assertNull(simulation.zigbeeReport("hall.button", Val.ofEnum(2)));
					assertEquals("15.15.255 write 1/1/1 01", poll(bus));
					assertTrue(actuator.awaitValue(0, ON, TIMEOUT));
					assertEquals(Val.ofBool(true), simulation.value("living.light"));
					assertNull(simulation.zigbeeReport("hall.button", Val.ofEnum(1)));
					assertEquals(2, simulation.status("button-to-knx").firings());
					assertEquals(List.of("2000 command button-to-knx write living.light = true"),
						entries(simulation, Kind.COMMAND).subList(1, 2));
					assertEquals(List.of(
						"2000 zigbee action " + BUTTON + " action 2 (toggle) (hall.button)",
						"2000 zigbee action " + BUTTON + " action 1 (on) (hall.button)"),
						entries(simulation, Kind.ZIGBEE_ACTION));
					assertNull("Nothing more on the bus.", bus.poll(500, TimeUnit.MILLISECONDS));

					// The colour temperature: refused outside the Kelvin range, commanded in mired steps.
					assertEquals("a colour temperature takes 16..1000000 K",
						simulation.zigbeeReport("hall.ct", Val.ofInt(0)));
					assertFalse(simulation.value("hall.ct").has());
					assertEquals(CommandStatus.OK, simulation.command("hall.ct", Val.ofInt(2700)));
					// 1000000 / 2700 = 370 mireds, 1000000 / 370 = 2702.7 K, rounded as the controller rounds.
					assertEquals(Val.ofInt(2703), simulation.value("hall.ct"));
					assertEquals(CommandStatus.REFUSED, simulation.command("hall.ct", Val.ofInt(5)));
					assertEquals(Val.ofInt(2703), simulation.value("hall.ct"));
				}
			} finally {
				simulation.close();
			}
		}
	}

	private static List<String> entries(Simulation simulation, Kind... kinds) {
		List<String> result = new ArrayList<>();
		for (Entry entry : simulation.timeline()) {
			if (List.of(kinds).contains(entry.kind())) {
				result.add(entry.ms() + " " + entry.kind().externalName() + " " + entry.source() + " " + entry.text());
			}
		}
		return result;
	}

	private static String poll(BlockingQueue<String> bus) throws InterruptedException {
		String result = bus.poll(TIMEOUT, TimeUnit.MILLISECONDS);
		assertNotNull("No telegram on the bus.", result);
		return result;
	}

	private static int address(String text) {
		return KnxBinding.groupAddress(text);
	}

	public static Test suite() {
		return new TestSuite(TestSimulationZigbeeProbe.class);
	}

}
