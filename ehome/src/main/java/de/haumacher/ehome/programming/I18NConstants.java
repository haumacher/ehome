/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

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
	 * @en "{0}" is not part of the configuration of the device under its current values.
	 */
	public static ResKey1 ERROR_NOT_IN_CONFIGURATION__ELEMENT;

	/**
	 * @en You may not change the device "{0}".
	 */
	public static ResKey1 ERROR_NOT_ALLOWED__DEVICE;

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

	/**
	 * @en {0} is switched off by the parameters of its device but still connected: it neither sends
	 *     nor receives on this group address. Remove the connection, or switch the object on in the
	 *     parameters of the device.
	 */
	public static ResKey1 WARNING_NOT_IN_EFFECT__OBJECT;

	/**
	 * @en The object {0} is switched off by the parameters of the device but still connected to {1}:
	 *     it neither sends nor receives there. Remove its connections, or switch it on in the
	 *     parameters.
	 */
	public static ResKey2 WARNING_OBJECT_NOT_IN_EFFECT__OBJECT_ADDRESSES;

	/**
	 * @en Communication objects
	 */
	public static ResKey CHANGES_OBJECTS;

	/**
	 * @en Not in the configuration
	 */
	public static ResKey CHANGES_NOT_SHOWN;

	/**
	 * @en not connected
	 */
	public static ResKey NOT_CONNECTED;

	/**
	 * @en {0} ({1})
	 */
	public static ResKey2 CONNECTION__ADDRESS_KIND;

	/**
	 * @en Device: {0}
	 */
	public static ResKey1 HINT_DEVICE__VALUE;

	/**
	 * @en The group address {0} "{1}" was connected to at the last download no longer exists.
	 */
	public static ResKey2 ERROR_ADDRESS_GONE__ADDRESS_OBJECT;

	/**
	 * @en No download of "{0}" is known: there is no state of the device to go back to.
	 */
	public static ResKey1 ERROR_NO_DOWNLOAD__DEVICE;

	/**
	 * @en "{0}" has no changes since its last download.
	 */
	public static ResKey1 ERROR_NO_CHANGES__DEVICE;

	/**
	 * @en No device of the project has changes since its last download.
	 */
	public static ResKey ERROR_NO_CHANGES_PROJECT;

	/**
	 * @en Discard all changes of "{0}" since its last download (changes: {1})? Its values and connections are set back to the state of the last download.
	 */
	public static ResKey2 CONFIRM_DISCARD__DEVICE_COUNT;

	/**
	 * @en Discard all changes of the devices since their last download (changes: {0}, devices: {1})? Their values and connections are set back to the state of their last download.
	 */
	public static ResKey2 CONFIRM_DISCARD_PROJECT__COUNT_DEVICES;

	/**
	 * @en No group range is chosen.
	 */
	public static ResKey ERROR_NO_GROUP_RANGE;

	/**
	 * @en No address is entered.
	 */
	public static ResKey ERROR_NO_ADDRESS_NUMBER;

	/**
	 * @en The address {0} lies outside the range "{1}" ({2} to {3}).
	 */
	public static ResKey4 ERROR_OUTSIDE_RANGE__ADDRESS_RANGE_START_END;

	/**
	 * @en The address {0} lies in the range "{1}" below; create it there.
	 */
	public static ResKey2 ERROR_IN_SUB_RANGE__ADDRESS_RANGE;

	/**
	 * @en The address {0} is taken already by "{1}".
	 */
	public static ResKey2 ERROR_ADDRESS_TAKEN__ADDRESS_EXISTING;

	/**
	 * @en "{0}" cannot be placed in "{1}": the building part belongs to another project.
	 */
	public static ResKey2 ERROR_OTHER_PROJECT__DEVICE_PART;

	/**
	 * @en {0} cannot be created in "{1}".
	 */
	public static ResKey2 ERROR_CANNOT_CONTAIN__TYPE_CONTAINER;

	/**
	 * @en No building part is to be moved.
	 */
	public static ResKey ERROR_NOTHING_TO_MOVE;

	/**
	 * @en The building parts cannot be moved there.
	 */
	public static ResKey ERROR_NO_CONTAINER;

	/**
	 * @en "{0}" is no building part.
	 */
	public static ResKey1 ERROR_NO_BUILDING_PART__OBJECT;

	/**
	 * @en "{0}" belongs to another project.
	 */
	public static ResKey1 ERROR_OTHER_PROJECT__PART;

	/**
	 * @en "{0}" cannot be moved into itself.
	 */
	public static ResKey1 ERROR_INSIDE_ITSELF__PART;

	/**
	 * @en "{0}" cannot be moved into "{1}".
	 */
	public static ResKey2 ERROR_CANNOT_MOVE__PART_CONTAINER;

	/**
	 * @en "{0}" is no datapoint type.
	 */
	public static ResKey1 ERROR_NO_DATAPOINT_TYPE__VALUE;

	/**
	 * @en The values of {0} have {1} bits, the objects connected to the address have {2} bits.
	 */
	public static ResKey3 ERROR_TYPE_SIZE_DIFFERS__TYPE_TYPE_SIZE_OBJECT_SIZE;

	/**
	 * @en The module "{0}" (instance {1}) needs a value of the allocator "{2}" beyond its limit {3}.
	 */
	public static ResKey4 ERROR_ALLOCATOR_LIMIT__MODULE_INSTANCE_ALLOCATOR_LIMIT;

	/**
	 * @en {0} – {1}
	 */
	public static ResKey2 CHANNEL_DESCRIPTION__CHANNEL_DESCRIPTION;

	/**
	 * @en {0} (inactive)
	 */
	public static ResKey1 CHANNEL_INACTIVE__CHANNEL;

	static {
		initConstants(I18NConstants.class);
	}
}
