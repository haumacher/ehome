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
@SuppressWarnings("javadoc")
public class I18NConstants extends I18NConstantsBase {

	public static ResKey ERROR_PROBLEM__FILE_LINE_COL_MSG;

	public static ResKey ERROR_NO_LINKING;

	public static ResKey1 ERROR_INVALID_PART_REF__VALUE;

	public static ResKey2 ERROR_UNRESOLVED_REFERENCE__ID_REF;

	public static ResKey1 ERROR_UNRESOLVED_ID__ID;

	public static ResKey1 ERROR_DUPLICATE_IDENTIFIER__ID;

	public static ResKey ERROR_LINKING_NULL_TARGET;

	public static ResKey ERROR_LINKING_NULL_VALUE;

	static {
		initConstants(I18NConstants.class);
	}
}
