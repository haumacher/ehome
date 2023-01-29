/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfigurationValueProvider;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.StringValueProvider;
import com.top_logic.basic.config.annotation.InstanceFormat;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.InstanceDefault;
import com.top_logic.basic.config.annotation.defaults.StringDefault;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class PropertyImport<C extends PropertyImport.Config<?>> extends DispatchingImporter<C> {

	/**
	 * Configuration options for {@link PropertyImport}.
	 */
	@TagName("property")
	public interface Config<I extends PropertyImport<?>> extends DispatchingImporter.Config<I> {

		@StringDefault(ImportContext.THIS_VAR)
		@Nullable
		String getTargetVar();

		String getName();

		String getXmlAttribute();

		@InstanceFormat
		@InstanceDefault(StringValueProvider.class)
		ConfigurationValueProvider<?> getFormat();

	}

	/**
	 * Creates a {@link PropertyImport} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public PropertyImport(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		try {
			String rawValue = in.getAttributeValue(null, getConfig().getXmlAttribute());
			if (rawValue != null) {
				Object applicationValue = getConfig().getFormat().getValue(getConfig().getXmlAttribute(), rawValue);
				context.setProperty(context.getVar(getConfig().getTargetVar()), getConfig().getName(),
					applicationValue);
			}
		} catch (ConfigurationException ex) {
			context.error(in.getLocation(), ex.getErrorKey());
		}
		return null;
	}

}
