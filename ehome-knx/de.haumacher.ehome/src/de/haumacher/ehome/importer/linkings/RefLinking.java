/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.linkings;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;

import de.haumacher.ehome.importer.binding.ImportContext;
import de.haumacher.ehome.importer.binding.PartRef;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class RefLinking<C extends RefLinking.Config<?>> extends AbstractObjectLinking<C> {

	/**
	 * Configuration options for {@link RefLinking}.
	 */
	@TagName("ref-linking")
	public interface Config<I extends RefLinking<?>> extends AbstractObjectLinking.Config<I> {
		PartRef getName();
	}

	/**
	 * Creates a {@link RefLinking} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public RefLinking(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	protected void link(ImportContext context, Object self, Object value, Runnable continuation) {
		context.isInstanceOf(self, getConfig().getName().getType(),
			() -> context.setReference(self, getConfig().getName().getPart(), value),
			continuation);
	}

}
