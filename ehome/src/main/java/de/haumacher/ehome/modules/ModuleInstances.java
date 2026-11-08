/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.modules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.ModuleArgument;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;

/**
 * The module instances a device stores (<code>ehome.physical:Device#moduleInstances</code>,
 * haumacher/ehome #119): E-Home computes them from the device's program and values (the walk of
 * {@link DeviceConfiguration}) and stores each with its placement, repeat index and argument values.
 *
 * <p>
 * {@link #update(TLObject)} brings the stored instances in line with the computed ones: an instance
 * computed now is stored (created if new, its values updated) and marked in effect; a stored instance
 * no longer computed (a smaller count, a branch no longer taken) is kept, marked not in effect, with
 * its settings and connectors, which apply again when the instance is computed again.
 * </p>
 *
 * <p>
 * An argument of a stored instance may record the value a project file gave it where it differs from
 * E-Home's (<code>deviceValue</code>, rule 5 of the design): the device's memory was written with
 * another layout, and a download is refused until a full download the user confirms writes E-Home's
 * ({@link #differences(TLObject)}, {@link #clearDifferences(TLObject)}).
 * </p>
 */
public final class ModuleInstances {

	/** The attribute of a device holding its module instances. */
	public static final String MODULE_INSTANCES = "moduleInstances";

	/** The type of a stored module instance. */
	public static final String INSTANCE_TYPE = "ehome.physical:ModuleInstance";

	/** The type of an argument value of a stored module instance. */
	public static final String ARGUMENT_TYPE = "ehome.physical:InstanceArgument";

	/** The ID of an instance. */
	public static final String NAME = "name";

	/** The placement of an instance. */
	public static final String PLACEMENT = "placement";

	/** The instance of the enclosing module of a sub-module's instance. */
	public static final String PARENT = "parent";

	/** The repeat index of an instance. */
	public static final String REPEAT_INDEX = "repeatIndex";

	/** Whether an instance is in effect. */
	public static final String ACTIVE = "active";

	/** The argument values of an instance. */
	public static final String ARGUMENTS = "arguments";

	/** The argument of an argument value. */
	public static final String ARGUMENT = "argument";

	/** The value of an argument value. */
	public static final String VALUE = "value";

	/** The value a project file gave an argument, where it differs. */
	public static final String DEVICE_VALUE = "deviceValue";

	private ModuleInstances() {
		// Static helpers only.
	}

	/**
	 * Computes the module instances of the given device and stores them (see the class comment).
	 *
	 * @return Why the device's values give no valid layout of its modules (an allocator beyond its
	 *         limit), empty if they do.
	 */
	public static List<ResKey> update(TLObject device) {
		Program program = DeviceConfiguration.program(device);
		if (program == null || (program.getModules().isEmpty() && stored(device).isEmpty())) {
			return List.of();
		}
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		store(device, configuration.getInstances());
		return configuration.getModuleProblems();
	}

	/**
	 * Stores the given computed instances of the device (in this order, in effect), and marks the
	 * other stored ones not in effect.
	 */
	public static void store(TLObject device, List<InstanceInfo> computed) {
		Map<String, TLObject> existing = new LinkedHashMap<>();
		for (TLObject instance : stored(device)) {
			existing.putIfAbsent((String) instance.tValueByName(NAME), instance);
		}
		List<TLObject> order = new ArrayList<>();
		for (InstanceInfo info : computed) {
			TLObject instance = existing.remove(info.getId());
			if (instance == null) {
				instance = create(INSTANCE_TYPE);
				instance.tUpdateByName(NAME, info.getId());
			}
			write(instance, info);
			info.setStored(instance);
			order.add(instance);
		}
		for (TLObject removed : existing.values()) {
			set(removed, ACTIVE, Boolean.FALSE);
			order.add(removed);
		}
		if (!order.equals(stored(device))) {
			device.tUpdateByName(MODULE_INSTANCES, order);
		}
	}

	private static void write(TLObject instance, InstanceInfo info) {
		set(instance, PLACEMENT, info.getPlacement());
		set(instance, PARENT, info.getParent() == null ? null : stored(info.getParent()));
		set(instance, REPEAT_INDEX, info.getRepeatIndex());
		set(instance, ACTIVE, Boolean.TRUE);
		Map<TLObject, TLObject> arguments = new HashMap<>();
		List<TLObject> current = list(instance, ARGUMENTS);
		for (TLObject argument : current) {
			arguments.putIfAbsent((TLObject) argument.tValueByName(ARGUMENT), argument);
		}
		List<TLObject> order = new ArrayList<>();
		for (Map.Entry<ModuleArgument, String> entry : info.getValues().entrySet()) {
			TLObject argument = arguments.remove(entry.getKey());
			if (argument == null) {
				argument = create(ARGUMENT_TYPE);
				argument.tUpdateByName(ARGUMENT, entry.getKey());
			}
			set(argument, VALUE, entry.getValue());
			order.add(argument);
		}
		for (TLObject obsolete : arguments.values()) {
			obsolete.tDelete();
		}
		if (!order.equals(list(instance, ARGUMENTS))) {
			instance.tUpdateByName(ARGUMENTS, order);
		}
	}

	/**
	 * The instance the device stores for the given computed one; stored now if the device stores
	 * none yet.
	 */
	public static TLObject stored(InstanceInfo info) {
		TLObject result = info.getStored();
		if (result != null && result.tValid()) {
			return result;
		}
		TLObject device = info.getDevice();
		for (TLObject instance : stored(device)) {
			if (info.getId().equals(instance.tValueByName(NAME))) {
				info.setStored(instance);
				return instance;
			}
		}
		result = create(INSTANCE_TYPE);
		result.tUpdateByName(NAME, info.getId());
		write(result, info);
		List<TLObject> instances = new ArrayList<>(stored(device));
		instances.add(result);
		device.tUpdateByName(MODULE_INSTANCES, instances);
		info.setStored(result);
		return result;
	}

	/**
	 * The stored instance of the device with the given ID; if there is none, a new one not in
	 * effect, without argument values (an instance a project file names that E-Home does not compute
	 * for the device's values, kept with its settings and connectors until it is in effect).
	 *
	 * @param parent
	 *        The stored instance of the enclosing module of a sub-module's instance, else
	 *        <code>null</code>.
	 */
	public static TLObject inactive(TLObject device, String id, TLObject placement, TLObject parent) {
		for (TLObject instance : stored(device)) {
			if (id.equals(instance.tValueByName(NAME))) {
				return instance;
			}
		}
		TLObject result = create(INSTANCE_TYPE);
		result.tUpdateByName(NAME, id);
		result.tUpdateByName(PLACEMENT, placement);
		result.tUpdateByName(PARENT, parent);
		result.tUpdateByName(ACTIVE, Boolean.FALSE);
		List<TLObject> instances = new ArrayList<>(stored(device));
		instances.add(result);
		device.tUpdateByName(MODULE_INSTANCES, instances);
		return result;
	}

	/**
	 * The module instances the device stores, in order.
	 */
	public static List<TLObject> stored(TLObject device) {
		if (device.tType().getPart(MODULE_INSTANCES) == null) {
			return List.of();
		}
		return list(device, MODULE_INSTANCES);
	}

	/**
	 * The argument values of the device's instances recording a difference to a project file (see
	 * the class comment), in the order of the instances.
	 */
	public static List<TLObject> differences(TLObject device) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject instance : stored(device)) {
			for (TLObject argument : list(instance, ARGUMENTS)) {
				if (argument.tValueByName(DEVICE_VALUE) != null) {
					result.add(argument);
				}
			}
		}
		return result;
	}

	/**
	 * The recorded differences of the device as a text naming each instance, argument and both
	 * values, <code>null</code> if there is none.
	 */
	public static String differenceText(TLObject device) {
		List<String> parts = new ArrayList<>();
		for (TLObject argument : differences(device)) {
			TLObject instance = argument.tContainer();
			TLObject definition = (TLObject) argument.tValueByName(ARGUMENT);
			parts.add(instance.tValueByName(NAME) + " " + definition.tValueByName(NAME) + ": E-Home "
				+ argument.tValueByName(VALUE) + ", " + argument.tValueByName(DEVICE_VALUE));
		}
		return parts.isEmpty() ? null : String.join("; ", parts);
	}

	/**
	 * Clears the recorded differences of the device: its memory holds E-Home's layout now.
	 */
	public static void clearDifferences(TLObject device) {
		for (TLObject argument : differences(device)) {
			argument.tUpdateByName(DEVICE_VALUE, null);
		}
	}

	private static void set(TLObject obj, String attribute, Object value) {
		if (!Objects.equals(obj.tValueByName(attribute), value)) {
			obj.tUpdateByName(attribute, value);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<TLObject> list(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value instanceof Collection<?> collection ? new ArrayList<>((Collection<TLObject>) collection)
			: new ArrayList<>();
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

}
