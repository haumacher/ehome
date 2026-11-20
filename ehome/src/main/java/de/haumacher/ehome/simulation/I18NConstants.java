/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants of the simulation of the rules.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en No project is selected.
	 */
	public static ResKey ERROR_NO_PROJECT;

	/**
	 * @en An object follower needs its device, the switching object and the state object.
	 */
	public static ResKey ERROR_FOLLOWER_INCOMPLETE;

	/**
	 * @en No simulation runs for the project.
	 */
	public static ResKey ERROR_NOT_RUNNING;

	/**
	 * @en A simulation runs for the project already. Stop it first.
	 */
	public static ResKey ERROR_ALREADY_RUNNING;

	/**
	 * @en The runtime image of the project is not valid, so the simulation cannot start: {0}
	 */
	public static ResKey1 ERROR_IMAGE_INVALID__PROBLEMS;

	/**
	 * @en The simulation cannot start: {0}
	 */
	public static ResKey1 ERROR_IMAGE_REFUSED__REASON;

	/**
	 * @en The simulation cannot connect to its virtual installation: {0}
	 */
	public static ResKey1 ERROR_NO_CONNECTION__DETAIL;

	/**
	 * @en No UDP port on the loopback address is free for the virtual installation.
	 */
	public static ResKey ERROR_NO_FREE_PORT;

	/**
	 * @en "{0}" is no duration. Enter a duration such as 30s, 30min, 2h or 1d.
	 */
	public static ResKey1 ERROR_INVALID_DURATION__TEXT;

	/**
	 * @en "{0}" is no value of the datapoint {1}: {2}
	 */
	public static ResKey3 ERROR_INVALID_VALUE__TEXT_DATAPOINT_DETAIL;

	/**
	 * @en The Zigbee device of the datapoint {1} cannot report "{0}": {2}
	 */
	public static ResKey3 ERROR_ZIGBEE_REPORT_REFUSED__TEXT_DATAPOINT_REASON;

	/**
	 * @en The datapoint type {0} has no coding.
	 */
	public static ResKey1 REASON_NO_CODING__TYPE;

	/**
	 * @en There is no coding of values of the type {0}.
	 */
	public static ResKey1 REASON_NO_CODING_OF__VALUE_TYPE;

	/**
	 * @en There is no value.
	 */
	public static ResKey REASON_NO_VALUE;

	/**
	 * @en The text is longer than a string value of the rules.
	 */
	public static ResKey REASON_TEXT_TOO_LONG;

	/**
	 * @en The end of the day (24:00) is no time of day of the rules.
	 */
	public static ResKey REASON_END_OF_DAY_NO_TIME_OF_DAY;

	/**
	 * @en The end of the day (24:00) is no point in time of the rules.
	 */
	public static ResKey REASON_END_OF_DAY_NO_DATE_TIME;

	/**
	 * @en A value of the type {0} is required, not {1}.
	 */
	public static ResKey2 REASON_VALUE_OF_TYPE_REQUIRED__TYPE_VALUE;

	/**
	 * @en A number is required, not {0}.
	 */
	public static ResKey1 REASON_NUMBER_REQUIRED__VALUE;

	/**
	 * @en The data does not have the length of the datapoint type {0}.
	 */
	public static ResKey1 REASON_WRONG_LENGTH__TYPE;

	/**
	 * @en The value lies outside the range of the datapoint type {0}.
	 */
	public static ResKey1 REASON_OUT_OF_RANGE__TYPE;

	/**
	 * @en The data is no value of the datapoint type {0}.
	 */
	public static ResKey1 REASON_NO_VALUE_OF__TYPE;

	/**
	 * @en A value of the type {0} is expected.
	 */
	public static ResKey1 REASON_VALUE_OF_TYPE_EXPECTED__TYPE;

	/**
	 * @en The datapoint {0} is not bound to a Zigbee device.
	 */
	public static ResKey1 REASON_ZIGBEE_NOT_BOUND__DATAPOINT;

	/**
	 * @en A press of a button is no state the device keeps. Press the button while the simulation runs.
	 */
	public static ResKey REASON_ZIGBEE_PRESS_NO_STATE;

	/**
	 * @en The function {0} takes the values true and false.
	 */
	public static ResKey1 REASON_ZIGBEE_TAKES_BOOLEAN__FUNCTION;

	/**
	 * @en The function {0} takes a number.
	 */
	public static ResKey1 REASON_ZIGBEE_TAKES_NUMBER__FUNCTION;

	/**
	 * @en A level takes values from 0 to 100 %.
	 */
	public static ResKey REASON_ZIGBEE_LEVEL_RANGE;

	/**
	 * @en A colour temperature takes values from 16 to 1000000 K.
	 */
	public static ResKey REASON_ZIGBEE_COLOR_TEMPERATURE_RANGE;

	/**
	 * @en A temperature takes values from -327.67 to 327.67 °C.
	 */
	public static ResKey REASON_ZIGBEE_TEMPERATURE_RANGE;

	/**
	 * @en A humidity takes values from 0 to 100 %.
	 */
	public static ResKey REASON_ZIGBEE_HUMIDITY_RANGE;

	/**
	 * @en An illuminance takes values from 0 lux upwards.
	 */
	public static ResKey REASON_ZIGBEE_ILLUMINANCE_NEGATIVE;

	/**
	 * @en An illuminance takes values from 0 to {0} lux.
	 */
	public static ResKey1 REASON_ZIGBEE_ILLUMINANCE_RANGE__MAX;

	/**
	 * @en A battery level takes values from 0 to 100 %.
	 */
	public static ResKey REASON_ZIGBEE_BATTERY_RANGE;

	/**
	 * @en A multistate value takes the integers from 0 to 65535.
	 */
	public static ResKey REASON_ZIGBEE_MULTISTATE_RANGE;

	/**
	 * @en An action takes the values 0 (off), 1 (on), 2 (toggle), 3 (dim up), 4 (dim down) and 5 (dim stop).
	 */
	public static ResKey REASON_ZIGBEE_ACTION_RANGE;

	/**
	 * @en The simulation does not simulate the function {0}.
	 */
	public static ResKey1 REASON_ZIGBEE_NOT_SIMULATED__FUNCTION;

	/**
	 * @en The simulation has no such datapoint.
	 */
	public static ResKey REASON_COMMAND_UNKNOWN_DATAPOINT;

	/**
	 * @en The datapoint cannot be written.
	 */
	public static ResKey REASON_COMMAND_NOT_WRITABLE;

	/**
	 * @en The value does not fit the type of the datapoint.
	 */
	public static ResKey REASON_COMMAND_WRONG_TYPE;

	/**
	 * @en The device of the datapoint refused the value. The timeline shows why.
	 */
	public static ResKey REASON_COMMAND_REFUSED;

	/**
	 * @en The simulation has no such rule.
	 */
	public static ResKey REASON_PARAMETER_UNKNOWN_RULE;

	/**
	 * @en The rule has no such parameter.
	 */
	public static ResKey REASON_PARAMETER_UNKNOWN_PARAMETER;

	/**
	 * @en The parameter is not adjustable.
	 */
	public static ResKey REASON_PARAMETER_NOT_ADJUSTABLE;

	/**
	 * @en The value is no valid value of the parameter.
	 */
	public static ResKey REASON_PARAMETER_INVALID_VALUE;

	/**
	 * @en "{0}" is no value of the parameter {1}. Enter a literal of its type, e.g. true, 21.5, 3min or 06:30.
	 */
	public static ResKey2 ERROR_INVALID_PARAMETER_VALUE__TEXT_PARAMETER;

	/**
	 * @en Choose a datapoint in the table.
	 */
	public static ResKey ERROR_NO_DATAPOINT;

	/**
	 * @en Choose a parameter in the table.
	 */
	public static ResKey ERROR_NO_PARAMETER;

	/**
	 * @en The datapoint {0} is not bound to a group address, so no device sends it. A command sets it.
	 */
	public static ResKey1 ERROR_NOT_ON_BUS__DATAPOINT;

	/**
	 * @en The command for {0} was refused: {1}
	 */
	public static ResKey2 ERROR_COMMAND_REFUSED__DATAPOINT_REASON;

	/**
	 * @en The value of the parameter {0} was refused: {1}
	 */
	public static ResKey2 ERROR_PARAMETER_REFUSED__PARAMETER_REASON;

	/**
	 * @en No simulation runs. Start it to run the rules and schedules of the project against a virtual installation of its devices.
	 */
	public static ResKey STATUS_NOT_RUNNING;

	/**
	 * @en Simulated time: {0}. The virtual installation with {2} devices of the project is reached as gateway {1}.
	 */
	public static ResKey3 STATUS_RUNNING__TIME_ENDPOINT_DEVICES;

	/**
	 * @en Next timer or schedule firing: {0}.
	 */
	public static ResKey1 STATUS_NEXT_EVENT__TIME;

	/**
	 * @en No timer runs and no schedule fires.
	 */
	public static ResKey STATUS_NO_NEXT_EVENT;

	/**
	 * @en The clock moves only by "Step" and "Fast-forward".
	 */
	public static ResKey STATUS_CLOCK_PAUSED;

	/**
	 * @en The clock runs in real time.
	 */
	public static ResKey STATUS_CLOCK_REAL_TIME;

	/**
	 * @en The clock runs at {0} times real time.
	 */
	public static ResKey1 STATUS_CLOCK_FASTER__SPEED;

	/**
	 * @en The clock does not run by itself.
	 */
	public static ResKey ERROR_CLOCK_NOT_RUNNING;

	/**
	 * @en The clock runs at this speed already.
	 */
	public static ResKey ERROR_CLOCK_AT_SPEED;

	/**
	 * @en The speed {0} is not offered; the clock runs at 1, 10 or 60 times real time.
	 */
	public static ResKey1 ERROR_INVALID_SPEED__SPEED;

	/**
	 * @en Simulation values of {0} saved
	 */
	public static ResKey1 COMMIT_SIMULATION_SAVED__PROJECT;

	static {
		initConstants(I18NConstants.class);
	}
}
