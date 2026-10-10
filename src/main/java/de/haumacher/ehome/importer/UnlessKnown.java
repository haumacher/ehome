/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * Imports an element only if the object its ID names is not known yet, and skips it otherwise.
 *
 * <p>
 * Used for data that never changes under the same ID, such as an application program, whose ID
 * includes its version: an import that finds the program already in the database (see
 * {@link Importer}) skips it with all its content instead of creating it again.
 * </p>
 */
public class UnlessKnown<C extends UnlessKnown.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration of {@link UnlessKnown}.
	 */
	@TagName("unless-known")
	public interface Config<I extends UnlessKnown<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute holding the ID of the element.
		 */
		@Name("id-attribute")
		@Mandatory
		String getIdAttribute();

		/**
		 * The handlers importing the element if its object is not known yet.
		 */
		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getContents();

	}

	private final List<Handler> _contents;

	/**
	 * Creates a {@link UnlessKnown} from configuration.
	 */
	@CalledByReflection
	public UnlessKnown(InstantiationContext context, C config) {
		super(context, config);
		_contents = TypedConfiguration.getInstanceList(context, config.getContents());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String id = in.getAttributeValue(null, getConfig().getIdAttribute());
		if (id != null) {
			Object known = context.resolveObjectBackwards(this, in.getLocation(), id);
			if (known != null) {
				XMLStreamUtil.skipUpToMatchingEndTag(in);
				return known;
			}
		}
		Object result = null;
		for (Handler handler : _contents) {
			result = handler.importXml(context, in);
		}
		return result;
	}

}
