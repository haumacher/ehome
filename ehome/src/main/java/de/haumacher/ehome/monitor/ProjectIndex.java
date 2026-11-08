/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.runtime.DatapointNames;

/**
 * What the group monitor knows of a project: its group addresses with their names and datapoint
 * types, its devices by their individual addresses, and the labels of the datapoints of its image.
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

	private final Map<String, String> _datapointLabels;

	private ProjectIndex(TLObject project, GroupAddressStyle style, Map<Integer, AddressInfo> addresses,
			Map<Integer, DeviceInfo> devices, Map<String, String> datapointLabels) {
		_project = project;
		_style = style;
		_addresses = Collections.unmodifiableMap(addresses);
		_devices = Collections.unmodifiableMap(devices);
		_datapointLabels = Map.copyOf(datapointLabels);
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
			Dpt dpt = GroupAddressTypes.effective(groupAddress);
			String label = GroupAddressTypes.label(groupAddress);
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
		return new ProjectIndex(project, style, addresses, devices, DatapointNames.of(project).labels());
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
	 * The datapoint of the controller's image with the given id as a person reads it:
	 * <code>Licht Flur (11/0/4)</code> ({@link DatapointNames}, haumacher/ehome #113).
	 */
	public String datapointName(String id) {
		return DatapointNames.format(_datapointLabels.get(id), id);
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

}
