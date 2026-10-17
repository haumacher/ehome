/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.monitor.DatapointCodec.Dpt;

/**
 * The datapoint type of a group address (<code>ehome.logical:GroupAddress</code>): the one rule
 * saying which type the values sent to an address have, read by the runtime image, the group
 * monitor and the group address view, and the user's choice of a type for an address that has
 * none.
 *
 * <p>
 * The {@link #effective(TLObject) effective type} of an address is its own type (set in the project
 * or by the user), else the one of the first connected communication object that has one (the
 * object's in the project, else its placement's, else the object's in the program), else the main
 * type the size of the connected objects decides where it decides one: 1 bit (DPT 1, a switch) and
 * 4 bits (DPT 3, a step). An address without an effective type is <em>untyped</em>: the runtime
 * image leaves it out, the view marks it and offers to {@link #setType(TLObject, TLObject) set} a
 * type.
 * </p>
 *
 * <p>
 * The type a user sets is the address's own type, the same attribute the ETS import fills from the
 * project file. An import creates a project of its own, so importing the project file again leaves
 * the types set in the existing project alone.
 * </p>
 */
public final class GroupAddressTypes {

	private static final String DATAPOINT_TYPES = "datapointTypes";

	private static final String MAIN_TYPE = "ehome.datapoint:DatapointMainType";

	private static final String SUBTYPE = "ehome.datapoint:DatapointSubtype";

	private GroupAddressTypes() {
		// Functions only.
	}

	/**
	 * The datapoint type of the group address in effect, <code>null</code> for an untyped address.
	 *
	 * @see GroupAddressTypes
	 */
	public static Dpt effective(TLObject groupAddress) {
		Dpt declared = dpt(declaredType(groupAddress));
		return declared != null ? declared : bySize(groupAddress);
	}

	/**
	 * Whether the group address has a datapoint type in effect.
	 */
	public static boolean hasType(TLObject groupAddress) {
		return effective(groupAddress) != null;
	}

	/**
	 * The datapoint type of the master data in effect at the group address (see
	 * {@link #effective(TLObject)}): a type the address or an object names, else the main type the
	 * size of the objects decides; <code>null</code> for an untyped address.
	 */
	public static TLObject effectiveType(TLObject groupAddress) {
		TLObject declared = declaredType(groupAddress);
		if (dpt(declared) != null) {
			return declared;
		}
		Dpt bySize = bySize(groupAddress);
		return bySize == null ? null : mainType(bySize.main());
	}

	/**
	 * The datapoint type in effect for a person, e.g. "9.001 temperature (°C)", or the code of the
	 * main type the objects' size decides ("1.*"); empty for an untyped address.
	 */
	public static String label(TLObject groupAddress) {
		TLObject declared = declaredType(groupAddress);
		Dpt dpt = dpt(declared);
		if (dpt != null) {
			Object description = declared.tValueByName("description");
			String text = description instanceof ResKey key ? Resources.getInstance().getString(key, null) : null;
			return text == null || text.isEmpty() ? dpt.code() : dpt.code() + " " + text;
		}
		Dpt bySize = bySize(groupAddress);
		return bySize == null ? "" : bySize.code();
	}

	/**
	 * The datapoint type the group address or its objects name: its own, else the one of the first
	 * connected object that has one; <code>null</code> if none does.
	 */
	public static TLObject declaredType(TLObject groupAddress) {
		Object own = groupAddress.tValueByName(DATAPOINT_TYPES);
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
	 * The main type of the values of a group address by the size of its objects where it decides
	 * the main type: 1 bit (DPT 1, a switch), 4 bits (DPT 3, a step control); <code>null</code>
	 * otherwise.
	 */
	public static Dpt bySize(TLObject groupAddress) {
		Integer size = objectSize(groupAddress);
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
	 * The size in bits of the objects connected to the group address, <code>null</code> if no object
	 * is connected or the objects differ in size.
	 */
	public static Integer objectSize(TLObject groupAddress) {
		Integer size = null;
		for (TLObject connection : GroupAddressConnections.connections(groupAddress)) {
			int objectSize = GroupAddressConnections.size(connection);
			if (size != null && size.intValue() != objectSize) {
				return null;
			}
			size = Integer.valueOf(objectSize);
		}
		return size;
	}

	/**
	 * The numbers of a datapoint type (<code>ehome.datapoint:DatapointType</code>), <code>null</code>
	 * for none.
	 */
	public static Dpt dpt(TLObject type) {
		if (type == null || !(type.tValueByName("number") instanceof Integer number)) {
			return null;
		}
		if (isA(type, SUBTYPE)) {
			TLObject main = type.tContainer();
			if (main == null || !(main.tValueByName("number") instanceof Integer mainNumber)) {
				return null;
			}
			return new Dpt(mainNumber, number);
		}
		return Dpt.mainType(number);
	}

	/**
	 * The datapoint types the objects connected to the group address suggest: those whose size is
	 * the size of the objects (16 bits: 7.001, ..., 9.001 temperature, ...), the subtypes of each main
	 * type and a main type without subtypes; empty if no object is connected or the objects differ in
	 * size.
	 */
	public static List<TLObject> suggestions(TLObject groupAddress) {
		Integer size = objectSize(groupAddress);
		List<TLObject> result = new ArrayList<>();
		if (size == null) {
			return result;
		}
		for (TLObject main : mainTypes()) {
			if (main.tValueByName("sizeInBit") instanceof Integer mainSize && mainSize.equals(size)) {
				addTypes(main, result);
			}
		}
		return result;
	}

	/**
	 * All datapoint types of the master data: the subtypes of each main type and a main type without
	 * subtypes, in the order of their numbers.
	 */
	public static List<TLObject> allTypes() {
		List<TLObject> result = new ArrayList<>();
		for (TLObject main : mainTypes()) {
			addTypes(main, result);
		}
		return result;
	}

	/**
	 * The datapoint types offered for the group address: the {@link #suggestions(TLObject)}, or all
	 * types if asked for or if the objects suggest none.
	 */
	public static List<TLObject> choices(TLObject groupAddress, boolean all) {
		if (!all) {
			List<TLObject> suggestions = suggestions(groupAddress);
			if (!suggestions.isEmpty()) {
				return suggestions;
			}
		}
		return allTypes();
	}

	/**
	 * Why the given datapoint type cannot be set for the group address, <code>null</code> if it can.
	 *
	 * <p>
	 * A type whose size differs from the size of the connected objects is refused: the values could
	 * not be sent between the objects.
	 * </p>
	 */
	public static ResKey veto(TLObject groupAddress, TLObject type) {
		if (type == null) {
			return null;
		}
		if (!isA(type, "ehome.datapoint:DatapointType")) {
			return I18NConstants.ERROR_NO_DATAPOINT_TYPE__VALUE.fill(MetaLabelProvider.INSTANCE.getLabel(type));
		}
		Integer size = objectSize(groupAddress);
		Integer typeSize = sizeInBit(type);
		if (size != null && typeSize != null && !size.equals(typeSize)) {
			return I18NConstants.ERROR_TYPE_SIZE_DIFFERS__TYPE_TYPE_SIZE_OBJECT_SIZE.fill(
				MetaLabelProvider.INSTANCE.getLabel(type), typeSize, size);
		}
		return null;
	}

	/**
	 * Sets the datapoint type of the group address: the type the user chose, <code>null</code> to
	 * remove the address's own type (the objects' then applies).
	 *
	 * <p>
	 * Must be called within a transaction.
	 * </p>
	 *
	 * @return The group address.
	 * @throws TopLogicException
	 *         With the reason of {@link #veto(TLObject, TLObject)}, if the type does not fit.
	 */
	public static TLObject setType(TLObject groupAddress, TLObject type) {
		ResKey veto = veto(groupAddress, type);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		groupAddress.tUpdateByName(DATAPOINT_TYPES, type == null ? List.of() : List.of(type));
		return groupAddress;
	}

	/**
	 * The group ranges and addresses directly below the given project or group range that are or
	 * hold untyped addresses, in the order of {@link GroupAddresses#parts(TLObject)}.
	 */
	public static List<TLObject> untypedParts(TLObject node) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject part : GroupAddresses.parts(node)) {
			if (isA(part, "ehome.logical:GroupAddress") ? !hasType(part) : !untypedParts(part).isEmpty()) {
				result.add(part);
			}
		}
		return result;
	}

	private static void addTypes(TLObject main, List<TLObject> result) {
		Collection<?> subtypes = (Collection<?>) main.tValueByName("subtypes");
		if (subtypes == null || subtypes.isEmpty()) {
			result.add(main);
			return;
		}
		for (Object subtype : subtypes) {
			result.add((TLObject) subtype);
		}
	}

	private static TLObject mainType(int number) {
		for (TLObject main : mainTypes()) {
			if (main.tValueByName("number") instanceof Integer mainNumber && mainNumber.intValue() == number) {
				return main;
			}
		}
		return null;
	}

	private static List<TLObject> mainTypes() {
		List<TLObject> result = new ArrayList<>();
		TLModule module = TLModelUtil.findType(MAIN_TYPE).getModule();
		TLObject root = module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
		if (root != null) {
			for (Object main : (Collection<?>) root.tValueByName("types")) {
				result.add((TLObject) main);
			}
		}
		return result;
	}

	private static Integer sizeInBit(TLObject type) {
		TLObject main = isA(type, SUBTYPE) ? type.tContainer() : type;
		return main != null && main.tValueByName("sizeInBit") instanceof Integer size ? size : null;
	}

	private static boolean isA(TLObject obj, String typeName) {
		return TLModelUtil.isGeneralization((TLClass) TLModelUtil.findType(typeName), obj.tType());
	}

}
