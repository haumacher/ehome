/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.operational.VoidType;

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
 * The same walk gives the configuration as a person reads it (haumacher/ehome #4): the pages
 * (channels, the channel independent part and parameter blocks, {@link #getPages(Object)}) in their
 * hierarchy, and on each page the parameters and communication objects shown there
 * ({@link #getItems(Object)}), switches resolved. The device itself stands for the top of the
 * structure. What the walk leaves out is not part of the configuration: neither shown nor written
 * into the device.
 * </p>
 *
 * <p>
 * Values are changed through {@link DeviceSettings}, which keeps a setting exactly where a value
 * differs from its default.
 * </p>
 */
public class DeviceConfiguration {

	private final TLObject _device;

	private final Program _program;

	private final Map<ParameterView, TLObject> _settings = new HashMap<>();

	private final Map<Parameter, ParameterView> _activeViews = new LinkedHashMap<>();

	private final Map<Port, Interface> _activeInterfaces = new LinkedHashMap<>();

	private final List<Case> _selectedCases = new ArrayList<>();

	private final Map<Object, Set<ConfigFragment>> _pages = new HashMap<>();

	private final Map<Object, Set<Option>> _items = new HashMap<>();

	private final Map<ConfigFragment, Object> _parents = new HashMap<>();

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
			walk(_program, device);
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
	 * This is the one definition of a device's effective parameter value, shared by the view of the
	 * device's configuration, the editing of a value ({@link DeviceSettings}) and the download image.
	 * The result is a {@link de.haumacher.ehome.model.operational.ParameterValue} holder:
	 * {@link ParameterValues#encode(TLObject)} gives the value in ETS notation,
	 * {@link ParameterValues#value(TLObject)} the typed value.
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

	/**
	 * The object holding the default of the given placement: the placement itself if it sets a
	 * default of its own, else its parameter.
	 */
	public static TLObject defaultHolder(ParameterView view) {
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

	private void walk(ConfigFragment fragment, Object page) {
		for (Option option : fragment.getContents()) {
			walk(option, page);
		}
	}

	private void walk(Option option, Object page) {
		if (option instanceof ParameterView view) {
			addView(view);
			addItem(page, view);
		} else if (option instanceof Interface placed) {
			_activeInterfaces.putIfAbsent(placed.getPort(), placed);
			addItem(page, placed);
		} else if (option instanceof Switch choice) {
			Case selected = select(choice);
			if (selected != null) {
				_selectedCases.add(selected);
				walk(selected, page);
			}
		} else if (option instanceof ConfigFragment fragment) {
			Object inner = page;
			if (isPage(fragment)) {
				_pages.computeIfAbsent(page, x -> new LinkedHashSet<>()).add(fragment);
				_parents.put(fragment, page);
				inner = fragment;
			}
			if (option instanceof ParameterGroup group && group.getInput() != null) {
				// The parameter a block shows as its title is in effect with the block.
				addView(group.getInput());
				addItem(inner, group.getInput());
			}
			walk(fragment, inner);
		}
	}

	private static boolean isPage(ConfigFragment fragment) {
		return fragment instanceof Channel || fragment instanceof ChannelIndependent
			|| fragment instanceof ParameterGroup;
	}

	private void addItem(Object page, Option item) {
		_items.computeIfAbsent(page, x -> new LinkedHashSet<>()).add(item);
	}

	private void addView(ParameterView view) {
		Parameter parameter = view.getParameter();
		if (parameter != null) {
			_activeViews.putIfAbsent(parameter, view);
		}
	}

	/**
	 * The cases of the switches in effect, in the order of the structure: what the values of the
	 * device select. Two configurations with the same cases show the same structure.
	 */
	public List<Case> getSelectedCases() {
		return Collections.unmodifiableList(_selectedCases);
	}

	/**
	 * The pages directly below the given one, in the order of the structure: the channels, the
	 * channel independent part and the parameter blocks in effect that are shown.
	 *
	 * @param page
	 *        The device for the top of the structure, otherwise a page of this configuration.
	 * @return The pages; empty for anything that is no page in effect.
	 */
	public List<ConfigFragment> getPages(Object page) {
		Set<ConfigFragment> pages = _pages.get(page);
		if (pages == null) {
			return Collections.emptyList();
		}
		List<ConfigFragment> result = new ArrayList<>();
		for (ConfigFragment candidate : pages) {
			if (!isHidden(candidate)) {
				result.add(candidate);
			}
		}
		return result;
	}

	/**
	 * The page the given page is shown in: the device for a page at the top, <code>null</code> for
	 * anything that is no page in effect.
	 */
	public Object getParentPage(Object page) {
		return _parents.get(page);
	}

	/**
	 * Whether the given object is the device or a page in effect.
	 */
	public boolean isPage(Object page) {
		return page == _device || _parents.containsKey(page);
	}

	/**
	 * The parameters and communication objects shown on the given page, in the order of the
	 * structure, with the cases of its switches resolved (pages nested in it not included).
	 * Parameters ETS does not show (access <code>None</code>) are left out.
	 *
	 * @param page
	 *        The device for the top of the structure, otherwise a page of this configuration.
	 * @return The {@link ParameterView}s and {@link Interface}s; empty for anything that is no page
	 *         in effect.
	 */
	public List<Option> getItems(Object page) {
		Set<Option> items = _items.get(page);
		if (items == null) {
			return Collections.emptyList();
		}
		List<Option> result = new ArrayList<>();
		for (Option item : items) {
			if (!(item instanceof ParameterView view) || !"None".equals(accessName(view))) {
				result.add(item);
			}
		}
		return result;
	}

	/**
	 * Whether the given placement is shown in this configuration: on a page in effect and not
	 * hidden by its access.
	 */
	public boolean isShown(ParameterView view) {
		for (Map.Entry<Object, Set<Option>> entry : _items.entrySet()) {
			if (entry.getValue().contains(view)) {
				Object page = entry.getKey();
				return !"None".equals(accessName(view))
					&& (page == _device || !isHidden((ConfigFragment) page) && isPage(page));
			}
		}
		return false;
	}

	/**
	 * The parameters and communication objects shown in this configuration with the page each is
	 * shown on (the device for the top of the structure), in the order of the structure: the
	 * entries of a page, then those of the pages below it.
	 */
	public Map<Option, Object> getShownItems() {
		Map<Option, Object> result = new LinkedHashMap<>();
		collectShown(_device, result);
		return result;
	}

	private void collectShown(Object page, Map<Option, Object> result) {
		for (Option item : getItems(page)) {
			result.putIfAbsent(item, page);
		}
		for (ConfigFragment inner : getPages(page)) {
			collectShown(inner, result);
		}
	}

	private boolean isHidden(ConfigFragment page) {
		for (Object current = page; current instanceof ConfigFragment fragment; current = _parents.get(fragment)) {
			if (fragment instanceof ParameterGroup group && "None".equals(accessName(group))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The name of the access of a placement (<code>None</code>, <code>Read</code>,
	 * <code>Write</code>, <code>ReadWrite</code>): its own, otherwise that of its parameter,
	 * otherwise <code>ReadWrite</code> (as ETS defaults it).
	 */
	public static String accessName(ParameterView view) {
		TLClassifier access = view.getAccess();
		if (access == null && view.getParameter() != null) {
			access = view.getParameter().getAccess();
		}
		return access == null ? "ReadWrite" : access.getName();
	}

	/**
	 * The name of the access of a parameter block: its own, otherwise that of the parameter it shows
	 * as its title, otherwise <code>ReadWrite</code>.
	 */
	public static String accessName(ParameterGroup group) {
		TLClassifier access = group.getAccess();
		if (access != null) {
			return access.getName();
		}
		return group.getInput() == null ? "ReadWrite" : accessName(group.getInput());
	}

	/**
	 * Whether the value of the given placement can be changed: its access and that of every block
	 * around it allows it, and its parameter has a value at all.
	 */
	public static boolean isEditable(ParameterView view) {
		if (!editable(accessName(view))) {
			return false;
		}
		for (TLObject container = view.tContainer(); container != null; container = container.tContainer()) {
			if (container instanceof ParameterGroup group && group.getAccess() != null
				&& !editable(group.getAccess().getName())) {
				return false;
			}
		}
		return !(ParameterValues.valueType(view) instanceof VoidType);
	}

	private static boolean editable(String access) {
		return "ReadWrite".equals(access) || "Write".equals(access);
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
