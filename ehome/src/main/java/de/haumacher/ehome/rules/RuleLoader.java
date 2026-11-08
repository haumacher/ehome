/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.rules.Node.Fn;
import de.haumacher.ehome.rules.Node.Kind;
import de.haumacher.ehome.rules.Node.Op;
import de.haumacher.ehome.rules.RuleSet.Action;
import de.haumacher.ehome.rules.RuleSet.ActionKind;
import de.haumacher.ehome.rules.RuleSet.Level;
import de.haumacher.ehome.rules.RuleSet.Parameter;
import de.haumacher.ehome.rules.RuleSet.Reaction;
import de.haumacher.ehome.rules.RuleSet.Rule;
import de.haumacher.ehome.rules.RuleSet.StartReason;
import de.haumacher.ehome.rules.RuleSet.State;
import de.haumacher.ehome.rules.RuleSet.Timer;
import de.haumacher.ehome.rules.RuleSet.Trigger;
import de.haumacher.ehome.rules.RuleSet.TriggerKind;

/**
 * The rule loader: validates the <code>rules</code> member of an image (<code>spec/rules.md</code>
 * 10) against the datapoints and schedules of the image and compiles it into a {@link RuleSet}.
 *
 * <p>
 * A port of homecontrol <code>core/src/rules/loader.cpp</code>: the checks run in the same order,
 * so that the first failure, its reason, its JSON path and its rule are those the controller
 * reports.
 * </p>
 */
public final class RuleLoader {

	/**
	 * The load refusal reasons of <code>spec/rules.md</code> 10.1.
	 */
	public enum LoadError {
		/** Wrong JSON type, missing or unknown member, unknown kind, function or operator. */
		MALFORMED("malformed"),
		/** Two rules, or two parameters, timers or states of one rule, with the same id. */
		DUPLICATE_ID("duplicateId"),
		/** A datapoint, schedule, parameter, timer, state or rule that does not exist. */
		UNKNOWN_REFERENCE("unknownReference"),
		/** Operand or argument types, a condition that is not boolean, a write that does not convert. */
		TYPE_ERROR("typeError"),
		/** A literal value out of its range. */
		INVALID_LITERAL("invalidLiteral"),
		/** A default that is not valid, min above max, choices on a type without them. */
		INVALID_PARAMETER("invalidParameter"),
		/** A write to a datapoint without the writable flag, or to the output of a schedule entry. */
		NOT_WRITABLE("notWritable"),
		/** A read of a datapoint no technology can be asked for. */
		NOT_READABLE("notReadable"),
		/** A construct the specification forbids at its position. */
		FORBIDDEN("forbidden"),
		/** A limit of <code>spec/rules.md</code> 10.2. */
		LIMIT_EXCEEDED("limitExceeded");

		private final String _name;

		LoadError(String name) {
			_name = name;
		}

		/** The reason's name in the specification. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * A load refusal: the reason, the id of the rule ("" outside a rule), the JSON path, the text as
	 * the controller gives it (English) and the same as a whole sentence for the user, in the
	 * languages of the application.
	 */
	public record Refusal(LoadError error, String rule, String path, String text, ResKey message) {

		@Override
		public String toString() {
			return "rule '" + rule + "' at " + path + ": " + error.externalName() + ": " + text;
		}
	}

	/**
	 * Thrown by {@link RuleLoader#load(Object, DatapointDirectory, ScheduleDirectory)} with the first
	 * refusal.
	 */
	public static final class LoadException extends Exception {

		private final Refusal _refusal;

		LoadException(Refusal refusal) {
			super(refusal.toString());
			_refusal = refusal;
		}

		/** The refusal. */
		public Refusal getRefusal() {
			return _refusal;
		}
	}

	/**
	 * The datapoints of the image as the loader sees them.
	 */
	public interface DatapointDirectory {

		/** The value type of the datapoint, <code>null</code> if there is no such datapoint. */
		ValueType type(String name);

		/** Whether rules may command the datapoint. */
		boolean writable(String name);
	}

	/**
	 * The schedule entries of the image as the loader sees them.
	 */
	public interface ScheduleDirectory {

		/** Whether the image has a schedule entry with the id. */
		boolean hasSchedule(String id);

		/**
		 * Whether the datapoint is the <code>output</code> of a schedule entry: the scheduler owns it,
		 * a rule's <code>write</code> to it is refused (<code>notWritable</code>), a
		 * <code>read</code> of it too (<code>notReadable</code>).
		 */
		default boolean ownsOutput(String datapoint) {
			return false;
		}

		/**
		 * Whether a technology owns the datapoint that can be asked for its value (a KNX group object,
		 * a Zigbee attribute, an external source): a rule's <code>read</code> of a datapoint bound to
		 * no such technology is refused (<code>notReadable</code>). <code>true</code> when the
		 * directory cannot tell; the technology's refusal at run time speaks then.
		 */
		default boolean technologyReads(String datapoint) {
			return true;
		}
	}

	/**
	 * A datapoint mirroring a rule parameter (<code>spec/rules.md</code> 8.6), as an image loader
	 * derives it from the <code>rules</code> member: its name, value type and whether it is writable
	 * (exactly when the parameter is adjustable).
	 */
	public record ParameterDatapoint(String name, ValueType type, boolean writable, String rule, String parameter) {
		// A value.
	}

	private static final int MAX_NAME = 63;

	private final DatapointDirectory _datapoints;

	private final ScheduleDirectory _schedules;

	private final RuleSet _set = new RuleSet();

	private final StringBuilder _path = new StringBuilder();

	private final List<String> _ruleIds = new ArrayList<>();

	private final List<Boolean> _ruleHasStates = new ArrayList<>();

	/** The index of the rule being compiled, -1 outside one. */
	private int _rule = -1;

	private Rule _current;

	private int _totalNodes;

	private int _expressionNodes;

	/** The kind of the expression being compiled. */
	private RootKind _rootKind = RootKind.NORMAL;

	private final String _language;

	/** How an expression root may be used. */
	private enum RootKind {
		/** Anything. */
		NORMAL,
		/** A match condition: pure functions only. */
		MATCH,
		/** The period of a silence trigger: pure functions and no datapoints. */
		CONSTANT;
	}

	private RuleLoader(DatapointDirectory datapoints, ScheduleDirectory schedules, String language) {
		_datapoints = datapoints;
		_schedules = schedules;
		_language = language == null || language.isEmpty() ? "en" : language;
	}

	/**
	 * Validates and compiles the <code>rules</code> member.
	 *
	 * @param rules
	 *        The parsed JSON value of the member.
	 * @param datapoints
	 *        The datapoints of the image.
	 * @param schedules
	 *        The schedules of the image, <code>null</code> for none.
	 * @throws LoadException
	 *         With the first refusal.
	 */
	public static RuleSet load(Object rules, DatapointDirectory datapoints, ScheduleDirectory schedules)
			throws LoadException {
		return load(rules, datapoints, schedules, "en");
	}

	/**
	 * Validates and compiles the <code>rules</code> member, the labels resolved in the given language
	 * (<code>spec/rules.md</code> 2.2).
	 *
	 * @param language
	 *        A language tag, the device's setting <code>ui.lang</code>.
	 * @see #load(Object, DatapointDirectory, ScheduleDirectory)
	 */
	public static RuleSet load(Object rules, DatapointDirectory datapoints, ScheduleDirectory schedules,
			String language) throws LoadException {
		RuleLoader loader = new RuleLoader(datapoints, schedules, language);
		loader.run(rules);
		loader.checkStorageKeys();
		return loader._set;
	}

	/**
	 * The <code>rules</code> member with every expression in its normalized form
	 * (<code>spec/rules.md</code> 4.8): a minus folded into a number literal, an integer literal
	 * partnering an enumeration as an enumeration literal (and an enumeration literal elsewhere as an
	 * integer literal), a controlled literal as the call <code>controlled(b, n)</code>. Everything
	 * else is copied.
	 *
	 * @throws LoadException
	 *         When the rules are refused.
	 */
	public static List<Object> normalized(Object rules, DatapointDirectory datapoints, ScheduleDirectory schedules)
			throws LoadException {
		RuleSet set = load(rules, datapoints, schedules);
		@SuppressWarnings("unchecked")
		List<Object> result = (List<Object>) copy(rules);
		for (Node root : set._roots) {
			replace(result, set._rootPaths.get(root._root), root.toJson());
		}
		return result;
	}

	private static Object copy(Object json) {
		if (json instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			map.forEach((key, value) -> result.put((String) key, copy(value)));
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

	/** Replaces the value at the path (<code>rules[0].reactions[1].condition</code>). */
	@SuppressWarnings("unchecked")
	private static void replace(List<Object> rules, String path, Object value) {
		Object container = null;
		Object key = null;
		Object current = rules;
		int pos = path.startsWith("rules") ? "rules".length() : 0;
		while (pos < path.length()) {
			container = current;
			if (path.charAt(pos) == '[') {
				int end = path.indexOf(']', pos);
				int index = Integer.parseInt(path.substring(pos + 1, end));
				key = Integer.valueOf(index);
				current = ((List<Object>) current).get(index);
				pos = end + 1;
			} else {
				int end = pos + 1;
				while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
					end++;
				}
				key = path.substring(pos + 1, end);
				current = ((Map<String, Object>) current).get(key);
				pos = end;
			}
		}
		if (key instanceof Integer index) {
			((List<Object>) container).set(index.intValue(), value);
		} else if (key != null) {
			((Map<String, Object>) container).put((String) key, value);
		}
	}

	// ---- Failures and members ---------------------------------------------------------------

	/**
	 * The refusal at the current path.
	 *
	 * @param text
	 *        The text as the controller gives it.
	 * @param message
	 *        The same for the user.
	 */
	private LoadException fail(LoadError error, String text, ResKey message) {
		String rule = _rule >= 0 && _rule < _ruleIds.size() ? _ruleIds.get(_rule) : "";
		return new LoadException(new Refusal(error, rule, _path.toString(), text, message));
	}

	private int enter(String segment) {
		int mark = _path.length();
		_path.append(segment);
		return mark;
	}

	private void leave(int mark) {
		_path.setLength(mark);
	}

	private Map<?, ?> only(Object json, String... allowed) throws LoadException {
		if (!(json instanceof Map<?, ?> map)) {
			throw fail(LoadError.MALFORMED, "an object expected", I18NConstants.RULE_NOT_AN_OBJECT);
		}
		for (Object key : map.keySet()) {
			String name = (String) key;
			boolean known = name.equals("description") || name.equals("comment");
			for (int i = 0; !known && i < allowed.length; i++) {
				known = name.equals(allowed[i]);
			}
			if (!known) {
				throw fail(LoadError.MALFORMED, "unknown member '" + name + "'",
					I18NConstants.RULE_UNKNOWN_MEMBER__NAME.fill(name));
			}
		}
		return map;
	}

	private static int bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8).length;
	}

	private String stringMember(Map<?, ?> object, String name, boolean required, int min, int max)
			throws LoadException {
		if (!object.containsKey(name)) {
			if (required) {
				throw fail(LoadError.MALFORMED, "member '" + name + "' missing",
					I18NConstants.RULE_MEMBER_MISSING__NAME.fill(name));
			}
			return null;
		}
		int mark = enter("." + name);
		Object member = object.get(name);
		if (!(member instanceof String text)) {
			throw fail(LoadError.MALFORMED, "'" + name + "' must be a string",
				I18NConstants.RULE_NOT_A_STRING__NAME.fill(name));
		}
		int length = bytes(text);
		if (length < min || length > max || text.indexOf('\0') >= 0) {
			throw fail(LoadError.MALFORMED, "'" + name + "' must have " + min + ".." + max + " bytes",
				I18NConstants.RULE_STRING_LENGTH__NAME_MIN_MAX.fill(name, String.valueOf(min), String.valueOf(max)));
		}
		leave(mark);
		return text;
	}

	/** A label: the text resolved in the language of the load and the language map (or none). */
	private record Label(String text, Map<String, String> map) {
		// A value.
	}

	/**
	 * Whether the text is a language tag of a label: 2..8 characters of <code>A-Z a-z 0-9 -</code>.
	 */
	public static boolean validLanguageTag(String tag) {
		int n = tag.length();
		if (n < 2 || n > 8) {
			return false;
		}
		for (int i = 0; i < n; i++) {
			char c = tag.charAt(i);
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The text of a label's language map in the given language (<code>spec/rules.md</code> 2.2): the
	 * entry whose tag equals the language (letters compared without case), else the entry of its base
	 * language (the part before <code>-</code>), else <code>en</code>, else the first entry;
	 * <code>null</code> for an empty map.
	 */
	public static String resolveLabel(Map<String, String> map, String language) {
		String base = language.indexOf('-') >= 0 ? language.substring(0, language.indexOf('-')) : language;
		String exact = null;
		String baseText = null;
		String english = null;
		String first = null;
		for (Map.Entry<String, String> entry : map.entrySet()) {
			String tag = entry.getKey();
			if (first == null) {
				first = entry.getValue();
			}
			if (exact == null && tag.equalsIgnoreCase(language)) {
				exact = entry.getValue();
			}
			if (baseText == null && tag.equalsIgnoreCase(base)) {
				baseText = entry.getValue();
			}
			if (english == null && tag.equalsIgnoreCase("en")) {
				english = entry.getValue();
			}
		}
		return exact != null ? exact : baseText != null ? baseText : english != null ? english : first;
	}

	private Label labelMember(Map<?, ?> object, String name) throws LoadException {
		if (!object.containsKey(name)) {
			throw fail(LoadError.MALFORMED, "member '" + name + "' missing",
				I18NConstants.RULE_MEMBER_MISSING__NAME.fill(name));
		}
		int mark = enter("." + name);
		Object member = object.get(name);
		if (member instanceof String text) {
			int length = bytes(text);
			if (length < 1 || length > RuleSet.MAX_LABEL || text.indexOf('\0') >= 0) {
				throw fail(LoadError.MALFORMED, "'" + name + "' must have 1.." + RuleSet.MAX_LABEL + " bytes",
					I18NConstants.RULE_STRING_LENGTH__NAME_MIN_MAX.fill(name, "1", String.valueOf(RuleSet.MAX_LABEL)));
			}
			leave(mark);
			return new Label(text, null);
		}
		if (!(member instanceof Map<?, ?> languages)) {
			throw fail(LoadError.MALFORMED, "'" + name + "' must be a string or an object of language tags to strings",
				I18NConstants.RULE_LABEL_MALFORMED__NAME.fill(name));
		}
		Map<String, String> map = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : languages.entrySet()) {
			String tag = (String) entry.getKey();
			if (!validLanguageTag(tag)) {
				throw fail(LoadError.MALFORMED,
					"a language tag of '" + name + "' must have 2..8 characters of A-Z a-z 0-9 -",
						I18NConstants.RULE_LABEL_LANGUAGE_TAG__NAME_TAG.fill(name, tag));
			}
			int tagMark = enter("." + tag);
			if (map.size() >= RuleSet.MAX_LABEL_LANGUAGES) {
				throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_LABEL_LANGUAGES + " languages in a label",
					I18NConstants.RULE_TOO_MANY_LANGUAGES__MAX.fill(String.valueOf(RuleSet.MAX_LABEL_LANGUAGES)));
			}
			if (!(entry.getValue() instanceof String text) || bytes(text) < 1 || bytes(text) > RuleSet.MAX_LABEL
				|| text.indexOf('\0') >= 0) {
				throw fail(LoadError.MALFORMED, "a label text must be a string of 1.." + RuleSet.MAX_LABEL + " bytes",
					I18NConstants.RULE_LABEL_TEXT__MAX.fill(String.valueOf(RuleSet.MAX_LABEL)));
			}
			map.put(tag, text);
			leave(tagMark);
		}
		if (map.isEmpty()) {
			throw fail(LoadError.MALFORMED, "'" + name + "' names no language",
				I18NConstants.RULE_LABEL_NO_LANGUAGE__NAME.fill(name));
		}
		leave(mark);
		return new Label(resolveLabel(map, _language), Collections.unmodifiableMap(map));
	}

	private boolean boolMember(Map<?, ?> object, String name, boolean fallback) throws LoadException {
		if (!object.containsKey(name)) {
			return fallback;
		}
		int mark = enter("." + name);
		if (!(object.get(name) instanceof Boolean value)) {
			throw fail(LoadError.MALFORMED, "'" + name + "' must be a boolean",
				I18NConstants.RULE_NOT_A_BOOLEAN__NAME.fill(name));
		}
		leave(mark);
		return value.booleanValue();
	}

	private List<?> arrayMember(Map<?, ?> object, String name, boolean required) throws LoadException {
		if (!object.containsKey(name)) {
			if (required) {
				throw fail(LoadError.MALFORMED, "member '" + name + "' missing",
					I18NConstants.RULE_MEMBER_MISSING__NAME.fill(name));
			}
			return null;
		}
		if (!(object.get(name) instanceof List<?> list)) {
			enter("." + name);
			throw fail(LoadError.MALFORMED, "'" + name + "' must be an array",
				I18NConstants.RULE_NOT_AN_ARRAY__NAME.fill(name));
		}
		return list;
	}

	private static boolean isKeyword(String text) {
		return text.equals("and") || text.equals("or") || text.equals("not") || text.equals("true")
			|| text.equals("false");
	}

	/**
	 * Whether the text is an identifier of the rule language: 1..24 characters
	 * <code>[A-Za-z_][A-Za-z0-9_]*</code>, not a keyword.
	 */
	public static boolean validIdentifier(String text) {
		int n = text.length();
		if (n < 1 || n > 24) {
			return false;
		}
		for (int i = 0; i < n; i++) {
			char c = text.charAt(i);
			boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
			if (!(letter || (i > 0 && c >= '0' && c <= '9'))) {
				return false;
			}
		}
		return !isKeyword(text);
	}

	/**
	 * Whether the text is the id of a rule parameter (homecontrol <code>spec/rules.md</code> 2.1,
	 * haumacher/ehome #110): 1..24 characters of <code>A-Z a-z 0-9 _ -</code> (the characters of a rule
	 * id), not a keyword. The id <code>enabled</code> is one, reserved for the enable switch.
	 */
	public static boolean validParameterId(String text) {
		return parameterIdCharacters(text) && !isKeyword(text);
	}

	/**
	 * Whether the text is 1..24 characters of <code>A-Z a-z 0-9 _ -</code>.
	 */
	public static boolean parameterIdCharacters(String text) {
		return text.length() <= 24 && validRuleId(text);
	}

	/**
	 * Whether the given word is a keyword of the rule language (<code>and or not true false</code>).
	 */
	public static boolean keyword(String text) {
		return isKeyword(text);
	}

	/**
	 * Whether the text is a rule or schedule id: 1..32 characters of <code>A-Z a-z 0-9 _ -</code>.
	 */
	public static boolean validRuleId(String text) {
		int n = text.length();
		if (n < 1 || n > 32) {
			return false;
		}
		for (int i = 0; i < n; i++) {
			char c = text.charAt(i);
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
				return false;
			}
		}
		return true;
	}

	// ---- Storage keys and parameter datapoints ----------------------------------------------

	/**
	 * Checks the storage keys of every parameter and rule state (spec 8.4, 8.5,
	 * {@link ParameterStore#hash(String, String)}): two that hash alike refuse the set.
	 */
	private void checkStorageKeys() throws LoadException {
		Map<Long, String> seen = new HashMap<>();
		for (int r = 0; r < _set._rules.size(); r++) {
			Rule rule = _set._rules.get(r);
			List<String> keys = new ArrayList<>();
			for (Parameter parameter : rule._parameters) {
				keys.add(parameter._id);
			}
			if (!rule._states.isEmpty()) {
				keys.add(RuleSet.STATE_KEY);
			}
			for (String key : keys) {
				String logical = ParameterStore.key(rule._id, key);
				String before = seen.putIfAbsent(Long.valueOf(ParameterStore.hash(rule._id, key)), logical);
				if (before != null) {
					throw new LoadException(new Refusal(LoadError.LIMIT_EXCEEDED, rule._id, "rules[" + r + "]",
						"the storage keys of " + before + " and " + logical + " collide; rename one",
						I18NConstants.RULE_STORAGE_COLLISION__FIRST_SECOND.fill(before, logical)));
				}
			}
		}
	}

	/**
	 * The name of the datapoint mirroring a rule parameter (<code>spec/rules.md</code> 8.6):
	 * <code>rule/&lt;ruleId&gt;/&lt;parameterId&gt;</code>.
	 */
	public static String parameterDatapointName(String rule, String parameter) {
		return RuleSet.PARAMETER_DATAPOINT_PREFIX + rule + "/" + parameter;
	}

	/**
	 * The datapoint value type that mirrors a parameter of the type (<code>spec/rules.md</code> 8.6):
	 * boolean, signed (integer), real, string, unsigned (duration in ms), time of day, enumeration,
	 * color; <code>null</code> for another type.
	 */
	public static ValueType parameterDatapointType(Type type) {
		if (type == null) {
			return null;
		}
		switch (type) {
			case BOOLEAN:
				return ValueType.BOOLEAN;
			case INTEGER:
				return ValueType.SIGNED;
			case REAL:
				return ValueType.REAL;
			case STRING:
				return ValueType.STRING;
			case DURATION:
				return ValueType.UNSIGNED;
			case TIME_OF_DAY:
				return ValueType.TIME_OF_DAY;
			case ENUMERATION:
				return ValueType.ENUMERATION;
			case COLOR:
				return ValueType.COLOR;
			default:
				return null;
		}
	}

	/**
	 * The parameter datapoints of the <code>rules</code> member (<code>spec/rules.md</code> 8.6), in
	 * rule order, <code>enabled</code> first in each rule. Lenient: a rule or parameter that is not
	 * well-formed (an invalid or repeated id, an unknown type) is skipped, since {@link #load} refuses
	 * it with the precise reason.
	 */
	public static List<ParameterDatapoint> parameterDatapoints(Object rules) {
		List<ParameterDatapoint> result = new ArrayList<>();
		if (!(rules instanceof List<?> list)) {
			return result;
		}
		Set<String> ruleIds = new HashSet<>();
		for (Object item : list) {
			if (ruleIds.size() >= RuleSet.MAX_RULES) {
				break;
			}
			if (!(item instanceof Map<?, ?> rule) || !(rule.get("id") instanceof String id) || !validRuleId(id)
				|| !ruleIds.add(id)) {
				continue;
			}
			boolean adjustable = !(rule.get("enabledAdjustable") instanceof Boolean b) || b.booleanValue();
			result.add(new ParameterDatapoint(parameterDatapointName(id, "enabled"), ValueType.BOOLEAN, adjustable, id,
				"enabled"));
			if (!(rule.get("parameters") instanceof List<?> parameters)) {
				continue;
			}
			Set<String> seen = new HashSet<>();
			for (Object p : parameters) {
				if (seen.size() + 1 >= RuleSet.MAX_PARAMETERS) {
					break;
				}
				if (!(p instanceof Map<?, ?> parameter) || !(parameter.get("id") instanceof String pid)
					|| !validParameterId(pid) || pid.equals("enabled") || !(parameter.get("type") instanceof String typeText)) {
					continue;
				}
				Type type = Type.fromName(typeText);
				ValueType valueType = type != null && isParameterType(type) ? parameterDatapointType(type) : null;
				if (valueType == null || !seen.add(pid)) {
					continue;
				}
				boolean writable = !(parameter.get("adjustable") instanceof Boolean b) || b.booleanValue();
				result.add(new ParameterDatapoint(parameterDatapointName(id, pid), valueType, writable, id, pid));
			}
		}
		return result;
	}

	// ---- Rules ------------------------------------------------------------------------------

	private void run(Object rules) throws LoadException {
		if (!(rules instanceof List<?> list)) {
			_path.append("rules");
			throw fail(LoadError.MALFORMED, "'rules' must be an array",
				I18NConstants.RULE_NOT_AN_ARRAY__NAME.fill("rules"));
		}
		prescan(list);
		for (int i = 0; i < list.size(); i++) {
			_rule = i;
			int mark = enter("rules[" + i + "]");
			rule(list.get(i));
			leave(mark);
		}
	}

	private void prescan(List<?> rules) throws LoadException {
		for (int i = 0; i < rules.size(); i++) {
			int mark = enter("rules[" + i + "]");
			if (i >= RuleSet.MAX_RULES) {
				throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_RULES + " rules",
					I18NConstants.RULE_TOO_MANY_RULES__MAX.fill(String.valueOf(RuleSet.MAX_RULES)));
			}
			if (!(rules.get(i) instanceof Map<?, ?> item)) {
				throw fail(LoadError.MALFORMED, "a rule must be an object", I18NConstants.RULE_RULE_NOT_AN_OBJECT);
			}
			String id = stringMember(item, "id", true, 1, 32);
			if (!validRuleId(id)) {
				enter(".id");
				throw fail(LoadError.MALFORMED, "rule id '" + id + "' is not 1..32 characters of A-Z a-z 0-9 _ -",
					I18NConstants.RULE_RULE_ID_PATTERN__ID.fill(id));
			}
			if (_ruleIds.contains(id)) {
				enter(".id");
				throw fail(LoadError.DUPLICATE_ID, "rule id '" + id + "' is used twice",
					I18NConstants.RULE_RULE_ID_TWICE__ID.fill(id));
			}
			_ruleIds.add(id);
			_ruleHasStates.add(Boolean.valueOf(item.get("states") instanceof List<?> states && !states.isEmpty()));
			leave(mark);
		}
	}

	private void rule(Object json) throws LoadException {
		Map<?, ?> map = only(json, "id", "name", "enabled", "enabledAdjustable", "parameters", "timers", "states",
			"initialState", "reactions");
		Label name = labelMember(map, "name");
		boolean enabled = boolMember(map, "enabled", true);
		boolean enabledAdjustable = boolMember(map, "enabledAdjustable", true);
		Rule rule = new Rule(_ruleIds.get(_rule), name.text(), name.map());
		_current = rule;

		// Parameters: `enabled` first, then the declared ones.
		List<?> parameters = arrayMember(map, "parameters", false);
		rule._parameters.add(new Parameter("enabled", "Enabled", null, "", Type.BOOLEAN, enabledAdjustable,
			Val.ofBool(enabled), null, null, List.of(), List.of()));
		if (parameters != null) {
			int scope = enter(".parameters");
			for (int i = 0; i < parameters.size(); i++) {
				int mark = enter("[" + i + "]");
				if (rule._parameters.size() >= RuleSet.MAX_PARAMETERS) {
					throw fail(LoadError.LIMIT_EXCEEDED,
						"more than " + RuleSet.MAX_PARAMETERS + " parameters (with 'enabled')",
							I18NConstants.RULE_TOO_MANY_PARAMETERS__MAX.fill(String.valueOf(RuleSet.MAX_PARAMETERS)));
				}
				rule._parameters.add(parameter(parameters.get(i)));
				leave(mark);
			}
			leave(scope);
		}

		// Timer and state ids first: expressions and actions may reference any of them.
		List<?> timers = arrayMember(map, "timers", false);
		List<?> states = arrayMember(map, "states", false);
		if (timers != null) {
			int scope = enter(".timers");
			for (int i = 0; i < timers.size(); i++) {
				int mark = enter("[" + i + "]");
				Map<?, ?> item = only(timers.get(i), "id", "duration");
				if (rule._timers.size() >= RuleSet.MAX_TIMERS) {
					throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_TIMERS + " timers",
						I18NConstants.RULE_TOO_MANY_TIMERS__MAX.fill(String.valueOf(RuleSet.MAX_TIMERS)));
				}
				String id = stringMember(item, "id", true, 1, 24);
				if (!validIdentifier(id)) {
					enter(".id");
					throw fail(LoadError.MALFORMED, "timer id '" + id + "' is not an identifier",
						I18NConstants.RULE_TIMER_ID_INVALID__ID.fill(id));
				}
				if (rule.findTimer(id) >= 0) {
					enter(".id");
					throw fail(LoadError.DUPLICATE_ID, "timer id '" + id + "' is used twice",
						I18NConstants.RULE_TIMER_ID_TWICE__ID.fill(id));
				}
				rule._timers.add(new Timer(id));
				leave(mark);
			}
			leave(scope);
		}
		if (states != null) {
			int scope = enter(".states");
			for (int i = 0; i < states.size(); i++) {
				int mark = enter("[" + i + "]");
				Map<?, ?> item = only(states.get(i), "id", "entry", "exit");
				if (rule._states.size() >= RuleSet.MAX_STATES) {
					throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_STATES + " states",
						I18NConstants.RULE_TOO_MANY_STATES__MAX.fill(String.valueOf(RuleSet.MAX_STATES)));
				}
				String id = stringMember(item, "id", true, 1, 24);
				if (!validIdentifier(id)) {
					enter(".id");
					throw fail(LoadError.MALFORMED, "state id '" + id + "' is not an identifier",
						I18NConstants.RULE_STATE_ID_INVALID__ID.fill(id));
				}
				if (rule.findState(id) >= 0) {
					enter(".id");
					throw fail(LoadError.DUPLICATE_ID, "state id '" + id + "' is used twice",
						I18NConstants.RULE_STATE_ID_TWICE__ID.fill(id));
				}
				rule._states.add(new State(id));
				leave(mark);
			}
			leave(scope);
		}
		String initial = stringMember(map, "initialState", false, 1, 24);
		if (!rule._states.isEmpty()) {
			if (initial == null) {
				throw fail(LoadError.MALFORMED, "member 'initialState' missing (the rule has states)",
					I18NConstants.RULE_INITIAL_STATE_MISSING);
			}
			int s = rule.findState(initial);
			if (s < 0) {
				enter(".initialState");
				throw fail(LoadError.UNKNOWN_REFERENCE, "initial state '" + initial + "' is not a state of the rule",
					I18NConstants.RULE_INITIAL_STATE_UNKNOWN__STATE.fill(initial));
			}
			rule._initialState = s;
		} else if (initial != null) {
			enter(".initialState");
			throw fail(LoadError.FORBIDDEN, "'initialState' in a rule without states",
				I18NConstants.RULE_INITIAL_STATE_WITHOUT_STATES);
		}

		// Timer durations.
		if (timers != null) {
			int scope = enter(".timers");
			for (int i = 0; i < timers.size(); i++) {
				int mark = enter("[" + i + "]");
				Map<?, ?> item = (Map<?, ?>) timers.get(i);
				if (!item.containsKey("duration")) {
					throw fail(LoadError.MALFORMED, "member 'duration' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("duration"));
				}
				enter(".duration");
				Node duration = root(item.get("duration"), false);
				if (duration._type != Type.DURATION) {
					throw fail(LoadError.TYPE_ERROR, "a timer duration must be a duration, not " + typeName(duration._type),
						I18NConstants.RULE_TIMER_DURATION_TYPE__TYPE.fill(typeName(duration._type)));
				}
				rule._timers.get(i)._duration = duration;
				leave(mark);
			}
			leave(scope);
		}

		// States with their entry and exit actions.
		if (states != null) {
			int scope = enter(".states");
			for (int i = 0; i < states.size(); i++) {
				int mark = enter("[" + i + "]");
				Map<?, ?> item = (Map<?, ?>) states.get(i);
				State state = rule._states.get(i);
				List<?> entry = arrayMember(item, "entry", false);
				if (entry != null) {
					int list = enter(".entry");
					actionList(entry, state._entry);
					leave(list);
				}
				List<?> exit = arrayMember(item, "exit", false);
				if (exit != null) {
					int list = enter(".exit");
					actionList(exit, state._exit);
					leave(list);
				}
				leave(mark);
			}
			leave(scope);
		}

		// Reactions.
		List<?> reactions = arrayMember(map, "reactions", true);
		int scope = enter(".reactions");
		if (reactions.size() < 1) {
			throw fail(LoadError.MALFORMED, "a rule needs at least one reaction", I18NConstants.RULE_NO_REACTION);
		}
		if (reactions.size() > RuleSet.MAX_REACTIONS) {
			throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_REACTIONS + " reactions",
				I18NConstants.RULE_TOO_MANY_REACTIONS__MAX.fill(String.valueOf(RuleSet.MAX_REACTIONS)));
		}
		for (int i = 0; i < reactions.size(); i++) {
			int mark = enter("[" + i + "]");
			rule._reactions.add(reaction(reactions.get(i)));
			leave(mark);
		}
		leave(scope);
		_set._rules.add(rule);
	}

	private Parameter parameter(Object json) throws LoadException {
		Map<?, ?> map = only(json, "id", "label", "type", "default", "min", "max", "step", "unit", "choices",
			"adjustable");
		String id = stringMember(map, "id", true, 1, 24);
		if (!parameterIdCharacters(id)) {
			enter(".id");
			throw fail(LoadError.MALFORMED, "parameter id '" + id + "' is not 1..24 characters of A-Z a-z 0-9 _ -",
				I18NConstants.RULE_PARAMETER_ID_INVALID__ID.fill(id));
		}
		if (isKeyword(id)) {
			enter(".id");
			throw fail(LoadError.MALFORMED, "parameter id '" + id + "' is a keyword",
				I18NConstants.RULE_PARAMETER_ID_KEYWORD__ID.fill(id));
		}
		if (id.equals("enabled")) {
			enter(".id");
			throw fail(LoadError.DUPLICATE_ID, "the parameter id 'enabled' is reserved for the enable switch",
				I18NConstants.RULE_PARAMETER_ID_ENABLED);
		}
		if (_current.findParameter(id) >= 0) {
			enter(".id");
			throw fail(LoadError.DUPLICATE_ID, "parameter id '" + id + "' is used twice",
				I18NConstants.RULE_PARAMETER_ID_TWICE__ID.fill(id));
		}
		Label label = labelMember(map, "label");
		String typeText = stringMember(map, "type", true, 1, 23);
		Type type = Type.fromName(typeText);
		if (type == null) {
			enter(".type");
			throw fail(LoadError.MALFORMED, "unknown type '" + typeText + "'",
				I18NConstants.RULE_UNKNOWN_TYPE__TYPE.fill(typeText));
		}
		if (!isParameterType(type)) {
			enter(".type");
			throw fail(LoadError.INVALID_PARAMETER, "'" + typeText + "' is not a parameter type",
				I18NConstants.RULE_NOT_A_PARAMETER_TYPE__TYPE.fill(typeText));
		}
		String unit = "";
		if (map.containsKey("unit")) {
			int mark = enter(".unit");
			if (!(map.get("unit") instanceof String text)) {
				throw fail(LoadError.MALFORMED, "'unit' must be a string",
					I18NConstants.RULE_NOT_A_STRING__NAME.fill("unit"));
			}
			if (bytes(text) > 15) {
				throw fail(LoadError.INVALID_PARAMETER, "a unit has at most 15 bytes",
					I18NConstants.RULE_UNIT_TOO_LONG);
			}
			unit = text;
			leave(mark);
		}
		if (map.containsKey("step") && !(map.get("step") instanceof Number)) {
			enter(".step");
			throw fail(LoadError.MALFORMED, "'step' must be a number",
				I18NConstants.RULE_NOT_A_NUMBER__NAME.fill("step"));
		}
		boolean adjustable = boolMember(map, "adjustable", true);
		boolean ranged = type == Type.INTEGER || type == Type.REAL || type == Type.DURATION || type == Type.TIME_OF_DAY;
		Val min = null;
		Val max = null;
		for (String name : new String[] { "min", "max" }) {
			if (map.containsKey(name)) {
				int mark = enter("." + name);
				if (!ranged) {
					throw fail(LoadError.INVALID_PARAMETER, "'" + name + "' on a " + typeText + " parameter",
						I18NConstants.RULE_MEMBER_ON_TYPE__NAME_TYPE.fill(name, typeText));
				}
				Val value = literalValue(map.get(name), type);
				if (name.equals("min")) {
					min = value;
				} else {
					max = value;
				}
				leave(mark);
			}
		}
		if (min != null && max != null && Evaluator.order(min, max) > 0) {
			throw fail(LoadError.INVALID_PARAMETER, "min > max", I18NConstants.RULE_MIN_ABOVE_MAX);
		}
		List<?> choices = arrayMember(map, "choices", false);
		List<Val> choiceValues = new ArrayList<>();
		List<String> choiceLabels = new ArrayList<>();
		if (choices != null) {
			int scope = enter(".choices");
			if (type != Type.INTEGER && type != Type.STRING && type != Type.ENUMERATION) {
				throw fail(LoadError.INVALID_PARAMETER, "'choices' on a " + typeText + " parameter",
					I18NConstants.RULE_MEMBER_ON_TYPE__NAME_TYPE.fill("choices", typeText));
			}
			for (int i = 0; i < choices.size(); i++) {
				int mark = enter("[" + i + "]");
				Map<?, ?> item = only(choices.get(i), "value", "label");
				if (choiceValues.size() >= RuleSet.MAX_CHOICES) {
					throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_CHOICES + " choices",
						I18NConstants.RULE_TOO_MANY_CHOICES__MAX.fill(String.valueOf(RuleSet.MAX_CHOICES)));
				}
				if (!item.containsKey("value")) {
					throw fail(LoadError.MALFORMED, "member 'value' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("value"));
				}
				int value = enter(".value");
				Val choice = literalValue(item.get("value"), type);
				leave(value);
				choiceLabels.add(labelMember(item, "label").text());
				choiceValues.add(choice);
				leave(mark);
			}
			if (choiceValues.isEmpty()) {
				throw fail(LoadError.INVALID_PARAMETER, "'choices' is empty", I18NConstants.RULE_CHOICES_EMPTY);
			}
			leave(scope);
		}
		if (!map.containsKey("default")) {
			throw fail(LoadError.MALFORMED, "member 'default' missing",
				I18NConstants.RULE_MEMBER_MISSING__NAME.fill("default"));
		}
		int mark = enter(".default");
		Val value = literalValue(map.get("default"), type);
		if ((min != null && Evaluator.order(value, min) < 0) || (max != null && Evaluator.order(value, max) > 0)) {
			throw fail(LoadError.INVALID_PARAMETER, "the default is outside min..max",
				I18NConstants.RULE_DEFAULT_OUTSIDE);
		}
		if (!choiceValues.isEmpty() && choiceValues.stream().noneMatch(choice -> Val.equal(value, choice))) {
			throw fail(LoadError.INVALID_PARAMETER, "the default is not one of the choices",
				I18NConstants.RULE_DEFAULT_NOT_A_CHOICE);
		}
		leave(mark);
		return new Parameter(id, label.text(), label.map(), unit, type, adjustable, value, min, max,
			List.copyOf(choiceValues), List.copyOf(choiceLabels));
	}

	private static boolean isParameterType(Type t) {
		return t == Type.BOOLEAN || t == Type.INTEGER || t == Type.REAL || t == Type.STRING || t == Type.DURATION
			|| t == Type.TIME_OF_DAY || t == Type.ENUMERATION || t == Type.COLOR;
	}

	private Val literalValue(Object json, Type type) throws LoadException {
		Values.Literal literal = Values.fromJson(type, json);
		switch (literal.status()) {
			case MALFORMED:
				throw fail(LoadError.MALFORMED, literal.reason(), literal.message());
			case INVALID:
				throw fail(LoadError.INVALID_LITERAL, literal.reason(), literal.message());
			default:
				return literal.value();
		}
	}

	private void actionList(List<?> array, List<Action> out) throws LoadException {
		if (array.size() > RuleSet.MAX_ACTIONS) {
			throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_ACTIONS + " actions in a list",
				I18NConstants.RULE_TOO_MANY_ACTIONS__MAX.fill(String.valueOf(RuleSet.MAX_ACTIONS)));
		}
		for (int i = 0; i < array.size(); i++) {
			int mark = enter("[" + i + "]");
			Action action = action(array.get(i));
			action._index = _set._actionCount++;
			out.add(action);
			leave(mark);
		}
	}

	private Action action(Object json) throws LoadException {
		if (!(json instanceof Map<?, ?> map)) {
			throw fail(LoadError.MALFORMED, "an action must be an object", I18NConstants.RULE_ACTION_NOT_AN_OBJECT);
		}
		String kind = stringMember(map, "kind", true, 1, 23);
		switch (kind) {
			case "write": {
				only(map, "kind", "datapoint", "value", "onlyOnChange");
				String name = datapointMember(map);
				ValueType target = _datapoints.type(name);
				if (!_datapoints.writable(name)) {
					enter(".datapoint");
					throw fail(LoadError.NOT_WRITABLE, "datapoint '" + name + "' is not writable",
						I18NConstants.RULE_NOT_WRITABLE__NAME.fill(DatapointId.of(name)));
				}
				if (_schedules != null && _schedules.ownsOutput(name)) {
					enter(".datapoint");
					throw fail(LoadError.NOT_WRITABLE,
						"datapoint '" + name + "' is the output of a schedule entry; the scheduler owns it",
							I18NConstants.RULE_SCHEDULE_OUTPUT__NAME.fill(DatapointId.of(name)));
				}
				boolean onlyOnChange = boolMember(map, "onlyOnChange", false);
				if (!map.containsKey("value")) {
					throw fail(LoadError.MALFORMED, "member 'value' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("value"));
				}
				int mark = enter(".value");
				Node value = root(map.get("value"), false);
				if (!Values.writeAllowed(value._type, target)) {
					throw fail(LoadError.TYPE_ERROR, "a " + typeName(value._type) + " value cannot be written to the "
						+ target.externalName() + " datapoint '" + name + "'",
							I18NConstants.RULE_WRITE_TYPE__VALUE_TARGET_NAME.fill(typeName(value._type), target.externalName(),
								DatapointId.of(name)));
				}
				leave(mark);
				Action action = new Action(ActionKind.WRITE);
				action._datapoint = name;
				action._datapointType = target;
				action._onlyOnChange = onlyOnChange;
				action._expr = value;
				return action;
			}
			case "read": {
				only(map, "kind", "datapoint");
				String name = datapointMember(map);
				if (name.startsWith(RuleSet.PARAMETER_DATAPOINT_PREFIX)) {
					enter(".datapoint");
					throw fail(LoadError.NOT_READABLE, "datapoint '" + name + "' mirrors a rule parameter: no technology reads it",
						I18NConstants.RULE_READ_PARAMETER__NAME.fill(DatapointId.of(name)));
				}
				if (name.startsWith(Schedule.DATAPOINT_PREFIX)) {
					enter(".datapoint");
					throw fail(LoadError.NOT_READABLE, "datapoint '" + name + "' mirrors a schedule's time: no technology reads it",
						I18NConstants.RULE_READ_SCHEDULE__NAME.fill(DatapointId.of(name)));
				}
				if (_schedules != null && (_schedules.ownsOutput(name) || !_schedules.technologyReads(name))) {
					enter(".datapoint");
					throw fail(LoadError.NOT_READABLE, "datapoint '" + name + "' is bound to no technology that reads values",
						I18NConstants.RULE_READ_UNBOUND__NAME.fill(DatapointId.of(name)));
				}
				Action action = new Action(ActionKind.READ);
				action._datapoint = name;
				return action;
			}
			case "startTimer":
			case "restartTimer":
			case "cancelTimer": {
				boolean cancel = kind.equals("cancelTimer");
				if (cancel) {
					only(map, "kind", "timer");
				} else {
					only(map, "kind", "timer", "duration");
				}
				String id = stringMember(map, "timer", true, 1, 24);
				int t = _current.findTimer(id);
				if (t < 0) {
					enter(".timer");
					throw fail(LoadError.UNKNOWN_REFERENCE, "no timer '" + id + "' in the rule",
						I18NConstants.RULE_NO_TIMER__ID.fill(id));
				}
				Action action = new Action(cancel ? ActionKind.CANCEL_TIMER
					: kind.equals("startTimer") ? ActionKind.START_TIMER : ActionKind.RESTART_TIMER);
				action._ref = t;
				if (!cancel && map.containsKey("duration")) {
					int mark = enter(".duration");
					Node duration = root(map.get("duration"), false);
					if (duration._type != Type.DURATION) {
						throw fail(LoadError.TYPE_ERROR,
							"a timer duration must be a duration, not " + typeName(duration._type),
								I18NConstants.RULE_TIMER_DURATION_TYPE__TYPE.fill(typeName(duration._type)));
					}
					action._expr = duration;
					leave(mark);
				}
				return action;
			}
			case "setParameter": {
				only(map, "kind", "parameter", "value");
				String id = stringMember(map, "parameter", true, 1, 24);
				int p = _current.findParameter(id);
				if (p == 0) {
					enter(".parameter");
					throw fail(LoadError.FORBIDDEN, "the enable switch cannot be set by a rule",
						I18NConstants.RULE_ENABLE_SET);
				}
				if (p < 0) {
					enter(".parameter");
					throw fail(LoadError.UNKNOWN_REFERENCE, "no parameter '" + id + "' in the rule",
						I18NConstants.RULE_NO_PARAMETER__ID.fill(id));
				}
				if (!map.containsKey("value")) {
					throw fail(LoadError.MALFORMED, "member 'value' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("value"));
				}
				int mark = enter(".value");
				Node value = root(map.get("value"), false);
				Type target = _current._parameters.get(p)._type;
				if (target == Type.ENUMERATION) {
					// The value takes the type of the parameter it is assigned to (4.8).
					value = retypeRoot(value);
				}
				if (!Values.assignAllowed(value._type, target)) {
					throw fail(LoadError.TYPE_ERROR, "a " + typeName(value._type) + " value cannot be assigned to the "
						+ target.externalName() + " parameter '" + id + "'",
							I18NConstants.RULE_ASSIGN_TYPE__VALUE_TARGET_ID.fill(typeName(value._type), target.externalName(), id));
				}
				leave(mark);
				Action action = new Action(ActionKind.SET_PARAMETER);
				action._ref = p;
				action._expr = value;
				return action;
			}
			case "log": {
				only(map, "kind", "level", "message");
				String level = stringMember(map, "level", true, 1, 15);
				Action action = new Action(ActionKind.LOG);
				switch (level) {
					case "info" -> action._level = Level.INFO;
					case "warning" -> action._level = Level.WARNING;
					case "error" -> action._level = Level.ERROR;
					default -> {
						enter(".level");
						throw fail(LoadError.MALFORMED, "unknown level '" + level + "'",
							I18NConstants.RULE_UNKNOWN_LEVEL__LEVEL.fill(level));
					}
				}
				List<?> message = arrayMember(map, "message", true);
				int mark = enter(".message");
				if (message.size() > RuleSet.MAX_LOG_PARTS) {
					throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_LOG_PARTS + " parts in a log message",
						I18NConstants.RULE_TOO_MANY_LOG_PARTS__MAX.fill(String.valueOf(RuleSet.MAX_LOG_PARTS)));
				}
				for (int i = 0; i < message.size(); i++) {
					int part = enter("[" + i + "]");
					Object item = message.get(i);
					if (item instanceof String text) {
						if (bytes(text) > RuleSet.MAX_MESSAGE) {
							throw fail(LoadError.LIMIT_EXCEEDED, "a text part longer than " + RuleSet.MAX_MESSAGE + " bytes",
								I18NConstants.RULE_LOG_TEXT_TOO_LONG__MAX.fill(String.valueOf(RuleSet.MAX_MESSAGE)));
						}
						action._parts.add(text);
					} else {
						action._parts.add(root(item, false));
					}
					leave(part);
				}
				leave(mark);
				return action;
			}
			default:
				enter(".kind");
				throw fail(LoadError.MALFORMED, "unknown action kind '" + kind + "'",
					I18NConstants.RULE_UNKNOWN_ACTION_KIND__KIND.fill(kind));
		}
	}

	private Trigger trigger(Object json) throws LoadException {
		if (!(json instanceof Map<?, ?> map)) {
			throw fail(LoadError.MALFORMED, "a trigger must be an object", I18NConstants.RULE_TRIGGER_NOT_AN_OBJECT);
		}
		String kind = stringMember(map, "kind", true, 1, 23);
		switch (kind) {
			case "datapoint": {
				only(map, "kind", "datapoint", "on", "origin");
				String name = datapointMember(map);
				Trigger trigger = new Trigger(TriggerKind.DATAPOINT);
				trigger._name = name;
				String on = stringMember(map, "on", false, 1, 15);
				if ("update".equals(on)) {
					trigger._onUpdate = true;
				} else if (on != null && !on.equals("change")) {
					enter(".on");
					throw fail(LoadError.MALFORMED, "'on' must be change or update", I18NConstants.RULE_TRIGGER_ON);
				}
				String origin = stringMember(map, "origin", false, 1, 15);
				if ("report".equals(origin)) {
					trigger._originReport = true;
				} else if ("command".equals(origin)) {
					trigger._originCommand = true;
				} else if (origin != null && !origin.equals("any")) {
					enter(".origin");
					throw fail(LoadError.MALFORMED, "'origin' must be any, report or command",
						I18NConstants.RULE_TRIGGER_ORIGIN);
				}
				_set._interest.add(name);
				return trigger;
			}
			case "match": {
				only(map, "kind", "condition");
				if (!map.containsKey("condition")) {
					throw fail(LoadError.MALFORMED, "member 'condition' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("condition"));
				}
				int mark = enter(".condition");
				Node condition = root(map.get("condition"), true);
				if (condition._type != Type.BOOLEAN) {
					throw fail(LoadError.TYPE_ERROR, "a condition must be boolean, not " + typeName(condition._type),
						I18NConstants.RULE_CONDITION_TYPE__TYPE.fill(typeName(condition._type)));
				}
				leave(mark);
				Trigger trigger = new Trigger(TriggerKind.MATCH);
				trigger._condition = condition;
				condition.visit(node -> {
					if (node._kind == Kind.DATAPOINT) {
						trigger._matchDatapoints.add(node._name);
						_set._interest.add(node._name);
					} else if (node._kind == Kind.PARAMETER) {
						trigger._matchParameters.add(Integer.valueOf(node._ref));
					}
				});
				_set._matches.add(trigger);
				_set._matchRules.add(Integer.valueOf(_rule));
				return trigger;
			}
			case "schedule": {
				only(map, "kind", "schedule");
				String id = stringMember(map, "schedule", true, 1, 32);
				if (_schedules == null || !_schedules.hasSchedule(id)) {
					enter(".schedule");
					throw fail(LoadError.UNKNOWN_REFERENCE, "no schedule '" + id + "'",
						I18NConstants.RULE_NO_SCHEDULE__ID.fill(id));
				}
				Trigger trigger = new Trigger(TriggerKind.SCHEDULE);
				trigger._name = id;
				_set._schedules.add(id);
				return trigger;
			}
			case "timer": {
				only(map, "kind", "timer");
				String id = stringMember(map, "timer", true, 1, 24);
				int timer = _current.findTimer(id);
				if (timer < 0) {
					enter(".timer");
					throw fail(LoadError.UNKNOWN_REFERENCE, "no timer '" + id + "' in the rule",
						I18NConstants.RULE_NO_TIMER__ID.fill(id));
				}
				Trigger trigger = new Trigger(TriggerKind.TIMER);
				trigger._ref = timer;
				return trigger;
			}
			case "start": {
				only(map, "kind", "reasons");
				List<?> reasons = arrayMember(map, "reasons", false);
				Trigger trigger = new Trigger(TriggerKind.START);
				trigger._reasons = EnumSet.allOf(StartReason.class);
				if (reasons != null) {
					trigger._reasons = EnumSet.noneOf(StartReason.class);
					int scope = enter(".reasons");
					for (int i = 0; i < reasons.size(); i++) {
						int itemMark = enter("[" + i + "]");
						Object item = reasons.get(i);
						if (!(item instanceof String reason)) {
							throw fail(LoadError.MALFORMED, "a reason must be boot, image or enable",
								I18NConstants.RULE_START_REASON_NOT_A_STRING);
						}
						StartReason found = null;
						for (StartReason candidate : StartReason.values()) {
							if (candidate.externalName().equals(reason)) {
								found = candidate;
							}
						}
						if (found == null) {
							throw fail(LoadError.MALFORMED, "unknown start reason '" + reason + "'",
								I18NConstants.RULE_UNKNOWN_START_REASON__REASON.fill(reason));
						}
						trigger._reasons.add(found);
						leave(itemMark);
					}
					leave(scope);
				}
				return trigger;
			}
			case "parameter": {
				only(map, "kind", "parameter");
				String id = stringMember(map, "parameter", true, 1, 24);
				int p = _current.findParameter(id);
				if (p == 0) {
					enter(".parameter");
					throw fail(LoadError.FORBIDDEN, "the enable switch cannot trigger a reaction",
						I18NConstants.RULE_ENABLE_TRIGGER);
				}
				if (p < 0) {
					enter(".parameter");
					throw fail(LoadError.UNKNOWN_REFERENCE, "no parameter '" + id + "' in the rule",
						I18NConstants.RULE_NO_PARAMETER__ID.fill(id));
				}
				Trigger trigger = new Trigger(TriggerKind.PARAMETER);
				trigger._ref = p;
				return trigger;
			}
			case "silence": {
				only(map, "kind", "datapoint", "after");
				String name = datapointMember(map);
				if (!map.containsKey("after")) {
					throw fail(LoadError.MALFORMED, "member 'after' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("after"));
				}
				int mark = enter(".after");
				Node after = root(map.get("after"), RootKind.CONSTANT);
				if (after._type != Type.DURATION) {
					throw fail(LoadError.TYPE_ERROR, "a silence duration must be a duration, not " + typeName(after._type),
						I18NConstants.RULE_SILENCE_TYPE__TYPE.fill(typeName(after._type)));
				}
				leave(mark);
				Trigger trigger = new Trigger(TriggerKind.SILENCE);
				trigger._name = name;
				trigger._after = after;
				trigger._ref = _set._silences.size();
				_set._silences.add(trigger);
				_set._silenceRules.add(Integer.valueOf(_rule));
				return trigger;
			}
			default:
				enter(".kind");
				throw fail(LoadError.MALFORMED, "unknown trigger kind '" + kind + "'",
					I18NConstants.RULE_UNKNOWN_TRIGGER_KIND__KIND.fill(kind));
		}
	}

	private Reaction reaction(Object json) throws LoadException {
		Map<?, ?> map = only(json, "triggers", "condition", "actions", "elseActions", "from", "to");
		Reaction reaction = new Reaction();
		List<?> triggers = arrayMember(map, "triggers", true);
		int scope = enter(".triggers");
		if (triggers.size() < 1) {
			throw fail(LoadError.MALFORMED, "a reaction needs at least one trigger", I18NConstants.RULE_NO_TRIGGER);
		}
		if (triggers.size() > RuleSet.MAX_TRIGGERS) {
			throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_TRIGGERS + " triggers",
				I18NConstants.RULE_TOO_MANY_TRIGGERS__MAX.fill(String.valueOf(RuleSet.MAX_TRIGGERS)));
		}
		for (int i = 0; i < triggers.size(); i++) {
			int mark = enter("[" + i + "]");
			reaction._triggers.add(trigger(triggers.get(i)));
			leave(mark);
		}
		leave(scope);
		if (map.containsKey("condition")) {
			int mark = enter(".condition");
			Node condition = root(map.get("condition"), false);
			if (condition._type != Type.BOOLEAN) {
				throw fail(LoadError.TYPE_ERROR, "a condition must be boolean, not " + typeName(condition._type),
					I18NConstants.RULE_CONDITION_TYPE__TYPE.fill(typeName(condition._type)));
			}
			reaction._condition = condition;
			leave(mark);
		}
		List<?> actions = arrayMember(map, "actions", true);
		int mark = enter(".actions");
		actionList(actions, reaction._actions);
		leave(mark);
		List<?> elseActions = arrayMember(map, "elseActions", false);
		if (elseActions != null) {
			mark = enter(".elseActions");
			actionList(elseActions, reaction._elseActions);
			leave(mark);
		}
		List<?> from = arrayMember(map, "from", false);
		if (from != null) {
			mark = enter(".from");
			if (_current._states.isEmpty()) {
				throw fail(LoadError.FORBIDDEN, "'from' in a rule without states",
					I18NConstants.RULE_FROM_WITHOUT_STATES);
			}
			reaction._fromMask = 0;
			for (int i = 0; i < from.size(); i++) {
				int item = enter("[" + i + "]");
				if (!(from.get(i) instanceof String id) || bytes(id) > 24) {
					throw fail(LoadError.MALFORMED, "a state id expected", I18NConstants.RULE_STATE_ID_EXPECTED);
				}
				int s = _current.findState(id);
				if (s < 0) {
					throw fail(LoadError.UNKNOWN_REFERENCE, "no state '" + id + "' in the rule",
						I18NConstants.RULE_NO_STATE__ID.fill(id));
				}
				reaction._fromMask |= 1 << s;
				leave(item);
			}
			leave(mark);
		}
		String to = stringMember(map, "to", false, 1, 24);
		if (to != null) {
			mark = enter(".to");
			if (_current._states.isEmpty()) {
				throw fail(LoadError.FORBIDDEN, "'to' in a rule without states", I18NConstants.RULE_TO_WITHOUT_STATES);
			}
			if (elseActions != null) {
				throw fail(LoadError.FORBIDDEN, "'elseActions' together with 'to'", I18NConstants.RULE_ELSE_WITH_TO);
			}
			int s = _current.findState(to);
			if (s < 0) {
				throw fail(LoadError.UNKNOWN_REFERENCE, "no state '" + to + "' in the rule",
					I18NConstants.RULE_NO_STATE__ID.fill(to));
			}
			reaction._to = s;
			leave(mark);
		}
		return reaction;
	}

	// ---- Expressions ------------------------------------------------------------------------

	private static String typeName(Type type) {
		return type == null ? "invalid" : type.externalName();
	}

	private void allocNodes(int count) throws LoadException {
		if (_expressionNodes + count > RuleSet.MAX_NODES_PER_EXPRESSION) {
			throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_NODES_PER_EXPRESSION + " nodes in an expression",
				I18NConstants.RULE_TOO_MANY_NODES__MAX.fill(String.valueOf(RuleSet.MAX_NODES_PER_EXPRESSION)));
		}
		if (_totalNodes + count > RuleSet.MAX_NODES) {
			throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_NODES + " expression nodes in the image",
				I18NConstants.RULE_TOO_MANY_NODES_IN_IMAGE__MAX.fill(String.valueOf(RuleSet.MAX_NODES)));
		}
		_totalNodes += count;
		_expressionNodes += count;
	}

	private Node root(Object json, boolean match) throws LoadException {
		return root(json, match ? RootKind.MATCH : RootKind.NORMAL);
	}

	private Node root(Object json, RootKind kind) throws LoadException {
		_rootKind = kind;
		_expressionNodes = 0;
		allocNodes(1);
		String path = _path.toString();
		Node node = expr(json, 1, false);
		node._root = _set._roots.size();
		_set._roots.add(node);
		_set._rootPaths.add(path);
		return node;
	}

	/**
	 * An integer literal in 0..2^32-1 as an enumeration literal (spec 4.8: the partner of an
	 * enumeration); <code>null</code> for another node.
	 */
	private static Node retyped(Node node) {
		if (node._kind != Kind.LITERAL || node._type != Type.INTEGER || node._literal.integer() < 0
			|| node._literal.integer() > 4294967295L) {
			return null;
		}
		return new Node(Kind.LITERAL, null, null, Type.ENUMERATION, List.of(), Val.ofEnum(node._literal.integer()), null,
			-1);
	}

	/** The root retyped as an enumeration literal if it is an integer literal in range. */
	private Node retypeRoot(Node root) {
		Node retyped = retyped(root);
		if (retyped == null) {
			return root;
		}
		retyped._root = root._root;
		_set._roots.set(root._root, retyped);
		return retyped;
	}

	/**
	 * Whether the datapoint is a parameter datapoint of the rule being compiled
	 * (<code>rule/&lt;its id&gt;/...</code>): the rule names its own parameters as parameters (8.6).
	 */
	private boolean ownParameterDatapoint(String name) {
		if (_rule < 0 || _rule >= _ruleIds.size() || !name.startsWith(RuleSet.PARAMETER_DATAPOINT_PREFIX)) {
			return false;
		}
		String prefix = RuleSet.PARAMETER_DATAPOINT_PREFIX + _ruleIds.get(_rule) + "/";
		return name.startsWith(prefix);
	}

	/**
	 * The member <code>datapoint</code> of an action or trigger: refused when there is no such
	 * datapoint (<code>unknownReference</code>) or it is a parameter datapoint of the rule itself
	 * (<code>forbidden</code>).
	 */
	private String datapointMember(Map<?, ?> map) throws LoadException {
		String name = stringMember(map, "datapoint", true, 1, MAX_NAME);
		int mark = enter(".datapoint");
		if (_datapoints.type(name) == null) {
			throw fail(LoadError.UNKNOWN_REFERENCE, "no datapoint '" + name + "'",
				I18NConstants.RULE_NO_DATAPOINT__NAME.fill(DatapointId.of(name)));
		}
		if (ownParameterDatapoint(name)) {
			throw fail(LoadError.FORBIDDEN,
				"'" + name + "' is a parameter of this rule: use the parameter (setParameter, $id)",
					I18NConstants.RULE_OWN_PARAMETER_DATAPOINT__NAME.fill(DatapointId.of(name)));
		}
		leave(mark);
		return name;
	}

	/**
	 * A minus applied directly to an integer, real or duration literal is part of the literal (spec
	 * 4.8): a chain of unary minus nodes ending in such a literal compiles to one literal;
	 * <code>null</code> for another operand.
	 */
	private Node foldedNegation(Object operand, int depth) throws LoadException {
		Object inner = operand;
		int minus = 1;
		while (true) {
			if (!(inner instanceof Map<?, ?> map) || !(map.get("kind") instanceof String kind)) {
				return null;
			}
			if (!kind.equals("unary")) {
				break;
			}
			if (!"-".equals(map.get("op"))) {
				return null;
			}
			only(map, "kind", "op", "operand");
			if (!map.containsKey("operand")) {
				return null;
			}
			minus++;
			inner = map.get("operand");
		}
		Map<?, ?> literal = (Map<?, ?>) inner;
		Object type = literal.get("type");
		if (!"literal".equals(literal.get("kind"))
			|| !("integer".equals(type) || "real".equals(type) || "duration".equals(type))) {
			return null;
		}
		int mark = enter(".operand");
		Node node = expr(inner, depth, false);
		leave(mark);
		if (minus % 2 == 0) {
			return node;
		}
		Val value = node._literal;
		Val negated = node._type == Type.REAL ? Val.ofReal(-value.real())
			: node._type == Type.DURATION ? Val.ofDuration(-value.integer()) : Val.ofInt(-value.integer());
		return new Node(Kind.LITERAL, null, null, node._type, List.of(), negated, null, -1);
	}

	private Node expr(Object json, int depth, boolean timerAllowed) throws LoadException {
		if (depth > RuleSet.MAX_DEPTH) {
			throw fail(LoadError.LIMIT_EXCEEDED, "an expression deeper than " + RuleSet.MAX_DEPTH,
				I18NConstants.RULE_TOO_DEEP__MAX.fill(String.valueOf(RuleSet.MAX_DEPTH)));
		}
		if (!(json instanceof Map<?, ?> map)) {
			throw fail(LoadError.MALFORMED, "an expression node must be an object",
				I18NConstants.RULE_EXPRESSION_NOT_AN_OBJECT);
		}
		String kind = stringMember(map, "kind", true, 1, 15);
		switch (kind) {
			case "literal": {
				only(map, "kind", "type", "value");
				String typeText = stringMember(map, "type", true, 1, 23);
				Type type = Type.fromName(typeText);
				if (type == null) {
					enter(".type");
					throw fail(LoadError.MALFORMED, "unknown type '" + typeText + "'",
						I18NConstants.RULE_UNKNOWN_TYPE__TYPE.fill(typeText));
				}
				if (!map.containsKey("value")) {
					throw fail(LoadError.MALFORMED, "member 'value' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("value"));
				}
				int mark = enter(".value");
				Val value = literalValue(map.get("value"), type);
				leave(mark);
				// Normalization (spec 4.8): an enumeration literal is the integer of its element number
				// (its partner gives it back the type, retyped()); a controlled literal is the call
				// controlled(bit, n).
				if (type == Type.ENUMERATION) {
					return new Node(Kind.LITERAL, null, null, Type.INTEGER, List.of(), Val.ofInt(value.integer()), null, -1);
				}
				if (type == Type.CONTROLLED) {
					if (depth + 1 > RuleSet.MAX_DEPTH) {
						throw fail(LoadError.LIMIT_EXCEEDED,
							"an expression deeper than " + RuleSet.MAX_DEPTH + " (a controlled literal is a call)",
								I18NConstants.RULE_TOO_DEEP_CONTROLLED__MAX.fill(String.valueOf(RuleSet.MAX_DEPTH)));
					}
					allocNodes(2);
					Node bit = new Node(Kind.LITERAL, null, null, Type.BOOLEAN, List.of(),
						Val.ofBool(value.controlled().control()), null, -1);
					Node number = new Node(Kind.LITERAL, null, null, Type.INTEGER, List.of(),
						Val.ofInt(value.controlled().value()), null, -1);
					return new Node(Kind.CALL, null, Fn.CONTROLLED, Type.CONTROLLED, List.of(bit, number), null, null, -1);
				}
				return new Node(Kind.LITERAL, null, null, type, List.of(), value, null, -1);
			}
			case "datapoint": {
				only(map, "kind", "name");
				String name = stringMember(map, "name", true, 1, MAX_NAME);
				if (_rootKind == RootKind.CONSTANT) {
					throw fail(LoadError.FORBIDDEN, "a datapoint in a silence duration (it may use the rule's parameters)",
						I18NConstants.RULE_DATAPOINT_IN_SILENCE);
				}
				ValueType type = _datapoints.type(name);
				if (type == null) {
					enter(".name");
					throw fail(LoadError.UNKNOWN_REFERENCE, "no datapoint '" + name + "'",
						I18NConstants.RULE_NO_DATAPOINT__NAME.fill(DatapointId.of(name)));
				}
				if (ownParameterDatapoint(name)) {
					enter(".name");
					throw fail(LoadError.FORBIDDEN,
						"'" + name + "' is a parameter of this rule: reference it as a parameter ($id)",
							I18NConstants.RULE_OWN_PARAMETER_REFERENCE__NAME.fill(DatapointId.of(name)));
				}
				return new Node(Kind.DATAPOINT, null, null, type.expressionType(), List.of(), null, name, -1);
			}
			case "parameter": {
				only(map, "kind", "id");
				String id = stringMember(map, "id", true, 1, 24);
				int p = _current.findParameter(id);
				if (p == 0) {
					enter(".id");
					throw fail(LoadError.FORBIDDEN, "the enable switch cannot be referenced",
						I18NConstants.RULE_ENABLE_REFERENCED);
				}
				if (p < 0) {
					enter(".id");
					throw fail(LoadError.UNKNOWN_REFERENCE, "no parameter '" + id + "' in the rule",
						I18NConstants.RULE_NO_PARAMETER__ID.fill(id));
				}
				return new Node(Kind.PARAMETER, null, null, _current._parameters.get(p)._type, List.of(), null, id, p);
			}
			case "timer": {
				only(map, "kind", "id");
				if (!timerAllowed) {
					throw fail(LoadError.FORBIDDEN, "a timer node outside running() or remaining()",
						I18NConstants.RULE_TIMER_NODE);
				}
				String id = stringMember(map, "id", true, 1, 24);
				int t = _current.findTimer(id);
				if (t < 0) {
					enter(".id");
					throw fail(LoadError.UNKNOWN_REFERENCE, "no timer '" + id + "' in the rule",
						I18NConstants.RULE_NO_TIMER__ID.fill(id));
				}
				return new Node(Kind.TIMER, null, null, null, List.of(), null, id, t);
			}
			case "unary": {
				only(map, "kind", "op", "operand");
				String opText = stringMember(map, "op", true, 1, 7);
				Op op;
				if (opText.equals("-")) {
					op = Op.NEG;
				} else if (opText.equals("not")) {
					op = Op.NOT;
				} else {
					enter(".op");
					throw fail(LoadError.MALFORMED, "unknown unary operator '" + opText + "'",
						I18NConstants.RULE_UNKNOWN_UNARY__OP.fill(opText));
				}
				if (!map.containsKey("operand")) {
					throw fail(LoadError.MALFORMED, "member 'operand' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("operand"));
				}
				if (op == Op.NEG) {
					Node folded = foldedNegation(map.get("operand"), depth);
					if (folded != null) {
						return folded;
					}
				}
				allocNodes(1);
				int mark = enter(".operand");
				Node operand = expr(map.get("operand"), depth + 1, false);
				leave(mark);
				Type t = operand._type;
				if (op == Op.NOT ? t != Type.BOOLEAN : !(t != null && (t.isNumeric() || t == Type.DURATION))) {
					throw fail(LoadError.TYPE_ERROR, "operator " + opText + " on " + typeName(t),
						I18NConstants.RULE_UNARY_TYPE__OP_TYPE.fill(opText, typeName(t)));
				}
				return new Node(Kind.UNARY, op, null, t, List.of(operand), null, null, -1);
			}
			case "binary": {
				only(map, "kind", "op", "left", "right");
				String opText = stringMember(map, "op", true, 1, 7);
				Op op = Op.binary(opText);
				if (op == null) {
					enter(".op");
					throw fail(LoadError.MALFORMED, "unknown binary operator '" + opText + "'",
						I18NConstants.RULE_UNKNOWN_BINARY__OP.fill(opText));
				}
				if (!map.containsKey("left")) {
					throw fail(LoadError.MALFORMED, "member 'left' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("left"));
				}
				if (!map.containsKey("right")) {
					throw fail(LoadError.MALFORMED, "member 'right' missing",
						I18NConstants.RULE_MEMBER_MISSING__NAME.fill("right"));
				}
				allocNodes(2);
				int mark = enter(".left");
				Node left = expr(map.get("left"), depth + 1, false);
				leave(mark);
				mark = enter(".right");
				Node right = expr(map.get("right"), depth + 1, false);
				leave(mark);
				if (op == Op.EQ || op == Op.NE) {
					// An integer literal compared with an enumeration is an enumeration literal (4.8).
					Node retyped;
					if (left._type == Type.ENUMERATION && (retyped = retyped(right)) != null) {
						right = retyped;
					} else if (right._type == Type.ENUMERATION && (retyped = retyped(left)) != null) {
						left = retyped;
					}
				}
				Type t = binaryType(op, left._type, right._type);
				if (t == null) {
					throw fail(LoadError.TYPE_ERROR,
						"operator " + opText + " on " + typeName(left._type) + " and " + typeName(right._type),
							I18NConstants.RULE_BINARY_TYPE__OP_LEFT_RIGHT.fill(opText, typeName(left._type), typeName(right._type)));
				}
				return new Node(Kind.BINARY, op, null, t, List.of(left, right), null, null, -1);
			}
			case "call":
				only(map, "kind", "function", "args");
				return call(map, depth);
			default:
				enter(".kind");
				throw fail(LoadError.MALFORMED, "unknown expression kind '" + kind + "'",
					I18NConstants.RULE_UNKNOWN_EXPRESSION_KIND__KIND.fill(kind));
		}
	}

	private static boolean numeric(Type t) {
		return t == Type.INTEGER || t == Type.REAL;
	}

	private static boolean ordered(Type t) {
		return numeric(t) || t == Type.DURATION || t == Type.TIME_OF_DAY || t == Type.DATE || t == Type.DATE_TIME
			|| t == Type.STRING;
	}

	/** The common type of two ordered operands (a numeric mix promotes to real); null if none. */
	static Type commonOrdered(Type a, Type b) {
		if (numeric(a) && numeric(b)) {
			return a == Type.INTEGER && b == Type.INTEGER ? Type.INTEGER : Type.REAL;
		}
		return a == b && a != null && ordered(a) ? a : null;
	}

	/** The common type of two operands of any type; null if none. */
	static Type commonAny(Type a, Type b) {
		if (numeric(a) && numeric(b)) {
			return a == Type.INTEGER && b == Type.INTEGER ? Type.INTEGER : Type.REAL;
		}
		return a == b ? a : null;
	}

	private static boolean equalityComparable(Type a, Type b) {
		if (numeric(a) && numeric(b)) {
			return true;
		}
		if ((a == Type.ENUMERATION && b == Type.INTEGER) || (a == Type.INTEGER && b == Type.ENUMERATION)) {
			return true;
		}
		return a == b && a != null;
	}

	private static Type binaryType(Op op, Type a, Type b) {
		switch (op) {
			case OR:
			case AND:
				return a == Type.BOOLEAN && b == Type.BOOLEAN ? Type.BOOLEAN : null;
			case EQ:
			case NE:
				return equalityComparable(a, b) ? Type.BOOLEAN : null;
			case LT:
			case LE:
			case GT:
			case GE:
				return commonOrdered(a, b) != null ? Type.BOOLEAN : null;
			case ADD:
			case SUB:
				if (numeric(a) && numeric(b)) {
					return a == Type.INTEGER && b == Type.INTEGER ? Type.INTEGER : Type.REAL;
				}
				if (a == Type.DURATION && b == Type.DURATION) {
					return Type.DURATION;
				}
				if ((a == Type.TIME_OF_DAY || a == Type.DATE_TIME) && b == Type.DURATION) {
					return a;
				}
				if (op == Op.SUB && ((a == Type.TIME_OF_DAY && b == Type.TIME_OF_DAY)
					|| (a == Type.DATE_TIME && b == Type.DATE_TIME) || (a == Type.DATE && b == Type.DATE))) {
					return Type.DURATION;
				}
				if (op == Op.ADD && a == Type.STRING && b == Type.STRING) {
					return Type.STRING;
				}
				return null;
			case MUL:
				if (numeric(a) && numeric(b)) {
					return a == Type.INTEGER && b == Type.INTEGER ? Type.INTEGER : Type.REAL;
				}
				if ((a == Type.DURATION && numeric(b)) || (numeric(a) && b == Type.DURATION)) {
					return Type.DURATION;
				}
				return null;
			case DIV:
				if (numeric(a) && numeric(b)) {
					return Type.REAL;
				}
				if (a == Type.DURATION && numeric(b)) {
					return Type.DURATION;
				}
				if (a == Type.DURATION && b == Type.DURATION) {
					return Type.REAL;
				}
				return null;
			case MOD:
				if (a == Type.INTEGER && b == Type.INTEGER) {
					return Type.INTEGER;
				}
				if (a == Type.DURATION && b == Type.DURATION) {
					return Type.DURATION;
				}
				return null;
			default:
				return null;
		}
	}

	private Node call(Map<?, ?> map, int depth) throws LoadException {
		String name = stringMember(map, "function", true, 1, 23);
		Fn fn = Fn.fromName(name);
		if (fn == null) {
			enter(".function");
			throw fail(LoadError.MALFORMED, "unknown function '" + name + "'",
				I18NConstants.RULE_UNKNOWN_FUNCTION__NAME.fill(name));
		}
		if (_rootKind != RootKind.NORMAL
			&& (fn.isTime() || fn.isHistory() || fn == Fn.RUNNING || fn == Fn.REMAINING || fn == Fn.STATE)) {
			throw fail(LoadError.FORBIDDEN,
				name + "() in a " + (_rootKind == RootKind.MATCH ? "match condition" : "silence duration"),
				(_rootKind == RootKind.MATCH ? I18NConstants.RULE_FUNCTION_IN_MATCH__NAME
					: I18NConstants.RULE_FUNCTION_IN_SILENCE__NAME).fill(name));
		}
		List<?> args = arrayMember(map, "args", true);
		enter(".args");
		int argc = args.size();
		if (argc > RuleSet.MAX_ARGUMENTS) {
			throw fail(LoadError.LIMIT_EXCEEDED, "more than " + RuleSet.MAX_ARGUMENTS + " arguments",
				I18NConstants.RULE_TOO_MANY_ARGUMENTS__MAX.fill(String.valueOf(RuleSet.MAX_ARGUMENTS)));
		}
		int[] arity = arity(fn);
		if (argc < arity[0] || argc > arity[1]) {
			throw fail(LoadError.TYPE_ERROR,
				name + "() takes " + arity[0] + ".." + arity[1] + " arguments, not " + argc,
					I18NConstants.RULE_ARITY__NAME_MIN_MAX_COUNT.fill(name, String.valueOf(arity[0]), String.valueOf(arity[1]),
						String.valueOf(argc)));
		}

		// Structural constraints on arguments.
		Object first = argc > 0 ? args.get(0) : null;
		String firstKind = first instanceof Map<?, ?> f && f.get("kind") instanceof String k ? k : "";
		if (fn.isHistory() && !firstKind.equals("datapoint")) {
			throw fail(LoadError.TYPE_ERROR, "the first argument of " + name + "() must be a datapoint reference",
				I18NConstants.RULE_HISTORY_ARGUMENT__NAME.fill(name));
		}
		if ((fn == Fn.RUNNING || fn == Fn.REMAINING) && !firstKind.equals("timer")) {
			throw fail(LoadError.TYPE_ERROR, "the argument of " + name + "() must be a timer",
				I18NConstants.RULE_TIMER_ARGUMENT__NAME.fill(name));
		}
		int stateRule = _rule;
		if (fn == Fn.STATE) {
			if (argc == 0) {
				if (_current._states.isEmpty()) {
					throw fail(LoadError.FORBIDDEN, "state() in a rule without states",
						I18NConstants.RULE_STATE_WITHOUT_STATES);
				}
			} else {
				Map<?, ?> literal = (Map<?, ?>) first;
				if (!firstKind.equals("literal") || !"string".equals(literal.get("type")) || !literal.containsKey("value")) {
					throw fail(LoadError.FORBIDDEN, "the argument of state() must be one string literal",
						I18NConstants.RULE_STATE_ARGUMENT);
				}
				if (!(literal.get("value") instanceof String id) || bytes(id) > 32) {
					throw fail(LoadError.UNKNOWN_REFERENCE, "no rule with that id",
						I18NConstants.RULE_STATE_NO_SUCH_RULE);
				}
				int target = _ruleIds.lastIndexOf(id);
				if (target < 0) {
					throw fail(LoadError.UNKNOWN_REFERENCE, "no rule '" + id + "'",
						I18NConstants.RULE_NO_RULE__ID.fill(id));
				}
				if (!_ruleHasStates.get(target).booleanValue()) {
					throw fail(LoadError.UNKNOWN_REFERENCE, "rule '" + id + "' has no states",
						I18NConstants.RULE_RULE_WITHOUT_STATES__ID.fill(id));
				}
				stateRule = target;
			}
		}

		// The arguments.
		if (argc > 0) {
			allocNodes(argc);
		}
		List<Node> nodes = new ArrayList<>(argc);
		Type[] t = new Type[argc];
		int argsPath = _path.length();
		for (int i = 0; i < argc; i++) {
			enter("[" + i + "]");
			boolean timerAllowed = (fn == Fn.RUNNING || fn == Fn.REMAINING) && i == 0;
			Node arg = expr(args.get(i), depth + 1, timerAllowed);
			nodes.add(arg);
			t[i] = arg._type;
			leave(argsPath);
		}
		// An integer literal next to an enumeration in if() and coalesce(), and the argument of
		// toInt(), is an enumeration literal (4.8).
		if (fn == Fn.IF) {
			partners(nodes, t, 1, 2);
		} else if (fn == Fn.COALESCE) {
			partners(nodes, t, 0, 1);
		} else if (fn == Fn.TO_INT) {
			Node retyped = retyped(nodes.get(0));
			if (retyped != null) {
				nodes.set(0, retyped);
				t[0] = Type.ENUMERATION;
			}
		}
		if (fn == Fn.SINCE_CHANGE || fn == Fn.CHANGED_WITHIN) {
			_set._tracked.add(nodes.get(0)._name);
		}

		Type result = callType(fn, argc, t);
		if (result == null) {
			StringBuilder types = new StringBuilder();
			for (int k = 0; k < argc; k++) {
				types.append(k > 0 ? ", " : "").append(typeName(t[k]));
			}
			throw fail(LoadError.TYPE_ERROR, name + "(" + types + ")",
				I18NConstants.RULE_CALL_TYPES__NAME_TYPES.fill(name, types.toString()));
		}
		_path.setLength(argsPath - ".args".length());
		return new Node(Kind.CALL, null, fn, result, List.copyOf(nodes), null, null, fn == Fn.STATE ? stateRule : -1);
	}

	private static void partners(List<Node> nodes, Type[] t, int x, int y) {
		Node retyped;
		if (t[x] == Type.ENUMERATION && (retyped = retyped(nodes.get(y))) != null) {
			nodes.set(y, retyped);
			t[y] = Type.ENUMERATION;
		} else if (t[y] == Type.ENUMERATION && (retyped = retyped(nodes.get(x))) != null) {
			nodes.set(x, retyped);
			t[x] = Type.ENUMERATION;
		}
	}

	/**
	 * The most arguments the given function takes (<code>spec/rules.md</code> 4.7); 0 for a function
	 * without arguments.
	 */
	public static int maxArguments(Fn fn) {
		return arity(fn)[1];
	}

	private static int[] arity(Fn fn) {
		switch (fn) {
			case NOW:
			case TODAY:
			case TIME:
			case SUNRISE:
			case SUNSET:
			case DAWN:
			case DUSK:
			case SUN_AZIMUTH:
			case SUN_ELEVATION:
				return new int[] { 0, 0 };
			case WEEKDAY:
			case STATE:
				return new int[] { 0, 1 };
			case COALESCE:
			case ROUND_TO:
			case CHANGED_WITHIN:
			case CONTAINS:
			case STARTS_WITH:
			case ENDS_WITH:
			case CONTROLLED:
				return new int[] { 2, 2 };
			case IF:
			case BETWEEN:
			case CLAMP:
			case IN_WINDOW:
			case RGB:
				return new int[] { 3, 3 };
			case HYSTERESIS:
			case RGBW:
				return new int[] { 4, 4 };
			case MIN:
			case MAX:
				return new int[] { 2, 8 };
			default:
				return new int[] { 1, 1 };
		}
	}

	private static Type callType(Fn fn, int argc, Type[] t) {
		switch (fn) {
			case IF:
				return t[0] == Type.BOOLEAN ? commonAny(t[1], t[2]) : null;
			case KNOWN:
				return Type.BOOLEAN;
			case COALESCE:
				return commonAny(t[0], t[1]);
			case BETWEEN:
				return commonOrdered(t[0], t[1]) != null && commonOrdered(commonOrdered(t[0], t[1]), t[2]) != null
					? Type.BOOLEAN : null;
			case HYSTERESIS: {
				boolean numbers = numeric(t[0]) && numeric(t[1]) && numeric(t[2]);
				boolean durations = t[0] == Type.DURATION && t[1] == Type.DURATION && t[2] == Type.DURATION;
				return (numbers || durations) && t[3] == Type.BOOLEAN ? Type.BOOLEAN : null;
			}
			case ABS:
				return numeric(t[0]) || t[0] == Type.DURATION ? t[0] : null;
			case MIN:
			case MAX:
			case CLAMP: {
				Type c = t[0];
				for (int k = 1; k < argc && c != null; k++) {
					c = commonOrdered(c, t[k]);
				}
				return c != null && ordered(c) ? c : null;
			}
			case ROUND:
			case FLOOR:
			case CEIL:
			case TRUNC:
				return numeric(t[0]) ? Type.INTEGER : null;
			case ROUND_TO:
				return numeric(t[0]) && numeric(t[1]) ? Type.REAL : null;
			case TO_INT:
				return t[0] == Type.ENUMERATION || t[0] == Type.BOOLEAN ? Type.INTEGER : null;
			case NOW:
				return Type.DATE_TIME;
			case TODAY:
				return Type.DATE;
			case TIME:
				return Type.TIME_OF_DAY;
			case WEEKDAY:
				return argc == 0 || t[0] == Type.DATE || t[0] == Type.DATE_TIME || t[0] == Type.TIME_OF_DAY ? Type.INTEGER
					: null;
			case HOUR:
			case MINUTE:
			case SECOND:
				return t[0] == Type.TIME_OF_DAY || t[0] == Type.DATE_TIME ? Type.INTEGER : null;
			case YEAR:
			case MONTH:
			case DAY:
				return t[0] == Type.DATE || t[0] == Type.DATE_TIME ? Type.INTEGER : null;
			case DATE_OF:
				return t[0] == Type.DATE_TIME ? Type.DATE : null;
			case TIME_OF:
				return t[0] == Type.DATE_TIME ? Type.TIME_OF_DAY : null;
			case IN_WINDOW:
				return (t[0] == Type.TIME_OF_DAY || t[0] == Type.DATE_TIME) && t[1] == Type.TIME_OF_DAY
					&& t[2] == Type.TIME_OF_DAY ? Type.BOOLEAN : null;
			case SUNRISE:
			case SUNSET:
			case DAWN:
			case DUSK:
				return Type.TIME_OF_DAY;
			case SUN_AZIMUTH:
			case SUN_ELEVATION:
				return Type.REAL;
			case SINCE_UPDATE:
			case SINCE_CHANGE:
				return Type.DURATION;
			case CHANGED_WITHIN:
				return t[1] == Type.DURATION ? Type.BOOLEAN : null;
			case LAST_KNOWN:
				return t[0];
			case QUALITY:
				return Type.STRING;
			case RUNNING:
				return Type.BOOLEAN;
			case REMAINING:
				return Type.DURATION;
			case STATE:
				return argc == 0 || t[0] == Type.STRING ? Type.STRING : null;
			case LENGTH:
				return t[0] == Type.STRING ? Type.INTEGER : null;
			case CONTAINS:
			case STARTS_WITH:
			case ENDS_WITH:
				return t[0] == Type.STRING && t[1] == Type.STRING ? Type.BOOLEAN : null;
			case TO_STRING:
				return Type.STRING;
			case RGB:
			case RGBW:
				for (int k = 0; k < argc; k++) {
					if (t[k] != Type.INTEGER) {
						return null;
					}
				}
				return Type.COLOR;
			case RED:
			case GREEN:
			case BLUE:
			case WHITE:
				return t[0] == Type.COLOR ? Type.INTEGER : null;
			case CONTROLLED:
				return t[0] == Type.BOOLEAN && t[1] == Type.INTEGER ? Type.CONTROLLED : null;
			case CONTROL_BIT:
				return t[0] == Type.CONTROLLED ? Type.BOOLEAN : null;
			case CONTROL_VALUE:
				return t[0] == Type.CONTROLLED ? Type.INTEGER : null;
			default:
				return null;
		}
	}

}
