/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.planning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.scan.InstallationBuilder;
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.modules.ModuleParts;

/**
 * Planning devices in the app (haumacher/ehome #104): a new device from the catalog in a line, and
 * the duplicate of a device with its parameter settings.
 *
 * <p>
 * A planned device is created like a device the scan finds ({@link InstallationBuilder#createDevice(TLObject, int, TLObject, TLObject, Collection)}):
 * of a product variant of the catalog (<code>ehome.catalog:Variant</code>), running the program of
 * one of the program assignments of its product (<code>ehome.catalog:ProgramAssignment</code>),
 * with a device number free in its line, and with an external ID. Nothing of its configuration is
 * in the real device yet (<code>loaded</code> is empty), so it needs a full download, which writes
 * the individual address and the program. It has no connections; the communication objects get
 * their connectors when they are connected.
 * </p>
 *
 * <p>
 * The operations changing the model must run in a transaction; they refuse with a
 * {@link TopLogicException} naming the reason. They are not access checked: the commands calling
 * them check <code>Write</code> on the line or the device.
 * </p>
 */
public final class DevicePlanning {

	private static final String LINE = "ehome.physical:Line";

	private static final String AREA = "ehome.physical:Area";

	private static final String DEVICE = "ehome.physical:Device";

	private static final String PROJECT = "ehome.project:Project";

	private static final String BUILDING_DEFINITION = "ehome.building:BuildingDefinition";

	private static final String ADDRESS = "address";

	private static final String DEVICES = "devices";

	private static final String SETTINGS = "settings";

	/** The lowest device number of a line. */
	public static final int MIN_NUMBER = 0;

	/** The highest device number of a line. */
	public static final int MAX_NUMBER = 255;

	/** The lowest device number proposed: 0 is the number of the coupler of a line. */
	private static final int FIRST_PROPOSED = 1;

	private DevicePlanning() {
		// Utility.
	}

	/**
	 * The product variants of the catalog a device can be planned with: those whose product runs
	 * at least one application program, by manufacturer, name and order number.
	 */
	public static List<TLObject> variants() {
		TLModule module = TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule();
		TLObject root = module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
		List<TLObject> result = new ArrayList<>();
		for (TLObject manufacturer : ExtIds.list(root, "manufacturers")) {
			for (TLObject product : ExtIds.list(manufacturer, "products")) {
				if (programs(product).isEmpty()) {
					continue;
				}
				result.addAll(ExtIds.list(product, "variants"));
			}
		}
		result.sort(Comparator.comparing(DevicePlanning::variantLabel, String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	/**
	 * The label a product variant is offered with, and searched by: its manufacturer, its name
	 * and its order number.
	 */
	public static String variantLabel(TLObject variant) {
		if (variant == null) {
			return "";
		}
		StringBuilder result = new StringBuilder();
		TLObject product = product(variant);
		TLObject manufacturer = product == null ? null : product.tContainer();
		if (manufacturer != null && manufacturer.tValueByName("name") instanceof String name && !name.isBlank()) {
			result.append(name).append(" · ");
		}
		result.append(MetaLabelProvider.INSTANCE.getLabel(variant));
		if (variant.tValueByName("orderNumber") instanceof String orderNumber && !orderNumber.isBlank()) {
			result.append(" · ").append(orderNumber);
		}
		return result.toString();
	}

	/**
	 * The program assignments of the product of the given variant that name a program, the newest
	 * program first (the highest application version, then the highest application number).
	 */
	public static List<TLObject> programs(TLObject variantOrProduct) {
		TLObject product = ExtIds.isA(variantOrProduct, "ehome.catalog:Variant") ? product(variantOrProduct)
			: variantOrProduct;
		List<TLObject> result = new ArrayList<>();
		for (TLObject assignment : ExtIds.list(product, "programs")) {
			if (assignment.tValueByName("program") instanceof Program) {
				result.add(assignment);
			}
		}
		result.sort(Comparator.comparingInt((TLObject a) -> version(program(a).getApplicationVersion()))
			.thenComparingInt(a -> version(program(a).getApplicationNumber())).reversed());
		return result;
	}

	/**
	 * The program assignment proposed for a device of the given variant: the newest program of its
	 * product, <code>null</code> if the product runs none.
	 */
	public static TLObject defaultProgram(TLObject variant) {
		List<TLObject> programs = programs(variant);
		return programs.isEmpty() ? null : programs.get(0);
	}

	/**
	 * The label a program assignment is offered with: the name of its program and its version
	 * (ETS writes the application version as major.minor, a nibble each).
	 */
	public static String programLabel(TLObject assignment) {
		if (assignment == null || !(assignment.tValueByName("program") instanceof Program program)) {
			return "";
		}
		String name = MetaLabelProvider.INSTANCE.getLabel(program);
		Integer version = program.getApplicationVersion();
		if (version == null) {
			return name;
		}
		return Resources.getInstance().getString(
			I18NConstants.PROGRAM_LABEL__NAME_VERSION.fill(name, (version >>> 4) + "." + (version & 0x0F)));
	}

	/**
	 * The line a new device is planned in from the given node of the topology: the line itself, or
	 * the line of a device; <code>null</code> for every other node.
	 */
	public static TLObject line(TLObject node) {
		if (node == null || !node.tValid()) {
			return null;
		}
		if (ExtIds.isA(node, LINE)) {
			return node;
		}
		if (ExtIds.isA(node, DEVICE) && node.tContainer() != null && ExtIds.isA(node.tContainer(), LINE)) {
			return node.tContainer();
		}
		return null;
	}

	/**
	 * The device number proposed for a new device in the given line: the first free one above the
	 * highest number in use, or the lowest free one (from 1) if there is none above;
	 * <code>null</code> if the line is full.
	 */
	public static Integer nextFree(TLObject line) {
		if (line == null || !line.tValid()) {
			return null;
		}
		Set<Integer> taken = taken(line);
		int highest = FIRST_PROPOSED - 1;
		for (Integer number : taken) {
			highest = Math.max(highest, number.intValue());
		}
		for (int number = highest + 1; number <= MAX_NUMBER; number++) {
			if (!taken.contains(number)) {
				return number;
			}
		}
		for (int number = FIRST_PROPOSED; number <= highest; number++) {
			if (!taken.contains(number)) {
				return number;
			}
		}
		return null;
	}

	/**
	 * Why a device cannot get the given device number in the given line, <code>null</code> if it
	 * can.
	 */
	public static ResKey addressVeto(TLObject line, Object number) {
		if (line == null || !line.tValid() || !ExtIds.isA(line, LINE)) {
			return I18NConstants.ERROR_NO_LINE;
		}
		if (!(number instanceof Number value)) {
			return I18NConstants.ERROR_NO_NUMBER__LINE.fill(lineText(line));
		}
		if (value.doubleValue() != Math.rint(value.doubleValue()) || value.doubleValue() < MIN_NUMBER
			|| value.doubleValue() > MAX_NUMBER) {
			return I18NConstants.ERROR_NUMBER_RANGE__NUMBER_MIN_MAX.fill(value, MIN_NUMBER, MAX_NUMBER);
		}
		int device = value.intValue();
		for (TLObject other : ExtIds.list(line, DEVICES)) {
			if (other.tValueByName(ADDRESS) instanceof Integer existing && existing.intValue() == device) {
				return I18NConstants.ERROR_ADDRESS_TAKEN__ADDRESS_DEVICE.fill(addressText(line, device),
					GroupAddressConnections.deviceLabel(other));
			}
		}
		return null;
	}

	/**
	 * The text of the individual address a device with the given number gets in the given line,
	 * area.line.device; <code>null</code> without line or number.
	 */
	public static String addressText(TLObject line, Object number) {
		if (line == null || !line.tValid() || !(number instanceof Number value)) {
			return null;
		}
		return lineText(line) + "." + value.intValue();
	}

	/**
	 * The building parts of the project of the given object a device can be placed in, in the
	 * order of the building structure.
	 */
	public static List<TLObject> locations(TLObject obj) {
		TLObject project = ExtIds.ancestor(obj, PROJECT);
		List<TLObject> result = new ArrayList<>();
		for (TLObject building : ExtIds.list(project, "buildings")) {
			result.addAll(BuildingDevices.partsWithin(building));
		}
		return result;
	}

	/**
	 * Plans a new device in the given line.
	 *
	 * @param line
	 *        The line (<code>ehome.physical:Line</code>).
	 * @param variant
	 *        The product variant from the catalog (<code>ehome.catalog:Variant</code>).
	 * @param assignment
	 *        The program assignment of the product of the variant, <code>null</code> for the newest
	 *        (see {@link #defaultProgram(TLObject)}).
	 * @param number
	 *        The device number in the line; it must be free (see {@link #addressVeto(TLObject, Object)}).
	 * @param title
	 *        The name of the device, <code>null</code> or empty to name it by its type and address.
	 * @param location
	 *        The building part of the project the device is placed in, <code>null</code> for none.
	 * @return The new device (<code>ehome.physical:Device</code>).
	 * @throws TopLogicException
	 *         With the reason, if the device cannot be planned so.
	 */
	public static TLObject create(TLObject line, TLObject variant, TLObject assignment, Object number, String title,
			TLObject location) {
		ResKey veto = addressVeto(line, number);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		if (variant == null || !variant.tValid() || !ExtIds.isA(variant, "ehome.catalog:Variant")) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PRODUCT);
		}
		List<TLObject> programs = programs(variant);
		TLObject program = assignment == null ? defaultProgram(variant) : assignment;
		if (program == null || !programs.contains(program)) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PROGRAM__PRODUCT.fill(variantLabel(variant)));
		}
		if (location != null && (!ExtIds.isA(location, BUILDING_DEFINITION) || !locations(line).contains(location))) {
			throw new TopLogicException(I18NConstants.ERROR_OTHER_PROJECT__LOCATION.fill(BuildingDevices.path(location)));
		}
		TLObject device = InstallationBuilder.createDevice(line, ((Number) number).intValue(), variant, program, List.of());
		if (title != null && !title.isBlank()) {
			device.tUpdateByName("title", title.trim());
		}
		if (location != null) {
			BuildingDevices.place(device, location);
		}
		return device;
	}

	/**
	 * Why the given device cannot be duplicated, <code>null</code> if it can.
	 */
	public static ResKey duplicateVeto(TLObject device) {
		if (device == null || !device.tValid() || !ExtIds.isA(device, DEVICE)) {
			return I18NConstants.ERROR_NO_DEVICE;
		}
		TLObject line = line(device);
		if (line == null) {
			return I18NConstants.ERROR_NOT_IN_LINE__DEVICE.fill(GroupAddressConnections.deviceLabel(device));
		}
		if (device.tValueByName("type") == null || device.tValueByName("program") == null) {
			return I18NConstants.ERROR_NO_CATALOG_ENTRY__DEVICE.fill(GroupAddressConnections.deviceLabel(device));
		}
		if (nextFree(line) == null) {
			return I18NConstants.ERROR_LINE_FULL__LINE.fill(lineText(line));
		}
		return null;
	}

	/**
	 * Creates a copy of the given device in its line, at the next free device number (see
	 * {@link #nextFree(TLObject)}): of the same product variant, running the same program, with a
	 * copy of each of its parameter settings, without connections, with nothing loaded.
	 *
	 * @return The copy.
	 * @throws TopLogicException
	 *         With the reason, if the device cannot be duplicated (see {@link #duplicateVeto(TLObject)}).
	 */
	public static TLObject duplicate(TLObject device) {
		ResKey veto = duplicateVeto(device);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		TLObject line = line(device);
		TLObject copy = InstallationBuilder.createDevice(line, nextFree(line).intValue(),
			(TLObject) device.tValueByName("type"), (TLObject) device.tValueByName("program"), List.of());
		// The module instances of the original (haumacher/ehome #119), so that the copied settings of
		// module parameters name the copy's instances of the same IDs.
		Map<String, TLObject> instances = copyInstances(device, copy);
		List<TLObject> settings = new ArrayList<>();
		for (TLObject setting : ExtIds.list(device, SETTINGS)) {
			TLObject copied = copySetting(setting);
			String instance = ModuleParts.instanceId(setting);
			if (instance != null) {
				copied.tUpdateByName(ModuleParts.INSTANCE, instances.get(instance));
			}
			settings.add(copied);
		}
		copy.tUpdateByName(SETTINGS, settings);
		ModuleInstances.update(copy);
		return copy;
	}

	/**
	 * Gives the copy of a device the module instances of the original (with their argument values,
	 * without a recorded difference to a project file).
	 *
	 * @return The copy's instances by their IDs.
	 */
	private static Map<String, TLObject> copyInstances(TLObject device, TLObject copy) {
		Map<TLObject, TLObject> copies = new HashMap<>();
		Map<String, TLObject> result = new HashMap<>();
		List<TLObject> instances = new ArrayList<>();
		for (TLObject instance : ModuleInstances.stored(device)) {
			TLObject copied = create(ModuleInstances.INSTANCE_TYPE);
			for (String attribute : List.of(ModuleInstances.NAME, ModuleInstances.PLACEMENT, ModuleInstances.REPEAT_INDEX,
				ModuleInstances.ACTIVE)) {
				copied.tUpdateByName(attribute, instance.tValueByName(attribute));
			}
			copied.tUpdateByName(ModuleInstances.PARENT, copies.get(instance.tValueByName(ModuleInstances.PARENT)));
			List<TLObject> arguments = new ArrayList<>();
			for (TLObject argument : ExtIds.list(instance, ModuleInstances.ARGUMENTS)) {
				TLObject value = create(ModuleInstances.ARGUMENT_TYPE);
				value.tUpdateByName(ModuleInstances.ARGUMENT, argument.tValueByName(ModuleInstances.ARGUMENT));
				value.tUpdateByName(ModuleInstances.VALUE, argument.tValueByName(ModuleInstances.VALUE));
				arguments.add(value);
			}
			copied.tUpdateByName(ModuleInstances.ARGUMENTS, arguments);
			copies.put(instance, copied);
			result.put((String) instance.tValueByName(ModuleInstances.NAME), copied);
			instances.add(copied);
		}
		for (TLObject existing : ModuleInstances.stored(copy)) {
			existing.tDelete();
		}
		copy.tUpdateByName(ModuleInstances.MODULE_INSTANCES, instances);
		return result;
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	/**
	 * A new parameter setting (<code>ehome.physical:ParameterSetting</code>) with the stored values
	 * of the given one: the parameter it sets and its typed value.
	 */
	private static TLObject copySetting(TLObject setting) {
		TLObject result = ModelService.getInstance().getFactory().createObject((TLClass) setting.tType());
		for (TLStructuredTypePart part : setting.tType().getAllParts()) {
			if (part.isDerived()) {
				continue;
			}
			result.tUpdate(part, setting.tValue(part));
		}
		return result;
	}

	/**
	 * The text of the given line: area.line.
	 */
	public static String lineText(TLObject line) {
		TLObject area = line.tContainer();
		Object areaNumber = area != null && ExtIds.isA(area, AREA) ? area.tValueByName(ADDRESS) : null;
		return (areaNumber == null ? "–" : areaNumber.toString()) + "."
			+ (line.tValueByName(ADDRESS) == null ? "–" : line.tValueByName(ADDRESS).toString());
	}

	private static Set<Integer> taken(TLObject line) {
		Set<Integer> result = new HashSet<>();
		for (TLObject device : ExtIds.list(line, DEVICES)) {
			if (device.tValueByName(ADDRESS) instanceof Integer number) {
				result.add(number);
			}
		}
		return result;
	}

	private static TLObject product(TLObject variant) {
		Object product = variant.tValueByName("product");
		return product instanceof TLObject result ? result : variant.tContainer();
	}

	private static Program program(TLObject assignment) {
		return (Program) assignment.tValueByName("program");
	}

	private static int version(Integer value) {
		return value == null ? -1 : value.intValue();
	}

}
