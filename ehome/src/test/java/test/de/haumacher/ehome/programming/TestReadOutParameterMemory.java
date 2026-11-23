/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.Logger;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.ParameterMemory;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.scan.ReadBack;

/**
 * One read-out holds every byte a parameter of the program can place its value in, not only those
 * the project's download image writes (haumacher/ehome #223): the IP router 1.3.1 of
 * <code>samples/KNX-Testsystem.knxproj</code>, whose filter parameters of the main groups 16 to 31
 * (<code>GrpTelSubMain_16_17</code> ...) the switch <code>GrpTelSubMain_16_31</code> turns on, is
 * downloaded into a device in memory with the switch on and a filter parameter off, then the project
 * is set back to the switch off. One read-out and one take-over give the project the device's values,
 * and the read-out then compares identical in every block; so does one take-over from the bus. Also the memory of the module instances a
 * program can have, beyond those a device's values place (<code>TestModules.knxproj</code>).
 */
@SuppressWarnings("javadoc")
public class TestReadOutParameterMemory extends BasicTestCase {

	/** The individual address 1.3.1 of the IP router. */
	private static final int ROUTER = 0x1301;

	/** The switch of the filter parameters of the main groups 16 to 31 (line to main line). */
	private static final String SWITCH = "GrpTelSubMain_16_31";

	/** A filter parameter the switch turns on, at 0157 bit 7. */
	private static final String FILTER = "GrpTelSubMain_16_17";

	public void testOneReadOutDecodesSwitchedParameters() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			Map<Integer, TLObject> devices = new java.util.HashMap<>();
			for (TLObject device : SharedData.within("ehome.physical:Device", projects)) {
				devices.put(DownloadImage.individualAddress(device), device);
			}
			readCounts(devices);

			TLObject router = devices.get(Integer.valueOf(ROUTER));
			assertNotNull(router);
			assertEquals("0", value(router, SWITCH));
			assertNull("The filter is not in effect.", view(router, FILTER));

			// The device: the switch on, the filter (default "freigeben") blocked.
			set(router, SWITCH, "1");
			set(router, FILTER, "0");
			MockDevice mock = programmed(router);
			assertEquals(0, mock.memory()[0x0157] & 0x01);
			// The project: the filter back to its default, the switch off.
			set(router, FILTER, "1");
			set(router, SWITCH, "0");
			assertNull(view(router, FILTER));
			// The project's image does not write the filter's byte; the read-out reads it nevertheless.
			DownloadPlan plan = DownloadPlanner.plan(router, DownloadPlan.Kind.FULL);
			assertEquals(0, written(plan.getImage(), 0x0157));

			Snapshot snapshot = ReadOut.read(router, mock, (number, count, block) -> {
				// No progress.
			});
			assertTrue(snapshot.isComplete());
			assertTrue(snapshot.isKnown(0x0157));

			ReadBack.Report report = ReadBack.takeOver(router, ReadBack.Memory.of(snapshot, router));
			assertTrue(report.problems() + " " + report.differences(), report.identical());
			assertEquals("1", value(router, SWITCH));
			assertEquals("0", value(router, FILTER));

			// The same read-out compares identical with the plan now writing the filter's byte.
			DownloadPlan after = DownloadPlanner.plan(router, DownloadPlan.Kind.FULL);
			assertEquals(0xFF, written(after.getImage(), 0x0157));
			DeviceComparison.Result result =
				DeviceComparison.compare(DeviceConfiguration.program(router), after, snapshot);
			for (DeviceComparison.BlockResult block : result.blocks()) {
				assertNull(block.name(), block.failure());
				assertTrue(block.name() + ": " + block.differences(), block.differences().isEmpty());
			}
			assertEquals(result.blocks().size(), result.identicalBlocks());

			// Likewise the take-over from the bus.
			set(router, FILTER, "1");
			set(router, SWITCH, "0");
			ReadBack.Report fromBus = ReadBack.takeOver(router, ReadBack.read(router, mock, (done, total, what) -> {
				// No progress.
			}));
			assertTrue(fromBus.problems() + " " + fromBus.differences(), fromBus.identical());
			assertEquals("1", value(router, SWITCH));
			assertEquals("0", value(router, FILTER));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public void testModuleInstances() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestModules.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			Program program = null;
			for (TLObject device : SharedData.within("ehome.physical:Device", projects)) {
				Program candidate = DeviceConfiguration.program(device);
				if (candidate != null && "M-0008_A-FFF8-01-0000".equals(candidate.getExtId())) {
					program = candidate;
				}
			}
			assertNotNull(program);
			BitSet memory = ParameterMemory.of(program);
			// The type of each signal the allocator 160..224 (32 each) can place, of three signals.
			for (int offset : new int[] { 160, 192, 224 }) {
				assertTrue(Integer.toHexString(offset), memory.get(0x100 + offset));
			}
			// The number of scenes of each channel the allocator 128..152 (12 each) can place.
			for (int offset : new int[] { 128, 140, 152 }) {
				assertTrue(Integer.toHexString(offset), memory.get(0x100 + offset));
			}
			// The numbers of signals and channels.
			assertTrue(memory.get(0x100 + 120));
			assertTrue(memory.get(0x100 + 121));
			// No parameter lies there.
			assertFalse(memory.get(0x100 + 100));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * Logs the bytes the image writes and those a read-out reads, per device of the test system.
	 */
	private static void readCounts(Map<Integer, TLObject> devices) {
		for (TLObject device : devices.values()) {
			Program program = DeviceConfiguration.program(device);
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			BitSet parameters = ParameterMemory.of(program);
			int written = 0;
			int read = 0;
			int writtenRanges = 0;
			int readRanges = 0;
			for (Block block : plan.getImage().getBlocks()) {
				if (block.stateMachine() != null || plan.getUnloadedBlocks().contains(block)) {
					continue;
				}
				for (byte bits : block.mask()) {
					if (bits != 0) {
						written++;
					}
				}
				read += ParameterMemory.readCount(block, parameters);
				writtenRanges += ranges(block.mask());
				readRanges += ranges(ParameterMemory.readMask(block, parameters));
			}
			assertTrue(read >= written);
			Logger.info(program.getExtId() + ": the image writes " + written + " bytes in " + writtenRanges
				+ " ranges, a read-out reads " + read + " bytes in " + readRanges + " ranges.",
				TestReadOutParameterMemory.class);
		}
	}

	private static int ranges(byte[] mask) {
		int result = 0;
		for (int n = 0; n < mask.length; n++) {
			if (mask[n] != 0 && (n == 0 || mask[n - 1] == 0)) {
				result++;
			}
		}
		return result;
	}

	/**
	 * A device in memory holding what the image of the device's plan writes. E-Home does not load a
	 * coupler (its load procedure writes the coupler's own memory spaces), the router of the test
	 * system was loaded by ETS: its memory is the image.
	 */
	private static MockDevice programmed(TLObject device) {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		MockDevice mock = new MockDevice().setDescriptor("091A");
		for (Block block : plan.getImage().getBlocks()) {
			if (block.stateMachine() != null) {
				continue;
			}
			for (int n = 0; n < block.data().length; n++) {
				int bits = block.mask()[n] & 0xFF;
				int address = block.address() + n;
				int old = mock.memory()[address] & 0xFF;
				mock.setMemory(address, new byte[] { (byte) ((old & ~bits) | (block.data()[n] & bits)) });
			}
		}
		return mock;
	}

	/**
	 * The bits of the given byte the image writes.
	 */
	private static int written(DownloadImage image, int address) {
		for (Block block : image.getBlocks()) {
			if (block.stateMachine() == null && block.contains(address)) {
				return block.mask()[address - block.address()] & 0xFF;
			}
		}
		return 0;
	}

	private static ParameterView view(TLObject device, String name) {
		for (Map.Entry<Parameter, ParameterView> entry : new DeviceConfiguration(device).getActiveViews().entrySet()) {
			if (name.equals(entry.getKey().getName())) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static String value(TLObject device, String name) {
		ParameterView view = view(device, name);
		assertNotNull(name, view);
		return new DeviceConfiguration(device).value(view);
	}

	private static void set(TLObject device, String name, String value) {
		ParameterView view = view(device, name);
		assertNotNull(name, view);
		EnumerationValue entry = null;
		for (EnumerationValue candidate : ((EnumerationType) view.getParameter().getType()).getValues()) {
			if (value.equals(String.valueOf(candidate.getValue()))) {
				entry = candidate;
			}
		}
		assertNotNull(value, entry);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			DeviceSettings.setValue(device, view, entry);
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestReadOutParameterMemory.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
