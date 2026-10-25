/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en "{0}" is no group address, expected: {1}
	 */
	public static ResKey2 ERROR_INVALID_GROUP_ADDRESS__TEXT_EXPECTED;

	/**
	 * @en The address {0} lies outside the range {1} to {2} of its group.
	 */
	public static ResKey3 ERROR_ADDRESS_OUTSIDE_RANGE__ADDRESS_START_END;

	/**
	 * @en The range ends at {1} before it starts at {0}.
	 */
	public static ResKey2 ERROR_RANGE_REVERSED__START_END;

	/**
	 * @en The value "{0}" was kept as text: its parameter type is not known.
	 */
	public static ResKey1 ERROR_VALUE_WITHOUT_TYPE__VALUE;

	/**
	 * @en The value "{0}" was kept as text: the parameter type {1} has no values.
	 */
	public static ResKey2 ERROR_VALUE_FOR_TYPE_WITHOUT_VALUE__VALUE_TYPE;

	/**
	 * @en The value "{0}" was kept as text: it is no value of the parameter type {1}.
	 */
	public static ResKey2 ERROR_VALUE_DOES_NOT_FIT_TYPE__VALUE_TYPE;

	/**
	 * @en Nothing is selected.
	 */
	public static ResKey ERROR_NOTHING_SELECTED;

	/**
	 * @en {0} cannot be placed in or after "{1}".
	 */
	public static ResKey2 ERROR_CANNOT_INSERT__TYPE_ANCHOR;

	/**
	 * @en "{0}" is not part of an ordered structure.
	 */
	public static ResKey1 ERROR_NOT_ORDERED__NODE;

	/**
	 * @en "{0}" cannot be placed in "{1}".
	 */
	public static ResKey2 ERROR_CANNOT_CONTAIN__NODE_CONTAINER;

	/**
	 * @en "{0}" cannot be placed inside itself.
	 */
	public static ResKey1 ERROR_INSIDE_ITSELF__NODE;

	/**
	 * @en "{0}" belongs to another application program.
	 */
	public static ResKey1 ERROR_OTHER_PROGRAM__NODE;

	/**
	 * @en Elements can be placed only inside the application program.
	 */
	public static ResKey ERROR_OUTSIDE_PROGRAM;

	/**
	 * @en The type "{0}" cannot be deleted: {1} parameters use it, e.g. "{2}".
	 */
	public static ResKey3 ERROR_TYPE_IN_USE__TYPE_COUNT_EXAMPLE;

	/**
	 * @en The entry "{0}" cannot be deleted: it is the value or default of {1} parameters or settings.
	 */
	public static ResKey2 ERROR_ENTRY_IN_USE__ENTRY_COUNT;

	/**
	 * @en The parameter "{0}" cannot be deleted: {1} devices set it.
	 */
	public static ResKey2 ERROR_PARAMETER_SET_BY_DEVICES__PARAMETER_COUNT;

	/**
	 * @en The parameter "{0}" cannot be deleted: "{1}" depends on it.
	 */
	public static ResKey2 ERROR_PARAMETER_DECIDES__PARAMETER_USER;

	/**
	 * @en The communication object "{0}" cannot be deleted: {1} devices use it.
	 */
	public static ResKey2 ERROR_OBJECT_USED_BY_DEVICES__OBJECT_COUNT;

	/**
	 * @en Not placed
	 */
	public static ResKey NOT_PLACED;

	static {
		initConstants(I18NConstants.class);
	}
}
