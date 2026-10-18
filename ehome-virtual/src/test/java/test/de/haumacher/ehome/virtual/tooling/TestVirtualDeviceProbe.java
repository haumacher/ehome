/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.read;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceFormat;
import de.haumacher.ehome.virtual.tooling.trace.TraceOperations;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;

/**
 * Probe of the virtual device with devices it was not developed against: one device of each
 * program of <code>samples/EHomeDemo.knxproj</code>, each downloaded twice into the same simulated
 * device (the second time into a loaded device). Each trace equals the plan, and the memory holds
 * what the plan wrote last.
 *
 * <p>
 * The test may take longer than the budget of a test class (5 s): each download runs over KNXnet/IP
 * on the loopback interface in real time.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVirtualDeviceProbe extends BasicTestCase {

	public void testEveryProgramOfTheDemo() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("../ehome/samples/EHomeDemo.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			Set<Program> seen = new HashSet<>();
			int downloaded = 0;
			List<String> skipped = new ArrayList<>();
			for (TLObject device : devices(project)) {
				Program program = DeviceConfiguration.program(device);
				if (program == null || !seen.add(program)) {
					continue;
				}
				String name = String.valueOf(device.tValueByName("name")) + " (" + program.getName() + ")";
				if (ProjectDevices.veto(device) != null) {
					skipped.add(name + ": " + ProjectDevices.veto(device));
					continue;
				}
				DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
				if (!plan.getProblems().isEmpty()) {
					skipped.add(name + ": " + plan.getProblems());
					continue;
				}
				SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
				for (int round = 1; round <= 2; round++) {
					download(plan, simulated, name + ", download " + round);
				}
				downloaded++;
			}
			System.out.println("PROBE downloaded " + downloaded + " programs, skipped: " + skipped);
			assertTrue("At least two programs of the demo downloaded, skipped: " + skipped, downloaded >= 2);
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static void download(DownloadPlan plan, SimulatedDevice simulated, String name) throws Exception {
		VirtualInterfaceManager manager = manager("probe");
		Endpoint endpoint = loopback();
		VirtualInterface running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("probe", name));
		File file = running.recorder().file();
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
		TraceFormat.Trace trace = read(file);
		TestVirtualDownload.assertRestartEndsAtOnce(name, trace.entries(), TransportTiming.DEFAULT);
		List<Operation> recorded = TraceOperations.operations(trace.entries());
		assertEquals(name + ": trace differs from the plan", List.of(),
			TraceOperations.compare(plan.getOperations(), recorded));
		byte[] memory = simulated.memorySnapshot();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.WriteMemory write) {
				for (int n = 0; n < write.data().length; n++) {
					int address = write.address() + n;
					if (lastWrite(plan, address) == write) {
						assertEquals(String.format("%s: memory %04X", name, address), write.data()[n],
							memory[address]);
					}
				}
			}
		}
	}

	private static Operation.WriteMemory lastWrite(DownloadPlan plan, int address) {
		Operation.WriteMemory result = null;
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.WriteMemory write && address >= write.address()
				&& address < write.address() + write.data().length) {
				result = write;
			}
		}
		return result;
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualDeviceProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
