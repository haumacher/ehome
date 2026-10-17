/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.ProjectIndex;

/**
 * Probe of the send inputs by datapoint kind (haumacher/ehome #24) against the addresses of the
 * project itself: the kind comes from the project's datapoint type when none is entered, an entered
 * type replaces it, and the values the controls deliver (a slider's fraction, a choice's number as
 * text) produce the frames of the type.
 */
@SuppressWarnings("javadoc")
public class TestGroupMonitorInputsProbe extends BasicTestCase {

	public void testProjectAddresses() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		TLObject project = projects.get(0);
		try {
			GroupMonitor monitor = new GroupMonitor(ProjectIndex.of(project));
			MockBusLink link = new MockBusLink();
			monitor.connect(link);
			try {
				monitor.setSending(true);
				Map<String, Integer> byKind = new TreeMap<>();
				Map<String, Integer> firstOfKind = new TreeMap<>();
				for (int address = 1; address < 64; address++) {
					Map<String, Object> target = monitor.target(address, null);
					String kind = String.valueOf(target.get("kind"));
					byKind.merge(kind + " " + target.get("dpt"), 1, Integer::sum);
					firstOfKind.putIfAbsent(kind, address);
				}
				// The test system has one 5.001 and two 5.010 addresses (DPST-5-1, DPST-5-10).
				assertTrue(byKind.toString(), firstOfKind.containsKey("percent"));
				assertTrue(byKind.toString(), firstOfKind.containsKey("scale"));

				int percent = firstOfKind.get("percent");
				// A slider delivers a fraction: 33.3 % is 84.9 of 255, rounded to 85.
				monitor.send(percent, Double.valueOf(33.3), null);
				assertTrue(last(link), last(link).endsWith("00 80 55"));

				// An entered type replaces the project's: the same address as dimming step.
				Map<String, Object> asStep = monitor.target(percent, "3.007");
				assertEquals("step", asStep.get("kind"));
				assertEquals("3.007", asStep.get("dpt"));

				// A choice delivered as the text of its number.
				monitor.send(percent, "3", "20.102");
				assertTrue(last(link), last(link).endsWith("00 80 03"));

				// A text that is no value of the enumeration is refused, nothing sent.
				int count = link.getSent().size();
				try {
					monitor.send(percent, "nonsense", "20.102");
					fail("Unknown enumeration value sent.");
				} catch (TopLogicException ex) {
					assertNotNull(ex.getErrorKey());
				}
				assertEquals(count, link.getSent().size());
			} finally {
				monitor.close();
			}
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static String last(MockBusLink link) {
		List<String> sent = link.getSent();
		return sent.isEmpty() ? "<nothing>" : sent.get(sent.size() - 1);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupMonitorInputsProbe.class,
			ModelService.Module.INSTANCE));
	}

}
