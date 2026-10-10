/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
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

	static {
		initConstants(I18NConstants.class);
	}
}
