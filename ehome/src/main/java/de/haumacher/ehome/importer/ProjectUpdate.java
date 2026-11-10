/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ExtIds.Kind;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ProjectParts;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.ExpressionRenames;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.Referers;

/**
 * Updates a project from a new export of the same project in ETS (haumacher/ehome #125), keeping
 * what E-Home alone holds.
 *
 * <p>
 * The file is imported by the normal ETS import into a project of its own (the <em>source</em>,
 * reusing the shared data as every import does); its KNX part is then merged into the existing
 * project (the <em>target</em>) in place, and the source is deleted, all in the transaction of the
 * import: a refusal or failure leaves the target as it was. The split between ETS and E-Home is the
 * one of {@link ProjectParts}: ETS wins on what the file carries, E-Home keeps its compositions and
 * its own attributes of KNX objects.
 * </p>
 *
 * <p>
 * <b>Matching.</b> An object of the project's installation (area, line, device, group range, group
 * address, building part) is matched by its ID wherever it is, so a group address re-addressed or
 * renamed in ETS keeps its identity, its settings, recordings and every rule using it, and a device
 * moved to another line keeps its functions. An object E-Home created itself (its ID in the app's
 * range, {@link ExtIds#isCreatedInApp(TLObject)}) is never deleted: the file adopts it where it
 * brings the same object by its natural identity (a device by line, individual address and product,
 * a group address by its address, a range by its addresses, a building part by name and type in the
 * same parent, an area or line by its address); the object keeps its identity and takes the ID of
 * ETS. Otherwise it stays as it is. Objects without an ID of their own (parameter settings,
 * communication objects, connections, module instances) are matched within their container by
 * their natural key ({@link ProjectParts#naturalKey(TLObject)}). Objects of the file without a
 * counterpart are added; objects of ETS the file no longer has are deleted.
 * </p>
 *
 * <p>
 * <b>Refused</b> as a whole, naming each problem: a file of another project; an object the file no
 * longer has that E-Home's part of the project uses (a group address in a rule or function, a
 * building part as the location of a Zigbee device, a device of a function, …; the operating history
 * does not count: recordings and deployments keep their own record of an address); a group address
 * the file no longer has whose address an expression of a rule names; an object created in E-Home
 * whose container the file no longer has; a device created in E-Home at the place of a device of the
 * file with another product.
 * </p>
 *
 * <p>
 * <b>Order.</b> Ordered references keep the order of the project (the user's order of the building
 * structure, haumacher/ehome #71); parts new in the file follow at the end of their container in the
 * order of the file.
 * </p>
 *
 * <p>
 * <b>Report.</b> What was added, changed and removed per kind, which objects of E-Home were kept or
 * adopted; a file that brings no changes changes no object and says so.
 * </p>
 */
public final class ProjectUpdate {

	private final TLObject _target;

	private final TLObject _source;

	private final BufferingI18NLog _log = new BufferingI18NLog();

	/** The objects of the installation of the source matched beforehand, with their counterparts. */
	private final Map<TLObject, TLObject> _counterparts = new HashMap<>();

	/** The objects of the target that are the counterpart of an object of the source. */
	private final Set<TLObject> _claimed = new HashSet<>();

	/** The objects of the target E-Home created that the file adopts. */
	private final List<TLObject> _adopted = new ArrayList<>();

	/** The objects of the KNX part of the source. */
	private Set<TLObject> _sources = Set.of();

	private final List<String> _problems = new ArrayList<>();

	/** The labels of the objects of the target before the update. */
	private final Map<TLObject, String> _labels = new HashMap<>();

	private boolean _changes;

	/**
	 * Creates a {@link ProjectUpdate}.
	 *
	 * @param target
	 *        The project to update.
	 * @param source
	 *        The project the import of the file created, deleted by {@link #apply()}.
	 */
	public ProjectUpdate(TLObject target, TLObject source) {
		_target = target;
		_source = source;
	}

	/**
	 * The messages about the update: what changed, what was kept and adopted.
	 */
	public List<BufferingI18NLog.Entry> getMessages() {
		return _log.getEntries();
	}

	/**
	 * Whether the update changed the project.
	 */
	public boolean hasChanges() {
		return _changes;
	}

	/**
	 * Merges the KNX part of the source into the target and deletes the source.
	 *
	 * @throws TopLogicException
	 *         If the update is refused, with the reasons; the caller rolls back the transaction.
	 */
	public void apply() {
		checkProject();

		Set<TLObject> targets = ProjectParts.objects(_target);
		_sources = ProjectParts.objects(_source);
		matchByIds(targets);
		adopt(targets);
		refuseProblems();

		// Named as they are now: a removed object loses its container, from which a label may derive.
		for (TLObject target : targets) {
			if (ExtIds.extId(target) != null) {
				_labels.put(target, label(target));
			}
		}

		ObjectMerge merge = new ObjectMerge(new Policy());
		merge.match(_target, _source, Set.of());
		List<TLObject> garbage = new ArrayList<>();
		merge.apply(garbage);

		Set<TLObject> removed = new LinkedHashSet<>();
		for (TLObject root : garbage) {
			removed.addAll(Referers.withParts(root));
		}
		checkRemoved(garbage, removed);
		refuseProblems();

		// Named before they are gone.
		report(merge, garbage);

		delete(garbage);
		_source.tDelete();
		// Numbers of the app's range brought by the file (an object of E-Home that went through ETS).
		ExtIds.raiseImported(merge.getCreated());
	}

	private void checkProject() {
		Object id = _source.tValueByName(ExtIds.EXT_ID);
		Object expected = _target.tValueByName(ExtIds.EXT_ID);
		if (!Objects.equals(id, expected)) {
			throw new TopLogicException(I18NConstants.ERROR_UPDATE_OTHER_PROJECT__FILE_ID_PROJECT_ID.fill(
				label(_source), id, label(_target), expected));
		}
	}

	/**
	 * Matches the objects of the installation by their IDs, wherever they are.
	 */
	private void matchByIds(Set<TLObject> targets) {
		Map<String, TLObject> byId = new HashMap<>();
		for (TLObject target : targets) {
			if (isInstallation(target) && ExtIds.extId(target) != null) {
				byId.put(ExtIds.extId(target), target);
			}
		}
		for (TLObject source : _sources) {
			if (!isInstallation(source)) {
				continue;
			}
			TLObject target = byId.get(ExtIds.extId(source));
			if (target != null && target.tType() == source.tType()) {
				counterpart(source, target);
			}
		}
	}

	/**
	 * Matches the objects E-Home created that the file brings by their natural identity.
	 */
	private void adopt(Set<TLObject> targets) {
		List<TLObject> created = new ArrayList<>();
		for (TLObject target : targets) {
			if (ExtIds.isCreatedInApp(target) && !_claimed.contains(target)) {
				created.add(target);
			}
		}
		if (created.isEmpty()) {
			return;
		}
		// Containers first: the identity of a part names the counterpart of its container.
		for (TLObject source : _sources) {
			if (!isInstallation(source) || _counterparts.containsKey(source)) {
				continue;
			}
			List<Object> identity = identity(source, true);
			if (identity == null) {
				continue;
			}
			for (TLObject target : created) {
				if (_claimed.contains(target) || target.tType() != source.tType()
					|| !identity.equals(identity(target, false))) {
					continue;
				}
				if (ExtIds.kindOf(target) == Kind.DEVICE && !sameProduct(target, source)) {
					_problems.add(text(I18NConstants.UPDATE_OTHER_PRODUCT__OBJECT_FILE.fill(name(target), name(source))));
				} else {
					counterpart(source, target);
					_adopted.add(target);
				}
				break;
			}
		}
	}

	private void counterpart(TLObject source, TLObject target) {
		_counterparts.put(source, target);
		_claimed.add(target);
	}

	private static boolean sameProduct(TLObject a, TLObject b) {
		return a.tValueByName("type") == b.tValueByName("type") && a.tValueByName("program") == b.tValueByName("program");
	}

	/**
	 * The natural identity of an object of the installation: the counterpart of its container where
	 * it is identified within it, and its address or name; <code>null</code> if it has none.
	 *
	 * @param source
	 *        Whether the object is of the source: its container is then named by its counterpart.
	 */
	private List<Object> identity(TLObject obj, boolean source) {
		Kind kind = ExtIds.kindOf(obj);
		TLObject container = obj.tContainer();
		TLObject containerKey = !source ? container : container == _source ? _target : _counterparts.get(container);
		switch (kind) {
			case AREA:
				return List.of(String.valueOf(obj.tValueByName("address")));
			case LINE:
				return containerKey == null ? null : List.of(containerKey, String.valueOf(obj.tValueByName("address")));
			case DEVICE:
				// An unassigned device has no address in a line.
				if (containerKey == null || containerKey == _target || obj.tValueByName("address") == null) {
					return null;
				}
				return List.of(containerKey, String.valueOf(obj.tValueByName("address")));
			case GROUP_ADDRESS:
				return obj.tValueByName("address") instanceof GA address ? List.of(Integer.valueOf(address.getValue())) : null;
			case GROUP_RANGE:
				if (obj.tValueByName("rangeStart") instanceof GA start && obj.tValueByName("rangeEnd") instanceof GA end) {
					return List.of(Integer.valueOf(start.getValue()), Integer.valueOf(end.getValue()));
				}
				return null;
			case BUILDING_PART:
				return containerKey == null ? null : List.of(containerKey, String.valueOf(obj.tValueByName("name")));
			default:
				return null;
		}
	}

	private static boolean isInstallation(TLObject obj) {
		Kind kind = ExtIds.kindOf(obj);
		return kind != null && kind.isProjectPart();
	}

	/**
	 * Refuses objects E-Home created whose container the file removes, objects the file removes
	 * that E-Home's part uses, and group addresses the file removes that an expression names.
	 */
	private void checkRemoved(List<TLObject> roots, Set<TLObject> removed) {
		for (TLObject root : roots) {
			for (TLObject part : Referers.withParts(root)) {
				if (part != root && ExtIds.isCreatedInApp(part)) {
					_problems.add(text(I18NConstants.UPDATE_CONTAINER_REMOVED__OBJECT_CONTAINER.fill(removedName(part),
						removedName(root))));
				}
			}
		}
		for (TLObject root : roots) {
			for (TLObject part : Referers.withParts(root)) {
				Map<TLObject, Set<String>> referers =
					Referers.referers(_target, List.of(part), removed, referer -> referer == _source
						|| ProjectParts.isInHistory(referer) || isWithin(referer, _source));
				for (Map.Entry<TLObject, Set<String>> entry : referers.entrySet()) {
					_problems.add(text(I18NConstants.UPDATE_REMOVED_IN_USE__OBJECT_REFERER.fill(removedName(part),
						Referers.line(entry.getKey(), entry.getValue()))));
				}
			}
		}
		checkExpressions(removed);
	}

	/**
	 * Refuses the update if an expression of a rule names the address of a removed group address
	 * that no group address of the project has afterwards.
	 */
	private void checkExpressions(Set<TLObject> removed) {
		Set<String> remaining = new HashSet<>();
		for (TLObject obj : ProjectParts.objects(_target)) {
			if (!removed.contains(obj) && Pages.isGroupAddress(obj)) {
				remaining.add(Pages.imageId(obj));
			}
		}
		List<TLObject> rules = ProjectParts.values(_target.tValueByName("rules"));
		for (TLObject obj : removed) {
			if (!Pages.isGroupAddress(obj)) {
				continue;
			}
			String id = Pages.imageId(obj);
			if (id == null || remaining.contains(id)) {
				continue;
			}
			ExpressionRenames probe = new ExpressionRenames();
			probe.datapoint(id, id);
			for (TLObject rule : rules) {
				if (probe.names(rule)) {
					_problems.add(
						text(I18NConstants.UPDATE_REMOVED_IN_EXPRESSION__OBJECT_RULE.fill(removedName(obj), label(rule))));
				}
			}
		}
	}

	private static boolean isWithin(TLObject obj, TLObject container) {
		for (TLObject current = obj; current != null; current = current.tContainer()) {
			if (current == container) {
				return true;
			}
		}
		return false;
	}

	private void refuseProblems() {
		if (_problems.isEmpty()) {
			return;
		}
		StringBuilder lines = new StringBuilder();
		for (String problem : new LinkedHashSet<>(_problems)) {
			lines.append("\n• ").append(problem);
		}
		throw new TopLogicException(I18NConstants.ERROR_UPDATE_REFUSED__PROJECT_PROBLEMS.fill(label(_target), lines));
	}

	private static void delete(List<TLObject> objects) {
		List<KnowledgeItem> items = new ArrayList<>();
		for (TLObject obj : objects) {
			if (obj.tValid()) {
				items.add(obj.tHandle());
			}
		}
		if (!items.isEmpty()) {
			items.get(0).getKnowledgeBase().deleteAll(items);
		}
	}

	// ---- The report ---------------------------------------------------------------------------

	private void report(ObjectMerge merge, List<TLObject> removed) {
		Map<String, List<List<TLObject>>> kinds = new TreeMap<>();
		Set<TLObject> created = merge.getCreated();
		for (TLObject obj : created) {
			// A new object with its parts counts once.
			if (!created.contains(obj.tContainer())) {
				entry(kinds, obj).get(0).add(obj);
			}
		}
		Set<TLObject> changed = new LinkedHashSet<>(merge.getChanged());
		changed.addAll(merge.getMoved());
		changed.removeAll(created);
		for (TLObject obj : changed) {
			entry(kinds, obj).get(1).add(obj);
		}
		for (TLObject obj : removed) {
			entry(kinds, obj).get(2).add(obj);
		}
		for (Map.Entry<String, List<List<TLObject>>> kind : kinds.entrySet()) {
			List<List<TLObject>> lists = kind.getValue();
			_log.log(Level.INFO, I18NConstants.UPDATE_CHANGES__KIND_ADDED_CHANGED_REMOVED.fill(kind.getKey(),
				names(lists.get(0), false), names(lists.get(1), false), names(lists.get(2), true)), null);
		}
		List<TLObject> kept = merge.getKept();
		if (!_adopted.isEmpty()) {
			_log.log(Level.INFO, I18NConstants.UPDATE_ADOPTED__OBJECTS.fill(join(_adopted)), null);
		}
		if (!kept.isEmpty()) {
			_log.log(Level.INFO, I18NConstants.UPDATE_KEPT__OBJECTS.fill(join(kept)), null);
		}
		_changes = !kinds.isEmpty() || !_adopted.isEmpty();
		if (!_changes) {
			_log.log(Level.INFO, I18NConstants.UPDATE_NO_CHANGES, null);
		}
	}

	private static List<List<TLObject>> entry(Map<String, List<List<TLObject>>> kinds, TLObject obj) {
		return kinds.computeIfAbsent(kindLabel(obj), x -> List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
	}

	/**
	 * The number of the given objects, with their names where they have an ID of ETS.
	 *
	 * @param removed
	 *        Whether the objects are removed: they are named as before the update.
	 */
	private String names(List<TLObject> objects, boolean removed) {
		if (objects.isEmpty()) {
			return "0";
		}
		List<String> names = new ArrayList<>();
		for (TLObject obj : objects) {
			if (ExtIds.extId(obj) != null) {
				names.add(removed ? removedLabel(obj) : label(obj));
			}
		}
		if (names.isEmpty()) {
			return Integer.toString(objects.size());
		}
		names.sort(Comparator.naturalOrder());
		return objects.size() + " (" + String.join(", ", names) + ")";
	}

	private String join(Collection<TLObject> objects) {
		List<String> names = new ArrayList<>();
		for (TLObject obj : objects) {
			names.add(name(obj));
		}
		return String.join(", ", names);
	}

	/**
	 * An object with its kind: <code>Gruppenadresse „Licht (1/1/1)“</code>.
	 */
	private String name(TLObject obj) {
		return text(I18NConstants.UPDATE_OBJECT__KIND_NAME.fill(kindLabel(obj), label(obj)));
	}

	/**
	 * An object the update removes with its kind, labelled as before the update.
	 */
	private String removedName(TLObject obj) {
		return text(I18NConstants.UPDATE_OBJECT__KIND_NAME.fill(kindLabel(obj), removedLabel(obj)));
	}

	private String removedLabel(TLObject obj) {
		String before = _labels.get(obj);
		return before != null ? before : label(obj);
	}

	private static String kindLabel(TLObject obj) {
		return Objects.toString(MetaLabelProvider.INSTANCE.getLabel(obj.tType()), "");
	}

	/**
	 * The label of an object, a datapoint as its display texts name it (haumacher/ehome #113).
	 */
	private String label(TLObject obj) {
		if (Pages.isGroupAddress(obj)) {
			String name = DatapointNames.name(obj);
			if (name != null) {
				return name;
			}
		}
		return Objects.toString(MetaLabelProvider.INSTANCE.getLabel(obj), "");
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	/**
	 * The {@link ObjectMerge.Policy} of the update.
	 */
	private final class Policy implements ObjectMerge.Policy {

		private final Map<TLObject, Object> _keys = new LinkedHashMap<>();

		@Override
		public boolean merges(TLStructuredTypePart part) {
			return ProjectParts.isEtsOwned(part);
		}

		@Override
		public TLObject counterpart(TLObject source) {
			return _counterparts.get(source);
		}

		@Override
		public Object key(TLObject part) {
			if (isInstallation(part)) {
				// Matched by ID or adopted beforehand, never by position.
				return part;
			}
			// Not computeIfAbsent: the key of a part may contain the keys of other parts.
			Object key = _keys.get(part);
			if (key == null) {
				key = computeKey(part);
				_keys.put(part, key);
			}
			return key;
		}

		private Object computeKey(TLObject part) {
			String extId = ExtIds.extId(part);
			if (extId != null) {
				return extId;
			}
			List<String> attributes = ProjectParts.naturalKey(part);
			if (attributes == null) {
				// Never matched by position.
				return part;
			}
			List<Object> key = new ArrayList<>();
			key.add(part.tType());
			for (String attribute : attributes) {
				key.add(keyValue(part.tValueByName(attribute)));
			}
			return key;
		}

		private Object keyValue(Object value) {
			if (!(value instanceof TLObject obj)) {
				return value;
			}
			TLObject counterpart = _counterparts.get(obj);
			if (counterpart != null) {
				return counterpart;
			}
			if (_sources.contains(obj)) {
				// A part of the source without counterpart: its ID or its key.
				return isInstallation(obj) ? ExtIds.extId(obj) : key(obj);
			}
			if (!isInstallation(obj) && ProjectParts.naturalKey(obj) != null) {
				return key(obj);
			}
			return obj;
		}

		@Override
		public boolean keeps(TLObject target) {
			return ExtIds.isCreatedInApp(target);
		}

		@Override
		public boolean keepsOrder(TLStructuredTypePart part) {
			return true;
		}
	}

}
