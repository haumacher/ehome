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
public class WithResult<C extends WithResult.Config<?>> extends ObjectRef<C> {

	/**
	 * Configuration options for {@link WithResult}.
	 */
	@TagName("with-result")
	public interface Config<I extends WithResult<?>> extends ObjectRef.Config<I> {
		// Pure marker interface.
	}

	/**
	 * Creates a {@link WithResult} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public WithResult(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String id = in.getAttributeValue(null, getConfig().getIdAttribute());

		Object result = super.importXml(context, in);
		context.assignId(result, id);

		return result;
	}

}
