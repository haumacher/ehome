/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.server;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tuwien.auto.calimero.IndividualAddress;

import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupDevice.Outgoing;

/**
 * A virtual KNX installation: devices with group communication ({@link GroupDevice}) on one virtual
 * subnet behind a KNXnet/IP interface ({@link KnxServer}), which tools (the group monitor, a
 * simulation of the rules) reach through tunnelling connections as they reach a real installation
 * through its gateway.
 *
 * <p>
 * The interface itself has the address {@link #SERVER_ADDRESS}; its tunnelling connections the
 * addresses below 15.15.255, so that the devices of a project keep their own addresses. A group
 * telegram of a tunnelling connection reaches every device and every other connection, one of a
 * device every other device and every connection (Calimero's virtual subnet and server gateway).
 * </p>
 */
public final class VirtualInstallation implements AutoCloseable {

	/** The individual address of the interface, 15.15.0. */
	public static final int SERVER_ADDRESS = 0xFF00;

	/** How long {@link #set(GroupDevice, int, byte[])} waits for the telegram to leave, in ms. */
	private static final long SEND_TIMEOUT = 10_000;

	private final Endpoint _endpoint;

	private final String _name;

	private final KnxServer _server;

	private final List<Integer> _tunnels;

	private final ExecutorService _sender;

	private final Map<GroupDevice, GroupStack> _stacks = new LinkedHashMap<>();

	private VirtualInstallation(Endpoint endpoint, String name, KnxServer server, List<Integer> tunnels) {
		_endpoint = endpoint;
		_name = name;
		_server = server;
		_tunnels = tunnels;
		_sender = Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "E-Home virtual installation " + endpoint);
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Starts an installation without devices.
	 *
	 * @param name
	 *        The name the interface announces (at most 30 characters).
	 * @param tunnels
	 *        How many tunnelling connections can be open at a time (1 to 8).
	 * @param workDir
	 *        The directory for the server's configuration.
	 * @throws com.top_logic.util.error.TopLogicException
	 *         If the interface cannot be started, with the reason.
	 */
	public static VirtualInstallation start(Endpoint endpoint, String name, int tunnels, File workDir) {
		if (tunnels < 1 || tunnels > 8) {
			throw new IllegalArgumentException("1 to 8 tunnelling connections, not " + tunnels + ".");
		}
		List<Integer> addresses = new ArrayList<>();
		for (int n = 0; n < tunnels; n++) {
			addresses.add(Integer.valueOf(SERVER_ADDRESS | (0xFF - n)));
		}
		KnxServer server = KnxServer.start(endpoint, name, SERVER_ADDRESS, addresses, workDir);
		return new VirtualInstallation(endpoint, name, server, List.copyOf(addresses));
	}

	/**
	 * Where the interface listens.
	 */
	public Endpoint endpoint() {
		return _endpoint;
	}

	/**
	 * The name the interface announces.
	 */
	public String name() {
		return _name;
	}

	/**
	 * Puts a device on the subnet.
	 *
	 * @throws IllegalArgumentException
	 *         If the subnet has a device of the address already, or it is an address of the
	 *         interface.
	 */
	public synchronized void attach(GroupDevice device) {
		int address = device.address();
		if (address == SERVER_ADDRESS || _tunnels.contains(Integer.valueOf(address))) {
			throw new IllegalArgumentException(
				"The address " + BusTelegram.individualAddress(address) + " of " + device + " belongs to the interface.");
		}
		for (GroupDevice other : _stacks.keySet()) {
			if (other.address() == address) {
				throw new IllegalArgumentException("Two devices at " + BusTelegram.individualAddress(address) + ": " + other
					+ " and " + device + ".");
			}
		}
		_stacks.put(device,
			new GroupStack(device, _server.subnet().createDeviceLink(new IndividualAddress(address)), _sender));
	}

	/**
	 * Takes a device off the subnet; nothing happens for a device that is not on it.
	 */
	public synchronized void detach(GroupDevice device) {
		GroupStack stack = _stacks.remove(device);
		if (stack != null) {
			stack.close();
		}
	}

	/**
	 * The devices on the subnet, in the order they were attached.
	 */
	public synchronized List<GroupDevice> devices() {
		return List.copyOf(_stacks.keySet());
	}

	/**
	 * The application of a device sets an object (see {@link GroupDevice#set(int, byte[])}); the
	 * telegram the object sends is on the subnet when the method returns.
	 *
	 * @return The telegram sent, <code>null</code> if the object sends none.
	 */
	public Outgoing set(GroupDevice device, int object, byte[] data) {
		GroupStack stack;
		synchronized (this) {
			stack = _stacks.get(device);
		}
		if (stack == null) {
			throw new IllegalArgumentException(device + " is not on the subnet of " + _name + ".");
		}
		Outgoing telegram = device.set(object, data);
		if (telegram != null) {
			await(stack.send(telegram));
		}
		return telegram;
	}

	private static void await(Future<?> sent) {
		if (sent == null) {
			return;
		}
		try {
			sent.get(SEND_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException ex) {
			throw new IllegalStateException("A telegram of the virtual installation was not sent.", ex);
		}
	}

	/**
	 * Whether the interface is up.
	 */
	public boolean isRunning() {
		return _server.isRunning();
	}

	/**
	 * Stops the interface and waits until its port is free.
	 */
	@Override
	public void close() {
		synchronized (this) {
			for (GroupStack stack : _stacks.values()) {
				stack.close();
			}
			_stacks.clear();
		}
		_sender.shutdownNow();
		_server.close();
	}

	@Override
	public String toString() {
		return _name + " at " + _endpoint;
	}

}
