/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.Type;

/**
 * The form in which the assistant reads and writes rules, schedule entries and application
 * datapoints (haumacher/ehome #128): the JSON of the runtime image (homecontrol
 * <code>spec/rules.md</code> 2, <code>spec/schedules.md</code> 2), with every expression, literal
 * value and log message written as the text of the rule language, as the rule editor shows it
 * (<code>"condition": "@\"1/0/0\" < 5.0"</code>, <code>"default": "3min"</code>, <code>"time":
 * "06:30"</code>, <code>"message": ["Frost: ", "{@\"1/0/0\"}"]</code> as <code>"Frost: {@\"1/0/0\"}"</code>).
 *
 * <p>
 * {@link #toImage(Object, List)} turns that form into the JSON of the image, which
 * <code>RuleImport</code> reads into the model; a text that does not parse is a {@link Syntax}
 * problem with its path and the position of the error. {@link #toText(Object)} turns the JSON of the
 * image (<code>RuleExport</code>) back. A member given in the image's own form (an AST, milliseconds)
 * passes unchanged.
 * </p>
 */
final class AutomationJson {

	/** The members of a rule's parts that hold an expression. */
	private static final Set<String> EXPRESSIONS = Set.of("condition", "value", "duration", "after");

	/** The members of a rule parameter and a choice that hold a value of the parameter's type. */
	private static final Set<String> PARAMETER_VALUES = Set.of("default", "min", "max", "step");

	/** The members of a schedule entry holding a time of day. */
	private static final Set<String> TIMES = Set.of("time", "notBefore", "notAfter", "from", "until");

	/** The members of a schedule entry holding a duration. */
	private static final Set<String> DURATIONS = Set.of("every", "offset");

	/**
	 * A text of the rule language that does not parse.
	 *
	 * @param path
	 *        The JSON path of the text in the definitions (<code>rules[0].reactions[1].condition</code>).
	 * @param text
	 *        The text.
	 * @param position
	 *        The position of the error, 1 for the first character.
	 * @param message
	 *        What is wrong there.
	 */
	record Syntax(String path, String text, int position, String message) {

		/** The problem as the model reads it. */
		Map<String, Object> json() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("path", path);
			result.put("text", text);
			result.put("position", Integer.valueOf(position));
			result.put("error", "syntax error at character " + position + ": " + message);
			return result;
		}
	}

	private AutomationJson() {
		// Static only.
	}

	// ---- text to image -----------------------------------------------------------------------

	/**
	 * The definitions (an object with the members <code>datapoints</code>, <code>schedules</code>,
	 * <code>rules</code>) with their texts parsed into the JSON of the image.
	 *
	 * @param problems
	 *        Where the texts that do not parse are reported; such a text is kept as it is.
	 */
	static Object toImage(Object definitions, List<Syntax> problems) {
		Object copy = copy(definitions);
		if (!(copy instanceof Map<?, ?> root)) {
			return copy;
		}
		int r = 0;
		for (Object rule : list(root.get("rules"))) {
			if (rule instanceof Map<?, ?> map) {
				ruleToImage(map(map), "rules[" + r + "]", problems);
			}
			r++;
		}
		int s = 0;
		for (Object entry : list(root.get("schedules"))) {
			if (entry instanceof Map<?, ?> map) {
				scheduleToImage(map(map), "schedules[" + s + "]", problems);
			}
			s++;
		}
		return copy;
	}

	private static void ruleToImage(Map<String, Object> rule, String path, List<Syntax> problems) {
		int p = 0;
		for (Object item : list(rule.get("parameters"))) {
			if (item instanceof Map<?, ?> parameter) {
				Map<String, Object> map = map(parameter);
				Type type = map.get("type") instanceof String name ? Type.fromName(name) : null;
				String at = path + ".parameters[" + p + "]";
				for (String member : PARAMETER_VALUES) {
					Type valueType = member.equals("step") && type != Type.DURATION ? stepType(map.get(member)) : type;
					valueToImage(map, member, valueType, at, problems);
				}
				int c = 0;
				for (Object choice : list(map.get("choices"))) {
					if (choice instanceof Map<?, ?> choiceMap) {
						valueToImage(map(choiceMap), "value", type, at + ".choices[" + c + "]", problems);
					}
					c++;
				}
			}
			p++;
		}
		partsToImage(rule.get("timers"), path + ".timers", problems);
		int s = 0;
		for (Object item : list(rule.get("states"))) {
			if (item instanceof Map<?, ?> state) {
				Map<String, Object> map = map(state);
				partsToImage(map.get("entry"), path + ".states[" + s + "].entry", problems);
				partsToImage(map.get("exit"), path + ".states[" + s + "].exit", problems);
			}
			s++;
		}
		int n = 0;
		for (Object item : list(rule.get("reactions"))) {
			if (item instanceof Map<?, ?> reaction) {
				Map<String, Object> map = map(reaction);
				String at = path + ".reactions[" + n + "]";
				expressionToImage(map, "condition", at, problems);
				partsToImage(map.get("triggers"), at + ".triggers", problems);
				partsToImage(map.get("actions"), at + ".actions", problems);
				partsToImage(map.get("elseActions"), at + ".elseActions", problems);
			}
			n++;
		}
	}

	/** The type of a step given as a number or literal text of a numeric parameter. */
	private static Type stepType(Object step) {
		if (step instanceof String text && !text.contains(".")) {
			return Type.INTEGER;
		}
		return Type.REAL;
	}

	private static void partsToImage(Object parts, String path, List<Syntax> problems) {
		int i = 0;
		for (Object item : list(parts)) {
			if (item instanceof Map<?, ?> part) {
				Map<String, Object> map = map(part);
				String at = path + "[" + i + "]";
				for (String member : EXPRESSIONS) {
					expressionToImage(map, member, at, problems);
				}
				if (map.get("message") instanceof String message) {
					try {
						map.put("message", RuleText.parseMessage(message));
					} catch (SyntaxError ex) {
						problems.add(new Syntax(at + ".message", message, ex.getOffset() + 1, ex.getMessage()));
					}
				}
			}
			i++;
		}
	}

	private static void expressionToImage(Map<String, Object> map, String member, String path, List<Syntax> problems) {
		if (map.get(member) instanceof String text) {
			try {
				map.put(member, RuleText.parse(text));
			} catch (SyntaxError ex) {
				problems.add(new Syntax(path + "." + member, text, ex.getOffset() + 1, ex.getMessage()));
			}
		}
	}

	private static void valueToImage(Map<String, Object> map, String member, Type type, String path,
			List<Syntax> problems) {
		if (type == null || type == Type.STRING || !(map.get(member) instanceof String text)) {
			return;
		}
		try {
			map.put(member, RuleText.parseValue(text, type));
		} catch (SyntaxError ex) {
			problems.add(new Syntax(path + "." + member, text, ex.getOffset() + 1, ex.getMessage()));
		}
	}

	private static void scheduleToImage(Map<String, Object> entry, String path, List<Syntax> problems) {
		for (String member : TIMES) {
			valueToImage(entry, member, Type.TIME_OF_DAY, path, problems);
		}
		for (String member : DURATIONS) {
			valueToImage(entry, member, Type.DURATION, path, problems);
		}
		valueToImage(entry, "at", Type.DATE_TIME, path, problems);
		if (entry.get("dates") instanceof List<?> dates) {
			List<Object> parsed = new ArrayList<>();
			int d = 0;
			for (Object date : dates) {
				if (date instanceof String text) {
					try {
						parsed.add(RuleText.parseValue(text, Type.DATE));
					} catch (SyntaxError ex) {
						problems.add(new Syntax(path + ".dates[" + d + "]", text, ex.getOffset() + 1, ex.getMessage()));
						parsed.add(text);
					}
				} else {
					parsed.add(date);
				}
				d++;
			}
			entry.put("dates", parsed);
		}
		if (entry.get("bounds") instanceof Map<?, ?> bounds) {
			for (Map.Entry<String, Object> bound : map(bounds).entrySet()) {
				if (!(bound.getValue() instanceof Map<?, ?> range)) {
					continue;
				}
				Map<String, Object> rangeMap = map(range);
				String member = bound.getKey();
				Type type = member.equals("at") ? Type.DATE_TIME : DURATIONS.contains(member) ? Type.DURATION : Type.TIME_OF_DAY;
				String at = path + ".bounds." + member;
				valueToImage(rangeMap, "min", type, at, problems);
				valueToImage(rangeMap, "max", type, at, problems);
				if (rangeMap.get("step") instanceof String text) {
					try {
						Object ms = RuleText.parseValue(text, Type.DURATION);
						// The step of a time is in seconds, of a duration in milliseconds (spec/schedules.md 2.1).
						rangeMap.put("step", type == Type.DURATION ? ms : Long.valueOf(((Number) ms).longValue() / 1000));
					} catch (SyntaxError ex) {
						problems.add(new Syntax(at + ".step", text, ex.getOffset() + 1, ex.getMessage()));
					}
				}
			}
		}
	}

	// ---- image to text -----------------------------------------------------------------------

	/**
	 * A rule of the image (<code>RuleExport</code>) with its expressions, values and messages as text.
	 */
	static Map<String, Object> ruleToText(Object rule) {
		Map<String, Object> result = map((Map<?, ?>) copy(rule));
		for (Object item : list(result.get("parameters"))) {
			Map<String, Object> parameter = map((Map<?, ?>) item);
			Type type = parameter.get("type") instanceof String name ? Type.fromName(name) : null;
			for (String member : PARAMETER_VALUES) {
				Type valueType = member.equals("step") && type != Type.DURATION
					? (parameter.get(member) instanceof Double ? Type.REAL : Type.INTEGER) : type;
				valueToText(parameter, member, valueType);
			}
			for (Object choice : list(parameter.get("choices"))) {
				valueToText(map((Map<?, ?>) choice), "value", type);
			}
		}
		partsToText(result.get("timers"));
		for (Object item : list(result.get("states"))) {
			Map<String, Object> state = map((Map<?, ?>) item);
			partsToText(state.get("entry"));
			partsToText(state.get("exit"));
		}
		for (Object item : list(result.get("reactions"))) {
			Map<String, Object> reaction = map((Map<?, ?>) item);
			expressionToText(reaction, "condition");
			partsToText(reaction.get("triggers"));
			partsToText(reaction.get("actions"));
			partsToText(reaction.get("elseActions"));
		}
		return result;
	}

	/**
	 * A schedule entry of the image with its times, durations and dates as text.
	 */
	static Map<String, Object> scheduleToText(Object entry) {
		Map<String, Object> result = map((Map<?, ?>) copy(entry));
		for (String member : TIMES) {
			valueToText(result, member, Type.TIME_OF_DAY);
		}
		for (String member : DURATIONS) {
			valueToText(result, member, Type.DURATION);
		}
		valueToText(result, "at", Type.DATE_TIME);
		if (result.get("dates") instanceof List<?> dates) {
			List<Object> texts = new ArrayList<>();
			for (Object date : dates) {
				texts.add(date instanceof Map<?, ?> ? RuleText.printValue(date, Type.DATE) : date);
			}
			result.put("dates", texts);
		}
		if (result.get("bounds") instanceof Map<?, ?> bounds) {
			for (Map.Entry<String, Object> bound : map(bounds).entrySet()) {
				if (bound.getValue() instanceof Map<?, ?> range) {
					String member = bound.getKey();
					Type type = member.equals("at") ? Type.DATE_TIME : DURATIONS.contains(member) ? Type.DURATION
						: Type.TIME_OF_DAY;
					Map<String, Object> rangeMap = map(range);
					valueToText(rangeMap, "min", type);
					valueToText(rangeMap, "max", type);
					if (rangeMap.get("step") instanceof Number step) {
						long ms = type == Type.DURATION ? step.longValue() : step.longValue() * 1000;
						rangeMap.put("step", RuleText.printValue(Long.valueOf(ms), Type.DURATION));
					}
				}
			}
		}
		return result;
	}

	private static void partsToText(Object parts) {
		for (Object item : list(parts)) {
			if (item instanceof Map<?, ?> part) {
				Map<String, Object> map = map(part);
				for (String member : EXPRESSIONS) {
					expressionToText(map, member);
				}
				if (map.get("message") instanceof List<?> message) {
					map.put("message", RuleText.printMessage(message));
				}
			}
		}
	}

	private static void expressionToText(Map<String, Object> map, String member) {
		if (map.get(member) instanceof Map<?, ?> node) {
			map.put(member, RuleText.print(RuleText.normalize(node)));
		}
	}

	private static void valueToText(Map<String, Object> map, String member, Type type) {
		Object value = map.get(member);
		if (type == null || type == Type.STRING || value == null || value instanceof String) {
			return;
		}
		map.put(member, RuleText.printValue(value, type));
	}

	// ---- helpers -----------------------------------------------------------------------------

	/** A deep copy whose objects and arrays can be changed. */
	static Object copy(Object json) {
		if (json instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			map.forEach((key, value) -> result.put(String.valueOf(key), copy(value)));
			return result;
		}
		if (json instanceof List<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object item : list) {
				result.add(copy(item));
			}
			return result;
		}
		return json;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	static List<?> list(Object json) {
		return json instanceof List<?> list ? list : List.of();
	}

}
