/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual;

import static test.de.haumacher.ehome.virtual.TestVirtualDownload.delete;
import static test.de.haumacher.ehome.virtual.TestVirtualDownload.device;
import static test.de.haumacher.ehome.virtual.TestVirtualDownload.importProject;
import static test.de.haumacher.ehome.virtual.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.VirtualTesting.read;

import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
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
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.virtual.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.device.ProjectDevices;
import de.haumacher.ehome.virtual.device.SimulatedDevice;
import de.haumacher.ehome.virtual.server.DeviceStack;
import de.haumacher.ehome.virtual.server.VirtualInterface;
import de.haumacher.ehome.virtual.server.VirtualInterface.Endpoint;
import de.haumacher.ehome.virtual.trace.TraceEntry;
import de.haumacher.ehome.virtual.trace.TraceFormat;

/**
 * The programming button of the virtual device and the services ETS 6 sends around a download
 * (haumacher/ehome #35, found with <code>src/test/fixtures/ets-traces/ets6-download-1.3.5.txt</code>):
 * a session like ETS's against the MDT actuator 1.3.5 of <code>samples/KNX-Testsystem.knxproj</code>
 * over KNXnet/IP tunnelling on the loopback address.
 *
 * <ul>
 * <li>Address programming: A_IndividualAddress_Read without the button stays unanswered; with the
 * button the device answers; A_IndividualAddress_Write gives it the new address, at which it answers
 * from then on (still in programming mode, as ETS reads the address back); the restart ends the
 * programming mode, a second press of the button does as well.</li>
 * <li>The properties ETS reads (0/56, 0/11, 0/14): from the project where it holds them (the serial
 * number), else the substitutes named in the header of the trace; A_Authorize_Request.</li>
 * <li>The trace names every service of the session; none is <code>A_Other</code>.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestVirtualProgramming extends BasicTestCase {

	private static final HexFormat HEX = HexFormat.of();

	/** 1.3.5, the actuator's address in the project. */
	private static final int PROJECT_ADDRESS = 0x1305;

	/** 1.3.6, the address the session programs. */
	private static final int NEW_ADDRESS = 0x1306;

	/** A serial number stored in the project, ETS's base64 form of 0083:12345678. */
	private static final String PROJECT_SERIAL = Base64.getEncoder().encodeToString(HEX.parseHex("008312345678"));

	/** How long the tool waits for answers to a broadcast that nobody answers. */
	private static final Duration SILENCE = Duration.ofMillis(400);

	public void testSession() throws Exception {
		TLObject project = importProject("samples/KNX-Testsystem.knxproj");
		try {
			TLObject device = device(project, PROJECT_ADDRESS);

			// Without a serial number in the project: the substitutes, each named.
			SimulatedDevice substituted = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			assertEquals("008365aef06d", HEX.formatHex(substituted.property(0, SimulatedDevice.PID_SERIAL_NUMBER, 1, 1)));
			assertEquals("00", HEX.formatHex(substituted.property(0, SimulatedDevice.PID_DEVICE_CONTROL, 1, 1)));
			assertEquals("002f", HEX.formatHex(substituted.property(0, SimulatedDevice.PID_MAX_APDU_LENGTH, 1, 1)));
			Map<String, String> notes = substituted.notes();
			assertTrue(notes.toString(), notes.get("property 0/11").startsWith("serial number 0083:65AEF06D, a substitute"));
			assertTrue(notes.toString(), notes.get("property 0/14").startsWith("device control 00, a substitute"));
			assertTrue(notes.toString(), notes.get("property 0/56").startsWith("max APDU length 47, a substitute"));

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				device.tUpdateByName("serialNumber", PROJECT_SERIAL);
				tx.commit();
			}
			SimulatedDevice simulated = ProjectDevices.create(device, MappedLoadStateRule.ECHO);
			assertEquals("serial number 0083:12345678 from the project", simulated.notes().get("property 0/11"));

			session(simulated);
		} finally {
			delete(project);
		}
	}

	private void session(SimulatedDevice simulated) throws Exception {
		VirtualInterfaceManager manager = manager("programming");
		Endpoint endpoint = loopback();
		// The device ends the connection after the restart: the tool does not wait for its own timeout.
		VirtualInterface running =
			manager.start(simulated, endpoint,
				TestVirtualDownload.RESTART_DISCONNECTS.withResponseDelay(Duration.ofMillis(20)), Map.of("session", "ETS-like"));
		File file = running.recorder().file();
		try {
			InetSocketAddress gateway = gateway(endpoint);
			KNXNetworkLink link = KNXNetworkLinkIP.newTunnelingLink(new InetSocketAddress(gateway.getAddress(), 0),
				gateway, false, new TPSettings());
			try {
				ManagementClientImpl tool = new ManagementClientImpl(link);
				try {
					tool.responseTimeout(SILENCE);

					// Not in programming mode: no answer, as on real hardware.
					assertEquals(List.of(), devicesInProgrammingMode(tool));

					// The button: the device answers with its address.
					assertTrue(running.pressProgrammingButton());
					tool.responseTimeout(Duration.ofSeconds(3));
					assertEquals(List.of(new IndividualAddress(PROJECT_ADDRESS)), List.of(tool.readAddress(true)));

					// The address written: the device answers there, still in programming mode.
					tool.writeAddress(new IndividualAddress(NEW_ADDRESS));
					assertEquals(List.of(new IndividualAddress(NEW_ADDRESS)), List.of(tool.readAddress(true)));
					assertEquals(NEW_ADDRESS, simulated.address());
					assertEquals(PROJECT_ADDRESS, simulated.initialAddress());
					assertTrue(simulated.programmingMode());

					// The download's first services at the new address, as ETS 6 sends them.
					try (Destination destination = tool.createDestination(new IndividualAddress(NEW_ADDRESS), true)) {
						assertEquals("0705", HEX.formatHex(tool.readDeviceDesc(destination, 0)));
						assertEquals("002f", HEX.formatHex(tool.readProperty(destination, 0, 56, 1, 1)));
						assertEquals(0, tool.authorize(destination, HEX.parseHex("ffffffff")));
						assertEquals("008312345678", HEX.formatHex(tool.readProperty(destination, 0, 11, 1, 1)));
						assertEquals("00", HEX.formatHex(tool.readProperty(destination, 0, 14, 1, 1)));
						// The restart ends the programming mode.
						tool.restart(destination);
					}
					awaitEvent(running, "off");
					assertFalse(simulated.programmingMode());
					tool.responseTimeout(SILENCE);
					assertEquals(List.of(), devicesInProgrammingMode(tool));

					// A second press of the button ends it, too.
					assertTrue(running.pressProgrammingButton());
					assertFalse(running.pressProgrammingButton());
				} finally {
					tool.detach();
				}
			} finally {
				link.close();
			}
		} finally {
			manager.stop();
		}

		// Another line is not routed by the interface (coupler 1.3.0); the device's own line is.
		assertTrue(DeviceStack.routes(0x1300, NEW_ADDRESS));
		assertFalse(DeviceStack.routes(0x1300, 0x1405));
		assertTrue(DeviceStack.routes(0x1000, 0x1405));

		TraceFormat.Trace trace = read(file);
		assertEquals("serial number 0083:12345678 from the project", trace.header().get("property 0/11"));
		assertTrue(trace.header().toString(), trace.header().get("property 0/56").contains("a substitute"));
		List<TraceEntry> entries = trace.entries();
		assertEquals(List.of(), entries.stream().filter(e -> TraceEntry.OTHER.equals(e.service())).toList());

		// Four reads, two answered: from the old address and from the new one.
		assertEquals(4, count(entries, true, TraceEntry.INDIVIDUAL_ADDRESS_READ));
		assertEquals(List.of("1.3.5", "1.3.6"),
			entries.stream().filter(e -> e.outgoing() && TraceEntry.INDIVIDUAL_ADDRESS_RESPONSE.equals(e.service()))
				.map(e -> TraceFormat.individual(e.source()) + (e.destination() == 0 ? "" : " not broadcast")).toList());
		TraceEntry write = only(entries, true, TraceEntry.INDIVIDUAL_ADDRESS_WRITE);
		assertEquals("1.3.6", write.field(TraceEntry.INDIVIDUAL));
		TraceEntry changed = only(entries, false, TraceEntry.DEV_ADDRESS_CHANGED);
		assertEquals("1.3.5", changed.field(TraceEntry.PREVIOUS));
		assertEquals("the_project_keeps_1.3.5", changed.field(TraceEntry.REASON));
		assertEquals(List.of("on button", "off restart", "on button", "off button"),
			entries.stream().filter(e -> TraceEntry.DEV_PROGRAMMING_MODE.equals(e.service()))
				.map(e -> e.field(TraceEntry.MODE) + " " + e.field(TraceEntry.REASON)).toList());

		assertEquals("FFFFFFFF", only(entries, true, TraceEntry.AUTHORIZE_REQUEST).field(TraceEntry.KEY));
		assertEquals("0", only(entries, false, TraceEntry.AUTHORIZE_RESPONSE).field(TraceEntry.LEVEL));
		assertEquals(List.of("56 002F", "11 008312345678", "14 00"),
			entries.stream().filter(e -> e.outgoing() && TraceEntry.PROPERTY_RESPONSE.equals(e.service()))
				.map(e -> e.field(TraceEntry.PROPERTY) + " " + TraceFormat.hex(e.data())).toList());
	}

	private static List<IndividualAddress> devicesInProgrammingMode(ManagementClientImpl tool) throws Exception {
		try {
			return List.of(tool.readAddress(false));
		} catch (KNXTimeoutException ex) {
			return List.of();
		}
	}

	private static void awaitEvent(VirtualInterface running, String mode) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (running.recorder().entries().stream().noneMatch(
			e -> TraceEntry.DEV_PROGRAMMING_MODE.equals(e.service()) && mode.equals(e.field(TraceEntry.MODE)))) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("The programming mode did not switch " + mode + ".");
			}
			Thread.sleep(10);
		}
	}

	private static long count(List<TraceEntry> entries, boolean incoming, String service) {
		return entries.stream().filter(e -> e.incoming() == incoming && service.equals(e.service())).count();
	}

	private static TraceEntry only(List<TraceEntry> entries, boolean incoming, String service) {
		List<TraceEntry> found =
			entries.stream().filter(e -> e.incoming() == incoming && service.equals(e.service())).toList();
		assertEquals(service, 1, found.size());
		return found.get(0);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualProgramming.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
