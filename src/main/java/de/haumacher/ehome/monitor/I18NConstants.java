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
	 * @en Recording "{0}" runs: {1} telegrams.
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
	 * @en "Up" sends "{0}", "Down" sends "{1}".
	 */
	public static ResKey2 TARGET_STEP__UP_DOWN;

	static {
		initConstants(I18NConstants.class);
	}
}
