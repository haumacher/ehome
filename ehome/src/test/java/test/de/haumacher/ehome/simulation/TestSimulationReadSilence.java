/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import static test.de.haumacher.ehome.simulation.LoopbackEndpoints.gateway;
import static test.de.haumacher.ehome.simulation.LoopbackEndpoints.loopback;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.monitor.BusLink;
import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.monitor.CalimeroBusLink;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.simulation.KnxBinding;
import de.haumacher.ehome.simulation.KnxValues;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Entry;
import de.haumacher.ehome.simulation.Simulation.Kind;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupObject;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.server.VirtualInstallation;

/**
 * The read action and the silence trigger (homecontrol spec/rules.md 7.11, 7.12, haumacher/ehome #63)
 * in a {@link Simulation} on the simulated clock: a temperature sensor on 1/3/1 that answers reads; a
 * rule watching it for 2 min of silence and reading it then.
 *
 * <ul>
 * <li>Without a report the period counts from the start: at 2 min the rule reads the sensor (a
 * GroupValueRead of 1/3/1 on the bus); the sensor's answer (21.5 °C) is a report of the datapoint
 * and re-arms the trigger, which fires again 2 min after the answer.</li>
 * <li>The datapoints of the rule's parameters are in the simulation: the enable switch and the
 * period, a duration as unsigned milliseconds.</li>
 * <li>A simulation without a bus refuses the read: <code>readRefused</code>; without an answer the
 * datapoint stays silent, and the trigger fires once.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestSimulationReadSilence extends TestCase {

	/** Monday 2026-10-19 06:00 in Berlin (CEST, UTC+2), in seconds. */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 4 * 3600;

	private static final long TIMEOUT = 5000;

	private static final String IMAGE = """
		{"image": {"schemaVersion": "1.0", "projectId": "read-silence", "revision": 1},
			"settings": {"tz": "CET-1CEST,M3.5.0,M10.5.0/3"},
			"datapoints": [{"id": "hall.temp", "type": "real", "knx": {"dpt": "9.001", "listen": ["1/3/1"]}}],
			"rules": [{"id": "watch", "name": "Watch",
			"parameters": [{"id": "quiet", "label": "Quiet period", "type": "duration", "default": 120000}],
			"reactions": [{
			"triggers": [{"kind": "silence", "datapoint": "hall.temp", "after": {"kind": "parameter", "id": "quiet"}}],
			"actions": [{"kind": "read", "datapoint": "hall.temp"}, {"kind": "log", "level": "warning", "message": ["silent"]}]}]}]}
		""";

	public void testReadOnSilence() throws Exception {
		Map<?, ?> image = (Map<?, ?>) Json.parse(IMAGE, 100);
		GroupDevice sensor = new GroupDevice("Temperature sensor", 0x1103)
			.add(GroupObject.of(0, "Temperature", 16, GroupObject.OUTPUT, KnxBinding.groupAddress("1/3/1")));
		sensor.set(0, KnxValues.encode(Dpt.parse("9.001"), Val.ofReal(21.5)));
		Endpoint endpoint = loopback();
		try (VirtualInstallation installation =
			VirtualInstallation.start(endpoint, "E-Home read test", 3, new File("target/virtual/read-silence"))) {
			installation.attach(sensor);
			Simulation simulation = new Simulation(image, START, new ParameterStore());
			try {
				simulation.connect(CalimeroBusLink.openVirtual(gateway(endpoint)));
				BlockingQueue<String> bus = new LinkedBlockingQueue<>();
				try (BusLink observer = CalimeroBusLink.openVirtual(gateway(endpoint))) {
					observer.setListener(new BusLink.Listener() {
						@Override
						public void received(BusTelegram telegram) {
							bus.add(telegram.service().classifier() + " " + RuntimeImage.threeLevel(telegram.destination()));
						}

						@Override
						public void closed(String reason) {
							bus.add("closed " + reason);
						}
					});
					assertNull(simulation.start());
					assertEquals(Val.ofBool(true), simulation.value("rule/watch/enabled"));
					assertEquals(Val.ofInt(120000), simulation.value("rule/watch/quiet"));
					assertFalse(simulation.value("hall.temp").has());

					long received = simulation.received();
					simulation.advance(120_000);
					assertEquals(List.of("120000 watch read hall.temp", "120000 watch log (warning): silent"),
						entries(simulation, Kind.COMMAND, Kind.RULE));
					// The observer's tunnel delivers the read and the sensor's answer in either order.
					assertEquals(Set.of("read 1/3/1", "response 1/3/1"), poll(bus, 2));
					assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
					assertEquals(Val.ofReal(21.5), simulation.value("hall.temp"));
					assertEquals("log", simulation.status("watch").lastAction());
					assertEquals("silence hall.temp", simulation.status("watch").lastTrigger());

					// The answer re-armed the trigger: the next silent period ends 2 min after it.
					simulation.advance(120_000);
					assertEquals(2, simulation.status("watch").firings());
					assertEquals(Set.of("read 1/3/1", "response 1/3/1"), poll(bus, 2));
				}
			} finally {
				simulation.close();
			}
		}
	}

	public void testReadRefusedWithoutBus() throws Exception {
		Simulation simulation = new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, new ParameterStore());
		try {
			assertNull(simulation.start());
			simulation.advance(120_000);
			simulation.advance(240_000);
			List<String> entries = entries(simulation, Kind.COMMAND, Kind.RULE);
			assertEquals(entries.toString(), List.of(
				"120000 watch readRefused (warning): read of 'hall.temp' refused: the technology refused",
				"120000 watch log (warning): silent"), entries);
		} finally {
			simulation.close();
		}
	}

	private static List<String> entries(Simulation simulation, Kind... kinds) {
		List<String> result = new ArrayList<>();
		for (Entry entry : simulation.timeline()) {
			if (List.of(kinds).contains(entry.kind())) {
				result.add(entry.ms() + " " + entry.source() + " " + entry.text());
			}
		}
		return result;
	}

	private static String poll(BlockingQueue<String> bus) throws InterruptedException {
		String result = bus.poll(TIMEOUT, TimeUnit.MILLISECONDS);
		assertNotNull("No telegram on the bus.", result);
		return result;
	}

	private static Set<String> poll(BlockingQueue<String> bus, int count) throws InterruptedException {
		Set<String> result = new HashSet<>();
		for (int n = 0; n < count; n++) {
			result.add(poll(bus));
		}
		return result;
	}

	public static Test suite() {
		return new TestSuite(TestSimulationReadSilence.class);
	}

}
