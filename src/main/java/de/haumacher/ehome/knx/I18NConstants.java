/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.knx;

import com.top_logic.basic.util.ResKey2;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en "{0}" is no group address, expected: {1}
	 */
	public static ResKey2 ERROR_INVALID_GROUP_ADDRESS__TEXT_EXPECTED;

	static {
		initConstants(I18NConstants.class);
	}
}
