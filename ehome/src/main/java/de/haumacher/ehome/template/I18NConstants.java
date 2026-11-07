/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en "{0}" is no application program.
	 */
	public static ResKey1 ERROR_NO_PROGRAM__VALUE;

	/**
	 * @en The application program "{0}" has a template already.
	 */
	public static ResKey1 ERROR_TEMPLATE_EXISTS__PROGRAM;

	/**
	 * @en No communication object of a device is given.
	 */
	public static ResKey ERROR_NO_DEVICE_OBJECT;

	static {
		initConstants(I18NConstants.class);
	}
}
