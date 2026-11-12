/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.settings;

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
 * TL-Script function <code>localNetworkVeto()</code>: why this installation does not reach local
 * networks (haumacher/ehome #150, {@link OperatorSettings#localNetworkVeto()}), <code>null</code> if
 * it does.
 *
 * <p>
 * The views do not offer what reaches into the local networks of the server while it is not
 * <code>null</code> (the gateway search, the entry of a gateway address, the controller search, a
 * controller by host and token), and show it as the reason where a refused command stays visible.
 * </p>
 */
public class LocalNetworkVeto extends GenericMethod {

	/**
	 * Creates a {@link LocalNetworkVeto}.
	 */
	protected LocalNetworkVeto(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new LocalNetworkVeto(getName(), arguments);
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
		// The configuration is read at every evaluation.
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		return OperatorSettings.localNetworkVeto();
	}

	/**
	 * Builder for {@link LocalNetworkVeto}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<LocalNetworkVeto> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public LocalNetworkVeto build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkNoArguments(expr, args);
			return new LocalNetworkVeto(getConfig().getName(), args);
		}
	}

}
