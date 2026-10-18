/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ApplicationDatapoints;

/**
 * Imports the devices of a controller's Zigbee export ({@link ZigbeeExport}) into a project
 * (haumacher/ehome #40): <code>ehome.zigbee:ZigbeeDevice</code> with its endpoints and functions in
 * the project's <code>zigbeeDevices</code>.
 *
 * <p>
 * The IEEE address identifies a device, the endpoint number an endpoint of it and the endpoint with
 * the function's key a function: importing an export again updates the known objects in place and
 * creates only what is new, so the same file imported twice changes nothing. The effective reporting
 * of a function goes to its <code>reportedMin</code>, <code>reportedMax</code> and
 * <code>reportedChange</code> (empty where the export gives none); the installer's override
 * (<code>reportingMin</code>, ...) is never touched. What the user adds is kept: the device's name (set from the controller's label, else manufacturer and model, only for a
 * new device), its room and the datapoints bound to its functions. An endpoint the export no longer
 * lists is removed; a function only if no datapoint is bound to it, else it is kept and marked as
 * absent (a note says so). A device the export does not list stays (it may have been paired with
 * another controller); the user deletes it.
 * </p>
 */
public final class ZigbeeImport {

	/**
	 * The outcome of an import.
	 *
	 * @param devices
	 *        The devices of the export, in its order.
	 * @param created
	 *        How many of them were new.
	 * @param functions
	 *        The number of functions of the export.
	 * @param notes
	 *        What the import did besides, for the user.
	 */
	public record Result(List<TLObject> devices, int created, int functions, List<String> notes) {

		/**
		 * The sentence saying what was imported, followed by the notes, one per line.
		 */
		public String summary() {
			Resources resources = Resources.getInstance();
			StringBuilder result = new StringBuilder(resources.getString(
				I18NConstants.IMPORTED__DEVICES_CREATED_UPDATED_FUNCTIONS.fill(Integer.valueOf(devices.size()),
					Integer.valueOf(created), Integer.valueOf(devices.size() - created), Integer.valueOf(functions))));
			for (String note : notes) {
				result.append('\n').append(note);
			}
			return result.toString();
		}
	}

	private final List<String> _notes = new ArrayList<>();

	private ZigbeeImport() {
		// See importExport().
	}

	/**
	 * Imports the uploaded export file into the project; must run in a transaction.
	 *
	 * @return The sentence saying what was imported, followed by the notes.
	 * @throws TopLogicException
	 *         If the file is no export E-Home reads, with the reason.
	 */
	public static String importFile(TLObject project, Object file) {
		if (!(file instanceof BinaryData data)) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_NO_FILE);
		}
		String text;
		try (InputStream in = data.getStream()) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_UNREADABLE__REASON.fill(ex.getMessage()), ex);
		}
		return importExport(project, ZigbeeExport.parse(text)).summary();
	}

	/**
	 * Imports the export into the project; must run in a transaction.
	 */
	public static Result importExport(TLObject project, ZigbeeExport.Export export) {
		return new ZigbeeImport().run(project, export);
	}

	private Result run(TLObject project, ZigbeeExport.Export export) {
		List<TLObject> known = devices(project);
		Map<String, TLObject> byIeee = new HashMap<>();
		for (TLObject device : known) {
			if (device.tValueByName("ieee") instanceof String ieee) {
				byIeee.putIfAbsent(ieee, device);
			}
		}
		List<TLObject> result = new ArrayList<>();
		List<TLObject> all = new ArrayList<>(known);
		int created = 0;
		int functions = 0;
		for (ZigbeeExport.Device device : export.devices()) {
			TLObject obj = byIeee.get(device.ieee());
			if (obj == null) {
				obj = create("ZigbeeDevice");
				obj.tUpdateByName("ieee", device.ieee());
				obj.tUpdateByName("name", initialName(device));
				byIeee.put(device.ieee(), obj);
				all.add(obj);
				created++;
			}
			set(obj, "manufacturer", device.manufacturer());
			set(obj, "model", device.model());
			set(obj, "label", device.label());
			set(obj, "interviewed", Boolean.valueOf(device.interviewed()));
			set(obj, "controllerName", export.controller());
			endpoints(obj, device.endpoints());
			functions(obj, device.functions());
			functions += device.functions().size();
			result.add(obj);
		}
		if (all.size() != known.size()) {
			project.tUpdateByName("zigbeeDevices", all);
		}
		return new Result(result, created, functions, _notes);
	}

	private static String initialName(ZigbeeExport.Device device) {
		if (device.label() != null) {
			return device.label();
		}
		String name = (Objects.toString(device.manufacturer(), "") + " " + Objects.toString(device.model(), "")).trim();
		return name.isEmpty() ? device.ieee() : name;
	}

	private static void endpoints(TLObject device, List<ZigbeeExport.Endpoint> endpoints) {
		Map<Integer, TLObject> byId = new HashMap<>();
		List<TLObject> before = objects(device.tValueByName("endpoints"));
		for (TLObject endpoint : before) {
			if (endpoint.tValueByName("endpoint") instanceof Number id) {
				byId.putIfAbsent(Integer.valueOf(id.intValue()), endpoint);
			}
		}
		List<TLObject> after = new ArrayList<>();
		for (ZigbeeExport.Endpoint endpoint : endpoints) {
			TLObject obj = byId.remove(Integer.valueOf(endpoint.id()));
			if (obj == null) {
				obj = create("ZigbeeEndpoint");
				obj.tUpdateByName("endpoint", Integer.valueOf(endpoint.id()));
			}
			set(obj, "profile", endpoint.profile());
			set(obj, "deviceType", endpoint.device());
			set(obj, "inputClusters", clusters(endpoint.in()));
			set(obj, "outputClusters", clusters(endpoint.out()));
			set(obj, "zoneType", endpoint.zoneType());
			after.add(obj);
		}
		if (!after.equals(before)) {
			device.tUpdateByName("endpoints", after);
		}
		for (TLObject removed : byId.values()) {
			removed.tDelete();
		}
	}

	private void functions(TLObject device, List<ZigbeeExport.Function> functions) {
		Map<String, TLObject> byKey = new HashMap<>();
		List<TLObject> before = objects(device.tValueByName("functions"));
		for (TLObject function : before) {
			byKey.putIfAbsent(key(function.tValueByName("endpoint"), function.tValueByName("function")), function);
		}
		List<TLObject> after = new ArrayList<>();
		for (ZigbeeExport.Function function : functions) {
			TLObject obj = byKey.remove(key(Integer.valueOf(function.endpoint()), function.function()));
			if (obj == null) {
				obj = create("ZigbeeFunction");
				obj.tUpdateByName("function", function.function());
				obj.tUpdateByName("endpoint", Integer.valueOf(function.endpoint()));
			}
			set(obj, "cluster", Integer.valueOf(function.cluster()));
			set(obj, "attribute", function.attribute());
			set(obj, "valueType", valueType(function.type()));
			set(obj, "unit", function.unit());
			set(obj, "writable", Boolean.valueOf(function.writable()));
			set(obj, "genericName", function.name());
			set(obj, "absent", null);
			ZigbeeExport.Reporting reporting = function.reporting();
			set(obj, "reportedMin", reporting == null ? null : reporting.min());
			set(obj, "reportedMax", reporting == null ? null : reporting.max());
			set(obj, "reportedChange", reporting == null ? null : reporting.change());
			after.add(obj);
		}
		String deviceName = String.valueOf(device.tValueByName("name"));
		Resources resources = Resources.getInstance();
		List<TLObject> removed = new ArrayList<>();
		for (TLObject function : before) {
			if (!byKey.containsValue(function)) {
				continue;
			}
			String label = function.tValueByName("function") + " (" + function.tValueByName("endpoint") + ")";
			List<TLObject> bound = ZigbeeBindings.boundDatapoints(function);
			if (bound.isEmpty()) {
				removed.add(function);
				_notes.add(resources.getString(I18NConstants.NOTE_FUNCTION_REMOVED__FUNCTION_DEVICE.fill(label, deviceName)));
			} else {
				set(function, "absent", Boolean.TRUE);
				after.add(function);
				_notes.add(resources.getString(I18NConstants.NOTE_FUNCTION_ABSENT__FUNCTION_DEVICE_DATAPOINT.fill(label,
					deviceName, bound.stream().map(ApplicationDatapoints::id).collect(Collectors.joining(", ")))));
			}
		}
		if (!after.equals(before)) {
			device.tUpdateByName("functions", after);
		}
		for (TLObject function : removed) {
			function.tDelete();
		}
	}

	private static String key(Object endpoint, Object function) {
		return (endpoint instanceof Number number ? number.intValue() : -1) + "/" + function;
	}

	private static String clusters(List<Long> clusters) {
		return clusters.isEmpty() ? null
			: clusters.stream().map(String::valueOf).collect(Collectors.joining(", "));
	}

	/**
	 * Sets the attribute only where its value changes, so an import of the same export writes nothing.
	 */
	private static void set(TLObject obj, String attribute, Object value) {
		if (!Objects.equals(normalized(obj.tValueByName(attribute)), normalized(value))) {
			obj.tUpdateByName(attribute, value);
		}
	}

	private static Object normalized(Object value) {
		if (value instanceof Double || value instanceof Float) {
			return Double.valueOf(((Number) value).doubleValue());
		}
		if (value instanceof Number number) {
			return Long.valueOf(number.longValue());
		}
		// An unset flag reads as null on a transient object and as false on a stored one (TL #29883).
		return Boolean.FALSE.equals(value) ? null : value;
	}

	/**
	 * The Zigbee devices of the project, in their order.
	 */
	public static List<TLObject> devices(TLObject project) {
		return objects(project.tValueByName("zigbeeDevices"));
	}

	static List<TLObject> objects(Object value) {
		List<TLObject> result = new ArrayList<>();
		if (value instanceof Collection<?> list) {
			for (Object element : list) {
				if (element instanceof TLObject obj) {
					result.add(obj);
				}
			}
		}
		return result;
	}

	static TLObject valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType("ehome.zigbee:" + type));
	}

}
