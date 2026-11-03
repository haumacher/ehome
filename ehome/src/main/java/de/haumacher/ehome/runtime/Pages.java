/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.Schedule;

/**
 * The control pages of a project (haumacher/ehome #72, model <code>ehome.runtime:Page</code>): what
 * the view <code>projects/pages.view.xml</code> does with them and what the export
 * ({@link PageExport}) and the checks at the fields need to know of their targets.
 *
 * <p>
 * The structure is a tree: the project holds its pages, a page its sections, a section its elements
 * (a room, a function, a datapoint, the enable switch of a rule, a chart), a chart its series and
 * reference lines. {@link #insert(TLObject, TLObject, String, Collection)} adds at the place the
 * selection names, {@link #move(Collection, TLObject, TLObject)} moves within and between the
 * containers of a kind. The references of the elements, series and reference lines delete them with
 * their target (<code>deletion-policy="delete-referer"</code> in the model), so an exported image
 * never names a deleted object.
 * </p>
 *
 * <p>
 * {@link #chartUses(TLObject)} collects what the charts ask of each datapoint, from which
 * {@link HistoryConfig} derives the history of the datapoint (haumacher/ehome #73); a series needs a
 * datapoint that can have one ({@link #hasHistory(TLObject)}).
 * </p>
 */
public final class Pages {

	/** The kind of a new page. */
	public static final String PAGE = "page";

	/** The kind of a new section. */
	public static final String SECTION = "section";

	/** The kind of a new room element (targets: building parts). */
	public static final String ROOM = "room";

	/** The kind of a new function element (targets: functions). */
	public static final String FUNCTION = "function";

	/** The kind of a new datapoint element (targets: datapoints). */
	public static final String DATAPOINT = "datapoint";

	/** The kind of a new element of a rule's enable switch (targets: rules). */
	public static final String RULE = "rule";

	/** The kind of a new chart. */
	public static final String CHART = "chart";

	/** The kind of a new series of a chart (targets: datapoints). */
	public static final String SERIES = "series";

	/** The kind of a new constant reference line of a chart. */
	public static final String REFERENCE = "reference";

	/** The kind of a new reference line at the value of a datapoint (targets: datapoints). */
	public static final String REFERENCE_DATAPOINT = "referenceDatapoint";

	/** The external names of the chart ranges of the image by the classifiers of ChartRange. */
	private static final Map<String, String> RANGES = Map.of("hour", "1h", "day", "24h", "week", "7d", "month", "30d",
		"year", "1y");

	/** The length in seconds of the chart ranges by the classifiers of ChartRange. */
	private static final Map<String, Long> RANGE_SECONDS = Map.of("hour", Long.valueOf(3600), "day", Long.valueOf(86400),
		"week", Long.valueOf(7 * 86400), "month", Long.valueOf(30 * 86400), "year", Long.valueOf(365 * 86400));

	/** The classifiers of ChartRange in their order. */
	static final List<String> RANGE_ORDER = List.of("hour", "day", "week", "month", "year");

	private Pages() {
		// Static helpers.
	}

	// ---- types -------------------------------------------------------------------------------

	/**
	 * Whether the object is of the given type of <code>ehome.runtime</code> (or a specialization).
	 */
	static boolean is(Object obj, String type) {
		return obj instanceof TLObject object && object.tValid() && ExtIds.isA(object, "ehome.runtime:" + type);
	}

	/**
	 * Whether the object is a parameter of a rule.
	 */
	public static boolean isRuleParameter(Object obj) {
		return is(obj, "RuleParameter");
	}

	/**
	 * Whether the object is a schedule entry that can have an adjustable time (weekly, yearly, dates):
	 * then it is the datapoint <code>schedule/&lt;id&gt;/time</code> of the image (homecontrol #58).
	 */
	public static boolean isTimeSchedule(Object obj) {
		return is(obj, "WeeklySchedule") || is(obj, "YearlySchedule") || is(obj, "DatesSchedule");
	}

	private static boolean isGroupAddress(Object obj) {
		return obj instanceof TLObject object && ExtIds.isA(object, "ehome.logical:GroupAddress");
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType("ehome.runtime:" + type));
	}

	private static List<TLObject> list(TLObject owner, String reference) {
		return RuleExport.objects(owner.tValueByName(reference));
	}

	/**
	 * The name of the classifier of an enumeration value, <code>null</code> for none.
	 */
	static String name(Object classifier) {
		return classifier instanceof TLClassifier value ? value.getName() : null;
	}

	// ---- targets -----------------------------------------------------------------------------

	/**
	 * The objects the given kind of element can refer to in the project of the given object:
	 * <code>function</code> the functions, <code>rule</code> the rules, <code>room</code> the parts of
	 * the buildings, <code>datapoint</code> the datapoints ({@link #datapoints(TLObject)}).
	 */
	public static List<TLObject> options(TLObject obj, String kind) {
		TLObject project = RuntimeFunctions.project(obj);
		if (project == null) {
			return List.of();
		}
		switch (kind) {
			case FUNCTION:
				return FunctionProposal.functions(project);
			case RULE:
				return list(project, "rules");
			case ROOM:
				return RuntimeFunctions.buildingParts(project);
			case DATAPOINT:
			case SERIES:
			case REFERENCE_DATAPOINT:
				return datapoints(project);
			default:
				return List.of();
		}
	}

	/**
	 * The datapoints of the project a page can show: its group addresses by their address, its
	 * application datapoints, then the parameters of its rules (spec/rules.md 8.6), rule by rule, then
	 * its schedule entries with an adjustable time (spec/schedules.md 2.2).
	 */
	public static List<TLObject> datapoints(TLObject project) {
		List<TLObject> result = RuntimeFunctions.datapoints(project);
		for (TLObject rule : list(project, "rules")) {
			result.addAll(list(rule, "parameters"));
		}
		for (TLObject entry : list(project, "schedules")) {
			if (isTimeSchedule(entry) && Boolean.TRUE.equals(entry.tValueByName("timeAdjustable"))) {
				result.add(entry);
			}
		}
		return result;
	}

	/**
	 * The functions a room element shows: those in the part of the building, then those in its
	 * sub-parts, depth-first in the order of the building structure (haumacher/ehome #71); in each part
	 * in the order of the project's functions.
	 */
	public static List<TLObject> roomFunctions(TLObject room) {
		TLObject project = RuntimeFunctions.project(room);
		if (project == null) {
			return List.of();
		}
		List<TLObject> parts = new ArrayList<>();
		parts.add(room);
		collectParts(room, parts);
		List<TLObject> functions = FunctionProposal.functions(project);
		List<TLObject> result = new ArrayList<>();
		for (TLObject part : parts) {
			for (TLObject function : functions) {
				if (function.tValueByName("room") == part) {
					result.add(function);
				}
			}
		}
		return result;
	}

	private static void collectParts(TLObject part, List<TLObject> result) {
		for (TLObject child : list(part, "parts")) {
			result.add(child);
			collectParts(child, result);
		}
	}

	/**
	 * The id the target has in the image as the project defines it: the address in three levels for a
	 * group address, the id of an application datapoint, <code>rule/&lt;rule&gt;/&lt;parameter&gt;</code>
	 * for a rule parameter, <code>rule/&lt;rule&gt;/enabled</code> for a rule,
	 * <code>schedule/&lt;id&gt;/time</code> for a weekly, yearly or dates entry, the identifier of a
	 * function; <code>null</code> for none.
	 */
	public static String imageId(TLObject target) {
		if (target == null || !target.tValid()) {
			return null;
		}
		if (isGroupAddress(target)) {
			return target.tValueByName("address") instanceof GA ga ? RuntimeImage.threeLevel(ga.getValue()) : null;
		}
		if (ApplicationDatapoints.isApplicationDatapoint(target)) {
			return ApplicationDatapoints.id(target);
		}
		if (isRuleParameter(target)) {
			String rule = text(target.tContainer().tValueByName("identifier"));
			String parameter = text(target.tValueByName("identifier"));
			return rule == null || parameter == null ? null : RuleLoader.parameterDatapointName(rule, parameter);
		}
		if (is(target, "Rule")) {
			String rule = text(target.tValueByName("identifier"));
			return rule == null ? null : RuleLoader.parameterDatapointName(rule, "enabled");
		}
		if (isTimeSchedule(target)) {
			String entry = text(target.tValueByName("identifier"));
			return entry == null ? null : Schedule.timeDatapointName(entry);
		}
		if (is(target, "Function")) {
			return text(target.tValueByName("identifier"));
		}
		return null;
	}

	/**
	 * The target for the user: a function or rule by its name, a group address by its address and
	 * name, a datapoint by its id, a part of a building by its path.
	 */
	public static String targetLabel(TLObject target) {
		if (target == null || !target.tValid()) {
			return "";
		}
		if (isGroupAddress(target)) {
			Object address = target.tValueByName("address");
			String name = text(target.tValueByName("name"));
			return address == null ? String.valueOf(name) : name == null ? address.toString() : address + " " + name;
		}
		if (ApplicationDatapoints.isApplicationDatapoint(target) || isRuleParameter(target) || isTimeSchedule(target)) {
			return String.valueOf(imageId(target));
		}
		if (is(target, "Function") || is(target, "Rule")) {
			return String.valueOf(text(target.tValueByName("name")));
		}
		if (ExtIds.isA(target, "ehome.building:BuildingDefinition")) {
			return BuildingDevices.path(target);
		}
		return String.valueOf(target);
	}

	/**
	 * The value type of the datapoint in the image (<code>boolean</code>, <code>real</code>, ...);
	 * <code>null</code> where it has none.
	 */
	public static String valueType(TLObject datapoint) {
		if (datapoint == null || !datapoint.tValid()) {
			return null;
		}
		if (ApplicationDatapoints.isApplicationDatapoint(datapoint)) {
			return ApplicationDatapoints.valueType(datapoint);
		}
		if (isRuleParameter(datapoint)) {
			String type = name(datapoint.tValueByName("type"));
			if (type == null) {
				return null;
			}
			switch (type) {
				case "boolean":
					return ValueTypes.BOOLEAN;
				case "integer":
					return ValueTypes.SIGNED;
				case "string":
					return ValueTypes.STRING;
				case "timeOfDay":
					return ValueTypes.TIME_OF_DAY;
				case "enumeration":
					return ValueTypes.ENUMERATION;
				case "color":
					return ValueTypes.COLOR;
				default:
					return ValueTypes.REAL;
			}
		}
		if (isTimeSchedule(datapoint)) {
			return ValueTypes.TIME_OF_DAY;
		}
		if (isGroupAddress(datapoint)) {
			Dpt dpt = GroupAddressTypes.effective(datapoint);
			return dpt == null ? null : ValueTypes.valueType(dpt.main(), dpt.isMainType() ? 0 : dpt.sub());
		}
		return null;
	}

	/**
	 * Whether the controller keeps a history of the datapoint when a chart draws it (haumacher/ehome
	 * #73): a datapoint of a number or a truth value whose history is derived from the charts, or one
	 * set by hand with its raw ring (a rule parameter has none).
	 */
	public static boolean hasHistory(TLObject datapoint) {
		if (!HistoryConfig.canHaveHistory(datapoint)) {
			return false;
		}
		return !HistoryConfig.isManual(datapoint) || HistoryConfig.manual(datapoint) != null;
	}

	/**
	 * A use of a datapoint by a series of a chart: the ranges the chart offers (its default range and
	 * its presets) in seconds, ascending, and its width (<code>1</code> to <code>4</code> columns, or
	 * <code>full</code>).
	 */
	public record ChartUse(TLObject chart, TLObject series, List<Long> ranges, String width) {
		// A record.
	}

	/**
	 * What the charts of the project ask of the datapoints of their series, by datapoint in the order
	 * of the pages, from which {@link HistoryConfig#derived(TLObject)} derives their histories
	 * (haumacher/ehome #73).
	 */
	public static Map<TLObject, List<ChartUse>> chartUses(TLObject project) {
		Map<TLObject, List<ChartUse>> result = new LinkedHashMap<>();
		for (TLObject page : list(project, "pages")) {
			for (TLObject section : list(page, "sections")) {
				for (TLObject element : list(section, "elements")) {
					if (!is(element, "ChartElement")) {
						continue;
					}
					List<Long> ranges = new ArrayList<>();
					for (String range : chartRanges(element)) {
						ranges.add(RANGE_SECONDS.get(range));
					}
					Object width = width(element);
					for (TLObject series : list(element, "series")) {
						if (series.tValueByName("datapoint") instanceof TLObject datapoint) {
							result.computeIfAbsent(datapoint, x -> new ArrayList<>())
								.add(new ChartUse(element, series, ranges, String.valueOf(width)));
						}
					}
				}
			}
		}
		return result;
	}

	/**
	 * The ranges a chart offers: its default range and its presets, in the order of ChartRange.
	 */
	static List<String> chartRanges(TLObject chart) {
		Set<String> used = new HashSet<>();
		String range = name(chart.tValueByName("range"));
		if (range != null) {
			used.add(range);
		}
		used.addAll(presets(chart));
		List<String> result = new ArrayList<>();
		for (String name : RANGE_ORDER) {
			if (used.contains(name)) {
				result.add(name);
			}
		}
		return result;
	}

	/**
	 * The presets of a chart (classifiers of ChartRange), in the order of ChartRange.
	 */
	static List<String> presets(TLObject chart) {
		Set<String> set = new HashSet<>();
		if (chart.tValueByName("presets") instanceof Collection<?> presets) {
			for (Object preset : presets) {
				String name = name(preset);
				if (name != null) {
					set.add(name);
				}
			}
		}
		List<String> result = new ArrayList<>();
		for (String name : RANGE_ORDER) {
			if (set.contains(name)) {
				result.add(name);
			}
		}
		return result;
	}

	/**
	 * The name of a range in the image (<code>24h</code>), <code>null</code> for none.
	 */
	static String rangeName(String classifier) {
		return classifier == null ? null : RANGES.get(classifier);
	}

	/**
	 * The width of a chart in the image: a number of columns or <code>full</code>.
	 */
	static Object width(TLObject chart) {
		String width = name(chart.tValueByName("width"));
		if (width == null) {
			width = "two";
		}
		switch (width) {
			case "one":
				return Long.valueOf(1);
			case "three":
				return Long.valueOf(3);
			case "four":
				return Long.valueOf(4);
			case "full":
				return "full";
			default:
				return Long.valueOf(2);
		}
	}

	/**
	 * The height of a chart in the image: 2 or 4 tile rows.
	 */
	static Long height(TLObject chart) {
		return Long.valueOf("four".equals(name(chart.tValueByName("height"))) ? 4 : 2);
	}

	// ---- checks at the fields ----------------------------------------------------------------

	/**
	 * Why the text cannot be the identifier of the page: not an identifier of the image, or the one of
	 * another page of the project; <code>null</code> for an empty text or one that can.
	 */
	public static ResKey identifierProblem(Object value, Object page) {
		if (value == null || value.toString().isBlank()) {
			return null;
		}
		String text = value.toString();
		ResKey problem = RuntimeFunctions.identifierProblem(text);
		if (problem != null) {
			return problem;
		}
		TLObject project = page instanceof TLObject obj ? RuntimeFunctions.project(edited(obj)) : null;
		if (project == null) {
			return null;
		}
		for (TLObject other : list(project, "pages")) {
			if (other != edited((TLObject) page) && text.equals(text(other.tValueByName("identifier")))) {
				return I18NConstants.PAGE_IDENTIFIER_DUPLICATE__ID.fill(text);
			}
		}
		return null;
	}

	private static TLObject edited(TLObject obj) {
		if (obj instanceof com.top_logic.model.TLFormObjectBase form && form.getEditedObject() != null) {
			return form.getEditedObject();
		}
		return obj;
	}

	/**
	 * Why the datapoint cannot be drawn by a series: it has no history; <code>null</code> if it can.
	 */
	public static ResKey seriesProblem(Object datapoint) {
		if (!(datapoint instanceof TLObject target) || !target.tValid()) {
			return null;
		}
		return hasHistory(target) ? null : I18NConstants.SERIES_NO_HISTORY__DATAPOINT.fill(targetLabel(target));
	}

	/**
	 * Why the consolidation does not fit the series: <code>consumption</code> on a truth value;
	 * <code>null</code> if it fits.
	 */
	public static ResKey consolidationProblem(Object consolidation, Object series) {
		if (!"consumption".equals(name(consolidation)) || !(series instanceof TLObject obj)
			|| !(obj.tValueByName("datapoint") instanceof TLObject datapoint)) {
			return null;
		}
		return ValueTypes.BOOLEAN.equals(valueType(datapoint)) ? I18NConstants.CONSUMPTION_OF_BOOLEAN : null;
	}

	/**
	 * Why the fixed range of the given axis (<code>left</code>, <code>right</code>) of the chart is
	 * wrong: its lowest value is not below its highest; <code>null</code> if it is right.
	 */
	public static ResKey axisProblem(Object chart, String axis) {
		if (!(chart instanceof TLObject obj)) {
			return null;
		}
		if (obj.tValueByName(axis + "Min") instanceof Number min && obj.tValueByName(axis + "Max") instanceof Number max
			&& min.doubleValue() >= max.doubleValue()) {
			return I18NConstants.AXIS_MIN_NOT_BELOW_MAX;
		}
		return null;
	}

	/**
	 * Why the reference line is wrong: it has neither or both of a value and a datapoint;
	 * <code>null</code> if it has one of them.
	 */
	public static ResKey referenceProblem(Object reference) {
		if (!(reference instanceof TLObject obj)) {
			return null;
		}
		boolean value = obj.tValueByName("value") != null;
		boolean datapoint = obj.tValueByName("datapoint") != null;
		return value == datapoint ? I18NConstants.REFERENCE_VALUE_OR_DATAPOINT : null;
	}

	/**
	 * What is wrong with a node of the pages for the image, for the tree; <code>null</code> for
	 * nothing. The image's check refuses the same (or the export leaves the element out with a note).
	 */
	public static ResKey problem(Object node) {
		if (is(node, "ChartSeries")) {
			TLObject series = (TLObject) node;
			ResKey problem = seriesProblem(series.tValueByName("datapoint"));
			return problem != null ? problem : consolidationProblem(series.tValueByName("consolidation"), series);
		}
		if (is(node, "ChartReference")) {
			return referenceProblem(node);
		}
		if (is(node, "ChartElement")) {
			TLObject chart = (TLObject) node;
			if (list(chart, "series").isEmpty()) {
				return I18NConstants.CHART_WITHOUT_SERIES;
			}
			ResKey problem = axisProblem(chart, "left");
			return problem != null ? problem : axisProblem(chart, "right");
		}
		if (is(node, "RoomElement") && ((TLObject) node).tValueByName("room") instanceof TLObject room
			&& roomFunctions(room).isEmpty()) {
			return I18NConstants.ROOM_WITHOUT_FUNCTIONS;
		}
		if (is(node, "DatapointElement") && ((TLObject) node).tValueByName("datapoint") instanceof TLObject datapoint
			&& isGroupAddress(datapoint) && valueType(datapoint) == null) {
			return I18NConstants.ADDRESS_WITHOUT_TYPE;
		}
		return null;
	}

	// ---- labels ------------------------------------------------------------------------------

	/**
	 * The label of a node of the pages in the tree: a page by its name, a section by its title (or its
	 * number), an element, series or reference line by its label, else by its target.
	 */
	public static String label(Object node) {
		Resources resources = Resources.getInstance();
		if (is(node, "Page")) {
			return String.valueOf(text(((TLObject) node).tValueByName("name")));
		}
		if (is(node, "PageSection")) {
			TLObject section = (TLObject) node;
			String title = text(section.tValueByName("title"));
			return title != null ? title
				: resources.getString(I18NConstants.SECTION_NUMBER__N.fill(
					Integer.valueOf(list(section.tContainer(), "sections").indexOf(section) + 1)));
		}
		if (!(node instanceof TLObject obj) || !obj.tValid()) {
			return "";
		}
		String label = text(obj.tValueByName("label"));
		if (label != null) {
			return label;
		}
		if (is(obj, "ChartElement")) {
			return resources.getString(I18NConstants.CHART_LABEL);
		}
		if (is(obj, "ChartReference") && obj.tValueByName("value") instanceof Number value) {
			return resources.getString(I18NConstants.REFERENCE_AT__VALUE.fill(value));
		}
		TLObject target = target(obj);
		return target == null ? "" : targetLabel(target);
	}

	/**
	 * The object an element, series or reference line refers to; <code>null</code> for none.
	 */
	public static TLObject target(TLObject node) {
		for (String reference : List.of("room", "function", "datapoint", "rule")) {
			if (node.tType().getPart(reference) != null && node.tValueByName(reference) instanceof TLObject target) {
				return target;
			}
		}
		return null;
	}

	/**
	 * The children of a node of the pages tree: the pages of a project, the sections of a page, the
	 * elements of a section, the series and reference lines of a chart.
	 */
	public static List<TLObject> children(Object node) {
		if (!(node instanceof TLObject obj) || !obj.tValid()) {
			return List.of();
		}
		if (ExtIds.isA(obj, "ehome.project:Project")) {
			return list(obj, "pages");
		}
		if (is(obj, "Page")) {
			return list(obj, "sections");
		}
		if (is(obj, "PageSection")) {
			return list(obj, "elements");
		}
		if (is(obj, "ChartElement")) {
			List<TLObject> result = list(obj, "series");
			result.addAll(list(obj, "references"));
			return result;
		}
		return List.of();
	}

	// ---- inserting ---------------------------------------------------------------------------

	/** Where a new object goes: the reference of the owner and the index in it. */
	private record Place(TLObject owner, String reference, int index) {
		// A record.
	}

	private static TLObject ancestor(TLObject node, String type) {
		for (TLObject obj = node; obj != null; obj = obj.tContainer()) {
			if (is(obj, type)) {
				return obj;
			}
		}
		return null;
	}

	/**
	 * The index after the child of the owner's reference that is the given node or holds it; the end
	 * of the list without one.
	 */
	private static int after(TLObject owner, String reference, TLObject node) {
		List<TLObject> children = list(owner, reference);
		for (TLObject obj = node; obj != null; obj = obj.tContainer()) {
			int index = children.indexOf(obj);
			if (index >= 0) {
				return index + 1;
			}
		}
		return children.size();
	}

	private static Object place(TLObject project, TLObject selected, String kind) {
		if (project == null) {
			return I18NConstants.NO_PROJECT;
		}
		TLObject node = selected != null && selected.tValid() && RuntimeFunctions.project(selected) == project ? selected
			: null;
		switch (kind) {
			case PAGE:
				return new Place(project, "pages", after(project, "pages", node));
			case SECTION: {
				TLObject page = ancestor(node, "Page");
				if (page == null) {
					return I18NConstants.SELECT_PAGE;
				}
				return new Place(page, "sections", after(page, "sections", node));
			}
			case SERIES:
			case REFERENCE:
			case REFERENCE_DATAPOINT: {
				TLObject chart = ancestor(node, "ChartElement");
				if (chart == null) {
					return I18NConstants.SELECT_CHART;
				}
				String reference = SERIES.equals(kind) ? "series" : "references";
				return new Place(chart, reference, after(chart, reference, node));
			}
			case ROOM:
			case FUNCTION:
			case DATAPOINT:
			case RULE:
			case CHART: {
				TLObject section = ancestor(node, "PageSection");
				if (section == null && ancestor(node, "Page") != null) {
					List<TLObject> sections = list(ancestor(node, "Page"), "sections");
					section = sections.isEmpty() ? null : sections.get(sections.size() - 1);
				}
				if (section == null) {
					return I18NConstants.SELECT_SECTION;
				}
				return new Place(section, "elements", after(section, "elements", node));
			}
			default:
				return I18NConstants.NO_PROJECT;
		}
	}

	/**
	 * Why nothing of the given kind can be added at the place the selection names (see
	 * {@link #insert(TLObject, TLObject, String, Collection)}); <code>null</code> if it can.
	 */
	public static ResKey insertVeto(TLObject project, TLObject selected, String kind) {
		Object place = place(project, selected, kind);
		return place instanceof ResKey veto ? veto : null;
	}

	/**
	 * Adds objects of the given kind ({@link #PAGE}, ...) to the pages of the project, after the
	 * selected node of their level (or the one holding the selection), at the end of their container
	 * otherwise: a page to the project, a section to the selected page, an element to the selected
	 * section (with a page selected, to its last section), a series or reference line to the selected
	 * chart. A kind with targets adds one object per target, in their order; the caller provides the
	 * transaction.
	 *
	 * @return The last object added; <code>null</code> for none.
	 */
	public static TLObject insert(TLObject project, TLObject selected, String kind, Collection<?> targets) {
		Object found = place(project, selected, kind);
		if (found instanceof ResKey veto) {
			throw new TopLogicException(veto);
		}
		Place place = (Place) found;
		List<TLObject> created = new ArrayList<>();
		switch (kind) {
			case PAGE: {
				TLObject page = create("Page");
				page.tUpdateByName("name", FunctionProposal.text(I18NConstants.NEW_PAGE_NAME));
				created.add(page);
				break;
			}
			case SECTION:
				created.add(create("PageSection"));
				break;
			case CHART: {
				TLObject chart = create("ChartElement");
				chart.tUpdateByName("width", RuleImport.classifier("ChartWidth", "two"));
				chart.tUpdateByName("height", RuleImport.classifier("ChartHeight", "two"));
				chart.tUpdateByName("range", RuleImport.classifier("ChartRange", "day"));
				created.add(chart);
				break;
			}
			case REFERENCE: {
				TLObject reference = create("ChartReference");
				reference.tUpdateByName("value", Double.valueOf(0));
				created.add(reference);
				break;
			}
			default: {
				String type = switch (kind) {
					case ROOM -> "RoomElement";
					case FUNCTION -> "FunctionElement";
					case DATAPOINT -> "DatapointElement";
					case RULE -> "RuleEnabledElement";
					case SERIES -> "ChartSeries";
					default -> "ChartReference";
				};
				String reference = switch (kind) {
					case ROOM -> "room";
					case RULE -> "rule";
					case FUNCTION -> "function";
					default -> "datapoint";
				};
				if (targets != null) {
					for (Object target : targets) {
						if (target instanceof TLObject obj && obj.tValid()) {
							TLObject element = create(type);
							element.tUpdateByName(reference, obj);
							created.add(element);
						}
					}
				}
				break;
			}
		}
		if (created.isEmpty()) {
			return null;
		}
		List<TLObject> children = list(place.owner(), place.reference());
		children.addAll(Math.min(place.index(), children.size()), created);
		place.owner().tUpdateByName(place.reference(), children);
		return created.get(created.size() - 1);
	}

	// ---- moving ------------------------------------------------------------------------------

	/**
	 * The reference of the parent that holds objects of the type of the given object; <code>null</code>
	 * for a parent that holds none.
	 */
	private static String holding(TLObject parent, TLObject obj) {
		if (parent == null || obj == null) {
			return null;
		}
		if (is(obj, "Page")) {
			return ExtIds.isA(parent, "ehome.project:Project") ? "pages" : null;
		}
		if (is(obj, "PageSection")) {
			return is(parent, "Page") ? "sections" : null;
		}
		if (is(obj, "PageElement")) {
			return is(parent, "PageSection") ? "elements" : null;
		}
		if (is(obj, "ChartSeries")) {
			return is(parent, "ChartElement") ? "series" : null;
		}
		if (is(obj, "ChartReference")) {
			return is(parent, "ChartElement") ? "references" : null;
		}
		return null;
	}

	/**
	 * Why the objects cannot be moved before the given child of the parent (at its end without one):
	 * the parent cannot hold objects of their kind, they are of different kinds, the parent is in
	 * another project, or the place is one of them; <code>null</code> if they can.
	 */
	public static ResKey moveVeto(Collection<?> objects, TLObject parent, TLObject before) {
		if (objects == null || objects.isEmpty() || parent == null) {
			return I18NConstants.MOVE_NOT_HERE;
		}
		String reference = null;
		TLObject project = RuntimeFunctions.project(parent);
		for (Object element : objects) {
			if (!(element instanceof TLObject obj) || !obj.tValid()) {
				return I18NConstants.MOVE_NOT_HERE;
			}
			String holding = holding(parent, obj);
			if (holding == null || (reference != null && !reference.equals(holding))) {
				return I18NConstants.MOVE_NOT_HERE;
			}
			reference = holding;
			if (RuntimeFunctions.project(obj) != project) {
				return I18NConstants.MOVE_OTHER_PROJECT;
			}
		}
		if (before != null && objects.contains(before)) {
			return I18NConstants.MOVE_NOT_HERE;
		}
		return null;
	}

	/**
	 * Moves the objects before the given child of the parent, at its end without one (or where the
	 * child is not of their kind, e.g. a reference line where series are moved), keeping their order
	 * in the tree; the caller provides the transaction.
	 */
	public static void move(Collection<?> objects, TLObject parent, TLObject before) {
		ResKey veto = moveVeto(objects, parent, before);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		List<TLObject> moved = new ArrayList<>();
		for (TLObject node : treeOrder(RuntimeFunctions.project(parent))) {
			if (objects.contains(node)) {
				moved.add(node);
			}
		}
		String reference = holding(parent, moved.get(0));
		List<TLObject> elements = list(parent, reference);
		int index = before == null ? -1 : elements.indexOf(before);
		if (index < 0) {
			index = elements.size();
		}
		int target = 0;
		for (int n = 0; n < index; n++) {
			if (!moved.contains(elements.get(n))) {
				target++;
			}
		}
		// Out of the old containers first: a part has one container at a time.
		for (TLObject node : moved) {
			TLObject oldContainer = node.tContainer();
			if (oldContainer != parent) {
				String oldReference = holding(oldContainer, node);
				List<TLObject> siblings = list(oldContainer, oldReference);
				siblings.remove(node);
				oldContainer.tUpdateByName(oldReference, siblings);
			}
		}
		elements.removeAll(moved);
		elements.addAll(target, moved);
		parent.tUpdateByName(reference, elements);
	}

	private static List<TLObject> treeOrder(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		if (project != null) {
			addTree(project, result);
		}
		return result;
	}

	private static void addTree(TLObject node, List<TLObject> result) {
		for (TLObject child : children(node)) {
			result.add(child);
			addTree(child, result);
		}
	}

	// ---- texts -------------------------------------------------------------------------------

	/**
	 * A text in the user's language, <code>null</code> for an empty one.
	 */
	static String text(Object value) {
		if (value instanceof ResKey key) {
			return text(Resources.getInstance().getString(key, null));
		}
		return value == null || value.toString().isBlank() ? null : value.toString().trim();
	}

}
