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

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class NestedImportHandler<C extends NestedImportHandler.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration options for {@link NestedImportHandler}.
	 */
	public interface Config<I extends NestedImportHandler<?>> extends ConfiguredImportHandler.Config<I> {

		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getContent();

	}

	private final Handler _contentHandler;

	/**
	 * Creates a {@link NestedImportHandler} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public NestedImportHandler(InstantiationContext context, C config) {
		super(context, config);

		List<PolymorphicConfiguration<? extends Handler>> content = config.getContent();
		_contentHandler = buildHandlers(context, content);
	}

	/**
	 * TODO
	 *
	 * @param context
	 * @param handlerConfigs
	 * @return
	 */
	public static Handler buildHandlers(InstantiationContext context,
			List<PolymorphicConfiguration<? extends Handler>> handlerConfigs) {
		if (handlerConfigs.isEmpty()) {
			return context.getInstance(TypedConfiguration.newConfigItem(DispatchingImporter.Config.class));
		} else if (handlerConfigs.size() > 1) {
			List<Handler> handlers = TypedConfiguration.getInstanceList(context, handlerConfigs);
			return new Handler() {
				@Override
				public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
					Object lastResult = null;
					for (Handler handler : handlers) {
						lastResult = handler.importXml(context, in);
					}
					return lastResult;
				}
			};
		} else {
			return context.getInstance(handlerConfigs.get(0));
		}
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		return importXmlInScope(context, in);
	}

	/**
	 * TODO
	 *
	 * @param context
	 * @param in
	 * @return
	 * @throws XMLStreamException
	 */
	protected final Object importXmlInScope(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		inScopeLocal(context, in);

		return _contentHandler.importXml(context, in);
	}

	/**
	 * TODO
	 *
	 * @param context
	 * @param in
	 * @throws XMLStreamException
	 */
	protected void inScopeLocal(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		// Hook for sub-classes.
	}
}
