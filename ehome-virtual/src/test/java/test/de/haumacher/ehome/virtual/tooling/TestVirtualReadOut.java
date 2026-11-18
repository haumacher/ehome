/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.ReadOutJob;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.programming.download.Snapshots;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;

/**
 * The read-out of a device (haumacher/ehome #193) over KNXnet/IP tunnelling from the virtual device,
 * after E-Home's download into it: the actuator 1.3.5 of <code>samples/KNX-Testsystem.knxproj</code>.
 * The snapshot holds the device's memory in every range of the download image, the program version
 * and the load states it reports; a range the device refuses to read is unknown in the snapshot (not
 * zero), with a note; the snapshot stored with the device reads back equal.
 */
@SuppressWarnings("javadoc")
public class TestVirtualReadOut extends BasicTestCase {

	public void testReadOut() throws Exception {
		TLObject project = TestVirtualDownload.importProject("../ehome/samples/KNX-Testsystem.knxproj");
		try {
			TLObject device = TestVirtualDownload.device(project, 0x1305);
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			VirtualInterfaceManager manager = manager("readout");
			Endpoint endpoint = loopback();
			manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("plan", "readout"));
			try {
				writing(() -> {
					try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), plan.getIndividualAddress())) {
						DownloadExecutor.Result result = new DownloadExecutor().execute(plan, writer,
							(number, count, operation) -> {
								// No progress.
							});
						assertTrue(result.failure(), result.success());
					}
				});
			} finally {
				manager.stop();
			}

			// The largest block of the image, a part of it protected in the device.
			Block largest = null;
			for (Block block : plan.getImage().getBlocks()) {
				if (!plan.getUnloadedBlocks().contains(block) && block.stateMachine() == null
					&& (largest == null || block.data().length > largest.data().length)) {
					largest = block;
				}
			}
			assertNotNull(largest);
			assertTrue("A block of several reads: " + largest.data().length, largest.data().length > 40);
			int protectedStart = largest.address() + 20;
			int protectedLength = 4;

			// Read out as the device holds it, all of it readable.
			byte[] memory = simulated.memorySnapshot();
			manager.start(simulated, endpoint, Map.of("readout", "all"));
			Snapshot snapshot;
			try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
				snapshot = ReadOut.read(device, reader, (number, count, block) -> {
					// No progress.
				});
			} finally {
				manager.stop();
			}
			assertTrue(snapshot.isComplete());
			assertEquals(String.format("%04X", simulated.mask()), snapshot.getDescriptor());
			assertEquals(plan.getIndividualAddress(), snapshot.getAddress());
			assertNotNull("The program version read.", snapshot.getProgramVersion());
			assertEquals(3, snapshot.getLoadStates().size());
			for (Snapshot.LoadState state : snapshot.getLoadStates()) {
				assertEquals(Integer.valueOf(simulated.state(state.stateMachine())), state.state());
			}
			int compared = 0;
			for (Block block : plan.getImage().getBlocks()) {
				if (plan.getUnloadedBlocks().contains(block) || block.stateMachine() != null) {
					continue;
				}
				for (int n = 0; n < block.data().length; n++) {
					if (block.mask()[n] == 0) {
						continue;
					}
					int address = block.address() + n;
					assertEquals(String.format("%04X", address), Integer.valueOf(memory[address] & 0xFF),
						snapshot.byteAt(address));
					compared++;
				}
			}
			assertTrue(compared > 100);

			// The stored snapshot reads back equal.
			TLObject stored = ReadOutJob.store(device, snapshot);
			assertTrue(snapshot.sameContent(Snapshots.load(stored)));

			// A protected range: unknown in the snapshot, the rest of the block read.
			simulated.protectMemory(protectedStart, protectedLength);
			manager.start(simulated, endpoint, Map.of("readout", "protected"));
			Snapshot partly;
			try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
				partly = ReadOut.read(device, reader, (number, count, block) -> {
					// No progress.
				});
			} finally {
				manager.stop();
			}
			int unknown = 0;
			for (int n = 0; n < largest.data().length; n++) {
				if (largest.mask()[n] == 0) {
					continue;
				}
				int address = largest.address() + n;
				assertTrue(partly.isRead(address));
				if (address >= protectedStart && address < protectedStart + protectedLength) {
					assertNull("Unknown, never zero: " + String.format("%04X", address), partly.byteAt(address));
				}
				if (partly.isKnown(address)) {
					assertEquals(Integer.valueOf(memory[address] & 0xFF), partly.byteAt(address));
				} else {
					unknown++;
				}
			}
			// The refused reads: at most two requests touch the protected bytes.
			assertTrue("Unknown bytes: " + unknown,
				unknown >= protectedLength && unknown <= 2 * DownloadPlanner.MAX_CHUNK);
			assertFalse("A note on the refused reads.", partly.getNotes().isEmpty());
			// The unknown bytes stay unknown through the store.
			TLObject storedPartly = ReadOutJob.store(device, partly);
			Snapshot loaded = Snapshots.load(storedPartly);
			assertTrue(partly.sameContent(loaded));
			assertNull(loaded.byteAt(protectedStart));
		} finally {
			TestVirtualDownload.delete(project);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualReadOut.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
