/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import static de.haumacher.ehome.knx.ExtIds.isA;
import static de.haumacher.ehome.knx.ExtIds.list;

import java.util.ArrayList;
import java.util.Collection;
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

import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.query.Queries;

/**
 * Editing the configuration structure and the definitions of an application program: where a new
 * structure element may go, inserting it there, moving elements to another place of the structure
 * (a drop in the structure tree), and deleting
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
		 * The position a script value stands for: its name (or a classifier of that name);
		 * <code>null</code> for none.
		 */
		public static Position of(Object value) {
			if (value == null) {
				return null;
			}
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
	 * right after it; without a position at the {@link #defaultPosition(TLObject, TLStructuredType)
	 * default one}.
	 *
	 * @return The inserted element.
	 */
	public static TLObject insert(TLObject node, TLObject anchor, Position givenPosition) {
		TLStructuredType type = node.tType();
		Position position = givenPosition == null ? defaultPosition(anchor, type) : givenPosition;
		if (!canInsert(anchor, type, position)) {
			throw new TopLogicException(I18NConstants.ERROR_CANNOT_INSERT__TYPE_ANCHOR.fill(type, anchor));
		}
		switch (position) {
			case INSIDE: {
				List<Object> elements = new ArrayList<>(children(anchor));
				elements.remove(node);
				elements.add(node);
				setChildren(anchor, elements);
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
	 * Why the given elements cannot be moved into the given container before the given one of its
	 * elements (at its end without one); <code>null</code> if they can.
	 *
	 * <p>
	 * An element of the structure (a channel, a block, a switch, a case, a placed parameter or
	 * communication object) can be moved within its program into every element that may contain an
	 * element of its kind ({@link #canContain(TLObject, TLStructuredType)}), but not into itself or
	 * one of its own elements. The program itself is not moved.
	 * </p>
	 *
	 * @param nodes
	 *        The elements to move.
	 * @param container
	 *        The element they go into; <code>null</code> for a place beside the program.
	 * @param before
	 *        The element of the container they are inserted before; <code>null</code> to append
	 *        them.
	 */
	public static ResKey moveVeto(Collection<?> nodes, TLObject container, TLObject before) {
		if (nodes == null || nodes.isEmpty()) {
			return I18NConstants.ERROR_NOTHING_SELECTED;
		}
		if (container == null) {
			return I18NConstants.ERROR_OUTSIDE_PROGRAM;
		}
		TLObject program = root(container);
		for (Object element : nodes) {
			if (!(element instanceof TLObject node) || siblingReference(node) == null) {
				return I18NConstants.ERROR_NOT_ORDERED__NODE.fill(element);
			}
			if (program == null || root(node) != program) {
				return I18NConstants.ERROR_OTHER_PROGRAM__NODE.fill(node);
			}
			for (TLObject ancestor = container; ancestor != null; ancestor = ancestor.tContainer()) {
				if (ancestor == node) {
					return I18NConstants.ERROR_INSIDE_ITSELF__NODE.fill(node);
				}
			}
			if (!canContain(container, node.tType())) {
				return I18NConstants.ERROR_CANNOT_CONTAIN__NODE_CONTAINER.fill(node, container);
			}
		}
		return null;
	}

	/**
	 * Moves the given elements, in their given order, into the given container before the given one
	 * of its elements (at its end without one): into the cases of a switch, the contents of every
	 * other element.
	 *
	 * @see #moveVeto(Collection, TLObject, TLObject)
	 */
	public static void move(Collection<?> nodes, TLObject container, TLObject before) {
		ResKey veto = moveVeto(nodes, container, before);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		List<TLObject> moved = new ArrayList<>();
		for (Object node : nodes) {
			if (!moved.contains(node)) {
				moved.add((TLObject) node);
			}
		}
		List<Object> elements = new ArrayList<>(children(container));
		int index = before == null ? -1 : elements.indexOf(before);
		if (index < 0) {
			index = elements.size();
		}
		// The place counts the elements staying before it; an element moved stands nowhere meanwhile.
		int target = 0;
		for (int n = 0; n < index; n++) {
			if (!moved.contains(elements.get(n))) {
				target++;
			}
		}
		// Out of the old containers first: a part has one container at a time.
		for (TLObject node : moved) {
			TLObject oldContainer = node.tContainer();
			if (oldContainer != container) {
				TLReference oldReference = siblingReference(node);
				List<Object> siblings = new ArrayList<>((Collection<?>) oldContainer.tValue(oldReference));
				siblings.remove(node);
				oldContainer.tUpdate(oldReference, siblings);
			}
		}
		elements.removeAll(moved);
		elements.addAll(target, moved);
		setChildren(container, elements);
	}

	/**
	 * The elements inside the given structure element: the cases of a switch, else its contents.
	 */
	private static List<? extends TLObject> children(TLObject container) {
		return container instanceof Switch choice ? choice.getAlternatives()
			: ((ConfigFragment) container).getContents();
	}

	/**
	 * Sets the elements inside the given structure element (see {@link #children(TLObject)}).
	 */
	@SuppressWarnings("unchecked")
	private static void setChildren(TLObject container, List<?> elements) {
		if (container instanceof Switch choice) {
			choice.setAlternatives((List<Case>) elements);
		} else {
			((ConfigFragment) container).setContents((List<Option>) elements);
		}
	}

	/**
	 * The program the given structure element belongs to; <code>null</code> for none.
	 */
	private static TLObject root(TLObject node) {
		for (TLObject ancestor = node; ancestor != null; ancestor = ancestor.tContainer()) {
			if (isA(ancestor, "ehome.operational:Program")) {
				return ancestor;
			}
		}
		return null;
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
	 *
	 * <p>
	 * The program is shared by the projects of all tenants: its own references (types, switches) are
	 * read, the use by the devices of the projects (settings, connectors) and of an enumeration entry
	 * is an existence check of the database that reads one link, not every device of every project
	 * (haumacher/ehome #172); a user of the entry outside the program is not named.
	 * </p>
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
			// The holders share the reference of ParameterValue: the parameters, placements and
			// assignments of programs, the settings of devices, the values of presets.
			TLObject holder = Queries.firstReferer(obj, reference("ehome.operational:ParameterValue", "choice"));
			if (holder != null) {
				// A holder outside the shared program belongs to a project or a template: not named.
				return ExtIds.ancestor(holder, "ehome.operational:Program") != null
					? I18NConstants.ERROR_ENTRY_IN_USE__ENTRY_HOLDER.fill(obj, holder)
					: I18NConstants.ERROR_ENTRY_SET_ELSEWHERE__ENTRY.fill(obj);
			}
		} else if (isA(obj, "ehome.operational:Parameter")) {
			for (TLObject view : list(obj, "views")) {
				if (Queries.hasReferer(view, reference("ehome.physical:ParameterSetting", "parameter"))) {
					return I18NConstants.ERROR_PARAMETER_SET_BY_DEVICES__PARAMETER.fill(obj);
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
				if (Queries.hasReferer(placement, reference("ehome.physical:Connector", "port"))) {
					return I18NConstants.ERROR_OBJECT_USED_BY_DEVICES__OBJECT.fill(obj);
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
		Location location = obj instanceof Parameter parameter ? parameter.getAddress() : null;
		obj.tDelete();
		if (location != null && location.tValid() && location.getUsage().isEmpty()) {
			location.tDelete();
		}
	}

	private static TLReference reference(String typeName, String name) {
		return (TLReference) ((TLClass) TLModelUtil.findType(typeName)).getPart(name);
	}

}
