/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.Schedule;

/**
 * The datapoints of a runtime image that the controller shows on the inhabitant's pages without a
 * name (haumacher/ehome #114): the device UI names a datapoint there by its label, by its id
 * (<code>11/0/4</code>) only where it has none (homecontrol #82). A deployment warns for each of
 * them, without refusing the image.
 *
 * <p>
 * A datapoint is on the inhabitant's pages when it is
 * </p>
 * <ul>
 * <li>a datapoint of a function: the overview shows the function, its sheet each role's
 * datapoint;</li>
 * <li>the datapoint of an element of a control page, of a series or of a reference line of a chart,
 * unless the element, series or line carries a label of its own, which the page shows instead;</li>
 * <li>the output of a schedule entry;</li>
 * <li>the datapoint of a rule parameter: the rules page lists every parameter of a rule.</li>
 * </ul>
 *
 * <p>
 * Its name is the label of the image's datapoint (a group address's name, an application
 * datapoint's label), for the datapoint of a rule parameter the parameter's label, for the enable
 * switch of a rule the rule's name, for the adjustable time of a schedule entry the entry's label; a
 * label that is the id names nothing (the image labels a rule parameter without title with its id).
 * A datapoint the image does not declare (one the controller adds itself, such as
 * <code>knx.time</code>, or a mirror of a virtual device) is left out: it is named by the controller,
 * or the image is refused for it anyway.
 * </p>
 */
public final class UnnamedDatapoints {

	private UnnamedDatapoints() {
		// Static helpers.
	}

	/**
	 * The ids of the datapoints of the given image without a name that the controller shows on the
	 * inhabitant's pages, each once, in the order of their first appearance: functions, control pages,
	 * schedule entries, rules.
	 *
	 * @param image
	 *        The JSON document of the image ({@link RuntimeImage#getJson()}).
	 */
	public static List<String> ids(Map<String, Object> image) {
		Map<String, Boolean> named = names(image);
		List<String> result = new ArrayList<>();
		for (String id : shown(image)) {
			if (Boolean.FALSE.equals(named.get(id))) {
				result.add(id);
			}
		}
		return result;
	}

	/**
	 * The warnings of a deployment of the given image: one per datapoint of {@link #ids(Map)}.
	 */
	public static List<ResKey> warnings(Map<String, Object> image) {
		List<ResKey> result = new ArrayList<>();
		for (String id : ids(image)) {
			result.add(I18NConstants.DEPLOY_UNNAMED_DATAPOINT__ID.fill(id));
		}
		return result;
	}

	/**
	 * Whether each datapoint the image declares has a name, by its id.
	 */
	private static Map<String, Boolean> names(Map<String, Object> image) {
		Map<String, Boolean> result = new HashMap<>();
		for (Map<?, ?> datapoint : maps(image.get("datapoints"))) {
			if (datapoint.get("id") instanceof String id) {
				result.putIfAbsent(id, Boolean.valueOf(named(datapoint.get("label"), id)));
			}
		}
		for (Map<?, ?> rule : maps(image.get("rules"))) {
			if (!(rule.get("id") instanceof String ruleId)) {
				continue;
			}
			String enabled = RuleLoader.parameterDatapointName(ruleId, "enabled");
			result.putIfAbsent(enabled, Boolean.valueOf(named(rule.get("name"), enabled)));
			for (Map<?, ?> parameter : maps(rule.get("parameters"))) {
				if (parameter.get("id") instanceof String parameterId) {
					result.putIfAbsent(RuleLoader.parameterDatapointName(ruleId, parameterId),
						Boolean.valueOf(named(parameter.get("label"), parameterId)));
				}
			}
		}
		for (Map<?, ?> entry : maps(image.get("schedules"))) {
			if (entry.get("id") instanceof String entryId) {
				String time = Schedule.timeDatapointName(entryId);
				result.putIfAbsent(time, Boolean.valueOf(named(entry.get("label"), time)));
			}
		}
		return result;
	}

	/**
	 * The ids of the datapoints the inhabitant's pages show by their name, each once.
	 */
	private static Set<String> shown(Map<String, Object> image) {
		Set<String> result = new LinkedHashSet<>();
		for (Map<?, ?> function : maps(image.get("functions"))) {
			if (function.get("datapoints") instanceof Map<?, ?> roles) {
				for (Object id : roles.values()) {
					add(result, id);
				}
			}
		}
		if (image.get("ui") instanceof Map<?, ?> ui) {
			for (Map<?, ?> page : maps(ui.get("pages"))) {
				for (Map<?, ?> section : maps(page.get("sections"))) {
					for (Map<?, ?> element : maps(section.get("elements"))) {
						if (element.get("chart") instanceof Map<?, ?> chart) {
							unlabeled(result, chart.get("series"));
							unlabeled(result, chart.get("references"));
						} else {
							unlabeled(result, element);
						}
					}
				}
			}
		}
		for (Map<?, ?> entry : maps(image.get("schedules"))) {
			add(result, entry.get("output"));
		}
		for (Map<?, ?> rule : maps(image.get("rules"))) {
			if (!(rule.get("id") instanceof String ruleId)) {
				continue;
			}
			for (Map<?, ?> parameter : maps(rule.get("parameters"))) {
				if (parameter.get("id") instanceof String parameterId) {
					result.add(RuleLoader.parameterDatapointName(ruleId, parameterId));
				}
			}
		}
		return result;
	}

	private static void unlabeled(Set<String> result, Object entries) {
		for (Map<?, ?> entry : maps(entries)) {
			unlabeled(result, entry);
		}
	}

	/** The datapoint of a page element, series or reference line that carries no label of its own. */
	private static void unlabeled(Set<String> result, Map<?, ?> entry) {
		if (!entry.containsKey("label")) {
			add(result, entry.get("datapoint"));
		}
	}

	private static void add(Set<String> result, Object id) {
		if (id instanceof String text && !text.isEmpty()) {
			result.add(text);
		}
	}

	/**
	 * Whether a label of the image (a text, or a map of languages to texts) names its object: it has
	 * a text other than the id, which the image writes for a rule parameter without title
	 * ({@link DatapointNames#format(String, String)} reads a label that is the id as none, too).
	 */
	private static boolean named(Object label, String id) {
		if (label instanceof Map<?, ?> texts) {
			return texts.values().stream().anyMatch(text -> named(text, id));
		}
		return label instanceof String text && !text.isBlank() && !text.trim().equals(id);
	}

	private static List<Map<?, ?>> maps(Object list) {
		List<Map<?, ?>> result = new ArrayList<>();
		if (list instanceof List<?> entries) {
			for (Object entry : entries) {
				if (entry instanceof Map<?, ?> map) {
					result.add(map);
				}
			}
		}
		return result;
	}

}
