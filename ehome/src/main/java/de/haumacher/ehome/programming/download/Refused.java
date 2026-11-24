/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import com.top_logic.basic.util.ResKey;

/**
 * The device answered an access negatively (a property it does not give, "failed/forbidden"), as
 * opposed to no answer or a lost connection.
 */
public class Refused extends BusException {

	/**
	 * Creates a {@link Refused}.
	 *
	 * @param message
	 *        What the device refused.
	 */
	public Refused(ResKey message) {
		super(message);
	}

}
