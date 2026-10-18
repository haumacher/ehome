/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import static test.de.haumacher.ehome.virtual.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.VirtualTesting.loopback;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.rules.Engine.ParameterStatus;
import de.haumacher.ehome.rules.Engine.RuleStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.KnxValues;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Entry;
import de.haumacher.ehome.simulation.Simulation.Kind;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupObject;
import de.haumacher.ehome.virtual.server.VirtualInstallation;
import de.haumacher.ehome.virtual.server.VirtualInterface.Endpoint;

/**
 * The rules and schedules of HomeControl's example image (<code>images/example.json</code>) run by
 * a {@link Simulation} against a {@link VirtualInstallation} on the loopback address: a switch
 * actuator whose two channels report their state, and a presence detector with a brightness
 * sensor. A third tunnelling connection watches the bus.
 *
 * <ul>
 * <li>The schedule <code>workdayMorning</code> (Monday to Friday 06:30, Europe/Berlin) fires on
 * Monday 2026-10-19 after the clock was run forward by 30 minutes from 06:00: the rule
 * <code>morning-light</code> writes <code>living.light</code>, the telegram 1/1/1 = 1 is on the bus,
 * channel A switches, and its state 1/1/2 comes back as <code>living.light.state</code>.</li>
 * <li>The presence detector sends the brightness (50 lx) and presence: the rule
 * <code>hall-presence-light</code> writes 1/1/10 = 1; channel B switches. Without presence the
 * switch-off timer runs 3 min; a step of the clock goes exactly there, and the light goes off.</li>
 * <li>The diagnostics name the last trigger and action with their simulated times; a parameter set
 * from outside is stored as an override and shortens the timer.</li>
 * </ul>
 *
 * <p>
 * The image's time master broadcasts on 0/7/1 and 0/7/2 meanwhile; the watcher ignores them
 * ({@link TestTimeMaster} checks them).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSimulation extends TestCase {

	/** Monday 2026-10-19 06:00 in Berlin (CEST, UTC+2), in seconds. */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 4 * 3600;

	private static final long TIMEOUT = 5000;

	private static final byte[] ON = { 1 };

	private static final byte[] OFF = { 0 };

	public void testExampleHouse() throws Exception {
		Map<?, ?> image = (Map<?, ?>) Json.parse(Files.readString(
			new File("src/test/fixtures/homecontrol/images/example.json").toPath(), StandardCharsets.UTF_8), 100);

		GroupDevice actuator = new GroupDevice("Switch actuator", 0x1101)
			.add(GroupObject.of(0, "Channel A switching", 1, GroupObject.INPUT, address("1/1/1")))
			.add(GroupObject.of(1, "Channel A state", 1, GroupObject.OUTPUT, address("1/1/2")))
			.add(GroupObject.of(2, "Channel B switching", 1, GroupObject.INPUT, address("1/1/10")))
			.add(GroupObject.of(3, "Channel B state", 1, GroupObject.OUTPUT, address("1/1/11")))
			.follow(1, 0)
			.follow(3, 2);
		GroupDevice detector = new GroupDevice("Presence detector", 0x1102)
			.add(GroupObject.of(0, "Presence", 1, GroupObject.OUTPUT, address("1/4/1")))
			.add(GroupObject.of(1, "Brightness", 16, GroupObject.OUTPUT, address("1/4/2")));

		Endpoint endpoint = loopback();
		try (VirtualInstallation installation =
			VirtualInstallation.start(endpoint, "E-Home simulation test", 3, new File("target/virtual/simulation"))) {
			installation.attach(actuator);
			installation.attach(detector);

			ParameterStore store = new ParameterStore();
			Simulation simulation = new Simulation(image, START, store);
			try {
				simulation.connect(CalimeroBusLink.open(gateway(endpoint)));
				BlockingQueue<String> bus = new LinkedBlockingQueue<>();
				try (BusLink observer = CalimeroBusLink.open(gateway(endpoint))) {
					observer.setListener(new BusLink.Listener() {
						@Override
						public void received(BusTelegram telegram) {
							if (telegram.destination() >> 8 == 7) {
								// The time master's broadcasts on 0/7/1 and 0/7/2 (TestTimeMaster).
								return;
							}
							bus.add(telegram.sourceText() + " " + telegram.service().classifier() + " "
								+ de.haumacher.ehome.runtime.RuntimeImage.threeLevel(telegram.destination()) + " "
								+ telegram.hex());
						}

						@Override
						public void closed(String reason) {
							bus.add("closed " + reason);
						}
					});

					assertNull(simulation.start());
					// The datapoints read on start: living.light.state, living.dimmer.state, living.temperature.
					assertEquals("15.15.255 read 1/1/2 ", poll(bus));
					assertEquals("15.15.255 read 1/2/2 ", poll(bus));
					assertEquals("15.15.255 read 1/3/1 ", poll(bus));

					morning(simulation, actuator, bus);
					presence(simulation, installation, actuator, detector, store);
				}
			} finally {
				simulation.close();
			}
		}
	}

	private void morning(Simulation simulation, GroupDevice actuator, BlockingQueue<String> bus) throws Exception {
		assertEquals("Mon 2026-10-19 06:00:00.000", simulation.timeText(simulation.now()));
		long received = simulation.received();
		simulation.advance(30 * 60 * 1000);
		assertEquals(30 * 60 * 1000, simulation.now());

		assertEquals("15.15.255 write 1/1/1 01", poll(bus));
		assertTrue(actuator.awaitValue(0, ON, TIMEOUT));
		assertTrue(actuator.awaitValue(1, ON, TIMEOUT));
		assertEquals("1.1.1 write 1/1/2 01", poll(bus));
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value("living.light.state"));
		assertEquals(Val.ofBool(true), simulation.value("schedule.morning"));

		Entry command = only(simulation, Kind.COMMAND, "morning-light");
		assertEquals(30 * 60 * 1000, command.ms());
		assertEquals("write living.light = true", command.text());
		assertEquals("Mon 2026-10-19 06:30:00.000", simulation.timeText(command.ms()));
		Entry fired = only(simulation, Kind.SCHEDULE, "workdayMorning");
		assertEquals(30 * 60 * 1000, fired.ms());

		RuleStatus status = simulation.status("morning-light");
		assertEquals(1, status.firings());
		assertEquals("schedule workdayMorning", status.lastTrigger());
		assertEquals(30 * 60 * 1000, status.lastTriggerMs());
		assertEquals("write living.light = true", status.lastAction());
		assertEquals(30 * 60 * 1000, status.lastActionMs());
		assertEquals(0, status.errors());
	}

	private void presence(Simulation simulation, VirtualInstallation installation, GroupDevice actuator,
			GroupDevice detector, ParameterStore store) throws Exception {
		long t0 = simulation.now();
		long received = simulation.received();
		installation.set(detector, 1, KnxValues.encode(Dpt.parse("9.004"), Val.ofReal(50)));
		assertTrue(simulation.awaitReceived(++received, TIMEOUT));
		assertEquals(Val.ofReal(50), simulation.value("hall.lux"));

		installation.set(detector, 0, ON);
		assertTrue(simulation.awaitReceived(++received, TIMEOUT));
		assertTrue(actuator.awaitValue(2, ON, TIMEOUT));
		// The state of channel B (1/1/11) comes back to hall.light.
		assertTrue(simulation.awaitReceived(++received, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value("hall.light"));
		RuleStatus status = simulation.status("hall-presence-light");
		assertEquals("datapoint hall.presence", status.lastTrigger());
		assertEquals("write hall.light = true", status.lastAction());
		assertEquals(t0, status.lastActionMs());

		installation.set(detector, 0, OFF);
		assertTrue(simulation.awaitReceived(++received, TIMEOUT));
		assertEquals(Long.valueOf(180_000), simulation.timerRemaining("hall-presence-light", "off"));
		assertEquals("restartTimer off 3min", simulation.status("hall-presence-light").lastAction());

		assertEquals(180_000, simulation.step(3_600_000));
		assertEquals(t0 + 180_000, simulation.now());
		assertTrue(actuator.awaitValue(2, OFF, TIMEOUT));
		// The state of channel B.
		assertTrue(simulation.awaitReceived(++received, TIMEOUT));
		status = simulation.status("hall-presence-light");
		assertEquals("timer off", status.lastTrigger());
		assertEquals(t0 + 180_000, status.lastTriggerMs());
		assertEquals("write hall.light = false", status.lastAction());
		assertNull(simulation.timerRemaining("hall-presence-light", "off"));

		// An override from outside: stored, and the next switch-off waits 1 min.
		assertEquals(ParameterStatus.OK, simulation.setParameter("hall-presence-light", "timeout", Val.ofDuration(60_000)));
		assertEquals(Val.ofDuration(60_000), store.read("hall-presence-light", "timeout"));
		assertEquals(Val.ofDuration(60_000), simulation.parameter("hall-presence-light", "timeout"));
		installation.set(detector, 0, ON);
		// The presence and the state of channel B.
		received += 2;
		assertTrue(simulation.awaitReceived(received, TIMEOUT));
		assertTrue(actuator.awaitValue(2, ON, TIMEOUT));
		installation.set(detector, 0, OFF);
		assertTrue(simulation.awaitReceived(++received, TIMEOUT));
		assertEquals(simulation.timeline().toString(), Long.valueOf(60_000),
			simulation.timerRemaining("hall-presence-light", "off"));
	}

	private static Entry only(Simulation simulation, Kind kind, String source) {
		List<Entry> result = new ArrayList<>();
		for (Entry entry : simulation.timeline()) {
			if (entry.kind() == kind && entry.source().equals(source)) {
				result.add(entry);
			}
		}
		assertEquals(simulation.timeline().toString(), 1, result.size());
		return result.get(0);
	}

	private static String poll(BlockingQueue<String> bus) throws InterruptedException {
		String result = bus.poll(TIMEOUT, TimeUnit.MILLISECONDS);
		assertNotNull("No telegram on the bus.", result);
		return result;
	}

	private static int address(String text) {
		return de.haumacher.ehome.simulation.KnxBinding.groupAddress(text);
	}

	public static Test suite() {
		return new TestSuite(TestSimulation.class);
	}

}
