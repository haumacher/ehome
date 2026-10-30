/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.ResourceProvider;
import com.top_logic.layout.provider.ProxyResourceProvider;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.react.control.ReactControl;
import com.top_logic.layout.react.control.table.ReactResourceCellControl;
import com.top_logic.layout.react.controlprovider.ReactControlProvider;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelInputs;
import com.top_logic.layout.view.channel.InputRef;
import com.top_logic.layout.view.channel.Inputs;
import com.top_logic.layout.view.channel.ViewChannel;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;

/**
 * Display of a node of a <code>&lt;tree&gt;</code> (its <code>nodeContent</code>) with a label
 * and a CSS class computed by TL-Script functions, for a tree whose nodes need another label than
 * the one their objects have everywhere else, or a mark.
 *
 * <p>
 * The node shows the icon of its object and the label the function computes from the object;
 * where the function gives nothing, the object's own label. Like the default node display, the
 * node is no link: a click selects it. Both expressions see the values of the channels named in
 * <code>inputs</code> as variables named after the channels, and the object as <code>$value</code>.
 * </p>
 *
 * <pre>
 * &lt;nodeContent class="de.haumacher.ehome.view.ScriptLabelControlProvider"
 *     label="if($value.instanceOf(`ehome.physical:Connection`), connectionLabel($value), null)"/&gt;
 * &lt;nodeContent class="de.haumacher.ehome.view.ScriptLabelControlProvider"
 *     inputs="device" css-class="if(deviceChanged($device, $value), 'ehomeChanged', null)"/&gt;
 * </pre>
 *
 * <p>
 * The label and the class are computed when the node is displayed and whenever its object
 * changes; a mark depending on other objects needs a tree that is built anew when it changes (an
 * input of the tree that changes with the mark).
 * </p>
 */
public class ScriptLabelControlProvider implements ReactControlProvider {

	/**
	 * Configuration of {@link ScriptLabelControlProvider}.
	 */
	public interface Config extends PolymorphicConfiguration<ScriptLabelControlProvider>, Inputs {

		/** Configuration name for {@link #getLabel()}. */
		String LABEL = "label";

		/** Configuration name for {@link #getCssClass()}. */
		String CSS_CLASS = "css-class";

		@Override
		@ClassDefault(ScriptLabelControlProvider.class)
		Class<? extends ScriptLabelControlProvider> getImplementationClass();

		/**
		 * Expression computing the label of a node from its object (the variable
		 * <code>$value</code>); <code>null</code> for the object's own label. Unset, every node
		 * shows the label of its object.
		 */
		@Name(LABEL)
		@Nullable
		Expr getLabel();

		/**
		 * Expression computing the CSS class of a node from its object (the variable
		 * <code>$value</code>); <code>null</code> for none.
		 */
		@Name(CSS_CLASS)
		@Nullable
		Expr getCssClass();
	}

	/** The variable holding the object of the node, behind the inputs. */
	private static final String VALUE = "value";

	private final Config _config;

	private final QueryExecutor _label;

	private final QueryExecutor _cssClass;

	/**
	 * Creates a {@link ScriptLabelControlProvider} from configuration.
	 */
	@CalledByReflection
	public ScriptLabelControlProvider(InstantiationContext context, Config config) {
		_config = config;
		List<String> variables = new ArrayList<>(InputRef.names(config.getInputs()));
		variables.add(VALUE);
		_label = ViewScripts.compileOptional(config.getLabel(), variables);
		_cssClass = ViewScripts.compileOptional(config.getCssClass(), variables);
	}

	@Override
	public ReactControl createControl(ReactContext context, Object model) {
		List<ViewChannel> inputs = context instanceof ViewContext view && !_config.getInputs().isEmpty()
			? ChannelInputs.resolve(view, _config.getInputs())
			: List.of();
		ResourceProvider resources = resources(
			_label == null ? null : object -> evaluate(_label, inputs, object),
			_cssClass == null ? null : object -> evaluate(_cssClass, inputs, object));
		return new ReactResourceCellControl(context, model, resources, true, true, false);
	}

	/**
	 * The display of an object with a label and a CSS class computed by the given functions: the
	 * icon of the object, the label the function computes (the object's own label where it gives
	 * nothing), and the class it computes (none where it gives nothing).
	 *
	 * @param label
	 *        Computes the label of an object; <code>null</code> for the object's own label.
	 * @param cssClass
	 *        Computes the CSS class of an object; <code>null</code> for the object's own class.
	 */
	static ResourceProvider resources(Function<Object, Object> label, Function<Object, Object> cssClass) {
		return new ProxyResourceProvider() {
			@Override
			public String getLabel(Object object) {
				Object result = label == null || object == null ? null : label.apply(object);
				return result == null ? super.getLabel(object) : result.toString();
			}

			@Override
			public String getCssClass(Object object) {
				if (cssClass == null) {
					return super.getCssClass(object);
				}
				// Never nothing: the class of the display is replaced only by another one.
				Object result = object == null ? null : cssClass.apply(object);
				return result == null ? "" : result.toString();
			}
		};
	}

	private static Object evaluate(QueryExecutor function, List<ViewChannel> inputs, Object object) {
		return function.execute(ChannelInputs.arguments(inputs, object));
	}

}
