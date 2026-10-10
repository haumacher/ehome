/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.knx;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.element.meta.AttributeException;
import com.top_logic.element.meta.kbbased.storage.PrimitiveStorage;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.search.expr.query.QueryExecutor;

/**
 * Attribute storage of the model type <code>ehome.logical:GA</code>.
 *
 * <p>
 * Stores the plain group address number like any primitive attribute and, when reading, attaches
 * the notation of the owning object to the {@link GA}: the attribute's {@link GAStyle} expression
 * evaluated on that object. The storage is declared on the type, so every attribute of the type
 * uses it without declaring anything.
 * </p>
 */
public class GroupAddressStorage<C extends GroupAddressStorage.Config<?>> extends PrimitiveStorage<C> {

	/**
	 * Configuration of {@link GroupAddressStorage}.
	 */
	@TagName("group-address-storage")
	public interface Config<I extends GroupAddressStorage<?>> extends PrimitiveStorage.Config<I> {

		@Override
		@ClassDefault(GroupAddressStorage.class)
		Class<? extends I> getImplementationClass();

	}

	private QueryExecutor _style;

	/**
	 * Creates a {@link GroupAddressStorage} from configuration.
	 */
	@CalledByReflection
	public GroupAddressStorage(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public void init(TLStructuredTypePart attribute) {
		super.init(attribute);

		GAStyle annotation = attribute.getAnnotation(GAStyle.class);
		if (annotation == null) {
			_style = null;
		} else {
			_style = QueryExecutor.compile(annotation.getStyle());
			// The notation is part of the model, not of what the current user may read.
			_style.disableSecurity();
		}
	}

	@Override
	public Object getAttributeValue(TLObject object, TLStructuredTypePart attribute) throws AttributeException {
		Object value = super.getAttributeValue(object, attribute);
		if (value instanceof GA ga) {
			return ga.withStyle(getStyle(object));
		}
		return value;
	}

	/**
	 * The notation of the group addresses of the given object, <code>null</code> if it cannot be
	 * determined.
	 */
	public GroupAddressStyle getStyle(TLObject object) {
		if (_style == null || object == null) {
			return null;
		}
		return GroupAddressStyle.fromModel(_style.execute(object));
	}

}
