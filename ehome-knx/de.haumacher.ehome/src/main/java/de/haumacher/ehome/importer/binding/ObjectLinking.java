/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.binding;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public interface ObjectLinking {

	/**
	 * TODO
	 *
	 * @param context
	 * @param scope
	 * @param target
	 * @param object
	 */
	void linkOrElse(ImportContext context, Object scope, Object target, Runnable continuation);

}
