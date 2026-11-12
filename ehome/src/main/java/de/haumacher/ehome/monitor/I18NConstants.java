/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

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
	 * @en "{0}" is no datapoint type; write it like 9.001.
	 */
	public static ResKey1 ERROR_INVALID_DATAPOINT_TYPE__TEXT;

	/**
	 * @en Enter a value to send.
	 */
	public static ResKey ERROR_NO_VALUE;

	/**
	 * @en Values of the datapoint type {0} cannot be encoded; send the data as hexadecimal bytes.
	 */
	public static ResKey1 ERROR_UNSUPPORTED_DATAPOINT_TYPE__TYPE;

	/**
	 * @en "{0}" is no value of the datapoint type {1}: {2}
	 */
	public static ResKey3 ERROR_INVALID_VALUE__VALUE_TYPE_DETAIL;

	/**
	 * @en "{0}" lies outside the range of the datapoint type {1}.
	 */
	public static ResKey2 ERROR_OUT_OF_RANGE__VALUE_TYPE;

	/**
	 * @en The data {0} is no valid value of the datapoint type {1}.
	 */
	public static ResKey2 ERROR_INVALID_DATA__DATA_TYPE;

	/**
	 * @en The data {0} does not have the length of the datapoint type {1}.
	 */
	public static ResKey2 ERROR_WRONG_LENGTH__DATA_TYPE;

	/**
	 * @en No value of {0}
	 */
	public static ResKey1 DECODED_INVALID__TYPE;

	/**
	 * @en Wrong length for {0}
	 */
	public static ResKey1 DECODED_WRONG_LENGTH__TYPE;

	/**
	 * @en Choose the size of the step.
	 */
	public static ResKey ERROR_NO_STEP;

	/**
	 * @en "{0}" is no step size; choose a step code of 1 (100 %) to 7 (1.56 %).
	 */
	public static ResKey1 ERROR_INVALID_STEP__VALUE;

	/**
	 * @en A step goes up or down, or stops.
	 */
	public static ResKey ERROR_NO_DIRECTION;

	/**
	 * @en "{0}" is no day of the week.
	 */
	public static ResKey1 ERROR_INVALID_DAY__VALUE;

	/**
	 * @en No day
	 */
	public static ResKey NO_DAY;

	/**
	 * @en "{0}" are no hexadecimal bytes (e.g. 0C 33).
	 */
	public static ResKey1 ERROR_INVALID_HEX__VALUE;

	/**
	 * @en The project has no KNX gateway; choose one with "Choose the gateway".
	 */
	public static ResKey ERROR_NO_GATEWAY;

	/**
	 * @en The gateway "{0}" is no address (address or address:port).
	 */
	public static ResKey1 ERROR_INVALID_GATEWAY__TEXT;

	/**
	 * @en No connection to the gateway {0}: {1}
	 */
	public static ResKey2 ERROR_CONNECT_FAILED__GATEWAY_DETAIL;

	/**
	 * @en The monitor is not connected to a gateway.
	 */
	public static ResKey ERROR_NOT_CONNECTED;

	/**
	 * @en Sending is not allowed; allow it first with "Allow sending".
	 */
	public static ResKey ERROR_SENDING_DISABLED;

	/**
	 * @en Sending failed: {0}
	 */
	public static ResKey1 ERROR_SEND_FAILED__DETAIL;

	/**
	 * @en Enter a group address or choose one in the state board.
	 */
	public static ResKey ERROR_NO_ADDRESS;

	/**
	 * @en A recording is already running.
	 */
	public static ResKey ERROR_RECORDING_RUNNING;

	/**
	 * @en Not connected.
	 */
	public static ResKey STATUS_DISCONNECTED;

	/**
	 * @en Connected to the gateway {0} as {1}, {2} telegrams received.
	 */
	public static ResKey3 STATUS_CONNECTED__GATEWAY_ADDRESS_COUNT;

	/**
	 * @en Connected to the gateway {0}, {1} telegrams received.
	 */
	public static ResKey2 STATUS_CONNECTED__GATEWAY_COUNT;

	/**
	 * @en The connection to the gateway {0} was lost: {1}
	 */
	public static ResKey2 STATUS_LOST__GATEWAY_REASON;

	/**
	 * @en Sending is allowed: "Send" and "Read" write to the bus.
	 */
	public static ResKey SENDING_ALLOWED;

	/**
	 * @en Sending is locked; the monitor only listens.
	 */
	public static ResKey SENDING_LOCKED;

	/**
	 * @en Recording "{0}" runs: {1} entries (telegrams and events).
	 */
	public static ResKey2 RECORDING_RUNNING__NAME_COUNT;

	/**
	 * @en No recording runs.
	 */
	public static ResKey RECORDING_NONE;

	/**
	 * @en Recording of {0}
	 */
	public static ResKey1 DEFAULT_RECORDING_NAME__TIME;

	/**
	 * @en The log is paused; the state board goes on.
	 */
	public static ResKey LOG_PAUSED;

	/**
	 * @en The project does not know the address {0}: enter its datapoint type (e.g. 1.001), or send the data as hexadecimal bytes.
	 */
	public static ResKey1 TARGET_UNKNOWN__ADDRESS;

	/**
	 * @en {0} {1}: datapoint type {2}, current value {3}.
	 */
	public static ResKey4 TARGET__ADDRESS_NAME_TYPE_VALUE;

	/**
	 * @en 0 sends "{0}", 1 sends "{1}".
	 */
	public static ResKey2 TARGET_VALUES__OFF_ON;

	/**
	 * @en Values from {0} to {1} {2}.
	 */
	public static ResKey3 TARGET_RANGE__MIN_MAX_UNIT;

	/**
	 * @en Values from {0} to {1}.
	 */
	public static ResKey2 TARGET_RANGE__MIN_MAX;

	/**
	 * @en Values from {0} to {1} pulses.
	 */
	public static ResKey2 TARGET_RANGE_PULSES__MIN_MAX;

	/**
	 * @en Fan stages from {0} to {1}.
	 */
	public static ResKey2 TARGET_RANGE_FAN_STAGES__MIN_MAX;

	/**
	 * @en "Up" sends "brighter" (control bit 1), "Down" sends "darker" (control bit 0).
	 */
	public static ResKey TARGET_STEP_DIMMING;

	/**
	 * @en "Up" sends "up" (control bit 0), "Down" sends "down" (control bit 1).
	 */
	public static ResKey TARGET_STEP_BLINDS;

	/**
	 * @en pulses
	 */
	public static ResKey UNIT_PULSES;

	/**
	 * @en fan stage
	 */
	public static ResKey UNIT_FAN_STAGE;

	/**
	 * @en The project has no HomeControl controller; choose one in the deployment tab.
	 */
	public static ResKey ERROR_NO_CONTROLLER;

	/**
	 * @en The controller {0} has no token: set the token created on its console in the tab Controllers of the project.
	 */
	public static ResKey1 ERROR_NO_CONTROLLER_TOKEN__CONTROLLER;

	/**
	 * @en No connection to the controller {0}: {1}
	 */
	public static ResKey2 ERROR_CONTROLLER_CONNECT_FAILED__CONTROLLER_DETAIL;

	/**
	 * @en Connected to the controller {0} (its tunnel sends as {1}), {2} telegrams received.
	 */
	public static ResKey3 STATUS_CONTROLLER_CONNECTED__CONTROLLER_ADDRESS_COUNT;

	/**
	 * @en Connected to the controller {0}, {1} telegrams received.
	 */
	public static ResKey2 STATUS_CONTROLLER_CONNECTED__CONTROLLER_COUNT;

	/**
	 * @en The connection to the controller {0} was lost: {1}
	 */
	public static ResKey2 STATUS_CONTROLLER_LOST__CONTROLLER_REASON;

	/**
	 * @en The connection to the controller {0} is interrupted: {1}
	 */
	public static ResKey2 STATUS_CONTROLLER_INTERRUPTED__CONTROLLER_REASON;

	/**
	 * @en The connection to the gateway {0} is interrupted: {1}
	 */
	public static ResKey2 STATUS_INTERRUPTED__GATEWAY_REASON;

	/**
	 * @en KNX
	 */
	public static ResKey EVENT_KIND_KNX;

	/**
	 * @en Zigbee
	 */
	public static ResKey EVENT_KIND_ZIGBEE;

	/**
	 * @en Rule
	 */
	public static ResKey EVENT_KIND_RULE;

	/**
	 * @en Command
	 */
	public static ResKey EVENT_KIND_COMMAND;

	/**
	 * @en Runtime image
	 */
	public static ResKey EVENT_KIND_IMAGE;

	/**
	 * @en Gap
	 */
	public static ResKey EVENT_KIND_GAP;

	/**
	 * @en Restart
	 */
	public static ResKey EVENT_KIND_RESET;

	/**
	 * @en Other
	 */
	public static ResKey EVENT_KIND_OTHER;

	/**
	 * @en Report of {0} endpoint {1}, cluster {2}, attribute {3}
	 */
	public static ResKey4 EVENT_ZIGBEE_REPORT__DEVICE_ENDPOINT_CLUSTER_ATTRIBUTE;

	/**
	 * @en Command {3} of {0} endpoint {1}, cluster {2}
	 */
	public static ResKey4 EVENT_ZIGBEE_ACTION__DEVICE_ENDPOINT_CLUSTER_COMMAND;

	/**
	 * @en Command {3} to {0} endpoint {1}, cluster {2}
	 */
	public static ResKey4 EVENT_ZIGBEE_COMMAND__DEVICE_ENDPOINT_CLUSTER_COMMAND;

	/**
	 * @en Rule {0}
	 */
	public static ResKey1 EVENT_RULE__RULE;

	/**
	 * @en Commanded through {0}
	 */
	public static ResKey1 EVENT_COMMAND__ORIGIN;

	/**
	 * @en Runtime image {0}, revision {1}, loaded ({2})
	 */
	public static ResKey3 EVENT_IMAGE_LOAD__PROJECT_REVISION_SOURCE;

	/**
	 * @en Model rebuilt for the datapoints of new devices
	 */
	public static ResKey EVENT_IMAGE_RELOAD;

	/**
	 * @en Runtime image unloaded
	 */
	public static ResKey EVENT_IMAGE_UNLOAD;

	/**
	 * @en {0} events lost: the monitor fell behind the controller.
	 */
	public static ResKey1 EVENT_GAP__COUNT;

	/**
	 * @en The controller restarted; the events start with the oldest it holds.
	 */
	public static ResKey EVENT_RESET;

	/**
	 * @en Event {0}
	 */
	public static ResKey1 EVENT_OTHER__KIND;

	/**
	 * @en Choose a controller or enter the address and token of one.
	 */
	public static ResKey ERROR_NO_CONTROLLER_CHOSEN;

	/**
	 * @en Enter the token of the controller {0}: the monitor logs in to it with the token.
	 */
	public static ResKey1 ERROR_NO_AD_HOC_TOKEN__CONTROLLER;

	/**
	 * @en "{0}" is no port of a controller; enter a number from 1 to 65535.
	 */
	public static ResKey1 ERROR_INVALID_CONTROLLER_PORT__PORT;

	/**
	 * @en Recording {0} started in {1}
	 */
	public static ResKey2 COMMIT_RECORDING_STARTED__RECORDING_PROJECT;

	/**
	 * @en Recording {0} ended
	 */
	public static ResKey1 COMMIT_RECORDING_STOPPED__RECORDING;

	/**
	 * @en Recording {0}: {1} entries stored
	 */
	public static ResKey2 COMMIT_RECORDING_STORED__RECORDING_COUNT;

	static {
		initConstants(I18NConstants.class);
	}
}
