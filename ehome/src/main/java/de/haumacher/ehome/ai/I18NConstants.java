/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants of E-Home's tools for the assistant (<code>de.haumacher.ehome.ai</code>
 * and <code>de.haumacher.ehome.ai.logic</code>): the commit messages of their writing calls (TL #30276).
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Building parts created in {0}: {1}
	 */
	public static ResKey2 COMMIT_BUILDING_PARTS_CREATED__PARENT_PARTS;

	/**
	 * @en Building parts moved to {0}: {1}
	 */
	public static ResKey2 COMMIT_BUILDING_PARTS_MOVED__CONTAINER_PARTS;

	/**
	 * @en Device {0} placed in {1}
	 */
	public static ResKey2 COMMIT_DEVICE_PLACED__DEVICE_LOCATION;

	/**
	 * @en Device {0} removed from its building part
	 */
	public static ResKey1 COMMIT_DEVICE_UNPLACED__DEVICE;

	/**
	 * @en Device planned in line {0}: {1}
	 */
	public static ResKey2 COMMIT_DEVICE_PLANNED__LINE_PRODUCT;

	/**
	 * @en Device {0} duplicated
	 */
	public static ResKey1 COMMIT_DEVICE_DUPLICATED__DEVICE;

	/**
	 * @en Parameter {1} of {0} set to {2}
	 */
	public static ResKey3 COMMIT_PARAMETER_SET__DEVICE_PARAMETER_VALUE;

	/**
	 * @en Parameter {1} of {0} set back to its default
	 */
	public static ResKey2 COMMIT_PARAMETER_RESET__DEVICE_PARAMETER;

	/**
	 * @en Group addresses named in {0}: {1}
	 */
	public static ResKey2 COMMIT_GROUP_ADDRESSES_NAMED__PROJECT_ADDRESSES;

	/**
	 * @en Group address {1} created in {0}
	 */
	public static ResKey2 COMMIT_GROUP_ADDRESS_CREATED__RANGE_NAME;

	/**
	 * @en Datapoint type of {0} set to {1}
	 */
	public static ResKey2 COMMIT_GROUP_ADDRESS_TYPE_SET__ADDRESS_TYPE;

	/**
	 * @en Own datapoint type of {0} removed
	 */
	public static ResKey1 COMMIT_GROUP_ADDRESS_TYPE_REMOVED__ADDRESS;

	/**
	 * @en Object {1} of {0} connected to {2}
	 */
	public static ResKey3 COMMIT_OBJECT_CONNECTED__DEVICE_OBJECT_ADDRESS;

	/**
	 * @en Connection removed: {0}
	 */
	public static ResKey1 COMMIT_OBJECT_DISCONNECTED__CONNECTION;

	/**
	 * @en Virtual device {1} created in {0}
	 */
	public static ResKey2 COMMIT_BLOCK_CREATED__ROOM_NAME;

	/**
	 * @en Datapoints bound to the virtual device {0}
	 */
	public static ResKey1 COMMIT_BLOCK_BOUND__BLOCK;

	/**
	 * @en Rules, schedule entries and application datapoints saved in {0}: {1}
	 */
	public static ResKey2 COMMIT_AUTOMATION_SAVED__PROJECT_DEFINITIONS;

	/**
	 * @en Deleted from {0}: {1}
	 */
	public static ResKey2 COMMIT_AUTOMATION_DELETED__PROJECT_OBJECTS;

	/**
	 * @en Functions proposed for {0}
	 */
	public static ResKey1 COMMIT_FUNCTIONS_PROPOSED__PROJECT;

	/**
	 * @en Functions deleted from {0}: {1}
	 */
	public static ResKey2 COMMIT_FUNCTIONS_DELETED__PROJECT_FUNCTIONS;

	/**
	 * @en Function {1} created in {0}
	 */
	public static ResKey2 COMMIT_FUNCTION_CREATED__PROJECT_NAME;

	/**
	 * @en Control page {1} created in {0}
	 */
	public static ResKey2 COMMIT_PAGE_CREATED__PROJECT_NAME;

	static {
		initConstants(I18NConstants.class);
	}
}
