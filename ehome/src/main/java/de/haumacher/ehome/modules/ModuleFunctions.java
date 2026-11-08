/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.modules;

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

/**
 * The TL-Script functions giving the parts of dynamic modules as they are in a device's module
 * instance (haumacher/ehome #119, see {@link ModuleParts}).
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class ModuleFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>settingParameter(setting)</code>: {@link ModuleParts#parameter(TLObject)}. */
		settingParameter,
		/** <code>connectorPort(connector)</code>: {@link ModuleParts#port(TLObject)}. */
		connectorPort;
	}

	private final Function _function;

	/**
	 * Creates a {@link ModuleFunctions}.
	 */
	protected ModuleFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ModuleFunctions(getName(), _function, arguments);
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
		return true;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (!(arguments[0] instanceof TLObject holder)) {
			return null;
		}
		switch (_function) {
			case settingParameter:
				return ModuleParts.parameter(holder);
			case connectorPort:
				return ModuleParts.port(holder);
		}
		return null;
	}

	/**
	 * Builder of a {@link ModuleFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ModuleFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ModuleFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkSingleArg(expr, args);
			return new ModuleFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
