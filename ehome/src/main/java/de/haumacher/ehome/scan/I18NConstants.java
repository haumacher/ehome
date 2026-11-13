/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Enter the area and the line to scan (0 to 15 each), or choose another search.
	 */
	public static ResKey ERROR_NO_LINE;

	/**
	 * @en Nothing to search: choose a line, the routers, the gateway or the devices in programming mode.
	 */
	public static ResKey ERROR_NOTHING_TO_SCAN;

	/**
	 * @en Scan through {0}: {1} devices found, {2} identified by the catalog, {3} unidentified.
	 */
	public static ResKey4 SCAN_DONE__GATEWAY_FOUND_IDENTIFIED_UNIDENTIFIED;

	/**
	 * @en The scan through {0} failed: {1}
	 */
	public static ResKey2 SCAN_FAILED__GATEWAY_REASON;

	/**
	 * @en Unidentified devices of the project matched again: {0}.
	 */
	public static ResKey1 REMATCH_DONE__COUNT;

	/**
	 * @en The catalog does not identify the device: {0}
	 */
	public static ResKey1 ERROR_UNIDENTIFIED__REASON;

	/**
	 * @en Choose one of the products and programs the device may be.
	 */
	public static ResKey ERROR_NO_CANDIDATE;

	/**
	 * @en The project has no device {0}: create it first.
	 */
	public static ResKey1 ERROR_NO_DEVICE__ADDRESS;

	/**
	 * @en The device {0} of the project runs the program {1}, the device on the bus another one.
	 */
	public static ResKey2 ERROR_OTHER_PROGRAM__ADDRESS_PROGRAM;

	/**
	 * @en The device {0} is the gateway; it answers only through local device management, which offers no access to its configuration.
	 */
	public static ResKey1 ERROR_LOCAL__ADDRESS;

	/**
	 * @en No device on the bus is selected.
	 */
	public static ResKey ERROR_NO_ROW;

	/**
	 * @en New
	 */
	public static ResKey STATE_NEW;

	/**
	 * @en In the project: {0}
	 */
	public static ResKey1 STATE_EXISTS__DEVICE;

	/**
	 * @en Conflict: the project''s device {0} runs {1}
	 */
	public static ResKey2 STATE_CONFLICT__DEVICE_PROGRAM;

	/**
	 * @en Unidentified, kept in the project
	 */
	public static ResKey STATE_UNIDENTIFIED;

	/**
	 * @en {0} candidates: choose one
	 */
	public static ResKey1 MATCH_CHOICE__COUNT;

	/**
	 * @en The device {0} was created.
	 */
	public static ResKey1 APPLY_CREATED__ADDRESS;

	/**
	 * @en The device {0} was updated.
	 */
	public static ResKey1 APPLY_UPDATED__ADDRESS;

	/**
	 * @en Nothing changed: {0}
	 */
	public static ResKey1 APPLY_CONFLICT__MESSAGE;

	/**
	 * @en Configuration of {0} read back through {1}: {2} parameter values differ from the defaults, {3} connections.
	 */
	public static ResKey4 READBACK_DONE__DEVICE_GATEWAY_SETTINGS_CONNECTIONS;

	/**
	 * @en Reading the configuration of {0} through {1} failed: {2}
	 */
	public static ResKey3 READBACK_FAILED__DEVICE_GATEWAY_REASON;

	/**
	 * @en Switch the programming mode (programming LED) of {0} {1} through {2}? This writes into the device.
	 */
	public static ResKey3 CONFIRM_LED__ADDRESS_STATE_GATEWAY;

	/**
	 * @en on
	 */
	public static ResKey LED_ON;

	/**
	 * @en off
	 */
	public static ResKey LED_OFF;

	/**
	 * @en Programming mode of {0} switched {1} through {2}; the device reports: {3}.
	 */
	public static ResKey4 LED_DONE__ADDRESS_STATE_GATEWAY_REPORTED;

	/**
	 * @en Switching the programming mode of {0} through {1} failed: {2}
	 */
	public static ResKey3 LED_FAILED__ADDRESS_GATEWAY_REASON;

	/**
	 * @en Bus of {0} scanned via {1}: {2} devices
	 */
	public static ResKey3 COMMIT_SCAN__PROJECT_GATEWAY_FOUND;

	/**
	 * @en Configuration of {0} read back
	 */
	public static ResKey1 COMMIT_READ_BACK__DEVICE;

	/**
	 * @en Configuration of {0} read back: equal to the project
	 */
	public static ResKey1 COMMIT_READ_BACK_IDENTICAL__DEVICE;

	/**
	 * @en The gateway itself cannot be read through a controller: its local device management is a KNXnet/IP service of its own, not a frame on the bus. Uncheck "The gateway itself".
	 */
	public static ResKey ERROR_GATEWAY_THROUGH_CONTROLLER;

	static {
		initConstants(I18NConstants.class);
	}
}
