/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.SharedPlacement;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.template.DeviceTemplate;
import de.haumacher.ehome.model.template.ObjectOverlay;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.template.ChannelShape.Condition;

/**
 * The kinds of communication objects of an application program (haumacher/ehome #166): the rows of
 * the tab "Communication objects" of a device template.
 *
 * <p>
 * A program places its objects in its channels and in the cases of its switches; the Tastsensor of
 * the test system places 91 objects 410 times. The places of one object differ by the setting that
 * switches them on, which alone decides the datapoint type, and the channels of one structure repeat
 * the same places. <b>A kind</b> is what is equal across them: the object's text relative to its
 * channel (none where the object's text names the channel, as "Taste 1" does), its function text,
 * its size and the setting that selects it, written relative to its channel - the chain of the
 * conditions set by the switches within the channel's block, e.g. "Funktion: Wertgeber 2 Byte › Funktionsweise: Temperaturwertgeber".
 * A place the structure shares in several cases has each of their settings ("… | …"). The kind lists
 * the channels it occurs in ("Taste 1–8, Wippe 1–4").
 * </p>
 *
 * <p>
 * Kinds are compared by the texts of the program's base language (the first of its languages, the
 * fallback language of the application for a program without), so that the grouping does not
 * depend on the reader's language; the row shows them in the reader's language.
 * </p>
 *
 * <p>
 * <b>A row</b> is a dictionary (a {@link Map}) with the entries {@link #OBJECT}, {@link #SIZE},
 * {@link #SETTING}, {@link #CHANNELS} and {@link #PLACEMENTS}, computed from the program alone: it
 * reads no project data, and it stays equal while the template's types change, so that the table
 * keeps its selection. A datapoint type entered for a kind is the type of the overlay of each of its
 * places ({@link #setType(TLObject, Object, TLObject)}): the overlays stay per place, and the devices
 * read them through {@link ObjectTypes} as before.
 * </p>
 */
public final class ObjectKinds {

	/** Entry of a row: the object's text relative to its channel and its function text. */
	public static final String OBJECT = "object";

	/** Entry of a row: the size of the objects in bits. */
	public static final String SIZE = "size";

	/** Entry of a row: the setting selecting the objects, relative to their channels. */
	public static final String SETTING = "setting";

	/** Entry of a row: the channels the kind occurs in. */
	public static final String CHANNELS = "channels";

	/** Entry of a row: the places of the objects (<code>ehome.operational:Interface</code>). */
	public static final String PLACEMENTS = "placements";

	private static final String CHAIN_SEPARATOR = " › ";

	private static final String ALTERNATIVE_SEPARATOR = " | ";

	private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");

	private static final Pattern NUMBERED = Pattern.compile("^(.*?)(\\d+)$");

	private ObjectKinds() {
		// Static only.
	}

	/**
	 * The kinds of the communication objects of the program, ordered by the smallest number of their
	 * objects; with a project, the kinds a device of the project connects without a datapoint type
	 * ({@link #untyped(Object, TLObject)}) first.
	 *
	 * @param project
	 *        The project whose usage orders the kinds, <code>null</code> for none: then no project data
	 *        is read.
	 */
	public static List<Map<String, Object>> kinds(TLObject program, TLObject project) {
		return kinds(program, project, Resources.getInstance());
	}

	/**
	 * The {@link #kinds(TLObject, TLObject)} with their texts in the language of the given resources.
	 */
	public static List<Map<String, Object>> kinds(TLObject program, TLObject project, Resources language) {
		List<Map<String, Object>> result = new ArrayList<>();
		if (!(program instanceof Program)) {
			return result;
		}
		Resources keyLanguage = Resources.getInstance(baseLanguage((Program) program));

		Map<List<Object>, Kind> kinds = new LinkedHashMap<>();
		for (Interface placed : DeviceTemplates.placements(program)) {
			List<Places> found = places(placed, language);
			List<Object> key = new ArrayList<>();
			key.add(object(placed, keyLanguage));
			key.add(Integer.valueOf(ObjectTypes.size(placed)));
			key.add(setting(placed, found, keyLanguage));
			Kind kind = kinds.computeIfAbsent(key, k -> new Kind());
			if (kind._placements.isEmpty()) {
				kind._object = object(placed, language);
				kind._size = ObjectTypes.size(placed);
				kind._setting = setting(placed, found, language);
				kind._first = placed.getPort().getIndex();
			}
			kind._placements.add(placed);
			for (Places place : found) {
				kind._channels.add(place._channel);
			}
		}

		List<Kind> ordered = new ArrayList<>(kinds.values());
		ordered.sort(Comparator.comparingInt((Kind k) -> k._first)
			.thenComparing(k -> k._object)
			.thenComparing(k -> k._setting));
		if (project != null) {
			Set<Interface> untyped = new LinkedHashSet<>(DeviceTemplates.untypedConnected(program, project));
			// Stable: the kinds connected without a type first, each part in the order above.
			ordered.sort(Comparator.comparing(k -> !intersects(k._placements, untyped)));
		}
		for (Kind kind : ordered) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put(OBJECT, kind._object);
			row.put(SIZE, Integer.valueOf(kind._size));
			row.put(SETTING, kind._setting);
			row.put(CHANNELS, channels(kind._channels));
			row.put(PLACEMENTS, List.copyOf(kind._placements));
			result.add(row);
		}
		return result;
	}

	/**
	 * The places of the communication objects of the given row of {@link #kinds(TLObject, TLObject)}.
	 */
	public static List<Interface> placements(Object kind) {
		List<Interface> result = new ArrayList<>();
		if (kind instanceof Map<?, ?> row && row.get(PLACEMENTS) instanceof Collection<?> placements) {
			for (Object placed : placements) {
				if (placed instanceof Interface valid && valid.tValid()) {
					result.add(valid);
				}
			}
		}
		return result;
	}

	/**
	 * The texts of the program's base language, in which {@link #kinds(TLObject, TLObject, Resources)}
	 * compares the kinds: the language of the program's first language, the fallback language of the
	 * application for a program without.
	 */
	public static Resources baseLanguage(TLObject program) {
		return program instanceof Program valid ? Resources.getInstance(baseLanguage(valid))
			: Resources.getInstance(ResourcesModule.getInstance().getFallbackLocale());
	}

	private static Locale baseLanguage(Program program) {
		String languages = program.getLanguages();
		if (languages != null && !languages.isBlank()) {
			return Locale.forLanguageTag(languages.trim().split("\\s+")[0]);
		}
		return ResourcesModule.getInstance().getFallbackLocale();
	}

	/**
	 * The datapoint types the devices of the program inherit for the objects of the kind (the
	 * template's, else the manufacturer's, {@link ObjectTypes#inherited(Interface)}), each once: one
	 * for a kind typed alike, several for a mixed one, none for an untyped one.
	 */
	public static List<TLObject> types(Object kind) {
		Set<TLObject> result = new LinkedHashSet<>();
		for (Interface placed : placements(kind)) {
			List<TLObject> inherited = ObjectTypes.inherited(placed);
			if (!inherited.isEmpty()) {
				result.add(inherited.get(0));
			}
		}
		return new ArrayList<>(result);
	}

	/**
	 * The datapoint type the template gives every object of the kind, <code>null</code> if it gives
	 * none or the objects different ones ({@link #mixed(TLObject, Object)}).
	 */
	public static TLObject type(TLObject template, Object kind) {
		Set<TLObject> types = templateTypes(template, kind);
		return types.size() == 1 ? types.iterator().next() : null;
	}

	/**
	 * Whether the template gives the objects of the kind different datapoint types (some none): types
	 * entered for single objects before. Entering a type for the kind unifies them.
	 */
	public static boolean mixed(TLObject template, Object kind) {
		return templateTypes(template, kind).size() > 1;
	}

	/**
	 * The datapoint types offered for the kind: those of its size, all if none has its size.
	 */
	public static List<TLObject> typeChoices(Object kind) {
		List<Interface> placements = placements(kind);
		return DeviceTemplates.typeChoices(placements.isEmpty() ? null : placements.get(0));
	}

	/**
	 * Whether a device of the given project connects an object of the kind to a group address
	 * although E-Home cannot determine its datapoint type ({@link DeviceTemplates#untypedConnected(TLObject, TLObject)}).
	 *
	 * @param project
	 *        The project, mandatory: no other project is read.
	 */
	public static boolean untyped(Object kind, TLObject project) {
		Objects.requireNonNull(project, "The project is mandatory.");
		List<Interface> placements = placements(kind);
		if (placements.isEmpty()) {
			return false;
		}
		TLObject program = DeviceTemplates.program(placements.get(0));
		return intersects(placements, new LinkedHashSet<>(DeviceTemplates.untypedConnected(program, project)));
	}

	/**
	 * Gives every object of the kind the given datapoint type in the template, <code>null</code> to
	 * remove the template's type of all of them. A place without an overlay (the program was
	 * extended after the template was created) gets one.
	 *
	 * <p>
	 * Must be called within a transaction.
	 * </p>
	 *
	 * @throws TopLogicException
	 *         If there is no template, or the kind is one of another program (a selection left over
	 *         from another program).
	 */
	public static void setType(TLObject template, Object kind, TLObject type) {
		if (template == null) {
			throw new TopLogicException(I18NConstants.ERROR_KIND_NO_TEMPLATE);
		}
		Object program = ((DeviceTemplate) template).getProgram();
		for (Interface placed : placements(kind)) {
			if (DeviceTemplates.program(placed) != program) {
				throw new TopLogicException(I18NConstants.ERROR_KIND_OTHER_PROGRAM__PROGRAM.fill(program));
			}
		}
		Map<Interface, ObjectOverlay> overlays = overlays(template);
		List<ObjectOverlay> created = new ArrayList<>();
		List<TLObject> types = type == null ? List.of() : List.of(type);
		for (Interface placed : placements(kind)) {
			ObjectOverlay overlay = overlays.get(placed);
			if (overlay == null) {
				overlay = (ObjectOverlay) ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.template:ObjectOverlay"));
				overlay.setPlacement(placed);
				created.add(overlay);
			}
			if (!types.equals(overlay.getDatapointTypes())) {
				overlay.setDatapointTypes(types);
			}
		}
		if (!created.isEmpty()) {
			List<ObjectOverlay> all = new ArrayList<>(((DeviceTemplate) template).getOverlays());
			all.addAll(created);
			((DeviceTemplate) template).setOverlays(all);
		}
	}

	private static Set<TLObject> templateTypes(TLObject template, Object kind) {
		Set<TLObject> result = new LinkedHashSet<>();
		if (template == null) {
			return result;
		}
		Map<Interface, ObjectOverlay> overlays = overlays(template);
		for (Interface placed : placements(kind)) {
			ObjectOverlay overlay = overlays.get(placed);
			Object types = overlay == null ? null : overlay.getDatapointTypes();
			result.add(types instanceof List<?> list && !list.isEmpty() ? (TLObject) list.get(0) : null);
		}
		return result;
	}

	private static Map<Interface, ObjectOverlay> overlays(TLObject template) {
		Map<Interface, ObjectOverlay> result = new HashMap<>();
		for (ObjectOverlay overlay : ((DeviceTemplate) template).getOverlays()) {
			if (overlay.getPlacement() instanceof Interface placed) {
				result.putIfAbsent(placed, overlay);
			}
		}
		return result;
	}

	private static boolean intersects(Collection<Interface> placements, Set<Interface> others) {
		for (Interface placed : placements) {
			if (others.contains(placed)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The places of the placed object in the structure: its own and those of the structure's further
	 * places of it ({@link SharedPlacement}, a place in several cases of a switch), each with the
	 * conditions relative to its channel.
	 */
	private static List<Places> places(Interface placed, Resources language) {
		String channel = FunctionProposal.channelLabel(null, placed, language);
		List<Places> result = new ArrayList<>();
		result.add(new Places(channel, conditions(placed)));
		TLReference shared = (TLReference) TLModelUtil.findPart("ehome.operational:SharedPlacement#placed");
		for (TLObject further : placed.tReferers(shared)) {
			if (further != null && further.tValid() && further.tContainer() != null) {
				result.add(new Places(channel, conditions(further)));
			}
		}
		return result;
	}

	/**
	 * The conditions selecting the given place of the structure relative to its channel, outermost
	 * first: those of the switches below the outermost block (<code>ParameterGroup</code>) of the
	 * channel above the place, all those below the channel (of the program, of a module definition)
	 * where no block encloses it. A switch with a single default case groups content and sets no
	 * condition.
	 *
	 * <p>
	 * The block is the channel as the user sees it: the Tastsensor places each key ("Taste 1") and
	 * each rocker ("Wippe 1") in a block of its own, within switches on the operation concept of two
	 * keys, which select the block, not the object within it.
	 * </p>
	 */
	private static List<Condition> conditions(TLObject place) {
		List<Condition> result = new ArrayList<>();
		int inner = -1;
		TLObject child = place;
		for (TLObject current = place.tContainer(); current != null; current = current.tContainer()) {
			if (current instanceof Channel || current instanceof ChannelIndependent || current instanceof ModuleDefinition
				|| current instanceof Program) {
				break;
			}
			if (current instanceof ParameterGroup) {
				inner = result.size();
			}
			if (current instanceof Switch choice && child instanceof Case alternative
				&& !(choice.getAlternatives().size() == 1 && alternative.getIsDefault())) {
				String pattern = alternative.getPattern();
				result.add(0, new Condition(choice.getInput(), pattern == null ? null : pattern.trim(),
					alternative.getIsDefault()));
			}
			child = current;
		}
		return inner < 0 ? result : new ArrayList<>(result.subList(result.size() - inner, result.size()));
	}

	/**
	 * The object's text relative to its channel and its function text: the function text alone where
	 * the object's text is the name of its channel (the Tastsensor's "Taste 1").
	 */
	private static String object(Interface placed, Resources resources) {
		String text = GroupAddressConnections.objectName(placed, resources);
		String channel = FunctionProposal.channelLabel(null, placed, resources);
		String function = localize(resources, placed.getFunction());
		if (function == null) {
			function = localize(resources, placed.getPort().getFunction());
		}
		boolean named = text == null || text.isBlank() || text.equals(channel);
		if (named) {
			return function == null ? "" : function;
		}
		return function == null ? text : text + ": " + function;
	}

	/**
	 * The setting selecting the object at its places: the chain of the conditions of each place, the
	 * title of a parameter left out where it is the name of the channel ("Szenenausgang 2: Schalten"
	 * is "Schalten").
	 */
	private static String setting(Interface placed, List<Places> places, Resources resources) {
		String channel = FunctionProposal.channelLabel(null, placed, resources);
		Set<String> alternatives = new LinkedHashSet<>();
		for (Places place : places) {
			List<String> chain = new ArrayList<>();
			for (Condition condition : place._conditions) {
				chain.add(condition(condition, channel, resources));
			}
			alternatives.add(String.join(CHAIN_SEPARATOR, chain));
		}
		return String.join(ALTERNATIVE_SEPARATOR, alternatives);
	}

	private static String condition(Condition condition, String channel, Resources resources) {
		String title = condition.input() == null ? "" : title(condition.input(), resources);
		if (channel != null && WHITE_SPACE.matcher(title.trim()).replaceAll(" ")
			.equals(WHITE_SPACE.matcher(channel.trim()).replaceAll(" "))) {
			// A parameter titled with the channel's name ("Szenenausgang 2: Schalten") is the channel's
			// own: its value alone. A title that only contains the name keeps it, or a phrase loses its
			// object ("Funktion der Betriebs-LED" would be "Funktion der", "Alarm signal display"
			// would be "display").
			title = "";
		}
		String value;
		if (condition.pattern() == null || condition.pattern().isEmpty()) {
			value = resources.getString(I18NConstants.KIND_OTHERWISE);
		} else {
			List<String> values = new ArrayList<>();
			for (String token : condition.pattern().split("\\s+")) {
				values.add(valueText(condition.input(), token, resources));
			}
			value = String.join(", ", values);
			if (condition.isDefault()) {
				value = resources.getString(I18NConstants.KIND_OR_OTHERWISE__VALUES.fill(value));
			}
		}
		return title.isEmpty() ? value : title + ": " + value;
	}

	private static String title(ParameterView view, Resources resources) {
		Parameter parameter = view.getParameter();
		String result = localize(resources, view.getDescription());
		if (result == null && parameter != null) {
			result = localize(resources, parameter.getDescription());
		}
		if (result == null) {
			result = view.getName();
		}
		if (result == null && parameter != null) {
			result = parameter.getName();
		}
		return result == null ? "" : WHITE_SPACE.matcher(result.trim()).replaceAll(" ");
	}

	private static String valueText(ParameterView input, String token, Resources resources) {
		ParameterType type = input == null || input.getParameter() == null ? null : input.getParameter().getType();
		if (type instanceof EnumerationType enumeration) {
			for (EnumerationValue value : enumeration.getValues()) {
				if (Integer.toString(value.getValue()).equals(token)
					&& value.getNameKey() instanceof ResKey name) {
					String text = localize(resources, name);
					if (text != null) {
						return text;
					}
				}
			}
		}
		return token;
	}

	/**
	 * The channels compressed by their numbers: "Taste 1–8, Wippe 1–4", in the order a name first
	 * occurs.
	 */
	static String channels(Collection<String> labels) {
		Map<String, TreeSet<Integer>> numbered = new LinkedHashMap<>();
		for (String label : labels) {
			Matcher matcher = NUMBERED.matcher(label);
			if (matcher.matches() && matcher.group(2).length() < 9) {
				numbered.computeIfAbsent(matcher.group(1), p -> new TreeSet<>())
					.add(Integer.valueOf(matcher.group(2)));
			} else {
				numbered.computeIfAbsent(label, p -> new TreeSet<>());
			}
		}
		List<String> result = new ArrayList<>();
		for (Map.Entry<String, TreeSet<Integer>> entry : numbered.entrySet()) {
			String prefix = entry.getKey();
			if (entry.getValue().isEmpty()) {
				result.add(prefix);
				continue;
			}
			Integer start = null;
			Integer previous = null;
			for (Integer number : entry.getValue()) {
				if (previous != null && number.intValue() == previous.intValue() + 1) {
					previous = number;
					continue;
				}
				if (start != null) {
					result.add(range(prefix, start, previous));
				}
				start = number;
				previous = number;
			}
			result.add(range(prefix, start, previous));
		}
		return String.join(", ", result);
	}

	private static String range(String prefix, Integer start, Integer end) {
		return start.equals(end) ? prefix + start : prefix + start + "–" + end;
	}

	/**
	 * The text of the key, its white space (line breaks of the manufacturer's texts) as single
	 * spaces; <code>null</code> for none.
	 */
	private static String localize(Resources resources, ResKey key) {
		if (key == null) {
			return null;
		}
		String result = resources.getString(key, null);
		return result == null || result.isBlank() ? null : WHITE_SPACE.matcher(result.trim()).replaceAll(" ");
	}

	/** The places of an object in one channel: the channel's name and the conditions within it. */
	private static final class Places {

		final String _channel;

		final List<Condition> _conditions;

		Places(String channel, List<Condition> conditions) {
			_channel = channel;
			_conditions = conditions;
		}
	}

	/** A kind while it is collected. */
	private static final class Kind {

		String _object;

		int _size;

		String _setting;

		int _first;

		final Set<String> _channels = new LinkedHashSet<>();

		final List<Interface> _placements = new ArrayList<>();
	}

}
