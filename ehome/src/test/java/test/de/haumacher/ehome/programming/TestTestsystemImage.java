/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.Logger;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * The download images of the devices of the user's test system, computed from the ETS project the
 * devices were programmed from (<code>samples/KNX-Testsystem.knxproj</code>, exported 2026-10-11),
 * against the memory read back from the devices the same day: every byte the image writes must be
 * what the device holds, parameters included.
 */
@SuppressWarnings("javadoc")
public class TestTestsystemImage extends BasicTestCase {

	public void testImages() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			Map<Integer, TLObject> devices = devicesByAddress(project);
			assertEquals(3, devices.size());
			// The actuator at 1.3.5 and the Tastsensor at 1.3.50.
			compare(devices.get(5), "DI-2-1.3.5", List.of("0700", "0784", "4000", "4201", "4400", "44EC"));
			// Segment 454E of the Tastsensor (the download marker and the state of the dismantling
			// alarm) is loaded with SegFlags 0: ETS does not write it, the device holds zeros where
			// the image would write the marker. Its load step is the only one without the flag 0x80
			// among the segments with data.
			compare(devices.get(50), "DI-3-1.3.50",
				List.of("0700", "4000", "40F3", "41E5", "435C", "4369", "436F", "4439", "444E"));
			assertEquals(List.of(0x454E), unflaggedSegments(devices.get(50)));
			// ETS's own record of what it loaded into the Tastsensor.
			DownloadImage image = DownloadImage.compute(devices.get(50));
			Map<Integer, byte[]> loaded = EtsLoadedImage.records("samples/KNX-Testsystem.knxproj", "P-0563/0.xml", "P-0563-0_DI-2");
			assertFalse(loaded.isEmpty());
			int bytes = 0;
			for (Map.Entry<Integer, byte[]> record : loaded.entrySet()) {
				byte[] expected = record.getValue();
				for (int n = 0; n < expected.length; n++) {
					int at = record.getKey() + n;
					Integer computed = image.byteAt(at);
					assertNotNull(String.format("The image writes nothing at %04X.", at), computed);
					assertEquals(String.format("ETS loaded image at %04X", at), expected[n] & 0xFF, computed.intValue());
					bytes++;
				}
			}
			Logger.info("Tastsensor: " + bytes + " bytes of the ETS loaded image equal the computed image.",
				TestTestsystemImage.class);
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	/**
	 * Every byte the image writes (within the masks of its blocks) equals the device's memory; the
	 * table blocks exactly.
	 */
	private static void compare(TLObject device, String fixture, List<String> segments) throws Exception {
		DownloadImage image = DownloadImage.compute(device);
		assertEquals(image.getProblems().toString(), 0, image.getProblems().size());
		int compared = 0;
		int differing = 0;
		StringBuilder differences = new StringBuilder();
		for (String segment : segments) {
			byte[][] readBack = TestDownloadImage.readBack(fixture + "-" + segment + ".hex");
			int start = (readBack[0][0] & 0xFF) << 8 | (readBack[0][1] & 0xFF);
			byte[] memory = readBack[1];
			for (int n = 0; n < memory.length; n++) {
				int address = start + n;
				Block block = blockAt(image, address);
				if (block == null) {
					continue;
				}
				int mask = block.mask()[address - block.address()] & 0xFF;
				if (mask == 0) {
					continue;
				}
				int computed = block.data()[address - block.address()] & 0xFF;
				int actual = memory[n] & 0xFF;
				compared++;
				if ((computed & mask) != (actual & mask)) {
					differing++;
					if (differing <= 20) {
						differences.append(String.format("%n%s at %04X: image %02X, device %02X", block.name(), address, computed, actual));
					}
				}
			}
		}
		Logger.info(fixture + ": " + compared + " bytes compared, " + differing + " differ." + differences,
			TestTestsystemImage.class);
		assertEquals(fixture + ": bytes differing from the device:" + differences, 0, differing);
	}

	/** The addresses of the segments with data whose load step lacks the flag 0x80. */
	private static List<Integer> unflaggedSegments(TLObject device) {
		List<Integer> result = new ArrayList<>();
		Program program = DeviceConfiguration.program(device);
		for (LoadProcedure procedure : program.getLoadProcedures()) {
			for (LoadStep step : procedure.getSteps()) {
				if ("AbsSegment".equals(step.getKind().getName()) && (step.getSegmentFlags() & 0x80) == 0
					&& hasData(program, step.getAddress().intValue())) {
					result.add(step.getAddress().intValue());
				}
			}
		}
		return result;
	}

	private static boolean hasData(Program program, int address) {
		return program.getSegments().stream()
			.anyMatch(s -> s instanceof CodeSegment c && c.getAddress() == address && c.getData() != null);
	}

	private static Block blockAt(DownloadImage image, int address) {
		for (Block block : image.getBlocks()) {
			if (block.contains(address)) {
				return block;
			}
		}
		return null;
	}

	private static Map<Integer, TLObject> devicesByAddress(TLObject project) {
		Map<Integer, TLObject> result = new java.util.HashMap<>();
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestTestsystemImage.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
