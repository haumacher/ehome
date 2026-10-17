/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * What the group monitor knows of a project: its group addresses with their names and datapoint
 * types, and its devices by their individual addresses.
 *
 * <p>
 * Built once when the monitor attaches, in the request of the user (labels in the user's language,
 * no derived attributes read): the telegrams are decoded on the thread of the link, where neither is
 * at hand.
 * </p>
 */
public class ProjectIndex {

	/**
	 * A group address of the project.
	 *
	 * @param address
	 *        The 16-bit address.
	 * @param text
	 *        The address in the notation of the project.
	 * @param name
	 *        The name of the address.
	 * @param groupAddress
	 *        The <code>ehome.logical:GroupAddress</code>.
	 * @param dpt
	 *        The datapoint type of the values sent to the address, <code>null</code> if unknown.
	 * @param dptLabel
	 *        The datapoint type for a person, e.g. "9.001 temperature (°C)", empty if unknown.
	 */
	public record AddressInfo(int address, String text, String name, TLObject groupAddress, Dpt dpt,
			String dptLabel) {
		// Data only.
	}

	/**
	 * A device of the project.
	 *
	 * @param address
	 *        The 16-bit individual address.
	 * @param label
	 *        The device by name and address, e.g. "Tastsensor 3 (1.3.50)".
	 * @param device
	 *        The <code>ehome.physical:Device</code>.
	 */
	public record DeviceInfo(int address, String label, TLObject device) {
		// Data only.
	}

	private final TLObject _project;

	private final GroupAddressStyle _style;

	private final Map<Integer, AddressInfo> _addresses;

	private final Map<Integer, DeviceInfo> _devices;

	private ProjectIndex(TLObject project, GroupAddressStyle style, Map<Integer, AddressInfo> addresses,
			Map<Integer, DeviceInfo> devices) {
		_project = project;
		_style = style;
		_addresses = Collections.unmodifiableMap(addresses);
		_devices = Collections.unmodifiableMap(devices);
	}

	/**
	 * Reads the group addresses and devices of the given project.
	 */
	public static ProjectIndex of(TLObject project) {
		GroupAddressStyle style = GroupAddressStyle.fromModel(project.tValueByName("groupAddressStyle"));
		if (style == null) {
			style = GroupAddressStyle.DEFAULT;
		}
		Map<Integer, AddressInfo> addresses = new TreeMap<>();
		for (TLObject groupAddress : DeviceSettings.groupAddresses(project)) {
			if (!(groupAddress.tValueByName("address") instanceof GA ga)) {
				continue;
			}
			TLObject type = datapointType(groupAddress);
			Dpt dpt = dpt(type);
			String label = dptLabel(type);
			if (dpt == null) {
				dpt = bySize(groupAddress);
				label = dpt == null ? "" : dpt.code();
			}
			Object name = groupAddress.tValueByName("name");
			addresses.put(ga.getValue(), new AddressInfo(ga.getValue(), style.format(ga.getValue()),
				name == null ? "" : name.toString(), groupAddress, dpt, label));
		}
		Map<Integer, DeviceInfo> devices = new HashMap<>();
		for (TLObject device : GroupAddressConnections.devices(project)) {
			Integer address = DownloadImage.individualAddress(device);
			if (address != null) {
				devices.put(address, new DeviceInfo(address, GroupAddressConnections.deviceLabel(device), device));
			}
		}
		return new ProjectIndex(project, style, addresses, devices);
	}

	/**
	 * The project.
	 */
	public TLObject getProject() {
		return _project;
	}

	/**
	 * The group addresses of the project by their 16-bit address, in ascending order.
	 */
	public Map<Integer, AddressInfo> getAddresses() {
		return _addresses;
	}

	/**
	 * The group address of the project with the given 16-bit address, <code>null</code> if the
	 * project does not know it.
	 */
	public AddressInfo address(int address) {
		return _addresses.get(address);
	}

	/**
	 * The device of the project with the given individual address, <code>null</code> if there is
	 * none.
	 */
	public DeviceInfo device(int address) {
		return _devices.get(address);
	}

	/**
	 * A group address in the notation of the project.
	 */
	public String format(int address) {
		return _style.format(address);
	}

	/**
	 * Reads a group address in the notation of the project (or in any notation with the number of
	 * levels written).
	 */
	public int parse(String text) {
		try {
			return _style.parse(text);
		} catch (RuntimeException ex) {
			return GroupAddressStyle.parseAny(text);
		}
	}

	/**
	 * The datapoint type of a group address: its own, else the one of the first connected object
	 * that has one (as <code>ehome.logical:GroupAddress#effectiveDatapointType</code>, computed
	 * without the rights of a reader).
	 */
	public static TLObject datapointType(TLObject groupAddress) {
		Object own = groupAddress.tValueByName("datapointTypes");
		if (own instanceof Collection<?> types && !types.isEmpty()) {
			return (TLObject) types.iterator().next();
		}
		for (TLObject connection : GroupAddressConnections.connections(groupAddress)) {
			TLObject type = GroupAddressConnections.datapointType(connection);
			if (type != null) {
				return type;
			}
		}
		return null;
	}

	/**
	 * The main type of the values of a group address none of whose objects has a datapoint type, by
	 * the size of the objects where it decides the main type: 1 bit (DPT 1, a switch), 4 bit (DPT 3,
	 * a step control); <code>null</code> otherwise.
	 */
	public static Dpt bySize(TLObject groupAddress) {
		Integer size = null;
		for (TLObject connection : GroupAddressConnections.connections(groupAddress)) {
			int objectSize = GroupAddressConnections.size(connection);
			if (size != null && size.intValue() != objectSize) {
				return null;
			}
			size = Integer.valueOf(objectSize);
		}
		if (size == null) {
			return null;
		}
		switch (size.intValue()) {
			case 1:
				return Dpt.mainType(1);
			case 4:
				return Dpt.mainType(3);
			default:
				return null;
		}
	}

	/**
	 * The numbers of a datapoint type (<code>ehome.datapoint:DatapointType</code>), <code>null</code>
	 * for none.
	 */
	public static Dpt dpt(TLObject type) {
		if (type == null || !(type.tValueByName("number") instanceof Integer number)) {
			return null;
		}
		if (isSubtype(type)) {
			TLObject main = type.tContainer();
			if (main == null || !(main.tValueByName("number") instanceof Integer mainNumber)) {
				return null;
			}
			return new Dpt(mainNumber, number);
		}
		return Dpt.mainType(number);
	}

	private static String dptLabel(TLObject type) {
		Dpt dpt = dpt(type);
		if (dpt == null) {
			return "";
		}
		Object description = type.tValueByName("description");
		String text = description instanceof ResKey key ? Resources.getInstance().getString(key, null) : null;
		return text == null || text.isEmpty() ? dpt.code() : dpt.code() + " " + text;
	}

	private static boolean isSubtype(TLObject type) {
		return TLModelUtil.isGeneralization((TLClass) TLModelUtil.findType("ehome.datapoint:DatapointSubtype"),
			type.tType());
	}

}
