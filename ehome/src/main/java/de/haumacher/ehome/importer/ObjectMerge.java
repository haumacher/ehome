/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TLType;
import com.top_logic.util.model.ModelService;

/**
 * Compares two object trees and updates one to the other in place, by the stored attributes of their
 * types: values and references compared by value and identity, composite parts recursively.
 *
 * <p>
 * An update keeps the identity of the target and of every part that has a counterpart of the same
 * type at the same position; parts without a counterpart are deleted, and parts of the source
 * without a counterpart are copied to the target. The source (typically transient) is not changed.
 * Nothing outside the target may refer to its parts.
 * </p>
 */
final class ObjectMerge {

	/**
	 * The stored parts of each type compared or updated so far, see {@link #storedParts(TLObject, Set)}:
	 * the master data of a file compare thousands of objects of a few types.
	 */
	private final Map<TLType, List<TLStructuredTypePart>> _storedParts = new HashMap<>();

	/**
	 * Whether the two objects are of the same type and have the same content.
	 *
	 * @param ignored
	 *        Names of attributes of the two objects themselves (not of their parts) not to compare.
	 */
	boolean equal(TLObject a, TLObject b, Set<String> ignored) {
		if (a.tType() != b.tType()) {
			return false;
		}
		for (TLStructuredTypePart part : storedParts(a, ignored)) {
			Object x = a.tValue(part);
			Object y = b.tValue(part);
			if (isComposite(part)) {
				if (!equalParts(asList(x), asList(y))) {
					return false;
				}
			} else if (!sameValue(x, y)) {
				return false;
			}
		}
		return true;
	}

	private boolean equalParts(List<TLObject> x, List<TLObject> y) {
		if (x.size() != y.size()) {
			return false;
		}
		for (int n = 0, cnt = x.size(); n < cnt; n++) {
			if (!equal(x.get(n), y.get(n), Set.of())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Gives the target the content of the source, which must be of the same type.
	 *
	 * <p>
	 * The source is not changed.
	 * </p>
	 *
	 * @param ignored
	 *        Names of attributes of the two objects themselves (not of their parts) not to update.
	 * @param garbage
	 *        Where to put the parts of the target that are to be deleted (all at once: a deletion
	 *        checks the references to the deleted objects).
	 */
	void update(TLObject target, TLObject source, Set<String> ignored, List<TLObject> garbage) {
		for (TLStructuredTypePart part : storedParts(target, ignored)) {
			Object value = source.tValue(part);
			if (isComposite(part)) {
				if (part.isMultiple()) {
					updateParts(target, source, part, garbage);
				} else {
					updatePart(target, source, part, garbage);
				}
			} else if (!sameValue(target.tValue(part), value)) {
				target.tUpdate(part, value instanceof Collection<?> collection ? new ArrayList<>(collection) : value);
			}
		}
	}

	private void updateParts(TLObject target, TLObject source, TLStructuredTypePart part,
			List<TLObject> garbage) {
		List<TLObject> targets = asList(target.tValue(part));
		List<TLObject> sources = asList(source.tValue(part));
		List<TLObject> result = new ArrayList<>();
		List<TLObject> dropped = new ArrayList<>();
		for (int n = 0, cnt = Math.max(targets.size(), sources.size()); n < cnt; n++) {
			TLObject kept = n < targets.size() ? targets.get(n) : null;
			TLObject given = n < sources.size() ? sources.get(n) : null;
			if (given == null) {
				dropped.add(kept);
			} else if (kept != null && kept.tType() == given.tType()) {
				update(kept, given, Set.of(), garbage);
				result.add(kept);
			} else {
				if (kept != null) {
					dropped.add(kept);
				}
				result.add(copy(given, garbage));
			}
		}
		if (!result.equals(targets)) {
			target.tUpdate(part, result);
		}
		garbage.addAll(dropped);
	}

	private void updatePart(TLObject target, TLObject source, TLStructuredTypePart part,
			List<TLObject> garbage) {
		TLObject kept = (TLObject) target.tValue(part);
		TLObject given = (TLObject) source.tValue(part);
		if (given != null && kept != null && kept.tType() == given.tType()) {
			update(kept, given, Set.of(), garbage);
			return;
		}
		target.tUpdate(part, given == null ? null : copy(given, garbage));
		if (kept != null) {
			garbage.add(kept);
		}
	}

	/**
	 * A new persistent object with the content of the given one.
	 */
	private TLObject copy(TLObject source, List<TLObject> garbage) {
		TLObject result = ModelService.getInstance().getFactory().createObject((TLClass) source.tType());
		update(result, source, Set.of(), garbage);
		return result;
	}

	private List<TLStructuredTypePart> storedParts(TLObject obj, Set<String> ignored) {
		List<TLStructuredTypePart> stored = _storedParts.computeIfAbsent(obj.tType(), ObjectMerge::storedParts);
		if (ignored.isEmpty()) {
			return stored;
		}
		List<TLStructuredTypePart> result = new ArrayList<>();
		for (TLStructuredTypePart part : stored) {
			if (!ignored.contains(part.getName())) {
				result.add(part);
			}
		}
		return result;
	}

	private static List<TLStructuredTypePart> storedParts(TLType type) {
		List<TLStructuredTypePart> result = new ArrayList<>();
		for (TLStructuredTypePart part : ((TLStructuredType) type).getAllParts()) {
			if (part.isDerived()) {
				continue;
			}
			if (part instanceof TLReference reference && reference.isBackwards()) {
				continue;
			}
			result.add(part);
		}
		return result;
	}

	private static boolean isComposite(TLStructuredTypePart part) {
		return part instanceof TLReference reference && reference.isComposite();
	}

	private static List<TLObject> asList(Object value) {
		List<TLObject> result = new ArrayList<>();
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				result.add((TLObject) element);
			}
		} else if (value != null) {
			result.add((TLObject) value);
		}
		return result;
	}

	private static boolean sameValue(Object x, Object y) {
		if (x instanceof BinaryDataSource a && y instanceof BinaryDataSource b) {
			return Arrays.equals(bytes(a), bytes(b));
		}
		if (x instanceof Set<?> a && y instanceof Collection<?> b) {
			return a.equals(new HashSet<>(b));
		}
		if (x instanceof Collection<?> a && y instanceof Collection<?> b) {
			if (a.size() != b.size()) {
				return false;
			}
			for (Iterator<?> i = a.iterator(), j = b.iterator(); i.hasNext();) {
				if (!sameValue(i.next(), j.next())) {
					return false;
				}
			}
			return true;
		}
		if (x instanceof Collection<?> a && y == null) {
			return a.isEmpty();
		}
		if (y instanceof Collection<?> b && x == null) {
			return b.isEmpty();
		}
		return Objects.equals(x, y);
	}

	private static byte[] bytes(BinaryDataSource data) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			data.deliverTo(buffer);
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot read binary value: " + ex.getMessage(), ex);
		}
		return buffer.toByteArray();
	}

}
