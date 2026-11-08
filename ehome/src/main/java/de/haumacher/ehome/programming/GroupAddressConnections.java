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

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.FillTextParameter;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.template.ObjectTypes;

/**
 * The connections of a group address (<code>ehome.logical:GroupAddress</code>) seen from the address:
 * which communication object of which device sends to it or receives from it.
 *
 * <p>
 * This is the address-centric side of the data {@link DeviceSettings} changes from the device: a
 * connection (<code>ehome.physical:Connection</code>) belongs to a connector of a device and names
 * the address as its target. Connections are made and removed through {@link DeviceSettings}, so
 * that its rules hold whichever side the user works from.
 * </p>
 */
public class GroupAddressConnections {

	private static final String SEPARATOR = " · ";

	/**
	 * The connections to the given group address, the sending ones first, then by the individual
	 * address of the device and the number of the object.
	 */
	public static List<TLObject> connections(TLObject groupAddress) {
		List<TLObject> result = new ArrayList<>();
		TLReference target = (TLReference) TLModelUtil.findPart("ehome.physical:Connection#target");
		for (TLObject connection : groupAddress.tReferers(target)) {
			if (device(connection) != null && object(connection) != null) {
				result.add(connection);
			}
		}
		result.sort(Comparator
			.comparing((TLObject c) -> isSend(c) ? 0 : 1)
			.thenComparing(c -> individualAddress(device(c)), Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparingInt(c -> object(c).getPort().getIndex()));
		return result;
	}

	/**
	 * The number of connections sending to the given group address.
	 */
	public static int senders(TLObject groupAddress) {
		return (int) connections(groupAddress).stream().filter(GroupAddressConnections::isSend).count();
	}

	/**
	 * The device a connection belongs to, <code>null</code> for no connection of a device.
	 */
	public static TLObject device(TLObject connection) {
		TLObject connector = connection == null ? null : connection.tContainer();
		TLObject device = connector == null ? null : connector.tContainer();
		return device != null && isA(device, "ehome.physical:Device") ? device : null;
	}

	/**
	 * The communication object (its placement in the device's program) a connection belongs to.
	 */
	public static Interface object(TLObject connection) {
		TLObject connector = connection == null ? null : connection.tContainer();
		return connector != null && connector.tValueByName("port") instanceof Interface placed ? placed : null;
	}

	/**
	 * The label of a connection seen from its group address: the device with its individual
	 * address, the number and name of the object, and the direction, e.g. "Tastsensor 3 (1.3.50) ·
	 * 66 Szenenausgang 1 · Senden".
	 */
	public static String label(TLObject connection) {
		Interface placed = object(connection);
		return deviceLabel(device(connection)) + SEPARATOR + placed.getPort().getIndex() + " "
			+ objectName(device(connection), placed, Resources.getInstance()) + SEPARATOR + MetaLabelProvider.INSTANCE.getLabel(connection.tValueByName("kind"));
	}

	/**
	 * The device by its name and individual address, e.g. "Tastsensor 3 (1.3.50)".
	 */
	public static String deviceLabel(TLObject device) {
		if (device == null) {
			return "";
		}
		Object title = device.tValueByName("title");
		String name = title != null && !title.toString().isEmpty() ? title.toString()
			: MetaLabelProvider.INSTANCE.getLabel(device.tValueByName("type"));
		return name + " (" + addressText(device) + ")";
	}

	/**
	 * The individual address of the device in its notation area.line.device; the device number
	 * alone where the device is in no line.
	 */
	public static String addressText(TLObject device) {
		Integer address = DownloadImage.individualAddress(device);
		if (address == null) {
			Object number = device.tValueByName("address");
			return number == null ? "–" : number.toString();
		}
		return (address >>> 12) + "." + ((address >>> 8) & 0x0F) + "." + (address & 0xFF);
	}

	/**
	 * The text of a communication object as its program shows it (the placement's name, else the
	 * object's text, else its name).
	 */
	public static String objectName(Interface placed) {
		return objectName(placed, Resources.getInstance());
	}

	/**
	 * The text of the object of a connection in the given device: {@link #objectName(Interface)} with
	 * the placeholder <code>{{0}}</code> filled with the device's value of the object's text parameter
	 * (haumacher/ehome #119), <code>null</code> for a connection without an object.
	 */
	public static String objectText(TLObject connection) {
		Interface placed = object(connection);
		return placed == null ? null : objectName(device(connection), placed, Resources.getInstance());
	}

	/**
	 * The text of a communication object in the given device in the language of the given resources:
	 * {@link #objectName(Interface, Resources)} with the placeholder <code>{{0}}</code> filled with
	 * the device's value of the object's text parameter (haumacher/ehome #119, as
	 * <code>ehome.physical:Connector#displayName</code> does).
	 *
	 * @param device
	 *        The device, <code>null</code> for the program's text with its placeholder.
	 */
	public static String objectName(TLObject device, Interface placed, Resources resources) {
		String text = objectName(placed, resources);
		ParameterView parameter = placed.getTextParameter();
		if (device == null || parameter == null) {
			return text;
		}
		return FillTextParameter.fill(text, DeviceSettings.value(device, parameter));
	}

	/**
	 * The text of a communication object as its program shows it in the language of the given
	 * resources, see {@link #objectName(Interface)}.
	 */
	public static String objectName(Interface placed, Resources resources) {
		Port port = placed.getPort();
		String result = placed.tValueByName("name") instanceof ResKey name ? localize(resources, name) : null;
		if (result == null) {
			result = localize(resources, port.getText());
		}
		if (result == null) {
			result = port.getName();
		}
		return result == null ? "" : result;
	}

	/**
	 * The function text of the object of a connection: the project's own, else the one of the
	 * placement, else the object's.
	 */
	public static String function(TLObject connection) {
		return function(connection, Resources.getInstance());
	}

	/**
	 * The function text of the object of a connection in the language of the given resources, see
	 * {@link #function(TLObject)}.
	 */
	public static String function(TLObject connection, Resources resources) {
		Object own = connection.tContainer().tValueByName("function");
		if (own != null && !own.toString().isEmpty()) {
			return own.toString();
		}
		Interface placed = object(connection);
		String result = localize(resources, placed.getFunction());
		return result != null ? result : localize(resources, placed.getPort().getFunction());
	}

	/**
	 * The datapoint type of the object of a connection: the project's own, else the template's, else
	 * the manufacturer's ({@link ObjectTypes#effective(TLObject)}); <code>null</code> if none of them
	 * has one.
	 */
	public static TLObject datapointType(TLObject connection) {
		return ObjectTypes.effective(connection.tContainer());
	}

	/**
	 * The size of the object of a connection in bits, as in effect at its placement.
	 */
	public static int size(TLObject connection) {
		Interface placed = object(connection);
		Integer size = placed.getSize();
		return size != null ? size.intValue() : placed.getPort().getSize();
	}

	/**
	 * What does not fit together at the given group address: objects of different sizes, or of
	 * datapoint types of different main types (among themselves or compared with the address's own
	 * type). Objects without a datapoint type are compared by their size only.
	 *
	 * @return The problems, empty if the objects fit together.
	 */
	public static List<ResKey> problems(TLObject groupAddress) {
		List<TLObject> connections = connections(groupAddress);
		List<ResKey> result = new ArrayList<>();
		TLObject addressType = first(groupAddress.tValueByName("datapointTypes"));
		Integer addressMain = mainNumber(addressType);
		Integer addressSize = addressType == null ? null : sizeInBit(addressType);
		Set<Integer> sizes = new LinkedHashSet<>();
		Set<Integer> mainTypes = new LinkedHashSet<>();
		for (TLObject connection : connections) {
			int size = size(connection);
			sizes.add(size);
			TLObject type = datapointType(connection);
			Integer main = mainNumber(type);
			if (main != null) {
				mainTypes.add(main);
			}
			String object = deviceLabel(device(connection)) + SEPARATOR + object(connection).getPort().getIndex() + " "
				+ objectName(object(connection));
			if (main != null && addressMain != null && !main.equals(addressMain)) {
				result.add(I18NConstants.WARNING_TYPE_DIFFERS_FROM_ADDRESS__OBJECT_TYPE_ADDRESS_TYPE.fill(object,
					MetaLabelProvider.INSTANCE.getLabel(type), MetaLabelProvider.INSTANCE.getLabel(addressType)));
			} else if (addressSize != null && size != addressSize.intValue()) {
				result.add(I18NConstants.WARNING_SIZE_DIFFERS_FROM_ADDRESS__OBJECT_SIZE_ADDRESS_SIZE.fill(object,
					size, addressSize));
			}
		}
		if (mainTypes.size() > 1) {
			result.add(I18NConstants.WARNING_OBJECT_TYPES_DIFFER);
		}
		if (sizes.size() > 1) {
			result.add(I18NConstants.WARNING_OBJECT_SIZES_DIFFER);
		}
		return result;
	}

	/**
	 * The problems of {@link #problems(TLObject)} as one text for a person, <code>null</code> if
	 * there are none.
	 */
	public static String problemText(TLObject groupAddress) {
		List<ResKey> problems = problems(groupAddress);
		if (problems.isEmpty()) {
			return null;
		}
		Resources resources = Resources.getInstance();
		StringBuilder result = new StringBuilder();
		for (ResKey problem : problems) {
			if (result.length() > 0) {
				result.append('\n');
			}
			result.append(resources.getString(problem));
		}
		return result.toString();
	}

	/**
	 * The devices of the project the group address belongs to, by their individual address.
	 */
	public static List<TLObject> devices(TLObject groupAddress) {
		List<TLObject> result = new ArrayList<>();
		TLObject project = project(groupAddress);
		if (project != null) {
			for (Object area : (Collection<?>) project.tValueByName("areas")) {
				for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
					for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
						result.add((TLObject) device);
					}
				}
			}
			for (Object device : (Collection<?>) project.tValueByName("unassignedDevices")) {
				result.add((TLObject) device);
			}
		}
		result.sort(Comparator.comparing(GroupAddressConnections::individualAddress,
			Comparator.nullsLast(Comparator.naturalOrder())));
		return result;
	}

	/**
	 * The communication objects of the device that can be connected (those in effect in its
	 * configuration), by their number.
	 */
	public static List<Interface> objects(TLObject device) {
		List<Interface> result = new ArrayList<>(new DeviceConfiguration(device).getActiveInterfaces().values());
		result.sort(Comparator.comparingInt(i -> i.getPort().getIndex()));
		return result;
	}

	/**
	 * The communication object as an option of a choice: its number and name, e.g. "66
	 * Szenenausgang 1".
	 */
	public static String objectLabel(Interface placed) {
		return placed.getPort().getIndex() + " " + objectName(placed);
	}

	private static Integer individualAddress(TLObject device) {
		return device == null ? null : DownloadImage.individualAddress(device);
	}

	private static boolean isSend(TLObject connection) {
		return connection.tValueByName("kind") instanceof TLClassifier classifier
			&& "send".equals(classifier.getName());
	}

	private static Integer mainNumber(TLObject type) {
		if (type == null) {
			return null;
		}
		TLObject main = isA(type, "ehome.datapoint:DatapointSubtype") ? type.tContainer() : type;
		return main != null && main.tValueByName("number") instanceof Integer number ? number : null;
	}

	private static Integer sizeInBit(TLObject type) {
		TLObject main = isA(type, "ehome.datapoint:DatapointSubtype") ? type.tContainer() : type;
		return main != null && main.tValueByName("sizeInBit") instanceof Integer size ? size : null;
	}

	private static TLObject first(Object values) {
		if (values instanceof Collection<?> collection) {
			for (Object value : collection) {
				if (value instanceof TLObject obj) {
					return obj;
				}
			}
		}
		return null;
	}

	private static String localize(Resources resources, ResKey key) {
		if (key == null) {
			return null;
		}
		String result = resources.getString(key, null);
		return result == null || result.isEmpty() ? null : result;
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
