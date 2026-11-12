/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.settings;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en This installation does not reach local networks; connect through the controller.
	 */
	public static ResKey ERROR_NO_LOCAL_NETWORK;

	static {
		initConstants(I18NConstants.class);
	}
}
