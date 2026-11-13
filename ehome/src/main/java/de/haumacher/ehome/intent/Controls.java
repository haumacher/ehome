/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.intent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.template.Presets;

/**
 * The functions and controls of the intent of a project (haumacher/ehome #147): the choices the
 * functions view offers, the creation of a control, and the preview of the derivation
 * ({@link Derivation}) as the dialog shows it.
 *
 * <p>
 * Not access checked (Java): the commands check <code>Write</code> on the function (a new control)
 * and <code>Read</code> respectively <code>Write</code> on the project (preview, applying).
 * </p>
 */
public final class Controls {

	private static final String CONTROL = "ehome.runtime:Control";

	private Controls() {
		// Static only.
	}

	/**
	 * The actuator presets offered on the channel of the function's device, see
	 * {@link Presets#offered(TLObject, String)}.
	 */
	public static List<TLObject> functionPresets(TLObject function) {
		List<TLObject> result = new ArrayList<>();
		Object device = function == null ? null : function.tValueByName("device");
		Object channel = function == null ? null : function.tValueByName("channel");
		if (device instanceof TLObject actuator && channel != null) {
			for (TLObject preset : Presets.offered(actuator, channel.toString())) {
				if (!Presets.isSourcePreset(preset)) {
					result.add(preset);
				}
			}
		}
		return result;
	}

	/**
	 * The source presets offered on the given channel of the device.
	 */
	public static List<TLObject> sourcePresets(TLObject device, String channel) {
		List<TLObject> result = new ArrayList<>();
		if (device == null || channel == null) {
			return result;
		}
		for (TLObject preset : Presets.offered(device, channel)) {
			if (Presets.isSourcePreset(preset)) {
				result.add(preset);
			}
		}
		return result;
	}

	/**
	 * The devices of the function's project a control can operate it from: those with an application
	 * program, by their names.
	 */
	public static List<TLObject> sourceDevices(TLObject function) {
		List<TLObject> result = new ArrayList<>();
		TLObject project = function == null ? null : ExtIds.ancestor(function, "ehome.project:Project");
		if (project == null) {
			return result;
		}
		for (TLObject device : ExtIds.members(project, ExtIds.Kind.DEVICE)) {
			if (DeviceConfiguration.program(device) != null) {
				result.add(device);
			}
		}
		result.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(GroupAddressConnections.deviceLabel(a),
			GroupAddressConnections.deviceLabel(b)));
		return result;
	}

	/**
	 * Adds a control to the function; must run in a transaction.
	 *
	 * @param device
	 *        The device of the source.
	 * @param channel
	 *        The key of the channel of the device ({@link Presets#channels(TLObject)}).
	 * @param preset
	 *        The source preset, <code>null</code> for none (the derivation then refuses the control,
	 *        saying so).
	 * @param operation
	 *        <code>ehome.template:SourceOperation</code>.
	 * @return The new control.
	 * @throws TopLogicException
	 *         If the device, the channel or the operation is missing, or the device is not of the
	 *         function's project.
	 */
	public static TLObject create(TLObject function, TLObject device, String channel, TLObject preset,
			TLObject operation) {
		if (function == null || device == null || channel == null || operation == null) {
			throw new TopLogicException(I18NConstants.ERROR_CONTROL_INCOMPLETE);
		}
		if (ExtIds.ancestor(device, "ehome.project:Project") != ExtIds.ancestor(function, "ehome.project:Project")
			|| !Presets.channels(device).contains(channel)) {
			throw new TopLogicException(I18NConstants.ERROR_CONTROL_NO_SOURCE);
		}
		TLObject control = ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(CONTROL));
		control.tUpdateByName("device", device);
		control.tUpdateByName("channel", channel);
		control.tUpdateByName("preset", preset);
		control.tUpdateByName("operation", operation);
		List<TLObject> controls = ExtIds.list(function, "controls");
		controls.add(control);
		function.tUpdateByName("controls", controls);
		return control;
	}

	/**
	 * Why the derivation refuses the function or control, in the user's language; <code>null</code> if
	 * it derives it.
	 */
	public static String problem(TLObject origin) {
		TLObject project = origin == null ? null : ExtIds.ancestor(origin, "ehome.project:Project");
		if (project == null) {
			return null;
		}
		ResKey problem = Derivation.of(project).problem(origin);
		return problem == null ? null : Resources.getInstance().getString(problem);
	}

	/**
	 * The request of the preview dialog of the derivation of the project: <code>project</code>,
	 * <code>items</code> (each with <code>action</code>, <code>what</code>, <code>object</code>,
	 * <code>detail</code> and <code>origin</code>), <code>refusals</code> (each with
	 * <code>origin</code> and <code>reason</code>), <code>fingerprint</code> (what applying confirms)
	 * and <code>empty</code> (nothing to change).
	 */
	public static Map<String, Object> preview(TLObject project) {
		Derivation plan = Derivation.plan(project);
		Resources resources = Resources.getInstance();
		List<Map<String, Object>> items = new ArrayList<>();
		for (Derivation.Item item : plan.getItems()) {
			Map<String, Object> row = new HashMap<>();
			row.put("action", resources.getString(item.action().label()));
			row.put("what", resources.getString(item.what().label()));
			row.put("object", item.object());
			row.put("detail", item.detail());
			row.put("origin", item.originLabel());
			items.add(row);
		}
		List<Map<String, Object>> refusals = new ArrayList<>();
		for (Derivation.Refusal refusal : plan.getRefusals()) {
			Map<String, Object> row = new HashMap<>();
			row.put("origin", refusal.label());
			row.put("reason", resources.getString(refusal.reason()));
			refusals.add(row);
		}
		Map<String, Object> result = new HashMap<>();
		result.put("project", project);
		result.put("items", items);
		result.put("refusals", refusals);
		result.put("fingerprint", plan.fingerprint());
		result.put("empty", Boolean.valueOf(plan.isEmpty()));
		return result;
	}

	/**
	 * Applies the derivation previewed in the given request ({@link #preview(TLObject)}); must run in
	 * a transaction.
	 *
	 * @return What was done, for the user.
	 */
	@SuppressWarnings("unchecked")
	public static ResKey apply(Map<?, ?> request) {
		TLObject project = (TLObject) request.get("project");
		List<String> fingerprint = (List<String>) request.get("fingerprint");
		Derivation plan = Derivation.apply(project, fingerprint);
		int changes = 0;
		for (Derivation.Item item : plan.getItems()) {
			if (item.action() != Derivation.Action.KEEP) {
				changes++;
			}
		}
		return changes == 0 ? I18NConstants.NOTHING_DERIVED : I18NConstants.DERIVED__COUNT.fill(Integer.valueOf(changes));
	}

}
