/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport.importer.format;

import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.util.TLModelUtil;

/**
 * Format reading the medium (<code>ehome.common:Medium</code>) from the ID of an ETS medium type
 * (<code>MT-0</code>), which is formed from the KNX medium number: 0 twisted pair, 1 power line, 2
 * radio frequency, 5 IP, 6 KNX IoT (ETS 6).
 */
public class MediumFormat extends AbstractConfigurationValueProvider<TLClassifier> {

	private static final String PREFIX = "MT-";

	/**
	 * Creates a {@link MediumFormat}.
	 */
	public MediumFormat() {
		super(TLClassifier.class);
	}

	@Override
	protected TLClassifier getValueNonEmpty(String propertyName, CharSequence propertyValue)
			throws ConfigurationException {
		String id = propertyValue.toString().trim();
		String name = switch (id) {
			case PREFIX + "0" -> "TP";
			case PREFIX + "1" -> "PL";
			case PREFIX + "2" -> "RF";
			case PREFIX + "5" -> "IP";
			case PREFIX + "6" -> "IoT";
			default -> null;
		};
		if (name == null) {
			throw new ConfigurationException(I18NConstants.ERROR_UNKNOWN_MEDIUM__VALUE.fill(id), propertyName,
				propertyValue);
		}
		return medium().getClassifier(name);
	}

	@Override
	protected String getSpecificationNonNull(TLClassifier configValue) {
		return PREFIX + switch (configValue.getName()) {
			case "PL" -> "1";
			case "RF" -> "2";
			case "IP" -> "5";
			case "IoT" -> "6";
			default -> "0";
		};
	}

	private static TLEnumeration medium() {
		return (TLEnumeration) TLModelUtil.findType("ehome.common:Medium");
	}

}
