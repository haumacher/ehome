/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.util.List;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.zigbee.ZigbeeDevice;
import de.haumacher.ehome.model.zigbee.ZigbeeFunction;
import de.haumacher.ehome.model.zigbee.catalog.EhomeZigbeeCatalogFactory;
import de.haumacher.ehome.model.zigbee.catalog.ZigbeeDeviceType;
import de.haumacher.ehome.model.zigbee.catalog.ZigbeeFunctionEntry;

/**
 * The catalog of known Zigbee devices (haumacher/ehome #64): shared master data
 * (<code>ehome.zigbee.catalog:ZigbeeCatalog</code>, the module's singleton) that says what a device of
 * a manufacturer and model is (<code>ZigbeeDeviceType</code>) and, per function of the device
 * (endpoint and function key, <code>ZigbeeFunctionEntry</code>), its name for the installer, the
 * datapoint type and unit recommended for it, whether it means nothing to the installer (hidden) and
 * the reporting recommended for it.
 *
 * <p>
 * The match of a project's device to its type is derived whenever it is read (by the manufacturer and
 * model the device reports, exactly), never copied into the project: a type added or changed later
 * applies to the devices already imported, and nothing of the catalog is stored on a device until the
 * installer creates datapoints from it ({@link ZigbeeBindings#createDatapoint(TLObject)}).
 * </p>
 */
public final class ZigbeeCatalog {

	/** The module of the catalog. */
	public static final String MODULE = "ehome.zigbee.catalog";

	/** The type of the catalog, the module's singleton. */
	public static final String CATALOG = MODULE + ":ZigbeeCatalog";

	/** The type of a device type. */
	public static final String DEVICE_TYPE = MODULE + ":ZigbeeDeviceType";

	/** The type of a function entry of a device type. */
	public static final String FUNCTION_ENTRY = MODULE + ":ZigbeeFunctionEntry";

	private ZigbeeCatalog() {
		// Static helpers.
	}

	/**
	 * The catalog of the application, <code>null</code> before the model has created it.
	 */
	public static TLObject root() {
		TLModule module = TLModelUtil.findType(CATALOG).getModule();
		return module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
	}

	/**
	 * The device types of the catalog, in its order.
	 */
	public static List<TLObject> types(TLObject catalog) {
		return catalog == null ? List.of()
			: ZigbeeImport.objects(((de.haumacher.ehome.model.zigbee.catalog.ZigbeeCatalog) catalog).getDeviceTypes());
	}

	/**
	 * The type of the catalog with exactly the given manufacturer and model, <code>null</code> for
	 * none.
	 */
	public static TLObject type(TLObject catalog, Object manufacturer, Object model) {
		if (manufacturer == null || model == null) {
			return null;
		}
		for (TLObject type : types(catalog)) {
			ZigbeeDeviceType typed = (ZigbeeDeviceType) type;
			if (manufacturer.equals(typed.getManufacturer()) && model.equals(typed.getModel())) {
				return type;
			}
		}
		return null;
	}

	/**
	 * The type of the application's catalog the device (<code>ehome.zigbee:ZigbeeDevice</code>) is,
	 * by the manufacturer and model it reports; <code>null</code> for an unknown device.
	 */
	public static TLObject type(TLObject device) {
		if (device == null || !device.tValid()) {
			return null;
		}
		ZigbeeDevice typed = (ZigbeeDevice) device;
		return type(root(), typed.getManufacturer(), typed.getModel());
	}

	/**
	 * The function entries of the device type, in their order.
	 */
	public static List<TLObject> entries(TLObject type) {
		return type == null ? List.of() : ZigbeeImport.objects(((ZigbeeDeviceType) type).getFunctions());
	}

	/**
	 * The entry of the device type for the endpoint and function key, <code>null</code> for none.
	 */
	public static TLObject entry(TLObject type, Object endpoint, Object function) {
		if (!(endpoint instanceof Number number) || function == null) {
			return null;
		}
		for (TLObject entry : entries(type)) {
			Number other = endpoint(entry);
			if (other != null && other.longValue() == number.longValue()
				&& function.equals(((ZigbeeFunctionEntry) entry).getFunction())) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * The catalog's entry for the function of a device (<code>ehome.zigbee:ZigbeeFunction</code>),
	 * <code>null</code> if its device has no type or the type does not list the function.
	 */
	public static TLObject entry(TLObject function) {
		if (function == null || !function.tValid()) {
			return null;
		}
		TLObject type = type(function.tContainer());
		if (type == null) {
			return null;
		}
		ZigbeeFunction typed = (ZigbeeFunction) function;
		return entry(type, typed.getEndpointOrNull(), typed.getFunction());
	}

	/**
	 * The name the catalog gives the function, <code>null</code> for none.
	 */
	public static ResKey name(TLObject function) {
		return entryName(entry(function));
	}

	/**
	 * The name of a function entry or device type of the catalog, <code>null</code> for none (or no
	 * object).
	 */
	public static ResKey entryName(TLObject entry) {
		if (entry instanceof ZigbeeFunctionEntry functionEntry) {
			return functionEntry.getNameKey();
		} else if (entry instanceof ZigbeeDeviceType type) {
			return type.getNameKey();
		} else {
			return null;
		}
	}

	/**
	 * The endpoint of a function entry, <code>null</code> where it is unset (the primitive getter
	 * fails there).
	 */
	public static Number endpoint(TLObject entry) {
		return entry.tValue(EhomeZigbeeCatalogFactory.getEndpointZigbeeFunctionEntryAttr()) instanceof Number number
			? number : null;
	}

	/**
	 * Whether the function entry is marked hidden; <code>false</code> where it is unset (the
	 * primitive getter fails there).
	 */
	public static boolean entryHidden(TLObject entry) {
		return Boolean.TRUE.equals(entry.tValue(EhomeZigbeeCatalogFactory.getHiddenZigbeeFunctionEntryAttr()));
	}

	/**
	 * Whether the catalog marks the function as meaning nothing to the installer (a quirk of the
	 * device, such as the controller endpoint of a Hue motion sensor that is no button).
	 */
	public static boolean hidden(TLObject function) {
		TLObject entry = entry(function);
		return entry != null && entryHidden(entry);
	}

	/**
	 * The name of the value type the catalog recommends for the entry's datapoint, <code>null</code>
	 * for none.
	 */
	public static String recommendedType(TLObject entry) {
		TLClassifier type = entry == null ? null : ((ZigbeeFunctionEntry) entry).getType();
		return type != null ? type.getName() : null;
	}

	/**
	 * The unit the catalog recommends for the entry's datapoint, <code>null</code> for none.
	 */
	public static String recommendedUnit(TLObject entry) {
		String unit = entry == null ? null : ((ZigbeeFunctionEntry) entry).getUnit();
		return unit != null && !unit.isBlank() ? unit : null;
	}

}
