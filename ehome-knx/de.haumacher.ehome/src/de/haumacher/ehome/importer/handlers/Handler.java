/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Id;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Nullable;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public interface Handler {

	/**
	 * Configuration options for a {@link Handler}.
	 */
	interface Config<I extends Handler> extends PolymorphicConfiguration<I> {
		@Id(value = Handler.class)
		@Nullable
		String getId();

		interface HandlerRef extends ConfigurationItem {
			@DefaultContainer
			@Mandatory
			PolymorphicConfiguration<Handler> getHandler();
		}

	}

	/**
	 * TODO
	 * @param context TODO
	 * @param in
	 * @return TODO
	 *
	 * @throws XMLStreamException
	 */
	Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException;

}
