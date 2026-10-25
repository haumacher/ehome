/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.form.model.FieldModel;
import com.top_logic.layout.form.model.SelectFieldModel;
import com.top_logic.layout.provider.MetaResourceProvider;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.react.control.ReactControl;
import com.top_logic.layout.react.control.select.ReactDropdownSelectControl;
import com.top_logic.layout.react.control.select.SelectDisplay;
import com.top_logic.layout.react.field.FieldSpec;
import com.top_logic.layout.structure.OrientationAware.Orientation;
import com.top_logic.layout.view.form.SelectControlProvider;

/**
 * {@link SelectControlProvider} offering the options in the order they are given instead of
 * sorted by their labels, for options whose order is part of their meaning: the days of the week,
 * the step sizes of a dimmer.
 *
 * <pre>
 * &lt;value-input inputs="target" options="t -> $t['days']" value="day"&gt;
 *     &lt;input-control class="de.haumacher.ehome.view.OptionOrderSelectControlProvider"/&gt;
 * &lt;/value-input&gt;
 * </pre>
 *
 * <p>
 * Workaround: the engine's {@link SelectControlProvider} always sorts the options by their labels
 * and has no setting to keep their order (8.0.0-alpha11, TL #29874, see <code>doc/tl-todo.md</code>).
 * </p>
 */
public class OptionOrderSelectControlProvider extends SelectControlProvider {

	/**
	 * Configuration of {@link OptionOrderSelectControlProvider}.
	 */
	public interface Config extends SelectControlProvider.Config {

		@Override
		@ClassDefault(OptionOrderSelectControlProvider.class)
		Class<? extends SelectControlProvider> getImplementationClass();

	}

	/**
	 * Creates an {@link OptionOrderSelectControlProvider} from configuration.
	 */
	@CalledByReflection
	public OptionOrderSelectControlProvider(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public ReactControl createControl(ReactContext context, FieldSpec field, FieldModel model) {
		SelectDisplay display = getDisplay() == null ? field.getSelectDisplay() : getDisplay();
		Orientation orientation = getOrientation() == null ? field.getSelectOrientation() : getOrientation();
		// No comparator: the options keep the order of the option list.
		ReactDropdownSelectControl control = new ReactDropdownSelectControl(context, (SelectFieldModel) model,
			MetaResourceProvider.INSTANCE, null, field.isOrdered(), display, orientation);
		if (!hasFilter()) {
			control.setFilter(false);
		}
		return control;
	}

}
