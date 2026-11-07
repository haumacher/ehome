/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.rules.TestRuleText;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.simulation.KnxBinding;
import de.haumacher.ehome.simulation.ProjectSimulation;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulations;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupObject;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeReporting;

/**
 * The test home of the KNX test system (<code>samples/KNX-Testsystem.home.json</code>,
 * <code>doc/test-home.md</code>): the project <code>samples/KNX-Testsystem.knxproj</code>, the Hue
 * motion sensor "Flur Bewegung" and the Hue smart plug "Hue Steckdose" of the controller's Zigbee
 * network (the controller's exports, <code>src/test/fixtures/zigbee/flur-bewegung.export.json</code>
 * and <code>hue-steckdose.export.json</code>, imported as a file import does),
 * the sample's rules, schedule entries, application datapoints and location imported in the tab
 * "Rules" ({@link RuleImport#importImage(TLObject, String)}).
 *
 * <ul>
 * <li>The project leaves only the room temperature (0/0/28) without a datapoint type, which a rule
 * needs; the test sets 9.001 as the dialog "Set datapoint type" does. Every address the rules write
 * is writable as the project's objects give it.</li>
 * <li>The import creates the four <code>flur.*</code> datapoints bound to the sensor's functions and
 * the writable <code>steckdose.on</code> bound to the plug's on/off, the reporting override of the
 * illuminance, the project's location and time zone; a second import
 * keeps the datapoints and says so.</li>
 * <li>The image is valid with every rule loaded, and its rules, schedule entries and datapoints are
 * the sample's (the round trip).</li>
 * <li>The simulation runs the scenarios of the hardware checklist, starting Monday 2026-10-19 12:00
 * in Nürnberg: illuminance, frost, presence, keys, LEDs, the battery alarm and its acknowledgement,
 * the comparison of the temperatures, key 6 switching the plug and LED 5 showing it, dusk, the silence of the sensor, the night and the morning.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestTestHome extends BasicTestCase {

	/** Monday 2026-10-19 10:00 UTC, 12:00 in Nürnberg (summer time). */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 10 * 3600;

	private static final long TIMEOUT = 5000;

	private static final String A = "0/0/34";

	private static final String B = "0/0/40";

	private static final String C = "0/0/46";

	private static final String D = "0/0/48";

	private static final String ALARM = "0/0/29";

	private static final String NIGHT_SETBACK = "0/0/32";

	private static final String PLUG = "steckdose.on";

	private static final String LED_PLUG = "0/0/11";

	public void testTestHome() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			setUp(project);
			Map<?, ?> sample = (Map<?, ?>) Json.parse(read("samples/KNX-Testsystem.home.json"), 100);
			importHome(project, sample);
			RuntimeImage image = RuntimeImage.build(project, 1, Instant.ofEpochSecond(START));
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			assertEquals(List.of(), report.getNotes());
			assertEquals(List.of(), image.getNotes());
			roundTrip(image.getJson(), sample);
			simulate(project);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * Probe: what the import of datapoints and settings decides besides, into a project without Zigbee
	 * devices: a KNX datapoint is not created, a Zigbee datapoint without its device is created unbound,
	 * a different location is kept; each is named. An unknown value type, an id of the form of a group
	 * address and an id listed twice refuse the import, with nothing written.
	 */
	public void testImportDecisions() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("latitude", Double.valueOf(52.5));
				project.tUpdateByName("longitude", Double.valueOf(13.4));
				tx.commit();
			}
			RuleImport importer = new RuleImport();
			RuleImport.Imported imported;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				imported = importer.importImage(project, """
					{"datapoints": [
					  {"id": "0/0/1", "type": "boolean", "knx": {"dpt": "1.001", "listen": ["0/0/1"]}},
					  {"id": "hall.temperature", "type": "real", "unit": "°C",
					   "zigbee": {"ieee": "0000000000000001", "endpoint": 1, "cluster": 1026, "attribute": 0}},
					  {"id": "mode", "label": "Mode", "type": "unsigned", "writable": true, "persist": true, "virtual": {}}
					 ],
					 "settings": {"location": {"lat": 49.45, "lon": 11.08}}}""");
				tx.commit();
			}
			assertEquals(List.of("hall.temperature", "mode"),
				imported.datapoints().stream().map(ApplicationDatapoints::id).toList());
			assertNull(ZigbeeBindings.function(imported.datapoints().get(0)));
			assertEquals(Boolean.TRUE, imported.datapoints().get(1).tValueByName("writable"));
			assertEquals(Boolean.TRUE, imported.datapoints().get(1).tValueByName("persist"));
			List<String> notes = importer.getNotes();
			assertEquals(notes.toString(), 3, notes.size());
			assertTrue(notes.get(0), notes.get(0).contains("0000000000000001"));
			assertTrue(notes.get(1), notes.get(1).startsWith("1 "));
			assertTrue(notes.get(2), notes.get(2).contains("52.5"));
			assertEquals(Double.valueOf(52.5), project.tValueByName("latitude"));

			for (String refused : List.of("{\"datapoints\": [{\"id\": \"x\", \"type\": \"wobble\"}]}",
				"{\"datapoints\": [{\"id\": \"1/2/3\", \"type\": \"boolean\"}]}",
				"{\"datapoints\": [{\"id\": \"y\"}, {\"id\": \"y\"}]}",
				"{\"functions\": []}")) {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					new RuleImport().importImage(project, refused);
					fail("Not refused: " + refused);
				} catch (TopLogicException ex) {
					// Refused, rolled back.
				}
			}
			assertEquals(2, ApplicationDatapoints.of(project).size());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static void setUp(TLObject project) throws Exception {
		List<String> notes = RuntimeImage.build(project, 1, Instant.ofEpochSecond(START)).getNotes();
		assertEquals(notes.toString(), 1, notes.size());
		assertTrue(notes.get(0), notes.get(0).contains("0/0/28 Raumtemperatur"));

		TLObject temperature = SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
			.filter(a -> "Raumtemperatur".equals(a.tValueByName("name"))).findFirst().orElseThrow();
		TLObject type = instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> "DPST-9-1".equals(t.tValueByName("extId"))).findFirst().orElseThrow();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			GroupAddressTypes.setType(temperature, type);
			ZigbeeImport.importExport(project,
				ZigbeeExport.parse(read("src/test/fixtures/zigbee/flur-bewegung.export.json")));
			ZigbeeImport.importExport(project,
				ZigbeeExport.parse(read("src/test/fixtures/zigbee/hue-steckdose.export.json")));
			tx.commit();
		}
	}

	private static void importHome(TLObject project, Map<?, ?> sample) throws Exception {
		String text = Json.write(sample);
		RuleImport importer = new RuleImport();
		RuleImport.Imported imported;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			imported = importer.importImage(project, text);
			tx.commit();
		}
		assertEquals(11, imported.rules().size());
		assertEquals(6, imported.schedules().size());
		assertEquals(5, imported.datapoints().size());
		// The location and the time zone set; nothing else to say.
		assertEquals(importer.getNotes().toString(), 2, importer.getNotes().size());
		assertEquals(Double.valueOf(49.45), project.tValueByName("latitude"));
		assertEquals(Double.valueOf(11.08), project.tValueByName("longitude"));
		assertEquals("CET-1CEST,M3.5.0,M10.5.0/3", project.tValueByName("timeZone"));

		Map<String, String> functions = Map.of("flur.presence", "occupancy", "flur.lux", "illuminance",
			"flur.temperature", "temperature", "flur.battery", "battery", "steckdose.on", "on_off");
		for (TLObject datapoint : imported.datapoints()) {
			TLObject function = ZigbeeBindings.function(datapoint);
			assertNotNull(ApplicationDatapoints.id(datapoint), function);
			assertEquals(functions.get(ApplicationDatapoints.id(datapoint)), function.tValueByName("function"));
			boolean plug = "steckdose.on".equals(ApplicationDatapoints.id(datapoint));
			assertEquals(plug ? "001788010f069c8e" : "0017880106498a49", ZigbeeBindings.device(function).tValueByName("ieee"));
			assertEquals(Boolean.valueOf(plug), Boolean.valueOf(ApplicationDatapoints.writable(datapoint)));
			if ("flur.lux".equals(ApplicationDatapoints.id(datapoint))) {
				assertEquals(Map.of("min", Long.valueOf(10), "max", Long.valueOf(300)), ZigbeeReporting.json(function));
			}
		}

		// Imported again, nothing is added: the rules, entries and datapoints are kept and named, the
		// location and time zone are the same.
		RuleImport again = new RuleImport();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			RuleImport.Imported second = again.importImage(project, text);
			assertEquals(0, second.rules().size() + second.schedules().size() + second.datapoints().size());
			tx.commit();
		}
		assertEquals(again.getNotes().toString(), 22, again.getNotes().size());
		assertEquals(5, ApplicationDatapoints.of(project).size());
		assertEquals(11, ((java.util.Collection<?>) project.tValueByName("rules")).size());
	}

	private static void roundTrip(Map<String, Object> image, Map<?, ?> sample) {
		for (String member : List.of("rules", "schedules")) {
			Object expected = TestRuleAuthoring.normalize(sample.get(member));
			Object actual = TestRuleAuthoring.normalize(image.get(member));
			assertTrue(member + ": " + Json.write(actual), TestRuleText.same(expected, actual));
		}
		List<Object> datapoints = new ArrayList<>();
		for (Object datapoint : (List<?>) image.get("datapoints")) {
			if (!((Map<?, ?>) datapoint).containsKey("knx")) {
				datapoints.add(datapoint);
			}
		}
		assertTrue(Json.write(datapoints), TestRuleText.same(sample.get("datapoints"), datapoints));
		assertTrue(Json.write(image.get("settings")), TestRuleText.same(sample.get("settings"), image.get("settings")));
	}

	private static void simulate(TLObject project) throws Exception {
		Simulations simulations = Simulations.getInstance();
		ProjectSimulation running = simulations.start(project, START);
		try {
			simulate(running);
		} finally {
			simulations.stop(project);
		}
	}

	private static void simulate(ProjectSimulation running) throws Exception {
		Simulation simulation = running.simulation();
		assertEquals(11, simulation.rules().rules().size());
		GroupDevice button = device(running, 0x1332);
		GroupDevice actuator = device(running, 0x1305);

		// At noon the start sets channel B off (between dawn and dusk).
		assertEquals(Val.ofBool(false), simulation.value(B));

		// dark-light: dark switches A on, bright off.
		running.send("flur.lux", "10");
		assertEquals(Val.ofBool(true), simulation.value(A));
		assertTrue(actuator.awaitValue(receiver(actuator, A), new byte[] { 1 }, TIMEOUT));
		running.send("flur.lux", "100");
		assertEquals(Val.ofBool(false), simulation.value(A));

		// frost: below 17 °C on, from 17.5 °C off.
		running.send("flur.temperature", "16.5");
		assertEquals(Val.ofBool(true), simulation.value(C));
		running.send("flur.temperature", "17.2");
		assertEquals(Val.ofBool(true), simulation.value(C));
		running.send("flur.temperature", "17.6");
		assertEquals(Val.ofBool(false), simulation.value(C));

		// presence-light: motion in the dark switches D on, D goes off 2 min after the last motion.
		running.send("flur.lux", "10");
		running.send("flur.presence", "true");
		assertEquals(Val.ofBool(true), simulation.value(D));
		running.send("flur.presence", "false");
		assertEquals(Long.valueOf(120_000), simulation.timerRemaining("presence-light", "off"));
		simulation.advance(119_000);
		assertEquals(Val.ofBool(true), simulation.value(D));
		simulation.advance(1_000);
		assertEquals(Val.ofBool(false), simulation.value(D));

		// keys: key 1 switches A with its value.
		long received = simulation.received();
		running.send("0/0/1", "false");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(false), simulation.value(A));
		assertTrue(actuator.awaitValue(receiver(actuator, A), new byte[] { 0 }, TIMEOUT));
		received = simulation.received();
		running.send("0/0/1", "true");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value(A));
		assertTrue(actuator.awaitValue(receiver(actuator, A), new byte[] { 1 }, TIMEOUT));

		// leds: the state of channel A lights LED 1.
		received = simulation.received();
		running.send("0/0/37", "true");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value("0/0/7"));
		assertTrue(button.awaitValue(receiver(button, "0/0/7"), new byte[] { 1 }, TIMEOUT));

		// plug: key 6 switches the plug with its value, LED 5 shows the plug's state, also when the plug
		// is switched at its own button.
		received = simulation.received();
		running.send("0/0/6", "true");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value(PLUG));
		assertEquals(Val.ofBool(true), simulation.value(LED_PLUG));
		assertTrue(button.awaitValue(receiver(button, LED_PLUG), new byte[] { 1 }, TIMEOUT));
		running.send(PLUG, "false");
		assertEquals(Val.ofBool(false), simulation.value(LED_PLUG));
		assertTrue(button.awaitValue(receiver(button, LED_PLUG), new byte[] { 0 }, TIMEOUT));

		// temp-compare: the room temperature 21.5 °C against 17.6 °C in the hall.
		received = simulation.received();
		running.send("0/0/28", "21.5");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertLogged(simulation, "Raumtemperatur 21.5 °C, Flur 17.6 °C: Abweichung über 3 K.");

		// sensor-watch: a low battery raises the alarm, the acknowledgement clears it.
		running.send("flur.battery", "15");
		assertEquals(Val.ofBool(true), simulation.value(ALARM));
		assertLogged(simulation, "Die Batterie des Flur-Sensors ist bei 15 %.");
		received = simulation.received();
		running.send("0/0/30", "true");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(false), simulation.value(ALARM));

		// 19:00: B on since dusk; the presence simulation is disabled and has not toggled it. The
		// hall sensor has not reported a temperature for 2 h: the alarm again.
		advanceTo(simulation, 0, 19, 0);
		assertEquals(Val.ofBool(true), simulation.value(B));
		assertTrue(simulation.status("dusk-dawn").lastTrigger(), simulation.status("dusk-dawn").lastTrigger().contains("dusk"));
		assertEquals(0, simulation.status("presence-sim").firings());
		assertEquals(Val.ofBool(true), simulation.value(ALARM));
		assertLogged(simulation, "Der Flur-Sensor meldet seit 2h keine Temperatur.");

		// 22:00: every channel off, the night setback on.
		assertEquals(Val.ofBool(true), simulation.value(A));
		advanceTo(simulation, 0, 22, 1);
		for (String channel : List.of(A, B, C, D)) {
			assertEquals(channel, Val.ofBool(false), simulation.value(channel));
		}
		assertEquals(Val.ofBool(true), simulation.value(NIGHT_SETBACK));
		assertTrue(button.awaitValue(receiver(button, NIGHT_SETBACK), new byte[] { 1 }, TIMEOUT));

		// Tuesday 08:00: the night setback off at 06:30, B switched off at dawn.
		advanceTo(simulation, 1, 8, 0);
		assertEquals(Val.ofBool(false), simulation.value(NIGHT_SETBACK));
		assertTrue(simulation.status("dusk-dawn").lastTrigger(), simulation.status("dusk-dawn").lastTrigger().contains("dawn"));
		assertEquals(Val.ofBool(false), simulation.value(B));
	}

	/**
	 * Runs the clock to the given local time (summer time, UTC+2) of the given day after the start.
	 */
	private static void advanceTo(Simulation simulation, int day, int hour, int minute) {
		long target = (day * 86400L + (hour - 12) * 3600L + minute * 60L) * 1000;
		simulation.advance(target - simulation.now());
	}

	private static void assertLogged(Simulation simulation, String text) {
		assertTrue(simulation.timeline().toString(), simulation.timeline().stream()
			.anyMatch(entry -> entry.kind() == Simulation.Kind.RULE && entry.text().contains(text)));
	}

	private static GroupDevice device(ProjectSimulation running, int address) {
		for (GroupDevice device : running.installation().devices()) {
			if (device.address() == address) {
				return device;
			}
		}
		throw new AssertionError("No device " + Integer.toHexString(address));
	}

	private static int receiver(GroupDevice device, String address) {
		for (GroupObject object : device.objects()) {
			if (object.listensTo(KnxBinding.groupAddress(address))) {
				return object.number();
			}
		}
		throw new AssertionError("No object of " + device + " receives " + address);
	}

	private static String read(String path) throws Exception {
		return Files.readString(new File(path).toPath(), StandardCharsets.UTF_8);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestTestHome.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE, Simulations.Module.INSTANCE));
	}

}
