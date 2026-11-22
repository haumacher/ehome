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

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.zigbee.ZigbeeFunction;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.zigbee.ZigbeeBindings;

/**
 * The application datapoints of a project (<code>ehome.runtime:ApplicationDatapoint</code>,
 * haumacher/ehome #59): datapoints of the runtime image that no technology owns (homecontrol
 * <code>spec/runtime-image.md</code> 3.3, <code>virtual</code>), values of the application that rules
 * write and the user commands where they are writable, and the outputs of schedule entries
 * (<code>spec/schedules.md</code> 5).
 *
 * <p>
 * In the image, an application datapoint follows the datapoints of the group addresses: its id, its
 * label in the languages it has, its value type, unit, the flags writable and persist, the history,
 * an empty <code>virtual</code> member in place of a technology binding and its initial value
 * ({@link #initialText(TLObject)}, schema 1.9, haumacher/ehome #217). An application datapoint
 * bound to a function of a Zigbee device (<code>zigbeeFunction</code>, haumacher/ehome #40) carries
 * the <code>zigbee</code> member instead ({@link ZigbeeBindings#json(TLObject)}): a writable one
 * transmits, with the function's cluster as the command cluster where the function can be commanded
 * (else the controller refuses it, saying so); a server attribute but the multistate input is read
 * on start, as the controller's generic datapoint of the function is. Its id is checked as it
 * is entered ({@link #idProblem(Object, Object)}): an id of the image (1..63 bytes without control
 * characters), unique among the project's datapoints (also the identifiers of its group addresses,
 * haumacher/ehome #130), not of the three-level form of a group address, not one the controller adds
 * itself.
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
		return RuleExport.objects(((Project) project).getApplicationDatapoints());
	}

	/**
	 * The id of the application datapoint, <code>null</code> for none.
	 */
	public static String id(TLObject datapoint) {
		Object id = Identifiers.of(datapoint);
		return id == null || id.toString().isBlank() ? null : id.toString().trim();
	}

	/**
	 * The value type of the application datapoint (<code>boolean</code>, ..., the spec's names).
	 */
	public static String valueType(TLObject datapoint) {
		return datapoint.tValueByName("type") instanceof TLClassifier type ? type.getName() : ValueTypes.BOOLEAN;
	}

	/**
	 * Whether the user (the controller's user interface, its API) may command the application
	 * datapoint. Rules write every application datapoint, writable or not (homecontrol
	 * <code>spec/rules.md</code> 7.3, schema 1.6, haumacher/ehome #138): a rule's own output, which the
	 * user must not change, is not writable.
	 */
	public static boolean writable(TLObject datapoint) {
		return datapoint instanceof ApplicationDatapoint application && application.getWritable();
	}

	/**
	 * The datapoint of the image the application datapoint stands for.
	 *
	 * @param derived
	 *        The histories the charts derive ({@link HistoryConfig#derivedHistories(TLObject)}).
	 */
	static Map<String, Object> json(TLObject datapoint, Map<TLObject, HistoryConfig.History> derived) {
		Map<String, Object> result = new LinkedHashMap<>();
		String id = id(datapoint);
		result.put("id", id == null ? "" : id);
		ApplicationDatapoint application = (ApplicationDatapoint) datapoint;
		Object label = RuntimeImage.labels(application.getLabel());
		if (label != null) {
			result.put("label", label);
		}
		result.put("type", valueType(datapoint));
		Object unit = application.getUnit();
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
		if (application.getPersist()) {
			result.put("persist", Boolean.TRUE);
		}
		Map<String, Object> history = HistoryConfig.json(datapoint, derived);
		if (history != null) {
			result.put("history", history);
		}
		if (function != null) {
			Map<String, Object> zigbee = ZigbeeBindings.json(function);
			if (writable(datapoint) && ((ZigbeeFunction) function).getWritable()) {

				zigbee.put("command", zigbee.get("cluster"));
			}
			result.put("zigbee", zigbee);
		} else {
			result.put("virtual", new LinkedHashMap<>());
			result.put("initial", initialJson(datapoint));
		}
		return result;
	}

	/**
	 * The neutral value of the value type as a literal of the rule language: the initial value of an
	 * application datapoint while none is entered (haumacher/ehome #217): <code>false</code>,
	 * <code>0</code>, <code>0.0</code>, the empty string, midnight, 1970-01-01, the first element of an
	 * enumeration (0), no bytes.
	 *
	 * @param valueType
	 *        The value type, the spec's name ({@link ValueTypes}).
	 */
	public static String neutralInitial(String valueType) {
		switch (valueType) {
			case ValueTypes.UNSIGNED:
			case ValueTypes.SIGNED:
			case ValueTypes.ENUMERATION:
				return "0";
			case ValueTypes.REAL:
				return "0.0";
			case ValueTypes.STRING:
				return "\"\"";
			case ValueTypes.TIME_OF_DAY:
				return "00:00";
			case ValueTypes.DATE:
				return "1970-01-01";
			case ValueTypes.DATE_TIME:
				return "1970-01-01T00:00";
			case ValueTypes.CONTROLLED:
				return "controlled(false, 0)";
			case ValueTypes.COLOR:
				return "#000000";
			case ValueTypes.RAW:
				return "x\"\"";
			default:
				return "false";
		}
	}

	/**
	 * The initial value of the application datapoint as a literal of the rule language: the one
	 * entered, else the neutral value of its type ({@link #neutralInitial(String)}).
	 */
	public static String initialText(TLObject datapoint) {
		String entered = ((ApplicationDatapoint) datapoint).getInitialValue();
		return entered == null || entered.isBlank() ? neutralInitial(valueType(datapoint)) : entered.trim();
	}

	/**
	 * The member <code>initial</code> of the application datapoint in the image (homecontrol
	 * <code>spec/runtime-image.md</code> 3.3): the JSON literal value of its initial value; a text
	 * that is no value of its type as it is, which the validation of the image refuses at the
	 * datapoint.
	 */
	static Object initialJson(TLObject datapoint) {
		String text = initialText(datapoint);
		try {
			return initialValue(text, valueType(datapoint));
		} catch (SyntaxError ex) {
			return text;
		}
	}

	/**
	 * The JSON literal value (homecontrol <code>spec/rules.md</code> 3.1) of an initial value given as
	 * a literal of the rule language: of the expression type the value type is read as, within the
	 * value type (an unsigned one not negative, a string of at most 28 bytes); a controlled value as
	 * the call <code>controlled(b, n)</code>, which the language writes for it.
	 *
	 * @param valueType
	 *        The value type, the spec's name ({@link ValueTypes}).
	 * @throws SyntaxError
	 *         If the text is no such value.
	 */
	public static Object initialValue(String text, String valueType) throws SyntaxError {
		ValueType type = ValueType.fromName(valueType);
		if (type == null) {
			throw new SyntaxError("unknown type " + valueType, -1);
		}
		Object json = type == ValueType.CONTROLLED ? controlled(text) : RuleText.parseValue(text, type.expressionType());
		Values.Literal literal = Values.fromJson(type.expressionType(), json);
		if (!literal.isOk()) {
			throw new SyntaxError(literal.reason(), -1);
		}
		if (Values.convertForDatapoint(literal.value(), type) == null) {
			throw new SyntaxError("out of the range of the type " + valueType, -1);
		}
		return json;
	}

	/**
	 * The initial value given as a JSON literal value (an image's member <code>initial</code>) as a
	 * literal of the rule language ({@link #initialValue(String, String)} reads it back);
	 * <code>null</code> when it is no value of the type.
	 *
	 * @param valueType
	 *        The value type, the spec's name ({@link ValueTypes}).
	 */
	public static String initialLiteral(Object json, String valueType) {
		if (!ImageValidator.initialFits(json, valueType)) {
			return null;
		}
		ValueType type = ValueType.fromName(valueType);
		if (type == ValueType.CONTROLLED) {
			Map<?, ?> value = (Map<?, ?>) json;
			return "controlled(" + value.get("control") + ", " + value.get("value") + ")";
		}
		return RuleText.printValue(json, type.expressionType());
	}

	/** The JSON form of the controlled value <code>controlled(b, n)</code>. */
	private static Object controlled(String text) throws SyntaxError {
		Map<String, Object> node = RuleText.parse(text);
		if ("call".equals(node.get("kind")) && "controlled".equals(node.get("function"))
			&& node.get("args") instanceof List<?> args && args.size() == 2
			&& args.get(0) instanceof Map<?, ?> control && "boolean".equals(control.get("type"))
			&& args.get(1) instanceof Map<?, ?> value && "integer".equals(value.get("type"))) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("control", control.get("value"));
			result.put("value", value.get("value"));
			return result;
		}
		throw new SyntaxError("controlled(b, n) with a truth value and a number expected", -1);
	}

	/**
	 * Why the text cannot be the initial value of the given application datapoint (haumacher/ehome
	 * #217); <code>null</code> if it can, or is empty (the neutral value of its type applies).
	 *
	 * @param value
	 *        The text entered.
	 * @param obj
	 *        The application datapoint, in a form the form's object.
	 */
	public static ResKey initialProblem(Object value, Object obj) {
		if (value == null || value.toString().isBlank() || !(obj instanceof TLObject datapoint)) {
			return null;
		}
		String type = valueType(datapoint);
		try {
			initialValue(value.toString().trim(), type);
			return null;
		} catch (SyntaxError ex) {
			return I18NConstants.INITIAL_VALUE_INVALID__TYPE_REASON.fill(type, ex.getOffset() < 0 ? ex.getMessage() : ex.describe());
		}
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
				// Its address in three levels, or its identifier (haumacher/ehome #130).
				if (id.equals(GroupAddressIds.address(address)) || id.equals(GroupAddressIds.identifier(address))) {
					return I18NConstants.DATAPOINT_ID_OF_GROUP_ADDRESS__ADDRESS.fill(DatapointNames.name(address));
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
	 *        The output entered: the application datapoint referenced (or its id).
	 */
	public static ResKey outputProblem(Object entry, Object output) {
		if (output instanceof TLObject datapoint) {
			// The reference of the entry (haumacher/ehome #90).
			return datapoint.tValid() && isApplicationDatapoint(datapoint) && writable(datapoint)
				? I18NConstants.OUTPUT_WRITABLE__ID.fill(DatapointNames.name(datapoint)) : null;
		}
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
				return I18NConstants.OUTPUT_WRITABLE__ID.fill(DatapointNames.name(datapoint));
			}
		}
		return null;
	}

}
