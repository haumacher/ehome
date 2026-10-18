/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLFormObjectBase;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.zigbee.ZigbeeBindings;

/**
 * The application datapoints of a project (<code>ehome.runtime:ApplicationDatapoint</code>,
 * haumacher/ehome #59): datapoints of the runtime image that no technology owns (homecontrol
 * <code>spec/runtime-image.md</code> 3.3, <code>virtual</code>), values of the application that rules
 * and the user interface command, and the outputs of schedule entries
 * (<code>spec/schedules.md</code> 5).
 *
 * <p>
 * In the image, an application datapoint follows the datapoints of the group addresses: its id, its
 * label in the languages it has, its value type, unit, the flags writable and persist, the history,
 * and an empty <code>virtual</code> member in place of a technology binding. An application datapoint
 * bound to a function of a Zigbee device (<code>zigbeeFunction</code>, haumacher/ehome #40) carries
 * the <code>zigbee</code> member instead ({@link ZigbeeBindings#json(TLObject)}): a writable one
 * transmits, with the function's cluster as the command cluster where the function can be commanded
 * (else the controller refuses it, saying so); a server attribute but the multistate input is read
 * on start, as the controller's generic datapoint of the function is. Its id is checked as it
 * is entered ({@link #idProblem(Object, Object)}): an id of the image (1..63 bytes without control
 * characters), unique among the project's datapoints, not of the three-level form of a group
 * address, not one the controller adds itself.
 * </p>
 */
public final class ApplicationDatapoints {

	/** The type of an application datapoint. */
	public static final String TYPE = "ApplicationDatapoint";

	/** The reference of the project holding its application datapoints. */
	public static final String REFERENCE = "applicationDatapoints";

	/** The ids the controller gives the datapoints of its time master. */
	private static final List<String> TIME_MASTER_IDS = List.of("knx.time", "knx.date", "knx.datetime");

	/** The prefix of the ids of the datapoints the controller adds for joined Zigbee devices. */
	private static final String ZIGBEE_PREFIX = "zigbee.";

	private static final Pattern THREE_LEVEL = Pattern.compile("\\d+/\\d+/\\d+");

	private ApplicationDatapoints() {
		// Static utility.
	}

	/**
	 * Whether the object is an application datapoint.
	 */
	public static boolean isApplicationDatapoint(TLObject obj) {
		return obj != null && TYPE.equals(obj.tType().getName())
			&& "ehome.runtime".equals(obj.tType().getModule().getName());
	}

	/**
	 * The application datapoints of the project, in their order.
	 */
	public static List<TLObject> of(TLObject project) {
		return RuleExport.objects(project.tValueByName(REFERENCE));
	}

	/**
	 * The id of the application datapoint, <code>null</code> for none.
	 */
	public static String id(TLObject datapoint) {
		Object id = datapoint.tValueByName("identifier");
		return id == null || id.toString().isBlank() ? null : id.toString().trim();
	}

	/**
	 * The value type of the application datapoint (<code>boolean</code>, ..., the spec's names).
	 */
	public static String valueType(TLObject datapoint) {
		return datapoint.tValueByName("type") instanceof TLClassifier type ? type.getName() : ValueTypes.BOOLEAN;
	}

	/**
	 * Whether rules, the user interface and the API may command the application datapoint.
	 */
	public static boolean writable(TLObject datapoint) {
		return Boolean.TRUE.equals(datapoint.tValueByName("writable"));
	}

	/**
	 * The datapoint of the image the application datapoint stands for.
	 */
	static Map<String, Object> json(TLObject datapoint) {
		Map<String, Object> result = new LinkedHashMap<>();
		String id = id(datapoint);
		result.put("id", id == null ? "" : id);
		Object label = RuntimeImage.labels(datapoint.tValueByName("label"));
		if (label != null) {
			result.put("label", label);
		}
		result.put("type", valueType(datapoint));
		Object unit = datapoint.tValueByName("unit");
		if (unit != null && !unit.toString().isBlank()) {
			result.put("unit", unit.toString().trim());
		}
		TLObject function = ZigbeeBindings.function(datapoint);
		if (writable(datapoint)) {
			result.put("writable", Boolean.TRUE);
			if (function != null) {
				// A commanded value goes to the device; without a command cluster the controller refuses.
				result.put("transmit", Boolean.TRUE);
			}
		}
		if (function != null && ZigbeeBindings.readOnStart(function)) {
			result.put("readOnStart", Boolean.TRUE);
		}
		if (Boolean.TRUE.equals(datapoint.tValueByName("persist"))) {
			result.put("persist", Boolean.TRUE);
		}
		Integer retention = DatapointSettings.historyRetention(datapoint);
		Integer spacing = DatapointSettings.historySpacing(datapoint);
		if (retention != null || spacing != null) {
			Map<String, Object> history = new LinkedHashMap<>();
			if (retention != null) {
				history.put("retention", Long.valueOf(retention.longValue()));
			}
			if (spacing != null) {
				history.put("spacing", Long.valueOf(spacing.longValue()));
			}
			result.put("history", history);
		}
		if (function != null) {
			Map<String, Object> zigbee = ZigbeeBindings.json(function);
			if (writable(datapoint) && Boolean.TRUE.equals(function.tValueByName("writable"))) {
				zigbee.put("command", zigbee.get("cluster"));
			}
			result.put("zigbee", zigbee);
		} else {
			result.put("virtual", new LinkedHashMap<>());
		}
		return result;
	}

	/**
	 * Why the text cannot be the id of the given application datapoint; <code>null</code> if it can
	 * (or is empty, which the mandatory field refuses).
	 *
	 * @param value
	 *        The id entered.
	 * @param obj
	 *        The application datapoint, in a form the form's object.
	 */
	public static ResKey idProblem(Object value, Object obj) {
		if (value == null || value.toString().isBlank()) {
			return null;
		}
		String id = value.toString().trim();
		if (id.getBytes(StandardCharsets.UTF_8).length > ImageValidator.MAX_NAME || id.matches(".*\\p{Cntrl}.*")) {
			return I18NConstants.INVALID_DATAPOINT_ID;
		}
		if (TIME_MASTER_IDS.contains(id) || id.startsWith(ZIGBEE_PREFIX)) {
			return I18NConstants.DATAPOINT_ID_RESERVED__ID.fill(id);
		}
		TLObject self = obj instanceof TLFormObjectBase form ? form.getEditedObject() : obj instanceof TLObject o ? o : null;
		TLObject project = self == null || !self.tValid() ? null : RuntimeFunctions.project(self);
		if (project != null) {
			for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
				if (address.tValueByName("address") instanceof GA ga && id.equals(RuntimeImage.threeLevel(ga.getValue()))) {
					return I18NConstants.DATAPOINT_ID_OF_GROUP_ADDRESS__ADDRESS.fill(ga.toString() + " "
						+ String.valueOf(address.tValueByName("name")));
				}
			}
			for (TLObject other : of(project)) {
				if (other != self && id.equals(id(other))) {
					return I18NConstants.DATAPOINT_ID_DUPLICATE__ID.fill(id);
				}
			}
		}
		if (THREE_LEVEL.matcher(id).matches()) {
			return I18NConstants.DATAPOINT_ID_ADDRESS_FORM;
		}
		return null;
	}

	/**
	 * Why the text cannot be the unit of a datapoint; <code>null</code> if it can.
	 */
	public static ResKey unitProblem(String text) {
		return text.trim().getBytes(StandardCharsets.UTF_8).length > 15 || text.matches(".*\\p{Cntrl}.*")
			? I18NConstants.INVALID_UNIT : null;
	}

	/**
	 * Why the datapoint named as the output of a schedule entry should be changed: it is an
	 * application datapoint of the project that is writable, and the controller refuses commands to
	 * the output of a schedule entry (<code>spec/schedules.md</code> 5); <code>null</code> otherwise.
	 *
	 * @param entry
	 *        The schedule entry, in a form the form's object.
	 * @param output
	 *        The id of the output entered.
	 */
	public static ResKey outputProblem(Object entry, Object output) {
		if (output == null || output.toString().isBlank()) {
			return null;
		}
		TLObject stored = entry instanceof TLFormObjectBase form ? form.getEditedObject()
			: entry instanceof TLObject o ? o : null;
		TLObject project = stored == null || !stored.tValid() ? null : RuntimeFunctions.project(stored);
		if (project == null) {
			return null;
		}
		String id = output.toString().trim();
		for (TLObject datapoint : of(project)) {
			if (id.equals(id(datapoint)) && writable(datapoint)) {
				return I18NConstants.OUTPUT_WRITABLE__ID.fill(id);
			}
		}
		return null;
	}

}
