/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.AbstractConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.util.ValueNormalizer;

/**
 * {@link ValueNormalizer} of a text of the rule language (haumacher/ehome #60): a form stores an
 * entered expression or literal in its canonical form ({@link RuleNormalization#canonical(TLObject,
 * String, String)}), the form the image carries.
 */
public class RuleTextNormalizer extends AbstractConfiguredInstance<RuleTextNormalizer.Config<?>>
		implements ValueNormalizer {

	/**
	 * Configuration of {@link RuleTextNormalizer}.
	 */
	@TagName("rule-text")
	public interface Config<I extends RuleTextNormalizer> extends PolymorphicConfiguration<I> {
		// No properties.
	}

	/**
	 * Creates a {@link RuleTextNormalizer} from configuration.
	 */
	@CalledByReflection
	public RuleTextNormalizer(InstantiationContext context, Config<?> config) {
		super(context, config);
	}

	@Override
	public Object normalize(TLObject object, TLStructuredTypePart attribute, Object value) {
		return value instanceof String text ? RuleNormalization.canonical(object, attribute.getName(), text) : value;
	}

}
