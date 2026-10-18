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
import java.util.List;
import java.util.Optional;

import tuwien.auto.calimero.device.ios.InterfaceObject;
import tuwien.auto.calimero.mgmt.PropertyAccess.PID;
import tuwien.auto.calimero.server.gateway.KnxServerGateway;
import tuwien.auto.calimero.server.gateway.SubnetConnector;
import tuwien.auto.calimero.server.Launcher;
import tuwien.auto.calimero.server.VirtualLink;

import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.virtual.I18NConstants;

/**
 * A KNXnet/IP server of calimero-server whose KNX subnet is a {@link VirtualLink}: a subnet in
 * memory without a connection to any bus or gateway. Tunnelling only (no KNX Secure, no routing),
 * on one local address; the devices of the subnet are attached through device links of the
 * {@link #subnet()}.
 *
 * <p>
 * The {@link Launcher} is configured by an XML file (Calimero's server configuration format), which
 * is written into the working directory together with the interface objects the server stores
 * there on shutdown (deleted before every start, so that no state of an earlier run is loaded):
 * the container setup of the server (tunnelling addresses, router object) is not available
 * otherwise.
 * </p>
 */
public final class KnxServer implements AutoCloseable {

	/** How long to wait for the server to come up or go down, in milliseconds. */
	private static final long TIMEOUT = 10_000;

	private static final String SERVER_NAME = "ehome-virtual";

	/** The maximum APDU length of a standard frame on TP1. */
	private static final int TP1_MAX_APDU = 15;

	private final Endpoint _endpoint;

	private final Launcher _launcher;

	private final Thread _thread;

	private VirtualLink _subnet;

	private KnxServer(Endpoint endpoint, Launcher launcher) {
		_endpoint = endpoint;
		_launcher = launcher;
		_thread = new Thread(launcher, SERVER_NAME + " " + endpoint);
		_thread.setDaemon(true);
	}

	/**
	 * Starts a server.
	 *
	 * @param friendlyName
	 *        The name the server announces (at most 30 characters).
	 * @param serverAddress
	 *        The individual address of the server (the coupler of the subnet's line).
	 * @param tunnelAddresses
	 *        The individual addresses of the tunnelling connections: as many connections as
	 *        addresses can be open at a time.
	 * @param workDir
	 *        The directory for the server's configuration and the interface objects it saves.
	 * @throws TopLogicException
	 *         If the server cannot be started, with the reason.
	 */
	public static KnxServer start(Endpoint endpoint, String friendlyName, int serverAddress, List<Integer> tunnelAddresses,
			File workDir) {
		NetworkInterface networkInterface = checkAddress(endpoint.address());
		if (!portFree(endpoint)) {
			throw new TopLogicException(I18NConstants.ERROR_PORT_IN_USE__ENDPOINT.fill(endpoint.toString()));
		}
		File config = new File(workDir, SERVER_NAME + ".xml");
		try {
			Files.createDirectories(workDir.toPath());
			Files.deleteIfExists(new File(workDir, SERVER_NAME + "-ios.xml").toPath());
			Files.writeString(config.toPath(),
				configuration(endpoint, networkInterface, friendlyName, serverAddress, tunnelAddresses, workDir),
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
		KnxServer result = new KnxServer(endpoint, launcher);
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
		_subnet = awaitSubnet();
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
	 * The subnet: devices are attached through its device links
	 * ({@link VirtualLink#createDeviceLink(tuwien.auto.calimero.IndividualAddress)}).
	 */
	public VirtualLink subnet() {
		return _subnet;
	}

	/**
	 * Whether the server is up.
	 */
	public boolean isRunning() {
		return _thread.isAlive();
	}

	/**
	 * Stops the server and waits until its port is free.
	 */
	@Override
	public void close() {
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

	private static String configuration(Endpoint endpoint, NetworkInterface networkInterface, String friendlyName,
			int serverAddress, List<Integer> tunnelAddresses, File workDir) {
		String netif = networkInterface.getName();
		StringBuilder tunnels = new StringBuilder();
		for (int tunnel : tunnelAddresses) {
			tunnels.append("\t\t\t<knxAddress type=\"individual\">").append(BusTelegram.individualAddress(tunnel))
				.append("</knxAddress>\n");
		}
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<!-- A virtual KNXnet/IP interface of E-Home: simulated devices, no connection to any bus. -->\n"
			+ "<knxServer name=\"" + SERVER_NAME + "\" friendlyName=\"" + xml(friendlyName) + "\" appData=\""
			+ xml(workDir.getAbsolutePath()) + "\">\n"
			+ "\t<discovery activate=\"" + endpoint.discovery() + "\" listenNetIf=\"" + xml(netif) + "\" outgoingNetIf=\""
			+ xml(netif) + "\"/>\n"
			+ "\t<serviceContainer activate=\"true\" routing=\"false\" networkMonitoring=\"false\" reuseCtrlEP=\"false\""
			+ " udpPort=\"" + endpoint.port() + "\" listenNetIf=\"" + xml(netif) + "\">\n"
			+ "\t\t<knxAddress type=\"individual\">" + BusTelegram.individualAddress(serverAddress) + "</knxAddress>\n"
			+ "\t\t<knxSubnet type=\"virtual\" medium=\"tp1\">" + SERVER_NAME + "-subnet</knxSubnet>\n"
			+ "\t\t<additionalAddresses>\n"
			+ tunnels
			+ "\t\t</additionalAddresses>\n"
			+ "\t</serviceContainer>\n"
			+ "</knxServer>\n";
	}

	private static String xml(String text) {
		return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
	}

	/**
	 * Waits a moment while polling for the server.
	 */
	static void pause() {
		try {
			Thread.sleep(20);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new TopLogicException(I18NConstants.ERROR_INTERRUPTED);
		}
	}

}
