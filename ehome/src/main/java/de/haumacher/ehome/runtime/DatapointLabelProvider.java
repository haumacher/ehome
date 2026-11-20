/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.layout.LabelProvider;
import com.top_logic.model.TLObject;

/**
 * {@link LabelProvider} naming a datapoint that has no name of its own as every datapoint is named
 * ({@link DatapointNames#name(TLObject)}, haumacher/ehome #212): a port or parameter of a virtual
 * device by the device and its manifest with its id, <code>Raumregler Wohnzimmer: Gefilterte
 * Temperatur (block/raumregler/filtered)</code>, where the default label would be "unnamed object".
 *
 * <p>
 * Registered for <code>ehome.runtime:BlockPort</code> and <code>ehome.runtime:BlockParameter</code> in the
 * <code>LabelProviderService</code>, so the role fields of a function, choices and table cells show the
 * same text.
 * </p>
 */
public class DatapointLabelProvider implements LabelProvider {

	@Override
	public String getLabel(Object object) {
		return object instanceof TLObject obj && obj.tValid() ? DatapointNames.name(obj) : null;
	}

}
