/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.List;
import java.util.Locale;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.EntryTag;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Imports the <code>Languages</code> section of an ETS file: the texts of the file's objects in
 * further languages.
 *
 * <pre>
 * &lt;Language Identifier="de-DE"&gt;
 *   &lt;TranslationUnit RefId="..."&gt;
 *     &lt;TranslationElement RefId="(object ID)"&gt;
 *       &lt;Translation AttributeName="Text" Text="..."/&gt;
 * </pre>
 *
 * <p>
 * The object is looked up by its ID among the objects of this import and the shared objects known
 * from earlier imports. The {@link Config#getMappings() mappings} say which model attribute an XML
 * attribute of an object of a given type is imported into; translations of other attributes are
 * ignored.
 * </p>
 */
public class TranslationsImport<C extends TranslationsImport.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration of {@link TranslationsImport}.
	 */
	@TagName("translations")
	public interface Config<I extends TranslationsImport<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * Which model attribute the translations of an XML attribute go to, per model type.
		 */
		@Name("mappings")
		@EntryTag("mapping")
		List<Mapping> getMappings();

	}

	/**
	 * Maps the translations of an XML attribute of objects of a model type to a model attribute.
	 */
	public interface Mapping extends ConfigurationItem {

		/**
		 * The model type of the objects (including its specializations).
		 */
		@Name("type")
		@Mandatory
		String getType();

		/**
		 * The XML attribute whose translations are mapped.
		 */
		@Name("xml-attribute")
		@Mandatory
		String getXmlAttribute();

		/**
		 * The internationalized model attribute receiving the translations.
		 */
		@Name("attribute")
		@Mandatory
		String getAttribute();

	}

	private static final String LANGUAGE = "Language";

	private static final String TRANSLATION_ELEMENT = "TranslationElement";

	private static final String TRANSLATION = "Translation";

	/**
	 * Creates a {@link TranslationsImport} from configuration.
	 */
	@CalledByReflection
	public TranslationsImport(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Locale locale = null;
		TLObject target = null;
		int depth = 1;
		while (depth > 0) {
			int event = in.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				depth++;
				switch (in.getLocalName()) {
					case LANGUAGE:
						locale = I18NTexts.locale(in.getAttributeValue(null, "Identifier"));
						break;
					case TRANSLATION_ELEMENT:
						Object obj = context.resolveObjectBackwards(this, in.getLocation(),
							in.getAttributeValue(null, "RefId"));
						target = obj instanceof TLObject tlObject ? tlObject : null;
						break;
					case TRANSLATION:
						if (target != null && locale != null) {
							String attribute = attribute(target, in.getAttributeValue(null, "AttributeName"));
							if (attribute != null) {
								I18NTexts.set(target, attribute, locale, in.getAttributeValue(null, "Text"), false);
							}
						}
						break;
					default:
						break;
				}
			} else if (event == XMLStreamConstants.END_ELEMENT) {
				depth--;
			}
		}
		return null;
	}

	private String attribute(TLObject target, String xmlAttribute) {
		for (Mapping mapping : getConfig().getMappings()) {
			if (mapping.getXmlAttribute().equals(xmlAttribute)) {
				TLType type = TLModelUtil.findType(mapping.getType());
				if (TLModelUtil.isCompatibleInstance(type, target)) {
					return mapping.getAttribute();
				}
			}
		}
		return null;
	}

}
