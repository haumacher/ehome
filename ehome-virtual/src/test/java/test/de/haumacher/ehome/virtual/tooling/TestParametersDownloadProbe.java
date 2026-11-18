/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.util.ArrayList;
import java.util.Collection;
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
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;

/**
 * Probe of the download "Parameters only" (haumacher/ehome #195) composed with the group tables and
 * the comparison: the Gira Tastsensor 1.3.50 of the test system (mask 7.5, no segment of code only),
 * downloaded in full, then one group address connection removed and "Parameters only" downloaded.
 * The app's own comparison against the full plan of the new configuration finds no difference, and
 * a second "Parameters only" without a change succeeds as well.
 */
@SuppressWarnings("javadoc")
public class TestParametersDownloadProbe extends BasicTestCase {

	public void testTablesChanged() throws Exception {
		TLObject project = TestVirtualDownload.importProject("../ehome/samples/KNX-Testsystem.knxproj");
		try {
			TLObject device = TestVirtualDownload.device(project, 0x1332);
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);

			assertSuccess("full", download(device, DownloadPlan.Kind.FULL, simulated, "probe-full"));
			assertEquals(List.of(), compare(device, simulated, "probe-after-full"));

			TLObject connection = firstConnection(device);
			DownloadPlan before = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				connection.tDelete();
				tx.commit();
			}
			assertTrue("The default after a full download is \"Parameters only\".",
				de.haumacher.ehome.programming.download.ProgrammingFunctions.parametersOnlyByDefault(device));
			assertFalse("The removed connection changes the tables.",
				before.getImage().getBlocks().toString().equals(
					DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getImage().getBlocks().toString()));
			// Before the download, the device differs from the new plan.
			assertFalse(compare(device, simulated, "probe-before-params").isEmpty());

			assertSuccess("parameters", download(device, DownloadPlan.Kind.PARAMETERS, simulated, "probe-params"));
			assertEquals(List.of(), compare(device, simulated, "probe-after-params"));

			assertSuccess("parameters again",
				download(device, DownloadPlan.Kind.PARAMETERS, simulated, "probe-params-again"));
			assertEquals(List.of(), compare(device, simulated, "probe-after-params-again"));
		} finally {
			TestVirtualDownload.delete(project);
		}
	}

	private static void assertSuccess(String name, DownloadExecutor.Result result) {
		assertTrue(name + ": " + result.failure(), result.success());
	}

	static TLObject firstConnection(TLObject device) {
		for (Object connector : (Collection<?>) device.tValueByName("connectors")) {
			Collection<?> connections = (Collection<?>) ((TLObject) connector).tValueByName("connections");
			if (!connections.isEmpty()) {
				return (TLObject) connections.iterator().next();
			}
		}
		throw new AssertionError("No connection.");
	}

	private static DownloadExecutor.Result download(TLObject device, DownloadPlan.Kind kind,
			SimulatedDevice simulated, String name) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, kind);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		VirtualInterfaceManager manager = manager(name);
		Endpoint endpoint = loopback();
		manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("plan", name));
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
		return result[0];
	}

	/**
	 * The addresses at which the device differs from the full plan of its configuration.
	 */
	private static List<Integer> compare(TLObject device, SimulatedDevice simulated, String name) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		VirtualInterfaceManager manager = manager(name);
		Endpoint endpoint = loopback();
		manager.start(simulated, endpoint, Map.of("compare", name));
		List<Integer> different = new ArrayList<>();
		try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
			DeviceComparison.Result comparison = DeviceComparison.compare(DeviceConfiguration.program(device), plan,
				reader, (number, count, block) -> {
					// No progress.
				});
			for (DeviceComparison.BlockResult block : comparison.blocks()) {
				assertNull(name + ", " + block.name(), block.failure());
				different.addAll(block.different());
			}
		} finally {
			manager.stop();
		}
		return different;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParametersDownloadProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
