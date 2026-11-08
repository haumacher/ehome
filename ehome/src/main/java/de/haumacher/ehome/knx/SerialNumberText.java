/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.util.Base64;
import java.util.HexFormat;
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
 * TL-Script function <code>serialNumberText(stored)</code>: the serial number of a device as KNX
 * writes it (<code>000A:2A4A2711</code>, manufacturer and number in hex) from the form ETS stores
 * in a project and E-Home in <code>ehome.physical:Device#serialNumber</code> (Base64 of the six
 * bytes, haumacher/ehome #120). A value that is not the Base64 of six bytes is returned as it is.
 */
public class SerialNumberText extends GenericMethod {

	/** The number of bytes of a KNX serial number. */
	private static final int LENGTH = 6;

	/**
	 * Creates a {@link SerialNumberText}.
	 */
	protected SerialNumberText(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	/**
	 * The serial number in hex with the manufacturer separated, see {@link SerialNumberText}.
	 *
	 * @return The text, <code>null</code> for no serial number.
	 */
	public static String text(String stored) {
		if (stored == null || stored.isBlank()) {
			return null;
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(stored.trim());
		} catch (IllegalArgumentException ex) {
			return stored;
		}
		if (bytes.length != LENGTH) {
			return stored;
		}
		String hex = HexFormat.of().withUpperCase().formatHex(bytes);
		return hex.substring(0, 4) + ":" + hex.substring(4);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new SerialNumberText(getName(), arguments);
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
		Object stored = arguments[0];
		return stored == null ? null : text(asString(stored));
	}

	/**
	 * Builder for {@link SerialNumberText}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<SerialNumberText> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public SerialNumberText build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkSingleArg(expr, args);
			return new SerialNumberText(getConfig().getName(), args);
		}
	}

}
