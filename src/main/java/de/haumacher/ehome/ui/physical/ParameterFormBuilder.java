/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.ui.physical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;

import com.top_logic.basic.CollectionUtil;
import com.top_logic.basic.Logger;
import com.top_logic.basic.StringServices;
import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.layout.LabelProvider;
import com.top_logic.layout.form.FormContainer;
import com.top_logic.layout.form.FormField;
import com.top_logic.layout.form.ValueListener;
import com.top_logic.layout.form.constraints.IntRangeConstraint;
import com.top_logic.layout.form.constraints.RangeConstraint;
import com.top_logic.layout.form.model.ComplexField;
import com.top_logic.layout.form.model.DataField;
import com.top_logic.layout.form.model.FormContext;
import com.top_logic.layout.form.model.FormFactory;
import com.top_logic.layout.form.model.FormGroup;
import com.top_logic.layout.form.model.IntField;
import com.top_logic.layout.form.model.SelectField;
import com.top_logic.layout.form.model.StringField;
import com.top_logic.layout.form.values.Fields;
import com.top_logic.mig.html.ModelBuilder;
import com.top_logic.mig.html.layout.LayoutComponent;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.DateType;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.VoidType;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class ParameterFormBuilder implements ModelBuilder {

	private TLType _deviceType;

	/**
	 * Creates a {@link ParameterFormBuilder}.
	 *
	 */
	public ParameterFormBuilder() throws ConfigurationException {
		_deviceType = TLModelUtil.findType("ehome.physical:Device");
	}

	@Override
	public Object getModel(Object businessModel, LayoutComponent aComponent) {
		if (businessModel == null) {
			return null;
		}

		FormContext result = new FormContext(aComponent);

		TLObject model = (TLObject) businessModel;
		TLObject programAssignment = (TLObject) model.tValueByName("program");
		Program program = (Program) programAssignment.tValueByName("program");

		Context context = new Context();

		FormContainer current = result;
		for (ConfigFragment fragment : program.getFragments()) {
			fill(context, current, fragment);
		}

		context.attach();

		return result;
	}

	private void fill(Context context, FormContainer current, ConfigFragment fragment) {
		for (Option option : fragment.getContents()) {
			if (option instanceof ParameterGroup) {
				ParameterGroup group = (ParameterGroup) option;
				String name = group.getName();
				if (name == null) {
					name = "g" + System.nanoTime();
				}
				FormGroup inner = Fields.group(current, name);
				String label = group.getDescription();
				if (label == null || label.isEmpty()) {
					if (group.getInput() != null) {
						label = group.getInput().getDescription();
						if (label == null || label.isEmpty()) {
							label = group.getInput().getParameter().getDescription();
						}
					}
					if (label == null || label.isEmpty()) {
						label = group.getName();
					}
				}
				inner.setLabel(label);

				fill(context, inner, group);
			}
			else if (option instanceof ParameterView) {
				ParameterView view = (ParameterView) option;
				ParameterType type = view.getParameter().getType();
				FormField field = createField("p" + System.nanoTime(), type);
				if (field != null) {
					String label = view.getDescription();
					if (label == null || label.isEmpty()) {
						label = view.getParameter().getDescription();
						if (label == null || label.isEmpty()) {
							label = view.getName();
							if (label == null || label.isEmpty()) {
								label = view.getParameter().getName();
							}
						}
					}
					field.setLabel(label);
					current.addMember(field);
					context.registerInput(view, field);
				}
			}
			else if (option instanceof Switch) {
				Switch condition = (Switch) option;
				ParameterView input = condition.getInput();

				FormGroup switchGroup = Fields.group(current, "c" + System.nanoTime());

				Map<Object, FormGroup> alternativeByPattern = new HashMap<>();
				for (Case alternative : condition.getAlternatives()) {
					String pattern = alternative.getPattern();
					String patternName = pattern;
					if (patternName == null) {
						patternName = "p" + System.nanoTime();
					}
					FormGroup caseGroup = Fields.group(switchGroup, patternName);
					alternativeByPattern.put(pattern, caseGroup);
					
					fill(context, caseGroup, alternative);
				}
				
				context.registerOnChange(input, value -> alternativeByPattern.entrySet()
					.forEach(e -> e.getValue().setVisible(StringServices.equals(e.getKey(), value))));
			}
		}
	}

	private FormField createField(String name, ParameterType type) {
		if (type instanceof EnumerationType) {
			EnumerationType enumType = (EnumerationType) type;
			SelectField result = FormFactory.newSelectField(name, enumType.getValues(), false, false);
			result.setOptionLabelProvider(new LabelProvider() {
				@Override
				public String getLabel(Object object) {
					return ((EnumerationValue) object).getName();
				}
			});
			result.setOptionComparator(new Comparator<EnumerationValue>() {
				@Override
				public int compare(EnumerationValue o1, EnumerationValue o2) {
					Integer i1 = o1.getIndex();
					Integer i2 = o2.getIndex();
					if (i1 != null && i2 != null) {
						return Integer.compare(i1.intValue(), i2.intValue());
					}
					return Integer.compare(o1.getValue(), o2.getValue());
				}
			});
			return result;
		}
		if (type instanceof TextType) {
			StringField result = FormFactory.newStringField(name);
			return result;
		}
		if (type instanceof DateType) {
			DataField result = FormFactory.newDataField(name);
			return result;
		}
		if (type instanceof FloatingPointType) {
			ComplexField result = FormFactory.newDoubleField(name, 0.0d, false);
			return result;
		}
		if (type instanceof VoidType) {
			return null;
		}
		if (type instanceof NumericType) {
			NumericType numericType = (NumericType) type;
			long min = numericType.getMin();
			long max = numericType.getMax();
			if (min >= 0 && max < 256) {
				IntField result = FormFactory.newIntField(name);
				result.addConstraint(new IntRangeConstraint((int) min, (int) max));
				return result;
			} else {
				ComplexField result = FormFactory.newLongField(name, 0L, false);
				result.addConstraint(new RangeConstraint(min, max));
				return result;
			}
		}

		Logger.error("Unknown parameter type '" + type + "'.", ParameterFormBuilder.class);
		return null;
	}

	@Override
	public boolean supportsModel(Object aModel, LayoutComponent aComponent) {
		return aModel instanceof TLObject && TLModelUtil.isCompatibleInstance(_deviceType, (TLObject) aModel);
	}

	static class Context implements ValueListener {
	
		private static final Property<ParameterView> INPUT = TypedAnnotatable.property(ParameterView.class, "input");
	
		private Map<ParameterView, List<Consumer<Object>>> _handlers = new HashMap<>();
	
		private Map<ParameterView, FormField> _fields = new HashMap<>();
	
		/**
		 * TODO
		 *
		 * @param input
		 * @param object
		 */
		public void registerOnChange(ParameterView input, Consumer<Object> handler) {
			mk(input).add(handler);
		}
	
		private List<Consumer<Object>> mk(ParameterView input) {
			List<Consumer<Object>> result = _handlers.get(input);
			if (result == null) {
				result = new ArrayList<>();
				_handlers.put(input, result);
			}
			return result;
		}
	
		/**
		 * TODO
		 *
		 * @param option
		 * @param field
		 */
		public void registerInput(ParameterView input, FormField field) {
			field.set(INPUT, input);
			FormField clash = _fields.put(input, field);
			if (clash != null) {
				Logger.warn("More than one input for paramter '" + input + "'.", ParameterFormBuilder.class);
			}
		}
	
		public void attach() {
			for (Entry<ParameterView, FormField> entry : _fields.entrySet()) {
				ParameterView input = entry.getKey();
				FormField field = entry.getValue();
				field.addValueListener(this);
				deliverValue(input, field);
			}
		}
	
		public void detach() {
			for (FormField field : _fields.values()) {
				field.removeValueListener(this);
			}
		}
	
		@Override
		public void valueChanged(FormField field, Object oldValue, Object newValue) {
			Object normalizedValue;
			if (field instanceof SelectField && !((SelectField) field).isMultiple()) {
				normalizedValue = CollectionUtil.getSingleValueFrom(newValue);
			} else {
				normalizedValue = newValue;
			}
			if (normalizedValue instanceof EnumerationValue) {
				normalizedValue = Integer.toString(((EnumerationValue) normalizedValue).getValue());
			}
			deliverValue(field.get(INPUT), normalizedValue);
		}
	
		private void deliverValue(ParameterView input, Object value) {
			_handlers.getOrDefault(input, Collections.emptyList()).forEach(h -> h.accept(value));
		}
	
	}

}
