/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.virtual.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.VirtualTesting.read;
import static test.de.haumacher.ehome.virtual.VirtualTesting.writing;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.virtual.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.device.ProjectDevices;
import de.haumacher.ehome.virtual.device.SimulatedDevice;
import de.haumacher.ehome.virtual.server.TransportTiming;
import de.haumacher.ehome.virtual.server.VirtualInterface;
import de.haumacher.ehome.virtual.server.VirtualInterface.Endpoint;
import de.haumacher.ehome.virtual.trace.TraceEntry;
import de.haumacher.ehome.virtual.trace.TraceFormat;
import de.haumacher.ehome.virtual.trace.TraceOperations;

/**
 * E-Home's own download into the virtual device (haumacher/ehome #27), over KNXnet/IP tunnelling
 * on the loopback address: the MDT actuator (1.3.5) and the Gira Tastsensor (1.3.50) of
 * <code>samples/KNX-Testsystem.knxproj</code> (mask 0705) and the BCU 1 program of
 * <code>samples/Tastsensor2.knxproj</code>.
 *
 * <p>
 * For each: the trace recorded by the virtual device, read back from its file, is the download
 * plan operation by operation; the memory of the simulated device afterwards holds every block of
 * the download image (under its mask), the blocks the plan does not load hold the program's static
 * data as before; and the comparison of E-Home (read path, {@link CalimeroReader}) finds the device
 * identical to what the download left (the image, and the procedure's own inline data over it).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVirtualDownload extends BasicTestCase {

	public void testTestsystem() throws Exception {
		TLObject project = importProject("samples/KNX-Testsystem.knxproj");
		try {
			// The option of the virtual device: it ends the connection itself after the restart (the
			// header of the trace says so).
			download(device(project, 0x1305), "actuator", RESTART_DISCONNECTS);
			// The default: the device stays silent after the restart, E-Home ends the connection.
			download(device(project, 0x1332), "tastsensor", TransportTiming.DEFAULT);
		} finally {
			delete(project);
		}
	}

	public void testBcu1() throws Exception {
		TLObject project = importProject("samples/Tastsensor2.knxproj");
		try {
			List<TLObject> devices = devices(project).stream()
				.filter(d -> DeviceConfiguration.program(d) != null && DeviceConfiguration.program(d).getMask() != null
					&& (DeviceConfiguration.program(d).getMask().getNumber() & 0xFFF0) == 0x0010)
				.toList();
			assertFalse("A BCU 1 device in Tastsensor2.knxproj.", devices.isEmpty());
			// The default timing: the device keeps the connection after the restart, E-Home ends it.
			download(devices.get(0), "bcu1", TransportTiming.DEFAULT);
		} finally {
			delete(project);
		}
	}

	/** The option of the virtual device: it ends the connection after a restart. */
	static final TransportTiming RESTART_DISCONNECTS = TransportTiming.DEFAULT.withDisconnectOnRestart(true);

	private void download(TLObject device, String name, TransportTiming timing) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
		byte[] initial = simulated.memorySnapshot();
		VirtualInterfaceManager manager = manager("download-" + name);
		Endpoint endpoint = loopback();
		VirtualInterface running = manager.start(simulated, endpoint, timing, Map.of("plan", name));
		File file = running.recorder().file();
		try {
			writing(() -> {
				try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), plan.getIndividualAddress())) {
					// Real waits (the delays of the load procedure).
					DownloadExecutor.Result result = new DownloadExecutor().execute(plan, writer, (number, count, operation) -> {
						// No progress.
					});
					assertTrue(name + ": " + result.failure(), result.success());
				}
			});
		} finally {
			manager.stop();
		}

		// The trace equals the plan.
		TraceFormat.Trace trace = read(file);
		assertRestartEndsAtOnce(name, trace.entries(), timing);
		assertEquals(timing.describe(), trace.header().get(VirtualInterfaceManager.HEADER_TRANSPORT));
		List<Operation> recorded = TraceOperations.operations(trace.entries());
		List<String> differences = TraceOperations.compare(plan.getOperations(), recorded);
		assertEquals(name + ": the trace differs from the plan\n" + plan.dump(), List.of(), differences);
		Logger.info(name + ": " + recorded.size() + " operations recorded in " + file, TestVirtualDownload.class);

		// The memory equals the image, except where the procedure writes inline data of its own over
		// it (BCU 1: RunError at 010D, 00 in the program's static data, FF at the end of the load).
		Map<Integer, Integer> lastWritten = new TreeMap<>();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.WriteMemory write) {
				byte[] data = write.data();
				for (int n = 0; n < data.length; n++) {
					lastWritten.put(write.address() + n, data[n] & 0xFF);
				}
			}
		}
		byte[] memory = simulated.memorySnapshot();
		for (Map.Entry<Integer, Integer> written : lastWritten.entrySet()) {
			assertEquals(String.format("%s: memory %04X as last written", name, written.getKey()),
				written.getValue().intValue(), memory[written.getKey()] & 0xFF);
		}
		int bytes = 0;
		List<Integer> overwritten = new ArrayList<>();
		for (Block block : plan.getImage().getBlocks()) {
			assertNull(name + ": a relative segment, its place is not known: " + block.name(), block.stateMachine());
			for (int n = 0; n < block.data().length; n++) {
				int address = block.address() + n;
				int bits = block.mask()[n] & 0xFF;
				if (bits == 0) {
					continue;
				}
				if (inUnloaded(plan, address)) {
					assertEquals(String.format("%s: %04X is not loaded, it keeps the static data", name, address),
						initial[address], memory[address]);
				} else if (((block.data()[n] ^ memory[address]) & bits) != 0) {
					overwritten.add(address);
				} else {
					bytes++;
				}
			}
		}
		assertEquals(name + ": bytes of the image overwritten by inline data of the procedure", overwritten(name),
			overwritten);
		Logger.info(name + ": " + bytes + " bytes of the image in the simulated device.", TestVirtualDownload.class);

		// The read path, against the same device behind a new interface: the comparison finds the
		// device holding the image.
		manager.start(simulated, endpoint, Map.of("compare", name));
		try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
			DeviceComparison.Result comparison = DeviceComparison.compare(DeviceConfiguration.program(device),
				plan, reader, (number, count, block) -> {
					// No progress.
				});
			assertEquals(String.format("%02X %02X", simulated.mask() >>> 8, simulated.mask() & 0xFF),
				comparison.descriptor());
			Logger.info(name + ": comparison " + comparison.facts(), TestVirtualDownload.class);
			assertFalse(comparison.blocks().isEmpty());
			List<Integer> different = new ArrayList<>();
			for (DeviceComparison.BlockResult block : comparison.blocks()) {
				assertNull(name + ", " + block.name(), block.failure());
				different.addAll(block.different());
			}
			// The comparison expects the procedure's own writes over the image (haumacher/ehome #29).
			assertEquals(name + ": differences found by the comparison", List.of(), different);
			for (String fact : comparison.facts()) {
				if (fact.startsWith("Program version: ") || fact.startsWith("Program identity ")) {
					String[] parts = fact.substring(fact.indexOf(':') + 2).split(", the (program|image)'s: ");
					assertEquals(name + ": " + fact, parts[1], parts[0]);
				} else if (fact.startsWith("Load state machine ")) {
					assertTrue(name + ": " + fact, fact.endsWith(": loaded"));
				}
			}

			// A device that does not hold the procedure's own data (BCU 1: the application stopped,
			// RunError not FF) differs there.
			for (int address : overwritten) {
				simulated.initMemory(address, new byte[] { (byte) ~simulated.memorySnapshot()[address] });
			}
			DeviceComparison.Result stopped = DeviceComparison.compare(DeviceConfiguration.program(device), plan,
				reader, (number, count, block) -> {
					// No progress.
				});
			List<Integer> differentStopped = new ArrayList<>();
			for (DeviceComparison.BlockResult block : stopped.blocks()) {
				differentStopped.addAll(block.different());
			}
			assertEquals(name + ": differences where the procedure's data is missing", overwritten, differentStopped);
		} finally {
			manager.stop();
		}
	}

	/**
	 * The addresses at which the load procedure writes inline data over the image.
	 */
	private static List<Integer> overwritten(String name) {
		return "bcu1".equals(name) ? List.of(0x010D) : List.of();
	}

	/**
	 * After each restart, the connection ends at once (haumacher/ehome #30): by E-Home right after
	 * the device's acknowledgement, or by the device; no wait for an idle timeout.
	 */
	static void assertRestartEndsAtOnce(String name, List<TraceEntry> entries, TransportTiming timing) {
		int restarts = 0;
		for (int n = 0; n < entries.size(); n++) {
			TraceEntry restart = entries.get(n);
			if (!restart.incoming() || !TraceEntry.RESTART.equals(restart.service())) {
				continue;
			}
			restarts++;
			TraceEntry end = null;
			for (TraceEntry entry : entries.subList(n + 1, entries.size())) {
				if (TraceEntry.T_DISCONNECT.equals(entry.service())) {
					end = entry;
					break;
				}
			}
			assertNotNull(name + ": no disconnect after the restart", end);
			assertEquals(name + ": who ends the connection", timing.disconnectOnRestart(), end.outgoing());
			long millis = java.time.Duration.between(restart.time(), end.time()).toMillis();
			assertTrue(name + ": the connection ended " + millis + " ms after the restart", millis < 2000);
		}
		assertTrue(name + ": a restart in the trace", restarts > 0);
	}

	private static boolean inUnloaded(DownloadPlan plan, int address) {
		return plan.getUnloadedBlocks().stream().anyMatch(b -> b.contains(address));
	}

	static TLObject importProject(String sample) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(sample);
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		return projects.get(0);
	}

	static void delete(TLObject project) throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project.tDelete();
			tx.commit();
		}
	}

	static TLObject device(TLObject project, int address) {
		for (TLObject device : devices(project)) {
			if (Integer.valueOf(address & 0xFF).equals(device.tValueByName("address"))
				&& DeviceConfiguration.program(device) != null) {
				return device;
			}
		}
		throw new AssertionError("No device " + TraceFormat.individual(address));
	}

	private static List<TLObject> devices(TLObject project) {
		List<TLObject> result = new ArrayList<>();
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualDownload.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
