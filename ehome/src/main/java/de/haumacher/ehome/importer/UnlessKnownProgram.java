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
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * Imports an application program only if the program its ID names is not known yet; a known one is
 * skipped, except for the texts of the languages it lacks.
 *
 * <p>
 * An application program never changes under its ID, which includes its version: an import that
 * finds the program already in the database skips it with all its content instead of creating it
 * again, as the engine's <code>&lt;unless-known&gt;</code> does. But a program records the languages
 * of its texts (see {@link ProgramLanguages}), and ETS writes the same program in the language of
 * the installation that exports it. A new program records the language of the element (the variable
 * {@link I18NTexts#LANGUAGE_VAR}, bound to its <code>DefaultLanguage</code>); a known one that lacks
 * it gets the texts of the element in that language (haumacher/ehome #38). The engine's
 * <code>&lt;unless-known&gt;</code> can do neither (TL ticket, see <code>doc/tl-todo.md</code>).
 * </p>
 */
public class UnlessKnownProgram<C extends UnlessKnownProgram.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration of {@link UnlessKnownProgram}.
	 */
	@TagName("unless-known-program")
	public interface Config<I extends UnlessKnownProgram<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute holding the ID of the element.
		 */
		@Name("id-attribute")
		@Mandatory
		String getIdAttribute();

		/**
		 * The ID of the {@link TranslationsImport} whose mappings name the texts of the program.
		 */
		@Name("texts")
		@Mandatory
		String getTexts();

		/**
		 * The handlers importing the program if it is not known yet.
		 */
		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getContents();

	}

	private final List<Handler> _contents;

	private TranslationsImport<?> _texts;

	/**
	 * Creates a {@link UnlessKnownProgram} from configuration.
	 */
	@CalledByReflection
	public UnlessKnownProgram(InstantiationContext context, C config) {
		super(context, config);
		_contents = TypedConfiguration.getInstanceList(context, config.getContents());
		context.resolveReference(config.getTexts(), Handler.class, texts -> _texts = (TranslationsImport<?>) texts);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String id = in.getAttributeValue(null, getConfig().getIdAttribute());
		ProgramLanguages programs = id == null ? null : ProgramLanguages.get(context);
		Locale base = I18NTexts.language(context);
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

}
