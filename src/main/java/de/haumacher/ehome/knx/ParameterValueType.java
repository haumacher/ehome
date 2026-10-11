/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

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
 * TL-Script function <code>parameterValueType(holder)</code>: the type of the value of a parameter,
 * parameter reference or parameter setting (<code>ehome.operational:ParameterValue</code>), see
 * {@link ParameterValues#valueType(TLObject)}.
 *
 * <p>
 * The one navigation from a holder to its type, used by the model's <code>valueType</code> and by
 * the options, constraints and visibility of the typed attributes. The result is <code>null</code>
 * if the holder has no type (yet).
 * </p>
 */
public class ParameterValueType extends GenericMethod {

	/**
	 * Creates a {@link ParameterValueType}.
	 */
	protected ParameterValueType(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ParameterValueType(getName(), arguments);
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (arguments[0] instanceof TLObject holder) {
			return ParameterValues.valueType(holder);
		}
		return null;
	}

	/**
	 * Builder for {@link ParameterValueType}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ParameterValueType> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public ParameterValueType build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkSingleArg(expr, args);
			return new ParameterValueType(getConfig().getName(), args);
		}
	}

}
