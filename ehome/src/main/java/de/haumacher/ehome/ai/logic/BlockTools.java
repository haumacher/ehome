/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import static de.haumacher.ehome.ai.logic.LogicSupport.answer;
import static de.haumacher.ehome.ai.logic.LogicSupport.failure;
import static de.haumacher.ehome.ai.logic.LogicSupport.id;
import static de.haumacher.ehome.ai.logic.LogicSupport.label;
import static de.haumacher.ehome.ai.logic.LogicSupport.project;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.model.TLObject;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.core.tools.refusal.ApplicationRefusals;
import com.top_logic.tl.ai.core.tools.refusal.messages.Reason;
import com.top_logic.tl.ai.tool.api.P;
import com.top_logic.tl.ai.tool.api.ToolGroups;
import com.top_logic.tl.ai.tool.api.ToolMethod;
import com.top_logic.tl.ai.tool.api.ToolMode;
import com.top_logic.tl.ai.tool.api.ToolResult;

import de.haumacher.ehome.ai.logic.LogicSupport.Outcome;
import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.runtime.BlockDiagnostics;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.BlockTexts;
import de.haumacher.ehome.runtime.BlockValues;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuntimeFunctions;

/**
 * The assistant's tools for the virtual devices of a project (haumacher/ehome #128, homecontrol
 * <code>spec/blocks.md</code>): the block types with their ports and parameters, creating an instance
 * in a room with its bindings and parameters as the blocks view does (<code>blockCreate</code>,
 * <code>blockBind</code>, <code>blockApplyValue</code>), binding further datapoints, and the problems
 * and diagnostics of an instance.
 */
public class BlockTools {

	/**
	 * The block types.
	 */
	@ToolMethod(description = "Lists the block types E-Home's controller runs as virtual devices (room controller,"
		+ " heating demand, heating curve, sun shading, house mode, PV surplus control, ...): for each its key"
		+ " (as createBlock takes it), label, ports (input or output, value type, unit, required, how many"
		+ " datapoints an input aggregates) and parameters (type, unit, default, range or choices), with the"
		+ " help texts. Read it before you create a virtual device.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult blockTypes() {
		List<Object> result = new ArrayList<>();
		for (String key : BlockInstances.typeKeys()) {
			BlockType type = BlockInstances.typeOfKey(key);
			Manifest m = type.manifest();
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("key", key);
			entry.put("label", m.label());
			if (m.description() != null) {
				entry.put("description", m.description());
			}
			List<Object> ports = new ArrayList<>();
			for (Port port : m.ports()) {
				Map<String, Object> p = new LinkedHashMap<>();
				p.put("id", port.id());
				p.put("label", port.label());
				p.put("direction", port.isInput() ? "input" : "output");
				p.put("type", port.type().externalName());
				if (!port.unit().isEmpty()) {
					p.put("unit", port.unit());
				}
				p.put("required", Boolean.valueOf(port.required()));
				if (port.isInput() && port.multiple() > 1) {
					p.put("multiple", Integer.valueOf(port.multiple()));
				}
				help(p, m.type(), port.id());
				ports.add(p);
			}
			entry.put("ports", ports);
			List<Object> params = new ArrayList<>();
			for (Param param : m.params()) {
				Map<String, Object> p = new LinkedHashMap<>();
				p.put("id", param.id());
				p.put("label", param.label());
				p.put("kind", BlockValues.kind(param).name().toLowerCase());
				p.put("default", BlockValues.display(m.type(), param, param.defaultValue()));
				p.put("range", BlockValues.range(m.type(), param));
				if (!param.unit().isEmpty()) {
					p.put("unit", BlockValues.unit(param));
				}
				help(p, m.type(), param.id());
				params.add(p);
			}
			entry.put("parameters", params);
			result.add(entry);
		}
		return answer("The block types:", result);
	}

	private static void help(Map<String, Object> entry, String type, String id) {
		String help = BlockTexts.help(type, id, "en");
		if (help != null) {
			entry.put("help", help);
		}
	}

	/**
	 * Creates a virtual device.
	 */
	@ToolMethod(description = "Creates a virtual device (an instance of a block type the controller runs) in a"
		+ " room of an E-Home project, as the blocks view does: with its ports bound to datapoints (by their ids"
		+ " in the image: the identifier of a group address or its address \"1/2/3\", the id of an application"
		+ " datapoint) and parameters set"
		+ " as the view's inputs take them (a number, true/false, the label of a choice, a duration in the"
		+ " parameter's unit, a time of day as \"06:30\"). Only datapoints the port can bind are taken: of a"
		+ " type it reads (input) or commands (output, writable), of its unit. The answer names what the"
		+ " runtime image would still refuse (a required port without a datapoint). " + LogicSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, dryRun = "dryRun")
	public ToolResult createBlock(ToolContext context,
			@P("The room (a building part) the device belongs to") TLObject room,
			@P("The key of the block type, e.g. \"org.homecontrol.room-controller 1.0\" (blockTypes lists them)") String type,
			@P(value = "The name; omitted: the type's label", required = false) String name,
			@P(value = "The bindings by port: {\"temperature\": [\"1/3/0\"], \"valve\": [\"1/3/30\"]}", required = false)
			Map<String, Object> bindings,
			@P(value = "The parameters by id: {\"comfort\": 21.5}", required = false) Map<String, Object> parameters,
			@P(value = "true to only check", required = false) Boolean dryRun) {
		TLObject owner = project(room);
		if (owner == null || !RuntimeFunctions.buildingParts(owner).contains(room)) {
			return LogicSupport.wrongKind("room", room, "a building part of a project");
		}
		if (BlockInstances.typeOfKey(type) == null) {
			return failure("Unknown block type '" + type + "'; the types are " + BlockInstances.typeKeys()
				+ ". Nothing was created.");
		}
		boolean dry = Boolean.TRUE.equals(dryRun);
		return LogicSupport.perform(context, owner, dry, () -> {
			TLObject instance = BlockInstances.create(owner, type, room, name == null || name.isBlank() ? null
				: LogicSupport.allLanguages(name));
			List<Reason> errors = new ArrayList<>();
			bind(instance, bindings, errors);
			setParameters(instance, parameters, errors);
			if (!errors.isEmpty()) {
				return Outcome.refused(errors);
			}
			errors.addAll(LogicSupport.reasons(AutomationChange.check("virtual device " + label(instance), instance), false));
			if (!errors.isEmpty()) {
				return Outcome.refused(errors);
			}
			return Outcome.done(dry ? "The virtual device can be created." : "Created.", describe(owner, instance, !dry));
		});
	}

	/**
	 * Binds further datapoints to ports of a virtual device.
	 */
	@ToolMethod(description = "Binds datapoints to ports of an existing virtual device of E-Home, as the bind"
		+ " dialog of the blocks view does (a binding is added; an input that aggregates takes several). The"
		+ " datapoints by their ids in the image; only those the port can bind are taken. "
		+ LogicSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, dryRun = "dryRun")
	public ToolResult bindBlock(ToolContext context,
			@P("The virtual device (ehome.runtime:BlockInstance)") TLObject block,
			@P("The bindings by port: {\"window\": [\"wz.window\"]}") Map<String, Object> bindings,
			@P(value = "true to only check", required = false) Boolean dryRun) {
		if (block == null || !BlockInstances.isInstance(block)) {
			return LogicSupport.wrongKind("block", block, "a virtual device (ehome.runtime:BlockInstance)");
		}
		TLObject owner = project(block);
		boolean dry = Boolean.TRUE.equals(dryRun);
		return LogicSupport.perform(context, owner, dry, () -> {
			List<Reason> errors = new ArrayList<>();
			bind(block, bindings, errors);
			if (!errors.isEmpty()) {
				return Outcome.refused(errors);
			}
			return Outcome.done(dry ? "The datapoints can be bound." : "Bound.", describe(owner, block, true));
		});
	}

	/**
	 * The state of a virtual device.
	 */
	@ToolMethod(description = "Answers how a virtual device of E-Home stands: its type, room, the datapoints of"
		+ " its ports (Name (id)), its parameters, and what the runtime image refuses of it (a required port"
		+ " without a datapoint, a parameter out of range). With live=true also the diagnostics of the deployed"
		+ " instance as the project's controller reports them (read only; the controller is asked over the"
		+ " network, nothing is changed there).",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult blockStatus(
			@P("The virtual device (ehome.runtime:BlockInstance)") TLObject block,
			@P(value = "true to read the diagnostics from the controller as well", required = false) Boolean live) {
		if (block == null || !BlockInstances.isInstance(block)) {
			return LogicSupport.wrongKind("block", block, "a virtual device (ehome.runtime:BlockInstance)");
		}
		Map<String, Object> result = describe(project(block), block, true);
		if (Boolean.TRUE.equals(live)) {
			result.put("controller", BlockDiagnostics.read(block));
		}
		return answer("The virtual device:", result);
	}

	// ---- helpers -----------------------------------------------------------------------------

	private static void bind(TLObject instance, Map<String, Object> bindings, List<Reason> errors) {
		if (bindings == null) {
			return;
		}
		for (Map.Entry<String, Object> binding : bindings.entrySet()) {
			TLObject port = BlockInstances.port(instance, binding.getKey());
			if (port == null) {
				errors.add(ApplicationRefusals.reason("The block type has no port '" + binding.getKey() + "'.")
					.setAttribute(binding.getKey()));
				continue;
			}
			Object value = binding.getValue();
			List<?> ids = value instanceof List<?> list ? list : List.of(value);
			for (Object id : ids) {
				TLObject datapoint = null;
				List<String> options = new ArrayList<>();
				for (TLObject option : BlockInstances.bindingOptions(port)) {
					String optionId = BlockInstances.datapointImageId(option);
					options.add(optionId);
					// A group address also by its address where its datapoint has an identifier (haumacher/ehome #130).
					if (String.valueOf(id).equals(optionId)
						|| Pages.isGroupAddress(option) && String.valueOf(id).equals(GroupAddressIds.address(option))) {
						datapoint = option;
					}
				}
				if (datapoint == null) {
					errors.add(ApplicationRefusals.reason("'" + id + "' cannot be bound to the port: it is no datapoint of"
						+ " the project of a type, writability and unit the port takes, or it is bound already. The port"
						+ " takes " + (options.isEmpty() ? "none of the project's datapoints" : String.join(", ", options))
						+ ".").setAttribute(binding.getKey()));
					continue;
				}
				BlockInstances.bind(port, datapoint);
			}
		}
	}

	private static void setParameters(TLObject instance, Map<String, Object> parameters, List<Reason> errors) {
		if (parameters == null) {
			return;
		}
		TimeZone zone = ThreadContext.getTimeZone();
		for (Map.Entry<String, Object> entry : parameters.entrySet()) {
			TLObject parameter = BlockInstances.parameter(instance, entry.getKey());
			if (parameter == null) {
				errors.add(ApplicationRefusals.reason("The block type has no parameter '" + entry.getKey() + "'.")
					.setAttribute(entry.getKey()));
				continue;
			}
			Object entered = entry.getValue();
			Param decl = BlockInstances.paramDecl(parameter);
			if (decl != null && BlockValues.kind(decl) == BlockValues.Kind.TIME && entered instanceof String text
				&& text.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
				String[] parts = text.split(":");
				Calendar calendar = Calendar.getInstance(zone);
				calendar.clear();
				calendar.set(1970, Calendar.JANUARY, 1, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
					parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
				entered = calendar.getTime();
			}
			String refusal = BlockInstances.applyValue(parameter, entered, zone);
			if (refusal != null) {
				errors.add(ApplicationRefusals.reason(refusal).setAttribute(entry.getKey()));
			}
		}
	}

	/**
	 * A virtual device as the answers show it, with what the image refuses of it.
	 */
	private static Map<String, Object> describe(TLObject project, TLObject instance, boolean withId) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (withId) {
			result.put("id", id(instance));
		}
		result.put("name", label(instance));
		result.put("identifier", instance.tValueByName("identifier"));
		result.put("type", instance.tValueByName("blockType") + " " + instance.tValueByName("version"));
		if (instance.tValueByName("room") instanceof TLObject room) {
			result.put("room", label(room));
		}
		Map<String, Object> ports = new LinkedHashMap<>();
		for (TLObject port : AutomationChange.objects(instance.tValueByName("ports"))) {
			List<String> bound = new ArrayList<>();
			for (TLObject datapoint : BlockInstances.bound(port)) {
				bound.add(DatapointNames.format(DatapointNames.label(datapoint), BlockInstances.datapointImageId(datapoint)));
			}
			ports.put(String.valueOf(port.tValueByName("port")), bound);
		}
		result.put("ports", ports);
		Map<String, Object> parameters = new LinkedHashMap<>();
		String type = String.valueOf(instance.tValueByName("blockType"));
		for (TLObject parameter : AutomationChange.objects(instance.tValueByName("parameters"))) {
			Param decl = BlockInstances.paramDecl(parameter);
			if (decl != null) {
				double value = parameter.tValueByName("value") instanceof Number n ? n.doubleValue() : decl.defaultValue();
				parameters.put(decl.id(), BlockValues.display(type, decl, value));
			}
		}
		result.put("parameters", parameters);
		List<String> refused = FunctionTools.imageRefusals(project, instance);
		result.put("imageRefuses", refused);
		return result;
	}

}
