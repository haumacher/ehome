/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

import com.top_logic.basic.util.ResKey;

/**
 * A script of a parameter calculation could not be evaluated: a syntax error, a construct E-Home
 * does not support, an error at run time (an unbound name, a division by zero, an exception the
 * script throws) or the limit of its run time.
 */
public class CalculationException extends Exception {

	private final ResKey _reason;

	/**
	 * Creates a {@link CalculationException}.
	 *
	 * @param reason
	 *        What went wrong, as a sentence a person reads in the language of the user (in the terms of
	 *        the script).
	 */
	public CalculationException(ResKey reason) {
		super(reason.toString(), null, false, false);
		_reason = reason;
	}

	/**
	 * What went wrong, as a sentence a person reads in the language of the user.
	 */
	public ResKey getReason() {
		return _reason;
	}

}
