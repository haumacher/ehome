/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.SimpleInstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.ModelKind;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.TLConstraints;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Referers;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleImport;

/**
 * Applies definitions of rules, schedule entries and application datapoints in the JSON of the
 * runtime image ({@link AutomationJson#toImage(Object, List)}) to a project, in the caller's
 * transaction (haumacher/ehome #128).
 *
 * <ul>
 * <li>An item whose id the project has not got is added, as the import of an image adds it
 * ({@link RuleImport}): the expressions in canonical text, the references to datapoints, schedule
 * entries, timers and parameters resolved by their ids.</li>
 * <li>An item whose id the project has is <b>replaced in place</b>: the definition is read by the
 * same import, then the existing object takes its values, so that everything that refers to the
 * existing object (a page element switching a rule, a trigger naming a schedule entry, a function
 * role naming a datapoint) keeps referring to it. Parts with an id (the parameters, timers and
 * states of a rule) are matched by it and keep their identity too; the parts without (reactions,
 * triggers, actions) are replaced. The order of a rule, the overrides of the simulation and the
 * Zigbee binding of a datapoint stay as they are. A part removed by the definition that something
 * outside the item still refers to refuses the change.</li>
 * </ul>
 *
 * <p>
 * Afterwards every field of the items is checked as the rule editor checks it (the model's
 * constraints: the syntax of a text, the refusal of the controller's loader, the checks of ids and
 * units), see {@link #problems()}.
 * </p>
 */
final class AutomationChange {

	/** The attributes a replaced item keeps from the existing object. */
	private static final Set<String> KEPT = Set.of("order", "simulationEnabled", "simulationState", "simulationValue",
		"zigbeeFunction");

	/** The kinds of items: the member of the definitions and the reference of the project. */
	private static final String[][] KINDS = {
		{ "datapoints", ApplicationDatapoints.REFERENCE },
		{ "schedules", "schedules" },
		{ "rules", "rules" } };

	/**
	 * A field of an item the application refuses.
	 *
	 * @param item
	 *        The item: <code>rule frost</code>.
	 * @param part
	 *        The part of the item the field belongs to, as the rule editor's tables show it
	 *        (<code>write Heizung (1/0/1) := true</code>), <code>null</code> for the item itself.
	 * @param attribute
	 *        The name of the field's attribute.
	 * @param message
	 *        The application's message in the language of the run's user.
	 * @param error
	 *        Whether the editor refuses to store the value (a text that does not parse, a missing
	 *        value); otherwise the controller would refuse the image (a warning in the editor).
	 */
	record Problem(String item, String part, String attribute, String message, boolean error) {

		/** The problem as the model reads it. */
		Map<String, Object> json() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("item", item);
			if (part != null) {
				result.put("part", part);
			}
			result.put("attribute", attribute);
			result.put("message", message);
			result.put("severity", error ? "error" : "refused by the controller");
			return result;
		}
	}

	private final TLObject _project;

	private final List<Problem> _problems = new ArrayList<>();

	private final List<String> _notes = new ArrayList<>();

	private final List<TLObject> _created = new ArrayList<>();

	private final List<TLObject> _replaced = new ArrayList<>();

	/** The objects of the imported definitions standing for existing objects, to the existing ones. */
	private final Map<TLObject, TLObject> _correspondence = new IdentityHashMap<>();

	/**
	 * Creates an {@link AutomationChange} of the given project.
	 */
	AutomationChange(TLObject project) {
		_project = project;
	}

	/** The items added. */
	List<TLObject> created() {
		return _created;
	}

	/** The existing items replaced. */
	List<TLObject> replaced() {
		return _replaced;
	}

	/** What the fields of the items are refused for. */
	List<Problem> problems() {
		return _problems;
	}

	/** What the import did not keep or decided besides. */
	List<String> notes() {
		return _notes;
	}

	/**
	 * Applies the definitions in the JSON of the image.
	 *
	 * @throws TopLogicException
	 *         If the definitions cannot be read (an unknown kind of trigger, an id naming nothing of
	 *         the project, a removed part still referred to). The caller's transaction must then be
	 *         rolled back: part of the change may have been made.
	 */
	void apply(Map<?, ?> image) {
		// The existing items the definitions replace are taken out of the project first, so that the
		// import neither keeps them (an application datapoint of a known id) nor resolves an id to
		// them instead of to the definition read with it.
		Map<String, Map<String, TLObject>> existing = new HashMap<>();
		Map<TLObject, Integer> positions = new IdentityHashMap<>();
		for (String[] kind : KINDS) {
			Map<String, TLObject> byId = new HashMap<>();
			List<TLObject> items = objects(_project.tValueByName(kind[1]));
			for (int n = 0; n < items.size(); n++) {
				TLObject item = items.get(n);
				String id = identifier(item);
				if (id != null) {
					byId.put(id, item);
					positions.put(item, Integer.valueOf(n));
				}
			}
			existing.put(kind[0], byId);
		}
		Map<TLObject, TLObject> pairs = new LinkedHashMap<>();
		Map<String, List<TLObject>> detached = new HashMap<>();
		for (String[] kind : KINDS) {
			List<TLObject> remove = new ArrayList<>();
			for (Object item : AutomationJson.list(image.get(kind[0]))) {
				if (item instanceof Map<?, ?> map && map.get("id") != null) {
					TLObject old = existing.get(kind[0]).get(String.valueOf(map.get("id")));
					if (old != null && !remove.contains(old)) {
						remove.add(old);
					}
				}
			}
			if (!remove.isEmpty()) {
				List<TLObject> items = objects(_project.tValueByName(kind[1]));
				items.removeAll(remove);
				_project.tUpdateByName(kind[1], items);
			}
			detached.put(kind[1], remove);
		}

		RuleImport importer = new RuleImport();
		Map<String, List<TLObject>> imported = new LinkedHashMap<>();
		imported.put(ApplicationDatapoints.REFERENCE,
			importer.importDatapoints(_project, AutomationJson.list(image.get("datapoints"))));
		imported.put("schedules", importer.importSchedules(_project, AutomationJson.list(image.get("schedules"))));
		imported.put("rules", importer.importRules(_project, AutomationJson.list(image.get("rules"))));
		_notes.addAll(importer.getNotes());

		for (Map.Entry<String, List<TLObject>> kind : imported.entrySet()) {
			for (TLObject item : kind.getValue()) {
				TLObject old = null;
				for (TLObject candidate : detached.get(kind.getKey())) {
					if (String.valueOf(identifier(item)).equals(identifier(candidate))) {
						old = candidate;
					}
				}
				if (old == null) {
					_created.add(item);
					continue;
				}
				if (old.tType() != item.tType()) {
					throw new TopLogicException(ResKey.text("'" + identifier(old) + "' is a " + old.tType().getName()
						+ " and cannot become a " + item.tType().getName()
						+ ". Delete it first, then create the new one."));
				}
				pairs.put(item, old);
				match(old, item);
			}
		}

		// The values of the replacements, with every reference to a part of a replacement directed to
		// the existing object it stands for.
		Set<TLObject> removed = new LinkedHashSet<>();
		for (Map.Entry<TLObject, TLObject> pair : pairs.entrySet()) {
			take(pair.getValue(), pair.getKey(), removed);
		}
		for (TLObject item : _created) {
			redirect(item);
		}

		// The existing items back at their places, the shells of their replacements gone.
		for (String[] kind : KINDS) {
			List<TLObject> back = detached.get(kind[1]);
			if (back.isEmpty()) {
				continue;
			}
			List<TLObject> items = objects(_project.tValueByName(kind[1]));
			items.removeAll(pairs.keySet());
			back.sort((a, b) -> positions.get(a).compareTo(positions.get(b)));
			for (TLObject old : back) {
				items.add(Math.min(positions.get(old).intValue(), items.size()), old);
			}
			_project.tUpdateByName(kind[1], items);
			_replaced.addAll(back);
		}
		for (TLObject shell : pairs.keySet()) {
			shell.tDelete();
		}

		// What the definitions removed must not be referred to from outside.
		Set<TLObject> deleted = new HashSet<>();
		for (TLObject part : removed) {
			deleted.addAll(Referers.withParts(part));
		}
		Map<TLObject, Set<String>> referers = Referers.referers(_project, removed, deleted, referer -> false);
		if (!referers.isEmpty()) {
			List<String> lines = new ArrayList<>();
			for (Map.Entry<TLObject, Set<String>> referer : referers.entrySet()) {
				lines.add(Referers.line(referer.getKey(), referer.getValue()));
			}
			throw new TopLogicException(ResKey.text("A part the definition leaves out is still referred to: "
				+ String.join("; ", lines) + ". Keep that part (with its id) or change what refers to it first."));
		}
		for (TLObject part : removed) {
			if (part.tValid()) {
				part.tDelete();
			}
		}

		List<TLObject> items = new ArrayList<>(_created);
		items.addAll(_replaced);
		for (TLObject item : items) {
			check(itemName(item), item, null, _problems);
		}
	}

	/**
	 * Records which parts of the replacement stand for which existing parts: the item itself, and
	 * the parts with an id of the same type, recursively.
	 */
	private void match(TLObject old, TLObject replacement) {
		_correspondence.put(replacement, old);
		for (TLStructuredTypePart part : replacement.tType().getAllParts()) {
			if (!isComposition(part)) {
				continue;
			}
			List<TLObject> olds = objects(old.tValue(part));
			for (TLObject child : objects(replacement.tValue(part))) {
				String id = identifier(child);
				if (id == null) {
					continue;
				}
				for (TLObject candidate : olds) {
					if (candidate.tType() == child.tType() && id.equals(identifier(candidate))
						&& !_correspondence.containsValue(candidate)) {
						match(candidate, child);
						break;
					}
				}
			}
		}
	}

	/**
	 * The existing object takes the values of its replacement; its parts the replacement does not
	 * keep are collected in <code>removed</code>.
	 */
	private void take(TLObject old, TLObject replacement, Set<TLObject> removed) {
		for (TLStructuredTypePart part : replacement.tType().getAllParts()) {
			if (part.isDerived() || KEPT.contains(part.getName())) {
				continue;
			}
			Object value = replacement.tValue(part);
			if (isComposition(part)) {
				List<TLObject> before = objects(old.tValue(part));
				List<TLObject> after = new ArrayList<>();
				for (TLObject child : objects(value)) {
					TLObject existing = _correspondence.get(child);
					if (existing != null) {
						take(existing, child, removed);
						after.add(existing);
					} else {
						redirect(child);
						after.add(child);
					}
				}
				// The parts move from the replacement to the existing object.
				replacement.tUpdate(part, ((TLReference) part).isMultiple() ? List.of() : null);
				old.tUpdate(part, ((TLReference) part).isMultiple() ? after : (after.isEmpty() ? null : after.get(0)));
				for (TLObject child : before) {
					if (!after.contains(child)) {
						removed.add(child);
					}
				}
			} else {
				old.tUpdate(part, mapped(value));
			}
		}
	}

	/**
	 * Directs the references of the object and its parts to parts of replacements to the existing
	 * objects they stand for.
	 */
	private void redirect(TLObject obj) {
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (part.isDerived() || part.getModelKind() != ModelKind.REFERENCE) {
				continue;
			}
			Object value = obj.tValue(part);
			if (isComposition(part)) {
				for (TLObject child : objects(value)) {
					redirect(child);
				}
			} else {
				Object mapped = mapped(value);
				if (mapped != value) {
					obj.tUpdate(part, mapped);
				}
			}
		}
	}

	private Object mapped(Object value) {
		if (value instanceof TLObject obj) {
			TLObject existing = _correspondence.get(obj);
			return existing != null ? existing : obj;
		}
		if (value instanceof Collection<?> collection) {
			boolean changed = false;
			List<Object> result = new ArrayList<>();
			for (Object element : collection) {
				Object mapped = mapped(element);
				changed |= mapped != element;
				result.add(mapped);
			}
			return changed ? (value instanceof Set<?> ? new LinkedHashSet<>(result) : result) : value;
		}
		return value;
	}

	// ---- the checks of the fields ------------------------------------------------------------

	/**
	 * Checks every field of the object and its parts as the forms of the views do: the constraints of
	 * the model (the syntax of the rule language, the refusal of the controller's loader, the checks of
	 * ids, units and outputs) and the mandatory fields.
	 *
	 * @param item
	 *        How the answers name the object.
	 */
	static List<Problem> check(String item, TLObject obj) {
		List<Problem> result = new ArrayList<>();
		check(item, obj, null, result);
		return result;
	}

	private static void check(String item, TLObject obj, String where, List<Problem> problems) {
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (part.isDerived()) {
				continue;
			}
			if (isComposition(part)) {
				for (TLObject child : objects(obj.tValue(part))) {
					check(item, child, describe(child), problems);
				}
				continue;
			}
			if (part.isMandatory() && empty(obj.tValue(part))) {
				problems.add(new Problem(item, where, part.getName(), "A value is required.", true));
			}
			TLConstraints constraints = part.getAnnotation(TLConstraints.class);
			if (constraints == null) {
				continue;
			}
			for (PolymorphicConfiguration<? extends ConstraintCheck> config : constraints.getConstraints()) {
				ConstraintCheck check = SimpleInstantiationContext.CREATE_ALWAYS_FAIL_IMMEDIATELY.getInstance(config);
				ResKey problem = check.check(obj, part);
				if (problem != null) {
					problems.add(new Problem(item, where, part.getName(), LogicSupport.text(problem),
						check.type() == ConstraintCheck.ConstraintType.ERROR));
				}
			}
		}
	}

	private static boolean empty(Object value) {
		return value == null || value instanceof Collection<?> c && c.isEmpty()
			|| value instanceof String s && s.isBlank();
	}

	/**
	 * A part of an item as the rule editor's tables show it: a trigger, an action, a reaction by its
	 * line; a parameter, timer or state by its kind and id.
	 */
	static String describe(TLObject part) {
		String type = part.tType().getName();
		String id = identifier(part);
		if (id != null) {
			return type + " " + id;
		}
		try {
			return RuleFunctions.summary(part);
		} catch (RuntimeException ex) {
			return type;
		}
	}

	/** An item as the answers name it: <code>rule frost</code>. */
	static String itemName(TLObject item) {
		String type = item.tType().getName();
		String kind = type.equals("Rule") ? "rule" : ApplicationDatapoints.isApplicationDatapoint(item) ? "datapoint"
			: "schedule entry";
		return kind + " " + identifier(item);
	}

	// ---- helpers -----------------------------------------------------------------------------

	/** The id of a rule, a part of one, a schedule entry or an application datapoint. */
	static String identifier(TLObject obj) {
		if (obj.tType().getPart("identifier") == null) {
			return null;
		}
		Object id = obj.tValueByName("identifier");
		return id == null || id.toString().isBlank() ? null : id.toString().trim();
	}

	private static boolean isComposition(TLStructuredTypePart part) {
		return part.getModelKind() == ModelKind.REFERENCE && ((TLReference) part).isComposite();
	}

	static List<TLObject> objects(Object value) {
		List<TLObject> result = new ArrayList<>();
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				if (element instanceof TLObject obj && obj.tValid()) {
					result.add(obj);
				}
			}
		} else if (value instanceof TLObject obj && obj.tValid()) {
			result.add(obj);
		}
		return result;
	}

	/** The definitions as the JSON text of the answer. */
	static String text(Object json) {
		return Json.write(json);
	}

}
