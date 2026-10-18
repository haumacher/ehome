/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

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
 * TL-Script function <code>matchesPattern(text, pattern)</code>: whether the text matches the regular
 * expression as a whole, see {@link ParameterValues#matchesPattern(String, String)}.
 *
 * <p>
 * A pattern that Java cannot compile (ETS writes .NET expressions) matches every text, so that it
 * never makes a check fail.
 * </p>
 */
public class MatchesPattern extends GenericMethod {

	/**
	 * Creates a {@link MatchesPattern}.
	 */
	protected MatchesPattern(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new MatchesPattern(getName(), arguments);
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		return Boolean.valueOf(ParameterValues.matchesPattern(asString(arguments[0]), asString(arguments[1])));
	}

	/**
	 * Builder for {@link MatchesPattern}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<MatchesPattern> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public MatchesPattern build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkTwoArgs(expr, args);
			return new MatchesPattern(getConfig().getName(), args);
		}
	}

}
