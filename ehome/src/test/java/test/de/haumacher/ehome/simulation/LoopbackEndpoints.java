/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

import de.haumacher.ehome.monitor.CalimeroBusLink;
import de.haumacher.ehome.virtual.server.Endpoint;

/**
 * Endpoints of the KNXnet/IP servers of E-Home in tests (the virtual installation, the virtual device
 * of the module <code>ehome-virtual</code>): the loopback address only, at a free port, without
 * discovery.
 */
public class LoopbackEndpoints {

	/**
	 * The ports {@link #loopback()} hands out, below the ephemeral range of the operating system (Linux:
	 * from 32768): a port taken from that range and closed again could be given to another socket
	 * (a client's local port) before the server binds it.
	 */
	private static final int FIRST_PORT = 20000;

	private static final int PORT_COUNT = 10000;

	private static final AtomicInteger NEXT_PORT =
		new AtomicInteger((int) (ProcessHandle.current().pid() % PORT_COUNT));

	/**
	 * An endpoint on the loopback address at a free port, without discovery.
	 */
	public static Endpoint loopback() throws IOException {
		InetAddress loopback = InetAddress.getByName("127.0.0.1");
		for (int attempt = 0; attempt < PORT_COUNT; attempt++) {
			int port = FIRST_PORT + Math.floorMod(NEXT_PORT.getAndIncrement(), PORT_COUNT);
			try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(loopback, port))) {
				return new Endpoint(loopback, port, false);
			} catch (SocketException ex) {
				// In use, try the next one.
			}
		}
		throw new IOException("No free UDP port on the loopback address.");
	}

	/**
	 * The gateway address of an endpoint on the loopback address, for a client connection
	 * ({@link CalimeroBusLink#open(InetSocketAddress)}).
	 */
	public static InetSocketAddress gateway(Endpoint endpoint) {
		if (!endpoint.address().isLoopbackAddress()) {
			throw new AssertionError("Tests must use the loopback address only: " + endpoint.address());
		}
		return new InetSocketAddress(endpoint.address(), endpoint.port());
	}

}
