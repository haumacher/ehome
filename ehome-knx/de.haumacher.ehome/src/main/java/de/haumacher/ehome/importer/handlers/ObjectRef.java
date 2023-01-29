/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class ObjectRef<C extends ObjectRef.Config<?>> extends ObjectImport<C> {

	/**
	 * Configuration options for {@link ObjectRef}.
	 */
	@TagName("object-ref")
	public interface Config<I extends ObjectRef<?>> extends ObjectImport.Config<I> {
		// Pure marker interface.
	}

	/**
	 * Creates a {@link ObjectRef} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public ObjectRef(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	protected Object resolveObject(ImportContext context, String id) {
		return context.resolveObject(id);
	}

}
