/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * Imports an element with the language its texts are written in: the element's
 * <code>DefaultLanguage</code>, made available to {@link I18NPropertyImport} in the variable
 * {@link I18NTexts#LANGUAGE_VAR}.
 */
public class DefaultLanguage<C extends DefaultLanguage.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration of {@link DefaultLanguage}.
	 */
	@TagName("default-language")
	public interface Config<I extends DefaultLanguage<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute naming the language.
		 */
		@Name("xml-attribute")
		@StringDefault("DefaultLanguage")
		String getXmlAttribute();

		/**
		 * The handlers importing the element.
		 */
		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getContents();

	}

	private final List<Handler> _contents;

	/**
	 * Creates a {@link DefaultLanguage} from configuration.
	 */
	@CalledByReflection
	public DefaultLanguage(InstantiationContext context, C config) {
		super(context, config);
		_contents = TypedConfiguration.getInstanceList(context, config.getContents());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String language = in.getAttributeValue(null, getConfig().getXmlAttribute());
		if (language == null || language.isEmpty()) {
			return importContents(context, in);
		}
		return context.withVar(I18NTexts.LANGUAGE_VAR, I18NTexts.locale(language), in, this::importContents);
	}

	private Object importContents(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object result = null;
		for (Handler handler : _contents) {
			result = handler.importXml(context, in);
		}
		return result;
	}

}
