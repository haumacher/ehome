/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Key;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;

import de.haumacher.ehome.importer.binding.ImportContext;
import de.haumacher.ehome.importer.handlers.Handler.Config.HandlerRef;
import de.haumacher.ehome.importer.handlers.PropertySwitch.Config.ValueHandler;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class PropertySwitch<C extends PropertySwitch.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration options for {@link PropertySwitch}.
	 */
	@TagName("switch")
	public interface Config<I extends PropertySwitch<?>> extends ConfiguredImportHandler.Config<I> {

		String getXmlAttribute();

		@Key(ValueHandler.VALUE)
		@DefaultContainer
		Map<String, ValueHandler> getCases();

		HandlerRef getDefault();

		interface ValueHandler extends HandlerRef {
			public static final String VALUE = "value";

			@Name(VALUE)
			@Mandatory
			String getValue();
		}
	}

	private Map<String, Handler> _cases;

	private Handler _default;

	/**
	 * Creates a {@link PropertySwitch} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public PropertySwitch(InstantiationContext context, C config) {
		super(context, config);

		_cases = new HashMap<>();
		for (ValueHandler valueHandler : config.getCases().values()) {
			_cases.put(valueHandler.getValue(), context.getInstance(valueHandler.getHandler()));
		}

		HandlerRef defaultRef = config.getDefault();
		if (defaultRef != null) {
			_default = context.getInstance(defaultRef.getHandler());
		} else {
			_default = context.getInstance(TypedConfiguration.newConfigItem(NoOp.Config.class));
		}
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String value = in.getAttributeValue(null, getConfig().getXmlAttribute());
		if (value != null) {
			Handler handler = _cases.get(value);
			if (handler == null) {
				handler = _default;
			}
			return handler.importXml(context, in);
		}
		return null;
	}

}
