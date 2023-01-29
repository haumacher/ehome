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
public class ObjectCreate<C extends ObjectCreate.Config<?>> extends ObjectImport<C> {

	/**
	 * Configuration options for {@link ObjectCreate}.
	 */
	@TagName("object")
	public interface Config<I extends ObjectCreate<?>> extends ObjectImport.Config<I> {
		String getModelType();

		boolean getJoinDuplicates();
	}

	/**
	 * Creates a {@link ObjectCreate} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public ObjectCreate(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	protected Object resolveObject(ImportContext context, String id) {
		if (getConfig().getJoinDuplicates()) {
			Object result = context.resolveObjectBackwards(id);
			if (result != null) {
				return result;
			}
		}
		return context.createObject(getConfig().getModelType(), id);
	}

	@Override
	protected Object importTarget(ImportContext context, XMLStreamReader in, Object target) throws XMLStreamException {
		super.importTarget(context, in, target);

		// Make sure to return the created object instead of its contents.
		return target;
	}

}
