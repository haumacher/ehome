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
 * TL-Script function <code>encodedParameterValue(holder)</code>: the value of a parameter, parameter
 * reference or parameter setting (<code>ehome.operational:ParameterValue</code>) in the notation ETS
 * writes it in, see {@link ParameterValues#encode(TLObject)}.
 *
 * <p>
 * The result is <code>null</code> if the holder holds no value.
 * </p>
 */
public class EncodedParameterValue extends GenericMethod {

	/**
	 * Creates a {@link EncodedParameterValue}.
	 */
	protected EncodedParameterValue(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new EncodedParameterValue(getName(), arguments);
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (arguments[0] instanceof TLObject holder) {
			return ParameterValues.encode(holder);
		}
		return null;
	}

	/**
	 * Builder for {@link EncodedParameterValue}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<EncodedParameterValue> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public EncodedParameterValue build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkSingleArg(expr, args);
			return new EncodedParameterValue(getConfig().getName(), args);
		}
	}

}
