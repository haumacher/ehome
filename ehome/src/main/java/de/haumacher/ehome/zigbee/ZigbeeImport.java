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
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.zigbee.EhomeZigbeeFactory;
import de.haumacher.ehome.model.zigbee.ZigbeeDevice;
import de.haumacher.ehome.model.zigbee.ZigbeeEndpoint;
import de.haumacher.ehome.model.zigbee.ZigbeeFunction;
import de.haumacher.ehome.runtime.DatapointNames;

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
		Project typedProject = (Project) project;
		List<ZigbeeDevice> known = new ArrayList<>(typedProject.getZigbeeDevices());
		Map<String, ZigbeeDevice> byIeee = new HashMap<>();
		for (ZigbeeDevice device : known) {
			if (device.getIeee() instanceof String ieee) {
				byIeee.putIfAbsent(ieee, device);
			}
		}
		List<TLObject> result = new ArrayList<>();
		List<ZigbeeDevice> all = new ArrayList<>(known);
		int created = 0;
		int functions = 0;
		for (ZigbeeExport.Device device : export.devices()) {
			ZigbeeDevice obj = byIeee.get(device.ieee());
			if (obj == null) {
				obj = (ZigbeeDevice) create("ZigbeeDevice");
				obj.setIeee(device.ieee());
				obj.setName(initialName(device));
				byIeee.put(device.ieee(), obj);
				all.add(obj);
				created++;
			}
			set(obj.getManufacturer(), device.manufacturer(), obj::setManufacturer);
			set(obj.getModel(), device.model(), obj::setModel);
			set(obj.getLabel(), device.label(), obj::setLabel);
			set(obj.getInterviewedOrNull(), Boolean.valueOf(device.interviewed()), obj::setInterviewed);
			set(obj.getControllerName(), export.controller(), obj::setControllerName);
			endpoints(obj, device.endpoints());
			functions(obj, device.functions());
			functions += device.functions().size();
			result.add(obj);
		}
		if (all.size() != known.size()) {
			typedProject.setZigbeeDevices(all);
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

	private static void endpoints(ZigbeeDevice device, List<ZigbeeExport.Endpoint> endpoints) {
		Map<Integer, ZigbeeEndpoint> byId = new HashMap<>();
		List<ZigbeeEndpoint> before = new ArrayList<>(device.getEndpoints());
		for (ZigbeeEndpoint endpoint : before) {
			if (endpoint.getEndpointOrNull() instanceof Number id) {
				byId.putIfAbsent(Integer.valueOf(id.intValue()), endpoint);
			}
		}
		List<ZigbeeEndpoint> after = new ArrayList<>();
		for (ZigbeeExport.Endpoint endpoint : endpoints) {
			ZigbeeEndpoint obj = byId.remove(Integer.valueOf(endpoint.id()));
			if (obj == null) {
				obj = (ZigbeeEndpoint) create("ZigbeeEndpoint");
				obj.setEndpoint(endpoint.id());
			}
			set(obj.getProfile(), endpoint.profile(), obj::setProfile);
			set(obj.getDeviceType(), endpoint.device(), obj::setDeviceType);
			set(obj.getInputClusters(), clusters(endpoint.in()), obj::setInputClusters);
			set(obj.getOutputClusters(), clusters(endpoint.out()), obj::setOutputClusters);
			set(obj.getZoneType(), endpoint.zoneType(), obj::setZoneType);
			after.add(obj);
		}
		if (!after.equals(before)) {
			device.setEndpoints(after);
		}
		for (ZigbeeEndpoint removed : byId.values()) {
			removed.tDelete();
		}
	}

	private void functions(ZigbeeDevice device, List<ZigbeeExport.Function> functions) {
		Map<String, ZigbeeFunction> byKey = new HashMap<>();
		List<ZigbeeFunction> before = new ArrayList<>(device.getFunctions());
		for (ZigbeeFunction function : before) {
			byKey.putIfAbsent(key(function.getEndpointOrNull(), function.getFunction()), function);
		}
		List<ZigbeeFunction> after = new ArrayList<>();
		for (ZigbeeExport.Function function : functions) {
			ZigbeeFunction obj = byKey.remove(key(Integer.valueOf(function.endpoint()), function.function()));
			if (obj == null) {
				obj = (ZigbeeFunction) create("ZigbeeFunction");
				obj.setFunction(function.function());
				obj.setEndpoint(function.endpoint());
			}
			set(obj.getCluster(), Integer.valueOf(function.cluster()), obj::setCluster);
			set(obj.getAttribute(), function.attribute(), obj::setAttribute);
			set(obj.getValueType(), valueType(function.type()), obj::setValueType);
			set(obj.getUnit(), function.unit(), obj::setUnit);
			set(obj.getWritableOrNull(), Boolean.valueOf(function.writable()), obj::setWritable);
			set(obj.getGenericName(), function.name(), obj::setGenericName);
			clearAbsent(obj);
			ZigbeeExport.Reporting reporting = function.reporting();
			set(obj.getReportedMin(), reporting == null ? null : reporting.min(), obj::setReportedMin);
			set(obj.getReportedMax(), reporting == null ? null : reporting.max(), obj::setReportedMax);
			set(obj.getReportedChange(), reporting == null ? null : reporting.change(), obj::setReportedChange);
			after.add(obj);
		}
		String deviceName = String.valueOf(device.getName());
		Resources resources = Resources.getInstance();
		List<ZigbeeFunction> removed = new ArrayList<>();
		for (ZigbeeFunction function : before) {
			if (!byKey.containsValue(function)) {
				continue;
			}
			String label = function.getFunction() + " (" + function.getEndpointOrNull() + ")";
			List<TLObject> bound = ZigbeeBindings.boundDatapoints(function);
			if (bound.isEmpty()) {
				removed.add(function);
				_notes.add(resources.getString(I18NConstants.NOTE_FUNCTION_REMOVED__FUNCTION_DEVICE.fill(label, deviceName)));
			} else {
				set(function.getAbsentOrNull(), Boolean.TRUE, function::setAbsent);
				after.add(function);
				_notes.add(resources.getString(I18NConstants.NOTE_FUNCTION_ABSENT__FUNCTION_DEVICE_DATAPOINT.fill(label,
					deviceName, bound.stream().map(DatapointNames::name).collect(Collectors.joining(", ")))));
			}
		}
		if (!after.equals(before)) {
			device.setFunctions(after);
		}
		for (ZigbeeFunction function : removed) {
			function.tDelete();
		}
	}

	/**
	 * Clears the absence mark of a function the export lists again (an unset flag reads as false, see
	 * {@link #normalized(Object)}): writes no value, as an import of the same export writes nothing.
	 */
	private static void clearAbsent(ZigbeeFunction function) {
		if (Boolean.TRUE.equals(function.getAbsentOrNull())) {
			function.tUpdate(EhomeZigbeeFactory.getAbsentZigbeeFunctionAttr(), null);
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
	private static <T> void set(Object current, T value, Consumer<? super T> setter) {
		if (!Objects.equals(normalized(current), normalized(value))) {
			setter.accept(value);
		}
	}

	private static Object normalized(Object value) {
		if (value instanceof Double || value instanceof Float) {
			return Double.valueOf(((Number) value).doubleValue());
		}
		if (value instanceof Number number) {
			return Long.valueOf(number.longValue());
		}
		// An unset flag reads as false (stored and transient alike): a flag the export leaves out (null) is false.
		return Boolean.FALSE.equals(value) ? null : value;
	}

	/**
	 * The Zigbee devices of the project, in their order.
	 */
	public static List<TLObject> devices(TLObject project) {
		return new ArrayList<>(((Project) project).getZigbeeDevices());
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

	static TLClassifier valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType("ehome.zigbee:" + type));
	}

}
