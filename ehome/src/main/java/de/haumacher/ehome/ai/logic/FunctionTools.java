/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import static de.haumacher.ehome.ai.logic.LogicSupport.failure;
import static de.haumacher.ehome.ai.logic.LogicSupport.foreign;
import static de.haumacher.ehome.ai.logic.LogicSupport.id;
import static de.haumacher.ehome.ai.logic.LogicSupport.label;
import static de.haumacher.ehome.ai.logic.LogicSupport.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.core.tools.refusal.messages.Reason;
import com.top_logic.tl.ai.tool.api.P;
import com.top_logic.tl.ai.tool.api.ToolExecutionContext;
import com.top_logic.tl.ai.tool.api.ToolGroups;
import com.top_logic.tl.ai.tool.api.ToolMethod;
import com.top_logic.tl.ai.tool.api.ToolMode;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.ai.logic.LogicSupport.Outcome;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The assistant's tools for the functions and the control pages of a project (haumacher/ehome
 * #128): the function proposal of the functions view, dismissing a function, creating a function
 * with its roles, creating a control page with sections of rooms, functions, datapoints and rule
 * switches as the pages view inserts them.
 */
public class FunctionTools {

	/**
	 * Runs the function proposal.
	 */
	@ToolMethod(description = "Runs the function proposal of E-Home for a project, as the command \"Propose"
		+ " functions\" of the functions view does: it adds the functions the channels of the devices, the Zigbee"
		+ " datapoints and the virtual devices suggest and the project lacks (lights, dimmers, blinds, switches,"
		+ " sensors, climate, scenes, buttons, each with its roles bound to group addresses), and updates the"
		+ " existing proposed ones. Functions dismissed earlier are not added again. The answer lists the functions in"
		+ " use apart from the unused ones (a channel without group address: stored, but not deployed to the"
		+ " controller), and the warnings about the wiring the devices' objects show (a status object sending on the"
		+ " command address, an address whose datapoint type contradicts the object). With dryRun=true it answers"
		+ " what it would add and update without changing anything; show that to the user before accepting. "
		+ LogicSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = LogicSupport.PROJECT, dryRun = "dryRun")
	public ToolResult proposeFunctions(ToolContext context,
			@P("The project, or any object of it") TLObject project,
			@P(value = "true to only answer what the proposal would add", required = false) Boolean dryRun) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project. Nothing was changed.");
		}
		boolean dry = Boolean.TRUE.equals(dryRun);
		return LogicSupport.perform(context, owner, dry, () -> {
			FunctionProposal.Run run = FunctionProposal.run(owner);
			List<TLObject> inUse = run.addedInUse();
			List<TLObject> unused = run.addedUnused();
			Map<String, Object> data = new LinkedHashMap<>();
			data.put(dry ? "wouldAdd" : "added", functions(inUse, !dry));
			data.put(dry ? "wouldAddUnused" : "addedUnused", functions(unused, !dry));
			data.put(dry ? "wouldUpdate" : "updated", functions(run.updated(), true));
			List<Object> warnings = new ArrayList<>();
			run.warnings().forEach((function, texts) -> {
				for (ResKey text : texts) {
					Map<String, Object> warning = new LinkedHashMap<>();
					if (!dry || !run.added().contains(function)) {
						warning.put("id", id(function));
					}
					warning.put("function", label(function));
					warning.put("warning", LogicSupport.text(text));
					warnings.add(warning);
				}
			});
			data.put("warnings", warnings);
			String sentence = (dry ? "Would add " : "Added ") + inUse.size() + " functions in use and " + unused.size()
				+ " unused functions (no group address: not deployed), " + (dry ? "would update " : "updated ")
				+ run.updated().size() + "; " + warnings.size() + " warnings about the wiring.";
			return Outcome.done(sentence, data);
		});
	}

	/**
	 * Dismisses functions.
	 */
	@ToolMethod(description = "Deletes functions of an E-Home project as the delete command of the functions view"
		+ " does: a function the proposal made is remembered as dismissed, so the next proposal does not add it"
		+ " again. With dryRun=true it only answers what still refers to each function (control pages): those"
		+ " refer to nothing afterwards. " + LogicSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = LogicSupport.PROJECT, dryRun = "dryRun")
	public ToolResult dismissFunctions(ToolContext context,
			@P("The functions (ehome.runtime:Function)") List<TLObject> functions,
			@P(value = "true to only answer what refers to them", required = false) Boolean dryRun) {
		if (functions == null || functions.isEmpty()) {
			return failure("'functions' is empty. Nothing was deleted.");
		}
		TLObject owner = project(functions.get(0));
		for (TLObject function : functions) {
			if (!LogicSupport.isA(function, "ehome.runtime:Function")) {
				return LogicSupport.wrongKind("functions", function, "a function (ehome.runtime:Function)");
			}
			ToolResult other = foreign(owner, function, "functions");
			if (other != null) {
				return other;
			}
		}
		List<Object> report = new ArrayList<>();
		for (TLObject function : functions) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("function", label(function));
			entry.put("proposed", Boolean.valueOf(function.tValueByName("channel") != null));
			entry.put("stillReferredToBy", RuleTools.usage(owner, function, null));
			report.add(entry);
		}
		boolean dry = Boolean.TRUE.equals(dryRun);
		return LogicSupport.perform(context, owner, dry, () -> {
			if (!dry) {
				for (TLObject function : functions) {
					FunctionProposal.dismiss(function);
				}
			}
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("functions", report);
			return Outcome.done(dry ? "These functions would be deleted." : "Deleted.", data);
		});
	}

	/**
	 * Creates a function.
	 */
	@ToolMethod(description = "Creates a function of an E-Home project (what the inhabitant operates on the"
		+ " controller's user interface), as the functions view creates one and its form fills it: a kind,"
		+ " a name, a room, and its roles bound to datapoints by their ids in the image (the identifier of a group"
		+ " address or its address \"1/2/3\", the id of an application datapoint). Roles by kind: light command,"
		+ " state; dimmer command, state, levelCommand, level, step (it needs command or levelCommand); blind move,"
		+ " stop, position, positionCommand, slat, slatCommand, atTop, atBottom (1-bit end position reports);"
		+ " switch command, state; sensor value; climate temperature, setpoint, setpointCommand, mode, modeCommand,"
		+ " heating (a truth value or the actuating value in percent), heatingLevel (the actuating value in"
		+ " percent); scene command (with 'scene' 1..64); button press, state. The first role is the main one."
		+ " Prefer proposeFunctions where the devices suggest the function. The answer names"
		+ " what the runtime image would refuse of the function. " + LogicSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = LogicSupport.PROJECT, dryRun = "dryRun")
	public ToolResult createFunction(ToolContext context,
			@P("The project, or any object of it") TLObject project,
			@P("The kind: light, dimmer, blind, switch, sensor, climate, scene or button") String kind,
			@P("The name the controller shows") String name,
			@P(value = "The room (a building part) it belongs to", required = false) TLObject room,
			@P("The roles: {\"command\": \"1/1/0\", \"state\": \"1/1/1\"}") Map<String, Object> roles,
			@P(value = "The scene number 1..64 of a scene", required = false) Integer scene,
			@P(value = "true to only check the function", required = false) Boolean dryRun) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project. Nothing was created.");
		}
		if (kind == null || !FunctionKinds.ROLES.containsKey(kind)) {
			return failure("Unknown kind '" + kind + "'; the kinds are " + String.join(", ", FunctionKinds.ROLES.keySet())
				+ ". Nothing was created.");
		}
		if (name == null || name.isBlank()) {
			return failure("'name' is empty. Nothing was created.");
		}
		if (room != null) {
			ToolResult other = foreign(owner, room, "room");
			if (other != null || !RuntimeFunctions.buildingParts(owner).contains(room)) {
				return other != null ? other : LogicSupport.wrongKind("room", room, "a building part of the project");
			}
		}
		Map<String, TLObject> targets = new LinkedHashMap<>();
		List<String> roleNames = FunctionKinds.roles(kind);
		Map<String, TLObject> byImageId = new LinkedHashMap<>();
		for (TLObject datapoint : RuntimeFunctions.roleDatapoints(owner)) {
			byImageId.putIfAbsent(Pages.imageId(datapoint), datapoint);
		}
		GroupAddressIds.addAddresses(byImageId);
		if (roles != null) {
			for (Map.Entry<String, Object> role : roles.entrySet()) {
				if (!roleNames.contains(role.getKey())) {
					return failure("A " + kind + " has no role '" + role.getKey() + "'; its roles are "
						+ String.join(", ", roleNames) + ". Nothing was created.");
				}
				TLObject target = byImageId.get(String.valueOf(role.getValue()));
				if (target == null) {
					return failure("The role '" + role.getKey() + "' names '" + role.getValue() + "', which is no datapoint"
						+ " of the project a role can name (automation lists the ids). Nothing was created.");
				}
				targets.put(role.getKey(), target);
			}
		}
		boolean dry = Boolean.TRUE.equals(dryRun);
		return LogicSupport.perform(context, owner, dry, () -> {
			TLObject function = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Function"));
			function.tUpdateByName("name", LogicSupport.allLanguages(name));
			function.tUpdateByName("kind", FunctionProposal.classifier(kind));
			function.tUpdateByName("room", room);
			if (scene != null) {
				function.tUpdateByName("scene", scene);
			}
			targets.forEach((role, target) -> function.tUpdateByName(role, target));
			List<TLObject> functions = FunctionProposal.functions(owner);
			functions.add(function);
			owner.tUpdateByName("functions", functions);
			List<AutomationChange.Problem> problems = AutomationChange.check("function " + name, function);
			List<Reason> errors = LogicSupport.reasons(problems, false);
			if (!errors.isEmpty()) {
				return Outcome.refused(errors);
			}
			Map<String, Object> data = describe(function, !dry);
			List<String> refused = imageRefusals(owner, function);
			if (!refused.isEmpty()) {
				data.put("imageRefuses", refused);
			}
			return Outcome.done(dry ? "The function can be created." : "Created.", data);
		});
	}

	/**
	 * Creates a control page.
	 */
	@ToolMethod(description = "Creates a control page of an E-Home project (a page of the controller's user"
		+ " interface, replacing its generated room overview), as the pages view inserts pages, sections and"
		+ " elements: the page with its sections in order, each with a title and its elements - rooms (a room"
		+ " shows its functions and those of its sub-parts), functions, datapoints (by their ids in the image,"
		+ " shown as value or switch) and rule switches (a rule's enable switch). E.g. a page \"Ground floor\""
		+ " with one section per room, each holding that room. The answer names what the pages view marks as a"
		+ " problem (a room without functions). " + LogicSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = LogicSupport.PROJECT, dryRun = "dryRun")
	public ToolResult createPage(ToolContext context,
			@P("The project, or any object of it") TLObject project,
			@P("The name of the page") String name,
			@P("The sections: [{\"title\": \"Kitchen\", \"rooms\": [\"<room id>\"], \"functions\": [\"<function id>\"],"
				+ " \"datapoints\": [\"1/2/3\"], \"rules\": [\"<rule id>\"]}], the members but title optional; rooms,"
				+ " functions and rules as object ids (Type:id)") List<Map<String, Object>> sections,
			@P(value = "true to only check the page", required = false) Boolean dryRun) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project. Nothing was created.");
		}
		if (name == null || name.isBlank()) {
			return failure("'name' is empty. Nothing was created.");
		}
		ToolExecutionContext resolver = context;
		List<Map<String, List<TLObject>>> resolved = new ArrayList<>();
		List<String> titles = new ArrayList<>();
		Map<String, TLObject> byImageId = new LinkedHashMap<>();
		for (TLObject datapoint : Pages.datapoints(owner)) {
			byImageId.putIfAbsent(Pages.imageId(datapoint), datapoint);
		}
		GroupAddressIds.addAddresses(byImageId);
		for (Map<String, Object> section : sections == null ? List.<Map<String, Object>> of() : sections) {
			Map<String, List<TLObject>> elements = new LinkedHashMap<>();
			for (String kind : new String[] { Pages.ROOM, Pages.FUNCTION, Pages.DATAPOINT, Pages.RULE }) {
				List<TLObject> targets = new ArrayList<>();
				for (Object ref : AutomationJson.list(section.get(kind + "s"))) {
					TLObject target;
					if (kind.equals(Pages.DATAPOINT)) {
						target = byImageId.get(String.valueOf(ref));
					} else {
						try {
							target = resolver.resolveObject(String.valueOf(ref)) instanceof TLObject obj ? obj : null;
						} catch (IllegalArgumentException ex) {
							return failure("'" + ref + "' in " + kind + "s: " + ex.getMessage() + " Nothing was created.");
						}
					}
					if (target == null || !Pages.options(owner, kind).contains(target)) {
						return failure("'" + ref + "' is no " + kind + " of the project a page can show. Nothing was created.");
					}
					targets.add(target);
				}
				elements.put(kind, targets);
			}
			for (String key : section.keySet()) {
				if (!List.of("title", "rooms", "functions", "datapoints", "rules").contains(key)) {
					return failure("Unknown member '" + key + "' of a section. Nothing was created.");
				}
			}
			resolved.add(elements);
			titles.add(section.get("title") == null ? null : section.get("title").toString());
		}
		boolean dry = Boolean.TRUE.equals(dryRun);
		return LogicSupport.perform(context, owner, dry, () -> {
			TLObject last = null;
			for (TLObject page : Pages.children(owner)) {
				last = page;
			}
			TLObject page = Pages.insert(owner, last, Pages.PAGE, List.of());
			page.tUpdateByName("name", LogicSupport.allLanguages(name));
			List<Object> problems = new ArrayList<>();
			for (int n = 0; n < resolved.size(); n++) {
				TLObject section = Pages.insert(owner, page, Pages.SECTION, List.of());
				if (titles.get(n) != null) {
					section.tUpdateByName("title", LogicSupport.allLanguages(titles.get(n)));
				}
				for (Map.Entry<String, List<TLObject>> kind : resolved.get(n).entrySet()) {
					if (!kind.getValue().isEmpty()) {
						Pages.insert(owner, section, kind.getKey(), kind.getValue());
					}
				}
				for (TLObject element : Pages.children(section)) {
					ResKey problem = Pages.problem(element);
					if (problem != null) {
						problems.add(Pages.label(element) + ": " + LogicSupport.text(problem));
					}
				}
			}
			List<AutomationChange.Problem> checks = AutomationChange.check("page " + name, page);
			List<Reason> errors = LogicSupport.reasons(checks, false);
			if (!errors.isEmpty()) {
				return Outcome.refused(errors);
			}
			Map<String, Object> data = new LinkedHashMap<>();
			if (!dry) {
				data.put("page", LogicSupport.ref(page));
			}
			data.put("identifier", page.tValueByName("identifier"));
			List<Object> sectionList = new ArrayList<>();
			for (TLObject section : Pages.children(page)) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("section", Pages.label(section));
				List<String> elements = new ArrayList<>();
				for (TLObject element : Pages.children(section)) {
					elements.add(Pages.label(element));
				}
				entry.put("elements", elements);
				sectionList.add(entry);
			}
			data.put("sections", sectionList);
			if (!problems.isEmpty()) {
				data.put("problems", problems);
			}
			return Outcome.done(dry ? "The page can be created." : "Created.", data);
		});
	}

	// ---- helpers -----------------------------------------------------------------------------

	private static List<Object> functions(List<TLObject> functions, boolean withIds) {
		List<Object> result = new ArrayList<>();
		for (TLObject function : functions) {
			result.add(describe(function, withIds));
		}
		return result;
	}

	/**
	 * A function as the answers show it: name, kind, room and roles, the datapoints as Name (id).
	 */
	static Map<String, Object> describe(TLObject function, boolean withId) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (withId) {
			result.put("id", id(function));
		}
		result.put("name", label(function));
		String kind = FunctionProposal.kind(function);
		result.put("kind", kind);
		if (function.tValueByName("room") instanceof TLObject room) {
			result.put("room", label(room));
		}
		Map<String, Object> roles = new LinkedHashMap<>();
		for (String role : FunctionKinds.roles(kind)) {
			if (function.tValueByName(role) instanceof TLObject target) {
				roles.put(role, DatapointNames.name(target));
			}
		}
		result.put("roles", roles);
		return result;
	}

	/**
	 * What the runtime image refuses of the given object or its parts, as the user reads it.
	 */
	static List<String> imageRefusals(TLObject project, TLObject obj) {
		RuntimeImage image = RuntimeImage.build(project, 1, java.time.Instant.now());
		List<String> result = new ArrayList<>();
		for (ImageReport.Refusal refusal : image.validate().getRefusals()) {
			for (TLObject element = image.elementAt(refusal.path()); element != null; element = element.tContainer()) {
				if (element == obj) {
					result.add(LogicSupport.text(image.describe(refusal)));
					break;
				}
			}
		}
		return result;
	}

}
