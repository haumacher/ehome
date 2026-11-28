/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import static de.haumacher.ehome.ai.ToolSupport.saved;
import static de.haumacher.ehome.ai.ToolSupport.answer;
import static de.haumacher.ehome.ai.ToolSupport.changing;
import static de.haumacher.ehome.ai.ToolSupport.failure;
import static de.haumacher.ehome.ai.ToolSupport.id;
import static de.haumacher.ehome.ai.ToolSupport.label;
import static de.haumacher.ehome.ai.ToolSupport.project;
import static de.haumacher.ehome.ai.ToolSupport.ref;
import static de.haumacher.ehome.ai.ToolSupport.refused;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.ai.core.agent.ToolContext;
import com.top_logic.ai.tool.api.P;
import com.top_logic.ai.tool.api.ToolGroups;
import com.top_logic.ai.tool.api.ToolMethod;
import com.top_logic.ai.tool.api.ToolMode;
import com.top_logic.ai.tool.api.ToolResult;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.catalog.Catalog;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DeviceTexts;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.scan.DeviceInfo;
import de.haumacher.ehome.scan.FoundDevice;

/**
 * The assistant's tools for the devices of a project (haumacher/ehome #127): the catalog, the
 * devices of the lines, planning and duplicating a device, and reading and setting its parameters.
 *
 * <p>
 * The tools run the operations of the device view and its dialogs: {@link DevicePlanning} plans a
 * device of a catalog variant at a free address of a line (<code>projects/create-device.view.xml</code>)
 * and duplicates one; {@link DeviceConfiguration} gives the pages and parameters in effect;
 * {@link DeviceSettings#setValue(TLObject, ParameterView, Object)} sets a value with the checks of the
 * parameter editor and the manufacturer's validations and calculations (haumacher/ehome #83, #123,
 * #124). A parameter and a page are named by the ID ETS gives them in the device
 * ({@link ModuleParts#partId(TLObject)}), which also tells the instances of a module apart.
 * {@link #deviceInfo(TLObject)} reads a device over the project's path to the bus, nothing written
 * ({@link DeviceInfo}, haumacher/ehome #175).
 * </p>
 */
public class DeviceTools {

	private static final String DEVICE = "ehome.physical:Device";

	private static final String CATALOG_ROOT = "ehome.catalog:ManufacturerRoot";

	/** How many products a search answers at most by default. */
	private static final int DEFAULT_LIMIT = 20;

	/**
	 * Searches the catalog.
	 */
	@ToolMethod(description = "Searches E-Home's product catalog (the manufacturers' products imported from"
		+ " ETS projects and product data) for products a device can be planned with. Every word of 'text'"
		+ " must occur in the manufacturer, the product name, the order number or the catalog section"
		+ " (e.g. \"MDT switch actuator\", \"Gira 5133\", \"Schaltaktor 8fach\"). Product names are the"
		+ " manufacturer's, often German: search for \"Schaltaktor\", \"Dimmaktor\", \"Jalousieaktor\","
		+ " \"Tastsensor\", \"Präsenzmelder\" as well as for English words. Each product comes with its"
		+ " application programs, the newest first (the default when planning).",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = CATALOG_ROOT)
	public ToolResult searchCatalog(
			@P("Words to search for; empty lists the first products") String text,
			@P(value = "How many products to answer at most, default " + DEFAULT_LIMIT, required = false) Integer limit) {
		List<String> words = new ArrayList<>();
		for (String word : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("\\s+")) {
			if (!word.isBlank()) {
				words.add(word);
			}
		}
		int max = limit == null || limit.intValue() <= 0 ? DEFAULT_LIMIT : limit.intValue();
		Map<TLObject, String> sections = sections();
		List<Object> products = new ArrayList<>();
		int found = 0;
		for (TLObject variant : DevicePlanning.variants()) {
			String section = sections.get(variant);
			String haystack = (DevicePlanning.variantLabel(variant) + " " + Objects.toString(section, ""))
				.toLowerCase(Locale.ROOT);
			if (!words.stream().allMatch(haystack::contains)) {
				continue;
			}
			found++;
			if (products.size() >= max) {
				continue;
			}
			Map<String, Object> product = new LinkedHashMap<>();
			product.put("id", id(variant));
			product.put("product", DevicePlanning.variantLabel(variant));
			if (section != null) {
				product.put("catalogSection", section);
			}
			List<Object> programs = new ArrayList<>();
			for (TLObject assignment : DevicePlanning.programs(variant)) {
				programs.add(ref(assignment, DevicePlanning.programLabel(assignment)));
			}
			product.put("programs", programs);
			products.add(product);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("found", found);
		result.put("products", products);
		String sentence = found == 0
			? "No product of the catalog matches. A product missing from the catalog is imported with an ETS"
				+ " project or the manufacturer's product data (Catalog tab)."
			: found > products.size() ? "The first " + products.size() + " of " + found + " products; narrow the search for others:"
				: "Products:";
		return answer(sentence, result);
	}

	/**
	 * The catalog sections of the variants: "Aktoren / Schaltaktoren".
	 */
	private static Map<TLObject, String> sections() {
		Map<TLObject, String> result = new HashMap<>();
		for (TLObject manufacturer : ExtIds.list(catalogRoot(), "manufacturers")) {
			for (TLObject catalog : ExtIds.list(manufacturer, "catalogs")) {
				collectSections(catalog, null, result);
			}
		}
		return result;
	}

	private static void collectSections(TLObject catalog, String path, Map<TLObject, String> result) {
		Object name = ((Catalog) catalog).getNameKey();
		String own = path == null ? Objects.toString(name, "") : path + " / " + Objects.toString(name, "");
		for (TLObject variant : ExtIds.list(catalog, "variants")) {
			result.putIfAbsent(variant, own);
		}
		for (TLObject section : ExtIds.list(catalog, "sections")) {
			collectSections(section, own, result);
		}
	}

	private static TLObject catalogRoot() {
		return TLModelUtil.findType(CATALOG_ROOT).getModule().getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
	}

	/**
	 * The devices of a project by line.
	 */
	@ToolMethod(description = "Lists the topology of an E-Home project: its lines (area.line) with the next"
		+ " free device number, and the devices of each line with individual address, name, product,"
		+ " application program and building part. Read it to find a line to plan a device in, or a device"
		+ " by its name or address.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult listDevices(
			@P("The project, or any object of it") TLObject project) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project.");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("project", ref(owner));
		List<Object> lines = new ArrayList<>();
		for (TLObject area : ExtIds.list(owner, "areas")) {
			for (TLObject line : ExtIds.list(area, "lines")) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("id", id(line));
				entry.put("line", DevicePlanning.lineText(line));
				entry.put("name", line.tValueByName("name"));
				entry.put("nextFreeNumber", DevicePlanning.nextFree(line));
				List<Object> devices = new ArrayList<>();
				for (TLObject device : ExtIds.list(line, "devices")) {
					devices.add(device(device));
				}
				entry.put("devices", devices);
				lines.add(entry);
			}
		}
		result.put("lines", lines);
		List<Object> unassigned = new ArrayList<>();
		for (TLObject device : ExtIds.list(owner, "unassignedDevices")) {
			unassigned.add(device(device));
		}
		if (!unassigned.isEmpty()) {
			result.put("devicesInNoLine", unassigned);
		}
		return answer(lines.isEmpty() ? "The project has no line yet." : "The lines and their devices:", result);
	}

	private static Map<String, Object> device(TLObject device) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id(device));
		result.put("address", GroupAddressConnections.addressText(device));
		result.put("name", device.tValueByName("title"));
		if (ExtIds.isA(device, DEVICE)) {
			TLObject variant = device.tValueByName("type") instanceof TLObject type ? type : null;
			result.put("product", variant == null ? null : DevicePlanning.variantLabel(variant));
			TLObject assignment = device.tValueByName("program") instanceof TLObject program ? program : null;
			result.put("program", assignment == null ? null : DevicePlanning.programLabel(assignment));
		} else {
			// The stub of a device a scan found that the catalog does not identify (haumacher/ehome #183).
			result.put("unidentified", "No product and program: the catalog does not identify the device "
				+ GroupAddressConnections.deviceLabel(device) + "; the user assigns it after importing the"
				+ " manufacturer's product data.");
		}
		TLObject location = device.tValueByName("location") instanceof TLObject part ? part : null;
		result.put("location", location == null ? null : ref(location, BuildingDevices.path(location)));
		return result;
	}

	/**
	 * The commit message of {@link #planDevice(ToolContext, TLObject, TLObject, TLObject, Integer, String, TLObject, Boolean)}.
	 */
	@CalledByReflection
	ResKey planDeviceMessage(ToolContext context, TLObject line, TLObject product, TLObject program, Integer number,
			String name, TLObject location, Boolean dryRun) {
		TLObject target = line == null ? null : DevicePlanning.line(line);
		String device = name != null && !name.isBlank() ? name.trim() : CommitMessages.label(product);
		return I18NConstants.COMMIT_DEVICE_PLANNED__LINE_PRODUCT.fill(CommitMessages.label(target != null ? target : line),
			device);
	}

	/**
	 * Plans a device.
	 */
	@ToolMethod(description = "Plans a new KNX device in a line of an E-Home project: a product of the catalog"
		+ " (searchCatalog) running one of its application programs, at a free device number of the line -"
		+ " the next free one unless given - with an optional name and building part. The device then has the"
		+ " program's default parameters and no group address; it is programmed later by the installer. With"
		+ " dryRun=true nothing is created and the answer gives the individual address the device would get,"
		+ " or why it cannot. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, dryRun = "dryRun",
		commitMessage = "planDeviceMessage")
	public ToolResult planDevice(ToolContext context,
			@P("The line to plan it in (listDevices), or a device of that line") TLObject line,
			@P("The product variant from searchCatalog") TLObject product,
			@P(value = "The application program (a program id from searchCatalog); omit for the newest",
				required = false) TLObject program,
			@P(value = "The device number in the line (the last part of area.line.device); omit for the next"
				+ " free one", required = false) Integer number,
			@P(value = "The device's name, e.g. \"Schaltaktor Küche\"; omit to name it by its product",
				required = false) String name,
			@P(value = "The building part to place it in", required = false) TLObject location,
			@P(value = "true to only check the address", required = false) Boolean dryRun) {
		TLObject target = DevicePlanning.line(line);
		if (target == null) {
			return failure("'" + id(line) + "' is no line and no device of a line. Nothing was created.");
		}
		Integer chosen = number != null ? number : DevicePlanning.nextFree(target);
		ResKey veto = DevicePlanning.addressVeto(target, chosen);
		if (veto != null) {
			return refused(target, veto);
		}
		if (Boolean.TRUE.equals(dryRun)) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("address", DevicePlanning.addressText(target, chosen));
			TLObject assignment = program != null ? program
				: ExtIds.isA(product, "ehome.catalog:Variant") ? DevicePlanning.defaultProgram(product) : null;
			result.put("program", assignment == null ? null : DevicePlanning.programLabel(assignment));
			return answer("The address is free. Nothing was created.", result);
		}
		return changing(context, target, () -> {
			TLObject device = DevicePlanning.create(target, product, program, chosen, name, location);
			return answer("Planned. " + saved(context), device(device));
		});
	}

	/**
	 * The commit message of {@link #duplicateDevice(ToolContext, TLObject, Boolean)}.
	 */
	@CalledByReflection
	ResKey duplicateDeviceMessage(ToolContext context, TLObject device, Boolean dryRun) {
		return I18NConstants.COMMIT_DEVICE_DUPLICATED__DEVICE.fill(deviceName(device));
	}

	private static String deviceName(TLObject device) {
		return ExtIds.isA(device, DEVICE) ? GroupAddressConnections.deviceLabel(device) : CommitMessages.label(device);
	}

	/**
	 * Duplicates a device.
	 */
	@ToolMethod(description = "Duplicates a device of an E-Home project: a new device of the same product and"
		+ " program with a copy of its parameter settings, at the next free device number of its line, without"
		+ " group addresses and without a building part (placeDevice places it, updateObject renames it). With"
		+ " dryRun=true nothing is created and the answer gives the address the copy would get. "
		+ ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, dryRun = "dryRun",
		commitMessage = "duplicateDeviceMessage")
	public ToolResult duplicateDevice(ToolContext context,
			@P("The device to copy") TLObject device,
			@P(value = "true to only check", required = false) Boolean dryRun) {
		ResKey veto = DevicePlanning.duplicateVeto(device);
		if (veto != null) {
			return refused(device, veto);
		}
		if (Boolean.TRUE.equals(dryRun)) {
			TLObject line = DevicePlanning.line(device);
			return answer("The device can be duplicated. Nothing was created.",
				Collections.singletonMap("address", DevicePlanning.addressText(line, DevicePlanning.nextFree(line))));
		}
		return changing(context, device, () -> {
			TLObject copy = DevicePlanning.duplicate(device);
			return answer("Duplicated. " + saved(context), device(copy));
		});
	}

	/**
	 * The parameter pages of a device.
	 */
	@ToolMethod(description = "Reads the parameters of a device of an E-Home project as its program shows them"
		+ " for the current values. Without 'page': the tree of the pages in effect (channels, blocks), each"
		+ " with its id, and the parameters shown at the top. With 'page': the parameters and communication"
		+ " objects of that page, each parameter with its id, title, current value, default, whether the"
		+ " project changed it, whether it can be changed (or why not) and its allowed values (the entries"
		+ " of a choice, the range of a number, the length of a text). A changed value can switch pages and"
		+ " parameters on or off: read the pages again after setting one.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult deviceParameters(
			@P("The device") TLObject device,
			@P(value = "The id of a page from the page tree; omit for the tree", required = false) String page) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device.");
		}
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		if (configuration.getProgram() == null) {
			return failure("The device " + GroupAddressConnections.deviceLabel(device)
				+ " runs no application program of the catalog; it has no parameters.");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", ref(device, GroupAddressConnections.deviceLabel(device)));
		if (page == null || page.isBlank()) {
			result.put("pages", pages(device, configuration, device));
			result.put("items", items(device, configuration, device));
			return answer("The pages in effect, and the parameters at the top:", result);
		}
		Object found = findPage(configuration, device, page.trim());
		if (found == null) {
			return failure("The device has no page '" + page + "' in effect. Read the page tree (without 'page') for"
				+ " the ids.");
		}
		result.put("page", ToolSupport.named(page.trim(), DeviceTexts.label(device, found)));
		result.put("items", items(device, configuration, found));
		return answer("The parameters and objects of the page:", result);
	}

	private static List<Object> pages(TLObject device, DeviceConfiguration configuration, Object page) {
		List<Object> result = new ArrayList<>();
		for (ConfigFragment inner : configuration.getPages(page)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", ModuleParts.partId(inner));
			entry.put("title", DeviceTexts.label(device, inner));
			entry.put("items", configuration.getItems(inner).size());
			List<Object> below = pages(device, configuration, inner);
			if (!below.isEmpty()) {
				entry.put("pages", below);
			}
			result.add(entry);
		}
		return result;
	}

	private static Object findPage(DeviceConfiguration configuration, Object page, String id) {
		for (ConfigFragment inner : configuration.getPages(page)) {
			if (id.equals(ModuleParts.partId(inner))) {
				return inner;
			}
			Object found = findPage(configuration, inner, id);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static List<Object> items(TLObject device, DeviceConfiguration configuration, Object page) {
		Resources resources = Resources.getInstance();
		List<Object> result = new ArrayList<>();
		for (Option item : configuration.getItems(page)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			if (item instanceof Interface placed) {
				entry.put("object", Integer.valueOf(placed.getPort().getIndex()));
				entry.put("text", DeviceTexts.label(device, placed));
			} else if (item instanceof ParameterView view) {
				ParameterType type = ParameterValues.valueType(view);
				entry.put("id", ModuleParts.partId(view));
				entry.put("title", DeviceTexts.label(device, view));
				entry.put("value", display(type, ParameterValues.value(configuration.effectiveHolder(view))));
				entry.put("default", display(type, ParameterValues.value(DeviceConfiguration.defaultHolder(view))));
				entry.put("changed", Boolean.valueOf(DeviceSettings.setting(device, view) != null));
				ResKey veto = DeviceSettings.editVeto(device, view);
				entry.put("editable", veto == null ? Boolean.TRUE : resources.getString(veto));
				entry.put("type", type(type));
			} else {
				continue;
			}
			result.add(entry);
		}
		return result;
	}

	/**
	 * A value in an answer: an entry of a choice by its value and text, a number, a text.
	 */
	static Object display(ParameterType type, Object value) {
		if (value instanceof EnumerationValue entry) {
			return entry.getUnsignedValue() + " " + label(entry);
		}
		if (value instanceof Number number && type instanceof TimeType time && time.getUnit() != null) {
			return number + " " + label(time.getUnit());
		}
		return value;
	}

	private static Map<String, Object> type(ParameterType type) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (type instanceof EnumerationType enumeration) {
			result.put("kind", "choice");
			List<Object> entries = new ArrayList<>();
			for (EnumerationValue entry : enumeration.getValues()) {
				entries.add(entry.getUnsignedValue() + " " + label(entry));
			}
			result.put("entries", entries);
		} else if (type instanceof NumericType numeric) {
			result.put("kind", "integer");
			result.put("min", Long.valueOf(numeric.getMin()));
			result.put("max", Long.valueOf(numeric.getMax()));
		} else if (type instanceof TimeType time) {
			result.put("kind", "time");
			result.put("min", Long.valueOf(time.getMin()));
			result.put("max", Long.valueOf(time.getMax()));
			result.put("unit", time.getUnit() == null ? null : label(time.getUnit()));
		} else if (type instanceof FloatingPointType floating) {
			result.put("kind", "decimal");
			result.put("min", Double.valueOf(floating.getMin()));
			result.put("max", Double.valueOf(floating.getMax()));
		} else if (type instanceof TextType text) {
			result.put("kind", "text");
			result.put("maxLength", Integer.valueOf(text.getBits() / 8));
		} else {
			result.put("kind", type == null ? "none" : type.tType().getName());
		}
		return result;
	}

	/**
	 * The commit message of {@link #setDeviceParameter(ToolContext, TLObject, String, String)}.
	 */
	@CalledByReflection
	ResKey setDeviceParameterMessage(ToolContext context, TLObject device, String parameter, String value) {
		String name = CommitMessages.text(parameter);
		if (ExtIds.isA(device, DEVICE) && parameter != null) {
			ParameterView view = findParameter(device, parameter.trim());
			if (view != null) {
				name = CommitMessages.label(view);
			}
		}
		return value == null ? I18NConstants.COMMIT_PARAMETER_RESET__DEVICE_PARAMETER.fill(deviceName(device), name)
			: I18NConstants.COMMIT_PARAMETER_SET__DEVICE_PARAMETER_VALUE.fill(deviceName(device), name, value);
	}

	/**
	 * Sets a parameter of a device.
	 */
	@ToolMethod(description = "Sets one parameter of a device of an E-Home project, by its id from"
		+ " deviceParameters: a choice by the number of its entry (or the entry's text), a number, or a text;"
		+ " omit 'value' to set the parameter back to its default. The application checks the value as the"
		+ " parameter editor does - its type and range, whether the parameter can be changed, and the"
		+ " manufacturer's validations and calculations - and keeps the device unchanged when it refuses."
		+ " The device needs a download afterwards, which the installer starts. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE,
		commitMessage = "setDeviceParameterMessage")
	public ToolResult setDeviceParameter(ToolContext context,
			@P("The device") TLObject device,
			@P("The id of the parameter from deviceParameters") String parameter,
			@P(value = "The new value; omit for the default", required = false) String value) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device. Nothing was changed.");
		}
		ParameterView view = parameter == null ? null : findParameter(device, parameter.trim());
		if (view == null) {
			return failure("The device has no parameter '" + parameter + "' in effect. Read deviceParameters for the"
				+ " ids. Nothing was changed.");
		}
		ParameterType type = ParameterValues.valueType(view);
		Object input = value == null || value.isEmpty() ? null : input(type, value);
		return changing(context, device, () -> {
			Object before = DeviceSettings.value(device, view);
			DeviceSettings.setValue(device, view, input);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("device", ref(device, GroupAddressConnections.deviceLabel(device)));
			result.put("parameter", ToolSupport.named(parameter.trim(), DeviceTexts.label(device, view)));
			result.put("from", display(type, before));
			result.put("to", display(type, DeviceSettings.value(device, view)));
			return answer("Set. " + saved(context), result);
		});
	}

	/**
	 * Reads the device info of a device over the bus.
	 */
	@ToolMethod(description = "Asks a device of an E-Home project over the bus who it is and in what state"
		+ " (haumacher/ehome #175): the device at its individual address is read through the project's path to"
		+ " the bus (its KNXnet/IP gateway or its connected controller); nothing is written. The answer gives"
		+ " the identity the device reports (mask version, manufacturer, serial number, order info, hardware"
		+ " type, program version) beside the project's values, each compared: equal, different (another"
		+ " device or another program at the address), notInProject (e.g. a serial number the project does"
		+ " not know yet), notRead or notComparable (an order info in the manufacturer's own encoding); its"
		+ " state (load states, run state, programming mode); and what could not be read. 'answered' false means no device answers at the address - a result, not an error."
		+ " Read it before the installer programs a device, to check that the right device is there. Takes"
		+ " a few seconds; refused while the path is in use or cannot be used, with the reason.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult deviceInfo(
			@P("The device") TLObject device) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device.");
		}
		ResKey veto = DeviceInfo.veto(device);
		if (veto != null) {
			return refused(device, veto);
		}
		BusPath path;
		DeviceInfo info;
		try {
			path = BusPath.of(device);
			int address = DownloadImage.individualAddress(device).intValue();
			try (DeviceReader reader = CalimeroReader.open(path, address)) {
				info = DeviceInfo.read(device, path.text(), reader);
			} catch (BusException ex) {
				return failure(Resources.getInstance().getString(de.haumacher.ehome.scan.I18NConstants.INFO_FAILED__ADDRESS_PATH_REASON
					.fill(FoundDevice.formatAddress(address), path.text(), ex.getErrorKey())));
			}
		} catch (TopLogicException ex) {
			return refused(device, ex.getErrorKey());
		}
		Resources resources = Resources.getInstance();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", ref(device, GroupAddressConnections.deviceLabel(device)));
		result.put("address", info.addressText());
		result.put("path", info.path());
		result.put("time", info.time().toInstant().toString());
		result.put("answered", Boolean.valueOf(info.answered()));
		if (info.answered()) {
			List<Object> identity = new ArrayList<>();
			for (DeviceInfo.Row row : info.identity()) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("value", row.name());
				entry.put("project", row.project());
				entry.put("device", row.device());
				entry.put("comparison", comparison(row.comparison()));
				identity.add(entry);
			}
			result.put("identity", identity);
			result.put("state", info.state().stream().map(resources::getString).toList());
		}
		List<String> notes = info.notes().stream().map(resources::getString).toList();
		if (!notes.isEmpty()) {
			result.put("notRead", notes);
		}
		return answer(resources.getString(info.summary()), result);
	}

	private static String comparison(DeviceInfo.Comparison comparison) {
		return switch (comparison) {
			case EQUAL -> "equal";
			case DIFFERENT -> "different";
			case NOT_IN_PROJECT -> "notInProject";
			case NOT_READ -> "notRead";
			case NOT_COMPARABLE -> "notComparable";
		};
	}

	/**
	 * The placement of the given ID in effect in the device's configuration, or one the device holds a
	 * setting of (which can be set back to its default).
	 */
	private static ParameterView findParameter(TLObject device, String id) {
		for (Option item : new DeviceConfiguration(device).getShownItems().keySet()) {
			if (item instanceof ParameterView view && id.equals(ModuleParts.partId(view))) {
				return view;
			}
		}
		for (TLObject setting : ExtIds.list(device, "settings")) {
			ParameterView view = ModuleParts.parameter(setting);
			if (view != null && id.equals(ModuleParts.partId(view))) {
				return view;
			}
		}
		return null;
	}

	/**
	 * The value the model wrote as the input of
	 * {@link DeviceSettings#setValue(TLObject, ParameterView, Object)}: the entry of a choice named by
	 * its number or its text, the text itself otherwise (the operation reads a number from it).
	 */
	private static Object input(ParameterType type, String value) {
		String text = value.trim();
		if (type instanceof EnumerationType enumeration) {
			for (EnumerationValue entry : enumeration.getValues()) {
				if (text.equals(Long.toString(entry.getUnsignedValue()))
					|| text.equals(Integer.toString(entry.getValue()))
					|| text.equalsIgnoreCase(Objects.toString(label(entry), ""))
					|| text.equalsIgnoreCase(entry.getUnsignedValue() + " " + label(entry))) {
					return entry;
				}
			}
		}
		return text;
	}

}
