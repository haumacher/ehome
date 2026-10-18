/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLFormObjectBase;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleLoader.LoadException;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Type;

/**
 * The rules and schedule entries of a project (<code>ehome.runtime:Rule</code>,
 * <code>ehome.runtime:ScheduleEntry</code>, haumacher/ehome #57) as the members <code>rules</code> and
 * <code>schedules</code> of the runtime image (homecontrol <code>spec/rules.md</code> 2,
 * <code>spec/schedules.md</code> 2).
 *
 * <p>
 * The expressions and literal values the model stores as text are parsed into the JSON of the image
 * ({@link RuleText}); a text that does not parse is a {@link #getProblems() problem} with its path,
 * and the image carries the text itself at that path. Members at their default are left out. Every
 * path written is recorded with the object and attribute it stems from ({@link #sourceAt(String)}),
 * so that a refusal of the image is shown at the field the user edits.
 * </p>
 *
 * <p>
 * The check of a single field while it is edited ({@link #fieldProblem(Object, String, Object, boolean)})
 * exports a <i>focused</i> image: the rule of the field with the field's object and its containers in
 * full and everything else reduced to what the field needs (the ids and types of the declarations,
 * the ids and states of the other rules), so that a problem elsewhere does not hide the field's own.
 * </p>
 */
public final class RuleExport {

	/**
	 * The object and attribute a path of the image stems from; the attribute is <code>null</code>
	 * for the object as a whole.
	 */
	public record Source(TLObject object, String attribute) {
		// A record.
	}

	/**
	 * A text that does not parse: its path, the reason, and the field.
	 */
	public record Problem(String path, String reason, Source source) {
		// A record.
	}

	private final Map<String, Source> _sources = new HashMap<>();

	private final List<Problem> _problems = new ArrayList<>();

	/** The object of the checked field (the stored one), <code>null</code> for a full export. */
	private TLObject _target;

	/** The values entered in the form for {@link #_target}, <code>null</code> for the stored ones. */
	private TLObject _overlay;

	private String _attribute;

	private Object _value;

	/** {@link #_target} and its containers. */
	private Set<TLObject> _chain;

	/**
	 * Creates a {@link RuleExport} for the whole project.
	 */
	public RuleExport() {
		// Full export.
	}

	/**
	 * The problems found: texts that do not parse.
	 */
	public List<Problem> getProblems() {
		return _problems;
	}

	/**
	 * The object and attribute the given path of the image stems from: the one of the longest
	 * written path the given path starts with; <code>null</code> outside the rules and schedules.
	 */
	public Source sourceAt(String path) {
		if (path == null) {
			return null;
		}
		String current = path;
		while (!current.isEmpty()) {
			Source source = _sources.get(current);
			if (source != null) {
				return source.attribute() == null ? new Source(source.object(), primary(source.object())) : source;
			}
			int cut = Math.max(current.lastIndexOf('.'), current.lastIndexOf('['));
			if (cut <= 0) {
				return null;
			}
			current = current.substring(0, cut);
		}
		return null;
	}

	/**
	 * The object and attribute a refusal with the given path and text stems from: as
	 * {@link #sourceAt(String)}, but a missing member of an object (<code>member 'value'
	 * missing</code>) is shown at the field of that member.
	 */
	public Source sourceAt(String path, String text) {
		Source source = sourceAt(path);
		Matcher missing = MISSING.matcher(text == null ? "" : text);
		if (source != null && _sources.get(path) != null && _sources.get(path).attribute() == null && missing.matches()) {
			String member = missing.group(1);
			return new Source(source.object(), member.equals("id") ? "identifier" : member);
		}
		return source;
	}

	private static final Pattern MISSING = Pattern.compile("member '([A-Za-z]+)' missing.*");

	/**
	 * The field a refusal of the object as a whole is shown at.
	 */
	private static String primary(TLObject obj) {
		switch (obj.tType().getName()) {
			case "RuleParameter":
				return "default";
			case "RuleTimer":
				return "duration";
			case "Reaction":
				return "condition";
			case "DatapointTrigger":
			case "WriteAction":
				return "datapoint";
			case "MatchTrigger":
				return "condition";
			case "ScheduleTrigger":
				return "schedule";
			case "TimerTrigger":
			case "StartTimerAction":
			case "RestartTimerAction":
			case "CancelTimerAction":
				return "timer";
			case "StartTrigger":
				return "onBoot";
			case "ParameterTrigger":
			case "SetParameterAction":
				return "parameter";
			case "LogAction":
				return "message";
			default:
				return "identifier";
		}
	}

	// ---- the project -------------------------------------------------------------------------

	/**
	 * The rules of the project (in their order, <code>Rule#order</code>, then their position) as the
	 * member <code>rules</code>; <code>null</code> for none.
	 */
	public List<Object> rules(TLObject project) {
		List<TLObject> rules = orderedRules(project);
		if (rules.isEmpty()) {
			return null;
		}
		List<Object> result = new ArrayList<>();
		for (TLObject rule : rules) {
			result.add(rule(rule, "rules[" + result.size() + "]"));
		}
		return result;
	}

	/**
	 * The rules of the project in the rule order: by <code>order</code> (rules without one last),
	 * then by their position.
	 */
	public static List<TLObject> orderedRules(TLObject project) {
		List<TLObject> rules = objects(project.tValueByName("rules"));
		rules.sort(Comparator.comparingLong(r -> r.tValueByName("order") instanceof Number n ? n.longValue() : Long.MAX_VALUE));
		return rules;
	}

	/**
	 * The schedule entries of the project as the member <code>schedules</code>; <code>null</code>
	 * for none.
	 */
	public List<Object> schedules(TLObject project) {
		List<TLObject> entries = objects(project.tValueByName("schedules"));
		if (entries.isEmpty()) {
			return null;
		}
		List<Object> result = new ArrayList<>();
		for (TLObject entry : entries) {
			result.add(schedule(entry, "schedules[" + result.size() + "]"));
		}
		return result;
	}

	// ---- reading -----------------------------------------------------------------------------

	private Object get(TLObject obj, String attribute) {
		if (obj == _target) {
			if (attribute.equals(_attribute)) {
				return _value;
			}
			if (_overlay != null) {
				return _overlay.tValueByName(attribute);
			}
		}
		return obj.tValueByName(attribute);
	}

	private List<TLObject> list(TLObject obj, String attribute) {
		return objects(get(obj, attribute));
	}

	static List<TLObject> objects(Object value) {
		List<TLObject> result = new ArrayList<>();
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				if (element instanceof TLObject obj && obj.tValid()) {
					result.add(obj);
				}
			}
		} else if (value instanceof TLObject obj) {
			result.add(obj);
		}
		return result;
	}

	private String text(TLObject obj, String attribute) {
		return text(get(obj, attribute));
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String text = value instanceof ResKey key ? oneLanguage(key) : value.toString();
		return text == null || text.isBlank() ? null : text.trim();
	}

	private boolean flag(TLObject obj, String attribute, boolean fallback) {
		Object value = get(obj, attribute);
		return value instanceof Boolean b ? b.booleanValue() : fallback;
	}

	private String classifier(TLObject obj, String attribute, String fallback) {
		return get(obj, attribute) instanceof TLClassifier c ? c.getName() : fallback;
	}

	/**
	 * The text of a multilingual name in one language: the first application language it has.
	 */
	public static String oneLanguage(ResKey key) {
		if (key instanceof LiteralKey literal) {
			Map<Locale, String> translations = literal.getTranslations();
			for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
				String text = translations.get(locale);
				if (text == null) {
					text = translations.get(new Locale(locale.getLanguage()));
				}
				if (text != null && !text.isBlank()) {
					return text;
				}
			}
			for (String text : translations.values()) {
				if (text != null && !text.isBlank()) {
					return text;
				}
			}
			return null;
		}
		return com.top_logic.util.Resources.getInstance().getString(key, null);
	}

	private void source(String path, TLObject obj, String attribute) {
		_sources.put(path, new Source(obj, attribute));
	}

	private boolean full(TLObject obj) {
		return _chain == null || _chain.contains(obj);
	}

	// ---- parsing -----------------------------------------------------------------------------

	private Object expression(TLObject obj, String attribute, String path) {
		source(path, obj, attribute);
		String text = text(obj, attribute);
		if (text == null) {
			return null;
		}
		try {
			return RuleText.parse(text);
		} catch (SyntaxError ex) {
			problem(path, obj, attribute, ex);
			return text;
		}
	}

	private Object value(TLObject obj, String attribute, String path, Type type) {
		source(path, obj, attribute);
		String text = text(obj, attribute);
		if (text == null) {
			return null;
		}
		try {
			return RuleText.parseValue(text, type);
		} catch (SyntaxError ex) {
			problem(path, obj, attribute, ex);
			return text;
		}
	}

	private void problem(String path, TLObject obj, String attribute, SyntaxError ex) {
		_problems.add(new Problem(path, "malformed: " + ex.describe(), new Source(obj, attribute)));
	}

	// ---- rules -------------------------------------------------------------------------------

	private static final Map<String, Object> STUB_REACTION =
		Map.of("triggers", List.of(Map.of("kind", "start")), "actions", List.of());

	private Map<String, Object> rule(TLObject rule, String path) {
		source(path, rule, null);
		Map<String, Object> result = new LinkedHashMap<>();
		source(path + ".id", rule, "identifier");
		result.put("id", orEmpty(text(rule, "identifier")));
		source(path + ".name", rule, "name");
		result.put("name", orEmpty(text(rule, "name")));
		String description = text(rule, "description");
		if (description != null && _chain == null) {
			result.put("description", description);
		}
		if (!flag(rule, "enabled", true)) {
			result.put("enabled", Boolean.FALSE);
		}
		if (!flag(rule, "enabledAdjustable", true)) {
			result.put("enabledAdjustable", Boolean.FALSE);
		}

		List<Object> parameters = new ArrayList<>();
		for (TLObject parameter : list(rule, "parameters")) {
			String itemPath = path + ".parameters[" + parameters.size() + "]";
			parameters.add(full(parameter) ? parameter(parameter, itemPath) : parameterStub(parameter));
		}
		if (!parameters.isEmpty()) {
			result.put("parameters", parameters);
		}

		List<Object> timers = new ArrayList<>();
		for (TLObject timer : list(rule, "timers")) {
			String itemPath = path + ".timers[" + timers.size() + "]";
			timers.add(full(timer) ? timer(timer, itemPath) : timerStub(timer));
		}
		if (!timers.isEmpty()) {
			result.put("timers", timers);
		}

		List<Object> states = new ArrayList<>();
		for (TLObject state : list(rule, "states")) {
			states.add(state(state, path + ".states[" + states.size() + "]"));
		}
		if (!states.isEmpty()) {
			result.put("states", states);
		}
		source(path + ".initialState", rule, "initialState");
		if (get(rule, "initialState") instanceof TLObject initial) {
			result.put("initialState", orEmpty(text(initial, "identifier")));
		}

		List<Object> reactions = new ArrayList<>();
		source(path + ".reactions", rule, "identifier");
		List<TLObject> all = list(rule, "reactions");
		for (TLObject reaction : all) {
			if (_chain == null || _chain.contains(reaction)) {
				reactions.add(reaction(reaction, path + ".reactions[" + reactions.size() + "]"));
			}
		}
		if (reactions.isEmpty() && !all.isEmpty()) {
			reactions.add(STUB_REACTION);
		}
		result.put("reactions", reactions);
		return result;
	}

	private static String orEmpty(String text) {
		return text == null ? "" : text;
	}

	private Map<String, Object> parameter(TLObject parameter, String path) {
		source(path, parameter, null);
		Map<String, Object> result = new LinkedHashMap<>();
		source(path + ".id", parameter, "identifier");
		result.put("id", orEmpty(text(parameter, "identifier")));
		source(path + ".label", parameter, "label");
		result.put("label", orEmpty(text(parameter, "label")));
		String typeName = classifier(parameter, "type", "real");
		source(path + ".type", parameter, "type");
		result.put("type", typeName);
		Type type = Type.fromName(typeName);
		Object value = value(parameter, "default", path + ".default", type);
		if (value != null) {
			result.put("default", value);
		}
		for (String bound : new String[] { "min", "max" }) {
			Object limit = value(parameter, bound, path + "." + bound, type);
			if (limit != null) {
				result.put(bound, limit);
			}
		}
		source(path + ".step", parameter, "step");
		String step = text(parameter, "step");
		if (step != null) {
			result.put("step", step(parameter, step, type, path + ".step"));
		}
		source(path + ".unit", parameter, "unit");
		String unit = text(parameter, "unit");
		if (unit != null) {
			result.put("unit", unit);
		}
		source(path + ".choices", parameter, "choices");
		String choices = text(parameter, "choices");
		if (choices != null) {
			try {
				result.put("choices", choices(choices, type));
			} catch (SyntaxError ex) {
				problem(path + ".choices", parameter, "choices", ex);
				result.put("choices", choices);
			}
		}
		if (!flag(parameter, "adjustable", true)) {
			result.put("adjustable", Boolean.FALSE);
		}
		return result;
	}

	private Object step(TLObject parameter, String text, Type type, String path) {
		try {
			if (type == Type.DURATION) {
				return RuleText.parseValue(text, Type.DURATION);
			}
			Map<String, Object> node = RuleText.parse(text);
			if ("literal".equals(node.get("kind")) && node.get("value") instanceof Number number) {
				return number;
			}
			throw new SyntaxError("a number expected", 0);
		} catch (SyntaxError ex) {
			problem(path, parameter, "step", ex);
			return text;
		}
	}

	/**
	 * Reads the choices of a parameter: one per line, the value as a literal, <code>=</code>, the
	 * label.
	 */
	public static List<Object> choices(String text, Type type) throws SyntaxError {
		List<Object> result = new ArrayList<>();
		int offset = 0;
		for (String line : text.split("\n", -1)) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				int separator = separator(trimmed);
				if (separator < 0) {
					throw new SyntaxError("a choice is written as: value = label", offset);
				}
				Object value;
				try {
					value = RuleText.parseValue(trimmed.substring(0, separator).trim(), type);
				} catch (SyntaxError ex) {
					throw new SyntaxError(ex.getMessage(), offset + ex.getOffset());
				}
				Map<String, Object> choice = new LinkedHashMap<>();
				choice.put("value", value);
				choice.put("label", trimmed.substring(separator + 1).trim());
				result.add(choice);
			}
			offset += line.length() + 1;
		}
		return result;
	}

	/** The position of the "=" after the value of a choice (a string value may contain one). */
	private static int separator(String line) {
		int pos = 0;
		if (line.startsWith("\"")) {
			pos = 1;
			while (pos < line.length() && line.charAt(pos) != '"') {
				pos += line.charAt(pos) == '\\' ? 2 : 1;
			}
		}
		return line.indexOf('=', pos);
	}

	/**
	 * The text of the choices of a parameter (see {@link #choices(String, Type)}).
	 */
	public static String choicesText(List<?> choices, Type type) {
		StringBuilder out = new StringBuilder();
		for (Object item : choices) {
			if (item instanceof Map<?, ?> choice) {
				if (out.length() > 0) {
					out.append('\n');
				}
				out.append(RuleText.printValue(choice.get("value"), type)).append(" = ").append(choice.get("label"));
			}
		}
		return out.toString();
	}

	private Map<String, Object> parameterStub(TLObject parameter) {
		String typeName = classifier(parameter, "type", "real");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", orEmpty(text(parameter, "identifier")));
		result.put("label", "x");
		result.put("type", typeName);
		result.put("default", stubValue(typeName));
		return result;
	}

	private static Object stubValue(String type) {
		switch (type) {
			case "boolean":
				return Boolean.FALSE;
			case "string":
				return "";
			case "timeOfDay":
				return Map.of("hour", Long.valueOf(0), "minute", Long.valueOf(0), "second", Long.valueOf(0));
			case "color":
				return Map.of("red", Long.valueOf(0), "green", Long.valueOf(0), "blue", Long.valueOf(0));
			default:
				return Long.valueOf(0);
		}
	}

	private Map<String, Object> timer(TLObject timer, String path) {
		source(path, timer, null);
		Map<String, Object> result = new LinkedHashMap<>();
		source(path + ".id", timer, "identifier");
		result.put("id", orEmpty(text(timer, "identifier")));
		Object duration = expression(timer, "duration", path + ".duration");
		if (duration != null) {
			result.put("duration", duration);
		}
		return result;
	}

	private Map<String, Object> timerStub(TLObject timer) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", orEmpty(text(timer, "identifier")));
		result.put("duration", RuleText.literal("duration", Long.valueOf(100)));
		return result;
	}

	private Map<String, Object> state(TLObject state, String path) {
		source(path, state, null);
		Map<String, Object> result = new LinkedHashMap<>();
		source(path + ".id", state, "identifier");
		result.put("id", orEmpty(text(state, "identifier")));
		for (String member : new String[] { "entry", "exit" }) {
			List<Object> actions = actions(state, member, path + "." + member);
			if (!actions.isEmpty()) {
				result.put(member, actions);
			}
		}
		return result;
	}

	private List<Object> actions(TLObject owner, String member, String path) {
		List<Object> result = new ArrayList<>();
		for (TLObject action : list(owner, member)) {
			if (_chain == null || _chain.contains(action)) {
				result.add(action(action, path + "[" + result.size() + "]"));
			}
		}
		return result;
	}

	private Map<String, Object> reaction(TLObject reaction, String path) {
		source(path, reaction, null);
		boolean whole = _chain == null || reaction == _target;
		Map<String, Object> result = new LinkedHashMap<>();
		List<Object> triggers = new ArrayList<>();
		List<TLObject> allTriggers = list(reaction, "triggers");
		for (TLObject trigger : allTriggers) {
			if (_chain == null || _chain.contains(trigger)) {
				triggers.add(trigger(trigger, path + ".triggers[" + triggers.size() + "]"));
			}
		}
		if (triggers.isEmpty() && !allTriggers.isEmpty()) {
			triggers.add(Map.of("kind", "start"));
		}
		source(path + ".triggers", reaction, "condition");
		result.put("triggers", triggers);
		if (whole) {
			Object condition = expression(reaction, "condition", path + ".condition");
			if (condition != null) {
				result.put("condition", condition);
			}
		}
		result.put("actions", actions(reaction, "actions", path + ".actions"));
		List<Object> elseActions = actions(reaction, "elseActions", path + ".elseActions");
		if (!elseActions.isEmpty() || (whole && _chain != null && !list(reaction, "elseActions").isEmpty())) {
			result.put("elseActions", elseActions);
		}
		if (whole) {
			source(path + ".from", reaction, "from");
			List<Object> from = new ArrayList<>();
			for (TLObject state : list(reaction, "from")) {
				from.add(orEmpty(text(state, "identifier")));
			}
			if (!from.isEmpty()) {
				result.put("from", from);
			}
			source(path + ".to", reaction, "to");
			if (get(reaction, "to") instanceof TLObject to) {
				result.put("to", orEmpty(text(to, "identifier")));
			}
		}
		return result;
	}

	/**
	 * The kind of a trigger, an action or a schedule entry in the image, by its type.
	 */
	public static String kind(TLObject obj) {
		switch (obj.tType().getName()) {
			case "DatapointTrigger":
				return "datapoint";
			case "MatchTrigger":
				return "match";
			case "ScheduleTrigger":
				return "schedule";
			case "TimerTrigger":
				return "timer";
			case "StartTrigger":
				return "start";
			case "ParameterTrigger":
				return "parameter";
			case "WriteAction":
				return "write";
			case "StartTimerAction":
				return "startTimer";
			case "RestartTimerAction":
				return "restartTimer";
			case "CancelTimerAction":
				return "cancelTimer";
			case "SetParameterAction":
				return "setParameter";
			case "LogAction":
				return "log";
			case "WeeklySchedule":
				return "weekly";
			case "YearlySchedule":
				return "yearly";
			case "DatesSchedule":
				return "dates";
			case "AstroSchedule":
				return "astro";
			case "IntervalSchedule":
				return "interval";
			case "OnceSchedule":
				return "once";
			default:
				return obj.tType().getName();
		}
	}

	private void id(Map<String, Object> result, TLObject obj, String member, String attribute, String path) {
		source(path + "." + member, obj, attribute);
		result.put(member, orEmpty(text(obj, attribute)));
	}

	private Map<String, Object> trigger(TLObject trigger, String path) {
		source(path, trigger, null);
		Map<String, Object> result = new LinkedHashMap<>();
		String kind = kind(trigger);
		result.put("kind", kind);
		switch (kind) {
			case "datapoint": {
				id(result, trigger, "datapoint", "datapoint", path);
				String on = classifier(trigger, "on", "change");
				if (!on.equals("change")) {
					result.put("on", on);
				}
				String origin = classifier(trigger, "origin", "any");
				if (!origin.equals("any")) {
					result.put("origin", origin);
				}
				break;
			}
			case "match": {
				Object condition = expression(trigger, "condition", path + ".condition");
				if (condition != null) {
					result.put("condition", condition);
				}
				break;
			}
			case "schedule":
				id(result, trigger, "schedule", "schedule", path);
				break;
			case "timer":
				id(result, trigger, "timer", "timer", path);
				break;
			case "parameter":
				id(result, trigger, "parameter", "parameter", path);
				break;
			case "start": {
				List<Object> reasons = new ArrayList<>();
				String[][] flags = { { "onBoot", "boot" }, { "onImage", "image" }, { "onEnable", "enable" } };
				for (String[] flag : flags) {
					if (flag(trigger, flag[0], true)) {
						reasons.add(flag[1]);
					}
				}
				source(path + ".reasons", trigger, "onBoot");
				if (reasons.size() < flags.length) {
					result.put("reasons", reasons);
				}
				break;
			}
			default:
				break;
		}
		return result;
	}

	private Map<String, Object> action(TLObject action, String path) {
		source(path, action, null);
		Map<String, Object> result = new LinkedHashMap<>();
		String kind = kind(action);
		result.put("kind", kind);
		switch (kind) {
			case "write": {
				id(result, action, "datapoint", "datapoint", path);
				Object value = expression(action, "value", path + ".value");
				if (value != null) {
					result.put("value", value);
				}
				if (flag(action, "onlyOnChange", false)) {
					result.put("onlyOnChange", Boolean.TRUE);
				}
				break;
			}
			case "startTimer":
			case "restartTimer": {
				id(result, action, "timer", "timer", path);
				Object duration = expression(action, "duration", path + ".duration");
				if (duration != null) {
					result.put("duration", duration);
				}
				break;
			}
			case "cancelTimer":
				id(result, action, "timer", "timer", path);
				break;
			case "setParameter": {
				id(result, action, "parameter", "parameter", path);
				Object value = expression(action, "value", path + ".value");
				if (value != null) {
					result.put("value", value);
				}
				break;
			}
			case "log": {
				result.put("level", classifier(action, "level", "info"));
				source(path + ".message", action, "message");
				String text = text(action, "message");
				List<Object> message = List.of();
				if (text != null) {
					try {
						message = RuleText.parseMessage(text);
					} catch (SyntaxError ex) {
						problem(path + ".message", action, "message", ex);
						message = List.of(text);
					}
				}
				result.put("message", message);
				break;
			}
			default:
				break;
		}
		return result;
	}

	// ---- schedules ---------------------------------------------------------------------------

	private static final String[] WEEKDAYS =
		{ "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday" };

	private Map<String, Object> schedule(TLObject entry, String path) {
		source(path, entry, null);
		Map<String, Object> result = new LinkedHashMap<>();
		id(result, entry, "id", "identifier", path);
		id(result, entry, "label", "label", path);
		String kind = kind(entry);
		result.put("kind", kind);
		if (!flag(entry, "enabled", true)) {
			result.put("enabled", Boolean.FALSE);
		}
		if (!flag(entry, "enabledAdjustable", true)) {
			result.put("enabledAdjustable", Boolean.FALSE);
		}
		String missed = classifier(entry, "missed", "skip");
		if (!missed.equals("skip")) {
			result.put("missed", missed);
		}
		source(path + ".output", entry, "output");
		String output = text(entry, "output");
		if (output != null) {
			result.put("output", output);
		}
		switch (kind) {
			case "weekly":
				days(result, entry, path);
				put(result, "time", value(entry, "time", path + ".time", Type.TIME_OF_DAY));
				break;
			case "yearly":
				source(path + ".month", entry, "month");
				result.put("month", get(entry, "month") instanceof Number m ? Long.valueOf(m.longValue()) : Long.valueOf(0));
				source(path + ".day", entry, "day");
				result.put("day", get(entry, "day") instanceof Number d ? Long.valueOf(d.longValue()) : Long.valueOf(0));
				put(result, "time", value(entry, "time", path + ".time", Type.TIME_OF_DAY));
				break;
			case "dates":
				put(result, "dates", dates(entry, path + ".dates"));
				put(result, "time", value(entry, "time", path + ".time", Type.TIME_OF_DAY));
				break;
			case "astro":
				source(path + ".sun", entry, "sun");
				result.put("sun", classifier(entry, "sun", "sunset"));
				put(result, "offset", value(entry, "offset", path + ".offset", Type.DURATION));
				put(result, "notBefore", value(entry, "notBefore", path + ".notBefore", Type.TIME_OF_DAY));
				put(result, "notAfter", value(entry, "notAfter", path + ".notAfter", Type.TIME_OF_DAY));
				days(result, entry, path);
				break;
			case "interval":
				days(result, entry, path);
				put(result, "every", value(entry, "every", path + ".every", Type.DURATION));
				put(result, "from", value(entry, "from", path + ".from", Type.TIME_OF_DAY));
				put(result, "until", value(entry, "until", path + ".until", Type.TIME_OF_DAY));
				break;
			case "once":
				put(result, "at", value(entry, "at", path + ".at", Type.DATE_TIME));
				break;
			default:
				break;
		}
		return result;
	}

	private static void put(Map<String, Object> result, String member, Object value) {
		if (value != null) {
			result.put(member, value);
		}
	}

	private void days(Map<String, Object> result, TLObject entry, String path) {
		source(path + ".days", entry, "days");
		Set<Integer> days = new java.util.TreeSet<>();
		for (Object day : objects(get(entry, "days"))) {
			if (day instanceof TLClassifier classifier) {
				for (int i = 0; i < WEEKDAYS.length; i++) {
					if (WEEKDAYS[i].equals(classifier.getName())) {
						days.add(Integer.valueOf(i + 1));
					}
				}
			}
		}
		if (!days.isEmpty() && days.size() < WEEKDAYS.length) {
			List<Object> list = new ArrayList<>();
			for (Integer day : days) {
				list.add(Long.valueOf(day.longValue()));
			}
			result.put("days", list);
		}
	}

	/**
	 * The classifier names of the days 1 (Monday) .. 7 (Sunday).
	 */
	static String weekday(int day) {
		return WEEKDAYS[day - 1];
	}

	private Object dates(TLObject entry, String path) {
		source(path, entry, "dates");
		String text = text(entry, "dates");
		if (text == null) {
			return null;
		}
		List<Object> result = new ArrayList<>();
		int offset = 0;
		for (String part : text.split("[,;\\s]", -1)) {
			if (!part.isEmpty()) {
				try {
					result.add(RuleText.parseValue(part, Type.DATE));
				} catch (SyntaxError ex) {
					problem(path, entry, "dates", new SyntaxError(ex.getMessage(), offset + ex.getOffset()));
					return text;
				}
			}
			offset += part.length() + 1;
		}
		return result;
	}

	// ---- the check of a field ----------------------------------------------------------------

	/**
	 * Why the value of a field of a rule or schedule entry is refused, as the controller would refuse
	 * it, <code>null</code> if it is not.
	 *
	 * @param obj
	 *        The object the field belongs to: a rule, a part of a rule, a schedule entry; in a form the
	 *        form's object, whose entered values are read.
	 * @param attribute
	 *        The name of the field's attribute.
	 * @param value
	 *        The value of the field.
	 * @param syntax
	 *        <code>true</code> for the syntax of the field's text, <code>false</code> for what the
	 *        loader refuses (not reported for a text that does not parse).
	 */
	public static String fieldProblem(Object obj, String attribute, Object value, boolean syntax) {
		TLObject stored;
		TLObject overlay = null;
		if (obj instanceof TLFormObjectBase form) {
			stored = form.getEditedObject();
			overlay = form;
		} else if (obj instanceof TLObject object) {
			stored = object;
		} else {
			return null;
		}
		if (stored == null || !stored.tValid()) {
			return null;
		}
		TLObject project = RuntimeFunctions.project(stored);
		if (project == null) {
			return null;
		}
		RuleExport export = new RuleExport();
		export._target = stored;
		export._overlay = overlay;
		export._attribute = attribute;
		export._value = value;
		export._chain = new HashSet<>();
		TLObject rule = null;
		TLObject entry = null;
		for (TLObject node = stored; node != null; node = node.tContainer()) {
			export._chain.add(node);
			String type = node.tType().getName();
			if (type.equals("Rule")) {
				rule = node;
			} else if (node.tType().getModule().getName().equals("ehome.runtime") && type.endsWith("Schedule")) {
				entry = node;
			}
		}
		Source field = new Source(stored, attribute);
		if (rule != null) {
			return export.checkRule(project, rule, field, syntax);
		}
		if (entry != null) {
			return export.checkSchedules(project, field, syntax);
		}
		return null;
	}

	private String syntaxProblem(Source field) {
		for (Problem problem : _problems) {
			if (problem.source().equals(field)) {
				return problem.reason();
			}
		}
		return null;
	}

	private String checkRule(TLObject project, TLObject rule, Source field, boolean syntax) {
		List<Object> rules = new ArrayList<>();
		String ruleId = text(rule, "identifier");
		Set<String> ids = new HashSet<>();
		for (TLObject other : objects(project.tValueByName("rules"))) {
			String id = text(other, "identifier");
			if (other != rule && id != null && RuleLoader.validRuleId(id) && ids.add(id)) {
				rules.add(ruleStub(other, id));
			}
		}
		Map<String, Object> focused = rule(rule, "rules[" + rules.size() + "]");
		rules.add(focused);
		String problem = syntaxProblem(field);
		if (syntax || problem != null) {
			return syntax ? problem : null;
		}
		if (ruleId == null) {
			return null;
		}
		ImageValidator.Directory directory = directory(project);
		Set<String> schedules = new HashSet<>();
		for (TLObject entry : objects(project.tValueByName("schedules"))) {
			String id = text(entry, "identifier");
			if (id != null) {
				schedules.add(id);
			}
		}
		try {
			RuleLoader.load(rules, directory, schedules::contains);
			return null;
		} catch (LoadException ex) {
			RuleLoader.Refusal refusal = ex.getRefusal();
			if (field.equals(sourceAt(refusal.path(), refusal.text()))) {
				return refusal.error().externalName() + ": " + refusal.text();
			}
			return null;
		}
	}

	private Map<String, Object> ruleStub(TLObject rule, String id) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("name", "x");
		List<Object> states = new ArrayList<>();
		Set<String> stateIds = new HashSet<>();
		for (TLObject state : objects(rule.tValueByName("states"))) {
			String stateId = text(state, "identifier");
			if (stateId != null && RuleLoader.validIdentifier(stateId) && stateIds.add(stateId)) {
				states.add(Map.of("id", stateId));
			}
		}
		if (!states.isEmpty()) {
			result.put("states", states);
			result.put("initialState", ((Map<?, ?>) states.get(0)).get("id"));
		}
		result.put("reactions", List.of(STUB_REACTION));
		return result;
	}

	private String checkSchedules(TLObject project, Source field, boolean syntax) {
		List<Object> schedules = schedules(project);
		String problem = syntaxProblem(field);
		if (syntax || problem != null) {
			return syntax ? problem : null;
		}
		Schedule.Loaded loaded = Schedule.load(schedules == null ? List.of() : schedules, directory(project));
		for (Schedule.Refusal refusal : loaded.refusals()) {
			if (field.equals(sourceAt(refusal.path()))) {
				return refusal.reason();
			}
		}
		return null;
	}

	/**
	 * The datapoints of the project's image as the loaders see them.
	 */
	static ImageValidator.Directory directory(TLObject project) {
		return ImageValidator.directory(RuntimeImage.build(project, 0, Instant.EPOCH, false).getText());
	}

}
