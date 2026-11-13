/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import static de.haumacher.ehome.ai.ToolSupport.SAVED;
import static de.haumacher.ehome.ai.ToolSupport.answer;
import static de.haumacher.ehome.ai.ToolSupport.changing;
import static de.haumacher.ehome.ai.ToolSupport.failure;
import static de.haumacher.ehome.ai.ToolSupport.id;
import static de.haumacher.ehome.ai.ToolSupport.project;
import static de.haumacher.ehome.ai.ToolSupport.ref;
import static de.haumacher.ehome.ai.ToolSupport.refused;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.tool.api.P;
import com.top_logic.tl.ai.tool.api.ToolGroups;
import com.top_logic.tl.ai.tool.api.ToolMethod;
import com.top_logic.tl.ai.tool.api.ToolMode;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The assistant's tools for the building structure of a project (haumacher/ehome #127): reading it,
 * creating parts in order, moving parts, and placing a device in a part.
 *
 * <p>
 * The tools run the operations of the building view (<code>projects/buildings.view.xml</code>):
 * {@link BuildingDevices#insertVeto(TLObject, TLStructuredType)} decides which part may be created
 * where (the structure of ETS), {@link BuildingDevices#move(Collection, TLObject, TLObject)} moves
 * parts and {@link BuildingDevices#place(TLObject, TLObject)} places a device. A new part is added
 * at the end of its container, as the create dialog adds it, and gets its external ID when the turn
 * commits.
 * </p>
 */
public class BuildingTools {

	private static final String BUILDING_MODULE = "ehome.building";

	private static final String BUILDING_DEFINITION = "ehome.building:BuildingDefinition";

	/** The kinds of building parts, by the names the tools take. */
	private static final List<String> KINDS =
		List.of("Building", "BuildingPart", "Floor", "Room", "Corridor", "Stairway", "Location", "DistributionBoard");

	/** How deep one call creates parts. */
	private static final int MAX_DEPTH = 4;

	/**
	 * The building structure of a project.
	 */
	@ToolMethod(description = "Lists the building structure of an E-Home project: its buildings and their"
		+ " parts (floors, rooms, corridors, stairways, locations, distribution boards) as a tree in their"
		+ " order, each with its identifier, kind, name and the number of devices placed directly in it."
		+ " Read it before you create, move or name parts, and to find the identifier of a room to place a"
		+ " device in.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult buildingStructure(
			@P("The project, or any object of it (a building part, a device)") TLObject project) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project.");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("project", ref(owner));
		List<Object> buildings = new ArrayList<>();
		for (TLObject building : ExtIds.list(owner, "buildings")) {
			buildings.add(node(building));
		}
		result.put("buildings", buildings);
		result.put("unplacedDevices", BuildingDevices.unplaced(owner).size());
		return answer(buildings.isEmpty() ? "The project has no building yet." : "The building structure:", result);
	}

	private static Map<String, Object> node(TLObject part) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id(part));
		result.put("kind", part.tType().getName());
		result.put("name", part.tValueByName("name"));
		result.put("devices", BuildingDevices.devices(part).size());
		List<Object> parts = new ArrayList<>();
		for (TLObject child : ExtIds.list(part, "parts")) {
			parts.add(node(child));
		}
		if (!parts.isEmpty()) {
			result.put("parts", parts);
		}
		return result;
	}

	/**
	 * Creates building parts, with the parts inside them.
	 */
	@ToolMethod(description = "Creates building parts in E-Home, with the parts inside them, in the given"
		+ " order: e.g. under a building the floor \"Ground floor\" with the rooms \"Kitchen\" and \"Living"
		+ " room\" in one call. Kinds: Building (only in the project), BuildingPart and Floor (in a building or"
		+ " building part), Room, Corridor and Stairway (in a floor, building or building part), Location"
		+ " and DistributionBoard (in any part but a distribution board, which takes devices only). New parts"
		+ " are added after the existing parts of the parent unless 'before' names one of them. The answer"
		+ " lists the created parts with their identifiers. With dryRun=true nothing is created and the"
		+ " answer says whether the parts can be created. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE,
		dryRun = "dryRun")
	public ToolResult createBuildingParts(ToolContext context,
			@P("Where to create them: the project (for buildings) or a building part") TLObject parent,
			@P("The new parts, each {\"kind\": \"Floor\", \"name\": \"Ground floor\", \"parts\": [...]}; 'parts'"
				+ " optional, nested up to " + MAX_DEPTH + " levels") List<Map<String, Object>> parts,
			@P(value = "An existing part of the parent to put the new parts before; omit to add them at the end",
				required = false) TLObject before,
			@P(value = "true to only check whether the parts can be created", required = false) Boolean dryRun) {
		if (parent == null || !(ExtIds.isA(parent, ToolSupport.PROJECT) || ExtIds.isA(parent, BUILDING_DEFINITION))) {
			return failure("'parent' must be a project or a building part. Nothing was created.");
		}
		if (parts == null || parts.isEmpty()) {
			return failure("'parts' is empty. Pass the parts to create, e.g. [{\"kind\": \"Room\", \"name\": \"Kitchen\"}].");
		}
		if (before != null && !ExtIds.list(parent, partsReference(parent)).contains(before)) {
			return failure("'before' must be a part directly inside the parent. Nothing was created.");
		}
		List<ResKey> vetoes = new ArrayList<>();
		String problem = check(parent.tType(), String.valueOf(ToolSupport.label(parent)), parts, 1, vetoes);
		if (problem != null) {
			return failure(problem + " Nothing was created.");
		}
		if (!vetoes.isEmpty()) {
			return refused(parent, vetoes);
		}
		if (Boolean.TRUE.equals(dryRun)) {
			return ToolResult.of("The parts can be created in " + ToolSupport.label(parent) + ". Nothing was created.");
		}
		return changing(context, parent, () -> {
			List<TLObject> created = new ArrayList<>();
			List<Object> answer = new ArrayList<>();
			for (Map<String, Object> part : parts) {
				TLObject object = create(parent, part);
				created.add(object);
				answer.add(createdNode(object));
			}
			if (before != null) {
				BuildingDevices.move(created, parent, before);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("created", answer);
			result.put("parent", ref(parent));
			return answer("Created. " + SAVED, result);
		});
	}

	/**
	 * Checks the given parts to be created in a container of the given type.
	 *
	 * @param vetoes
	 *        The application's reasons against a part, added.
	 * @return A fault of the call itself, <code>null</code> if there is none.
	 */
	private static String check(TLStructuredType containerKind, String containerName, List<?> parts, int depth,
			List<ResKey> vetoes) {
		if (depth > MAX_DEPTH) {
			return "Parts nest up to " + MAX_DEPTH + " levels in one call; create deeper parts in a second call.";
		}
		for (Object element : parts) {
			if (!(element instanceof Map<?, ?> part)) {
				return "Each part is an object {\"kind\": ..., \"name\": ...}, got: " + element + ".";
			}
			TLClass kind = kind(part.get("kind"));
			if (kind == null) {
				return "Unknown kind '" + part.get("kind") + "'. Kinds: " + KINDS + ".";
			}
			Object name = part.get("name");
			if (!(name instanceof String text) || text.isBlank()) {
				return "Every part needs a 'name'.";
			}
			ResKey veto = BuildingDevices.insertVeto(containerKind, containerName, kind);
			if (veto != null) {
				vetoes.add(veto);
			}
			Object children = part.get("parts");
			if (children != null) {
				if (!(children instanceof List<?> list)) {
					return "'parts' of '" + name + "' must be a list.";
				}
				String problem = check(kind, text.trim(), list, depth + 1, vetoes);
				if (problem != null) {
					return problem;
				}
			}
		}
		return null;
	}

	private static TLObject create(TLObject container, Map<?, ?> part) {
		TLClass kind = kind(part.get("kind"));
		TLObject result = ModelService.getInstance().getFactory().createObject(kind);
		result.tUpdateByName("name", ((String) part.get("name")).trim());
		String reference = partsReference(container);
		List<TLObject> elements = ExtIds.list(container, reference);
		elements.add(result);
		container.tUpdateByName(reference, elements);
		if (part.get("parts") instanceof List<?> children) {
			for (Object child : children) {
				create(result, (Map<?, ?>) child);
			}
		}
		return result;
	}

	private static Map<String, Object> createdNode(TLObject part) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id(part));
		result.put("kind", part.tType().getName());
		result.put("name", part.tValueByName("name"));
		List<Object> parts = new ArrayList<>();
		for (TLObject child : ExtIds.list(part, "parts")) {
			parts.add(createdNode(child));
		}
		if (!parts.isEmpty()) {
			result.put("parts", parts);
		}
		return result;
	}

	/**
	 * The building part class of the given kind name (<code>Room</code>, also qualified or in any
	 * case), <code>null</code> for none.
	 */
	private static TLClass kind(Object name) {
		if (!(name instanceof String text)) {
			return null;
		}
		String simple = text.trim();
		int colon = simple.lastIndexOf(':');
		if (colon >= 0) {
			simple = simple.substring(colon + 1);
		}
		String normalized = simple.replace(" ", "").toLowerCase(Locale.ROOT);
		for (String kind : KINDS) {
			if (kind.toLowerCase(Locale.ROOT).equals(normalized)) {
				return (TLClass) TLModelUtil.findType(BUILDING_MODULE + ":" + kind);
			}
		}
		return null;
	}

	private static String partsReference(TLObject container) {
		return ExtIds.isA(container, ToolSupport.PROJECT) ? "buildings" : "parts";
	}

	/**
	 * Moves building parts.
	 */
	@ToolMethod(description = "Moves building parts of an E-Home project to another place: into another"
		+ " part (or the project, for buildings), before one of its parts or at its end. Use it to reorder"
		+ " parts within their parent as well. The parts keep their own parts and devices. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE)
	public ToolResult moveBuildingParts(ToolContext context,
			@P("The building parts to move") List<TLObject> parts,
			@P("Where to move them: a building part, or the project for buildings") TLObject container,
			@P(value = "A part of the container to put them before; omit for the end", required = false) TLObject before) {
		ResKey veto = BuildingDevices.moveVeto(parts, container, before);
		if (veto != null) {
			return refused(container, veto);
		}
		return changing(context, container, () -> {
			BuildingDevices.move(parts, container, before);
			List<Object> order = new ArrayList<>();
			for (TLObject part : ExtIds.list(container, partsReference(container))) {
				order.add(ref(part, (String) part.tValueByName("name")));
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("container", ref(container));
			result.put("parts", order);
			return answer("Moved. The parts of the container are now, in order: " + SAVED, result);
		});
	}

	/**
	 * Places a device in a building part.
	 */
	@ToolMethod(description = "Places a device of an E-Home project in a building part (a room, a"
		+ " distribution board, ...) of the same project, or removes it from its part with location=null."
		+ " A device is in one part at a time; placing it moves it there. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE)
	public ToolResult placeDevice(ToolContext context,
			@P("The device") TLObject device,
			@P(value = "The building part to place it in; omit to remove it from its part", required = false)
			TLObject location) {
		if (!ExtIds.isA(device, "ehome.physical:Device")) {
			return failure("'" + id(device) + "' is no device. Nothing was changed.");
		}
		if (location != null && !ExtIds.isA(location, BUILDING_DEFINITION)) {
			return failure("'" + id(location) + "' is no building part. Nothing was changed.");
		}
		return changing(context, device, () -> {
			BuildingDevices.place(device, location);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("device", ref(device, GroupAddressConnections.deviceLabel(device)));
			result.put("location", location == null ? null : ref(location, BuildingDevices.path(location)));
			return answer((location == null ? "Removed from its building part. " : "Placed. ") + SAVED, result);
		});
	}

}
