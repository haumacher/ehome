/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.Collection;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.FillTextParameter;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.ModuleParts;

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
			String title = renamed(device, channel);
			String label = title != null ? fill(device, numbered(channel, title), channel.getTextParameter())
				: fill(device, (String) channel.tValueByName("displayName"), channel.getTextParameter());
			return withInstance(device, channel, label);
		}
		if (part instanceof ParameterGroup group) {
			String title = renamed(device, group);
			return fill(device, title != null ? title : (String) group.tValueByName("displayName"),
				group.getTextParameter());
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
	 * The title a rename in effect gives the given block or channel in the device (haumacher/ehome
	 * #122), <code>null</code> if none does.
	 */
	private static String renamed(TLObject device, ConfigFragment part) {
		if (device == null || ModuleParts.template(part).getRenames().isEmpty()) {
			return null;
		}
		ResKey title = new DeviceConfiguration(device).getRename(part);
		return title == null ? null : Resources.getInstance().getString(title);
	}

	private static String numbered(Channel channel, String title) {
		Integer number = channel.getNumber();
		return number == null ? title : number + ": " + title;
	}

	/**
	 * The label of a channel with what the project says of it in the device (ETS 6: ChannelInstance,
	 * haumacher/ehome #122): "1: Kanal A – Binäreingang Küchentür", with "(inactive)" for a channel the
	 * project deactivated.
	 */
	private static String withInstance(TLObject device, Channel channel, String label) {
		TLObject instance = channelInstance(device, channel);
		if (instance == null) {
			return label;
		}
		String result = label;
		Object description = instance.tValueByName("description");
		if (description != null && !description.toString().isBlank()) {
			result = Resources.getInstance().getString(
				I18NConstants.CHANNEL_DESCRIPTION__CHANNEL_DESCRIPTION.fill(label, description.toString().trim()));
		}
		if (Boolean.FALSE.equals(instance.tValueByName("active"))) {
			result = Resources.getInstance().getString(I18NConstants.CHANNEL_INACTIVE__CHANNEL.fill(result));
		}
		return result;
	}

	/**
	 * What the project says of the given channel (a channel of the program or of a module instance) in
	 * the device (<code>ehome.physical:ChannelInstance</code>), <code>null</code> if nothing.
	 */
	public static TLObject channelInstance(TLObject device, Channel channel) {
		if (device == null || device.tType().getPart("channelInstances") == null) {
			return null;
		}
		Collection<?> instances = (Collection<?>) device.tValueByName("channelInstances");
		if (instances.isEmpty()) {
			return null;
		}
		String id = ModuleParts.partId(channel);
		Program program = ModuleParts.programOf(channel);
		String programId = program == null ? null : program.getExtId();
		for (Object element : instances) {
			TLObject instance = (TLObject) element;
			Object ref = instance.tValueByName("channel");
			if (ref == null) {
				continue;
			}
			String full = ref.toString().trim();
			if (!full.startsWith("M-") && programId != null) {
				// ETS 6 names the channel relative to the program.
				full = programId + "_" + full;
			}
			if (full.equals(id)) {
				return instance;
			}
		}
		return null;
	}

	/**
	 * The given text with <code>{{0}}</code> filled with the device's value of the given text
	 * parameter (none: the placeholder's default, else nothing).
	 */
	public static String fill(TLObject device, String text, ParameterView textParameter) {
		if (text == null || !text.contains("{{")) {
			return text;
		}
		Object value = device == null || textParameter == null ? null : DeviceSettings.effectiveValue(device, textParameter);
		return FillTextParameter.fill(text, value);
	}

}
