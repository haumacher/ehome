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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * An update keeps the identity of the target and of every part that has a counterpart in the source;
 * parts of the target without a counterpart are deleted (unless the {@link Policy} keeps them), and
 * parts of the source without a counterpart are copied to the target. A reference of the source to
 * one of its own parts becomes a reference to the counterpart of that part in the target. The source
 * is not changed. Nothing outside the target may refer to the parts it deletes.
 * </p>
 *
 * <p>
 * Which part of the target is the counterpart of a part of the source decides the {@link Policy}:
 * by default the part at the same position of the same reference ({@link #POSITIONAL}, the update of
 * known master data); a policy matches parts by a key within their reference (a parameter setting by
 * its parameter), or anywhere in the target (a device by its ID, also when it moved to another line),
 * restricts the attributes updated to those of one owner, keeps unmatched parts of the target, and
 * keeps the order of the target's ordered references (the update of a project from ETS,
 * {@link ProjectUpdate}).
 * </p>
 */
final class ObjectMerge {

	/**
	 * How the parts of a source and a target are matched, and what is updated.
	 */
	interface Policy {

		/**
		 * Whether the given stored attribute is compared and updated; an attribute not merged keeps
		 * the target's value, and its composite parts stay as they are.
		 */
		default boolean merges(TLStructuredTypePart part) {
			return true;
		}

		/**
		 * Whether the given merged attribute keeps the target's value where the source has none (an
		 * empty text, no value): a value the source does not know is no reason to erase the
		 * target's.
		 */
		default boolean keepsWhenEmpty(TLStructuredTypePart part) {
			return false;
		}

		/**
		 * The part of the target the given part of the source was matched to beforehand, wherever it
		 * is in the target; <code>null</code> to match it within its reference.
		 */
		default TLObject counterpart(TLObject source) {
			return null;
		}

		/**
		 * The key matching a part of the source with a part of the target within their reference:
		 * parts of the same type with equal keys are counterparts; <code>null</code> to match by
		 * position.
		 */
		default Object key(TLObject part) {
			return null;
		}

		/**
		 * Whether a part of the target without a counterpart in the source stays where it is,
		 * instead of being deleted.
		 */
		default boolean keeps(TLObject target) {
			return false;
		}

		/**
		 * Whether the given ordered reference keeps the order of the target: its parts with a
		 * counterpart stay in their order, new parts follow in the order of the source. Otherwise the
		 * reference takes the order of the source.
		 */
		default boolean keepsOrder(TLStructuredTypePart part) {
			return false;
		}
	}

	/**
	 * The {@link Policy} matching parts by their position, updating every stored attribute.
	 */
	static final Policy POSITIONAL = new Policy() {
		// All defaults.
	};

	/**
	 * A composite reference of a target, its parts before and the counterparts of the source's parts
	 * in the source's order.
	 */
	private record Composite(TLObject target, TLStructuredTypePart part, List<TLObject> before,
			List<TLObject> given) {
		// Pure data.
	}

	/**
	 * The stored parts of each type compared or updated so far, see {@link #storedParts(TLObject, Set)}:
	 * the master data of a file compare thousands of objects of a few types.
	 */
	private final Map<TLType, List<TLStructuredTypePart>> _storedParts = new HashMap<>();

	private final Policy _policy;

	/** The counterpart in the target of each part of the source, in the order matched. */
	private final Map<TLObject, TLObject> _targets = new LinkedHashMap<>();

	/** The attributes not to update of the target objects given (not of their parts). */
	private final Map<TLObject, Set<String>> _ignored = new HashMap<>();

	/** The parts of the target that are the counterpart of a part of the source. */
	private final Set<TLObject> _claimed = new HashSet<>();

	/** The parts of the target created as counterparts of new parts of the source. */
	private final Set<TLObject> _created = new LinkedHashSet<>();

	/** The parts of the target whose own values changed. */
	private final Set<TLObject> _changed = new LinkedHashSet<>();

	/** The parts of the target given another container. */
	private final Set<TLObject> _moved = new LinkedHashSet<>();

	/** The parts of the target deleted, without their own parts. */
	private final List<TLObject> _deleted = new ArrayList<>();

	/** The parts of the target kept without a counterpart. */
	private final List<TLObject> _kept = new ArrayList<>();

	private final List<Composite> _composites = new ArrayList<>();

	/**
	 * Creates a {@link ObjectMerge} matching parts by their position.
	 */
	ObjectMerge() {
		this(POSITIONAL);
	}

	/**
	 * Creates a {@link ObjectMerge} with the given policy.
	 */
	ObjectMerge(Policy policy) {
		_policy = policy;
	}

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
		reset();
		match(target, source, ignored);
		apply(garbage);
	}

	/**
	 * Forgets the outcome of the last update.
	 */
	void reset() {
		_targets.clear();
		_ignored.clear();
		_claimed.clear();
		_created.clear();
		_changed.clear();
		_moved.clear();
		_deleted.clear();
		_kept.clear();
		_composites.clear();
	}

	/**
	 * Matches the parts of the source with those of the target (recursively), creating a
	 * counterpart for each part of the source without one; nothing of the target is changed before
	 * {@link #apply(List)}.
	 *
	 * @param ignored
	 *        Names of attributes of the two objects themselves (not of their parts) not to update.
	 */
	void match(TLObject target, TLObject source, Set<String> ignored) {
		_targets.put(source, target);
		_claimed.add(target);
		if (!ignored.isEmpty()) {
			_ignored.put(target, ignored);
		}
		for (TLStructuredTypePart part : storedParts(target, ignored)) {
			if (isComposite(part)) {
				matchParts(target, source, part);
			}
		}
	}

	private void matchParts(TLObject target, TLObject source, TLStructuredTypePart part) {
		List<TLObject> targets = asList(target.tValue(part));
		List<TLObject> sources = asList(source.tValue(part));
		Map<Object, List<TLObject>> byKey = new HashMap<>();
		for (TLObject candidate : targets) {
			Object key = _policy.key(candidate);
			if (key != null) {
				byKey.computeIfAbsent(key, x -> new ArrayList<>()).add(candidate);
			}
		}
		List<TLObject> given = new ArrayList<>();
		for (int n = 0, cnt = sources.size(); n < cnt; n++) {
			TLObject sourcePart = sources.get(n);
			TLObject counterpart = _policy.counterpart(sourcePart);
			if (counterpart == null) {
				Object key = _policy.key(sourcePart);
				if (key != null) {
					counterpart = unclaimed(byKey.getOrDefault(key, List.of()), sourcePart);
				} else if (n < targets.size()) {
					TLObject candidate = targets.get(n);
					if (!_claimed.contains(candidate) && candidate.tType() == sourcePart.tType()) {
						counterpart = candidate;
					}
				}
			}
			if (counterpart == null) {
				counterpart = ModelService.getInstance().getFactory().createObject((TLClass) sourcePart.tType());
				_created.add(counterpart);
			} else if (!targets.contains(counterpart)) {
				_moved.add(counterpart);
			}
			given.add(counterpart);
			match(counterpart, sourcePart, Set.of());
		}
		_composites.add(new Composite(target, part, targets, given));
	}

	private TLObject unclaimed(List<TLObject> candidates, TLObject source) {
		for (TLObject candidate : candidates) {
			if (!_claimed.contains(candidate) && candidate.tType() == source.tType()) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Updates the target as matched by {@link #match(TLObject, TLObject, Set)}: the composite
	 * references, then the values of every counterpart.
	 *
	 * @param garbage
	 *        Where to put the parts of the target that are to be deleted.
	 */
	void apply(List<TLObject> garbage) {
		List<Composite> updates = new ArrayList<>();
		List<List<TLObject>> results = new ArrayList<>();
		for (Composite composite : _composites) {
			List<TLObject> result = result(composite);
			if (!sameParts(composite, result)) {
				updates.add(composite);
				results.add(result);
			}
		}
		// Parts moving to another container leave the old one first.
		for (int n = 0, cnt = updates.size(); n < cnt; n++) {
			Composite composite = updates.get(n);
			List<TLObject> staying = new ArrayList<>(composite.before());
			staying.retainAll(results.get(n));
			if (staying.size() != composite.before().size()) {
				set(composite.target(), composite.part(), staying);
			}
		}
		for (int n = 0, cnt = updates.size(); n < cnt; n++) {
			Composite composite = updates.get(n);
			set(composite.target(), composite.part(), results.get(n));
		}
		for (Map.Entry<TLObject, TLObject> entry : _targets.entrySet()) {
			TLObject source = entry.getKey();
			TLObject target = entry.getValue();
			for (TLStructuredTypePart part : storedParts(target, _ignored.getOrDefault(target, Set.of()))) {
				if (isComposite(part)) {
					continue;
				}
				Object value = mapped(source.tValue(part));
				if (isEmpty(value) && _policy.keepsWhenEmpty(part)) {
					continue;
				}
				if (!sameValue(target.tValue(part), value)) {
					target.tUpdate(part, value instanceof Collection<?> collection ? new ArrayList<>(collection) : value);
					if (!_created.contains(target)) {
						_changed.add(target);
					}
				}
			}
		}
		garbage.addAll(_deleted);
	}

	private List<TLObject> result(Composite composite) {
		List<TLObject> before = composite.before();
		List<TLObject> given = composite.given();
		List<TLObject> result = new ArrayList<>();
		if (composite.part().isOrdered() && _policy.keepsOrder(composite.part())) {
			Set<TLObject> givenSet = new HashSet<>(given);
			for (TLObject part : before) {
				if (givenSet.contains(part) || stays(part)) {
					result.add(part);
				}
			}
			Set<TLObject> beforeSet = new HashSet<>(before);
			for (TLObject part : given) {
				if (!beforeSet.contains(part)) {
					result.add(part);
				}
			}
		} else {
			result.addAll(given);
			for (TLObject part : before) {
				if (stays(part)) {
					result.add(part);
				}
			}
		}
		for (TLObject part : before) {
			if (!_claimed.contains(part)) {
				if (stays(part)) {
					_kept.add(part);
				} else {
					_deleted.add(part);
				}
			}
		}
		return result;
	}

	private boolean stays(TLObject part) {
		return !_claimed.contains(part) && _policy.keeps(part);
	}

	private static boolean sameParts(Composite composite, List<TLObject> result) {
		if (composite.part().isOrdered()) {
			return composite.before().equals(result);
		}
		return composite.before().size() == result.size() && new HashSet<>(composite.before()).equals(new HashSet<>(result));
	}

	private static void set(TLObject target, TLStructuredTypePart part, List<TLObject> value) {
		if (part.isMultiple()) {
			target.tUpdate(part, value);
		} else {
			target.tUpdate(part, value.isEmpty() ? null : value.get(0));
		}
	}

	/**
	 * The value of the source with its references to parts of the source replaced by their
	 * counterparts in the target.
	 */
	private Object mapped(Object value) {
		if (value instanceof TLObject obj) {
			TLObject target = _targets.get(obj);
			return target == null ? obj : target;
		}
		if (value instanceof Collection<?> collection && collection.stream().anyMatch(_targets::containsKey)) {
			List<Object> result = new ArrayList<>(collection.size());
			for (Object element : collection) {
				result.add(mapped(element));
			}
			return result;
		}
		return value;
	}

	/**
	 * The counterpart in the target of the given part of the source, after
	 * {@link #match(TLObject, TLObject, Set)}.
	 */
	TLObject targetOf(TLObject source) {
		return _targets.get(source);
	}

	/**
	 * The parts of the target created as counterparts of new parts of the source.
	 */
	Set<TLObject> getCreated() {
		return _created;
	}

	/**
	 * The parts of the target whose own values the update changed (not those created).
	 */
	Set<TLObject> getChanged() {
		return _changed;
	}

	/**
	 * The parts of the target the update gave another container.
	 */
	Set<TLObject> getMoved() {
		return _moved;
	}

	/**
	 * The parts of the target without a counterpart that are deleted (without their own parts),
	 * after {@link #apply(List)}.
	 */
	List<TLObject> getDeleted() {
		return _deleted;
	}

	/**
	 * The parts of the target without a counterpart that the policy keeps, after
	 * {@link #apply(List)}.
	 */
	List<TLObject> getKept() {
		return _kept;
	}

	private List<TLStructuredTypePart> storedParts(TLObject obj, Set<String> ignored) {
		List<TLStructuredTypePart> stored = _storedParts.computeIfAbsent(obj.tType(), this::mergedParts);
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

	private List<TLStructuredTypePart> mergedParts(TLType type) {
		List<TLStructuredTypePart> result = new ArrayList<>();
		for (TLStructuredTypePart part : ((TLStructuredType) type).getAllParts()) {
			if (part.isDerived()) {
				continue;
			}
			if (part instanceof TLReference reference && reference.isBackwards()) {
				continue;
			}
			if (!_policy.merges(part)) {
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

	private static boolean isEmpty(Object value) {
		return value == null || value instanceof CharSequence text && text.toString().isBlank()
			|| value instanceof Collection<?> collection && collection.isEmpty();
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
