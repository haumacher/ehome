/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

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
	 * @en "{0}" is not part of the configuration of the device under its current values.
	 */
	public static ResKey1 ERROR_NOT_IN_CONFIGURATION__ELEMENT;

	/**
	 * @en The parameter "{0}" can only be read.
	 */
	public static ResKey1 ERROR_READ_ONLY__PARAMETER;

	/**
	 * @en The parameter "{0}" has no value.
	 */
	public static ResKey1 ERROR_NO_VALUE__PARAMETER;

	/**
	 * @en "{0}" is no entry of the type {1}.
	 */
	public static ResKey2 ERROR_NO_ENTRY__VALUE_TYPE;

	/**
	 * @en The value {0} must lie between {1} and {2}.
	 */
	public static ResKey3 ERROR_OUT_OF_RANGE__VALUE_MIN_MAX;

	/**
	 * @en The value {0} is no whole number.
	 */
	public static ResKey1 ERROR_NOT_INTEGER__VALUE;

	/**
	 * @en "{0}" is no value of the type {1}.
	 */
	public static ResKey2 ERROR_WRONG_VALUE__VALUE_TYPE;

	/**
	 * @en At most {0} characters are allowed.
	 */
	public static ResKey1 ERROR_TEXT_TOO_LONG__MAX;

	/**
	 * @en "{0}" is no setting of the device.
	 */
	public static ResKey1 ERROR_NOT_A_SETTING__ELEMENT;

	/**
	 * @en No group address is chosen.
	 */
	public static ResKey ERROR_NO_GROUP_ADDRESS;

	/**
	 * @en Neither sending nor receiving is chosen.
	 */
	public static ResKey ERROR_NO_CONNECTION_KIND;

	/**
	 * @en The group address {0} does not belong to the project of the device.
	 */
	public static ResKey1 ERROR_FOREIGN_GROUP_ADDRESS__ADDRESS;

	/**
	 * @en The communication object "{0}" is already connected to {1}.
	 */
	public static ResKey2 ERROR_ALREADY_CONNECTED__OBJECT_ADDRESS;

	/**
	 * @en The communication object "{0}" already sends to {1}; an object sends to one group address
	 *     only.
	 */
	public static ResKey2 ERROR_SENDS_ALREADY__OBJECT_ADDRESS;

	/**
	 * @en "{0}" is no connection of a device.
	 */
	public static ResKey1 ERROR_NOT_A_CONNECTION__ELEMENT;

	/**
	 * @en {0} has the datapoint type {1}, the group address {2}.
	 */
	public static ResKey3 WARNING_TYPE_DIFFERS_FROM_ADDRESS__OBJECT_TYPE_ADDRESS_TYPE;

	/**
	 * @en {0} has {1} bits, the datapoint type of the group address {2} bits.
	 */
	public static ResKey3 WARNING_SIZE_DIFFERS_FROM_ADDRESS__OBJECT_SIZE_ADDRESS_SIZE;

	/**
	 * @en The connected objects have datapoint types of different main types.
	 */
	public static ResKey WARNING_OBJECT_TYPES_DIFFER;

	/**
	 * @en The connected objects have different sizes.
	 */
	public static ResKey WARNING_OBJECT_SIZES_DIFFER;

	static {
		initConstants(I18NConstants.class);
	}
}
