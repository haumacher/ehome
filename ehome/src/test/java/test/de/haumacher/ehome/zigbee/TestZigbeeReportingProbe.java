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

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeReporting;
import de.haumacher.ehome.zigbee.ZigbeeStatus;

/**
 * Probe of the reporting overrides composed with the rest of the Zigbee round (haumacher/ehome #64):
 * an override on an unbound function never reaches the image (only bound functions are in it) and
 * follows the function when it is bound later; the installer's value goes into the image as
 * written (the controller rounds, E-Home does not); ZCL's "on a change only" maximum with a minimum
 * is accepted and written as both members; a function the controller stopped exporting keeps its
 * override while absent and the cleanup removes it with the function; the validator refuses an image
 * whose reporting was written by hand with the controller's path and text.
 */
@SuppressWarnings("javadoc")
public class TestZigbeeReportingProbe extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static final String FIXTURE = "src/test/fixtures/zigbee/motion-sensor.export.json";

	public void testOverridesFollowBindings() throws Exception {
		String text = Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8);
		TLObject project = project("Zigbee reporting probe", "P-TEST-64-REPORTING-PROBE");
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.parse(text));
				tx.commit();
			}
			TLObject device = ZigbeeImport.devices(project).get(0);
			TLObject temperature = function(device, "temperature");
			TLObject illuminance = function(device, "illuminance");
			TLObject battery = function(device, "battery");

			// Overrides on unbound functions: stored, but no datapoint carries them into the image.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				temperature.tUpdateByName(ZigbeeReporting.MIN, Integer.valueOf(60));
				temperature.tUpdateByName(ZigbeeReporting.MAX, Integer.valueOf(ZigbeeReporting.NO_PERIODIC));
				battery.tUpdateByName(ZigbeeReporting.CHANGE, Double.valueOf(0.5));
				illuminance.tUpdateByName(ZigbeeReporting.MAX, Integer.valueOf(1200));
				tx.commit();
			}
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			assertFalse(image.getText(), image.getText().contains("\"reporting\""));

			// Bound: the overrides travel with their function, as written (0.5 % battery stays 0.5, the
			// controller rounds to its half-percent step; max 0 with min 60 is "on a change only").
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeBindings.createDatapoint(temperature);
				ZigbeeBindings.createDatapoint(battery);
				ZigbeeBindings.createDatapoint(illuminance);
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			Map<String, Map<String, Object>> reporting = reportingByFunction(image);
			assertEquals(map("min", Long.valueOf(60), "max", Long.valueOf(0)), reporting.get("temperature"));
			assertEquals(map("change", Double.valueOf(0.5)), reporting.get("battery"));
			assertEquals(map("max", Long.valueOf(1200)), reporting.get("illuminance"));

			// The controller stops exporting the battery: the function is absent, its override and
			// binding stay, the image still carries it (the device may come back); the cleanup refuses
			// while it is bound, removes it once unbound.
			Map<?, ?> reduced = (Map<?, ?>) Json.parse(text, 100);
			List<Object> functions = new ArrayList<>((List<?>) ((Map<?, ?>) ((List<?>) reduced.get("devices")).get(0)).get("functions"));
			functions.removeIf(f -> "battery".equals(((Map<?, ?>) f).get("function")));
			((Map<String, Object>) ((List<?>) reduced.get("devices")).get(0)).put("functions", functions);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.read(reduced));
				tx.commit();
			}
			assertEquals(Boolean.TRUE, battery.tValueByName("absent"));
			assertEquals(Double.valueOf(0.5), battery.tValueByName(ZigbeeReporting.CHANGE));
			assertEquals(map("change", Double.valueOf(0.5)), reportingByFunction(RuntimeImage.build(project, 1, NOW)).get("battery"));
			assertNull(ZigbeeStatus.removeAbsentVeto(device));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeStatus.removeAbsent(device);
				tx.commit();
			}
			assertTrue("Bound: kept.", battery.tValid());
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeBindings.unbind(battery);
				ZigbeeStatus.removeAbsent(device);
				tx.commit();
			}
			assertFalse(battery.tValid());
			assertNull(reportingByFunction(RuntimeImage.build(project, 1, NOW)).get("battery"));

			// A refused override on a bound function cannot be committed, so the image never carries it.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				illuminance.tUpdateByName(ZigbeeReporting.CHANGE, Double.valueOf(50));
				tx.commit();
				fail("The controller refuses a change in lux.");
			} catch (TopLogicException ex) {
				// Refused at the commit.
			}
			assertNull(illuminance.tValueByName(ZigbeeReporting.CHANGE));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testHandWrittenImageIsRefusedLikeTheController() throws Exception {
		String image = """
			{"image": {"schemaVersion": "1.0", "projectId": "probe", "revision": 1},
			"datapoints": [
			{"id": "hall.presence", "type": "boolean",
			"zigbee": {"ieee": "0017880106498a49", "endpoint": 2, "cluster": 1030, "attribute": 0,
			"reporting": {"min": 10, "change": 1}}},
			{"id": "hall.button", "type": "enumeration",
			"zigbee": {"ieee": "0017880106498a49", "endpoint": 1, "cluster": 6, "client": true,
			"reporting": {"max": 60}}}]}
			""";
		ImageReport validation = ImageValidator.validate(image);
		String report = validation.toString();
		assertFalse(report, validation.isValid());
		assertTrue(report, report.contains("datapoints[0].zigbee.reporting.change"));
		assertTrue(report, report.contains("the function occupancy has no reportable change (a discrete value reports every change)"));
		assertTrue(report, report.contains("datapoints[1].zigbee.reporting"));
		assertTrue(report, report.contains("the function action (a client binding, button commands) configures no reporting"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, Object>> reportingByFunction(RuntimeImage image) throws Exception {
		Map<?, ?> json = (Map<?, ?>) Json.parse(image.getText(), 100);
		Map<String, Map<String, Object>> result = new LinkedHashMap<>();
		for (Object item : (List<?>) json.get("datapoints")) {
			Map<?, ?> dp = (Map<?, ?>) item;
			Map<?, ?> zigbee = (Map<?, ?>) dp.get("zigbee");
			if (zigbee == null) {
				continue;
			}
			long cluster = ((Number) zigbee.get("cluster")).longValue();
			String function = cluster == 1026 ? "temperature" : cluster == 1024 ? "illuminance" : cluster == 1 ? "battery"
				: cluster == 1030 ? "occupancy" : "other";
			result.put(function, (Map<String, Object>) zigbee.get("reporting"));
		}
		return result;
	}

	private static TLObject function(TLObject device, String key) {
		for (Object function : (Iterable<?>) device.tValueByName("functions")) {
			if (key.equals(((TLObject) function).tValueByName("function"))) {
				return (TLObject) function;
			}
		}
		throw new AssertionError("No function " + key);
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

	private static Map<String, Object> map(Object... keyValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int n = 0; n < keyValues.length; n += 2) {
			result.put((String) keyValues[n], keyValues[n + 1]);
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeReportingProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
