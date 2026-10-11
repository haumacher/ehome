/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;

/**
 * Probe of the download (issue #7) with the live reference: the devices of the test system were
 * programmed by ETS from <code>samples/KNX-Testsystem.knxproj</code>, and their memory was read
 * back. The compare of the plan computed from that project with that memory must find every block
 * identical, and a download of that plan onto a device holding that memory must change nothing.
 */
@SuppressWarnings("javadoc")
public class TestDownloadPlanProbe extends BasicTestCase {

	private static final File READBACK = new File("src/test/fixtures/readback");

	public void testProbe() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			Map<Integer, TLObject> devices = devicesByAddress(project);
			probe(devices.get(5), "DI-2-1.3.5", new MockDevice()
				.setProperty(0, 78, "00000000030d")
				.setProperty(3, 13, "0083000c21")
				.setState(1, 1).setState(2, 1).setState(3, 1));
			probe(devices.get(50), "DI-3-1.3.50", new MockDevice()
				.setProperty(3, 13, "000810d711")
				.setState(1, 1).setState(2, 1).setState(3, 1));
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static void probe(TLObject device, String fixture, MockDevice mock) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		assertTrue(plan.bytesWritten() > 0);
		byte[] memory = deviceMemory(fixture);
		mock.setMemory(0, memory.clone());

		// The compare: the device holds exactly what the project says.
		DeviceComparison.Result comparison = DeviceComparison.compare(DeviceConfiguration.program(device),
			plan.getImage(), plan.getUnloadedBlocks(), mock, (number, count, block) -> { /* Ignore. */ });
		assertEquals(0, mock.writes());
		assertEquals("07 05", comparison.descriptor());
		for (DeviceComparison.BlockResult block : comparison.blocks()) {
			assertNull(block.name() + ": " + block.failure(), block.failure());
			assertTrue(block.name() + " at " + String.format("%04X", block.address()) + ": " + block.ranges(),
				block.identical());
		}
		assertEquals(comparison.blocks().size(), comparison.identicalBlocks());
		assertTrue(comparison.blocks().toString(), comparison.blocks().size() >= 3);

		// The download onto a device holding exactly this configuration changes no byte it holds,
		// apart from the segments the plan does not load, which it does not touch either.
		byte[] held = mock.memory().clone();
		DownloadExecutor.Result result = DownloadService.download(device, plan, mock,
			new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
		assertTrue(result.failure(), result.success());
		assertEquals(plan.getOperations().size(), result.done());
		assertTrue(mock.writes() > 0);
		byte[] after = mock.memory();
		int changed = 0;
		StringBuilder changes = new StringBuilder();
		for (int address = 0; address < after.length; address++) {
			if (held[address] != after[address]) {
				changed++;
				if (changed <= 20) {
					changes.append(String.format("%n%04X: device %02X, after download %02X", address, held[address] & 0xFF,
						after[address] & 0xFF));
				}
			}
		}
		assertEquals(fixture + ": the download changed the device's memory:" + changes, 0, changed);
		for (Block block : plan.getUnloadedBlocks()) {
			for (int n = 0; n < block.data().length; n++) {
				assertEquals(memory[block.address() + n], after[block.address() + n]);
			}
		}
		assertNotNull(device.tValueByName("lastDownload"));
	}

	/** The memory of a device as its read-back fixtures give it. */
	private static byte[] deviceMemory(String device) throws Exception {
		byte[] result = new byte[0x10000];
		File[] files = READBACK.listFiles((dir, name) -> name.startsWith(device + "-") && name.matches(".*-[0-9A-F]{4}\\.hex"));
		assertNotNull(files);
		assertTrue(files.length >= 6);
		for (File file : files) {
			byte[][] readBack = TestDownloadImage.readBack(file.getName());
			int start = (readBack[0][0] & 0xFF) << 8 | readBack[0][1] & 0xFF;
			System.arraycopy(readBack[1], 0, result, start, readBack[1].length);
		}
		return result;
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDownloadPlanProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
