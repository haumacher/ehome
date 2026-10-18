/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import static de.haumacher.ehome.knx.ExtIds.isA;
import static de.haumacher.ehome.knx.ExtIds.list;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;

/**
 * Editing the configuration structure and the definitions of an application program: where a new
 * structure element may go, inserting it there, moving an element among its siblings, and deleting
 * parameters, communication objects, parameter types and enumeration entries.
 *
 * <p>
 * The views call these operations through TL-Script functions ({@link EhomeFunctions}), so that the
 * user interface and the tests share them.
 * </p>
 */
public final class ProgramStructure {

	/**
	 * Where a new element goes relative to the selected one.
	 */
	public enum Position {
		/** At the end of the selected element. */
		INSIDE,

		/** Into the container of the selected element, right after it. */
		AFTER;

		/**
		 * The position a script value stands for: a classifier of
		 * <code>ehome.operational:InsertPosition</code> or its name.
		 */
		public static Position of(Object value) {
			String name = value instanceof TLClassifier classifier ? classifier.getName() : String.valueOf(value);
			return valueOf(name.toUpperCase(Locale.ROOT));
		}
	}

	private static final String CONTENTS = "contents";

	private static final String ALTERNATIVES = "alternatives";

	private ProgramStructure() {
		// Utility.
	}

	/**
	 * Whether an element of the given type may be contained in the given structure element.
	 *
	 * <p>
	 * A case belongs to a switch; a channel to the program or a case (a channel that depends on a
	 * parameter); the channel independent part to the program; a parameter block to every element
	 * but the program; a switch everywhere; a placed parameter or communication object to a block,
	 * a channel, a case or the channel independent part.
	 * </p>
	 */
	public static boolean canContain(TLObject container, TLStructuredType type) {
		if (container == null || type == null) {
			return false;
		}
		boolean program = isA(container, "ehome.operational:Program");
		switch (TLModelUtil.qualifiedName(type)) {
			case "ehome.operational:Case":
				return isA(container, "ehome.operational:Switch");
			case "ehome.operational:Channel":
				return program || isA(container, "ehome.operational:Case");
			case "ehome.operational:ChannelIndependent":
				return program;
			case "ehome.operational:ParameterGroup":
				return !program && isA(container, "ehome.operational:ConfigFragment");
			case "ehome.operational:Switch":
				return isA(container, "ehome.operational:ConfigFragment");
			case "ehome.operational:ParameterView":
			case "ehome.operational:Interface":
				return isA(container, "ehome.operational:ParameterGroup")
					|| isA(container, "ehome.operational:Channel")
					|| isA(container, "ehome.operational:Case")
					|| isA(container, "ehome.operational:ChannelIndependent");
			default:
				return false;
		}
	}

	/**
	 * Whether an element of the given type may be inserted at the given position relative to the
	 * given anchor.
	 */
	public static boolean canInsert(TLObject anchor, TLStructuredType type, Position position) {
		if (anchor == null) {
			return false;
		}
		switch (position) {
			case INSIDE:
				return canContain(anchor, type);
			case AFTER:
				return siblingReference(anchor) != null && canContain(anchor.tContainer(), type);
		}
		return false;
	}

	/**
	 * Why an element of the given type can be inserted neither into nor after the given anchor;
	 * <code>null</code> if it can.
	 */
	public static ResKey insertVeto(TLObject anchor, TLStructuredType type) {
		if (anchor == null) {
			return I18NConstants.ERROR_NOTHING_SELECTED;
		}
		if (canInsert(anchor, type, Position.INSIDE) || canInsert(anchor, type, Position.AFTER)) {
			return null;
		}
		return I18NConstants.ERROR_CANNOT_INSERT__TYPE_ANCHOR.fill(type, anchor);
	}

	/**
	 * The position a new element of the given type takes by default: inside the anchor if the
	 * anchor can contain it, otherwise after it.
	 */
	public static Position defaultPosition(TLObject anchor, TLStructuredType type) {
		return canInsert(anchor, type, Position.INSIDE) ? Position.INSIDE : Position.AFTER;
	}

	/**
	 * Inserts the given element at the given position relative to the anchor: at the end of the
	 * anchor (its cases for a switch, its contents otherwise), or into the container of the anchor
	 * right after it.
	 *
	 * @return The inserted element.
	 */
	public static TLObject insert(TLObject node, TLObject anchor, Position position) {
		TLStructuredType type = node.tType();
		if (!canInsert(anchor, type, position)) {
			throw new TopLogicException(I18NConstants.ERROR_CANNOT_INSERT__TYPE_ANCHOR.fill(type, anchor));
		}
		switch (position) {
			case INSIDE: {
				String reference = isA(anchor, "ehome.operational:Switch") ? ALTERNATIVES : CONTENTS;
				List<Object> elements = new ArrayList<>((Collection<?>) anchor.tValueByName(reference));
				elements.remove(node);
				elements.add(node);
				anchor.tUpdateByName(reference, elements);
				break;
			}
			case AFTER: {
				TLObject container = anchor.tContainer();
				TLReference reference = siblingReference(anchor);
				List<Object> elements = new ArrayList<>((Collection<?>) container.tValue(reference));
				elements.remove(node);
				elements.add(elements.indexOf(anchor) + 1, node);
				container.tUpdate(reference, elements);
				break;
			}
		}
		return node;
	}

	/**
	 * Why the given element cannot be moved by the given number of places among its siblings;
	 * <code>null</code> if it can.
	 */
	public static ResKey moveVeto(TLObject node, int delta) {
		if (node == null) {
			return I18NConstants.ERROR_NOTHING_SELECTED;
		}
		TLReference reference = siblingReference(node);
		if (reference == null) {
			return I18NConstants.ERROR_NOT_ORDERED__NODE.fill(node);
		}
		List<?> siblings = (List<?>) node.tContainer().tValue(reference);
		int target = siblings.indexOf(node) + delta;
		if (target < 0) {
			return I18NConstants.ERROR_ALREADY_FIRST__NODE.fill(node);
		}
		if (target >= siblings.size()) {
			return I18NConstants.ERROR_ALREADY_LAST__NODE.fill(node);
		}
		return null;
	}

	/**
	 * Moves the given element by the given number of places among its siblings (the contents or the
	 * cases of its container): <code>-1</code> up, <code>1</code> down.
	 *
	 * @return The moved element.
	 */
	public static TLObject move(TLObject node, int delta) {
		ResKey veto = moveVeto(node, delta);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		TLObject container = node.tContainer();
		TLReference reference = siblingReference(node);
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValue(reference));
		int index = elements.indexOf(node);
		elements.remove(index);
		elements.add(index + delta, node);
		container.tUpdate(reference, elements);
		return node;
	}

	/**
	 * The ordered reference of the structure holding the given element with its siblings
	 * (contents or cases); <code>null</code> if it is not part of the structure (e.g. a reference
	 * no element places).
	 */
	private static TLReference siblingReference(TLObject node) {
		if (node.tContainer() == null) {
			return null;
		}
		TLReference reference = node.tContainerReference();
		if (reference == null) {
			return null;
		}
		String name = reference.getName();
		return CONTENTS.equals(name) || ALTERNATIVES.equals(name) ? reference : null;
	}

	/**
	 * Why the given parameter, communication object, parameter type or enumeration entry cannot be
	 * deleted; <code>null</code> if it can.
	 */
	public static ResKey deleteVeto(TLObject obj) {
		if (obj == null) {
			return I18NConstants.ERROR_NOTHING_SELECTED;
		}
		if (isA(obj, "ehome.operational:ParameterType")) {
			Set<? extends TLObject> users = obj.tReferers(reference("ehome.operational:Parameter", "type"));
			if (!users.isEmpty()) {
				return I18NConstants.ERROR_TYPE_IN_USE__TYPE_COUNT_EXAMPLE.fill(obj, users.size(),
					users.iterator().next());
			}
		} else if (isA(obj, "ehome.operational:EnumerationValue")) {
			// The holders share the reference of ParameterValue, or have one each: count every holder once.
			Set<TLObject> holders = new HashSet<>();
			for (String holder : List.of("ehome.operational:Parameter", "ehome.operational:ParameterView",
				"ehome.physical:ParameterSetting")) {
				holders.addAll(obj.tReferers(reference(holder, "choice")));
			}
			if (!holders.isEmpty()) {
				return I18NConstants.ERROR_ENTRY_IN_USE__ENTRY_COUNT.fill(obj, holders.size());
			}
		} else if (isA(obj, "ehome.operational:Parameter")) {
			for (TLObject view : list(obj, "views")) {
				int settings = view.tReferers(reference("ehome.physical:ParameterSetting", "parameter")).size();
				if (settings > 0) {
					return I18NConstants.ERROR_PARAMETER_SET_BY_DEVICES__PARAMETER_COUNT.fill(obj, settings);
				}
				for (String user : List.of("ehome.operational:Switch", "ehome.operational:ParameterGroup")) {
					Set<? extends TLObject> users = view.tReferers(reference(user, "input"));
					if (!users.isEmpty()) {
						return I18NConstants.ERROR_PARAMETER_DECIDES__PARAMETER_USER.fill(obj, users.iterator().next());
					}
				}
			}
		} else if (isA(obj, "ehome.operational:Port")) {
			for (TLObject placement : list(obj, "interfaces")) {
				int connectors = placement.tReferers(reference("ehome.physical:Connector", "port")).size();
				if (connectors > 0) {
					return I18NConstants.ERROR_OBJECT_USED_BY_DEVICES__OBJECT_COUNT.fill(obj, connectors);
				}
			}
		}
		return null;
	}

	/**
	 * Deletes the given parameter (with its placements, and its location in the device unless
	 * another parameter of its union still uses it), communication object (with its placements),
	 * parameter type or enumeration entry, unless {@link #deleteVeto(TLObject)} refuses.
	 */
	public static void delete(TLObject obj) {
		ResKey veto = deleteVeto(obj);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		for (String placements : List.of("views", "interfaces")) {
			for (TLObject placement : list(obj, placements)) {
				placement.tDelete();
			}
		}
		TLObject location = isA(obj, "ehome.operational:Parameter") ? (TLObject) obj.tValueByName("address") : null;
		obj.tDelete();
		if (location != null && location.tValid() && ((Collection<?>) location.tValueByName("usage")).isEmpty()) {
			location.tDelete();
		}
	}

	private static TLReference reference(String typeName, String name) {
		return (TLReference) ((TLClass) TLModelUtil.findType(typeName)).getPart(name);
	}

}
