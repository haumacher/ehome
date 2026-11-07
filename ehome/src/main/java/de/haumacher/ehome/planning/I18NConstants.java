/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.planning;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en {0}, version {1}
	 */
	public static ResKey2 PROGRAM_LABEL__NAME_VERSION;

	/**
	 * @en Choose the line the device is planned in.
	 */
	public static ResKey ERROR_NO_LINE;

	/**
	 * @en Enter the device number of the individual address in the line {0}.
	 */
	public static ResKey1 ERROR_NO_NUMBER__LINE;

	/**
	 * @en {0} is no device number of a line: the device number lies between {1} and {2}.
	 */
	public static ResKey3 ERROR_NUMBER_RANGE__NUMBER_MIN_MAX;

	/**
	 * @en The individual address {0} is taken by the device {1}.
	 */
	public static ResKey2 ERROR_ADDRESS_TAKEN__ADDRESS_DEVICE;

	/**
	 * @en Choose the product from the catalog.
	 */
	public static ResKey ERROR_NO_PRODUCT;

	/**
	 * @en Choose one of the application programs of the product {0}.
	 */
	public static ResKey1 ERROR_NO_PROGRAM__PRODUCT;

	/**
	 * @en The building part {0} belongs to another project than the line.
	 */
	public static ResKey1 ERROR_OTHER_PROJECT__LOCATION;

	/**
	 * @en Select the device to duplicate.
	 */
	public static ResKey ERROR_NO_DEVICE;

	/**
	 * @en The device {0} is in no line; a duplicate is created in the line of the original.
	 */
	public static ResKey1 ERROR_NOT_IN_LINE__DEVICE;

	/**
	 * @en The device {0} has no product or no application program of the catalog.
	 */
	public static ResKey1 ERROR_NO_CATALOG_ENTRY__DEVICE;

	/**
	 * @en The line {0} has no free individual address.
	 */
	public static ResKey1 ERROR_LINE_FULL__LINE;

	static {
		initConstants(I18NConstants.class);
	}
}
