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
	 * @en E-Home signed the image with the installation key {0}, and the device expects another key (its reason names it): compare the fingerprints in the tab Controller of the project; the owner accepts the project''s key on the device.
	 */
	public static ResKey1 DEPLOY_REFUSED_SIGNED__FINGERPRINT;

	/**
	 * @en The project has no installation key, so E-Home sent the image unsigned, and the device holds a key and accepts only images signed with it: create the project's key in the tab Controller and have the owner accept it on the device.
	 */
	public static ResKey DEPLOY_REFUSED_UNSIGNED;

	/**
	 * @en Signed with the installation key {0}.
	 */
	public static ResKey1 EXPORT_SIGNED__FINGERPRINT;

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
	 * @en {0} functions were proposed and added.
	 */
	public static ResKey1 PROPOSAL_ADDED__COUNT;

	/**
	 * @en The project has a function for every channel and Zigbee datapoint the proposal recognizes; nothing was added.
	 */
	public static ResKey PROPOSAL_NOTHING_NEW;

	/**
	 * @en New function
	 */
	public static ResKey NEW_FUNCTION_NAME;

	/**
	 * @en {0} is the datapoint "{1}" of the function "{2}": the controller offers what the button does there already. Delete the button; the proposal does not add it again.
	 */
	public static ResKey3 BUTTON_REPEATS_COMMAND__ADDRESS_ROLE_FUNCTION;

	/**
	 * @en {0}: scene {1}
	 */
	public static ResKey2 SCENE_FUNCTION__CHANNEL_NUMBER;

	/**
	 * @en {0} (endpoint {1})
	 */
	public static ResKey2 ZIGBEE_ENDPOINT__NAME_ENDPOINT;

	/**
	 * @en {0}: {1}
	 */
	public static ResKey2 UNUSED_FUNCTION__DEVICE_CHANNEL;

	/**
	 * @en {0} existing functions were updated: unused functions whose channel has its group address now got their datapoints, and generated names were replaced by the name of the group address.
	 */
	public static ResKey1 PROPOSAL_UPDATED__COUNT;

	/**
	 * @en {0} unused functions are not deployed: their main datapoint has no group address.
	 */
	public static ResKey1 EXPORT_UNUSED__COUNT;

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
	 * @en The stored image has {0} rules and {1} schedules that the new image does not contain; deploying removes them.
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
	 * @en The file has neither rules, schedule entries, datapoints nor a location (the members "rules", "schedules", "datapoints" and "settings" of a runtime image).
	 */
	public static ResKey ERROR_RULE_IMPORT_NOTHING;

	/**
	 * @en The image does not belong to this project: it names what the project does not have: {0}.
	 */
	public static ResKey1 ERROR_RULE_IMPORT_UNRESOLVED__IDS;

	/**
	 * @en The rules of the file cannot be read: {0}
	 */
	public static ResKey1 ERROR_RULE_IMPORT_UNREADABLE__REASON;

	/**
	 * @en {0} rules, {1} schedule entries and {2} datapoints imported.
	 */
	public static ResKey3 IMPORTED__RULES_SCHEDULES_DATAPOINTS;

	/**
	 * @en The datapoint {0} exists already in the project and is kept as it is.
	 */
	public static ResKey1 IMPORT_DATAPOINT_KEPT__ID;

	/**
	 * @en The rule {0} exists already in the project and is kept as it is.
	 */
	public static ResKey1 IMPORT_RULE_KEPT__ID;

	/**
	 * @en The schedule entry {0} exists already in the project and is kept as it is.
	 */
	public static ResKey1 IMPORT_SCHEDULE_KEPT__ID;

	/**
	 * @en {0} datapoints of KNX group addresses are not created: they come from the group addresses of the project.
	 */
	public static ResKey1 IMPORT_KNX_DATAPOINTS_SKIPPED__COUNT;

	/**
	 * @en The datapoint {0} is created without its Zigbee binding: the project has no Zigbee device {1}. Read the Zigbee devices of the controller and bind it in the tab "Zigbee".
	 */
	public static ResKey2 IMPORT_ZIGBEE_NO_DEVICE__ID_IEEE;

	/**
	 * @en The datapoint {0} is created without its Zigbee binding: the Zigbee device {1} has no function on endpoint {2}, cluster {3}, attribute {4}.
	 */
	public static ResKey5 IMPORT_ZIGBEE_NO_FUNCTION__ID_IEEE_ENDPOINT_CLUSTER_ATTRIBUTE;

	/**
	 * @en The datapoint {0} is created without its Zigbee binding: its function is bound to the datapoint {1} already.
	 */
	public static ResKey2 IMPORT_ZIGBEE_BOUND__ID_OTHER;

	/**
	 * @en The reporting of the Zigbee function of the datapoint {0} is kept as the project sets it ({1}); the file gives {2}.
	 */
	public static ResKey3 IMPORT_REPORTING_KEPT__ID_PROJECT_FILE;

	/**
	 * @en The location of the project is set to latitude {0}, longitude {1}.
	 */
	public static ResKey2 IMPORT_LOCATION_SET__LAT_LON;

	/**
	 * @en The location of the project is kept (latitude {0}, longitude {1}); the file gives latitude {2}, longitude {3}.
	 */
	public static ResKey4 IMPORT_LOCATION_KEPT__LAT_LON_FILE_LAT_FILE_LON;

	/**
	 * @en The time zone of the project is set to {0}.
	 */
	public static ResKey1 IMPORT_TIME_ZONE_SET__TZ;

	/**
	 * @en The time zone of the project is kept ({0}); the file gives {1}.
	 */
	public static ResKey2 IMPORT_TIME_ZONE_KEPT__TZ_FILE_TZ;

	/**
	 * @en A datapoint of the file has no id: datapoints[{0}].
	 */
	public static ResKey1 ERROR_IMPORT_DATAPOINT_NO_ID__INDEX;

	/**
	 * @en The file lists the datapoint {0} twice.
	 */
	public static ResKey1 ERROR_IMPORT_DATAPOINT_TWICE__ID;

	/**
	 * @en The datapoint {0} has the value type "{1}", which E-Home does not know.
	 */
	public static ResKey2 ERROR_IMPORT_DATAPOINT_TYPE__ID_TYPE;

	/**
	 * @en The datapoint {0} cannot be created: {1}
	 */
	public static ResKey2 ERROR_IMPORT_DATAPOINT_ID__ID_REASON;

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
	 * @en Schedule time
	 */
	public static ResKey BINDING_SCHEDULE_TIME;

	/**
	 * @en The controller reads the image schema up to {0}; this image is {1}: update the controller''s firmware first, or what is newer in the image (adjustable schedules) is refused.
	 */
	public static ResKey2 CONFIRM_SCHEMA_OLDER__READS_IMAGE;

	/**
	 * @en The image has {0} bytes, more than the {1} bytes the controller can store and load again: it will run, but may not survive a restart of the controller.
	 */
	public static ResKey2 CONFIRM_TOO_LARGE__BYTES_STORABLE;

	/**
	 * @en The controller does not report which image schema it reads (a firmware before schema 1.2); this image is {0}: update the controller''s firmware first, or what is newer in the image (adjustable schedules) is refused.
	 */
	public static ResKey1 CONFIRM_SCHEMA_UNKNOWN__IMAGE;

	/**
	 * @en Schedule entry {0}: a value of {1} the inhabitant set on the controller would be dropped, since {1} is no longer adjustable.
	 */
	public static ResKey2 COMPARE_OVERRIDE_NOT_ADJUSTABLE__ENTRY_MEMBER;

	/**
	 * @en Schedule entry {0}: a value of {1} the inhabitant set on the controller would be dropped if it does not fit the new bounds or step.
	 */
	public static ResKey2 COMPARE_OVERRIDE_NARROWED__ENTRY_MEMBER;

	/**
	 * @en Zigbee
	 */
	public static ResKey BINDING_ZIGBEE;

	/**
	 * @en New page
	 */
	public static ResKey NEW_PAGE_NAME;

	/**
	 * @en Another page has the id {0} already.
	 */
	public static ResKey1 PAGE_IDENTIFIER_DUPLICATE__ID;

	/**
	 * @en {0} has no history: the controller can draw no chart of it. A history is kept of numbers and truth values; one set by hand needs a retention and a spacing. Derive its history, or choose another datapoint.
	 */
	public static ResKey1 SERIES_NO_HISTORY__DATAPOINT;

	/**
	 * @en A consumption needs a number; a truth value is drawn as an on/off strip.
	 */
	public static ResKey CONSUMPTION_OF_BOOLEAN;

	/**
	 * @en The lowest value of the axis must be below its highest value.
	 */
	public static ResKey AXIS_MIN_NOT_BELOW_MAX;

	/**
	 * @en A reference line is drawn either at a value or at the value of a datapoint: set exactly one of them.
	 */
	public static ResKey REFERENCE_VALUE_OR_DATAPOINT;

	/**
	 * @en {0} is no number: a reference line follows the value of a datapoint of a number.
	 */
	public static ResKey1 REFERENCE_NOT_NUMERIC__DATAPOINT;

	/**
	 * @en The chart has no series; the image leaves it out.
	 */
	public static ResKey CHART_WITHOUT_SERIES;

	/**
	 * @en No function is in this room; the element shows nothing.
	 */
	public static ResKey ROOM_WITHOUT_FUNCTIONS;

	/**
	 * @en The group address has no datapoint type and is not part of the image; the element is left out.
	 */
	public static ResKey ADDRESS_WITHOUT_TYPE;

	/**
	 * @en Section {0}
	 */
	public static ResKey1 SECTION_NUMBER__N;

	/**
	 * @en Chart
	 */
	public static ResKey CHART_LABEL;

	/**
	 * @en Line at {0}
	 */
	public static ResKey1 REFERENCE_AT__VALUE;

	/**
	 * @en Choose a project first.
	 */
	public static ResKey NO_PROJECT;

	/**
	 * @en Select the page to add the section to, or add a page first.
	 */
	public static ResKey SELECT_PAGE;

	/**
	 * @en Select the section to add to, or add a section to the page first.
	 */
	public static ResKey SELECT_SECTION;

	/**
	 * @en Select the chart to add to.
	 */
	public static ResKey SELECT_CHART;

	/**
	 * @en This place cannot hold what is dragged: pages go into the project, sections into a page, elements into a section, series and reference lines into a chart.
	 */
	public static ResKey MOVE_NOT_HERE;

	/**
	 * @en The pages of another project cannot take it.
	 */
	public static ResKey MOVE_OTHER_PROJECT;

	/**
	 * @en Page "{0}", section {1}, element {2}: {3} is not part of the runtime image; the element is left out.
	 */
	public static ResKey4 NOTE_PAGE_ELEMENT_LEFT_OUT__PAGE_SECTION_ELEMENT_TARGET;

	/**
	 * @en Page "{0}", section {1}, element {2}: no function is in {3}; the element shows nothing.
	 */
	public static ResKey4 NOTE_PAGE_ROOM_EMPTY__PAGE_SECTION_ELEMENT_ROOM;

	/**
	 * @en Page "{0}", section {1}, element {2}, series {3}: {4} is not part of the runtime image; the series is left out.
	 */
	public static ResKey5 NOTE_PAGE_SERIES_LEFT_OUT__PAGE_SECTION_ELEMENT_SERIES_TARGET;

	/**
	 * @en Page "{0}", section {1}, element {2}, reference line {3}: {4} is not part of the runtime image; the line is left out.
	 */
	public static ResKey5 NOTE_PAGE_REFERENCE_LEFT_OUT__PAGE_SECTION_ELEMENT_REFERENCE_TARGET;

	/**
	 * @en Page "{0}", section {1}, element {2}: the chart has no series; it is left out.
	 */
	public static ResKey3 NOTE_PAGE_CHART_EMPTY__PAGE_SECTION_ELEMENT;

	/**
	 * @en No history.
	 */
	public static ResKey HISTORY_NONE;

	/**
	 * @en every {0} for {1}
	 */
	public static ResKey2 HISTORY_RAW__SPACING_RETENTION;

	/**
	 * @en {0} for {1}
	 */
	public static ResKey2 HISTORY_ARCHIVE__RESOLUTION_RETENTION;

	/**
	 * @en {0}; archives: {1}
	 */
	public static ResKey2 HISTORY_WITH_ARCHIVES__RAW_ARCHIVES;

	/**
	 * @en {0} ({1} bytes of RAM)
	 */
	public static ResKey2 HISTORY_BYTES__HISTORY_RAM;

	/**
	 * @en {0} ({1} bytes of RAM, {2} bytes of flash)
	 */
	public static ResKey3 HISTORY_BYTES__HISTORY_RAM_FLASH;

	/**
	 * @en No history: the controller keeps one only of numbers and truth values.
	 */
	public static ResKey HISTORY_NOT_POSSIBLE;

	/**
	 * @en No history: no chart of the control pages draws the datapoint.
	 */
	public static ResKey HISTORY_NOT_CHARTED;

	/**
	 * @en Set by hand: {0}
	 */
	public static ResKey1 HISTORY_MANUAL__HISTORY;

	/**
	 * @en Derived from the charts: {0}
	 */
	public static ResKey1 HISTORY_DERIVED__HISTORY;

	/**
	 * @en The histories need {0} bytes of RAM, more than the {1} bytes allowed. The biggest: {2}.
	 */
	public static ResKey3 HISTORY_USAGE_RAM_EXCEEDED__BYTES_LIMIT_BIGGEST;

	/**
	 * @en The histories need {0} bytes of RAM of the budget of {1} bytes (the controller allows {2}).
	 */
	public static ResKey3 HISTORY_USAGE_RAM__BYTES_BUDGET_CAP;

	/**
	 * @en The histories need {0} bytes of RAM of the {1} bytes the controller allows.
	 */
	public static ResKey2 HISTORY_USAGE_RAM__BYTES_CAP;

	/**
	 * @en The archives need {0} bytes of flash, more than the {1} bytes the controller allows. The biggest: {2}.
	 */
	public static ResKey3 HISTORY_USAGE_FLASH_EXCEEDED__BYTES_CAP_BIGGEST;

	/**
	 * @en The archives need {0} bytes of flash of the {1} bytes the controller allows.
	 */
	public static ResKey2 HISTORY_USAGE_FLASH__BYTES_CAP;

	/**
	 * @en To fit the controller, the histories of these datapoints are coarser than their charts ask for: {0}.
	 */
	public static ResKey1 HISTORY_USAGE_COARSENED__DATAPOINTS;

	/**
	 * @en A history has at most {0} archives.
	 */
	public static ResKey1 HISTORY_TOO_MANY_ARCHIVES__MAX;

	/**
	 * @en Archives need the raw ring: set its retention and spacing.
	 */
	public static ResKey HISTORY_ARCHIVES_WITHOUT_RAW;

	/**
	 * @en The resolution of an archive ({0}) must be coarser than the spacing of the raw ring ({1}).
	 */
	public static ResKey2 HISTORY_ARCHIVE_NOT_COARSER__RESOLUTION_SPACING;

	/**
	 * @en The retention of an archive is at least its resolution.
	 */
	public static ResKey HISTORY_ARCHIVE_RETENTION_BELOW_RESOLUTION;

	/**
	 * @en An archive is kept for at most 10 years.
	 */
	public static ResKey HISTORY_ARCHIVE_TOO_LONG;

	/**
	 * @en The archive would hold {0} slots; the controller keeps at most {1} per archive.
	 */
	public static ResKey2 HISTORY_TOO_MANY_SLOTS__SLOTS_MAX;

	/**
	 * @en The virtual device {0}: the datapoint {2} bound to its port ''{1}'' no longer exists (a re-import of the ETS project removed it); remove the binding or bind another datapoint.
	 */
	public static ResKey3 BLOCK_BINDING_DELETED__BLOCK_PORT_DATAPOINT;

	/**
	 * @en The virtual device {0}: its output ''{1}'' is bound to several datapoints; an output commands one.
	 */
	public static ResKey2 BLOCK_OUTPUT_SEVERAL__BLOCK_PORT;

	/**
	 * @en outputConflict: the output ''{1}'' of the virtual device {0} is bound to the group address {2}, on which the KNX object {3} sends: two sources would write the datapoint.
	 */
	public static ResKey4 BLOCK_OUTPUT_SENT_BY_OBJECT__BLOCK_PORT_ADDRESS_OBJECT;

	/**
	 * @en The output ''{1}'' of the virtual device {0} is bound to {2}, which the rule {3} writes too: both command it.
	 */
	public static ResKey4 NOTE_BLOCK_OUTPUT_WRITTEN_BY_RULE__BLOCK_PORT_DATAPOINT_RULE;

	/**
	 * @en Not deployed: the image has virtual devices, which need image schema 1.3; the controller {0} reads schema {1} and would run none of them. Update its firmware first.
	 */
	public static ResKey2 DEPLOY_REFUSED_BLOCKS_SCHEMA__CONTROLLER_SCHEMA;

	/**
	 * @en Not deployed: the image has virtual devices, which need image schema 1.3; the controller {0} reports no schema (a firmware reading at most 1.1) and would run none of them. Update its firmware first.
	 */
	public static ResKey1 DEPLOY_REFUSED_BLOCKS_NO_SCHEMA__CONTROLLER;

	/**
	 * @en Not deployed: the image has Zigbee remotes as buttons, which need image schema 1.4; the controller {0} reads schema {1} and would refuse the whole image. Update its firmware first.
	 */
	public static ResKey2 DEPLOY_REFUSED_PRESSES_SCHEMA__CONTROLLER_SCHEMA;

	/**
	 * @en Not deployed: the image has Zigbee remotes as buttons, which need image schema 1.4; the controller {0} reports no schema (a firmware reading at most 1.1) and would refuse the whole image. Update its firmware first.
	 */
	public static ResKey1 DEPLOY_REFUSED_PRESSES_NO_SCHEMA__CONTROLLER;

	/**
	 * @en {0} (deleted)
	 */
	public static ResKey1 BLOCK_BINDING_GONE__ID;

	/**
	 * @en input
	 */
	public static ResKey BLOCK_PORT_INPUT;

	/**
	 * @en output
	 */
	public static ResKey BLOCK_PORT_OUTPUT;

	/**
	 * @en required
	 */
	public static ResKey BLOCK_PORT_REQUIRED;

	/**
	 * @en diagnostic
	 */
	public static ResKey BLOCK_PORT_DIAGNOSTIC;

	/**
	 * @en up to {0} datapoints: {1}
	 */
	public static ResKey2 BLOCK_PORT_MULTIPLE__COUNT_AGGREGATIONS;

	/**
	 * @en yes
	 */
	public static ResKey BLOCK_VALUE_YES;

	/**
	 * @en no
	 */
	public static ResKey BLOCK_VALUE_NO;

	/**
	 * @en The value {0} does not fit the parameter {1}.
	 */
	public static ResKey2 BLOCK_VALUE_INVALID__VALUE_PARAMETER;

	/**
	 * @en The type of the virtual device has no parameter {0}.
	 */
	public static ResKey1 BLOCK_PARAMETER_UNKNOWN__PARAMETER;

	/**
	 * @en mean
	 */
	public static ResKey BLOCK_AGGREGATION_MEAN;

	/**
	 * @en lowest
	 */
	public static ResKey BLOCK_AGGREGATION_MIN;

	/**
	 * @en highest
	 */
	public static ResKey BLOCK_AGGREGATION_MAX;

	/**
	 * @en any of them
	 */
	public static ResKey BLOCK_AGGREGATION_ANY;

	/**
	 * @en all of them
	 */
	public static ResKey BLOCK_AGGREGATION_ALL;

	/**
	 * @en The port {0} binds a single datapoint and has no aggregation.
	 */
	public static ResKey1 BLOCK_AGGREGATION_NONE__PORT;

	/**
	 * @en The port {1} does not allow the aggregation {0}.
	 */
	public static ResKey2 BLOCK_AGGREGATION_INVALID__AGGREGATION_PORT;

	/**
	 * @en none (check that it is {0})
	 */
	public static ResKey1 BLOCK_BINDING_NO_UNIT__UNIT;

	/**
	 * @en Of them, {1} belong to its {0} virtual devices.
	 */
	public static ResKey2 EXPORT_BLOCKS__BLOCKS_DATAPOINTS;

	/**
	 * @en The project has no controller: the virtual device runs nowhere.
	 */
	public static ResKey BLOCK_DIAG_NO_CONTROLLER;

	/**
	 * @en The controller cannot be reached: {0}
	 */
	public static ResKey1 BLOCK_DIAG_UNREACHABLE__STATUS;

	/**
	 * @en Not deployed: the controller runs no image of the project. {0}
	 */
	public static ResKey1 BLOCK_DIAG_NOT_DEPLOYED__STATUS;

	/**
	 * @en Not deployed: the controller runs the image of another project. {0}
	 */
	public static ResKey1 BLOCK_DIAG_OTHER_PROJECT__STATUS;

	/**
	 * @en The controller did not tell the diagnostics (HTTP {0}): {1}
	 */
	public static ResKey2 BLOCK_DIAG_FAILED__STATUS_ERROR;

	/**
	 * @en Not deployed: the image the controller runs (revision {1}) has no virtual device {0}. Deploy the image.
	 */
	public static ResKey2 BLOCK_DIAG_NOT_IN_IMAGE__ID_REVISION;

	/**
	 * @en Runs on the controller (image revision {0}).
	 */
	public static ResKey1 BLOCK_DIAG_RUNNING__REVISION;

	/**
	 * @en Switched off on the controller (image revision {0}).
	 */
	public static ResKey1 BLOCK_DIAG_DISABLED__REVISION;

	/**
	 * @en Faulted {1} ago: {0}
	 */
	public static ResKey2 BLOCK_DIAG_FAULT__REASON_AGO;

	/**
	 * @en Status on the controller: {0}
	 */
	public static ResKey1 BLOCK_DIAG_STATUS__STATUS;

	/**
	 * @en The inhabitant switched it on.
	 */
	public static ResKey BLOCK_DIAG_SWITCHED_ON;

	/**
	 * @en The inhabitant switched it off.
	 */
	public static ResKey BLOCK_DIAG_SWITCHED_OFF;

	/**
	 * @en {0} calls since the start, {1} ms of computing time, the longest call {2} ms, {3} errors.
	 */
	public static ResKey4 BLOCK_DIAG_CALLS__CALLS_TIME_LONGEST_ERRORS;

	/**
	 * @en Last call: the start ({0}), {1} ago.
	 */
	public static ResKey2 BLOCK_DIAG_CALL_START__REASON_AGO;

	/**
	 * @en Last call: the input {0} changed, {1} ago.
	 */
	public static ResKey2 BLOCK_DIAG_CALL_INPUT__PORT_AGO;

	/**
	 * @en Last call: the timer {0} expired, {1} ago.
	 */
	public static ResKey2 BLOCK_DIAG_CALL_TIMER__TIMER_AGO;

	/**
	 * @en Last call: the period, {0} ago.
	 */
	public static ResKey1 BLOCK_DIAG_CALL_PERIOD__AGO;

	/**
	 * @en Last call: the parameter {0} changed, {1} ago.
	 */
	public static ResKey2 BLOCK_DIAG_CALL_PARAMETER__PARAMETER_AGO;

	/**
	 * @en Last call: {0}, {1} ago.
	 */
	public static ResKey2 BLOCK_DIAG_CALL__KIND_AGO;

	/**
	 * @en Last error {2} ago: {0} {1}
	 */
	public static ResKey3 BLOCK_DIAG_LAST_ERROR__CODE_TEXT_AGO;

	/**
	 * @en Outputs:
	 */
	public static ResKey BLOCK_DIAG_OUTPUTS;

	/**
	 * @en Inputs:
	 */
	public static ResKey BLOCK_DIAG_INPUTS;

	/**
	 * @en unknown
	 */
	public static ResKey BLOCK_DIAG_UNKNOWN;

	/**
	 * @en {0} (stale)
	 */
	public static ResKey1 BLOCK_DIAG_STALE__VALUE;

	/**
	 * @en {0} in {1}
	 */
	public static ResKey2 BLOCK_DIAG_TIMER__TIMER_IN;

	/**
	 * @en Timers: {0}
	 */
	public static ResKey1 BLOCK_DIAG_TIMERS__TIMERS;

	/**
	 * @en The inhabitant set {0} to {1} (the image: {2}).
	 */
	public static ResKey3 BLOCK_DIAG_OVERRIDDEN__PARAMETER_VALUE_IMAGE;

	/**
	 * @en State: {0}
	 */
	public static ResKey1 BLOCK_DIAG_STATE__FIELDS;

	/**
	 * @en Delete {0} "{1}"? Nothing in the project refers to it.
	 */
	public static ResKey2 CONFIRM_DELETE__KIND_NAME;

	/**
	 * @en Delete {0} "{1}"? One part of the project refers to it:
	 */
	public static ResKey2 CONFIRM_DELETE_REFERRED_ONCE__KIND_NAME;

	/**
	 * @en Delete {0} "{1}"? {2} parts of the project refer to it:
	 */
	public static ResKey3 CONFIRM_DELETE_REFERRED__KIND_NAME_COUNT;

	/**
	 * @en They lose the reference, and the next deployment refuses them until they refer to something else or are deleted as well.
	 */
	public static ResKey CONFIRM_DELETE_REFERRED_CONSEQUENCE;

	/**
	 * @en {0} "{1}": {2}
	 */
	public static ResKey3 REFERER__KIND_NAME_REFERENCES;

	/**
	 * @en The image has {0} bytes, the limit is {1}.
	 */
	public static ResKey2 IMAGE_TOO_LARGE__BYTES_LIMIT;

	/**
	 * @en The image is not JSON: {0} at character {1}.
	 */
	public static ResKey2 IMAGE_NOT_JSON__ERROR_OFFSET;

	/**
	 * @en The image must be a JSON object.
	 */
	public static ResKey IMAGE_NOT_A_JSON_OBJECT;

	/**
	 * @en The member image (schemaVersion, projectId, revision) is missing.
	 */
	public static ResKey IMAGE_HEADER_MISSING;

	/**
	 * @en {0} is missing.
	 */
	public static ResKey1 IMAGE_MISSING__MEMBER;

	/**
	 * @en The schemaVersion "{0}" is not of the form major.minor with a major version from 1.
	 */
	public static ResKey1 IMAGE_VERSION_MALFORMED__VERSION;

	/**
	 * @en The schemaVersion {0} is newer than this firmware reads (up to {1}): update the firmware.
	 */
	public static ResKey2 IMAGE_VERSION_NEWER__VERSION_SUPPORTED;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: it is empty.
	 */
	public static ResKey1 IMAGE_TZ_EMPTY__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: zone files (":name") are not supported, give a POSIX TZ rule.
	 */
	public static ResKey1 IMAGE_TZ_ZONE_FILE__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: the name of the standard zone must have 3 or more letters or be written in angle brackets.
	 */
	public static ResKey1 IMAGE_TZ_STANDARD_NAME__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: the offset of the standard zone is missing or invalid ([+-]hh[:mm[:ss]], hh at most 24).
	 */
	public static ResKey1 IMAGE_TZ_STANDARD_OFFSET__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: the name of the daylight saving zone must have 3 or more letters or be written in angle brackets.
	 */
	public static ResKey1 IMAGE_TZ_DAYLIGHT_NAME__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: the offset of the daylight saving zone is invalid ([+-]hh[:mm[:ss]], hh at most 24).
	 */
	public static ResKey1 IMAGE_TZ_DAYLIGHT_OFFSET__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: a zone with daylight saving time needs both transition rules (,start,end).
	 */
	public static ResKey1 IMAGE_TZ_RULES__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: the start rule is invalid (Jn, n or Mm.w.d, optional /time).
	 */
	public static ResKey1 IMAGE_TZ_START_RULE__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: the end rule is missing.
	 */
	public static ResKey1 IMAGE_TZ_END_MISSING__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: the end rule is invalid (Jn, n or Mm.w.d, optional /time).
	 */
	public static ResKey1 IMAGE_TZ_END_RULE__TZ;

	/**
	 * @en The time zone "{0}" is not a POSIX TZ rule: unexpected characters follow the end rule.
	 */
	public static ResKey1 IMAGE_TZ_TRAILING__TZ;

	/**
	 * @en The server "{0}" is not a host name: it has an empty part between dots.
	 */
	public static ResKey1 IMAGE_HOST_EMPTY_LABEL__SERVER;

	/**
	 * @en The server "{0}" is not a host name: it may contain only letters, digits, "-" and ".".
	 */
	public static ResKey1 IMAGE_HOST_CHARACTER__SERVER;

	/**
	 * @en The server "{0}" is not a host name: a part between the dots is longer than 63 characters.
	 */
	public static ResKey1 IMAGE_HOST_LONG_LABEL__SERVER;

	/**
	 * @en The server "{0}" is not a host name: it ends with a dot.
	 */
	public static ResKey1 IMAGE_HOST_TRAILING_DOT__SERVER;

	/**
	 * @en The location needs lat and lon.
	 */
	public static ResKey IMAGE_LOCATION_NEEDS_LAT_LON;

	/**
	 * @en The NTP setting needs a server.
	 */
	public static ResKey IMAGE_NTP_NEEDS_SERVER;

	/**
	 * @en The time master names no address (time, date, dateTime).
	 */
	public static ResKey IMAGE_TIME_MASTER_NO_ADDRESS;

	/**
	 * @en {0} and {1} of the time master use the same group address.
	 */
	public static ResKey2 IMAGE_TIME_MASTER_SAME_ADDRESS__FIRST_SECOND;

	/**
	 * @en The address of the time master is the sending address of datapoints[{0}] "{1}".
	 */
	public static ResKey2 IMAGE_TIME_MASTER_ADDRESS_SENT__INDEX_ID;

	/**
	 * @en {0} datapoints exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_DATAPOINTS__COUNT_LIMIT;

	/**
	 * @en {0} locations exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_LOCATIONS__COUNT_LIMIT;

	/**
	 * @en {0} functions exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_FUNCTIONS__COUNT_LIMIT;

	/**
	 * @en {0} Zigbee bindings exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_ZIGBEE_BINDINGS__COUNT_LIMIT;

	/**
	 * @en {0} pages exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_PAGES__COUNT_LIMIT;

	/**
	 * @en {0} sections of the pages exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_SECTIONS__COUNT_LIMIT;

	/**
	 * @en {0} elements of the pages exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_ELEMENTS__COUNT_LIMIT;

	/**
	 * @en {0} chart series exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_CHART_SERIES__COUNT_LIMIT;

	/**
	 * @en {0} reference lines exceed the limit of {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_REFERENCE_LINES__COUNT_LIMIT;

	/**
	 * @en The id "{0}" is used twice (also datapoints[{1}]).
	 */
	public static ResKey2 IMAGE_DUPLICATE_DATAPOINT_ID__ID_INDEX;

	/**
	 * @en The id "{0}" is reserved for the datapoints of knx.timeMaster.
	 */
	public static ResKey1 IMAGE_ID_RESERVED_TIME_MASTER__ID;

	/**
	 * @en The id "{0}" begins with "{1}", which is reserved for the datapoints of rule parameters.
	 */
	public static ResKey2 IMAGE_ID_RESERVED_RULES__ID_PREFIX;

	/**
	 * @en The id "{0}" begins with "{1}", which is reserved for the datapoints of the schedules.
	 */
	public static ResKey2 IMAGE_ID_RESERVED_SCHEDULES__ID_PREFIX;

	/**
	 * @en The id "{0}" begins with "{1}", which is reserved for the datapoints of the virtual devices.
	 */
	public static ResKey2 IMAGE_ID_RESERVED_BLOCKS__ID_PREFIX;

	/**
	 * @en Unknown type "{0}" ({1}).
	 */
	public static ResKey2 IMAGE_UNKNOWN_TYPE__TYPE_TYPES;

	/**
	 * @en A datapoint is either zigbee or virtual.
	 */
	public static ResKey IMAGE_ZIGBEE_OR_VIRTUAL;

	/**
	 * @en A datapoint is either knx or zigbee.
	 */
	public static ResKey IMAGE_KNX_OR_ZIGBEE;

	/**
	 * @en A datapoint is either knx or virtual.
	 */
	public static ResKey IMAGE_KNX_OR_VIRTUAL;

	/**
	 * @en "{0}" is not a datapoint type (expected e.g. 1.001, 9.001).
	 */
	public static ResKey1 IMAGE_NOT_A_DPT__TEXT;

	/**
	 * @en DPT {0}: 6.020 (status with mode) is not implemented.
	 */
	public static ResKey1 IMAGE_DPT_6020_UNSUPPORTED__DPT;

	/**
	 * @en DPT {0}: of the main type 232 only 232.600 (RGB) is implemented.
	 */
	public static ResKey1 IMAGE_DPT_232_UNSUPPORTED__DPT;

	/**
	 * @en DPT {0}: of the main type 251 only 251.600 (RGBW) is implemented.
	 */
	public static ResKey1 IMAGE_DPT_251_UNSUPPORTED__DPT;

	/**
	 * @en DPT {0}: the main type is not implemented.
	 */
	public static ResKey1 IMAGE_DPT_MAIN_UNSUPPORTED__DPT;

	/**
	 * @en DPT {0} carries {1} values, the datapoint is {2}.
	 */
	public static ResKey3 IMAGE_DPT_TYPE_MISMATCH__DPT_CARRIED_TYPE;

	/**
	 * @en A datapoint has more than {0} listening addresses.
	 */
	public static ResKey1 IMAGE_TOO_MANY_LISTEN__MAX;

	/**
	 * @en A KNX datapoint needs a sending or a listening group address.
	 */
	public static ResKey IMAGE_KNX_NEEDS_ADDRESS;

	/**
	 * @en A transmitting or readable KNX datapoint needs a sending address (send).
	 */
	public static ResKey IMAGE_KNX_NEEDS_SEND;

	/**
	 * @en The value "{1}" of {0} is not a group address: it has more than three levels.
	 */
	public static ResKey2 IMAGE_ADDRESS_LEVELS__PATH_TEXT;

	/**
	 * @en The value "{1}" of {0} is not a group address: a level is not a number.
	 */
	public static ResKey2 IMAGE_ADDRESS_NUMBER__PATH_TEXT;

	/**
	 * @en The value "{1}" of {0} is not a group address: a group address has two or three levels (1/2/3 or 1/515).
	 */
	public static ResKey2 IMAGE_ADDRESS_ONE_LEVEL__PATH_TEXT;

	/**
	 * @en The value "{1}" of {0} is not a group address: the main group must be 0 to 31.
	 */
	public static ResKey2 IMAGE_ADDRESS_MAIN__PATH_TEXT;

	/**
	 * @en The value "{1}" of {0} is not a group address: the sub group of a two-level address must be 0 to 2047.
	 */
	public static ResKey2 IMAGE_ADDRESS_SUB_TWO_LEVELS__PATH_TEXT;

	/**
	 * @en The value "{1}" of {0} is not a group address: the middle group must be 0 to 7.
	 */
	public static ResKey2 IMAGE_ADDRESS_MIDDLE__PATH_TEXT;

	/**
	 * @en The value "{1}" of {0} is not a group address: the sub group must be 0 to 255.
	 */
	public static ResKey2 IMAGE_ADDRESS_SUB__PATH_TEXT;

	/**
	 * @en "{0}" is not an IEEE address (16 hex digits, colons allowed; not 0 and not all ones).
	 */
	public static ResKey1 IMAGE_NOT_IEEE__TEXT;

	/**
	 * @en The attribute is missing; only a client binding (the commands of a button) has none.
	 */
	public static ResKey IMAGE_ZIGBEE_ATTRIBUTE_MISSING;

	/**
	 * @en Client cluster {0}: a client binding (button commands) needs cluster 6 (On/Off) or 8 (Level Control).
	 */
	public static ResKey1 IMAGE_ZIGBEE_CLIENT_CLUSTER__CLUSTER;

	/**
	 * @en Client cluster {0}: a client binding (button commands) cannot be commanded.
	 */
	public static ResKey1 IMAGE_ZIGBEE_CLIENT_COMMANDED__CLUSTER;

	/**
	 * @en Cluster {0} attribute {1}: no function is known for this cluster and attribute (doc/zigbee.md lists them).
	 */
	public static ResKey2 IMAGE_ZIGBEE_UNKNOWN_FUNCTION__CLUSTER_ATTRIBUTE;

	/**
	 * @en Cluster {0} attribute {1}: the command cluster cannot set this attribute.
	 */
	public static ResKey2 IMAGE_ZIGBEE_CANNOT_SET__CLUSTER_ATTRIBUTE;

	/**
	 * @en The function {0} carries {1} values, the datapoint is {2}.
	 */
	public static ResKey3 IMAGE_ZIGBEE_TYPE_MISMATCH__FUNCTION_CARRIED_TYPE;

	/**
	 * @en A transmitting Zigbee datapoint needs the cluster its commands go to (command); a client binding (button commands) cannot be commanded.
	 */
	public static ResKey IMAGE_ZIGBEE_TRANSMIT_CLIENT;

	/**
	 * @en A transmitting Zigbee datapoint needs the cluster its commands go to (command); without it the binding is read-only.
	 */
	public static ResKey IMAGE_ZIGBEE_TRANSMIT;

	/**
	 * @en Device {0} endpoint {1}: the function {2} is bound twice (also datapoints[{3}] "{4}").
	 */
	public static ResKey5 IMAGE_ZIGBEE_BOUND_TWICE__DEVICE_ENDPOINT_FUNCTION_INDEX_NAME;

	/**
	 * @en A history needs retention and spacing (seconds).
	 */
	public static ResKey IMAGE_HISTORY_NEEDS_RETENTION_SPACING;

	/**
	 * @en Retention / spacing gives more than {0} samples.
	 */
	public static ResKey1 IMAGE_HISTORY_TOO_MANY_SAMPLES__MAX;

	/**
	 * @en A history needs a boolean, unsigned, signed or real datapoint.
	 */
	public static ResKey IMAGE_HISTORY_TYPE;

	/**
	 * @en A history has more than {0} archives.
	 */
	public static ResKey1 IMAGE_TOO_MANY_ARCHIVES__MAX;

	/**
	 * @en An archive needs resolution and retention (seconds).
	 */
	public static ResKey IMAGE_ARCHIVE_NEEDS_RESOLUTION_RETENTION;

	/**
	 * @en The resolution must be larger than the spacing of the raw values ({0} s).
	 */
	public static ResKey1 IMAGE_ARCHIVE_RESOLUTION__SPACING;

	/**
	 * @en Two archives have the resolution {0} s.
	 */
	public static ResKey1 IMAGE_ARCHIVE_SAME_RESOLUTION__RESOLUTION;

	/**
	 * @en Retention / resolution gives more than {0} slots.
	 */
	public static ResKey1 IMAGE_ARCHIVE_TOO_MANY_SLOTS__MAX;

	/**
	 * @en The location id "{0}" is used twice.
	 */
	public static ResKey1 IMAGE_DUPLICATE_LOCATION_ID__ID;

	/**
	 * @en Unknown location kind "{0}" (building, floor, room, area, outside).
	 */
	public static ResKey1 IMAGE_UNKNOWN_LOCATION_KIND__KIND;

	/**
	 * @en Unknown location "{0}".
	 */
	public static ResKey1 IMAGE_UNKNOWN_LOCATION__ID;

	/**
	 * @en The parents of the locations form a cycle.
	 */
	public static ResKey IMAGE_LOCATION_CYCLE;

	/**
	 * @en The id "{1}" ({0}) must have 1 to 32 characters of A-Z a-z 0-9 _ . -.
	 */
	public static ResKey2 IMAGE_ID_PATTERN__PATH_ID;

	/**
	 * @en The function id "{0}" is used twice.
	 */
	public static ResKey1 IMAGE_DUPLICATE_FUNCTION_ID__ID;

	/**
	 * @en Unknown function kind "{0}" (light, dimmer, blind, switch, sensor, climate, scene, button).
	 */
	public static ResKey1 IMAGE_UNKNOWN_FUNCTION_KIND__KIND;

	/**
	 * @en A scene function needs scene (1 to 64).
	 */
	public static ResKey IMAGE_SCENE_NEEDS_NUMBER;

	/**
	 * @en The datapoints (the roles of a {0}) are missing.
	 */
	public static ResKey1 IMAGE_FUNCTION_ROLES_MISSING__KIND;

	/**
	 * @en A {0} needs the role {1}.
	 */
	public static ResKey2 IMAGE_ROLE_MISSING__KIND_ROLE;

	/**
	 * @en Unknown datapoint "{0}".
	 */
	public static ResKey1 IMAGE_UNKNOWN_DATAPOINT__NAME;

	/**
	 * @en The datapoint "{0}" is {1}, but the role {2} of a {3} takes {4}.
	 */
	public static ResKey5 IMAGE_ROLE_TYPE__NAME_TYPE_ROLE_KIND_TYPES;

	/**
	 * @en The datapoint "{0}" has no valid type, but the role {1} of a {2} takes {3}.
	 */
	public static ResKey4 IMAGE_ROLE_TYPE_NONE__NAME_ROLE_KIND_TYPES;

	/**
	 * @en The role {0} needs a writable datapoint; "{1}" is not.
	 */
	public static ResKey2 IMAGE_ROLE_WRITABLE__ROLE_NAME;

	/**
	 * @en An enumeration press needs a Zigbee action datapoint (a client binding), whose elements are known; "{0}" is not one.
	 */
	public static ResKey1 IMAGE_PRESS_NEEDS_ACTION__NAME;

	/**
	 * @en The presses are only for an enumeration press; "{0}" is {1}.
	 */
	public static ResKey2 IMAGE_PRESSES_NOT_ENUMERATION__NAME_TYPE;

	/**
	 * @en The presses are only for an enumeration press; "{0}" has no valid type.
	 */
	public static ResKey1 IMAGE_PRESSES_NOT_ENUMERATION_NONE__NAME;

	/**
	 * @en A button with an enumeration press needs presses (which elements are which press).
	 */
	public static ResKey IMAGE_PRESSES_MISSING;

	/**
	 * @en The presses must be an object of press kinds (press, on, off, up, down, release) to arrays of elements.
	 */
	public static ResKey IMAGE_PRESSES_MALFORMED;

	/**
	 * @en Unknown member "{0}".
	 */
	public static ResKey1 IMAGE_UNKNOWN_MEMBER__NAME;

	/**
	 * @en Unknown member "{0}": the schema {1} of the image is newer than this firmware reads (up to {2}); update the firmware.
	 */
	public static ResKey3 IMAGE_UNKNOWN_MEMBER_NEWER__NAME_SCHEMA_SUPPORTED;

	/**
	 * @en The press {0} must be a non-empty array of elements.
	 */
	public static ResKey1 IMAGE_PRESS_ELEMENTS__KIND;

	/**
	 * @en The presses name no press.
	 */
	public static ResKey IMAGE_PRESSES_NONE;

	/**
	 * @en An element must be an integer.
	 */
	public static ResKey IMAGE_ELEMENT_NOT_INTEGER;

	/**
	 * @en The element {0} is no element of "{1}" (a Zigbee action: 0 to {2}).
	 */
	public static ResKey3 IMAGE_ELEMENT_RANGE__ELEMENT_NAME_MAX;

	/**
	 * @en The element {0} is a press of {1} already.
	 */
	public static ResKey2 IMAGE_ELEMENT_TAKEN__ELEMENT_KIND;

	/**
	 * @en The page id "{0}" is used twice.
	 */
	public static ResKey1 IMAGE_DUPLICATE_PAGE_ID__ID;

	/**
	 * @en The icon "{0}" must have 1 to {1} characters of A-Z a-z 0-9 _ . -.
	 */
	public static ResKey2 IMAGE_ICON_PATTERN__ICON_MAX;

	/**
	 * @en An element names exactly one of function, datapoint or chart; this one names none.
	 */
	public static ResKey IMAGE_ELEMENT_KIND_NONE;

	/**
	 * @en An element names exactly one of function, datapoint or chart; this one names several.
	 */
	public static ResKey IMAGE_ELEMENT_KIND_SEVERAL;

	/**
	 * @en The member readOnly applies to a datapoint element only.
	 */
	public static ResKey IMAGE_READ_ONLY_DATAPOINT;

	/**
	 * @en Unknown function "{0}".
	 */
	public static ResKey1 IMAGE_UNKNOWN_FUNCTION__ID;

	/**
	 * @en The width must be 1, 2, 3, 4 (columns) or "full".
	 */
	public static ResKey IMAGE_CHART_WIDTH;

	/**
	 * @en The height must be 2 or 4 (tile rows).
	 */
	public static ResKey IMAGE_CHART_HEIGHT;

	/**
	 * @en The preset {0} is listed twice.
	 */
	public static ResKey1 IMAGE_PRESET_TWICE__PRESET;

	/**
	 * @en The presets must name at least one range (or be left out: the range alone).
	 */
	public static ResKey IMAGE_PRESETS_EMPTY;

	/**
	 * @en The presets do not offer the range {0} of the chart.
	 */
	public static ResKey1 IMAGE_PRESETS_LACK_RANGE__RANGE;

	/**
	 * @en A chart needs its series (an empty array draws an empty chart).
	 */
	public static ResKey IMAGE_CHART_NEEDS_SERIES;

	/**
	 * @en Unknown {0} "{1}" ({2}).
	 */
	public static ResKey3 IMAGE_UNKNOWN_CHOICE__MEMBER_NAME_CHOICES;

	/**
	 * @en The min of an axis must be less than its max.
	 */
	public static ResKey IMAGE_AXIS_MIN_MAX;

	/**
	 * @en A series needs its datapoint.
	 */
	public static ResKey IMAGE_SERIES_NEEDS_DATAPOINT;

	/**
	 * @en The datapoint "{0}" keeps no history: a series needs one.
	 */
	public static ResKey1 IMAGE_SERIES_NO_HISTORY__NAME;

	/**
	 * @en The consumption needs a numeric datapoint; "{0}" is boolean.
	 */
	public static ResKey1 IMAGE_CONSUMPTION_NUMERIC__NAME;

	/**
	 * @en A reference line has exactly one of value (a constant) or datapoint (its current value).
	 */
	public static ResKey IMAGE_REFERENCE_ONE;

	/**
	 * @en A reference line needs a numeric datapoint; "{0}" is {1}.
	 */
	public static ResKey2 IMAGE_REFERENCE_NUMERIC__NAME_TYPE;

	/**
	 * @en A reference line needs a numeric datapoint; "{0}" has no valid type.
	 */
	public static ResKey1 IMAGE_REFERENCE_NUMERIC_NONE__NAME;

	/**
	 * @en The histories need {0} bytes of RAM, the device allows at most {1}; the biggest, in bytes: {2}.
	 */
	public static ResKey3 IMAGE_HISTORY_RAM__NEED_CAP_BIGGEST;

	/**
	 * @en The archives need {0} bytes of flash, the device allows at most {1}; the biggest, in bytes: {2}.
	 */
	public static ResKey3 IMAGE_HISTORY_FLASH__NEED_CAP_BIGGEST;

	/**
	 * @en The image asks for {0} bytes of history RAM, the device allows at most {1}.
	 */
	public static ResKey2 IMAGE_HISTORY_BUDGET_CAP__BYTES_CAP;

	/**
	 * @en The image asks for {0} bytes of archive flash, the device allows at most {1}.
	 */
	public static ResKey2 IMAGE_ARCHIVE_BUDGET_CAP__BYTES_CAP;

	/**
	 * @en The rings and archives need {0} bytes of RAM, the budget of the image is {1}; the biggest, in bytes: {2}.
	 */
	public static ResKey3 IMAGE_HISTORY_OVER_BUDGET__NEED_BUDGET_BIGGEST;

	/**
	 * @en The archives need {0} bytes of flash, the archive budget of the image is {1}; the biggest, in bytes: {2}.
	 */
	public static ResKey3 IMAGE_ARCHIVE_OVER_BUDGET__NEED_BUDGET_BIGGEST;

	/**
	 * @en {0} must be an object.
	 */
	public static ResKey1 IMAGE_NOT_AN_OBJECT__PATH;

	/**
	 * @en {0} must be an array.
	 */
	public static ResKey1 IMAGE_NOT_AN_ARRAY__PATH;

	/**
	 * @en {0} must be a string.
	 */
	public static ResKey1 IMAGE_NOT_A_STRING__PATH;

	/**
	 * @en {0} must have {1} to {2} bytes.
	 */
	public static ResKey3 IMAGE_STRING_LENGTH__PATH_MIN_MAX;

	/**
	 * @en {0} contains a control character.
	 */
	public static ResKey1 IMAGE_CONTROL_CHARACTER__PATH;

	/**
	 * @en {0} must be an integer.
	 */
	public static ResKey1 IMAGE_NOT_AN_INTEGER__PATH;

	/**
	 * @en {0} must be {1} to {2}.
	 */
	public static ResKey3 IMAGE_RANGE__PATH_MIN_MAX;

	/**
	 * @en {0} must be {1} to {2} seconds.
	 */
	public static ResKey3 IMAGE_RANGE_SECONDS__PATH_MIN_MAX;

	/**
	 * @en {0} must be {1} to {2} bytes.
	 */
	public static ResKey3 IMAGE_RANGE_BYTES__PATH_MIN_MAX;

	/**
	 * @en {0} must be a number.
	 */
	public static ResKey1 IMAGE_NOT_A_NUMBER__PATH;

	/**
	 * @en {0} must be true or false.
	 */
	public static ResKey1 IMAGE_NOT_A_FLAG__PATH;

	/**
	 * @en The label {0} must be a string or an object of language tags to strings.
	 */
	public static ResKey1 IMAGE_LABEL_MALFORMED__PATH;

	/**
	 * @en The language tag "{1}" of the label {0} must have 2 to 8 characters of A-Z a-z 0-9 -.
	 */
	public static ResKey2 IMAGE_LABEL_LANGUAGE_TAG__PATH_TAG;

	/**
	 * @en The label {0} names no language.
	 */
	public static ResKey1 IMAGE_LABEL_NO_LANGUAGE__PATH;

	/**
	 * @en Rule "{0}": {1}
	 */
	public static ResKey2 IMAGE_IN_RULE__RULE_MESSAGE;

	/**
	 * @en Virtual device "{0}": {1}
	 */
	public static ResKey2 IMAGE_IN_BLOCK__BLOCK_MESSAGE;

	/**
	 * @en {0} The schema {1} of the image is newer than this firmware reads (up to {2}); update the firmware.
	 */
	public static ResKey3 IMAGE_LOADER_NEWER__MESSAGE_SCHEMA_SUPPORTED;

	/**
	 * @en {0} An example of a POSIX TZ rule: CET-1CEST,M3.5.0,M10.5.0/3.
	 */
	public static ResKey1 INVALID_TIME_ZONE__PROBLEM;

	/**
	 * @en The time zone is longer than 127 characters.
	 */
	public static ResKey INVALID_TIME_ZONE_TOO_LONG;

	/**
	 * @en The host name is longer than 127 characters.
	 */
	public static ResKey INVALID_HOST_NAME_TOO_LONG;

	static {
		initConstants(I18NConstants.class);
	}
}
