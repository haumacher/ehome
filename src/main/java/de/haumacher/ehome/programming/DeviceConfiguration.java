/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;

/**
 * The configuration of a device (<code>ehome.physical:Device</code>): the effective value of each
 * parameter placement and the part of its program's structure that is in effect with these values.
 *
 * <p>
 * The structure of the program (channels, parameter groups, switches with their cases) is walked
 * with the device's values: a {@link Switch} takes the {@link Case} whose pattern matches the
 * effective value of its input, else its default case. Only the placements and communication
 * objects of the cases taken are part of the configuration, exactly as ETS shows them; ETS writes
 * the parameters of this part only and leaves the memory of all other parameters at the static data
 * of the program (verified with the memory ETS loaded into the Tastsensor of
 * <code>samples/EHomeDemo.knxproj</code>).
 * </p>
 *
 * <p>
 * Haumacher/ehome #4 (device configuration by the program structure) is to replace the inside of
 * {@link #valueHolder(TLObject, ParameterView)}; the walk here is the minimal one the download image
 * needs.
 * </p>
 */
public class DeviceConfiguration {

	private final TLObject _device;

	private final Program _program;

	private final Map<ParameterView, TLObject> _settings = new HashMap<>();

	private final Map<Parameter, ParameterView> _activeViews = new LinkedHashMap<>();

	private final Map<Port, Interface> _activeInterfaces = new LinkedHashMap<>();

	/**
	 * Creates the {@link DeviceConfiguration} of the given device.
	 */
	public DeviceConfiguration(TLObject device) {
		_device = device;
		_program = program(device);
		for (Object setting : (Collection<?>) device.tValueByName("settings")) {
			TLObject value = (TLObject) setting;
			if (value.tValueByName("parameter") instanceof ParameterView view) {
				_settings.putIfAbsent(view, value);
			}
		}
		if (_program != null) {
			walk(_program);
		}
	}

	/**
	 * The program of the given device; <code>null</code> if it has none.
	 */
	public static Program program(TLObject device) {
		Object assignment = device.tValueByName("program");
		if (assignment instanceof TLObject programAssignment
			&& programAssignment.tValueByName("program") instanceof Program program) {
			return program;
		}
		return null;
	}

	/**
	 * The device.
	 */
	public TLObject getDevice() {
		return _device;
	}

	/**
	 * The program of the device, <code>null</code> if it has none.
	 */
	public Program getProgram() {
		return _program;
	}

	/**
	 * The object holding the effective value of the given placement for the device: the device's
	 * setting for it, else the placement itself if it sets a default of its own, else the parameter.
	 *
	 * <p>
	 * This is the one definition of a device's effective parameter value until haumacher/ehome #4
	 * replaces its inside. The result is a {@link de.haumacher.ehome.model.operational.ParameterValue}
	 * holder: {@link ParameterValues#encode(TLObject)} gives the value in ETS notation.
	 * </p>
	 */
	public static TLObject valueHolder(TLObject device, ParameterView view) {
		for (Object setting : (Collection<?>) device.tValueByName("settings")) {
			TLObject value = (TLObject) setting;
			if (value.tValueByName("parameter") == view) {
				return value;
			}
		}
		return defaultHolder(view);
	}

	/**
	 * The object holding the effective value of the given placement for the device of this
	 * configuration, see {@link #valueHolder(TLObject, ParameterView)}.
	 */
	public TLObject valueHolder(ParameterView view) {
		TLObject setting = _settings.get(view);
		return setting != null ? setting : defaultHolder(view);
	}

	private static TLObject defaultHolder(ParameterView view) {
		if (holdsValue(view)) {
			return view;
		}
		return view.getParameter();
	}

	private static boolean holdsValue(TLObject holder) {
		return holder.tValueByName(ParameterValues.CHOICE) != null
			|| holder.tValueByName(ParameterValues.NUMBER) != null
			|| holder.tValueByName(ParameterValues.DECIMAL) != null
			|| holder.tValueByName(ParameterValues.TEXT) != null;
	}

	/**
	 * The effective value of the given placement in ETS notation, <code>null</code> if it has none.
	 */
	public String value(ParameterView view) {
		return ParameterValues.encode(valueHolder(view));
	}

	/**
	 * The parameters in effect, each with the first placement in effect, in the order of the
	 * structure.
	 */
	public Map<Parameter, ParameterView> getActiveViews() {
		return Collections.unmodifiableMap(_activeViews);
	}

	/**
	 * The communication objects in effect, each with the first placement in effect, in the order of
	 * the structure.
	 */
	public Map<Port, Interface> getActiveInterfaces() {
		return Collections.unmodifiableMap(_activeInterfaces);
	}

	private void walk(ConfigFragment fragment) {
		for (Option option : fragment.getContents()) {
			walk(option);
		}
	}

	private void walk(Option option) {
		if (option instanceof ParameterView view) {
			addView(view);
		} else if (option instanceof Interface placed) {
			_activeInterfaces.putIfAbsent(placed.getPort(), placed);
		} else if (option instanceof Switch choice) {
			Case selected = select(choice);
			if (selected != null) {
				walk(selected);
			}
		} else {
			if (option instanceof ParameterGroup group && group.getInput() != null) {
				// The parameter a block shows as its title is in effect with the block.
				addView(group.getInput());
			}
			if (option instanceof ConfigFragment fragment) {
				walk(fragment);
			}
		}
	}

	private void addView(ParameterView view) {
		Parameter parameter = view.getParameter();
		if (parameter != null) {
			_activeViews.putIfAbsent(parameter, view);
		}
	}

	/**
	 * The case of the given switch that is in effect: the first whose pattern matches the effective
	 * value of the switch's input, else the default case; <code>null</code> if none.
	 */
	public Case select(Switch choice) {
		String value = choice.getInput() == null ? null : value(choice.getInput());
		Case fallback = null;
		for (Case alternative : choice.getAlternatives()) {
			if (alternative.getPattern() != null && matches(alternative.getPattern(), value)) {
				return alternative;
			}
			if (alternative.getIsDefault() && fallback == null) {
				fallback = alternative;
			}
		}
		return fallback;
	}

	/**
	 * Whether a value in ETS notation matches the pattern of a case (ETS: <code>when@test</code>):
	 * a list of values separated by spaces, each a value to be equal to or a comparison
	 * (<code>&lt;5</code>, <code>&gt;=3</code>, <code>!=0</code>).
	 */
	public static boolean matches(String pattern, String value) {
		if (value == null) {
			return false;
		}
		for (String token : pattern.trim().split("\\s+")) {
			if (token.isEmpty()) {
				continue;
			}
			if (matchesToken(token, value.trim())) {
				return true;
			}
		}
		return false;
	}

	private static boolean matchesToken(String token, String value) {
		String operator = "";
		for (String candidate : List.of("<=", ">=", "!=", "<", ">", "=")) {
			if (token.startsWith(candidate)) {
				operator = candidate;
				break;
			}
		}
		String operand = token.substring(operator.length());
		Double left = number(value);
		Double right = number(operand);
		if (operator.isEmpty() || operator.equals("=")) {
			return operand.equals(value) || (left != null && right != null && left.doubleValue() == right.doubleValue());
		}
		if (operator.equals("!=")) {
			return left != null && right != null ? left.doubleValue() != right.doubleValue() : !operand.equals(value);
		}
		if (left == null || right == null) {
			return false;
		}
		int comparison = Double.compare(left.doubleValue(), right.doubleValue());
		switch (operator) {
			case "<":
				return comparison < 0;
			case "<=":
				return comparison <= 0;
			case ">":
				return comparison > 0;
			default:
				return comparison >= 0;
		}
	}

	private static Double number(String text) {
		try {
			return Double.valueOf(text);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * The connectors of the device (<code>ehome.physical:Connector</code>) that are in use: all not
	 * marked inactive.
	 */
	public List<TLObject> getConnectors() {
		List<TLObject> result = new ArrayList<>();
		for (Object connector : (Collection<?>) _device.tValueByName("connectors")) {
			TLObject value = (TLObject) connector;
			if (!Boolean.FALSE.equals(value.tValueByName("active"))) {
				result.add(value);
			}
		}
		return result;
	}

}
