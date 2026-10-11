/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.GA;

/**
 * The devices placed in the parts of a building (<code>ehome.physical:Device#location</code>, seen
 * from the part as <code>ehome.building:BuildingDefinition#devices</code>), and the group addresses
 * in use in a part.
 *
 * <p>
 * ETS places devices into building parts, not group addresses: the group addresses in use in a part
 * are derived from the connections of the devices placed in it and in its sub-parts, and are stored
 * nowhere.
 * </p>
 */
public class BuildingDevices {

	private static final String LOCATION = "location";

	private static final String DEVICES = "devices";

	private static final String PARTS = "parts";

	private static final String PATH_SEPARATOR = " / ";

	/**
	 * The order of devices: by their individual address, devices in no line last, then by label.
	 */
	public static final Comparator<TLObject> BY_ADDRESS = Comparator
		.comparing(DownloadImage::individualAddress, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(GroupAddressConnections::deviceLabel);

	/**
	 * The devices placed directly in the given building part (not in its sub-parts), by their
	 * individual address.
	 */
	public static List<TLObject> devices(TLObject part) {
		List<TLObject> result = new ArrayList<>();
		for (Object device : (Collection<?>) part.tValueByName(DEVICES)) {
			result.add((TLObject) device);
		}
		result.sort(BY_ADDRESS);
		return result;
	}

	/**
	 * The devices placed in the given building part and in all its sub-parts.
	 */
	public static Set<TLObject> allDevices(TLObject part) {
		Set<TLObject> result = new LinkedHashSet<>();
		collectDevices(part, result);
		return result;
	}

	private static void collectDevices(TLObject part, Set<TLObject> result) {
		result.addAll(devices(part));
		for (Object child : (Collection<?>) part.tValueByName(PARTS)) {
			collectDevices((TLObject) child, result);
		}
	}

	/**
	 * The group addresses in use in the given building part: the connections
	 * (<code>ehome.physical:Connection</code>) of the devices placed in the part and its sub-parts,
	 * by group address, the sending ones first, then by the individual address of the device and the
	 * number of the object.
	 */
	public static List<TLObject> connections(TLObject part) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject device : allDevices(part)) {
			for (Object connector : (Collection<?>) device.tValueByName("connectors")) {
				for (Object connection : (Collection<?>) ((TLObject) connector).tValueByName("connections")) {
					TLObject value = (TLObject) connection;
					if (value.tValueByName("target") instanceof TLObject
						&& GroupAddressConnections.object(value) != null) {
						result.add(value);
					}
				}
			}
		}
		result.sort(Comparator
			.comparingInt((TLObject c) -> addressValue((TLObject) c.tValueByName("target")))
			.thenComparing(c -> isSend(c) ? 0 : 1)
			.thenComparing(c -> GroupAddressConnections.device(c), BY_ADDRESS)
			.thenComparingInt(c -> GroupAddressConnections.object(c).getPort().getIndex()));
		return result;
	}

	/**
	 * The devices of the project that can be placed in the given building part: those not placed
	 * anywhere first, then those placed in another part, each by individual address.
	 */
	public static List<TLObject> choices(TLObject part) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject device : GroupAddressConnections.devices(part)) {
			if (device.tValueByName(LOCATION) != part) {
				result.add(device);
			}
		}
		result.sort(Comparator.comparingInt((TLObject d) -> d.tValueByName(LOCATION) == null ? 0 : 1)
			.thenComparing(BY_ADDRESS));
		return result;
	}

	/**
	 * The path of the given building part from its building, e.g. "Haus / Erdgeschoss / Küche";
	 * <code>null</code> for no part.
	 */
	public static String path(TLObject part) {
		if (part == null) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for (TLObject current = part; current != null && isA(current, "ehome.building:BuildingDefinition");
				current = current.tContainer()) {
			names.add(0, MetaLabelProvider.INSTANCE.getLabel(current));
		}
		return String.join(PATH_SEPARATOR, names);
	}

	/**
	 * Places the device in the given building part, <code>null</code> removes it from its part.
	 *
	 * @throws TopLogicException
	 *         If the part belongs to another project than the device.
	 */
	public static void place(TLObject device, TLObject part) {
		if (part != null && project(part) != project(device)) {
			throw new TopLogicException(I18NConstants.ERROR_OTHER_PROJECT__DEVICE_PART.fill(
				GroupAddressConnections.deviceLabel(device), path(part)));
		}
		device.tUpdateByName(LOCATION, part);
	}

	private static int addressValue(TLObject groupAddress) {
		return groupAddress.tValueByName("address") instanceof GA address ? address.getValue() : Integer.MAX_VALUE;
	}

	private static boolean isSend(TLObject connection) {
		return connection.tValueByName("kind") instanceof TLClassifier classifier
			&& "send".equals(classifier.getName());
	}

	private static TLObject project(TLObject obj) {
		for (TLObject current = obj; current != null; current = current.tContainer()) {
			if (isA(current, "ehome.project:Project")) {
				return current;
			}
		}
		return null;
	}

	private static boolean isA(TLObject obj, String typeName) {
		return TLModelUtil.isGeneralization((TLClass) TLModelUtil.findType(typeName), obj.tType());
	}

}
