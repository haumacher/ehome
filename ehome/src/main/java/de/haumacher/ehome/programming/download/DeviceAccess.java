/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

/**
 * Read and write access to one device on the bus: the management services a download needs.
 *
 * <p>
 * The only implementation reaching a real bus is {@link CalimeroWriter}, which refuses to open
 * unless writing is switched on for the installation; tests record the calls and answer from
 * scripted data.
 * </p>
 */
public interface DeviceAccess extends DeviceReader {

	/**
	 * Writes a load control into the load state control property of the interface object of a load
	 * state machine.
	 *
	 * @return The load state the device answers with, <code>null</code> if its answer holds none
	 *         (the state is then to be read).
	 */
	Integer writeLoadControl(int objectIndex, byte[] control) throws BusException;

	/**
	 * Writes elements of a property; the device's answer must repeat the value.
	 */
	void writeProperty(int objectIndex, int propertyId, int start, int count, byte[] data) throws BusException;

	/**
	 * Writes memory, at most {@link DownloadPlanner#MAX_CHUNK} bytes.
	 */
	void writeMemory(int address, byte[] data) throws BusException;

	/**
	 * Restarts the device; the connection to it ends.
	 */
	void restart() throws BusException;

	/**
	 * Writes the individual address into the one device in programming mode.
	 */
	void writeIndividualAddress(int address) throws BusException;

}
