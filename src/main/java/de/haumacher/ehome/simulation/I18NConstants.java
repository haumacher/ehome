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
	 * @en The command for {0} was refused: {1}.
	 */
	public static ResKey2 ERROR_COMMAND_REFUSED__DATAPOINT_REASON;

	/**
	 * @en The value of the parameter {0} was refused: {1}.
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

	static {
		initConstants(I18NConstants.class);
	}
}
