/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.discover;

import com.top_logic.basic.util.ResKey1;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Discover failed: {0}
	 */
	public static ResKey1 DISCOVERY_FAILED__DETAIL;

	/**
	 * @en Unknown host: {0}
	 */
	public static ResKey1 UNKNOWN_HOST__VALUE;

	/**
	 * @en Unknown interface: {0}
	 */
	public static ResKey1 UNKNOWN_INTERFACE__VALUE;

	static {
		initConstants(I18NConstants.class);
	}
}
