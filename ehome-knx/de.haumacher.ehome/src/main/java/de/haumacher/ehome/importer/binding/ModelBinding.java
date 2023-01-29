/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.binding;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public interface ModelBinding extends ModelBindingBase {


	/**
	 * TODO
	 *
	 * @param obj
	 * @param type
	 * @return
	 */
	boolean isInstanceOf(Object obj, String type);


}
