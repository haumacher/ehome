/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.Type;

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
		 * <code>ruleNormalize(obj)</code>: stores the canonical form of the texts of the rule language
		 * the part of a rule or schedule entry holds ({@link RuleNormalization}); the object.
		 */
		ruleNormalize(1, false),
		/**
		 * <code>ruleImportImage(project, file)</code>: adds the rules and schedule entries of the
		 * uploaded runtime image (JSON) to the project ({@link RuleImport#importImage(TLObject, String)});
		 * the sentence saying what was imported, followed by what the import did not keep.
		 */
		ruleImportImage(2, false);

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
			return _function == Function.ruleList || _function == Function.ruleDatapoints ? List.of() : null;
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
			case ruleNormalize:
				return RuleNormalization.normalize(obj);
			case ruleImportImage:
				return importImage(obj, arguments[1]);
			default:
				return null;
		}
	}

	/**
	 * Adds the rules and schedule entries of the given file (a runtime image) to the project; must run
	 * in a transaction.
	 *
	 * @return The sentence saying what was imported, followed by the notes of the import.
	 */
	public static String importImage(TLObject project, Object file) {
		if (!(file instanceof BinaryData data)) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_NO_RULES);
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
			I18NConstants.RULES_IMPORTED__RULES_SCHEDULES.fill(imported.rules().size(), imported.schedules().size())));
		for (String note : importer.getNotes()) {
			result.append('\n').append(note);
		}
		return result.toString();
	}

	// ---- summaries ---------------------------------------------------------------------------

	/**
	 * A line describing a reaction, a trigger, an action or a schedule entry in the notation of the
	 * rule language.
	 */
	public static String summary(TLObject obj) {
		String type = obj.tType().getName();
		if (type.equals("Reaction")) {
			StringBuilder out = new StringBuilder("when ");
			List<String> triggers = new ArrayList<>();
			for (TLObject trigger : RuleExport.objects(obj.tValueByName("triggers"))) {
				triggers.add(summary(trigger));
			}
			out.append(triggers.isEmpty() ? "-" : String.join(" | ", triggers));
			String condition = text(obj, "condition");
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
				append(out, " ", text(obj, "datapoint"));
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
				append(out, " ", text(obj, "condition"));
				break;
			case "silence":
				append(out, " ", text(obj, "datapoint"));
				append(out, " after ", text(obj, "after"));
				break;
			case "read":
				append(out, " ", text(obj, "datapoint"));
				break;
			case "schedule":
			case "parameter":
			case "timer":
			case "cancelTimer":
				append(out, " ", text(obj, kind.equals("cancelTimer") ? "timer" : kind));
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
				append(out, " ", text(obj, "datapoint"));
				append(out, " := ", text(obj, "value"));
				if (Boolean.TRUE.equals(obj.tValueByName("onlyOnChange"))) {
					out.append(" (onlyOnChange)");
				}
				break;
			case "startTimer":
			case "restartTimer":
				append(out, " ", text(obj, "timer"));
				append(out, " ", text(obj, "duration"));
				break;
			case "setParameter":
				append(out, " ", text(obj, "parameter"));
				append(out, " := ", text(obj, "value"));
				break;
			case "log":
				if (obj.tValueByName("level") instanceof TLClassifier level) {
					out.append(' ').append(level.getName());
				}
				append(out, ": ", text(obj, "message"));
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
		List<TLObject> parts = RuleExport.objects(owner.tValueByName(reference));
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
		parts.add(result);
		owner.tUpdateByName(reference, parts);
		return result;
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
	 * The datapoints of the project's image, each a copy of its map in the image with its label in
	 * the language of the user and its <code>binding</code>: KNX for the datapoint of a group
	 * address, application for an application datapoint ({@link ApplicationDatapoints}), Zigbee for
	 * one bound to a function of a Zigbee device.
	 */
	public static List<Object> datapoints(TLObject project) {
		RuntimeImage image = RuntimeImage.build(project, 0, Instant.EPOCH, false);
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
		List<Object> rules = new RuleExport().rules(project);
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
		return result;
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
