/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.NestedImportHandler;

/**
 * Reads the dynamic modules of an application program (haumacher/ehome #119) with its content
 * handlers: the module definitions (<code>ModuleDefs</code>), or a placement of a module
 * (<code>Module</code>) or a repeated part (<code>Repeat</code>) of the structure, unless the program
 * uses a construct the import cannot interpret (see {@link ModuleScan#getRefusal(String)}).
 *
 * <p>
 * Then the element is skipped: for the module definitions, the program is marked
 * (<code>ehome.operational:Program#usesModules</code>), which makes the download refuse it, and the
 * import reports a warning naming the program and the construct; a placement or repeat is skipped
 * without a further message. The device's settings and objects of instances of its modules are left
 * out with a warning per device (<code>Importer</code>).
 * </p>
 */
public class ReadModules<C extends ReadModules.Config<?>> extends NestedImportHandler<C> {

	private static final String USES_MODULES = "usesModules";

	private static final String EXT_ID = "extId";

	private static final String PROGRAM_VAR = "program";

	/**
	 * Configuration of {@link ReadModules}.
	 */
	@TagName("read-modules")
	public interface Config<I extends ReadModules<?>> extends NestedImportHandler.Config<I> {

		/**
		 * Whether the element is the module definitions of the program (else a placement or repeat).
		 */
		@Name("definitions")
		boolean getDefinitions();

	}

	/**
	 * Creates a {@link ReadModules} from configuration.
	 */
	@CalledByReflection
	public ReadModules(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object program = context.getVarOrNull(PROGRAM_VAR);
		String id = program instanceof TLObject obj ? (String) obj.tValueByName(EXT_ID) : null;
		String construct = id == null ? null : ModuleScan.get(context).getRefusal(id);
		if (construct == null) {
			return importXmlInScope(context, in);
		}
		if (getConfig().getDefinitions()) {
			context.setProperty(this, program, USES_MODULES, Boolean.TRUE);
			context.log(Level.WARN, in.getLocation(),
				I18NConstants.WARN_MODULES_NOT_READ__PROGRAM_CONSTRUCT.fill(id, construct));
		}
		XMLStreamUtil.skipUpToMatchingEndTag(in);
		return null;
	}

}
