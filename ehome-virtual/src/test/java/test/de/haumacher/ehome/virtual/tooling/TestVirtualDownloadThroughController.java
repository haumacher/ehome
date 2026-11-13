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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.connection.FakeBusController;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.GroupAddress;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAckTimeoutException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.NetworkLinkListener;
import tuwien.auto.calimero.link.medium.TPSettings;

import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceFormat;
import de.haumacher.ehome.virtual.tooling.trace.TraceOperations;

/**
 * A download and a comparison of E-Home through a connected controller (haumacher/ehome #148): the
 * project's path to the bus is its controller, a simulated controller connection
 * ({@link FakeBusController}) whose tunnel is a KNXnet/IP tunnel to the virtual device on the loopback
 * address ({@link TunnelBridge}), as the controller's tunnel goes to the gateway of the installation.
 * The bus session carries the frames of E-Home's transport connection and load procedure unchanged:
 * the trace of the virtual device is the plan operation by operation, as through a gateway
 * ({@link TestVirtualDownload}), and the comparison through the controller finds the device identical.
 */
@SuppressWarnings("javadoc")
public class TestVirtualDownloadThroughController extends BasicTestCase {

	public void testActuator() throws Exception {
		TLObject project = importProject("../ehome/samples/KNX-Testsystem.knxproj");
		FakeBusController controller = null;
		try {
			TLObject device = device(project, 0x1305);
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertEquals(plan.dump(), List.of(), plan.getProblems());
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			VirtualInterfaceManager manager = manager("controller-download");
			Endpoint endpoint = loopback();

			controller = FakeBusController.connect(project, "homecontrol-virtual", true);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName(BusPath.PROGRAMMING_CONTROLLER, controller.controller());
				tx.commit();
			}
			assertEquals("Controller homecontrol-virtual", BusPath.describe(device));
			assertNull(BusPath.veto(device));

			VirtualInterface running = manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("plan", "actuator"));
			File file = running.recorder().file();
			try (TunnelBridge bridge = new TunnelBridge(gateway(endpoint), controller)) {
				writing(() -> {
					try (CalimeroWriter writer = CalimeroWriter.open(BusPath.of(device), plan.getIndividualAddress())) {
						DownloadExecutor.Result result = new DownloadExecutor().execute(plan, writer, (number, count, operation) -> {
							// No progress.
						});
						assertTrue(String.valueOf(result.failure()), result.success());
					}
				});
				assertTrue("frames went through the controller", controller.framesSent().size() > plan.getOperations().size());
			} finally {
				manager.stop();
			}

			// The trace of the device is the plan, as through a gateway.
			TraceFormat.Trace trace = read(file);
			List<Operation> recorded = TraceOperations.operations(trace.entries());
			assertEquals("the trace differs from the plan\n" + plan.dump(), List.of(),
				TraceOperations.compare(plan.getOperations(), recorded));

			// The comparison through the controller: identical.
			manager.start(simulated, endpoint, Map.of("compare", "actuator"));
			try (TunnelBridge bridge = new TunnelBridge(gateway(endpoint), controller);
					CalimeroReader reader = CalimeroReader.open(BusPath.of(device), plan.getIndividualAddress())) {
				DeviceComparison.Result comparison = DeviceComparison.compare(DeviceConfiguration.program(device), plan,
					reader, (number, count, block) -> {
						// No progress.
					});
				assertEquals("07 05", comparison.descriptor());
				assertFalse(comparison.blocks().isEmpty());
				List<Integer> different = new ArrayList<>();
				for (DeviceComparison.BlockResult block : comparison.blocks()) {
					assertNull(block.name(), block.failure());
					different.addAll(block.different());
				}
				assertEquals(List.of(), different);
			} finally {
				manager.stop();
			}
		} finally {
			if (controller != null) {
				controller.stop();
			}
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The tunnel of the simulated controller: a KNXnet/IP tunnel to the virtual device. A frame of the
	 * bus session goes out on it, its confirmation is the outcome; the indications to the tunnel's
	 * address and the broadcasts go back as <code>frames</code>, as the controller forwards them.
	 */
	static final class TunnelBridge implements FakeBusController.Bus, NetworkLinkListener, AutoCloseable {

		private final KNXNetworkLink _tunnel;

		private final FakeBusController _controller;

		private final IndividualAddress _address;

		TunnelBridge(InetSocketAddress gateway, FakeBusController controller) throws Exception {
			_tunnel = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(gateway.getAddress(), 0), gateway, false,
				new TPSettings());
			_controller = controller;
			_address = _tunnel.getKNXMedium().assignedAddress().orElse(_tunnel.getKNXMedium().getDeviceAddress());
			_tunnel.addLinkListener(this);
			controller.setAddress(_address.toString());
			controller.setBus(this);
		}

		@Override
		public String send(FakeBusController controller, byte[] frame) throws Exception {
			CEMILData request = new CEMILData(frame, 0, frame.length);
			try {
				_tunnel.send(request, true);
				return "confirmed";
			} catch (KNXAckTimeoutException ex) {
				return "negative";
			} catch (KNXTimeoutException ex) {
				return "no_confirmation";
			} catch (KNXLinkClosedException ex) {
				return "lost";
			}
		}

		@Override
		public void indication(FrameEvent e) {
			if (!(e.getFrame() instanceof CEMILData frame)) {
				return;
			}
			boolean own = frame.getDestination() instanceof IndividualAddress destination && destination.equals(_address);
			boolean broadcast = frame.getDestination() instanceof GroupAddress destination && destination.getRawAddress() == 0;
			if (own || broadcast) {
				_controller.frames(frame.toByteArray());
			}
		}

		@Override
		public void close() {
			_tunnel.close();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualDownloadThroughController.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
