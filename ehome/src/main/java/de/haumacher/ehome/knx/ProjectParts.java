/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.element.meta.AttributeOperations;
import com.top_logic.element.meta.AttributeWithFallbackStorage;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;

/**
 * Who owns which part of a project: ETS (what a <code>.knxproj</code> carries and the ETS import
 * writes) or E-Home (what E-Home alone holds), and which of E-Home's parts are operating history.
 *
 * <p>
 * The split is declared once here and used by the project file (<code>.ehome</code>,
 * {@code de.haumacher.ehome.projectfile.ProjectFile}: the <code>.knxproj</code> carries the part of
 * ETS, the JSON document the part of E-Home) and by the update of a project from ETS
 * ({@code de.haumacher.ehome.importer.ProjectUpdate}, haumacher/ehome #125: ETS wins on its part,
 * E-Home keeps its own).
 * </p>
 *
 * <p>
 * <b>The KNX part</b> of a project is what its references {@link #PROJECT_REFERENCES} hold, with
 * every part reached through the composite references ETS owns: areas, lines, devices with their
 * parameter settings, communication objects, connections, module and channel instances, group ranges
 * and addresses, building parts. Of the project itself, ETS owns its ID, its name, the notation of
 * its group addresses and these references; everything else of the project is E-Home's (rules,
 * functions, pages, Zigbee devices, controllers, settings, …).
 * </p>
 *
 * <p>
 * Every stored attribute of a type of the KNX part is declared as owned by ETS ({@link #isEtsOwned})
 * or by E-Home ({@link #isAppOwned}): on a group address E-Home's settings for the runtime image and
 * the history (with its composite reference to the archives), on a device the revision of its last
 * download by E-Home, the identifier of a group address (haumacher/ehome #130). Of ETS's texts, the
 * name and the description of a group address are kept by an update where the file has none
 * ({@link #isKeptWhenEmpty}). A new attribute of such a type has to be declared here (checked by
 * {@code test.de.haumacher.ehome.importer.TestProjectUpdate}), so that an update neither overwrites
 * E-Home's data nor leaves ETS's stale.
 * </p>
 *
 * <p>
 * An object of the KNX part without an ID of its own is identified by its natural key within its
 * container ({@link #naturalKey(TLObject)}): a parameter setting by its parameter (and module
 * instance), a communication object by the program's object (and module instance), a connection by
 * its kind and group address, a module instance by its placement, parent and repetition, an argument
 * by the argument of the module.
 * </p>
 */
public final class ProjectParts {

	/**
	 * The references of a project leading into its KNX part.
	 */
	public static final Set<String> PROJECT_REFERENCES = Set.of("areas", "unassignedDevices", "buildings",
		"groupAddresses");

	/**
	 * The attributes ETS owns, by the type defining them.
	 */
	private static final Set<String> ETS_OWNED = Set.of(
		"ehome.core:Externalized#extId",
		"ehome.project:Project#name",
		"ehome.project:Project#groupAddressStyle",
		"ehome.project:Project#areas",
		"ehome.project:Project#unassignedDevices",
		"ehome.project:Project#buildings",
		"ehome.project:Project#groupAddresses",
		"ehome.physical:Area#name",
		"ehome.physical:Area#address",
		"ehome.physical:Area#lines",
		"ehome.physical:Line#name",
		"ehome.physical:Line#address",
		"ehome.physical:Line#devices",
		"ehome.physical:Device#title",
		"ehome.physical:Device#description",
		"ehome.physical:Device#installationHints",
		"ehome.physical:Device#serialNumber",
		"ehome.physical:Device#lastModified",
		"ehome.physical:Device#lastDownload",
		"ehome.physical:Device#loaded",
		"ehome.physical:Device#address",
		"ehome.physical:Device#type",
		"ehome.physical:Device#program",
		"ehome.physical:Device#location",
		"ehome.physical:Device#settings",
		"ehome.physical:Device#connectors",
		"ehome.physical:Device#moduleInstances",
		"ehome.physical:Device#channelInstances",
		"ehome.physical:ParameterSetting#parameter",
		"ehome.physical:ParameterSetting#instance",
		"ehome.operational:ParameterValue#choice",
		"ehome.operational:ParameterValue#number",
		"ehome.operational:ParameterValue#decimal",
		"ehome.operational:ParameterValue#text",
		"ehome.physical:Connector#port",
		"ehome.physical:Connector#instance",
		"ehome.physical:Connector#text",
		"ehome.physical:Connector#description",
		"ehome.physical:Connector#function",
		"ehome.physical:Connector#active",
		"ehome.physical:Connector#datapointTypes",
		"ehome.physical:Connector#priority",
		"ehome.physical:Connector#canRead",
		"ehome.physical:Connector#canWrite",
		"ehome.physical:Connector#communication",
		"ehome.physical:Connector#transmission",
		"ehome.physical:Connector#update",
		"ehome.physical:Connector#readOnInit",
		"ehome.physical:Connector#connections",
		"ehome.physical:Connection#kind",
		"ehome.physical:Connection#target",
		"ehome.physical:Connection#acknowledge",
		"ehome.physical:ModuleInstance#name",
		"ehome.physical:ModuleInstance#placement",
		"ehome.physical:ModuleInstance#parent",
		"ehome.physical:ModuleInstance#repeatIndex",
		"ehome.physical:ModuleInstance#active",
		"ehome.physical:ModuleInstance#arguments",
		"ehome.physical:InstanceArgument#argument",
		"ehome.physical:InstanceArgument#value",
		"ehome.physical:InstanceArgument#deviceValue",
		"ehome.physical:ChannelInstance#channel",
		"ehome.physical:ChannelInstance#description",
		"ehome.physical:ChannelInstance#active",
		"ehome.building:BuildingDefinition#name",
		"ehome.building:BuildingDefinition#parts",
		"ehome.logical:GroupAddressDefinition#name",
		"ehome.logical:GroupAddressDefinition#description",
		"ehome.logical:GroupAddressDefinition#unfiltered",
		"ehome.logical:GroupAddress#address",
		"ehome.logical:GroupAddress#central",
		"ehome.logical:GroupAddress#datapointTypes",
		"ehome.logical:GroupRange#rangeStart",
		"ehome.logical:GroupRange#rangeEnd",
		"ehome.logical:GroupRange#parts");

	/**
	 * The attributes of types of the KNX part that E-Home owns, by the type defining them.
	 */
	private static final Set<String> APP_OWNED = Set.of(
		"ehome.physical:Device#downloadRevision",
		"ehome.logical:GroupAddress#identifier",
		"ehome.logical:GroupAddress#runtimeWritable",
		"ehome.logical:GroupAddress#runtimeReadOnStart",
		"ehome.logical:GroupAddress#runtimeReadable",
		"ehome.logical:GroupAddress#runtimePersist",
		"ehome.logical:GroupAddress#runtimeUnit",
		"ehome.logical:GroupAddress#historyRetention",
		"ehome.logical:GroupAddress#historySpacing",
		"ehome.logical:GroupAddress#historyManual",
		"ehome.logical:GroupAddress#historyArchives");

	/**
	 * The texts ETS owns that an update from ETS does not erase: an empty text in the file keeps the
	 * text of the project, a text in the file replaces it (haumacher/ehome #130: the names and
	 * descriptions of group addresses E-Home assigned where the ETS project has none).
	 */
	private static final Set<String> KEPT_WHEN_EMPTY = Set.of(
		"ehome.logical:GroupAddressDefinition#name",
		"ehome.logical:GroupAddressDefinition#description");

	/**
	 * The composite references of a project holding its operating history: recordings of the
	 * monitor, deployments, the devices a scan did not identify.
	 */
	private static final Set<String> HISTORY = Set.of(
		"ehome.project:Project#recordings",
		"ehome.project:Project#deployments",
		"ehome.project:Project#unidentifiedDevices");

	/**
	 * The attributes identifying an object of the KNX part without an ID within its container, by the
	 * qualified name of its type.
	 */
	private static final Map<String, List<String>> NATURAL_KEYS = Map.of(
		"ehome.physical:ParameterSetting", List.of("parameter", "instance"),
		"ehome.physical:Connector", List.of("port", "instance"),
		"ehome.physical:Connection", List.of("kind", "target"),
		"ehome.physical:ModuleInstance", List.of("placement", "parent", "repeatIndex"),
		"ehome.physical:InstanceArgument", List.of("argument"));

	private ProjectParts() {
		// Declarations only.
	}

	/**
	 * Whether ETS owns the given attribute: the <code>.knxproj</code> carries it, an update from ETS
	 * sets it.
	 */
	public static boolean isEtsOwned(TLStructuredTypePart part) {
		return ETS_OWNED.contains(qualifiedName(part));
	}

	/**
	 * Whether E-Home owns the given attribute of a type of the KNX part (not of the project, of which
	 * E-Home owns everything ETS does not, see {@link #isEtsOwned(TLStructuredTypePart)}).
	 */
	public static boolean isAppOwned(TLStructuredTypePart part) {
		return APP_OWNED.contains(qualifiedName(part));
	}

	/**
	 * Whether an update from ETS keeps the project's value of the given attribute ETS owns where the
	 * file has none: a text E-Home may fill in where the ETS project left it empty (the name and
	 * description of a group address), which a text in the file replaces.
	 */
	public static boolean isKeptWhenEmpty(TLStructuredTypePart part) {
		return KEPT_WHEN_EMPTY.contains(qualifiedName(part));
	}

	/**
	 * Whether the given attribute is declared, as owned by ETS or by E-Home.
	 */
	public static boolean isDeclared(TLStructuredTypePart part) {
		return isEtsOwned(part) || isAppOwned(part);
	}

	/**
	 * Whether the given composite reference of a project holds operating history (recordings,
	 * deployments, devices a scan did not identify).
	 */
	public static boolean isHistory(TLStructuredTypePart part) {
		return HISTORY.contains(qualifiedName(part));
	}

	/**
	 * Whether the given object belongs to the operating history of its project: it is held, directly
	 * or through its containers, by a reference of {@link #isHistory(TLStructuredTypePart)}.
	 */
	public static boolean isInHistory(TLObject obj) {
		for (TLObject current = obj; current != null; current = current.tContainer()) {
			TLReference reference = current.tContainerReference();
			if (reference != null && isHistory(reference)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The qualified name of an attribute by the type defining it: <code>module:Type#name</code>.
	 */
	public static String qualifiedName(TLStructuredTypePart part) {
		return TLModelUtil.qualifiedName(part.getDefinition().getOwner()) + "#" + part.getName();
	}

	/**
	 * Whether the given attribute is stored: neither derived nor a backward reference, nor a view of
	 * other attributes (a value with a fallback, <code>&lt;fallback-storage&gt;</code>, stores its value
	 * in another attribute).
	 */
	public static boolean isStored(TLStructuredTypePart part) {
		return !part.isDerived() && !(part instanceof TLReference reference && reference.isBackwards())
			&& !(AttributeOperations.getStorageImplementation(part) instanceof AttributeWithFallbackStorage);
	}

	/**
	 * The composite references of the given type that lead further into the KNX part: those ETS
	 * owns.
	 */
	public static List<TLReference> knxCompositions(TLStructuredType type) {
		List<TLReference> result = new ArrayList<>();
		for (TLStructuredTypePart part : type.getAllParts()) {
			if (part instanceof TLReference reference && reference.isComposite() && isStored(reference)
				&& isEtsOwned(reference)) {
				result.add(reference);
			}
		}
		return result;
	}

	/**
	 * The objects of the KNX part of the given project, each container before its parts.
	 */
	public static Set<TLObject> objects(TLObject project) {
		Set<TLObject> result = new LinkedHashSet<>();
		for (String name : PROJECT_REFERENCES) {
			for (TLObject part : values(project.tValueByName(name))) {
				collect(result, part);
			}
		}
		return result;
	}

	private static void collect(Set<TLObject> result, TLObject obj) {
		if (!result.add(obj)) {
			return;
		}
		for (TLReference reference : knxCompositions(obj.tType())) {
			for (TLObject child : values(obj.tValue(reference))) {
				collect(result, child);
			}
		}
	}

	/**
	 * The attributes identifying an object of the given type without an ID within its container,
	 * <code>null</code> if objects of the type have none.
	 */
	public static List<String> naturalKey(TLObject obj) {
		return NATURAL_KEYS.get(TLModelUtil.qualifiedName(obj.tType()));
	}

	/**
	 * The objects of a reference's value.
	 */
	public static List<TLObject> values(Object value) {
		if (value instanceof Collection<?> collection) {
			List<TLObject> result = new ArrayList<>(collection.size());
			for (Object element : collection) {
				result.add((TLObject) element);
			}
			return result;
		}
		return value == null ? List.of() : List.of((TLObject) value);
	}

}
