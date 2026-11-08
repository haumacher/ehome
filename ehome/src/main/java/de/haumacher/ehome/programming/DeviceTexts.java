/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.FillTextParameter;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;

/**
 * The texts a person reads for the parts of a device's configuration (haumacher/ehome #119): the
 * label of a channel, a parameter block, a communication object or a parameter, with the placeholder
 * <code>{{0}}</code> filled with the device's value of the part's text parameter (ETS:
 * <code>TextParameterRefId</code> of a channel, a parameter block or an object reference), and
 * <code>{{0:default}}</code> with its default where the value is empty or the part has no text
 * parameter (see {@link FillTextParameter#fill(String, Object)}). The arguments of a module
 * instance (<code>{{argument}}</code>) are in the part's texts already (it is the part in its
 * instance, see {@link de.haumacher.ehome.modules.ModuleParts}).
 */
public final class DeviceTexts {

	private DeviceTexts() {
		// Static only.
	}

	/**
	 * The label of the given part of the device's configuration (or of the device itself), as the
	 * editor shows it.
	 */
	public static String label(TLObject device, Object part) {
		if (part instanceof Channel channel) {
			return fill(device, (String) channel.tValueByName("displayName"), channel.getTextParameter());
		}
		if (part instanceof ParameterGroup group) {
			return fill(device, (String) group.tValueByName("displayName"), group.getTextParameter());
		}
		if (part instanceof Interface placed) {
			return fill(device, (String) placed.tValueByName("displayName"), placed.getTextParameter());
		}
		if (part instanceof ParameterView view) {
			return fill(device, view.getTitle(), null);
		}
		return MetaLabelProvider.INSTANCE.getLabel(part);
	}

	/**
	 * The given text with <code>{{0}}</code> filled with the device's value of the given text
	 * parameter (none: the placeholder's default, else nothing).
	 */
	public static String fill(TLObject device, String text, ParameterView textParameter) {
		if (text == null || !text.contains("{{")) {
			return text;
		}
		Object value = device == null || textParameter == null ? null : DeviceSettings.value(device, textParameter);
		return FillTextParameter.fill(text, value);
	}

}
