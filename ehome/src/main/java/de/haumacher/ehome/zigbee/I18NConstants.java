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

	/**
	 * @en the function action (a client binding, button commands) configures no reporting
	 */
	public static ResKey REPORTING_NONE_ACTION;

	/**
	 * @en the function multistate configures no reporting (its reports are events)
	 */
	public static ResKey REPORTING_NONE_MULTISTATE;

	/**
	 * @en the function ias_zone configures no reporting (zone status change notifications)
	 */
	public static ResKey REPORTING_NONE_IAS_ZONE;

	/**
	 * @en the function configures no reporting
	 */
	public static ResKey REPORTING_NONE;

	/**
	 * @en min must be 0..65534
	 */
	public static ResKey REPORTING_MIN_RANGE;

	/**
	 * @en max must be 0..65535
	 */
	public static ResKey REPORTING_MAX_RANGE;

	/**
	 * @en max must not be below min (an absent min is the function's default); 0: no periodic reports, 65535: no reports
	 */
	public static ResKey REPORTING_MAX_BELOW_MIN;

	/**
	 * @en min must not exceed max (an absent max is the function's default); max 0: no periodic reports, max 65535: no reports
	 */
	public static ResKey REPORTING_MIN_ABOVE_MAX;

	/**
	 * @en the function {0} has no reportable change (a discrete value reports every change)
	 */
	public static ResKey1 REPORTING_NO_CHANGE_DISCRETE__FUNCTION;

	/**
	 * @en the function illuminance has no reportable change in lux (its attribute is logarithmic: a lux step has no fixed attribute step); min and max are allowed
	 */
	public static ResKey REPORTING_NO_CHANGE_ILLUMINANCE;

	/**
	 * @en the function color_temperature has no reportable change in K (its attribute counts mireds, 1000000 / K: a kelvin step has no fixed attribute step); min and max are allowed
	 */
	public static ResKey REPORTING_NO_CHANGE_COLOR_TEMPERATURE;

	/**
	 * @en the function has no reportable change
	 */
	public static ResKey REPORTING_NO_CHANGE;

	/**
	 * @en change must be 0..327.67 (°C)
	 */
	public static ResKey REPORTING_CHANGE_TEMPERATURE;

	/**
	 * @en change must be 0..100 (%)
	 */
	public static ResKey REPORTING_CHANGE_PERCENT;

	/**
	 * @en Reports: every {0}..{1} s
	 */
	public static ResKey2 REPORTING_EVERY__MIN_MAX;

	/**
	 * @en Reports: on a change only, at most every {0} s
	 */
	public static ResKey1 REPORTING_ON_CHANGE__MIN;

	/**
	 * @en Reports: none, the value only from reads
	 */
	public static ResKey REPORTING_OFF;

	/**
	 * @en {0}, change {1} {2}
	 */
	public static ResKey3 REPORTING_CHANGE__REPORTS_CHANGE_UNIT;

	/**
	 * @en {0}, change as the controller's default
	 */
	public static ResKey1 REPORTING_CHANGE_DEFAULT__REPORTS;

	/**
	 * @en {0} (default)
	 */
	public static ResKey1 REPORTING_DEFAULT__REPORTS;

	/**
	 * @en {0} (as the controller reports)
	 */
	public static ResKey1 REPORTING_CONTROLLER__REPORTS;

	/**
	 * @en Reports: not configured (events or notifications of the device)
	 */
	public static ResKey REPORTING_NOT_CONFIGURED;

	/**
	 * @en The function {0} of endpoint {1} is listed twice.
	 */
	public static ResKey2 CATALOG_DUPLICATE_FUNCTION__FUNCTION_ENDPOINT;

	/**
	 * @en The device type {0} {1} is listed twice.
	 */
	public static ResKey2 CATALOG_DUPLICATE_TYPE__MANUFACTURER_MODEL;

	/**
	 * @en {0} device types imported: {1} new, {2} updated, {3} unchanged.
	 */
	public static ResKey4 CATALOG_IMPORTED__TYPES_CREATED_UPDATED_UNCHANGED;

	/**
	 * @en The endpoint must be an integer 1..240.
	 */
	public static ResKey CATALOG_INVALID_ENDPOINT;

	/**
	 * @en The functions must be an array.
	 */
	public static ResKey CATALOG_INVALID_FUNCTIONS;

	/**
	 * @en Hidden must be true or false.
	 */
	public static ResKey CATALOG_INVALID_HIDDEN;

	/**
	 * @en An integer is expected.
	 */
	public static ResKey CATALOG_INVALID_INTEGER;

	/**
	 * @en A number is expected.
	 */
	public static ResKey CATALOG_INVALID_NUMBER;

	/**
	 * @en An object is expected.
	 */
	public static ResKey CATALOG_INVALID_OBJECT;

	/**
	 * @en A text (or an object of texts by language) is expected.
	 */
	public static ResKey CATALOG_INVALID_TEXT;

	/**
	 * @en {0} is no datapoint type of the runtime image.
	 */
	public static ResKey1 CATALOG_INVALID_TYPE__TYPE;

	/**
	 * @en The types must be an array.
	 */
	public static ResKey CATALOG_INVALID_TYPES;

	/**
	 * @en The version must be a positive integer.
	 */
	public static ResKey CATALOG_INVALID_VERSION;

	/**
	 * @en Device types of the Zigbee catalog shipped with E-Home ({0})
	 */
	public static ResKey1 CATALOG_SEED__FILE;

	/**
	 * @en The datapoint type {0} does not fit the function {1}, which carries the value type {2}.
	 */
	public static ResKey3 CATALOG_TYPE_MISFIT__TYPE_FUNCTION_VALUE_TYPE;

	/**
	 * @en {0} datapoints created: {1}
	 */
	public static ResKey2 DATAPOINTS_CREATED__COUNT_IDS;

	/**
	 * @en The file is no Zigbee catalog file of E-Home (format "{0}", expected "ehome-zigbee-catalog").
	 */
	public static ResKey1 ERROR_CATALOG_FORMAT__FORMAT;

	/**
	 * @en The catalog file is refused at {0}: {1}
	 */
	public static ResKey2 ERROR_CATALOG_INVALID__PATH_REASON;

	/**
	 * @en The catalog file has version {0}, which is newer than the version E-Home reads ({1}): update E-Home.
	 */
	public static ResKey2 ERROR_CATALOG_VERSION__VERSION_SUPPORTED;

	/**
	 * @en Every function of the device that is not hidden has a datapoint already.
	 */
	public static ResKey VETO_NOTHING_TO_CREATE;

	static {
		initConstants(I18NConstants.class);
	}
}
