/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Predicate;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.link.KNXNetworkLink;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey4;

import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.knx.transport.Tpdu;
import de.haumacher.ehome.knx.transport.TransportClient;
import de.haumacher.ehome.knx.transport.TransportClient.TransportException;
import de.haumacher.ehome.settings.OperatorSettings;

/**
 * {@link DeviceReader} reaching a device on the {@link BusPath} of its project: Calimero's
 * tunnelling link to the gateway, or the bus session of the project's controller
 * (<code>BusSessionLink</code>, haumacher/ehome #148), and a connection-oriented transport connection
 * to the device of E-Home's own ({@link TransportClient}, haumacher/ehome #31).
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

	private final String _path;

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
		return open(BusPath.gateway(gateway), device);
	}

	/**
	 * Opens a link on the given path for reading the given device.
	 *
	 * @param device
	 *        The individual address of the device.
	 */
	public static CalimeroReader open(BusPath path, int device) throws BusException {
		return new CalimeroReader(path, device);
	}

	/**
	 * Creates a {@link CalimeroReader} and opens the link on the given path.
	 *
	 * @throws com.top_logic.util.error.TopLogicException
	 *         If the path is refused ({@link BusPath#open()}): this installation does not reach local
	 *         networks ({@link OperatorSettings#checkLocalNetwork()}, haumacher/ehome #150) for a
	 *         gateway, the controller refuses the bus session.
	 */
	protected CalimeroReader(BusPath path, int device) throws BusException {
		_path = path.text();
		_device = new IndividualAddress(device);
		KNXNetworkLink link = path.open();
		_link = link;
		_connection = new TransportClient(link, device, TIMING);
	}

	/**
	 * The path to the bus, for a person ({@link BusPath#text()}).
	 */
	protected String path() {
		return _path;
	}

	/**
	 * The link to the bus.
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
		byte[] answer = request(reason -> I18NConstants.ACCESS_DESCRIPTOR_FAILED__DEVICE_PATH_REASON.fill(device(), _path, reason),
			DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.DESCRIPTOR_READ, (byte) 0),
			r -> (Tpdu.service(r) & 0x3C0) == Tpdu.DESCRIPTOR_RESPONSE && (Tpdu.asdu(r)[0] & 0x3F) == 0);
		byte[] asdu = Tpdu.asdu(answer);
		return Arrays.copyOfRange(asdu, 1, asdu.length);
	}

	@Override
	public byte[] readProperty(int objectIndex, int propertyId, int start, int count) throws BusException {
		byte[] answer = request(
			reason -> I18NConstants.ACCESS_READ_PROPERTY_FAILED__OBJECT_PROPERTY_DEVICE_PATH_REASON.fill(objectIndex, propertyId,
				device(), _path, reason),
			propertyService(Tpdu.PROPERTY_READ, objectIndex, propertyId, start, count, new byte[0]),
			propertyAnswer(objectIndex, propertyId, start));
		return propertyValue(I18NConstants.ACCESS_READ_PROPERTY_REFUSED__OBJECT_PROPERTY_DEVICE_PATH, answer);
	}

	@Override
	public byte[] readMemory(int address, int length) throws BusException {
		String hexAddress = String.format("%04X", address);
		byte[] request = DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.MEMORY_READ, (byte) length, (byte) (address >>> 8),
			(byte) address);
		byte[] answer = request(
			reason -> I18NConstants.ACCESS_READ_MEMORY_FAILED__ADDRESS_LENGTH_DEVICE_PATH_REASON.fill(hexAddress, length,
				device(), _path, reason),
			request, memoryAnswer(address));
		byte[] asdu = Tpdu.asdu(answer);
		int count = asdu[0] & 0x3F;
		byte[] data = Arrays.copyOfRange(asdu, 3, asdu.length);
		if (count == 0 || data.length != length) {
			throw new BusException(I18NConstants.ACCESS_READ_MEMORY_SHORT__ADDRESS_LENGTH_DEVICE_PATH_COUNT.fill(hexAddress,
				length, device(), _path, data.length));
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
	 * @param failure
	 *        The message of a failure of the service, for a person, given the reason the transport
	 *        reports.
	 * @param answer
	 *        Tells the answer, <code>null</code> for a service without answer.
	 */
	protected byte[] request(Function<Object, ResKey> failure, byte[] apdu, Predicate<byte[]> answer)
			throws BusException {
		try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
			return _connection.request(apdu, answer);
		} catch (TransportException ex) {
			throw failure(failure, ex);
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
	protected byte[] propertyValue(ResKey4 refused, byte[] answer) throws BusException {
		byte[] asdu = Tpdu.asdu(answer);
		if ((asdu[2] & 0xF0) == 0) {
			throw new BusException(refused.fill(asdu[0] & 0xFF, asdu[1] & 0xFF, device(), _path));
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
	 * A failure of the device access.
	 *
	 * @param failure
	 *        The message, naming the device and the path to the bus, given the reason.
	 * @param ex
	 *        The failure: its reason is the message of a {@link BusException}, otherwise the text of
	 *        the KNX library.
	 */
	protected static BusException failure(Function<Object, ResKey> failure, Exception ex) {
		return new BusException(failure.apply(BusException.reason(ex)), ex);
	}

	/**
	 * The individual address of the device, as text (<code>1.3.5</code>).
	 */
	protected String device() {
		return _device.toString();
	}

	/**
	 * An interrupt of the device access (the job was cancelled).
	 */
	protected static BusException interrupted(InterruptedException ex) {
		Thread.currentThread().interrupt();
		return new BusException(I18NConstants.ACCESS_INTERRUPTED, ex);
	}

}
