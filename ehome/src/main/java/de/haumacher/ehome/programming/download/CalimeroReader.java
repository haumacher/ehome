/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Predicate;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.medium.TPSettings;

import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.knx.transport.Tpdu;
import de.haumacher.ehome.knx.transport.TransportClient;
import de.haumacher.ehome.knx.transport.TransportClient.TransportException;

/**
 * {@link DeviceReader} reaching a device through a KNXnet/IP gateway: Calimero's tunnelling link to
 * the gateway and a connection-oriented transport connection to the device of E-Home's own
 * ({@link TransportClient}, haumacher/ehome #31).
 *
 * <p>
 * Reads only; writing is the business of {@link CalimeroWriter}, the one class sending anything
 * that changes a device.
 * </p>
 */
public class CalimeroReader implements DeviceReader {

	/** The time to wait for an answer of the device. */
	public static final Duration TIMEOUT = Duration.ofSeconds(5);

	/** The timing of the transport connection: the KNX values, {@link #TIMEOUT} for an answer. */
	public static final TransportClient.Timing TIMING = new TransportClient.Timing(Duration.ofSeconds(3), 3, TIMEOUT,
		Duration.ofSeconds(6));

	private final InetSocketAddress _gateway;

	private final IndividualAddress _device;

	private final KNXNetworkLink _link;

	private final TransportClient _connection;

	/**
	 * Opens a connection to the gateway for reading the given device.
	 *
	 * @param gateway
	 *        The gateway, see {@link Gateways#parse(String)}.
	 * @param device
	 *        The individual address of the device.
	 */
	public static CalimeroReader open(InetSocketAddress gateway, int device) throws BusException {
		return new CalimeroReader(gateway, device);
	}

	/**
	 * Creates a {@link CalimeroReader} and opens the link to the gateway.
	 */
	protected CalimeroReader(InetSocketAddress gateway, int device) throws BusException {
		_gateway = gateway;
		_device = new IndividualAddress(device);
		KNXNetworkLink link = null;
		try {
			InetSocketAddress remote = Gateways.resolve(gateway);
			if (remote.isUnresolved()) {
				throw new IOException("unknown host " + remote.getHostString());
			}
			InetSocketAddress local = new InetSocketAddress(localAddressTowards(remote), 0);
			try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
				link = KNXNetworkLinkIP.newTunnelingLink(local, remote, false, new TPSettings());
			}
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
		_connection = new TransportClient(link, device, TIMING);
	}

	/**
	 * The link to the gateway.
	 */
	protected KNXNetworkLink link() {
		return _link;
	}

	/**
	 * The transport connection to the device.
	 */
	protected TransportClient connection() {
		return _connection;
	}

	@Override
	public byte[] readDescriptor() throws BusException {
		byte[] answer = request("Reading the device descriptor", DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.DESCRIPTOR_READ, (byte) 0),
			r -> (Tpdu.service(r) & 0x3C0) == Tpdu.DESCRIPTOR_RESPONSE && (Tpdu.asdu(r)[0] & 0x3F) == 0);
		byte[] asdu = Tpdu.asdu(answer);
		return Arrays.copyOfRange(asdu, 1, asdu.length);
	}

	@Override
	public byte[] readProperty(int objectIndex, int propertyId, int start, int count) throws BusException {
		String what = "Reading object " + objectIndex + " property " + propertyId;
		byte[] answer = request(what, propertyService(Tpdu.PROPERTY_READ, objectIndex, propertyId, start, count, new byte[0]),
			propertyAnswer(objectIndex, propertyId, start));
		return propertyValue(what, answer);
	}

	@Override
	public byte[] readMemory(int address, int length) throws BusException {
		String what = String.format("Reading memory %04X (%d bytes)", address, length);
		byte[] request = DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.MEMORY_READ, (byte) length, (byte) (address >>> 8),
			(byte) address);
		byte[] answer = request(what, request, memoryAnswer(address));
		byte[] asdu = Tpdu.asdu(answer);
		int count = asdu[0] & 0x3F;
		byte[] data = Arrays.copyOfRange(asdu, 3, asdu.length);
		if (count == 0 || data.length != length) {
			throw new BusException(what + " of " + _device + " through " + Gateways.format(_gateway)
				+ " failed: the device answered with " + data.length + " bytes.");
		}
		return data;
	}

	@Override
	public void disconnect() {
		_connection.disconnect();
	}

	@Override
	public void close() {
		_connection.close();
		_link.close();
	}

	/**
	 * Sends a service and waits for its answer.
	 *
	 * @param what
	 *        The service for a person (the message of a failure).
	 * @param answer
	 *        Tells the answer, <code>null</code> for a service without answer.
	 */
	protected byte[] request(String what, byte[] apdu, Predicate<byte[]> answer) throws BusException {
		try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
			return _connection.request(apdu, answer);
		} catch (TransportException ex) {
			throw failure(what, ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	/**
	 * The application unit of a property service: object, property, number of elements, first
	 * element, data.
	 */
	protected static byte[] propertyService(int service, int objectIndex, int propertyId, int start, int count,
			byte[] data) {
		byte[] asdu = new byte[4 + data.length];
		asdu[0] = (byte) objectIndex;
		asdu[1] = (byte) propertyId;
		asdu[2] = (byte) (count << 4 | (start >>> 8) & 0x0F);
		asdu[3] = (byte) start;
		System.arraycopy(data, 0, asdu, 4, data.length);
		return DataUnitBuilder.createAPDU(service, asdu);
	}

	/**
	 * Tells the answer to a property service of the given object, property and first element.
	 */
	protected static Predicate<byte[]> propertyAnswer(int objectIndex, int propertyId, int start) {
		return r -> {
			if (Tpdu.service(r) != Tpdu.PROPERTY_RESPONSE) {
				return false;
			}
			byte[] asdu = Tpdu.asdu(r);
			return asdu.length >= 4 && (asdu[0] & 0xFF) == objectIndex && (asdu[1] & 0xFF) == propertyId
				&& ((asdu[2] & 0x0F) << 8 | asdu[3] & 0xFF) == start;
		};
	}

	/**
	 * The value of a property answer.
	 *
	 * @throws BusException
	 *         If the answer has no elements (the device refused or does not have the property).
	 */
	protected byte[] propertyValue(String what, byte[] answer) throws BusException {
		byte[] asdu = Tpdu.asdu(answer);
		if ((asdu[2] & 0xF0) == 0) {
			throw new BusException(what + " of " + _device + " through " + Gateways.format(_gateway)
				+ " failed: property access OI " + (asdu[0] & 0xFF) + " PID " + (asdu[1] & 0xFF)
				+ " failed/forbidden (KNXRemoteException)");
		}
		return Arrays.copyOfRange(asdu, 4, asdu.length);
	}

	/**
	 * Tells the answer to a memory service at the given address.
	 */
	protected static Predicate<byte[]> memoryAnswer(int address) {
		return r -> {
			if (Tpdu.service(r) != Tpdu.MEMORY_RESPONSE) {
				return false;
			}
			byte[] asdu = Tpdu.asdu(r);
			return asdu.length >= 3 && ((asdu[1] & 0xFF) << 8 | asdu[2] & 0xFF) == address;
		};
	}

	/**
	 * A failure of the device access, naming the device and the gateway.
	 */
	protected BusException failure(String what, Exception ex) {
		return new BusException(what + " of " + _device + " through " + Gateways.format(_gateway) + " failed: "
			+ ex.getMessage(), ex);
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
