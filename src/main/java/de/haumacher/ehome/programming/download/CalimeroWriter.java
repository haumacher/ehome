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

import de.haumacher.ehome.knx.transport.Tpdu;
import de.haumacher.ehome.knx.transport.TransportClient;
import de.haumacher.ehome.knx.transport.TransportClient.TransportException;

/**
 * The one class that changes a device on the bus: {@link DeviceAccess} through a KNXnet/IP gateway,
 * on the transport connection of {@link CalimeroReader}.
 *
 * <p>
 * Writing is switched off unless the application runs with the system property
 * {@value #WRITE_ENABLED}<code>=true</code>: {@link #open(InetSocketAddress, int)} refuses
 * otherwise. Every write to the bus of the application goes through this class (checked by
 * <code>TestBusWritePath</code>), so that switch is the one place deciding whether E-Home may
 * program a device.
 * </p>
 */
public final class CalimeroWriter extends CalimeroReader implements DeviceAccess {

	/**
	 * The system property switching on writes to the bus.
	 */
	public static final String WRITE_ENABLED = "ehome.programming.writeEnabled";

	/**
	 * Whether writing to the bus is switched on for this installation.
	 */
	public static boolean writeEnabled() {
		return Boolean.getBoolean(WRITE_ENABLED);
	}

	/**
	 * Opens a connection to the gateway for writing into the given device.
	 *
	 * @throws IllegalStateException
	 *         If writing is switched off ({@link #writeEnabled()}).
	 */
	public static CalimeroWriter open(InetSocketAddress gateway, int device) throws BusException {
		if (!writeEnabled()) {
			throw new IllegalStateException("Writing to the bus is switched off (system property " + WRITE_ENABLED + ").");
		}
		return new CalimeroWriter(gateway, device);
	}

	private CalimeroWriter(InetSocketAddress gateway, int device) throws BusException {
		super(gateway, device);
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
		String what = "The load control " + LoadControls.describe(control) + " of object " + objectIndex;
		byte[] answer = request(what, propertyService(Tpdu.PROPERTY_WRITE, objectIndex, LoadControls.PID_LOAD_STATE_CONTROL,
			1, 1, control), propertyAnswer(objectIndex, LoadControls.PID_LOAD_STATE_CONTROL, 1));
		byte[] asdu = Tpdu.asdu(answer);
		if ((asdu[2] & 0xF0) == 0) {
			throw failure(what, new BusException("the device refused the load control"));
		}
		// A load state of one byte, or something else (e.g. the control repeated): then the state is
		// to be read.
		return asdu.length == 5 ? Integer.valueOf(asdu[4] & 0xFF) : null;
	}

	@Override
	public void writeProperty(int objectIndex, int propertyId, int start, int count, byte[] data) throws BusException {
		String what = "Writing object " + objectIndex + " property " + propertyId;
		byte[] answer = request(what, propertyService(Tpdu.PROPERTY_WRITE, objectIndex, propertyId, start, count, data),
			propertyAnswer(objectIndex, propertyId, start));
		byte[] value = propertyValue(what, answer);
		if (!Arrays.equals(value, data)) {
			throw failure(what, new BusException("the device answered with " + Operation.hex(value) + ", written was "
				+ Operation.hex(data)));
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
		request(String.format("Writing memory %04X (%d bytes)", address, data.length),
			DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.MEMORY_WRITE, asdu), null);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * A basic restart has no answer, and the device may or may not end the connection (KNX allows
	 * both). As soon as the device has acknowledged the restart, E-Home ends the connection itself
	 * (haumacher/ehome #30); a device that disconnects first ends it as well.
	 * </p>
	 */
	@Override
	public void restart() throws BusException {
		TransportClient.End end;
		try {
			end = connection().requestAndEnd(DataUnitBuilder.createLengthOptimizedAPDU(Tpdu.RESTART));
		} catch (TransportException ex) {
			throw failure("The restart", ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
		Logger.info("Restart of " + connection().device() + ": the connection was ended by the " + end + ".",
			CalimeroWriter.class);
	}

	@Override
	public void writeIndividualAddress(int address) throws BusException {
		ManagementProceduresImpl procedures = null;
		try {
			procedures = new ManagementProceduresImpl(link());
			if (!procedures.writeAddress(new IndividualAddress(address))) {
				throw new BusException("A device with the address " + new IndividualAddress(address)
					+ " exists, but is not in programming mode.");
			}
		} catch (KNXException ex) {
			throw failure("Writing the individual address " + new IndividualAddress(address), ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		} finally {
			if (procedures != null) {
				procedures.detach();
			}
		}
	}

}
