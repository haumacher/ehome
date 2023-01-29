/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class HandlerRef<C extends HandlerRef.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration options for {@link HandlerRef}.
	 */
	@TagName("ref")
	public interface Config<I extends HandlerRef<?>> extends ConfiguredImportHandler.Config<I> {
		String getHandlerId();
	}

	private Handler _delegate;

	/**
	 * Creates a {@link HandlerRef} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public HandlerRef(InstantiationContext context, C config) {
		super(context, config);
		context.resolveReference(config.getHandlerId(), Handler.class, h -> _delegate = h);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		return _delegate.importXml(context, in);
	}

}
