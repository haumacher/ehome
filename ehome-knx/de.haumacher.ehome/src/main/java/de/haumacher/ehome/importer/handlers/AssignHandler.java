/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.model.search.expr.config.dom.Expr;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class AssignHandler<C extends AssignHandler.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration options for {@link AssignHandler}.
	 */
	@TagName("assign")
	public interface Config<I extends AssignHandler<?>> extends ConfiguredImportHandler.Config<I> {

		@StringDefault(ImportContext.SCOPE_VAR)
		@Nullable
		String getArgumentVar();

		@StringDefault(ImportContext.THIS_VAR)
		@Nullable
		String getTargetVar();

		@Mandatory
		String getProperty();

		@Mandatory
		Expr getValue();
	}

	/**
	 * Creates a {@link AssignHandler} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public AssignHandler(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object argument = context.getVar(getConfig().getArgumentVar());
		Object value = context.eval(getConfig().getValue(), argument);
		Object target = context.getVar(getConfig().getTargetVar());
		context.setProperty(target, getConfig().getProperty(), value);
		return value;
	}

}
