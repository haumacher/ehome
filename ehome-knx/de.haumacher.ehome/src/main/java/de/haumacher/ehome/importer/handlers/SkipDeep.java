/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.xml.XMLStreamUtil;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class SkipDeep<C extends SkipDeep.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration options for {@link SkipDeep}.
	 */
	@TagName("skipDeep")
	public interface Config<I extends SkipDeep<?>> extends ConfiguredImportHandler.Config<I> {

	}

	/**
	 * Creates a {@link SkipDeep} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public SkipDeep(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		XMLStreamUtil.skipUpToMatchingEndTag(in);
		return null;
	}

}
