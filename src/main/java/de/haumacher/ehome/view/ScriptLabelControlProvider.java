/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.ResourceProvider;
import com.top_logic.layout.provider.ProxyResourceProvider;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.react.control.ReactControl;
import com.top_logic.layout.react.control.table.ReactResourceCellControl;
import com.top_logic.layout.react.controlprovider.ReactControlProvider;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;

/**
 * Display of a node of a <code>&lt;tree&gt;</code> (its <code>nodeContent</code>) with a label
 * computed by a TL-Script function, for a tree whose nodes need another label than the one their
 * objects have everywhere else.
 *
 * <p>
 * The node shows the icon of its object and the label the function computes from the object;
 * where the function gives nothing, the object's own label. Like the default node display, the
 * node is no link: a click selects it.
 * </p>
 *
 * <pre>
 * &lt;nodeContent class="de.haumacher.ehome.view.ScriptLabelControlProvider"
 *     label="x -> if($x.instanceOf(`ehome.physical:Connection`), connectionLabel($x), null)"/&gt;
 * </pre>
 */
public class ScriptLabelControlProvider implements ReactControlProvider {

	/**
	 * Configuration of {@link ScriptLabelControlProvider}.
	 */
	public interface Config extends PolymorphicConfiguration<ScriptLabelControlProvider> {

		/** Configuration name for {@link #getLabel()}. */
		String LABEL = "label";

		@Override
		@ClassDefault(ScriptLabelControlProvider.class)
		Class<? extends ScriptLabelControlProvider> getImplementationClass();

		/**
		 * Function computing the label of a node from its object; <code>null</code> for the
		 * object's own label.
		 */
		@Name(LABEL)
		@Mandatory
		Expr getLabel();
	}

	private final QueryExecutor _label;

	private final ResourceProvider _resources;

	/**
	 * Creates a {@link ScriptLabelControlProvider} from configuration.
	 */
	@CalledByReflection
	public ScriptLabelControlProvider(InstantiationContext context, Config config) {
		_label = QueryExecutor.compile(config.getLabel());
		_resources = new ProxyResourceProvider() {
			@Override
			public String getLabel(Object object) {
				Object label = object == null ? null : _label.execute(object);
				return label == null ? super.getLabel(object) : label.toString();
			}
		};
	}

	@Override
	public ReactControl createControl(ReactContext context, Object model) {
		return new ReactResourceCellControl(context, model, _resources, true, true, false);
	}

}
