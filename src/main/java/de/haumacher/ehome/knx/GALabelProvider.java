/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.knx;

import com.top_logic.layout.LabelProvider;

/**
 * {@link LabelProvider} for {@link GA} values: the address in the notation it carries.
 *
 * <p>
 * Registered for the class {@link GA} in the <code>LabelProviderService</code>, so table cells,
 * search and filter texts and every other label show the same text as the input field.
 * </p>
 */
public class GALabelProvider implements LabelProvider {

	/**
	 * Singleton {@link GALabelProvider} instance.
	 */
	public static final GALabelProvider INSTANCE = new GALabelProvider();

	@Override
	public String getLabel(Object object) {
		return object == null ? null : object.toString();
	}

}
