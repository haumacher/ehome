/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLFormObjectBase;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.rules.RuleText;

/**
 * The readable ids of the group addresses of a project (haumacher/ehome #130): the datapoint of a
 * group address in the runtime image has the address's <code>identifier</code> as its id
 * (<code>kueche.licht</code>, referenced by rules as <code>@kueche.licht</code>), else its address in
 * three levels (<code>1/2/3</code>, referenced as <code>@"1/2/3"</code>). The KNX binding of the
 * datapoint carries the address either way.
 *
 * <p>
 * An identifier is a datapoint name of the rule language (homecontrol
 * <code>spec/rules-grammar.ebnf</code>: identifiers of letters, digits and underscores joined by dots,
 * the first starting with a letter or underscore and no keyword) of 1..63 bytes, unique among the
 * datapoints of the project (the identifiers of its group addresses and the ids of its application
 * datapoints), not an id the controller adds itself (<code>knx.time</code>, <code>knx.date</code>,
 * <code>knx.datetime</code>, <code>zigbee.…</code>); the prefixes <code>rule/</code>,
 * <code>schedule/</code> and <code>block/</code> are no datapoint names anyway
 * ({@link #problem(Object, Object)}).
 * </p>
 *
 * <p>
 * The proposal ({@link #propose(TLObject)}) derives the missing identifiers: of an address a function
 * uses, from the function's room and name and the role (<code>kueche.licht</code>,
 * <code>kueche.licht.state</code>); else from the address's name, else from its description, after
 * the room of the connected devices when they share one (<code>kueche.taste_2_kurz</code> for
 * "Küche Taste 2 Kurz" of a push button in the kitchen, <code>licht_kueche</code> without a room); made
 * unique with a counter (<code>_2</code>).
 * </p>
 */
public final class GroupAddressIds {

	/** The ids the controller gives the datapoints of its time master. */
	private static final Set<String> TIME_MASTER_IDS = Set.of("knx.time", "knx.date", "knx.datetime");

	/** The prefix of the ids of the datapoints the controller adds for joined Zigbee devices. */
	private static final String ZIGBEE_PREFIX = "zigbee.";

	/** The words of the rule language that are no identifiers. */
	private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "true", "false");

	private GroupAddressIds() {
		// Static utility.
	}

	/**
	 * The identifier of the group address, <code>null</code> for none.
	 */
	public static String identifier(TLObject address) {
		Object id = address.tValueByName("identifier");
		return id == null || id.toString().isBlank() ? null : id.toString().trim();
	}

	/**
	 * The id of the datapoint of the group address in the runtime image: its identifier, else its
	 * address in three levels; <code>null</code> for an address without both.
	 */
	public static String datapointId(TLObject address) {
		String identifier = identifier(address);
		if (identifier != null) {
			return identifier;
		}
		return address(address);
	}

	/**
	 * The address of the group address in three levels, the notation of the KNX binding of the
	 * image; <code>null</code> for an address without one.
	 */
	public static String address(TLObject address) {
		return address.tValueByName("address") instanceof GA ga ? RuntimeImage.threeLevel(ga.getValue()) : null;
	}

	/**
	 * Adds to datapoints by their ids in the image the group addresses among them by their addresses
	 * in three levels, where these are no id of the map: an address names its datapoint on input also
	 * where the datapoint has an identifier.
	 */
	public static void addAddresses(Map<String, TLObject> byId) {
		for (TLObject datapoint : new ArrayList<>(byId.values())) {
			if (Pages.isGroupAddress(datapoint)) {
				String address = address(datapoint);
				if (address != null) {
					byId.putIfAbsent(address, datapoint);
				}
			}
		}
	}

	// ---- the check ---------------------------------------------------------------------------

	/**
	 * Why the text cannot be the identifier of the given group address; <code>null</code> if it can
	 * or is empty (no identifier).
	 *
	 * @param value
	 *        The identifier entered.
	 * @param obj
	 *        The group address, in a form the form's object.
	 */
	public static ResKey problem(Object value, Object obj) {
		if (value == null || value.toString().isEmpty()) {
			return null;
		}
		String id = value.toString();
		ResKey syntax = syntaxProblem(id);
		if (syntax != null) {
			return syntax;
		}
		TLObject self = obj instanceof TLFormObjectBase form ? form.getEditedObject() : obj instanceof TLObject o ? o : null;
		TLObject project = self == null || !self.tValid() ? null : RuntimeFunctions.project(self);
		if (project == null) {
			return null;
		}
		for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
			if (address != self && id.equals(identifier(address))) {
				return I18NConstants.GROUP_ADDRESS_ID_DUPLICATE__ID_ADDRESS.fill(id, holder(address));
			}
		}
		for (TLObject datapoint : ApplicationDatapoints.of(project)) {
			if (id.equals(ApplicationDatapoints.id(datapoint))) {
				return I18NConstants.GROUP_ADDRESS_ID_OF_APPLICATION_DATAPOINT__ID_DATAPOINT.fill(id,
					DatapointNames.name(datapoint));
			}
		}
		return null;
	}

	/**
	 * Why identifiers given to several group addresses of the project at once cannot be set: each
	 * checked as {@link #problem(Object, Object)} checks one, against the identifiers the project's
	 * addresses have after the change (so that two addresses may exchange theirs).
	 *
	 * @param changes
	 *        The new identifiers by group address; <code>null</code> or an empty text removes one.
	 * @return The problems by group address, in the order of the changes; empty if there is none.
	 */
	public static Map<TLObject, ResKey> problems(TLObject project, Map<TLObject, String> changes) {
		Map<TLObject, String> after = new LinkedHashMap<>();
		for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
			String id = changes.containsKey(address) ? changes.get(address) : identifier(address);
			if (id != null && !id.isEmpty()) {
				after.put(address, id);
			}
		}
		Map<TLObject, ResKey> result = new LinkedHashMap<>();
		for (Map.Entry<TLObject, String> change : changes.entrySet()) {
			String id = change.getValue();
			if (id == null || id.isEmpty()) {
				continue;
			}
			ResKey problem = syntaxProblem(id);
			if (problem == null) {
				for (Map.Entry<TLObject, String> other : after.entrySet()) {
					TLObject address = other.getKey();
					if (address != change.getKey() && id.equals(other.getValue())) {
						problem = changes.containsKey(address)
							? I18NConstants.GROUP_ADDRESS_ID_TWICE__ID_ADDRESS.fill(id,
								DatapointNames.format(DatapointNames.label(address), address(address)))
							: I18NConstants.GROUP_ADDRESS_ID_DUPLICATE__ID_ADDRESS.fill(id, holder(address));
						break;
					}
				}
			}
			if (problem == null) {
				for (TLObject datapoint : ApplicationDatapoints.of(project)) {
					if (id.equals(ApplicationDatapoints.id(datapoint))) {
						problem = I18NConstants.GROUP_ADDRESS_ID_OF_APPLICATION_DATAPOINT__ID_DATAPOINT.fill(id,
							DatapointNames.name(datapoint));
						break;
					}
				}
			}
			if (problem != null) {
				result.put(change.getKey(), problem);
			}
		}
		return result;
	}

	/**
	 * Why the text cannot be the identifier of any group address, regardless of the project: no
	 * datapoint name of the rule language, longer than 63 bytes, or reserved; <code>null</code> if it
	 * can.
	 */
	public static ResKey syntaxProblem(String id) {
		if (!RuleText.isBareName(id)) {
			return I18NConstants.GROUP_ADDRESS_ID_SYNTAX__ID.fill(id);
		}
		if (id.getBytes(StandardCharsets.UTF_8).length > ImageValidator.MAX_NAME) {
			return I18NConstants.GROUP_ADDRESS_ID_TOO_LONG__LIMIT.fill(Integer.valueOf(ImageValidator.MAX_NAME));
		}
		if (TIME_MASTER_IDS.contains(id) || id.startsWith(ZIGBEE_PREFIX)) {
			return I18NConstants.DATAPOINT_ID_RESERVED__ID.fill(id);
		}
		return null;
	}

	// ---- the proposal ------------------------------------------------------------------------

	/**
	 * The identifiers proposed for the group addresses of the project that have none, in the order
	 * of their addresses: valid ({@link #syntaxProblem(String)}) and unique among the project's
	 * datapoints and among each other. An address that neither a function uses nor has a name or
	 * description gets none.
	 */
	public static Map<TLObject, String> propose(TLObject project) {
		Set<String> taken = taken(project);
		List<TLObject> addresses = RuntimeFunctions.groupAddresses(project);
		Map<TLObject, List<String>> uses = uses(project);
		Map<TLObject, String> result = new LinkedHashMap<>();
		for (TLObject address : addresses) {
			if (identifier(address) != null) {
				continue;
			}
			List<String> parts = uses.get(address);
			if (parts == null) {
				parts = nameParts(address);
			}
			if (parts == null) {
				continue;
			}
			result.put(address, unique(parts, taken));
		}
		return result;
	}

	/**
	 * The ids no new identifier of the project may take: the identifiers of its group addresses, the
	 * ids of its application datapoints and the ids the controller adds itself.
	 */
	public static Set<String> taken(TLObject project) {
		Set<String> taken = new HashSet<>(TIME_MASTER_IDS);
		for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
			String identifier = identifier(address);
			if (identifier != null) {
				taken.add(identifier);
			}
		}
		for (TLObject datapoint : ApplicationDatapoints.of(project)) {
			String id = ApplicationDatapoints.id(datapoint);
			if (id != null) {
				taken.add(id);
			}
		}
		return taken;
	}

	/**
	 * The identifier proposed for the group address of the given role of the function, as
	 * {@link #propose(TLObject)} proposes it for an address a function uses: the function's room, its
	 * name without the words of the room, and the role unless it is the given main role
	 * (<code>kueche.licht</code>, <code>kueche.licht.state</code>); unique among the taken ones, which it
	 * is added to. <code>null</code> for a function without a name.
	 */
	public static String proposeFor(TLObject function, String role, String mainRole, Set<String> taken) {
		List<String> parts = functionParts(function);
		if (parts == null) {
			return null;
		}
		if (!role.equals(mainRole)) {
			parts.add(role);
		}
		return unique(parts, taken);
	}

	/**
	 * The parts of the proposal of the group addresses functions use: of the first function using an
	 * address (in the project's order) its room, its name without the words of the room, and the role
	 * unless it is the main role of the function's kind.
	 */
	private static Map<TLObject, List<String>> uses(TLObject project) {
		Map<TLObject, List<String>> result = new LinkedHashMap<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			String kind = function.tValueByName("kind") instanceof TLClassifier classifier ? classifier.getName() : null;
			String main = FunctionKinds.boundMainRole(kind, function::tValueByName);
			List<String> base = functionParts(function);
			if (base == null) {
				continue;
			}
			for (TLStructuredTypePart part : function.tType().getAllParts()) {
				if (!(part instanceof TLReference) || part.isMultiple() || !Pages.isGroupAddress(function.tValue(part))) {
					continue;
				}
				List<String> parts = new ArrayList<>(base);
				if (!part.getName().equals(main)) {
					parts.add(part.getName());
				}
				result.putIfAbsent((TLObject) function.tValue(part), parts);
			}
		}
		return result;
	}

	private static List<String> functionParts(TLObject function) {
		String name = segment(DatapointNames.label(function));
		if (name == null) {
			return null;
		}
		String room = function.tValueByName("room") instanceof TLObject part ? segment(DatapointNames.label(part)) : null;
		if (room == null) {
			return new ArrayList<>(List.of(name));
		}
		// "Licht Küche" in the room "Küche" is the light of the kitchen: kueche.licht.
		List<String> words = new ArrayList<>(Arrays.asList(name.split("_")));
		words.removeAll(Arrays.asList(room.split("_")));
		return new ArrayList<>(List.of(room, words.isEmpty() ? name : String.join("_", words)));
	}

	private static List<String> nameParts(TLObject address) {
		String name = segment(text(address.tValueByName("name")));
		if (name == null) {
			String description = text(address.tValueByName("description"));
			name = description == null ? null : segment(description.lines().findFirst().orElse(null));
		}
		if (name == null) {
			return null;
		}
		String room = room(address);
		if (room == null) {
			return new ArrayList<>(List.of(name));
		}
		// "Küche Taste 2 Kurz" sent by a device in the kitchen: kueche.taste_2_kurz, as for a function.
		List<String> words = new ArrayList<>(Arrays.asList(name.split("_")));
		words.removeAll(Arrays.asList(room.split("_")));
		return new ArrayList<>(List.of(room, words.isEmpty() ? name : String.join("_", words)));
	}

	/**
	 * The room of the devices connected to the given address as a part of a datapoint name, when they
	 * are all placed in the same one; <code>null</code> otherwise.
	 */
	private static String room(TLObject address) {
		TLObject room = null;
		for (TLObject connection : GroupAddressConnections.connections(address)) {
			TLObject device = GroupAddressConnections.device(connection);
			Object location = device == null ? null : device.tValueByName("location");
			if (!(location instanceof TLObject place)) {
				return null;
			}
			if (room != null && room != place) {
				return null;
			}
			room = place;
		}
		return room == null ? null : segment(DatapointNames.label(room));
	}

	/**
	 * The group address holding an identifier, by its name and address: the identifier itself is
	 * named by the refusal already.
	 */
	private static String holder(TLObject address) {
		return DatapointNames.format(DatapointNames.label(address), String.valueOf(address.tValueByName("address")));
	}

	private static String text(Object value) {
		return value == null || value.toString().isBlank() ? null : value.toString();
	}

	/**
	 * The given text as a part of a datapoint name: lower case letters, digits and underscores
	 * ("Licht Küche" becomes "licht_kueche"); <code>null</code> if nothing of it remains.
	 */
	public static String segment(String text) {
		if (text == null) {
			return null;
		}
		String result = text.toLowerCase(Locale.ROOT)
			.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
		result = Normalizer.normalize(result, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		result = result.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		return result.isEmpty() ? null : result;
	}

	/**
	 * The parts joined by dots, a valid datapoint name of at most 63 bytes, not among the taken ones
	 * (which it is added to): the parts shortened from the longest, a counter appended where needed.
	 */
	private static String unique(List<String> parts, Set<String> taken) {
		List<String> fitted = new ArrayList<>(parts);
		String first = fitted.get(0);
		if (!Character.isLetter(first.charAt(0))) {
			first = "_" + first;
		}
		if (KEYWORDS.contains(first)) {
			first = first + "_";
		}
		fitted.set(0, first);
		String base = fit(fitted, ImageValidator.MAX_NAME);
		String result = base;
		for (int n = 2; !taken.add(result); n++) {
			String suffix = "_" + n;
			result = fit(fitted, ImageValidator.MAX_NAME - suffix.length()) + suffix;
		}
		return result;
	}

	/** The parts joined by dots in at most the given length (ASCII: bytes), shortening the longest. */
	private static String fit(List<String> parts, int length) {
		List<String> result = new ArrayList<>(parts);
		while (String.join(".", result).length() > length) {
			int longest = 0;
			for (int n = 1; n < result.size(); n++) {
				if (result.get(n).length() > result.get(longest).length()) {
					longest = n;
				}
			}
			String part = result.get(longest);
			String shorter = part.substring(0, part.length() - 1).replaceAll("_+$", "");
			result.set(longest, shorter.isEmpty() || shorter.equals("_") ? part.substring(0, part.length() - 1) : shorter);
		}
		return String.join(".", result);
	}

}
