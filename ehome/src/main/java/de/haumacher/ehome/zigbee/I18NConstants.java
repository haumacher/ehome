/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

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
	 * @en No file was uploaded.
	 */
	public static ResKey ERROR_EXPORT_NO_FILE;

	/**
	 * @en The file could not be read: {0}
	 */
	public static ResKey1 ERROR_EXPORT_UNREADABLE__REASON;

	/**
	 * @en The file is not JSON: {0}
	 */
	public static ResKey1 ERROR_EXPORT_NO_JSON__REASON;

	/**
	 * @en The file is no Zigbee export of a HomeControl controller (format "{0}", expected "homecontrol-zigbee-export").
	 */
	public static ResKey1 ERROR_EXPORT_FORMAT__FORMAT;

	/**
	 * @en The Zigbee export has version {0}, which is newer than the version E-Home reads ({1}): update E-Home.
	 */
	public static ResKey2 ERROR_EXPORT_VERSION__VERSION_SUPPORTED;

	/**
	 * @en The Zigbee export is invalid at {0}: {1}
	 */
	public static ResKey2 ERROR_EXPORT_INVALID__PATH_REASON;

	/**
	 * @en Zigbee devices imported: {0} ({1} new, {2} updated), functions: {3}.
	 */
	public static ResKey4 IMPORTED__DEVICES_CREATED_UPDATED_FUNCTIONS;

	/**
	 * @en The function {0} of {1} is no longer in the export; it is kept because the datapoint {2} is bound to it.
	 */
	public static ResKey3 NOTE_FUNCTION_ABSENT__FUNCTION_DEVICE_DATAPOINT;

	/**
	 * @en The function {0} of {1} is no longer in the export and was removed.
	 */
	public static ResKey2 NOTE_FUNCTION_REMOVED__FUNCTION_DEVICE;

	/**
	 * @en The Zigbee network of {0} is open for joining for {1} s: put the device into pairing mode now, then read the Zigbee devices.
	 */
	public static ResKey2 PERMIT_OPEN__CONTROLLER_SECONDS;

	/**
	 * @en The Zigbee network of {0} is closed for joining.
	 */
	public static ResKey1 PERMIT_CLOSED__CONTROLLER;

	/**
	 * @en The controller at {0} cannot be reached: {1}
	 */
	public static ResKey2 ERROR_UNREACHABLE__ADDRESS_ERROR;

	/**
	 * @en The controller {0} runs no Zigbee coordinator: {1}
	 */
	public static ResKey2 ERROR_UNAVAILABLE__CONTROLLER_REASON;

	/**
	 * @en The controller {0} refused the token of E-Home ({1}): set the token again.
	 */
	public static ResKey2 ERROR_TOKEN__CONTROLLER_ERROR;

	/**
	 * @en The controller {0} refused the request (HTTP {1}): {2}
	 */
	public static ResKey3 ERROR_REFUSED__CONTROLLER_STATUS_ERROR;

	/**
	 * @en The controller {0} sent an answer E-Home cannot read: {1}
	 */
	public static ResKey2 ERROR_BAD_ANSWER__CONTROLLER_DETAIL;

	/**
	 * @en The function is bound to the datapoint {0}.
	 */
	public static ResKey1 VETO_BOUND__DATAPOINT;

	/**
	 * @en The function is bound to no datapoint.
	 */
	public static ResKey VETO_UNBOUND;

	/**
	 * @en The function {0} carries {1} values, the datapoint {2} is {3}.
	 */
	public static ResKey4 ERROR_BIND_TYPE__FUNCTION_TYPE_DATAPOINT_DATAPOINT_TYPE;

	/**
	 * @en The datapoint {0} is bound to another function already.
	 */
	public static ResKey1 ERROR_BIND_TAKEN__DATAPOINT;

	/**
	 * @en The datapoint and the function belong to different projects.
	 */
	public static ResKey ERROR_BIND_OTHER_PROJECT;

	/**
	 * @en The Zigbee devices could not be imported: {0}
	 */
	public static ResKey1 ERROR_LIVE_IMPORT__DETAIL;

	/**
	 * @en Network: no controller chosen for the project.
	 */
	public static ResKey NETWORK_NO_CONTROLLER;

	/**
	 * @en Network: not read yet.
	 */
	public static ResKey NETWORK_UNKNOWN;

	/**
	 * @en Network: not formed.
	 */
	public static ResKey NETWORK_DOWN;

	/**
	 * @en Network: PAN {0}, channel {1}, joining open for {2} s more
	 */
	public static ResKey3 NETWORK_OPEN__PAN_CHANNEL_SECONDS;

	/**
	 * @en Network: PAN {0}, channel {1}, joining closed
	 */
	public static ResKey2 NETWORK_CLOSED__PAN_CHANNEL;

	/**
	 * @en Last read: {0} (on request)
	 */
	public static ResKey1 READ_ON_REQUEST__TIME;

	/**
	 * @en Last read: {0} (live from the controller)
	 */
	public static ResKey1 READ_LIVE__TIME;

	/**
	 * @en The live import from {0} failed at {1}: {2}
	 */
	public static ResKey3 LIVE_IMPORT_FAILED__CONTROLLER_TIME_REASON;

	/**
	 * @en Interviewing
	 */
	public static ResKey DEVICE_INTERVIEWING;

	/**
	 * @en Interview failed
	 */
	public static ResKey DEVICE_INTERVIEW_FAILED;

	/**
	 * @en Left the network
	 */
	public static ResKey DEVICE_LEFT;

	/**
	 * @en Paired
	 */
	public static ResKey DEVICE_PAIRED;

	/**
	 * @en Not interviewed
	 */
	public static ResKey DEVICE_NOT_INTERVIEWED;

	/**
	 * @en On/Off
	 */
	public static ResKey FUNCTION_ON_OFF;

	/**
	 * @en Brightness
	 */
	public static ResKey FUNCTION_LEVEL;

	/**
	 * @en Color temperature
	 */
	public static ResKey FUNCTION_COLOR_TEMPERATURE;

	/**
	 * @en Temperature
	 */
	public static ResKey FUNCTION_TEMPERATURE;

	/**
	 * @en Humidity
	 */
	public static ResKey FUNCTION_HUMIDITY;

	/**
	 * @en Illuminance
	 */
	public static ResKey FUNCTION_ILLUMINANCE;

	/**
	 * @en Occupancy
	 */
	public static ResKey FUNCTION_OCCUPANCY;

	/**
	 * @en Alarm zone
	 */
	public static ResKey FUNCTION_IAS_ZONE;

	/**
	 * @en Contact
	 */
	public static ResKey FUNCTION_CONTACT;

	/**
	 * @en Motion
	 */
	public static ResKey FUNCTION_MOTION;

	/**
	 * @en Smoke
	 */
	public static ResKey FUNCTION_SMOKE;

	/**
	 * @en Water
	 */
	public static ResKey FUNCTION_WATER;

	/**
	 * @en Alarm
	 */
	public static ResKey FUNCTION_ALARM;

	/**
	 * @en Battery
	 */
	public static ResKey FUNCTION_BATTERY;

	/**
	 * @en Multistate
	 */
	public static ResKey FUNCTION_MULTISTATE;

	/**
	 * @en Action
	 */
	public static ResKey FUNCTION_ACTION;

	/**
	 * @en No function of the device is absent from the last export.
	 */
	public static ResKey VETO_NOTHING_ABSENT;

	/**
	 * @en {0} removed, {1} kept: {2}
	 */
	public static ResKey3 ABSENT_REMOVED__REMOVED_KEPT_DETAILS;

	/**
	 * @en {0} removed.
	 */
	public static ResKey1 ABSENT_REMOVED__REMOVED;

	/**
	 * @en {0} is bound to {1}
	 */
	public static ResKey2 ABSENT_KEPT__FUNCTION_DATAPOINTS;

	static {
		initConstants(I18NConstants.class);
	}
}
