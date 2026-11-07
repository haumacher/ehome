/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.device;

import java.time.Instant;
import java.util.ArrayList;
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
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.runtime.ExportRuntimeImageAction;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The candidates of the device channels linked by their sending group addresses into functions
 * (haumacher/ehome #98, <code>src/test/fixtures/TestFunctionLinks.knxproj</code>, see
 * <code>make-fixtures.py</code>).
 *
 * <ul>
 * <li>A key sending on the address an actuator channel receives as its first gives one function of
 * the actuator's kind, with the actuator's state, named after the address ("Licht Küche").</li>
 * <li>The central address 4/0/9, a second address of two channels, links neither: two functions;
 * the key sending on it (received as first address only by the central object, which gives no
 * function) is a button, as is the key on "Klingel", which nothing else receives.</li>
 * <li>The push-button's temperature on its own address is a sensor named after it.</li>
 * <li>Two actuators on "Licht Flur" give one function.</li>
 * <li>The channels connected to nothing give unused functions named "&lt;device&gt;:
 * &lt;channel&gt;", without datapoints, which the runtime image leaves out and its summary
 * counts.</li>
 * <li>A second run after the user linked two of these channels fills their roles and renames the
 * one whose name is still the generated one, not the one the user renamed; a function named after
 * its channel, as earlier proposals named it, is renamed after its address.</li>
 * <li>A second import of the same project gives the same functions, and a second run adds
 * none.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestFunctionLinks extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestFunctionLinks.knxproj";

	private static final List<String> EXPECTED = List.of(
		"Klingel: button press=4/0/8",
		"Licht Flur: light command=4/0/3",
		"Licht Küche: light command=4/0/1, state=4/0/2",
		"Licht Zentral: button press=4/0/9",
		"Raumtemperatur Küche: sensor value=4/1/1",
		"TEST Aktor Flur: Kanal A: light unused",
		"TEST Aktor Flur: Kanal C: blind unused",
		"TEST Aktor Küche: Kanal C: blind unused",
		"TEST Taster Küche: Taste 2: button unused",
		"TEST Taster Küche: Wippe 3: button unused");

	public void testLinks() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			List<TLObject> added = TestFunctionProposal.apply(project);
			assertEquals(EXPECTED, summary(project));
			assertEquals(10, added.size());

			// The kitchen light is the actuator's channel, the two lights of the corridor the first actuator's.
			assertSame(device(project, "1.0.1"), function(project, "Licht Küche").tValueByName("device"));
			assertSame(device(project, "1.0.1"), function(project, "Licht Flur").tValueByName("device"));
			// Named after the address in every language, the identifier after the name.
			TLObject kitchen = function(project, "Licht Küche");
			assertEquals("Licht Küche", text(kitchen, Locale.GERMAN));
			assertEquals("Licht Küche", text(kitchen, Locale.ENGLISH));
			assertEquals("licht-kueche", kitchen.tValueByName("identifier"));
			TLObject corridorA = function(project, "TEST Aktor Flur: Kanal A");
			assertSame(device(project, "1.0.2"), corridorA.tValueByName("device"));
			assertNotNull(corridorA.tValueByName("channel"));
			assertNull(corridorA.tValueByName("room"));

			// "Unused" follows from the content; the model's derived attribute says the same as the code.
			for (TLObject function : FunctionProposal.functions(project)) {
				assertEquals(name(function), Boolean.valueOf(FunctionProposal.unused(function)),
					derived(function, "unused"));
			}

			// The runtime image leaves the unused functions out, without refusing, and its summary counts them.
			RuntimeImage image = RuntimeImage.build(project, 1, Instant.parse("2026-11-07T12:00:00Z"));
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			assertEquals(5, ((List<?>) image.getJson().get("functions")).size());
			assertEquals(5, image.getUnusedFunctions());
			String summary = ExportRuntimeImageAction.summary(image);
			assertTrue(summary, summary.endsWith(
				" 5 unused functions are not deployed: their main datapoint has no group address."));

			// Running again adds and changes nothing.
			assertEquals(new FunctionProposal.Run(List.of(), List.of()), run(project));

			// The user renames an unused blind and links it, links the other actuator's channel A, and
			// gives the kitchen light the name earlier proposals gave it, its channel's.
			TLObject blind = function(project, "TEST Aktor Küche: Kanal C");
			String blindIdentifier = (String) blind.tValueByName("identifier");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				blind.tUpdateByName("name", FunctionProposal.text(Map.of("de", "Markise", "en", "Awning")));
				kitchen.tUpdateByName("name", FunctionProposal.text(Map.of("de", "Kanal A")));
				connect(device(project, "1.0.1"), 6, address(project, "4/2/1"));
				connect(device(project, "1.0.2"), 0, address(project, "4/0/4"));
				tx.commit();
			}
			FunctionProposal.Run run = run(project);
			assertEquals(List.of(), run.added());
			assertEquals(3, run.updated().size());
			assertTrue(run.updated().containsAll(List.of(blind, kitchen, corridorA)));
			assertEquals(List.of(
				"Awning: blind move=4/2/1",
				"Klingel: button press=4/0/8",
				"Licht Bad: light command=4/0/4",
				"Licht Flur: light command=4/0/3",
				"Licht Küche: light command=4/0/1, state=4/0/2",
				"Licht Zentral: button press=4/0/9",
				"Raumtemperatur Küche: sensor value=4/1/1",
				"TEST Aktor Flur: Kanal C: blind unused",
				"TEST Taster Küche: Taste 2: button unused",
				"TEST Taster Küche: Wippe 3: button unused"), summary(project));
			// The edited name stays (in both languages), and so does the identifier of the renamed blind.
			assertEquals("Markise", text(blind, Locale.GERMAN));
			assertEquals(blindIdentifier, blind.tValueByName("identifier"));
			// The unused function renamed after its address gets the identifier of the name; the kitchen
			// light, which the controller may have had, keeps its own.
			assertEquals("licht-bad", corridorA.tValueByName("identifier"));
			assertEquals("Licht Küche", text(kitchen, Locale.GERMAN));
			assertEquals("licht-kueche", kitchen.tValueByName("identifier"));
			assertEquals(Boolean.FALSE, derived(corridorA, "unused"));

			// A role the user bound stays; the next run changes nothing.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				kitchen.tUpdateByName("state", null);
				tx.commit();
			}
			assertEquals(new FunctionProposal.Run(List.of(), List.of()), run(project));
			assertNull(kitchen.tValueByName("state"));

			// The same project imported again: the same functions, and a second run adds none.
			List<TLObject> before2 = instances("ehome.project:Project");
			importSample(FIXTURE);
			List<TLObject> again = SharedData.newProjects(before2);
			projects = new ArrayList<>(projects);
			projects.addAll(again);
			assertEquals(1, again.size());
			assertEquals(10, TestFunctionProposal.apply(again.get(0)).size());
			assertEquals(EXPECTED, summary(again.get(0)));
			assertEquals(List.of(), TestFunctionProposal.apply(again.get(0)));
			assertEquals(10, FunctionProposal.functions(again.get(0)).size());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static FunctionProposal.Run run(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			FunctionProposal.Run result = FunctionProposal.run(project);
			tx.commit();
			return result;
		}
	}

	/**
	 * Sends the object of the given number of the device on the address.
	 */
	private static void connect(TLObject device, int number, TLObject address) {
		Interface placed = new DeviceConfiguration(device).getActiveInterfaces().values().stream()
			.filter(p -> p.getPort().getIndex() == number).findFirst().orElseThrow();
		DeviceSettings.connect(device, placed, address,
			((TLEnumeration) TLModelUtil.findType("ehome.physical:ConnectionType")).getClassifier("send"));
	}

	private static TLObject function(TLObject project, String name) {
		return FunctionProposal.functions(project).stream().filter(f -> name.equals(name(f))).findFirst()
			.orElseThrow(() -> new AssertionError("No function " + name));
	}

	private static String name(TLObject function) {
		return TestFunctionProposal.name(function);
	}

	private static String text(TLObject function, Locale locale) {
		return Resources.getInstance(locale).getString((ResKey) function.tValueByName("name"));
	}

	/**
	 * The project's functions by name (in the user's language), kind and role bindings in the order
	 * of their kind ("unused" for none), sorted.
	 */
	static List<String> summary(TLObject project) {
		List<String> result = new ArrayList<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			List<String> roles = new ArrayList<>();
			for (String role : FunctionKinds.roles(FunctionProposal.kind(function))) {
				if (function.tValueByName(role) instanceof TLObject address) {
					roles.add(role + "=" + TestFunctionProposal.text(address));
				}
			}
			result.add(name(function) + ": " + FunctionProposal.kind(function) + " "
				+ (FunctionProposal.unused(function) ? "unused" : String.join(", ", roles)));
		}
		result.sort(null);
		return result;
	}

	/**
	 * The project's functions, one line each with name (in the user's language), kind, role
	 * bindings in the order of the kind, room ("-" for none) and whether it is unused, sorted.
	 */
	static List<String> table(TLObject project) {
		List<String> result = new ArrayList<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			List<String> roles = new ArrayList<>();
			for (String role : FunctionKinds.roles(FunctionProposal.kind(function))) {
				if (function.tValueByName(role) instanceof TLObject address) {
					roles.add(role + "=" + TestFunctionProposal.text(address));
				}
			}
			Object room = function.tValueByName("room");
			result.add(name(function) + " | " + FunctionProposal.kind(function) + " | " + String.join(", ", roles) + " | "
				+ (room == null ? "-" : ((TLObject) room).tValueByName("name")) + " | "
				+ (FunctionProposal.unused(function) ? "unused" : "used"));
		}
		result.sort(null);
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionLinks.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
