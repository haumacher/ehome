/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.runtime.TestFunctionProposal.apply;

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
import de.haumacher.ehome.runtime.FunctionProposal.Proposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeCatalog;
import de.haumacher.ehome.zigbee.ZigbeeCatalogJson;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;

/**
 * Zigbee remotes as buttons (haumacher/ehome #70, homecontrol <code>spec/runtime-image.md</code> 3.5,
 * schema 1.4).
 *
 * <p>
 * A remote of the catalog (a type of this test, "E-Home test" <code>REMOTE-1</code>) with its action
 * bound: the proposal is a button named for the device, the action's datapoint as its press, keyed
 * <code>zigbee:&lt;ieee&gt;:&lt;endpoint&gt;:action</code>; proposed twice it is created once; a
 * dismissal keeps it away, also across a new import of the export. The action a type of the catalog
 * hides (a sensor's controller endpoint) gives no button. The image carries the remote's
 * <code>presses</code> and is valid; E-Home's validator refuses what the controller's loader refuses
 * (the corpus cases <code>accept-button-presses</code> and <code>refuse-button-presses</code> run in
 * {@link TestImageConformance}; here an unknown kind with the hint of a newer schema).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeRemoteProposal extends BasicTestCase {

	/** The IEEE address of the remote of {@link #EXPORT}. */
	public static final String REMOTE = "00000000000000b1";

	/** The key of the remote's button. */
	public static final String REMOTE_CHANNEL = FunctionProposal.ZIGBEE_CHANNEL + REMOTE + ":1:action";

	private static final String CATALOG = """
		{"format": "ehome-zigbee-catalog", "version": 1, "types": [
			{"manufacturer": "E-Home test", "model": "REMOTE-1", "name": {"de": "Fernbedienung (Test)", "en": "Test remote"},
			 "functions": [{"endpoint": 1, "function": "action", "name": {"de": "Tasten", "en": "Buttons"}, "type": "enumeration"}]},
			{"manufacturer": "E-Home test", "model": "SENSOR-1", "name": {"de": "Melder (Test)", "en": "Test sensor"},
			 "functions": [{"endpoint": 1, "function": "action", "hidden": true},
			   {"endpoint": 2, "function": "occupancy", "name": {"de": "Bewegung", "en": "Motion"}, "type": "boolean"}]}]}""";

	private static final String EXPORT = """
		{"format": "homecontrol-zigbee-export", "version": 1, "devices": [
			{"ieee": "00000000000000b1", "label": "Fernbedienung Flur", "manufacturer": "E-Home test", "model": "REMOTE-1",
			 "interviewed": true, "functions": [
				{"function": "action", "endpoint": 1, "cluster": 6, "type": "enumeration", "writable": false}]},
			{"ieee": "00000000000000b2", "label": "Melder Flur", "manufacturer": "E-Home test", "model": "SENSOR-1",
			 "interviewed": true, "functions": [
				{"function": "action", "endpoint": 1, "cluster": 6, "type": "enumeration", "writable": false},
				{"function": "occupancy", "endpoint": 2, "cluster": 1030, "attribute": 0, "type": "boolean", "writable": false}]}
		 ]}""";

	private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

	@SuppressWarnings("unchecked")
	public void testRemote() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		List<TLObject> types = new ArrayList<>();
		try {
			types.addAll(createRemote(project));
			TLObject action = datapoint(project, REMOTE);

			List<Proposal> proposals = FunctionProposal.propose(project);
			assertEquals(List.of("button " + REMOTE_CHANNEL, "sensor zigbee:00000000000000b2:2:occupancy"),
				proposals.stream().map(p -> p.kind() + " " + p.channel()).toList());
			Proposal button = proposals.get(0);
			assertEquals(Map.of("press", action), button.roles());
			assertEquals(Map.of("de", "Fernbedienung Flur", "en", "Fernbedienung Flur"), button.label());

			// Applied: the button once; proposed again, nothing is created twice.
			List<TLObject> added = apply(project);
			assertEquals(2, added.size());
			TLObject remote = added.get(0);
			assertEquals("button", FunctionProposal.kind(remote));
			assertSame(action, remote.tValueByName("press"));
			assertNull(remote.tValueByName("state"));
			assertNull(remote.tValueByName("device"));
			assertEquals(REMOTE_CHANNEL, remote.tValueByName("channel"));
			assertEquals("Fernbedienung Flur", Resources.getInstance(Locale.GERMAN).getString((ResKey) remote.tValueByName("name")));
			assertEquals(List.of(), apply(project));
			assertEquals(List.of(), apply(project));
			assertEquals(2, FunctionProposal.functions(project).size());

			// The image: schema 1.4, the button with the remote's presses, valid.
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			Map<?, ?> json = image.getJson();
			assertEquals("1.6", ((Map<?, ?>) json.get("image")).get("schemaVersion"));
			Map<?, ?> function = (Map<?, ?>) ((List<?>) json.get("functions")).get(0);
			assertEquals("button", function.get("kind"));
			assertEquals(Map.of("press", ApplicationDatapoints.id(action)), function.get("datapoints"));
			assertEquals(RuntimeImage.ACTION_PRESSES, function.get("presses"));
			assertEquals(List.of("press", "on", "off", "up", "down", "release"),
				List.copyOf(((Map<?, ?>) function.get("presses")).keySet()));
			assertNull(((Map<?, ?>) ((List<?>) json.get("functions")).get(1)).get("presses"));

			// The validator mirrors the loader: an unknown kind of press, with the hint of a newer schema.
			Map<String, Object> changed = copy(json);
			Map<String, Object> first = (Map<String, Object>) ((List<Object>) changed.get("functions")).get(0);
			Map<String, Object> presses = new LinkedHashMap<>(RuntimeImage.ACTION_PRESSES);
			presses.put("longPress", List.of(Long.valueOf(3)));
			first.put("presses", presses);
			ImageReport refused = ImageValidator.validate(Json.write(changed));
			assertEquals(1, refused.getRefusals().size());
			assertEquals("functions[0].presses", refused.getRefusals().get(0).path());
			assertEquals("malformed: unknown member 'longPress'", refused.getRefusals().get(0).reason());
			((Map<String, Object>) changed.get("image")).put("schemaVersion", "1.7");
			refused = ImageValidator.validate(Json.write(changed));
			assertEquals("malformed: unknown member 'longPress' (the image's schema 1.7 is newer than this firmware "
				+ "reads, up to 1.6: update the firmware)", refused.getRefusals().get(0).reason());

			// Dismissed: it stays away, also after the export is imported again.
			TLObject dismissal;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				dismissal = FunctionProposal.dismiss(remote);
				tx.commit();
			}
			assertNotNull(dismissal);
			assertEquals(REMOTE_CHANNEL, dismissal.tValueByName("channel"));
			assertEquals(List.of(), apply(project));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.parse(EXPORT));
				tx.commit();
			}
			assertEquals(List.of(), apply(project));
			assertEquals(1, FunctionProposal.functions(project).size());
		} finally {
			SharedData.deleteProjects(List.of(project));
			delete(types);
		}
	}

	/**
	 * Imports the remote and the sensor of {@link #EXPORT} into the project, with the types of
	 * {@link #CATALOG}, and binds every function of them (the hidden action of the sensor included).
	 *
	 * @return The catalog types created, which the caller deletes.
	 */
	public static List<TLObject> createRemote(TLObject project) {
		List<TLObject> types = new ArrayList<>();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (String model : new String[] { "REMOTE-1", "SENSOR-1" }) {
				TLObject existing = ZigbeeCatalog.type(ZigbeeCatalog.root(), "E-Home test", model);
				if (existing != null) {
					existing.tDelete();
				}
			}
			types.addAll(ZigbeeCatalogJson.importText(ZigbeeCatalog.root(), CATALOG, ZigbeeCatalogJson.Mode.UPDATE).types());
			ZigbeeImport.importExport(project, ZigbeeExport.parse(EXPORT));
			for (TLObject device : ZigbeeImport.devices(project)) {
				for (Object function : (Collection<?>) device.tValueByName("functions")) {
					ZigbeeBindings.createDatapoint((TLObject) function);
				}
			}
			tx.commit();
		}
		return types;
	}

	/** The datapoint bound to the action of the device. */
	private static TLObject datapoint(TLObject project, String ieee) {
		for (TLObject datapoint : ApplicationDatapoints.of(project)) {
			TLObject function = ZigbeeBindings.function(datapoint);
			if ("action".equals(function.tValueByName("function")) && ieee.equals(function.tContainer().tValueByName("ieee"))) {
				return datapoint;
			}
		}
		throw new AssertionError("No action datapoint of " + ieee);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> copy(Map<?, ?> json) throws Exception {
		return (Map<String, Object>) Json.parse(Json.write(json), 100);
	}

	/** Deletes the catalog types of {@link #createRemote(TLObject)}. */
	public static void delete(List<TLObject> types) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject type : types) {
				if (type.tValid()) {
					type.tDelete();
				}
			}
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeRemoteProposal.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
