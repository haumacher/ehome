/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport.importer.format;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.util.TLModelUtil;

/**
 * Format reading a list of classifiers of an enumeration from their names separated by spaces (an
 * XML Schema list, e.g. <code>remote local1</code>), for a multiple reference to an enumeration.
 */
public class TLEnumerationSetFormat extends AbstractConfigurationValueProvider<List<TLClassifier>>
		implements ConfiguredInstance<TLEnumerationSetFormat.Config<?>> {

	/**
	 * Configuration options for {@link TLEnumerationSetFormat}.
	 */
	public interface Config<I extends TLEnumerationSetFormat> extends PolymorphicConfiguration<I> {
		/**
		 * The qualified name of the enumeration.
		 */
		@Mandatory
		String getType();
	}

	private final Config<?> _config;

	private final TLEnumeration _type;

	/**
	 * Creates a {@link TLEnumerationSetFormat} from configuration.
	 */
	@CalledByReflection
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public TLEnumerationSetFormat(InstantiationContext context, Config<?> config) {
		super((Class) List.class);
		_config = config;
		_type = (TLEnumeration) TLModelUtil.findType(config.getType());
	}

	@Override
	public Config<?> getConfig() {
		return _config;
	}

	@Override
	public List<TLClassifier> defaultValue() {
		return new ArrayList<>();
	}

	@Override
	protected List<TLClassifier> getValueNonEmpty(String propertyName, CharSequence propertyValue)
			throws ConfigurationException {
		List<TLClassifier> result = new ArrayList<>();
		for (String name : propertyValue.toString().trim().split("\\s+")) {
			TLClassifier classifier = _type.getClassifier(name);
			if (classifier == null) {
				throw new ConfigurationException("No such classifier '" + name + "' in '" + _type + "'.");
			}
			if (!result.contains(classifier)) {
				result.add(classifier);
			}
		}
		return result;
	}

	@Override
	protected String getSpecificationNonNull(List<TLClassifier> configValue) {
		return configValue.stream().map(TLClassifier::getName).collect(Collectors.joining(" "));
	}

}
