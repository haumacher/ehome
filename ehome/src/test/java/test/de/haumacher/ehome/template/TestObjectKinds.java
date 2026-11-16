/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.template;

import static test.de.haumacher.ehome.importer.TestEtsImport.asRoot;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import org.w3c.dom.NodeList;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.ObjectKinds;
import de.haumacher.ehome.template.ObjectTypes;

/**
 * The kinds of communication objects of a device template (haumacher/ehome #166) with the Gira
 * Tastsensor of the KNX test system (<code>samples/KNX-Testsystem.knxproj</code>, program
 * <code>M-0008_A-10D7-11-A0E5-O000A</code>): 91 objects placed 410 times are 67 kinds.
 *
 * <p>
 * The count: the sensor's six keys, three rockers and two lock functions ("Taste 1–6, Wippe 1–3,
 * Sperrfunktion 1–2") share one block structure, whose 19 kinds each stand for 11 places (209); the
 * whole-surface operation of the rockers adds 9 kinds of 3 places (27), the left and right status
 * LEDs 4 kinds of 3 each (24), the superimposed LED function 3 kinds of 6 (18), the scene outputs 2
 * to 8 3 kinds of 7 (21) and output 1, whose parameter has another text, 3 kinds of its own (3), the
 * generic objects without a text ("Object 1", ...) 5 kinds by their sizes and settings (91), and the
 * single functions (alarms, room controller extension, LED functions, temperature measurement,
 * scenes) 17 kinds of one place (17): 67 kinds, 410 places.
 * </p>
 *
 * <p>
 * The device of the test system's project connects its measured room temperature without a type;
 * the test sets its key 1 to "Temperaturwertgeber" and connects the temperature value. In the
 * context of the project, the two kinds are marked and listed first. A datapoint type entered for
 * the kind "Temperaturwert" types each of its places, in every device running the program (the
 * key of the device, the group address it sends to), and leaves the other kinds as they are; without
 * a project, the rows do not depend on any project's data.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestObjectKinds extends BasicTestCase {

	private static final String VIEW = "src/main/webapp/WEB-INF/views/device-templates.view.xml";

	private static final String TEMPERATURE = "Funktion: Wertgeber 2Byte › Funktionsweise: Temperaturwertgeber";

	private static final String CHANNELS = "Taste 1–6, Wippe 1–3, Sperrfunktion 1–2";

	public void testKinds() throws Exception {
		Resources german = Resources.getInstance(Locale.GERMAN);
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		TLObject template = null;
		TLObject program = program();
		// The texts in German, the base language of the program, whatever the language of the test run.
		List<Map<String, Object>> kinds = ObjectKinds.kinds(program, null, german);
		List<?> programs = programs(null);
		List<Map<String, Object>> rows = viewKinds(program, null);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			List<Interface> placements = DeviceTemplates.placements(program);
			assertEquals(410, placements.size());

			// One row per kind: every place in exactly one row, no two rows alike.
			assertEquals(67, kinds.size());
			Set<Interface> covered = new HashSet<>();
			Set<List<Object>> keys = new HashSet<>();
			for (Map<String, Object> kind : kinds) {
				for (Interface placed : ObjectKinds.placements(kind)) {
					assertTrue("Placed once: " + placed, covered.add(placed));
				}
				assertTrue("A kind once: " + kind, keys.add(List.of(kind.get(ObjectKinds.OBJECT),
					kind.get(ObjectKinds.SIZE), kind.get(ObjectKinds.SETTING))));
			}
			assertEquals(new HashSet<>(placements), covered);

			// The three "Kanal 1 Wert" kinds of the two-channel operation are three rows with their settings.
			Map<String, Object> value255 = kind(kinds, "Kanal 1 Wert",
				"Funktion: 2-Kanal Bedienung › Funktion Kanal 1: Wertgeber 0...255 (1 Byte)");
			Map<String, Object> value100 = kind(kinds, "Kanal 1 Wert",
				"Funktion: 2-Kanal Bedienung › Funktion Kanal 1: Wertgeber 0...100% (1 Byte)");
			Map<String, Object> value2Byte = kind(kinds, "Kanal 1 Wert",
				"Funktion: 2-Kanal Bedienung › Funktion Kanal 1: Temperaturwertgeber (2 Byte)");
			assertEquals(8, value255.get(ObjectKinds.SIZE));
			assertEquals(8, value100.get(ObjectKinds.SIZE));
			assertEquals(16, value2Byte.get(ObjectKinds.SIZE));
			assertEquals(CHANNELS, value255.get(ObjectKinds.CHANNELS));

			// "Temperaturwert" is one row naming the keys and rockers it occurs in.
			Map<String, Object> temperature = kind(kinds, "Temperaturwert", TEMPERATURE);
			assertEquals(CHANNELS, temperature.get(ObjectKinds.CHANNELS));
			assertEquals(11, ObjectKinds.placements(temperature).size());

			// The project's device connects 0/0/28 to its measured room temperature, without a type.
			TLObject roomConnector =
				GroupAddressConnections.connections(address(project, "0/0/28")).get(0).tContainer();
			Interface room = (Interface) roomConnector.tValueByName("port");
			Map<String, Object> roomKind = kindOf(kinds, room);
			assertEquals("Gemessene Raumtemperatur", roomKind.get(ObjectKinds.OBJECT));
			TLObject device = roomConnector.tContainer();

			// A key of it set to "Temperaturwertgeber" and its temperature value connected to a new address:
			// a key whose object the project holds no connector of (a connector keeps the place it was
			// created for, see the report of haumacher/ehome #166).
			Interface connected = ObjectKinds.placements(temperature).stream()
				.filter(p -> ObjectTypes.connector(device, p) == null).findFirst().orElseThrow();
			assertFalse(DeviceSettings.interfaces(device).contains(connected));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				select(device, connected);
				tx.commit();
			}
			assertTrue(DeviceSettings.interfaces(device).contains(connected));
			TLObject free;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				free = GroupAddresses.create(address(project, "0/0/28").tContainer(), Integer.valueOf(200),
					"Temperatur", null);
				DeviceSettings.connect(device, connected, free, kind("send"));
				tx.commit();
			}
			TLObject connector = ObjectTypes.connector(device, connected);
			assertNull(ObjectTypes.effective(connector));

			// With the project as context: the two kinds connected without a type are marked and first.
			List<Map<String, Object>> inProject = ObjectKinds.kinds(program, project, german);
			assertEquals(new HashSet<>(kinds), new HashSet<>(inProject));
			assertEquals(List.of(temperature, roomKind), inProject.subList(0, 2));
			assertTrue(ObjectKinds.untyped(temperature, project));
			assertTrue(ObjectKinds.untyped(roomKind, project));
			for (Map<String, Object> kind : inProject.subList(2, inProject.size())) {
				assertFalse(kind.toString(), ObjectKinds.untyped(kind, project));
			}
			assertEquals(List.of(connected, room), DeviceTemplates.untypedConnected(program, project));
			// The view's rows of the tab, as the table computes them, with and without the project.
			// (Evaluated as the user the view evaluates them for, in the user's language.)
			assertEquals(asRoot(() -> ObjectKinds.kinds(program, project)), viewKinds(program, project));
			assertEquals(asRoot(() -> ObjectKinds.kinds(program, null)), viewKinds(program, null));

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				template = DeviceTemplates.create(program);
				tx.commit();
			}
			TLObject celsius = subtype("DPST-9-1");
			TLObject lux = subtype("DPST-9-4");
			assertTrue(ObjectKinds.typeChoices(temperature).contains(celsius));

			// A type entered before for a single place: the kind is mixed.
			Interface other = ObjectKinds.placements(temperature).stream().filter(p -> p != connected)
				.findFirst().orElseThrow();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ObjectTypes.overlay(other).tUpdateByName("datapointTypes", List.of(lux));
				tx.commit();
			}
			assertTrue(ObjectKinds.mixed(template, temperature));
			assertNull(ObjectKinds.type(template, temperature));
			assertEquals(List.of(lux), ObjectKinds.types(temperature));

			// 9.001 entered on the row, as the field of the view does it, types every place of the kind.
			setType(template, temperature, celsius);
			assertFalse(ObjectKinds.mixed(template, temperature));
			assertEquals(celsius, ObjectKinds.type(template, temperature));
			assertEquals(List.of(celsius), ObjectKinds.types(temperature));
			for (Interface placed : ObjectKinds.placements(temperature)) {
				assertEquals(List.of(celsius), ObjectTypes.inherited(placed));
			}
			// The device whose key is set to "Temperaturwertgeber" has the type now, the group address too.
			assertEquals(celsius, ObjectTypes.effective(device, connected));
			assertEquals(celsius, ObjectTypes.effective(connector));
			assertEquals(new Dpt(9, 1), GroupAddressTypes.effective(free));
			// The 1-byte value kinds stay untyped: a device whose key is set to one of them inherits no type.
			for (Map<String, Object> kind : List.of(value255, value100)) {
				assertEquals(List.of(), ObjectKinds.types(kind));
				assertNull(ObjectKinds.type(template, kind));
				for (Interface placed : ObjectKinds.placements(kind)) {
					assertEquals(List.of(), ObjectTypes.inherited(placed));
				}
			}
			// Only the room temperature is left untyped in the project; the rows did not change, so
			// that the table keeps its selection.
			assertEquals(List.of(room), DeviceTemplates.untypedConnected(program, project));
			assertFalse(ObjectKinds.untyped(temperature, project));
			assertEquals(roomKind, ObjectKinds.kinds(program, project, german).get(0));
			assertEquals(kinds, ObjectKinds.kinds(program, null, german));

			// The emptied field removes the template's type of the kind.
			setType(template, temperature, null);
			assertEquals(List.of(), ObjectKinds.types(temperature));
			assertNull(ObjectTypes.effective(device, connected));
			assertTrue(ObjectKinds.untyped(temperature, project));
		} finally {
			SharedData.deleteProjects(projects);
			if (template != null) {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					template.tDelete();
					tx.commit();
				}
			}
		}

		// Without a project, the rows depend on no project's data: they are the same without the test
		// system's project as with it, and the program table's switch narrows nothing.
		assertEquals(rows, viewKinds(program, null));
		assertEquals(programs, programs(null));
	}

	private static void setType(TLObject template, Map<String, Object> kind, TLObject type) throws Exception {
		QueryExecutor set = ViewScripts.compile(
			ExprFormat.INSTANCE.getValue("function", "templateKindSetType($template, $kind, $value)"),
			List.of("template", "kind", "value"));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			asRoot(() -> set.execute(template, kind, type));
			tx.commit();
		}
	}

	/** The rows of the tab "Communication objects" as the view computes them. */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> viewKinds(TLObject program, TLObject project) throws Exception {
		QueryExecutor rows = ViewScripts.compile(ExprFormat.INSTANCE.getValue("rows", rows("templateKinds")),
			List.of("program", "project"));
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object row : (Collection<?>) asRoot(() -> rows.execute(program, project))) {
			result.add((Map<String, Object>) row);
		}
		return result;
	}

	/** The programs of the overview with its switch on, in the context of the given project. */
	private static List<?> programs(TLObject project) throws Exception {
		QueryExecutor rows = ViewScripts.compile(ExprFormat.INSTANCE.getValue("rows", rows("untypedOnly")),
			List.of("untypedOnly", "project"));
		return new ArrayList<>((Collection<?>) asRoot(() -> rows.execute(Boolean.TRUE, project)));
	}

	private static String rows(String containing) throws Exception {
		NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(VIEW))
			.getElementsByTagName("rows");
		for (int n = 0; n < nodes.getLength(); n++) {
			String text = nodes.item(n).getTextContent();
			if (text.contains(containing)) {
				return text;
			}
		}
		fail("No rows with " + containing + " in " + VIEW);
		return null;
	}

	/**
	 * Sets the device's parameters to the values of the cases of the switches above the placed object,
	 * so that it is in effect.
	 */
	private static void select(TLObject device, Interface placed) {
		List<TLObject> path = new ArrayList<>();
		for (TLObject current = placed; current != null; current = current.tContainer()) {
			path.add(0, current);
		}
		for (int n = 1; n < path.size(); n++) {
			if (path.get(n - 1) instanceof Switch choice && path.get(n) instanceof Case alternative
				&& alternative.getPattern() != null && choice.getInput() != null) {
				DeviceSettings.setValue(device, choice.getInput(),
					Integer.valueOf(alternative.getPattern().trim().split("\\s+")[0]));
			}
		}
	}

	private static Map<String, Object> kindOf(List<Map<String, Object>> kinds, Interface placed) {
		for (Map<String, Object> kind : kinds) {
			if (ObjectKinds.placements(kind).contains(placed)) {
				return kind;
			}
		}
		fail("No kind of " + placed);
		return null;
	}

	private static TLObject kind(String name) {
		return (TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#" + name);
	}

	private static Map<String, Object> kind(List<Map<String, Object>> kinds, String object, String setting) {
		for (Map<String, Object> kind : kinds) {
			if (object.equals(kind.get(ObjectKinds.OBJECT)) && setting.equals(kind.get(ObjectKinds.SETTING))) {
				return kind;
			}
		}
		fail("No kind " + object + " / " + setting + " in " + kinds);
		return null;
	}

	private static TLObject program() {
		return instances("ehome.operational:Program").stream()
			.filter(p -> "M-0008_A-10D7-11-A0E5-O000A".equals(ExtIds.extId(p))).findFirst().orElseThrow();
	}

	private static TLObject address(TLObject project, String text) {
		return SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
			.filter(a -> a.tValueByName("address") instanceof GA ga && ga.toString().equals(text))
			.findFirst().orElseThrow();
	}

	private static TLObject subtype(String extId) {
		return instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> extId.equals(t.tValueByName("extId"))).findFirst().orElseThrow();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestObjectKinds.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
