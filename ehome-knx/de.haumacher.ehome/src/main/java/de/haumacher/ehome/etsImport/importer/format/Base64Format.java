/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.etsImport.importer.format;

import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfigurationValueProvider;
import com.top_logic.basic.io.StreamUtilities;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;

/**
 * {@link ConfigurationValueProvider} reading {@link BinaryData} from {@link Base64} encoded
 * strings.
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class Base64Format extends AbstractConfigurationValueProvider<BinaryData> {

	/**
	 * Creates a {@link Base64Format}.
	 */
	public Base64Format() {
		super(BinaryData.class);
	}

	@Override
	protected BinaryData getValueNonEmpty(String propertyName, CharSequence propertyValue)
			throws ConfigurationException {
		byte[] data = Base64.getDecoder().decode(propertyValue.toString());
		return BinaryDataFactory.createBinaryData(data);
	}
	
	@Override
	protected String getSpecificationNonNull(BinaryData configValue) {
		byte[] data = new byte[(int) configValue.getSize()];
		try (InputStream in = configValue.getStream()) {
			int cnt = StreamUtilities.readFully(in, data);
			assert cnt == data.length;
			return Base64.getEncoder().encodeToString(data);
		} catch (IOException ex) {
			throw new IOError(ex);
		}
	}

}
