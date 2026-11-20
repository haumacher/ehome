/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.top_logic.model.TLObject;

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.model.logical.ProjectDatapoint;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.BlockBinding;
import de.haumacher.ehome.model.runtime.BlockInstance;
import de.haumacher.ehome.model.runtime.BlockPort;
import de.haumacher.ehome.model.runtime.Function;

/**
 * A room controller regulates a climate function (haumacher/ehome #212): the virtual device names its
 * function (<code>BlockInstance#function</code>), which exists before and without it.
 *
 * <ul>
 * <li>Its input <code>temperature</code> reads the function's <code>sensors</code> ({@link #reads(TLObject)}),
 * in the image, in the simulation and on the device's page; how it combines them is its own aggregation.
 * Binding a datapoint to that input adds it to the sensors ({@link BlockInstances#bind(TLObject, TLObject)}).</li>
 * <li>Its outputs give the function's roles ({@link #sync(TLObject)}): <code>filtered</code> the actual value
 * (<code>temperature</code>), <code>activeSetpoint</code> the <code>setpoint</code>, <code>valve</code> the
 * <code>heatingLevel</code>, <code>demand</code> <code>heating</code>, each the datapoint the output is bound to
 * (the valve's group address), else the output itself; its comfort setpoint is the
 * <code>setpointCommand</code>.</li>
 * </ul>
 *
 * <p>
 * Attaching the device ({@link #regulate(TLObject, TLObject)}) moves the datapoints its input
 * <code>temperature</code> was bound to into the function's sensors, so it reads what it read, and fills the
 * roles. Deleting the device leaves the function and its sensors; the roles bound to the device's own
 * outputs are cleared with them. Deleting the function, or letting the device regulate none, binds the
 * function's sensors to the device's input again ({@link #release(TLObject)}), so that it reads what it
 * read.
 * </p>
 */
public final class RoomControllers {

	/** The input reading the function's sensors. */
	public static final String TEMPERATURE = "temperature";

	/** The output giving the actual value. */
	private static final String FILTERED = "filtered";

	/** The output giving the setpoint. */
	private static final String ACTIVE_SETPOINT = "activeSetpoint";

	/** The output giving whether the room is heated. */
	private static final String DEMAND = "demand";

	/** The output giving the heating level. */
	private static final String VALVE = "valve";

	/** The parameter giving the setpoint command. */
	private static final String COMFORT = "comfort";

	private RoomControllers() {
		// Static helpers.
	}

	/** Whether the object is a room controller. */
	public static boolean isRoomController(TLObject obj) {
		return BlockInstances.isInstance(obj) && RoomController.TYPE.equals(((BlockInstance) obj).getBlockType());
	}

	/** The function the room controller regulates, <code>null</code> for none or another object. */
	public static Function regulated(TLObject instance) {
		return isRoomController(instance) && ((BlockInstance) instance).getFunction() instanceof Function function
			&& function.tValid() ? function : null;
	}

	/**
	 * The room controllers of the function's project that regulate it, in block order (one
	 * project's virtual devices are read).
	 */
	public static List<TLObject> regulating(TLObject function) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject instance : BlockInstances.of(function.tContainer())) {
			if (regulated(instance) == function) {
				result.add(instance);
			}
		}
		return result;
	}

	/**
	 * Whether the port is the input <code>temperature</code> of a room controller that regulates a
	 * function: it reads the function's sensors.
	 */
	public static boolean readsSensors(TLObject port) {
		return BlockInstances.isPort(port) && TEMPERATURE.equals(((BlockPort) port).getPort())
			&& regulated(port.tContainer()) != null;
	}

	/**
	 * The datapoints the input reads, in their order: the sensors of the function the room controller
	 * regulates for its input <code>temperature</code>, the bound datapoints for any other port
	 * ({@link BlockInstances#bound(TLObject)}).
	 */
	public static List<TLObject> reads(TLObject port) {
		if (readsSensors(port)) {
			return sensors(regulated(port.tContainer()));
		}
		return BlockInstances.bound(port);
	}

	/** The sensors of the function, valid ones, in their order. */
	public static List<TLObject> sensors(TLObject function) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject sensor : RuleExport.objects(((Function) function).getSensors())) {
			if (sensor.tValid()) {
				result.add(sensor);
			}
		}
		return result;
	}

	/**
	 * The climate functions of the instance's project the room controller may regulate: those no other
	 * room controller regulates.
	 */
	public static List<TLObject> options(TLObject instance) {
		TLObject project = instance == null ? null : instance.tContainer();
		if (!(project instanceof Project)) {
			return List.of();
		}
		Set<TLObject> taken = new LinkedHashSet<>();
		for (TLObject other : BlockInstances.of(project)) {
			if (other != instance && regulated(other) != null) {
				taken.add(regulated(other));
			}
		}
		List<TLObject> result = new ArrayList<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			if ("climate".equals(FunctionProposal.kind(function)) && !taken.contains(function)) {
				result.add(function);
			}
		}
		return result;
	}

	/**
	 * Lets the room controller regulate the function, <code>null</code> for none (to be called in a
	 * transaction): moves the datapoints its input <code>temperature</code> is bound to into the
	 * function's sensors and fills the function's roles ({@link #sync(TLObject)}). A function the
	 * device regulated before keeps its roles and sensors; a device that regulates none any more
	 * reads them by bindings of its own again ({@link #release(TLObject)}).
	 *
	 * @return Whether anything changed.
	 */
	public static boolean regulate(TLObject instance, TLObject function) {
		if (!isRoomController(instance)) {
			throw new IllegalArgumentException("Not a room controller: " + instance);
		}
		if (function != null && !"climate".equals(FunctionProposal.kind(function))) {
			throw new IllegalArgumentException("Not a climate function: " + function);
		}
		BlockInstance block = (BlockInstance) instance;
		if (block.getFunction() == function) {
			return sync(instance);
		}
		if (function == null) {
			release(instance);
		} else {
			block.setFunction((Function) function);
			sync(instance);
		}
		return true;
	}

	/**
	 * Ends the regulation of the room controller (to be called in a transaction): its input
	 * <code>temperature</code> is bound to the sensors of the function it regulated, so that it reads
	 * what it read; the function keeps them.
	 */
	public static void release(TLObject instance) {
		Function function = regulated(instance);
		if (function == null) {
			return;
		}
		((BlockInstance) instance).setFunction(null);
		TLObject input = BlockInstances.port(instance, TEMPERATURE);
		if (input != null) {
			List<TLObject> bound = BlockInstances.bound(input);
			for (TLObject sensor : sensors(function)) {
				if (!bound.contains(sensor)) {
					BlockInstances.bind(input, sensor);
				}
			}
		}
	}

	/**
	 * Releases the room controllers regulating the function before it is deleted (to be called in a
	 * transaction, {@link #release(TLObject)}): they read its sensors by bindings of their own again.
	 */
	public static void deleting(TLObject function) {
		if (function.tContainer() == null) {
			return;
		}
		for (TLObject instance : regulating(function)) {
			release(instance);
		}
	}

	/**
	 * Brings the function the room controller regulates in line with it (to be called in a transaction):
	 * the datapoints bound to its input <code>temperature</code> move into the function's sensors (at
	 * their end, those it has not), the outputs and the comfort setpoint give the function's roles. Does
	 * nothing for a device regulating no function.
	 *
	 * @return Whether anything changed.
	 */
	public static boolean sync(TLObject instance) {
		Function function = regulated(instance);
		if (function == null) {
			return false;
		}
		boolean changed = false;
		TLObject input = BlockInstances.port(instance, TEMPERATURE);
		if (input != null && !((BlockPort) input).getBindings().isEmpty()) {
			List<TLObject> sensors = sensors(function);
			for (TLObject binding : RuleExport.objects(((BlockPort) input).getBindings())) {
				if (((BlockBinding) binding).getDatapoint() instanceof TLObject datapoint && datapoint.tValid()
					&& !sensors.contains(datapoint)) {
					sensors.add(datapoint);
				}
				binding.tDelete();
			}
			function.setSensors(RuleImport.typed(sensors));
			changed = true;
		}
		Map<String, TLObject> roles = roles(instance);
		changed |= set(function::getTemperature, function::setTemperature, roles.get("temperature"));
		changed |= set(function::getSetpoint, function::setSetpoint, roles.get("setpoint"));
		changed |= set(function::getSetpointCommand, function::setSetpointCommand, roles.get("setpointCommand"));
		changed |= set(function::getHeating, function::setHeating, roles.get("heating"));
		changed |= set(function::getHeatingLevel, function::setHeatingLevel, roles.get("heatingLevel"));
		return changed;
	}

	/**
	 * The datapoint of the output for the function's role: the datapoint it is bound to (the valve's
	 * group address), else the output itself; <code>null</code> without the output.
	 */
	public static TLObject output(TLObject instance, String id) {
		TLObject port = BlockInstances.port(instance, id);
		if (port == null) {
			return null;
		}
		List<TLObject> bound = BlockInstances.bound(port);
		return bound.isEmpty() ? port : bound.get(0);
	}

	/** The datapoints the room controller gives the roles of a climate, by role, in the order of the roles. */
	public static Map<String, TLObject> roles(TLObject instance) {
		Map<String, TLObject> result = new LinkedHashMap<>();
		put(result, "temperature", output(instance, FILTERED));
		put(result, "setpoint", output(instance, ACTIVE_SETPOINT));
		put(result, "setpointCommand", BlockInstances.parameter(instance, COMFORT));
		put(result, "heating", output(instance, DEMAND));
		put(result, "heatingLevel", output(instance, VALVE));
		return result;
	}

	private static void put(Map<String, TLObject> roles, String role, TLObject datapoint) {
		if (datapoint != null) {
			roles.put(role, datapoint);
		}
	}

	/** Sets the role to the datapoint, where the device gives one and the role has another. */
	private static boolean set(Supplier<ProjectDatapoint> current, Consumer<ProjectDatapoint> setter, TLObject datapoint) {
		if (datapoint == null || current.get() == datapoint) {
			return false;
		}
		setter.accept((ProjectDatapoint) datapoint);
		return true;
	}

}
