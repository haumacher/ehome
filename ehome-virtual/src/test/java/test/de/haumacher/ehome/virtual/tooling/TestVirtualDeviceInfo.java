/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.device;
import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.importProject;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.connection.FakeBusController;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.Gateways;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.scan.DeviceInfo;
import de.haumacher.ehome.scan.DeviceInfoJob;
import de.haumacher.ehome.scan.FoundDevice;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;

/**
 * The device info (haumacher/ehome #175) of the actuator 1.3.5 of the test system from its virtual
 * device, run by the job of the command over the project's path to the bus: over a KNXnet/IP gateway
 * (the virtual interface on the loopback address) and through a connected controller (the simulated
 * controller of {@link TestVirtualDownloadThroughController}, its tunnel to the same virtual
 * interface). The device holds the project's program and is in programming mode: identity and state
 * read back equal what it holds and compare equal with the project, both ways; with another program
 * loaded, the program is marked as different. The device receives no write.
 */
@SuppressWarnings("javadoc")
public class TestVirtualDeviceInfo extends BasicTestCase {

	private static final int ACTUATOR = 0x1305;

	/** The program version of the Tastsensor 1.3.50 of the test system. */
	private static final byte[] OTHER_PROGRAM = { 0x00, 0x08, 0x10, (byte) 0xD7, 0x11 };

	public void testDeviceInfo() throws Exception {
		TLObject project = importProject("../ehome/samples/KNX-Testsystem.knxproj");
		FakeBusController controller = null;
		VirtualInterfaceManager manager = manager("device-info");
		try {
			TLObject device = device(project, ACTUATOR);
			Program program = DeviceConfiguration.program(device);
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			// The device holds the project's program (as after a download) and its button was pressed.
			simulated.initProperty(3, LoadControls.PID_PROGRAM_VERSION, DownloadPlanner.applicationId(program));
			assertTrue(simulated.pressProgrammingButton());
			Endpoint endpoint = loopback();
			InetSocketAddress gateway = gateway(endpoint);

			// Over the gateway.
			set(project, "gateway", Gateways.format(gateway));
			assertFalse("Reading needs no write switch.", CalimeroWriter.writeEnabled());
			assertNull(DeviceInfo.veto(device));
			VirtualInterface running = manager.start(simulated, endpoint, Map.of("info", "gateway"));
			DeviceInfo viaGateway;
			try {
				viaGateway = run(device);
			} finally {
				manager.stop();
			}
			assertEquals("Gateway " + Gateways.format(gateway), viaGateway.path());
			assertIdentity(viaGateway, simulated, program);
			assertNoWrite(running);

			// Through the controller: the same.
			controller = FakeBusController.connect(project, "homecontrol-virtual", true);
			set(project, "gateway", null);
			set(project, BusPath.PROGRAMMING_CONTROLLER, controller.controller());
			assertNull(DeviceInfo.veto(device));
			running = manager.start(simulated, endpoint, Map.of("info", "controller"));
			DeviceInfo viaController;
			try (TestVirtualDownloadThroughController.TunnelBridge bridge =
				new TestVirtualDownloadThroughController.TunnelBridge(gateway, controller)) {
				viaController = run(device);
				assertFalse("frames went through the controller", controller.framesSent().isEmpty());
			} finally {
				manager.stop();
			}
			assertEquals("Controller homecontrol-virtual", viaController.path());
			assertIdentity(viaController, simulated, program);
			assertEquals(texts(viaGateway), texts(viaController));
			assertNoWrite(running);

			// Another program loaded at the address: marked as different.
			simulated.initProperty(3, LoadControls.PID_PROGRAM_VERSION, OTHER_PROGRAM);
			manager.start(simulated, endpoint, Map.of("info", "other program"));
			DeviceInfo other;
			try (TestVirtualDownloadThroughController.TunnelBridge bridge =
				new TestVirtualDownloadThroughController.TunnelBridge(gateway, controller)) {
				other = run(device);
			} finally {
				manager.stop();
			}
			assertEquals(DeviceInfo.Comparison.DIFFERENT, rows(other).get("program").comparison());
			assertEquals(FoundDevice.hex(OTHER_PROGRAM), other.found().programVersionText());
			assertEquals(1, other.count(DeviceInfo.Comparison.DIFFERENT));
			assertNotNull("Not the project's device: its serial number is not taken over.", other.serialVeto());
		} finally {
			if (controller != null) {
				controller.stop();
			}
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The identity and state read equal what the virtual device holds and compare equal with the
	 * project; its serial number is a substitute the project does not know.
	 */
	private static void assertIdentity(DeviceInfo info, SimulatedDevice simulated, Program program) {
		assertTrue(info.answered());
		assertEquals("1.3.5", info.addressText());
		assertEquals(String.format("%04X", program.getMask().getNumber()), info.found().descriptorText());
		assertEquals(FoundDevice.hex(DownloadPlanner.applicationId(program)), info.found().programVersionText());
		assertEquals(FoundDevice.hex(simulated.property(0, SimulatedDevice.PID_SERIAL_NUMBER, 1, 1)),
			FoundDevice.hex(info.found().serialNumber()));
		byte[] manufacturer = simulated.property(0, SimulatedDevice.PID_MANUFACTURER_ID, 1, 1);
		assertEquals(Integer.valueOf((manufacturer[0] & 0xFF) << 8 | (manufacturer[1] & 0xFF)),
			info.found().manufacturerId());
		assertEquals(Boolean.TRUE, info.programmingMode());

		Map<String, DeviceInfo.Row> rows = rows(info);
		assertEquals(DeviceInfo.Comparison.EQUAL, rows.get("mask").comparison());
		assertEquals(DeviceInfo.Comparison.EQUAL, rows.get("manufacturer").comparison());
		assertEquals(DeviceInfo.Comparison.EQUAL, rows.get("program").comparison());
		assertEquals(DeviceInfo.Comparison.NOT_IN_PROJECT, rows.get("serialNumber").comparison());
		assertEquals(0, info.count(DeviceInfo.Comparison.DIFFERENT));
		assertTrue(texts(info).toString(), texts(info).contains("Programming mode: On"));
	}

	private static void assertNoWrite(VirtualInterface running) {
		List<String> writes = running.recorder().entries().stream()
			.filter(entry -> entry.incoming())
			.map(TraceEntry::service)
			.filter(service -> TraceEntry.PROPERTY_WRITE.equals(service) || TraceEntry.MEMORY_WRITE.equals(service)
				|| TraceEntry.INDIVIDUAL_ADDRESS_WRITE.equals(service))
			.toList();
		assertEquals("The device info writes nothing.", List.of(), writes);
	}

	private static DeviceInfo run(TLObject device) throws Exception {
		DeviceInfoJob job = new DeviceInfoJob(null, TypedConfiguration.newConfigItem(DeviceInfoJob.Config.class));
		Object result = new RecordingJobMonitor().run(job, List.of(device));
		DeviceInfo info = DeviceInfo.of(result);
		assertNotNull(String.valueOf(result), info);
		return info;
	}

	private static Map<String, DeviceInfo.Row> rows(DeviceInfo info) {
		Map<String, DeviceInfo.Row> result = new LinkedHashMap<>();
		for (DeviceInfo.Row row : info.identity()) {
			result.put(row.name(), row);
		}
		return result;
	}

	/** The identity and state in English, for comparing two readings. */
	private static List<String> texts(DeviceInfo info) {
		List<String> result = new java.util.ArrayList<>();
		for (DeviceInfo.Row row : info.identity()) {
			result.add(row.name() + ": " + row.project() + " / " + row.device() + " " + row.comparison());
		}
		info.state().forEach(state -> result.add(de.haumacher.ehome.programming.download.DownloadPlan.english(state)));
		return result;
	}

	private static void set(TLObject project, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualDeviceInfo.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
