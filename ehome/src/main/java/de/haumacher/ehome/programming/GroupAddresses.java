/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;

/**
 * Creating a group address in a group range of a project, as the connect dialog of a device does
 * (<code>projects/create-group-address.view.xml</code>).
 *
 * <p>
 * A new address is added to the <code>parts</code> of its range, like the create dialog of the
 * address tree does, and gets its external ID from the project's sequence of group addresses
 * ({@link ExtIds}). Its number must lie within the range, not within a range below it, and must not
 * be taken by another address of the project.
 * </p>
 */
public final class GroupAddresses {

	private static final String GROUP_RANGE = "ehome.logical:GroupRange";

	private static final String GROUP_ADDRESS = "ehome.logical:GroupAddress";

	private static final String RANGE_START = "rangeStart";

	private static final String RANGE_END = "rangeEnd";

	private static final String PARTS = "parts";

	private static final String ADDRESS = "address";

	/** The lowest group address; 0 is the broadcast address. */
	private static final int MIN_ADDRESS = 1;

	/** The highest group address (16 bits). */
	private static final int MAX_ADDRESS = 0xFFFF;

	private GroupAddresses() {
		// Utility.
	}

	/**
	 * The group ranges of the project the given object belongs to that take addresses: those
	 * without ranges below them (the middle groups in the three-level notation), by their start.
	 */
	public static List<TLObject> ranges(TLObject obj) {
		List<TLObject> result = new ArrayList<>();
		TLObject project = ExtIds.ancestor(obj, "ehome.project:Project");
		if (project != null) {
			for (TLObject range : ExtIds.members(project, ExtIds.Kind.GROUP_RANGE)) {
				if (subRanges(range).isEmpty()) {
					result.add(range);
				}
			}
		}
		result.sort(Comparator.comparingInt((TLObject r) -> bound(r, RANGE_START, Integer.MAX_VALUE))
			.thenComparingInt(r -> bound(r, RANGE_END, Integer.MAX_VALUE)));
		return result;
	}

	/**
	 * The group ranges and addresses directly below the given project or group range, in the order
	 * of their addresses: a range by its start, an address by its value; a range before an address
	 * of the same value, entries without address last.
	 */
	public static List<TLObject> parts(TLObject node) {
		List<TLObject> result = ExtIds.isA(node, GROUP_RANGE) ? ExtIds.list(node, PARTS)
			: ExtIds.list(node, "groupAddresses");
		result.sort(Comparator.comparingInt(GroupAddresses::position)
			.thenComparingInt(part -> ExtIds.isA(part, GROUP_RANGE) ? 0 : 1)
			.thenComparing(part -> String.valueOf(part.tValueByName("name"))));
		return result;
	}

	private static int position(TLObject part) {
		return ExtIds.isA(part, GROUP_RANGE) ? bound(part, RANGE_START, Integer.MAX_VALUE)
			: bound(part, ADDRESS, Integer.MAX_VALUE);
	}

	/**
	 * The range a new address is proposed in: the range of the given address (the one chosen last)
	 * if it belongs to the project of the given object, otherwise the project's first range taking
	 * addresses.
	 *
	 * @param obj
	 *        An object of the project (a device, an address).
	 * @param lastAddress
	 *        The address chosen last, <code>null</code> if none.
	 * @return The proposed range, <code>null</code> if the project has none.
	 */
	public static TLObject defaultRange(TLObject obj, TLObject lastAddress) {
		List<TLObject> ranges = ranges(obj);
		if (lastAddress != null && lastAddress.tValid() && ranges.contains(lastAddress.tContainer())) {
			return lastAddress.tContainer();
		}
		return ranges.isEmpty() ? null : ranges.get(0);
	}

	/**
	 * The address proposed for a new address in the given range: the first free one above the
	 * highest address of the range, or the lowest free one if there is none above.
	 *
	 * @param range
	 *        The group range; for any other object (a project) there is no proposal.
	 *
	 * @return The address in the notation of the project, <code>null</code> if the range is full or
	 *         has no bounds.
	 */
	public static GA nextFree(TLObject range) {
		if (range == null || !range.tValid() || !ExtIds.isA(range, GROUP_RANGE)) {
			return null;
		}
		int start = Math.max(MIN_ADDRESS, bound(range, RANGE_START, -1));
		int end = Math.min(MAX_ADDRESS, bound(range, RANGE_END, -1));
		if (end < start) {
			return null;
		}
		Set<Integer> taken = taken(range);
		int highest = start - 1;
		for (Integer number : taken) {
			if (number >= start && number <= end) {
				highest = Math.max(highest, number);
			}
		}
		for (int number = highest + 1; number <= end; number++) {
			if (free(range, taken, number)) {
				return ga(range, number);
			}
		}
		for (int number = start; number <= highest; number++) {
			if (free(range, taken, number)) {
				return ga(range, number);
			}
		}
		return null;
	}

	/**
	 * Why an address with the given number cannot be created in the given range,
	 * <code>null</code> if it can.
	 *
	 * @param range
	 *        The group range (<code>ehome.logical:GroupRange</code>).
	 * @param address
	 *        The address, a {@link GA} or its number.
	 */
	public static ResKey veto(TLObject range, Object address) {
		if (range == null || !range.tValid() || !ExtIds.isA(range, GROUP_RANGE)) {
			return I18NConstants.ERROR_NO_GROUP_RANGE;
		}
		Integer number = number(address);
		if (number == null) {
			return I18NConstants.ERROR_NO_ADDRESS_NUMBER;
		}
		GA ga = ga(range, number.intValue());
		int start = bound(range, RANGE_START, MIN_ADDRESS);
		int end = bound(range, RANGE_END, MAX_ADDRESS);
		if (number < Math.max(MIN_ADDRESS, start) || number > Math.min(MAX_ADDRESS, end)) {
			return I18NConstants.ERROR_OUTSIDE_RANGE__ADDRESS_RANGE_START_END.fill(ga, label(range),
				ga(range, start), ga(range, end));
		}
		for (TLObject sub : subRanges(range)) {
			if (bound(sub, RANGE_START, MAX_ADDRESS + 1) <= number && number <= bound(sub, RANGE_END, -1)) {
				return I18NConstants.ERROR_IN_SUB_RANGE__ADDRESS_RANGE.fill(ga, label(sub));
			}
		}
		for (TLObject other : DeviceSettings.groupAddresses(range)) {
			if (other.tValueByName(ADDRESS) instanceof GA existing && existing.getValue() == number.intValue()) {
				return I18NConstants.ERROR_ADDRESS_TAKEN__ADDRESS_EXISTING.fill(ga, label(other));
			}
		}
		return null;
	}

	/**
	 * Creates a group address in the given range.
	 *
	 * <p>
	 * Must be called within a transaction.
	 * </p>
	 *
	 * @param range
	 *        The group range the address is added to (<code>ehome.logical:GroupRange</code>).
	 * @param address
	 *        The address, a {@link GA} or its number; it must lie in the range and be free in the
	 *        project (see {@link #veto(TLObject, Object)}).
	 * @param name
	 *        The name of the address, <code>null</code> for none.
	 * @param datapointType
	 *        The datapoint type of the address (<code>ehome.datapoint:DatapointType</code>),
	 *        <code>null</code> to take the one of the objects connected to it.
	 * @return The new group address (<code>ehome.logical:GroupAddress</code>).
	 * @throws TopLogicException
	 *         With the reason, if the address cannot be created.
	 */
	public static TLObject create(TLObject range, Object address, String name, TLObject datapointType) {
		ResKey veto = veto(range, address);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		TLObject result = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType(GROUP_ADDRESS));
		result.tUpdateByName(ADDRESS, ga(range, number(address).intValue()));
		if (name != null && !name.isBlank()) {
			result.tUpdateByName("name", name.trim());
		}
		if (datapointType != null) {
			result.tUpdateByName("datapointTypes", List.of(datapointType));
		}
		List<Object> parts = new ArrayList<>((Collection<?>) range.tValueByName(PARTS));
		parts.add(result);
		range.tUpdateByName(PARTS, parts);
		ExtIds.assign(result);
		return result;
	}

	private static List<TLObject> subRanges(TLObject range) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject part : ExtIds.list(range, PARTS)) {
			if (ExtIds.isA(part, GROUP_RANGE)) {
				result.add(part);
			}
		}
		return result;
	}

	private static Set<Integer> taken(TLObject range) {
		Set<Integer> result = new HashSet<>();
		for (TLObject address : DeviceSettings.groupAddresses(range)) {
			if (address.tValueByName(ADDRESS) instanceof GA ga) {
				result.add(ga.getValue());
			}
		}
		return result;
	}

	private static boolean free(TLObject range, Set<Integer> taken, int number) {
		if (taken.contains(number)) {
			return false;
		}
		for (TLObject sub : subRanges(range)) {
			if (bound(sub, RANGE_START, MAX_ADDRESS + 1) <= number && number <= bound(sub, RANGE_END, -1)) {
				return false;
			}
		}
		return true;
	}

	private static int bound(TLObject range, String attribute, int fallback) {
		return range.tValueByName(attribute) instanceof GA ga ? ga.getValue() : fallback;
	}

	private static GA ga(TLObject range, int number) {
		GroupAddressStyle style = range.tValueByName(RANGE_START) instanceof GA start ? start.getStyle() : null;
		return new GA(number, style);
	}

	private static Integer number(Object address) {
		if (address instanceof GA ga) {
			return ga.getValue();
		}
		if (address instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

	/**
	 * The label ETS lists a range or an address with (its prefix respectively address, and its
	 * name), computed from the stored values (the derived <code>displayName</code> depends on the
	 * rights of the reading user).
	 */
	private static String label(TLObject obj) {
		Object name = obj.tValueByName("name");
		String prefix;
		if (ExtIds.isA(obj, GROUP_RANGE)) {
			prefix = obj.tValueByName(RANGE_START) instanceof GA start && obj.tValueByName(RANGE_END) instanceof GA end
				? start.rangePrefix(end) : null;
		} else {
			prefix = obj.tValueByName(ADDRESS) instanceof GA ga ? ga.toString() : null;
		}
		if (prefix == null) {
			return String.valueOf(name);
		}
		return name == null ? prefix : prefix + " " + name;
	}

}
