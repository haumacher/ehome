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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The functions proposed for the KNX test system (<code>samples/KNX-Testsystem.knxproj</code>,
 * haumacher/ehome #43), compared with the functions of the specification's template
 * (<code>src/test/fixtures/homecontrol/images/test-system.template.json</code>), and the image of the
 * test system with the template's settings entered (#45).
 *
 * <p>
 * The user's part, done by the test as a user would: the room temperature gets its datapoint type
 * (9.001, the project has none), the building gets the template's two locations (the room "Test
 * system" with the actuator, the area "Push-button sensor" in it with the push-button sensor), the
 * project a time address (0/0/60, DPT 10.001; the test system has none) as time master, the history
 * budget, and the room temperature persistence and history.
 * </p>
 *
 * <p>
 * The proposal then matches the template's eleven functions in kind and role bindings. The
 * differences, each asserted:
 * </p>
 * <ul>
 * <li>Names and identifiers come from the objects of the devices, in the user's language from the
 * languages the program has: in English the actuator's channels are "Channel A" ...
 * (<code>channel-a</code>, as in the template), the keys "Taste 1" or, once a project brought the
 * program's English texts, "Button 1" (template <code>key-1</code>, "Key 1"), the temperature
 * "Raumtemperatur-Messung" (template <code>room-temperature</code>, "Temperature"). The functions
 * are therefore matched with the template's by kind and main role. The template's hand-written
 * remarks ("Key 3 (toggles C)", "Channel D (auto-off)") describe its rules.</li>
 * <li>Channel D has no status address in the project, so its light has no state, as in the
 * template.</li>
 * <li>The room temperature is measured by the push-button sensor, so its function is in the sensor's
 * area; the template puts it in the room.</li>
 * <li>The time master names 0/0/60 and writes <code>onSynchronized</code> (the specification's
 * default, true), which the template leaves out.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestFunctionProposal extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	/** The time address the test adds; the test system has none. */
	static final String TIME_ADDRESS = "0/0/60";

	public void testTestSystem() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			setDatapointType(address(project, "0/0/28"), "DPST-9-1");
			TLObject lab = createLocation(project, null, "ehome.building:Room", "Test system");
			TLObject panel = createLocation(project, lab, "ehome.building:BuildingPart", "Push-button sensor");
			update(device(project, "1.3.5"), "location", lab);
			update(device(project, "1.3.50"), "location", panel);

			List<TLObject> added = apply(project);
			assertEquals(11, added.size());
			assertEquals(added, FunctionProposal.functions(project));

			Map<?, ?> template = template();
			Map<String, String> templateRooms = Map.of("lab", "Test system", "panel", "Push-button sensor");
			// The proposed functions by kind and main role: their names (and identifiers) are the
			// texts of the objects in the language of the user, from the languages the program has.
			Map<String, TLObject> byMainRole = new HashMap<>();
			for (TLObject function : added) {
				String kind = FunctionProposal.kind(function);
				String main = FunctionKinds.mainRole(kind);
				byMainRole.put(kind + " " + text((TLObject) function.tValueByName(main)), function);
				assertEquals(FunctionProposal.slug(name(function)),
					function.tValueByName("identifier"));
			}
			Map<String, TLObject> byIdentifier = new HashMap<>();
			Map<String, String> identifiers = new HashMap<>();
			for (Object entry : (List<?>) template.get("functions")) {
				Map<?, ?> expected = (Map<?, ?>) entry;
				String id = (String) expected.get("id");
				String kind = (String) expected.get("kind");
				String main = (String) ((Map<?, ?>) expected.get("datapoints")).get(FunctionKinds.mainRole(kind));
				TLObject function = byMainRole.get(kind + " " + TestRuntimeImage.TEMPLATE_ADDRESSES.get(main));
				assertNotNull(id, function);
				byIdentifier.put(id, function);
				identifiers.put(id, (String) function.tValueByName("identifier"));
				assertEquals(id, expected.get("kind"), FunctionProposal.kind(function));
				Map<String, String> roles = new LinkedHashMap<>();
				for (String role : FunctionKinds.roles(FunctionProposal.kind(function))) {
					if (function.tValueByName(role) instanceof TLObject address) {
						roles.put(role, RuntimeImage.threeLevel(((GA) address.tValueByName("address")).getValue()));
					}
				}
				Map<String, String> expectedRoles = new LinkedHashMap<>();
				for (Map.Entry<?, ?> role : ((Map<?, ?>) expected.get("datapoints")).entrySet()) {
					String address = TestRuntimeImage.TEMPLATE_ADDRESSES.get(role.getValue());
					if (address != null) {
						expectedRoles.put((String) role.getKey(), address);
					}
				}
				assertEquals(id, expectedRoles, roles);
				String room = id.equals("room-temperature") ? "Push-button sensor"
					: templateRooms.get(expected.get("room"));
				assertEquals(id, room, ((TLObject) function.tValueByName("room")).tValueByName("name"));
				assertSame(id, function.tValueByName("room") == lab ? device(project, "1.3.5")
					: device(project, "1.3.50"), function.tValueByName("device"));
			}
			assertNull(byIdentifier.get("channel-d").tValueByName("state"));

			// Idempotent: a second run adds nothing, and the user's edits survive it.
			TLObject channelA = byIdentifier.get("channel-a");
			TLObject channelC = byIdentifier.get("channel-c");
			Object channelAName = channelA.tValueByName("name");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				channelA.tUpdateByName("name", FunctionProposal.text(Map.of("de", "Deckenlicht")));
				channelA.tUpdateByName("kind", FunctionProposal.classifier("switch"));
				channelC.tUpdateByName("state", null);
				channelC.tUpdateByName("room", null);
				tx.commit();
			}
			assertEquals(List.of(), apply(project));
			assertEquals(11, FunctionProposal.functions(project).size());
			assertEquals("Deckenlicht", name(channelA));
			assertEquals("switch", FunctionProposal.kind(channelA));
			assertNull(channelC.tValueByName("state"));
			assertNull(channelC.tValueByName("room"));

			// A function the user made for a key keeps the proposal from adding a second one for it,
			// once the proposed one is gone.
			TLObject key1 = byIdentifier.get("key-1");
			String key1Identifier = (String) key1.tValueByName("identifier");
			TLObject own;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				own = create("ehome.runtime:Function");
				own.tUpdateByName("name", FunctionProposal.text(Map.of("de", "Klingel")));
				own.tUpdateByName("kind", FunctionProposal.classifier("button"));
				own.tUpdateByName("press", key1.tValueByName("press"));
				List<TLObject> functions = FunctionProposal.functions(project);
				functions.remove(key1);
				functions.add(own);
				project.tUpdateByName("functions", functions);
				key1.tDelete();
				tx.commit();
			}
			assertEquals(List.of(), apply(project));
			assertEquals(11, FunctionProposal.functions(project).size());
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("functions", added.stream().filter(TLObject::tValid).toList());
				own.tDelete();
				tx.commit();
			}
			// The deleted proposal comes back with its identifier.
			List<TLObject> again = apply(project);
			assertEquals(1, again.size());
			assertEquals(key1Identifier, again.get(0).tValueByName("identifier"));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				channelA.tUpdateByName("name", channelAName);
				channelA.tUpdateByName("kind", FunctionProposal.classifier("light"));
				channelC.tUpdateByName("state", address(project, "0/0/47"));
				channelC.tUpdateByName("room", lab);
				tx.commit();
			}

			// The template's settings.
			TLObject time = createAddress(project, TIME_ADDRESS, "Uhrzeit", "DPST-10-1");
			TLObject temperature = address(project, "0/0/28");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("timeMasterTime", time);
				project.tUpdateByName("timeMasterInterval", Integer.valueOf(600));
				project.tUpdateByName("timeMasterAtMidnight", Boolean.TRUE);
				project.tUpdateByName("historyBudget", Integer.valueOf(16384));
				temperature.tUpdateByName("runtimePersist", Boolean.TRUE);
				temperature.tUpdateByName("historyRetention", Integer.valueOf(86400));
				temperature.tUpdateByName("historySpacing", Integer.valueOf(60));
				tx.commit();
			}
			assertEquals(List.of(time), List.of(RuntimeFunctions.groupAddresses(project).stream()
				.filter(a -> ((GA) a.tValueByName("address")).getValue() == 60).findFirst().orElseThrow()));

			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			Files.createDirectories(new File("target/runtime").toPath());
			Files.writeString(new File("target/runtime/KNX-Testsystem.functions.runtime.json").toPath(), image.getText(),
				StandardCharsets.UTF_8);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			assertEquals(List.of(), image.getNotes());
			Map<String, Object> json = image.getJson();

			// The time address is the time master's, no datapoint of the image.
			List<?> datapoints = (List<?>) json.get("datapoints");
			assertEquals(54, datapoints.size());
			assertNull(TestRuntimeImage.datapoint(datapoints, TIME_ADDRESS));
			Map<?, ?> expectedTimeMaster = new LinkedHashMap<>((Map<?, ?>) ((Map<?, ?>) template.get("knx")).get("timeMaster"));
			Map<String, Object> timeMaster = new LinkedHashMap<>();
			((Map<?, ?>) ((Map<?, ?>) json.get("knx")).get("timeMaster")).forEach((k, v) -> timeMaster.put((String) k, v));
			assertEquals(TIME_ADDRESS, timeMaster.remove("time"));
			assertEquals(Boolean.TRUE, timeMaster.remove("onSynchronized"));
			expectedTimeMaster.remove("time");
			assertEquals(expectedTimeMaster, timeMaster);
			assertEquals(template.get("history"), json.get("history"));
			assertFalse(json.containsKey("settings"));

			// The room temperature as the template has it.
			Map<?, ?> expectedTemperature = TestRuntimeImage.datapoint((List<?>) template.get("datapoints"), "temperature");
			Map<?, ?> actualTemperature = TestRuntimeImage.datapoint(datapoints, "0/0/28");
			for (String member : List.of("type", "unit", "readOnStart", "persist", "history")) {
				assertEquals(member, expectedTemperature.get(member), actualTemperature.get(member));
			}

			// The functions as the template has them, by the datapoint ids of the export.
			List<?> functions = (List<?>) json.get("functions");
			assertEquals(11, functions.size());
			Map<String, String> addressOf = new HashMap<>(TestRuntimeImage.TEMPLATE_ADDRESSES);
			for (Object entry : (List<?>) template.get("functions")) {
				Map<?, ?> expected = (Map<?, ?>) entry;
				String id = (String) expected.get("id");
				Map<?, ?> actual = function(functions, identifiers.get(id));
				assertNotNull(id, actual);
				assertEquals(id, expected.get("kind"), actual.get("kind"));
				assertEquals(id, expected.get("unit"), actual.get("unit"));
				Map<String, String> expectedRoles = new LinkedHashMap<>();
				for (Map.Entry<?, ?> role : ((Map<?, ?>) expected.get("datapoints")).entrySet()) {
					if (addressOf.containsKey(role.getValue())) {
						expectedRoles.put((String) role.getKey(), addressOf.get(role.getValue()));
					}
				}
				assertEquals(id, expectedRoles, actual.get("datapoints"));
				int index = functions.indexOf(actual);
				assertEquals(id.equals("key-1") ? again.get(0) : byIdentifier.get(id),
					image.elementAt("functions[" + index + "].kind"));
			}
			List<?> locations = (List<?>) json.get("locations");
			assertEquals(2, locations.size());
			Map<?, ?> labLocation = (Map<?, ?>) locations.get(0);
			Map<?, ?> panelLocation = (Map<?, ?>) locations.get(1);
			assertEquals("Test system", labLocation.get("label"));
			assertEquals("room", labLocation.get("kind"));
			assertEquals("Push-button sensor", panelLocation.get("label"));
			assertEquals("area", panelLocation.get("kind"));
			assertEquals(labLocation.get("id"), panelLocation.get("parent"));
			assertEquals(labLocation.get("id"), function(functions, identifiers.get("channel-a")).get("room"));
			assertEquals(panelLocation.get("id"), function(functions, identifiers.get("key-1")).get("room"));
			assertEquals(project, image.elementAt("knx.timeMaster.time"));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * The name of the function in the user's language.
	 */
	static String name(TLObject function) {
		return Resources.getInstance().getString((ResKey) function.tValueByName("name"));
	}

	static String text(TLObject address) {
		return RuntimeImage.threeLevel(((GA) address.tValueByName("address")).getValue());
	}

	static List<TLObject> apply(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			List<TLObject> result = FunctionProposal.apply(project);
			tx.commit();
			return result;
		}
	}

	static Map<?, ?> function(List<?> functions, String id) {
		for (Object entry : functions) {
			if (id.equals(((Map<?, ?>) entry).get("id"))) {
				return (Map<?, ?>) entry;
			}
		}
		return null;
	}

	static Map<?, ?> template() throws Exception {
		return (Map<?, ?>) Json.parse(Files.readString(
			new File(TestImageConformance.IMAGES, "test-system.template.json").toPath(), StandardCharsets.UTF_8), 100);
	}

	/**
	 * The group address of the project with the given address in three levels.
	 */
	static TLObject address(TLObject project, String threeLevel) {
		for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
			if (address.tValueByName("address") instanceof GA ga && RuntimeImage.threeLevel(ga.getValue()).equals(threeLevel)) {
				return address;
			}
		}
		throw new AssertionError("No group address " + threeLevel);
	}

	/**
	 * The device of the project with the given individual address.
	 */
	static TLObject device(TLObject project, String individualAddress) {
		for (TLObject device : SharedData.within("ehome.physical:Device", List.of(project))) {
			if (individualAddress.equals(GroupAddressConnections.addressText(device))) {
				return device;
			}
		}
		throw new AssertionError("No device " + individualAddress);
	}

	static void setDatapointType(TLObject address, String typeId) {
		update(address, "datapointTypes", List.of(datapointType(typeId)));
	}

	private static TLObject datapointType(String typeId) {
		return instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> typeId.equals(t.tValueByName("extId"))).findFirst().orElseThrow();
	}

	static void update(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	/**
	 * Creates a part of the project's buildings, in the given parent or at the top.
	 */
	static TLObject createLocation(TLObject project, TLObject parent, String type, String name) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject part = create(type);
			part.tUpdateByName("name", name);
			TLObject container = parent == null ? project : parent;
			String reference = parent == null ? "buildings" : "parts";
			List<Object> parts = new ArrayList<>((java.util.Collection<?>) container.tValueByName(reference));
			parts.add(part);
			container.tUpdateByName(reference, parts);
			tx.commit();
			return part;
		}
	}

	/**
	 * Creates a group address beside the address 0/0/1 of the project.
	 */
	static TLObject createAddress(TLObject project, String threeLevel, String name, String typeId) {
		TLObject range = address(project, "0/0/1").tContainer();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject address = create("ehome.logical:GroupAddress");
			address.tUpdateByName("name", name);
			address.tUpdateByName("address", GA.parse(threeLevel, GroupAddressStyle.ThreeLevel));
			address.tUpdateByName("datapointTypes", List.of(datapointType(typeId)));
			List<Object> parts = new ArrayList<>((java.util.Collection<?>) range.tValueByName("parts"));
			parts.add(address);
			range.tUpdateByName("parts", parts);
			tx.commit();
			return address;
		}
	}

	static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionProposal.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
