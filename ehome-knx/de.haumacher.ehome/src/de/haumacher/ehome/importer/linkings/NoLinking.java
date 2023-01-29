/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.linkings;

import de.haumacher.ehome.importer.binding.ImportContext;
import de.haumacher.ehome.importer.binding.ObjectLinking;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class NoLinking implements ObjectLinking {

	/**
	 * Singleton {@link NoLinking} instance.
	 */
	public static final NoLinking INSTANCE = new NoLinking();

	private NoLinking() {
		// Singleton constructor.
	}

	@Override
	public void linkOrElse(ImportContext context, Object scope, Object target, Runnable continuation) {
		// Ignore.
	}

}
