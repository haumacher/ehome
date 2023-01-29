/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.binding;

import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.Name;

import de.haumacher.ehome.importer.I18NConstants;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
@Format(PartRef.ConfigFormat.class)
public interface PartRef extends ConfigurationItem {

	String PART = "part";

	String TYPE = "type";

	public class ConfigFormat extends AbstractConfigurationValueProvider<PartRef> {

		/**
		 * Creates a {@link ConfigFormat}.
		 */
		public ConfigFormat() {
			super(PartRef.class);
		}

		@Override
		protected PartRef getValueNonEmpty(String propertyName, CharSequence propertyValue)
				throws ConfigurationException {
			PartRef result = TypedConfiguration.newConfigItem(PartRef.class);
			String value = propertyValue.toString();
			int sepIndex = value.indexOf('#');
			if (sepIndex < 0) {
				throw new ConfigurationException(
					I18NConstants.ERROR_INVALID_PART_REF__VALUE.fill(value), propertyName,
					propertyValue);
			}

			result.update(result.descriptor().getProperty(TYPE), value.substring(0, sepIndex));
			result.update(result.descriptor().getProperty(PART), value.substring(sepIndex + 1));
			
			return result;
		}

		@Override
		protected String getSpecificationNonNull(PartRef configValue) {
			return configValue.getType() + "#" + configValue.getPart();
		}

	}

	@Name(TYPE)
	String getType();

	@Name(PART)
	String getPart();

}
