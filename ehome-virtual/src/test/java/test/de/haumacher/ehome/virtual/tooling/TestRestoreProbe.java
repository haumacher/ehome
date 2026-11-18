/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

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
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.Gateways;
import de.haumacher.ehome.programming.download.ProgrammingReport;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.ReadOutJob;
import de.haumacher.ehome.programming.download.RestoreJob;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;

/**
 * Probe of the restore of a snapshot (haumacher/ehome #194) on another mask: the actuator 1.3.5 of
 * <code>TestMask71.knxproj</code> (mask 7.1, load states and stamps in memory). A read-out A, a
 * changed parameter downloaded, A restored: the device holds A's bytes again, and a second restore
 * finds nothing to write.
 */
@SuppressWarnings("javadoc")
public class TestRestoreProbe extends BasicTestCase {

	public void testMask71() throws Exception {
		TLObject project = TestVirtualDownload.importProject("../ehome/src/test/fixtures/TestMask71.knxproj");
		VirtualInterfaceManager manager = manager("restore-probe");
		try {
			TLObject device = TestVirtualDownload.device(project, 0x1305);
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			Endpoint endpoint = loopback();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("gateway", Gateways.format(gateway(endpoint)));
				tx.commit();
			}

			download(device, simulated, manager, endpoint);
			Snapshot a = readOut(device, simulated, manager, endpoint, "probe-a");
			assertTrue(a.isComplete());
			TLObject storedA = ReadOutJob.store(device, a);

			TestVirtualParametersDownload.changeParameter(device);
			download(device, simulated, manager, endpoint);
			assertFalse("The device differs from A.", holds(simulated, a));

			DownloadPlan plan = DownloadPlanner.restore(device, a, false);
			assertEquals(plan.dump(), List.of(), plan.getProblems());
			manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("restore", "probe"));
			Map<?, ?> report;
			try {
				report = restore(storedA);
			} finally {
				manager.stop();
			}
			assertEquals(String.valueOf(report), Boolean.TRUE, report.get(ProgrammingReport.SUCCESS));
			assertTrue("Memory equals A in every range.", holds(simulated, a));

			// Restoring again: the device holds A already.
			byte[] memory = simulated.memorySnapshot();
			manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("restore", "probe-again"));
			try {
				report = restore(storedA);
			} finally {
				manager.stop();
			}
			assertEquals(String.valueOf(report), Boolean.TRUE, report.get(ProgrammingReport.SUCCESS));
			assertTrue(java.util.Arrays.equals(memory, simulated.memorySnapshot()));
		} finally {
			TestVirtualDownload.delete(project);
		}
	}

	private static Map<?, ?> restore(TLObject stored) throws Exception {
		Object[] result = new Object[1];
		writing(() -> {
			result[0] = new RecordingJobMonitor().run(
				new RestoreJob(null, TypedConfiguration.newConfigItem(RestoreJob.Config.class)),
				List.of(Boolean.FALSE, stored));
		});
		return (Map<?, ?>) result[0];
	}

	private static void download(TLObject device, SimulatedDevice simulated, VirtualInterfaceManager manager,
			Endpoint endpoint) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("download", "probe"));
		try {
			writing(() -> {
				try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), plan.getIndividualAddress())) {
					DownloadExecutor.Result result = new DownloadExecutor().execute(plan, writer, (n, c, op) -> {
						// No progress.
					});
					assertTrue(result.failure(), result.success());
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

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRestoreProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
