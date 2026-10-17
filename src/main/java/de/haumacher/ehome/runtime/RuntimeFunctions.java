/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

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
 * The TL-Script functions of the controllers and the deployment of a project (haumacher/ehome #39).
 *
 * <p>
 * Each function is registered in <code>ehome.runtime.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class RuntimeFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>controllerTokenHint(controller)</code>: whether the controller has a token, without
		 * the token itself.
		 */
		controllerTokenHint,
		/** <code>controllerAddress(controller)</code>: <code>host:port</code>. */
		controllerAddress,
		/**
		 * <code>deploymentVeto(project)</code>: why the project cannot be deployed now, as text;
		 * <code>null</code> if it can ({@link Deployer#veto(TLObject)}).
		 */
		deploymentVeto,
		/**
		 * <code>nextImageRevision(project)</code>: the revision the next deployment carries.
		 */
		nextImageRevision;
	}

	private final Function _function;

	/**
	 * Creates a {@link RuntimeFunctions}.
	 */
	protected RuntimeFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new RuntimeFunctions(getName(), _function, arguments);
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
		if (!(arguments[0] instanceof TLObject obj) || !obj.tValid()) {
			return null;
		}
		Resources resources = Resources.getInstance();
		switch (_function) {
			case controllerTokenHint: {
				Object token = obj.tValueByName("token");
				return resources.getString(token == null || token.toString().isBlank() ? I18NConstants.TOKEN_NOT_SET
					: I18NConstants.TOKEN_SET);
			}
			case controllerAddress:
				return Deployer.client(obj).address();
			case deploymentVeto: {
				ResKey veto = Deployer.veto(obj);
				return veto == null ? null : resources.getString(veto);
			}
			case nextImageRevision:
				return SearchExpression.toNumber(Deployer.nextRevision(obj));
		}
		return null;
	}

	/**
	 * Builder of a {@link RuntimeFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<RuntimeFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public RuntimeFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, 1, 1);
			return new RuntimeFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
