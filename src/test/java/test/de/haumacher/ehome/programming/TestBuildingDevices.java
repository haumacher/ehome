/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The building tree of a project shows the devices placed in a building part, and a device is
 * placed from either side (haumacher/ehome #19), on the fixture <code>TestDetails.knxproj</code>: the
 * building "Haus" with the floor "Erdgeschoss", which holds the room "Küche" with the device "Taster
 * Küche" and the empty distribution board "Verteiler"; the device "Taster Reserve" is placed nowhere.
 *
 * <p>
 * The children and labels are computed by the scripts of the view
 * (<code>projects/buildings.view.xml</code>), the placement by the scripts of the dialogs
 * <code>projects/place-device.view.xml</code> (from the part) and
 * <code>projects/choose-location.view.xml</code> (from the device), the removal by the script of the
 * view's command "Remove the placement". The group addresses in use in a part are the union of the
 * connections of the devices placed in it and in its sub-parts.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBuildingDevices extends BasicTestCase {

	private static final String BUILDINGS_VIEW = "src/main/webapp/WEB-INF/views/projects/buildings.view.xml";

	private static final String PLACE_VIEW = "src/main/webapp/WEB-INF/views/projects/place-device.view.xml";

	private static final String LOCATION_VIEW = "src/main/webapp/WEB-INF/views/projects/choose-location.view.xml";

	public void testPlacement() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestDetails.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			check(project);
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private void check(TLObject project) throws Exception {
		TLObject house = single(list(project, "buildings"));
		assertEquals("Haus", house.tValueByName("name"));
		assertEquals("ehome.building:Building", TLModelUtil.qualifiedName(house.tType()));
		TLObject floor = single(list(house, "parts"));
		assertEquals("ehome.building:Floor", TLModelUtil.qualifiedName(floor.tType()));
		List<TLObject> rooms = list(floor, "parts");
		assertEquals(2, rooms.size());
		TLObject kitchen = byName(rooms, "Küche");
		TLObject board = byName(rooms, "Verteiler");
		assertEquals("ehome.building:Room", TLModelUtil.qualifiedName(kitchen.tType()));
		assertEquals("ehome.building:DistributionBoard", TLModelUtil.qualifiedName(board.tType()));

		TLObject device = byTitle(project, "Taster Küche");
		TLObject reserve = byTitle(project, "Taster Reserve");

		// The import places the device in the kitchen; the part sees it through the backwards reference.
		assertSame(kitchen, device.tValueByName("location"));
		assertNull(reserve.tValueByName("location"));
		assertEquals(Set.of(device), new HashSet<>((Collection<?>) kitchen.tValueByName("devices")));
		assertEquals(List.of(device), BuildingDevices.devices(kitchen));
		assertEquals("Haus / Erdgeschoss / Küche", BuildingDevices.path(kitchen));

		QueryExecutor children = script(viewText(BUILDINGS_VIEW, "children", 0));
		QueryExecutor parents = script(viewText(BUILDINGS_VIEW, "parents", 0));
		QueryExecutor label = script(viewAttribute(BUILDINGS_VIEW, "nodeContent", "label", 0));
		// The input rebuilding the tree when a device is placed or removed (see the view).
		QueryExecutor placements = script(channelExpr(BUILDINGS_VIEW, "placements"));
		Object placementsBefore = asRoot(Locale.ENGLISH, () -> placements.execute(project));

		// The devices of a part follow its sub-parts; a device is a leaf below its location.
		assertEquals(List.of(house), children(children, project, project));
		assertEquals(List.of(floor), children(children, project, house));
		assertEquals(rooms, children(children, project, floor));
		assertEquals(List.of(device), children(children, project, kitchen));
		assertEquals(List.of(), children(children, project, board));
		assertEquals(List.of(), children(children, project, device));
		assertSame(kitchen, asRoot(Locale.ENGLISH, () -> parents.execute(project, null, device)));
		assertSame(floor, asRoot(Locale.ENGLISH, () -> parents.execute(project, null, kitchen)));
		String deviceLabel = (String) asRoot(Locale.GERMAN, () -> label.execute(device));
		assertEquals(GroupAddressConnections.deviceLabel(device), deviceLabel);
		assertTrue(deviceLabel, deviceLabel.startsWith("Taster Küche (") && deviceLabel.endsWith(")"));
		assertNull(asRoot(Locale.GERMAN, () -> label.execute(kitchen)));

		// The "New" menu offers the part types the selected container takes, as ETS does.
		assertTrue(BuildingDevices.canContain(project, buildingType("Building")));
		assertFalse(BuildingDevices.canContain(project, buildingType("Room")));
		assertTrue(BuildingDevices.canContain(house, buildingType("Floor")));
		assertTrue(BuildingDevices.canContain(house, buildingType("Room")));
		assertFalse(BuildingDevices.canContain(house, buildingType("Building")));
		assertTrue(BuildingDevices.canContain(floor, buildingType("Room")));
		assertTrue(BuildingDevices.canContain(floor, buildingType("DistributionBoard")));
		assertFalse(BuildingDevices.canContain(floor, buildingType("Floor")));
		assertTrue(BuildingDevices.canContain(kitchen, buildingType("DistributionBoard")));
		assertFalse(BuildingDevices.canContain(kitchen, buildingType("Building")));
		assertFalse(BuildingDevices.canContain(kitchen, buildingType("Room")));
		for (String type : List.of("Building", "BuildingPart", "Floor", "Room", "Corridor", "Stairway",
			"DistributionBoard", "Location")) {
			assertFalse(type, BuildingDevices.canContain(board, buildingType(type)));
		}
		// The rule of the view's create commands, in the order of the commands (Building first, Room fourth).
		QueryExecutor buildingVeto = script(viewAttribute(BUILDINGS_VIEW, "disabled-if", "expr", 0));
		QueryExecutor roomVeto = script(viewAttribute(BUILDINGS_VIEW, "disabled-if", "expr", 3));
		assertNull(asRoot(Locale.ENGLISH, () -> buildingVeto.execute(project)));
		assertNotNull(asRoot(Locale.ENGLISH, () -> buildingVeto.execute(kitchen)));
		assertNull(asRoot(Locale.ENGLISH, () -> roomVeto.execute(floor)));
		assertNotNull(asRoot(Locale.ENGLISH, () -> roomVeto.execute(board)));

		// The group addresses in use: the connections of the device, in the kitchen and in every part above.
		Set<TLObject> deviceConnections = connectionsOf(device);
		assertFalse(deviceConnections.isEmpty());
		assertEquals(deviceConnections, new HashSet<>(BuildingDevices.connections(kitchen)));
		assertEquals(BuildingDevices.connections(kitchen), BuildingDevices.connections(house));
		assertTrue(BuildingDevices.connections(board).isEmpty());

		// The dialog of a part offers the devices not placed in it, those placed nowhere first.
		assertEquals(List.of(reserve, device), BuildingDevices.choices(board));
		assertEquals(List.of(reserve), BuildingDevices.choices(kitchen));

		// Place the reserve device in the distribution board from the part's side.
		QueryExecutor place = script(viewAttribute(PLACE_VIEW, "execute-script", "function", 0));
		Object placed;
		try (Transaction tx = kb().beginTransaction()) {
			placed = asRoot(Locale.ENGLISH, () -> place.execute(board, reserve));
			tx.commit();
		}
		assertSame(reserve, placed);
		assertFalse(placementsBefore.equals(asRoot(Locale.ENGLISH, () -> placements.execute(project))));
		assertSame(board, reserve.tValueByName("location"));
		assertEquals(List.of(reserve), children(children, project, board));
		assertEquals(List.of(device), children(children, project, kitchen));
		assertSame(board, asRoot(Locale.ENGLISH, () -> parents.execute(project, null, reserve)));
		assertEquals(List.of(device), BuildingDevices.choices(board));

		// A connection of the reserve device: the floor shows the union of both devices' connections.
		TLObject groupAddress = DeviceSettings.groupAddresses(reserve).get(0);
		Interface object = GroupAddressConnections.objects(reserve).get(0);
		TLObject connection;
		try (Transaction tx = kb().beginTransaction()) {
			connection = DeviceSettings.connect(reserve, object, groupAddress,
				DeviceSettings.defaultKind(reserve, object));
			tx.commit();
		}
		Set<TLObject> union = new HashSet<>(deviceConnections);
		union.addAll(connectionsOf(reserve));
		assertTrue(union.contains(connection));
		assertEquals(union, new HashSet<>(BuildingDevices.connections(floor)));
		assertEquals(union, new HashSet<>(BuildingDevices.connections(house)));
		assertEquals(connectionsOf(reserve), new HashSet<>(BuildingDevices.connections(board)));
		assertEquals(deviceConnections, new HashSet<>(BuildingDevices.connections(kitchen)));
		assertSorted(BuildingDevices.connections(house));

		// Remove the placement with the command of the building view: the board is selected afterwards.
		QueryExecutor remove = script(viewText(BUILDINGS_VIEW, "function", 0));
		Object selected;
		try (Transaction tx = kb().beginTransaction()) {
			selected = asRoot(Locale.ENGLISH, () -> remove.execute(board, reserve));
			tx.commit();
		}
		assertSame(board, selected);
		assertNull(reserve.tValueByName("location"));
		assertEquals(List.of(), children(children, project, board));
		assertEquals(deviceConnections, new HashSet<>(BuildingDevices.connections(house)));

		// From the device's side: move the kitchen device to the board, then remove it from there.
		QueryExecutor choose = script(viewAttribute(LOCATION_VIEW, "execute-script", "function", 0));
		QueryExecutor unplace = script(viewAttribute(LOCATION_VIEW, "execute-script", "function", 1));
		try (Transaction tx = kb().beginTransaction()) {
			asRoot(Locale.ENGLISH, () -> choose.execute(device, board));
			tx.commit();
		}
		assertSame(board, device.tValueByName("location"));
		assertEquals(List.of(), children(children, project, kitchen));
		assertEquals(List.of(device), children(children, project, board));
		try (Transaction tx = kb().beginTransaction()) {
			asRoot(Locale.ENGLISH, () -> unplace.execute(device));
			tx.commit();
		}
		assertNull(device.tValueByName("location"));
		assertTrue(BuildingDevices.connections(house).isEmpty());

		// A part of another project is refused with a reason.
		try (Transaction tx = kb().beginTransaction()) {
			TLObject foreign = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.building:Building"));
			foreign.tUpdateByName("name", "Fremd");
			try {
				BuildingDevices.place(device, foreign);
				fail("A part of another project must be refused.");
			} catch (TopLogicException ex) {
				assertNotNull(ex.getErrorKey());
			}
			assertNull(device.tValueByName("location"));
		}

		// Deleting a part holding a device leaves the device without location.
		try (Transaction tx = kb().beginTransaction()) {
			BuildingDevices.place(device, kitchen);
			BuildingDevices.place(reserve, board);
			tx.commit();
		}
		try (Transaction tx = kb().beginTransaction()) {
			floor.tDelete();
			tx.commit();
		}
		assertNull(device.tValueByName("location"));
		assertNull(reserve.tValueByName("location"));
		assertEquals(List.of(), children(children, project, house));
		assertTrue(BuildingDevices.connections(house).isEmpty());
	}

	private static void assertSorted(List<TLObject> connections) {
		int last = Integer.MIN_VALUE;
		for (TLObject connection : connections) {
			int value = ((de.haumacher.ehome.knx.GA) ((TLObject) connection.tValueByName("target"))
				.tValueByName("address")).getValue();
			assertTrue(last <= value);
			last = value;
		}
	}

	private static Set<TLObject> connectionsOf(TLObject device) {
		Set<TLObject> result = new HashSet<>();
		for (Interface object : DeviceSettings.interfaces(device)) {
			result.addAll(DeviceSettings.connections(device, object));
		}
		return result;
	}

	private static TLClass buildingType(String name) {
		return (TLClass) TLModelUtil.findType("ehome.building:" + name);
	}

	private static TLObject byTitle(TLObject project, String title) {
		List<TLObject> devices = GroupAddressConnections.devices(project);
		return devices.stream().filter(d -> title.equals(d.tValueByName("title"))).findFirst()
			.orElseThrow(() -> new AssertionError("No device " + title));
	}

	private static TLObject byName(List<TLObject> parts, String name) {
		return parts.stream().filter(p -> name.equals(p.tValueByName("name"))).findFirst()
			.orElseThrow(() -> new AssertionError("No part " + name));
	}

	private static TLObject single(List<TLObject> values) {
		assertEquals(1, values.size());
		return values.get(0);
	}

	private static List<TLObject> list(TLObject obj, String attribute) {
		List<TLObject> result = new ArrayList<>();
		for (Object value : (Collection<?>) obj.tValueByName(attribute)) {
			result.add((TLObject) value);
		}
		return result;
	}

	private static List<Object> children(QueryExecutor children, TLObject project, TLObject node) {
		return new ArrayList<>((Collection<?>) asRoot(Locale.ENGLISH, () -> children.execute(project, null, node)));
	}

	private static QueryExecutor script(String source) throws Exception {
		return QueryExecutor.compile(ExprFormat.INSTANCE.getValue("script", source));
	}

	private static String viewText(String file, String element, int index) throws Exception {
		return ((Element) elements(file, element).item(index)).getTextContent();
	}

	private static String viewAttribute(String file, String element, String attribute, int index) throws Exception {
		return ((Element) elements(file, element).item(index)).getAttribute(attribute);
	}

	private static String channelExpr(String file, String name) throws Exception {
		NodeList channels = elements(file, "derived-channel");
		for (int n = 0; n < channels.getLength(); n++) {
			Element channel = (Element) channels.item(n);
			if (name.equals(channel.getAttribute("name"))) {
				return channel.getAttribute("expr");
			}
		}
		throw new AssertionError("No channel " + name);
	}

	private static NodeList elements(String file, String element) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(file))
			.getElementsByTagName(element);
	}

	/**
	 * The value computed by the supplier as the technical administrator, in the given language
	 * (derived attributes are computed with the rights of the reading user, see
	 * {@link test.de.haumacher.ehome.importer.TestEtsImport#derived(TLObject, String)}).
	 */
	private static Object asRoot(Locale locale, Supplier<Object> supplier) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		try {
			return context.withLocale(locale, supplier);
		} finally {
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBuildingDevices.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
