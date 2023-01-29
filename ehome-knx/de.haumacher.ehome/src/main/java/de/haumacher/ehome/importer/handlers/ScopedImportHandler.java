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
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.binding.ImportContext;

/**
 * {@link NestedImportHandler} selecting a new import scope.
 * 
 * @see Config#getExpr()
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class ScopedImportHandler<C extends ScopedImportHandler.Config<?>> extends NestedImportHandler<C> {
	
	/**
	 * Configuration options for {@link ScopedImportHandler}.
	 */
	@TagName("in-scope")
	public interface Config<I extends ScopedImportHandler<?>> extends NestedImportHandler.Config<I> {

		@StringDefault(ImportContext.SCOPE_VAR)
		@Nullable
		String getArgumentVar();

		@StringDefault(ImportContext.THIS_VAR)
		@Nullable
		String getScopeVar();

		/**
		 * Expression computing the new scope for nested imports.
		 * 
		 * <p>
		 * The expression is expected to be a function taking as single argument the current scope
		 * of the import.
		 * </p>
		 */
		Expr getExpr();
	}

	private QueryExecutor _scope;
	
	/**
	 * Creates a {@link ScopedImportHandler} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public ScopedImportHandler(InstantiationContext context, C config) {
		super(context, config);

		_scope = QueryExecutor.compile(PersistencyLayer.getKnowledgeBase(), ModelService.getApplicationModel(),
			config.getExpr());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object newScope = _scope.execute(context.getVar(getConfig().getArgumentVar()));
		return context.withVar(getConfig().getScopeVar(), newScope, in, (context1, in1) -> {
			return importXmlInScope(context1, in1);
		});
	}

}
