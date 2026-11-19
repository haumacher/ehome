/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.building.BuildingDefinition;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.zigbee.ZigbeeDevice;

/**
 * The devices placed in the parts of a building (<code>ehome.physical:BusDevice#location</code>, seen
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

	private static final String PATH_SEPARATOR = ", ";

	/**
	 * The order of devices: by their individual address, devices in no line last, then by label.
	 */
	public static final Comparator<TLObject> BY_ADDRESS = Comparator
		.comparing(DownloadImage::individualAddress, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(GroupAddressConnections::deviceLabel);

	/**
	 * The devices placed directly in the given building part (not in its sub-parts), by their
	 * individual address; the stubs of devices the catalog does not identify among them
	 * (haumacher/ehome #183).
	 */
	public static List<TLObject> devices(TLObject part) {
		List<TLObject> result = new ArrayList<>();
		for (Object device : ((BuildingDefinition) part).getDevices()) {
			result.add((TLObject) device);
		}
		result.sort(BY_ADDRESS);
		return result;
	}

	/**
	 * The devices of the given project placed in no building part, by their individual address: the
	 * node "No location" of the device tree by building part (haumacher/ehome #106).
	 */
	public static List<TLObject> unplaced(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject device : GroupAddressConnections.busDevices(project)) {
			if (device.tValueByName(LOCATION) == null) {
				result.add(device);
			}
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
		for (Object child : ((BuildingDefinition) part).getParts()) {
			collectDevices((TLObject) child, result);
		}
	}

	/**
	 * The devices placed in the given building part and in all its sub-parts, by their individual
	 * address (haumacher/ehome #87).
	 */
	public static List<TLObject> devicesWithin(TLObject part) {
		List<TLObject> result = new ArrayList<>(allDevices(part));
		result.sort(BY_ADDRESS);
		return result;
	}

	/**
	 * The Zigbee devices placed in the given building part and in all its sub-parts
	 * (<code>ehome.building:BuildingDefinition#zigbeeDevices</code>), by their name.
	 */
	public static List<TLObject> zigbeeDevicesWithin(TLObject part) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject current : partsWithin(part)) {
			for (Object device : ((BuildingDefinition) current).getZigbeeDevices()) {
				result.add((TLObject) device);
			}
		}
		result.sort(Comparator.comparing((TLObject d) -> ((ZigbeeDevice) d).getName(),
			Comparator.nullsLast(Comparator.naturalOrder())));
		return result;
	}

	/**
	 * The given building part and all its sub-parts, depth-first in their order.
	 */
	public static List<TLObject> partsWithin(TLObject part) {
		List<TLObject> result = new ArrayList<>();
		result.add(part);
		addTreeOrder(partsOf(part), result);
		return result;
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
			if (device.tType().getPart("connectors") == null) {
				// The stub of a device the catalog does not identify has no objects.
				continue;
			}
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
			.comparingInt((TLObject c) -> addressValue((GroupAddress) c.tValueByName("target")))
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
		for (TLObject device : GroupAddressConnections.busDevices(part)) {
			if (device.tValueByName(LOCATION) != part) {
				result.add(device);
			}
		}
		result.sort(Comparator.comparingInt((TLObject d) -> d.tValueByName(LOCATION) == null ? 0 : 1)
			.thenComparing(BY_ADDRESS));
		return result;
	}

	/**
	 * The path of the given building part, details first: the part, then its containers up to the
	 * building, e.g. "Küche, Erdgeschoss, Haus"; <code>null</code> for no part.
	 *
	 * <p>
	 * The building is left out when the project has exactly one building ("Küche, Erdgeschoss"):
	 * then it tells no parts apart. The single building itself is labelled with its name, a path is
	 * never empty.
	 * </p>
	 */
	public static String path(TLObject part) {
		if (part == null) {
			return null;
		}
		List<String> names = new ArrayList<>();
		TLObject current = part;
		for (; current != null && isA(current, "ehome.building:BuildingDefinition"); current = current.tContainer()) {
			names.add(MetaLabelProvider.INSTANCE.getLabel(current));
		}
		if (names.size() > 1 && current != null && isA(current, "ehome.project:Project")
			&& ((Project) current).getBuildings().size() == 1) {
			names.remove(names.size() - 1);
		}
		return String.join(PATH_SEPARATOR, names);
	}

	/**
	 * Whether a building part of the given type may be created in the given container (the project
	 * or a building part), following the building structure of ETS.
	 *
	 * <p>
	 * The project takes buildings; a building and a building part take building parts, floors and
	 * everything a floor takes; a floor takes rooms, corridors, stairways, locations and distribution
	 * boards; a room, a corridor, a stairway and a location take locations and distribution boards; a
	 * distribution board takes no further part, only devices.
	 * </p>
	 */
	public static boolean canContain(TLObject container, TLStructuredType type) {
		return container != null && canContain(container.tType(), type);
	}

	/**
	 * Whether a building part of the given type may be created in a container of the given type,
	 * see {@link #canContain(TLObject, TLStructuredType)}: also for a container that is to be created
	 * with it (the assistant creates a floor with its rooms in one call).
	 */
	public static boolean canContain(TLStructuredType containerKind, TLStructuredType type) {
		if (containerKind == null || type == null) {
			return false;
		}
		String containerType = TLModelUtil.qualifiedName(containerKind);
		String partType = TLModelUtil.qualifiedName(type);
		switch (containerType) {
			case "ehome.project:Project":
				return partType.equals("ehome.building:Building");
			case "ehome.building:Building":
			case "ehome.building:BuildingPart":
				return partType.equals("ehome.building:BuildingPart") || partType.equals("ehome.building:Floor")
					|| floorPart(partType);
			case "ehome.building:Floor":
				return floorPart(partType);
			case "ehome.building:Room":
			case "ehome.building:Corridor":
			case "ehome.building:Stairway":
			case "ehome.building:Location":
				return roomPart(partType);
			default:
				return false;
		}
	}

	private static boolean floorPart(String partType) {
		return partType.equals("ehome.building:Room") || partType.equals("ehome.building:Corridor") || partType.equals("ehome.building:Stairway")
			|| roomPart(partType);
	}

	private static boolean roomPart(String partType) {
		return partType.equals("ehome.building:Location") || partType.equals("ehome.building:DistributionBoard");
	}

	/**
	 * Why a building part of the given type cannot be created in the given container;
	 * <code>null</code> if it can, see {@link #canContain(TLObject, TLStructuredType)}.
	 */
	public static ResKey insertVeto(TLObject container, TLStructuredType type) {
		if (canContain(container, type)) {
			return null;
		}
		return I18NConstants.ERROR_CANNOT_CONTAIN__TYPE_CONTAINER.fill(type, container);
	}

	/**
	 * Why a building part of the given type cannot be created in a container of the given type that
	 * is to be created with it; <code>null</code> if it can, see
	 * {@link #canContain(TLStructuredType, TLStructuredType)}.
	 *
	 * @param containerName
	 *        The name of the container to be created, for the reason.
	 */
	public static ResKey insertVeto(TLStructuredType containerKind, String containerName, TLStructuredType type) {
		if (canContain(containerKind, type)) {
			return null;
		}
		return I18NConstants.ERROR_CANNOT_CONTAIN__TYPE_CONTAINER.fill(type, containerName);
	}

	/**
	 * Why the given building parts cannot be moved into the given container (the project or a
	 * building part) before its part <code>before</code>; <code>null</code> if they can.
	 *
	 * <p>
	 * A part can be moved within its project, into a container that may hold a part of its type
	 * ({@link #canContain(TLObject, TLStructuredType)}), but not into itself or one of its sub-parts.
	 * </p>
	 *
	 * @param before
	 *        The part of the container the moved parts are placed before; <code>null</code>, or an
	 *        object that is no part of the container, for the end.
	 */
	public static ResKey moveVeto(Collection<?> parts, TLObject container, TLObject before) {
		if (parts == null || parts.isEmpty()) {
			return I18NConstants.ERROR_NOTHING_TO_MOVE;
		}
		if (container == null) {
			return I18NConstants.ERROR_NO_CONTAINER;
		}
		TLObject project = project(container);
		for (Object element : parts) {
			if (!(element instanceof TLObject part) || !isA(part, "ehome.building:BuildingDefinition")) {
				return I18NConstants.ERROR_NO_BUILDING_PART__OBJECT.fill(element);
			}
			if (project == null || project(part) != project) {
				return I18NConstants.ERROR_OTHER_PROJECT__PART.fill(part);
			}
			for (TLObject ancestor = container; ancestor != null; ancestor = ancestor.tContainer()) {
				if (ancestor == part) {
					return I18NConstants.ERROR_INSIDE_ITSELF__PART.fill(part);
				}
			}
			if (!canContain(container, part.tType())) {
				return I18NConstants.ERROR_CANNOT_MOVE__PART_CONTAINER.fill(part, container);
			}
		}
		return null;
	}

	/**
	 * Moves the given building parts into the given container before its part <code>before</code> (at
	 * the end of its parts without one).
	 *
	 * <p>
	 * The moved parts keep their relative order in the tree, whatever order they were selected in:
	 * they are placed in the depth-first order of the project's building structure, so parts of
	 * different containers come in the order the tree shows them. A part <code>before</code> that is
	 * itself moved stands for its place: the moved parts go after the parts of the container staying
	 * before it, i.e. before the first part at or after <code>before</code> that is not moved (at the
	 * end without one). A part dropped before itself therefore stays where it is.
	 * </p>
	 *
	 * @throws TopLogicException
	 *         If the parts cannot be moved there, see {@link #moveVeto(Collection, TLObject, TLObject)}.
	 */
	public static void move(Collection<?> parts, TLObject container, TLObject before) {
		ResKey veto = moveVeto(parts, container, before);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		Set<Object> selected = new HashSet<>(parts);
		List<BuildingDefinition> moved = new ArrayList<>();
		for (BuildingDefinition part : treeOrder(project(container))) {
			if (selected.contains(part)) {
				moved.add(part);
			}
		}
		List<BuildingDefinition> elements = partsOf(container);
		int index = before == null ? -1 : elements.indexOf(before);
		if (index < 0) {
			index = elements.size();
		}
		// The place counts the parts staying before it; a part moved stands nowhere meanwhile.
		int target = 0;
		for (int n = 0; n < index; n++) {
			if (!moved.contains(elements.get(n))) {
				target++;
			}
		}
		// Out of the old containers first: a part has one container at a time.
		for (BuildingDefinition part : moved) {
			TLObject oldContainer = part.tContainer();
			if (oldContainer != container) {
				List<BuildingDefinition> siblings = partsOf(oldContainer);
				siblings.remove(part);
				setPartsOf(oldContainer, siblings);
			}
		}
		elements.removeAll(moved);
		elements.addAll(target, moved);
		setPartsOf(container, elements);
	}

	/**
	 * The building parts of the given project, depth-first in their order: a building, then each of
	 * its parts followed by their parts.
	 */
	private static List<BuildingDefinition> treeOrder(TLObject project) {
		List<BuildingDefinition> result = new ArrayList<>();
		addTreeOrder(partsOf(project), result);
		return result;
	}

	private static void addTreeOrder(List<BuildingDefinition> parts, List<? super BuildingDefinition> result) {
		for (BuildingDefinition part : parts) {
			result.add(part);
			addTreeOrder(partsOf(part), result);
		}
	}

	/**
	 * The building parts the given container (the project or a building part) holds, in their
	 * order: a modifiable copy.
	 */
	private static List<BuildingDefinition> partsOf(TLObject container) {
		return new ArrayList<>(isA(container, "ehome.project:Project") ? ((Project) container).getBuildings()
			: ((BuildingDefinition) container).getParts());
	}

	/**
	 * Sets the building parts the given container (the project or a building part) holds.
	 */
	private static void setPartsOf(TLObject container, List<BuildingDefinition> parts) {
		if (isA(container, "ehome.project:Project")) {
			((Project) container).setBuildings(parts);
		} else {
			((BuildingDefinition) container).setParts(parts);
		}
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

	private static int addressValue(GroupAddress groupAddress) {
		GA address = groupAddress.getAddress();
		return address != null ? address.getValue() : Integer.MAX_VALUE;
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
		return ExtIds.isA(obj, typeName);
	}

}
