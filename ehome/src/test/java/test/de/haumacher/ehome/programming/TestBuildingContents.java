/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.runtime.BlockInstances;

/**
 * The tabs "In this building part" of the building view list what lies in the selected part and in
 * all its sub-parts (haumacher/ehome #87), on the fixture <code>TestDetails.knxproj</code>: the
 * building "Haus" with the floor "Erdgeschoss" and its room "Küche", which holds the device "Taster
 * Küche". The test adds a Zigbee device and a virtual device to the kitchen and a Zigbee device to
 * the floor, and evaluates the row scripts of the four tabs of <code>projects/buildings.view.xml</code>
 * for the room, the floor and the house; the direct functions keep listing the part's own devices.
 */
@SuppressWarnings("javadoc")
public class TestBuildingContents extends BasicTestCase {

	private static final String BUILDINGS_VIEW = "src/main/webapp/WEB-INF/views/projects/buildings.view.xml";

	public void testRecursive() throws Exception {
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
		TLObject house = list(project, "buildings").get(0);
		TLObject floor = list(house, "parts").get(0);
		TLObject kitchen = list(floor, "parts").stream().filter(p -> "Küche".equals(p.tValueByName("name")))
			.findFirst().orElseThrow();
		List<TLObject> devices = BuildingDevices.devices(kitchen);
		assertEquals(1, devices.size());

		TLObject sensor;
		TLObject lamp;
		TLObject block;
		try (Transaction tx = kb().beginTransaction()) {
			sensor = zigbee(project, "Melder Küche", "00158d0000000001", kitchen);
			lamp = zigbee(project, "Lampe Flur", "00158d0000000002", floor);
			block = BlockInstances.create(project, BlockInstances.typeKey(RoomController.INSTANCE.manifest()), kitchen,
				ResKey.text("Heizung Küche"));
			tx.commit();
		}

		// The rows of the tabs, in the order of the tabs: devices, Zigbee devices, virtual devices, connections.
		QueryExecutor deviceRows = rows(0);
		QueryExecutor zigbeeRows = rows(1);
		QueryExecutor blockRows = rows(2);
		QueryExecutor connectionRows = rows(3);

		for (TLObject part : List.of(kitchen, floor, house)) {
			assertEquals(part.toString(), devices, values(deviceRows, part));
			assertEquals(part.toString(), List.of(block), values(blockRows, part));
			assertEquals(part.toString(), BuildingDevices.connections(kitchen), values(connectionRows, part));
		}
		assertEquals(List.of(sensor), values(zigbeeRows, kitchen));
		// By name: "Lampe Flur" before "Melder Küche".
		assertEquals(List.of(lamp, sensor), values(zigbeeRows, floor));
		assertEquals(List.of(lamp, sensor), values(zigbeeRows, house));
		// The parts within the house: itself first, depth-first in the order of the parts.
		List<TLObject> within = BuildingDevices.partsWithin(house);
		assertEquals(List.of(house, floor), within.subList(0, 2));
		assertEquals(list(floor, "parts"), within.subList(2, within.size()));

		// The direct functions stay: the floor holds no device itself.
		assertTrue(BuildingDevices.devices(floor).isEmpty());
		assertEquals(devices, BuildingDevices.devices(kitchen));
		assertEquals(List.of(lamp), new ArrayList<>((Collection<?>) floor.tValueByName("zigbeeDevices")));
	}

	private static TLObject zigbee(TLObject project, String name, String ieee, TLObject part) {
		TLObject device = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType("ehome.zigbee:ZigbeeDevice"));
		device.tUpdateByName("name", name);
		device.tUpdateByName("ieee", ieee);
		List<TLObject> all = list(project, "zigbeeDevices");
		all.add(device);
		project.tUpdateByName("zigbeeDevices", all);
		device.tUpdateByName("location", part);
		return device;
	}

	private static List<Object> values(QueryExecutor rows, TLObject part) {
		return new ArrayList<>((Collection<?>) rows.execute(part));
	}

	/**
	 * The script of the given <code>rows</code> of the view, which uses the selected part as
	 * <code>part</code>.
	 */
	private static QueryExecutor rows(int index) throws Exception {
		NodeList elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(BUILDINGS_VIEW))
			.getElementsByTagName("rows");
		String source = ((Element) elements.item(index)).getTextContent();
		return ViewScripts.compile(ExprFormat.INSTANCE.getValue("script", source), List.of("part"));
	}

	private static List<TLObject> list(TLObject obj, String attribute) {
		List<TLObject> result = new ArrayList<>();
		for (Object value : (Collection<?>) obj.tValueByName(attribute)) {
			result.add((TLObject) value);
		}
		return result;
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBuildingContents.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
