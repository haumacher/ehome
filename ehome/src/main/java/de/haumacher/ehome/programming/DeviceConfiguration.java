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

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.PageOrder;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Button;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Allocator;
import de.haumacher.ehome.model.operational.Assign;
import de.haumacher.ehome.model.operational.ModuleArgument;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ModulePlacement;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.PlacementArgument;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Rename;
import de.haumacher.ehome.model.operational.Repeat;
import de.haumacher.ehome.model.operational.SharedPlacement;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.operational.VoidType;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.modules.ModuleParts;

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
 *
 * <p>
 * Dynamic modules (haumacher/ehome #119): the walk computes the module instances of the device. A
 * placement of a module ({@link ModulePlacement}) in effect is an instance, a {@link Repeat} places
 * its content as many times as its counting parameter (or its count) says, in the order of the
 * structure; an instance's arguments are the fixed values of its placement or the next values of
 * allocators. The structure of the module is walked in the instance: its parameters, objects and
 * structure elements are {@link de.haumacher.ehome.modules.InstancePart}s, whose numbers, offsets and
 * texts are those of the instance ({@link ModuleParts}), and the device's values of them are the
 * settings naming the part of the template and the instance. Placements in modules (sub-modules) are
 * walked the same way. {@link #getInstances()} gives the instances, {@link ModuleInstances} stores
 * them.
 * </p>
 *
 * <p>
 * Cases (haumacher/ehome #142): every case of a switch whose pattern matches the value of its input
 * is taken, not only the first (ETS: all <code>when</code> of a <code>choose</code> that match; the
 * MDT Taster Light <code>M-0083_A-0094-15-3CB3</code> shows the page of key 1 under
 * <code>when test="2 3"</code> and the page of key 2 under <code>when test="2"</code> of the same
 * switch); the default case only if none matches.
 * </p>
 *
 * <p>
 * Assignments (haumacher/ehome #142): an {@link Assign} in a case taken gives its target placement the
 * effective value of its source placement, or its constant. The target's effective value is the
 * assigned one ({@link #value(ParameterView)}, {@link #effectiveHolder(ParameterView)}) for every
 * switch and repeat on it, and the memory of its parameter holds it ({@link #encode(ParameterView)});
 * the device's setting of the target, if any, does not count while it is assigned. As an assignment
 * may open or close cases with further assignments and switches (also before it in the document), the
 * structure is walked in the order of the document, each assignment taking effect where the walk
 * meets it, and walked again (starting with the assignments the walk before found) until every value
 * the walk read is the one it ends with (a fixpoint, at most {@value #MAX_PASSES} walks; assignments that
 * keep switching each other off stop there with the last walk). Of several assignments in effect to the
 * same placement, the last in the order of the structure counts.
 * </p>
 */
public class DeviceConfiguration {

	/** The most walks of the structure until the assignments in effect settle. */
	public static final int MAX_PASSES = 16;

	/**
	 * An assignment in effect: the target placement (in its module instance), the source placement or
	 * the assignment holding the constant, and the value in ETS notation it gives the target.
	 */
	private record Assignment(ParameterView target, ParameterView source, Assign assign, String value) {
		// Value.
	}

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
				throw new ValueException(ValueException.reason(ex), ex);
			}
			return result == NO_BITS ? null : (ParameterEncoding.Bits) result;
		}

		private Object ref(Option option) {
			Option template = ModuleParts.template(option);
			Object result = _refs.get(template);
			if (result == null && !_refs.containsKey(template)) {
				if (template instanceof Interface placed) {
					result = placed.getPort();
				} else if (template instanceof SharedPlacement shared) {
					result = shared.getPlaced();
				} else if (template instanceof ParameterGroup group) {
					result = group.getInput();
				}
				_refs.put(template, result);
			}
			return result instanceof TLObject obj ? ModuleParts.wrap(obj, ModuleParts.instance(option)) : result;
		}

		List<Option> contents(ConfigFragment fragment) {
			ConfigFragment template = ModuleParts.template(fragment);
			List<Option> result = _contents.computeIfAbsent(template, f -> new ArrayList<>(f.getContents()));
			InstanceInfo instance = ModuleParts.instance(fragment);
			if (instance == null) {
				return result;
			}
			List<Option> wrapped = new ArrayList<>(result.size());
			for (Option option : result) {
				wrapped.add((Option) ModuleParts.wrap(option, instance));
			}
			return wrapped;
		}

		Parameter parameter(ParameterView view) {
			ParameterView template = ModuleParts.template(view);
			Parameter result = _parameters.get(template);
			if (result == null && !_parameters.containsKey(template)) {
				result = template.getParameter();
				_parameters.put(template, result);
			}
			return (Parameter) ModuleParts.wrap(result, ModuleParts.instance(view));
		}

		Integer order(ParameterView view) {
			ParameterView template = ModuleParts.template(view);
			Integer result = _orders.get(template);
			if (result == null && !_orders.containsKey(template)) {
				result = template.getOrder();
				_orders.put(template, result);
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
			SwitchInfo result = templateInfo(ModuleParts.template(choice));
			InstanceInfo instance = ModuleParts.instance(choice);
			if (instance == null) {
				return result;
			}
			List<Case> alternatives = new ArrayList<>();
			for (Case alternative : result.alternatives()) {
				alternatives.add((Case) ModuleParts.wrap(alternative, instance));
			}
			return new SwitchInfo((ParameterView) ModuleParts.wrap(result.input(), instance), alternatives,
				result.patterns(), result.defaults());
		}

		private SwitchInfo templateInfo(Switch choice) {
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

	/** The device's settings by {@link #key(ParameterView)} of their placement. */
	private final Map<Object, TLObject> _settings = new HashMap<>();

	/** The module instances the walk computed, in the order of the structure. */
	private final List<InstanceInfo> _instances = new ArrayList<>();

	/** The IDs of {@link #_instances}. */
	private final Set<String> _instanceIds = new java.util.HashSet<>();

	/** Why the values give no valid layout of the modules. */
	private final List<ResKey> _moduleProblems = new ArrayList<>();

	/** The module instances the device stores, by their IDs. */
	private final Map<String, TLObject> _storedInstances = new HashMap<>();

	/** The position in the document and the iteration of the enclosing repeats, outermost first. */
	private List<int[]> _repeatPath = new ArrayList<>();

	/** The next value of each allocator (of a module: per instance of the module). */
	private final Map<Object, Long> _allocators = new HashMap<>();

	private final Map<Parameter, ParameterView> _activeViews = new LinkedHashMap<>();

	private final Map<Port, Interface> _activeInterfaces = new LinkedHashMap<>();

	private final List<Case> _selectedCases = new ArrayList<>();

	private final List<Switch> _switches = new ArrayList<>();

	private final Map<Object, Set<ConfigFragment>> _pages = new HashMap<>();

	private final Map<Object, PageOrder> _items = new HashMap<>();

	/** The inputs of the switches the walk is in, outermost first. */
	private final List<ParameterView> _inputs = new ArrayList<>();

	private final Map<ConfigFragment, Object> _parents = new HashMap<>();

	/** The titles of the renames in effect, by the block or channel they rename. */
	private final Map<Object, ResKey> _renames = new HashMap<>();

	/**
	 * The assignments in effect, by {@link #key(ParameterView)} of their target: during a walk those of
	 * the walk before, updated by each assignment the walk meets.
	 */
	private Map<Object, Assignment> _assigned = new LinkedHashMap<>();

	/** Whether the structure is being walked: {@link #value(ParameterView)} records what it gives. */
	private boolean _walking;

	/** The placements whose values the current walk read, with the values read. */
	private final Map<Object, Object[]> _reads = new HashMap<>();

	/** Whether the current walk read different values of the same placement. */
	private boolean _conflict;

	/** The assignments in effect the current walk found, by {@link #key(ParameterView)} of their target. */
	private Map<Object, Assignment> _found = new LinkedHashMap<>();

	/** The assignments in effect by the parameter of their target (the memory they give a value). */
	private final Map<Parameter, Assignment> _assignedParameters = new HashMap<>();

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
		for (TLObject instance : ModuleInstances.stored(device)) {
			_storedInstances.putIfAbsent((String) instance.tValueByName(ModuleInstances.NAME), instance);
		}
		for (Object setting : (Collection<?>) device.tValueByName("settings")) {
			TLObject value = (TLObject) setting;
			if (value.tValueByName("parameter") instanceof ParameterView view) {
				String instance = ModuleParts.instanceId(value);
				_settings.putIfAbsent(instance == null ? view : new PartKey(view, instance), value);
			}
		}
		if (_program != null) {
			boolean settled = false;
			for (int pass = 1;; pass++) {
				_walking = true;
				walk(_program, device);
				_walking = false;
				settled = settled();
				if (settled || pass == MAX_PASSES) {
					break;
				}
				_assigned = new LinkedHashMap<>(_found);
				_found = new LinkedHashMap<>();
				_reads.clear();
				_conflict = false;
				reset();
			}
			// The assignments in effect in the last walk.
			_assigned = _found;
			for (Assignment assignment : _assigned.values()) {
				Parameter parameter = _structure.parameter(assignment.target());
				if (parameter != null) {
					_assignedParameters.put(parameter, assignment);
					// The memory of an assigned parameter is written, also where no placement of it is shown.
					_activeViews.putIfAbsent(parameter, assignment.target());
				}
			}
		}
	}

	/**
	 * Whether the walk just done read every placement with the value it has with the assignments the
	 * walk found in effect: then walking again changes nothing. A program without assignments, or
	 * whose assignments precede every switch on their targets, settles with one walk.
	 */
	private boolean settled() {
		if (_conflict) {
			return false;
		}
		for (Map.Entry<Object, Object[]> entry : _reads.entrySet()) {
			Assignment assignment = _found.get(entry.getKey());
			Object[] read = entry.getValue();
			String effective = assignment != null ? assignment.value() : stored((ParameterView) read[0]);
			if (!java.util.Objects.equals(read[1], effective)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Forgets what a walk computed, for walking again with other assignments.
	 */
	private void reset() {
		_instances.clear();
		_instanceIds.clear();
		_moduleProblems.clear();
		_repeatPath = new ArrayList<>();
		_allocators.clear();
		_activeViews.clear();
		_activeInterfaces.clear();
		_selectedCases.clear();
		_switches.clear();
		_pages.clear();
		_items.clear();
		_inputs.clear();
		_parents.clear();
		_renames.clear();
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
			if (ModuleParts.refersTo(value, ModuleParts.PARAMETER, view)) {
				return value;
			}
		}
		return defaultHolder(view);
	}

	/**
	 * A placement of a parameter in a module instance: the placement of the template and the ID of
	 * the instance.
	 */
	private record PartKey(TLObject template, String instance) {
		// Value.
	}

	/**
	 * The key of a placement in {@link #_settings}: the placement itself, for a placement in a module
	 * instance its template and instance.
	 */
	private static Object key(ParameterView view) {
		InstanceInfo instance = ModuleParts.instance(view);
		return instance == null ? view : new PartKey(ModuleParts.template(view), instance.getId());
	}

	/**
	 * The object holding the effective value of the given placement for the device of this
	 * configuration, see {@link #valueHolder(TLObject, ParameterView)}.
	 */
	public TLObject valueHolder(ParameterView view) {
		TLObject setting = _settings.get(key(view));
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
		Object key = key(view);
		Assignment assignment = _assigned.get(key);
		String result = assignment != null ? assignment.value() : stored(view);
		if (_walking) {
			Object[] read = _reads.putIfAbsent(key, new Object[] { view, result });
			if (read != null && !java.util.Objects.equals(read[1], result)) {
				_conflict = true;
			}
		}
		return result;
	}

	/**
	 * The value of the given placement the device holds (its setting, else the default) in ETS
	 * notation, regardless of assignments.
	 */
	private String stored(ParameterView view) {
		TLObject setting = _settings.get(key(view));
		return setting != null ? ParameterValues.encode(setting) : _structure.defaultValue(view);
	}

	/**
	 * Whether an assignment in effect gives the given placement its value (haumacher/ehome #142): the
	 * value is then derived, not set by a person.
	 */
	public boolean isAssigned(ParameterView view) {
		return _assigned.containsKey(key(view));
	}

	/**
	 * The placement whose value an assignment in effect gives the given placement, <code>null</code> if
	 * it is not assigned or assigned a constant.
	 */
	public ParameterView getAssignmentSource(ParameterView view) {
		Assignment assignment = _assigned.get(key(view));
		return assignment == null ? null : assignment.source();
	}

	/**
	 * The object holding the effective value of the given placement: for a placement an assignment in
	 * effect gives its value, the holder of the source's effective value or the assignment holding the
	 * constant; otherwise {@link #valueHolder(ParameterView)}.
	 */
	public TLObject effectiveHolder(ParameterView view) {
		ParameterView current = view;
		for (int depth = 0; depth < MAX_PASSES; depth++) {
			Assignment assignment = _assigned.get(key(current));
			if (assignment == null) {
				break;
			}
			if (assignment.source() == null) {
				return assignment.assign();
			}
			current = assignment.source();
		}
		return valueHolder(current);
	}

	/**
	 * The parameters in effect, each with the first placement in effect, in the order of the
	 * structure; then the parameters only an assignment in effect gives a value (haumacher/ehome #142),
	 * each with the target of the assignment. Their memory is written in a download
	 * ({@link #encode(ParameterView)}).
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
			if (option instanceof Rename rename) {
				// Another title of a block while this part is in effect (haumacher/ehome #122).
				InstanceInfo instance = ModuleParts.instance(fragment);
				if (rename.getTarget() != null) {
					_renames.put(ModuleParts.wrap(rename.getTarget(), instance),
						ModuleParts.substitute(rename.getText(), instance));
				}
				continue;
			}
			if (option instanceof Assign assign) {
				// A value the program gives a placement while this part is in effect (haumacher/ehome #142).
				InstanceInfo instance = ModuleParts.instance(fragment);
				if (assign.getParameter() != null) {
					ParameterView target = (ParameterView) ModuleParts.wrap(assign.getParameter(), instance);
					ParameterView source =
						assign.getSource() == null ? null : (ParameterView) ModuleParts.wrap(assign.getSource(), instance);
					String assigned = source != null ? value(source) : ParameterValues.encode(assign);
					Object key = key(target);
					// The last assignment in the order of the structure counts; it takes effect at once.
					Assignment assignment = new Assignment(target, source, assign, assigned);
					_found.remove(key);
					_found.put(key, assignment);
					_assigned.put(key, assignment);
				}
				continue;
			}
			walk(option, page);
		}
	}

	/**
	 * The title the given block or channel has under the device's values (ETS 6: the text of the last
	 * <code>Rename</code> of it in effect, haumacher/ehome #122); <code>null</code> if no rename of it is
	 * in effect.
	 */
	public ResKey getRename(Object part) {
		return _renames.get(part);
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
		} else if (option instanceof Button button) {
			// A button of the parameter dialog at its place (haumacher/ehome #158).
			items(page).addButton(button);
		} else if (option instanceof ModulePlacement placement) {
			place(placement, page);
		} else if (option instanceof Repeat repeat) {
			int count = count(repeat);
			Integer order = ModuleParts.template(repeat).getOrder();
			for (int iteration = 1; iteration <= count; iteration++) {
				_repeatPath.add(new int[] { order == null ? 0 : order.intValue(), iteration });
				walk((ConfigFragment) repeat, page);
				_repeatPath.remove(_repeatPath.size() - 1);
			}
		} else if (option instanceof Switch choice) {
			_switches.add(choice);
			for (Case selected : selectAll(choice)) {
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

	/**
	 * How many times a repeat places its content: the value of its parameter, else its count.
	 */
	private int count(Repeat repeat) {
		ParameterView counter = repeat.getCountParameter();
		if (counter != null) {
			long value = InstanceInfo.parse(value(counter));
			return (int) Math.max(0, Math.min(value, 10000));
		}
		Integer count = repeat.getCount();
		return count == null ? 0 : Math.max(0, count.intValue());
	}

	/**
	 * Computes the instance of a placement of a module in effect and walks the structure of the module
	 * in it.
	 */
	private void place(ModulePlacement placement, Object page) {
		ModulePlacement template = ModuleParts.template(placement);
		InstanceInfo parent = ModuleParts.instance(placement);
		ModuleDefinition definition = template.getModule();
		if (definition == null) {
			return;
		}
		List<int[]> index = new ArrayList<>(_repeatPath);
		Integer order = template.getOrder();
		index.add(new int[] { order == null ? 0 : order.intValue(), 1 });
		StringBuilder counters = new StringBuilder();
		StringBuilder repeatIndex = new StringBuilder();
		for (int n = 0; n < index.size(); n++) {
			int[] entry = index.get(n);
			if (n > 0) {
				repeatIndex.append(' ');
			}
			repeatIndex.append(entry[0]).append('x').append(entry[1]);
			if (n < index.size() - 1) {
				if (counters.length() > 0) {
					counters.append('-');
				}
				counters.append(entry[1]);
			}
		}
		String number = counters.length() == 0 ? "1" : counters.toString();
		String id = (parent == null ? local(template.getExtId(), _program.getExtId())
			: parent.getId() + "_" + local(template.getExtId(), parent.getDefinition().getExtId())) + "_MI-" + number;
		if (!_instanceIds.add(id)) {
			// Placed once more under the same ID (a shared case): the first placement counts.
			return;
		}
		Map<ModuleArgument, String> values = arguments(template, parent, id);
		InstanceInfo instance = new InstanceInfo(_device, id, template, parent, repeatIndex.toString(), values,
			_storedInstances.get(id));
		_instances.add(instance);
		List<int[]> outer = _repeatPath;
		_repeatPath = index;
		try {
			walk((ConfigFragment) ModuleParts.wrap(definition, instance), page);
		} finally {
			_repeatPath = outer;
		}
	}

	/**
	 * The ID of a part relative to the given scope (the program or the module definition containing it).
	 */
	private static String local(String id, String scope) {
		if (id == null) {
			return "";
		}
		return scope != null && id.startsWith(scope + "_") ? id.substring(scope.length() + 1) : id;
	}

	/**
	 * The values of the arguments of an instance of the given placement: a fixed value, or the next
	 * value of an allocator (which then advances by the argument's Allocates), plus the value of the
	 * enclosing instance's argument a base names.
	 */
	private Map<ModuleArgument, String> arguments(ModulePlacement placement, InstanceInfo parent, String id) {
		Map<ModuleArgument, String> result = new LinkedHashMap<>();
		for (PlacementArgument argument : placement.getArguments()) {
			ModuleArgument definition = argument.getArgument();
			if (definition == null) {
				continue;
			}
			Allocator allocator = argument.getAllocator();
			String value;
			if (allocator != null) {
				Object key = ModuleParts.definitionOf(allocator) == null || parent == null ? allocator
					: List.of(allocator, parent.getId());
				Long start = allocator.getStart();
				long current = _allocators.getOrDefault(key, Long.valueOf(start == null ? 0 : start.longValue()));
				Long step = definition.getAllocates();
				_allocators.put(key, Long.valueOf(current + (step == null ? 1 : step.longValue())));
				Long max = allocator.getMaxInclusive();
				if (max != null && current > max.longValue()) {
					_moduleProblems.add(I18NConstants.ERROR_ALLOCATOR_LIMIT__MODULE_INSTANCE_ALLOCATOR_LIMIT.fill(
						moduleName(placement), id, allocator.getName(), max));
				}
				value = Long.toString(current);
			} else {
				value = argument.getValue();
			}
			ModuleArgument base = argument.getBase();
			if (base != null && parent != null) {
				value = Long.toString(InstanceInfo.parse(value) + parent.number(base));
			}
			result.put(definition, value);
		}
		return result;
	}

	private static String moduleName(ModulePlacement placement) {
		if (placement.getName() != null && !placement.getName().isEmpty()) {
			return placement.getName();
		}
		ModuleDefinition definition = placement.getModule();
		return definition == null ? placement.getExtId() : definition.getName();
	}

	/**
	 * The module instances in effect for the device's values, in the order of the structure (an
	 * instance of a sub-module after the instance of its enclosing module).
	 */
	public List<InstanceInfo> getInstances() {
		return Collections.unmodifiableList(_instances);
	}

	/**
	 * Why the device's values give no valid layout of its modules (an allocator beyond its limit);
	 * empty if they do.
	 */
	public List<ResKey> getModuleProblems() {
		return Collections.unmodifiableList(_moduleProblems);
	}

	/**
	 * The communication objects of the device: those of the program, then those of the module
	 * instances in effect, as they are in their instance.
	 */
	public List<Port> getPorts() {
		List<Port> result = new ArrayList<>();
		if (_program == null) {
			return result;
		}
		result.addAll(_program.getPorts());
		for (InstanceInfo instance : _instances) {
			for (Port port : instance.getDefinition().getPorts()) {
				result.add((Port) ModuleParts.wrap(port, instance));
			}
		}
		// The objects of the placements not in effect whose arguments are fixed: their numbers are
		// known and taken in the object table, as those of the program's objects not in effect.
		for (ModulePlacement placement : fixedPlacements(_program, new ArrayList<>())) {
			String id = local(placement.getExtId(), _program.getExtId()) + "_MI-1";
			if (_instanceIds.contains(id) || placement.getModule() == null) {
				continue;
			}
			Map<ModuleArgument, String> values = new LinkedHashMap<>();
			for (PlacementArgument argument : placement.getArguments()) {
				values.put(argument.getArgument(), argument.getValue());
			}
			InstanceInfo instance = new InstanceInfo(_device, id, placement, null, null, values, null);
			for (Port port : placement.getModule().getPorts()) {
				result.add((Port) ModuleParts.wrap(port, instance));
			}
		}
		return result;
	}

	/**
	 * The placements of modules in the structure of the program (in every case, not in a repeat)
	 * whose arguments all have fixed values.
	 */
	private static List<ModulePlacement> fixedPlacements(TLObject fragment, List<ModulePlacement> result) {
		Collection<?> contents = fragment instanceof ConfigFragment config ? config.getContents()
			: fragment instanceof Switch choice ? choice.getAlternatives() : List.of();
		for (Object element : contents) {
			if (element instanceof ModulePlacement placement) {
				if (placement.getArguments().stream()
					.allMatch(a -> a.getAllocator() == null && a.getBase() == null && a.getArgument() != null)) {
					result.add(placement);
				}
			} else if (element instanceof Switch || element instanceof ConfigFragment && !(element instanceof Repeat)) {
				fixedPlacements((TLObject) element, result);
			}
		}
		return result;
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
		Assignment assignment = _assignedParameters.get(_structure.parameter(view));
		if (assignment != null) {
			// The memory of an assigned parameter holds the assigned value.
			ParameterType type = _structure.parameter(assignment.target()).getType();
			return ParameterEncoding.encode(type, assignment.value(), _program);
		}
		TLObject setting = _settings.get(key(view));
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
	 * @return The {@link ParameterView}s, {@link Interface}s and {@link Button}s; empty for anything that
	 *         is no page in effect.
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
	 * Whether the value of the given placement can be changed: its own access allows it, no block
	 * around it is hidden (access <code>None</code>), and its parameter has a value at all.
	 *
	 * <p>
	 * The access of a block does not make the parameters in it read-only: a block's
	 * <code>Access="Read"</code> concerns the block itself (e.g. its title parameter), not its
	 * contents. The program STYA662AN (M-0009_A-E0CE-10-8EBD) declares 41 of its 43 blocks
	 * <code>Access="Read"</code>, and its parameters in them are edited in ETS (a project holds values
	 * other than their defaults); until 2026-11-08 E-Home showed all of them read-only.
	 * </p>
	 */
	public static boolean isEditable(ParameterView view) {
		if (!editable(accessName(view))) {
			return false;
		}
		for (TLObject container = view.tContainer(); container != null; container = container.tContainer()) {
			if (container instanceof ParameterGroup group && group.getAccess() != null
				&& "None".equals(group.getAccess().getName())) {
				return false;
			}
		}
		return !(ParameterValues.valueType(view) instanceof VoidType);
	}

	private static boolean editable(String access) {
		return "ReadWrite".equals(access) || "Write".equals(access);
	}

	/**
	 * The first case of the given switch that is in effect, see {@link #selectAll(Switch)};
	 * <code>null</code> if none.
	 */
	public Case select(Switch choice) {
		List<Case> selected = selectAll(choice);
		return selected.isEmpty() ? null : selected.get(0);
	}

	/**
	 * The cases of the given switch that are in effect, in their order: every case whose pattern matches
	 * the effective value of the switch's input, else the (first) default case; empty if none.
	 */
	public List<Case> selectAll(Switch choice) {
		Structure.SwitchInfo info = _structure.info(choice);
		String value = info.input() == null ? null : value(info.input());
		List<Case> result = new ArrayList<>(1);
		Case fallback = null;
		for (int n = 0, cnt = info.alternatives().size(); n < cnt; n++) {
			String pattern = info.patterns().get(n);
			if (pattern != null && matches(pattern, value)) {
				result.add(info.alternatives().get(n));
			} else if (info.defaults().get(n).booleanValue() && fallback == null) {
				fallback = info.alternatives().get(n);
			}
		}
		if (result.isEmpty() && fallback != null) {
			result.add(fallback);
		}
		return result;
	}

	/**
	 * Whether the given case of the given switch is in effect, see {@link #selectAll(Switch)}.
	 */
	public boolean isSelected(Switch choice, Case alternative) {
		return selectAll(choice).contains(alternative);
	}

	/**
	 * The cases of the given switch a value of its input in ETS notation takes, see
	 * {@link #selectAll(Switch)}.
	 */
	public static List<Case> casesFor(Switch choice, String value) {
		List<Case> result = new ArrayList<>(1);
		Case fallback = null;
		for (Case alternative : choice.getAlternatives()) {
			if (alternative.getPattern() != null && matches(alternative.getPattern(), value)) {
				result.add(alternative);
			} else if (alternative.getIsDefault() && fallback == null) {
				fallback = alternative;
			}
		}
		if (result.isEmpty() && fallback != null) {
			result.add(fallback);
		}
		return result;
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
	 * marked inactive whose module instance (if any) is in effect, of the device's program (not of a
	 * program the device ran before).
	 */
	public List<TLObject> getConnectors() {
		List<TLObject> result = new ArrayList<>();
		for (Object connector : (Collection<?>) _device.tValueByName("connectors")) {
			TLObject value = (TLObject) connector;
			if (Boolean.FALSE.equals(value.tValueByName("active"))) {
				continue;
			}
			String instance = ModuleParts.instanceId(value);
			if (instance != null && !_instanceIds.contains(instance)) {
				// An object of a module instance the values no longer place.
				continue;
			}
			if (ModuleParts.programOf(ModuleParts.port(value)) != _program) {
				// An object of another program: the device was switched to another program.
				continue;
			}
			result.add(value);
		}
		return result;
	}

}
