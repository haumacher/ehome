/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.Locale;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.NestedImportHandler;

/**
 * Updates a known application program (the <code>this</code> of an <code>&lt;update&gt;</code> of
 * <code>&lt;unless-known&gt;</code>) with the texts of the file's language, if the program lacks it
 * (haumacher/ehome #38).
 *
 * <p>
 * An application program never changes under its ID, which includes its version, so a known one is
 * not imported again. But a program records the languages of its texts (see
 * {@link ProgramLanguages}), and ETS writes the same program in the language of the installation that
 * exports it. If the stored program lacks the language of the element (the variable
 * {@link I18NTexts#LANGUAGE_VAR}, bound to its <code>DefaultLanguage</code>), the content handlers set
 * the texts (the text groups of the program's elements, applied to its known descendants by
 * <code>&lt;update-known&gt;</code>), and the language is recorded; otherwise the element is skipped.
 * Either way the program is known to the translations of the file, which add only the languages it
 * lacked before the import.
 * </p>
 *
 * <p>
 * The decision and the record are the app's: the engine's <code>condition</code> of
 * <code>&lt;update&gt;</code> could decide, but not record what the import added.
 * </p>
 */
public class KnownProgramTexts<C extends KnownProgramTexts.Config<?>> extends NestedImportHandler<C> {

	/**
	 * Configuration of {@link KnownProgramTexts}.
	 */
	@TagName("known-program-texts")
	public interface Config<I extends KnownProgramTexts<?>> extends NestedImportHandler.Config<I> {
		// No options.
	}

	/**
	 * Creates a {@link KnownProgramTexts} from configuration.
	 */
	@CalledByReflection
	public KnownProgramTexts(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		ProgramLanguages programs = ProgramLanguages.get(context);
		if (programs != null && context.getVarOrNull(ImportContext.THIS_VAR) instanceof TLObject program) {
			Locale base = I18NTexts.language(context);
			ProgramLanguages.Entry entry = programs.known(ProgramImported.id(program), program);
			if (entry.lacks(base)) {
				Object result = importXmlInScope(context, in);
				entry.addLanguage(base);
				return result;
			}
		}
		XMLStreamUtil.skipUpToMatchingEndTag(in);
		return null;
	}

}
