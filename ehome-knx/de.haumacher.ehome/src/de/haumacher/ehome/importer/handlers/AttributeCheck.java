/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.format.RegExpValueProvider;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * {@link Importer} that checks that a certain XML attribute has a given value.
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class AttributeCheck<C extends AttributeCheck.Config<?>> extends DispatchingImporter<C> {

	/**
	 * Configuration options for {@link AttributeCheck}.
	 */
	@TagName("attribute-check")
	public interface Config<I extends AttributeCheck<?>> extends DispatchingImporter.Config<I> {

		String getXmlAttribute();

		@Format(RegExpValueProvider.class)
		Pattern getValue();

	}

	/**
	 * Creates a {@link AttributeCheck} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public AttributeCheck(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String rawValue = in.getAttributeValue(null, getConfig().getXmlAttribute());
		if (!getConfig().getValue().matcher(rawValue).matches()) {
			context.error(in.getLocation(),
				I18NConstants.ERROR_UNEXPECTED_ATTRIBUTE_VALUE__GIVEN_EXPECTED.fill(rawValue,
					getConfig().getValue().toString()));
		}
		return null;
	}

}
