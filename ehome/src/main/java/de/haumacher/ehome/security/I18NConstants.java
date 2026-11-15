/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.security;

import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en The E-Home administrators manage the members of the groups ehome.installers and ehome.administrators only, not of {0}.
	 */
	public static ResKey1 ERROR_NOT_EHOME_GROUP__GROUP;

	/**
	 * @en {0} is a member of {1} already.
	 */
	public static ResKey2 ERROR_ALREADY_IN_GROUP__ACCOUNT_GROUP;

	/**
	 * @en {0} is not a member of {1}.
	 */
	public static ResKey2 ERROR_NOT_IN_GROUP__ACCOUNT_GROUP;

	static {
		initConstants(I18NConstants.class);
	}
}
