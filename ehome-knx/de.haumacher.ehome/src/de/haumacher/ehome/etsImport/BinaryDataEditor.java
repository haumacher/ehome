/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.etsImport;

import static com.top_logic.layout.form.values.Fields.*;
import static com.top_logic.layout.form.values.Values.*;

import com.top_logic.basic.config.PropertyDescriptor;
import com.top_logic.basic.func.Identity;
import com.top_logic.layout.form.FormContainer;
import com.top_logic.layout.form.FormMember;
import com.top_logic.layout.form.model.DataField;
import com.top_logic.layout.form.model.FormFactory;
import com.top_logic.layout.form.values.ConfigurationValue;
import com.top_logic.layout.form.values.Fields;
import com.top_logic.layout.form.values.ModifiableValue;
import com.top_logic.layout.form.values.Values;
import com.top_logic.layout.form.values.edit.EditorFactory;
import com.top_logic.layout.form.values.edit.ValueModel;
import com.top_logic.layout.form.values.edit.editor.Editor;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class BinaryDataEditor implements Editor {

	/**
	 * Singleton {@link BinaryDataEditor} instance.
	 */
	public static final BinaryDataEditor INSTANCE = new BinaryDataEditor();

	private BinaryDataEditor() {
		// Singleton constructor.
	}

	@Override
	public FormMember createUI(EditorFactory editorFactory, FormContainer container, ValueModel model) {
		PropertyDescriptor property = model.getProperty();
		DataField result = FormFactory.newDataField(property.getPropertyName());
		ConfigurationValue<Object> modelValue = Values.configurationValue(model.getModel(), property);
		Fields.bindValue(result, modelValue, Identity.INSTANCE);
		if (property.canHaveSetter()) {
			ModifiableValue<Object> input = fieldValue(result);
			linkStorage(input, modelValue, Identity.INSTANCE);
		}
		container.addMember(result);
		return result;
	}

}
