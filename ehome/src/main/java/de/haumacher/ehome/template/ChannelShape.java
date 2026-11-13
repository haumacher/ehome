/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ModulePlacement;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.SharedPlacement;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.runtime.FunctionProposal;

/**
 * The shape of a channel of an application program (haumacher/ehome #146): its parameters and
 * communication objects in the order of the program's structure, each with the conditions under
 * which it is in effect. Two channels are alike when their shapes map onto each other exactly
 * ({@link #map(ChannelShape)}), and a preset of one then applies to the other by position.
 *
 * <p>
 * <b>The channel</b> is the one of the function proposal ({@link FunctionProposal#channelKey(Interface)}):
 * a numbered channel of the program where it has several, the channel of a module instance, otherwise
 * the communication objects sharing a name ("Channel A", "Taste 1"). Its <b>part of the structure</b>
 * is every largest part (a channel, a block, a switch, a case, a single placement) all of whose
 * communication objects, in every case of the program, belong to the channel: the parameters there
 * belong to the channel, also those of a case none of whose objects is in effect. The AKK-04 of the
 * test system places each of its channels A to D in one switch on the channel's mode; the keys of the
 * Tastsensor are blocks within the switches on the operation concept of two keys.
 * </p>
 *
 * <p>
 * <b>The items</b> are the placements of parameters (and the parameter a block shows as its title)
 * and of communication objects there, in the order of the structure, every case of a switch with its
 * contents. Each has its <b>conditions</b>: the input of every switch above it, from the top of the
 * program, with the pattern of the case taken (a switch with a single default case, a group, sets no
 * condition). An input placed in the channel's part is named by its position there; one outside (the
 * channel's mode in the block "Channel selection", the operation concept of the keys 1 and 2) is
 * external.
 * </p>
 *
 * <p>
 * <b>The mapping</b> of one shape onto another requires the same number of items and, position by
 * position, a parameter for a parameter with the same type (the same type, or one with the same
 * kind, size, range and values), a communication object for a communication object with the same
 * size and flags, and the same conditions: the same patterns, on inputs at the same position or on
 * external inputs that map onto each other one to one, of the same type. The external inputs are
 * mapped with the items. Texts are not compared: they carry the channel's letter or number.
 * Assignments of the program (<code>Assign</code>), renames and buttons are not compared: a preset
 * sets no assigned value.
 * </p>
 *
 * <p>
 * A channel of a module instance has the shape of the channel of its module definition: it maps onto
 * the same channel of every other instance of the definition by the module, without a declaration;
 * its conditions start at the module.
 * </p>
 */
public final class ChannelShape {

	/**
	 * A condition of an item: the input of a switch and the case it takes.
	 *
	 * @param input
	 *        The input of the switch, <code>null</code> for a switch without one.
	 * @param pattern
	 *        The pattern of the case, <code>null</code> for a default case without one.
	 * @param isDefault
	 *        Whether the case is taken when no other one matches.
	 */
	public record Condition(ParameterView input, String pattern, boolean isDefault) {
		// Value.
	}

	/**
	 * A parameter or communication object of the channel with the conditions it is in effect under,
	 * outermost first.
	 *
	 * @param part
	 *        A {@link ParameterView} or an {@link Interface}; of a module instance its instance part.
	 */
	public record Item(Option part, List<Condition> conditions) {
		// Value.
	}

	/** The channels of a program as computed for a revision of the knowledge base. */
	private record Cached(long revision, Map<String, ChannelShape> shapes) {
		// Value.
	}

	private static final Map<Program, Cached> CACHE = new WeakHashMap<>();

	private final String _key;

	private final List<Item> _items;

	private final InstanceInfo _instance;

	private final Channel _moduleChannel;

	private ChannelShape(String key, List<Item> items, InstanceInfo instance, Channel moduleChannel) {
		_key = key;
		_items = items;
		_instance = instance;
		_moduleChannel = moduleChannel;
	}

	/**
	 * The key of the channel ({@link FunctionProposal#channelKey(Interface)}).
	 */
	public String getKey() {
		return _key;
	}

	/**
	 * The parameters and objects of the channel with their conditions, in the order of the structure.
	 */
	public List<Item> getItems() {
		return _items;
	}

	/**
	 * The module instance of a channel of a module, <code>null</code> for a channel of the program.
	 */
	public InstanceInfo getInstance() {
		return _instance;
	}

	/**
	 * The channel of the module definition of a channel of a module instance, <code>null</code> for a
	 * channel of the program.
	 */
	public Channel getModuleChannel() {
		return _moduleChannel;
	}

	/**
	 * The communication objects of the channel, each once, in the order of the structure.
	 */
	public List<Interface> getObjects() {
		Set<Interface> result = new LinkedHashSet<>();
		for (Item item : _items) {
			if (item.part() instanceof Interface placed) {
				result.add(placed);
			}
		}
		return new ArrayList<>(result);
	}

	/**
	 * The parameter placements of the channel, each once, in the order of the structure.
	 */
	public List<ParameterView> getParameters() {
		Set<ParameterView> result = new LinkedHashSet<>();
		for (Item item : _items) {
			if (item.part() instanceof ParameterView view) {
				result.add(view);
			}
		}
		return new ArrayList<>(result);
	}

	/**
	 * The external inputs of the conditions of the channel: the switches outside the channel's part
	 * of the structure that decide whether parts of it are in effect, outermost first.
	 */
	public List<ParameterView> getExternalInputs() {
		Set<Option> own = new LinkedHashSet<>();
		for (Item item : _items) {
			own.add(item.part());
		}
		Set<ParameterView> result = new LinkedHashSet<>();
		for (Item item : _items) {
			for (Condition condition : item.conditions()) {
				if (condition.input() != null && !own.contains(condition.input())) {
					result.add(condition.input());
				}
			}
		}
		return new ArrayList<>(result);
	}

	/**
	 * Whether the given part (a parameter placement or a communication object, of a module instance
	 * its instance part) is an item of the channel.
	 */
	public boolean contains(Option part) {
		for (Item item : _items) {
			if (item.part().equals(part)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The name of the channel in the given device (<code>null</code>: as the program names it) in the
	 * user's language.
	 */
	public String label(TLObject device) {
		for (Item item : _items) {
			if (item.part() instanceof Interface placed) {
				return FunctionProposal.channelLabel(device, placed, Resources.getInstance());
			}
		}
		return _key;
	}

	/**
	 * The channels of the program, outside its module instances, by their keys, in the order of the
	 * smallest number of their objects.
	 */
	public static Map<String, ChannelShape> of(Program program) {
		if (program == null) {
			return Map.of();
		}
		long revision = PersistencyLayer.getKnowledgeBase().getHistoryManager().getLastRevision();
		synchronized (CACHE) {
			Cached cached = CACHE.get(program);
			if (cached != null && cached.revision() == revision) {
				return cached.shapes();
			}
		}
		Map<String, ChannelShape> result = Collections.unmodifiableMap(compute(program));
		synchronized (CACHE) {
			CACHE.put(program, new Cached(revision, result));
		}
		return result;
	}

	/**
	 * The channels of the given device: those of its program ({@link #of(Program)}), then those of the
	 * module instances in effect with its values, by their keys.
	 */
	public static Map<String, ChannelShape> of(TLObject device) {
		Program program = DeviceConfiguration.program(device);
		Map<String, ChannelShape> result = new LinkedHashMap<>(of(program));
		if (program == null || program.getModules().isEmpty()) {
			return result;
		}
		for (InstanceInfo instance : new DeviceConfiguration(device).getInstances()) {
			for (Channel channel : moduleChannels(instance.getDefinition())) {
				Interface first = firstObject(channel);
				if (first == null) {
					continue;
				}
				String key = FunctionProposal.channelKey((Interface) ModuleParts.wrap(first, instance));
				List<Item> items = new ArrayList<>();
				flatten(channel, instance, new ArrayList<>(), items);
				result.putIfAbsent(key, new ChannelShape(key, items, instance, channel));
			}
		}
		return result;
	}

	/**
	 * The channel of a module definition the given key names in some instance (see
	 * {@link FunctionProposal#channelKey(Interface)}), <code>null</code> for a key of no channel of a
	 * module of the program.
	 */
	public static Channel moduleChannel(Program program, String key) {
		if (program == null || key == null || !key.startsWith("channel:")) {
			return null;
		}
		String id = key.substring("channel:".length());
		String prefix = program.getExtId() + "_";
		for (ModuleDefinition definition : allModules(program)) {
			String definitionId = definition.getExtId();
			if (definitionId == null || !definitionId.startsWith(prefix)) {
				continue;
			}
			String local = definitionId.substring(prefix.length());
			for (Channel channel : moduleChannels(definition)) {
				String channelId = channel.getExtId();
				if (channelId != null && channelId.startsWith(definitionId) && id.startsWith(prefix + local + "_")
					&& id.endsWith(channelId.substring(definitionId.length()))) {
					return channel;
				}
			}
		}
		return null;
	}

	/**
	 * The communication objects of the given part of the structure (a channel of a module definition),
	 * each once, in the order of the structure.
	 */
	public static List<Interface> objectsOf(TLObject node) {
		List<Item> items = new ArrayList<>();
		flatten(node, null, new ArrayList<>(), items);
		return new ChannelShape(null, items, null, null).getObjects();
	}

	private static List<ModuleDefinition> allModules(Program program) {
		List<ModuleDefinition> result = new ArrayList<>();
		collectModules(program.getModules(), result);
		return result;
	}

	private static void collectModules(Collection<? extends ModuleDefinition> definitions,
			List<ModuleDefinition> result) {
		for (ModuleDefinition definition : definitions) {
			result.add(definition);
			collectModules(definition.getModules(), result);
		}
	}

	private static List<Channel> moduleChannels(ModuleDefinition definition) {
		List<Channel> result = new ArrayList<>();
		collectChannels(definition, result);
		return result;
	}

	private static void collectChannels(TLObject node, List<Channel> result) {
		if (node instanceof Channel channel) {
			result.add(channel);
			return;
		}
		if (node instanceof ModulePlacement) {
			return;
		}
		for (TLObject child : children(node)) {
			collectChannels(child, result);
		}
	}

	private static Interface firstObject(TLObject node) {
		if (node instanceof Interface placed) {
			return placed;
		}
		if (node instanceof ModulePlacement) {
			return null;
		}
		for (TLObject child : children(node)) {
			Interface found = firstObject(child);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static Map<String, ChannelShape> compute(Program program) {
		Map<TLObject, Set<String>> keys = new IdentityHashMap<>();
		collectKeys(program, keys);
		Map<String, List<Item>> items = new LinkedHashMap<>();
		for (TLObject child : children(program)) {
			findRoots(child, keys, new ArrayList<>(), items);
		}
		List<ChannelShape> shapes = new ArrayList<>();
		for (Map.Entry<String, List<Item>> entry : items.entrySet()) {
			shapes.add(new ChannelShape(entry.getKey(), List.copyOf(entry.getValue()), null, null));
		}
		shapes.sort(Comparator.comparingInt(ChannelShape::firstNumber).thenComparing(ChannelShape::getKey));
		Map<String, ChannelShape> result = new LinkedHashMap<>();
		for (ChannelShape shape : shapes) {
			result.put(shape.getKey(), shape);
		}
		return result;
	}

	private int firstNumber() {
		int result = Integer.MAX_VALUE;
		for (Interface placed : getObjects()) {
			result = Math.min(result, placed.getPort().getIndex());
		}
		return result;
	}

	/**
	 * The keys of the channels of the communication objects in the part of the structure, in every
	 * case.
	 */
	private static Set<String> collectKeys(TLObject node, Map<TLObject, Set<String>> keys) {
		Set<String> result = keys.get(node);
		if (result != null) {
			return result;
		}
		if (node instanceof Interface placed) {
			result = Set.of(FunctionProposal.channelKey(placed));
		} else if (node instanceof SharedPlacement shared) {
			result = shared.getPlaced() instanceof Interface placed ? Set.of(FunctionProposal.channelKey(placed))
				: Set.of();
		} else if (node instanceof ModulePlacement) {
			result = Set.of();
		} else {
			result = new LinkedHashSet<>();
			for (TLObject child : children(node)) {
				result.addAll(collectKeys(child, keys));
			}
		}
		keys.put(node, result);
		return result;
	}

	private static void findRoots(TLObject node, Map<TLObject, Set<String>> keys, List<Condition> conditions,
			Map<String, List<Item>> result) {
		Set<String> found = keys.get(node);
		if (found == null || found.isEmpty()) {
			return;
		}
		if (found.size() == 1) {
			flatten(node, null, new ArrayList<>(conditions), result.computeIfAbsent(found.iterator().next(),
				k -> new ArrayList<>()));
			return;
		}
		if (node instanceof Switch choice) {
			for (Case alternative : choice.getAlternatives()) {
				Condition condition = condition(choice, alternative, null);
				if (condition != null) {
					conditions.add(condition);
				}
				findRoots(alternative, keys, conditions, result);
				if (condition != null) {
					conditions.remove(conditions.size() - 1);
				}
			}
			return;
		}
		for (TLObject child : children(node)) {
			findRoots(child, keys, conditions, result);
		}
	}

	/**
	 * Adds the items of the given part of the structure (of the module definition for a module
	 * instance) to the given list.
	 *
	 * @param instance
	 *        The module instance whose parts the items are, <code>null</code> for parts of the program.
	 */
	private static void flatten(TLObject node, InstanceInfo instance, List<Condition> conditions, List<Item> result) {
		if (node instanceof SharedPlacement shared) {
			if (shared.getPlaced() != null) {
				flatten(shared.getPlaced(), instance, conditions, result);
			}
		} else if (node instanceof ParameterView view) {
			result.add(new Item((Option) ModuleParts.wrap(view, instance), List.copyOf(conditions)));
		} else if (node instanceof Interface placed) {
			result.add(new Item((Option) ModuleParts.wrap(placed, instance), List.copyOf(conditions)));
		} else if (node instanceof Switch choice) {
			for (Case alternative : choice.getAlternatives()) {
				Condition condition = condition(choice, alternative, instance);
				if (condition != null) {
					conditions.add(condition);
				}
				flatten(alternative, instance, conditions, result);
				if (condition != null) {
					conditions.remove(conditions.size() - 1);
				}
			}
		} else if (node instanceof ModulePlacement) {
			// The instances of a module are channels of the device.
		} else if (node instanceof ConfigFragment fragment) {
			if (fragment instanceof ParameterGroup group && group.getInput() != null) {
				// The parameter a block shows as its title.
				result.add(new Item((Option) ModuleParts.wrap(group.getInput(), instance), List.copyOf(conditions)));
			}
			for (TLObject child : children(fragment)) {
				flatten(child, instance, conditions, result);
			}
		}
	}

	/**
	 * The condition the given case of the switch sets, <code>null</code> for the only case of a
	 * switch without patterns (a group, always in effect).
	 */
	private static Condition condition(Switch choice, Case alternative, InstanceInfo instance) {
		if (choice.getAlternatives().size() == 1 && alternative.getIsDefault()) {
			return null;
		}
		ParameterView input = choice.getInput() == null ? null : (ParameterView) ModuleParts.wrap(choice.getInput(),
			instance);
		String pattern = alternative.getPattern();
		return new Condition(input, pattern == null ? null : pattern.trim(), alternative.getIsDefault());
	}

	private static List<TLObject> children(TLObject node) {
		if (node instanceof Switch choice) {
			return new ArrayList<>(choice.getAlternatives());
		}
		if (node instanceof ConfigFragment fragment) {
			return new ArrayList<>(fragment.getContents());
		}
		return List.of();
	}

	/**
	 * The mapping of the parts of this channel onto those of the given one by position, see the class
	 * comment.
	 *
	 * @param device
	 *        The device the channels are named for in a refusal, <code>null</code> for the program's
	 *        names.
	 * @return The mapping; {@link Mapping#getProblem()} says why there is none.
	 */
	public Mapping map(ChannelShape other, TLObject device) {
		Mapping result = new Mapping(this, other);
		String from = label(device);
		String to = other.label(device);
		if (_items.size() != other._items.size()) {
			return result.refuse(I18NConstants.ERROR_ALIKE_SIZE__CHANNEL_COUNT_OTHER_OTHER_COUNT.fill(from,
				_items.size(), to, other._items.size()));
		}
		Map<Option, Integer> ownPositions = positions(_items);
		Map<Option, Integer> otherPositions = positions(other._items);
		Map<ParameterView, ParameterView> external = new LinkedHashMap<>();
		Map<ParameterView, ParameterView> externalBack = new HashMap<>();
		for (int n = 0; n < _items.size(); n++) {
			Item item = _items.get(n);
			Item counterpart = other._items.get(n);
			Option part = item.part();
			Option otherPart = counterpart.part();
			if (part instanceof ParameterView view) {
				if (!(otherPart instanceof ParameterView otherView)) {
					return result.refuse(I18NConstants.ERROR_ALIKE_PARAMETER_OBJECT__CHANNEL_PARAMETER_OTHER_OBJECT
						.fill(from, parameterLabel(view), to, objectLabel(device, (Interface) otherPart)));
				}
				if (!sameType(type(view), type(otherView))) {
					return result.refuse(I18NConstants.ERROR_ALIKE_TYPE__CHANNEL_PARAMETER_OTHER_PARAMETER.fill(from,
						parameterLabel(view), to, parameterLabel(otherView)));
				}
			} else {
				Interface placed = (Interface) part;
				if (!(otherPart instanceof Interface otherPlaced)) {
					return result.refuse(I18NConstants.ERROR_ALIKE_OBJECT_PARAMETER__CHANNEL_OBJECT_OTHER_PARAMETER
						.fill(from, objectLabel(device, placed), to, parameterLabel((ParameterView) otherPart)));
				}
				if (!sameObject(placed, otherPlaced)) {
					return result.refuse(I18NConstants.ERROR_ALIKE_OBJECT__CHANNEL_OBJECT_OTHER_OBJECT.fill(from,
						objectLabel(device, placed), to, objectLabel(device, otherPlaced)));
				}
			}
			if (!sameConditions(item, counterpart, ownPositions, otherPositions, external, externalBack)) {
				return result.refuse(I18NConstants.ERROR_ALIKE_CONDITIONS__CHANNEL_PART_OTHER_PART.fill(from,
					partLabel(device, part), to, partLabel(device, otherPart)));
			}
			Option known = result._parts.putIfAbsent(part, otherPart);
			if (known != null && !known.equals(otherPart)) {
				return result.refuse(I18NConstants.ERROR_ALIKE_PLACED_TWICE__CHANNEL_PART_OTHER.fill(from,
					partLabel(device, part), to));
			}
		}
		for (Map.Entry<ParameterView, ParameterView> entry : external.entrySet()) {
			result._parts.putIfAbsent(entry.getKey(), entry.getValue());
		}
		return result;
	}

	private static Map<Option, Integer> positions(List<Item> items) {
		Map<Option, Integer> result = new HashMap<>();
		for (int n = 0; n < items.size(); n++) {
			result.putIfAbsent(items.get(n).part(), Integer.valueOf(n));
		}
		return result;
	}

	private static boolean sameConditions(Item item, Item counterpart, Map<Option, Integer> ownPositions,
			Map<Option, Integer> otherPositions, Map<ParameterView, ParameterView> external,
			Map<ParameterView, ParameterView> externalBack) {
		List<Condition> own = item.conditions();
		List<Condition> other = counterpart.conditions();
		if (own.size() != other.size()) {
			return false;
		}
		for (int n = 0; n < own.size(); n++) {
			Condition a = own.get(n);
			Condition b = other.get(n);
			if (a.isDefault() != b.isDefault() || !Objects.equals(a.pattern(), b.pattern())) {
				return false;
			}
			if (a.input() == null || b.input() == null) {
				if (a.input() != b.input()) {
					return false;
				}
				continue;
			}
			Integer ownPosition = ownPositions.get(a.input());
			Integer otherPosition = otherPositions.get(b.input());
			if (ownPosition != null || otherPosition != null) {
				if (!Objects.equals(ownPosition, otherPosition)) {
					return false;
				}
				continue;
			}
			// Both external: they map onto each other one to one.
			ParameterView mapped = external.get(a.input());
			ParameterView back = externalBack.get(b.input());
			if (mapped == null && back == null) {
				if (!sameType(type(a.input()), type(b.input()))) {
					return false;
				}
				external.put(a.input(), b.input());
				externalBack.put(b.input(), a.input());
			} else if (!b.input().equals(mapped) || !a.input().equals(back)) {
				return false;
			}
		}
		return true;
	}

	private static ParameterType type(ParameterView view) {
		return view.getParameter() == null ? null : view.getParameter().getType();
	}

	/**
	 * Whether two parameter types are the same: the same type, or one of the same kind with the same
	 * size, range and values (a program may give each channel a type of its own).
	 */
	static boolean sameType(ParameterType a, ParameterType b) {
		if (a == null || b == null) {
			return a == b;
		}
		if (a.equals(b)) {
			return true;
		}
		if (!a.tType().equals(b.tType())) {
			return false;
		}
		if (a instanceof EnumerationType enumA && b instanceof EnumerationType enumB) {
			if (!Objects.equals(enumA.getBits(), enumB.getBits())) {
				return false;
			}
			List<Long> valuesA = new ArrayList<>();
			for (EnumerationValue value : enumA.getValues()) {
				valuesA.add(Long.valueOf(value.getUnsignedValue()));
			}
			List<Long> valuesB = new ArrayList<>();
			for (EnumerationValue value : enumB.getValues()) {
				valuesB.add(Long.valueOf(value.getUnsignedValue()));
			}
			return valuesA.equals(valuesB);
		}
		if (a instanceof NumericType numA && b instanceof NumericType numB) {
			return numA.getBits() == numB.getBits() && numA.getMin() == numB.getMin() && numA.getMax() == numB.getMax()
				&& Objects.equals(numA.getKind(), numB.getKind());
		}
		if (a instanceof FloatingPointType floatA && b instanceof FloatingPointType floatB) {
			return floatA.getMin() == floatB.getMin() && floatA.getMax() == floatB.getMax()
				&& Objects.equals(floatA.getEncoding(), floatB.getEncoding());
		}
		if (a instanceof TimeType timeA && b instanceof TimeType timeB) {
			return timeA.getBits() == timeB.getBits() && timeA.getMin() == timeB.getMin()
				&& timeA.getMax() == timeB.getMax() && Objects.equals(timeA.getUnit(), timeB.getUnit());
		}
		if (a instanceof TextType textA && b instanceof TextType textB) {
			return textA.getBits() == textB.getBits();
		}
		return false;
	}

	private static final List<String> FLAGS =
		List.of("communication", "canRead", "canWrite", "transmission", "update", "readOnInit");

	private static boolean sameObject(Interface a, Interface b) {
		if (ObjectTypes.size(a) != ObjectTypes.size(b)) {
			return false;
		}
		for (String flag : FLAGS) {
			if (!Objects.equals(flag(a, flag), flag(b, flag))) {
				return false;
			}
		}
		return true;
	}

	private static String flag(Interface placed, String flag) {
		for (TLObject holder : new TLObject[] { placed, placed.getPort() }) {
			if (holder != null && holder.tValueByName(flag) instanceof TLClassifier classifier) {
				return classifier.getName();
			}
		}
		return null;
	}

	/**
	 * The text naming a parameter placement in a refusal.
	 */
	public static String parameterLabel(ParameterView view) {
		return MetaLabelProvider.INSTANCE.getLabel(view);
	}

	/**
	 * The text naming a communication object in a refusal and in the lists of the presets: its number,
	 * its text and its function text as the app lists the objects of a device ("7 Kanal A: Status",
	 * {@link GroupAddressConnections#objectLabel(TLObject, Interface, Resources)}).
	 *
	 * @param device
	 *        The device, <code>null</code> for the program's texts.
	 */
	public static String objectLabel(TLObject device, Interface placed) {
		return GroupAddressConnections.objectLabel(device, placed, Resources.getInstance());
	}

	private static String partLabel(TLObject device, Option part) {
		return part instanceof Interface placed ? objectLabel(device, placed) : parameterLabel((ParameterView) part);
	}

	/**
	 * The mapping of the parts of a channel onto those of another one, see
	 * {@link ChannelShape#map(ChannelShape, TLObject)}.
	 */
	public static final class Mapping {

		private final ChannelShape _from;

		private final ChannelShape _to;

		private final Map<Option, Option> _parts = new LinkedHashMap<>();

		private ResKey _problem;

		Mapping(ChannelShape from, ChannelShape to) {
			_from = from;
			_to = to;
		}

		Mapping refuse(ResKey problem) {
			_problem = problem;
			_parts.clear();
			return this;
		}

		/**
		 * Why the channels do not map onto each other, <code>null</code> if they do.
		 */
		public ResKey getProblem() {
			return _problem;
		}

		/**
		 * The channel mapped.
		 */
		public ChannelShape getFrom() {
			return _from;
		}

		/**
		 * The channel mapped onto.
		 */
		public ChannelShape getTo() {
			return _to;
		}

		/**
		 * The counterpart of the given part of the mapped channel (an item or an external input of its
		 * conditions), <code>null</code> if it has none.
		 */
		public Option counterpart(Option part) {
			return _parts.get(part);
		}

		/**
		 * The parts mapped, with their counterparts.
		 */
		public Map<Option, Option> getParts() {
			return Collections.unmodifiableMap(_parts);
		}

		/**
		 * The mapping the other way round.
		 */
		public Mapping inverse() {
			Mapping result = new Mapping(_to, _from);
			result._problem = _problem;
			for (Map.Entry<Option, Option> entry : _parts.entrySet()) {
				result._parts.putIfAbsent(entry.getValue(), entry.getKey());
			}
			return result;
		}

		/**
		 * This mapping followed by the given one, which maps the channel this one maps onto.
		 */
		public Mapping then(Mapping next) {
			Mapping result = new Mapping(_from, next._to);
			result._problem = _problem != null ? _problem : next._problem;
			if (result._problem != null) {
				return result;
			}
			for (Map.Entry<Option, Option> entry : _parts.entrySet()) {
				Option target = next._parts.get(entry.getValue());
				if (target != null) {
					result._parts.put(entry.getKey(), target);
				}
			}
			return result;
		}
	}

}
