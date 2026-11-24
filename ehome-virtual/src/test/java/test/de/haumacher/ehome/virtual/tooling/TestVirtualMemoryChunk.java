/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.device;
import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.importProject;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.read;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.transport.TransportClient;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.MemoryChunk;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.scan.ReadBack;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;

/**
 * The chunk of the memory accesses from the maximum APDU length of the device and of the path
 * (haumacher/ehome #196), on the actuator 1.3.5 of <code>samples/KNX-Testsystem.knxproj</code>:
 * behind a virtual interface of APDU 55, a device of APDU 55 is downloaded, compared, read out and
 * read back in memory accesses of up to 52 bytes, asked once for its APDU; behind an interface of a
 * standard frame (15), it is not asked and the accesses carry 12 bytes; a device that does not
 * answer the property, and a device managed as BCU 1 (also a coupler of mask 091A), keep 12. Behind an interface announcing 55 whose line
 * carries standard frames only, the first longer access fails without an answer, the accesses fall
 * back to 12 bytes once and the download, the comparison and the read-out complete.
 */
@SuppressWarnings("javadoc")
public class TestVirtualMemoryChunk extends BasicTestCase {

	private static final int APDU = 55;

	private static final int CHUNK = APDU - MemoryChunk.HEADER;

	public void testChunkFormula() {
		assertEquals(12, MemoryChunk.of(15, 15));
		assertEquals(12, MemoryChunk.of(254, 15));
		assertEquals(12, MemoryChunk.of(15, 254));
		assertEquals(44, MemoryChunk.of(254, 47));
		assertEquals(52, MemoryChunk.of(55, 254));
		// A device answering less than a standard frame takes a standard frame.
		assertEquals(12, MemoryChunk.of(254, 0));
		// The count of the memory services has 6 bits.
		assertEquals(63, MemoryChunk.of(66, 254));
		assertEquals(63, MemoryChunk.of(254, 254));
	}

	public void testLongFrames() throws Exception {
		TLObject project = importProject("../ehome/samples/KNX-Testsystem.knxproj");
		try {
			TLObject device = device(project, 0x1305);
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertEquals(plan.dump(), List.of(), plan.getProblems());
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			simulated.initProperty(0, MemoryChunk.PID_MAX_APDU_LENGTH, new byte[] { 0, APDU });
			VirtualInterfaceManager manager = manager("chunk-55");
			Endpoint endpoint = loopback();

			// The download: the plan's writes of 12 bytes as chunks of up to 52.
			VirtualInterface running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, APDU, Map.of("plan", "55"));
			File file = running.recorder().file();
			List<Integer> announced = new ArrayList<>();
			try {
				writing(() -> {
					try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), plan.getIndividualAddress())) {
						DownloadExecutor.Result result = new DownloadExecutor(ms -> {
							// No waits.
						}).execute(plan, writer, (number, count, operation) -> announced.add(number));
						assertTrue(result.failure(), result.success());
						assertEquals(CHUNK, writer.memoryChunk());
					}
				});
			} finally {
				manager.stop();
			}
			List<Integer> expected = new ArrayList<>();
			for (int n = 1; n <= plan.getOperations().size(); n++) {
				expected.add(n);
			}
			assertEquals("Every operation of the plan announced once, in order.", expected, announced);
			List<TraceEntry> entries = read(file).entries();
			assertEquals("One read of the maximum APDU length.", 1, apduReads(entries));
			assertEquals(CHUNK, maxWrite(entries));
			assertTrue(maxRead(entries) > MemoryChunk.STANDARD);
			assertTrue(maxRead(entries) <= CHUNK);

			// The memory holds what the plan writes.
			Map<Integer, Integer> lastWritten = new TreeMap<>();
			for (Operation operation : plan.getOperations()) {
				if (operation instanceof Operation.WriteMemory write) {
					for (int n = 0; n < write.data().length; n++) {
						lastWritten.put(write.address() + n, write.data()[n] & 0xFF);
					}
				}
			}
			byte[] memory = simulated.memorySnapshot();
			for (Map.Entry<Integer, Integer> written : lastWritten.entrySet()) {
				assertEquals(String.format("memory %04X", written.getKey()), written.getValue().intValue(),
					memory[written.getKey()] & 0xFF);
			}

			// The comparison, the read-out and the read-back read in chunks of up to 52.
			running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, APDU, Map.of("compare", "55"));
			file = running.recorder().file();
			try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
				DeviceComparison.Result comparison = DeviceComparison.compare(DeviceConfiguration.program(device), plan,
					reader, (number, count, block) -> {
						// No progress.
					});
				for (DeviceComparison.BlockResult block : comparison.blocks()) {
					assertNull(block.name(), block.failure());
					assertEquals(block.name(), List.of(), block.different());
				}
				Snapshot snapshot = ReadOut.read(device, reader, (number, count, block) -> {
					// No progress.
				});
				assertTrue(snapshot.isComplete());
				ReadBack.read(device, reader, (done, total, what) -> {
					// No progress.
				});
			} finally {
				manager.stop();
			}
			entries = read(file).entries();
			assertEquals("One read of the maximum APDU length.", 1, apduReads(entries));
			assertEquals(CHUNK, maxRead(entries));
			assertEquals("No fall back.", List.of(), dropped(entries));

			// Behind an interface of a standard frame: not asked, 12 bytes.
			running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("compare", "15"));
			file = running.recorder().file();
			try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
				DeviceComparison.Result comparison = DeviceComparison.compare(DeviceConfiguration.program(device), plan,
					reader, (number, count, block) -> {
						// No progress.
					});
				assertFalse(comparison.blocks().isEmpty());
				assertEquals(MemoryChunk.STANDARD, reader.memoryChunk());
			} finally {
				manager.stop();
			}
			entries = read(file).entries();
			assertEquals(0, apduReads(entries));
			assertEquals(MemoryChunk.STANDARD, maxRead(entries));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The transport timing of E-Home in the test of the fall back: shorter waits for the lost frames.
	 */
	private static final TransportClient.Timing SHORT = new TransportClient.Timing(Duration.ofMillis(400), 3,
		Duration.ofMillis(800), Duration.ofSeconds(6));

	/**
	 * Interface and device of APDU 55, the line between them carrying standard frames only (a router
	 * without extended frames announcing more): one fall back to 12 bytes in the download, one in the
	 * reads; the device holds the image, the comparison finds it identical, the read-out is complete.
	 */
	public void testStandardLine() throws Exception {
		TLObject project = importProject("../ehome/samples/KNX-Testsystem.knxproj");
		try {
			TLObject device = device(project, 0x1305);
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			simulated.initProperty(0, MemoryChunk.PID_MAX_APDU_LENGTH, new byte[] { 0, APDU });
			VirtualInterfaceManager manager = manager("chunk-standard-line");
			Endpoint endpoint = loopback();
			TransportTiming line = TransportTiming.DEFAULT.withStandardFramesOnly(true);
			BusPath path = BusPath.gateway(gateway(endpoint));

			VirtualInterface running = manager.start(simulated, endpoint, line, APDU, Map.of("plan", "standard line"));
			File file = running.recorder().file();
			List<Integer> announced = new ArrayList<>();
			try {
				writing(() -> {
					try (CalimeroWriter writer = CalimeroWriter.open(path, plan.getIndividualAddress(), SHORT)) {
						DownloadExecutor.Result result = new DownloadExecutor(ms -> {
							// No waits.
						}).execute(plan, writer, (number, count, operation) -> announced.add(number));
						assertTrue(result.failure(), result.success());
						assertEquals(MemoryChunk.STANDARD, writer.memoryChunk());
					}
				});
			} finally {
				manager.stop();
			}
			assertEquals(plan.getOperations().size(), announced.size());
			List<TraceEntry> entries = read(file).entries();
			assertEquals(1, apduReads(entries));
			// The one longer write, sent with its repetitions, lost; nothing longer afterwards.
			List<TraceEntry> dropped = dropped(entries);
			assertEquals(SHORT.repetitions() + 1, dropped.size());
			assertEquals(MemoryChunk.STANDARD, maxWrite(entries));
			assertEquals(MemoryChunk.STANDARD, maxRead(entries));
			Map<Integer, Integer> lastWritten = new TreeMap<>();
			for (Operation operation : plan.getOperations()) {
				if (operation instanceof Operation.WriteMemory write) {
					for (int n = 0; n < write.data().length; n++) {
						lastWritten.put(write.address() + n, write.data()[n] & 0xFF);
					}
				}
			}
			byte[] memory = simulated.memorySnapshot();
			for (Map.Entry<Integer, Integer> written : lastWritten.entrySet()) {
				assertEquals(String.format("memory %04X", written.getKey()), written.getValue().intValue(),
					memory[written.getKey()] & 0xFF);
			}

			// The comparison, the read-out and the read-back: the first longer answer lost, then 12.
			running = manager.start(simulated, endpoint, line, APDU, Map.of("compare", "standard line"));
			file = running.recorder().file();
			try (CalimeroReader reader = CalimeroReader.open(path, plan.getIndividualAddress(), SHORT)) {
				DeviceComparison.Result comparison = DeviceComparison.compare(DeviceConfiguration.program(device), plan,
					reader, (number, count, block) -> {
						// No progress.
					});
				assertFalse(comparison.blocks().isEmpty());
				for (DeviceComparison.BlockResult block : comparison.blocks()) {
					assertNull(block.name(), block.failure());
					assertEquals(block.name(), List.of(), block.different());
				}
				Snapshot snapshot = ReadOut.read(device, reader, (number, count, block) -> {
					// No progress.
				});
				assertTrue(snapshot.isComplete());
				ReadBack.read(device, reader, (done, total, what) -> {
					// No progress.
				});
				assertEquals(MemoryChunk.STANDARD, reader.memoryChunk());
			} finally {
				manager.stop();
			}
			entries = read(file).entries();
			assertFalse(dropped(entries).isEmpty());
			List<TraceEntry> longReads = entries.stream().filter(e -> e.incoming()
				&& TraceEntry.MEMORY_READ.equals(e.service()) && e.number(TraceEntry.COUNT) > MemoryChunk.STANDARD).toList();
			assertEquals("One longer read, its answer lost.", 1, longReads.size());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static List<TraceEntry> dropped(List<TraceEntry> entries) {
		return entries.stream().filter(e -> TraceEntry.LINE_DROPPED.equals(e.service())).toList();
	}

	/**
	 * A device refusing the property (a negative answer) behind an interface of APDU 55: 12 bytes.
	 */
	public void testNoAnswer() throws Exception {
		SimulatedDevice simulated = new SimulatedDevice("1.3.5", 0x1305, 0x0705, SimulatedDevice.LoadControlKind.NONE,
			MappedLoadStateRule.ECHO);
		simulated.initMemory(0x4000, new byte[64]);
		assertEquals(MemoryChunk.STANDARD, chunk(simulated, "no-answer", false));
	}

	/**
	 * A device of BCU 1 behind an interface of APDU 55: not asked, 12 bytes.
	 */
	public void testBcu1() throws Exception {
		SimulatedDevice simulated = new SimulatedDevice("1.3.5", 0x1305, 0x0012, SimulatedDevice.LoadControlKind.NONE,
			MappedLoadStateRule.ECHO);
		simulated.initMemory(0x0100, new byte[64]);
		assertEquals(MemoryChunk.STANDARD, chunk(simulated, "bcu1", true));
	}

	/**
	 * A coupler of mask 091A (management model <code>Bcu1</code> in the master data of the test
	 * system's project, which has such an IP router) announcing an APDU of 55 behind an interface of
	 * APDU 55: not asked, 12 bytes. Its memory services count in four bits, a read of 52 bytes would be
	 * answered with 4 (haumacher/ehome #227).
	 */
	public void testBcu1Coupler() throws Exception {
		TLObject project = importProject("../ehome/samples/KNX-Testsystem.knxproj");
		try {
			assertTrue(CalimeroReader.bcu1(0x091A));
			assertFalse(CalimeroReader.bcu1(0x0705));
			SimulatedDevice simulated = new SimulatedDevice("1.1.0", 0x1100, 0x091A, SimulatedDevice.LoadControlKind.NONE,
				MappedLoadStateRule.ECHO);
			simulated.initProperty(0, MemoryChunk.PID_MAX_APDU_LENGTH, new byte[] { 0, APDU });
			simulated.initMemory(0x0100, new byte[64]);
			assertEquals(MemoryChunk.STANDARD, chunk(simulated, "coupler", true));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static int chunk(SimulatedDevice simulated, String name, boolean descriptor) throws Exception {
		VirtualInterfaceManager manager = manager("chunk-" + name);
		Endpoint endpoint = loopback();
		VirtualInterface running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, APDU, Map.of());
		File file = running.recorder().file();
		int result;
		try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), simulated.address())) {
			if (descriptor) {
				reader.readDescriptor();
			}
			result = reader.memoryChunk();
			assertEquals(result, reader.memoryChunk());
		} finally {
			manager.stop();
		}
		assertEquals(name, descriptor ? 0 : 1, apduReads(read(file).entries()));
		return result;
	}

	private static int apduReads(List<TraceEntry> entries) {
		return (int) entries.stream()
			.filter(e -> e.incoming() && TraceEntry.PROPERTY_READ.equals(e.service())
				&& e.number(TraceEntry.OBJECT) == 0 && e.number(TraceEntry.PROPERTY) == MemoryChunk.PID_MAX_APDU_LENGTH)
			.count();
	}

	private static int maxWrite(List<TraceEntry> entries) {
		return entries.stream().filter(e -> e.incoming() && TraceEntry.MEMORY_WRITE.equals(e.service()))
			.mapToInt(e -> e.data().length).max().orElse(0);
	}

	private static int maxRead(List<TraceEntry> entries) {
		return entries.stream().filter(e -> e.incoming() && TraceEntry.MEMORY_READ.equals(e.service()))
			.mapToInt(e -> e.number(TraceEntry.COUNT)).max().orElse(0);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualMemoryChunk.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
