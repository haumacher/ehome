/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.model.TLObject;
import com.top_logic.model.initializer.TLObjectInitializer;

/**
 * Gives an object created without external ID one in the manner of ETS, see {@link ExtIds}.
 *
 * <p>
 * Configured on <code>ehome.core:Externalized</code> (<code>&lt;object-initializers&gt;</code>), so it
 * runs for every externalized object when the transaction creating it commits: at that time the
 * object is linked to its container, which determines the ID. An imported object keeps the ID ETS
 * wrote.
 * </p>
 */
public class ExtIdInitializer implements TLObjectInitializer {

	/**
	 * Creates a {@link ExtIdInitializer}.
	 */
	public ExtIdInitializer() {
		super();
	}

	@Override
	public void initializeObject(TLObject object) {
		if (object.tValid()) {
			ExtIds.assign(object);
		}
	}

}
