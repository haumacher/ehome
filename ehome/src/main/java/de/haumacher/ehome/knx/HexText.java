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
 * TL-Script function <code>hexText(number, digits)</code>: a number in hex with at least the given
 * number of digits, upper case, as KNX writes the numbers a device reports (the application number
 * <code>000C</code>, its version <code>21</code>, a mask version <code>0705</code>; haumacher/ehome
 * #207). Nothing for nothing.
 */
public class HexText extends GenericMethod {

	/**
	 * Creates a {@link HexText}.
	 */
	protected HexText(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	/**
	 * The given number in hex with at least the given number of digits.
	 *
	 * @return The text, <code>null</code> for no number.
	 */
	public static String text(Number value, int digits) {
		if (value == null) {
			return null;
		}
		String hex = Long.toHexString(value.longValue()).toUpperCase();
		return hex.length() >= digits ? hex : "0".repeat(digits - hex.length()) + hex;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new HexText(getName(), arguments);
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
	protected Object eval(Object[] arguments, EvalContext definitions) {
		Object value = arguments[0];
		if (!(value instanceof Number number)) {
			return null;
		}
		return text(number, asInt(arguments[1]));
	}

	/**
	 * Builder for {@link HexText}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<HexText> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public HexText build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, 2, 2);
			return new HexText(getConfig().getName(), args);
		}
	}

}
