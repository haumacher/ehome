/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.apply;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.device;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

import de.haumacher.ehome.runtime.FunctionProposal;

/**
 * The room of a proposed function is the location of most of its senders (haumacher/ehome #93,
 * <code>src/test/fixtures/TestFunctionRooms.knxproj</code>): the actuator sits in the distribution
 * board "Verteiler", the keys switching its channels in "Küche" and "Wohnzimmer".
 */
@SuppressWarnings("javadoc")
public class TestFunctionRooms extends BasicTestCase {

	public void testRooms() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestFunctionRooms.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			TLObject actuator = device(project, "1.0.1");
			assertEquals("Verteiler", name(actuator.tValueByName("location")));

			apply(project);
			assertEquals(Map.of(
				// One key in "Wohnzimmer".
				"light Kanal A", "Wohnzimmer",
				// Two keys in "Küche" (one switching, one dimming), one in "Wohnzimmer" (dimming).
				"dimmer Kanal B", "Küche",
				// One key each: "Wohnzimmer" comes first in the building, although the key in "Küche"
				// has the lower address.
				"blind Kanal C", "Wohnzimmer",
				// The sensor is where its device is.
				"sensor Temperatur", "Küche"), rooms(project));

			// Running again changes no room, also one the user changed.
			TLObject light = function(project, "Kanal A");
			TLObject kitchen = (TLObject) function(project, "Kanal B").tValueByName("room");
			update(light, "room", kitchen);
			assertEquals(List.of(), apply(project));
			assertSame(kitchen, light.tValueByName("room"));

			// The key in "Wohnzimmer" not placed: the light has no placed sender, its actuator sits in
			// the distribution board, so it has no room; the blind is in the room of the other key.
			TLObject living = (TLObject) device(project, "1.0.4").tValueByName("location");
			update(device(project, "1.0.4"), "location", null);
			deleteFunctions(project);
			apply(project);
			assertEquals(Map.of("light Kanal A", "-", "dimmer Kanal B", "Küche", "blind Kanal C", "Küche",
				"sensor Temperatur", "Küche"), rooms(project));

			// The actuator in a room: the light without a placed sender is there.
			update(actuator, "location", living);
			deleteFunctions(project);
			apply(project);
			assertEquals("Wohnzimmer", name(function(project, "Kanal A").tValueByName("room")));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static void deleteFunctions(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			List<TLObject> functions = FunctionProposal.functions(project);
			project.tUpdateByName("functions", List.of());
			functions.forEach(TLObject::tDelete);
			tx.commit();
		}
	}

	private static void update(TLObject obj, String attribute, Object value) {
		TestFunctionProposal.update(obj, attribute, value);
	}

	private static TLObject function(TLObject project, String name) {
		return FunctionProposal.functions(project).stream().filter(f -> name.equals(TestFunctionProposal.name(f)))
			.findFirst().orElseThrow(() -> new AssertionError("No function " + name));
	}

	/**
	 * The rooms of the project's functions by kind and name, "-" for none.
	 */
	private static Map<String, String> rooms(TLObject project) {
		Map<String, String> result = new TreeMap<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			Object room = function.tValueByName("room");
			result.put(FunctionProposal.kind(function) + " " + TestFunctionProposal.name(function),
				room == null ? "-" : name(room));
		}
		return result;
	}

	private static String name(Object part) {
		return String.valueOf(((TLObject) part).tValueByName("name"));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionRooms.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
