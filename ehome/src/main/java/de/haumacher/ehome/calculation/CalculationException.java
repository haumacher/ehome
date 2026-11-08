/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

/**
 * A script of a parameter calculation could not be evaluated: a syntax error, a construct E-Home
 * does not support, an error at run time (an unbound name, a division by zero, an exception the
 * script throws) or the limit of its run time.
 */
public class CalculationException extends Exception {

	/**
	 * Creates a {@link CalculationException}.
	 *
	 * @param message
	 *        What went wrong, as a person reads it (in the terms of the script).
	 */
	public CalculationException(String message) {
		super(message, null, false, false);
	}

}
