/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import com.top_logic.basic.util.ResKey2;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Error: Unexpected attribute value "{0}", expecting "{1}".
	 */
	public static ResKey2 ERROR_UNEXPECTED_ATTRIBUTE_VALUE__GIVEN_EXPECTED;

	static {
		initConstants(I18NConstants.class);
	}
}
