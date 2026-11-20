/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.element.meta.kbbased.storage.AbstractDerivedStorage;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;

import de.haumacher.ehome.model.operational.Program;

/**
 * The storage of <code>ehome.operational:Program#hardwareType</code> (haumacher/ehome #207): the
 * hardware type the program's load procedure compares, in hex as a device reports it, computed by
 * {@link CatalogMatch#hardwareTypeText(Program)} - the same reading of the load procedure the
 * catalog match excludes programs by. Nothing is stored: the load procedure stays the only source.
 *
 * <p>
 * In a form the storage is called with the edited object itself, so it reads the stored load
 * procedure, which no form edits.
 * </p>
 */
public class ProgramHardwareType extends AbstractDerivedStorage<AbstractDerivedStorage.Config<?>> {

	/**
	 * Creates a {@link ProgramHardwareType} from configuration.
	 */
	@CalledByReflection
	public ProgramHardwareType(InstantiationContext context, Config<?> config) {
		super(context, config);
	}

	@Override
	public Object getAttributeValue(TLObject object, TLStructuredTypePart attribute) {
		return object instanceof Program program ? CatalogMatch.hardwareTypeText(program) : null;
	}

}
