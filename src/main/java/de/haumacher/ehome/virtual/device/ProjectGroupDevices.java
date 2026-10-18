/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The program data of a device says nothing about what its application does between its objects (a
 * channel's state following its switching object): the devices hold, receive and answer values,
 * and send what they are given ({@link GroupDevice#set(int, byte[])}). Which object follows which
 * the user says with the object followers of the project
 * (<code>ehome.project:Project#simulationFollowers</code>, <code>ehome.runtime:ObjectFollower</code>,
 * {@link GroupDevice#follow(int, int, boolean)}), naming two communication objects (connectors) of
 * the device; a follower that cannot be applied (incomplete, an object the device does not use or that
 * has no group address, an inverted object of more than one bit) is left out with a problem.
 * </p>
 */
public class ProjectGroupDevices {

	/**
	 * The group devices of the devices of the project, by their order in the project.
	 */
	public static List<GroupDevice> ofProject(TLObject project) {
		return ofProject(project, new ArrayList<>());
	}

	/**
	 * The group devices of the devices of the project, by their order in the project, with the
	 * object followers of the project.
	 *
	 * @param problems
	 *        Receives why a follower of the project is left out.
	 */
	public static List<GroupDevice> ofProject(TLObject project, List<String> problems) {
		Map<TLObject, GroupDevice> result = new LinkedHashMap<>();
		for (TLObject device : DeviceChanges.devices(project)) {
			GroupDevice groupDevice = ofDevice(device);
			if (groupDevice != null) {
				result.put(device, groupDevice);
			}
		}
		for (TLObject follower : objects(project.tValueByName("simulationFollowers"))) {
			String problem = follow(follower, result);
			if (problem != null) {
				problems.add(problem);
			}
		}
		return new ArrayList<>(result.values());
	}

	/**
	 * Applies an object follower (<code>ehome.runtime:ObjectFollower</code>) to the group device of
	 * its device.
	 *
	 * @return <code>null</code>, or why it is left out.
	 */
	private static String follow(TLObject follower, Map<TLObject, GroupDevice> devices) {
		TLObject device = follower.tValueByName("device") instanceof TLObject d ? d : null;
		TLObject source = follower.tValueByName("source") instanceof TLObject c ? c : null;
		TLObject target = follower.tValueByName("follower") instanceof TLObject c ? c : null;
		if (device == null || source == null || target == null) {
			return "an object follower without a device, a switching object or a state object is left out";
		}
		String deviceLabel = GroupAddressConnections.deviceLabel(device);
		Integer sourceNumber = number(device, source);
		Integer targetNumber = number(device, target);
		if (sourceNumber == null || targetNumber == null) {
			return "the object follower of " + deviceLabel + " names an object the device does not use; it is left out";
		}
		GroupDevice groupDevice = devices.get(device);
		if (groupDevice == null || !has(groupDevice, sourceNumber.intValue()) || !has(groupDevice, targetNumber.intValue())) {
			return "the object follower of " + deviceLabel + " is left out: the device has no individual address,"
				+ " or one of its objects is not connected to a group address";
		}
		boolean inverted = Boolean.TRUE.equals(follower.tValueByName("inverted"));
		if (inverted && groupDevice.object(targetNumber.intValue()).bits() != 1) {
			return "the object follower of " + deviceLabel + " is left out: only an object of one bit follows inverted";
		}
		groupDevice.follow(targetNumber.intValue(), sourceNumber.intValue(), inverted);
		return null;
	}

	/**
	 * The number of the communication object of a connector, <code>null</code> if it is no active
	 * connector of the device.
	 */
	private static Integer number(TLObject device, TLObject object) {
		for (TLObject connector : new DeviceConfiguration(device).getConnectors()) {
			if (connector == object && connector.tValueByName("port") instanceof Interface placed) {
				return Integer.valueOf(placed.getPort().getIndex());
			}
		}
		return null;
	}

	private static boolean has(GroupDevice device, int number) {
		for (GroupObject object : device.objects()) {
			if (object.number() == number) {
				return true;
			}
		}
		return false;
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
