/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.StringDefault;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class ChainHandler<C extends ChainHandler.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration options for {@link ChainHandler}.
	 */
	@TagName("chain")
	public interface Config<I extends ChainHandler<?>> extends ConfiguredImportHandler.Config<I> {

		@StringDefault(ImportContext.SCOPE_VAR)
		@Nullable
		String getInputVar();

		@StringDefault(ImportContext.SCOPE_VAR)
		@Nullable
		String getChainVar();

		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getHandlers();

	}

	private final List<Handler> _handlers;

	/**
	 * Creates a {@link ChainHandler} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public ChainHandler(InstantiationContext context, C config) {
		super(context, config);

		_handlers = TypedConfiguration.getInstanceList(context, config.getHandlers());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String var = getConfig().getChainVar();
		Object result = context.getVar(getConfig().getInputVar());
		for (Handler handler : _handlers) {
			result = context.withVar(var, result, in, handler);
		}
		return result;
	}

}
