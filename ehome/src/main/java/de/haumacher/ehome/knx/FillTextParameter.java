/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;

/**
 * TL-Script function <code>fillTextParameter(text, value)</code>: the text of a communication object
 * with its placeholder <code>{{0}}</code> filled with the value of its text parameter (ETS:
 * <code>ComObjectRef@TextParameterRefId</code>), see {@link #fill(String, Object)}.
 */
public class FillTextParameter extends GenericMethod {

	/**
	 * The placeholder of a text parameter: <code>{{0}}</code>, or <code>{{0:default}}</code> with the
	 * text for an empty value.
	 */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{0(?::([^{}]*))?\\}\\}");

	/**
	 * Separators left at the end of a text whose placeholder was left out.
	 */
	private static final Pattern TRAILING_SEPARATOR = Pattern.compile("[\\s:;,\\-–]+$");

	/**
	 * Creates a {@link FillTextParameter}.
	 */
	protected FillTextParameter(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	/**
	 * The given text with every placeholder <code>{{0}}</code> replaced by the given value.
	 *
	 * <p>
	 * With an empty value, a placeholder <code>{{0:default}}</code> gives its default text and
	 * <code>{{0}}</code> is left out, with the blanks and separators (<code>:</code>, <code>-</code>, ...)
	 * it leaves at the end of the text.
	 * </p>
	 *
	 * @return The filled text; <code>null</code> for no text.
	 */
	public static String fill(String text, Object value) {
		if (text == null || !text.contains("{{")) {
			return text;
		}
		String filled = value == null ? "" : asText(value);
		Matcher matcher = PLACEHOLDER.matcher(text);
		StringBuilder result = new StringBuilder();
		boolean found = false;
		boolean leftOut = false;
		while (matcher.find()) {
			found = true;
			String replacement = filled.isEmpty() ? (matcher.group(1) == null ? "" : matcher.group(1)) : filled;
			leftOut |= replacement.isEmpty();
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		if (!found) {
			return text;
		}
		String filledText = result.toString().strip();
		// "Kanal B: {{0}}" without a value is "Kanal B", not "Kanal B:".
		return leftOut ? TRAILING_SEPARATOR.matcher(filledText).replaceFirst("") : filledText;
	}

	private static String asText(Object value) {
		if (value instanceof Double number && number.doubleValue() == Math.rint(number.doubleValue())
			&& !number.isInfinite()) {
			// A number is a double in TL-Script.
			return Long.toString(number.longValue());
		}
		return value.toString();
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new FillTextParameter(getName(), arguments);
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return argumentTypes.get(0);
	}

	@Override
	public boolean isSideEffectFree() {
		return true;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		Object text = arguments[0];
		return text == null ? null : fill(asString(text), arguments[1]);
	}

	/**
	 * Builder for {@link FillTextParameter}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<FillTextParameter> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public FillTextParameter build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkTwoArgs(expr, args);
			return new FillTextParameter(getConfig().getName(), args);
		}
	}

}
