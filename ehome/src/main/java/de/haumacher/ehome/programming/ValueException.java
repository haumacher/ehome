/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.programming.download.DownloadPlan;

/**
 * A value of a parameter that cannot be encoded into its bits or decoded from them, with the reason
 * for a person ({@link #getErrorKey()}, haumacher/ehome #161).
 *
 * <p>
 * {@link #getMessage()} is the English text of the reason, for the log; it is computed when asked
 * for, so that code without the resources of the application (a plain unit test of the encoding)
 * may throw and catch it.
 * </p>
 */
public class ValueException extends IllegalArgumentException {

	private final ResKey _errorKey;

	/**
	 * Creates a {@link ValueException}.
	 */
	public ValueException(ResKey reason) {
		this(reason, null);
	}

	/**
	 * Creates a {@link ValueException} with its cause.
	 */
	public ValueException(ResKey reason, Throwable cause) {
		super(null, cause);
		_errorKey = reason;
	}

	/**
	 * Why the value cannot be encoded or decoded, for a person.
	 */
	public ResKey getErrorKey() {
		return _errorKey;
	}

	@Override
	public String getMessage() {
		return DownloadPlan.english(_errorKey);
	}

	/**
	 * The reason of the given failure of an encoding or decoding, for a person: the reason of a
	 * {@link ValueException}, otherwise the text of the exception.
	 */
	public static ResKey reason(IllegalArgumentException ex) {
		return ex instanceof ValueException value ? value.getErrorKey() : ResKey.text(ex.getMessage());
	}

}
