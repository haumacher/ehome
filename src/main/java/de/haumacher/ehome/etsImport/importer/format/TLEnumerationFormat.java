/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.etsImport.importer.format;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.util.TLModelUtil;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class TLEnumerationFormat extends AbstractConfigurationValueProvider<TLClassifier>
		implements ConfiguredInstance<TLEnumerationFormat.Config<?>> {

	/**
	 * Configuration options for {@link TLEnumerationFormat}.
	 */
	public interface Config<I extends TLEnumerationFormat> extends PolymorphicConfiguration<I> {
		@Mandatory
		String getType();

		@Nullable
		String getDefaultValue();
	}

	private TLEnumeration _type;
	private TLClassifier _defaultValue;

	private Config<?> _config;

	/**
	 * Creates a {@link TLEnumerationFormat} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public TLEnumerationFormat(InstantiationContext context, Config<?> config) throws ConfigurationException {
		super(TLClassifier.class);
		
		_config = config;

		_type = (TLEnumeration) TLModelUtil.findType(config.getType());
		String defaultSpec = config.getDefaultValue();
		if (defaultSpec == null) {
			_defaultValue = _type.getClassifiers().get(0);
		} else {
			TLClassifier defaultValue = _type.getClassifier(defaultSpec);
			if (defaultValue == null) {
				throw new ConfigurationException("No such classifier '" + defaultSpec + "' in '" + _type + "'.");
			}
			_defaultValue = defaultValue;
		}
	}

	@Override
	public Config<?> getConfig() {
		return _config;
	}

	@Override
	public TLClassifier defaultValue() {
		return _defaultValue;
	}

	@Override
	protected TLClassifier getValueNonEmpty(String propertyName, CharSequence propertyValue)
			throws ConfigurationException {
		TLClassifier result = _type.getClassifier(propertyValue.toString());
		if (result == null) {
			throw new ConfigurationException("No such classifier '" + propertyValue + "' in '" + _type + "'.");
		}

		return result;
	}

	@Override
	protected String getSpecificationNonNull(TLClassifier configValue) {
		return configValue.getName();
	}

}
