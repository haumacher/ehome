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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddresses;

/**
 * Probe of the group address creation (issue #18) composed with the rest: the next free address
 * follows creations and deletions, two ranges number independently, a created and connected
 * address reaches the device's download image and the address view, and it survives the export
 * round trip with its external ID.
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressCreationProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		TLObject project = newProject(before);
		File export = new File("target/roundtrip/probe-addresses.knxproj");
		export.getParentFile().mkdirs();
		TLObject reimported = null;
		try {
			TLObject actuator = devicesByAddress(project).get(5);
			List<TLObject> ranges = GroupAddresses.ranges(actuator);
			assertFalse(ranges.isEmpty());
			TLObject middle = ranges.get(0);
			GA next = GroupAddresses.nextFree(middle);
			assertNotNull(next);
			int first = next.getValue();

			// Two creations number on; deleting the last one frees its number again.
			TLObject a;
			TLObject b;
			try (Transaction tx = kb.beginTransaction()) {
				a = GroupAddresses.create(middle, next, "Probe A", null);
				tx.commit();
			}
			assertEquals(first + 1, GroupAddresses.nextFree(middle).getValue());
			try (Transaction tx = kb.beginTransaction()) {
				b = GroupAddresses.create(middle, GroupAddresses.nextFree(middle), "Probe B", null);
				tx.commit();
			}
			assertEquals(first + 2, GroupAddresses.nextFree(middle).getValue());
			String extIdA = ExtIds.extId(a);
			assertTrue(extIdA, extIdA.matches("P-0563-0_GA-\\d+"));
			assertFalse(extIdA.equals(ExtIds.extId(b)));
			try (Transaction tx = kb.beginTransaction()) {
				b.tDelete();
				tx.commit();
			}
			assertEquals(first + 1, GroupAddresses.nextFree(middle).getValue());
			// The number of a deleted address is free again, its ID is not reused.
			TLObject c;
			try (Transaction tx = kb.beginTransaction()) {
				c = GroupAddresses.create(middle, new GA(first + 1, GroupAddressStyle.ThreeLevel), "Probe C", null);
				tx.commit();
			}
			assertFalse(ExtIds.extId(c).equals(extIdA));
			assertTrue(ExtIds.extId(c), Integer.parseInt(ExtIds.extId(c).replaceAll(".*GA-", "")) > Integer
				.parseInt(extIdA.replaceAll(".*GA-", "")));

			// A second middle group, if the project has one, numbers independently.
			TLObject other = ranges.stream().filter(r -> r != middle).findFirst().orElse(null);
			if (other != null) {
				GA nextOther = GroupAddresses.nextFree(other);
				assertNotNull(nextOther);
				assertFalse(nextOther.getValue() == GroupAddresses.nextFree(middle).getValue());
			}

			// Connected to the actuator's channel A: in the device's addresses, in the address view
			// and in the download image.
			Interface channelA = DeviceSettings.interfaces(actuator).stream()
				.filter(i -> i.getPort().getIndex() == 0)
				.findFirst().orElseThrow();
			DownloadImage imageBefore = DownloadImage.compute(actuator);
			try (Transaction tx = kb.beginTransaction()) {
				DeviceSettings.connect(actuator, channelA, a, (TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#receive"));
				tx.commit();
			}
			assertTrue(DeviceSettings.groupAddresses(actuator).contains(a));
			assertEquals(1, GroupAddressConnections.connections(a).size());
			assertEquals(0, GroupAddressConnections.senders(a));
			DownloadImage imageAfter = DownloadImage.compute(actuator);
			assertEquals((imageBefore.getAddressTable().data()[0] & 0xFF) + 1, imageAfter.getAddressTable().data()[0] & 0xFF);

			// The export round trip keeps the created address, its ID and its connection.
			try (OutputStream out = new FileOutputStream(export)) {
				KnxprojExporter.export(project, out);
			}
			List<TLObject> beforeReimport = instances("ehome.project:Project");
			importSample(export.getPath());
			reimported = newProject(beforeReimport);
			TLObject again = devicesByAddress(reimported).get(5);
			TLObject aAgain = DeviceSettings.groupAddresses(again).stream()
				.filter(g -> ((GA) g.tValueByName("address")).getValue() == first)
				.findFirst().orElseThrow();
			assertEquals("Probe A", aAgain.tValueByName("name"));
			assertEquals(extIdA, ExtIds.extId(aAgain));
			assertEquals(1, GroupAddressConnections.connections(aAgain).size());
			assertEquals(first + 2, GroupAddresses.nextFree(GroupAddresses.ranges(again).get(0)).getValue());
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

	private static Map<Integer, TLObject> devicesByAddress(TLObject project) {
		Map<Integer, TLObject> result = new HashMap<>();
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					result.put((Integer) ((TLObject) device).tValueByName("address"), (TLObject) device);
				}
			}
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupAddressCreationProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
