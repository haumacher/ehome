/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKeyN;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.rules.DatapointId;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;

/**
 * The names of the datapoints in the display texts of E-Home (haumacher/ehome #113): every text a
 * person reads that names a datapoint names it as <code>Name (id)</code>, as the option lists of
 * the forms do: <code>Licht Flur (11/0/4)</code>, <code>Licht Küche (kueche.licht)</code> for a group
 * address with an identifier (haumacher/ehome #130), the id alone where the datapoint has no name.
 *
 * <p>
 * The name is the label of the object the datapoint stands for ({@link #label(TLObject)}): a group
 * address by its name, an application datapoint (also one bound to a Zigbee function) by its label,
 * a parameter of a rule by its title, the enable switch of a rule by the rule's name, the adjustable
 * time of a schedule entry by the entry's label. A label in several languages is read in the
 * language of the reader ({@link Resources#getInstance()}), as the option lists read it.
 * </p>
 *
 * <p>
 * In parentheses stands what the text named the datapoint by before: its id
 * (<code>11/0/4</code>, <code>rule/licht/maxHelligkeit</code>) for a datapoint, the identifier of a
 * parameter, timer or schedule entry that a part of a rule references, and inside an expression the
 * reference to a parameter of its rule as it is written (<code>Max. Helligkeit ($maxHelligkeit)</code>,
 * <code>Max. Helligkeit ($"max-helligkeit")</code>). An expression is shown with these names
 * ({@link #expression(TLObject, String)}): its references are found by the parser of the rule
 * language ({@link RuleText}), a string literal stays as it is. The field that edits an expression
 * shows the rule language, never these names.
 * </p>
 */
public final class DatapointNames {

	private final TLObject _project;

	private Map<String, TLObject> _datapoints;

	private DatapointNames(TLObject project) {
		_project = project;
	}

	/**
	 * The names of the datapoints of the given project (<code>null</code> for an object outside a
	 * project: every datapoint is then named by its id).
	 */
	public static DatapointNames of(TLObject project) {
		return new DatapointNames(project);
	}

	// ---- the format --------------------------------------------------------------------------

	/**
	 * The display text of an object with the given label and id: <code>label (id)</code>, the id
	 * alone without a label (or with a label that is the id), the label alone without an id,
	 * <code>null</code> without both.
	 */
	public static String format(String label, String id) {
		if (id == null) {
			return label;
		}
		return label == null || label.equals(id) ? id : label + " (" + id + ")";
	}

	/**
	 * The label of an object a datapoint stands for: its title, label or name, the first one it has
	 * and that is not empty; a label in several languages in the language of the reader;
	 * <code>null</code> for none.
	 */
	public static String label(TLObject target) {
		if (target == null || !target.tValid()) {
			return null;
		}
		if (Pages.isRuleState(target)) {
			// The datapoint of a rule's state (haumacher/ehome #140): the rule's name and "state".
			String rule = label(target.tContainer());
			return rule == null ? null
				: Resources.getInstance().getString(I18NConstants.STATE_DATAPOINT_LABEL__RULE.fill(rule));
		}
		for (String attribute : new String[] { "title", "label", "name" }) {
			if (target.tType().getPart(attribute) != null) {
				String text = text(target.tValueByName(attribute));
				if (text != null) {
					return text;
				}
			}
		}
		return null;
	}

	private static String text(Object value) {
		if (value instanceof ResKey key) {
			String text = Resources.getInstance().getString(key, null);
			return text == null || text.isBlank() ? null : text.trim();
		}
		return value == null || value.toString().isBlank() ? null : value.toString().trim();
	}

	// ---- datapoints --------------------------------------------------------------------------

	/**
	 * The display text of the datapoint the given object is, by its own id
	 * ({@link Pages#imageId(TLObject)}: a group address by its identifier, else its three-level
	 * address, also where it is the address of the time master): <code>Licht Flur (11/0/4)</code>; its label alone if it
	 * has no id (a group address without an address), <code>null</code> without both.
	 */
	public static String name(TLObject datapoint) {
		return format(label(datapoint), Pages.imageId(datapoint));
	}

	/**
	 * The display text of the datapoint of the image with the given id: <code>Licht Flur
	 * (11/0/4)</code>, the id alone if the project has no object of this id or it has no name.
	 */
	public String datapoint(String id) {
		return format(label(datapoints().get(id)), id);
	}

	/**
	 * The labels of all datapoints of the project that have one, by their ids
	 * ({@link RuleReferences#datapoints(TLObject)}), and of its group addresses by their three-level
	 * addresses (also of the one of the time master), resolved now: for a text written outside the
	 * request of a person, e.g. by the simulation, with {@link #format(String, String)}.
	 */
	public Map<String, String> labels() {
		Map<String, String> result = new HashMap<>();
		for (Map.Entry<String, TLObject> entry : datapoints().entrySet()) {
			String label = label(entry.getValue());
			if (label != null) {
				result.put(entry.getKey(), label);
			}
		}
		if (_project != null) {
			for (TLObject address : RuntimeFunctions.groupAddresses(_project)) {
				// By the address also where the datapoint has an identifier (haumacher/ehome #130).
				String id = GroupAddressIds.address(address);
				String label = label(address);
				if (id != null && label != null) {
					result.putIfAbsent(id, label);
				}
			}
		}
		return result;
	}

	/**
	 * The label of the object of the project that is the datapoint of the image with the given id,
	 * <code>null</code> for none.
	 */
	public String label(String id) {
		return label(datapoints().get(id));
	}

	/**
	 * A message of a loader of the image as the user reads it (haumacher/ehome #118): every datapoint
	 * the message names by its id ({@link DatapointId}, also in a message given as an argument of the
	 * message) named as {@link #datapoint(String) Name (id)}.
	 *
	 * @param message
	 *        The message, <code>null</code> for none.
	 * @return The message with the names, the same message if it names no datapoint.
	 */
	public ResKey names(ResKey message) {
		if (message == null) {
			return null;
		}
		Object[] arguments = message.arguments();
		if (arguments == null || arguments.length == 0 || !(message.plain() instanceof ResKeyN key)) {
			return message;
		}
		Object[] named = null;
		for (int n = 0; n < arguments.length; n++) {
			Object argument = arguments[n];
			Object name = argument instanceof DatapointId id ? datapoint(id.id())
				: argument instanceof ResKey inner ? names(inner) : argument;
			if (name != argument) {
				if (named == null) {
					named = arguments.clone();
				}
				named[n] = name;
			}
		}
		return named == null ? message : key.fill(named);
	}

	/**
	 * The display text of the group address of the project with the given three-level address, also
	 * where it is the address of the time master (whose datapoint is <code>knx.time</code>):
	 * <code>Uhrzeit (0/0/1)</code>, the address alone if the project has no such group address or it
	 * has no name.
	 */
	public String groupAddress(String address) {
		if (_project != null) {
			for (TLObject candidate : RuntimeFunctions.groupAddresses(_project)) {
				if (address.equals(GroupAddressIds.address(candidate))) {
					return format(label(candidate), address);
				}
			}
		}
		return address;
	}

	/**
	 * The display text of the target of a reference of a part of a rule or schedule entry
	 * (haumacher/ehome #90): a datapoint by its label and id in the image, a schedule entry, timer or
	 * parameter by its label and identifier; <code>null</code> for none.
	 *
	 * @param target
	 *        The value of the reference.
	 * @param attribute
	 *        The name of the reference ({@link RuleReferences#ATTRIBUTES}).
	 */
	public String reference(Object target, String attribute) {
		String id = RuleReferences.id(_project, target, attribute);
		return id == null ? null : format(label((TLObject) target), id);
	}

	private Map<String, TLObject> datapoints() {
		if (_datapoints == null) {
			_datapoints = _project == null ? Map.of() : RuleReferences.datapoints(_project);
		}
		return _datapoints;
	}

	// ---- expressions -------------------------------------------------------------------------

	/**
	 * The expression of a part of the given rule as a display text: a datapoint with a name as
	 * <code>Helligkeit Flur (11/0/6)</code>, a parameter of the rule with a title as
	 * <code>Max. Helligkeit ($maxHelligkeit)</code>, every other reference as it is written; in the
	 * canonical form of the rule language. A text that does not parse is shown as it is.
	 *
	 * @param rule
	 *        The rule of the expression, <code>null</code> outside a rule (its parameters are then
	 *        named by their references).
	 * @param text
	 *        The expression, <code>null</code> for none.
	 * @return The display text, <code>null</code> for an empty expression.
	 */
	public String expression(TLObject rule, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return RuleText.print(RuleText.parse(text), names(rule));
		} catch (SyntaxError ex) {
			return text;
		}
	}

	/**
	 * A log message of a part of the given rule as a display text: its placeholders shown as
	 * {@link #expression(TLObject, String)} shows an expression. A text that does not parse is shown
	 * as it is.
	 *
	 * @return The display text, <code>null</code> for an empty message.
	 */
	public String message(TLObject rule, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			List<Object> parts = RuleText.parseMessage(text);
			return RuleText.printMessage(parts, names(rule));
		} catch (SyntaxError ex) {
			return text;
		}
	}

	/**
	 * How {@link RuleText} names the references of an expression of the given rule in a display
	 * text.
	 */
	public RuleText.Names names(TLObject rule) {
		return new RuleText.Names() {
			@Override
			public String datapoint(String name) {
				String label = label(datapoints().get(name));
				return label == null || label.equals(name) ? null : format(label, name);
			}

			@Override
			public String parameter(String id) {
				String label = label(ruleParameter(rule, id));
				return label == null ? null : format(label, RuleText.parameterReference(id));
			}
		};
	}

	private static TLObject ruleParameter(TLObject rule, String id) {
		if (rule == null) {
			return null;
		}
		for (TLObject parameter : RuleExport.objects(rule.tValueByName("parameters"))) {
			Object identifier = parameter.tValueByName("identifier");
			if (identifier != null && id.equals(identifier.toString().trim())) {
				return parameter;
			}
		}
		return null;
	}

}
