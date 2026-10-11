/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport.importer.format;

import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;

/**
 * Reads the number of decimal places from an ETS display format of floating point numbers
 * (<code>knx:FloatFormat_t</code>, e.g. <code>#,##0.00</code> or <code>0.0E+00</code>): the number of
 * digits after the decimal point.
 *
 * <p>
 * Grouping and exponent of the format are not read.
 * </p>
 */
public class DecimalsFormat extends AbstractConfigurationValueProvider<Integer> {

	/**
	 * Creates a {@link DecimalsFormat}.
	 */
	public DecimalsFormat() {
		super(Integer.class);
	}

	@Override
	protected Integer getValueNonEmpty(String propertyName, CharSequence propertyValue) throws ConfigurationException {
		String format = propertyValue.toString().trim();
		int point = format.indexOf('.');
		if (point < 0) {
			return Integer.valueOf(0);
		}
		int decimals = 0;
		for (int n = point + 1; n < format.length() && format.charAt(n) == '0'; n++) {
			decimals++;
		}
		return Integer.valueOf(decimals);
	}

	@Override
	protected String getSpecificationNonNull(Integer configValue) {
		int decimals = configValue.intValue();
		return decimals == 0 ? "0" : "0." + "0".repeat(decimals);
	}

}
