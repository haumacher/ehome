/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants of the virtual device.
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
	 * @en The UDP port {0} is in use: another KNXnet/IP server or a programming tool listens there.
	 */
	public static ResKey1 ERROR_PORT_IN_USE__ENDPOINT;

	/**
	 * @en The virtual interface on {0} could not be started: {1}
	 */
	public static ResKey2 ERROR_START__ENDPOINT_DETAIL;

	/**
	 * @en {0} is no IPv4 address, KNXnet/IP needs one.
	 */
	public static ResKey1 ERROR_NOT_IPV4__ADDRESS;

	/**
	 * @en {0} is no address of this computer.
	 */
	public static ResKey1 ERROR_NOT_LOCAL__ADDRESS;

	/**
	 * @en The virtual interface can only listen on the first IPv4 address of the network interface {1}, which is {2}, not {0}.
	 */
	public static ResKey3 ERROR_NOT_FIRST_ADDRESS__ADDRESS_INTERFACE_FIRST;

	/**
	 * @en The device {0} has the address of an area coupler, a virtual interface cannot serve it.
	 */
	public static ResKey1 ERROR_COUPLER_ADDRESS__ADDRESS;

	/**
	 * @en Interrupted while starting or stopping the virtual interface.
	 */
	public static ResKey ERROR_INTERRUPTED;

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

	static {
		initConstants(I18NConstants.class);
	}
}
