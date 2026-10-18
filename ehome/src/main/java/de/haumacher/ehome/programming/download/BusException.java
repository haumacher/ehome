/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

/**
 * A failure of an access to a device on the bus: no answer, a negative answer, or a lost
 * connection, with what the device answered.
 */
public class BusException extends Exception {

	/**
	 * Creates a {@link BusException}.
	 *
	 * @param message
	 *        What went wrong, including the device's answer.
	 */
	public BusException(String message) {
		super(message);
	}

	/**
	 * Creates a {@link BusException} caused by a failure of the KNX library.
	 */
	public BusException(String message, Throwable cause) {
		super(message, cause);
	}

}
