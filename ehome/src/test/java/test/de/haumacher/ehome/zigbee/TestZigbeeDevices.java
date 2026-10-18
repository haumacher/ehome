/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.runtime.FakeController;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.I18NConstants;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeController;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;

/**
 * The field round trip of Zigbee devices (haumacher/ehome #40): the export of a controller with one
 * device (<code>src/test/fixtures/zigbee/motion-sensor.export.json</code>, a Philips Hue motion sensor
 * SML002 paired with a test controller: a Home Automation endpoint 2 with occupancy, illuminance,
 * temperature and battery, a Light Link endpoint 1 whose On/Off client cluster is the function
 * <code>action</code>) imported into a project built without an ETS import, datapoints created for
 * two functions, and the runtime image with their Zigbee bindings.
 *
 * <p>
 * Importing the same file again changes nothing; a newer version and another format are refused
 * with the reason; a binding of the wrong type, a function bound twice and a writable datapoint of a
 * function that cannot be commanded are refusals of the image with the path. The same export read
 * from an in-process {@link FakeController} in pages, the pairing commands and the controller's
 * refusals (no coordinator, a wrong token).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeDevices extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static final String FIXTURE = "src/test/fixtures/zigbee/motion-sensor.export.json";

	private static final String IEEE = "0017880106498a49";

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	public void testRoundTrip() throws Exception {
		String text = Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8);
		TLObject project = project("Zigbee round trip", "P-TEST-40");
		try {
			String summary;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				summary = ZigbeeImport.importFile(project,
					BinaryDataFactory.createBinaryData(text.getBytes(StandardCharsets.UTF_8), "application/json", "export.json"));
				tx.commit();
			}
			assertTrue(summary, summary.contains(": 1 (1 "));

			List<TLObject> devices = ZigbeeImport.devices(project);
			assertEquals(1, devices.size());
			TLObject device = devices.get(0);
			assertEquals(IEEE, device.tValueByName("ieee"));
			assertEquals("Philips", device.tValueByName("manufacturer"));
			assertEquals("SML002", device.tValueByName("model"));
			assertEquals("The label is empty: the name is manufacturer and model.", "Philips SML002",
				device.tValueByName("name"));
			assertNull(device.tValueByName("label"));
			assertEquals(Boolean.TRUE, device.tValueByName("interviewed"));
			assertEquals("example", device.tValueByName("controllerName"));

			List<TLObject> endpoints = objects(device, "endpoints");
			assertEquals(2, endpoints.size());
			assertEndpoint(endpoints.get(0), 2, 260, 263, "0, 1, 3, 1030, 1024, 1026", "25");
			assertEndpoint(endpoints.get(1), 1, 49246, 2128, "0", "0, 3, 6, 8, 768, 4, 5");

			List<TLObject> functions = objects(device, "functions");
			assertEquals(5, functions.size());
			TLObject temperature = assertFunction(functions.get(0), "temperature", 2, 1026, 0, "real", "°C");
			assertFunction(functions.get(1), "illuminance", 2, 1024, 0, "real", "lx");
			TLObject occupancy = assertFunction(functions.get(2), "occupancy", 2, 1030, 0, "boolean", null);
			TLObject battery = assertFunction(functions.get(3), "battery", 2, 1, 33, "real", "%");
			TLObject action = assertFunction(functions.get(4), "action", 1, 6, null, "enumeration", null);
			assertEquals("zigbee." + IEEE + ".2.temperature", temperature.tValueByName("genericName"));

			// Datapoints for occupancy and temperature.
			TLObject occupancyDp;
			TLObject temperatureDp;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				occupancyDp = ZigbeeBindings.createDatapoint(occupancy);
				temperatureDp = ZigbeeBindings.createDatapoint(temperature);
				tx.commit();
			}
			assertEquals("philips-sml002-occupancy", occupancyDp.tValueByName("identifier"));
			assertEquals("philips-sml002-temperature", temperatureDp.tValueByName("identifier"));
			assertEquals("°C", temperatureDp.tValueByName("unit"));
			assertEquals(List.of(occupancyDp), ZigbeeBindings.boundDatapoints(occupancy));
			assertNotNull(ZigbeeBindings.bindVeto(occupancy));
			assertNull(ZigbeeBindings.bindVeto(battery));

			// The image: two datapoints with the bindings of the export.
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			List<?> datapoints = (List<?>) image.getJson().get("datapoints");
			assertEquals(2, datapoints.size());
			assertEquals(map("id", "philips-sml002-occupancy", "label", Map.of("de", "Philips SML002 occupancy", "en",
				"Philips SML002 occupancy"), "type", "boolean", "readOnStart", Boolean.TRUE, "zigbee",
				map("ieee", IEEE, "endpoint", Long.valueOf(2), "cluster", Long.valueOf(1030), "attribute", Long.valueOf(0))),
				datapoints.get(0));
			Map<?, ?> exported = (Map<?, ?>) datapoints.get(1);
			assertEquals("°C", exported.get("unit"));
			assertEquals(map("ieee", IEEE, "endpoint", Long.valueOf(2), "cluster", Long.valueOf(1026), "attribute",
				Long.valueOf(0)), exported.get("zigbee"));
			assertFalse(exported.containsKey("virtual"));
			assertSame(temperatureDp, image.elementAt("datapoints[1].zigbee"));

			// Importing the same file again changes nothing, and the user's name and room stay.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				device.tUpdateByName("name", "Bewegungsmelder Flur");
				tx.commit();
			}
			List<Object> before = ids(project);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.Result again = ZigbeeImport.importExport(project, ZigbeeExport.parse(text));
				assertEquals(0, again.created());
				assertEquals(List.of(), again.notes());
				tx.commit();
			}
			assertEquals(before, ids(project));
			assertEquals("Bewegungsmelder Flur", device.tValueByName("name"));
			assertEquals(List.of(occupancyDp), ZigbeeBindings.boundDatapoints(occupancy));

			// The action function: a client binding, an enumeration.
			TLObject actionDp;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				actionDp = ZigbeeBindings.createDatapoint(action);
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			Map<?, ?> client = (Map<?, ?>) datapoints(image).get(2);
			assertEquals("enumeration", client.get("type"));
			assertFalse(client.containsKey("readOnStart"));
			assertEquals(map("ieee", IEEE, "endpoint", Long.valueOf(1), "cluster", Long.valueOf(6), "client", Boolean.TRUE),
				client.get("zigbee"));

			// A binding of the wrong type: a refusal with the path, against the datapoint.
			update(occupancyDp, "type", valueType("real"));
			image = RuntimeImage.build(project, 1, NOW);
			assertRefusal(image, "datapoints[0].zigbee", "the function occupancy carries boolean values, the datapoint is real");
			assertSame(occupancyDp, image.elementAt("datapoints[0].zigbee"));
			update(occupancyDp, "type", valueType("boolean"));

			// A writable datapoint of a function that cannot be commanded.
			update(temperatureDp, "writable", Boolean.TRUE);
			image = RuntimeImage.build(project, 1, NOW);
			assertRefusal(image, "datapoints[1].zigbee", "a transmitting Zigbee datapoint needs the cluster its commands go to");
			update(temperatureDp, "writable", Boolean.FALSE);

			// A function bound twice (the binding commands refuse it; set directly, the image does).
			try {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					ZigbeeBindings.bind(temperature, actionDp);
					tx.commit();
				}
				fail("A bound function is bound again.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.VETO_BOUND__DATAPOINT.fill("philips-sml002-temperature"), ex.getErrorKey());
			}
			TLObject second;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				second = RuleFunctions.create(project, "applicationDatapoints",
					(TLClass) TLModelUtil.findType("ehome.runtime:ApplicationDatapoint"));
				second.tUpdateByName("identifier", "temperature.again");
				second.tUpdateByName("type", valueType("real"));
				second.tUpdateByName("zigbeeFunction", temperature);
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			assertRefusal(image, "datapoints[3].zigbee", "device " + IEEE
				+ " endpoint 2: the function temperature is bound twice (also datapoints[1] 'philips-sml002-temperature')");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				second.tDelete();
				tx.commit();
			}

			// A newer version and another format are refused with the reason.
			try {
				ZigbeeExport.parse(Files.readString(Path.of("src/test/fixtures/zigbee/newer-version.export.json")));
				fail("A newer version is read.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_EXPORT_VERSION__VERSION_SUPPORTED.fill(Long.valueOf(2), Integer.valueOf(1)),
					ex.getErrorKey());
			}
			try {
				ZigbeeExport.parse(image.getText());
				fail("A runtime image is read as export.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_EXPORT_FORMAT__FORMAT.fill(""), ex.getErrorKey());
			}

			// The export no longer lists temperature and battery: the bound one stays marked, the other goes.
			String fewer = text.replaceAll("(?s)\\{\\s*\"name\": \"zigbee\\.0017880106498a49\\.2\\.(temperature|battery)\".*?\\},?",
				"").replaceAll(",(\\s*)\\]", "$1]");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.Result result = ZigbeeImport.importExport(project, ZigbeeExport.parse(fewer));
				assertEquals(result.notes().toString(), 2, result.notes().size());
				tx.commit();
			}
			assertFalse(battery.tValid());
			assertTrue(temperature.tValid());
			assertEquals(Boolean.TRUE, temperature.tValueByName("absent"));
			assertEquals(4, objects(device, "functions").size());
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.parse(text));
				tx.commit();
			}
			assertFalse(Boolean.TRUE.equals(temperature.tValueByName("absent")));
			assertEquals(5, objects(device, "functions").size());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testController() throws Exception {
		String text = Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8);
		// A second device for a second page.
		String two = text.replace("\"devices\": [", "\"devices\": [" + device(text).replace(IEEE, "00158d0001020304") + ",");
		FakeController fake = new FakeController(TOKEN, 262144);
		TLObject project = project("Zigbee controller", "P-TEST-40-CONTROLLER");
		TLObject controller;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			controller = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			controller.tUpdateByName("name", "Fake");
			controller.tUpdateByName("host", "127.0.0.1");
			controller.tUpdateByName("port", Integer.valueOf(fake.getPort()));
			controller.tUpdateByName("token", TOKEN);
			project.tUpdateByName("controller", controller);
			tx.commit();
		}
		try {
			try {
				ZigbeeController.permit(project, ZigbeeController.PERMIT_SECONDS);
				fail("A controller without coordinator opens its network.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_UNAVAILABLE__CONTROLLER_REASON.fill("Fake",
					"Zigbee is not available on this platform"), ex.getErrorKey());
			}
			fake.setZigbeeExport(two, 1);
			ZigbeeController.permit(project, ZigbeeController.PERMIT_SECONDS);
			assertEquals(Integer.valueOf(254), fake.getPermitSeconds());
			ZigbeeController.permit(controller, 0);
			assertEquals(Integer.valueOf(0), fake.getPermitSeconds());

			String summary = ZigbeeController.read(project);
			assertTrue(summary, summary.contains(": 2 ("));
			List<TLObject> devices = ZigbeeImport.devices(project);
			assertEquals(2, devices.size());
			assertEquals("00158d0001020304", devices.get(0).tValueByName("ieee"));
			assertEquals(IEEE, devices.get(1).tValueByName("ieee"));
			assertEquals(5, objects(devices.get(1), "functions").size());
			List<Object> before = ids(project);
			ZigbeeController.read(project);
			assertEquals(before, ids(project));

			update(controller, "token", "hct_ffffffffffffffffffffffffffffffff");
			try {
				ZigbeeController.read(project);
				fail("A wrong token is accepted.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_TOKEN__CONTROLLER_ERROR.fill("Fake", "auth.token_invalid"), ex.getErrorKey());
			}
		} finally {
			fake.stop();
			SharedData.deleteProjects(List.of(project));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				controller.tDelete();
				tx.commit();
			}
		}
	}

	/** The text of the first device of the export. */
	private static String device(String export) {
		int start = export.indexOf('{', export.indexOf("\"devices\""));
		int depth = 0;
		for (int n = start; n < export.length(); n++) {
			char c = export.charAt(n);
			depth += c == '{' ? 1 : c == '}' ? -1 : 0;
			if (depth == 0) {
				return export.substring(start, n + 1);
			}
		}
		throw new AssertionError("No device in the export.");
	}

	private static TLObject project(String name, String extId) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", name);
			project.tUpdateByName("extId", extId);
			tx.commit();
			return project;
		}
	}

	private static void assertEndpoint(TLObject endpoint, int id, int profile, int deviceType, String in, String out) {
		assertEquals(Integer.valueOf(id), endpoint.tValueByName("endpoint"));
		assertEquals(Integer.valueOf(profile), endpoint.tValueByName("profile"));
		assertEquals(Integer.valueOf(deviceType), endpoint.tValueByName("deviceType"));
		assertEquals(in, endpoint.tValueByName("inputClusters"));
		assertEquals(out, endpoint.tValueByName("outputClusters"));
		assertNull(endpoint.tValueByName("zoneType"));
	}

	private static TLObject assertFunction(TLObject function, String key, int endpoint, int cluster, Integer attribute,
			String type, String unit) {
		assertEquals(key, function.tValueByName("function"));
		assertEquals(Integer.valueOf(endpoint), function.tValueByName("endpoint"));
		assertEquals(Integer.valueOf(cluster), function.tValueByName("cluster"));
		assertEquals(attribute, function.tValueByName("attribute"));
		assertEquals(type, ((TLClassifier) function.tValueByName("valueType")).getName());
		assertEquals(unit, function.tValueByName("unit"));
		assertEquals(Boolean.FALSE, function.tValueByName("writable"));
		return function;
	}

	private static void assertRefusal(RuntimeImage image, String path, String reason) {
		ImageReport report = image.validate();
		for (Refusal refusal : report.getRefusals()) {
			if (refusal.path().equals(path) && refusal.reason().contains(reason)) {
				return;
			}
		}
		fail("Expected refusal at " + path + " (" + reason + "): " + report);
	}

	private static List<?> datapoints(RuntimeImage image) {
		return (List<?>) image.getJson().get("datapoints");
	}

	/** The IDs of the project's Zigbee devices with their parts and of its application datapoints. */
	private static List<Object> ids(TLObject project) {
		List<Object> result = new ArrayList<>();
		for (TLObject device : ZigbeeImport.devices(project)) {
			result.add(device.tId());
			for (TLObject part : objects(device, "endpoints")) {
				result.add(part.tId());
			}
			for (TLObject part : objects(device, "functions")) {
				result.add(part.tId());
			}
		}
		for (TLObject datapoint : objects(project, "applicationDatapoints")) {
			result.add(datapoint.tId());
		}
		return result;
	}

	private static List<TLObject> objects(TLObject owner, String reference) {
		List<TLObject> result = new ArrayList<>();
		for (Object element : (Iterable<?>) owner.tValueByName(reference)) {
			result.add((TLObject) element);
		}
		return result;
	}

	private static void update(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static Object valueType(String name) {
		return ((com.top_logic.model.TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType"))
			.getClassifier(name);
	}

	private static Map<String, Object> map(Object... keyValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int n = 0; n < keyValues.length; n += 2) {
			result.put((String) keyValues[n], keyValues[n + 1]);
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeDevices.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
