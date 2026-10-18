/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

/**
 * Read access to one device on the bus: the few management services a comparison needs.
 *
 * <p>
 * An implementation reaches the device through a gateway ({@link CalimeroReader}); tests answer
 * from scripted data. Reading never changes the device.
 * </p>
 */
public interface DeviceReader extends AutoCloseable {

	/**
	 * Reads the device descriptor type 0 (the mask version), 2 bytes.
	 */
	byte[] readDescriptor() throws BusException;

	/**
	 * Reads elements of a property of an interface object.
	 *
	 * @param objectIndex
	 *        The index of the interface object.
	 * @param propertyId
	 *        The property.
	 * @param start
	 *        The first element, from 1 (0 reads the number of elements).
	 * @param count
	 *        The number of elements.
	 */
	byte[] readProperty(int objectIndex, int propertyId, int start, int count) throws BusException;

	/**
	 * Reads memory, at most {@link DownloadPlanner#MAX_CHUNK} bytes.
	 */
	byte[] readMemory(int address, int length) throws BusException;

	/**
	 * Ends the connection to the device (a following access connects again).
	 */
	void disconnect() throws BusException;

	/**
	 * Releases the connection to the gateway.
	 */
	@Override
	void close();

}
