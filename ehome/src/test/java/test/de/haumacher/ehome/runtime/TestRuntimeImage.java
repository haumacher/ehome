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
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The runtime image exported from the KNX test system (<code>samples/KNX-Testsystem.knxproj</code>),
 * compared with the hand-written template of the specification
 * (<code>src/test/fixtures/homecontrol/images/test-system.template.json</code>), and the image of the
 * demo project (<code>samples/EHomeDemo.knxproj</code>) checked against the conformance rules.
 *
 * <p>
 * The template is exact for the datapoints it lists: their value types, datapoint types and flags.
 * It names its own ids (<code>key1</code>, <code>chA</code>) where E-Home derives the id from the
 * group address, so the template's datapoints are compared with the exported datapoints of the
 * addresses they stand for ({@link #TEMPLATE_ADDRESSES}). The differences, each asserted:
 * </p>
 * <ul>
 * <li>The export has a datapoint for every group address of the project that has a datapoint type
 * (53 of 54), the template only for the 15 it uses.</li>
 * <li>The room temperature (<code>0/0/28 Raumtemperatur</code>) has no datapoint type in the
 * project: neither the address nor the Tastsensor's 2-byte object names one, and 2 bytes do not
 * decide the main type. E-Home leaves it out and says so in a note. With the type 9.001 set on the
 * address (as a user does in E-Home), it is exported as the template has it.</li>
 * <li>The status addresses carry the datapoint type the project gives them, 1.011 (state); the
 * template writes 1.001. Both are truth values.</li>
 * <li>The keys' objects of the Tastsensor name no datapoint type either; their size (1 bit) decides
 * the main type 1. E-Home writes the main type as <code>1.000</code> (the controller reads a sub
 * number it does not distinguish as its main type), the template <code>1.001</code>.</li>
 * <li>Channel D has no status address in the project: <code>chD.state</code> has no counterpart.</li>
 * <li><code>persist</code> and <code>history</code> of the room temperature are choices of the
 * template's author, settings of the address in E-Home that this test leaves unset
 * (<code>TestFunctionProposal</code> enters them). The unit (°C) is the datapoint type's.</li>
 * <li>Labels: the template's are hand-written in English, the export's are the names of the group
 * addresses (<code>Taste1</code>, <code>KanalA</code>).</li>
 * <li>Locations, functions, the time master, schedules, rules and the history budget are authored in
 * the template; the project has no building structure, no functions and no runtime settings, and
 * E-Home no rules or schedules yet, so the export has no locations and none of the others
 * (<code>TestFunctionProposal</code> adds the locations, functions and settings).</li>
 * <li><code>image</code>: <code>projectId</code> is the project's ID (<code>P-0563</code>), the
 * revision the one given, <code>generatedAt</code> the time of the export and the generator
 * E-Home; the template has no <code>generatedAt</code>.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestRuntimeImage extends BasicTestCase {

	/**
	 * The datapoints of the template by the group address of the test system they stand for.
	 */
	static final Map<String, String> TEMPLATE_ADDRESSES = Map.ofEntries(
		Map.entry("key1", "0/0/1"), Map.entry("key2", "0/0/2"), Map.entry("key3", "0/0/3"),
		Map.entry("key4", "0/0/4"), Map.entry("key5", "0/0/5"), Map.entry("key6", "0/0/6"),
		Map.entry("chA", "0/0/34"), Map.entry("chA.state", "0/0/37"),
		Map.entry("chB", "0/0/40"), Map.entry("chB.state", "0/0/43"),
		Map.entry("chC", "0/0/46"), Map.entry("chC.state", "0/0/47"),
		Map.entry("chD", "0/0/48"),
		Map.entry("temperature", "0/0/28"));

	/** The members of a datapoint of the template E-Home cannot derive (see the class comment). */
	static final List<String> AUTHORED = List.of("label", "persist", "history");

	/** The keys' datapoint type: the main type their size decides. */
	static final String KEY_DPT = "1.000";

	/** The datapoint type of the status addresses as the project names it (1.011 state). */
	static final String STATE_DPT = "1.011";

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	public void testTestSystem() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			RuntimeImage withoutType = RuntimeImage.build(project, 7, NOW);
			assertEquals(53, ((List<?>) withoutType.getJson().get("datapoints")).size());
			assertEquals(1, withoutType.getNotes().size());
			assertTrue(withoutType.getNotes().get(0), withoutType.getNotes().get(0).contains("0/0/28 Raumtemperatur"));
			assertNull(datapoint((List<?>) withoutType.getJson().get("datapoints"), "0/0/28"));

			setDatapointType(project, "Raumtemperatur", "DPST-9-1");
			RuntimeImage image = RuntimeImage.build(project, 7, NOW);
			dump(image, "KNX-Testsystem.runtime.json");
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			assertEquals(List.of(), report.getNotes());
			assertEquals(List.of(), image.getNotes());

			Map<String, Object> json = image.getJson();
			Map<?, ?> info = (Map<?, ?>) json.get("image");
			assertEquals("1.4", info.get("schemaVersion"));
			assertEquals("P-0563", info.get("projectId"));
			assertEquals(Long.valueOf(7), info.get("revision"));
			assertEquals("2026-10-18T12:00:00Z", info.get("generatedAt"));
			assertTrue(info.get("generator").toString().startsWith("E-Home"));
			assertEquals(List.of(), json.get("locations"));
			assertFalse(json.containsKey("functions"));
			assertFalse(json.containsKey("rules"));
			assertFalse(json.containsKey("schedules"));
			assertFalse(json.containsKey("knx"));

			List<?> datapoints = (List<?>) json.get("datapoints");
			assertEquals(54, datapoints.size());
			assertEquals(54, image.getDatapointSources().size());

			Map<?, ?> template = (Map<?, ?>) Json.parse(
				Files.readString(new File(TestImageConformance.IMAGES, "test-system.template.json").toPath(),
					StandardCharsets.UTF_8), 100);
			int compared = 0;
			for (Object entry : (List<?>) template.get("datapoints")) {
				Map<?, ?> expected = (Map<?, ?>) entry;
				String id = (String) expected.get("id");
				String address = TEMPLATE_ADDRESSES.get(id);
				if (id.equals("chD.state")) {
					assertNull("Channel D has no status address in the project.", address);
					continue;
				}
				assertNotNull(id, address);
				Map<?, ?> actual = datapoint(datapoints, address);
				assertNotNull(address, actual);
				for (Map.Entry<?, ?> member : expected.entrySet()) {
					String name = (String) member.getKey();
					if (AUTHORED.contains(name) || name.equals("id")) {
						continue;
					}
					if (name.equals("knx")) {
						Map<?, ?> knx = (Map<?, ?>) member.getValue();
						Map<?, ?> actualKnx = (Map<?, ?>) actual.get("knx");
						assertEquals(id + ".knx.dpt",
							id.startsWith("key") ? KEY_DPT : id.endsWith(".state") ? STATE_DPT : knx.get("dpt"),
							actualKnx.get("dpt"));
						assertEquals(id + ".knx.send", knx.containsKey("send") ? address : null, actualKnx.get("send"));
						assertEquals(id + ".knx.listen", knx.containsKey("listen") ? List.of(address) : null,
							actualKnx.get("listen"));
						continue;
					}
					assertEquals(id + "." + name, member.getValue(), actual.get(name));
				}
				// No flag of the export the template lacks.
				for (String flag : List.of("readable", "writable", "transmit", "readOnStart")) {
					assertEquals(id + "." + flag, expected.get(flag), actual.get(flag));
				}
				compared++;
			}
			assertEquals(14, compared);
			// The unit of the room temperature comes from its datapoint type.
			assertEquals("°C", datapoint(datapoints, "0/0/28").get("unit"));
			assertNull(datapoint(datapoints, "0/0/28").get("persist"));
			assertNull(datapoint(datapoints, "0/0/28").get("history"));
			assertEquals("Raumtemperatur", datapoint(datapoints, "0/0/28").get("label"));
			assertEquals("Taste1", datapoint(datapoints, "0/0/1").get("label"));

			// A path of the image leads to the group address it stands for.
			int index = datapoints.indexOf(datapoint(datapoints, "0/0/34"));
			TLObject address = image.elementAt("datapoints[" + index + "].knx.send");
			assertNotNull(address);
			assertEquals("KanalA", address.tValueByName("name"));
			assertNull(image.elementAt("image.projectId"));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public void testDemo() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/EHomeDemo.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			RuntimeImage image = RuntimeImage.build(projects.get(0), 1, NOW);
			dump(image, "EHomeDemo.runtime.json");
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			assertEquals(List.of(), image.getNotes());
			List<?> datapoints = (List<?>) image.getJson().get("datapoints");
			assertEquals(3, datapoints.size());
			assertEquals(List.of(), image.getJson().get("locations"));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * The building structure of a project becomes the locations: a building, its floor and the rooms
	 * of the floor, each with its parent, in the order of the structure (haumacher/ehome #71: the
	 * order of the file, which is neither that of the names nor that of the IDs), and a part without
	 * a type of its own as an area.
	 */
	public void testLocations() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestDetails.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			RuntimeImage image = RuntimeImage.build(projects.get(0), 1, NOW);
			dump(image, "TestDetails.runtime.json");
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			List<?> locations = (List<?>) image.getJson().get("locations");
			assertEquals(List.of(
				Map.of("id", "P-01A0-0_BP-1", "label", "Haus", "kind", "building"),
				Map.of("id", "P-01A0-0_BP-2", "label", "Erdgeschoss", "kind", "floor", "parent", "P-01A0-0_BP-1"),
				Map.of("id", "P-01A0-0_BP-5", "label", "Wohnzimmer", "kind", "room", "parent", "P-01A0-0_BP-2"),
				Map.of("id", "P-01A0-0_BP-3", "label", "Küche", "kind", "room", "parent", "P-01A0-0_BP-2"),
				Map.of("id", "P-01A0-0_BP-4", "label", "Verteiler", "kind", "area", "parent", "P-01A0-0_BP-2")),
				locations);
			for (int n = 0; n < locations.size(); n++) {
				TLObject part = image.elementAt("locations[" + n + "].parent");
				assertNotNull(part);
				assertEquals(((Map<?, ?>) locations.get(n)).get("label"), part.tValueByName("name"));
			}
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static void setDatapointType(TLObject project, String addressName, String typeId) {
		TLObject address = SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
			.filter(a -> addressName.equals(a.tValueByName("name"))).findFirst().orElseThrow();
		TLObject type = instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> typeId.equals(t.tValueByName("extId"))).findFirst().orElseThrow();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			address.tUpdateByName("datapointTypes", List.of(type));
			tx.commit();
		}
	}

	static Map<?, ?> datapoint(List<?> datapoints, String id) {
		for (Object entry : datapoints) {
			if (id.equals(((Map<?, ?>) entry).get("id"))) {
				return (Map<?, ?>) entry;
			}
		}
		return null;
	}

	private static void dump(RuntimeImage image, String name) throws Exception {
		File dir = new File("target/runtime");
		dir.mkdirs();
		Files.writeString(new File(dir, name).toPath(), image.getText(), StandardCharsets.UTF_8);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuntimeImage.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
