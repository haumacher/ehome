/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;

import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.Gateways;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.programming.download.ProgrammingReport;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.ReadOutJob;
import de.haumacher.ehome.programming.download.RestoreJob;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.programming.download.Snapshots;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;

/**
 * The restore of a snapshot (haumacher/ehome #194) into the virtual device over KNXnet/IP tunnelling:
 * the Gira Tastsensor 1.3.50 of <code>samples/KNX-Testsystem.knxproj</code> (mask 7.5, dynamic table
 * management, download stamps).
 *
 * <p>
 * Downloaded and read out (snapshot A), then a parameter changed and a connection removed (the tables
 * shrink) and downloaded; the restore of A brings the device's memory back to A in every range, its
 * read-back stored as a new snapshot equals A, and a read-out with the project's plan reads A's bytes.
 * A second restore of A finds the device holding it and writes nothing. Refused before anything is
 * written, each with its reason: a snapshot of another program, of another program version, with an
 * unknown byte the restore writes, and one read at another individual address without the explicit
 * choice (with it, the plan writes the target's address into the group address table). The restore of
 * a read-out taken right after a download is that download, for the sensor and the actuator 1.3.5.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVirtualRestore extends BasicTestCase {

	private static final int SENSOR = 0x1332;

	private static final int ACTUATOR = 0x1305;

	public void testRestore() throws Exception {
		TLObject project = TestVirtualDownload.importProject("../ehome/samples/KNX-Testsystem.knxproj");
		VirtualInterfaceManager manager = manager("restore");
		try {
			TLObject device = TestVirtualDownload.device(project, SENSOR);
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			Endpoint endpoint = loopback();
			set(project, "gateway", Gateways.format(gateway(endpoint)));

			// The project's configuration in the device, read out: snapshot A.
			download(device, simulated, manager, endpoint, "first");
			Snapshot a = readOut(device, simulated, manager, endpoint, "a");
			assertTrue(a.isComplete());
			TLObject storedA = ReadOutJob.store(device, a);
			// The snapshot of the project's configuration: the restore is the project's download.
			assertEquals(DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).dump(),
				DownloadPlanner.restore(device, a, false).dump());

			// Another configuration downloaded.
			TestVirtualParametersDownload.changeParameter(device);
			TLObject connection = TestParametersDownloadProbe.firstConnection(device);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				connection.tDelete();
				tx.commit();
			}
			download(device, simulated, manager, endpoint, "changed");
			assertFalse("The device differs from A.", holds(simulated, a));
			Object downloadRevision = device.tValueByName("downloadRevision");
			Object lastDownload = device.tValueByName("lastDownload");

			// Restore A.
			DownloadPlan plan = DownloadPlanner.restore(device, a, false);
			assertEquals(plan.dump(), List.of(), plan.getProblems());
			int snapshots = Snapshots.of(device).size();
			VirtualInterface running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("restore", "a"));
			RecordingJobMonitor monitor = new RecordingJobMonitor();
			Map<?, ?> report = restore(monitor, storedA, false);
			manager.stop();
			assertEquals(String.valueOf(report), Boolean.TRUE, report.get(ProgrammingReport.SUCCESS));
			// The cancel ends with the comparison, before the first write.
			assertEquals(List.of(RestoreJob.PHASE_COMPARE, "uncancelable", RestoreJob.PHASE_WRITE, RestoreJob.PHASE_READBACK),
				monitor.getPhases());
			assertTrue("The restore writes.", writes(running) > 0);
			assertTrue("Memory equals A in every range.", holds(simulated, a));
			// The read-back is stored as a new snapshot and equals A.
			List<TLObject> stored = Snapshots.of(device);
			assertEquals(snapshots + 1, stored.size());
			Snapshot readBack = Snapshots.load(stored.get(0));
			assertTrue("The read-back equals A.", sameReading(a, readBack));
			// A read-out with the project's plan reads A's bytes.
			Snapshot again = readOut(device, simulated, manager, endpoint, "again");
			assertTrue("A new read-out equals A.", sameReading(a, again));
			// The project is not changed: the record of the device's download stays.
			assertEquals(downloadRevision, device.tValueByName("downloadRevision"));
			assertEquals(lastDownload, device.tValueByName("lastDownload"));

			// Restoring A again: the device holds it, nothing is written.
			running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("restore", "equal"));
			monitor = new RecordingJobMonitor();
			report = restore(monitor, storedA, false);
			manager.stop();
			assertEquals(String.valueOf(report), Boolean.TRUE, report.get(ProgrammingReport.SUCCESS));
			assertTrue(String.valueOf(report), String.valueOf(report).contains("holds the snapshot"));
			assertEquals(List.of(RestoreJob.PHASE_COMPARE), monitor.getPhases());
			assertEquals("A restore of the device's state writes nothing.", 0, writes(running));

			// Refused before anything is written.
			Snapshot otherProgram = copy(a, null).setProgram("M-0008_A-FFFF-FF-FFFF");
			refused(device, simulated, manager, endpoint, otherProgram, false,
				"The snapshot was read from a device with the program M-0008_A-FFFF-FF-FFFF");
			Snapshot otherVersion = copy(a, null).setProgramVersion("0008FFFFFF");
			refused(device, simulated, manager, endpoint, otherVersion, false,
				"The snapshot was read from a device with the program version 00 08 FF FF FF");
			int written = firstWrittenFromImage(plan);
			Snapshot unknown = copy(a, Integer.valueOf(written));
			assertNull(unknown.byteAt(written));
			refused(device, simulated, manager, endpoint, unknown, false,
				String.format("The snapshot does not hold 1 bytes the restore writes: %04X.", written));
			int otherAddressValue = SENSOR + 1;
			Snapshot otherAddress = copy(a, null).setAddress(otherAddressValue);
			refused(device, simulated, manager, endpoint, otherAddress, false,
				"The snapshot was read at the individual address 1.3.51; this device has the address 1.3.50.");

			// With the explicit choice, the snapshot of another address is restored with the device's own
			// address in the group address table.
			Snapshot otherTable = copy(a, null).setAddress(otherAddressValue);
			int table = a.getAddressTable().intValue();
			otherTable.put(table + 1, new byte[] { (byte) (otherAddressValue >>> 8), (byte) otherAddressValue });
			DownloadPlan allowed = DownloadPlanner.restore(device, otherTable, true);
			assertEquals(allowed.dump(), List.of(), allowed.getProblems());
			byte[] entry = Arrays.copyOfRange(allowed.getImage().getAddressTable().data(), 1, 3);
			assertEquals(SENSOR, (entry[0] & 0xFF) << 8 | (entry[1] & 0xFF));

			// The actuator 1.3.5 (mask 7.5, the program's own procedure comparing the hardware type): the
			// restore of its read-out is its download.
			TLObject actuator = TestVirtualDownload.device(project, ACTUATOR);
			SimulatedDevice simulatedActuator = ProjectDevices.create(actuator, MappedLoadStateRule.ECHO);
			download(actuator, simulatedActuator, manager, endpoint, "actuator");
			Snapshot actuatorSnapshot = readOut(actuator, simulatedActuator, manager, endpoint, "actuator");
			assertEquals(DownloadPlanner.plan(actuator, DownloadPlan.Kind.FULL).dump(),
				DownloadPlanner.restore(actuator, actuatorSnapshot, false).dump());
		} finally {
			set(project, "gateway", null);
			TestVirtualDownload.delete(project);
		}
	}

	/**
	 * A restore refused with the given reason before the device is written.
	 */
	private static void refused(TLObject device, SimulatedDevice simulated, VirtualInterfaceManager manager,
			Endpoint endpoint, Snapshot snapshot, boolean otherAddress, String reason) throws Exception {
		List<String> problems = DownloadPlanner.restore(device, snapshot, otherAddress).getProblems().stream()
			.map(DownloadPlan::english).toList();
		assertTrue(problems.toString(), problems.stream().anyMatch(p -> p.startsWith(reason)));
		TLObject stored = ReadOutJob.store(device, snapshot);
		byte[] memory = simulated.memorySnapshot();
		VirtualInterface running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("restore", "refused"));
		try {
			restore(new RecordingJobMonitor(), stored, otherAddress);
			fail("Not refused: " + reason);
		} catch (TopLogicException ex) {
			assertTrue(ex.getMessage(), DownloadPlan.english(ex.getErrorKey()).contains(reason));
		} finally {
			manager.stop();
		}
		assertEquals(0, writes(running));
		assertTrue(Arrays.equals(memory, simulated.memorySnapshot()));
	}

	private static Map<?, ?> restore(RecordingJobMonitor monitor, TLObject stored, boolean otherAddress)
			throws Exception {
		Object[] result = new Object[1];
		writing(() -> {
			result[0] = monitor.run(new RestoreJob(null, TypedConfiguration.newConfigItem(RestoreJob.Config.class)),
				List.of(Boolean.valueOf(otherAddress), stored));
		});
		return (Map<?, ?>) result[0];
	}

	private static void download(TLObject device, SimulatedDevice simulated, VirtualInterfaceManager manager,
			Endpoint endpoint, String name) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("download", name));
		try {
			writing(() -> {
				try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), plan.getIndividualAddress())) {
					DownloadExecutor.Result result = new DownloadExecutor().execute(plan, writer, (number, count, op) -> {
						// No progress.
					});
					assertTrue(name + ": " + result.failure(), result.success());
				}
			});
		} finally {
			manager.stop();
		}
	}

	private static Snapshot readOut(TLObject device, SimulatedDevice simulated, VirtualInterfaceManager manager,
			Endpoint endpoint, String name) throws Exception {
		manager.start(simulated, endpoint, Map.of("readout", name));
		try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint),
			DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getIndividualAddress())) {
			return ReadOut.read(device, reader, (number, count, block) -> {
				// No progress.
			});
		} finally {
			manager.stop();
		}
	}

	/**
	 * Whether the device's memory holds every byte the snapshot knows.
	 */
	private static boolean holds(SimulatedDevice simulated, Snapshot snapshot) {
		byte[] memory = simulated.memorySnapshot();
		for (Snapshot.Range range : snapshot.ranges()) {
			for (int n = 0; n < range.data().length; n++) {
				if (range.known().get(n) && memory[range.address() + n] != range.data()[n]) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Whether the reading holds every byte, property and load state of the snapshot, with its program
	 * version and its tables.
	 */
	private static boolean sameReading(Snapshot snapshot, Snapshot reading) {
		for (Snapshot.Range range : snapshot.ranges()) {
			for (int n = 0; n < range.data().length; n++) {
				if (range.known().get(n)
					&& !Integer.valueOf(range.data()[n] & 0xFF).equals(reading.byteAt(range.address() + n))) {
					return false;
				}
			}
		}
		for (Map.Entry<String, byte[]> property : snapshot.getProperties().entrySet()) {
			if (!Arrays.equals(property.getValue(), reading.getProperties().get(property.getKey()))) {
				return false;
			}
		}
		return snapshot.getLoadStates().equals(reading.getLoadStates())
			&& snapshot.getProgramVersion().equals(reading.getProgramVersion())
			&& snapshot.getAddressTable().equals(reading.getAddressTable())
			&& snapshot.getAssociationTable().equals(reading.getAssociationTable());
	}

	/**
	 * The first address the plan writes from a segment of its image (not a table, whose count unknown
	 * leaves the whole table unknown).
	 */
	private static int firstWrittenFromImage(DownloadPlan plan) {
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.WriteMemory write) {
				for (Block block : plan.getImage().getSegments()) {
					if (block.stateMachine() == null && block.contains(write.address())
						&& block.mask()[write.address() - block.address()] != 0) {
						return write.address();
					}
				}
			}
		}
		throw new AssertionError("No memory write of the image.");
	}

	/**
	 * A copy of the snapshot, the byte at the given address unknown.
	 */
	private static Snapshot copy(Snapshot snapshot, Integer unknown) {
		Snapshot result = new Snapshot()
			.setTime(snapshot.getTime())
			.setBusPath(snapshot.getBusPath())
			.setAddress(snapshot.getAddress())
			.setDescriptor(snapshot.getDescriptor())
			.setProgram(snapshot.getProgram())
			.setProgramVersion(snapshot.getProgramVersion())
			.setAddressTable(snapshot.getAddressTable())
			.setAssociationTable(snapshot.getAssociationTable());
		for (Snapshot.LoadState state : snapshot.getLoadStates()) {
			result.setLoadState(state.stateMachine(), state.state());
		}
		for (Map.Entry<String, byte[]> property : snapshot.getProperties().entrySet()) {
			String[] key = property.getKey().split("/");
			result.setProperty(Integer.parseInt(key[0]), Integer.parseInt(key[1]), property.getValue());
		}
		for (Snapshot.Range range : snapshot.ranges()) {
			String hex = range.hex();
			if (unknown != null && unknown >= range.address() && unknown < range.end()) {
				int position = 2 * (unknown - range.address());
				hex = hex.substring(0, position) + Snapshot.UNKNOWN + hex.substring(position + 2);
			}
			result.putHex(range.address(), hex);
		}
		return result;
	}

	/**
	 * The number of writes the device received (memory, property, individual address).
	 */
	private static int writes(VirtualInterface running) {
		return (int) running.recorder().entries().stream()
			.filter(TraceEntry::incoming)
			.map(TraceEntry::service)
			.filter(service -> TraceEntry.PROPERTY_WRITE.equals(service) || TraceEntry.MEMORY_WRITE.equals(service)
				|| TraceEntry.INDIVIDUAL_ADDRESS_WRITE.equals(service))
			.count();
	}

	private static void set(TLObject project, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualRestore.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
