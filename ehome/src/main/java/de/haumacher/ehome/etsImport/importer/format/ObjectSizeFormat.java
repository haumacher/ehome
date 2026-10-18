/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport.importer.format;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.element.meta.complex.ColorValueProvider;

/**
 * {@link ColorValueProvider} parsing e.g. "3 Bit" or "2 Byte" into the amount of bits.
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class ObjectSizeFormat extends AbstractConfigurationValueProvider<Integer> {

	private static final String BIT_UNIT = "Bit";

	private static final String BYTE_UNIT = "Byte";

	private static final Pattern PATTERN =
		Pattern.compile("(\\d+)\\s+(?:(" + BIT_UNIT + ")|(" + BYTE_UNIT + "))(?:s?)");

	/**
	 * Creates a {@link ObjectSizeFormat}.
	 */
	public ObjectSizeFormat() {
		super(Integer.class);
	}

	@Override
	protected Integer getValueNonEmpty(String propertyName, CharSequence propertyValue) throws ConfigurationException {
		Matcher matcher = PATTERN.matcher(propertyValue);
		if (!matcher.matches()) {
			throw new ConfigurationException(
				I18NConstants.INVALID_OBJECT_SIZE_FORMAT__FOUND_EXPECTED.fill(propertyValue.toString(),
					PATTERN.toString()),
				propertyName, propertyValue);
		}
		
		int amount = Integer.parseInt(matcher.group(1));
		int bits;
		if (matcher.group(3) != null) {
			bits = amount * 8;
		} else {
			bits = amount;
		}
		return Integer.valueOf(bits);
	}

	@Override
	protected String getSpecificationNonNull(Integer configValue) {
		return text(configValue.intValue());
	}

	/**
	 * The size of a communication object with its unit, as ETS writes it: "1 Bit", "4 Bit", "1 Byte",
	 * "2 Bytes"; <code>null</code> for no size.
	 *
	 * @param bits
	 *        The size in bits.
	 */
	public static String text(Integer bits) {
		return bits == null ? null : text(bits.intValue());
	}

	private static String text(int bits) {
		int bytes = bits / 8;
		if (bytes > 0 && bits % 8 == 0) {
			// As the schema enumerates them: "1 Byte", "2 Bytes".
			return bytes + " " + BYTE_UNIT + (bytes > 1 ? "s" : "");
		} else {
			return bits + " " + BIT_UNIT;
		}
	}

}
