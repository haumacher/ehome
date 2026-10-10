/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport.importer.format;

import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;

/**
 * Format of an XML Schema boolean (<code>xs:boolean</code>): <code>true</code>, <code>false</code>,
 * <code>1</code> or <code>0</code>, as ETS writes its flags.
 */
public class XmlBooleanFormat extends AbstractConfigurationValueProvider<Boolean> {

	/**
	 * Creates a {@link XmlBooleanFormat}.
	 */
	public XmlBooleanFormat() {
		super(Boolean.class);
	}

	@Override
	protected Boolean getValueNonEmpty(String propertyName, CharSequence propertyValue) throws ConfigurationException {
		switch (propertyValue.toString().trim()) {
			case "true":
			case "1":
				return Boolean.TRUE;
			case "false":
			case "0":
				return Boolean.FALSE;
			default:
				throw new ConfigurationException(
					I18NConstants.ERROR_INVALID_BOOLEAN__VALUE.fill(propertyValue.toString()),
					propertyName, propertyValue);
		}
	}

	@Override
	protected String getSpecificationNonNull(Boolean configValue) {
		return configValue.toString();
	}

}
