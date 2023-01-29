/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Abstract;
import com.top_logic.basic.config.annotation.Nullable;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public abstract class ObjectImport<C extends ObjectImport.Config<?>> extends NestedImportHandler<C> {

	/**
	 * Configuration options for {@link ObjectImport}.
	 */
	@Abstract
	public interface Config<I extends ObjectImport<?>> extends NestedImportHandler.Config<I> {

		/**
		 * The XML attribute that assigns the ID to the created object.
		 */
		String getIdAttribute();

		/**
		 * Explicit variable name in the context for the created object.
		 * 
		 * <p>
		 * Inner handlers may explicitly reference this definition from the context.
		 * </p>
		 */
		@Nullable
		String getVar();
	}

	/**
	 * Creates a {@link ObjectImport} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public ObjectImport(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context0, XMLStreamReader in0) throws XMLStreamException {
		Object contextObject = context0.getVarOrNull(ImportContext.THIS_VAR);
		return context0.withVar(ImportContext.SCOPE_VAR, contextObject, in0, (context1, in1) -> {
			String id = in1.getAttributeValue(null, getConfig().getIdAttribute());

			Object target = resolveObject(context1, id);

			return importTarget(context1, in1, target);
		});
	}

	/** 
	 * TODO
	 *
	 * @param context
	 * @param in
	 * @param target
	 * @return
	 * @throws XMLStreamException
	 */
	protected Object importTarget(ImportContext context, XMLStreamReader in, Object target)
			throws XMLStreamException {
		return context.withVar(ImportContext.THIS_VAR, target, in, (context1, in1) -> {
			return context1.withVar(getConfig().getVar(), target, in1, (context2, in2) -> {
				return importXmlInScope(context2, in2);
			});
		});
	}

	/**
	 * TODO
	 *
	 * @param context
	 * @param id
	 * @return
	 */
	protected abstract Object resolveObject(ImportContext context, String id);

}
