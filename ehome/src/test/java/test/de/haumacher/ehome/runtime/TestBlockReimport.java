/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.DatapointSettings;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Virtual devices in an imported project (<code>samples/KNX-Testsystem.knxproj</code>, haumacher/ehome
 * #75): an output bound to a group address a KNX object sends on is refused naming the object; an
 * import of the ETS project again leaves the virtual devices and their bindings as they were. The
 * import is the cost of the test (about a second on shared data).
 */
@SuppressWarnings("javadoc")
public class TestBlockReimport extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

	public void testSenderConflictAndReimport() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(SharedData.newProjects(before));
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			TLObject sent = null;
			TLObject temperature = null;
			for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
				String type = Pages.valueType(address);
				if (sent == null && "boolean".equals(type) && DatapointSettings.sender(address) != null) {
					sent = address;
				}
				if (temperature == null && "real".equals(type)) {
					temperature = address;
				}
			}
			assertNotNull("The test system has a switching address a push button sends on.", sent);
			assertNotNull("The test system has a real address.", temperature);
			TLObject block;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				block = BlockInstances.create(project, BlockInstances.typeKey(RoomController.INSTANCE.manifest()), null,
					ResKey.text("Heizung"));
				block.tUpdateByName("identifier", "heating");
				BlockInstances.bind(BlockInstances.port(block, "temperature"), temperature);
				BlockInstances.bind(BlockInstances.port(block, "switch"), sent);
				tx.commit();
			}
			ImageReport report = RuntimeImage.build(project, 1, NOW).validate();
			assertFalse(report.isValid());
			ImageReport.Refusal refusal = report.getRefusals().get(0);
			assertEquals("blocks[0].outputs.switch", refusal.path());
			assertTrue(refusal.reason(), refusal.reason().startsWith("outputConflict:"));
			assertTrue(refusal.reason(), refusal.reason().contains(DatapointSettings.sender(sent)));

			// The project imported again: a project of its own; the virtual device keeps its bindings.
			List<TLObject> twice = instances("ehome.project:Project");
			importSample("samples/KNX-Testsystem.knxproj");
			projects.addAll(SharedData.newProjects(twice));
			assertEquals(2, projects.size());
			assertEquals(List.of(block), BlockInstances.of(project));
			assertEquals(List.of(temperature), BlockInstances.bound(BlockInstances.port(block, "temperature")));
			assertEquals(List.of(sent), BlockInstances.bound(BlockInstances.port(block, "switch")));
			assertEquals(List.of(), BlockInstances.of(projects.get(1)));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBlockReimport.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
