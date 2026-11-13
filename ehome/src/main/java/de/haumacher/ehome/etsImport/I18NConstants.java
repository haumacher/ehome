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
	 * @en No application program
	 */
	public static ResKey NO_PROGRAMS;

	/**
	 * @en Files of the manufacturer imported: {0}
	 */
	public static ResKey1 BAGGAGE_IMPORTED__COUNT;

	/**
	 * @en Files of the manufacturer replaced by a newer version: {0}
	 */
	public static ResKey1 BAGGAGE_REPLACED__NAMES;

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

	/**
	 * @en ETS import: {0}
	 */
	public static ResKey1 COMMIT_ETS_IMPORT__PROJECT;

	/**
	 * @en Product data imported: {0}
	 */
	public static ResKey1 COMMIT_PRODUCT_DATA_IMPORT__FILE;

	/**
	 * @en Product data imported, the data shared by the application programs: {0}
	 */
	public static ResKey1 COMMIT_PRODUCT_DATA_SHARED__FILE;

	/**
	 * @en Product data imported, application program {1}: {0}
	 */
	public static ResKey2 COMMIT_PRODUCT_DATA_PROGRAM__FILE_PROGRAM;

	/**
	 * @en Application programs not imported: {0}
	 */
	public static ResKey1 PROGRAMS_FAILED__NAMES;

	/**
	 * @en {0} ({1})
	 */
	public static ResKey2 PROGRAM_FAILED__PROGRAM_REASON;

	/**
	 * @en E-Home project file imported: {0}
	 */
	public static ResKey1 COMMIT_PROJECT_FILE_IMPORT__PROJECT;

	/**
	 * @en Project {0} updated from ETS ({1})
	 */
	public static ResKey2 COMMIT_ETS_UPDATE__PROJECT_FILE;

	static {
		initConstants(I18NConstants.class);
	}
}
