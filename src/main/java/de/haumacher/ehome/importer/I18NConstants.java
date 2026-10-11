/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Error in file "{0}" line {1} column {2}: {3}
	 */
	public static ResKey ERROR_PROBLEM__FILE_LINE_COL_MSG;

	/**
	 * @en Error: No linking.
	 */
	public static ResKey ERROR_NO_LINKING;

	/**
	 * @en Error: Invalid part reference: {0}
	 */
	public static ResKey1 ERROR_INVALID_PART_REF__VALUE;

	/**
	 * @en Error: Cannot resolve part reference "{0}": {1}
	 */
	public static ResKey2 ERROR_UNRESOLVED_REFERENCE__ID_REF;

	/**
	 * @en Error: Cannot resolve ID: {0}
	 */
	public static ResKey1 ERROR_UNRESOLVED_ID__ID;

	/**
	 * @en Error: Duplicate ID: {0}
	 */
	public static ResKey1 ERROR_DUPLICATE_IDENTIFIER__ID;

	/**
	 * @en Error: No target for linking.
	 */
	public static ResKey ERROR_LINKING_NULL_TARGET;

	/**
	 * @en Error: No value for linking.
	 */
	public static ResKey ERROR_LINKING_NULL_VALUE;

	/**
	 * @en Parameter "{0}": {1}
	 */
	public static ResKey2 WARN_PARAMETER__NAME_PROBLEM;

	/**
	 * @en The file cannot be read: it holds an encrypted (password protected) entry, which follows the entry "{0}". Only unencrypted files can be imported.
	 */
	public static ResKey1 ERROR_ENCRYPTED_ENTRY__AFTER;

	/**
	 * @en The file is no readable ZIP archive: {0}
	 */
	public static ResKey1 ERROR_INVALID_ARCHIVE__DETAIL;

	/**
	 * @en The product file holds a project (entry "{0}"). Import a project file (.knxproj) in the projects view.
	 */
	public static ResKey1 ERROR_PROJECT_IN_PRODUCT_DATA__ENTRY;

	/**
	 * @en The product file holds baggage (entry "{0}"): pictures, help or plug-in files of ETS 5 or 6, which E-Home cannot import. Only product files without baggage can be imported.
	 */
	public static ResKey1 ERROR_BAGGAGES_IN_PRODUCT_DATA__ENTRY;

	/**
	 * @en The file holds no product data of a manufacturer (no folder "M-xxxx").
	 */
	public static ResKey ERROR_NO_MANUFACTURER_DATA;

	static {
		initConstants(I18NConstants.class);
	}
}
