/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.net.InetSocketAddress;

import com.top_logic.model.TLObject;

/**
 * The KNXnet/IP gateway a device is reached through: the gateway of its project
 * (<code>ehome.project:Project#gateway</code>), an address with an optional port.
 */
public class Gateways {

	/** The port of KNXnet/IP. */
	public static final int DEFAULT_PORT = 3671;

	/**
	 * The gateway of the project of a device, as entered; <code>null</code> if there is none.
	 */
	public static String of(TLObject device) {
		TLObject root = device;
		while (root.tContainer() != null) {
			root = root.tContainer();
		}
		Object gateway = root.tValueByName("gateway");
		if (gateway instanceof String text && !text.isBlank()) {
			return text.trim();
		}
		return null;
	}

	/**
	 * The socket address of a gateway given as <code>address</code> or <code>address:port</code>.
	 *
	 * @return The address, <code>null</code> if the text is none.
	 */
	public static InetSocketAddress parse(String gateway) {
		if (gateway == null || gateway.isBlank()) {
			return null;
		}
		String text = gateway.trim();
		int port = DEFAULT_PORT;
		int colon = text.lastIndexOf(':');
		if (colon >= 0 && text.indexOf(':') == colon) {
			try {
				port = Integer.parseInt(text.substring(colon + 1).trim());
			} catch (NumberFormatException ex) {
				return null;
			}
			text = text.substring(0, colon).trim();
		}
		if (text.isEmpty() || port <= 0 || port > 0xFFFF || !text.matches("[A-Za-z0-9.\\-]+")) {
			return null;
		}
		return InetSocketAddress.createUnresolved(text, port);
	}

	/**
	 * A gateway address as text.
	 */
	public static String format(InetSocketAddress gateway) {
		return gateway.getHostString() + ":" + gateway.getPort();
	}

	/**
	 * Resolves the host of a gateway address.
	 */
	static InetSocketAddress resolve(InetSocketAddress gateway) {
		return gateway.isUnresolved() ? new InetSocketAddress(gateway.getHostString(), gateway.getPort()) : gateway;
	}

}
