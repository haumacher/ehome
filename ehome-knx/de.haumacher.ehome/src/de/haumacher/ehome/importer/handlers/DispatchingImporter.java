/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Key;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;

import de.haumacher.ehome.importer.binding.ImportContext;
import de.haumacher.ehome.importer.handlers.DispatchingImporter.Config.TagHandler;
import de.haumacher.ehome.importer.handlers.Handler.Config.HandlerRef;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class DispatchingImporter<C extends DispatchingImporter.Config<?>> extends ConfiguredImportHandler<C> {

	private static SkipDeep.Config<?> SKIP_DEEP = TypedConfiguration.newConfigItem(SkipDeep.Config.class);

	/**
	 * Configuration options for {@link DispatchingImporter}.
	 */
	@TagName("dispatch")
	public interface Config<I extends DispatchingImporter<?>> extends ConfiguredImportHandler.Config<I> {

		HandlerRef getDefault();

		@Key(TagHandler.NAME)
		@DefaultContainer
		Map<String, TagHandler> getTags();

		interface TagHandler extends ConfigurationItem {
			public static final String NAME = "name";

			@Name(NAME)
			String getName();

			@DefaultContainer
			List<PolymorphicConfiguration<? extends Handler>> getImpl();
		}
	}

	private Map<String, Handler> _handlers = new HashMap<>();

	private Handler _defaultHandler;

	/**
	 * Creates a {@link DispatchingImporter} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public DispatchingImporter(InstantiationContext context, C config) {
		super(context, config);

		HandlerRef defaultRef = config.getDefault();
		if (defaultRef != null) {
			_defaultHandler = context.getInstance(defaultRef.getHandler());
		} else {
			_defaultHandler = context.getInstance(SKIP_DEEP);
		}
		_handlers = new HashMap<>();
		for (TagHandler tag : config.getTags().values()) {
			_handlers.put(tag.getName(), NestedImportHandler.buildHandlers(context, tag.getImpl()));
		}
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object result = null;
		while (true) {
			switch (in.next()) {
				case XMLStreamConstants.END_ELEMENT:
				case XMLStreamConstants.END_DOCUMENT: {
					return result;
				}

				case XMLStreamConstants.START_ELEMENT: {
					String localName = in.getLocalName();
					Handler importer = importer(localName);
					result = importer.importXml(context, in);
					break;
				}

				default: {
					// Ignore.
				}
			}
		}
	}

	private void importTag(ImportContext context, XMLStreamReader in) throws XMLStreamException {
	}

	private Handler importer(String localName) {
		Handler result = _handlers.get(localName);
		if (result == null) {
			return _defaultHandler;
		}
		return result;
	}

}
