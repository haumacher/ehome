/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.impl.TransientObjectFactory;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * The draft of the dialog saving a channel of a device as a preset (haumacher/ehome #146,
 * <code>projects/preset-save.view.xml</code>): a transient preset with a transient object for each
 * communication object of the channel in effect, whose roles the installer chooses, and a transient
 * value for each {@link Presets#candidates(TLObject, String) candidate}, of which the installer
 * chooses those that belong to the preset. Nothing is stored before
 * {@link #save(TLObject, Collection)}.
 */
public final class PresetDrafts {

	/** What a draft was made for. */
	private record Draft(TLObject device, String channel, Map<TLObject, ParameterView> values,
			Map<TLObject, Interface> objects) {
		// Value.
	}

	private static final Map<TLObject, Draft> DRAFTS = new WeakHashMap<>();

	private static final Map<TLObject, TLObject> OBJECT_DRAFTS = new WeakHashMap<>();

	private PresetDrafts() {
		// Static only.
	}

	/**
	 * The request of the save dialog for the given channel of the device: <code>device</code>,
	 * <code>channel</code> (the key), <code>channelName</code>, <code>draft</code> (the transient
	 * preset), <code>values</code> (the transient values offered), <code>proposed</code> (those
	 * proposed, see {@link Presets.Candidate#proposed()}) and <code>objects</code> (the transient
	 * objects of the draft).
	 */
	public static Map<String, Object> request(TLObject device, String channel) {
		List<Presets.Candidate> candidates = Presets.candidates(device, channel);
		TLObject root = DeviceTemplates.root();
		TLObject draft = transientObject("ehome.template:Preset", root);
		draft.tUpdateByName("channel", channel);
		draft.tUpdateByName("purpose", TLModelUtil.findPart("ehome.template:PresetPurpose#actuator"));
		Map<TLObject, ParameterView> values = new LinkedHashMap<>();
		List<TLObject> proposed = new ArrayList<>();
		for (Presets.Candidate candidate : candidates) {
			TLObject value = transientObject("ehome.template:PresetValue", root);
			ParameterView view = ModuleParts.template(candidate.view());
			value.tUpdateByName("parameter", view);
			ParameterType type = ParameterValues.valueType(view);
			value.tUpdateByName(ParameterValues.valueAttribute(type), candidate.value());
			values.put(value, candidate.view());
			if (candidate.proposed()) {
				proposed.add(value);
			}
		}
		Map<TLObject, Interface> objects = new LinkedHashMap<>();
		for (Interface placed : Presets.channelObjects(device, channel)) {
			TLObject object = transientObject("ehome.template:PresetObject", null);
			object.tUpdateByName("placement", ModuleParts.template(placed));
			objects.put(object, placed);
		}
		draft.tUpdateByName("objects", new ArrayList<>(objects.keySet()));
		synchronized (DRAFTS) {
			DRAFTS.put(draft, new Draft(device, channel, values, objects));
			for (TLObject object : objects.keySet()) {
				OBJECT_DRAFTS.put(object, draft);
			}
		}
		Map<String, Object> result = new HashMap<>();
		result.put("device", device);
		result.put("channel", channel);
		result.put("channelName", Presets.channelLabel(device, channel));
		result.put("draft", draft);
		result.put("values", new ArrayList<>(values.keySet()));
		result.put("proposed", proposed);
		result.put("objects", new ArrayList<>(objects.keySet()));
		return result;
	}

	/**
	 * The preset an object of a preset belongs to: its container, the draft for an object of a draft.
	 */
	public static TLObject preset(TLObject object) {
		if (object == null) {
			return null;
		}
		synchronized (DRAFTS) {
			TLObject draft = OBJECT_DRAFTS.get(object);
			if (draft != null) {
				return draft;
			}
		}
		return object.tContainer();
	}

	/**
	 * The communication objects an object of a preset may name: those of the channel of its preset
	 * (for a draft those of the channel in the device), as the preset names them.
	 */
	public static List<Interface> objectOptions(TLObject object) {
		TLObject preset = preset(object);
		if (preset == null) {
			return List.of();
		}
		Draft draft;
		synchronized (DRAFTS) {
			draft = DRAFTS.get(preset);
		}
		List<Interface> result = new ArrayList<>();
		if (draft != null) {
			for (Interface placed : Presets.channelObjects(draft.device(), draft.channel())) {
				result.add(ModuleParts.template(placed));
			}
			return result;
		}
		TLObject template = preset.tContainer();
		Object key = preset.tValueByName("channel");
		return template == null || key == null ? result : Presets.templateChannelObjects(template, key.toString());
	}

	/**
	 * The communication object of an object of a preset as the app labels it
	 * ({@link ChannelShape#objectLabel(TLObject, Interface)}): for a draft in the device it was made
	 * from, else with the program's texts; <code>null</code> for an object naming none.
	 */
	public static String objectLabel(TLObject object) {
		if (object == null) {
			return null;
		}
		TLObject preset = preset(object);
		Draft draft;
		synchronized (DRAFTS) {
			draft = preset == null ? null : DRAFTS.get(preset);
		}
		if (draft != null) {
			Interface placed = draft.objects().get(object);
			if (placed != null) {
				return ChannelShape.objectLabel(draft.device(), placed);
			}
		}
		return object.tValueByName("placement") instanceof Interface placed ? ChannelShape.objectLabel(null, placed)
			: null;
	}

	/**
	 * Whether the value of a draft lies in the channel's part of the structure.
	 */
	public static boolean inChannel(TLObject value) {
		Draft draft = draftOf(value);
		if (draft == null) {
			return false;
		}
		ParameterView view = draft.values().get(value);
		return ChannelShape.of(draft.device()).get(draft.channel()).contains(view);
	}

	/**
	 * Whether the value of a draft differs from the default of its parameter.
	 */
	public static boolean differs(TLObject value) {
		Draft draft = draftOf(value);
		return draft != null && DeviceSettings.setting(draft.device(),
			draft.values().get(value)) != null;
	}

	private static Draft draftOf(TLObject value) {
		synchronized (DRAFTS) {
			for (Draft draft : DRAFTS.values()) {
				if (draft.values().containsKey(value)) {
					return draft;
				}
			}
		}
		return null;
	}

	/**
	 * Saves the draft as a preset with the chosen values, see
	 * {@link Presets#save(TLObject, String, String, TLObject, TLObject, Collection, List, Map)}.
	 *
	 * @param chosen
	 *        The values of the draft's request that belong to the preset; a single one or a
	 *        collection.
	 * @return The new preset.
	 */
	public static TLObject save(TLObject draft, Object chosen) {
		Draft info;
		synchronized (DRAFTS) {
			info = draft == null ? null : DRAFTS.get(draft);
		}
		if (info == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PRESET);
		}
		List<Object> selected = new ArrayList<>();
		if (chosen instanceof Collection<?> collection) {
			selected.addAll(collection);
		} else if (chosen != null) {
			selected.add(chosen);
		}
		List<ParameterView> views = new ArrayList<>();
		for (Map.Entry<TLObject, ParameterView> entry : info.values().entrySet()) {
			if (selected.contains(entry.getKey())) {
				views.add(entry.getValue());
			}
		}
		Map<Interface, TLObject> roles = new LinkedHashMap<>();
		List<Interface> channelObjects = Presets.channelObjects(info.device(), info.channel());
		if (draft.tValueByName("objects") instanceof Collection<?> objects) {
			for (Object element : objects) {
				TLObject object = (TLObject) element;
				if (!(object.tValueByName("role") instanceof TLObject role)) {
					continue;
				}
				Object placement = object.tValueByName("placement");
				Interface placed = channelObjects.stream().filter(p -> ModuleParts.template(p) == placement).findFirst()
					.orElse(null);
				if (placed == null) {
					throw new TopLogicException(I18NConstants.ERROR_PRESET_FOREIGN_OBJECT__OBJECT.fill(placement));
				}
				roles.put(placed, role);
			}
		}
		Collection<?> operations = draft.tValueByName("operations") instanceof Collection<?> values ? values : List.of();
		TLObject result = Presets.save(info.device(), info.channel(), (String) draft.tValueByName("name"),
			(TLObject) draft.tValueByName("purpose"), (TLObject) draft.tValueByName("kind"), operations, views, roles);
		synchronized (DRAFTS) {
			DRAFTS.remove(draft);
		}
		return result;
	}

	private static TLObject transientObject(String type, TLObject context) {
		return TransientObjectFactory.INSTANCE.createObject((TLClass) TLModelUtil.findType(type), context);
	}

}
