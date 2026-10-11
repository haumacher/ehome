/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Import failed: {0}
	 */
	public static ResKey1 ERROR_IMPORT_FAILED__DETAIL;

	/**
	 * @en Imported: {0}. Messages of the import: {1}.
	 */
	public static ResKey2 IMPORT_SUMMARY__IMPORTED_COUNT;

	/**
	 * @en Error
	 */
	public static ResKey LEVEL_ERROR;

	/**
	 * @en Warning
	 */
	public static ResKey LEVEL_WARNING;

	/**
	 * @en Note
	 */
	public static ResKey LEVEL_INFO;

	static {
		initConstants(I18NConstants.class);
	}
}
