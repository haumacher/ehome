/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * Probe of the devices in the building tree (issue #19) composed with the rest: the placement of
 * a device survives the export round trip with its part's path, a part's address list is the
 * union over its sub-parts, a device placed from the device side appears in the part, and
 * deleting a device takes it out of its part without touching the part.
 */
@SuppressWarnings("javadoc")
public class TestBuildingDevicesProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestDetails.knxproj");
		TLObject project = newProject(before);
		File export = new File("target/roundtrip/probe-buildings.knxproj");
		export.getParentFile().mkdirs();
		TLObject reimported = null;
		try {
			List<TLObject> devices = devices(project);
			TLObject placed = devices.stream().filter(d -> d.tValueByName("location") != null).findFirst().orElseThrow();
			TLObject unplaced = devices.stream().filter(d -> d.tValueByName("location") == null).findFirst().orElseThrow();
			TLObject room = (TLObject) placed.tValueByName("location");
			String path = BuildingDevices.path(room);
			assertTrue(path, path.startsWith(room.tValueByName("name") + ", "));
			TLObject floor = room.tContainer();
			TLObject house = floor.tContainer();

			// The part holds the device, its ancestors hold it transitively, and the address list of
			// an ancestor is the union over the parts below.
			assertEquals(List.of(placed), BuildingDevices.devices(room));
			assertTrue(BuildingDevices.devices(floor).isEmpty());
			assertTrue(BuildingDevices.allDevices(house).contains(placed));
			Set<TLObject> addresses = new HashSet<>();
			for (TLObject connection : BuildingDevices.connections(house)) {
				addresses.add((TLObject) connection.tValueByName("target"));
			}
			assertFalse(addresses.isEmpty());
			assertEquals(BuildingDevices.connections(room).size(), BuildingDevices.connections(house).size());
			for (TLObject address : addresses) {
				assertTrue(DeviceSettings.groupAddresses(placed).contains(address));
			}

			// Placed from the device side: in the part's devices and offered no longer.
			try (Transaction tx = kb.beginTransaction()) {
				BuildingDevices.place(unplaced, room);
				tx.commit();
			}
			assertEquals(2, BuildingDevices.devices(room).size());
			assertFalse(BuildingDevices.choices(room).contains(unplaced));
			assertEquals(room, unplaced.tValueByName("location"));

			// The export round trip keeps both placements with their parts.
			try (OutputStream out = new FileOutputStream(export)) {
				KnxprojExporter.export(project, out);
			}
			List<TLObject> beforeReimport = instances("ehome.project:Project");
			importSample(export.getPath());
			reimported = newProject(beforeReimport);
			List<TLObject> again = devices(reimported);
			assertEquals(devices.size(), again.size());
			for (TLObject device : again) {
				TLObject location = (TLObject) device.tValueByName("location");
				assertNotNull(device.tValueByName("name") + " lost its location", location);
				assertEquals(path, BuildingDevices.path(location));
			}

			// Deleting a device takes it out of its part; the part stays.
			try (Transaction tx = kb.beginTransaction()) {
				unplaced.tDelete();
				tx.commit();
			}
			assertEquals(List.of(placed), BuildingDevices.devices(room));
			assertTrue(room.tValid());
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				if (reimported != null) {
					reimported.tDelete();
				}
				tx.commit();
			}
		}
	}

	private static TLObject newProject(List<TLObject> before) {
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		return projects.get(0);
	}

	/** The devices of a project: on its lines, and the ones not placed in a line yet. */
	private static List<TLObject> devices(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (Object device : (Collection<?>) project.tValueByName("unassignedDevices")) {
			result.add((TLObject) device);
		}
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					result.add((TLObject) device);
				}
			}
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBuildingDevicesProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
