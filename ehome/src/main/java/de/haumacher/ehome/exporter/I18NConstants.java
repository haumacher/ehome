/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import com.top_logic.basic.util.ResKey1;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Export failed: {0}
	 */
	public static ResKey1 ERROR_EXPORT_FAILED__DETAIL;

	/**
	 * @en Left out, as the catalog does not identify them (ETS needs a product for every device): {0}.
	 */
	public static ResKey1 EXPORT_LEFT_OUT__DEVICES;

	static {
		initConstants(I18NConstants.class);
	}
}
