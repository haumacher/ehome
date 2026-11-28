/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.top_logic.element.model.copy.CopyOperation;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.impl.TransientObjectFactory;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.runtime.ChartElement;
import de.haumacher.ehome.model.runtime.ChartReference;
import de.haumacher.ehome.model.runtime.RuleTimeline;

/**
 * The create dialogs of the rules, schedule entries, application datapoints and control pages
 * (haumacher/ehome #91): "New" opens a dialog over a transient draft ({@link #draft(TLObject, String, TLClass)},
 * {@link #pageDraft(TLObject, TLObject, String)}) with the defaults of its kind and a proposed unique
 * id, but no name or label (haumacher/ehome #205: a multilingual text is entered, never preset);
 * "Create" stores the draft as the dialog shows it at its place ({@link #add(TLObject)}), "Cancel"
 * leaves nothing.
 *
 * <p>
 * The draft is created in its owner (its {@link TLObject#tContainer() container} is the rule,
 * reaction, parameter, state or project it will be added to), so that the checks of its fields find
 * its project and rule; the reference it will be added to is remembered with it
 * ({@link #reference(TLObject)}), and the field checks put it there ({@link RuleExport}). The dialog
 * asks only for the fields that make the object meaningful ({@link #fields(TLObject)}); the others
 * keep their defaults and are edited in the detail form. A new timeline is a draft as a whole, with
 * its first day plan and the plan's assignment; its dialog asks for the plan.
 * </p>
 */
public final class RuleDrafts {

	/** The defining fields of each kind, in the order the dialog shows them. */
	private static final Map<String, List<String>> FIELDS = Map.ofEntries(
		Map.entry("WeeklySchedule", List.of("label", "identifier", "time", "days", "output")),
		Map.entry("YearlySchedule", List.of("label", "identifier", "month", "day", "time", "output")),
		Map.entry("DatesSchedule", List.of("label", "identifier", "dates", "time", "output")),
		Map.entry("AstroSchedule", List.of("label", "identifier", "sun", "offset", "days", "output")),
		Map.entry("IntervalSchedule", List.of("label", "identifier", "every", "from", "until", "days", "output")),
		Map.entry("OnceSchedule", List.of("label", "identifier", "at", "output")),
		Map.entry("Rule", List.of("name", "identifier")),
		Map.entry("RuleParameter", List.of("identifier", "title", "type", "default")),
		Map.entry("RuleChoice", List.of("value", "title")),
		Map.entry("RuleTimer", List.of("identifier", "duration")),
		Map.entry("RuleState", List.of("identifier", "label")),
		Map.entry("TimelinePlan", List.of("identifier", "label")),
		Map.entry("TimelinePoint", List.of("time", "sun", "offset", "state")),
		Map.entry("TimelineAssignment", List.of("plan", "days")),
		Map.entry("DatapointTrigger", List.of("datapoint")),
		Map.entry("SilenceTrigger", List.of("datapoint", "after")),
		Map.entry("ScheduleTrigger", List.of("schedule")),
		Map.entry("TimerTrigger", List.of("timer")),
		Map.entry("ParameterTrigger", List.of("parameter")),
		Map.entry("MatchTrigger", List.of("condition")),
		Map.entry("StartTrigger", List.of("onBoot", "onImage", "onEnable")),
		Map.entry("WriteAction", List.of("datapoint", "value")),
		Map.entry("ReadAction", List.of("datapoint")),
		Map.entry("StartTimerAction", List.of("timer", "duration")),
		Map.entry("RestartTimerAction", List.of("timer", "duration")),
		Map.entry("CancelTimerAction", List.of("timer")),
		Map.entry("SetParameterAction", List.of("parameter", "value")),
		Map.entry("LogAction", List.of("level", "message")),
		Map.entry(ApplicationDatapoints.TYPE, List.of("identifier", "label", "type")),
		Map.entry("Page", List.of("name")),
		Map.entry("PageSection", List.of("title")),
		Map.entry("ChartElement", List.of("label", "range")),
		Map.entry("ChartReference", List.of("value", "label")));

	/** The kinds of pages' nodes a dialog creates, with their types. */
	private static final Map<String, String> PAGE_TYPES = Map.of(Pages.PAGE, "Page", Pages.SECTION, "PageSection",
		Pages.CHART, "ChartElement", Pages.REFERENCE, "ChartReference");

	/** Where a draft goes: the reference of its owner, or the place in the pages. */
	private record Place(String reference, TLObject selected, String kind) {
		// Value only.
	}

	private static final Map<TLObject, Place> PLACES = new WeakHashMap<>();

	private RuleDrafts() {
		// Static only.
	}

	/**
	 * A draft of a part of the given type, to be added at the end of the given reference of the
	 * owner: created in the owner, with a unique id and the defaults of its kind
	 * ({@link RuleFunctions#initialize(TLObject, TLObject, TLClass, List)}), without a name or label.
	 * Of a timeline the result is the draft of its first day plan, created in the draft of the
	 * timeline with the plan's assignment. Nothing is stored.
	 */
	public static TLObject draft(TLObject owner, String reference, TLClass type) {
		TLObject result = TransientObjectFactory.INSTANCE.createObject(type, owner);
		RuleFunctions.initialize(result, owner, type, RuleExport.objects(owner.tValueByName(reference)));
		remember(result, new Place(reference, null, null));
		if (result.tType().getName().equals("RuleTimeline")) {
			TLObject plan = RuleExport.objects(((RuleTimeline) result).getPlans()).get(0);
			remember(plan, new Place("plans", null, null));
			return plan;
		}
		return result;
	}

	/**
	 * A draft of a node of the control pages ({@link Pages#PAGE}, {@link Pages#SECTION},
	 * {@link Pages#CHART}, {@link Pages#REFERENCE}), to be added where {@link Pages#insert(TLObject,
	 * TLObject, String, Collection)} adds it for the node selected now. Nothing is stored.
	 */
	public static TLObject pageDraft(TLObject project, TLObject selected, String kind) {
		String typeName = PAGE_TYPES.get(kind);
		if (typeName == null) {
			throw new IllegalArgumentException("No page draft of kind '" + kind + "'.");
		}
		TLClass type = (TLClass) TLModelUtil.findType("ehome.runtime:" + typeName);
		TLObject result = TransientObjectFactory.INSTANCE.createObject(type, project);
		switch (kind) {
			case Pages.CHART:
				((ChartElement) result).setRange(RuleImport.classifier("ChartRange", "day"));
				break;
			case Pages.REFERENCE:
				((ChartReference) result).setValue(Double.valueOf(0));
				break;
			default:
				break;
		}
		remember(result, new Place(null, selected, kind));
		return result;
	}

	private static void remember(TLObject draft, Place place) {
		synchronized (PLACES) {
			PLACES.put(draft, place);
		}
	}

	private static Place place(TLObject draft) {
		synchronized (PLACES) {
			return PLACES.get(draft);
		}
	}

	/**
	 * The reference of its owner the given draft will be added to, <code>null</code> for an object
	 * that is no draft of a rule part, schedule entry or application datapoint.
	 */
	public static String reference(TLObject draft) {
		Place place = place(draft);
		return place == null ? null : place.reference();
	}

	/**
	 * The attributes the create dialog of the given draft asks for.
	 */
	public static List<String> fields(TLObject draft) {
		return FIELDS.getOrDefault(draft.tType().getName(), List.of());
	}

	/**
	 * Whether the create dialog of the draft asks for the given attribute.
	 */
	public static boolean hasField(TLObject draft, String attribute) {
		return fields(draft).contains(attribute);
	}

	/**
	 * The stored object the draft is added to: its container, of the plan of a new timeline the rule
	 * (the container of the timeline's draft).
	 */
	public static TLObject owner(TLObject draft) {
		TLObject result = draft.tContainer();
		while (result != null && result.tTransient()) {
			result = result.tContainer();
		}
		return result;
	}

	/**
	 * The title of the create dialog: the name of the draft's type.
	 */
	public static String title(TLObject draft) {
		return MetaLabelProvider.INSTANCE.getLabel(draft.tType());
	}

	/**
	 * Stores the draft at its place, as the dialog shows it (haumacher/ehome #205): a part becomes
	 * persistent with the draft's values, at the end of its owner's reference (a timeline with its
	 * plan and assignment, the result then its plan); a node of the pages is added where the pages
	 * insert one for the node selected when the dialog opened and takes the values of the dialog's
	 * fields. A field left empty stays empty. The caller provides the transaction.
	 *
	 * @return The stored object.
	 */
	public static TLObject add(TLObject draft) {
		Place place = place(draft);
		if (place == null || !draft.tTransient()) {
			throw new IllegalArgumentException("No draft of a create dialog: " + draft);
		}
		TLObject created;
		if (place.kind() != null) {
			TLObject owner = draft.tContainer();
			created = Pages.insert(owner, place.selected() != null && place.selected().tValid() ? place.selected() : null,
				place.kind(), List.of());
			for (String attribute : fields(draft)) {
				TLStructuredTypePart part = draft.tType().getPart(attribute);
				if (part != null) {
					created.tUpdate(part, draft.tValue(part));
				}
			}
		} else {
			// The outermost draft at its place: of the plan of a new timeline the timeline's draft.
			TLObject root = draft;
			while (root.tContainer() != null && root.tContainer().tTransient()) {
				root = root.tContainer();
			}
			Place rootPlace = place(root);
			if (rootPlace == null || rootPlace.reference() == null) {
				throw new IllegalArgumentException("No draft of a create dialog: " + root);
			}
			Map<TLObject, TLObject> copies = persist(root, root.tContainer(), rootPlace.reference());
			if (root.tType().getName().equals("Rule")) {
				// Its state datapoint at once (haumacher/ehome #140), not only at the commit.
				StateDatapoints.ensure(copies.get(root));
			}
			created = copies.get(draft);
			forget(root);
		}
		forget(draft);
		return created;
	}

	/**
	 * Makes the transient draft persistent with its values and composition parts, as the engine's
	 * create dialogs do (<code>persist-transient</code>), and adds it to the given reference of the
	 * owner: at the end of a list, as the value of a single reference. The caller provides the
	 * transaction.
	 *
	 * @return The persistent copies by their drafts.
	 */
	public static Map<TLObject, TLObject> persist(TLObject draft, TLObject owner, String reference) {
		TLStructuredTypePart part = owner.tType().getPartOrFail(reference);
		CopyOperation operation = CopyOperation.initial();
		operation.setContext(owner, part instanceof TLReference ref ? ref : null);
		operation.setTransient(Boolean.FALSE);
		operation.skipTransactionDefaults(true);
		TLObject created = (TLObject) operation.copyReference(draft);
		operation.finish();
		if (part.isMultiple()) {
			owner.tAdd(part, created);
		} else {
			owner.tUpdate(part, created);
		}
		return operation.getCorrespondence();
	}

	private static void forget(TLObject draft) {
		synchronized (PLACES) {
			PLACES.remove(draft);
		}
	}

}
