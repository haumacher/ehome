/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;

import junit.framework.Test;
import junit.framework.TestSuite;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.physical.BusDevice;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.scan.InstallationBuilder;
import test.de.haumacher.ehome.importer.SharedData;

/**
 * Probe of haumacher/ehome #203: the individual address follows the line holding a device when the
 * device moves, in every reader (the derived attribute, the device's name, the download image's
 * address, the connection label), and the stored device number does not change with the move.
 */
public class TestDeviceNumbersProbe extends BasicTestCase {

	private static List<TLObject> _before;

	private static TLObject _project;

	/**
	 * Moves the Tastsensor 1.3.50 of the test system into the new line 15.15 and back.
	 */
	public void testMoveBetweenLines() throws Exception {
		TLObject device = InstallationBuilder.device(_project, 0x1332);
		TLObject home = device.tContainer();
		TLObject line;
		try (Transaction tx = kb().beginTransaction()) {
			line = InstallationBuilder.line(_project, 15, 15);
			home.tRemove(home.tType().getPart("devices"), device);
			line.tAdd(line.tType().getPart("devices"), device);
			tx.commit();
		}
		assertSame(line, device.tContainer());
		assertEquals(Integer.valueOf(50), ((BusDevice) device).getAddress());
		assertEquals("15.15.50", derived(device, "individualAddress"));
		assertEquals(Integer.valueOf(0xFF32), DownloadImage.individualAddress(device));
		assertEquals("15.15.50", GroupAddressConnections.addressText(device));
		assertTrue(String.valueOf(derived(device, "name")), String.valueOf(derived(device, "name")).endsWith(" (15.15.50)"));
		assertTrue(GroupAddressConnections.deviceLabel(device), GroupAddressConnections.deviceLabel(device).endsWith("(15.15.50)"));

		try (Transaction tx = kb().beginTransaction()) {
			line.tRemove(line.tType().getPart("devices"), device);
			home.tAdd(home.tType().getPart("devices"), device);
			tx.commit();
		}
		assertEquals("1.3.50", derived(device, "individualAddress"));
		assertEquals(Integer.valueOf(0x1332), DownloadImage.individualAddress(device));
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	/**
	 * Suite with the test system imported once.
	 */
	public static Test suite() {
		Test test = new junit.extensions.TestSetup(new TestSuite(TestDeviceNumbersProbe.class)) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestDeviceNumbersProbe.class, () -> {
					_before = instances("ehome.project:Project");
					importSample("samples/KNX-Testsystem.knxproj");
					_project = SharedData.newProjects(_before).get(0);
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestDeviceNumbersProbe.class, () -> {
					SharedData.deleteProjects(SharedData.newProjects(_before));
					return null;
				});
				_project = null;
				_before = null;
			}
		};
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(test,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
