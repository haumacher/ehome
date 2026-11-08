/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.xio.importer.binding.ImportContext;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Allocator;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.ModuleArgument;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ModulePlacement;
import de.haumacher.ehome.model.operational.PlacementArgument;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;

/**
 * The module instances of the devices an import reads (haumacher/ehome #119, rule 5 of the design).
 *
 * <p>
 * A device names the parameters and objects of its module instances by IDs of the instance
 * (<code>&lt;program&gt;_MD-1_M-1_MI-1_P-3_R-1</code>, <code>MD-1_M-1_MI-1_O-2-0_R-1</code>), which no
 * part of the program has: the import records them ({@link #pending(TLObject, String, String)}) instead
 * of resolving them. After the import, {@link #attach(List, BufferingI18NLog)} computes the instances of each
 * device from its program and its imported values (the instances depend on the values of their own
 * parameters: a repeat counted by a parameter of a module, so the computation is repeated until no
 * further part is attached), stores them, and lets each setting and connector name the part of the
 * module definition and its instance. A setting's value is read for the type of its parameter then.
 * A part of an instance E-Home does not compute for the device's values (ETS keeps the values of a
 * placement in a case not taken) is attached to a stored instance not in effect, which keeps it until
 * a change of a value brings the instance into effect; it is left out (and reported per device) only
 * if no placement or part of a module has its ID.
 * </p>
 *
 * <p>
 * Then it compares the argument values E-Home computes with those the project file lists for the
 * device (<code>ModuleInstances</code>, {@link #arguments(TLObject, String, String, String)}). The file
 * names an argument fed by an allocator by the allocator, and gives a value relative to its base
 * argument as it is written in the placement; these and E-Home's own value count as the same. On a
 * difference, the import warns naming the device, the instance and both values, and records the
 * file's value on the instance's argument: the download of the device is refused until a full
 * download the user confirms (see <code>DownloadPlanner</code>).
 * </p>
 */
final class ModuleImport {

	/**
	 * The {@link ModuleImport} of an import in its {@link ImportContext}.
	 */
	static final Property<ModuleImport> PROPERTY = TypedAnnotatable.property(ModuleImport.class, "moduleImport");

	private static final String EXT_ID = "extId";

	private static final String TEXT = "text";

	/** A reference of a setting or connector to a part of a module instance. */
	private record Pending(TLObject holder, String attribute, String id) {
		// Value.
	}

	private final Map<TLObject, List<Pending>> _pending = new LinkedHashMap<>();

	/** Per device, the arguments the file lists per instance ID: argument ID relative to the program -> value. */
	private final Map<TLObject, Map<String, Map<String, String>>> _arguments = new LinkedHashMap<>();

	/** The placements of modules of a program by their IDs. */
	private final Map<Program, Map<String, ModulePlacement>> _placements = new HashMap<>();

	/** The parts of the module definitions of a program by their IDs. */
	private final Map<Program, Map<String, TLObject>> _templates = new HashMap<>();

	/**
	 * The {@link ModuleImport} of the given import, created on first use.
	 */
	static ModuleImport get(ImportContext context) {
		ModuleImport result = context.get(PROPERTY);
		if (result == null) {
			result = new ModuleImport();
			context.set(PROPERTY, result);
		}
		return result;
	}

	/**
	 * Whether the given full ID names a part of a module instance.
	 */
	static boolean isInstancePart(String id) {
		return id.contains("_MI-");
	}

	/**
	 * Records a reference of a setting or connector of a device to a part of a module instance.
	 *
	 * @param holder
	 *        The setting or connector, whose container is the device.
	 * @param attribute
	 *        The attribute naming the part (<code>parameter</code>, <code>port</code>).
	 * @param id
	 *        The full ID of the part in the instance.
	 */
	void pending(TLObject holder, String attribute, String id) {
		_pending.computeIfAbsent(holder, x -> new ArrayList<>()).add(new Pending(holder, attribute, id));
	}

	/**
	 * Records an argument of a module instance as the project file lists it.
	 *
	 * @param device
	 *        The device.
	 * @param instance
	 *        The ID of the instance (relative to the program).
	 * @param argument
	 *        The ID of the argument, relative to the program (sub-module: to its enclosing module).
	 * @param value
	 *        The value.
	 */
	void arguments(TLObject device, String instance, String argument, String value) {
		_arguments.computeIfAbsent(device, x -> new LinkedHashMap<>())
			.computeIfAbsent(instance, x -> new LinkedHashMap<>()).put(argument, value);
	}

	/**
	 * Computes and stores the module instances of the devices the import read, attaches their
	 * settings and connectors and compares the argument values with the file (see the class comment).
	 */
	void attach(List<TLObject> devices, BufferingI18NLog log) {
		Map<TLObject, List<Pending>> byDevice = new LinkedHashMap<>();
		for (TLObject device : devices) {
			Program program = DeviceConfiguration.program(device);
			if (program != null && !program.getModules().isEmpty()) {
				byDevice.put(device, new ArrayList<>());
			}
		}
		for (List<Pending> references : _pending.values()) {
			for (Pending reference : references) {
				TLObject device = reference.holder().tContainer();
				if (device != null && reference.holder().tValid()) {
					byDevice.computeIfAbsent(device, x -> new ArrayList<>()).add(reference);
				}
			}
		}
		for (TLObject device : _arguments.keySet()) {
			byDevice.computeIfAbsent(device, x -> new ArrayList<>());
		}
		for (Map.Entry<TLObject, List<Pending>> entry : byDevice.entrySet()) {
			TLObject device = entry.getKey();
			if (!device.tValid()) {
				continue;
			}
			attach(device, entry.getValue());
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			ModuleInstances.store(device, configuration.getInstances());
			for (ResKey problem : configuration.getModuleProblems()) {
				log.log(Level.WARN, I18NConstants.WARN_MODULE_LAYOUT__DEVICE_PROBLEM.fill(label(device), problem));
			}
			compare(device, configuration.getInstances(), log);
		}
	}

	private void attach(TLObject device, List<Pending> references) {
		Program program = DeviceConfiguration.program(device);
		if (program == null) {
			return;
		}
		Map<String, TLObject> templates = templates(program);
		String programPrefix = program.getExtId() + "_";
		List<Pending> open = new ArrayList<>(references);
		// Each round may compute further instances: those placed by repeats counted by parameters of
		// the instances attached in the round before.
		for (int round = 0; round < 32 && !open.isEmpty(); round++) {
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			ModuleInstances.store(device, configuration.getInstances());
			Map<String, Object[]> prefixes = prefixes(programPrefix, configuration.getInstances());
			boolean attached = false;
			for (Iterator<Pending> it = open.iterator(); it.hasNext();) {
				Pending reference = it.next();
				Object[] match = match(prefixes, reference.id());
				if (match == null) {
					continue;
				}
				InstanceInfo instance = (InstanceInfo) match[0];
				TLObject template = templates.get(match[1]);
				if (template == null) {
					continue;
				}
				TLObject holder = reference.holder();
				ModuleParts.bind(holder, reference.attribute(), ModuleParts.wrap(template, instance));
				if (ModuleParts.PARAMETER.equals(reference.attribute()) && holder.tValueByName(TEXT) instanceof String raw) {
					// Read without its parameter, the value is kept as text: now it gets its type.
					holder.tUpdateByName(TEXT, null);
					ParameterValues.decode(holder, raw);
				}
				it.remove();
				attached = true;
			}
			if (!attached) {
				break;
			}
		}
		// The parts of instances E-Home does not compute for the device's values (a placement in a case
		// not taken, a repetition beyond the count): kept in an instance not in effect, like an instance
		// a change of a value removes; left out (and reported) only if no placement has the ID.
		for (Pending reference : open) {
			keepInactive(device, program, programPrefix, templates, reference);
		}
	}

	private static final Pattern TOP_INSTANCE = Pattern.compile("^(MD-\\d+_M-\\d+)_MI-([0-9-]+)_(.+)$");

	private static final Pattern SUB_INSTANCE = Pattern.compile("^(SM-\\d+_M-\\d+)_MI-([0-9-]+)_(.+)$");

	/**
	 * Attaches a part of an instance E-Home does not compute to a stored instance not in effect, see
	 * {@link ModuleInstances#inactive(TLObject, String, TLObject, TLObject)}.
	 */
	private void keepInactive(TLObject device, Program program, String programPrefix, Map<String, TLObject> templates,
			Pending reference) {
		String id = reference.id();
		if (!id.startsWith(programPrefix)) {
			return;
		}
		Matcher top = TOP_INSTANCE.matcher(id.substring(programPrefix.length()));
		if (!top.matches()) {
			return;
		}
		Map<String, ModulePlacement> placements = placements(program);
		ModulePlacement placement = placements.get(programPrefix + top.group(1));
		if (placement == null || placement.getModule() == null) {
			return;
		}
		String name = top.group(1) + "_MI-" + top.group(2);
		String rest = top.group(3);
		TLObject stored = ModuleInstances.inactive(device, name, placement, null);
		while (true) {
			ModuleDefinition definition = placement.getModule();
			Matcher sub = SUB_INSTANCE.matcher(rest);
			ModulePlacement inner = sub.matches() ? placements.get(definition.getExtId() + "_" + sub.group(1)) : null;
			if (inner == null || inner.getModule() == null) {
				break;
			}
			name = name + "_" + sub.group(1) + "_MI-" + sub.group(2);
			rest = sub.group(3);
			placement = inner;
			stored = ModuleInstances.inactive(device, name, placement, stored);
		}
		TLObject template = templates.get(placement.getModule().getExtId() + "_" + rest);
		if (template == null) {
			return;
		}
		TLObject holder = reference.holder();
		holder.tUpdateByName(reference.attribute(), template);
		holder.tUpdateByName(ModuleParts.INSTANCE, stored);
		if (ModuleParts.PARAMETER.equals(reference.attribute()) && holder.tValueByName(TEXT) instanceof String raw) {
			holder.tUpdateByName(TEXT, null);
			ParameterValues.decode(holder, raw);
		}
	}

	/**
	 * The placements of modules of the given program (in its structure and in its module
	 * definitions) by their IDs.
	 */
	private Map<String, ModulePlacement> placements(Program program) {
		return _placements.computeIfAbsent(program, p -> {
			Map<String, ModulePlacement> result = new HashMap<>();
			Deque<Object> todo = new ArrayDeque<>();
			todo.add(p);
			while (!todo.isEmpty()) {
				Object obj = todo.removeFirst();
				if (obj instanceof ModulePlacement placement) {
					result.put(placement.getExtId(), placement);
				}
				if (obj instanceof Program root) {
					todo.addAll(root.getModules());
				}
				if (obj instanceof ModuleDefinition definition) {
					todo.addAll(definition.getModules());
				}
				if (obj instanceof ConfigFragment fragment) {
					todo.addAll(fragment.getContents());
				}
				if (obj instanceof Switch choice) {
					todo.addAll(choice.getAlternatives());
				}
			}
			return result;
		});
	}

	/**
	 * The beginnings of the IDs of the parts of the given instances: per beginning the instance and
	 * the beginning of the IDs of the parts of its module definition.
	 *
	 * <p>
	 * A part of an instance is the program's ID, the instance's ID and the part's ID within its module
	 * definition. ETS writes the part of an instance of a sub-module also with the ID of the
	 * outermost placement and the instance number of the sub-module, followed by the part's ID within
	 * the outermost definition (<code>MD-4_M-15_MI-1-1-2_SM-1_P-1_R-1</code>); both are accepted.
	 * </p>
	 */
	private static Map<String, Object[]> prefixes(String programPrefix, List<InstanceInfo> instances) {
		Map<String, Object[]> result = new HashMap<>();
		for (InstanceInfo instance : instances) {
			String definition = instance.getDefinition().getExtId() + "_";
			result.put(programPrefix + instance.getId() + "_", new Object[] { instance, definition });
			InstanceInfo root = instance;
			while (root.getParent() != null) {
				root = root.getParent();
			}
			if (root != instance) {
				String id = instance.getId();
				String number = id.substring(id.lastIndexOf("_MI-") + 4);
				String rootId = root.getId();
				String placement = rootId.substring(0, rootId.lastIndexOf("_MI-"));
				result.putIfAbsent(programPrefix + placement + "_MI-" + number + "_",
					new Object[] { instance, root.getDefinition().getExtId() + "_" });
			}
		}
		return result;
	}

	/**
	 * The instance and the ID of the template part of the given ID of a part of an instance, by the
	 * longest beginning that fits; <code>null</code> if none fits.
	 */
	private static Object[] match(Map<String, Object[]> prefixes, String id) {
		for (int index = id.lastIndexOf('_'); index > 0; index = id.lastIndexOf('_', index - 1)) {
			Object[] entry = prefixes.get(id.substring(0, index + 1));
			if (entry != null) {
				return new Object[] { entry[0], entry[1] + id.substring(index + 1) };
			}
		}
		return null;
	}

	/**
	 * The parts of the module definitions of the given program by their IDs.
	 */
	private Map<String, TLObject> templates(Program program) {
		return _templates.computeIfAbsent(program, p -> {
			Map<String, TLObject> result = new HashMap<>();
			Deque<TLObject> todo = new ArrayDeque<>(p.getModules());
			while (!todo.isEmpty()) {
				TLObject obj = todo.removeFirst();
				if (obj.tType().getPart(EXT_ID) != null && obj.tValueByName(EXT_ID) instanceof String id) {
					result.putIfAbsent(id, obj);
				}
				for (TLStructuredTypePart part : ((TLClass) obj.tType()).getAllParts()) {
					if (part instanceof TLReference reference && reference.isComposite() && !reference.isBackwards()
						&& !reference.isDerived()) {
						Object value = obj.tValue(reference);
						if (value instanceof Collection<?> parts) {
							for (Object element : parts) {
								todo.add((TLObject) element);
							}
						} else if (value instanceof TLObject element) {
							todo.add(element);
						}
					}
				}
			}
			return result;
		});
	}

	/**
	 * Compares the computed argument values of the device's instances with those of the file and
	 * records the differences.
	 */
	private void compare(TLObject device, List<InstanceInfo> instances, BufferingI18NLog log) {
		Map<String, Map<String, String>> listed = _arguments.get(device);
		if (listed == null) {
			return;
		}
		for (InstanceInfo instance : instances) {
			Map<String, String> values = listed.get(instance.getId());
			if (values == null) {
				continue;
			}
			TLObject stored = ModuleInstances.stored(instance);
			for (Map.Entry<String, String> entry : values.entrySet()) {
				PlacementArgument argument = placementArgument(instance, entry.getKey());
				if (argument == null) {
					continue;
				}
				ModuleArgument definition = argument.getArgument();
				String computed = instance.value(definition);
				String file = entry.getValue();
				if (same(argument, computed, file)) {
					continue;
				}
				log.log(Level.WARN, I18NConstants.WARN_ARGUMENT_DIFFERS__DEVICE_INSTANCE_ARGUMENT_FILE_EHOME.fill(
					label(device), instance.getId(), definition.getName(), file, computed));
				for (Object element : (Collection<?>) stored.tValueByName(ModuleInstances.ARGUMENTS)) {
					TLObject value = (TLObject) element;
					if (value.tValueByName(ModuleInstances.ARGUMENT) == definition) {
						value.tUpdateByName(ModuleInstances.DEVICE_VALUE, file);
					}
				}
			}
		}
	}

	/**
	 * The argument of the instance's placement whose argument the file names by the given ID
	 * (relative to the program, or for a sub-module to its enclosing module definition).
	 */
	private static PlacementArgument placementArgument(InstanceInfo instance, String id) {
		for (PlacementArgument argument : instance.getPlacement().getArguments()) {
			ModuleArgument definition = argument.getArgument();
			String extId = definition == null ? null : definition.getExtId();
			if (extId != null && (extId.equals(id) || extId.endsWith("_" + id))) {
				return argument;
			}
		}
		return null;
	}

	/**
	 * Whether the file's value of an argument is E-Home's: the same value, the value the placement
	 * gives before its base is added, or the allocator that feeds it.
	 */
	private static boolean same(PlacementArgument argument, String computed, String file) {
		if (Objects.equals(computed, file) || numericEquals(computed, file)) {
			return true;
		}
		if (argument.getBase() != null && (Objects.equals(argument.getValue(), file)
			|| numericEquals(argument.getValue(), file))) {
			return true;
		}
		Allocator allocator = argument.getAllocator();
		if (allocator != null && file != null) {
			String extId = allocator.getExtId();
			return extId != null && (extId.equals(file) || extId.endsWith("_" + file));
		}
		return false;
	}

	private static boolean numericEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		try {
			return Double.parseDouble(a.trim()) == Double.parseDouble(b.trim());
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	private static String label(TLObject device) {
		return MetaLabelProvider.INSTANCE.getLabel(device);
	}

}
