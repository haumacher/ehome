/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.form.model.AbstractFieldModel;
import com.top_logic.layout.form.model.FieldModel;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.react.control.ReactControl;
import com.top_logic.layout.react.control.form.ReactTextInputControl;
import com.top_logic.layout.react.field.FieldSpec;
import com.top_logic.layout.react.field.ReactFieldControlProvider;

/**
 * The input of a line of the installation, written <code>area.line</code> (e.g. <code>1.1</code>,
 * {@link BusScanner.Line}), in the dialog "Read the installation" (<code>projects/scan.view.xml</code>,
 * haumacher/ehome #182).
 *
 * <p>
 * A text input whose text is no line shows the reason at the field
 * ({@link I18NConstants#ERROR_NO_LINE}). The text reaches the value as entered, a refused one as
 * well, so that what decides on it - the veto of the scan - reads what the field shows; a refused
 * text keeps no earlier line in force. As a text field that judges its value, it sends the value
 * when it is left (or by Enter), not while it is typed in, where every prefix of a line would be
 * refused.
 * </p>
 *
 * <p>
 * A <code>&lt;value-input&gt;</code> checks no constraint of its own beyond a missing value (the
 * other constraints are checks over an object in a <code>&lt;form&gt;</code>), so the check is part
 * of the input control.
 * </p>
 */
public class LineInputControlProvider implements ReactFieldControlProvider {

	/**
	 * Configuration of {@link LineInputControlProvider}.
	 */
	public interface Config extends PolymorphicConfiguration<LineInputControlProvider> {

		@Override
		@ClassDefault(LineInputControlProvider.class)
		Class<? extends LineInputControlProvider> getImplementationClass();
	}

	/**
	 * Creates a {@link LineInputControlProvider} from configuration.
	 */
	@CalledByReflection
	public LineInputControlProvider(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public ReactControl createControl(ReactContext context, FieldSpec field, FieldModel model) {
		return new LineInput(context, model);
	}

	/**
	 * Why the given text is no line, <code>null</code> if it is one or is empty (a missing value is
	 * reported as such).
	 */
	public static ResKey problem(Object value) {
		String text = value == null ? "" : value.toString().strip();
		if (text.isEmpty() || BusScanner.Line.parse(text) != null) {
			return null;
		}
		return I18NConstants.ERROR_NO_LINE;
	}

	/**
	 * The text input checking its text for a line.
	 */
	static class LineInput extends ReactTextInputControl {

		/**
		 * Creates a {@link LineInput}, showing at once why a text it is built over is no line.
		 */
		LineInput(ReactContext context, FieldModel model) {
			super(context, model);
			setSendValueOnBlur(true);
			setError(problem(model.getValue()));
		}

		@Override
		protected void applyRawClientValue(Object rawValue) {
			String text = rawValue == null ? "" : rawValue.toString().strip();
			setError(problem(text));
			applyClientValue(text.isEmpty() ? null : text);
		}

		private void setError(ResKey error) {
			if (getFieldModel() instanceof AbstractFieldModel model) {
				model.setError(error);
			}
		}
	}

}
