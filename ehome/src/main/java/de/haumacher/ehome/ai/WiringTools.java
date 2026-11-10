/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import static de.haumacher.ehome.ai.ToolSupport.SAVED;
import static de.haumacher.ehome.ai.ToolSupport.answer;
import static de.haumacher.ehome.ai.ToolSupport.changing;
import static de.haumacher.ehome.ai.ToolSupport.failure;
import static de.haumacher.ehome.ai.ToolSupport.id;
import static de.haumacher.ehome.ai.ToolSupport.label;
import static de.haumacher.ehome.ai.ToolSupport.project;
import static de.haumacher.ehome.ai.ToolSupport.ref;
import static de.haumacher.ehome.ai.ToolSupport.refused;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.tool.api.P;
import com.top_logic.tl.ai.tool.api.ToolGroups;
import com.top_logic.tl.ai.tool.api.ToolMethod;
import com.top_logic.tl.ai.tool.api.ToolMode;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceTexts;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.DatapointSettings;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.template.ObjectTypes;

/**
 * The assistant's tools for the group addresses of a project and the connections of the devices'
 * communication objects to them (haumacher/ehome #127).
 *
 * <p>
 * The tools run the operations of the group address view and the connect dialogs
 * (<code>projects/addresses.view.xml</code>, <code>create-group-address.view.xml</code>,
 * <code>set-datapoint-type.view.xml</code>, <code>connect-group-address.view.xml</code>):
 * {@link GroupAddresses} creates an address at the next free address of a range in the project's
 * notation, {@link GroupAddressTypes} sets its datapoint type and refuses one of another size than
 * its objects, {@link DeviceSettings#connect(TLObject, Interface, TLObject, TLObject)} connects an
 * object (one sending address per object, sending by default as long as the object sends nowhere)
 * and {@link DeviceSettings#disconnect(TLObject)} removes a connection.
 * </p>
 *
 * <p>
 * Before connecting, the tool asks what the group address view would show afterwards
 * ({@link GroupAddressConnections#problemsWith(TLObject, TLObject, Interface)}: objects of different
 * sizes or datapoint types at one address) and refuses a connection that adds such a problem unless
 * the call accepts it - the view connects anyway and warns, a person sees the warning, the model is
 * told before.
 * </p>
 */
public class WiringTools {

	private static final String GROUP_RANGE = "ehome.logical:GroupRange";

	private static final String GROUP_ADDRESS = "ehome.logical:GroupAddress";

	private static final String DEVICE = "ehome.physical:Device";

	/** The longest name of a group address an ETS project file takes. */
	private static final int MAX_NAME = 255;

	/** How many group addresses a page of the listing holds by default. */
	private static final int PAGE = 100;

	/** The flags of a communication object, by the letters ETS shows them with. */
	private static final String[][] FLAGS = { { "communication", "C" }, { "canRead", "R" }, { "canWrite", "W" },
		{ "transmission", "T" }, { "update", "U" }, { "readOnInit", "I" } };

	/**
	 * The group addresses of a project.
	 */
	@ToolMethod(description = "Lists the group addresses of an E-Home project with what tells their purpose,"
		+ " a page at a time: the group ranges (main and middle groups) with their prefix, name, description"
		+ " and - for a range that takes addresses - the next free address; and per address its id, address"
		+ " in the project's notation (three-level 1/2/3, two-level 1/2 or free), name, identifier (the readable"
		+ " id rules name its datapoint by, @kueche.licht; without one rules use @\"1/2/3\"), description, datapoint"
		+ " type, range, problems (objects that do not fit together), the functions that use it (with their"
		+ " room) and its connections: device with name and individual address, the device's room, the"
		+ " communication object's number and text, its function text, its channel and the channel's"
		+ " description, and the direction: 'send' marks the object's sending address (the first address of an"
		+ " object, also of an input such as a switching object, flags CW), 'receive' an additional address the"
		+ " object listens to. Read it before you create, connect or name"
		+ " addresses; page with 'offset' while the answer gives 'nextOffset'.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = ToolSupport.PROJECT)
	public ToolResult listGroupAddresses(
			@P("The project: the open project the turn context names (every tab of a project selects it), or any"
				+ " object of the project") TLObject project,
			@P(value = "Only the addresses in this group range (and the ranges below it)", required = false)
			TLObject range,
			@P(value = "true to list only the addresses without a name", required = false) Boolean unnamedOnly,
			@P(value = "How many addresses to skip, from 'nextOffset' of the previous page; default 0",
				required = false) Integer offset,
			@P(value = "How many addresses to answer at most; default " + PAGE, required = false) Integer limit) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project.");
		}
		if (range != null && (!ExtIds.isA(range, GROUP_RANGE) || project(range) != owner)) {
			return failure("'range' must be a group range of the project.");
		}
		TLObject root = range != null ? range : owner;
		List<TLObject> ranges = new ArrayList<>();
		List<TLObject> addresses = new ArrayList<>();
		collect(root, ranges, addresses);
		if (Boolean.TRUE.equals(unnamedOnly)) {
			addresses.removeIf(address -> address.tValueByName("name") instanceof String name && !name.isBlank());
		}
		int first = offset == null || offset.intValue() < 0 ? 0 : offset.intValue();
		int max = limit == null || limit.intValue() <= 0 ? PAGE : limit.intValue();
		int last = Math.min(addresses.size(), first + max);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("project", ref(owner));
		GroupAddressStyle style = GroupAddressStyle.fromModel(owner.tValueByName("groupAddressStyle"));
		result.put("notation", (style == null ? GroupAddressStyle.DEFAULT : style).name());
		List<Object> rangeEntries = new ArrayList<>();
		List<TLObject> leaves = GroupAddresses.ranges(owner);
		for (TLObject part : ranges) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", id(part));
			Object start = part.tValueByName("rangeStart");
			Object end = part.tValueByName("rangeEnd");
			entry.put("range", start instanceof GA begin && end instanceof GA finish ? begin.rangePrefix(finish) : null);
			entry.put("name", part.tValueByName("name"));
			putText(entry, "description", part.tValueByName("description"));
			if (ExtIds.isA(part.tContainer(), GROUP_RANGE)) {
				entry.put("parent", id(part.tContainer()));
			}
			if (leaves.contains(part)) {
				GA next = GroupAddresses.nextFree(part);
				entry.put("nextFreeAddress", next == null ? null : next.toString());
			}
			rangeEntries.add(entry);
		}
		result.put("ranges", rangeEntries);
		result.put("total", Integer.valueOf(addresses.size()));
		Map<TLObject, List<String>> functions = functions(owner);
		Map<TLObject, DeviceConfiguration> configurations = new HashMap<>();
		List<Object> page = new ArrayList<>();
		for (TLObject address : addresses.subList(Math.min(first, last), last)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", id(address));
			address(entry, address);
			putText(entry, "description", address.tValueByName("description"));
			if (ExtIds.isA(address.tContainer(), GROUP_RANGE)) {
				entry.put("range", id(address.tContainer()));
			}
			List<String> users = functions.get(address);
			if (users != null) {
				entry.put("functions", users);
			}
			List<Object> connections = new ArrayList<>();
			for (TLObject connection : GroupAddressConnections.connections(address)) {
				connections.add(connection(connection, configurations));
			}
			entry.put("connections", connections);
			page.add(entry);
		}
		result.put("addresses", page);
		if (last < addresses.size()) {
			result.put("nextOffset", Integer.valueOf(last));
		}
		String sentence;
		if (ranges.isEmpty() && addresses.isEmpty()) {
			sentence = "The project has no group range and no group address yet. Group ranges are created in the tab"
				+ " Group addresses (command \"Group range\", then its start and end in the form).";
		} else {
			sentence = "Group addresses " + (addresses.isEmpty() ? 0 : Math.min(first, last) + 1) + " to " + last + " of "
				+ addresses.size() + ":";
		}
		return answer(sentence, result);
	}

	/**
	 * The ranges and addresses below the given project or range, in the order of the address tree.
	 */
	private static void collect(TLObject node, List<TLObject> ranges, List<TLObject> addresses) {
		for (TLObject part : GroupAddresses.parts(node)) {
			if (ExtIds.isA(part, GROUP_RANGE)) {
				ranges.add(part);
				collect(part, ranges, addresses);
			} else if (ExtIds.isA(part, GROUP_ADDRESS)) {
				addresses.add(part);
			}
		}
	}

	/**
	 * A connection as the listing shows it: compact, with what tells the purpose of the address.
	 */
	private static Map<String, Object> connection(TLObject connection, Map<TLObject, DeviceConfiguration> configurations) {
		Map<String, Object> result = new LinkedHashMap<>();
		TLObject device = GroupAddressConnections.device(connection);
		Interface placed = GroupAddressConnections.object(connection);
		result.put("device", GroupAddressConnections.deviceLabel(device));
		TLObject location = device.tValueByName("location") instanceof TLObject part ? part : null;
		if (location != null) {
			result.put("room", BuildingDevices.path(location));
		}
		result.put("object", placed.getPort().getIndex() + " " + GroupAddressConnections.objectText(connection));
		String function = GroupAddressConnections.function(connection);
		if (function != null && !function.isBlank()) {
			result.put("function", function);
		}
		String channel = channel(device, placed, configurations);
		if (channel != null) {
			result.put("channel", channel);
		}
		result.put("direction", connection.tValueByName("kind") instanceof TLClassifier kind ? kind.getName() : null);
		return result;
	}

	/**
	 * The label of the channel the object is shown in (with the project's description of the
	 * channel), <code>null</code> for an object outside any channel.
	 */
	private static String channel(TLObject device, Interface placed, Map<TLObject, DeviceConfiguration> configurations) {
		DeviceConfiguration configuration = configurations.computeIfAbsent(device, DeviceConfiguration::new);
		Object page = configuration.getShownItems().get(placed);
		for (Object current = page; current != null && current != device; current = configuration.getParentPage(current)) {
			if (current instanceof Channel channel) {
				return DeviceTexts.label(device, channel);
			}
		}
		return null;
	}

	/**
	 * The functions of the project by the group addresses they use: "Licht Küche (state, room Küche)".
	 */
	private static Map<TLObject, List<String>> functions(TLObject project) {
		Map<TLObject, List<String>> result = new HashMap<>();
		for (TLObject function : ExtIds.list(project, "functions")) {
			TLObject room = function.tValueByName("room") instanceof TLObject part ? part : null;
			for (TLStructuredTypePart part : function.tType().getAllParts()) {
				if (!(part instanceof TLReference) || part.isMultiple()
					|| !(function.tValue(part) instanceof TLObject target) || !ExtIds.isA(target, GROUP_ADDRESS)) {
					continue;
				}
				String text = label(function) + " (" + part.getName()
					+ (room == null ? "" : ", room " + BuildingDevices.path(room)) + ")";
				result.computeIfAbsent(target, x -> new ArrayList<>()).add(text);
			}
		}
		return result;
	}

	private static void putText(Map<String, Object> entry, String key, Object value) {
		if (value != null && !value.toString().isBlank()) {
			entry.put(key, value.toString());
		}
	}

	private static Map<String, Object> address(Map<String, Object> entry, TLObject address) {
		Object ga = address.tValueByName("address");
		entry.put("address", ga == null ? null : ga.toString());
		entry.put("name", address.tValueByName("name"));
		String identifier = GroupAddressIds.identifier(address);
		if (identifier != null) {
			entry.put("identifier", identifier);
		}
		String type = GroupAddressTypes.label(address);
		entry.put("datapointType", type.isEmpty() ? null : type);
		entry.put("connections", Integer.valueOf(GroupAddressConnections.connections(address).size()));
		entry.put("senders", Integer.valueOf(GroupAddressConnections.senders(address)));
		String problems = GroupAddressConnections.problemText(address);
		if (problems != null) {
			entry.put("problems", problems);
		}
		return entry;
	}

	/**
	 * The name, description and identifier one call of {@link WiringTools#nameGroupAddresses} gives a
	 * group address.
	 *
	 * @param groupAddress
	 *        The group address.
	 * @param name
	 *        Its new name; <code>null</code> leaves it unchanged.
	 * @param description
	 *        Its new description; <code>null</code> leaves it unchanged, an empty text removes it.
	 * @param identifier
	 *        Its new identifier (haumacher/ehome #130); <code>null</code> leaves it unchanged, an empty
	 *        text removes it.
	 */
	public record GroupAddressName(TLObject groupAddress, String name, String description, String identifier) {
		// A value.
	}

	/**
	 * Names group addresses.
	 */
	@ToolMethod(description = "Sets the names - and optionally the descriptions and identifiers - of several group"
		+ " addresses of ONE E-Home project in one call: a list of {\"groupAddress\": id, \"name\": ...,"
		+ " \"description\": ..., \"identifier\": ...}. 'name', 'description' and 'identifier' omitted leave them"
		+ " as they are, an empty description or identifier removes it; each entry changes at least one. A name"
		+ " must be non-empty and at most " + MAX_NAME + " characters (the limit of an ETS project file). The"
		+ " identifier is the readable id of the address's datapoint that rules name it by (@kueche.licht instead"
		+ " of @\"1/2/3\"): parts of letters, digits and underscores joined by dots, the first starting with a"
		+ " letter, at most 63 bytes, unique among the project's datapoints (also the application datapoints);"
		+ " rules naming the address are rewritten to it, and the controller keeps a persisted value or history"
		+ " of the datapoint under its old id. If one entry is refused, nothing is changed and the answer names"
		+ " each refused entry. The answer lists every address with its values before and after. With"
		+ " dryRun=true nothing is changed and the answer shows the changes it would make.",
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = ToolSupport.PROJECT, dryRun = "dryRun")
	public ToolResult nameGroupAddresses(ToolContext context,
			@P("The changes: [{\"groupAddress\": \"id\", \"name\": \"Küche Licht Schalten\", \"description\":"
				+ " optional, \"identifier\": \"kueche.licht\" optional}]") List<GroupAddressName> names,
			@P(value = "true to only show what would change", required = false) Boolean dryRun) {
		if (names == null || names.isEmpty()) {
			return failure("'names' is empty. Pass the addresses and their new names. Nothing was changed.");
		}
		List<String> problems = new ArrayList<>();
		TLObject owner = null;
		Set<TLObject> seen = new HashSet<>();
		Map<TLObject, String> identifiers = new LinkedHashMap<>();
		for (GroupAddressName entry : names) {
			TLObject address = entry.groupAddress();
			if (!ExtIds.isA(address, GROUP_ADDRESS)) {
				problems.add("'" + id(address) + "' is no group address.");
				continue;
			}
			String where = address.tValueByName("address") + " (" + id(address) + ")";
			if (entry.name() == null && entry.description() == null && entry.identifier() == null) {
				problems.add(where + ": neither a name, a description nor an identifier is given.");
			}
			if (entry.name() != null) {
				String text = entry.name().trim();
				if (text.isEmpty()) {
					problems.add(where + ": the name is empty.");
				} else if (text.length() > MAX_NAME) {
					problems.add(
						where + ": the name has " + text.length() + " characters, at most " + MAX_NAME + " are allowed.");
				}
			}
			if (entry.identifier() != null) {
				identifiers.put(address, entry.identifier().trim());
			}
			if (!seen.add(address)) {
				problems.add(where + ": named twice in this call.");
			}
			TLObject project = project(address);
			if (owner == null) {
				owner = project;
			} else if (project != owner) {
				problems.add(where + ": belongs to another project than the first address; name the addresses of"
					+ " one project per call.");
			}
		}
		if (problems.isEmpty() && !identifiers.isEmpty()) {
			Resources resources = Resources.getInstance();
			for (Map.Entry<TLObject, ResKey> problem : GroupAddressIds.problems(owner, identifiers).entrySet()) {
				TLObject address = problem.getKey();
				problems.add(address.tValueByName("address") + " (" + id(address) + "): "
					+ resources.getString(problem.getValue()));
			}
		}
		if (!problems.isEmpty()) {
			return failure("Refused, nothing was changed:\n- " + String.join("\n- ", problems));
		}
		boolean preview = Boolean.TRUE.equals(dryRun);
		ToolResult denied = preview ? null : ToolSupport.writeDenied(context, owner);
		if (denied != null) {
			return denied;
		}
		List<Object> changes = new ArrayList<>();
		for (GroupAddressName entry : names) {
			TLObject address = entry.groupAddress();
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("id", id(address));
			change.put("address", String.valueOf(address.tValueByName("address")));
			if (entry.name() != null) {
				change.put("nameBefore", address.tValueByName("name"));
				change.put("name", entry.name().trim());
			}
			if (entry.description() != null) {
				change.put("descriptionBefore", address.tValueByName("description"));
				change.put("description", blankToNull(entry.description()));
			}
			if (entry.identifier() != null) {
				change.put("identifierBefore", GroupAddressIds.identifier(address));
				change.put("identifier", blankToNull(entry.identifier()));
			}
			if (!preview) {
				if (entry.name() != null) {
					address.tUpdateByName("name", entry.name().trim());
				}
				if (entry.description() != null) {
					address.tUpdateByName("description", blankToNull(entry.description()));
				}
				if (entry.identifier() != null) {
					address.tUpdateByName("identifier", blankToNull(entry.identifier()));
				}
			}
			changes.add(change);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("project", ref(owner));
		result.put("changes", changes);
		return answer(preview ? "These changes would be made; nothing was changed:" : "Named. " + SAVED, result);
	}

	private static String blankToNull(String text) {
		return text.isBlank() ? null : text.trim();
	}

	/**
	 * Creates a group address.
	 */
	@ToolMethod(description = "Creates a group address in a group range of an E-Home project (a range that"
		+ " takes addresses, from listGroupAddresses): at the next free address of the range unless an address"
		+ " in the project's notation is given, with a name and a datapoint type. Give the datapoint type of"
		+ " the object that will send to it, e.g. \"1.001\" switch, \"5.001\" percentage, \"9.001\" temperature"
		+ " (also written DPST-1-1). With dryRun=true nothing is created and the answer gives the address it"
		+ " would get, or why it cannot. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = ToolSupport.PROJECT, dryRun = "dryRun")
	public ToolResult createGroupAddress(ToolContext context,
			@P("The group range to create it in") TLObject range,
			@P("The name, e.g. \"Licht Küche schalten\"") String name,
			@P(value = "The datapoint type, e.g. \"1.001\"; omit to take the one of the objects connected later",
				required = false) String datapointType,
			@P(value = "The address in the project's notation, e.g. \"1/1/5\"; omit for the next free one",
				required = false) String address,
			@P(value = "true to only check the address", required = false) Boolean dryRun) {
		if (!ExtIds.isA(range, GROUP_RANGE)) {
			return failure("'" + id(range) + "' is no group range. Read listGroupAddresses for the ranges. Nothing was"
				+ " created.");
		}
		GA chosen;
		if (address == null || address.isBlank()) {
			chosen = GroupAddresses.nextFree(range);
		} else {
			TLObject owner = project(range);
			try {
				chosen = GA.parse(address.trim(),
					GroupAddressStyle.fromModel(owner == null ? null : owner.tValueByName("groupAddressStyle")));
			} catch (RuntimeException ex) {
				return failure("'" + address + "' is no group address in the project's notation. Nothing was created.");
			}
		}
		ResKey veto = GroupAddresses.veto(range, chosen);
		if (veto != null) {
			return refused(range, veto);
		}
		TLObject type;
		try {
			type = datapointType(datapointType);
		} catch (TopLogicException ex) {
			return refused(null, ex.getErrorKey());
		}
		if (datapointType != null && !datapointType.isBlank() && type == null) {
			return failure("The datapoint type '" + datapointType + "' is not in E-Home's master data. Nothing was"
				+ " created.");
		}
		if (Boolean.TRUE.equals(dryRun)) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("address", chosen.toString());
			result.put("range", ref(range));
			return answer("The address is free. Nothing was created.", result);
		}
		return changing(context, range, () -> {
			TLObject created = GroupAddresses.create(range, chosen, name, type);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("id", id(created));
			result.put("displayText", DatapointNames.name(created));
			address(result, created);
			return answer("Created. " + SAVED, result);
		});
	}

	/**
	 * The datapoint type of the master data with the given code, <code>null</code> for an empty code
	 * or one the master data do not know.
	 *
	 * @throws TopLogicException
	 *         If the text is no datapoint type.
	 */
	private static TLObject datapointType(String code) {
		Dpt dpt = Dpt.parse(code);
		if (dpt == null) {
			return null;
		}
		for (TLObject type : GroupAddressTypes.allTypes()) {
			if (dpt.equals(GroupAddressTypes.dpt(type))) {
				return type;
			}
		}
		return null;
	}

	/**
	 * Sets the datapoint type of a group address.
	 */
	@ToolMethod(description = "Sets the datapoint type of a group address of an E-Home project, e.g. \"9.001\";"
		+ " omit 'datapointType' to remove the address's own type, so that the type of its objects applies."
		+ " The application refuses a type whose size differs from the size of the connected objects. "
		+ ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = ToolSupport.PROJECT)
	public ToolResult setGroupAddressType(ToolContext context,
			@P("The group address") TLObject groupAddress,
			@P(value = "The datapoint type, e.g. \"1.001\"; omit to remove the own type", required = false)
			String datapointType) {
		if (!ExtIds.isA(groupAddress, GROUP_ADDRESS)) {
			return failure("'" + id(groupAddress) + "' is no group address. Nothing was changed.");
		}
		TLObject type;
		try {
			type = datapointType(datapointType);
		} catch (TopLogicException ex) {
			return refused(groupAddress, ex.getErrorKey());
		}
		if (datapointType != null && !datapointType.isBlank() && type == null) {
			return failure("The datapoint type '" + datapointType + "' is not in E-Home's master data. Nothing was"
				+ " changed.");
		}
		ResKey veto = GroupAddressTypes.veto(groupAddress, type);
		if (veto != null) {
			return refused(groupAddress, veto);
		}
		return changing(context, groupAddress, () -> {
			GroupAddressTypes.setType(groupAddress, type);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("id", id(groupAddress));
			address(result, groupAddress);
			return answer("Set. " + SAVED, result);
		});
	}

	/**
	 * The communication objects of a device.
	 */
	@ToolMethod(description = "Lists the communication objects of a device of an E-Home project that can be"
		+ " connected (those in effect for its parameters, and those no longer in effect that still have"
		+ " connections): each with its number, text, function, size in bits, datapoint type, flags"
		+ " (C communication, R read, W write, T transmit, U update, I read on init), the direction a new"
		+ " connection gets by default, and its connections with the group address and direction ('send': the"
		+ " sending address, the first address of every object, inputs included; 'receive': an additional"
		+ " listening address). The flags say what the object does: W it takes values from the bus, T it"
		+ " transmits its value. An object"
		+ " switched off by a parameter is not listed; set the parameter first.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = ToolSupport.PROJECT)
	public ToolResult deviceObjects(
			@P("The device") TLObject device) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device.");
		}
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		if (configuration.getProgram() == null) {
			return failure("The device " + GroupAddressConnections.deviceLabel(device)
				+ " runs no application program of the catalog; it has no communication objects.");
		}
		Resources resources = Resources.getInstance();
		List<Object> objects = new ArrayList<>();
		for (Interface placed : DeviceSettings.interfaces(device)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("number", Integer.valueOf(placed.getPort().getIndex()));
			entry.put("text", GroupAddressConnections.objectName(device, placed, resources));
			String function = text(resources, placed.getFunction());
			entry.put("function", function != null ? function : text(resources, placed.getPort().getFunction()));
			entry.put("size", Integer.valueOf(ObjectTypes.size(placed)));
			TLObject type = ObjectTypes.effective(device, placed);
			entry.put("datapointType", type == null ? null : label(type));
			TLObject connector = ObjectTypes.connector(device, placed);
			StringBuilder flags = new StringBuilder();
			for (String[] flag : FLAGS) {
				if (DatapointSettings.enabled(flag[0], connector, placed)) {
					flags.append(flag[1]);
				}
			}
			entry.put("flags", flags.toString());
			if (!configuration.getActiveInterfaces().containsKey(placed.getPort())) {
				entry.put("inEffect", Boolean.FALSE);
			}
			entry.put("defaultDirection", DeviceSettings.defaultKind(device, placed).getName());
			List<Object> connections = new ArrayList<>();
			for (TLObject connection : DeviceSettings.connections(device, placed)) {
				Map<String, Object> link = new LinkedHashMap<>();
				link.put("id", id(connection));
				TLObject target = connection.tValueByName("target") instanceof TLObject ga ? ga : null;
				link.put("groupAddress", target == null ? null : ref(target, DatapointNames.name(target)));
				link.put("direction", connection.tValueByName("kind") instanceof TLClassifier kind ? kind.getName() : null);
				connections.add(link);
			}
			entry.put("connections", connections);
			objects.add(entry);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", ref(device, GroupAddressConnections.deviceLabel(device)));
		result.put("objects", objects);
		return answer("The communication objects:", result);
	}

	/**
	 * Connects a communication object to a group address.
	 */
	@ToolMethod(description = "Connects a communication object of a device (its number from deviceObjects) to a"
		+ " group address of the same E-Home project. As in KNX, the first address of an object is its sending"
		+ " address ('send'): the object transmits on it and also takes values from it, so an input (a"
		+ " switching object of an actuator, flags CW) is connected with 'send' to the address that commands"
		+ " it. Every further address is a listening address ('receive'): the object takes values from it"
		+ " but never transmits on it (a central address, the status address a toggle key listens to)."
		+ " Omit 'direction' for this default: send while the object has no sending address, else receive -"
		+ " an object has one sending address only. The application refuses a"
		+ " second connection to the same address and a second sending address. It also refuses an object whose"
		+ " size or datapoint type does not fit the address or its other objects, naming the problem; pass"
		+ " despiteProblems=true only when the user wants it connected anyway. With dryRun=true nothing is"
		+ " connected and the answer gives the direction and the problems. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = ToolSupport.PROJECT, dryRun = "dryRun")
	public ToolResult connectObject(ToolContext context,
			@P("The device") TLObject device,
			@P("The number of the communication object") Integer object,
			@P("The group address") TLObject groupAddress,
			@P(value = "'send' or 'receive'; omit for the default", required = false) String direction,
			@P(value = "true to connect although the object does not fit the address", required = false)
			Boolean despiteProblems,
			@P(value = "true to only check", required = false) Boolean dryRun) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device. Nothing was connected.");
		}
		if (!ExtIds.isA(groupAddress, GROUP_ADDRESS)) {
			return failure("'" + id(groupAddress) + "' is no group address. Nothing was connected.");
		}
		Interface placed = object == null ? null : object(device, object.intValue());
		if (placed == null) {
			return failure("The device has no communication object " + object + " in effect. Read deviceObjects;"
				+ " an object switched off by a parameter comes with that parameter. Nothing was connected.");
		}
		TLClassifier kind;
		if (direction == null || direction.isBlank()) {
			kind = DeviceSettings.defaultKind(device, placed);
		} else {
			kind = ((TLEnumeration) TLModelUtil.findType("ehome.physical:ConnectionType"))
				.getClassifier(direction.trim().toLowerCase(Locale.ROOT));
			if (kind == null) {
				return failure("'direction' is 'send' or 'receive', got '" + direction + "'. Nothing was connected.");
			}
		}
		List<String> before = texts(GroupAddressConnections.problems(groupAddress));
		List<ResKey> added = new ArrayList<>();
		for (ResKey problem : GroupAddressConnections.problemsWith(groupAddress, device, placed)) {
			if (!before.contains(Resources.getInstance().getString(problem))) {
				added.add(problem);
			}
		}
		if (!added.isEmpty() && !Boolean.TRUE.equals(despiteProblems)) {
			return refused(groupAddress, added);
		}
		if (Boolean.TRUE.equals(dryRun)) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("direction", kind.getName());
			result.put("problems", texts(added));
			return answer("The object can be connected. Nothing was connected.", result);
		}
		return changing(context, device, () -> {
			TLObject connection = DeviceSettings.connect(device, placed, groupAddress, kind);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("id", id(connection));
			result.put("device", ref(device, GroupAddressConnections.deviceLabel(device)));
			result.put("object", placed.getPort().getIndex() + " " + GroupAddressConnections.objectName(device, placed, Resources.getInstance()));
			result.put("groupAddress", ref(groupAddress, DatapointNames.name(groupAddress)));
			result.put("direction", kind.getName());
			if (!added.isEmpty()) {
				result.put("problems", texts(added));
			}
			return answer("Connected. " + SAVED, result);
		});
	}

	/**
	 * Removes a connection.
	 */
	@ToolMethod(description = "Removes a connection of a communication object to a group address in an E-Home"
		+ " project (its id from deviceObjects). The object and the address stay. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, types = ToolSupport.PROJECT)
	public ToolResult disconnectObject(ToolContext context,
			@P("The connection") TLObject connection) {
		if (!ExtIds.isA(connection, "ehome.physical:Connection")) {
			return failure("'" + id(connection) + "' is no connection. Read deviceObjects for the ids. Nothing was"
				+ " changed.");
		}
		TLObject device = GroupAddressConnections.device(connection);
		String text = GroupAddressConnections.label(connection);
		TLObject target = connection.tValueByName("target") instanceof TLObject ga ? ga : null;
		return changing(context, device != null ? device : connection, () -> {
			DeviceSettings.disconnect(connection);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("removed", text);
			result.put("groupAddress", target == null ? null : ref(target, DatapointNames.name(target)));
			return answer("Disconnected. " + SAVED, result);
		});
	}

	/**
	 * The communication object of the given number the device can connect.
	 */
	private static Interface object(TLObject device, int number) {
		for (Interface placed : GroupAddressConnections.objects(device)) {
			if (placed.getPort().getIndex() == number) {
				return placed;
			}
		}
		return null;
	}

	private static List<String> texts(List<ResKey> keys) {
		List<String> result = new ArrayList<>();
		for (ResKey key : keys) {
			result.add(Resources.getInstance().getString(key));
		}
		return result;
	}

	private static String text(Resources resources, ResKey key) {
		if (key == null) {
			return null;
		}
		String result = resources.getString(key, null);
		return result == null || result.isBlank() ? null : result;
	}

}
