/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Concludes the import of a new application program (the <code>this</code> of its
 * <code>&lt;object&gt;</code>, as its last handler, after the handlers reading its element): records
 * the language of its texts (see {@link ProgramLanguages}), and reports the warnings about values of
 * the program held back until the access of their parameters is known (see
 * {@link HiddenProgramValues}).
 *
 * <p>
 * It reads nothing of the element.
 * </p>
 */
public class ProgramImported<C extends ProgramImported.Config<?>> extends ConfiguredImportHandler<C> {

	private static final String EXT_ID = "extId";

	/**
	 * Configuration of {@link ProgramImported}.
	 */
	@TagName("program-imported")
	public interface Config<I extends ProgramImported<?>> extends ConfiguredImportHandler.Config<I> {
		// No options.
	}

	/**
	 * Creates a {@link ProgramImported} from configuration.
	 */
	@CalledByReflection
	public ProgramImported(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		// The references of the program are read: the warnings about the values of its parameters
		// are decided.
		HiddenProgramValues.reportPending(context);
		ProgramLanguages programs = ProgramLanguages.get(context);
		if (programs != null && context.getVarOrNull(ImportContext.THIS_VAR) instanceof TLObject program) {
			programs.created(id(program), program, I18NTexts.language(context));
		}
		return null;
	}

	/**
	 * The ETS ID of the given program, which names the translation unit of its texts.
	 */
	static String id(TLObject program) {
		return (String) program.tValueByName(EXT_ID);
	}

}
