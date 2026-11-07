/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants of the rules and schedules: the refusals of their loaders.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en An object is expected here.
	 */
	public static ResKey RULE_NOT_AN_OBJECT;

	/**
	 * @en Unknown member "{0}".
	 */
	public static ResKey1 RULE_UNKNOWN_MEMBER__NAME;

	/**
	 * @en The member "{0}" is missing.
	 */
	public static ResKey1 RULE_MEMBER_MISSING__NAME;

	/**
	 * @en "{0}" must be a string.
	 */
	public static ResKey1 RULE_NOT_A_STRING__NAME;

	/**
	 * @en "{0}" must have {1} to {2} bytes.
	 */
	public static ResKey3 RULE_STRING_LENGTH__NAME_MIN_MAX;

	/**
	 * @en "{0}" must be a string or an object of language tags to strings.
	 */
	public static ResKey1 RULE_LABEL_MALFORMED__NAME;

	/**
	 * @en The language tag "{1}" of "{0}" must have 2 to 8 characters of A-Z a-z 0-9 -.
	 */
	public static ResKey2 RULE_LABEL_LANGUAGE_TAG__NAME_TAG;

	/**
	 * @en A label has more than {0} languages.
	 */
	public static ResKey1 RULE_TOO_MANY_LANGUAGES__MAX;

	/**
	 * @en A text of a label must be a string of 1 to {0} bytes.
	 */
	public static ResKey1 RULE_LABEL_TEXT__MAX;

	/**
	 * @en "{0}" names no language.
	 */
	public static ResKey1 RULE_LABEL_NO_LANGUAGE__NAME;

	/**
	 * @en "{0}" must be true or false.
	 */
	public static ResKey1 RULE_NOT_A_BOOLEAN__NAME;

	/**
	 * @en "{0}" must be an array.
	 */
	public static ResKey1 RULE_NOT_AN_ARRAY__NAME;

	/**
	 * @en There are more than {0} rules.
	 */
	public static ResKey1 RULE_TOO_MANY_RULES__MAX;

	/**
	 * @en A rule must be an object.
	 */
	public static ResKey RULE_RULE_NOT_AN_OBJECT;

	/**
	 * @en The rule id "{0}" must have 1 to 32 characters of A-Z a-z 0-9 _ -.
	 */
	public static ResKey1 RULE_RULE_ID_PATTERN__ID;

	/**
	 * @en The rule id "{0}" is used twice.
	 */
	public static ResKey1 RULE_RULE_ID_TWICE__ID;

	/**
	 * @en The rule has more than {0} parameters (with "enabled").
	 */
	public static ResKey1 RULE_TOO_MANY_PARAMETERS__MAX;

	/**
	 * @en The rule has more than {0} timers.
	 */
	public static ResKey1 RULE_TOO_MANY_TIMERS__MAX;

	/**
	 * @en The timer id "{0}" is no identifier (1 to 24 letters, digits and _, no digit first, no keyword).
	 */
	public static ResKey1 RULE_TIMER_ID_INVALID__ID;

	/**
	 * @en The timer id "{0}" is used twice.
	 */
	public static ResKey1 RULE_TIMER_ID_TWICE__ID;

	/**
	 * @en The rule has more than {0} states.
	 */
	public static ResKey1 RULE_TOO_MANY_STATES__MAX;

	/**
	 * @en The state id "{0}" is no identifier (1 to 24 letters, digits and _, no digit first, no keyword).
	 */
	public static ResKey1 RULE_STATE_ID_INVALID__ID;

	/**
	 * @en The state id "{0}" is used twice.
	 */
	public static ResKey1 RULE_STATE_ID_TWICE__ID;

	/**
	 * @en The member "initialState" is missing; the rule has states.
	 */
	public static ResKey RULE_INITIAL_STATE_MISSING;

	/**
	 * @en The initial state "{0}" is no state of the rule.
	 */
	public static ResKey1 RULE_INITIAL_STATE_UNKNOWN__STATE;

	/**
	 * @en "initialState" is given in a rule without states.
	 */
	public static ResKey RULE_INITIAL_STATE_WITHOUT_STATES;

	/**
	 * @en The duration of a timer must be a duration, not of the type {0}.
	 */
	public static ResKey1 RULE_TIMER_DURATION_TYPE__TYPE;

	/**
	 * @en A rule needs at least one reaction.
	 */
	public static ResKey RULE_NO_REACTION;

	/**
	 * @en The rule has more than {0} reactions.
	 */
	public static ResKey1 RULE_TOO_MANY_REACTIONS__MAX;

	/**
	 * @en The parameter id "{0}" is no identifier (1 to 24 letters, digits and _, no digit first, no keyword).
	 */
	public static ResKey1 RULE_PARAMETER_ID_INVALID__ID;

	/**
	 * @en The parameter id "enabled" is reserved for the on/off switch of the rule.
	 */
	public static ResKey RULE_PARAMETER_ID_ENABLED;

	/**
	 * @en The parameter id "{0}" is used twice.
	 */
	public static ResKey1 RULE_PARAMETER_ID_TWICE__ID;

	/**
	 * @en Unknown type "{0}".
	 */
	public static ResKey1 RULE_UNKNOWN_TYPE__TYPE;

	/**
	 * @en The type "{0}" is no type of a parameter.
	 */
	public static ResKey1 RULE_NOT_A_PARAMETER_TYPE__TYPE;

	/**
	 * @en A unit has at most 15 bytes.
	 */
	public static ResKey RULE_UNIT_TOO_LONG;

	/**
	 * @en "{0}" must be a number.
	 */
	public static ResKey1 RULE_NOT_A_NUMBER__NAME;

	/**
	 * @en "{0}" is not possible for a parameter of the type {1}.
	 */
	public static ResKey2 RULE_MEMBER_ON_TYPE__NAME_TYPE;

	/**
	 * @en "min" is greater than "max".
	 */
	public static ResKey RULE_MIN_ABOVE_MAX;

	/**
	 * @en The parameter has more than {0} choices.
	 */
	public static ResKey1 RULE_TOO_MANY_CHOICES__MAX;

	/**
	 * @en "choices" is empty.
	 */
	public static ResKey RULE_CHOICES_EMPTY;

	/**
	 * @en The default lies outside "min" to "max".
	 */
	public static ResKey RULE_DEFAULT_OUTSIDE;

	/**
	 * @en The default is none of the choices.
	 */
	public static ResKey RULE_DEFAULT_NOT_A_CHOICE;

	/**
	 * @en A list has more than {0} actions.
	 */
	public static ResKey1 RULE_TOO_MANY_ACTIONS__MAX;

	/**
	 * @en An action must be an object.
	 */
	public static ResKey RULE_ACTION_NOT_AN_OBJECT;

	/**
	 * @en The datapoint "{0}" is not writable.
	 */
	public static ResKey1 RULE_NOT_WRITABLE__NAME;

	/**
	 * @en The datapoint "{0}" is the output of a schedule entry; the scheduler owns it.
	 */
	public static ResKey1 RULE_SCHEDULE_OUTPUT__NAME;

	/**
	 * @en A value of the type {0} cannot be written to the datapoint "{2}" of the type {1}.
	 */
	public static ResKey3 RULE_WRITE_TYPE__VALUE_TARGET_NAME;

	/**
	 * @en The datapoint "{0}" mirrors a rule parameter: no technology reads it.
	 */
	public static ResKey1 RULE_READ_PARAMETER__NAME;

	/**
	 * @en The datapoint "{0}" mirrors the time of a schedule entry: no technology reads it.
	 */
	public static ResKey1 RULE_READ_SCHEDULE__NAME;

	/**
	 * @en The datapoint "{0}" is bound to no technology that reads values.
	 */
	public static ResKey1 RULE_READ_UNBOUND__NAME;

	/**
	 * @en The rule has no timer "{0}".
	 */
	public static ResKey1 RULE_NO_TIMER__ID;

	/**
	 * @en A rule cannot set the on/off switch.
	 */
	public static ResKey RULE_ENABLE_SET;

	/**
	 * @en The rule has no parameter "{0}".
	 */
	public static ResKey1 RULE_NO_PARAMETER__ID;

	/**
	 * @en A value of the type {0} cannot be assigned to the parameter "{2}" of the type {1}.
	 */
	public static ResKey3 RULE_ASSIGN_TYPE__VALUE_TARGET_ID;

	/**
	 * @en Unknown level "{0}" (info, warning, error).
	 */
	public static ResKey1 RULE_UNKNOWN_LEVEL__LEVEL;

	/**
	 * @en A log message has more than {0} parts.
	 */
	public static ResKey1 RULE_TOO_MANY_LOG_PARTS__MAX;

	/**
	 * @en A text part is longer than {0} bytes.
	 */
	public static ResKey1 RULE_LOG_TEXT_TOO_LONG__MAX;

	/**
	 * @en Unknown kind of action "{0}".
	 */
	public static ResKey1 RULE_UNKNOWN_ACTION_KIND__KIND;

	/**
	 * @en A trigger must be an object.
	 */
	public static ResKey RULE_TRIGGER_NOT_AN_OBJECT;

	/**
	 * @en "on" must be change or update.
	 */
	public static ResKey RULE_TRIGGER_ON;

	/**
	 * @en "origin" must be any, report or command.
	 */
	public static ResKey RULE_TRIGGER_ORIGIN;

	/**
	 * @en A condition must be boolean, not of the type {0}.
	 */
	public static ResKey1 RULE_CONDITION_TYPE__TYPE;

	/**
	 * @en There is no schedule entry "{0}".
	 */
	public static ResKey1 RULE_NO_SCHEDULE__ID;

	/**
	 * @en A start reason must be boot, image or enable.
	 */
	public static ResKey RULE_START_REASON_NOT_A_STRING;

	/**
	 * @en Unknown start reason "{0}" (boot, image, enable).
	 */
	public static ResKey1 RULE_UNKNOWN_START_REASON__REASON;

	/**
	 * @en The on/off switch cannot trigger a reaction.
	 */
	public static ResKey RULE_ENABLE_TRIGGER;

	/**
	 * @en The duration of a silence must be a duration, not of the type {0}.
	 */
	public static ResKey1 RULE_SILENCE_TYPE__TYPE;

	/**
	 * @en Unknown kind of trigger "{0}".
	 */
	public static ResKey1 RULE_UNKNOWN_TRIGGER_KIND__KIND;

	/**
	 * @en A reaction needs at least one trigger.
	 */
	public static ResKey RULE_NO_TRIGGER;

	/**
	 * @en A reaction has more than {0} triggers.
	 */
	public static ResKey1 RULE_TOO_MANY_TRIGGERS__MAX;

	/**
	 * @en "from" is given in a rule without states.
	 */
	public static ResKey RULE_FROM_WITHOUT_STATES;

	/**
	 * @en A state id is expected here.
	 */
	public static ResKey RULE_STATE_ID_EXPECTED;

	/**
	 * @en The rule has no state "{0}".
	 */
	public static ResKey1 RULE_NO_STATE__ID;

	/**
	 * @en "to" is given in a rule without states.
	 */
	public static ResKey RULE_TO_WITHOUT_STATES;

	/**
	 * @en "elseActions" cannot be given together with "to".
	 */
	public static ResKey RULE_ELSE_WITH_TO;

	/**
	 * @en An expression has more than {0} nodes.
	 */
	public static ResKey1 RULE_TOO_MANY_NODES__MAX;

	/**
	 * @en The expressions of the image have more than {0} nodes.
	 */
	public static ResKey1 RULE_TOO_MANY_NODES_IN_IMAGE__MAX;

	/**
	 * @en There is no datapoint "{0}".
	 */
	public static ResKey1 RULE_NO_DATAPOINT__NAME;

	/**
	 * @en "{0}" is a parameter of this rule: use the parameter (setParameter, $id).
	 */
	public static ResKey1 RULE_OWN_PARAMETER_DATAPOINT__NAME;

	/**
	 * @en An expression is nested deeper than {0} levels.
	 */
	public static ResKey1 RULE_TOO_DEEP__MAX;

	/**
	 * @en A node of an expression must be an object.
	 */
	public static ResKey RULE_EXPRESSION_NOT_AN_OBJECT;

	/**
	 * @en An expression is nested deeper than {0} levels (a literal with a control bit counts as a call).
	 */
	public static ResKey1 RULE_TOO_DEEP_CONTROLLED__MAX;

	/**
	 * @en The duration of a silence cannot use a datapoint (it may use the parameters of the rule).
	 */
	public static ResKey RULE_DATAPOINT_IN_SILENCE;

	/**
	 * @en "{0}" is a parameter of this rule: refer to it as a parameter ($id).
	 */
	public static ResKey1 RULE_OWN_PARAMETER_REFERENCE__NAME;

	/**
	 * @en An expression cannot refer to the on/off switch.
	 */
	public static ResKey RULE_ENABLE_REFERENCED;

	/**
	 * @en A timer can only be used in running() or remaining().
	 */
	public static ResKey RULE_TIMER_NODE;

	/**
	 * @en Unknown unary operator "{0}".
	 */
	public static ResKey1 RULE_UNKNOWN_UNARY__OP;

	/**
	 * @en The operator {0} cannot be applied to the type {1}.
	 */
	public static ResKey2 RULE_UNARY_TYPE__OP_TYPE;

	/**
	 * @en Unknown binary operator "{0}".
	 */
	public static ResKey1 RULE_UNKNOWN_BINARY__OP;

	/**
	 * @en The operator {0} cannot be applied to the types {1} and {2}.
	 */
	public static ResKey3 RULE_BINARY_TYPE__OP_LEFT_RIGHT;

	/**
	 * @en Unknown kind of expression "{0}".
	 */
	public static ResKey1 RULE_UNKNOWN_EXPRESSION_KIND__KIND;

	/**
	 * @en Unknown function "{0}".
	 */
	public static ResKey1 RULE_UNKNOWN_FUNCTION__NAME;

	/**
	 * @en {0}() cannot be used in a match condition.
	 */
	public static ResKey1 RULE_FUNCTION_IN_MATCH__NAME;

	/**
	 * @en {0}() cannot be used in the duration of a silence.
	 */
	public static ResKey1 RULE_FUNCTION_IN_SILENCE__NAME;

	/**
	 * @en A call has more than {0} arguments.
	 */
	public static ResKey1 RULE_TOO_MANY_ARGUMENTS__MAX;

	/**
	 * @en {0}() takes {1} to {2} arguments, not {3}.
	 */
	public static ResKey4 RULE_ARITY__NAME_MIN_MAX_COUNT;

	/**
	 * @en The first argument of {0}() must be a reference to a datapoint.
	 */
	public static ResKey1 RULE_HISTORY_ARGUMENT__NAME;

	/**
	 * @en The argument of {0}() must be a timer.
	 */
	public static ResKey1 RULE_TIMER_ARGUMENT__NAME;

	/**
	 * @en state() is used in a rule without states.
	 */
	public static ResKey RULE_STATE_WITHOUT_STATES;

	/**
	 * @en The argument of state() must be one string literal.
	 */
	public static ResKey RULE_STATE_ARGUMENT;

	/**
	 * @en There is no rule with that id.
	 */
	public static ResKey RULE_STATE_NO_SUCH_RULE;

	/**
	 * @en There is no rule "{0}".
	 */
	public static ResKey1 RULE_NO_RULE__ID;

	/**
	 * @en The rule "{0}" has no states.
	 */
	public static ResKey1 RULE_RULE_WITHOUT_STATES__ID;

	/**
	 * @en {0}() takes no arguments of the types ({1}).
	 */
	public static ResKey2 RULE_CALL_TYPES__NAME_TYPES;

	/**
	 * @en The storage keys of {0} and {1} collide; rename one of them.
	 */
	public static ResKey2 RULE_STORAGE_COLLISION__FIRST_SECOND;

	/**
	 * @en A literal of the type boolean must be true or false.
	 */
	public static ResKey RULE_LITERAL_BOOLEAN;

	/**
	 * @en A literal of the type integer must be a number.
	 */
	public static ResKey RULE_LITERAL_INTEGER;

	/**
	 * @en A duration must be a number of milliseconds.
	 */
	public static ResKey RULE_LITERAL_DURATION;

	/**
	 * @en The value is no integer within 2^53-1.
	 */
	public static ResKey RULE_LITERAL_SAFE_INTEGER;

	/**
	 * @en A literal of the type real must be a number.
	 */
	public static ResKey RULE_LITERAL_REAL;

	/**
	 * @en The value is no finite real number.
	 */
	public static ResKey RULE_LITERAL_NOT_FINITE;

	/**
	 * @en A literal of the type enumeration must be a number.
	 */
	public static ResKey RULE_LITERAL_ENUMERATION;

	/**
	 * @en An element of an enumeration must be 0 to 4294967295.
	 */
	public static ResKey RULE_LITERAL_ENUMERATION_RANGE;

	/**
	 * @en A literal of the type string must be a JSON string.
	 */
	public static ResKey RULE_LITERAL_STRING;

	/**
	 * @en A string is longer than 28 bytes or contains a NUL character.
	 */
	public static ResKey RULE_LITERAL_STRING_LENGTH;

	/**
	 * @en A value with a control bit is an object of "control" (true or false) and "value" (a number).
	 */
	public static ResKey RULE_LITERAL_CONTROLLED;

	/**
	 * @en The number of a value with a control bit must be 0 to 255.
	 */
	public static ResKey RULE_LITERAL_CONTROLLED_RANGE;

	/**
	 * @en The type of the literal is unknown.
	 */
	public static ResKey RULE_LITERAL_UNKNOWN_TYPE;

	/**
	 * @en A literal of the type raw must be a string of hexadecimal octets.
	 */
	public static ResKey RULE_LITERAL_RAW;

	/**
	 * @en A raw value is longer than 14 bytes.
	 */
	public static ResKey RULE_LITERAL_RAW_LENGTH;

	/**
	 * @en The octets of a raw value must be two hexadecimal digits each, separated by single spaces, at most 14.
	 */
	public static ResKey RULE_LITERAL_RAW_OCTETS;

	/**
	 * @en A time of day is an object of "hour", "minute" and "second" with an optional "dayOfWeek".
	 */
	public static ResKey RULE_LITERAL_TIME_OF_DAY;

	/**
	 * @en A field of the time of day is out of its range.
	 */
	public static ResKey RULE_LITERAL_TIME_OF_DAY_RANGE;

	/**
	 * @en A field of the time of day is missing.
	 */
	public static ResKey RULE_LITERAL_TIME_OF_DAY_MISSING;

	/**
	 * @en A date and time is an object of "year", "month", "day", "hour", "minute" and "second".
	 */
	public static ResKey RULE_LITERAL_DATE_TIME;

	/**
	 * @en A date is an object of "year", "month" and "day".
	 */
	public static ResKey RULE_LITERAL_DATE;

	/**
	 * @en A field of the date is out of its range.
	 */
	public static ResKey RULE_LITERAL_DATE_RANGE;

	/**
	 * @en A field of the date is missing.
	 */
	public static ResKey RULE_LITERAL_DATE_MISSING;

	/**
	 * @en The date does not exist.
	 */
	public static ResKey RULE_LITERAL_INVALID_DATE;

	/**
	 * @en A color is an object of "red", "green" and "blue" with an optional "white".
	 */
	public static ResKey RULE_LITERAL_COLOR;

	/**
	 * @en A color level must be 0 to 255.
	 */
	public static ResKey RULE_LITERAL_COLOR_RANGE;

	/**
	 * @en A color level is missing.
	 */
	public static ResKey RULE_LITERAL_COLOR_MISSING;

	/**
	 * @en "{0}" must be a string.
	 */
	public static ResKey1 SCHEDULE_NOT_A_STRING__NAME;

	/**
	 * @en "{0}" must have {1} to {2} bytes.
	 */
	public static ResKey3 SCHEDULE_STRING_LENGTH__NAME_MIN_MAX;

	/**
	 * @en "{0}" contains a control character.
	 */
	public static ResKey1 SCHEDULE_CONTROL_CHARACTER__NAME;

	/**
	 * @en "{0}" must be an integer.
	 */
	public static ResKey1 SCHEDULE_NOT_AN_INTEGER__NAME;

	/**
	 * @en "{0}" must be {1} to {2}.
	 */
	public static ResKey3 SCHEDULE_RANGE__NAME_MIN_MAX;

	/**
	 * @en "{0}" must be {1} to {2} seconds.
	 */
	public static ResKey3 SCHEDULE_RANGE_SECONDS__NAME_MIN_MAX;

	/**
	 * @en "{0}" must be {1} to {2} milliseconds.
	 */
	public static ResKey3 SCHEDULE_RANGE_MILLISECONDS__NAME_MIN_MAX;

	/**
	 * @en "offset" must be at most 12 hours (-43200000 to 43200000 milliseconds).
	 */
	public static ResKey SCHEDULE_OFFSET_RANGE;

	/**
	 * @en "every" must be 1 minute to 12 hours (60000 to 43200000 milliseconds).
	 */
	public static ResKey SCHEDULE_EVERY_RANGE;

	/**
	 * @en "{0}" must be true or false.
	 */
	public static ResKey1 SCHEDULE_NOT_A_FLAG__NAME;

	/**
	 * @en Unknown member "{0}".
	 */
	public static ResKey1 SCHEDULE_UNKNOWN_MEMBER__NAME;

	/**
	 * @en "{0}" must be an object of "hour", "minute" and "second".
	 */
	public static ResKey1 SCHEDULE_CLOCK_NOT_AN_OBJECT__NAME;

	/**
	 * @en "{0}" needs "hour", "minute" and "second".
	 */
	public static ResKey1 SCHEDULE_CLOCK_INCOMPLETE__NAME;

	/**
	 * @en "{0}" must be an object.
	 */
	public static ResKey1 SCHEDULE_NOT_AN_OBJECT__NAME;

	/**
	 * @en "{0}" needs "year", "month" and "day".
	 */
	public static ResKey1 SCHEDULE_DATE_INCOMPLETE__NAME;

	/**
	 * @en {0} is no date.
	 */
	public static ResKey1 SCHEDULE_NOT_A_DATE__DATE;

	/**
	 * @en "days" must be an array of 1 (Monday) to 7 (Sunday).
	 */
	public static ResKey SCHEDULE_DAYS_NOT_AN_ARRAY;

	/**
	 * @en The day {0} is listed twice.
	 */
	public static ResKey1 SCHEDULE_DAY_TWICE__DAY;

	/**
	 * @en "days" must name at least one day.
	 */
	public static ResKey SCHEDULE_DAYS_EMPTY;

	/**
	 * @en "schedules" must be an array.
	 */
	public static ResKey SCHEDULE_NOT_AN_ARRAY;

	/**
	 * @en {0} schedule entries exceed the limit of {1}.
	 */
	public static ResKey2 SCHEDULE_TOO_MANY_ENTRIES__COUNT_LIMIT;

	/**
	 * @en The id "{1}" is used by schedules[{0}] too.
	 */
	public static ResKey2 SCHEDULE_ID_TWICE__INDEX_ID;

	/**
	 * @en A schedule entry must be an object.
	 */
	public static ResKey SCHEDULE_ENTRY_NOT_AN_OBJECT;

	/**
	 * @en The member "{0}" is missing.
	 */
	public static ResKey1 SCHEDULE_MEMBER_MISSING__NAME;

	/**
	 * @en The id "{0}" must have 1 to 32 characters of A-Z a-z 0-9 _ -.
	 */
	public static ResKey1 SCHEDULE_ID_PATTERN__ID;

	/**
	 * @en Unknown kind "{0}" of a schedule entry (weekly, yearly, dates, astro, interval, once).
	 */
	public static ResKey1 SCHEDULE_UNKNOWN_KIND__KIND;

	/**
	 * @en "missed" must be skip or catchUp.
	 */
	public static ResKey SCHEDULE_MISSED;

	/**
	 * @en The output "{0}" is no datapoint of the image.
	 */
	public static ResKey1 SCHEDULE_OUTPUT_UNKNOWN__NAME;

	/**
	 * @en The output "{0}" is of the type {1}, not boolean.
	 */
	public static ResKey2 SCHEDULE_OUTPUT_TYPE__NAME_TYPE;

	/**
	 * @en {1} owns the output "{0}" already.
	 */
	public static ResKey2 SCHEDULE_OUTPUT_OWNED__NAME_OWNER;

	/**
	 * @en The month {0} has no day {1}.
	 */
	public static ResKey2 SCHEDULE_NO_SUCH_DAY__MONTH_DAY;

	/**
	 * @en "adjustable" must be an array.
	 */
	public static ResKey SCHEDULE_ADJUSTABLE_NOT_AN_ARRAY;

	/**
	 * @en "{0}" cannot be adjustable in an entry of the kind {1} ({2}).
	 */
	public static ResKey3 SCHEDULE_NOT_ADJUSTABLE_IN_KIND__NAME_KIND_NAMES;

	/**
	 * @en "{0}" is listed twice.
	 */
	public static ResKey1 SCHEDULE_LISTED_TWICE__NAME;

	/**
	 * @en "bounds" must be an object.
	 */
	public static ResKey SCHEDULE_BOUNDS_NOT_AN_OBJECT;

	/**
	 * @en There are bounds for "{0}", which is not adjustable.
	 */
	public static ResKey1 SCHEDULE_BOUNDS_NOT_ADJUSTABLE__NAME;

	/**
	 * @en "days" takes no bounds.
	 */
	public static ResKey SCHEDULE_DAYS_NO_BOUNDS;

	/**
	 * @en A bound must be an object.
	 */
	public static ResKey SCHEDULE_BOUND_NOT_AN_OBJECT;

	/**
	 * @en "at" takes no step.
	 */
	public static ResKey SCHEDULE_AT_NO_STEP;

	/**
	 * @en The step must be a whole number of minutes.
	 */
	public static ResKey SCHEDULE_STEP_MINUTES;

	/**
	 * @en The step must be a whole number of seconds.
	 */
	public static ResKey SCHEDULE_STEP_SECONDS;

	/**
	 * @en "min" is later than "max".
	 */
	public static ResKey SCHEDULE_MIN_AFTER_MAX;

	/**
	 * @en "{0}" must be a whole number of minutes.
	 */
	public static ResKey1 SCHEDULE_WHOLE_MINUTES__NAME;

	/**
	 * @en "{0}" must be a whole number of seconds.
	 */
	public static ResKey1 SCHEDULE_WHOLE_SECONDS__NAME;

	/**
	 * @en An entry of the kind {0} needs "{1}".
	 */
	public static ResKey2 SCHEDULE_KIND_NEEDS__KIND_NAME;

	/**
	 * @en "dates" must be an array of dates.
	 */
	public static ResKey SCHEDULE_DATES_NOT_AN_ARRAY;

	/**
	 * @en "dates" must list at least one date.
	 */
	public static ResKey SCHEDULE_DATES_EMPTY;

	/**
	 * @en {0} dates exceed the limit of {1}.
	 */
	public static ResKey2 SCHEDULE_TOO_MANY_DATES__COUNT_LIMIT;

	/**
	 * @en The date is listed twice.
	 */
	public static ResKey SCHEDULE_DATE_TWICE;

	/**
	 * @en "sun" must be dawn, sunrise, sunset or dusk.
	 */
	public static ResKey SCHEDULE_SUN;

	/**
	 * @en "notBefore" is later than "notAfter".
	 */
	public static ResKey SCHEDULE_NOT_BEFORE_AFTER_NOT_AFTER;

	/**
	 * @en "until" is earlier than "from"; a window across midnight needs two entries.
	 */
	public static ResKey SCHEDULE_UNTIL_BEFORE_FROM;

	/**
	 * @en "adjustable" names a member that this kind of entry cannot adjust.
	 */
	public static ResKey SCHEDULE_ADJUSTABLE_KIND;

	/**
	 * @en "notBefore" is adjustable, but the entry sets none.
	 */
	public static ResKey SCHEDULE_NOT_BEFORE_UNSET;

	/**
	 * @en "notAfter" is adjustable, but the entry sets none.
	 */
	public static ResKey SCHEDULE_NOT_AFTER_UNSET;

	/**
	 * @en "bounds" names a member that this kind of entry does not have.
	 */
	public static ResKey SCHEDULE_BOUNDS_KIND;

	/**
	 * @en "bounds" names a member twice.
	 */
	public static ResKey SCHEDULE_BOUNDS_TWICE;

	/**
	 * @en "bounds" names a member that is not adjustable.
	 */
	public static ResKey SCHEDULE_BOUNDS_UNADJUSTABLE;

	/**
	 * @en A bound is no value of its member.
	 */
	public static ResKey SCHEDULE_BOUND_RANGE;

	/**
	 * @en The "min" of a bound is greater than its "max".
	 */
	public static ResKey SCHEDULE_BOUND_MIN_MAX;

	/**
	 * @en A step must be positive.
	 */
	public static ResKey SCHEDULE_STEP_NEGATIVE;

	/**
	 * @en The step of "every" must be a whole number of minutes.
	 */
	public static ResKey SCHEDULE_EVERY_STEP;

	/**
	 * @en The step of a time of day is at most a day.
	 */
	public static ResKey SCHEDULE_TIME_STEP;

	/**
	 * @en The value of the image lies outside its bounds.
	 */
	public static ResKey SCHEDULE_VALUE_OUTSIDE;

	/**
	 * @en The value of the image does not lie on its step.
	 */
	public static ResKey SCHEDULE_VALUE_OFF_STEP;

	/**
	 * @en The KNX adapter
	 */
	public static ResKey OWNER_KNX_ADAPTER;

	/**
	 * @en The Zigbee adapter
	 */
	public static ResKey OWNER_ZIGBEE_ADAPTER;

	/**
	 * @en The KNX time master
	 */
	public static ResKey OWNER_TIME_MASTER;

	/**
	 * @en The rule engine
	 */
	public static ResKey OWNER_RULE_ENGINE;

	/**
	 * @en The rule engine (a rule parameter)
	 */
	public static ResKey OWNER_RULE_PARAMETER;

	/**
	 * @en The scheduler
	 */
	public static ResKey OWNER_SCHEDULER;

	/**
	 * @en A schedule entry
	 */
	public static ResKey OWNER_SCHEDULE_ENTRY;

	/**
	 * @en Another schedule entry
	 */
	public static ResKey OWNER_OTHER_SCHEDULE_ENTRY;

	/**
	 * @en The runtime of the virtual devices
	 */
	public static ResKey OWNER_BLOCKS;

	/**
	 * @en The runtime of the virtual devices (a datapoint of a virtual device)
	 */
	public static ResKey OWNER_BLOCK_DATAPOINT;

	static {
		initConstants(I18NConstants.class);
	}

}
