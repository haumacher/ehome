/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.common.builder;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.Logger;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.ConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.EntryTag;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.defaults.BooleanDefault;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.layout.tree.component.AbstractTreeModelBuilder;
import com.top_logic.mig.html.layout.LayoutComponent;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.common.builder.ConfiguredTreeBuilder.Config.ModelPattern;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class ConfiguredTreeBuilder<C extends ConfiguredTreeBuilder.Config<?>> extends AbstractTreeModelBuilder<TLObject>
		implements ConfiguredInstance<C> {

	/**
	 * Configuration options for {@link ConfiguredTreeBuilder}.
	 */
	public interface Config<I extends ConfiguredTreeBuilder<?>> extends PolymorphicConfiguration<I> {

		/**
		 * Whether this builder describes a finte model that can be completely expanded.
		 */
		@BooleanDefault(true)
		public boolean getExpandAll();

		/**
		 * One-arg function deciding whether the given object is accepted as model (root element) of
		 * this tree.
		 */
		@Mandatory
		Expr getSupports();

		/**
		 * Two-arg function deciding whether the second argument can be displayed anywhere in the
		 * tree, while the components model is passed as first argument.
		 */
		@Mandatory
		Expr getSupportsNode();

		@EntryTag("switch")
		List<ModelPattern> getChildren();

		@EntryTag("switch")
		List<ModelPattern> getParent();

		interface ModelPattern extends ConfigurationItem {
			String getType();

			Expr getExpr();
		}

	}

	private C _config;

	private QueryExecutor _supports;

	private QueryExecutor _supportsNode;

	private ModelSwitch _children;

	private ModelSwitch _parent;

	/**
	 * Creates a {@link ConfiguredTreeBuilder} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public ConfiguredTreeBuilder(InstantiationContext context, C config) {
		_config = config;
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLModel model = ModelService.getApplicationModel();
		_supports = QueryExecutor.compile(kb, model, config.getSupports());
		_supportsNode = QueryExecutor.compile(kb, model, config.getSupportsNode());
		_children = compileSwitch(model, config.getChildren());
		_parent = compileSwitch(model, config.getParent());
	}

	private static class ModelSwitch {

		private static class Case {

			private final TLType _type;

			private final QueryExecutor _expr;

			/**
			 * Creates a {@link Case}.
			 *
			 * @param type
			 * @param expr
			 */
			public Case(TLType type, QueryExecutor expr) {
				_type = type;
				_expr = expr;
			}

			/**
			 * TODO
			 *
			 * @param model
			 * @param p
			 * @return
			 */
			public static Case createOrNull(TLModel model, ModelPattern p) {
				try {
					return tryCreate(model, p);
				} catch (ConfigurationException ex) {
					Logger.error("No such type: " + p.getType(), Case.class);
					return null;
				}
			}

			/**
			 * TODO
			 *
			 * @param model
			 * @param p
			 * @return
			 * @throws ConfigurationException
			 */
			private static Case tryCreate(TLModel model, ModelPattern p) throws ConfigurationException {
				TLType type = TLModelUtil.findType(model, p.getType());
				QueryExecutor expr = QueryExecutor.compile(model.tKnowledgeBase(), model, p.getExpr());
				return new Case(type, expr);
			}

			/**
			 * TODO
			 */
			public TLType getType() {
				return _type;
			}

			/**
			 * TODO
			 */
			public QueryExecutor getExpr() {
				return _expr;
			}

		}

		private List<Case> _cases;

		/**
		 * Creates a {@link ModelSwitch}.
		 * 
		 * @param model
		 *
		 * @param patterns
		 * @throws ConfigurationException
		 */
		public ModelSwitch(TLModel model, List<ModelPattern> patterns) {
			_cases = patterns.stream().map(p -> Case.createOrNull(model, p)).filter(c -> c != null)
				.collect(Collectors.toList());
		}

		/**
		 * TODO
		 *
		 * @param tType
		 * @param node
		 * @return
		 */
		public Object execute(TLObject context, Object... args) {
			for (Case branch : _cases) {
				if (TLModelUtil.isCompatibleInstance(branch.getType(), context)) {
					return branch.getExpr().execute(args);
				}
			}
			return null;
		}

	}

	private ModelSwitch compileSwitch(TLModel model, List<ModelPattern> patterns) {
		return new ModelSwitch(model, patterns);
	}

	@Override
	public C getConfig() {
		return _config;
	}

	@Override
	public boolean canExpandAll() {
		return getConfig().getExpandAll();
	}

	@Override
	public Iterator<? extends TLObject> getChildIterator(TLObject node) {
		Collection<? extends TLObject> children = (Collection<? extends TLObject>) _children.execute(node, node);
		if (children == null) {
			return Collections.<TLObject> emptyList().iterator();
		}
		return children.iterator();
	}

	@Override
	public Collection<? extends TLObject> getParents(LayoutComponent contextComponent, TLObject node) {
		Object result = _parent.execute(node, contextComponent.getModel(), node);
		if (result instanceof Collection<?>) {
			return (Collection<? extends TLObject>) result;
		} else {
			return Collections.singletonList((TLObject) result);
		}
	}

	@Override
	public Object retrieveModelFromNode(LayoutComponent contextComponent, TLObject node) {
		// TODO: Automatically created
		return null;
	}

	@Override
	public boolean supportsNode(LayoutComponent contextComponent, Object node) {
		return ((Boolean) _supportsNode.execute(contextComponent.getModel(), node)).booleanValue();
	}

	@Override
	public boolean supportsModel(Object aModel, LayoutComponent aComponent) {
		return ((Boolean) _supports.execute(aModel)).booleanValue();
	}

}
