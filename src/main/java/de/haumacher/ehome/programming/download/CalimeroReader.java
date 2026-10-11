/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;

/**
 * {@link DeviceReader} reaching a device through a KNXnet/IP gateway with Calimero: a tunnelling
 * link to the gateway and a connection-oriented management connection to the device.
 *
 * <p>
 * Reads only; writing is the business of {@link CalimeroWriter}, the one class sending anything
 * that changes a device.
 * </p>
 */
public class CalimeroReader implements DeviceReader {

	/** The time to wait for an answer of the device. */
	public static final Duration TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Creates the management client on the link.
	 */
	protected interface ClientFactory {

		/**
		 * The management client on the given link.
		 */
		ManagementClientImpl create(KNXNetworkLink link) throws KNXLinkClosedException;
	}

	private final InetSocketAddress _gateway;

	private final IndividualAddress _device;

	private final KNXNetworkLink _link;

	private final ManagementClientImpl _management;

	private Destination _destination;

	/**
	 * Opens a connection to the gateway for reading the given device.
	 *
	 * @param gateway
	 *        The gateway, see {@link Gateways#parse(String)}.
	 * @param device
	 *        The individual address of the device.
	 */
	public static CalimeroReader open(InetSocketAddress gateway, int device) throws BusException {
		return new CalimeroReader(gateway, device, ManagementClientImpl::new);
	}

	/**
	 * Creates a {@link CalimeroReader} and opens the link to the gateway.
	 */
	protected CalimeroReader(InetSocketAddress gateway, int device, ClientFactory factory) throws BusException {
		_gateway = gateway;
		_device = new IndividualAddress(device);
		KNXNetworkLink link = null;
		try {
			InetSocketAddress remote = Gateways.resolve(gateway);
			if (remote.isUnresolved()) {
				throw new IOException("unknown host " + remote.getHostString());
			}
			InetSocketAddress local = new InetSocketAddress(localAddressTowards(remote), 0);
			link = KNXNetworkLinkIP.newTunnelingLink(local, remote, false, new TPSettings());
			_management = factory.create(link);
			_management.responseTimeout(TIMEOUT);
		} catch (KNXException | IOException ex) {
			if (link != null) {
				link.close();
			}
			throw new BusException("No connection to the gateway " + Gateways.format(gateway) + ": " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			if (link != null) {
				link.close();
			}
			throw new BusException("Interrupted while connecting to " + Gateways.format(gateway) + ".", ex);
		}
		_link = link;
	}

	/**
	 * The link to the gateway.
	 */
	protected KNXNetworkLink link() {
		return _link;
	}

	/**
	 * The management client.
	 */
	protected ManagementClientImpl management() {
		return _management;
	}

	/**
	 * The connection to the device, opened on demand.
	 */
	protected Destination destination() {
		if (_destination == null || _destination.getState() == Destination.State.Destroyed) {
			_destination = _management.createDestination(_device, true);
		}
		return _destination;
	}

	@Override
	public byte[] readDescriptor() throws BusException {
		try {
			return _management.readDeviceDesc(destination(), 0);
		} catch (KNXException ex) {
			throw failure("Reading the device descriptor", ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	@Override
	public byte[] readProperty(int objectIndex, int propertyId, int start, int count) throws BusException {
		try {
			return _management.readProperty(destination(), objectIndex, propertyId, start, count);
		} catch (KNXException ex) {
			throw failure("Reading object " + objectIndex + " property " + propertyId, ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	@Override
	public byte[] readMemory(int address, int length) throws BusException {
		try {
			return _management.readMemory(destination(), address, length);
		} catch (KNXException ex) {
			throw failure(String.format("Reading memory %04X (%d bytes)", address, length), ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	@Override
	public void disconnect() {
		if (_destination != null) {
			_destination.destroy();
			_destination = null;
		}
	}

	@Override
	public void close() {
		disconnect();
		_management.detach();
		_link.close();
	}

	/**
	 * A failure of the device access, naming the device and the gateway.
	 */
	protected BusException failure(String what, KNXException ex) {
		return new BusException(what + " of " + _device + " through " + Gateways.format(_gateway) + " failed: "
			+ ex.getMessage() + " (" + ex.getClass().getSimpleName() + ")", ex);
	}

	/**
	 * An interrupt of the device access (the job was cancelled).
	 */
	protected static BusException interrupted(InterruptedException ex) {
		Thread.currentThread().interrupt();
		return new BusException("Interrupted.", ex);
	}

	private static InetAddress localAddressTowards(InetSocketAddress remote) throws IOException {
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(remote.getAddress(), remote.getPort());
			return socket.getLocalAddress();
		}
	}

}
