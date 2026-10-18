/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.Type;

/**
 * Reads the members <code>rules</code> and <code>schedules</code> of a runtime image (their parsed
 * JSON) into a project's model (haumacher/ehome #57), the inverse of {@link RuleExport}: the
 * expressions are stored as their canonical text ({@link RuleText#print(Object)}), the literal values
 * as literals, the names in every application language.
 *
 * <p>
 * Used to bring rules written as JSON (the conformance corpus of HomeControl) into E-Home; the caller
 * provides the transaction. Members the model does not keep (a <code>description</code> or
 * <code>comment</code> below a rule) are named in the {@link #getNotes() notes}.
 * </p>
 */
public final class RuleImport {

	private final List<String> _notes = new ArrayList<>();

	/**
	 * What the import did not keep.
	 */
	public List<String> getNotes() {
		return _notes;
	}

	/**
	 * Adds the given rules (the member <code>rules</code> of an image) behind the rules of the
	 * project, in their order.
	 */
	public List<TLObject> importRules(TLObject project, List<?> rules) {
		List<TLObject> all = RuleExport.objects(project.tValueByName("rules"));
		long order = 0;
		for (TLObject rule : all) {
			if (rule.tValueByName("order") instanceof Number number) {
				order = Math.max(order, number.longValue());
			}
		}
		List<TLObject> result = new ArrayList<>();
		for (Object item : rules) {
			TLObject rule = rule((Map<?, ?>) item);
			rule.tUpdateByName("order", Integer.valueOf((int) ++order));
			result.add(rule);
		}
		all.addAll(result);
		project.tUpdateByName("rules", all);
		return result;
	}

	/**
	 * Adds the given schedule entries (the member <code>schedules</code> of an image) behind the
	 * entries of the project.
	 */
	public List<TLObject> importSchedules(TLObject project, List<?> schedules) {
		List<TLObject> all = RuleExport.objects(project.tValueByName("schedules"));
		List<TLObject> result = new ArrayList<>();
		for (Object item : schedules) {
			result.add(schedule((Map<?, ?>) item));
		}
		all.addAll(result);
		project.tUpdateByName("schedules", all);
		return result;
	}

	/**
	 * Adds the rules and schedule entries of a runtime image (the JSON text of an image, or of any
	 * object with the members <code>rules</code> and <code>schedules</code>, such as a case of the rule
	 * corpus) to the project (haumacher/ehome #60): the rules behind the project's rules, the entries
	 * behind its entries. The caller provides the transaction.
	 *
	 * @return What was imported: the rules and the schedule entries.
	 * @throws TopLogicException
	 *         If the text is no JSON object, or has neither rules nor schedule entries, or a rule or
	 *         entry cannot be read.
	 */
	public Imported importImage(TLObject project, String text) {
		Object json;
		try {
			json = Json.parse(text, 100);
		} catch (Json.JsonException ex) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_NO_JSON__REASON.fill(ex.getMessage()), ex);
		}
		if (!(json instanceof Map<?, ?> image)
			|| !(image.get("rules") instanceof List<?> || image.get("schedules") instanceof List<?>)) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_NO_RULES);
		}
		try {
			List<TLObject> rules = image.get("rules") instanceof List<?> list ? importRules(project, list) : List.of();
			List<TLObject> schedules =
				image.get("schedules") instanceof List<?> list ? importSchedules(project, list) : List.of();
			return new Imported(rules, schedules);
		} catch (RuntimeException ex) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_UNREADABLE__REASON.fill(String.valueOf(ex.getMessage())),
				ex);
		}
	}

	/**
	 * The result of {@link RuleImport#importImage(TLObject, String)}: the rules and schedule entries
	 * created.
	 */
	public record Imported(List<TLObject> rules, List<TLObject> schedules) {
		// A record.
	}

	// ---- rules -------------------------------------------------------------------------------

	private TLObject rule(Map<?, ?> json) {
		TLObject rule = create("Rule");
		rule.tUpdateByName("identifier", json.get("id"));
		rule.tUpdateByName("name", label(json.get("name"), "rule " + json.get("id")));
		rule.tUpdateByName("description", json.get("description"));
		rule.tUpdateByName("enabled", Boolean.valueOf(!Boolean.FALSE.equals(json.get("enabled"))));
		rule.tUpdateByName("enabledAdjustable", Boolean.valueOf(!Boolean.FALSE.equals(json.get("enabledAdjustable"))));
		notes(json, "rule " + json.get("id"), "description");

		List<TLObject> parameters = new ArrayList<>();
		for (Object item : list(json.get("parameters"))) {
			parameters.add(parameter((Map<?, ?>) item));
		}
		rule.tUpdateByName("parameters", parameters);

		List<TLObject> timers = new ArrayList<>();
		for (Object item : list(json.get("timers"))) {
			Map<?, ?> timer = (Map<?, ?>) item;
			TLObject result = create("RuleTimer");
			result.tUpdateByName("identifier", timer.get("id"));
			result.tUpdateByName("duration", text(timer.get("duration")));
			notes(timer, "timer " + timer.get("id"));
			timers.add(result);
		}
		rule.tUpdateByName("timers", timers);

		List<TLObject> states = new ArrayList<>();
		for (Object item : list(json.get("states"))) {
			Map<?, ?> state = (Map<?, ?>) item;
			TLObject result = create("RuleState");
			result.tUpdateByName("identifier", state.get("id"));
			result.tUpdateByName("entry", actions(state.get("entry")));
			result.tUpdateByName("exit", actions(state.get("exit")));
			notes(state, "state " + state.get("id"));
			states.add(result);
		}
		rule.tUpdateByName("states", states);
		rule.tUpdateByName("initialState", state(states, json.get("initialState")));

		List<TLObject> reactions = new ArrayList<>();
		for (Object item : list(json.get("reactions"))) {
			reactions.add(reaction((Map<?, ?>) item, states));
		}
		rule.tUpdateByName("reactions", reactions);
		return rule;
	}

	private TLObject parameter(Map<?, ?> json) {
		TLObject result = create("RuleParameter");
		result.tUpdateByName("identifier", json.get("id"));
		result.tUpdateByName("title", label(json.get("label"), "parameter " + json.get("id")));
		String typeName = String.valueOf(json.get("type"));
		Type type = Type.fromName(typeName);
		result.tUpdateByName("type", classifier("RuleParameterType", typeName));
		if (type != null) {
			for (String member : new String[] { "default", "min", "max" }) {
				if (json.containsKey(member)) {
					result.tUpdateByName(member, RuleText.printValue(json.get(member), type));
				}
			}
			if (json.get("step") instanceof Number step) {
				Type stepType = type == Type.DURATION ? Type.DURATION
					: step.doubleValue() == Math.rint(step.doubleValue()) && !(step instanceof Double) ? Type.INTEGER
						: Type.REAL;
				result.tUpdateByName("step", RuleText.printValue(step, stepType));
			}
			List<TLObject> choices = new ArrayList<>();
			for (Object item : list(json.get("choices"))) {
				Map<?, ?> choice = (Map<?, ?>) item;
				TLObject created = create("RuleChoice");
				created.tUpdateByName("value", RuleText.printValue(choice.get("value"), type));
				created.tUpdateByName("title", label(choice.get("label"), "choice of parameter " + json.get("id")));
				choices.add(created);
			}
			result.tUpdateByName("choices", choices);
		}
		result.tUpdateByName("unit", json.get("unit"));
		result.tUpdateByName("adjustable", Boolean.valueOf(!Boolean.FALSE.equals(json.get("adjustable"))));
		notes(json, "parameter " + json.get("id"));
		return result;
	}

	private TLObject reaction(Map<?, ?> json, List<TLObject> states) {
		TLObject result = create("Reaction");
		List<TLObject> triggers = new ArrayList<>();
		for (Object item : list(json.get("triggers"))) {
			triggers.add(trigger((Map<?, ?>) item));
		}
		result.tUpdateByName("triggers", triggers);
		result.tUpdateByName("condition", text(json.get("condition")));
		result.tUpdateByName("actions", actions(json.get("actions")));
		result.tUpdateByName("elseActions", actions(json.get("elseActions")));
		List<TLObject> from = new ArrayList<>();
		for (Object id : list(json.get("from"))) {
			TLObject state = state(states, id);
			if (state != null) {
				from.add(state);
			} else {
				_notes.add("unknown state " + id + " in from");
			}
		}
		result.tUpdateByName("from", from);
		result.tUpdateByName("to", state(states, json.get("to")));
		notes(json, "reaction");
		return result;
	}

	private TLObject trigger(Map<?, ?> json) {
		String kind = String.valueOf(json.get("kind"));
		TLObject result;
		switch (kind) {
			case "datapoint":
				result = create("DatapointTrigger");
				result.tUpdateByName("datapoint", json.get("datapoint"));
				result.tUpdateByName("on", classifier("UpdateKind", json.get("on") == null ? "change" : json.get("on")));
				result.tUpdateByName("origin", classifier("UpdateOrigin", json.get("origin") == null ? "any" : json.get("origin")));
				break;
			case "match":
				result = create("MatchTrigger");
				result.tUpdateByName("condition", text(json.get("condition")));
				break;
			case "schedule":
				result = create("ScheduleTrigger");
				result.tUpdateByName("schedule", json.get("schedule"));
				break;
			case "timer":
				result = create("TimerTrigger");
				result.tUpdateByName("timer", json.get("timer"));
				break;
			case "parameter":
				result = create("ParameterTrigger");
				result.tUpdateByName("parameter", json.get("parameter"));
				break;
			case "silence":
				result = create("SilenceTrigger");
				result.tUpdateByName("datapoint", json.get("datapoint"));
				result.tUpdateByName("after", text(json.get("after")));
				break;
			case "start": {
				result = create("StartTrigger");
				Object reasons = json.get("reasons");
				Collection<?> given = reasons instanceof Collection<?> c ? c : List.of("boot", "image", "enable");
				result.tUpdateByName("onBoot", Boolean.valueOf(given.contains("boot")));
				result.tUpdateByName("onImage", Boolean.valueOf(given.contains("image")));
				result.tUpdateByName("onEnable", Boolean.valueOf(given.contains("enable")));
				break;
			}
			default:
				throw new IllegalArgumentException("Unknown trigger kind '" + kind + "'.");
		}
		notes(json, "trigger " + kind);
		return result;
	}

	private List<TLObject> actions(Object json) {
		List<TLObject> result = new ArrayList<>();
		for (Object item : list(json)) {
			result.add(action((Map<?, ?>) item));
		}
		return result;
	}

	private TLObject action(Map<?, ?> json) {
		String kind = String.valueOf(json.get("kind"));
		TLObject result;
		switch (kind) {
			case "write":
				result = create("WriteAction");
				result.tUpdateByName("datapoint", json.get("datapoint"));
				result.tUpdateByName("value", text(json.get("value")));
				result.tUpdateByName("onlyOnChange", Boolean.valueOf(Boolean.TRUE.equals(json.get("onlyOnChange"))));
				break;
			case "startTimer":
			case "restartTimer":
				result = create(kind.equals("startTimer") ? "StartTimerAction" : "RestartTimerAction");
				result.tUpdateByName("timer", json.get("timer"));
				result.tUpdateByName("duration", text(json.get("duration")));
				break;
			case "cancelTimer":
				result = create("CancelTimerAction");
				result.tUpdateByName("timer", json.get("timer"));
				break;
			case "read":
				result = create("ReadAction");
				result.tUpdateByName("datapoint", json.get("datapoint"));
				break;
			case "setParameter":
				result = create("SetParameterAction");
				result.tUpdateByName("parameter", json.get("parameter"));
				result.tUpdateByName("value", text(json.get("value")));
				break;
			case "log":
				result = create("LogAction");
				result.tUpdateByName("level", classifier("LogLevel", json.get("level")));
				List<Object> message = new ArrayList<>();
				for (Object part : list(json.get("message"))) {
					message.add(RuleText.normalize(part));
				}
				result.tUpdateByName("message", RuleText.printMessage(message));
				break;
			default:
				throw new IllegalArgumentException("Unknown action kind '" + kind + "'.");
		}
		notes(json, "action " + kind);
		return result;
	}

	// ---- schedules ---------------------------------------------------------------------------

	private TLObject schedule(Map<?, ?> json) {
		String kind = String.valueOf(json.get("kind"));
		String type = switch (kind) {
			case "weekly" -> "WeeklySchedule";
			case "yearly" -> "YearlySchedule";
			case "dates" -> "DatesSchedule";
			case "astro" -> "AstroSchedule";
			case "interval" -> "IntervalSchedule";
			case "once" -> "OnceSchedule";
			default -> throw new IllegalArgumentException("Unknown schedule kind '" + kind + "'.");
		};
		TLObject result = create(type);
		result.tUpdateByName("identifier", json.get("id"));
		result.tUpdateByName("label", allLanguages(String.valueOf(json.get("label"))));
		result.tUpdateByName("enabled", Boolean.valueOf(!Boolean.FALSE.equals(json.get("enabled"))));
		result.tUpdateByName("enabledAdjustable", Boolean.valueOf(!Boolean.FALSE.equals(json.get("enabledAdjustable"))));
		result.tUpdateByName("missed", classifier("MissedFiring", json.get("missed") == null ? "skip" : json.get("missed")));
		result.tUpdateByName("output", json.get("output"));
		if (json.containsKey("days")) {
			List<TLClassifier> days = new ArrayList<>();
			for (Object day : list(json.get("days"))) {
				days.add(classifier("Weekday", RuleExport.weekday(((Number) day).intValue())));
			}
			result.tUpdateByName("days", days);
		}
		String[][] values = { { "time", "timeOfDay" }, { "notBefore", "timeOfDay" }, { "notAfter", "timeOfDay" },
			{ "from", "timeOfDay" }, { "until", "timeOfDay" }, { "every", "duration" }, { "offset", "duration" },
			{ "at", "dateTime" } };
		for (String[] value : values) {
			if (json.containsKey(value[0])) {
				result.tUpdateByName(value[0], RuleText.printValue(json.get(value[0]), Type.fromName(value[1])));
			}
		}
		if (json.get("month") instanceof Number month) {
			result.tUpdateByName("month", Integer.valueOf(month.intValue()));
		}
		if (json.get("day") instanceof Number day) {
			result.tUpdateByName("day", Integer.valueOf(day.intValue()));
		}
		if (json.containsKey("sun")) {
			result.tUpdateByName("sun", classifier("SunEvent", json.get("sun")));
		}
		if (json.containsKey("dates")) {
			List<String> dates = new ArrayList<>();
			for (Object date : list(json.get("dates"))) {
				dates.add(RuleText.printValue(date, Type.DATE));
			}
			result.tUpdateByName("dates", String.join(", ", dates));
		}
		return result;
	}

	// ---- helpers -----------------------------------------------------------------------------

	private void notes(Map<?, ?> json, String what, String... kept) {
		for (String member : new String[] { "description", "comment" }) {
			if (json.containsKey(member) && !List.of(kept).contains(member)) {
				_notes.add(what + ": the " + member + " is not kept");
			}
		}
	}

	/** The canonical text of an expression: its normalized tree printed (spec/rules.md 4.8). */
	private static String text(Object expression) {
		return expression == null ? null : RuleText.print(RuleText.normalize(expression));
	}

	/**
	 * A label of the image (spec/rules.md 2.2) as a multilingual text: a plain string in every
	 * application language; a language map with, for each application language, the text the
	 * controller shows in that language (the exact tag, the base language, en, the first entry). The
	 * texts of other languages are named in the {@link #getNotes() notes}.
	 */
	private ResKey label(Object json, String what) {
		if (!(json instanceof Map<?, ?> map)) {
			return allLanguages(String.valueOf(json));
		}
		Map<String, String> languages = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			languages.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
		}
		Map<String, String> texts = new TreeMap<>();
		List<String> kept = new ArrayList<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			texts.put(locale.getLanguage(), RuleLoader.resolveLabel(languages, locale.getLanguage()));
			kept.add(locale.getLanguage());
		}
		List<String> dropped = new ArrayList<>();
		for (String tag : languages.keySet()) {
			if (kept.stream().noneMatch(tag::equalsIgnoreCase)) {
				dropped.add(tag);
			}
		}
		if (!dropped.isEmpty()) {
			_notes.add(what + ": the label's texts in " + String.join(", ", dropped) + " are not kept (the languages of E-Home: "
				+ String.join(", ", kept) + ")");
		}
		return FunctionProposal.text(texts);
	}

	private static List<?> list(Object json) {
		return json instanceof List<?> list ? list : List.of();
	}

	private static TLObject state(List<TLObject> states, Object id) {
		for (TLObject state : states) {
			if (state.tValueByName("identifier").equals(id)) {
				return state;
			}
		}
		return null;
	}

	/**
	 * The text in every application language (a text of the image, which has no language).
	 */
	static ResKey allLanguages(String text) {
		Map<String, String> texts = new TreeMap<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			texts.put(locale.getLanguage(), text);
		}
		return FunctionProposal.text(texts);
	}

	static TLClassifier classifier(String enumeration, Object name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:" + enumeration)).getClassifier(String.valueOf(name));
	}

	static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType("ehome.runtime:" + type));
	}

}
