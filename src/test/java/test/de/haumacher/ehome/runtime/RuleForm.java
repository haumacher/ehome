/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.List;

import com.top_logic.basic.util.ResKey;
import com.top_logic.element.meta.form.validation.FormValidationModel;
import com.top_logic.layout.view.form.TLObjectOverlay;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.form.ValidationResult;
import com.top_logic.util.Resources;

/**
 * A form editing an object in a test: an overlay of the object whose model constraints are checked
 * by the {@link FormValidationModel} a form of the view layer uses, the messages read in the
 * language of the test.
 */
public final class RuleForm {

	private final TLObjectOverlay _overlay;

	private final FormValidationModel _validation = new FormValidationModel();

	/**
	 * Creates a {@link RuleForm} editing the given object.
	 */
	public RuleForm(TLObject obj) {
		_overlay = new TLObjectOverlay(obj);
		_validation.addOverlay(_overlay, obj);
	}

	/**
	 * Enters a value, as a field does.
	 */
	public void set(String attribute, Object value) {
		_overlay.tUpdateByName(attribute, value);
		_validation.onValueChanged(_overlay, part(attribute));
	}

	/**
	 * The errors the field of the attribute shows.
	 */
	public List<String> errors(String attribute) {
		return texts(result(attribute).getErrors());
	}

	/**
	 * The warnings the field of the attribute shows.
	 */
	public List<String> warnings(String attribute) {
		return texts(result(attribute).getWarnings());
	}

	private ValidationResult result(String attribute) {
		ValidationResult result = _validation.getValidation(_overlay, part(attribute));
		return result == null ? ValidationResult.VALID : result;
	}

	private TLStructuredTypePart part(String attribute) {
		return _overlay.tType().getPart(attribute);
	}

	private static List<String> texts(List<ResKey> keys) {
		return keys.stream().map(key -> Resources.getInstance().getString(key)).toList();
	}

}
