/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.net.InetSocketAddress;
import java.util.Arrays;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.mgmt.ManagementProceduresImpl;

import com.top_logic.basic.Logger;
import com.top_logic.basic.util.ResKey;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.transport.Tpdu;
import de.haumacher.ehome.knx.transport.TransportClient;
import de.haumacher.ehome.knx.transport.TransportClient.TransportException;
import de.haumacher.ehome.settings.OperatorSettings;

/**
 * The one class that changes a device on the bus: {@link DeviceAccess} on the {@link BusPath} of its
 * project (a KNXnet/IP gateway, or the bus session of the project's controller, haumacher/ehome #148),
 * on the transport connection of {@link CalimeroReader}.
 *
 * <p>
 * Writing is switched off unless the operator switches it on (the configuration variable
 * <code>tl_ehome_programming_write_enabled</code>, {@link OperatorSettings.Config#getProgrammingWriteEnabled()},
 * haumacher/ehome #149): {@link #open(BusPath, int)} refuses otherwise, whatever the path. Every write to
 * the bus of the application goes through this class (checked by <code>TestBusWritePath</code>), so that
 * {@link #writeEnabled()} is the one place deciding whether E-Home may program a device.
 * </p>
 */
public final class CalimeroWriter extends CalimeroReader implements DeviceAccess {

	/**
	 * Whether writing to the bus is switched on for this installation.
	 */
	public static boolean writeEnabled() {
		return OperatorSettings.programmingWriteEnabled();
	}

	/**
	 * Opens a connection to the gateway for writing into the given device.
	 *
	 * @see #open(BusPath, int)
	 */
	public static CalimeroWriter open(InetSocketAddress gateway, int device) throws BusException {
		return open(BusPath.gateway(gateway), device);
	}

	/**
	 * Opens a link on the given path for writing into the given device.
	 *
	 * @throws TopLogicException
	 *         If writing is switched off ({@link #writeEnabled()}; checked before anything is opened),
	 *         or the path is refused: this installation does not reach local networks
	 *         ({@link OperatorSettings#checkLocalNetwork()}) for a gateway, the controller refuses the
	 *         bus session.
	 */
	public static CalimeroWriter open(BusPath path, int device) throws BusException {
		if (!writeEnabled()) {
			throw new TopLogicException(I18NConstants.ERROR_WRITE_DISABLED);
		}
		return new CalimeroWriter(path, device);
	}

	private CalimeroWriter(BusPath path, int device) throws BusException {
		super(path, device);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The answer to a load control (10 bytes) is the load state (1 byte), not the value written.
	 * </p>
	 */
	@Override
	public Integer writeLoadControl(int objectIndex, byte[] control) throws BusException {
		ResKey what = LoadControls.describe(control);
		byte[] answer = request(
			reason -> I18NConstants.ACCESS_LOAD_CONTROL_FAILED__CONTROL_OBJECT_DEVICE_PATH_REASON.fill(what, objectIndex,
				device(), path(), reason),
			propertyService(Tpdu.PROPERTY_WRITE, objectIndex, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1, control),
			propertyAnswer(objectIndex, LoadControls.PID_LOAD_STATE_CONTROL, 1));
		byte[] asdu = Tpdu.asdu(answer);
		if ((asdu[2] & 0xF0) == 0) {
			throw new BusException(I18NConstants.ACCESS_LOAD_CONTROL_REFUSED__CONTROL_OBJECT_DEVICE_PATH.fill(what,
				objectIndex, device(), path()));
		}
		// A load state of one byte, or something else (e.g. the control repeated): then the state is
		// to be read.
		return asdu.length == 5 ? Integer.valueOf(asdu[4] & 0xFF) : null;
	}

	@Override
	public void writeProperty(int objectIndex, int propertyId, int start, int count, byte[] data) throws BusException {
		byte[] answer = request(
			reason -> I18NConstants.ACCESS_WRITE_PROPERTY_FAILED__OBJECT_PROPERTY_DEVICE_PATH_REASON.fill(objectIndex,
				propertyId, device(), path(), reason),
			propertyService(Tpdu.PROPERTY_WRITE, objectIndex, propertyId, start, count, data),
			propertyAnswer(objectIndex, propertyId, start));
		byte[] value = propertyValue(I18NConstants.ACCESS_WRITE_PROPERTY_REFUSED__OBJECT_PROPERTY_DEVICE_PATH, answer);
		if (!Arrays.equals(value, data)) {
			throw new BusException(I18NConstants.ACCESS_WRITE_PROPERTY_ANSWER__OBJECT_PROPERTY_DEVICE_PATH_VALUE_WRITTEN
				.fill(objectIndex, propertyId, device(), path(), Operation.hex(value), Operation.hex(data)));
		}
	}

	@Override
	public void writeMemory(int address, byte[] data) throws BusException {
		byte[] asdu = new byte[3 + data.length];
		asdu[0] = (byte) data.length;
		asdu[1] = (byte) (address >>> 8);
		asdu[2] = (byte) address;
		System.arraycopy(data, 0, asdu, 3, data.length);
		// No answer awaited: a device in verify mode answers with the memory written, which the
		// transport acknowledges and ignores (the plan reads it back itself).
		String hexAddress = String.format("%04X", address);
		request(
			reason -> I18NConstants.ACCESS_WRITE_MEMORY_FAILED__ADDRESS_LENGTH_DEVICE_PATH_REASON.fill(hexAddress,
				data.length, device(), path(), reason),
			DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.MEMORY_WRITE, asdu), null);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * A basic restart has no answer, and the device may or may not end the connection (KNX allows
	 * both). As soon as the device has acknowledged the restart, E-Home ends the connection itself
	 * (haumacher/ehome #30); a device that disconnects first ends it as well. A device may restart
	 * without acknowledging (the MDT actuator AKK-0416.03 of the test line does): after the
	 * repetitions, E-Home ends the connection, as Calimero did.
	 * </p>
	 */
	@Override
	public void restart() throws BusException {
		TransportClient.End end;
		try {
			end = connection().requestAndEnd(DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.RESTART));
		} catch (TransportException ex) {
			throw failure(reason -> I18NConstants.ACCESS_RESTART_FAILED__DEVICE_PATH_REASON.fill(device(), path(), reason),
				ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
		Logger.info("Restart of " + connection().device() + ": " + switch (end) {
			case CLIENT -> "acknowledged, E-Home ended the connection.";
			case DEVICE -> "the device ended the connection.";
			case UNACKNOWLEDGED -> "none of the repetitions acknowledged, E-Home ended the connection.";
		}, CalimeroWriter.class);
	}

	@Override
	public void writeIndividualAddress(int address) throws BusException {
		ManagementProceduresImpl procedures = null;
		try {
			procedures = new ManagementProceduresImpl(link());
			if (!procedures.writeAddress(new IndividualAddress(address))) {
				throw new BusException(
					I18NConstants.ACCESS_ADDRESS_NOT_IN_PROGRAMMING_MODE__ADDRESS.fill(new IndividualAddress(address).toString()));
			}
		} catch (KNXException ex) {
			String target = new IndividualAddress(address).toString();
			throw failure(reason -> I18NConstants.ACCESS_WRITE_ADDRESS_FAILED__ADDRESS_PATH_REASON.fill(target, path(), reason),
				ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		} finally {
			if (procedures != null) {
				procedures.detach();
			}
		}
	}

}
