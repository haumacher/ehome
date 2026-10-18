/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Handles the dynamic modules of an application program (ETS 5.6 and later: <code>ModuleDefs</code>),
 * which E-Home does not import.
 *
 * <p>
 * The parameters and communication objects of the modules are missing in the program, so a download
 * would be incomplete: the program is marked (<code>ehome.operational:Program#usesModules</code>),
 * which makes the download refuse it, and the import reports a warning naming the program. The
 * module definitions are skipped.
 * </p>
 */
public class ModulesNotImported<C extends ModulesNotImported.Config<?>> extends ConfiguredImportHandler<C> {

	private static final String USES_MODULES = "usesModules";

	private static final String EXT_ID = "extId";

	/**
	 * Configuration of {@link ModulesNotImported}.
	 */
	@TagName("modules-not-imported")
	public interface Config<I extends ModulesNotImported<?>> extends ConfiguredImportHandler.Config<I> {
		// No options.
	}

	/**
	 * Creates a {@link ModulesNotImported} from configuration.
	 */
	@CalledByReflection
	public ModulesNotImported(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object program = context.getVar(ImportContext.THIS_VAR);
		context.setProperty(this, program, USES_MODULES, Boolean.TRUE);
		String id = program instanceof TLObject obj ? (String) obj.tValueByName(EXT_ID) : null;
		context.log(Level.WARN, in.getLocation(),
			I18NConstants.WARN_MODULES_NOT_IMPORTED__PROGRAM.fill(id == null ? "-" : id));
		XMLStreamUtil.skipUpToMatchingEndTag(in);
		return null;
	}

}
