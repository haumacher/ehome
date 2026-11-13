/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.intent;

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
	 * @en Create
	 */
	public static ResKey ACTION_CREATE;

	/**
	 * @en Change
	 */
	public static ResKey ACTION_CHANGE;

	/**
	 * @en Remove
	 */
	public static ResKey ACTION_REMOVE;

	/**
	 * @en Not overwritten
	 */
	public static ResKey ACTION_KEEP;

	/**
	 * @en Group range
	 */
	public static ResKey WHAT_RANGE;

	/**
	 * @en Group address
	 */
	public static ResKey WHAT_ADDRESS;

	/**
	 * @en Connection
	 */
	public static ResKey WHAT_CONNECTION;

	/**
	 * @en Parameter value
	 */
	public static ResKey WHAT_VALUE;

	/**
	 * @en Preset
	 */
	public static ResKey WHAT_PRESET;

	/**
	 * @en Function
	 */
	public static ResKey WHAT_FUNCTION;

	/**
	 * @en {0} Switching
	 */
	public static ResKey1 ADDRESS_NAME_COMMAND__FUNCTION;

	/**
	 * @en {0} Status
	 */
	public static ResKey1 ADDRESS_NAME_STATE__FUNCTION;

	/**
	 * @en {0} {1}
	 */
	public static ResKey2 ADDRESS_NAME__FUNCTION_ROLE;

	/**
	 * @en Lights
	 */
	public static ResKey RANGE_LIGHTS;

	/**
	 * @en Switching
	 */
	public static ResKey RANGE_SWITCHING;

	/**
	 * @en Without floor
	 */
	public static ResKey RANGE_NO_FLOOR;

	/**
	 * @en {0} to {1}
	 */
	public static ResKey2 DETAIL_RANGE__START_END;

	/**
	 * @en Address {0}, identifier {1}, datapoint type {2}
	 */
	public static ResKey3 DETAIL_ADDRESS__ADDRESS_IDENTIFIER_TYPE;

	/**
	 * @en Name "{0}" becomes "{1}".
	 */
	public static ResKey2 DETAIL_RENAME__OLD_NEW;

	/**
	 * @en Datapoint type {0} becomes {1}.
	 */
	public static ResKey2 DETAIL_TYPE__OLD_NEW;

	/**
	 * @en {0}: {1}
	 */
	public static ResKey2 OBJECT_IN_DEVICE__DEVICE_OBJECT;

	/**
	 * @en Sends to {0}
	 */
	public static ResKey1 DETAIL_SENDS__ADDRESS;

	/**
	 * @en Receives from {0}
	 */
	public static ResKey1 DETAIL_RECEIVES__ADDRESS;

	/**
	 * @en {0} becomes {1}
	 */
	public static ResKey2 DETAIL_VALUE__OLD_NEW;

	/**
	 * @en {0} becomes the default {1}
	 */
	public static ResKey2 DETAIL_RESET__OLD_DEFAULT;

	/**
	 * @en Applies "{0}"
	 */
	public static ResKey1 DETAIL_PRESET__PRESET;

	/**
	 * @en "{0}" becomes "{1}"
	 */
	public static ResKey2 DETAIL_PRESET_CHANGE__OLD_NEW;

	/**
	 * @en Takes back "{0}"; the values stay unless listed.
	 */
	public static ResKey1 DETAIL_PRESET_REMOVE__PRESET;

	/**
	 * @en Kind {0} becomes {1}
	 */
	public static ResKey2 DETAIL_KIND__OLD_NEW;

	/**
	 * @en Role {0}: {1}
	 */
	public static ResKey2 DETAIL_ROLE__ROLE_ADDRESS;

	/**
	 * @en Changed by hand (derived: {0}); not overwritten.
	 */
	public static ResKey1 KEEP_CHANGED__DERIVED;

	/**
	 * @en Removed by hand; not created again.
	 */
	public static ResKey KEEP_ADDRESS_REMOVED;

	/**
	 * @en Removed by hand; not connected again.
	 */
	public static ResKey KEEP_CONNECTION_REMOVED;

	/**
	 * @en Set by hand to {0} (derived: {1}); not overwritten.
	 */
	public static ResKey2 KEEP_VALUE_CHANGED__CURRENT_DERIVED;

	/**
	 * @en The object sends to {0} already; not connected.
	 */
	public static ResKey1 KEEP_SENDS_ALREADY__ADDRESS;

	/**
	 * @en The role {0} is bound by hand to {1}; not changed.
	 */
	public static ResKey2 KEEP_ROLE_BOUND__ROLE_ADDRESS;

	/**
	 * @en No longer derived, but still used by other connections or functions; kept.
	 */
	public static ResKey KEEP_ADDRESS_USED;

	/**
	 * @en No longer derived, but changed by hand; left as it is.
	 */
	public static ResKey KEEP_LEFT_CHANGED;

	/**
	 * @en The function has controls but no actuator preset.
	 */
	public static ResKey ERROR_FUNCTION_NO_PRESET;

	/**
	 * @en The function names no channel of a device.
	 */
	public static ResKey ERROR_FUNCTION_NO_CHANNEL;

	/**
	 * @en "{0}" is a source preset; a function needs an actuator preset.
	 */
	public static ResKey1 ERROR_FUNCTION_SOURCE_PRESET__PRESET;

	/**
	 * @en Functions of the kind {0} are not derived yet; lights and switches are.
	 */
	public static ResKey1 ERROR_KIND_NOT_YET__KIND;

	/**
	 * @en The preset "{0}" names no communication object of the role {1}.
	 */
	public static ResKey2 ERROR_PRESET_NO_ROLE_OBJECT__PRESET_ROLE;

	/**
	 * @en No free group address in {0}.
	 */
	public static ResKey1 ERROR_NO_FREE_ADDRESS__RANGE;

	/**
	 * @en The function {0} is not derived: {1}
	 */
	public static ResKey2 ERROR_CONTROL_FUNCTION__FUNCTION_REASON;

	/**
	 * @en The control names no operation.
	 */
	public static ResKey ERROR_CONTROL_NO_OPERATION;

	/**
	 * @en The control names no channel of a device.
	 */
	public static ResKey ERROR_CONTROL_NO_SOURCE;

	/**
	 * @en {0} has no role for "{1}": its preset names no communication object of the role {2}.
	 */
	public static ResKey3 ERROR_CONTROL_NO_ROLE__FUNCTION_OPERATION_ROLE;

	/**
	 * @en {0} has no role for "{1}": a function of the kind {2} has none.
	 */
	public static ResKey3 ERROR_CONTROL_KIND_NO_ROLE__FUNCTION_OPERATION_KIND;

	/**
	 * @en The control has no source preset.
	 */
	public static ResKey ERROR_CONTROL_NO_PRESET;

	/**
	 * @en "{0}" is an actuator preset; a control needs a source preset.
	 */
	public static ResKey1 ERROR_CONTROL_ACTUATOR_PRESET__PRESET;

	/**
	 * @en The preset "{0}" cannot do "{1}".
	 */
	public static ResKey2 ERROR_PRESET_CANNOT__PRESET_OPERATION;

	/**
	 * @en The preset "{0}" names no communication object sending the command.
	 */
	public static ResKey1 ERROR_PRESET_NO_SENDER__PRESET;

	/**
	 * @en The channel is the source of {0} already.
	 */
	public static ResKey1 ERROR_CHANNEL_USED__OTHER;

	/**
	 * @en Conflicts with {0}: {1}
	 */
	public static ResKey2 ERROR_CONFLICT__OTHER_REASON;

	/**
	 * @en The channel has the preset "{0}" applied by hand.
	 */
	public static ResKey1 ERROR_HAND_PRESET__PRESET;

	/**
	 * @en The installation changed since the preview. Derive again.
	 */
	public static ResKey ERROR_CHANGED_SINCE_PREVIEW;

	/**
	 * @en {0} of {1}, {2}
	 */
	public static ResKey3 CONTROL_LABEL__CHANNEL_DEVICE_OPERATION;

	/**
	 * @en The derivation made {0} changes.
	 */
	public static ResKey1 DERIVED__COUNT;

	/**
	 * @en The derivation has nothing to change.
	 */
	public static ResKey NOTHING_DERIVED;

	/**
	 * @en Select a device, a channel and an operation.
	 */
	public static ResKey ERROR_CONTROL_INCOMPLETE;

	static {
		initConstants(I18NConstants.class);
	}
}
