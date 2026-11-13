/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.intent;

import java.util.List;
import java.util.Map;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;

import de.haumacher.ehome.template.Presets;

/**
 * The TL-Script functions of the configuration by intent (haumacher/ehome #147), see
 * {@link Controls} and {@link Derivation}: the options of the functions view and of its dialogs, the
 * problems of functions and controls (derived attributes of <code>ehome.runtime</code>), the creation
 * of a control and the preview and application of the derivation.
 *
 * <p>
 * Each function is registered in <code>ehome.intent.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class IntentFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>intentFunctionPresets(function)</code>: {@link Controls#functionPresets(TLObject)}. */
		intentFunctionPresets(1, true),
		/** <code>intentSourcePresets(device, channel)</code>: {@link Controls#sourcePresets(TLObject, String)}. */
		intentSourcePresets(2, true),
		/** <code>intentSourceDevices(function)</code>: {@link Controls#sourceDevices(TLObject)}. */
		intentSourceDevices(1, true),
		/**
		 * <code>intentSourceChannels(device)</code>: the channels of the device that matter now
		 * ({@link Presets#activeChannels(TLObject)}).
		 */
		intentSourceChannels(1, true),
		/**
		 * <code>intentControlCreate(function, device, channel, preset, operation)</code>:
		 * {@link Controls#create(TLObject, TLObject, String, TLObject, TLObject)}.
		 */
		intentControlCreate(5, false),
		/** <code>intentControlProblem(control)</code>: {@link Controls#problem(TLObject)}. */
		intentControlProblem(1, true),
		/** <code>intentFunctionProblem(function)</code>: {@link Controls#problem(TLObject)}. */
		intentFunctionProblem(1, true),
		/** <code>intentPreview(project)</code>: {@link Controls#preview(TLObject)}. */
		intentPreview(1, true),
		/** <code>intentApply(request)</code>: {@link Controls#apply(Map)}. */
		intentApply(1, false);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link IntentFunctions}.
	 */
	protected IntentFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new IntentFunctions(getName(), _function, arguments);
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
			case intentFunctionPresets:
				return Controls.functionPresets(object(arguments[0]));
			case intentSourcePresets:
				return Controls.sourcePresets(object(arguments[0]), text(arguments[1]));
			case intentSourceDevices:
				return Controls.sourceDevices(object(arguments[0]));
			case intentSourceChannels:
				return object(arguments[0]) == null ? List.of() : Presets.activeChannels(object(arguments[0]));
			case intentControlCreate:
				return Controls.create(object(arguments[0]), object(arguments[1]), text(arguments[2]),
					object(arguments[3]), object(arguments[4]));
			case intentControlProblem:
			case intentFunctionProblem:
				return Controls.problem(object(arguments[0]));
			case intentPreview:
				return object(arguments[0]) == null ? null : Controls.preview(object(arguments[0]));
			case intentApply:
				return arguments[0] instanceof Map<?, ?> request ? Controls.apply(request) : null;
		}
		return null;
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj && obj.tValid() ? obj : null;
	}

	/**
	 * Builder of a {@link IntentFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<IntentFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public IntentFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new IntentFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
