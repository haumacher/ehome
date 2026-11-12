/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.model.util.TLModelI18N;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.Node.Fn;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.ValueType;

/**
 * The TL-Script functions of the rules and schedules of a project (haumacher/ehome #57): the summaries
 * their tables show, the datapoints they may name, and creating, listing and moving their parts.
 *
 * <p>
 * Each function is registered in <code>ehome.runtime.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class RuleFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>ruleSummary(obj)</code>: a line describing a reaction, trigger, action or schedule entry. */
		ruleSummary(1, true),
		/** <code>ruleList(owner, reference)</code>: the parts in the given reference of the owner. */
		ruleList(2, true),
		/**
		 * <code>ruleCreate(owner, reference, type)</code>: a new part of the given type, added at the
		 * end of the given reference of the owner, with an id of its own and the defaults of its kind.
		 */
		ruleCreate(3, false),
		/**
		 * <code>ruleMove(part, delta)</code>: moves the part by the given number of positions within
		 * the list that holds it.
		 */
		ruleMove(2, false),
		/**
		 * <code>ruleMoveVeto(part, delta)</code>: why the part cannot be moved so far, <code>null</code>
		 * if it can.
		 */
		ruleMoveVeto(2, true),
		/**
		 * <code>ruleDatapoints(project)</code>: the datapoints of the project's image the rules may name,
		 * each a map with <code>id</code>, <code>label</code>, <code>type</code>, <code>writable</code>
		 * and <code>binding</code> ({@link RuleFunctions#datapoints(TLObject)}).
		 */
		ruleDatapoints(1, true),
		/**
		 * <code>ruleSuggestions(obj, text, caret)</code>: what a reference, a parameter, a timer, a
		 * state or a function name left unfinished before the caret may name, as far as the field of
		 * obj may name it, as suggestions of a text field
		 * ({@link RuleFunctions#suggestions(TLObject, String, int)}); obj is the edited part of a
		 * rule (or the form's overlay of it, or the draft of a create dialog).
		 */
		ruleSuggestions(3, true),
		/**
		 * <code>ruleImportImage(project, file)</code>: adds the rules, schedule entries and application
		 * datapoints of the uploaded runtime image (JSON) to the project and gives it the image's
		 * location where it has none ({@link RuleImport#importImage(TLObject, String)});
		 * the sentence saying what was imported, followed by what the import did not keep.
		 */
		ruleImportImage(2, false),
		/**
		 * <code>ruleOptions(obj, kind)</code>: the objects a reference of the given kind of a rule or
		 * schedule entry may name ({@link RuleReferences#options(Object, String)}); obj is the edited
		 * part (or the form's overlay of it).
		 */
		ruleOptions(2, true),
		/**
		 * <code>ruleOptionLabel(option, kind)</code>: the label of an option of a reference of the given
		 * kind, with the id the image names it by ({@link RuleReferences#optionLabel(Object, String)}).
		 */
		ruleOptionLabel(2, true),
		/**
		 * <code>ruleDraft(owner, reference, type)</code>: the draft of a create dialog, a transient part
		 * of the given type for the end of the given reference of the owner, with a unique id and the
		 * defaults of its kind ({@link RuleDrafts#draft(TLObject, String, TLClass)}); nothing is stored.
		 */
		ruleDraft(3, false),
		/**
		 * <code>rulePageDraft(project, selected, kind)</code>: the draft of a create dialog of a page, a
		 * section, a chart or a reference line, for the place the pages insert it at for the selected
		 * node ({@link RuleDrafts#pageDraft(TLObject, TLObject, String)}); nothing is stored.
		 */
		rulePageDraft(3, false),
		/**
		 * <code>ruleDraftField(draft, attribute)</code>: whether the create dialog of the draft asks for
		 * the attribute ({@link RuleDrafts#hasField(TLObject, String)}).
		 */
		ruleDraftField(2, true),
		/**
		 * <code>ruleDraftTitle(draft)</code>: the name of the kind the create dialog creates.
		 */
		ruleDraftTitle(1, true),
		/**
		 * <code>ruleAdd(draft)</code>: stores the draft of a create dialog at its place, with the values
		 * entered ({@link RuleDrafts#add(TLObject)}); must run in a transaction.
		 */
		ruleAdd(1, false);

		private final int _arity;

		private final boolean _sideEffectFree;

		Function(int arity, boolean sideEffectFree) {
			_arity = arity;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link RuleFunctions}.
	 */
	protected RuleFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new RuleFunctions(getName(), _function, arguments);
	}

	@Override
	public Object getId() {
		return _function;
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean isSideEffectFree() {
		return _function._sideEffectFree;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (!(arguments[0] instanceof TLObject obj) || !obj.tValid()) {
			if (_function == Function.ruleOptionLabel || _function == Function.ruleDraftTitle) {
				return "";
			}
			if (_function == Function.ruleDraftField) {
				return Boolean.FALSE;
			}
			return _function == Function.ruleList || _function == Function.ruleDatapoints
				|| _function == Function.ruleSuggestions || _function == Function.ruleOptions ? List.of() : null;
		}
		switch (_function) {
			case ruleSummary:
				return summary(obj);
			case ruleList:
				return objects(obj, String.valueOf(arguments[1]));
			case ruleCreate:
				return create(obj, String.valueOf(arguments[1]), (TLClass) arguments[2]);
			case ruleMove:
				move(obj, ((Number) arguments[1]).intValue());
				return obj;
			case ruleMoveVeto:
				return moveVeto(obj, ((Number) arguments[1]).intValue());
			case ruleDatapoints:
				return datapoints(obj);
			case ruleSuggestions:
				return suggestions(obj, arguments[1] == null ? "" : arguments[1].toString(),
					arguments[2] instanceof Number caret ? caret.intValue() : 0);
			case ruleImportImage:
				return importImage(obj, arguments[1]);
			case ruleOptions:
				return RuleReferences.options(obj, String.valueOf(arguments[1]));
			case ruleOptionLabel:
				return RuleReferences.optionLabel(obj, String.valueOf(arguments[1]));
			case ruleDraft:
				return RuleDrafts.draft(obj, String.valueOf(arguments[1]), (TLClass) arguments[2]);
			case rulePageDraft:
				return RuleDrafts.pageDraft(obj, arguments[1] instanceof TLObject selected ? selected : null,
					String.valueOf(arguments[2]));
			case ruleDraftField:
				return Boolean.valueOf(RuleDrafts.hasField(obj, String.valueOf(arguments[1])));
			case ruleDraftTitle:
				return RuleDrafts.title(obj);
			case ruleAdd:
				return RuleDrafts.add(obj);
			default:
				return null;
		}
	}

	/**
	 * Adds the rules, schedule entries and application datapoints of the given file (a runtime image)
	 * to the project ({@link RuleImport#importImage(TLObject, String)}); must run in a transaction.
	 *
	 * @return The sentence saying what was imported, followed by the notes of the import.
	 */
	public static String importImage(TLObject project, Object file) {
		if (!(file instanceof BinaryData data)) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_NOTHING);
		}
		String text;
		try (InputStream in = data.getStream()) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_NO_JSON__REASON.fill(ex.getMessage()), ex);
		}
		RuleImport importer = new RuleImport();
		RuleImport.Imported imported = importer.importImage(project, text);
		StringBuilder result = new StringBuilder(Resources.getInstance().getString(
			I18NConstants.IMPORTED__RULES_SCHEDULES_DATAPOINTS.fill(imported.rules().size(), imported.schedules().size(),
				imported.datapoints().size())));
		for (String note : importer.getNotes()) {
			result.append('\n').append(note);
		}
		return result.toString();
	}

	// ---- summaries ---------------------------------------------------------------------------

	/**
	 * A line describing a reaction, a trigger, an action or a schedule entry in the notation of the
	 * rule language, naming the datapoints, parameters, timers and schedule entries it references by
	 * their labels and ids ({@link DatapointNames}, haumacher/ehome #113).
	 */
	public static String summary(TLObject obj) {
		return summary(obj, DatapointNames.of(RuntimeFunctions.project(obj)), RuleReferences.rule(obj));
	}

	private static String summary(TLObject obj, DatapointNames names, TLObject rule) {
		String type = obj.tType().getName();
		if (type.equals("TimelinePoint")) {
			// The outline of homecontrol spec/rules.md 11.5: 06:00 comfort, sunset+2h<=22:00 night.
			StringBuilder out = new StringBuilder();
			if (obj.tValueByName("sun") instanceof TLClassifier sun) {
				out.append(sun.getName());
				String offset = text(obj, "offset");
				if (offset != null) {
					out.append(offset.startsWith("-") ? "" : "+").append(offset.trim());
				}
				String notBefore = text(obj, "notBefore");
				if (notBefore != null) {
					out.append(">=").append(notBefore.trim());
				}
				String notAfter = text(obj, "notAfter");
				if (notAfter != null) {
					out.append("<=").append(notAfter.trim());
				}
			} else {
				String time = text(obj, "time");
				out.append(time == null ? "-" : time.trim());
			}
			if (obj.tValueByName("state") instanceof TLObject state && state.tValid()) {
				out.append(' ').append(state.tValueByName("identifier"));
			}
			return out.toString();
		}
		if (type.equals("Reaction")) {
			StringBuilder out = new StringBuilder("when ");
			List<String> triggers = new ArrayList<>();
			for (TLObject trigger : RuleExport.objects(obj.tValueByName("triggers"))) {
				triggers.add(summary(trigger, names, rule));
			}
			out.append(triggers.isEmpty() ? "-" : String.join(" | ", triggers));
			String condition = names.expression(rule, text(obj, "condition"));
			if (condition != null) {
				out.append(" if ").append(condition);
			}
			List<String> from = new ArrayList<>();
			for (TLObject state : RuleExport.objects(obj.tValueByName("from"))) {
				from.add(String.valueOf(state.tValueByName("identifier")));
			}
			if (!from.isEmpty()) {
				out.append(" from ").append(String.join(", ", from));
			}
			if (obj.tValueByName("to") instanceof TLObject to) {
				out.append(" to ").append(to.tValueByName("identifier"));
			}
			return out.toString();
		}
		String kind = RuleExport.kind(obj);
		StringBuilder out = new StringBuilder(kind);
		switch (kind) {
			case "datapoint": {
				append(out, " ", reference(names, obj, "datapoint"));
				List<String> flags = new ArrayList<>();
				if (obj.tValueByName("on") instanceof TLClassifier on && !on.getName().equals("change")) {
					flags.add(on.getName());
				}
				if (obj.tValueByName("origin") instanceof TLClassifier origin && !origin.getName().equals("any")) {
					flags.add(origin.getName());
				}
				if (!flags.isEmpty()) {
					out.append(" (").append(String.join(", ", flags)).append(')');
				}
				break;
			}
			case "match":
				append(out, " ", names.expression(rule, text(obj, "condition")));
				break;
			case "silence":
				append(out, " ", reference(names, obj, "datapoint"));
				append(out, " after ", names.expression(rule, text(obj, "after")));
				break;
			case "read":
				append(out, " ", reference(names, obj, "datapoint"));
				break;
			case "schedule":
			case "parameter":
			case "timer":
			case "cancelTimer":
				append(out, " ", reference(names, obj, kind.equals("cancelTimer") ? "timer" : kind));
				break;
			case "start": {
				List<String> reasons = new ArrayList<>();
				for (String[] flag : new String[][] { { "onBoot", "boot" }, { "onImage", "image" }, { "onEnable", "enable" } }) {
					if (!Boolean.FALSE.equals(obj.tValueByName(flag[0]))) {
						reasons.add(flag[1]);
					}
				}
				if (reasons.size() < 3) {
					out.append(" (").append(String.join(", ", reasons)).append(')');
				}
				break;
			}
			case "write":
				append(out, " ", reference(names, obj, "datapoint"));
				append(out, " := ", names.expression(rule, text(obj, "value")));
				if (Boolean.TRUE.equals(obj.tValueByName("onlyOnChange"))) {
					out.append(" (onlyOnChange)");
				}
				break;
			case "startTimer":
			case "restartTimer":
				append(out, " ", reference(names, obj, "timer"));
				append(out, " ", names.expression(rule, text(obj, "duration")));
				break;
			case "setParameter":
				append(out, " ", reference(names, obj, "parameter"));
				append(out, " := ", names.expression(rule, text(obj, "value")));
				break;
			case "log":
				if (obj.tValueByName("level") instanceof TLClassifier level) {
					out.append(' ').append(level.getName());
				}
				append(out, ": ", names.message(rule, text(obj, "message")));
				break;
			case "weekly":
				append(out, " ", days(obj));
				append(out, " ", text(obj, "time"));
				break;
			case "yearly":
				out.append(' ').append(obj.tValueByName("month")).append('-').append(obj.tValueByName("day"));
				append(out, " ", text(obj, "time"));
				break;
			case "dates":
				append(out, " ", text(obj, "dates"));
				append(out, " ", text(obj, "time"));
				break;
			case "astro":
				if (obj.tValueByName("sun") instanceof TLClassifier sun) {
					out.append(' ').append(sun.getName());
				}
				append(out, " ", text(obj, "offset"));
				append(out, " ", days(obj));
				break;
			case "interval":
				append(out, " every ", text(obj, "every"));
				append(out, " ", text(obj, "from"));
				append(out, "..", text(obj, "until"));
				append(out, " ", days(obj));
				break;
			case "once":
				append(out, " ", text(obj, "at"));
				break;
			default:
				break;
		}
		if (obj.tType().getPart("output") != null) {
			// The output of a schedule entry: true from a firing until midnight.
			append(out, " output ", reference(names, obj, "output"));
		}
		return out.toString();
	}

	private static String days(TLObject entry) {
		List<String> days = new ArrayList<>();
		for (Object day : RuleExport.objects(entry.tValueByName("days"))) {
			if (day instanceof TLClassifier classifier) {
				days.add(classifier.getName().substring(0, 2));
			}
		}
		return days.isEmpty() ? null : String.join(",", days);
	}

	private static void append(StringBuilder out, String separator, String text) {
		if (text != null) {
			out.append(separator).append(text);
		}
	}

	/**
	 * The target of a reference of a rule or schedule entry (haumacher/ehome #90) by its label and id
	 * (haumacher/ehome #113).
	 */
	private static String reference(DatapointNames names, TLObject obj, String attribute) {
		return names.reference(obj.tValueByName(attribute), attribute);
	}

	private static String text(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value == null || value.toString().isBlank() ? null : value.toString();
	}

	/**
	 * The parts in the given reference of the owner.
	 */
	public static List<TLObject> objects(TLObject owner, String reference) {
		return RuleExport.objects(owner.tValueByName(reference));
	}

	// ---- creating and moving -----------------------------------------------------------------

	/**
	 * Creates a part of the given type at the end of the given reference of the owner, with a unique
	 * id and the defaults of its kind; the caller provides the transaction.
	 */
	public static TLObject create(TLObject owner, String reference, TLClass type) {
		TLObject result = ModelService.getInstance().getFactory().createObject(type);
		if (owner.tType().getPart(reference) instanceof com.top_logic.model.TLStructuredTypePart part && !part.isMultiple()) {
			// A single part: the timeline of a rule.
			owner.tUpdateByName(reference, result);
			initialize(result, owner, type, List.of());
			return result;
		}
		List<TLObject> parts = RuleExport.objects(owner.tValueByName(reference));
		initialize(result, owner, type, parts);
		parts.add(result);
		owner.tUpdateByName(reference, parts);
		return result;
	}

	/**
	 * Gives a new part of the given type a unique id among the given parts of its owner and the
	 * defaults of its kind, for {@link #create(TLObject, String, TLClass)} and the draft of a create
	 * dialog ({@link RuleDrafts}).
	 */
	static void initialize(TLObject result, TLObject owner, TLClass type, List<TLObject> parts) {
		switch (type.getName()) {
			case "Rule": {
				result.tUpdateByName("identifier", unique("rule", parts, "-"));
				result.tUpdateByName("name", FunctionProposal.text(I18NConstants.NEW_RULE_NAME));
				long order = 0;
				for (TLObject rule : parts) {
					if (rule.tValueByName("order") instanceof Number number) {
						order = Math.max(order, number.longValue());
					}
				}
				result.tUpdateByName("order", Integer.valueOf((int) order + 1));
				result.tUpdateByName("enabled", Boolean.TRUE);
				result.tUpdateByName("enabledAdjustable", Boolean.TRUE);
				if (!result.tTransient()) {
					// Its state datapoint at once (haumacher/ehome #140), not only at the commit.
					StateDatapoints.ensure(result);
				}
				break;
			}
			case "RuleParameter":
				result.tUpdateByName("identifier", unique("p", parts, ""));
				result.tUpdateByName("title", FunctionProposal.text(I18NConstants.NEW_PARAMETER_LABEL));
				result.tUpdateByName("type", RuleImport.classifier("RuleParameterType", "real"));
				result.tUpdateByName("default", "0.0");
				result.tUpdateByName("adjustable", Boolean.TRUE);
				break;
			case "RuleChoice": {
				Object parameterType = owner.tValueByName("type");
				String typeName = parameterType instanceof TLClassifier classifier ? classifier.getName() : "integer";
				Type valueType = Type.fromName(typeName);
				result.tUpdateByName("value", valueType == null ? "0"
					: RuleText.printValue(RuleExport.stubValue(typeName), valueType));
				result.tUpdateByName("title", FunctionProposal.text(I18NConstants.NEW_CHOICE_LABEL));
				break;
			}
			case "RuleTimer":
				result.tUpdateByName("identifier", unique("t", parts, ""));
				result.tUpdateByName("duration", "1min");
				break;
			case "RuleState":
				result.tUpdateByName("identifier", unique("s", parts, ""));
				break;
			case "RuleTimeline":
				// A timeline the controller accepts: one day plan without points, assigned by default
				// (homecontrol spec/rules.md 2.8).
				result.tUpdateByName("adjustable", Boolean.TRUE);
				create(result, "plans", (TLClass) com.top_logic.model.util.TLModelUtil.findType("ehome.runtime:TimelinePlan"));
				create(result, "assignments",
					(TLClass) com.top_logic.model.util.TLModelUtil.findType("ehome.runtime:TimelineAssignment"));
				break;
			case "TimelinePlan":
				result.tUpdateByName("identifier", unique("plan", parts, ""));
				result.tUpdateByName("label", FunctionProposal.text(I18NConstants.NEW_PLAN_LABEL));
				break;
			case "TimelinePoint": {
				result.tUpdateByName("time", "06:00");
				TLObject timeline = owner.tContainer();
				TLObject rule = timeline == null ? null : timeline.tContainer();
				List<TLObject> states = rule == null ? List.of() : RuleExport.objects(rule.tValueByName("states"));
				if (!states.isEmpty()) {
					result.tUpdateByName("state", states.get(0));
				}
				break;
			}
			case "TimelineAssignment": {
				List<TLObject> plans = RuleExport.objects(owner.tValueByName("plans"));
				if (!plans.isEmpty()) {
					result.tUpdateByName("plan", plans.get(0));
				}
				// The default first; then the weekdays no assignment names yet.
				Set<String> assigned = new HashSet<>();
				boolean withDefault = false;
				for (TLObject other : parts) {
					List<TLObject> days = RuleExport.objects(other.tValueByName("days"));
					withDefault |= days.isEmpty();
					for (Object day : days) {
						if (day instanceof TLClassifier classifier) {
							assigned.add(classifier.getName());
						}
					}
				}
				if (withDefault) {
					List<TLClassifier> free = new ArrayList<>();
					for (int day = 1; day <= 7; day++) {
						if (!assigned.contains(RuleExport.weekday(day))) {
							free.add(RuleImport.classifier("Weekday", RuleExport.weekday(day)));
						}
					}
					result.tUpdateByName("days", free);
				}
				break;
			}
			case "DatapointTrigger":
				result.tUpdateByName("on", RuleImport.classifier("UpdateKind", "change"));
				result.tUpdateByName("origin", RuleImport.classifier("UpdateOrigin", "any"));
				break;
			case "SilenceTrigger":
				result.tUpdateByName("after", "10min");
				break;
			case "StartTrigger":
				result.tUpdateByName("onBoot", Boolean.TRUE);
				result.tUpdateByName("onImage", Boolean.TRUE);
				result.tUpdateByName("onEnable", Boolean.TRUE);
				break;
			case "LogAction":
				result.tUpdateByName("level", RuleImport.classifier("LogLevel", "info"));
				break;
			case ApplicationDatapoints.TYPE:
				result.tUpdateByName("identifier", unique("value", parts, ""));
				result.tUpdateByName("label", FunctionProposal.text(I18NConstants.NEW_DATAPOINT_LABEL));
				result.tUpdateByName("type", RuleImport.classifier("DatapointValueType", ValueTypes.BOOLEAN));
				break;
			default:
				if (type.getName().endsWith("Schedule")) {
					schedule(result, parts);
				}
				break;
		}
	}

	private static void schedule(TLObject entry, List<TLObject> entries) {
		entry.tUpdateByName("identifier", unique("entry", entries, "-"));
		entry.tUpdateByName("label", FunctionProposal.text(I18NConstants.NEW_SCHEDULE_LABEL));
		entry.tUpdateByName("enabled", Boolean.TRUE);
		entry.tUpdateByName("enabledAdjustable", Boolean.TRUE);
		entry.tUpdateByName("missed", RuleImport.classifier("MissedFiring", "skip"));
		switch (entry.tType().getName()) {
			case "WeeklySchedule":
			case "DatesSchedule":
				entry.tUpdateByName("time", "06:00");
				break;
			case "YearlySchedule":
				entry.tUpdateByName("month", Integer.valueOf(1));
				entry.tUpdateByName("day", Integer.valueOf(1));
				entry.tUpdateByName("time", "00:00");
				break;
			case "AstroSchedule":
				entry.tUpdateByName("sun", RuleImport.classifier("SunEvent", "sunset"));
				break;
			case "IntervalSchedule":
				entry.tUpdateByName("every", "15min");
				entry.tUpdateByName("from", "06:00");
				entry.tUpdateByName("until", "22:00");
				break;
			default:
				break;
		}
	}

	private static String unique(String prefix, List<TLObject> parts, String separator) {
		Set<Object> used = new HashSet<>();
		for (TLObject part : parts) {
			used.add(part.tValueByName("identifier"));
		}
		for (int n = 1;; n++) {
			String id = prefix + separator + n;
			if (!used.contains(id)) {
				return id;
			}
		}
	}

	/**
	 * Moves the part by the given number of positions within the ordered list of its container
	 * that holds it; the caller provides the transaction.
	 */
	public static void move(TLObject part, int delta) {
		TLObject container = part.tContainer();
		String reference = part.tContainerReference().getName();
		List<TLObject> parts = RuleExport.objects(container.tValueByName(reference));
		int index = parts.indexOf(part);
		int target = index + delta;
		if (index < 0 || target < 0 || target >= parts.size()) {
			return;
		}
		parts.remove(index);
		parts.add(target, part);
		container.tUpdateByName(reference, parts);
	}

	/**
	 * Why the part cannot be moved by the given number of positions: it would leave its list.
	 */
	public static Object moveVeto(TLObject part, int delta) {
		TLObject container = part.tContainer();
		if (container == null || part.tContainerReference() == null) {
			return Boolean.TRUE;
		}
		List<TLObject> parts = RuleExport.objects(container.tValueByName(part.tContainerReference().getName()));
		int target = parts.indexOf(part) + delta;
		return target < 0 || target >= parts.size() ? Boolean.TRUE : null;
	}

	// ---- datapoints --------------------------------------------------------------------------

	/**
	 * The suggestions of a text field of the rule language (haumacher/ehome #109): what the text
	 * before the caret leaves unfinished, offered as far as the field may name it
	 * ({@link ExpressionKind}).
	 *
	 * <ul>
	 * <li>Inside <code>running(</code> and <code>remaining(</code>: the timers of the rule.</li>
	 * <li>Inside <code>state("</code>: the rules of the project that have states; after
	 * <code>state() == "</code> (or <code>!=</code>, or <code>state("r") == "</code>): the states of
	 * the rule (of rule <code>r</code>).</li>
	 * <li>After <code>$</code> (or <code>$"</code>) and the start of an id: the parameters of the rule,
	 * its enable switch <code>enabled</code> included, whose id or label contains what is typed, each
	 * replacing the reference by its complete text ({@link RuleText#parameterReference(String)}); in
	 * a field outside a rule (a schedule entry) nothing.</li>
	 * <li>After the start of a function name (two letters): the functions of the rule language with
	 * that start, in a match condition and a silence period only the pure ones.</li>
	 * <li>In the value of a write action to a boolean datapoint, while it is at most the start of a
	 * word: <code>true</code> and <code>false</code>.</li>
	 * <li>After <code>@</code> or <code>@"</code>: the datapoints of the project whose id or label
	 * contains what is typed, each replacing the reference by the complete <code>@"id"</code>; not in
	 * a silence period.</li>
	 * </ul>
	 *
	 * <p>
	 * A log message suggests only inside an open placeholder <code>{…</code>, as a value does.
	 * </p>
	 *
	 * @param part
	 *        The edited part of a rule (or the form's overlay of it, or the draft of a create dialog,
	 *        whose container is the part it is created in).
	 * @param text
	 *        The text typed so far.
	 * @param caret
	 *        The position of the caret in the text.
	 * @return The entries <code>{"label", "text", "from", "to"}</code> a text field offers.
	 */
	public static List<Object> suggestions(TLObject part, String text, int caret) {
		String before = text.substring(0, Math.max(0, Math.min(caret, text.length())));
		ExpressionKind kind = ExpressionKind.of(part);
		int offset = 0;
		if (kind == ExpressionKind.MESSAGE) {
			offset = openPlaceholder(before);
			if (offset < 0) {
				return List.of();
			}
			before = before.substring(offset);
		}
		TLObject rule = kind.inRule() ? rule(part) : null;
		Suggestions result = new Suggestions(offset, before.length());

		Matcher timer = UNFINISHED_TIMER.matcher(before);
		if (timer.find() && !insideString(before)) {
			if (kind.timersAndStates() && rule != null) {
				timerSuggestions(result, rule, timer);
			}
			return result.entries();
		}
		if (insideString(before)) {
			Matcher ruleState = UNFINISHED_RULE_STATE.matcher(before);
			if (ruleState.find()) {
				if (kind.timersAndStates()) {
					stateRuleSuggestions(result, project(part), ruleState);
				}
				return result.entries();
			}
			Matcher state = UNFINISHED_STATE.matcher(before);
			if (state.find()) {
				if (kind.timersAndStates() && rule != null) {
					stateSuggestions(result, rule, project(part), state);
				}
				return result.entries();
			}
		}
		Matcher parameter = UNFINISHED_PARAMETER.matcher(before);
		if (parameter.find() && !insideString(before.substring(0, parameter.start()))) {
			if (rule != null) {
				parameterSuggestions(result, rule, parameter);
			}
			return result.entries();
		}
		booleanSuggestions(result, part, before);
		functionSuggestions(result, before, kind.pureOnly());
		if (!result.isEmpty()) {
			return result.entries();
		}
		Matcher reference = UNFINISHED_REFERENCE.matcher(before);
		if (!kind.datapoints() || !reference.find()) {
			return List.of();
		}
		TLObject project = project(part);
		if (project == null) {
			return List.of();
		}
		String typed = reference.group(1);
		for (Object entry : datapoints(project)) {
			Map<?, ?> datapoint = (Map<?, ?>) entry;
			String id = String.valueOf(datapoint.get("id"));
			Object label = datapoint.get("label");
			String name = label == null ? null : label.toString();
			if (matches(typed, id, name)) {
				result.add("@\"" + id + "\"", name, reference.start());
			}
		}
		return result.entries();
	}

	/**
	 * The timers of the rule whose id contains what is typed inside <code>running(</code> or
	 * <code>remaining(</code>, each completing the call.
	 */
	private static void timerSuggestions(Suggestions result, TLObject rule, Matcher timer) {
		String typed = timer.group(2);
		for (TLObject candidate : RuleExport.objects(rule.tValueByName("timers"))) {
			if (candidate.tValueByName("identifier") instanceof String id && matches(typed, id, null)) {
				Object duration = candidate.tValueByName("duration");
				result.add(id + ")", id + (duration == null ? "" : " " + duration), timer.start(2));
			}
		}
	}

	/**
	 * The rules of the project that have states, inside <code>state("</code>, each completing the
	 * call with its id.
	 */
	private static void stateRuleSuggestions(Suggestions result, TLObject project, Matcher ruleState) {
		if (project == null) {
			return;
		}
		Resources resources = Resources.getInstance();
		String typed = ruleState.group(1);
		for (TLObject candidate : RuleExport.objects(project.tValueByName("rules"))) {
			if (candidate.tValueByName("identifier") instanceof String id
				&& !RuleExport.objects(candidate.tValueByName("states")).isEmpty()) {
				String name = text(candidate.tValueByName("name"), resources);
				if (matches(typed, id, name)) {
					result.add("\"" + id + "\")", name, ruleState.start(1) - 1);
				}
			}
		}
	}

	/**
	 * The states compared with <code>state()</code> (those of the rule) or with
	 * <code>state("r")</code> (those of rule <code>r</code> of the project), each completing the
	 * string.
	 */
	private static void stateSuggestions(Suggestions result, TLObject rule, TLObject project, Matcher state) {
		TLObject owner = rule;
		String named = state.group(1);
		if (named != null) {
			owner = null;
			if (project != null) {
				for (TLObject candidate : RuleExport.objects(project.tValueByName("rules"))) {
					if (named.equals(candidate.tValueByName("identifier"))) {
						owner = candidate;
						break;
					}
				}
			}
			if (owner == null) {
				return;
			}
		}
		String typed = state.group(2);
		for (TLObject candidate : RuleExport.objects(owner.tValueByName("states"))) {
			if (candidate.tValueByName("identifier") instanceof String id && matches(typed, id, null)) {
				result.add("\"" + id + "\"", null, state.start(2) - 1);
			}
		}
	}

	/**
	 * The parameters of the rule, its enable switch <code>enabled</code> last (spec/rules.md 8.3),
	 * whose id or label contains what is typed after <code>$</code>.
	 */
	private static void parameterSuggestions(Suggestions result, TLObject rule, Matcher parameter) {
		Resources resources = Resources.getInstance();
		String typed = parameter.group(1) != null ? parameter.group(1) : parameter.group(2);
		for (TLObject candidate : RuleExport.objects(rule.tValueByName("parameters"))) {
			if (candidate.tValueByName("identifier") instanceof String id) {
				String label = parameterLabel(rule, id, resources);
				if (matches(typed, id, label)) {
					result.add(RuleText.parameterReference(id), label, parameter.start());
				}
			}
		}
		String enabled = resources.getString(TLModelI18N.getI18NKey(rule.tType().getPart("enabled")), null);
		if (matches(typed, ENABLED, enabled)) {
			result.add(RuleText.parameterReference(ENABLED), enabled, parameter.start());
		}
	}

	/** The id of the enable switch of a rule, a parameter of every rule (spec/rules.md 8.3). */
	private static final String ENABLED = "enabled";

	/** Whether the id or the label contains what is typed, ignoring case. */
	private static boolean matches(String typed, String id, String label) {
		String search = typed.toLowerCase(Locale.ROOT);
		return id.toLowerCase(Locale.ROOT).contains(search)
			|| (label != null && label.toLowerCase(Locale.ROOT).contains(search));
	}

	/**
	 * The functions of the rule language (<code>spec/rules.md</code> 4.7) whose name starts with the
	 * identifier the text ends in (at least two letters, not inside a string, not the name of a
	 * parameter <code>$…</code> or the unit of a number), each with its label in the language of the
	 * user, replacing the identifier by the name and the opening parenthesis (both parentheses for a
	 * function without arguments).
	 *
	 * @param pureOnly
	 *        Whether only the pure functions are offered: no time, history, timer or state
	 *        functions (a match condition, a silence period, spec/rules.md 2.3).
	 */
	private static void functionSuggestions(Suggestions result, String before, boolean pureOnly) {
		Matcher identifier = UNFINISHED_FUNCTION.matcher(before);
		if (!identifier.find() || insideString(before)) {
			return;
		}
		String typed = identifier.group(1);
		Resources resources = Resources.getInstance();
		for (Fn fn : Fn.values()) {
			String name = fn.externalName();
			if (!name.startsWith(typed) || (pureOnly && !isPure(fn))) {
				continue;
			}
			String inserted = name + (RuleLoader.maxArguments(fn) == 0 ? "()" : "(");
			result.add(inserted, resources.getString(ResKey.forEnum(fn)), identifier.start(1));
		}
	}

	/**
	 * The literals <code>true</code> and <code>false</code> starting with what is typed, for the value
	 * of a write action to a boolean datapoint while the value is no more than the start of a word
	 * (haumacher/ehome #153: a <code>0</code> or <code>1</code> there is refused).
	 */
	private static void booleanSuggestions(Suggestions result, TLObject part, String before) {
		if (part == null || !"WriteAction".equals(part.tType().getName())
			|| part.tType().getPart("datapoint") == null) {
			return;
		}
		Matcher word = LITERAL_START.matcher(before);
		if (!word.matches() || !(part.tValueByName("datapoint") instanceof TLObject datapoint)
			|| !"boolean".equals(Pages.valueType(datapoint))) {
			return;
		}
		String typed = word.group(1);
		for (String literal : new String[] { "true", "false" }) {
			if (literal.startsWith(typed)) {
				result.add(literal, null, word.start(1));
			}
		}
	}

	/** The value typed so far is at most the start of a word. */
	private static final Pattern LITERAL_START = Pattern.compile("\\s*([a-z]*)");

	/**
	 * Whether the function may be used in a match condition and a silence period: no time, history,
	 * timer or state function (spec/rules.md 2.3, as the loader checks it).
	 */
	private static boolean isPure(Fn fn) {
		return !(fn.isTime() || fn.isHistory() || fn == Fn.RUNNING || fn == Fn.REMAINING || fn == Fn.STATE);
	}

	/** Whether the text ends inside a string literal (an odd number of unescaped quotes). */
	private static boolean insideString(String text) {
		boolean inside = false;
		for (int n = 0; n < text.length(); n++) {
			char c = text.charAt(n);
			if (inside && c == '\\') {
				n++;
			} else if (c == '"') {
				inside = !inside;
			}
		}
		return inside;
	}

	/**
	 * The start of the expression of a placeholder of a log message left open at the end of the text
	 * (the position after its <code>{</code>), -1 if the text ends outside a placeholder;
	 * <code>{{</code> and <code>}}</code> outside a placeholder are braces of the text
	 * ({@link RuleText#parseMessage(String)}).
	 */
	private static int openPlaceholder(String text) {
		int start = -1;
		boolean string = false;
		for (int n = 0; n < text.length(); n++) {
			char c = text.charAt(n);
			if (start < 0) {
				if ((c == '{' || c == '}') && n + 1 < text.length() && text.charAt(n + 1) == c) {
					n++;
				} else if (c == '{') {
					start = n + 1;
				}
			} else if (string) {
				if (c == '\\') {
					n++;
				} else if (c == '"') {
					string = false;
				}
			} else if (c == '"') {
				string = true;
			} else if (c == '}') {
				start = -1;
			}
		}
		return start;
	}

	/**
	 * What an expression field of a rule may name (spec/rules.md 2.3, 4.3), decided by the type of
	 * the edited part, which has one expression field each.
	 */
	private enum ExpressionKind {
		/** No expression of a rule (a schedule entry, a reference line): functions and datapoints. */
		OTHER,
		/** A condition, a value, a duration: datapoints, parameters, timers and states. */
		FULL,
		/** A match condition: datapoints, parameters and the pure functions. */
		MATCH,
		/** The period of a silence trigger: parameters and the pure functions. */
		SILENCE,
		/** A log message: as {@link #FULL} inside a placeholder <code>{…}</code>. */
		MESSAGE;

		boolean inRule() {
			return this != OTHER;
		}

		boolean timersAndStates() {
			return this == FULL || this == MESSAGE;
		}

		boolean pureOnly() {
			return this == MATCH || this == SILENCE;
		}

		boolean datapoints() {
			return this != SILENCE;
		}

		static ExpressionKind of(TLObject part) {
			if (part == null || !RUNTIME_MODULE.equals(part.tType().getModule().getName())) {
				return OTHER;
			}
			switch (part.tType().getName()) {
				case "Reaction":
				case "WriteAction":
				case "SetParameterAction":
				case "RuleTimer":
				case "StartTimerAction":
				case "RestartTimerAction":
					return FULL;
				case "MatchTrigger":
					return MATCH;
				case "SilenceTrigger":
					return SILENCE;
				case "LogAction":
					return MESSAGE;
				default:
					return OTHER;
			}
		}
	}

	/** The model module of the rules. */
	private static final String RUNTIME_MODULE = "ehome.runtime";

	/**
	 * The entries of a text field's suggestions, each replacing the text from its start up to the
	 * caret; the positions of an expression inside a placeholder of a log message are shifted to
	 * those of the whole text.
	 */
	private static final class Suggestions {

		private final int _offset;

		private final int _to;

		private final List<Object> _entries = new ArrayList<>();

		Suggestions(int offset, int to) {
			_offset = offset;
			_to = to;
		}

		void add(String inserted, String label, int from) {
			Map<String, Object> suggestion = new LinkedHashMap<>();
			suggestion.put("label", label == null || label.isEmpty() ? inserted : inserted + " " + label);
			suggestion.put("text", inserted);
			suggestion.put("from", Integer.valueOf(_offset + from));
			suggestion.put("to", Integer.valueOf(_offset + _to));
			_entries.add(suggestion);
		}

		boolean isEmpty() {
			return _entries.isEmpty();
		}

		List<Object> entries() {
			return _entries;
		}
	}

	/**
	 * An identifier of at least two letters at the end of a text that is not part of a reference,
	 * a parameter, a number with its unit or a dotted name.
	 */
	private static final Pattern UNFINISHED_FUNCTION = Pattern.compile("(?<![@$\\w.])([A-Za-z][A-Za-z0-9]+)$");

	/** A reference left unfinished at the end of a text: <code>@</code> or <code>@"</code>, then no quote. */
	private static final Pattern UNFINISHED_REFERENCE = Pattern.compile("@\"?([^\"@]*)$");

	/**
	 * A parameter left unfinished at the end of a text: <code>$</code>, then the start of an id or a
	 * string left open (group 1: the string's content, group 2: the id).
	 */
	private static final Pattern UNFINISHED_PARAMETER = Pattern.compile("\\$(?:\"([^\"\\\\]*)|([A-Za-z0-9_-]*))$");

	/** A timer function left open: <code>running(</code> or <code>remaining(</code>, then the start of an id (group 2). */
	private static final Pattern UNFINISHED_TIMER =
		Pattern.compile("(?<![@$\\w.])(running|remaining)\\(\\s*([A-Za-z0-9_]*)$");

	/** The rule id of <code>state("</code> left open (group 1). */
	private static final Pattern UNFINISHED_RULE_STATE = Pattern.compile("(?<![@$\\w.])state\\(\\s*\"([^\"\\\\]*)$");

	/**
	 * A state compared with <code>state()</code> or <code>state("r")</code> left open: the rule id
	 * (group 1, absent for the rule's own state), the start of the state id (group 2).
	 */
	private static final Pattern UNFINISHED_STATE = Pattern.compile(
		"(?<![@$\\w.])state\\(\\s*(?:\"([^\"\\\\]*)\"\\s*)?\\)\\s*[!=]=\\s*\"([^\"\\\\]*)$");

	/**
	 * The rule the given part of a rule belongs to, <code>null</code> if none: the first rule of its
	 * container chain, which is also that of the draft of a create dialog.
	 */
	private static TLObject rule(TLObject part) {
		for (TLObject obj = part; obj != null; obj = obj.tContainer()) {
			if ("Rule".equals(obj.tType().getName()) && RUNTIME_MODULE.equals(obj.tType().getModule().getName())) {
				return obj;
			}
		}
		return null;
	}

	/**
	 * The project the given part of a rule or schedule entry belongs to, <code>null</code> if none.
	 */
	private static TLObject project(TLObject part) {
		for (TLObject obj = part; obj != null; obj = obj.tContainer()) {
			if ("Project".equals(obj.tType().getName()) && "ehome.project".equals(obj.tType().getModule().getName())) {
				return obj;
			}
		}
		return null;
	}

	/**
	 * The datapoints the rules of the project may name, as maps of <code>id</code>, <code>label</code>,
	 * <code>type</code>, <code>writable</code> and <code>binding</code>: those of the image, of the
	 * rules' parameters and of the adjustable times of the schedule entries.
	 *
	 * <p>
	 * Computed once per state of the data and reader ({@link ImageCache}); the list and its maps
	 * cannot be changed.
	 * </p>
	 */
	public static List<Object> datapoints(TLObject project) {
		return ImageCache.get(project).derived(DATAPOINTS, entry -> datapoints(project, entry));
	}

	/** The key of {@link #datapoints(TLObject)} among the values derived from an {@link ImageCache.Entry}. */
	private static final Object DATAPOINTS = new Object();

	private static List<Object> datapoints(TLObject project, ImageCache.Entry cached) {
		RuntimeImage image = cached.image();
		if (!(image.getJson().get("datapoints") instanceof List<?> list)) {
			return List.of();
		}
		Resources resources = Resources.getInstance();
		List<Object> result = new ArrayList<>();
		for (int n = 0; n < list.size(); n++) {
			Map<String, Object> datapoint = new LinkedHashMap<>();
			((Map<?, ?>) list.get(n)).forEach((key, value) -> datapoint.put(String.valueOf(key), value));
			TLObject source = image.getDatapointSources().get(n);
			if (ApplicationDatapoints.isApplicationDatapoint(source) && source.tValueByName("label") instanceof ResKey label) {
				datapoint.put("label", resources.getString(label, null));
			}
			datapoint.put("binding", resources.getString(datapoint.containsKey("knx") ? I18NConstants.BINDING_KNX
				: datapoint.containsKey("zigbee") ? I18NConstants.BINDING_ZIGBEE : I18NConstants.BINDING_APPLICATION));
			result.add(datapoint);
		}
		// The datapoints of the rule parameters (spec/rules.md 8.6), which the controller derives from the
		// rules: another rule may read, trigger on and write them.
		List<Object> rules = cached.rules();
		Map<String, TLObject> byId = new LinkedHashMap<>();
		for (TLObject rule : RuleExport.objects(project.tValueByName("rules"))) {
			if (rule.tValueByName("identifier") instanceof String id) {
				byId.putIfAbsent(id, rule);
			}
		}
		for (RuleLoader.ParameterDatapoint parameter : RuleLoader.parameterDatapoints(rules)) {
			Map<String, Object> datapoint = new LinkedHashMap<>();
			datapoint.put("id", parameter.name());
			datapoint.put("label", parameterLabel(byId.get(parameter.rule()), parameter.parameter(), resources));
			datapoint.put("type", parameter.type().externalName());
			if (parameter.writable()) {
				datapoint.put("writable", Boolean.TRUE);
			}
			datapoint.put("binding", resources.getString(I18NConstants.BINDING_RULE_PARAMETER));
			datapoint.put("parameter", Map.of("rule", parameter.rule(), "parameter", parameter.parameter()));
			result.add(datapoint);
		}
		// The time datapoints of the schedule entries with an adjustable time (homecontrol #58): rules
		// read, trigger on and write them.
		List<Object> schedules = new RuleExport().schedules(project);
		for (String id : Schedule.timeDatapointIds(schedules)) {
			Map<String, Object> datapoint = new LinkedHashMap<>();
			datapoint.put("id", Schedule.timeDatapointName(id));
			for (TLObject entry : RuleExport.objects(project.tValueByName("schedules"))) {
				if (id.equals(entry.tValueByName("identifier"))) {
					datapoint.put("label", text(entry.tValueByName("label"), resources));
					break;
				}
			}
			datapoint.put("type", ValueType.TIME_OF_DAY.externalName());
			datapoint.put("writable", Boolean.TRUE);
			datapoint.put("binding", resources.getString(I18NConstants.BINDING_SCHEDULE_TIME));
			datapoint.put("schedule", id);
			result.add(datapoint);
		}
		for (int n = 0; n < result.size(); n++) {
			result.set(n, Collections.unmodifiableMap((Map<?, ?>) result.get(n)));
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * The label of a parameter datapoint in the language of the user: the parameter's label, the
	 * rule's name for the enable switch.
	 */
	private static String parameterLabel(TLObject rule, String parameter, Resources resources) {
		if (rule == null) {
			return null;
		}
		if (parameter.equals("enabled")) {
			return text(rule.tValueByName("name"), resources);
		}
		if (parameter.equals(de.haumacher.ehome.rules.RuleSet.STATE_DATAPOINT)
			&& !RuleExport.objects(rule.tValueByName("states")).isEmpty()) {
			// The datapoint of the rule's state (haumacher/ehome #140).
			String name = text(rule.tValueByName("name"), resources);
			return name == null ? null : resources.getString(I18NConstants.STATE_DATAPOINT_LABEL__RULE.fill(name));
		}
		for (TLObject candidate : RuleExport.objects(rule.tValueByName("parameters"))) {
			if (parameter.equals(candidate.tValueByName("identifier"))) {
				String title = text(candidate.tValueByName("title"), resources);
				return title != null ? title : text(candidate.tValueByName("label"), resources);
			}
		}
		return null;
	}

	private static String text(Object value, Resources resources) {
		if (value instanceof ResKey key) {
			return resources.getString(key, null);
		}
		return value == null || value.toString().isBlank() ? null : value.toString();
	}

	/**
	 * Builder of a {@link RuleFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<RuleFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public RuleFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arity, _function._arity);
			return new RuleFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
