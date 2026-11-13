/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport.importer.format;

import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;

/**
 * Format of an XML Schema unsigned 32-bit integer (<code>xs:unsignedInt</code>) as an
 * {@link Integer} holding its 32 bits (haumacher/ehome #160).
 *
 * <p>
 * ETS writes the value of an enumeration entry as <code>xs:unsignedInt</code>; a manufacturer writes
 * a negative value in its 32-bit form: Theben's entry "-60%" of an enumeration of 8 bits has the
 * value <code>4294967236</code> (<code>0xFFFFFFC4</code>). The model keeps the value in an
 * {@link Integer}, so a value above 2<sup>31</sup>-1 reads as the negative number of the same 32 bits
 * (-60); {@link de.haumacher.ehome.model.operational.EnumerationValue#getUnsignedValue()} gives the
 * value as ETS writes it. A negative number in the text is read as it stands.
 * </p>
 */
public class UnsignedIntFormat extends AbstractConfigurationValueProvider<Integer> {

	private static final long MAX_UNSIGNED = 0xFFFFFFFFL;

	/**
	 * Creates a {@link UnsignedIntFormat}.
	 */
	public UnsignedIntFormat() {
		super(Integer.class);
	}

	@Override
	protected Integer getValueNonEmpty(String propertyName, CharSequence propertyValue) throws ConfigurationException {
		String text = propertyValue.toString().trim();
		long value;
		try {
			value = Long.parseLong(text);
		} catch (NumberFormatException ex) {
			throw invalid(propertyName, text);
		}
		if (value < Integer.MIN_VALUE || value > MAX_UNSIGNED) {
			throw invalid(propertyName, text);
		}
		return Integer.valueOf((int) value);
	}

	private static ConfigurationException invalid(String propertyName, String text) {
		return new ConfigurationException(I18NConstants.ERROR_INVALID_UNSIGNED_INT__VALUE.fill(text), propertyName,
			text);
	}

	@Override
	protected String getSpecificationNonNull(Integer configValue) {
		return Integer.toUnsignedString(configValue.intValue());
	}

}
