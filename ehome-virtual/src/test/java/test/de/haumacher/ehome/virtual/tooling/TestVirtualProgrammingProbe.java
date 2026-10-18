/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.delete;
import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.device;
import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.importProject;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;

/**
 * Probe of the programming button (haumacher/ehome #35) with cases its test does not cover: an
 * address write without the button is ignored, and the Gira Tastsensor 1.3.50 (another mask than the
 * actuator) answers in programming mode as well.
 */
@SuppressWarnings("javadoc")
public class TestVirtualProgrammingProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		TLObject project = importProject("../ehome/samples/KNX-Testsystem.knxproj");
		try {
			check(ProjectDevices.create(device(project, 0x1305), MappedLoadStateRule.ECHO), 0x1305);
			check(ProjectDevices.create(device(project, 0x1332), MappedLoadStateRule.ECHO), 0x1332);
		} finally {
			delete(project);
		}
	}

	private static void check(SimulatedDevice simulated, int address) throws Exception {
		VirtualInterfaceManager manager = manager("probe");
		Endpoint endpoint = loopback();
		VirtualInterface running =
			manager.start(simulated, endpoint,
				TestVirtualDownload.RESTART_DISCONNECTS.withResponseDelay(Duration.ofMillis(20)), Map.of("probe", "button"));
		try {
			InetSocketAddress gateway = gateway(endpoint);
			KNXNetworkLink link = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(gateway.getAddress(), 0),
				gateway, false, new TPSettings());
			try {
				ManagementClientImpl tool = new ManagementClientImpl(link);
				try {
					tool.responseTimeout(Duration.ofMillis(400));
					tool.writeAddress(new IndividualAddress(0x1307));
					Thread.sleep(200);
					assertEquals(address, simulated.address());
					try {
						tool.readAddress(true);
						fail("Answered without the button.");
					} catch (KNXTimeoutException ex) {
						// Expected.
					}
					assertTrue(running.pressProgrammingButton());
					tool.responseTimeout(Duration.ofSeconds(3));
					assertEquals(List.of(new IndividualAddress(address)), List.of(tool.readAddress(true)));
				} finally {
					tool.detach();
				}
			} finally {
				link.close();
			}
		} finally {
			manager.stop();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualProgrammingProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
