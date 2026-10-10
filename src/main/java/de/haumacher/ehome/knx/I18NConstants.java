/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en "{0}" is no group address, expected: {1}
	 */
	public static ResKey2 ERROR_INVALID_GROUP_ADDRESS__TEXT_EXPECTED;

	/**
	 * @en The address {0} lies outside the range {1} to {2} of its group.
	 */
	public static ResKey3 ERROR_ADDRESS_OUTSIDE_RANGE__ADDRESS_START_END;

	/**
	 * @en The range ends at {1} before it starts at {0}.
	 */
	public static ResKey2 ERROR_RANGE_REVERSED__START_END;

	static {
		initConstants(I18NConstants.class);
	}
}
