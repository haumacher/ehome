/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.basic.util.ResKey5;
import com.top_logic.basic.util.ResKeyN;
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
	 * @en The program sets "{0}" to the value of "{1}": change "{1}" instead.
	 */
	public static ResKey2 ERROR_ASSIGNED_FROM__PARAMETER_SOURCE;

	/**
	 * @en The program sets the value of "{0}" under the current values of the device.
	 */
	public static ResKey1 ERROR_ASSIGNED__PARAMETER;

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

	/**
	 * @en Segment {0}
	 */
	public static ResKey1 BLOCK_SEGMENT__SEGMENT;

	/**
	 * @en Relative segment {0}
	 */
	public static ResKey1 BLOCK_RELATIVE_SEGMENT__SEGMENT;

	/**
	 * @en Group address table
	 */
	public static ResKey BLOCK_ADDRESS_TABLE;

	/**
	 * @en Association table
	 */
	public static ResKey BLOCK_ASSOCIATION_TABLE;

	/**
	 * @en Object table
	 */
	public static ResKey BLOCK_OBJECT_TABLE;

	/**
	 * @en Download image of {0}
	 */
	public static ResKey1 DUMP_TITLE__DEVICE;

	/**
	 * @en {0}, {1}-{2}, {3} bytes
	 */
	public static ResKey4 DUMP_BLOCK__BLOCK_FROM_TO_LENGTH;

	/**
	 * @en {0} (load state machine {1}), {2}-{3}, {4} bytes
	 */
	public static ResKey5 DUMP_RELATIVE_BLOCK__BLOCK_LSM_FROM_TO_LENGTH;

	/**
	 * @en ; {0} at {1} ({2} bytes)
	 */
	public static ResKey3 DUMP_CONTAINED_TABLE__TABLE_ADDRESS_LENGTH;

	/**
	 * @en Properties
	 */
	public static ResKey DUMP_PROPERTIES;

	/**
	 * @en Object {0}, property {1}:
	 */
	public static ResKey2 DUMP_PROPERTY__OBJECT_PROPERTY;

	/**
	 * @en Object type {0}, property {1}:
	 */
	public static ResKey2 DUMP_PROPERTY_OF_TYPE__TYPE_PROPERTY;

	/**
	 * @en Object type {0} #{1}, property {2}:
	 */
	public static ResKey3 DUMP_PROPERTY_OF_TYPE_OCCURRENCE__TYPE_OCCURRENCE_PROPERTY;

	/**
	 * @en Problems
	 */
	public static ResKey DUMP_PROBLEMS;

	/**
	 * @en A value must have at least one bit, not {0}.
	 */
	public static ResKey1 ENCODE_NO_BITS__SIZE;

	/**
	 * @en The value {0} does not fit into {1} bits.
	 */
	public static ResKey2 ENCODE_TOO_LARGE__VALUE_SIZE;

	/**
	 * @en The negative value {0} is no unsigned integer.
	 */
	public static ResKey1 ENCODE_NEGATIVE__VALUE;

	/**
	 * @en The value {0} does not fit into a signed integer of {1} bits.
	 */
	public static ResKey2 ENCODE_SIGNED_RANGE__VALUE_SIZE;

	/**
	 * @en The value {0} is outside the range of DPT 9.
	 */
	public static ResKey1 ENCODE_DPT9_RANGE__VALUE;

	/**
	 * @en The text "{0}" has more than {1} bytes.
	 */
	public static ResKey2 ENCODE_TEXT_LENGTH__TEXT_MAX;

	/**
	 * @en "{0}" is no date.
	 */
	public static ResKey1 ENCODE_NO_DATE__TEXT;

	/**
	 * @en The year {0} is outside the range of DPT 11 (1990 to 2089).
	 */
	public static ResKey1 ENCODE_YEAR_RANGE__YEAR;

	/**
	 * @en "{0}" is no IP address.
	 */
	public static ResKey1 ENCODE_NO_IP__TEXT;

	/**
	 * @en A little endian value must start at a byte and have whole bytes, not {0} bits at bit {1}.
	 */
	public static ResKey2 ENCODE_LITTLE_ENDIAN__SIZE_START;

	/**
	 * @en A value of {0} bits at bit {1} exceeds the memory of {2} bytes.
	 */
	public static ResKey3 ENCODE_OUTSIDE_MEMORY__SIZE_START_LENGTH;

	/**
	 * @en There is no value.
	 */
	public static ResKey ENCODE_NO_VALUE;

	/**
	 * @en The time unit {0} is not supported.
	 */
	public static ResKey1 ENCODE_TIME_UNIT__UNIT;

	/**
	 * @en "{0}" is no number.
	 */
	public static ResKey1 ENCODE_NO_NUMBER__TEXT;

	/**
	 * @en The parameter type {0} is not supported.
	 */
	public static ResKey1 ENCODE_TYPE__TYPE;

	/**
	 * @en The value {0} of the entry {1} does not fit into {2} bits.
	 */
	public static ResKey3 ENCODE_ENTRY_TOO_LARGE__VALUE_ENTRY_SIZE;

	/**
	 * @en The text encoding {0} is not known.
	 */
	public static ResKey1 ENCODE_TEXT_ENCODING__NAME;

	/**
	 * @en "{0}" is no integer.
	 */
	public static ResKey1 ENCODE_NO_INTEGER__TEXT;

	/**
	 * @en Parameter {0}: {1}
	 */
	public static ResKey2 IMAGE_PARAMETER__PARAMETER_REASON;

	/**
	 * @en Parameter {0}: its segment {1} is not written.
	 */
	public static ResKey2 IMAGE_PARAMETER_SEGMENT__PARAMETER_SEGMENT;

	/**
	 * @en The device has group addresses, but its program no group address table.
	 */
	public static ResKey IMAGE_NO_ADDRESS_TABLE;

	/**
	 * @en The device has {0} group addresses, the program allows {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_ADDRESSES__COUNT_MAX;

	/**
	 * @en The device has {0} associations, the program allows {1}.
	 */
	public static ResKey2 IMAGE_TOO_MANY_ASSOCIATIONS__COUNT_MAX;

	/**
	 * @en The place of the group address table is not known.
	 */
	public static ResKey IMAGE_ADDRESS_TABLE_PLACE;

	/**
	 * @en The place of the association table is not known.
	 */
	public static ResKey IMAGE_ASSOCIATION_TABLE_PLACE;

	/**
	 * @en The table "{0}" ({1}-{2}) exceeds its segment {3}.
	 */
	public static ResKey4 IMAGE_TABLE_EXCEEDS_SEGMENT__TABLE_FROM_TO_SEGMENT;

	/**
	 * @en The program places the {0} at {1}, mask {2} at {3}.
	 */
	public static ResKey4 IMAGE_FIXED_PLACE__RESOURCE_ADDRESS_MASK_EXPECTED;

	/**
	 * @en Mask {0} has no pointer {1} of format {2} in memory; the place of the table is not known to the device.
	 */
	public static ResKey3 IMAGE_NO_POINTER__MASK_RESOURCE_FORMAT;

	/**
	 * @en The table at {0} cannot be addressed by the pointer {1} ({2}).
	 */
	public static ResKey3 IMAGE_POINTER_RANGE__TABLE_RESOURCE_ADDRESS;

	/**
	 * @en The pointer {0} ({1}) lies in no segment of the program.
	 */
	public static ResKey2 IMAGE_POINTER_OUTSIDE__RESOURCE_ADDRESS;

	/**
	 * @en The static data hold {0} (mask {1}) in the pointer {2} ({3}), the table lies at {4}.
	 */
	public static ResKey5 IMAGE_POINTER_STATIC__VALUE_MASK_RESOURCE_ADDRESS_TABLE;

	/**
	 * @en The program has no mask version, the format of its tables is not known.
	 */
	public static ResKey IMAGE_NO_MASK;

	/**
	 * @en The {0} of mask {1} has the format {2}; supported is {3} (verified with mask 7.5).
	 */
	public static ResKey4 IMAGE_FORMAT__RESOURCE_MASK_FORMATS_SUPPORTED;

	/**
	 * @en A table in a relative segment is not supported.
	 */
	public static ResKey IMAGE_RELATIVE_TABLE;

	/**
	 * @en The object table has no static data in its segment.
	 */
	public static ResKey IMAGE_OBJECT_TABLE_NO_DATA;

	/**
	 * @en The option SetObjectTableLengthAlwaysToOne is not supported.
	 */
	public static ResKey IMAGE_LENGTH_ALWAYS_ONE;

	/**
	 * @en The object table lies outside its segment.
	 */
	public static ResKey IMAGE_OBJECT_TABLE_OUTSIDE;

	/**
	 * @en The object table of {0} objects exceeds its segment.
	 */
	public static ResKey1 IMAGE_OBJECT_TABLE_EXCEEDS__COUNT;

	/**
	 * @en The object table has an object {0} the program does not define.
	 */
	public static ResKey1 IMAGE_UNDEFINED_OBJECT__NUMBER;

	/**
	 * @en Object {0} has a size of {1} bits, which has no type code in {2}.
	 */
	public static ResKey3 IMAGE_OBJECT_SIZE__NUMBER_BITS_FORMAT;

	/**
	 * @en The table "{0}" ({1}-{2}) overlaps the table "{3}" ({4}-{5}).
	 */
	public static ResKeyN IMAGE_OVERLAP__TABLE_FROM_TO_OTHER_FROM_TO;

	/**
	 * @en {0} {1}
	 */
	public static ResKey2 JOIN_SENTENCES__FIRST_NEXT;

	/**
	 * @en {0}, {1}
	 */
	public static ResKey2 LIST__FIRST_NEXT;

	/**
	 * @en In ETS, the application program uses a plug-in of the manufacturer ({0}). E-Home edits and downloads the parameters and communication objects its product data declare; the functions the plug-in adds, such as the commissioning of a DALI bus, are not available in E-Home.
	 */
	public static ResKey1 NOTICE_REQUIRES_USER_INTERFACE_PLUGIN__REQUIREMENTS;

	/**
	 * @en In ETS, the application program needs what E-Home does not run: {0}. A device of it is planned, parameterized and connected in E-Home, but E-Home cannot download it.
	 */
	public static ResKey1 NOTICE_REQUIRES_NO_DOWNLOAD__REQUIREMENTS;

	/**
	 * @en In ETS, the application program needs what E-Home does not run: {0}. E-Home cannot download it.
	 */
	public static ResKey1 ERROR_DOWNLOAD_REQUIRES__REQUIREMENTS;

	static {
		initConstants(I18NConstants.class);
	}
}
