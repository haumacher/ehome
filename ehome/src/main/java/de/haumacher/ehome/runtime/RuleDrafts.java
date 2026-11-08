/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.impl.TransientObjectFactory;
import com.top_logic.model.util.TLModelUtil;

/**
 * The create dialogs of the rules, schedule entries, application datapoints and control pages
 * (haumacher/ehome #91): "New" opens a dialog over a transient draft ({@link #draft(TLObject, String, TLClass)},
 * {@link #pageDraft(TLObject, TLObject, String)}) with the defaults of its kind and a proposed unique
 * id; "Create" stores it at its place ({@link #add(TLObject)}), "Cancel" leaves nothing.
 *
 * <p>
 * The draft is created in its owner (its {@link TLObject#tContainer() container} is the rule,
 * reaction, parameter, state or project it will be added to), so that the checks of its fields find
 * its project and rule; the reference it will be added to is remembered with it
 * ({@link #reference(TLObject)}), and the field checks put it there ({@link RuleExport}). The dialog
 * asks only for the fields that make the object meaningful ({@link #fields(TLObject)}); the others
 * keep their defaults and are edited in the detail form.
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
		Map.entry("RuleState", List.of("identifier")),
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
	 * ({@link RuleFunctions#initialize(TLObject, TLObject, TLClass, List)}). Nothing is stored.
	 */
	public static TLObject draft(TLObject owner, String reference, TLClass type) {
		TLObject result = TransientObjectFactory.INSTANCE.createObject(type, owner);
		RuleFunctions.initialize(result, owner, type, RuleExport.objects(owner.tValueByName(reference)));
		remember(result, new Place(reference, null, null));
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
			case Pages.PAGE:
				result.tUpdateByName("name", FunctionProposal.text(I18NConstants.NEW_PAGE_NAME));
				break;
			case Pages.CHART:
				result.tUpdateByName("range", RuleImport.classifier("ChartRange", "day"));
				break;
			case Pages.REFERENCE:
				result.tUpdateByName("value", Double.valueOf(0));
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
	 * The title of the create dialog: the name of the draft's type.
	 */
	public static String title(TLObject draft) {
		return MetaLabelProvider.INSTANCE.getLabel(draft.tType());
	}

	/**
	 * Stores the draft at its place: a part at the end of its owner's reference (with the order of
	 * a rule after the last one), a node of the pages where the pages insert one for the node
	 * selected when the dialog opened; the values of the dialog's fields are those entered, the
	 * others the defaults of the kind. The caller provides the transaction.
	 *
	 * @return The stored object.
	 */
	public static TLObject add(TLObject draft) {
		Place place = place(draft);
		if (place == null || !draft.tTransient()) {
			throw new IllegalArgumentException("No draft of a create dialog: " + draft);
		}
		TLObject owner = draft.tContainer();
		TLObject created;
		if (place.kind() != null) {
			created = Pages.insert(owner, place.selected() != null && place.selected().tValid() ? place.selected() : null,
				place.kind(), List.of());
		} else {
			created = RuleFunctions.create(owner, place.reference(), (TLClass) draft.tType());
		}
		for (String attribute : fields(draft)) {
			TLStructuredTypePart part = draft.tType().getPart(attribute);
			if (part == null) {
				continue;
			}
			Object value = draft.tValue(part);
			if (value != null && !(value instanceof Collection<?> collection && collection.isEmpty())) {
				created.tUpdate(part, value);
			}
		}
		synchronized (PLACES) {
			PLACES.remove(draft);
		}
		return created;
	}

}
