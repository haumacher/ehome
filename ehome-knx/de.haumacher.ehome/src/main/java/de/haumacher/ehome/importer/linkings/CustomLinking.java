/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.linkings;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.AbstractConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.binding.ImportContext;
import de.haumacher.ehome.importer.binding.ObjectLinking;

/**
 * TODO Remove, since it is in general not usable, since forwards references cannot be manipulated
 * from the query language.
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class CustomLinking<C extends CustomLinking.Config<?>> extends AbstractConfiguredInstance<C>
		implements ObjectLinking {

	/**
	 * Configuration options for {@link CustomLinking}.
	 */
	@TagName("custom-linking")
	public interface Config<I extends CustomLinking<?>> extends PolymorphicConfiguration<I> {

		/**
		 * {@link Expr} being executed for each object to link to its context.
		 * 
		 * <p>
		 * The expression is expected to be a fuction with two arguments: The context object and the
		 * newly created object.
		 * </p>
		 */
		Expr getExpr();
	}

	private QueryExecutor _expr;

	/**
	 * Creates a {@link CustomLinking} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public CustomLinking(InstantiationContext context, C config) {
		super(context, config);

		_expr = QueryExecutor.compile(PersistencyLayer.getKnowledgeBase(), ModelService.getApplicationModel(),
			config.getExpr());
	}

	@Override
	public void linkOrElse(ImportContext context, Object scope, Object target, Runnable continuation) {
		_expr.execute(scope, target);
	}

}
