/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.AbstractReferenceImport;

/**
 * Imports a reference from a device to a part of its application program, given by a full ID (ETS 4)
 * or by an ID relative to the program (ETS 5 and later: <code>O-2_R-1</code>), see
 * {@link DeviceScope}.
 */
public class ProgramReference<C extends ProgramReference.Config<?>> extends AbstractReferenceImport<C> {

	/**
	 * The beginning of the ID of manufacturer data.
	 */
	private static final String MANUFACTURER_PREFIX = "M-";

	/**
	 * The part of an ID that names a module definition, in the ID of a part of a module instance.
	 */
	private static final String MODULE_PART = "_MD-";

	/**
	 * Configuration of {@link ProgramReference}.
	 */
	@TagName("program-reference")
	public interface Config<I extends ProgramReference<?>> extends AbstractReferenceImport.Config<I> {
		// Only the ID attribute and the target.
	}

	/**
	 * Creates a {@link ProgramReference} from configuration.
	 */
	@CalledByReflection
	public ProgramReference(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String rawId = readRawId(in);
		if (rawId != null && !rawId.isBlank()) {
			String id = DeviceScope.absolute(context, DeviceScope.PROGRAM_VAR, MANUFACTURER_PREFIX, rawId.trim());
			// A part of an instance of a dynamic module (MD-1_M-1_MI-1_P-2_R-3) exists if the import
			// expanded the modules of the program (ModuleExpansion); if it could not (ModulesNotImported),
			// the importer leaves out what refers to it and says so per device, instead of an error per ID.
			boolean required = !id.contains(MODULE_PART);
			storeReference(context, context.resolveObject(this, in.getLocation(), id, required));
		}
		return null;
	}

}
