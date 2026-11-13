/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.basic.util.ResKeyN;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en "{0}" is no application program.
	 */
	public static ResKey1 ERROR_NO_PROGRAM__VALUE;

	/**
	 * @en The application program "{0}" has a template already.
	 */
	public static ResKey1 ERROR_TEMPLATE_EXISTS__PROGRAM;

	/**
	 * @en No communication object of a device is given.
	 */
	public static ResKey ERROR_NO_DEVICE_OBJECT;

	/**
	 * @en {0} has {1} parameters and communication objects, {2} has {3}.
	 */
	public static ResKey4 ERROR_ALIKE_SIZE__CHANNEL_COUNT_OTHER_OTHER_COUNT;

	/**
	 * @en Where {0} has the parameter "{1}", {2} has the communication object {3}.
	 */
	public static ResKey4 ERROR_ALIKE_PARAMETER_OBJECT__CHANNEL_PARAMETER_OTHER_OBJECT;

	/**
	 * @en Where {0} has the communication object {1}, {2} has the parameter "{3}".
	 */
	public static ResKey4 ERROR_ALIKE_OBJECT_PARAMETER__CHANNEL_OBJECT_OTHER_PARAMETER;

	/**
	 * @en The parameter "{1}" of {0} has another type than its counterpart "{3}" of {2}.
	 */
	public static ResKey4 ERROR_ALIKE_TYPE__CHANNEL_PARAMETER_OTHER_PARAMETER;

	/**
	 * @en The communication object {1} of {0} differs from its counterpart {3} of {2} in its size or flags.
	 */
	public static ResKey4 ERROR_ALIKE_OBJECT__CHANNEL_OBJECT_OTHER_OBJECT;

	/**
	 * @en "{1}" of {0} is in effect under other conditions than its counterpart "{3}" of {2}.
	 */
	public static ResKey4 ERROR_ALIKE_CONDITIONS__CHANNEL_PART_OTHER_PART;

	/**
	 * @en "{1}" is placed several times in {0}, and its places have different counterparts in {2}.
	 */
	public static ResKey3 ERROR_ALIKE_PLACED_TWICE__CHANNEL_PART_OTHER;

	/**
	 * @en The device has no channel "{0}".
	 */
	public static ResKey1 ERROR_NO_CHANNEL__CHANNEL;

	/**
	 * @en Select at least two channels to declare them alike.
	 */
	public static ResKey ERROR_ALIKE_TWO_CHANNELS;

	/**
	 * @en {0} cannot be declared alike to {1}: {2}
	 */
	public static ResKey3 ERROR_NOT_ALIKE__CHANNEL_MODEL_REASON;

	/**
	 * @en Enter the name of the preset.
	 */
	public static ResKey ERROR_PRESET_NO_NAME;

	/**
	 * @en Choose the kind of the function the channel does.
	 */
	public static ResKey ERROR_PRESET_NO_KIND;

	/**
	 * @en Choose the parameter values that make the preset.
	 */
	public static ResKey ERROR_PRESET_NO_VALUES;

	/**
	 * @en The value of "{0}" is not a value of the device a preset of the channel can take.
	 */
	public static ResKey1 ERROR_PRESET_FOREIGN_VALUE__PARAMETER;

	/**
	 * @en The communication object {0} is not an object of the channel in effect.
	 */
	public static ResKey1 ERROR_PRESET_FOREIGN_OBJECT__OBJECT;

	/**
	 * @en The communication object {0} cannot take the role "{1}" in a preset of this purpose and kind.
	 */
	public static ResKey2 ERROR_PRESET_ROLE__OBJECT_ROLE;

	/**
	 * @en A preset "{0}" of {1} exists already.
	 */
	public static ResKey2 ERROR_PRESET_NAME_TAKEN__NAME_CHANNEL;

	/**
	 * @en The preset "{0}" belongs to the template of another application program.
	 */
	public static ResKey1 ERROR_PRESET_OTHER_PROGRAM__PRESET;

	/**
	 * @en The preset "{0}" sets "{2}", which has no counterpart in {1}.
	 */
	public static ResKey3 ERROR_PRESET_NO_COUNTERPART__PRESET_CHANNEL_PARAMETER;

	/**
	 * @en The preset "{0}" gives the communication object {2} a role, which has no counterpart in {1}.
	 */
	public static ResKey3 ERROR_PRESET_NO_OBJECT__PRESET_CHANNEL_OBJECT;

	/**
	 * @en The preset was saved from {0}, which is not declared alike to {1}.
	 */
	public static ResKey2 ERROR_PRESET_OTHER_CHANNEL__PRESET_CHANNEL;

	/**
	 * @en The preset "{0}" for {1} wants "{3}" for "{2}", but the preset "{4}" applied to {5} wants "{6}".
	 */
	public static ResKeyN ERROR_PRESET_CONFLICT__PRESET_CHANNEL_PARAMETER_VALUE_OTHER_OTHER_CHANNEL_OTHER_VALUE;

	/**
	 * @en Select a preset.
	 */
	public static ResKey ERROR_NO_PRESET;

	/**
	 * @en The preset "{0}" cannot set "{1}": {2}
	 */
	public static ResKey3 ERROR_PRESET_VALUE__PRESET_PARAMETER_REASON;

	/**
	 * @en Only administrators change the device templates and their presets.
	 */
	public static ResKey ERROR_TEMPLATES_NOT_ALLOWED;

	static {
		initConstants(I18NConstants.class);
	}
}
