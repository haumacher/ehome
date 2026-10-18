/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport.importer.format;

import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;

import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.io.StreamUtilities;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;

/**
 * Format reading {@link BinaryData} from the hexadecimal notation of XML Schema
 * (<code>xs:hexBinary</code>, e.g. <code>0000FF</code>), as ETS writes the inline data of a load
 * procedure step.
 */
public class HexBinaryFormat extends AbstractConfigurationValueProvider<BinaryData> {

	/**
	 * Creates a {@link HexBinaryFormat}.
	 */
	public HexBinaryFormat() {
		super(BinaryData.class);
	}

	@Override
	protected BinaryData getValueNonEmpty(String propertyName, CharSequence propertyValue)
			throws ConfigurationException {
		try {
			return BinaryDataFactory.createBinaryData(HexFormat.of().parseHex(propertyValue.toString().trim()));
		} catch (IllegalArgumentException ex) {
			throw new ConfigurationException(
				I18NConstants.ERROR_INVALID_HEX_BINARY__VALUE.fill(propertyValue.toString()),
				propertyName, propertyValue);
		}
	}

	@Override
	protected String getSpecificationNonNull(BinaryData configValue) {
		byte[] data = new byte[(int) configValue.getSize()];
		try (InputStream in = configValue.getStream()) {
			StreamUtilities.readFully(in, data);
			return HexFormat.of().withUpperCase().formatHex(data);
		} catch (IOException ex) {
			throw new IOError(ex);
		}
	}

}
