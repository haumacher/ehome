/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.StringDefault;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class ReferenceImport<C extends ReferenceImport.Config<?>> extends DispatchingImporter<C> {

	/**
	 * Configuration options for {@link ReferenceImport}.
	 */
	@TagName("reference")
	public interface Config<I extends ReferenceImport<?>> extends DispatchingImporter.Config<I> {

		@StringDefault(ImportContext.THIS_VAR)
		@Nullable
		String getTargetVar();

		String getName();

		String getIdAttribute();

	}

	/**
	 * Creates a {@link ReferenceImport} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public ReferenceImport(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String refId = in.getAttributeValue(null, getConfig().getIdAttribute());
		if (refId != null) {
			context.setReference(context.getVar(getConfig().getTargetVar()), getConfig().getName(),
				context.resolveObject(refId));
		}
		return null;
	}

}
