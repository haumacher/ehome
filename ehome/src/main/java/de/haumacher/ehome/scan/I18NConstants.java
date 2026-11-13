/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.basic.util.ResKey5;
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
	 * @en on
	 */
	public static ResKey LED_ON;

	/**
	 * @en off
	 */
	public static ResKey LED_OFF;

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

	/**
	 * @en Scanning line {0} through {1} failed: {2}
	 */
	public static ResKey3 SCAN_LINE_FAILED__LINE_PATH_REASON;

	/**
	 * @en Scanning for couplers and routers through {0} failed: {1}
	 */
	public static ResKey2 SCAN_ROUTERS_FAILED__PATH_REASON;

	/**
	 * @en Reading the address of the devices in programming mode through {0} failed: {1}
	 */
	public static ResKey2 SCAN_PROGRAMMING_MODE_FAILED__PATH_REASON;

	/**
	 * @en No management connection through {0}: {1}
	 */
	public static ResKey2 SCAN_NO_MANAGEMENT__PATH_REASON;

	/**
	 * @en The gateway itself cannot be read through {0}: its local device management is a KNXnet/IP service of its own.
	 */
	public static ResKey1 SCAN_GATEWAY_NOT_LOCAL__PATH;

	/**
	 * @en No local device management of {0}: {1}
	 */
	public static ResKey2 SCAN_NO_LOCAL_MANAGEMENT__PATH_REASON;

	/**
	 * @en The gateway has no interface object of type {0}.
	 */
	public static ResKey1 SCAN_GATEWAY_NO_OBJECT__TYPE;

	/**
	 * @en Reading object type {0} property {1} of {2} failed: {3}
	 */
	public static ResKey4 SCAN_LOCAL_PROPERTY_FAILED__TYPE_PROPERTY_PATH_REASON;

	/**
	 * @en Reading the gateway {0} (local device management).
	 */
	public static ResKey1 MESSAGE_READING_GATEWAY__PATH;

	/**
	 * @en The gateway {0} offers no local device management.
	 */
	public static ResKey1 NOTE_NO_LOCAL_MANAGEMENT__PATH;

	/**
	 * @en The gateway {0} could not be read through local device management: {1}
	 */
	public static ResKey2 NOTE_LOCAL_MANAGEMENT_FAILED__PATH_REASON;

	/**
	 * @en Found {0}.
	 */
	public static ResKey1 MESSAGE_FOUND__ADDRESS;

	/**
	 * @en Scanning line {0}.
	 */
	public static ResKey1 MESSAGE_SCANNING_LINE__LINE;

	/**
	 * @en Scanning for couplers and routers.
	 */
	public static ResKey MESSAGE_SCANNING_ROUTERS;

	/**
	 * @en Looking for devices in programming mode.
	 */
	public static ResKey MESSAGE_PROGRAMMING_MODE;

	/**
	 * @en Reading {0}.
	 */
	public static ResKey1 MESSAGE_READING__ADDRESS;

	/**
	 * @en Reading {0} again.
	 */
	public static ResKey1 MESSAGE_READING_AGAIN__ADDRESS;

	/**
	 * @en {0}: {1}
	 */
	public static ResKey2 NOTE__LABEL_VALUE;

	/**
	 * @en Device descriptor
	 */
	public static ResKey READ_DESCRIPTOR;

	/**
	 * @en Program identity (memory {0})
	 */
	public static ResKey1 READ_PROGRAM_IDENTITY__ADDRESS;

	/**
	 * @en Manufacturer
	 */
	public static ResKey READ_MANUFACTURER;

	/**
	 * @en Serial number
	 */
	public static ResKey READ_SERIAL_NUMBER;

	/**
	 * @en Program version
	 */
	public static ResKey READ_PROGRAM_VERSION;

	/**
	 * @en Hardware type
	 */
	public static ResKey READ_HARDWARE_TYPE;

	/**
	 * @en Order info
	 */
	public static ResKey READ_ORDER_INFO;

	/**
	 * @en Load state {0}
	 */
	public static ResKey1 READ_LOAD_STATE__LSM;

	/**
	 * @en Run state
	 */
	public static ResKey READ_RUN_STATE;

	/**
	 * @en The gateway reports no individual address.
	 */
	public static ResKey SCAN_GATEWAY_NO_ADDRESS;

	/**
	 * @en Halted
	 */
	public static ResKey RUN_STATE_HALTED;

	/**
	 * @en Running
	 */
	public static ResKey RUN_STATE_RUNNING;

	/**
	 * @en Ready
	 */
	public static ResKey RUN_STATE_READY;

	/**
	 * @en Terminated
	 */
	public static ResKey RUN_STATE_TERMINATED;

	/**
	 * @en Starting
	 */
	public static ResKey RUN_STATE_STARTING;

	/**
	 * @en Shutting down
	 */
	public static ResKey RUN_STATE_SHUTTING_DOWN;

	/**
	 * @en Scan through {0}: {1} devices found; the catalog identifies {2} uniquely, {3} need a choice among their candidates, {4} are unidentified.
	 */
	public static ResKey5 SCAN_DONE__GATEWAY_FOUND_IDENTIFIED_CHOICES_UNIDENTIFIED;

	/**
	 * @en In programming mode: {0}.
	 */
	public static ResKey1 NOTE_PROGRAMMING_MODE__ADDRESSES;

	/**
	 * @en Read the gateway
	 */
	public static ResKey PHASE_GATEWAY;

	/**
	 * @en Scan the line
	 */
	public static ResKey PHASE_LINE;

	/**
	 * @en Scan for couplers and routers
	 */
	public static ResKey PHASE_ROUTERS;

	/**
	 * @en Look for devices in programming mode
	 */
	public static ResKey PHASE_PROGRAMMING;

	/**
	 * @en Read the devices found
	 */
	public static ResKey PHASE_DEVICES;

	/**
	 * @en The manufacturer {0} is not in the catalog.
	 */
	public static ResKey1 UNIDENTIFIED_MANUFACTURER__MANUFACTURER;

	/**
	 * @en {0} is for mask {1}
	 */
	public static ResKey2 EXCLUDED_MASK__PROGRAM_MASK;

	/**
	 * @en {0} expects the hardware type {1}
	 */
	public static ResKey2 EXCLUDED_HARDWARE__PROGRAM_TYPE;

	/**
	 * @en No program {0} version {1} of {2} in the catalog.
	 */
	public static ResKey3 UNIDENTIFIED_PROGRAM__NUMBER_VERSION_MANUFACTURER;

	/**
	 * @en No program {0} version {1} of {2} in the catalog fits the device: {3}.
	 */
	public static ResKey4 UNIDENTIFIED_PROGRAM_EXCLUDED__NUMBER_VERSION_MANUFACTURER_EXCLUDED;

	/**
	 * @en No product of the catalog carries the program {0}.
	 */
	public static ResKey1 UNIDENTIFIED_NO_PRODUCT__PROGRAM;

	/**
	 * @en The device reported no program version and no order info.
	 */
	public static ResKey UNIDENTIFIED_NO_IDENTITY;

	/**
	 * @en The device reported no program version, and no product of {0} has the order number {1}.
	 */
	public static ResKey2 UNIDENTIFIED_ORDER__MANUFACTURER_ORDER;

	/**
	 * @en {0}; {1}
	 */
	public static ResKey2 JOIN__FIRST_NEXT;

	/**
	 * @en Switch the programming mode (programming LED) of {0} on through {1}? This writes into the device.
	 */
	public static ResKey2 CONFIRM_LED_ON__ADDRESS_GATEWAY;

	/**
	 * @en Switch the programming mode (programming LED) of {0} off through {1}? This writes into the device.
	 */
	public static ResKey2 CONFIRM_LED_OFF__ADDRESS_GATEWAY;

	/**
	 * @en Programming mode of {0} switched on through {1}; the device reports: {2}.
	 */
	public static ResKey3 LED_DONE_ON__ADDRESS_GATEWAY_REPORTED;

	/**
	 * @en Programming mode of {0} switched off through {1}; the device reports: {2}.
	 */
	public static ResKey3 LED_DONE_OFF__ADDRESS_GATEWAY_REPORTED;

	/**
	 * @en Property {0}
	 */
	public static ResKey1 READ_PROPERTY__PROPERTY;

	/**
	 * @en Parameters in property {0} of an object given by type ({1}) are not supported.
	 */
	public static ResKey2 READBACK_PROPERTY_BY_TYPE__PROPERTY_TYPE;

	/**
	 * @en Reading {0} answered {1} of {2} bytes.
	 */
	public static ResKey3 READBACK_SHORT_ANSWER__ADDRESS_COUNT_EXPECTED;

	/**
	 * @en Table pointer of object {0}: {1} bytes.
	 */
	public static ResKey2 READBACK_TABLE_POINTER_LENGTH__OBJECT_LENGTH;

	/**
	 * @en Table pointer of object {0}: {1}
	 */
	public static ResKey2 READBACK_TABLE_POINTER_FAILED__OBJECT_REASON;

	/**
	 * @en Parameter {0} ({1}): {2}
	 */
	public static ResKey3 READBACK_PARAMETER__NAME_ID_REASON;

	/**
	 * @en Property {0}/{1} differs: device {2}, project {3}.
	 */
	public static ResKey4 READBACK_PROPERTY_DIFFERS__OBJECT_PROPERTY_DEVICE_PROJECT;

	/**
	 * @en The parameter values did not settle after {0} passes.
	 */
	public static ResKey1 READBACK_NOT_SETTLED__PASSES;

	/**
	 * @en Object {0} is associated, but not in effect with the parameters read.
	 */
	public static ResKey1 READBACK_OBJECT_NOT_IN_EFFECT__OBJECT;

	/**
	 * @en Object {0}: {1}
	 */
	public static ResKey2 READBACK_OBJECT__OBJECT_REASON;

	/**
	 * @en Association {0} names the address {1} of {2}, which the address table does not hold.
	 */
	public static ResKey3 READBACK_ASSOCIATION_INDEX__NUMBER_INDEX_COUNT;

	/**
	 * @en Object {0} has other flags in the device, but is not in effect.
	 */
	public static ResKey1 READBACK_FLAGS_NOT_IN_EFFECT__OBJECT;

	/**
	 * @en Object {0} has the priority code 0 (system), which a project cannot express.
	 */
	public static ResKey1 READBACK_SYSTEM_PRIORITY__OBJECT;

	/**
	 * @en The size of its type is not known.
	 */
	public static ResKey DECODE_SIZE_UNKNOWN;

	/**
	 * @en Its memory was not read.
	 */
	public static ResKey DECODE_NOT_READ;

	/**
	 * @en Its segment has no absolute address.
	 */
	public static ResKey DECODE_NO_ABSOLUTE_SEGMENT;

	/**
	 * @en Its location is not supported.
	 */
	public static ResKey DECODE_LOCATION;

	/**
	 * @en The value {0} is no entry of {1}.
	 */
	public static ResKey2 DECODE_NO_ENTRY__VALUE_TYPE;

	/**
	 * @en No date: {0}
	 */
	public static ResKey1 DECODE_NO_DATE__BYTES;

	/**
	 * @en No IP address: {0}
	 */
	public static ResKey1 DECODE_NO_IP__BYTES;

	/**
	 * @en Its type {0} is not supported.
	 */
	public static ResKey1 DECODE_TYPE__TYPE;

	/**
	 * @en Group addresses created: {0}.
	 */
	public static ResKey1 READBACK_CREATED_ADDRESSES__ADDRESSES;

	/**
	 * @en {0} communication objects with flags of their own.
	 */
	public static ResKey1 READBACK_FLAGS__COUNT;

	/**
	 * @en {0} bytes of the download image compared with the memory read, all equal.
	 */
	public static ResKey1 READBACK_EQUAL__COUNT;

	/**
	 * @en {0} of {1} bytes of the download image differ from the memory read: {2}.
	 */
	public static ResKey3 READBACK_DIFFERENT__DIFFERENT_COUNT_ADDRESSES;

	static {
		initConstants(I18NConstants.class);
	}
}
