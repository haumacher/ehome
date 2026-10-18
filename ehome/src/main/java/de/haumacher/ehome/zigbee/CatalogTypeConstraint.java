/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.col.Sink;
import com.top_logic.basic.config.AbstractConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.model.form.OverlayLookup;
import com.top_logic.model.util.Pointer;

/**
 * Constraint of the recommended datapoint type of a function entry of the Zigbee catalog
 * (<code>ehome.zigbee.catalog:ZigbeeFunctionEntry#type</code>): it must fit the value type the
 * function carries ({@link ZigbeeBindings#fits(String, String)}), as a binding must; a function key
 * E-Home does not know (a newer controller's) takes any type.
 */
public class CatalogTypeConstraint extends AbstractConfiguredInstance<CatalogTypeConstraint.Config>
		implements ConstraintCheck {

	/**
	 * Configuration of {@link CatalogTypeConstraint}.
	 */
	public interface Config extends PolymorphicConfiguration<CatalogTypeConstraint> {
		// No options.
	}

	/**
	 * Creates a {@link CatalogTypeConstraint} from configuration.
	 */
	@CalledByReflection
	public CatalogTypeConstraint(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public ResKey check(TLObject object, TLStructuredTypePart attribute) {
		Object type = object.tValueByName(attribute.getName());
		Object function = object.tValueByName("function");
		if (!(type instanceof TLClassifier classifier) || function == null) {
			return null;
		}
		return check(function.toString(), classifier.getName());
	}

	/**
	 * Why a datapoint of the given value type does not fit the function with the given key,
	 * <code>null</code> if it fits or the key is unknown.
	 */
	public static ResKey check(String function, String type) {
		ZigbeeFunctionTable.Info info = ZigbeeFunctionTable.byKey(function);
		if (info == null || ZigbeeBindings.fits(info.valueType(), type)) {
			return null;
		}
		return I18NConstants.CATALOG_TYPE_MISFIT__TYPE_FUNCTION_VALUE_TYPE.fill(type, function, info.valueType());
	}

	@Override
	public void traceDependencies(TLObject object, TLStructuredTypePart attribute, Sink<Pointer> trace,
			OverlayLookup overlays) {
		trace.add(Pointer.create(object, object.tType().getPart("function")));
	}

}
