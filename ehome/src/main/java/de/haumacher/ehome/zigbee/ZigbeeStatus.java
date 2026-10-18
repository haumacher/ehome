/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.text.DateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.Deployer;

/**
 * What the Zigbee tab says about the devices and the network of a project's controller (haumacher/ehome
 * #64): the functions by their names, the state of each device, the network line with the permit
 * countdown, the time of the last read and the failure of the last live import; and the removal of
 * the functions the controller no longer exports.
 *
 * <p>
 * The live parts come from {@link ZigbeeLive} (held in memory), the rest from what the import stored:
 * the device's <code>interviewed</code> flag and the network facts on the controller
 * ({@link ZigbeeController#storeNetwork}). The countdown is computed when it is shown.
 * </p>
 */
public final class ZigbeeStatus {

	private static final String IAS_ZONE = "ias_zone";

	/** The names of the functions of the controller's table and of the IAS zone's datapoints. */
	private static final Map<String, ResKey> FUNCTION_LABELS = Map.ofEntries(
		Map.entry("on_off", I18NConstants.FUNCTION_ON_OFF),
		Map.entry("level", I18NConstants.FUNCTION_LEVEL),
		Map.entry("color_temperature", I18NConstants.FUNCTION_COLOR_TEMPERATURE),
		Map.entry("temperature", I18NConstants.FUNCTION_TEMPERATURE),
		Map.entry("humidity", I18NConstants.FUNCTION_HUMIDITY),
		Map.entry("illuminance", I18NConstants.FUNCTION_ILLUMINANCE),
		Map.entry("occupancy", I18NConstants.FUNCTION_OCCUPANCY),
		Map.entry(IAS_ZONE, I18NConstants.FUNCTION_IAS_ZONE),
		Map.entry("contact", I18NConstants.FUNCTION_CONTACT),
		Map.entry("motion", I18NConstants.FUNCTION_MOTION),
		Map.entry("smoke", I18NConstants.FUNCTION_SMOKE),
		Map.entry("water", I18NConstants.FUNCTION_WATER),
		Map.entry("alarm", I18NConstants.FUNCTION_ALARM),
		Map.entry("battery", I18NConstants.FUNCTION_BATTERY),
		Map.entry(ZigbeeFunctionTable.MULTISTATE, I18NConstants.FUNCTION_MULTISTATE),
		Map.entry(ZigbeeFunctionTable.ACTION, I18NConstants.FUNCTION_ACTION));

	private ZigbeeStatus() {
		// Static helpers.
	}

	/**
	 * The name of the function (<code>ehome.zigbee:ZigbeeFunction</code>) in the user's language: the
	 * name the device's type in the Zigbee catalog gives it ({@link ZigbeeCatalog#name(TLObject)}), else
	 * the name of its key, for an IAS zone the name of the datapoint its zone type gives (the last segment
	 * of the generic name: contact, motion, smoke, water, alarm). A key E-Home does not know (a newer
	 * controller's) is shown as it is.
	 */
	public static String functionLabel(TLObject function) {
		Object key = function.tValueByName("function");
		if (key == null) {
			return null;
		}
		ResKey catalogName = ZigbeeCatalog.name(function);
		if (catalogName != null) {
			return Resources.getInstance().getString(catalogName);
		}
		String name = key.toString();
		if (IAS_ZONE.equals(name) && function.tValueByName("genericName") instanceof String generic) {
			String suffix = generic.substring(generic.lastIndexOf('.') + 1);
			if (FUNCTION_LABELS.containsKey(suffix)) {
				name = suffix;
			}
		}
		return functionLabel(name);
	}

	/**
	 * The name of the function key in the user's language; the key itself for an unknown one.
	 */
	public static String functionLabel(String key) {
		ResKey label = FUNCTION_LABELS.get(key);
		return label == null ? key : Resources.getInstance().getString(label);
	}

	/**
	 * The state of the device (<code>ehome.zigbee:ZigbeeDevice</code>): what the events of its
	 * connected controller tell (interviewing, interview failed, left the network), else whether its
	 * interview finished as the last export said (paired, not interviewed).
	 */
	public static String deviceState(TLObject device) {
		ZigbeeLive.DeviceState live = null;
		TLObject project = device.tContainer();
		if (project != null && project.tValid()) {
			TLObject controller = Deployer.controller(project);
			live = ZigbeeLive.deviceState(ControllerConnections.controllerId(controller),
				(String) device.tValueByName("ieee"));
		}
		ResKey state;
		if (live != null) {
			switch (live) {
				case INTERVIEWING:
					state = I18NConstants.DEVICE_INTERVIEWING;
					break;
				case INTERVIEW_FAILED:
					state = I18NConstants.DEVICE_INTERVIEW_FAILED;
					break;
				default:
					state = I18NConstants.DEVICE_LEFT;
					break;
			}
		} else {
			state = Boolean.TRUE.equals(device.tValueByName("interviewed")) ? I18NConstants.DEVICE_PAIRED
				: I18NConstants.DEVICE_NOT_INTERVIEWED;
		}
		return Resources.getInstance().getString(state);
	}

	/**
	 * The network of the project's controller: PAN ID, channel and whether joining is open, with the
	 * seconds left. The permit time is the last one the connected controller named (event or read),
	 * else the stored one counted from the time of the export.
	 */
	public static String networkState(TLObject project, Instant now) {
		TLObject controller = Deployer.controller(project);
		Resources resources = Resources.getInstance();
		if (controller == null) {
			return resources.getString(I18NConstants.NETWORK_NO_CONTROLLER);
		}
		String controllerId = ControllerConnections.controllerId(controller);
		ZigbeeLive.Permit permit = ControllerConnections.get(controller) != null ? ZigbeeLive.permit(controllerId) : null;
		if (permit == null && controller.tValueByName("zigbeePermitJoin") instanceof Number seconds
			&& controller.tValueByName("zigbeeExported") instanceof Date exported) {
			permit = new ZigbeeLive.Permit(seconds.intValue(), exported.toInstant());
		}
		Object up = controller.tValueByName("zigbeeNetworkUp");
		if (up == null) {
			return resources.getString(I18NConstants.NETWORK_UNKNOWN);
		}
		if (!Boolean.TRUE.equals(up)) {
			return resources.getString(I18NConstants.NETWORK_DOWN);
		}
		String pan = controller.tValueByName("zigbeePanId") instanceof Number number
			? String.format("0x%04X", Integer.valueOf(number.intValue())) : "?";
		Object channel = controller.tValueByName("zigbeeChannel") instanceof Number number
			? Long.valueOf(number.longValue()) : "?";
		long remaining = permit == null ? 0 : permit.remaining(now);
		if (remaining > 0) {
			return resources.getString(
				I18NConstants.NETWORK_OPEN__PAN_CHANNEL_SECONDS.fill(pan, channel, Long.valueOf(remaining)));
		}
		return resources.getString(I18NConstants.NETWORK_CLOSED__PAN_CHANNEL.fill(pan, channel));
	}

	/**
	 * When the devices were last read from the project's controller, and whether live or on request;
	 * <code>null</code> before the first read.
	 */
	public static String readState(TLObject project) {
		TLObject controller = Deployer.controller(project);
		if (controller == null || !(controller.tValueByName("zigbeeExported") instanceof Date exported)) {
			return null;
		}
		ZigbeeLive.Read read = ZigbeeLive.lastRead(ControllerConnections.controllerId(controller));
		boolean live = read != null ? read.live() : Boolean.TRUE.equals(controller.tValueByName("zigbeeReadLive"));
		String time = format(read != null ? Date.from(read.at()) : exported);
		return Resources.getInstance().getString(
			live ? I18NConstants.READ_LIVE__TIME.fill(time) : I18NConstants.READ_ON_REQUEST__TIME.fill(time));
	}

	/**
	 * Why the last live import from the project's controller failed, <code>null</code> if it did not.
	 */
	public static String liveProblem(TLObject project) {
		TLObject controller = Deployer.controller(project);
		if (controller == null) {
			return null;
		}
		ZigbeeLive.Failure failure = ZigbeeLive.failure(ControllerConnections.controllerId(controller));
		if (failure == null) {
			return null;
		}
		Resources resources = Resources.getInstance();
		return resources.getString(I18NConstants.LIVE_IMPORT_FAILED__CONTROLLER_TIME_REASON.fill(
			controller.tValueByName("name"), format(Date.from(failure.at())), resources.getString(failure.reason())));
	}

	private static String format(Date date) {
		return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Resources.getInstance().getLocale())
			.format(date);
	}

	/**
	 * Why {@link #removeAbsent(TLObject)} has nothing to do for the device, <code>null</code> if it has.
	 */
	public static ResKey removeAbsentVeto(TLObject device) {
		for (TLObject function : ZigbeeImport.objects(device.tValueByName("functions"))) {
			if (Boolean.TRUE.equals(function.tValueByName("absent"))) {
				return null;
			}
		}
		return I18NConstants.VETO_NOTHING_ABSENT;
	}

	/**
	 * Deletes the functions of the device the last export no longer listed (<code>absent</code>) that
	 * no datapoint is bound to; the bound ones are kept. Must run in a transaction.
	 *
	 * @return The sentence saying how many were removed and which were kept, bound to what.
	 * @throws TopLogicException
	 *         If no function is absent.
	 */
	public static String removeAbsent(TLObject device) {
		ResKey veto = removeAbsentVeto(device);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		Resources resources = Resources.getInstance();
		List<TLObject> functions = ZigbeeImport.objects(device.tValueByName("functions"));
		List<TLObject> remaining = new ArrayList<>();
		List<TLObject> removed = new ArrayList<>();
		List<String> kept = new ArrayList<>();
		for (TLObject function : functions) {
			if (!Boolean.TRUE.equals(function.tValueByName("absent"))) {
				remaining.add(function);
				continue;
			}
			List<TLObject> bound = ZigbeeBindings.boundDatapoints(function);
			if (bound.isEmpty()) {
				removed.add(function);
			} else {
				remaining.add(function);
				kept.add(resources.getString(I18NConstants.ABSENT_KEPT__FUNCTION_DATAPOINTS.fill(name(function),
					bound.stream().map(ApplicationDatapoints::id).collect(Collectors.joining(", ")))));
			}
		}
		if (!removed.isEmpty()) {
			device.tUpdateByName("functions", remaining);
			for (TLObject function : removed) {
				function.tDelete();
			}
		}
		Integer count = Integer.valueOf(removed.size());
		if (kept.isEmpty()) {
			return resources.getString(I18NConstants.ABSENT_REMOVED__REMOVED.fill(count));
		}
		return resources.getString(I18NConstants.ABSENT_REMOVED__REMOVED_KEPT_DETAILS.fill(count,
			Integer.valueOf(kept.size()), String.join("; ", kept)));
	}

	private static String name(TLObject function) {
		Object generic = function.tValueByName("genericName");
		return generic != null ? generic.toString()
			: function.tValueByName("function") + " (" + function.tValueByName("endpoint") + ")";
	}

}
