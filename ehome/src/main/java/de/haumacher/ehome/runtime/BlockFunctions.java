/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.thread.ThreadContext;
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
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.programming.BuildingDevices;

/**
 * The TL-Script functions of the virtual devices of a project (haumacher/ehome #75,
 * {@link BlockInstances}), registered in <code>ehome.runtime.config.xml</code> under the names of
 * their {@link Function} constants. Only {@link Function#blockCreate}, {@link Function#blockBind},
 * {@link Function#blockApplyValue} and {@link Function#blockApplyAggregation} change data; a view
 * command calling them checks the Write access to their object.
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
		/**
		 * <code>blockAllRoomInstances(part)</code>: the virtual devices of the part of a building and
		 * of all its sub-parts, in the order of the project.
		 */
		blockAllRoomInstances(1),
		/** <code>blockProblem(instance)</code>: what the image refuses of the instance, <code>null</code> for nothing. */
		blockProblem(1),
		/**
		 * <code>blockValue(parameter)</code>: the value the parameter is set to for the user (a choice
		 * by its label, a duration in the manifest's unit), <code>null</code> when it is not set.
		 */
		blockValue(1),
		/**
		 * <code>blockParameterKind(parameter)</code>: how it is entered, <code>boolean</code>,
		 * <code>choice</code>, <code>duration</code>, <code>time</code> or <code>number</code>.
		 */
		blockParameterKind(1),
		/** <code>blockUnit(parameter)</code>: the unit its value is shown and entered in, empty for none. */
		blockUnit(1),
		/** <code>blockChoiceOptions(parameter)</code>: the labels of its choices in manifest order. */
		blockChoiceOptions(1),
		/**
		 * <code>blockEditValue(parameter)</code>: the value its input shows, the value of the image (set
		 * or default) in the form of {@link BlockValues#editValue}.
		 */
		blockEditValue(1),
		/**
		 * <code>blockApplyValue(parameter, value)</code>: sets the entered value (side effects); the
		 * type's default or an empty input removes the value. The reason it is refused, <code>null</code>
		 * when it is applied.
		 */
		blockApplyValue(2),
		/** <code>blockAggregated(port)</code>: whether it is an input that binds several datapoints. */
		blockAggregated(1),
		/** <code>blockAggregationOptions(port)</code>: the labels of the aggregations it allows. */
		blockAggregationOptions(1),
		/** <code>blockAggregationValue(port)</code>: the label of the aggregation in effect (set or default). */
		blockAggregationValue(1),
		/**
		 * <code>blockApplyAggregation(port, label)</code>: sets the aggregation of the label (side
		 * effects); the port's default or an empty input removes it.
		 */
		blockApplyAggregation(2),
		/**
		 * <code>blockBindingUnit(port, datapoint)</code>: the unit of the datapoint, with a hint where it
		 * has none and the port has one.
		 */
		blockBindingUnit(2);

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
		switch (_function) {
			case blockCreate:
			case blockBind:
			case blockApplyValue:
			case blockApplyAggregation:
				return false;
			default:
				return true;
		}
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
			switch (_function) {
				case blockBindingOptions:
				case blockInputPorts:
				case blockOutputPorts:
				case blockRoomInstances:
				case blockAllRoomInstances:
				case blockChoiceOptions:
				case blockAggregationOptions:
					return List.of();
				default:
					return null;
			}
		}
		switch (_function) {
			case blockBind:
				return arguments[1] instanceof TLObject datapoint ? BlockInstances.bind(obj, datapoint) : null;
			case blockBindingOptions:
				return BlockInstances.bindingOptions(obj);
			case blockLabel: {
				String type = obj.tContainer() == null ? null : String.valueOf(obj.tContainer().tValueByName("blockType"));
				if (BlockInstances.isPort(obj)) {
					Port decl = BlockInstances.portDecl(obj);
					return decl == null ? obj.tValueByName("port")
						: BlockTexts.label(type, decl.id(), decl.label(), language) + unit(decl.unit());
				}
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? obj.tValueByName("parameter")
					: BlockTexts.label(type, decl.id(), decl.label(), language);
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
				return decl == null ? null : BlockValues.range(blockType(obj), decl);
			}
			case blockDefault: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? null : BlockValues.display(blockType(obj), decl, decl.defaultValue());
			}
			case blockEffective: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? null : BlockValues.display(blockType(obj), decl, effective(obj, decl));
			}
			case blockValue: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null || !(obj.tValueByName("value") instanceof Number value) ? null
					: BlockValues.display(blockType(obj), decl, value.doubleValue());
			}
			case blockParameterKind: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? null : BlockValues.kind(decl).externalName();
			}
			case blockUnit: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? null : BlockValues.unit(decl);
			}
			case blockChoiceOptions: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? List.of() : BlockValues.choiceLabels(blockType(obj), decl);
			}
			case blockEditValue: {
				Param decl = BlockInstances.paramDecl(obj);
				return decl == null ? null
					: BlockValues.editValue(blockType(obj), decl, effective(obj, decl), ThreadContext.getTimeZone());
			}
			case blockApplyValue:
				return BlockInstances.applyValue(obj, arguments[1], ThreadContext.getTimeZone());
			case blockAggregated: {
				Port decl = BlockInstances.portDecl(obj);
				return Boolean.valueOf(decl != null && decl.isInput() && decl.multiple() > 1);
			}
			case blockAggregationOptions: {
				Port decl = BlockInstances.portDecl(obj);
				List<String> result = new ArrayList<>();
				if (decl != null) {
					for (Aggregation a : decl.aggregations()) {
						result.add(BlockValues.aggregationLabel(a));
					}
				}
				return result;
			}
			case blockAggregationValue: {
				Port decl = BlockInstances.portDecl(obj);
				if (decl == null || !decl.isInput() || decl.multiple() < 2) {
					return null;
				}
				Aggregation set = obj.tValueByName("aggregation") instanceof String name ? Aggregation.of(name.trim())
					: Aggregation.NONE;
				return BlockValues.aggregationLabel(set != Aggregation.NONE ? set : decl.defaultAggregation());
			}
			case blockApplyAggregation:
				return BlockInstances.applyAggregation(obj, arguments[1]);
			case blockBindingUnit:
				return arguments[1] instanceof TLObject datapoint ? BlockInstances.bindingUnit(obj, datapoint) : null;
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
			case blockAllRoomInstances: {
				Set<TLObject> parts = new HashSet<>(BuildingDevices.partsWithin(obj));
				List<TLObject> result = new ArrayList<>();
				for (TLObject instance : BlockInstances.of(RuntimeFunctions.project(obj))) {
					if (parts.contains(instance.tValueByName("room"))) {
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

	private static String blockType(TLObject parameter) {
		return parameter.tContainer() == null ? null : String.valueOf(parameter.tContainer().tValueByName("blockType"));
	}

	private static double effective(TLObject parameter, Param decl) {
		return parameter.tValueByName("value") instanceof Number value ? value.doubleValue() : decl.defaultValue();
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
				names.add(BlockValues.aggregationLabel(a));
			}
			result.append(" · ").append(resources.getString(
				I18NConstants.BLOCK_PORT_MULTIPLE__COUNT_AGGREGATIONS.fill(Integer.valueOf(decl.multiple()),
					String.join(", ", names))));
		}
		return result.toString();
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
				result.add(refusal.path() + ": " + refusal.text());
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
