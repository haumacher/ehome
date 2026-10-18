/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The {@link GroupDevice}s of the devices of a project: what each device does with group telegrams
 * as its project data says, for a virtual installation.
 *
 * <ul>
 * <li>A device with an individual address, with an object per communication object it uses (its
 * connectors in use) that is connected to at least one group address.</li>
 * <li>An object's flags are the device's own, else those of the object's placement in effect, else
 * the object's (as the download computes them); its size is the one in effect.</li>
 * <li>Its addresses are those of its connections; the sending one is that of the connection
 * marked as sending.</li>
 * </ul>
 *
 * <p>
 * The project says nothing about what the application of a device does between its objects (a
 * channel's state following its switching object): the devices hold, receive and answer values,
 * and send what they are given ({@link GroupDevice#set(int, byte[])}).
 * </p>
 */
public class ProjectGroupDevices {

	/**
	 * The group devices of the devices of the project, by their order in the project.
	 */
	public static List<GroupDevice> ofProject(TLObject project) {
		List<GroupDevice> result = new ArrayList<>();
		for (TLObject device : DeviceChanges.devices(project)) {
			GroupDevice groupDevice = ofDevice(device);
			if (groupDevice != null) {
				result.add(groupDevice);
			}
		}
		return result;
	}

	/**
	 * The group device of a device of a project, <code>null</code> for a device without an
	 * individual address or without a connected object.
	 */
	public static GroupDevice ofDevice(TLObject device) {
		Integer address = DownloadImage.individualAddress(device);
		if (address == null) {
			return null;
		}
		GroupDevice result = new GroupDevice(GroupAddressConnections.deviceLabel(device), address.intValue());
		boolean any = false;
		Set<Integer> numbers = new HashSet<>();
		for (TLObject connector : new DeviceConfiguration(device).getConnectors()) {
			if (!(connector.tValueByName("port") instanceof Interface placed)) {
				continue;
			}
			Port port = placed.getPort();
			if (!numbers.add(Integer.valueOf(port.getIndex()))) {
				// A second connector of the same object: the first one counts, as for the download.
				continue;
			}
			List<Integer> addresses = new ArrayList<>();
			int send = -1;
			for (TLObject connection : objects(connector.tValueByName("connections"))) {
				TLObject target = connection.tValueByName("target") instanceof TLObject t ? t : null;
				if (target == null || !(target.tValueByName("address") instanceof GA ga)) {
					continue;
				}
				Integer value = Integer.valueOf(ga.getValue());
				if (!addresses.contains(value)) {
					addresses.add(value);
				}
				if (send < 0 && connection.tValueByName("kind") instanceof TLClassifier kind && "send".equals(kind.getName())) {
					send = value.intValue();
				}
			}
			if (addresses.isEmpty()) {
				continue;
			}
			int flags = (enabled("communication", connector, placed, port) ? GroupObject.COMMUNICATION : 0)
				| (enabled("canRead", connector, placed, port) ? GroupObject.READ : 0)
				| (enabled("canWrite", connector, placed, port) ? GroupObject.WRITE : 0)
				| (enabled("transmission", connector, placed, port) ? GroupObject.TRANSMIT : 0)
				| (enabled("update", connector, placed, port) ? GroupObject.UPDATE : 0);
			int bits = placed.getSize() != null ? placed.getSize().intValue() : port.getSize();
			result.add(new GroupObject(port.getIndex(), GroupAddressConnections.objectLabel(placed), bits, flags, send,
				addresses));
			any = true;
		}
		return any ? result : null;
	}

	private static boolean enabled(String flag, TLObject connector, Interface placed, Port port) {
		for (TLObject holder : Arrays.asList(connector, placed, port)) {
			if (holder != null && holder.tValueByName(flag) instanceof TLClassifier classifier) {
				return "Enabled".equals(classifier.getName());
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static Collection<TLObject> objects(Object value) {
		return value instanceof Collection<?> collection ? (Collection<TLObject>) collection : List.of();
	}

}
