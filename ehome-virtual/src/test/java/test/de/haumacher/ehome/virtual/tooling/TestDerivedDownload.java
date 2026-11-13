/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.intent.IntentFixture.control;
import static test.de.haumacher.ehome.intent.IntentFixture.device;
import static test.de.haumacher.ehome.intent.IntentFixture.function;
import static test.de.haumacher.ehome.intent.IntentFixture.inTransaction;
import static test.de.haumacher.ehome.intent.IntentFixture.named;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.intent.IntentFixture;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.intent.Derivation;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;

/**
 * The download of an installation configured by intent (haumacher/ehome #147) into the programmable
 * virtual device: a planned AKK-04 and a planned Tastsensor 3 in a project without ETS data, the
 * function "Licht" on channel A with the actuator preset "Schalten", its controls "Taste 1, toggle"
 * and "Status-LED 1, show status", derived ({@code test.de.haumacher.ehome.intent.TestDerivation}
 * checks the derivation itself).
 *
 * <p>
 * Each device is downloaded over KNXnet/IP tunnelling on the loopback address; afterwards the memory
 * of the simulated device holds every block of the download image, whose address table holds the two
 * derived addresses 1/0/0 and 1/0/1, and E-Home's comparison finds the device identical to the image.
 * The virtual device holds memory, not the application's behaviour: that key 1 toggles the channel
 * with its LED following is checked on the test system.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDerivedDownload extends BasicTestCase {

	public void testDownload() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("../ehome/samples/KNX-Testsystem.knxproj");
		IntentFixture fixture = null;
		try {
			List<TLObject> imported = SharedData.newProjects(before);
			fixture = IntentFixture.create(device(imported, 1, 3, 5), device(imported, 1, 3, 50));
			TLObject project = fixture.project("Intent");
			TLObject[] devices = fixture.planDevices(project, "Aktor", "Taster");
			inTransaction(() -> FunctionProposal.apply(project));
			TLObject licht = named(function(project, devices[0], fixture.channelA), "Licht", fixture.schalten);
			control(licht, devices[1], fixture.key1, fixture.toggle, "toggle");
			control(licht, devices[1], fixture.led1, fixture.status, "showStatus");
			Derivation plan = Derivation.plan(project);
			assertEquals(List.of(), plan.getRefusals());
			inTransaction(() -> Derivation.apply(project, plan.fingerprint()));

			download(devices[0], "derived-actuator");
			download(devices[1], "derived-keys");
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
			if (fixture != null) {
				fixture.cleanUp();
			}
		}
	}

	private void download(TLObject device, String name) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		String table = HexFormat.of().formatHex(plan.getImage().getAddressTable().data());
		assertTrue(name + ": " + table, table.contains("08000801"));
		SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
		VirtualInterfaceManager manager = manager(name);
		Endpoint endpoint = loopback();
		manager.start(simulated, endpoint, TestVirtualDownload.RESTART_DISCONNECTS, Map.of("plan", name));
		try {
			writing(() -> {
				try (CalimeroWriter writer = CalimeroWriter.open(gateway(endpoint), plan.getIndividualAddress())) {
					DownloadExecutor.Result result = new DownloadExecutor().execute(plan, writer, (number, count, operation) -> {
						// No progress.
					});
					assertTrue(name + ": " + result.failure(), result.success());
				}
			});
		} finally {
			manager.stop();
		}

		// The memory holds the image, the derived address table among it.
		byte[] memory = simulated.memorySnapshot();
		for (Block block : plan.getImage().getBlocks()) {
			for (int n = 0; n < block.data().length; n++) {
				int bits = block.mask()[n] & 0xFF;
				if (bits != 0 && !inUnloaded(plan, block.address() + n)) {
					assertEquals(String.format("%s: %04X", name, block.address() + n), block.data()[n] & bits,
						memory[block.address() + n] & bits);
				}
			}
		}

		// The comparison of E-Home finds the device as downloaded.
		VirtualInterface running = manager.start(simulated, endpoint, Map.of("compare", name));
		try (CalimeroReader reader = CalimeroReader.open(gateway(endpoint), plan.getIndividualAddress())) {
			DeviceComparison.Result comparison = DeviceComparison.compare(DeviceConfiguration.program(device), plan,
				reader, (number, count, block) -> {
					// No progress.
				});
			List<Integer> different = new ArrayList<>();
			for (DeviceComparison.BlockResult block : comparison.blocks()) {
				assertNull(name + ", " + block.name(), block.failure());
				different.addAll(block.different());
			}
			assertEquals(name + ": differences found by the comparison", List.of(), different);
		} finally {
			assertNotNull(running);
			manager.stop();
		}
	}

	private static boolean inUnloaded(DownloadPlan plan, int address) {
		return plan.getUnloadedBlocks().stream().anyMatch(b -> b.contains(address));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDerivedDownload.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE));
	}

}
