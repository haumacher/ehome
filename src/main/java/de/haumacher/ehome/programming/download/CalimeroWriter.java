/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.net.InetSocketAddress;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXRemoteException;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;
import tuwien.auto.calimero.mgmt.ManagementProceduresImpl;

/**
 * The one class that changes a device on the bus: {@link DeviceAccess} through a KNXnet/IP gateway
 * with Calimero.
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

	/** KNX application service A_PropertyValue_Write. */
	private static final int PROPERTY_WRITE = 0x03D7;

	/** KNX application service A_PropertyValue_Response. */
	private static final int PROPERTY_RESPONSE = 0x03D6;

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
		super(gateway, device, LoadControlClient::new);
	}

	@Override
	public Integer writeLoadControl(int objectIndex, byte[] control) throws BusException {
		try {
			return ((LoadControlClient) management()).writeLoadControl(destination(), objectIndex, control);
		} catch (KNXException ex) {
			throw failure("The load control " + LoadControls.describe(control) + " of object " + objectIndex, ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	@Override
	public void writeProperty(int objectIndex, int propertyId, int start, int count, byte[] data) throws BusException {
		try {
			management().writeProperty(destination(), objectIndex, propertyId, start, count, data);
		} catch (KNXException ex) {
			throw failure("Writing object " + objectIndex + " property " + propertyId, ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	@Override
	public void writeMemory(int address, byte[] data) throws BusException {
		try {
			management().writeMemory(destination(), address, data);
		} catch (KNXException ex) {
			throw failure(String.format("Writing memory %04X (%d bytes)", address, data.length), ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
	}

	@Override
	public void restart() throws BusException {
		try {
			management().restart(destination());
		} catch (KNXException ex) {
			throw failure("The restart", ex);
		} catch (InterruptedException ex) {
			throw interrupted(ex);
		}
		// The device ends the connection with the restart; the next access opens a new one.
		disconnect();
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

	/**
	 * Management client writing load controls.
	 *
	 * <p>
	 * {@link ManagementClientImpl#writeProperty(Destination, int, int, int, int, byte[])} expects the
	 * answer to a property write to have the length of the value written; the answer to a load
	 * control (10 bytes) is the load state (1 byte).
	 * </p>
	 */
	private static final class LoadControlClient extends ManagementClientImpl {

		LoadControlClient(KNXNetworkLink link) throws KNXLinkClosedException {
			super(link);
		}

		Integer writeLoadControl(Destination destination, int objectIndex, byte[] control)
				throws KNXException, InterruptedException {
			byte[] asdu = new byte[4 + control.length];
			asdu[0] = (byte) objectIndex;
			asdu[1] = (byte) LoadControls.PID_LOAD_STATE_CONTROL;
			// One element from element 1.
			asdu[2] = (byte) (1 << 4);
			asdu[3] = 1;
			System.arraycopy(control, 0, asdu, 4, control.length);
			byte[] apdu = sendWait(destination, getPriority(), DataUnitBuilder.createAPDU(PROPERTY_WRITE, asdu),
				PROPERTY_RESPONSE, 4, 4 + LoadControls.LENGTH, responseTimeout());
			int elements = (apdu[4] & 0xFF) >> 4;
			if (elements == 0) {
				throw new KNXRemoteException("the device refused the load control");
			}
			// A load state of one byte, or something else (e.g. the control repeated): then the
			// state is to be read.
			return apdu.length == 7 ? Integer.valueOf(apdu[6] & 0xFF) : null;
		}
	}

}
