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
 * TL-Script function <code>groupRangePrefix(start, end)</code>: the prefix ETS labels a group range
 * with, e.g. <code>8</code> for a main group or <code>8/0</code> for a middle group, in the
 * notation of the given addresses (see {@link GA#rangePrefix(GA)}).
 *
 * <p>
 * The result is <code>null</code> if an address is missing or the range is no main or middle
 * group.
 * </p>
 */
public class GroupRangePrefix extends GenericMethod {

	/**
	 * Creates a {@link GroupRangePrefix}.
	 */
	protected GroupRangePrefix(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new GroupRangePrefix(getName(), arguments);
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (arguments[0] instanceof GA start && arguments[1] instanceof GA end) {
			return start.rangePrefix(end);
		}
		return null;
	}

	/**
	 * Builder for {@link GroupRangePrefix}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<GroupRangePrefix> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public GroupRangePrefix build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkTwoArgs(expr, args);
			return new GroupRangePrefix(getConfig().getName(), args);
		}
	}

}
