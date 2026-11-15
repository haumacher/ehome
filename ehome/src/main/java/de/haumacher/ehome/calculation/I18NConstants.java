/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

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
	 * @en The parameter "{0}" belongs to the calculation {1} of the manufacturer, which E-Home cannot evaluate, and cannot be changed in E-Home: {2}
	 */
	public static ResKey3 ERROR_NOT_EVALUATED__PARAMETER_CALCULATION_PROBLEM;

	/**
	 * @en E-Home cannot evaluate the calculation {0} of the manufacturer: {1}
	 */
	public static ResKey2 ERROR_CANNOT_COMPUTE__CALCULATION_PROBLEM;

	/**
	 * @en The calculation {0} of the manufacturer failed ({1}): {2} Nothing was changed.
	 */
	public static ResKey3 ERROR_FAILED__CALCULATION_DIRECTION_PROBLEM;

	/**
	 * @en The calculation {0} of the manufacturer gives the parameter "{1}" the value {2}, which it does not take: {3} Nothing was changed.
	 */
	public static ResKey4 ERROR_RESULT__CALCULATION_PARAMETER_VALUE_REASON;

	/**
	 * @en The calculations of the manufacturer feed each other without end (at {0}). Nothing was changed.
	 */
	public static ResKey1 ERROR_TOO_MANY_RUNS__CALCULATION;

	/**
	 * @en A parameter of the calculation {0} has neither an alias nor a name.
	 */
	public static ResKey1 ERROR_NO_NAME__CALCULATION;

	/**
	 * @en The calculation gives no value.
	 */
	public static ResKey ERROR_NO_RESULT;

	/**
	 * @en The value is no number.
	 */
	public static ResKey ERROR_NOT_A_NUMBER;

	/**
	 * @en Computed from other parameters by the calculation {0} of the manufacturer.
	 */
	public static ResKey1 COMPUTED_BY__CALCULATION;

	/**
	 * @en The parameter "{0}" is checked by the validation {1} of the manufacturer, which E-Home cannot evaluate, and cannot be changed in E-Home: {2}
	 */
	public static ResKey3 ERROR_NOT_EVALUATED__PARAMETER_VALIDATION_PROBLEM;

	/**
	 * @en {1} (the check {0} of the manufacturer refuses the value; nothing was changed)
	 */
	public static ResKey2 ERROR_REFUSED__VALIDATION_MESSAGE;

	/**
	 * @en The check {0} of the manufacturer refuses the value. Nothing was changed.
	 */
	public static ResKey1 ERROR_REFUSED__VALIDATION;

	/**
	 * @en The check {0} of the manufacturer failed: {1} Nothing was changed.
	 */
	public static ResKey2 ERROR_FAILED__VALIDATION_PROBLEM;

	/**
	 * @en A parameter of the validation {0} was not found or has neither an alias nor a name.
	 */
	public static ResKey1 ERROR_NO_NAME__VALIDATION;

	/**
	 * @en The check {0} refuses them: {1}
	 */
	public static ResKey2 IMPORT_REFUSED__VALIDATION_MESSAGE;

	/**
	 * @en The check {0} refuses them.
	 */
	public static ResKey1 IMPORT_REFUSED__VALIDATION;

	/**
	 * @en The check {0} failed: {1}
	 */
	public static ResKey2 IMPORT_FAILED__VALIDATION_PROBLEM;

	/**
	 * @en E-Home cannot run the button {0}: {1}
	 */
	public static ResKey2 ERROR_CANNOT_RUN__BUTTON_PROBLEM;

	/**
	 * @en The button {0} is not in the configuration of the device.
	 */
	public static ResKey1 ERROR_NOT_IN_EFFECT__BUTTON;

	/**
	 * @en The button {0} failed: {1} Nothing was changed.
	 */
	public static ResKey2 ERROR_FAILED__BUTTON_PROBLEM;

	/**
	 * @en The button {0} changed nothing: the parameter "{1}" refuses the value it gives. {2}
	 */
	public static ResKey3 ERROR_REFUSED__BUTTON_PARAMETER_REASON;

	/**
	 * @en The button gives no value.
	 */
	public static ResKey ERROR_NO_VALUE;

	/**
	 * @en {0} ({1})
	 */
	public static ResKey2 LABEL__TEXT_ID;

	/**
	 * @en The button names no handler.
	 */
	public static ResKey REASON_NO_HANDLER;

	/**
	 * @en The program has no script.
	 */
	public static ResKey REASON_NO_SCRIPT;

	/**
	 * @en The script is not JavaScript: {0}
	 */
	public static ResKey1 REASON_NOT_JAVASCRIPT__PROBLEM;

	/**
	 * @en The script defines no function {0}.
	 */
	public static ResKey1 REASON_NO_FUNCTION__FUNCTION;

	/**
	 * @en The handler uses {0}, which E-Home does not provide.
	 */
	public static ResKey1 REASON_USES__MEMBER;

	/**
	 * @en The handler sets {0}, which E-Home does not provide.
	 */
	public static ResKey1 REASON_SETS__MEMBER;

	/**
	 * @en The handler talks to the device ({0}), which E-Home does not do.
	 */
	public static ResKey1 REASON_TALKS_TO_DEVICE__MEMBER;

	/**
	 * @en The device has no parameter {0} ({1}).
	 */
	public static ResKey2 REASON_NO_PARAMETER__KEY_LOOKUP;

	/**
	 * @en The parameter {0} belongs to another module than the button.
	 */
	public static ResKey1 REASON_OTHER_MODULE__KEY;

	/**
	 * @en Stopped after {0} instructions (an endless loop?).
	 */
	public static ResKey1 REASON_INSTRUCTION_LIMIT__COUNT;

	/**
	 * @en Stopped after {0} ms (an endless loop?).
	 */
	public static ResKey1 REASON_TIME_LIMIT__MILLIS;

	/**
	 * @en The script reports in line {1}: {0}.
	 */
	public static ResKey2 REASON_SCRIPT_ERROR__DETAILS_LINE;

	/**
	 * @en The script reports: {0}.
	 */
	public static ResKey1 REASON_SCRIPT_ERROR__DETAILS;

	/**
	 * @en The calculation names no language.
	 */
	public static ResKey REASON_NO_LANGUAGE;

	/**
	 * @en The language {0} is not supported.
	 */
	public static ResKey1 REASON_LANGUAGE_NOT_SUPPORTED__LANGUAGE;

	/**
	 * @en {0}: {1}
	 */
	public static ResKey2 REASON_IN__PART_PROBLEM;

	/**
	 * @en A parameter of the calculation was not found.
	 */
	public static ResKey REASON_CALCULATION_PARAMETER_NOT_FOUND;

	/**
	 * @en A parameter of the validation was not found.
	 */
	public static ResKey REASON_VALIDATION_PARAMETER_NOT_FOUND;

	/**
	 * @en The validation names no function.
	 */
	public static ResKey REASON_NO_VALIDATION_FUNCTION;

	/**
	 * @en A function of the script as the transformation ({0}) assigns the members of an object, which E-Home''s VBScript does not support.
	 */
	public static ResKey1 REASON_VB_TRANSFORMATION_FUNCTION__FUNCTION;

	/**
	 * @en The script defines no Function {0}.
	 */
	public static ResKey1 REASON_VB_NO_FUNCTION__FUNCTION;

	/**
	 * @en The Function {0} takes {1} arguments instead of the 4 of a validation (input, changed, prevValue, context).
	 */
	public static ResKey2 REASON_VB_VALIDATION_ARGUMENTS__FUNCTION_COUNT;

	/**
	 * @en The Function {0} answers an object.
	 */
	public static ResKey1 REASON_VB_ANSWERS_OBJECT__FUNCTION;

	/**
	 * @en A hexadecimal number without digits (line {0}).
	 */
	public static ResKey1 REASON_VB_HEX_WITHOUT_DIGITS__LINE;

	/**
	 * @en An unterminated text (line {0}).
	 */
	public static ResKey1 REASON_VB_UNTERMINATED_TEXT__LINE;

	/**
	 * @en The character ''{0}'' is not supported (line {1}).
	 */
	public static ResKey2 REASON_VB_UNSUPPORTED_CHARACTER__CHARACTER_LINE;

	/**
	 * @en The name ''{0}'' has no value (line {1}).
	 */
	public static ResKey2 REASON_VB_NO_VALUE__NAME_LINE;

	/**
	 * @en ''{0}'' is no object, it has no member ''{1}'' (line {2}).
	 */
	public static ResKey3 REASON_VB_NO_OBJECT__VALUE_MEMBER_LINE;

	/**
	 * @en The member ''{0}'' has no value (line {1}).
	 */
	public static ResKey2 REASON_VB_MEMBER_NO_VALUE__MEMBER_LINE;

	/**
	 * @en The object has no member ''{0}'' (line {1}).
	 */
	public static ResKey2 REASON_VB_NO_MEMBER__MEMBER_LINE;

	/**
	 * @en The procedure ''{0}'' is unknown (line {1}).
	 */
	public static ResKey2 REASON_VB_UNKNOWN_PROCEDURE__NAME_LINE;

	/**
	 * @en Wrong number of arguments of ''{0}'' (line {1}).
	 */
	public static ResKey2 REASON_VB_ARGUMENT_COUNT__NAME_LINE;

	/**
	 * @en Calls nest deeper than {0} (line {1}).
	 */
	public static ResKey2 REASON_VB_TOO_DEEP__DEPTH_LINE;

	/**
	 * @en Division by zero (line {0}).
	 */
	public static ResKey1 REASON_VB_DIVISION_BY_ZERO__LINE;

	/**
	 * @en Sqr of a negative number (line {0}).
	 */
	public static ResKey1 REASON_VB_SQR_NEGATIVE__LINE;

	/**
	 * @en ''{0}'' is no number (line {1}).
	 */
	public static ResKey2 REASON_VB_NO_NUMBER__TEXT_LINE;

	/**
	 * @en If without End If (line {0}).
	 */
	public static ResKey1 REASON_VB_IF_WITHOUT_END__LINE;

	/**
	 * @en The function ''{0}'' is not supported (line {1}).
	 */
	public static ResKey2 REASON_VB_UNSUPPORTED_FUNCTION__NAME_LINE;

	/**
	 * @en Unexpected end of the script (line {0}).
	 */
	public static ResKey1 REASON_VB_END_OF_SCRIPT__LINE;

	/**
	 * @en Unexpected end of the statement (line {0}).
	 */
	public static ResKey1 REASON_VB_END_OF_STATEMENT__LINE;

	/**
	 * @en The construct ''{0}'' is not supported (line {1}).
	 */
	public static ResKey2 REASON_VB_UNSUPPORTED_CONSTRUCT__TOKEN_LINE;

	/**
	 * @en {0} expected instead of ''{1}'' (line {2}).
	 */
	public static ResKey3 REASON_VB_EXPECTED__WHAT_TOKEN_LINE;

	static {
		initConstants(I18NConstants.class);
	}
}
