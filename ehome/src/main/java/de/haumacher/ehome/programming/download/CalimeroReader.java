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

import com.top_logic.basic.Logger;
import com.top_logic.basic.thread.CancelScope;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey4;

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
 * The chunk of the memory accesses ({@link #memoryChunk()}, haumacher/ehome #196) follows the maximum
 * APDU length of the path (the medium of the link) and of the device: on a path of more than a
 * standard frame, the device is asked once (property 56 of the device object, as ETS does at every
 * download); a device that does not answer it, or a device of BCU 1 (mask 001x, without properties),
 * keeps the standard frame. On a path of a standard frame the device is not asked. A longer memory
 * access failing without an answer of the device falls back to standard frames for the rest of the
 * reader's connection and is repeated in them ({@link #fallBack(BusException)}).
 * </p>
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

	/** The mask version the device answered, <code>-1</code> before. */
	private int _mask = -1;

	/** The chunk of the memory accesses, <code>0</code> before it is determined. */
	private int _memoryChunk;

	/**
	 * Whether a longer memory access failed without an answer of the device: the memory accesses go
	 * in standard frames from then on.
	 */
	private boolean _standardFrames;

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
		return open(path, device, TIMING);
	}

	/**
	 * Opens a link on the given path for reading the given device, with the given timing of the
	 * transport connection (a test's shorter waits).
	 *
	 * @see #open(BusPath, int)
	 */
	public static CalimeroReader open(BusPath path, int device, TransportClient.Timing timing) throws BusException {
		return new CalimeroReader(path, device, timing);
	}

	/**
	 * Creates a {@link CalimeroReader} and opens the link on the given path.
	 *
	 * @throws com.top_logic.util.error.TopLogicException
	 *         If the path is refused ({@link BusPath#open()}): this installation does not reach local
	 *         networks ({@link OperatorSettings#checkLocalNetwork()}, haumacher/ehome #150) for a
	 *         gateway, the controller refuses the bus session.
	 */
	protected CalimeroReader(BusPath path, int device, TransportClient.Timing timing) throws BusException {
		this(path.text(), path.open(), device, timing);
	}

	/**
	 * Creates a {@link CalimeroReader} on a link that is open already (a test's link in memory).
	 *
	 * @param path
	 *        The path the link stands for, for a person ({@link BusPath#text()}).
	 * @param link
	 *        The link to the bus, closed with this reader.
	 * @param device
	 *        The individual address of the device.
	 */
	protected CalimeroReader(String path, KNXNetworkLink link, int device) {
		this(path, link, device, TIMING);
	}

	/**
	 * Creates a {@link CalimeroReader} on a link that is open already, with the given timing of the
	 * transport connection.
	 *
	 * @see #CalimeroReader(String, KNXNetworkLink, int)
	 */
	protected CalimeroReader(String path, KNXNetworkLink link, int device, TransportClient.Timing timing) {
		_path = path;
		_device = new IndividualAddress(device);
		_link = link;
		_connection = new TransportClient(link, device, timing);
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
		byte[] descriptor = Arrays.copyOfRange(asdu, 1, asdu.length);
		if (descriptor.length == 2) {
			_mask = (descriptor[0] & 0xFF) << 8 | descriptor[1] & 0xFF;
		}
		return descriptor;
	}

	@Override
	public int memoryChunk() {
		if (_memoryChunk == 0) {
			_memoryChunk = determineChunk();
		}
		return _memoryChunk;
	}

	private int determineChunk() {
		int path = _link.getKNXMedium().maxApduLength();
		if (path <= MemoryChunk.STANDARD_APDU) {
			return MemoryChunk.STANDARD;
		}
		if (_standardFrames) {
			return MemoryChunk.STANDARD;
		}
		int device = MemoryChunk.STANDARD_APDU;
		if ((_mask & 0xFFF0) == 0x0010) {
			Logger.info("Memory accesses of " + device() + " through " + _path + ": the device of mask "
				+ String.format("%04X", _mask) + " has no properties, " + MemoryChunk.STANDARD + " bytes.",
				CalimeroReader.class);
			return MemoryChunk.STANDARD;
		}
		try {
			byte[] value = readProperty(0, MemoryChunk.PID_MAX_APDU_LENGTH, 1, 1);
			if (value.length > 0) {
				device = value.length == 1 ? value[0] & 0xFF : (value[0] & 0xFF) << 8 | value[1] & 0xFF;
			}
		} catch (BusException ex) {
			Logger.info("Memory accesses of " + device() + " through " + _path
				+ ": no maximum APDU length of the device (" + DownloadPlan.english(ex.getErrorKey())
				+ "), a standard frame.", CalimeroReader.class);
		}
		int result = MemoryChunk.of(path, device);
		Logger.info("Memory accesses of " + device() + " through " + _path + ": " + result
			+ " bytes (maximum APDU length of the device " + device + ", of the path " + path + ").",
			CalimeroReader.class);
		return result;
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
		if (length > MemoryChunk.STANDARD) {
			if (_standardFrames) {
				return readInStandardFrames(address, length);
			}
			if (longFrames()) {
				try {
					return readMemoryOnce(address, length);
				} catch (NoAnswer ex) {
					fallBack(ex);
					return readInStandardFrames(address, length);
				}
			}
		}
		return readMemoryOnce(address, length);
	}

	private byte[] readInStandardFrames(int address, int length) throws BusException {
		byte[] result = new byte[length];
		for (int position = 0; position < length; position += MemoryChunk.STANDARD) {
			int chunk = Math.min(MemoryChunk.STANDARD, length - position);
			System.arraycopy(readMemoryOnce(address + position, chunk), 0, result, position, chunk);
		}
		return result;
	}

	/**
	 * Whether the memory accesses take longer frames than a standard frame (determined, and not fallen
	 * back).
	 */
	protected boolean longFrames() {
		return _memoryChunk > MemoryChunk.STANDARD;
	}

	/**
	 * Whether the memory accesses fell back to standard frames.
	 *
	 * @see #fallBack(BusException)
	 */
	protected boolean standardFrames() {
		return _standardFrames;
	}

	/**
	 * Falls back to memory accesses in standard frames for the rest of this reader's connection,
	 * after a longer access failed without an answer of the device: the line between the path and the
	 * device may not carry the longer frames whatever the device and the path announce
	 * (haumacher/ehome #196). The connection, whose state is unknown after the failure, is ended (the
	 * next access connects again, {@link #reconnected()}); nothing of the failed access counts as
	 * done.
	 *
	 * @param ex
	 *        The failure of the longer access.
	 */
	protected void fallBack(BusException ex) throws BusException {
		Logger.warn("Memory accesses of " + device() + " through " + _path + ": an access of more than "
			+ MemoryChunk.STANDARD + " bytes failed without an answer of the device (" + ex.getMessage()
			+ "), standard frames of " + MemoryChunk.STANDARD + " bytes from now on.", CalimeroReader.class);
		_standardFrames = true;
		_memoryChunk = MemoryChunk.STANDARD;
		_connection.disconnect();
		reconnected();
	}

	/**
	 * Restores what the connection held before the fall back ended it; the next access connects
	 * again.
	 */
	protected void reconnected() throws BusException {
		// Nothing to restore for reading.
	}

	private byte[] readMemoryOnce(int address, int length) throws BusException {
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
		try {
			// A cancel of the job ends the wait for the device (TL #30196).
			return CancelScope.runInterruptibly(() -> _connection.request(apdu, answer));
		} catch (TransportException ex) {
			throw new NoAnswer(failure.apply(BusException.reason(ex)), ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	/**
	 * A failure of a service without a valid answer of the device: no acknowledgement, no answer, the
	 * connection ended, the frame not confirmed.
	 */
	protected static final class NoAnswer extends BusException {

		NoAnswer(ResKey message, Throwable cause) {
			super(message, cause);
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
	 * An interrupt of the device access from elsewhere than the job's cancel (which the
	 * <code>CancelScope</code> takes back on its own), as the failure of the access. The interrupt is
	 * not set again: the work after the access may read the database, which an interrupt closes
	 * (TL #30196).
	 */
	protected static BusException interrupted(InterruptedException ex) {
		return new BusException(I18NConstants.ACCESS_INTERRUPTED, ex);
	}

}
