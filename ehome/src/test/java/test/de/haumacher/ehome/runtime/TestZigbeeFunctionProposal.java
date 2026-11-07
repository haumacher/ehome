/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.apply;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.FunctionProposal.Proposal;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeCatalog;
import de.haumacher.ehome.zigbee.ZigbeeCatalogJson;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;

/**
 * Functions proposed for the bound Zigbee datapoints of a project (haumacher/ehome #68).
 *
 * <ul>
 * <li>The test home (<code>doc/test-home.md</code>): the KNX test system with the hallway sensor "Flur
 * Bewegung" (the controller's export <code>src/test/fixtures/zigbee/flur-bewegung.export.json</code>,
 * the Philips type of the catalog) and the four datapoints of <code>samples/KNX-Testsystem.home.json</code>
 * bound to it (the sample's plug datapoint left out): four sensors named by the device and the catalog's names of the functions, in the
 * device's room, keyed by IEEE address, endpoint and function; the KNX proposals are the ones without
 * the sensor. A dismissed one stays dismissed across runs and a new import of the export.</li>
 * <li>Devices of an export written here: on/off with level on one endpoint is a dimmer, on/off alone
 * a switch, a light where the catalog type is named for one, an action a button (haumacher/ehome #70,
 * {@link TestZigbeeRemoteProposal}); an unbound function and an on/off datapoint that is not writable
 * give nothing. The image with the functions is valid.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeFunctionProposal extends BasicTestCase {

	private static final String SEED = "src/main/webapp/WEB-INF/zigbee-catalog/philips-sml002.json";

	private static final String SENSOR = "zigbee:0017880106498a49:2:";

	public void testTestHome() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		TLObject seeded = seedCatalog();
		try {
			List<String> knx = summary(FunctionProposal.propose(project));
			assertFalse(knx.isEmpty());

			TLObject hall = TestFunctionProposal.createLocation(project, null, "ehome.building:Room", "Flur");
			importExport(project);
			TLObject sensor = ZigbeeImport.devices(project).get(0);
			TestFunctionProposal.update(sensor, "location", hall);
			Map<?, ?> sample = (Map<?, ?>) Json.parse(
				Files.readString(Path.of("samples/KNX-Testsystem.home.json"), StandardCharsets.UTF_8), 100);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				// The hallway sensor's datapoints; the plug of the test home is not paired here.
				List<?> hallway = ((List<?>) sample.get("datapoints")).stream()
					.filter(d -> String.valueOf(((Map<?, ?>) d).get("id")).startsWith("flur.")).toList();
				new RuleImport().importImage(project, Json.write(Map.of("datapoints", hallway)));
				tx.commit();
			}
			Map<String, TLObject> datapoints = new LinkedHashMap<>();
			for (TLObject datapoint : ApplicationDatapoints.of(project)) {
				datapoints.put(ApplicationDatapoints.id(datapoint), datapoint);
			}
			assertEquals(4, datapoints.size());

			// The KNX proposals unchanged, the sensor's after them.
			List<Proposal> proposals = FunctionProposal.propose(project);
			List<Proposal> zigbee = proposals.stream().filter(p -> p.channel().startsWith(FunctionProposal.ZIGBEE_CHANNEL)).toList();
			assertEquals(knx, summary(proposals.subList(0, proposals.size() - zigbee.size())));
			assertEquals(List.of("sensor " + SENSOR + "temperature", "sensor " + SENSOR + "illuminance",
				"sensor " + SENSOR + "occupancy", "sensor " + SENSOR + "battery"), summary(zigbee));

			List<TLObject> added = apply(project);
			List<TLObject> functions = added.stream()
				.filter(f -> String.valueOf(f.tValueByName("channel")).startsWith(FunctionProposal.ZIGBEE_CHANNEL)).toList();
			assertEquals(knx.size() + 4, added.size());
			Map<String, String[]> expected = Map.of(
				"flur.temperature", new String[] { "Flur Bewegung: Temperatur", "Flur Bewegung: Temperature" },
				"flur.lux", new String[] { "Flur Bewegung: Helligkeit", "Flur Bewegung: Illuminance" },
				"flur.presence", new String[] { "Flur Bewegung: Bewegung", "Flur Bewegung: Motion" },
				"flur.battery", new String[] { "Flur Bewegung: Batterie", "Flur Bewegung: Battery" });
			assertEquals(4, functions.size());
			for (TLObject function : functions) {
				assertEquals("sensor", FunctionProposal.kind(function));
				TLObject value = (TLObject) function.tValueByName("value");
				String[] names = expected.get(ApplicationDatapoints.id(value));
				assertNotNull(names);
				ResKey name = (ResKey) function.tValueByName("name");
				assertEquals(names[0], Resources.getInstance(Locale.GERMAN).getString(name));
				assertEquals(names[1], Resources.getInstance(Locale.ENGLISH).getString(name));
				assertSame(hall, function.tValueByName("room"));
				assertNull(function.tValueByName("device"));
				assertEquals(SENSOR + ZigbeeBindings.function(value).tValueByName("function"), function.tValueByName("channel"));
			}
			assertEquals(List.of(), apply(project));

			// Dismissed: it stays away, also after the export is imported again.
			TLObject battery = functions.stream()
				.filter(f -> f.tValueByName("value") == datapoints.get("flur.battery")).findFirst().orElseThrow();
			TLObject dismissal;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				dismissal = FunctionProposal.dismiss(battery);
				tx.commit();
			}
			assertNotNull(dismissal);
			assertEquals(SENSOR + "battery", dismissal.tValueByName("channel"));
			assertEquals(List.of(), apply(project));
			importExport(project);
			assertSame(sensor, ZigbeeImport.devices(project).get(0));
			assertEquals(List.of(), apply(project));
			assertEquals(List.of(dismissal), List.copyOf((Collection<?>) project.tValueByName(FunctionProposal.DISMISSED)));
			assertEquals(knx.size() + 3, FunctionProposal.functions(project).size());

			// Restored: back once, as before.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				dismissal.tDelete();
				tx.commit();
			}
			List<TLObject> back = apply(project);
			assertEquals(1, back.size());
			assertSame(datapoints.get("flur.battery"), back.get(0).tValueByName("value"));
		} finally {
			SharedData.deleteProjects(projects);
			unseed(seeded);
		}
	}

	public void testKinds() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		TLObject lightType = null;
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				lightType = ZigbeeCatalogJson.importText(ZigbeeCatalog.root(), """
					{"format": "ehome-zigbee-catalog", "version": 1, "types": [
						{"manufacturer": "E-Home test", "model": "BULB-1",
					   "name": {"de": "Leuchte (Test)", "en": "Test bulb"}}]}""", ZigbeeCatalogJson.Mode.UPDATE)
					.types().get(0);
				ZigbeeImport.importExport(project, ZigbeeExport.parse("""
					{"format": "homecontrol-zigbee-export", "version": 1, "devices": [
						{"ieee": "00000000000000a1", "label": "Stehlampe", "interviewed": true, "functions": [
							{"function": "on_off", "endpoint": 11, "cluster": 6, "attribute": 0, "type": "boolean", "writable": true},
							{"function": "level", "endpoint": 11, "cluster": 8, "attribute": 0, "type": "real", "unit": "%", "writable": true}]},
						{"ieee": "00000000000000a2", "label": "Steckdose", "interviewed": true, "functions": [
							{"function": "on_off", "endpoint": 1, "cluster": 6, "attribute": 0, "type": "boolean", "writable": true},
							{"function": "on_off", "endpoint": 2, "cluster": 6, "attribute": 0, "type": "boolean", "writable": true}]},
						{"ieee": "00000000000000a3", "label": "Decke", "manufacturer": "E-Home test", "model": "BULB-1",
					   "interviewed": true, "functions": [
							{"function": "on_off", "endpoint": 1, "cluster": 6, "attribute": 0, "type": "boolean", "writable": true}]},
						{"ieee": "00000000000000a4", "label": "Taster", "interviewed": true, "functions": [
							{"function": "action", "endpoint": 1, "cluster": 6, "type": "enumeration", "writable": false}]}
					 ]}"""));
				for (TLObject device : ZigbeeImport.devices(project)) {
					for (TLObject function : functions(device)) {
						// The second endpoint of the socket stays unbound.
						if (!("Steckdose".equals(device.tValueByName("name"))
							&& ((Number) function.tValueByName("endpoint")).intValue() == 2)) {
							ZigbeeBindings.createDatapoint(function);
						}
					}
				}
				tx.commit();
			}
			assertEquals(5, ApplicationDatapoints.of(project).size());

			List<Proposal> proposals = FunctionProposal.propose(project);
			assertEquals(List.of("dimmer zigbee:00000000000000a1:11:on_off", "switch zigbee:00000000000000a2:1:on_off",
				"light zigbee:00000000000000a3:1:on_off", "button zigbee:00000000000000a4:1:action"), summary(proposals));
			assertEquals(Map.of("press", datapoint(project, "action", 1, "Taster")), proposals.get(3).roles());
			assertEquals(Map.of("de", "Taster", "en", "Taster"), proposals.get(3).label());
			Proposal dimmer = proposals.get(0);
			TLObject onOff = datapoint(project, "on_off", 11);
			TLObject level = datapoint(project, "level", 11);
			assertEquals(Map.of("levelCommand", level, "level", level, "command", onOff, "state", onOff), dimmer.roles());
			// A dimmer is named after the device; a function on one endpoint without the endpoint.
			assertEquals(Map.of("de", "Stehlampe", "en", "Stehlampe"), dimmer.label());
			assertEquals(Map.of("de", "Decke: Ein/Aus", "en", "Decke: On/Off"), proposals.get(2).label());
			// The socket has on/off on two endpoints (the second unbound): its name names the endpoint.
			assertEquals(Map.of("de", "Steckdose: Ein/Aus (Endpunkt 1)", "en", "Steckdose: On/Off (endpoint 1)"),
				proposals.get(1).label());
			assertEquals(Map.of("command", datapoint(project, "on_off", 1, "Steckdose"), "state",
				datapoint(project, "on_off", 1, "Steckdose")), proposals.get(1).roles());

			// Applied without a room (the devices have none); a datapoint used by a function is not
			// proposed again.
			List<TLObject> added = apply(project);
			assertEquals(4, added.size());
			for (TLObject function : added) {
				assertNull(function.tValueByName("room"));
			}
			// The controller takes them: the command roles are writable, the types fit.
			ImageReport report = RuntimeImage.build(project, 1, Instant.parse("2026-11-03T12:00:00Z")).validate();
			assertTrue(report.toString(), report.isValid());
			assertEquals(4, ((List<?>) RuntimeImage.build(project, 1, Instant.parse("2026-11-03T12:00:00Z"))
				.getJson().get("functions")).size());
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				added.get(1).tUpdateByName("channel", null);
				tx.commit();
			}
			assertEquals(List.of(), apply(project));

			// An on/off datapoint that is not writable cannot command: no function.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (TLObject function : added) {
					function.tDelete();
				}
				datapoint(project, "on_off", 1, "Steckdose").tUpdateByName("writable", Boolean.FALSE);
				level.tUpdateByName("writable", Boolean.FALSE);
				tx.commit();
			}
			assertEquals(List.of("switch zigbee:00000000000000a1:11:on_off", "light zigbee:00000000000000a3:1:on_off",
				"button zigbee:00000000000000a4:1:action"), summary(FunctionProposal.propose(project)));
		} finally {
			SharedData.deleteProjects(List.of(project));
			if (lightType != null) {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					lightType.tDelete();
					tx.commit();
				}
			}
		}
	}

	private static TLObject datapoint(TLObject project, String key, int endpoint) {
		return datapoint(project, key, endpoint, null);
	}

	private static TLObject datapoint(TLObject project, String key, int endpoint, String device) {
		for (TLObject datapoint : ApplicationDatapoints.of(project)) {
			TLObject function = ZigbeeBindings.function(datapoint);
			if (key.equals(function.tValueByName("function"))
				&& ((Number) function.tValueByName("endpoint")).intValue() == endpoint
				&& (device == null || device.equals(function.tContainer().tValueByName("name")))) {
				return datapoint;
			}
		}
		throw new AssertionError("No datapoint for " + key + " " + endpoint);
	}

	private static List<TLObject> functions(TLObject device) {
		List<TLObject> result = new ArrayList<>();
		for (Object function : (Collection<?>) device.tValueByName("functions")) {
			result.add((TLObject) function);
		}
		return result;
	}

	private static void importExport(TLObject project) throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeImport.importExport(project, ZigbeeExport.parse(Files.readString(
				Path.of("src/test/fixtures/zigbee/flur-bewegung.export.json"), StandardCharsets.UTF_8)));
			tx.commit();
		}
	}

	/**
	 * The kind and channel of each proposal, with its device for a KNX one.
	 */
	private static List<String> summary(List<Proposal> proposals) {
		return proposals.stream().map(p -> p.kind() + " "
			+ (p.channel().startsWith(FunctionProposal.ZIGBEE_CHANNEL) ? "" : p.device().tId() + " ") + p.channel()
			+ (p.channel().startsWith(FunctionProposal.ZIGBEE_CHANNEL) ? "" : " " + p.roles())).toList();
	}

	/**
	 * Adds the Philips type of the catalog where an earlier test removed it.
	 *
	 * @return The type added, <code>null</code> if the catalog had it.
	 */
	private static TLObject seedCatalog() throws Exception {
		if (ZigbeeCatalog.type(ZigbeeCatalog.root(), "Philips", "SML002") != null) {
			return null;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeCatalogJson.importText(ZigbeeCatalog.root(), Files.readString(Path.of(SEED), StandardCharsets.UTF_8),
				ZigbeeCatalogJson.Mode.UPDATE);
			tx.commit();
		}
		return ZigbeeCatalog.type(ZigbeeCatalog.root(), "Philips", "SML002");
	}

	private static void unseed(TLObject type) {
		if (type != null && type.tValid()) {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				type.tDelete();
				tx.commit();
			}
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeFunctionProposal.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
