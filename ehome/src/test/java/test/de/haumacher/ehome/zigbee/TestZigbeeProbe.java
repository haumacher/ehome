/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;

/**
 * Probe of the Zigbee import (haumacher/ehome #40) composed with the bindings and the image
 * validation: the controller may spell the IEEE address differently from one export to the next,
 * and its label may change while the user has named the device and bound a function.
 *
 * <p>
 * Costs well under a second: it imports the small fixture export, no ETS project.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeProbe extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static final String FIXTURE = "src/test/fixtures/zigbee/motion-sensor.export.json";

	private static final String IEEE = "0017880106498a49";

	public void testSpellingAndTypeChangeUnderABinding() throws Exception {
		String text = Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8);
		TLObject project = project("Zigbee probe", "P-TEST-40-PROBE");
		try {
			importText(project, text);
			List<TLObject> devices = ZigbeeImport.devices(project);
			assertEquals(1, devices.size());
			TLObject device = devices.get(0);
			TLObject temperature = function(device, "temperature");

			TLObject temperatureDp;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				temperatureDp = ZigbeeBindings.createDatapoint(temperature);
				tx.commit();
			}
			assertTrue(RuntimeImage.build(project, 1, NOW).validate().isValid());

			// The same device, its address in colons and capitals: the import matches it, nothing is doubled.
			String colons = text.replace("\"ieee\": \"" + IEEE + "\"", "\"ieee\": \"00:17:88:01:06:49:8A:49\"");
			assertTrue(colons.contains("8A:49"));
			importText(project, colons);
			assertEquals(1, ZigbeeImport.devices(project).size());
			assertSame(device, ZigbeeImport.devices(project).get(0));
			assertEquals(IEEE, device.tValueByName("ieee"));
			assertEquals(List.of(temperatureDp), ZigbeeBindings.boundDatapoints(temperature));
			assertEquals(5, objects(device, "functions").size());

			// The controller's label changed and the user named the device: the import takes the label
			// and leaves the name, the binding and the image untouched.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				device.tUpdateByName("name", "Melder Flur");
				tx.commit();
			}
			String labelled = text.replace("\"label\": \"\"", "\"label\": \"Flur\"");
			assertFalse("The fixture's empty label must be found for this probe.", labelled.equals(text));
			importText(project, labelled);
			assertEquals("Flur", device.tValueByName("label"));
			assertEquals("Melder Flur", device.tValueByName("name"));
			assertEquals(List.of(temperatureDp), ZigbeeBindings.boundDatapoints(temperature));
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			Map<?, ?> exported = (Map<?, ?>) ((List<?>) image.getJson().get("datapoints")).get(0);
			assertEquals("philips-sml002-temperature", exported.get("id"));
			assertEquals(IEEE, ((Map<?, ?>) exported.get("zigbee")).get("ieee"));

			// A datapoint of the wrong type under the binding is the validator's refusal, with the path.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				temperatureDp.tUpdateByName("type",
					((com.top_logic.model.TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier("boolean"));
				tx.commit();
			}
			assertRefusal(RuntimeImage.build(project, 1, NOW).validate(), "datapoints[0].zigbee", "the datapoint is boolean");
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static void importText(TLObject project, String text) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeImport.importExport(project, ZigbeeExport.parse(text));
			tx.commit();
		}
	}

	private static TLObject function(TLObject device, String key) {
		for (TLObject function : objects(device, "functions")) {
			if (key.equals(function.tValueByName("function"))) {
				return function;
			}
		}
		fail("No function " + key);
		return null;
	}

	private static void assertRefusal(ImageReport report, String path, String reason) {
		for (Refusal refusal : report.getRefusals()) {
			if (refusal.path().equals(path) && refusal.reason().contains(reason)) {
				return;
			}
		}
		fail("Expected refusal at " + path + " (" + reason + "): " + report);
	}

	private static List<TLObject> objects(TLObject owner, String reference) {
		List<TLObject> result = new ArrayList<>();
		for (Object element : (Iterable<?>) owner.tValueByName(reference)) {
			result.add((TLObject) element);
		}
		return result;
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

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
