/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport.importer.format;

import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Invalid object size format "{0}", expected "{1}".
	 */
	public static ResKey2 INVALID_OBJECT_SIZE_FORMAT__FOUND_EXPECTED;

	/**
	 * @en Invalid boolean "{0}", expected true, false, 1 or 0.
	 */
	public static ResKey1 ERROR_INVALID_BOOLEAN__VALUE;

	static {
		initConstants(I18NConstants.class);
	}
}
