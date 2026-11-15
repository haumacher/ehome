/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.security;

import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;

/**
 * The TL-Script functions of the E-Home administrators (haumacher/ehome #165, {@link Administration}),
 * registered in <code>ehome.security.config.xml</code> under the names of the {@link Function}
 * constants. <code>ehomeGroupAdd</code> and <code>ehomeGroupRemove</code> write: a command calling them
 * runs them in a transaction and checks the operation <code>EHomeAdministration</code> on the
 * security root.
 */
public class AdministrationFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>ehomeGroup(name)</code>: the group of E-Home with the given name
		 * (<code>ehome.installers</code> or <code>ehome.administrators</code>), <code>null</code> for any
		 * other name.
		 */
		ehomeGroup(1, true),
		/**
		 * <code>ehomeGroupAdd(group, account)</code>: adds the account (or the account with the given
		 * mail address or user name) to the group of E-Home; the account.
		 */
		ehomeGroupAdd(2, false),
		/**
		 * <code>ehomeGroupRemove(group, account)</code>: removes the account from the group of E-Home.
		 */
		ehomeGroupRemove(2, false),
		/**
		 * <code>projectsNamed(name)</code>: the projects with exactly this name, found by a query of the
		 * database.
		 */
		projectsNamed(1, true);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}

		/**
		 * The number of arguments of the function.
		 */
		public int arguments() {
			return _arguments;
		}

		/**
		 * Whether the function only reads.
		 */
		public boolean sideEffectFree() {
			return _sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link AdministrationFunctions}.
	 */
	protected AdministrationFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new AdministrationFunctions(getName(), _function, arguments);
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
		return _function.sideEffectFree();
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		switch (_function) {
			case ehomeGroup:
				return Administration.group(arguments[0] instanceof String name ? name : null);
			case ehomeGroupAdd:
				return Administration.addMember(arguments[0], arguments[1]);
			case ehomeGroupRemove:
				Administration.removeMember(arguments[0], arguments[1]);
				return null;
			case projectsNamed:
				return Administration.projectsNamed(arguments[0] instanceof String name ? name : null);
			default:
				return null;
		}
	}

	/**
	 * Builder of a {@link AdministrationFunctions} function: the {@link Function} named as the
	 * function is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<AdministrationFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public AdministrationFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function.arguments(), _function.arguments());
			return new AdministrationFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
