/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import com.top_logic.basic.config.annotation.Label;

/**
 * What a scan of the installation searches for: exactly one of these, then the devices found are
 * read (haumacher/ehome #182).
 *
 * <p>
 * The label of a constant is its {@link Label}, its tooltip its javadoc.
 * </p>
 */
public enum ScanTarget {

	/**
	 * Asks the bus which devices have their programming mode switched on (programming button
	 * pressed, LED lit) and reads those; the bus answers within seconds.
	 */
	@Label("Devices in programming mode")
	PROGRAMMING_MODE,

	/**
	 * Tries every address of the line entered (such as 1.1) and reads the devices answering; this
	 * takes a while.
	 */
	@Label("Line")
	LINE,

	/**
	 * Tries device 0 of every line, where couplers and routers sit, and reads the devices
	 * answering.
	 */
	@Label("Couplers and routers")
	ROUTERS,

	/**
	 * Reads the KNXnet/IP gateway the project reaches the bus through, by its local device
	 * management; not possible through a controller.
	 */
	@Label("The gateway itself")
	GATEWAY;

}
