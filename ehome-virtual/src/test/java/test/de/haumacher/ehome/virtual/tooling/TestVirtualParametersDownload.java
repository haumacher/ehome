/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.read;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.io.File;
import java.util.Arrays;
import java.util.HexFormat;
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

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.programming.download.ProgrammingFunctions;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;
import de.haumacher.ehome.virtual.tooling.trace.TraceOperations;

/**
 * The download "Parameters only" into the virtual device (haumacher/ehome #195), over KNXnet/IP
 * tunnelling on the loopback address: the actuator 1.3.5 of the app's fixture
 * <code>TestPartialDownload.knxproj</code> (mask 7.5), whose program holds the code segment
 * 4700-470F and whose load procedure does not say which steps belong to which download.
 *
 * <p>
 * After a full download and a parameter changed, "Parameters only" allocates the code segment without
 * writing it (the trace holds no write there), writes the tables and the parameters, and leaves the
 * device holding the full image. A device holding another program version, or one whose application
 * is not loaded, refuses it before anything is written, with the reason.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVirtualParametersDownload extends BasicTestCase {

	private static final int CODE = 0x4700;

	private static final int CODE_END = 0x4710;

	private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

	/**
	 * The outcome of a download into the virtual device with its trace.
	 */
	private record Run(DownloadExecutor.Result result, List<TraceEntry> entries) {
		List<Operation> recorded() {
			return TraceOperations.operations(entries);
		}
	}

	public void testParametersOnly() throws Exception {
		TLObject project = TestVirtualDownload.importProject("../ehome/src/test/fixtures/TestPartialDownload.knxproj");
		try {
			TLObject device = TestVirtualDownload.device(project, 0x1305);
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);

			// The full download writes the code segment.
			DownloadPlan full = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertEquals(full.dump(), List.of(), full.getProblems());
			Run fullRun = download(device, full, simulated, "full");
			assertTrue(fullRun.result().failure(), fullRun.result().success());
			assertTrue("The full download writes the code.", writesCode(fullRun.entries()));
			assertTrue("The device holds the program of its last download.",
				ProgrammingFunctions.parametersOnlyByDefault(device));

			// A parameter changed.
			changeParameter(device);
			DownloadPlan expected = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertFalse("The change reaches the image.", sameMemory(full, expected));

			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
			assertEquals(plan.dump(), List.of(), plan.getProblems());
			assertEquals(plan.dump(), List.of(CODE), plan.getUnloadedBlocks().stream().map(Block::address).toList());
			// The code segment is allocated all the same.
			assertTrue(plan.dump(), plan.getOperations().stream().anyMatch(o -> o instanceof Operation.LoadControl control
				&& Arrays.equals(control.control(), LoadControls.absoluteSegment(0, CODE, CODE_END - CODE, 0xFF, 3, 0x80))));
			// The checks of the program come after the hardware type, before the first write.
			assertEquals(List.of("CompareProperty 0/78", "CheckProgramVersion 3/13 " + HEX.formatHex(
				DownloadPlanner.applicationId(DeviceConfiguration.program(device))), "CheckLoadState 1", "CheckLoadState 2",
				"CheckLoadState 3", "LoadControl 1"),
				plan.getOperations().subList(2, 8).stream().map(TestVirtualParametersDownload::brief).toList());
			assertTrue(plan.bytesWritten() + " < " + expected.bytesWritten(), plan.bytesWritten() < expected.bytesWritten());

			Run run = download(device, plan, simulated, "parameters");
			assertTrue(run.result().failure(), run.result().success());
			assertEquals(plan.dump(), List.of(), TraceOperations.compare(plan.getOperations(), run.recorded()));
			assertFalse("The code segment is not written.", writesCode(run.entries()));
			assertTrue("The parameters are written.", writes(run.entries(), 0x44EC, 0x44EC + 304));
			assertTrue("The address table is written.", writes(run.entries(), 0x4000, 0x4000 + 513));
			assertTrue("The association table is written.", writes(run.entries(), 0x4201, 0x4201 + 511));
			assertTrue("The object table is written.", writes(run.entries(), 0x4400, 0x4400 + 236));

			// The device holds the full image of the changed configuration.
			byte[] memory = simulated.memorySnapshot();
			for (Block block : expected.getImage().getBlocks()) {
				for (int n = 0; n < block.data().length; n++) {
					int bits = block.mask()[n] & 0xFF;
					assertEquals(String.format("%04X (%s)", block.address() + n, block.name()), block.data()[n] & bits,
						memory[block.address() + n] & bits);
				}
			}
			for (int lsm = 1; lsm <= 3; lsm++) {
				assertEquals(LoadControls.STATE_LOADED, simulated.state(lsm));
			}

			// Refused before anything is written: another program version in the device.
			byte[] other = DownloadPlanner.applicationId(DeviceConfiguration.program(device));
			other[other.length - 1] ^= 1;
			simulated.initProperty(3, LoadControls.PID_PROGRAM_VERSION, other);
			refused(device, simulated, "other-version", Operation.CheckProgramVersion.class,
				"The device holds the program version " + HEX.formatHex(other) + ", the program is "
					+ HEX.formatHex(DownloadPlanner.applicationId(DeviceConfiguration.program(device)))
					+ ": only a full download loads the program.");
			simulated.initProperty(3, LoadControls.PID_PROGRAM_VERSION,
				DownloadPlanner.applicationId(DeviceConfiguration.program(device)));

			// Refused before anything is written: the application not loaded.
			simulated.initState(3, LoadControls.STATE_UNLOADED);
			refused(device, simulated, "unloaded", Operation.CheckLoadState.class,
				"Load state machine 3 of the device is \"Unloaded\", not loaded: only a full download loads the program.");
		} finally {
			TestVirtualDownload.delete(project);
		}
	}

	/**
	 * "Parameters only" stops at the check of the given type with the given reason; the device's
	 * memory and load states stay as they were, nothing is written.
	 */
	private void refused(TLObject device, SimulatedDevice simulated, String name, Class<? extends Operation> check,
			String reason) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
		byte[] memory = simulated.memorySnapshot();
		int[] states = { simulated.state(1), simulated.state(2), simulated.state(3) };
		Object revision = device.tValueByName("downloadRevision");
		Run run = download(device, plan, simulated, name);
		assertFalse(name, run.result().success());
		assertTrue(name + ": " + run.result().failed(), check.isInstance(run.result().failed()));
		assertEquals(reason, run.result().failure());
		assertTrue(name, Arrays.equals(memory, simulated.memorySnapshot()));
		assertEquals(states[0], simulated.state(1));
		assertEquals(states[1], simulated.state(2));
		assertEquals(states[2], simulated.state(3));
		assertTrue(name + ": " + run.recorded(), run.recorded().stream().noneMatch(Operation::writes));
		// A failed download leaves the device's record as it was.
		assertEquals(revision, device.tValueByName("downloadRevision"));
	}

	private static Run download(TLObject device, DownloadPlan plan, SimulatedDevice simulated, String name)
			throws Exception {
		VirtualInterfaceManager manager = manager("parameters-download-" + name);
		Endpoint endpoint = loopback();
		File file = manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("plan", name)).recorder().file();
		DownloadExecutor.Result[] result = new DownloadExecutor.Result[1];
		try {
			writing(() -> {
				try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), plan.getIndividualAddress())) {
					result[0] = DownloadService.download(device, plan, writer, new DownloadExecutor(),
						(number, count, operation) -> {
							// No progress.
						});
				}
			});
		} finally {
			manager.stop();
		}
		return new Run(result[0], read(file).entries());
	}

	private static boolean writesCode(List<TraceEntry> entries) {
		return writes(entries, CODE, CODE_END);
	}

	/**
	 * Whether the trace holds a memory write into the given range.
	 */
	private static boolean writes(List<TraceEntry> entries, int from, int to) {
		for (TraceEntry entry : entries) {
			if (entry.incoming() && TraceEntry.MEMORY_WRITE.equals(entry.service())) {
				int address = entry.number(TraceEntry.ADDRESS);
				if (address < to && from < address + entry.data().length) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean sameMemory(DownloadPlan a, DownloadPlan b) {
		List<Block> blocks = a.getImage().getBlocks();
		List<Block> others = b.getImage().getBlocks();
		if (blocks.size() != others.size()) {
			return false;
		}
		for (int n = 0; n < blocks.size(); n++) {
			if (!Arrays.equals(blocks.get(n).data(), others.get(n).data())) {
				return false;
			}
		}
		return true;
	}

	private static String brief(Operation operation) {
		if (operation instanceof Operation.CompareProperty compare) {
			return "CompareProperty " + compare.objectIndex() + "/" + compare.propertyId();
		}
		if (operation instanceof Operation.CheckProgramVersion check) {
			return "CheckProgramVersion " + check.objectIndex() + "/" + check.propertyId() + " " + HEX.formatHex(check.expected());
		}
		if (operation instanceof Operation.CheckLoadState check) {
			return "CheckLoadState " + check.stateMachine();
		}
		if (operation instanceof Operation.LoadControl control) {
			return "LoadControl " + control.stateMachine();
		}
		return operation.getClass().getSimpleName();
	}

	/**
	 * Changes a shown, editable choice of the device whose value lies in the image's memory.
	 */
	static void changeParameter(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		DownloadPlan before = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		for (Option item : configuration.getShownItems().keySet()) {
			if (!(item instanceof ParameterView view) || DeviceSettings.setting(device, view) == null
				|| !DeviceConfiguration.isEditable(view) || !(view.getParameter().getType() instanceof EnumerationType type)
				|| DeviceSettings.editVeto(device, view) != null || view.getParameter().getAddress() == null) {
				continue;
			}
			String value = configuration.value(view);
			for (EnumerationValue entry : type.getValues()) {
				if (ParameterValues.encodeValue(type, entry).equals(value)) {
					continue;
				}
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					DeviceSettings.setValue(device, view, entry);
					tx.commit();
				}
				if (!sameMemory(before, DownloadPlanner.plan(device, DownloadPlan.Kind.FULL))) {
					return;
				}
			}
		}
		throw new AssertionError("No choice of the device changes its memory.");
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualParametersDownload.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
