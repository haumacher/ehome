/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import static de.haumacher.ehome.ai.logic.LogicSupport.SAVED;
import static de.haumacher.ehome.ai.logic.LogicSupport.answer;
import static de.haumacher.ehome.ai.logic.LogicSupport.failure;
import static de.haumacher.ehome.ai.logic.LogicSupport.id;
import static de.haumacher.ehome.ai.logic.LogicSupport.label;
import static de.haumacher.ehome.ai.logic.LogicSupport.project;
import static de.haumacher.ehome.ai.logic.LogicSupport.ref;
import static de.haumacher.ehome.ai.logic.LogicSupport.writeDenied;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.model.TLObject;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.core.tools.refusal.ApplicationRefusals;
import com.top_logic.tl.ai.core.tools.refusal.messages.Reason;
import com.top_logic.tl.ai.tool.api.P;
import com.top_logic.tl.ai.tool.api.ToolGroups;
import com.top_logic.tl.ai.tool.api.ToolMethod;
import com.top_logic.tl.ai.tool.api.ToolMode;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.ExpressionRenames;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.Referers;
import de.haumacher.ehome.runtime.RuleExport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleReferences;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;

/**
 * The assistant's tools for the rules, schedule entries and application datapoints of a project
 * (haumacher/ehome #128): reading them, explaining a rule, finding what uses a datapoint, checking
 * definitions as the rule editor checks them, saving and deleting.
 *
 * <p>
 * Rules, schedule entries and application datapoints are written as <i>definitions</i>: the JSON of
 * the runtime image (homecontrol <code>spec/rules.md</code> 2, <code>spec/schedules.md</code> 2) with
 * the expressions, literal values and log messages as text of the rule language
 * ({@link AutomationJson}). They are read into the project by the import of an image
 * (<code>RuleImport</code>, the references resolved by their ids, the texts in canonical form) and
 * checked field by field with the model's constraints, the checks of the rule editor
 * ({@link AutomationChange}). An existing item of the same id is replaced in place.
 * </p>
 */
public class RuleTools {

	/** The format of the definitions, as the tool descriptions explain it. */
	static final String DEFINITIONS = "Definitions: {\"datapoints\": [...], \"schedules\": [...], \"rules\": [...]},"
		+ " each member optional, in the JSON of HomeControl's runtime image (spec/rules.md 2, spec/schedules.md 2)"
		+ " but with every expression, literal value and log message written as TEXT of the rule language:"
		+ " \"condition\": \"@outdoor.temp < $limit\", \"value\": \"true\", \"duration\": \"5min\","
		+ " parameter \"default\": \"3min\", schedule \"time\": \"06:30\", \"dates\": [\"2026-12-24\"],"
		+ " log \"message\": \"Frost at {@outdoor.temp}\". A rule: {\"id\", \"name\", \"description\"?,"
		+ " \"enabled\"?, \"parameters\"?: [{\"id\", \"label\", \"type\", \"default\", \"min\"?, \"max\"?, \"step\"?,"
		+ " \"unit\"?}], \"timers\"?: [{\"id\", \"duration\"}], \"states\"?: [{\"id\", \"entry\"?, \"exit\"?}],"
		+ " \"initialState\"?, \"reactions\": [{\"triggers\": [...], \"condition\"?, \"actions\": [...],"
		+ " \"elseActions\"?, \"from\"?, \"to\"?}]}. Triggers: {\"kind\": \"datapoint\", \"datapoint\": id,"
		+ " \"on\"?: change|update, \"origin\"?: any|report|command}, {\"kind\": \"match\", \"condition\"},"
		+ " {\"kind\": \"schedule\", \"schedule\": id}, {\"kind\": \"timer\", \"timer\": id}, {\"kind\": \"start\"},"
		+ " {\"kind\": \"parameter\", \"parameter\": id}, {\"kind\": \"silence\", \"datapoint\", \"after\"}."
		+ " Actions: write {datapoint, value, onlyOnChange?}, startTimer|restartTimer {timer, duration?},"
		+ " cancelTimer {timer}, setParameter {parameter, value}, read {datapoint}, log {level, message}."
		+ " An application datapoint: {\"id\", \"label\", \"type\", \"unit\"?, \"writable\"?, \"persist\"?}."
		+ " A schedule entry: {\"id\", \"label\", \"kind\": weekly|yearly|dates|astro|interval|once, ...,"
		+ " \"days\"?: [1..7], \"output\"?}. Datapoints are named by their ids in the image: a group address"
		+ " by its identifier where it has one (kueche.licht, in an expression @kueche.licht), else as \"1/2/3\""
		+ " (in an expression @\"1/2/3\"), an application datapoint by its id (automation lists them).";

	/**
	 * The rules, schedule entries and datapoints of a project.
	 */
	@ToolMethod(description = "Lists the logic of an E-Home project: its rules (each with its identifier,"
		+ " name, whether it is enabled and one line per reaction in the notation of the rule language,"
		+ " datapoints named as Name (id)), its schedule entries, and the datapoints rules may name (group"
		+ " addresses by their identifier, else as \"1/2/3\", application datapoints, rule parameters \"rule/<rule>/<parameter>\","
		+ " adjustable schedule times) with label, value type and whether they are writable. Read it before you"
		+ " write or change a rule: rules name datapoints by these ids.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = LogicSupport.PROJECT)
	public ToolResult automation(
			@P("The project, or any object of it") TLObject project) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project.");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("project", ref(owner));
		List<Object> rules = new ArrayList<>();
		for (TLObject rule : RuleExport.orderedRules(owner)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", id(rule));
			entry.put("identifier", rule.tValueByName("identifier"));
			entry.put("name", label(rule));
			entry.put("enabled", rule.tValueByName("enabled"));
			entry.put("outline", outline(rule));
			rules.add(entry);
		}
		result.put("rules", rules);
		List<Object> schedules = new ArrayList<>();
		for (TLObject entry : AutomationChange.objects(owner.tValueByName("schedules"))) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", id(entry));
			item.put("identifier", entry.tValueByName("identifier"));
			item.put("label", label(entry));
			item.put("entry", RuleFunctions.summary(entry));
			schedules.add(item);
		}
		result.put("schedules", schedules);
		List<Object> datapoints = new ArrayList<>();
		for (Object item : RuleFunctions.datapoints(owner)) {
			Map<?, ?> datapoint = (Map<?, ?>) item;
			Map<String, Object> entry = new LinkedHashMap<>();
			for (String key : new String[] { "id", "label", "type", "unit", "writable", "binding" }) {
				if (datapoint.get(key) != null) {
					entry.put(key, datapoint.get(key));
				}
			}
			datapoints.add(entry);
		}
		result.put("datapoints", datapoints);
		return answer("The logic of the project:", result);
	}

	/**
	 * What a rule does, part by part, with its definition.
	 */
	@ToolMethod(description = "Explains a rule of E-Home: its parameters, timers and states, and for each"
		+ " reaction its triggers (when), its condition (if), its actions (then) and else actions, with the"
		+ " datapoints and parameters named as Name (id); the datapoints the rule reads and writes; and its"
		+ " definition in the form saveAutomation takes, to change it. Describe the rule to the user from"
		+ " this in words.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = LogicSupport.PROJECT)
	public ToolResult explainRule(
			@P("The rule (ehome.runtime:Rule)") TLObject rule) {
		if (!LogicSupport.isA(rule, "ehome.runtime:Rule")) {
			return LogicSupport.wrongKind("rule", rule, "a rule (ehome.runtime:Rule)");
		}
		TLObject owner = project(rule);
		DatapointNames names = DatapointNames.of(owner);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rule", ref(rule));
		result.put("identifier", rule.tValueByName("identifier"));
		result.put("enabled", rule.tValueByName("enabled"));
		if (rule.tValueByName("description") != null) {
			result.put("description", rule.tValueByName("description"));
		}
		List<Object> parameters = new ArrayList<>();
		for (TLObject parameter : AutomationChange.objects(rule.tValueByName("parameters"))) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("parameter", DatapointNames.format(DatapointNames.label(parameter),
				"$" + parameter.tValueByName("identifier")));
			entry.put("type", label(parameter.tValueByName("type")));
			entry.put("default", parameter.tValueByName("default"));
			if (parameter.tValueByName("unit") != null) {
				entry.put("unit", parameter.tValueByName("unit"));
			}
			parameters.add(entry);
		}
		result.put("parameters", parameters);
		List<Object> timers = new ArrayList<>();
		for (TLObject timer : AutomationChange.objects(rule.tValueByName("timers"))) {
			timers.add(timer.tValueByName("identifier") + " = "
				+ names.expression(rule, (String) timer.tValueByName("duration")));
		}
		result.put("timers", timers);
		List<Object> states = new ArrayList<>();
		for (TLObject state : AutomationChange.objects(rule.tValueByName("states"))) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("state", state.tValueByName("identifier"));
			entry.put("entry", summaries(state.tValueByName("entry")));
			entry.put("exit", summaries(state.tValueByName("exit")));
			states.add(entry);
		}
		if (!states.isEmpty()) {
			result.put("states", states);
			if (rule.tValueByName("initialState") instanceof TLObject initial) {
				result.put("initialState", initial.tValueByName("identifier"));
			}
		}
		List<Object> reactions = new ArrayList<>();
		for (TLObject reaction : AutomationChange.objects(rule.tValueByName("reactions"))) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("when", summaries(reaction.tValueByName("triggers")));
			String condition = names.expression(rule, (String) reaction.tValueByName("condition"));
			if (condition != null) {
				entry.put("if", condition);
			}
			List<String> from = new ArrayList<>();
			for (TLObject state : AutomationChange.objects(reaction.tValueByName("from"))) {
				from.add(String.valueOf(state.tValueByName("identifier")));
			}
			if (!from.isEmpty()) {
				entry.put("inStates", from);
			}
			entry.put("then", summaries(reaction.tValueByName("actions")));
			List<String> otherwise = summaries(reaction.tValueByName("elseActions"));
			if (!otherwise.isEmpty()) {
				entry.put("else", otherwise);
			}
			if (reaction.tValueByName("to") instanceof TLObject to) {
				entry.put("toState", to.tValueByName("identifier"));
			}
			reactions.add(entry);
		}
		result.put("reactions", reactions);
		Set<String> reads = new LinkedHashSet<>();
		Set<String> writes = new LinkedHashSet<>();
		collect(owner, rule, rule, names, reads, writes);
		result.put("reads", new ArrayList<>(reads));
		result.put("writes", new ArrayList<>(writes));
		result.put("definition", definition(owner, rule));
		return answer("The rule, part by part:", result);
	}

	/**
	 * What uses a datapoint.
	 */
	@ToolMethod(description = "Answers which parts of an E-Home project use a datapoint (a group address, an"
		+ " application datapoint, a rule parameter, a schedule time, a datapoint of a virtual device): the"
		+ " rules that trigger on it, write it or read it in an expression, the schedule entries with it as"
		+ " output, the functions whose roles name it, the control pages that show it, the virtual devices"
		+ " bound to it. Use it before you change or delete a datapoint, and to find the rule behind a value.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = LogicSupport.PROJECT)
	public ToolResult datapointUsage(
			@P("The datapoint: a group address (ehome.logical:GroupAddress), an application datapoint"
				+ " (ehome.runtime:ApplicationDatapoint), ...") TLObject datapoint) {
		TLObject owner = project(datapoint);
		if (owner == null) {
			return failure("'" + id(datapoint) + "' belongs to no project.");
		}
		String imageId = RuleReferences.datapointId(owner, datapoint);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("datapoint", imageId == null ? label(datapoint) : DatapointNames.of(owner).datapoint(imageId));
		result.put("usedBy", usage(owner, datapoint, imageId));
		return answer("What uses the datapoint:", result);
	}

	/**
	 * The parts of the project using the datapoint: those referring to it, and the rules naming it
	 * in an expression.
	 */
	static List<Object> usage(TLObject project, TLObject datapoint, String imageId) {
		Map<TLObject, Set<String>> referers = new LinkedHashMap<>(Referers.referers(datapoint));
		if (imageId != null) {
			ExpressionRenames probe = new ExpressionRenames();
			probe.datapoint(imageId, imageId);
			for (TLObject rule : RuleExport.orderedRules(project)) {
				if (probe.names(rule)) {
					referers.computeIfAbsent(rule, x -> new LinkedHashSet<>()).add("expression");
				}
			}
		}
		List<Object> result = new ArrayList<>();
		for (Map.Entry<TLObject, Set<String>> referer : referers.entrySet()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", id(referer.getKey()));
			entry.put("kind", label(referer.getKey().tType()));
			entry.put("name", label(referer.getKey()));
			entry.put("in", new ArrayList<>(referer.getValue()));
			result.add(entry);
		}
		return result;
	}

	/**
	 * Checks definitions without saving them.
	 */
	@ToolMethod(description = "Checks rules, schedule entries and application datapoints of E-Home before"
		+ " they are saved, exactly as the rule editor and the controller's loader check them: syntax errors"
		+ " of a text with the position of the error, ids naming no datapoint, schedule entry, timer or"
		+ " parameter, type errors, values out of range. Nothing is saved. An item with the id of an existing"
		+ " one is checked as its replacement. Always check before saveAutomation. The check sees the data as"
		+ " committed: put datapoints, schedule entries and rules that belong together into one call. "
		+ DEFINITIONS,
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = LogicSupport.PROJECT)
	public ToolResult checkAutomation(ToolContext context,
			@P("The project, or any object of it") TLObject project,
			@P("The definitions to check") Map<String, Object> definitions) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project.");
		}
		Checked checked = check(context, owner, definitions);
		if (checked.failure() != null) {
			return checked.failure();
		}
		return answer("The definitions are valid: saveAutomation would save them as they are (nothing was saved).",
			checked.report());
	}

	/**
	 * Saves definitions.
	 */
	@ToolMethod(description = "Creates or changes rules, schedule entries and application datapoints of an E-Home"
		+ " project, as the rule editor does. A definition whose id the project has replaces that item in place"
		+ " (what refers to it keeps referring to it; parameters, timers and states are matched by their ids);"
		+ " any other is added. The definitions are checked first (as checkAutomation does); if any field is"
		+ " refused, nothing is saved and the problems are answered. With dryRun=true only the check runs. To"
		+ " change a rule, read its definition with explainRule, change it and save it whole. Saving never"
		+ " deploys: the controller gets the change with the next deployment, the installer's command. "
		+ DEFINITIONS + " " + LogicSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = LogicSupport.PROJECT, dryRun = "dryRun")
	public ToolResult saveAutomation(ToolContext context,
			@P("The project, or any object of it") TLObject project,
			@P("The definitions to save") Map<String, Object> definitions,
			@P(value = "true to only check the definitions", required = false) Boolean dryRun) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project. Nothing was saved.");
		}
		ToolResult denied = writeDenied(context, owner);
		if (denied != null) {
			return denied;
		}
		Checked checked = check(context, owner, definitions);
		if (checked.failure() != null) {
			return checked.failure();
		}
		if (Boolean.TRUE.equals(dryRun)) {
			return answer("Dry run, nothing was saved: the definitions are valid.", checked.report());
		}
		AutomationChange change = new AutomationChange(owner);
		try {
			change.apply(checked.image());
		} catch (TopLogicException ex) {
			throw new IllegalStateException("The definitions passed the check but could not be saved: "
				+ LogicSupport.text(ex.getErrorKey()), ex);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("created", refs(change.created()));
		result.put("replaced", refs(change.replaced()));
		if (!change.problems().isEmpty()) {
			result.put("problems", problems(change.problems()));
		}
		if (!change.notes().isEmpty()) {
			result.put("notes", change.notes());
		}
		return answer("Saved. " + SAVED + " The controller runs it after the next deployment (the installer's"
			+ " command in the runtime image view).", result);
	}

	/**
	 * The outcome of checking definitions.
	 *
	 * @param image
	 *        The definitions in the JSON of the image.
	 * @param report
	 *        What the check found besides (notes).
	 * @param failure
	 *        The answer refusing the definitions, <code>null</code> if they are valid.
	 */
	record Checked(Map<?, ?> image, Map<String, Object> report, ToolResult failure) {
		// A record.
	}

	/**
	 * Checks definitions in a {@link Sandbox}.
	 */
	static Checked check(ToolContext context, TLObject project, Map<String, Object> definitions) {
		if (definitions == null || definitions.isEmpty()) {
			return new Checked(null, null, failure("'definitions' is empty: pass {\"rules\": [...]}, {\"schedules\": [...]}"
				+ " or {\"datapoints\": [...]}. Nothing was checked."));
		}
		for (String key : definitions.keySet()) {
			if (!List.of("rules", "schedules", "datapoints").contains(key)) {
				return new Checked(null, null, failure("Unknown member '" + key + "' of the definitions; they have"
					+ " the members rules, schedules and datapoints. Nothing was checked."));
			}
		}
		List<AutomationJson.Syntax> syntax = new ArrayList<>();
		Map<?, ?> image = (Map<?, ?>) AutomationJson.toImage(definitions, syntax);
		if (!syntax.isEmpty()) {
			List<Object> errors = new ArrayList<>();
			for (AutomationJson.Syntax problem : syntax) {
				errors.add(problem.json());
			}
			return new Checked(null, null, ToolResult.failure("Refused, nothing was saved: texts of the rule language"
				+ " do not parse. Fix them at the given positions and check again.\n"
				+ AutomationChange.text(Map.of("syntaxErrors", errors))));
		}
		Map<String, Object> outcome = Sandbox.run(context, () -> {
			AutomationChange change = new AutomationChange(project);
			Map<String, Object> result = new LinkedHashMap<>();
			try {
				change.apply(image);
			} catch (TopLogicException ex) {
				result.put("refused", LogicSupport.text(ex.getErrorKey()));
				return result;
			} catch (RuntimeException ex) {
				result.put("refused", "The definitions cannot be read: " + ex.getMessage());
				return result;
			}
			if (!change.problems().isEmpty()) {
				result.put("problems", problems(change.problems()));
			}
			List<String> created = new ArrayList<>();
			for (TLObject item : change.created()) {
				created.add(AutomationChange.itemName(item));
			}
			List<String> replaced = new ArrayList<>();
			for (TLObject item : change.replaced()) {
				replaced.add(AutomationChange.itemName(item));
			}
			result.put("wouldCreate", created);
			result.put("wouldReplace", replaced);
			if (!change.notes().isEmpty()) {
				result.put("notes", change.notes());
			}
			return result;
		});
		if (outcome.get("refused") instanceof String refused) {
			List<Reason> reasons = new ArrayList<>();
			reasons.add(ApplicationRefusals.reason(refused));
			return new Checked(null, null, LogicSupport.refused(project, reasons));
		}
		if (outcome.get("problems") instanceof List<?> problems) {
			List<Reason> reasons = new ArrayList<>();
			for (Object item : problems) {
				Map<?, ?> problem = (Map<?, ?>) item;
				String where = problem.get("item") + (problem.get("part") != null ? " / " + problem.get("part") : "")
					+ " / " + problem.get("attribute");
				reasons.add(ApplicationRefusals.reason(String.valueOf(problem.get("message"))).setAttribute(where));
			}
			return new Checked(null, null, LogicSupport.refused(project, reasons));
		}
		return new Checked(image, outcome, null);
	}

	private static List<Object> problems(List<AutomationChange.Problem> problems) {
		List<Object> result = new ArrayList<>();
		for (AutomationChange.Problem problem : problems) {
			result.add(problem.json());
		}
		return result;
	}

	private static List<Object> refs(List<TLObject> objects) {
		List<Object> result = new ArrayList<>();
		for (TLObject obj : objects) {
			Map<String, Object> entry = ref(obj);
			entry.put("item", AutomationChange.itemName(obj));
			result.add(entry);
		}
		return result;
	}

	// ---- reading -----------------------------------------------------------------------------

	/**
	 * The reactions of a rule, one line each, in the notation of the rule language.
	 */
	static List<String> outline(TLObject rule) {
		List<String> result = new ArrayList<>();
		for (TLObject reaction : AutomationChange.objects(rule.tValueByName("reactions"))) {
			StringBuilder line = new StringBuilder(RuleFunctions.summary(reaction));
			List<String> actions = summaries(reaction.tValueByName("actions"));
			line.append(" then ").append(actions.isEmpty() ? "-" : String.join("; ", actions));
			List<String> otherwise = summaries(reaction.tValueByName("elseActions"));
			if (!otherwise.isEmpty()) {
				line.append(" else ").append(String.join("; ", otherwise));
			}
			result.add(line.toString());
		}
		return result;
	}

	private static List<String> summaries(Object parts) {
		List<String> result = new ArrayList<>();
		for (TLObject part : AutomationChange.objects(parts)) {
			result.add(RuleFunctions.summary(part));
		}
		return result;
	}

	/**
	 * The definition of a rule as saveAutomation takes it.
	 */
	static Map<String, Object> definition(TLObject project, TLObject rule) {
		List<Object> rules = new RuleExport().rules(project);
		List<TLObject> ordered = RuleExport.orderedRules(project);
		int index = ordered.indexOf(rule);
		if (rules == null || index < 0 || index >= rules.size()) {
			return null;
		}
		return AutomationJson.ruleToText(rules.get(index));
	}

	/**
	 * The datapoints the rule reads (in its triggers and expressions) and writes, as Name (id).
	 */
	private static void collect(TLObject project, TLObject rule, TLObject obj, DatapointNames names, Set<String> reads,
			Set<String> writes) {
		String type = obj.tType().getName();
		if (obj.tType().getPart("datapoint") != null && obj.tValueByName("datapoint") instanceof TLObject target) {
			String name = names.reference(target, "datapoint");
			if (type.equals("WriteAction")) {
				writes.add(name);
			} else {
				reads.add(name);
			}
		}
		if (obj.tType().getPart("output") != null && obj.tValueByName("output") instanceof TLObject output) {
			writes.add(names.reference(output, "output"));
		}
		for (String attribute : new String[] { "condition", "value", "duration", "after" }) {
			if (obj.tType().getPart(attribute) != null && obj.tValueByName(attribute) instanceof String text) {
				try {
					references(RuleText.parse(text), names, reads);
				} catch (SyntaxError ex) {
					// A text that does not parse names nothing.
				}
			}
		}
		if (type.equals("LogAction") && obj.tValueByName("message") instanceof String message) {
			try {
				for (Object part : RuleText.parseMessage(message)) {
					references(part, names, reads);
				}
			} catch (SyntaxError ex) {
				// Names nothing.
			}
		}
		for (var part : obj.tType().getAllParts()) {
			if (part.getModelKind() == com.top_logic.model.ModelKind.REFERENCE
				&& ((com.top_logic.model.TLReference) part).isComposite()) {
				for (TLObject child : AutomationChange.objects(obj.tValue(part))) {
					collect(project, rule, child, names, reads, writes);
				}
			}
		}
	}

	private static void references(Object node, DatapointNames names, Set<String> reads) {
		if (node instanceof Map<?, ?> map) {
			if ("datapoint".equals(map.get("kind")) && map.get("name") instanceof String name) {
				reads.add(names.datapoint(name));
			}
			for (Object value : map.values()) {
				references(value, names, reads);
			}
		} else if (node instanceof List<?> list) {
			for (Object item : list) {
				references(item, names, reads);
			}
		}
	}

	// ---- deleting ----------------------------------------------------------------------------

	/**
	 * Deletes rules, schedule entries and application datapoints.
	 */
	@ToolMethod(description = "Deletes rules, schedule entries or application datapoints of an E-Home project,"
		+ " as the delete commands of their views do. With dryRun=true it only answers what still refers to"
		+ " each of them (rules naming a datapoint in an expression, functions, pages, virtual devices): after"
		+ " the deletion those refer to nothing, and the next deployment refuses them until they are changed."
		+ " Ask the user before you delete something that is still referred to.",
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = LogicSupport.PROJECT, dryRun = "dryRun")
	public ToolResult deleteAutomation(ToolContext context,
			@P("The rules (ehome.runtime:Rule), schedule entries or application datapoints to delete")
			List<TLObject> objects,
			@P(value = "true to only answer what refers to them", required = false) Boolean dryRun) {
		if (objects == null || objects.isEmpty()) {
			return failure("'objects' is empty. Nothing was deleted.");
		}
		TLObject owner = project(objects.get(0));
		List<Object> report = new ArrayList<>();
		for (TLObject obj : objects) {
			boolean fits = LogicSupport.isA(obj, "ehome.runtime:Rule") || LogicSupport.isA(obj, "ehome.runtime:ScheduleEntry")
				|| ApplicationDatapoints.isApplicationDatapoint(obj);
			if (!fits) {
				return LogicSupport.wrongKind("objects", obj, "a rule, a schedule entry or an application datapoint");
			}
			if (project(obj) != owner) {
				return failure("The objects belong to different projects. Nothing was deleted.");
			}
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("item", AutomationChange.itemName(obj));
			String imageId = Pages.imageId(obj);
			List<Object> usage = usage(owner, obj, LogicSupport.isA(obj, "ehome.runtime:Rule") ? null : imageId);
			usage.removeIf(use -> objects.stream().anyMatch(o -> id(o).equals(((Map<?, ?>) use).get("id"))));
			entry.put("stillReferredToBy", usage);
			report.add(entry);
		}
		if (Boolean.TRUE.equals(dryRun)) {
			return answer("Dry run, nothing was deleted:", report);
		}
		ToolResult denied = writeDenied(context, owner);
		if (denied != null) {
			return denied;
		}
		for (TLObject obj : objects) {
			if (obj.tValid()) {
				obj.tDelete();
			}
		}
		return answer("Deleted. " + SAVED, report);
	}

}
