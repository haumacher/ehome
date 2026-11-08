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
	 * @en The parameter "{0}" belongs to the calculation {1} of the manufacturer, which E-Home cannot evaluate ({2}): it cannot be changed in E-Home.
	 */
	public static ResKey3 ERROR_NOT_EVALUATED__PARAMETER_CALCULATION_PROBLEM;

	/**
	 * @en E-Home cannot evaluate the calculation {0} of the manufacturer: {1}.
	 */
	public static ResKey2 ERROR_CANNOT_COMPUTE__CALCULATION_PROBLEM;

	/**
	 * @en The calculation {0} of the manufacturer failed ({1}): {2}. Nothing was changed.
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
	 * @en The parameter "{0}" is checked by the validation {1} of the manufacturer, which E-Home cannot evaluate ({2}): it cannot be changed in E-Home.
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
	 * @en The check {0} of the manufacturer failed: {1}. Nothing was changed.
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

	static {
		initConstants(I18NConstants.class);
	}
}
