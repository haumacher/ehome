/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TLType;
import com.top_logic.model.util.TLModelI18N;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

/**
 * What else in a project refers to an object about to be deleted (haumacher/ehome #94): the
 * functions whose roles name a datapoint, the rules whose triggers and actions name a datapoint or a
 * schedule entry, the schedule entries whose output is a datapoint.
 *
 * <p>
 * The referers are found through the model, not through a list of the kinds that may refer to
 * something: every forward reference of the E-Home modules whose value type fits, read backwards
 * ({@link TLObject#tReferers(TLReference)}), for the object and for every part its deletion removes
 * with it (an address of a deleted range). A new kind of reference is covered without a change here.
 * A referer that is deleted as well is left out. Each referer is reported as the part of the project
 * it belongs to (a trigger of a rule as the rule), with the references it refers to the object in.
 * </p>
 */
public final class Referers {

	private static final String MODULE_PREFIX = "ehome.";

	private Referers() {
		// Static only.
	}

	/**
	 * The parts of the project referring to the object or a part deleted with it, each with the
	 * labels of the references it does so in, ordered by kind and label.
	 *
	 * @param obj
	 *        The object about to be deleted.
	 */
	public static Map<TLObject, Set<String>> referers(TLObject obj) {
		Set<TLObject> deleted = withParts(obj);
		TLObject project = obj;
		while (project.tContainer() != null) {
			project = project.tContainer();
		}
		return referers(project, deleted, deleted, referer -> false);
	}

	/**
	 * The parts of the project referring to one of the given objects, each with the labels of the
	 * references it does so in, ordered by kind and label (haumacher/ehome #125: the objects an update
	 * from ETS removes).
	 *
	 * @param project
	 *        The project the objects belong to: a referer is reported as the part of it it belongs to.
	 * @param targets
	 *        The objects referred to (with their parts, see {@link #withParts(TLObject)}).
	 * @param deleted
	 *        Everything deleted together with them: a referer among them is left out.
	 * @param ignored
	 *        Which further referers are left out.
	 */
	public static Map<TLObject, Set<String>> referers(TLObject project, Collection<TLObject> targets,
			Set<TLObject> deleted, Predicate<TLObject> ignored) {
		Resources resources = Resources.getInstance();
		Map<TLStructuredType, List<TLReference>> referencesByType = new HashMap<>();
		Map<TLObject, Set<String>> result = new LinkedHashMap<>();
		for (TLObject target : targets) {
			List<TLReference> references =
				referencesByType.computeIfAbsent(target.tType(), Referers::referencesTo);
			for (TLReference reference : references) {
				for (TLObject referer : target.tReferers(reference)) {
					if (referer == null || !referer.tValid() || deleted.contains(referer) || ignored.test(referer)) {
						continue;
					}
					TLObject part = part(referer, project);
					if (deleted.contains(part)) {
						continue;
					}
					result.computeIfAbsent(part, x -> new LinkedHashSet<>())
						.add(resources.getString(TLModelI18N.getI18NKey(reference)));
				}
			}
		}

		List<TLObject> sorted = new ArrayList<>(result.keySet());
		sorted.sort(Comparator.comparing(Referers::typeLabel).thenComparing(Referers::label));
		Map<TLObject, Set<String>> ordered = new LinkedHashMap<>();
		for (TLObject part : sorted) {
			ordered.put(part, result.get(part));
		}
		return ordered;
	}

	/**
	 * One line per part of the project referring to the object: its kind, its label and the
	 * references, e.g. <code>Sensor „Flur Temperatur“: Wert</code>.
	 */
	public static List<String> lines(TLObject obj) {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<TLObject, Set<String>> entry : referers(obj).entrySet()) {
			lines.add(line(entry.getKey(), entry.getValue()));
		}
		return lines;
	}

	/**
	 * The question asked before the object is deleted: what refers to it, and that the next
	 * deployment refuses those until they refer to something else.
	 */
	public static String deleteConfirmation(TLObject obj) {
		Resources resources = Resources.getInstance();
		String kind = typeLabel(obj);
		String name = label(obj);
		List<String> lines = lines(obj);
		if (lines.isEmpty()) {
			return resources.getString(I18NConstants.CONFIRM_DELETE__KIND_NAME.fill(kind, name));
		}
		StringBuilder text = new StringBuilder(resources.getString(lines.size() == 1
			? I18NConstants.CONFIRM_DELETE_REFERRED_ONCE__KIND_NAME.fill(kind, name)
			: I18NConstants.CONFIRM_DELETE_REFERRED__KIND_NAME_COUNT.fill(kind, name, lines.size())));
		text.append('\n');
		for (String line : lines) {
			text.append("\n• ").append(line);
		}
		text.append("\n\n").append(resources.getString(I18NConstants.CONFIRM_DELETE_REFERRED_CONSEQUENCE));
		return text.toString();
	}

	/**
	 * The object and everything its deletion removes with it (the values of its composite
	 * references, recursively).
	 */
	public static Set<TLObject> withParts(TLObject obj) {
		Set<TLObject> result = new LinkedHashSet<>();
		addWithParts(result, obj);
		return result;
	}

	/**
	 * Adds the object and everything its deletion removes with it (the values of its composite
	 * references, recursively).
	 */
	private static void addWithParts(Set<TLObject> deleted, TLObject obj) {
		if (!deleted.add(obj)) {
			return;
		}
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (part instanceof TLReference reference && reference.isComposite() && !reference.isDerived()) {
				Object value = obj.tValue(reference);
				if (value instanceof TLObject child) {
					addWithParts(deleted, child);
				} else if (value instanceof Collection<?> children) {
					for (Object child : children) {
						if (child instanceof TLObject childObj) {
							addWithParts(deleted, childObj);
						}
					}
				}
			}
		}
	}

	/**
	 * The forward references of the E-Home modules an object of the given type can be the value of.
	 */
	private static List<TLReference> referencesTo(TLStructuredType type) {
		List<TLReference> result = new ArrayList<>();
		for (TLModule module : ModelService.getApplicationModel().getModules()) {
			if (!module.getName().startsWith(MODULE_PREFIX)) {
				continue;
			}
			for (TLClass owner : module.getClasses()) {
				for (TLStructuredTypePart part : owner.getLocalParts()) {
					if (part instanceof TLReference reference && !reference.isDerived() && !reference.isBackwards()
						&& !reference.isComposite() && isValueType(reference.getType(), type)) {
						result.add(reference);
					}
				}
			}
		}
		return result;
	}

	private static boolean isValueType(TLType valueType, TLStructuredType type) {
		return valueType instanceof TLStructuredType structured && TLModelUtil.isGeneralization(structured, type);
	}

	/**
	 * The part of the project the referer belongs to: its container just below the project (a
	 * trigger of a rule: the rule), within the KNX part the nearest container with an ID of ETS
	 * (a connection: the device), or the referer itself where it is not in the project.
	 */
	private static TLObject part(TLObject referer, TLObject project) {
		TLObject part = referer;
		while (part.tContainer() != null && part.tContainer() != project && !hasExtId(part)) {
			part = part.tContainer();
		}
		return part.tContainer() == project || hasExtId(part) ? part : referer;
	}

	private static boolean hasExtId(TLObject obj) {
		return obj.tType().getPart("extId") != null && obj.tValueByName("extId") != null;
	}

	/**
	 * The text naming a part of the project referring to an object, see {@link #referers(TLObject)}:
	 * its kind, its label and the references, e.g. <code>Sensor „Flur Temperatur“: Wert</code>.
	 */
	public static String line(TLObject part, Set<String> references) {
		return Resources.getInstance().getString(I18NConstants.REFERER__KIND_NAME_REFERENCES
			.fill(typeLabel(part), label(part), String.join(", ", references)));
	}

	private static String typeLabel(TLObject obj) {
		String label = MetaLabelProvider.INSTANCE.getLabel(obj.tType());
		return label == null ? "" : label;
	}

	private static String label(TLObject obj) {
		String label = MetaLabelProvider.INSTANCE.getLabel(obj);
		return label == null ? "" : label;
	}
}
