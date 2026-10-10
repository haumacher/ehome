/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.internal.AbstractConfiguredStorageMapping;

/**
 * Storage mapping of the model type <code>ehome.logical:GA</code>: the database keeps the plain
 * group address number, the application sees a {@link GA}.
 *
 * <p>
 * A mapping only sees the stored value, so the {@link GA} it creates carries no notation. The
 * attribute storage {@link GroupAddressStorage} adds the notation of the owning object. Writing
 * accepts a {@link GA} as well as a plain number, e.g. from an import.
 * </p>
 */
public class GAStorageMapping extends AbstractConfiguredStorageMapping<GAStorageMapping.Config, GA> {

	/**
	 * Configuration of {@link GAStorageMapping}.
	 */
	@TagName("group-address-mapping")
	public interface Config extends PolymorphicConfiguration<GAStorageMapping> {
		// Pure marker interface.
	}

	/**
	 * Creates a {@link GAStorageMapping} from configuration.
	 */
	@CalledByReflection
	public GAStorageMapping(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public Class<GA> getApplicationType() {
		return GA.class;
	}

	@Override
	public GA getBusinessObject(Object storageObject) {
		if (storageObject instanceof Number number) {
			return new GA(number.intValue(), null);
		}
		return null;
	}

	@Override
	public Object getStorageObject(Object businessObject) {
		if (businessObject instanceof GA ga) {
			return Integer.valueOf(ga.getValue());
		}
		if (businessObject instanceof Number number) {
			return Integer.valueOf(number.intValue());
		}
		return null;
	}

	@Override
	public boolean isCompatible(Object businessObject) {
		return businessObject == null || businessObject instanceof GA || businessObject instanceof Number;
	}

}
