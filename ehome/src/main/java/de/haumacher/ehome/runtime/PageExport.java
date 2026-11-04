/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

/**
 * The member <code>ui.pages</code> of the runtime image (haumacher/ehome #72, homecontrol #54, schema
 * 1.1): the control pages of a project, written by {@link RuntimeImage}.
 *
 * <p>
 * A page is written with its id (its identifier, derived from its name where it has none), its label
 * (its name in its languages), its icon and its sections; a section with its label (its title) and
 * its elements. An element names the image id of its target:
 * </p>
 * <ul>
 * <li>a function element <code>{"function": id}</code>;</li>
 * <li>a room element one function element per function of the room ({@link Pages#roomFunctions(TLObject)}),
 * taken when the image is written, without a label;</li>
 * <li>a datapoint element <code>{"datapoint": id}</code>, the id of <code>datapoints[]</code> or
 * <code>rule/&lt;rule&gt;/&lt;parameter&gt;</code> for a rule parameter, with <code>readOnly</code>
 * where set;</li>
 * <li>the enable switch of a rule <code>{"datapoint": "rule/&lt;rule&gt;/enabled"}</code>;</li>
 * <li>a chart <code>{"chart": {...}}</code> with its label (title), width, height, default range,
 * presets, both axes (<code>null</code> for automatic), series (datapoint, label, axis and
 * consolidation where set) and reference lines (value or datapoint, axis, label).</li>
 * </ul>
 *
 * <p>
 * An element, series or reference line whose target is not in the image (a group address without a
 * datapoint type, a rule parameter the rules do not export), a room without functions and a chart
 * left without series are left out, each with a {@link RuntimeImage#getNotes() note} naming its
 * place; nothing is dropped silently. A chart series of a datapoint without history is written: the
 * image's check refuses it with its path ({@link ImageValidator}).
 * </p>
 */
final class PageExport {

	/** The ids of the image's targets. */
	interface Targets {

		/** The id of the function in the image, <code>null</code> if it is not there. */
		String functionId(TLObject function);

		/**
		 * The id of the datapoint (a group address, application datapoint or rule parameter) in the
		 * image, <code>null</code> if it is not there.
		 */
		String datapointId(TLObject datapoint);

		/** The id of the datapoint of the rule's enable switch, <code>null</code> if it is not there. */
		String enabledId(TLObject rule);
	}

	private final Targets _targets;

	private final List<String> _notes;

	/** The objects of the project by the path of the image they stand for. */
	private final Map<String, TLObject> _sources = new LinkedHashMap<>();

	PageExport(Targets targets, List<String> notes) {
		_targets = targets;
		_notes = notes;
	}

	/**
	 * The objects of the project by the paths of <code>ui.pages</code>.
	 */
	Map<String, TLObject> getSources() {
		return _sources;
	}

	/**
	 * The pages of the project, <code>null</code> for a project without pages.
	 */
	List<Object> pages(TLObject project) {
		List<TLObject> pages = RuleExport.objects(project.tValueByName("pages"));
		if (pages.isEmpty()) {
			return null;
		}
		Set<String> ids = new HashSet<>();
		for (TLObject page : pages) {
			String identifier = Pages.text(page.tValueByName("identifier"));
			if (identifier != null) {
				ids.add(identifier);
			}
		}
		List<Object> result = new ArrayList<>();
		for (int p = 0; p < pages.size(); p++) {
			TLObject page = pages.get(p);
			String path = "ui.pages[" + p + "]";
			_sources.put(path, page);
			Map<String, Object> json = new LinkedHashMap<>();
			String identifier = Pages.text(page.tValueByName("identifier"));
			String name = Pages.text(page.tValueByName("name"));
			json.put("id", identifier != null ? identifier
				: FunctionProposal.uniqueIdentifier(name == null ? "page" : name, ids));
			Object label = RuntimeImage.labels(page.tValueByName("name"));
			json.put("label", label != null ? label : json.get("id"));
			String icon = Pages.text(page.tValueByName("icon"));
			if (icon != null) {
				json.put("icon", icon);
			}
			List<Object> sections = new ArrayList<>();
			List<TLObject> parts = RuleExport.objects(page.tValueByName("sections"));
			for (int s = 0; s < parts.size(); s++) {
				sections.add(section(parts.get(s), path + ".sections[" + s + "]", page, s));
			}
			json.put("sections", sections);
			result.add(json);
		}
		return result;
	}

	private Map<String, Object> section(TLObject section, String path, TLObject page, int s) {
		_sources.put(path, section);
		Map<String, Object> json = new LinkedHashMap<>();
		Object label = RuntimeImage.labels(section.tValueByName("title"));
		if (label != null) {
			json.put("label", label);
		}
		List<Object> elements = new ArrayList<>();
		List<TLObject> parts = RuleExport.objects(section.tValueByName("elements"));
		for (int e = 0; e < parts.size(); e++) {
			element(parts.get(e), path + ".elements[", elements, new Place(page, s, e));
		}
		json.put("elements", elements);
		return json;
	}

	/** Where an element stands in the project, for the notes: the page, the section and element numbers from 1. */
	private record Place(TLObject source, int s, int e) {

		String page() {
			String name = Pages.text(source.tValueByName("name"));
			return name == null ? "?" : name;
		}

		Integer section() {
			return Integer.valueOf(s + 1);
		}

		Integer element() {
			return Integer.valueOf(e + 1);
		}
	}

	private void element(TLObject element, String prefix, List<Object> result, Place place) {
		if (Pages.is(element, "RoomElement")) {
			TLObject room = (TLObject) element.tValueByName("room");
			List<TLObject> functions = room == null ? List.of() : Pages.roomFunctions(room);
			int added = 0;
			for (TLObject function : functions) {
				String id = _targets.functionId(function);
				if (id != null) {
					String path = prefix + result.size() + "]";
					_sources.put(path, element);
					result.add(new LinkedHashMap<>(Map.of("function", id)));
					added++;
				}
			}
			if (added == 0) {
				note(I18NConstants.NOTE_PAGE_ROOM_EMPTY__PAGE_SECTION_ELEMENT_ROOM.fill(place.page(), place.section(),
					place.element(), room == null ? "" : Pages.targetLabel(room)));
			}
			return;
		}
		Map<String, Object> json = new LinkedHashMap<>();
		String path = prefix + result.size() + "]";
		if (Pages.is(element, "ChartElement")) {
			Map<String, Object> chart = chart(element, path + ".chart", place);
			if (chart == null) {
				return;
			}
			json.put("chart", chart);
		} else {
			String member;
			String id;
			TLObject target;
			if (Pages.is(element, "FunctionElement")) {
				member = "function";
				target = (TLObject) element.tValueByName("function");
				id = target == null ? null : _targets.functionId(target);
			} else if (Pages.is(element, "RuleEnabledElement")) {
				member = "datapoint";
				target = (TLObject) element.tValueByName("rule");
				id = target == null ? null : _targets.enabledId(target);
			} else {
				member = "datapoint";
				target = (TLObject) element.tValueByName("datapoint");
				id = target == null ? null : _targets.datapointId(target);
			}
			if (id == null) {
				note(I18NConstants.NOTE_PAGE_ELEMENT_LEFT_OUT__PAGE_SECTION_ELEMENT_TARGET.fill(place.page(),
					place.section(), place.element(), Pages.targetLabel(target)));
				return;
			}
			json.put(member, id);
			Object label = RuntimeImage.labels(element.tValueByName("label"));
			if (label != null) {
				json.put("label", label);
			}
			if (Boolean.TRUE.equals(element.tValueByName("readOnly"))) {
				json.put("readOnly", Boolean.TRUE);
			}
		}
		_sources.put(path, element);
		result.add(json);
	}

	private Map<String, Object> chart(TLObject chart, String path, Place place) {
		Map<String, Object> json = new LinkedHashMap<>();
		Object label = RuntimeImage.labels(chart.tValueByName("label"));
		if (label != null) {
			json.put("label", label);
		}
		json.put("width", Pages.width(chart));
		json.put("height", Pages.height(chart));
		String range = Pages.rangeName(Pages.name(chart.tValueByName("range")));
		if (range != null) {
			json.put("range", range);
		}
		if (!Pages.presets(chart).isEmpty()) {
			// The controller refuses presets that do not offer the chart's range (spec/runtime-image.md
			// 3.9): the model's presets are the ranges besides the default one.
			List<Object> names = new ArrayList<>();
			for (String preset : Pages.offeredRanges(chart)) {
				names.add(Pages.rangeName(preset));
			}
			json.put("presets", names);
		}
		Map<String, Object> axes = new LinkedHashMap<>();
		for (String axis : List.of("left", "right")) {
			Map<String, Object> bounds = new LinkedHashMap<>();
			bounds.put("min", number(chart.tValueByName(axis + "Min")));
			bounds.put("max", number(chart.tValueByName(axis + "Max")));
			axes.put(axis, bounds);
		}
		json.put("axes", axes);

		List<Object> series = new ArrayList<>();
		List<TLObject> parts = RuleExport.objects(chart.tValueByName("series"));
		for (int n = 0; n < parts.size(); n++) {
			TLObject part = parts.get(n);
			TLObject datapoint = (TLObject) part.tValueByName("datapoint");
			// An output of a virtual device keeps no history: the series draws the datapoint it is bound to.
			String id = datapoint == null ? null : _targets.datapointId(BlockInstances.historyTarget(datapoint));
			if (id == null) {
				note(I18NConstants.NOTE_PAGE_SERIES_LEFT_OUT__PAGE_SECTION_ELEMENT_SERIES_TARGET.fill(place.page(),
					place.section(), place.element(), Integer.valueOf(n + 1), Pages.targetLabel(datapoint)));
				continue;
			}
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("datapoint", id);
			Object seriesLabel = RuntimeImage.labels(part.tValueByName("label"));
			if (seriesLabel != null) {
				entry.put("label", seriesLabel);
			}
			String axis = Pages.name(part.tValueByName("axis"));
			if (axis != null) {
				entry.put("axis", axis);
			}
			String consolidation = Pages.name(part.tValueByName("consolidation"));
			if (consolidation != null) {
				entry.put("consolidation", consolidation);
			}
			_sources.put(path + ".series[" + series.size() + "]", part);
			series.add(entry);
		}
		if (series.isEmpty()) {
			note(I18NConstants.NOTE_PAGE_CHART_EMPTY__PAGE_SECTION_ELEMENT.fill(place.page(), place.section(),
				place.element()));
			return null;
		}
		json.put("series", series);

		List<Object> references = new ArrayList<>();
		List<TLObject> lines = RuleExport.objects(chart.tValueByName("references"));
		for (int n = 0; n < lines.size(); n++) {
			TLObject line = lines.get(n);
			Map<String, Object> entry = new LinkedHashMap<>();
			if (line.tValueByName("value") instanceof Number value) {
				entry.put("value", Double.valueOf(value.doubleValue()));
			}
			if (line.tValueByName("datapoint") instanceof TLObject datapoint) {
				String id = _targets.datapointId(datapoint);
				if (id == null) {
					note(I18NConstants.NOTE_PAGE_REFERENCE_LEFT_OUT__PAGE_SECTION_ELEMENT_REFERENCE_TARGET.fill(
						place.page(), place.section(), place.element(), Integer.valueOf(n + 1),
						Pages.targetLabel(datapoint)));
					continue;
				}
				entry.put("datapoint", id);
			}
			String axis = Pages.name(line.tValueByName("axis"));
			if (axis != null) {
				entry.put("axis", axis);
			}
			Object lineLabel = RuntimeImage.labels(line.tValueByName("label"));
			if (lineLabel != null) {
				entry.put("label", lineLabel);
			}
			_sources.put(path + ".references[" + references.size() + "]", line);
			references.add(entry);
		}
		if (!references.isEmpty()) {
			json.put("references", references);
		}
		_sources.put(path, chart);
		return json;
	}

	private static Double number(Object value) {
		return value instanceof Number number ? Double.valueOf(number.doubleValue()) : null;
	}

	private void note(ResKey message) {
		_notes.add(Resources.getInstance().getString(message));
	}

}
