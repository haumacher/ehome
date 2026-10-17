/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.server;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Optional;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.device.ios.InterfaceObject;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.mgmt.PropertyAccess.PID;
import tuwien.auto.calimero.server.Launcher;
import tuwien.auto.calimero.server.VirtualLink;
import tuwien.auto.calimero.server.gateway.KnxServerGateway;
import tuwien.auto.calimero.server.gateway.SubnetConnector;

import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.virtual.I18NConstants;
import de.haumacher.ehome.virtual.device.SimulatedDevice;
import de.haumacher.ehome.virtual.trace.TraceFormat;
import de.haumacher.ehome.virtual.trace.TraceRecorder;

/**
 * A KNXnet/IP interface whose KNX subnet holds exactly one {@link SimulatedDevice} and nothing else:
 * search and description, tunnelling (no KNX Secure, no routing), on one local address.
 *
 * <p>
 * Built on calimero-server: its {@link Launcher} sets up a {@link tuwien.auto.calimero.server.knxnetip.KNXnetIPServer}
 * with one service container whose subnet is a {@link VirtualLink} - a subnet in memory without a
 * connection to any bus or gateway, so nothing a client sends is forwarded anywhere. The only
 * device on it is the {@link SimulatedDevice} behind a transport and application layer of E-Home
 * ({@link DeviceStack}, with the timing of {@link TransportTiming}). Every frame the device receives or sends is recorded
 * by the {@link DeviceStack} ({@link FrameDecoder}, {@link TraceRecorder}).
 * </p>
 *
 * <p>
 * The {@link Launcher} is configured by an XML file (Calimero's server configuration format), which
 * is written into the working directory together with the interface objects the server stores
 * there on shutdown (deleted before every start, so that no state of an earlier run is loaded):
 * the container setup of the server (tunnelling addresses, router object) is not available
 * otherwise.
 * </p>
 */
public final class VirtualInterface implements AutoCloseable {

	/** The prefix of the name the interface announces. */
	public static final String NAME_PREFIX = "E-Home virtual ";

	/** How long to wait for the server to come up or go down, in milliseconds. */
	private static final long TIMEOUT = 10_000;

	private static final String SERVER_NAME = "ehome-virtual";

	/** The maximum APDU length of a standard frame on TP1. */
	private static final int TP1_MAX_APDU = 15;

	/**
	 * Where the interface listens.
	 *
	 * @param address
	 *        The local IPv4 address; it must be the first IPv4 address of its network interface
	 *        (Calimero binds the server to that one).
	 * @param port
	 *        The UDP port, 3671 by default.
	 * @param discovery
	 *        Whether to answer search requests (KNXnet/IP discovery) on the network interface of the
	 *        address.
	 */
	public record Endpoint(InetAddress address, int port, boolean discovery) {
		@Override
		public String toString() {
			return address.getHostAddress() + ":" + port;
		}
	}

	private final Endpoint _endpoint;

	private final SimulatedDevice _device;

	private final TraceRecorder _recorder;

	private final Launcher _launcher;

	private final Thread _thread;

	private final int _serverAddress;

	private KNXNetworkLink _deviceLink;

	private DeviceStack _stack;

	private final TransportTiming _timing;

	private VirtualInterface(Endpoint endpoint, SimulatedDevice device, TraceRecorder recorder, TransportTiming timing,
			Launcher launcher, int serverAddress) {
		_endpoint = endpoint;
		_timing = timing;
		_device = device;
		_recorder = recorder;
		_launcher = launcher;
		_serverAddress = serverAddress;
		_thread = new Thread(launcher, SERVER_NAME + " " + endpoint);
		_thread.setDaemon(true);
	}

	/**
	 * Starts a virtual interface.
	 *
	 * @param workDir
	 *        The directory for the server's configuration and the interface objects it saves.
	 * @throws TopLogicException
	 *         If the interface cannot be started, with the reason.
	 */
	public static VirtualInterface start(Endpoint endpoint, SimulatedDevice device, TraceRecorder recorder,
			TransportTiming timing, File workDir) {
		NetworkInterface networkInterface = checkAddress(endpoint.address());
		if (!portFree(endpoint)) {
			throw new TopLogicException(I18NConstants.ERROR_PORT_IN_USE__ENDPOINT.fill(endpoint.toString()));
		}
		int serverAddress = serverAddress(device.address());
		File config = new File(workDir, SERVER_NAME + ".xml");
		try {
			Files.createDirectories(workDir.toPath());
			Files.deleteIfExists(new File(workDir, SERVER_NAME + "-ios.xml").toPath());
			Files.writeString(config.toPath(), configuration(endpoint, networkInterface, device, serverAddress, workDir),
				StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_START__ENDPOINT_DETAIL.fill(endpoint.toString(), ex.getMessage()),
				ex);
		}
		Launcher launcher;
		try {
			launcher = new Launcher(config.toURI().toString());
		} catch (RuntimeException ex) {
			throw new TopLogicException(I18NConstants.ERROR_START__ENDPOINT_DETAIL.fill(endpoint.toString(), ex.getMessage()),
				ex);
		}
		VirtualInterface result = new VirtualInterface(endpoint, device, recorder, timing, launcher, serverAddress);
		try {
			result.connect();
		} catch (RuntimeException ex) {
			result.close();
			throw ex;
		}
		return result;
	}

	private void connect() {
		_thread.start();
		VirtualLink subnet = awaitSubnet();
		// A TP1 interface without extended frames: the server's own maximum APDU length is that of
		// its subnet (15), not that of its KNX IP side (254), which a client reads to size the frames
		// it sends (and with a larger value asks every device for its own maximum first).
		_launcher.getGateway().getServer().getInterfaceObjectServer().setProperty(InterfaceObject.DEVICE_OBJECT, 1,
			PID.MAX_APDULENGTH, 1, 1, (byte) 0, (byte) TP1_MAX_APDU);
		long deadline = System.currentTimeMillis() + TIMEOUT;
		while (!listening(_endpoint)) {
			if (System.currentTimeMillis() > deadline || !_thread.isAlive()) {
				throw new TopLogicException(I18NConstants.ERROR_START__ENDPOINT_DETAIL.fill(_endpoint.toString(),
					"the server does not listen on the port"));
			}
			pause();
		}
		int address = _device.address();
		_deviceLink = subnet.createDeviceLink(new IndividualAddress(address));
		_stack = new DeviceStack(_device, _deviceLink, _recorder, _timing);
	}

	private VirtualLink awaitSubnet() {
		long deadline = System.currentTimeMillis() + TIMEOUT;
		while (true) {
			KnxServerGateway gateway = _launcher.getGateway();
			if (gateway != null) {
				for (SubnetConnector connector : gateway.getSubnetConnectors()) {
					if (connector.getSubnetLink() instanceof VirtualLink link) {
						return link;
					}
				}
			}
			if (System.currentTimeMillis() > deadline || !_thread.isAlive()) {
				throw new TopLogicException(I18NConstants.ERROR_START__ENDPOINT_DETAIL.fill(_endpoint.toString(),
					"the virtual subnet did not come up"));
			}
			pause();
		}
	}

	/**
	 * The name the interface announces (at most 30 characters, as KNXnet/IP allows).
	 */
	public String name() {
		return NAME_PREFIX + TraceFormat.individual(_device.address());
	}

	/**
	 * Where the interface listens.
	 */
	public Endpoint endpoint() {
		return _endpoint;
	}

	/**
	 * The simulated device.
	 */
	public SimulatedDevice device() {
		return _device;
	}

	/**
	 * The timing of the device's transport layer.
	 */
	public TransportTiming timing() {
		return _timing;
	}

	/**
	 * The recorder of the trace.
	 */
	public TraceRecorder recorder() {
		return _recorder;
	}

	/**
	 * The individual address of the interface itself (the server, the coupler of the device's
	 * line); the tunnelling connection gets area.line.255.
	 */
	public int serverAddress() {
		return _serverAddress;
	}

	/**
	 * Stops the interface and waits until its port is free.
	 */
	@Override
	public void close() {
		if (_stack != null) {
			_stack.close();
			_stack = null;
		}
		if (_deviceLink != null) {
			_deviceLink.close();
			_deviceLink = null;
		}
		_launcher.quit();
		try {
			_thread.join(TIMEOUT);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		long deadline = System.currentTimeMillis() + TIMEOUT;
		while (!portFree(_endpoint) && System.currentTimeMillis() < deadline) {
			pause();
		}
	}

	/**
	 * Whether the interface is up.
	 */
	public boolean isRunning() {
		return _thread.isAlive();
	}

	private static NetworkInterface checkAddress(InetAddress address) {
		if (!(address instanceof Inet4Address)) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_IPV4__ADDRESS.fill(address.getHostAddress()));
		}
		NetworkInterface networkInterface;
		try {
			networkInterface = NetworkInterface.getByInetAddress(address);
		} catch (SocketException ex) {
			networkInterface = null;
		}
		if (networkInterface == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_LOCAL__ADDRESS.fill(address.getHostAddress()));
		}
		Optional<InetAddress> first = Collections.list(networkInterface.getInetAddresses()).stream()
			.filter(Inet4Address.class::isInstance).findFirst();
		if (first.isEmpty() || !first.get().equals(address)) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_FIRST_ADDRESS__ADDRESS_INTERFACE_FIRST.fill(
				address.getHostAddress(), networkInterface.getName(), first.map(InetAddress::getHostAddress).orElse("-")));
		}
		return networkInterface;
	}

	/**
	 * Whether the port of the endpoint can be bound for UDP and for TCP (no server listens there; the
	 * KNXnet/IP server uses both).
	 */
	public static boolean portFree(Endpoint endpoint) {
		InetSocketAddress address = new InetSocketAddress(endpoint.address(), endpoint.port());
		try (DatagramSocket socket = new DatagramSocket(null)) {
			socket.setReuseAddress(false);
			socket.bind(address);
		} catch (IOException ex) {
			return false;
		}
		try (ServerSocket socket = new ServerSocket()) {
			// As the server binds it: a connection of an earlier run in TIME_WAIT does not block.
			socket.setReuseAddress(true);
			socket.bind(address);
		} catch (IOException ex) {
			return false;
		}
		return true;
	}

	/**
	 * Whether the server listens on the port of the endpoint, for UDP and for TCP.
	 */
	private static boolean listening(Endpoint endpoint) {
		InetSocketAddress address = new InetSocketAddress(endpoint.address(), endpoint.port());
		boolean udp;
		try (DatagramSocket socket = new DatagramSocket(null)) {
			socket.setReuseAddress(false);
			socket.bind(address);
			udp = false;
		} catch (IOException ex) {
			udp = true;
		}
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(true);
			socket.bind(address);
			return false;
		} catch (IOException ex) {
			return udp;
		}
	}

	/**
	 * The address of the server: the coupler of the device's line (area.line.0), so that Calimero's
	 * gateway routes the frames of the tunnelling connection into the subnet of the device; the
	 * area coupler (area.0.0) for a device numbered 0.
	 *
	 * @throws TopLogicException
	 *         For a device at area.0.0, an area coupler.
	 */
	private static int serverAddress(int device) {
		if ((device & 0xFF) != 0) {
			return device & 0xFF00;
		}
		if ((device & 0x0F00) != 0) {
			return device & 0xF000;
		}
		throw new TopLogicException(I18NConstants.ERROR_COUPLER_ADDRESS__ADDRESS.fill(TraceFormat.individual(device)));
	}

	/**
	 * The address of the tunnelling connection: area.line.255 of the server's line, or .254 if the
	 * device has .255.
	 */
	private static int tunnelAddress(int server, int device) {
		int result = server | 0xFF;
		return result == device ? result - 1 : result;
	}

	private static String configuration(Endpoint endpoint, NetworkInterface networkInterface, SimulatedDevice device,
			int serverAddress, File workDir) {
		String netif = networkInterface.getName();
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<!-- The virtual KNXnet/IP interface of E-Home: one simulated device, no connection to any bus. -->\n"
			+ "<knxServer name=\"" + SERVER_NAME + "\" friendlyName=\"" + NAME_PREFIX
			+ TraceFormat.individual(device.address()) + "\" appData=\"" + xml(workDir.getAbsolutePath()) + "\">\n"
			+ "\t<discovery activate=\"" + endpoint.discovery() + "\" listenNetIf=\"" + xml(netif) + "\" outgoingNetIf=\""
			+ xml(netif) + "\"/>\n"
			+ "\t<serviceContainer activate=\"true\" routing=\"false\" networkMonitoring=\"false\" reuseCtrlEP=\"false\""
			+ " udpPort=\"" + endpoint.port() + "\" listenNetIf=\"" + xml(netif) + "\">\n"
			+ "\t\t<knxAddress type=\"individual\">" + TraceFormat.individual(serverAddress) + "</knxAddress>\n"
			+ "\t\t<knxSubnet type=\"virtual\" medium=\"tp1\">" + SERVER_NAME + "-subnet</knxSubnet>\n"
			+ "\t\t<additionalAddresses>\n"
			+ "\t\t\t<knxAddress type=\"individual\">" + TraceFormat.individual(tunnelAddress(serverAddress, device.address())) + "</knxAddress>\n"
			+ "\t\t</additionalAddresses>\n"
			+ "\t</serviceContainer>\n"
			+ "</knxServer>\n";
	}

	private static String xml(String text) {
		return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
	}

	private static void pause() {
		try {
			Thread.sleep(20);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new TopLogicException(I18NConstants.ERROR_INTERRUPTED);
		}
	}

}
