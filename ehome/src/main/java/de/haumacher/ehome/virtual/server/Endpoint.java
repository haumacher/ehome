/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.server;

import java.net.InetAddress;

/**
 * Where a KNXnet/IP server of E-Home ({@link KnxServer}) listens.
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
