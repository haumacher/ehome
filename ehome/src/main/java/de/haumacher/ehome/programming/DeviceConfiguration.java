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

import de.haumacher.ehome.knx.PageOrder;
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
import de.haumacher.ehome.model.operational.SharedPlacement;
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

	/**
	 * The parts of a program's structure a {@link DeviceConfiguration} reads, each read once: the
	 * contents of the fragments, the inputs and cases of the switches, the parameters and the
	 * defaults of the placements.
	 *
	 * <p>
	 * Shared by the configurations computed one after the other while only the values of a device
	 * change (the read-back's search of values, which computes the configuration after every move):
	 * reading the model is what a configuration costs. A {@link Structure} must not outlive a change
	 * of the program.
	 * </p>
	 */
	public static final class Structure {

		/** A switch with its input and cases. */
		private record SwitchInfo(ParameterView input, List<Case> alternatives, List<String> patterns,
				List<Boolean> defaults) {
			// Value.
		}

		private final Map<ConfigFragment, List<Option>> _contents = new HashMap<>();

		private final Map<Switch, SwitchInfo> _switches = new HashMap<>();

		private final Map<ParameterView, Parameter> _parameters = new HashMap<>();

		private final Map<ParameterView, String> _defaults = new HashMap<>();

		private final Map<ParameterView, Integer> _orders = new HashMap<>();

		/**
		 * The object an option refers to: the port of a placed object, the placement of a shared one,
		 * the title of a block.
		 */
		private final Map<Option, Object> _refs = new HashMap<>();

		/** The encoded default of a placement: {@link ParameterEncoding.Bits}, {@link #NO_BITS} or the
		 * {@link IllegalArgumentException} of its encoding. */
		private final Map<ParameterView, Object> _defaultBits = new HashMap<>();

		private static final Object NO_BITS = new Object();

		ParameterEncoding.Bits defaultBits(ParameterView view, Program program) {
			Object result = _defaultBits.get(view);
			if (result == null) {
				Parameter parameter = parameter(view);
				try {
					ParameterEncoding.Bits bits =
						ParameterEncoding.encode(parameter.getType(), defaultHolder(view), program);
					result = bits == null ? NO_BITS : bits;
				} catch (IllegalArgumentException ex) {
					result = ex;
				}
				_defaultBits.put(view, result);
			}
			if (result instanceof IllegalArgumentException ex) {
				throw new IllegalArgumentException(ex.getMessage(), ex);
			}
			return result == NO_BITS ? null : (ParameterEncoding.Bits) result;
		}

		private Object ref(Option option) {
			Object result = _refs.get(option);
			if (result == null && !_refs.containsKey(option)) {
				if (option instanceof Interface placed) {
					result = placed.getPort();
				} else if (option instanceof SharedPlacement shared) {
					result = shared.getPlaced();
				} else if (option instanceof ParameterGroup group) {
					result = group.getInput();
				}
				_refs.put(option, result);
			}
			return result;
		}

		List<Option> contents(ConfigFragment fragment) {
			return _contents.computeIfAbsent(fragment, f -> new ArrayList<>(f.getContents()));
		}

		Parameter parameter(ParameterView view) {
			Parameter result = _parameters.get(view);
			if (result == null && !_parameters.containsKey(view)) {
				result = view.getParameter();
				_parameters.put(view, result);
			}
			return result;
		}

		Integer order(ParameterView view) {
			Integer result = _orders.get(view);
			if (result == null && !_orders.containsKey(view)) {
				result = view.getOrder();
				_orders.put(view, result);
			}
			return result;
		}

		String defaultValue(ParameterView view) {
			String result = _defaults.get(view);
			if (result == null && !_defaults.containsKey(view)) {
				result = ParameterValues.encode(defaultHolder(view));
				_defaults.put(view, result);
			}
			return result;
		}

		private SwitchInfo info(Switch choice) {
			return _switches.computeIfAbsent(choice, c -> {
				List<Case> alternatives = new ArrayList<>(c.getAlternatives());
				List<String> patterns = new ArrayList<>();
				List<Boolean> defaults = new ArrayList<>();
				for (Case alternative : alternatives) {
					patterns.add(alternative.getPattern());
					defaults.add(Boolean.valueOf(alternative.getIsDefault()));
				}
				return new SwitchInfo(c.getInput(), alternatives, patterns, defaults);
			});
		}

	}

	private final TLObject _device;

	private final Program _program;

	private final Structure _structure;

	private final Map<ParameterView, TLObject> _settings = new HashMap<>();

	private final Map<Parameter, ParameterView> _activeViews = new LinkedHashMap<>();

	private final Map<Port, Interface> _activeInterfaces = new LinkedHashMap<>();

	private final List<Case> _selectedCases = new ArrayList<>();

	private final List<Switch> _switches = new ArrayList<>();

	private final Map<Object, Set<ConfigFragment>> _pages = new HashMap<>();

	private final Map<Object, PageOrder> _items = new HashMap<>();

	/** The inputs of the switches the walk is in, outermost first. */
	private final List<ParameterView> _inputs = new ArrayList<>();

	private final Map<ConfigFragment, Object> _parents = new HashMap<>();

	/**
	 * Creates the {@link DeviceConfiguration} of the given device.
	 */
	public DeviceConfiguration(TLObject device) {
		this(device, new Structure());
	}

	/**
	 * Creates the {@link DeviceConfiguration} of the given device, reading the program's structure
	 * through the given {@link Structure}.
	 */
	public DeviceConfiguration(TLObject device, Structure structure) {
		_device = device;
		_structure = structure;
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
		TLObject setting = _settings.get(view);
		return setting != null ? ParameterValues.encode(setting) : _structure.defaultValue(view);
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
		for (Option option : _structure.contents(fragment)) {
			walk(option, page);
		}
	}

	private void walk(Option option, Object page) {
		if (option instanceof SharedPlacement) {
			// A further place of a reference placed elsewhere: the reference, at this place.
			if (_structure.ref(option) instanceof Option placed) {
				walk(placed, page);
			}
		} else if (option instanceof ParameterView view) {
			addView(view);
			items(page).addParameter(view, _structure.order(view));
		} else if (option instanceof Interface placed) {
			_activeInterfaces.putIfAbsent((Port) _structure.ref(placed), placed);
			items(page).addObject(placed, _inputs);
		} else if (option instanceof Switch choice) {
			_switches.add(choice);
			Case selected = select(choice);
			if (selected != null) {
				_selectedCases.add(selected);
				_inputs.add(input(choice));
				walk(selected, page);
				_inputs.remove(_inputs.size() - 1);
			}
		} else if (option instanceof ConfigFragment fragment) {
			Object inner = page;
			if (isPage(fragment)) {
				_pages.computeIfAbsent(page, x -> new LinkedHashSet<>()).add(fragment);
				_parents.put(fragment, page);
				inner = fragment;
			}
			if (option instanceof ParameterGroup && _structure.ref(option) instanceof ParameterView input) {
				// The parameter a block shows as its title is in effect with the block.
				addView(input);
				items(inner).addTitle(input);
			}
			walk(fragment, inner);
		}
	}

	private static boolean isPage(ConfigFragment fragment) {
		return fragment instanceof Channel || fragment instanceof ChannelIndependent
			|| fragment instanceof ParameterGroup;
	}

	private PageOrder items(Object page) {
		return _items.computeIfAbsent(page, x -> new PageOrder());
	}

	private void addView(ParameterView view) {
		Parameter parameter = _structure.parameter(view);
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
	 * The binary form of the effective value of the given placement for the device, see
	 * {@link ParameterEncoding#encode(de.haumacher.ehome.model.operational.ParameterType, TLObject, Program)}
	 * (the encoding of a default is computed once per {@link Structure}).
	 *
	 * @throws IllegalArgumentException
	 *         If the value cannot be encoded.
	 */
	public ParameterEncoding.Bits encode(ParameterView view) {
		TLObject setting = _settings.get(view);
		if (setting != null) {
			return ParameterEncoding.encode(_structure.parameter(view).getType(), setting, _program);
		}
		return _structure.defaultBits(view, _program);
	}

	/**
	 * The input of the given switch (read through the {@link Structure}).
	 */
	public ParameterView input(Switch choice) {
		return _structure.info(choice).input();
	}

	/**
	 * The parameter of the given placement (read through the {@link Structure}).
	 */
	public Parameter parameter(ParameterView view) {
		return _structure.parameter(view);
	}

	/**
	 * The switches in effect: those on the pages and in the cases taken, in the order of the
	 * structure.
	 */
	public List<Switch> getActiveSwitches() {
		return Collections.unmodifiableList(_switches);
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
	 * The parameters and communication objects shown on the given page, in the order ETS shows them
	 * ({@link PageOrder}: the parameters by their display order, a communication object after the
	 * parameter that brought it in), with the cases of its switches resolved (pages nested in it not
	 * included). Parameters ETS does not show (access <code>None</code>) are left out.
	 *
	 * @param page
	 *        The device for the top of the structure, otherwise a page of this configuration.
	 * @return The {@link ParameterView}s and {@link Interface}s; empty for anything that is no page
	 *         in effect.
	 */
	public List<Option> getItems(Object page) {
		PageOrder items = _items.get(page);
		if (items == null) {
			return Collections.emptyList();
		}
		List<Option> result = new ArrayList<>();
		for (Option item : items.getItems()) {
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
		for (Map.Entry<Object, PageOrder> entry : _items.entrySet()) {
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
	 * shown on (the device for the top of the structure): the entries of a page in the order of
	 * {@link #getItems(Object)}, then those of the pages below it in the order of the structure.
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
		Structure.SwitchInfo info = _structure.info(choice);
		String value = info.input() == null ? null : value(info.input());
		Case fallback = null;
		for (int n = 0, cnt = info.alternatives().size(); n < cnt; n++) {
			String pattern = info.patterns().get(n);
			if (pattern != null && matches(pattern, value)) {
				return info.alternatives().get(n);
			}
			if (info.defaults().get(n).booleanValue() && fallback == null) {
				fallback = info.alternatives().get(n);
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
