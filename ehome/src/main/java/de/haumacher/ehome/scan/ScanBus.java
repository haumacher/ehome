/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.List;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.DeviceReader;

/**
 * Read access to the bus behind one gateway, as the scan of an installation needs it
 * ({@link BusScanner}): which devices answer, and a {@link DeviceReader} per device.
 *
 * <p>
 * Nothing is written: a scan connects to devices and reads their descriptor and properties. The
 * implementation reaching a real bus is {@link CalimeroScanBus}; tests answer from recorded data.
 * </p>
 */
public interface ScanBus extends AutoCloseable {

	/**
	 * Is told how far a scan of addresses got.
	 */
	interface Progress {

		/**
		 * The given number of the addresses to try is done.
		 */
		void progress(int done, int total);

		/**
		 * A device answered at the given address.
		 */
		void found(int address);
	}

	/**
	 * The gateway, for a person.
	 */
	String gateway();

	/**
	 * The devices of a line that answer a connection, in ascending order.
	 *
	 * @param area
	 *        The area (0-15).
	 * @param line
	 *        The line (0-15).
	 */
	List<Integer> scanLine(int area, int line, Progress progress) throws BusException;

	/**
	 * The couplers and routers (device number 0 of each area and line) that answer, in ascending
	 * order.
	 */
	List<Integer> scanRouters(Progress progress) throws BusException;

	/**
	 * The addresses of the devices in programming mode (their programming button was pressed).
	 */
	List<Integer> programmingMode() throws BusException;

	/**
	 * Read access to the device of the given address.
	 */
	DeviceReader device(int address) throws BusException;

	/**
	 * Read access to the properties of the gateway itself through KNXnet/IP local device management:
	 * a router or interface does not answer point-to-point reads through its own tunnel.
	 *
	 * @return The access, <code>null</code> if the gateway does not offer local device management.
	 */
	LocalProperties gatewayProperties() throws BusException;

	/**
	 * Reads properties of a device by the type of its interface objects (local device management
	 * addresses objects by type and instance).
	 */
	interface LocalProperties extends AutoCloseable {

		/**
		 * Reads elements of a property of the first interface object of the given type.
		 */
		byte[] readProperty(int objectType, int propertyId, int start, int count) throws BusException;

		@Override
		void close();
	}

	@Override
	void close();

}
