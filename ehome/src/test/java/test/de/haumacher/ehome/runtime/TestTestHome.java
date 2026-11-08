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

/**
 * The test home of the KNX test system (<code>samples/KNX-Testsystem.home.json</code>,
 * <code>doc/test-home.md</code>): the project <code>samples/KNX-Testsystem.knxproj</code>, the Hue
 * smart plug "Hue Steckdose" of the controller's Zigbee network (the controller's export,
 * <code>src/test/fixtures/zigbee/hue-steckdose.export.json</code>, imported as a file import does),
 * the sample's rules, schedule entries, application datapoint and location imported in the tab
 * "Rules" ({@link RuleImport#importImage(TLObject, String)}).
 *
 * <ul>
 * <li>The project leaves only the room temperature (0/0/28) without a datapoint type, which a rule
 * needs; the test sets 9.001 as the dialog "Set datapoint type" does. The keys 1 to 4 and 6, whose
 * switching objects the rules feed with the state they switch, are set writable as the datapoint
 * settings of the address do; every other address the rules write is writable as the project's
 * objects give it.</li>
 * <li>The import creates the writable datapoint <code>steckdose.on</code> bound to the plug's on/off,
 * the project's location and time zone; a second import keeps the datapoint and says so.</li>
 * <li>The image is valid with every rule loaded, and its rules, schedule entries and datapoints are
 * the sample's (the round trip).</li>
 * <li>The simulation runs the scenarios of the hardware checklist, starting Monday 2026-10-19 12:00
 * in Nürnberg: frost by the Tastsensor's room temperature, keys, LEDs, key 6 switching the plug and
 * LED 5 and the key's own object following the plug (as the keys 1 to 4 follow the channels), the alarm of a silent plug, cleared by its next report and by the
 * acknowledgement, dusk, the night and the morning.</li>
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

	private static final String ROOM_TEMPERATURE = "0/0/28";

	private static final String LED_PLUG = "0/0/11";

	private static final String KEY_PLUG = "0/0/6";

	/** The keys whose switching objects the rules write (the "Writable" setting of the address). */
	private static final List<String> KEYS = List.of("Taste1", "Taste2", "Taste3", "Taste4", "Taste6");

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
		assertTrue(notes.get(0), notes.get(0).contains("Raumtemperatur (0/0/28)"));

		TLObject temperature = SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
			.filter(a -> "Raumtemperatur".equals(a.tValueByName("name"))).findFirst().orElseThrow();
		TLObject type = instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> "DPST-9-1".equals(t.tValueByName("extId"))).findFirst().orElseThrow();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			GroupAddressTypes.setType(temperature, type);
			// The keys' switching objects transmit on their addresses, so the derivation does not make
			// them writable; the rules feed the channels' and the plug's state back to them.
			for (TLObject key : SharedData.within("ehome.logical:GroupAddress", List.of(project))) {
				if (KEYS.contains(key.tValueByName("name"))) {
					key.tUpdateByName("runtimeWritable", Boolean.TRUE);
				}
			}
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
		assertEquals(8, imported.rules().size());
		assertEquals(6, imported.schedules().size());
		assertEquals(1, imported.datapoints().size());
		// The location and the time zone set; nothing else to say.
		assertEquals(importer.getNotes().toString(), 2, importer.getNotes().size());
		assertEquals(Double.valueOf(49.45), project.tValueByName("latitude"));
		assertEquals(Double.valueOf(11.08), project.tValueByName("longitude"));
		assertEquals("CET-1CEST,M3.5.0,M10.5.0/3", project.tValueByName("timeZone"));

		TLObject plug = imported.datapoints().get(0);
		assertEquals(PLUG, ApplicationDatapoints.id(plug));
		assertTrue(ApplicationDatapoints.writable(plug));
		TLObject function = ZigbeeBindings.function(plug);
		assertNotNull(function);
		assertEquals("on_off", function.tValueByName("function"));
		assertEquals("001788010f069c8e", ZigbeeBindings.device(function).tValueByName("ieee"));

		// Imported again, nothing is added: the rules, entries and datapoints are kept and named, the
		// location and time zone are the same.
		RuleImport again = new RuleImport();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			RuleImport.Imported second = again.importImage(project, text);
			assertEquals(0, second.rules().size() + second.schedules().size() + second.datapoints().size());
			tx.commit();
		}
		assertEquals(again.getNotes().toString(), 15, again.getNotes().size());
		assertEquals(1, ApplicationDatapoints.of(project).size());
		assertEquals(8, ((java.util.Collection<?>) project.tValueByName("rules")).size());
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
		assertEquals(8, simulation.rules().rules().size());
		GroupDevice button = device(running, 0x1332);
		GroupDevice actuator = device(running, 0x1305);

		// At noon the start sets channel B off (between dawn and dusk).
		assertEquals(Val.ofBool(false), simulation.value(B));

		// frost: the Tastsensor's room temperature below 17 °C switches C on, from 17.5 °C off.
		// The actuator's status telegrams of C arrive in between, so the steps wait for the room
		// temperature to arrive rather than count telegrams, each after the telegrams of the step
		// before have left (on a loaded CI machine a value sent into them did not arrive in 5 s).
		for (String[] step : new String[][] { { "16.5", "true" }, { "17.2", "true" }, { "17.6", "false" } }) {
			simulation.awaitSent();
			running.send(ROOM_TEMPERATURE, step[0]);
			awaitValue(simulation, ROOM_TEMPERATURE, Val.ofReal(Double.parseDouble(step[0])));
			assertEquals(step[0], Val.ofBool(Boolean.parseBoolean(step[1])), simulation.value(C));
		}

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

		// leds: the state of channel A lights LED 1 and is fed back to key 1.
		received = simulation.received();
		running.send("0/0/37", "true");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value("0/0/7"));
		assertTrue(button.awaitValue(receiver(button, "0/0/7"), new byte[] { 1 }, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value("0/0/1"));

		// plug: key 6 switches the plug with its value, LED 5 shows the plug's state, also when the plug
		// is switched at its own button.
		received = simulation.received();
		running.send(KEY_PLUG, "true");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value(PLUG));
		assertEquals(Val.ofBool(true), simulation.value(LED_PLUG));
		assertTrue(button.awaitValue(receiver(button, LED_PLUG), new byte[] { 1 }, TIMEOUT));
		running.send(PLUG, "false");
		assertEquals(Val.ofBool(false), simulation.value(LED_PLUG));
		assertTrue(button.awaitValue(receiver(button, LED_PLUG), new byte[] { 0 }, TIMEOUT));
		// The key's own switching object learns it too, so a toggling key sends "on" next.
		assertEquals(Val.ofBool(false), simulation.value(KEY_PLUG));
		assertTrue(button.awaitValue(receiver(button, KEY_PLUG), new byte[] { 0 }, TIMEOUT));

		// plug-watch: a plug silent for 15 min raises the alarm, its next report clears it; silent
		// again, the acknowledgement clears it.
		simulation.advance(15 * 60_000);
		assertEquals(Val.ofBool(true), simulation.value(ALARM));
		assertLogged(simulation, "Die Steckdose meldet seit 15min nichts.");
		running.send(PLUG, "false");
		assertEquals(Val.ofBool(false), simulation.value(ALARM));
		assertLogged(simulation, "Die Steckdose meldet sich wieder.");
		simulation.advance(15 * 60_000);
		assertEquals(Val.ofBool(true), simulation.value(ALARM));
		received = simulation.received();
		running.send("0/0/30", "true");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertEquals(Val.ofBool(false), simulation.value(ALARM));
		assertLogged(simulation, "Alarm quittiert.");

		// 19:00: B on since dusk; the presence simulation is disabled and has not toggled it.
		advanceTo(simulation, 0, 19, 0);
		assertEquals(Val.ofBool(true), simulation.value(B));
		assertTrue(simulation.status("dusk-dawn").lastTrigger(), simulation.status("dusk-dawn").lastTrigger().contains("dusk"));
		assertEquals(0, simulation.status("presence-sim").firings());

		// 22:00: every channel off, the night setback on.
		assertEquals(Val.ofBool(true), simulation.value(A));
		advanceTo(simulation, 0, 22, 1);
		for (String channel : List.of(A, B, C, D)) {
			assertEquals(channel, Val.ofBool(false), simulation.value(channel));
		}
		assertEquals(Val.ofBool(true), simulation.value(NIGHT_SETBACK));
		assertTrue(button.awaitValue(receiver(button, NIGHT_SETBACK), new byte[] { 1 }, TIMEOUT));
		// Key 1, which switched A on, learns from A's status (sent here, as the actuator does) that A
		// is off, so a toggling key 1 sends "on" next.
		long beforeStatus = simulation.received();
		running.send("0/0/37", "false");
		assertTrue(simulation.awaitReceived(beforeStatus + 1, TIMEOUT));
		assertEquals(Val.ofBool(false), simulation.value("0/0/1"));
		assertTrue(button.awaitValue(receiver(button, "0/0/1"), new byte[] { 0 }, TIMEOUT));

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

	private static void awaitValue(Simulation simulation, String datapoint, Val expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 4 * TIMEOUT;
		while (!expected.equals(simulation.value(datapoint)) && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertEquals(datapoint, expected, simulation.value(datapoint));
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
