/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

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

import de.haumacher.ehome.model.operational.Button;

/**
 * The TL-Script functions of the buttons of the parameter dialog (haumacher/ehome #158), see
 * {@link DeviceButtons}.
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class ButtonFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>buttonProblem(button)</code>: {@link DeviceButtons#problem(Button)}. */
		buttonProblem(1, true),
		/** <code>deviceButtonVeto(device, button)</code>: {@link DeviceButtons#veto(TLObject, Button)}. */
		deviceButtonVeto(2, true),
		/**
		 * <code>deviceButtonPress(device, button)</code>: {@link DeviceButtons#press(TLObject, Button)},
		 * changes the device.
		 */
		deviceButtonPress(2, false);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link ButtonFunctions}.
	 */
	protected ButtonFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ButtonFunctions(getName(), _function, arguments);
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
			case buttonProblem:
				return arguments[0] instanceof Button button ? DeviceButtons.problem(button) : null;
			case deviceButtonVeto:
				return arguments[0] instanceof TLObject device && arguments[1] instanceof Button button
					? DeviceButtons.veto(device, button) : null;
			case deviceButtonPress:
				if (arguments[0] instanceof TLObject device && arguments[1] instanceof Button button) {
					DeviceButtons.press(device, button);
				}
				return null;
		}
		return null;
	}

	/**
	 * Builder of a {@link ButtonFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ButtonFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ButtonFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new ButtonFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
