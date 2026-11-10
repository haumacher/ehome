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
	 * @en The import of {0} cannot be saved because values fail their checks: {1}
	 */
	public static ResKey2 ERROR_IMPORT_CONSTRAINTS__FILE_PROBLEMS;

	/**
	 * @en Imported: {0}. Messages of the import: {1}.
	 */
	public static ResKey2 IMPORT_SUMMARY__IMPORTED_COUNT;

	/**
	 * @en Imported product data: {0}. Messages of the import: {1}.
	 */
	public static ResKey2 PRODUCT_SUMMARY__IMPORTED_COUNT;

	/**
	 * @en {0} ({1})
	 */
	public static ResKey2 PRODUCT_FILE__FILE_CONTENTS;

	/**
	 * @en Manufacturer imported: {0}
	 */
	public static ResKey1 MANUFACTURERS_IMPORTED__NAMES;

	/**
	 * @en Manufacturer already known: {0}
	 */
	public static ResKey1 MANUFACTURERS_KNOWN__NAMES;

	/**
	 * @en Products imported: {0}
	 */
	public static ResKey1 PRODUCTS_IMPORTED__NAMES;

	/**
	 * @en Products already known: {0}
	 */
	public static ResKey1 PRODUCTS_KNOWN__NAMES;

	/**
	 * @en Application programs imported: {0}
	 */
	public static ResKey1 PROGRAMS_IMPORTED__NAMES;

	/**
	 * @en Application programs already known: {0}
	 */
	public static ResKey1 PROGRAMS_KNOWN__NAMES;

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

	/**
	 * @en Reading {0}
	 */
	public static ResKey1 PROGRESS_READING__FILE;

	/**
	 * @en Saving
	 */
	public static ResKey PROGRESS_SAVING;

	/**
	 * @en Updated from ETS: {0}. Messages of the update: {1}.
	 */
	public static ResKey2 UPDATE_SUMMARY__UPDATED_COUNT;

	/**
	 * @en Updating the project {0}
	 */
	public static ResKey1 PROGRESS_UPDATING__PROJECT;

	/**
	 * @en Select the project to update.
	 */
	public static ResKey ERROR_UPDATE_NO_PROJECT;

	/**
	 * @en The file holds no project.
	 */
	public static ResKey ERROR_UPDATE_NO_PROJECT_IN_FILE;

	static {
		initConstants(I18NConstants.class);
	}
}
