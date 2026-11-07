/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants of the virtual devices: the refusals of their loader.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en "blocks" must be an array.
	 */
	public static ResKey BLOCK_NOT_AN_ARRAY;

	/**
	 * @en {0} virtual devices exceed the limit of {1}.
	 */
	public static ResKey2 BLOCK_TOO_MANY__COUNT_LIMIT;

	/**
	 * @en The state records of "{0}" and "{1}" collide in the storage of the controller.
	 */
	public static ResKey2 BLOCK_RECORDS_COLLIDE__FIRST_SECOND;

	/**
	 * @en Two stored parameter changes of the virtual devices collide in the storage of the controller.
	 */
	public static ResKey BLOCK_OVERRIDES_COLLIDE;

	/**
	 * @en A datapoint name must be a string of 1 to 63 bytes.
	 */
	public static ResKey BLOCK_DATAPOINT_NAME;

	/**
	 * @en There is no datapoint "{0}".
	 */
	public static ResKey1 BLOCK_NO_DATAPOINT__NAME;

	/**
	 * @en A virtual device must be an object.
	 */
	public static ResKey BLOCK_NOT_AN_OBJECT;

	/**
	 * @en Unknown member "{0}".
	 */
	public static ResKey1 BLOCK_UNKNOWN_MEMBER__NAME;

	/**
	 * @en The member "{0}" is missing.
	 */
	public static ResKey1 BLOCK_MEMBER_MISSING__NAME;

	/**
	 * @en The id of a virtual device has 1 to 32 characters of A-Z a-z 0-9 _ -.
	 */
	public static ResKey BLOCK_ID_PATTERN;

	/**
	 * @en The id "{1}" is used by blocks[{0}] too.
	 */
	public static ResKey2 BLOCK_ID_TWICE__INDEX_ID;

	/**
	 * @en The member "type" (a type id) is missing or no string.
	 */
	public static ResKey BLOCK_TYPE_MISSING;

	/**
	 * @en The member "version" must have the form major.minor.
	 */
	public static ResKey BLOCK_VERSION_MALFORMED;

	/**
	 * @en This firmware has no type {0} {1} of virtual devices.
	 */
	public static ResKey2 BLOCK_UNKNOWN_TYPE__TYPE_VERSION;

	/**
	 * @en This firmware has no {0} {1}, but {2}.
	 */
	public static ResKey3 BLOCK_TYPE_VERSION__TYPE_VERSION_HAS;

	/**
	 * @en The label must be a string of 1 to 63 bytes or an object of language tags to such strings.
	 */
	public static ResKey BLOCK_LABEL_MALFORMED;

	/**
	 * @en "{0}" must be true or false.
	 */
	public static ResKey1 BLOCK_NOT_A_FLAG__NAME;

	/**
	 * @en "inputs" must be an object of port ids to datapoint names.
	 */
	public static ResKey BLOCK_INPUTS_MALFORMED;

	/**
	 * @en A port id is too long.
	 */
	public static ResKey BLOCK_PORT_ID_TOO_LONG;

	/**
	 * @en The type {0} has no port "{1}".
	 */
	public static ResKey2 BLOCK_NO_PORT__TYPE_PORT;

	/**
	 * @en "{0}" is an output of {1}: bind it under "outputs".
	 */
	public static ResKey2 BLOCK_OUTPUT_UNDER_INPUTS__PORT_TYPE;

	/**
	 * @en The port "{0}" takes at most one datapoint.
	 */
	public static ResKey1 BLOCK_PORT_ONE__PORT;

	/**
	 * @en The port "{0}" takes at most {1} datapoints.
	 */
	public static ResKey2 BLOCK_PORT_TOO_MANY__PORT_MAX;

	/**
	 * @en An input binds 1 to {0} datapoints.
	 */
	public static ResKey1 BLOCK_INPUT_EMPTY__MAX;

	/**
	 * @en "{0}" is a datapoint of this virtual device: it cannot be its input.
	 */
	public static ResKey1 BLOCK_OWN_DATAPOINT_INPUT__NAME;

	/**
	 * @en The port "{1}" of the type {0} cannot read the datapoint "{3}" of the type {2}.
	 */
	public static ResKey4 BLOCK_INPUT_TYPE__PORTTYPE_PORT_TYPE_NAME;

	/**
	 * @en "{0}" is listed twice.
	 */
	public static ResKey1 BLOCK_LISTED_TWICE__NAME;

	/**
	 * @en The required input "{0}" of {1} is not bound.
	 */
	public static ResKey2 BLOCK_REQUIRED_INPUT__PORT_TYPE;

	/**
	 * @en "aggregation" must be an object of port ids to aggregations.
	 */
	public static ResKey BLOCK_AGGREGATION_MALFORMED;

	/**
	 * @en The type {0} has no input "{1}".
	 */
	public static ResKey2 BLOCK_NO_INPUT__TYPE_PORT;

	/**
	 * @en An aggregation is one of mean, min, max, any, all.
	 */
	public static ResKey BLOCK_UNKNOWN_AGGREGATION;

	/**
	 * @en The input "{0}" does not bind several datapoints: there is nothing to aggregate.
	 */
	public static ResKey1 BLOCK_NOTHING_TO_AGGREGATE__PORT;

	/**
	 * @en The input "{0}" does not allow the aggregation "{1}".
	 */
	public static ResKey2 BLOCK_AGGREGATION_NOT_ALLOWED__PORT_AGGREGATION;

	/**
	 * @en "outputs" must be an object of port ids to datapoint names.
	 */
	public static ResKey BLOCK_OUTPUTS_MALFORMED;

	/**
	 * @en "{0}" is an input of {1}: bind it under "inputs".
	 */
	public static ResKey2 BLOCK_INPUT_UNDER_OUTPUTS__PORT_TYPE;

	/**
	 * @en {1} owns the datapoint "{0}" already.
	 */
	public static ResKey2 BLOCK_OUTPUT_OWNED__NAME_OWNER;

	/**
	 * @en The datapoint "{0}" is not writable.
	 */
	public static ResKey1 BLOCK_NOT_WRITABLE__NAME;

	/**
	 * @en The port "{1}" of the type {0} cannot command the datapoint "{3}" of the type {2}.
	 */
	public static ResKey4 BLOCK_OUTPUT_TYPE__PORTTYPE_PORT_TYPE_NAME;

	/**
	 * @en "{0}" is bound to the input "{1}": a virtual device does not read its own output.
	 */
	public static ResKey2 BLOCK_READS_OWN_OUTPUT__NAME_PORT;

	/**
	 * @en "{0}" is bound to the output "{1}" already.
	 */
	public static ResKey2 BLOCK_OUTPUT_BOUND_TWICE__NAME_PORT;

	/**
	 * @en "{0}" is the output "{1}" of the virtual device "{2}".
	 */
	public static ResKey3 BLOCK_OUTPUT_OF_OTHER__NAME_PORT_BLOCK;

	/**
	 * @en "parameters" must be an object of parameter ids to values.
	 */
	public static ResKey BLOCK_PARAMETERS_MALFORMED;

	/**
	 * @en A parameter id is too long.
	 */
	public static ResKey BLOCK_PARAMETER_ID_TOO_LONG;

	/**
	 * @en The type {0} has no parameter "{1}".
	 */
	public static ResKey2 BLOCK_NO_PARAMETER__TYPE_PARAMETER;

	/**
	 * @en The parameter "{0}" must be true or false.
	 */
	public static ResKey1 BLOCK_PARAM_NOT_BOOLEAN__PARAMETER;

	/**
	 * @en The parameter "{0}" must be a time of day of "hour", "minute" and "second".
	 */
	public static ResKey1 BLOCK_PARAM_NOT_TIME_OBJECT__PARAMETER;

	/**
	 * @en The value of the parameter "{0}" is none of its choices.
	 */
	public static ResKey1 BLOCK_PARAM_NOT_A_CHOICE__PARAMETER;

	/**
	 * @en The parameter "{0}" must be a number or the id of a choice.
	 */
	public static ResKey1 BLOCK_PARAM_NOT_NUMBER_OR_CHOICE__PARAMETER;

	/**
	 * @en The parameter "{0}" must be a number.
	 */
	public static ResKey1 BLOCK_PARAM_NOT_A_NUMBER__PARAMETER;

	/**
	 * @en The parameter "{0}" must be a finite number.
	 */
	public static ResKey1 BLOCK_PARAM_NOT_FINITE__PARAMETER;

	/**
	 * @en The parameter "{0}" must be a whole number.
	 */
	public static ResKey1 BLOCK_PARAM_NOT_WHOLE__PARAMETER;

	/**
	 * @en The parameter "{0}" must be 0 to 4294967295.
	 */
	public static ResKey1 BLOCK_PARAM_OUTSIDE_UNSIGNED__PARAMETER;

	/**
	 * @en The parameter "{0}" lies outside the signed 32-bit range.
	 */
	public static ResKey1 BLOCK_PARAM_OUTSIDE_SIGNED__PARAMETER;

	/**
	 * @en The parameter "{0}" must be a time of day.
	 */
	public static ResKey1 BLOCK_PARAM_NOT_TIME__PARAMETER;

	/**
	 * @en The parameter "{0}" lies outside its "min" to "max".
	 */
	public static ResKey1 BLOCK_PARAM_OUTSIDE_MIN_MAX__PARAMETER;

	/**
	 * @en "{0}" must be below "{1}".
	 */
	public static ResKey2 BLOCK_PARAM_BELOW__LOW_HIGH;

	/**
	 * @en "{0}" must be above "{1}".
	 */
	public static ResKey2 BLOCK_PARAM_ABOVE__HIGH_LOW;

	/**
	 * @en "{0}" must be at most "{1}".
	 */
	public static ResKey2 BLOCK_PARAM_AT_MOST__LOW_HIGH;

	/**
	 * @en The outside temperatures of the points must rise.
	 */
	public static ResKey BLOCK_POINTS_RISE;

	/**
	 * @en The directions of the horizon points must rise.
	 */
	public static ResKey BLOCK_HORIZON_RISE;

	/**
	 * @en The "minPower" of a modulated load must be above 0 and at most its power.
	 */
	public static ResKey BLOCK_MODULATED_MIN_POWER;

	static {
		initConstants(I18NConstants.class);
	}

}
