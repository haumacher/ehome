/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.tooling;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants of the programmable virtual device and its recorder.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en No device is selected.
	 */
	public static ResKey ERROR_NO_DEVICE;

	/**
	 * @en The device has no complete individual address.
	 */
	public static ResKey ERROR_NO_ADDRESS;

	/**
	 * @en The device has no application program.
	 */
	public static ResKey ERROR_NO_PROGRAM;

	/**
	 * @en The application program of the device names no mask version.
	 */
	public static ResKey ERROR_NO_MASK;

	/**
	 * @en The device {0} has the address of an area coupler, a virtual interface cannot serve it.
	 */
	public static ResKey1 ERROR_COUPLER_ADDRESS__ADDRESS;

	/**
	 * @en A virtual interface is running already: {0} on {1}. Stop it first.
	 */
	public static ResKey2 ERROR_ALREADY_RUNNING__NAME_ENDPOINT;

	/**
	 * @en No virtual interface is running.
	 */
	public static ResKey ERROR_NOT_RUNNING;

	/**
	 * @en Choose the local address the virtual interface listens on.
	 */
	public static ResKey ERROR_NO_LOCAL_ADDRESS;

	/**
	 * @en {0} is no UDP port, a port is a number from 1 to 65535.
	 */
	public static ResKey1 ERROR_INVALID_PORT__PORT;

	/**
	 * @en The trace file could not be created: {0}
	 */
	public static ResKey1 ERROR_TRACE_FILE__DETAIL;

	/**
	 * @en The trace {0} does not exist any more.
	 */
	public static ResKey1 ERROR_NO_TRACE__NAME;

	/**
	 * @en The virtual interface "{0}" listens on {1} (UDP). Its only device is {2}, nothing is forwarded to a bus. Services recorded so far: {3}.
	 */
	public static ResKey4 STATUS_RUNNING__NAME_ENDPOINT_DEVICE_SERVICES;

	/**
	 * @en No virtual interface is running.
	 */
	public static ResKey STATUS_STOPPED;

	/**
	 * @en Programming mode is on: the device {0} answers A_IndividualAddress_Read and takes the address of A_IndividualAddress_Write. It ends with the next restart of the device or another press of the programming button.
	 */
	public static ResKey1 STATUS_PROGRAMMING_MODE__ADDRESS;

	/**
	 * @en A programming tool wrote the address {0}: the device answers there on the virtual bus until the virtual device is stopped. The project keeps {1}, the virtual device does not change the project.
	 */
	public static ResKey2 STATUS_ADDRESS_CHANGED__ADDRESS_PROJECT;

	/**
	 * @en The interface (coupler {1}) does not route frames to {0}: the device cannot be reached through it at its new address.
	 */
	public static ResKey2 STATUS_ADDRESS_UNREACHABLE__ADDRESS_COUPLER;

	static {
		initConstants(I18NConstants.class);
	}
}
