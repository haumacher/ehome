/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.Locale;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Imports an XML attribute into an internationalized attribute (<code>tl.model.i18n:I18NString</code>)
 * of the current object, as the text of the element's language (see {@link DefaultLanguage}).
 *
 * <p>
 * Without a known language, the text is filed under the application's fallback language. Texts of
 * other languages come from the translations of the file (see {@link TranslationsImport}).
 * </p>
 */
public class I18NPropertyImport<C extends I18NPropertyImport.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration of {@link I18NPropertyImport}.
	 */
	@TagName("i18n-property")
	public interface Config<I extends I18NPropertyImport<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The model attribute to fill.
		 */
		@Name("name")
		@Mandatory
		String getName();

		/**
		 * The XML attribute holding the text.
		 */
		@Name("xml-attribute")
		@Mandatory
		String getXmlAttribute();

	}

	/**
	 * Creates a {@link I18NPropertyImport} from configuration.
	 */
	@CalledByReflection
	public I18NPropertyImport(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String text = in.getAttributeValue(null, getConfig().getXmlAttribute());
		Locale locale = (Locale) context.getVarOrNull(I18NTexts.LANGUAGE_VAR);
		if (locale == null) {
			locale = ResourcesModule.getInstance().getFallbackLocale();
		}
		TLObject self = (TLObject) context.getVar(ImportContext.THIS_VAR);
		I18NTexts.set(self, getConfig().getName(), locale, text, true);
		return null;
	}

}
