/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.List;
import java.util.Locale;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * Imports an element only if the object its ID names is not known yet, and skips it otherwise.
 *
 * <p>
 * Used for data that never changes under the same ID, such as an application program, whose ID
 * includes its version: an import that finds the program already in the database (see
 * {@link Importer}) skips it with all its content instead of creating it again.
 * </p>
 *
 * <p>
 * With {@link Config#getTexts() texts}, the element is an application program, which records the
 * languages of its texts (see {@link ProgramLanguages}): ETS writes the same program in the language
 * of the installation that exports it. A new program records the base language of the element
 * (<code>DefaultLanguage</code>); a known one that lacks it gets the texts of the element in that
 * language.
 * </p>
 */
public class UnlessKnown<C extends UnlessKnown.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration of {@link UnlessKnown}.
	 */
	@TagName("unless-known")
	public interface Config<I extends UnlessKnown<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute holding the ID of the element.
		 */
		@Name("id-attribute")
		@Mandatory
		String getIdAttribute();

		/**
		 * The ID of the {@link TranslationsImport} whose mappings name the texts of the element, which
		 * is an application program recording the languages of its texts; without, the languages are
		 * not recorded, and a known element is skipped.
		 */
		@Name("texts")
		@Nullable
		String getTexts();

		/**
		 * The handlers importing the element if its object is not known yet.
		 */
		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getContents();

	}

	private static final String DEFAULT_LANGUAGE = "DefaultLanguage";

	private final List<Handler> _contents;

	private TranslationsImport<?> _texts;

	/**
	 * Creates a {@link UnlessKnown} from configuration.
	 */
	@CalledByReflection
	public UnlessKnown(InstantiationContext context, C config) {
		super(context, config);
		_contents = TypedConfiguration.getInstanceList(context, config.getContents());
		if (config.getTexts() != null) {
			context.resolveReference(config.getTexts(), Handler.class, texts -> _texts = (TranslationsImport<?>) texts);
		}
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String id = in.getAttributeValue(null, getConfig().getIdAttribute());
		ProgramLanguages programs = _texts == null || id == null ? null : ProgramLanguages.get(context);
		Locale base = baseLanguage(in);
		if (id != null) {
			Object known = context.resolveObjectBackwards(this, in.getLocation(), id);
			if (known != null) {
				if (programs != null && known instanceof TLObject program) {
					ProgramLanguages.Entry entry = programs.known(id, program);
					if (entry.lacks(base)) {
						_texts.importBaseTexts(context, in, entry, base);
						return known;
					}
				}
				XMLStreamUtil.skipUpToMatchingEndTag(in);
				return known;
			}
		}
		Object result = null;
		for (Handler handler : _contents) {
			result = handler.importXml(context, in);
		}
		if (programs != null && result instanceof TLObject program) {
			programs.created(id, program, base);
		}
		return result;
	}

	/**
	 * The language the texts of the element are written in (as {@link DefaultLanguage} reads it), the
	 * application's fallback language if the element does not name one.
	 */
	private static Locale baseLanguage(XMLStreamReader in) {
		String language = in.getAttributeValue(null, DEFAULT_LANGUAGE);
		if (language == null || language.isEmpty()) {
			return ResourcesModule.getInstance().getFallbackLocale();
		}
		return I18NTexts.locale(language);
	}

}
