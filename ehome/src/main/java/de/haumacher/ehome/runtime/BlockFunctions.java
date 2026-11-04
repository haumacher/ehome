/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.Resources;

import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.rules.ValueType;

/**
 * The TL-Script functions of the virtual devices of a project (haumacher/ehome #75,
 * {@link BlockInstances}), registered in <code>ehome.runtime.config.xml</code> under the names of
 * their {@link Function} constants. Only {@link Function#blockCreate} and {@link Function#blockBind}
 * change data; a view command calling them checks the Write access to their object.
 */
public class BlockFunctions extends GenericMethod {

	/** The functions, named as they are registered. */
	public enum Function {
		/** <code>blockTypeOptions(obj)</code>: the keys of the block types E-Home has. */
		blockTypeOptions(1),
		/** <code>blockTypeLabel(key)</code>: the type of the key in the user's language with its version. */
		blockTypeLabel(1),
		/**
		 * <code>blockCreate(project, key, room)</code>: a new virtual device of the type in the room
		 * (side effects).
		 */
		blockCreate(3),
		/** <code>blockBind(port, datapoint)</code>: binds the datapoint to the port (side effects). */
		blockBind(2),
		/**
		 * <code>blockBindingOptions(port)</code>: the datapoints of the project the port can bind: of
		 * a type it reads (an input) or commands (an output), not the instance's own, not bound yet.
		 */
		blockBindingOptions(1),
		/** <code>blockLabel(portOrParameter)</code>: its label in the user's language. */
		blockLabel(1),
		/** <code>blockPortInfo(port)</code>: direction, type, unit, how many datapoints and its aggregations. */
		blockPortInfo(1),
		/** <code>blockPortBindings(port)</code>: the ids of its bound datapoints, a deleted one marked. */
		blockPortBindings(1),
		/** <code>blockRange(parameter)</code>: its bounds or choices. */
		blockRange(1),
		/** <code>blockDefault(parameter)</code>: the type's default. */
		blockDefault(1),
		/** <code>blockEffective(parameter)</code>: the value the image gives it (set or default). */
		blockEffective(1),
		/** <code>blockAdjustable(parameter)</code>: whether the inhabitant may change it. */
		blockAdjustable(1),
		/** <code>blockInputPorts(instance)</code>: its input ports in manifest order. */
		blockInputPorts(1),
		/** <code>blockOutputPorts(instance)</code>: its output ports in manifest order. */
		blockOutputPorts(1),
		/** <code>blockRoomInstances(part)</code>: the virtual devices of the part of a building. */
		blockRoomInstances(1),
		/** <code>blockProblem(instance)</code>: what the image refuses of the instance, <code>null</code> for nothing. */
		blockProblem(1);

		private final int _arity;

		Function(int arity) {
			_arity = arity;
		}

		/** The number of arguments. */
		public int arity() {
			return _arity;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link BlockFunctions}.
	 */
	protected BlockFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new BlockFunctions(getName(), _function, arguments);
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
		return _function != Function.blockCreate && _function != Function.blockBind;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		String language = Resources.getInstance().getLocale().getLanguage();
		Object argument = arguments[0];
		switch (_function) {
			case blockTypeOptions:
				return BlockInstances.typeKeys();
			case blockTypeLabel: {
				BlockType type = BlockInstances.typeOfKey(argument == null ? null : argument.toString());
				if (type == null) {
					return argument == null ? null : argument.toString();
				}
				String label = BlockInstances.typeLabel(type.manifest()).getOrDefault(language, type.manifest().label());
				return label + " " + type.manifest().version();
			}
			case blockCreate:
				return BlockInstances.create((TLObject) argument, String.valueOf(arguments[1]),
					(TLObject) arguments[2], null);
			default:
				break;
		}
		if (!(argument instanceof TLObject obj) || !obj.tValid()) {
			return _function == Function.blockBindingOptions || _function == Function.blockInputPorts
				|| _function == Function.blockOutputPorts || _function == Function.blockRoomInstances ? List.of() : null;
		}
		switch (_function) {
			case blockBind:
				return arguments[1] instanceof TLObject datapoint ? BlockInstances.bind(obj, datapoint) : null;
			case blockBindingOptions:
				return bindingOptions(obj);
			case blockLabel: {
				String type = obj.tContainer() == null ? null : String.valueOf(obj.tContainer().tValueByName("blockType"));
				if (BlockInstances.isPort(obj)) {
					Port decl = BlockInstances.portDecl(obj);
					return decl == null ? obj.tValueByName("port")
						: BlockTexts.label(type, decl.id(), decl.label(), language) + unit(decl.unit());
				}
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? obj.tValueByName("parameter")
					: BlockTexts.label(type, decl.id(), decl.label(), language) + unit(decl.unit());
			}
			case blockPortInfo:
				return portInfo(obj);
			case blockPortBindings: {
				List<String> result = new ArrayList<>();
				for (TLObject binding : RuleExport.objects(obj.tValueByName("bindings"))) {
					if (binding.tValueByName("datapoint") instanceof TLObject datapoint && datapoint.tValid()) {
						result.add(BlockInstances.datapointImageId(datapoint));
					} else {
						result.add(Resources.getInstance().getString(
							I18NConstants.BLOCK_BINDING_GONE__ID.fill(String.valueOf(binding.tValueByName("boundId")))));
					}
				}
				return String.join(", ", result);
			}
			case blockRange: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? null : choices(obj, decl, language);
			}
			case blockDefault: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? null : BlockInstances.display(decl, decl.defaultValue());
			}
			case blockEffective: {
				Param decl = BlockInstances.paramDecl(obj);
				if (decl == null) {
					return null;
				}
				return BlockInstances.display(decl,
					obj.tValueByName("value") instanceof Number value ? value.doubleValue() : decl.defaultValue());
			}
			case blockAdjustable: {
				Param decl = BlockInstances.paramDecl(obj);
				return Boolean.valueOf(decl != null && decl.adjustable());
			}
			case blockInputPorts:
			case blockOutputPorts: {
				List<TLObject> result = new ArrayList<>();
				for (TLObject port : RuleExport.objects(obj.tValueByName("ports"))) {
					Port decl = BlockInstances.portDecl(port);
					if (decl != null && decl.isInput() == (_function == Function.blockInputPorts)) {
						result.add(port);
					}
				}
				return result;
			}
			case blockRoomInstances: {
				List<TLObject> result = new ArrayList<>();
				for (TLObject instance : BlockInstances.of(RuntimeFunctions.project(obj))) {
					if (instance.tValueByName("room") == obj) {
						result.add(instance);
					}
				}
				return result;
			}
			case blockProblem:
				return problem(obj);
			default:
				return null;
		}
	}

	private static String unit(String unit) {
		return unit.isEmpty() ? "" : " (" + unit + ")";
	}

	private static String choices(TLObject parameter, Param decl, String language) {
		if (decl.choices().isEmpty()) {
			return BlockInstances.range(decl);
		}
		String type = String.valueOf(parameter.tContainer().tValueByName("blockType"));
		List<String> result = new ArrayList<>();
		for (Choice choice : decl.choices()) {
			result.add(BlockInstances.format(choice.value()) + " = "
				+ BlockTexts.label(type, decl.id() + "." + choice.id(), choice.label(), language));
		}
		return String.join(", ", result);
	}

	private static String portInfo(TLObject port) {
		Port decl = BlockInstances.portDecl(port);
		if (decl == null) {
			return null;
		}
		Resources resources = Resources.getInstance();
		StringBuilder result = new StringBuilder();
		result.append(resources.getString(decl.isInput() ? I18NConstants.BLOCK_PORT_INPUT : I18NConstants.BLOCK_PORT_OUTPUT));
		result.append(" · ").append(decl.type().externalName());
		if (!decl.unit().isEmpty()) {
			result.append(" · ").append(decl.unit());
		}
		if (decl.required()) {
			result.append(" · ").append(resources.getString(I18NConstants.BLOCK_PORT_REQUIRED));
		}
		if (decl.diagnostic()) {
			result.append(" · ").append(resources.getString(I18NConstants.BLOCK_PORT_DIAGNOSTIC));
		}
		if (decl.isInput() && decl.multiple() > 1) {
			List<String> names = new ArrayList<>();
			for (Aggregation a : decl.aggregations()) {
				names.add(a.externalName());
			}
			result.append(" · ").append(resources.getString(
				I18NConstants.BLOCK_PORT_MULTIPLE__COUNT_AGGREGATIONS.fill(Integer.valueOf(decl.multiple()),
					String.join(", ", names))));
		}
		return result.toString();
	}

	/**
	 * The datapoints the port can bind: those of the project (as the pages list them) of a type the
	 * port reads or commands, a writable one for an output, not a datapoint of its own instance and not
	 * bound to it already.
	 */
	private static List<TLObject> bindingOptions(TLObject port) {
		Port decl = BlockInstances.portDecl(port);
		TLObject instance = port.tContainer();
		if (decl == null || instance == null) {
			return List.of();
		}
		List<TLObject> bound = BlockInstances.bound(port);
		List<TLObject> result = new ArrayList<>();
		for (TLObject datapoint : Pages.datapoints(RuntimeFunctions.project(instance))) {
			if (bound.contains(datapoint) || datapoint == instance || datapoint.tContainer() == instance) {
				continue;
			}
			String name = Pages.valueType(datapoint);
			ValueType type = name == null ? null : ValueType.fromName(name);
			if (type == null) {
				continue;
			}
			if (decl.isInput() ? decl.type().reads(type) : decl.type().commands(type) && writable(datapoint)) {
				result.add(datapoint);
			}
		}
		return result;
	}

	private static boolean writable(TLObject datapoint) {
		if (BlockInstances.isBlockDatapoint(datapoint)) {
			// The datapoints of virtual devices are owned by the block runtime (spec/blocks.md B14).
			return false;
		}
		if (ApplicationDatapoints.isApplicationDatapoint(datapoint)) {
			return ApplicationDatapoints.writable(datapoint);
		}
		if (Pages.isRuleParameter(datapoint) || Pages.isTimeSchedule(datapoint)) {
			return false;
		}
		return DatapointSettings.writable(datapoint);
	}

	/** What the image refuses of the instance (the refusals whose path leads to it). */
	private static String problem(TLObject instance) {
		TLObject project = RuntimeFunctions.project(instance);
		if (project == null) {
			return null;
		}
		RuntimeImage image = RuntimeImage.build(project, 1, java.time.Instant.now());
		List<String> result = new ArrayList<>();
		for (ImageReport.Refusal refusal : image.validate().getRefusals()) {
			TLObject element = image.elementAt(refusal.path());
			if (element == instance || (element != null && element.tContainer() == instance)) {
				result.add(refusal.path() + ": " + refusal.reason());
			}
		}
		return result.isEmpty() ? null : String.join("\n", result);
	}

	/** The builder of a {@link BlockFunctions} function. */
	public static final class Builder extends AbstractSimpleMethodBuilder<BlockFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public BlockFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function.arity(), _function.arity());
			return new BlockFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
