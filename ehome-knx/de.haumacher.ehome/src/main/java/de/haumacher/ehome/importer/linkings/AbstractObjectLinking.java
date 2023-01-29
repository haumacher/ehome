/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.linkings;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.AbstractConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Abstract;

import de.haumacher.ehome.importer.binding.ImportContext;
import de.haumacher.ehome.importer.binding.ObjectLinking;

/**
 * Base class for {@link ObjectLinking} implementations.
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public abstract class AbstractObjectLinking<C extends AbstractObjectLinking.Config<?>>
		extends AbstractConfiguredInstance<C> implements ObjectLinking {

	/**
	 * Configuration options for {@link AbstractObjectLinking}.
	 */
	@Abstract
	public interface Config<I extends AbstractObjectLinking<?>> extends PolymorphicConfiguration<I> {
		/**
		 * Whether a property in the newly created object is referencing the context object instead
		 * of vice versa.
		 */
		boolean getReverse();
	}

	/**
	 * Creates a {@link AbstractObjectLinking} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public AbstractObjectLinking(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public final void linkOrElse(ImportContext context, Object scope, Object target, Runnable continuation) {
		Object self;
		Object value;
		if (getConfig().getReverse()) {
			self = target;
			value = scope;
		} else {
			self = scope;
			value = target;
		}
		link(context, self, value, continuation);
	}

	/**
	 * TODO
	 *
	 * @param context
	 * @param self
	 * @param value
	 * @param continuation
	 */
	protected abstract void link(ImportContext context, Object self, Object value, Runnable continuation);

}
