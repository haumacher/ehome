/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.model.ModelKind;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;

import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;

/**
 * The renamed ids of one project and the rewrite of the expressions of its rules that name them
 * (haumacher/ehome #111).
 *
 * <p>
 * The expressions (conditions, values, durations, the placeholders of log messages) store the ids
 * they name as text. A renamed id is replaced where an expression names it: a parameter of a rule
 * (<code>$id</code>, <code>$"id"</code> in its own rule, the datapoint
 * <code>@"rule/&lt;rule&gt;/&lt;id&gt;"</code> in every rule), an application datapoint and the
 * three-level address of a group address (<code>@"id"</code>), a timer of a rule
 * (<code>running(t)</code>, <code>remaining(t)</code> in its own rule), a state of a rule (the string
 * compared with <code>state()</code> in its own rule and with <code>state("rule")</code> in every
 * rule). The references are found in the parsed expression ({@link RuleText#parse(String)}), not in
 * its text: <code>$a</code> is not touched in <code>$ab</code>, nor in a string literal, nor a
 * parameter of the same id of another rule. A changed expression is stored in its canonical form
 * ({@link RuleText#print(Object)}); a text that does not parse names nothing and stays as it is.
 * </p>
 *
 * <p>
 * All renames of one transaction are applied in one pass, so that ids exchanged between two objects
 * are rewritten correctly.
 * </p>
 */
public final class ExpressionRenames {

	/** The expression attribute of the types of the parts of a rule that hold one. */
	private static final Map<String, String> EXPRESSIONS = Map.of(
		"RuleTimer", "duration",
		"StartTimerAction", "duration",
		"RestartTimerAction", "duration",
		"Reaction", "condition",
		"MatchTrigger", "condition",
		"SilenceTrigger", "after",
		"WriteAction", "value",
		"SetParameterAction", "value");

	/** The type of the parts of a rule holding a log message with expressions in placeholders. */
	private static final String LOG_ACTION = "LogAction";

	/** The attribute of the log message. */
	private static final String MESSAGE = "message";

	private final Map<String, String> _datapoints = new HashMap<>();

	private final Map<String, Map<String, String>> _parameters = new HashMap<>();

	private final Map<String, Map<String, String>> _timers = new HashMap<>();

	private final Map<String, Map<String, String>> _states = new HashMap<>();

	private final List<RenamedParameter> _renamedParameters = new ArrayList<>();

	/**
	 * A renamed parameter.
	 *
	 * @param rule
	 *        The id of its rule.
	 * @param oldId
	 *        Its id before.
	 * @param newId
	 *        Its id now.
	 */
	public record RenamedParameter(String rule, String oldId, String newId) {
		// Value only.
	}

	/**
	 * Notes that the datapoint of the image with the given id now has the new id (an application
	 * datapoint, the three-level address of a group address).
	 */
	public void datapoint(String oldId, String newId) {
		_datapoints.put(oldId, newId);
	}

	/**
	 * Notes that the parameter of the rule with the given id is renamed; its datapoint
	 * <code>rule/&lt;rule&gt;/&lt;parameter&gt;</code> follows.
	 */
	public void parameter(String rule, String oldId, String newId) {
		_parameters.computeIfAbsent(rule, x -> new HashMap<>()).put(oldId, newId);
		datapoint(RuleLoader.parameterDatapointName(rule, oldId), RuleLoader.parameterDatapointName(rule, newId));
		_renamedParameters.add(new RenamedParameter(rule, oldId, newId));
	}

	/**
	 * Notes that the timer of the rule with the given id is renamed.
	 */
	public void timer(String rule, String oldId, String newId) {
		_timers.computeIfAbsent(rule, x -> new HashMap<>()).put(oldId, newId);
	}

	/**
	 * Notes that the state of the rule with the given id is renamed.
	 */
	public void state(String rule, String oldId, String newId) {
		_states.computeIfAbsent(rule, x -> new HashMap<>()).put(oldId, newId);
	}

	/**
	 * Whether no id is renamed.
	 */
	public boolean isEmpty() {
		return _datapoints.isEmpty() && _timers.isEmpty() && _states.isEmpty();
	}

	/**
	 * The renamed parameters: the value the inhabitant set on the controller is stored under the old
	 * id and is lost (homecontrol <code>spec/rules.md</code> 8.4, D5).
	 */
	public List<RenamedParameter> getRenamedParameters() {
		return _renamedParameters;
	}

	// ---- applying ----------------------------------------------------------------------------

	/**
	 * Rewrites the expressions of the rules of the project that name a renamed id.
	 *
	 * @return The number of expressions changed: each field once (a log message with several
	 *         placeholders counts once).
	 */
	public int apply(TLObject project) {
		int result = 0;
		for (TLObject rule : RuleExport.objects(project.tValueByName("rules"))) {
			String ruleId = id(rule);
			if (ruleId != null) {
				result += applyTree(ruleId, rule);
			}
		}
		return result;
	}

	private int applyTree(String rule, TLObject obj) {
		int result = 0;
		String type = obj.tType().getName();
		String attribute = EXPRESSIONS.get(type);
		if (attribute != null) {
			result += update(obj, attribute, rewriteExpression(rule, text(obj, attribute)));
		} else if (type.equals(LOG_ACTION)) {
			result += update(obj, MESSAGE, rewriteMessage(rule, text(obj, MESSAGE)));
		}
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (part.getModelKind() == ModelKind.REFERENCE && ((TLReference) part).isComposite()) {
				for (TLObject child : RuleExport.objects(obj.tValue(part))) {
					result += applyTree(rule, child);
				}
			}
		}
		return result;
	}

	private static String text(TLObject obj, String attribute) {
		return obj.tValueByName(attribute) instanceof String text ? text : null;
	}

	private static int update(TLObject obj, String attribute, String text) {
		if (text == null) {
			return 0;
		}
		obj.tUpdateByName(attribute, text);
		return 1;
	}

	/**
	 * The expression of the rule with the given id with the renamed ids, in canonical form;
	 * <code>null</code> if it names none of them (or does not parse).
	 */
	public String rewriteExpression(String rule, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			Map<String, Object> node = RuleText.parse(text);
			return rewrite(node, rule) ? RuleText.print(node) : null;
		} catch (SyntaxError ex) {
			return null;
		}
	}

	/**
	 * The log message of the rule with the given id with the renamed ids in its placeholders, in
	 * canonical form; <code>null</code> if it names none of them (or does not parse).
	 */
	public String rewriteMessage(String rule, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			List<Object> parts = RuleText.parseMessage(text);
			boolean changed = false;
			for (Object part : parts) {
				changed |= rewrite(part, rule);
			}
			return changed ? RuleText.printMessage(parts) : null;
		} catch (SyntaxError ex) {
			return null;
		}
	}

	/**
	 * Replaces the renamed ids in the given node of a parsed expression and below it.
	 *
	 * @param rule
	 *        The id of the rule the expression belongs to.
	 * @return Whether an id was replaced.
	 */
	@SuppressWarnings("unchecked")
	private boolean rewrite(Object node, String rule) {
		if (!(node instanceof Map<?, ?>)) {
			return false;
		}
		Map<String, Object> map = (Map<String, Object>) node;
		switch (String.valueOf(map.get("kind"))) {
			case "datapoint":
				return replace(map, "name", _datapoints);
			case "parameter":
				return replace(map, "id", _parameters.get(rule));
			case "timer":
				return replace(map, "id", _timers.get(rule));
			case "binary": {
				boolean result = false;
				Object op = map.get("op");
				if ("==".equals(op) || "!=".equals(op)) {
					result |= state(map.get("left"), map.get("right"), rule);
					result |= state(map.get("right"), map.get("left"), rule);
				}
				result |= rewrite(map.get("left"), rule);
				result |= rewrite(map.get("right"), rule);
				return result;
			}
			case "unary":
				return rewrite(map.get("operand"), rule);
			case "call": {
				boolean result = false;
				if (map.get("args") instanceof List<?> args) {
					for (Object arg : args) {
						result |= rewrite(arg, rule);
					}
				}
				return result;
			}
			default:
				return false;
		}
	}

	/**
	 * Replaces a renamed state in a comparison of <code>state()</code> or
	 * <code>state("rule")</code> with a string literal.
	 */
	@SuppressWarnings("unchecked")
	private boolean state(Object call, Object other, String rule) {
		if (!(call instanceof Map<?, ?> c) || !"call".equals(c.get("kind")) || !"state".equals(c.get("function"))) {
			return false;
		}
		if (!(other instanceof Map<?, ?> o) || !isString(o)) {
			return false;
		}
		List<?> args = c.get("args") instanceof List<?> list ? list : List.of();
		String owner;
		if (args.isEmpty()) {
			owner = rule;
		} else if (args.size() == 1 && args.get(0) instanceof Map<?, ?> arg && isString(arg)) {
			owner = String.valueOf(arg.get("value"));
		} else {
			return false;
		}
		return replace((Map<String, Object>) other, "value", _states.get(owner));
	}

	private static boolean isString(Map<?, ?> node) {
		return "literal".equals(node.get("kind")) && "string".equals(node.get("type"));
	}

	private static boolean replace(Map<String, Object> node, String member, Map<String, String> renames) {
		if (renames == null) {
			return false;
		}
		String renamed = renames.get(String.valueOf(node.get(member)));
		if (renamed == null) {
			return false;
		}
		node.put(member, renamed);
		return true;
	}

	private static String id(TLObject obj) {
		Object id = obj.tValueByName("identifier");
		return id == null || id.toString().isBlank() ? null : id.toString().trim();
	}

}
