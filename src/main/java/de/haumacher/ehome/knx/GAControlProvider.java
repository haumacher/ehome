/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.element.meta.AttributeOperations;
import com.top_logic.layout.form.model.AbstractFieldModel;
import com.top_logic.layout.form.model.FieldModel;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.react.control.ReactControl;
import com.top_logic.layout.react.control.form.ReactTextInputControl;
import com.top_logic.layout.react.field.FieldSpec;
import com.top_logic.layout.react.field.ReactFieldControlProvider;
import com.top_logic.layout.view.form.AttributeFieldModel;
import com.top_logic.util.error.TopLogicException;

/**
 * Input control of the model type <code>ehome.logical:GA</code>: a text field showing the group
 * address in its notation and reading what the user types in that notation.
 *
 * <p>
 * The notation is the one of the current value. A field without a value takes it from the
 * attribute's storage ({@link GroupAddressStorage#getStyle(com.top_logic.model.TLObject)}); if that
 * yields none either (e.g. a new address not yet added to its project), every notation is
 * accepted.
 * </p>
 */
public class GAControlProvider implements ReactFieldControlProvider {

	/**
	 * Configuration of {@link GAControlProvider}.
	 */
	public interface Config extends PolymorphicConfiguration<GAControlProvider> {

		@Override
		@ClassDefault(GAControlProvider.class)
		Class<? extends GAControlProvider> getImplementationClass();

	}

	/**
	 * Creates a {@link GAControlProvider} from configuration.
	 */
	@CalledByReflection
	public GAControlProvider(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public ReactControl createControl(ReactContext context, FieldSpec field, FieldModel model) {
		return new GAInputControl(context, model);
	}

	/**
	 * Text field reading a {@link GA}.
	 */
	static class GAInputControl extends ReactTextInputControl {

		GAInputControl(ReactContext context, FieldModel model) {
			super(context, model);
		}

		@Override
		protected void applyRawClientValue(Object rawValue) {
			String text = rawValue == null ? "" : rawValue.toString().trim();
			if (text.isEmpty()) {
				setError(null);
				applyClientValue(null);
				return;
			}
			GA value;
			try {
				value = GA.parse(text, style());
			} catch (TopLogicException ex) {
				setError(ex.getErrorKey());
				return;
			}
			setError(null);
			applyClientValue(value);
		}

		private GroupAddressStyle style() {
			FieldModel model = getFieldModel();
			if (model.getValue() instanceof GA current && current.getStyle() != null) {
				return current.getStyle();
			}
			if (model instanceof AttributeFieldModel attributeModel
				&& AttributeOperations.getStorageImplementation(attributeModel.getPart()) instanceof GroupAddressStorage<?> storage) {
				return storage.getStyle(attributeModel.getObject());
			}
			return null;
		}

		private void setError(ResKey error) {
			if (getFieldModel() instanceof AbstractFieldModel fieldModel) {
				fieldModel.setError(error);
			}
		}
	}

}
