/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import com.top_logic.basic.util.ResKey;

/**
 * A failure of an access to a device on the bus: no answer, a negative answer, or a lost
 * connection, with what the device answered.
 *
 * <p>
 * The message is a {@link ResKey} ({@link #getErrorKey()}), shown to a person in the reader's
 * language; {@link #getMessage()} is its English text, for the log. What the KNX library reports
 * (the message of its exception) is a value filled into the message, not translated.
 * </p>
 */
public class BusException extends Exception {

	private final ResKey _errorKey;

	/**
	 * Creates a {@link BusException}.
	 *
	 * @param message
	 *        What went wrong, including the device's answer.
	 */
	public BusException(ResKey message) {
		this(message, null);
	}

	/**
	 * Creates a {@link BusException} caused by a failure of the KNX library.
	 */
	public BusException(ResKey message, Throwable cause) {
		super(DownloadPlan.english(message), cause);
		_errorKey = message;
	}

	/**
	 * Creates a {@link BusException} with a text that is not translated: what a simulated device or
	 * a link reports in the terms of the KNX library.
	 */
	public BusException(String message) {
		this(message, null);
	}

	/**
	 * Creates a {@link BusException} with a text that is not translated, caused by a failure of the
	 * KNX library.
	 */
	public BusException(String message, Throwable cause) {
		super(message, cause);
		_errorKey = ResKey.text(message);
	}

	/**
	 * What went wrong, for a person in the reader's language.
	 */
	public ResKey getErrorKey() {
		return _errorKey;
	}

	/**
	 * Why the given exception happened, as a value of a message: the message of a
	 * {@link BusException}, otherwise the text of the KNX library.
	 */
	public static Object reason(Throwable ex) {
		return ex instanceof BusException bus ? bus.getErrorKey() : ex.getMessage();
	}

}
