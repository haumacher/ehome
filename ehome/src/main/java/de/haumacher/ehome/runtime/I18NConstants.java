/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

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
	 * @en The group address {0} has no address and is not part of the runtime image.
	 */
	public static ResKey1 NOTE_NO_ADDRESS__NAME;

	/**
	 * @en The group address {0} has no datapoint type (neither its own nor one of a connected object) and is not part of the runtime image.
	 */
	public static ResKey1 NOTE_NO_DATAPOINT_TYPE__ADDRESS;

	/**
	 * @en The group address {0} has the datapoint type {1}, which the controller does not support; it is not part of the runtime image.
	 */
	public static ResKey2 NOTE_UNSUPPORTED_DATAPOINT_TYPE__ADDRESS_TYPE;

	/**
	 * @en The project has no controller to deploy to: choose one in the deployment tab.
	 */
	public static ResKey ERROR_NO_CONTROLLER;

	/**
	 * @en The controller {0} has no token: set the token created on its console first.
	 */
	public static ResKey1 ERROR_NO_TOKEN__CONTROLLER;

	/**
	 * @en The controller {0} is not connected to E-Home (last contact {1}): switch on the E-Home connection on its Settings page, or enter its address and token to reach it on the LAN.
	 */
	public static ResKey2 ERROR_OFFLINE__CONTROLLER_LAST_CONTACT;

	/**
	 * @en The controller {0} has not connected to E-Home yet: switch on the E-Home connection on its Settings page.
	 */
	public static ResKey1 ERROR_NEVER_CONNECTED__CONTROLLER;

	/**
	 * @en The controller {0} has no host: enter its address.
	 */
	public static ResKey1 ERROR_NO_HOST__CONTROLLER;

	/**
	 * @en E-Home connection
	 */
	public static ResKey ADDRESS_CONNECTION;

	/**
	 * @en not connected
	 */
	public static ResKey ADDRESS_OFFLINE;

	/**
	 * @en The runtime image could not be created: {0}
	 */
	public static ResKey1 ERROR_EXPORT_FAILED__DETAIL;

	/**
	 * @en The runtime image is valid: {0} datapoints, {1} locations, {2} functions, {3} settings, {4} bytes.
	 */
	public static ResKey5 EXPORT_VALID__DATAPOINTS_LOCATIONS_FUNCTIONS_SETTINGS_BYTES;

	/**
	 * @en The time master sends on {0}.
	 */
	public static ResKey1 EXPORT_TIME_MASTER__ADDRESSES;

	/**
	 * @en The image has no time master.
	 */
	public static ResKey EXPORT_NO_TIME_MASTER;

	/**
	 * @en The group address has the datapoint type {0}; the time master sends {1} on it.
	 */
	public static ResKey2 INVALID_TIME_MASTER_TYPE__TYPE_EXPECTED;

	/**
	 * @en The runtime image is not valid ({0} refusals); the controller would refuse it.
	 */
	public static ResKey1 EXPORT_INVALID__COUNT;

	/**
	 * @en The controller loaded revision {0}: {1} datapoints, {2} locations, {3} bytes.
	 */
	public static ResKey4 DEPLOY_ACCEPTED__REVISION_DATAPOINTS_LOCATIONS_BYTES;

	/**
	 * @en The controller runs the image but could not store it ({0}); after a restart it loads the previous one.
	 */
	public static ResKey1 DEPLOY_NOT_STORED__ERROR;

	/**
	 * @en E-Home did not send the image: it has {0} refusals.
	 */
	public static ResKey1 DEPLOY_REFUSED_LOCALLY__COUNT;

	/**
	 * @en The controller at {0} could not be reached: {1}
	 */
	public static ResKey2 DEPLOY_UNREACHABLE__ADDRESS_ERROR;

	/**
	 * @en The controller refused the image (HTTP {0}): {1}
	 */
	public static ResKey2 DEPLOY_REFUSED__STATUS_ERROR;

	/**
	 * @en The controller has no image loaded.
	 */
	public static ResKey STATUS_NO_IMAGE;

	/**
	 * @en The controller has no image loaded; the last one it was given was refused ({0}).
	 */
	public static ResKey1 STATUS_NO_IMAGE_REFUSED__CODE;

	/**
	 * @en The controller runs project {0}, revision {1} ({2}), loaded by {3}.
	 */
	public static ResKey4 STATUS_LOADED__PROJECT_REVISION_NAME_SOURCE;

	/**
	 * @en Its KNX connection is down (degraded).
	 */
	public static ResKey STATUS_DEGRADED;

	/**
	 * @en The controller at {0} could not be reached: {1}
	 */
	public static ResKey2 STATUS_UNREACHABLE__ADDRESS_ERROR;

	/**
	 * @en The controller does not tell its status (HTTP {0}): {1}
	 */
	public static ResKey2 STATUS_FAILED__STATUS_ERROR;

	/**
	 * @en In sync: the controller runs revision {0} of this project, the revision of its last deployment.
	 */
	public static ResKey1 DRIFT_IN_SYNC__REVISION;

	/**
	 * @en Drift: the controller runs another project ({0}).
	 */
	public static ResKey1 DRIFT_OTHER_PROJECT__PROJECT;

	/**
	 * @en Drift: the controller runs revision {0} of this project, its last deployment was revision {1}.
	 */
	public static ResKey2 DRIFT_OTHER_REVISION__LOADED_DEPLOYED;

	/**
	 * @en Drift: the controller runs revision {0} of this project, which has not been deployed from here.
	 */
	public static ResKey1 DRIFT_NOT_DEPLOYED__LOADED;

	/**
	 * @en Drift: the controller runs no image, the last deployment of this project was revision {0}.
	 */
	public static ResKey1 DRIFT_NO_IMAGE__DEPLOYED;

	/**
	 * @en The project has not been deployed yet.
	 */
	public static ResKey DRIFT_NEVER_DEPLOYED;

	/**
	 * @en Set
	 */
	public static ResKey TOKEN_SET;

	/**
	 * @en Not set
	 */
	public static ResKey TOKEN_NOT_SET;

	/**
	 * @en The location (latitude and longitude) is incomplete and not part of the runtime image.
	 */
	public static ResKey NOTE_LOCATION_INCOMPLETE;

	/**
	 * @en The group address {0} of the time master has the datapoint type {1}; the controller sends {2} on it.
	 */
	public static ResKey3 NOTE_TIME_MASTER_TYPE__ADDRESS_TYPE_EXPECTED;

	/**
	 * @en Writable and read on start are derived from the connected objects.
	 */
	public static ResKey FLAGS_DERIVED;

	/**
	 * @en Writable and read on start are set for the address.
	 */
	public static ResKey FLAGS_SET;

	/**
	 * @en Writable is set for the address, read on start is derived from the connected objects.
	 */
	public static ResKey FLAGS_WRITABLE_SET;

	/**
	 * @en Read on start is set for the address, writable is derived from the connected objects.
	 */
	public static ResKey FLAGS_READ_ON_START_SET;

	/**
	 * @en A history needs both the retention and the spacing.
	 */
	public static ResKey HISTORY_INCOMPLETE;

	/**
	 * @en A history is kept only of numbers and truth values, not of values of the type {0}.
	 */
	public static ResKey1 HISTORY_WRONG_TYPE__TYPE;

	/**
	 * @en The retention of a history is at least its spacing.
	 */
	public static ResKey HISTORY_RETENTION_BELOW_SPACING;

	/**
	 * @en The history would hold {0} samples; the controller keeps at most {1} per datapoint.
	 */
	public static ResKey2 HISTORY_TOO_MANY_SAMPLES__SAMPLES_MAX;

	/**
	 * @en An identifier has 1 to 32 letters, digits and the characters _ . -
	 */
	public static ResKey INVALID_IDENTIFIER;

	/**
	 * @en This is no POSIX TZ string (e.g. CET-1CEST,M3.5.0,M10.5.0/3): {0}
	 */
	public static ResKey1 INVALID_TIME_ZONE__DETAIL;

	/**
	 * @en This is no host name: {0}
	 */
	public static ResKey1 INVALID_HOST_NAME__DETAIL;

	/**
	 * @en {0} functions were proposed and added.
	 */
	public static ResKey1 PROPOSAL_ADDED__COUNT;

	/**
	 * @en The project has a function for every channel the proposal recognizes; nothing was added.
	 */
	public static ResKey PROPOSAL_NOTHING_NEW;

	/**
	 * @en New function
	 */
	public static ResKey NEW_FUNCTION_NAME;

	/**
	 * @en {0}: scene {1}
	 */
	public static ResKey2 SCENE_FUNCTION__CHANNEL_NUMBER;

	/**
	 * @en The stored image could not be read from {0}: {1}
	 */
	public static ResKey2 COMPARE_UNREACHABLE__ADDRESS_ERROR;

	/**
	 * @en The controller stores no image yet.
	 */
	public static ResKey COMPARE_NO_IMAGE;

	/**
	 * @en The controller did not give its image (HTTP {0}): {1}
	 */
	public static ResKey2 COMPARE_FAILED__STATUS_ERROR;

	/**
	 * @en The image the controller stores is no runtime image E-Home can read.
	 */
	public static ResKey COMPARE_UNREADABLE;

	/**
	 * @en Nothing to deploy: the controller stores revision {0} of this project, identical to its current image.
	 */
	public static ResKey1 COMPARE_IDENTICAL__REVISION;

	/**
	 * @en The controller stores revision {0} of this project; the image to deploy is revision {1}.
	 */
	public static ResKey2 COMPARE_REVISION__STORED_NEXT;

	/**
	 * @en The controller stores the image of another project ({0} "{1}", revision {2}); deploying replaces it.
	 */
	public static ResKey3 COMPARE_OTHER_PROJECT__PROJECT_NAME_REVISION;

	/**
	 * @en Stored image: {0} datapoints, {1} locations, {2} functions.
	 */
	public static ResKey3 COMPARE_STORED_COUNTS__DATAPOINTS_LOCATIONS_FUNCTIONS;

	/**
	 * @en Image to deploy: {0} datapoints, {1} locations, {2} functions.
	 */
	public static ResKey3 COMPARE_NEW_COUNTS__DATAPOINTS_LOCATIONS_FUNCTIONS;

	/**
	 * @en Changed parts of the image: {0}.
	 */
	public static ResKey1 COMPARE_CHANGED_PARTS__PARTS;

	/**
	 * @en The stored image has {0} rules and {1} schedules, which E-Home does not author; deploying removes them.
	 */
	public static ResKey2 COMPARE_RULES_REMOVED__RULES_SCHEDULES;

	/**
	 * @en Deploy revision {0} of the runtime image to {1}?
	 */
	public static ResKey2 CONFIRM_DEPLOY__REVISION_CONTROLLER;

	/**
	 * @en Deploy it again as revision {0}?
	 */
	public static ResKey1 CONFIRM_DEPLOY_AGAIN__REVISION;

	/**
	 * @en Unload the runtime image from {0}? The controller deletes it with its persisted values and history and returns to setup.
	 */
	public static ResKey1 CONFIRM_UNLOAD__CONTROLLER;

	/**
	 * @en The controller {0} unloaded its image and returned to setup.
	 */
	public static ResKey1 UNLOAD_DONE__CONTROLLER;

	/**
	 * @en The controller did not unload its image (HTTP {0}): {1}
	 */
	public static ResKey2 UNLOAD_REFUSED__STATUS_ERROR;

	/**
	 * @en The search for controllers needs a network interface that is up and supports multicast; there is none.
	 */
	public static ResKey ERROR_DISCOVERY_NO_NETWORK;

	/**
	 * @en The search for controllers failed: {0}
	 */
	public static ResKey1 ERROR_DISCOVERY_FAILED__DETAIL;

	/**
	 * @en There is no network interface "{0}".
	 */
	public static ResKey1 ERROR_DISCOVERY_UNKNOWN_INTERFACE__NAME;

	/**
	 * @en New rule
	 */
	public static ResKey NEW_RULE_NAME;

	/**
	 * @en New schedule entry
	 */
	public static ResKey NEW_SCHEDULE_LABEL;

	/**
	 * @en Choice
	 */
	public static ResKey NEW_CHOICE_LABEL;

	/**
	 * @en Parameter
	 */
	public static ResKey NEW_PARAMETER_LABEL;

	/**
	 * @en The file is no JSON document: {0}
	 */
	public static ResKey1 ERROR_RULE_IMPORT_NO_JSON__REASON;

	/**
	 * @en The file has neither rules nor schedule entries (the members "rules" and "schedules" of a runtime image).
	 */
	public static ResKey ERROR_RULE_IMPORT_NO_RULES;

	/**
	 * @en The rules of the file cannot be read: {0}
	 */
	public static ResKey1 ERROR_RULE_IMPORT_UNREADABLE__REASON;

	/**
	 * @en {0} rules and {1} schedule entries imported.
	 */
	public static ResKey2 RULES_IMPORTED__RULES_SCHEDULES;

	/**
	 * @en The image holds {0} rules and {1} schedule entries.
	 */
	public static ResKey2 EXPORT_AUTOMATION__RULES_SCHEDULES;

	/**
	 * @en The controller would refuse this: {0}
	 */
	public static ResKey1 RULE_REFUSED__REASON;

	/**
	 * @en Not in the rule language: {0}
	 */
	public static ResKey1 RULE_SYNTAX__REASON;

	/**
	 * @en New datapoint
	 */
	public static ResKey NEW_DATAPOINT_LABEL;

	/**
	 * @en A datapoint id has 1 to 63 bytes without control characters.
	 */
	public static ResKey INVALID_DATAPOINT_ID;

	/**
	 * @en The id {0} is reserved for a datapoint the controller adds itself.
	 */
	public static ResKey1 DATAPOINT_ID_RESERVED__ID;

	/**
	 * @en The id is the one of the datapoint of the group address {0}.
	 */
	public static ResKey1 DATAPOINT_ID_OF_GROUP_ADDRESS__ADDRESS;

	/**
	 * @en Another application datapoint of the project has the id {0}.
	 */
	public static ResKey1 DATAPOINT_ID_DUPLICATE__ID;

	/**
	 * @en An id of the form main/middle/sub is reserved for the datapoints of group addresses.
	 */
	public static ResKey DATAPOINT_ID_ADDRESS_FORM;

	/**
	 * @en A unit has at most 15 bytes without control characters.
	 */
	public static ResKey INVALID_UNIT;

	/**
	 * @en The datapoint {0} is writable, but the controller refuses commands to the output of a schedule entry: make it not writable.
	 */
	public static ResKey1 OUTPUT_WRITABLE__ID;

	/**
	 * @en KNX
	 */
	public static ResKey BINDING_KNX;

	/**
	 * @en Application
	 */
	public static ResKey BINDING_APPLICATION;

	/**
	 * @en Rule parameter
	 */
	public static ResKey BINDING_RULE_PARAMETER;

	/**
	 * @en Zigbee
	 */
	public static ResKey BINDING_ZIGBEE;

	static {
		initConstants(I18NConstants.class);
	}
}
