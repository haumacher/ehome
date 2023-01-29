/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.discover;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
@SuppressWarnings("javadoc")
public class I18NConstants extends I18NConstantsBase {

	public static ResKey DISCOVERY_FAILED__DETAIL;

	public static ResKey NO_ENDPOINTS_FOUND;

	public static ResKey UNKNOWN_HOST__VALUE;

	public static ResKey UNKNOWN_INTERFACE__VALUE;

	static {
		initConstants(I18NConstants.class);
	}
}
