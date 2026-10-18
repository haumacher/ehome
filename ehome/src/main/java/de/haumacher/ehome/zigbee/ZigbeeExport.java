/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;
import de.haumacher.ehome.runtime.ValueTypes;

/**
 * The device list a HomeControl controller exports (homecontrol <code>spec/zigbee.md</code>, "Export",
 * format <code>homecontrol-zigbee-export</code>, version 1): what the Zigbee page downloads as one file
 * and <code>GET /api/zigbee/export</code> serves in pages.
 *
 * <p>
 * {@link #parse(String)} reads the file and refuses, with the path and the reason, anything that is
 * not this format, a newer version, and members the import needs that are missing or malformed (an
 * IEEE address, an endpoint, a function's key, endpoint, cluster and value type). Members it does not
 * know are ignored, as the specification asks of a reader.
 * </p>
 */
public final class ZigbeeExport {

	/** The value of the member <code>format</code>. */
	public static final String FORMAT = "homecontrol-zigbee-export";

	/** The version this reader reads; a higher one counts incompatible changes. */
	public static final int VERSION = 1;

	/** The deepest nesting of the file. */
	private static final int MAX_NESTING = 16;

	/**
	 * An export.
	 *
	 * @param controller
	 *        The name of the controller that exported it, <code>null</code> if not given.
	 * @param exported
	 *        When it was exported, in seconds since the epoch; <code>null</code> if not given (a
	 *        controller without a synchronized clock).
	 * @param network
	 *        The controller's Zigbee network at the time of the export, <code>null</code> if not given.
	 * @param devices
	 *        Its devices.
	 */
	public record Export(String controller, Long exported, Network network, List<Device> devices) {
		// A record.
	}

	/**
	 * The Zigbee network of the controller at the time of an export (<code>network</code>). Each
	 * member is <code>null</code> when the export does not give it.
	 *
	 * @param up
	 *        Whether the network is formed.
	 * @param panId
	 *        The PAN ID (16 bits).
	 * @param channel
	 *        The radio channel (11..26).
	 * @param extendedPanId
	 *        The extended PAN ID, 16 hex digits.
	 * @param permitJoinSeconds
	 *        For how many seconds after the export the network accepts joining devices (0: closed).
	 */
	public record Network(Boolean up, Integer panId, Integer channel, String extendedPanId,
			Integer permitJoinSeconds) {
		// A record.
	}

	/**
	 * A device of an export.
	 *
	 * @param ieee
	 *        The IEEE address, normalized to 16 lower-case hex digits.
	 */
	public record Device(String ieee, String manufacturer, String model, String label, boolean interviewed,
			List<Endpoint> endpoints, List<Function> functions) {
		// A record.
	}

	/**
	 * An endpoint of a device: its simple descriptor.
	 *
	 * @param zoneType
	 *        The zone type of an IAS zone, <code>null</code> for another endpoint.
	 */
	public record Endpoint(int id, Integer profile, Integer device, List<Long> in, List<Long> out, Integer zoneType) {
		// A record.
	}

	/**
	 * A function the controller derives from an endpoint.
	 *
	 * @param name
	 *        The generic datapoint name.
	 * @param function
	 *        The key of the function table.
	 * @param attribute
	 *        The attribute, <code>null</code> for a client binding (<code>action</code>).
	 * @param type
	 *        The value type the function carries.
	 * @param unit
	 *        The unit, <code>null</code> for none.
	 */
	public record Function(String name, String function, int endpoint, int cluster, Integer attribute, String type,
			String unit, boolean writable) {
		// A record.
	}

	private final StringBuilder _path = new StringBuilder();

	private ZigbeeExport() {
		// See parse().
	}

	/**
	 * Reads an export from its JSON text.
	 *
	 * @throws TopLogicException
	 *         If the text is no export E-Home reads, with the reason.
	 */
	public static Export parse(String text) {
		Object root;
		try {
			root = Json.parse(text, MAX_NESTING);
		} catch (JsonException ex) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_NO_JSON__REASON.fill(
				ex.getMessage() + " at character " + ex.getOffset()));
		}
		return read(root);
	}

	/**
	 * Reads an export from its JSON objects (maps and lists, as {@link Json#parse(String, int)} gives
	 * them).
	 *
	 * @throws TopLogicException
	 *         If the object is no export E-Home reads, with the reason.
	 */
	public static Export read(Object root) {
		return new ZigbeeExport().export(root);
	}

	private Export export(Object root) {
		if (!(root instanceof Map<?, ?> map)) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_FORMAT__FORMAT.fill(""));
		}
		Object format = map.get("format");
		if (!FORMAT.equals(format)) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_FORMAT__FORMAT.fill(format == null ? "" : format));
		}
		Object version = map.get("version");
		if (!(version instanceof Long number) || number.longValue() < 1) {
			throw invalid("version", "the version must be a positive integer");
		}
		if (number.longValue() > VERSION) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_VERSION__VERSION_SUPPORTED.fill(number,
				Integer.valueOf(VERSION)));
		}
		String controller = map.get("controller") instanceof String name && !name.isBlank() ? name.trim() : null;
		if (!(map.get("devices") instanceof List<?> list)) {
			throw invalid("devices", "devices must be an array");
		}
		List<Device> devices = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			enter("devices[" + i + "]");
			devices.add(device(list.get(i)));
			leave();
		}
		Long exported = map.get("exported") instanceof Long seconds && seconds.longValue() > 0 ? seconds : null;
		return new Export(controller, exported, network(map.get("network")), devices);
	}

	/**
	 * The network of the export; members of another type than the specification's are taken as not
	 * given, since the import does not need them.
	 */
	private static Network network(Object item) {
		if (!(item instanceof Map<?, ?> network)) {
			return null;
		}
		return new Network(network.get("up") instanceof Boolean up ? up : null, optionalInteger(network, "pan_id"),
			optionalInteger(network, "channel"), text(network, "extended_pan_id"),
			optionalInteger(network, "permit_join_s"));
	}

	private Device device(Object item) {
		if (!(item instanceof Map<?, ?> device)) {
			throw invalid("", "a device must be an object");
		}
		String ieee = device.get("ieee") instanceof String text ? ZigbeeFunctionTable.normalizeIeee(text) : null;
		if (ieee == null) {
			throw invalid(".ieee", "\"" + device.get("ieee")
				+ "\" is not an IEEE address (16 hex digits, colons allowed; not 0 and not all ones)");
		}
		List<Endpoint> endpoints = new ArrayList<>();
		if (device.containsKey("endpoints")) {
			if (!(device.get("endpoints") instanceof List<?> list)) {
				throw invalid(".endpoints", "endpoints must be an array");
			}
			for (int i = 0; i < list.size(); i++) {
				enter(".endpoints[" + i + "]");
				endpoints.add(endpoint(list.get(i)));
				leave();
			}
		}
		List<Function> functions = new ArrayList<>();
		if (device.containsKey("functions")) {
			if (!(device.get("functions") instanceof List<?> list)) {
				throw invalid(".functions", "functions must be an array");
			}
			for (int i = 0; i < list.size(); i++) {
				enter(".functions[" + i + "]");
				functions.add(function(list.get(i)));
				leave();
			}
		}
		return new Device(ieee, text(device, "manufacturer"), text(device, "model"), text(device, "label"),
			Boolean.TRUE.equals(device.get("interviewed")), endpoints, functions);
	}

	private Endpoint endpoint(Object item) {
		if (!(item instanceof Map<?, ?> endpoint)) {
			throw invalid("", "an endpoint must be an object");
		}
		int id = integer(endpoint, "id", 1, 240);
		return new Endpoint(id, optionalInteger(endpoint, "profile"), optionalInteger(endpoint, "device"),
			clusters(endpoint, "in"), clusters(endpoint, "out"), optionalInteger(endpoint, "zone_type"));
	}

	private Function function(Object item) {
		if (!(item instanceof Map<?, ?> function)) {
			throw invalid("", "a function must be an object");
		}
		String key = text(function, "function");
		if (key == null) {
			throw invalid(".function", "function is missing");
		}
		int endpoint = integer(function, "endpoint", 1, 240);
		int cluster = integer(function, "cluster", 0, 65535);
		Integer attribute = function.containsKey("attribute") ? Integer.valueOf(integer(function, "attribute", 0, 65535))
			: null;
		String type = text(function, "type");
		if (type == null || !ValueTypes.ALL.contains(type)) {
			throw invalid(".type", "\"" + function.get("type") + "\" is not a value type ("
				+ String.join(", ", ValueTypes.ALL) + ")");
		}
		return new Function(text(function, "name"), key, endpoint, cluster, attribute, type, text(function, "unit"),
			Boolean.TRUE.equals(function.get("writable")));
	}

	private int integer(Map<?, ?> object, String member, int min, int max) {
		if (!(object.get(member) instanceof Long value) || value.longValue() < min || value.longValue() > max) {
			throw invalid("." + member, member + " must be an integer " + min + ".." + max);
		}
		return value.intValue();
	}

	private static Integer optionalInteger(Map<?, ?> object, String member) {
		return object.get(member) instanceof Long value ? Integer.valueOf(value.intValue()) : null;
	}

	private List<Long> clusters(Map<?, ?> object, String member) {
		List<Long> result = new ArrayList<>();
		if (!object.containsKey(member)) {
			return result;
		}
		if (!(object.get(member) instanceof List<?> list)) {
			throw invalid("." + member, member + " must be an array of cluster numbers");
		}
		for (Object value : list) {
			if (!(value instanceof Long cluster)) {
				throw invalid("." + member, member + " must be an array of cluster numbers");
			}
			result.add(cluster);
		}
		return result;
	}

	private static String text(Map<?, ?> object, String member) {
		return object.get(member) instanceof String text && !text.isBlank() ? text.trim() : null;
	}

	private TopLogicException invalid(String segment, String reason) {
		return new TopLogicException(I18NConstants.ERROR_EXPORT_INVALID__PATH_REASON.fill(_path + segment, reason));
	}

	private final List<Integer> _marks = new ArrayList<>();

	private void enter(String segment) {
		_marks.add(Integer.valueOf(_path.length()));
		_path.append(segment);
	}

	private void leave() {
		_path.setLength(_marks.remove(_marks.size() - 1).intValue());
	}

}
