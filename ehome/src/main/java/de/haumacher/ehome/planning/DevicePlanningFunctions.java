/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.planning;

import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.Resources;

/**
 * The TL-Script functions planning a device in the app, see {@link DevicePlanning} (haumacher/ehome
 * #104): the dialog of a new device (<code>projects/create-device.view.xml</code>) and the duplicate
 * of a device (<code>projects/devices.view.xml</code>).
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class DevicePlanningFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>devicePlanVariants()</code>: {@link DevicePlanning#variants()}. */
		devicePlanVariants(0, true),
		/** <code>devicePlanVariantLabel(variant)</code>: {@link DevicePlanning#variantLabel(TLObject)}. */
		devicePlanVariantLabel(1, true),
		/** <code>devicePlanPrograms(variant)</code>: {@link DevicePlanning#programs(TLObject)}. */
		devicePlanPrograms(1, true),
		/** <code>devicePlanDefaultProgram(variant)</code>: {@link DevicePlanning#defaultProgram(TLObject)}. */
		devicePlanDefaultProgram(1, true),
		/** <code>devicePlanProgramLabel(assignment)</code>: {@link DevicePlanning#programLabel(TLObject)}. */
		devicePlanProgramLabel(1, true),
		/** <code>devicePlanLine(node)</code>: {@link DevicePlanning#line(TLObject)}. */
		devicePlanLine(1, true),
		/** <code>devicePlanNextFree(line)</code>: {@link DevicePlanning#nextFree(TLObject)}. */
		devicePlanNextFree(1, true),
		/**
		 * <code>devicePlanAddressProblem(line, number)</code>: the reason of
		 * {@link DevicePlanning#addressVeto(TLObject, Object)} as text, <code>null</code> if the
		 * number is free.
		 */
		devicePlanAddressProblem(2, true),
		/** <code>devicePlanAddressText(line, number)</code>: {@link DevicePlanning#addressText(TLObject, Object)}. */
		devicePlanAddressText(2, true),
		/** <code>devicePlanLocations(obj)</code>: {@link DevicePlanning#locations(TLObject)}. */
		devicePlanLocations(1, true),
		/**
		 * <code>devicePlanCreate(line, variant, program, number, title, location)</code>:
		 * {@link DevicePlanning#create(TLObject, TLObject, TLObject, Object, String, TLObject)}.
		 */
		devicePlanCreate(6, false),
		/** <code>devicePlanDuplicateVeto(device)</code>: {@link DevicePlanning#duplicateVeto(TLObject)}. */
		devicePlanDuplicateVeto(1, true),
		/** <code>devicePlanDuplicate(device)</code>: {@link DevicePlanning#duplicate(TLObject)}. */
		devicePlanDuplicate(1, false);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link DevicePlanningFunctions}.
	 */
	protected DevicePlanningFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new DevicePlanningFunctions(getName(), _function, arguments);
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
		switch (_function) {
			case devicePlanVariants:
				return DevicePlanning.variants();
			case devicePlanVariantLabel:
				return DevicePlanning.variantLabel(object(arguments[0]));
			case devicePlanPrograms:
				return object(arguments[0]) == null ? List.of() : DevicePlanning.programs(object(arguments[0]));
			case devicePlanDefaultProgram:
				return object(arguments[0]) == null ? null : DevicePlanning.defaultProgram(object(arguments[0]));
			case devicePlanProgramLabel:
				return DevicePlanning.programLabel(object(arguments[0]));
			case devicePlanLine:
				return DevicePlanning.line(object(arguments[0]));
			case devicePlanNextFree: {
				Integer number = DevicePlanning.nextFree(object(arguments[0]));
				return number == null ? null : SearchExpression.toNumber(number.intValue());
			}
			case devicePlanAddressProblem:
				return text(DevicePlanning.addressVeto(object(arguments[0]), arguments[1]));
			case devicePlanAddressText:
				return DevicePlanning.addressText(object(arguments[0]), arguments[1]);
			case devicePlanLocations:
				return object(arguments[0]) == null ? List.of() : DevicePlanning.locations(object(arguments[0]));
			case devicePlanCreate:
				return DevicePlanning.create(object(arguments[0]), object(arguments[1]), object(arguments[2]),
					arguments[3], arguments[4] == null ? null : arguments[4].toString(), object(arguments[5]));
			case devicePlanDuplicateVeto:
				return DevicePlanning.duplicateVeto(object(arguments[0]));
			case devicePlanDuplicate:
				return DevicePlanning.duplicate(object(arguments[0]));
		}
		return null;
	}

	private static String text(ResKey key) {
		return key == null ? null : Resources.getInstance().getString(key);
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj && obj.tValid() ? obj : null;
	}

	/**
	 * Builder of a {@link DevicePlanningFunctions} function: the {@link Function} named as the
	 * function is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<DevicePlanningFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public DevicePlanningFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new DevicePlanningFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
