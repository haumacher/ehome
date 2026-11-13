/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.intent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.template.ChannelShape;
import de.haumacher.ehome.template.ObjectTypes;
import de.haumacher.ehome.template.Presets;

/**
 * The derivation of a KNX installation from its intent (haumacher/ehome #147, the second part of
 * #131): from the functions of a project that name an actuator preset (<code>Function#preset</code>)
 * and from their controls (<code>ehome.runtime:Control</code>), the group addresses, the connections
 * and the parameter values the installation needs. It is computed as a plan ({@link #plan(TLObject)}),
 * shown as a preview (what it would create, change and remove, and the controls it refuses), and
 * applied in one transaction ({@link #apply(TLObject, List)}); it does not run on every edit.
 *
 * <p>
 * <b>A function</b> is derived when it names a device, a channel and an actuator preset offered
 * there ({@link Presets#offered(TLObject, String)}), of the kind light or switch (the first step of
 * #131; other kinds are refused with that reason). For each role of its kind the preset names an
 * object of (<code>command</code>, <code>state</code>), it gets a group address named after the
 * function and the role ("Licht Schalten", "Licht Status", in the language of the user deriving it; a
 * later derivation renames it when the function was renamed), with the
 * identifier the identifier proposal gives an address a function uses ({@link GroupAddressIds}) and
 * the datapoint type of the actuator's object. The address is allocated once, in the project's
 * notation: the main group by the kind ({@link #mainGroup(String)}: light 1, switch 2, the further
 * kinds of #131 reserved: blind 3, heating 4, scene 5, sensor 6), the middle group by the floor of
 * the function's room ({@link #floorIndex(TLObject)}: the position of the floor among the floors of
 * its building, 0 for a function without a floor, floors beyond the eighth share the middle group
 * 7), the next free sub address (0..255) of a group range taking addresses there. Where the project
 * has no range there, the derivation creates the main range (named after the kind) and, in the
 * three-level notation, the middle range (named after the floor); the two-level and the free notation
 * get the main range, as ETS divides them. "No free address" only when no sub address of the middle
 * group is free in a range. The actuator's command object receives from the command address, its
 * state object sends on the state address, the function's roles are bound to the addresses and its
 * kind is the preset's.
 * </p>
 *
 * <p>
 * <b>A control</b> names a source (a device and its channel) with its source preset and an
 * operation. The operation needs a role of the function: toggle, on, off and on/off the command, show
 * status the state, dim a dimmer's role (refused on a switching function). The preset must be a
 * source preset offered on the channel that can do the operation, and name an object sending the
 * command (show status: an object receiving the status, or none, where the LED follows the key's
 * object by its parameters). The source's sending object sends on the command address; a toggling
 * source listens to the state address (with its listening object, else with its sending one); a
 * status display receives the state address. The preset's values are set on the source device and its
 * use recorded.
 * </p>
 *
 * <p>
 * <b>Refusals</b> speak, per function and control, in the preview and at the control
 * ({@link #problem(TLObject)}): the function has no such role; the source preset cannot do the
 * operation; two presets on one device want different values for the same parameter, or two controls
 * use the same channel (the later one is refused, naming the earlier); no free address. A refused
 * function refuses its controls with its reason. A refused control or function derives nothing;
 * what it derived before is removed as for a deleted one. The functions are decided first, in the
 * order of the project, then their controls.
 * </p>
 *
 * <p>
 * <b>Marks.</b> Every derived address, connection, parameter value and preset use is marked
 * (<code>ehome.runtime:DerivedPart</code> in <code>Project#derivedParts</code>) with the function or
 * control it comes from and what was derived. A further derivation changes only marked objects. One
 * that differs from its mark was changed by hand or by ETS (decision 1 of #131: ETS's values win): it
 * is reported in the preview and not overwritten - a renamed address keeps its name, a connection
 * removed by hand is not made again, a value set by hand stays. What a removed (or refused) function
 * or control derived is removed: its connections, its values (back to the default, where no other
 * derived part wants the parameter), its preset uses, and its addresses where nothing else uses them
 * (no connection, no role of another function); an object changed by hand is left as it is. An object
 * the derivation would create that exists already (connected by hand) is marked without a change, as
 * is the address a role of the function is bound to without a mark (a function imported from ETS, a
 * project file imported without the marks): the derivation takes it as it is. So a second derivation
 * without a change of the intent changes nothing.
 * </p>
 *
 * <p>
 * The plan is computed without changing anything; {@link #apply(TLObject, List)} computes it again
 * and refuses where it differs from the preview the user confirmed. Java is not access checked: the
 * commands check <code>Read</code> (preview) and <code>Write</code> (apply) on the project.
 * </p>
 */
public final class Derivation {

	/** What the derivation does with an object. */
	public enum Action {
		/** A new object. */
		CREATE(I18NConstants.ACTION_CREATE),
		/** A change of a derived object. */
		CHANGE(I18NConstants.ACTION_CHANGE),
		/** The removal of an object no longer derived. */
		REMOVE(I18NConstants.ACTION_REMOVE),
		/** A derived object changed by hand: reported, not overwritten. */
		KEEP(I18NConstants.ACTION_KEEP);

		private final ResKey _label;

		Action(ResKey label) {
			_label = label;
		}

		/** The label of the action. */
		public ResKey label() {
			return _label;
		}
	}

	/** What kind of object an item is about. */
	public enum What {
		/** A group range. */
		RANGE(I18NConstants.WHAT_RANGE),
		/** A group address. */
		ADDRESS(I18NConstants.WHAT_ADDRESS),
		/** A connection of a communication object to a group address. */
		CONNECTION(I18NConstants.WHAT_CONNECTION),
		/** A parameter value of a device. */
		VALUE(I18NConstants.WHAT_VALUE),
		/** The use of a preset on a channel of a device. */
		PRESET(I18NConstants.WHAT_PRESET),
		/** The kind or a role of a function. */
		FUNCTION(I18NConstants.WHAT_FUNCTION);

		private final ResKey _label;

		What(ResKey label) {
			_label = label;
		}

		/** The label of the kind. */
		public ResKey label() {
			return _label;
		}
	}

	/**
	 * An entry of the preview.
	 *
	 * @param action
	 *        What is done.
	 * @param what
	 *        What kind of object.
	 * @param object
	 *        The object (an address, a communication object of a device, a parameter of a device).
	 * @param detail
	 *        What changes (the address connected to, the old and the new value), or why it is kept.
	 * @param origin
	 *        The function or control the object comes from, <code>null</code> for a deleted one.
	 * @param originLabel
	 *        The name of the origin.
	 */
	public record Item(Action action, What what, String object, String detail, TLObject origin, String originLabel) {

		/** The item as one line, for the comparison with the preview and for logs. */
		public String line() {
			return action + "|" + what + "|" + object + "|" + detail + "|" + originLabel;
		}
	}

	/**
	 * A function or control the derivation refuses, with the reason.
	 */
	public record Refusal(TLObject origin, String label, ResKey reason) {

		/** The refusal as one line, for the comparison with the preview. */
		public String line() {
			return "REFUSED|" + label + "|" + Resources.getInstance().getString(reason);
		}
	}

	/** The main group of the addresses of each kind of function. */
	private static final Map<String, Integer> MAIN_GROUPS = Map.of(
		"light", 1,
		"switch", 2,
		"blind", 3,
		"climate", 4,
		"scene", 5,
		"sensor", 6);

	/** The kinds of functions derived in this step of haumacher/ehome #131. */
	private static final Set<String> DERIVED_KINDS = Set.of("light", "switch");

	/** The middle groups of a main group: floors beyond share the last. */
	private static final int MIDDLE_GROUPS = 8;

	/** The sub addresses of a middle group. */
	private static final int SUB_ADDRESSES = 256;

	private static final String MARK = "ehome.runtime:DerivedPart";

	private static final String SEND = "send";

	private static final String RECEIVE = "receive";

	private static final String COMMAND = "command";

	private static final String STATE = "state";

	private static final String SEND_COMMAND = "sendCommand";

	private static final String LISTEN_STATUS = "listenStatus";

	private static final String RECEIVE_STATUS = "receiveStatus";

	/** The phases of applying a plan, in their order. */
	private enum Phase {
		RANGES, ADDRESSES, DISCONNECT, VALUES, USES, CONNECT, FUNCTIONS, REMOVE_ADDRESSES, MARKS
	}

	private static final int CACHE_SIZE = 16;

	private static final Map<TLObject, Derivation> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<TLObject, Derivation> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	private final TLObject _project;

	private final long _revision;

	private final Resources _resources = Resources.getInstance();

	private final List<Item> _items = new ArrayList<>();

	private final Map<TLObject, Refusal> _refusals = new LinkedHashMap<>();

	private final Map<Phase, List<Runnable>> _operations = new EnumMap<>(Phase.class);

	private final List<TLObject> _marks;

	private final Set<TLObject> _usedMarks = new HashSet<>();

	private final List<Participant> _participants = new ArrayList<>();

	private final Map<TLObject, Participant> _functions = new LinkedHashMap<>();

	private final Set<TLObject> _removedConnections = new HashSet<>();

	private Allocator _allocator;

	private Set<String> _identifiers;

	/**
	 * A function or control the derivation derives: its source (actuator) channel with its preset.
	 */
	private final class Participant {
		final TLObject _origin;

		final boolean _control;

		final TLObject _function;

		final TLObject _device;

		final String _channel;

		final TLObject _preset;

		final String _label;

		Map<ParameterView, Object> _values;

		Map<String, Interface> _objects;

		/** Of a function: the address of each role. */
		final Map<String, Slot> _slots = new LinkedHashMap<>();

		Participant(TLObject origin, boolean control, TLObject function, TLObject device, String channel,
				TLObject preset, String label) {
			_origin = origin;
			_control = control;
			_function = function;
			_device = device;
			_channel = channel;
			_preset = preset;
			_label = label;
		}
	}

	/** The address of a role of a function: existing (marked) or planned. */
	private static final class Slot {
		final String _role;

		/** The address; for a planned one set when it is created. */
		TLObject _address;

		/** The mark, <code>null</code> for a planned address. */
		TLObject _mark;

		/** A marked address removed by hand: nothing is connected to it. */
		boolean _gone;

		/** An address the role was bound to without a mark: taken as it is. */
		boolean _adopted;

		/** Of an adopted address: its datapoint type. */
		TLObject _boundType;

		String _name;

		String _identifier;

		TLObject _type;

		int _number;

		Slot(String role) {
			_role = role;
		}

		/** The address as the preview names it. */
		String label() {
			return DatapointNames.format(_name, _identifier);
		}
	}

	private Derivation(TLObject project) {
		_project = project;
		_revision = HistoryUtils.getLastRevision().getCommitNumber();
		_marks = ExtIds.list(project, "derivedParts");
		for (Phase phase : Phase.values()) {
			_operations.put(phase, new ArrayList<>());
		}
		compute();
	}

	/**
	 * The plan of the derivation of the project as it is now, computed anew.
	 */
	public static Derivation plan(TLObject project) {
		return new Derivation(project);
	}

	/**
	 * The plan of the derivation of the project, cached for the last revision of the knowledge base
	 * (the problems of the functions and controls shown in their tables).
	 */
	public static Derivation of(TLObject project) {
		long revision = HistoryUtils.getLastRevision().getCommitNumber();
		synchronized (CACHE) {
			Derivation cached = CACHE.get(project);
			if (cached != null && cached._revision == revision && cached._resources == Resources.getInstance()) {
				return cached;
			}
		}
		Derivation result = new Derivation(project);
		synchronized (CACHE) {
			CACHE.put(project, result);
		}
		return result;
	}

	/**
	 * The entries of the preview, in the order the derivation decided them.
	 */
	public List<Item> getItems() {
		return _items;
	}

	/**
	 * The functions and controls the derivation refuses, with their reasons.
	 */
	public List<Refusal> getRefusals() {
		return new ArrayList<>(_refusals.values());
	}

	/**
	 * Why the given function or control is refused, <code>null</code> if it is derived (or not part
	 * of the intent).
	 */
	public ResKey problem(TLObject origin) {
		Refusal refusal = _refusals.get(origin);
		return refusal == null ? null : refusal.reason();
	}

	/**
	 * The plan as lines: what the user confirms in the preview, see {@link #apply(TLObject, List)}.
	 */
	public List<String> fingerprint() {
		List<String> result = new ArrayList<>();
		for (Item item : _items) {
			result.add(item.line());
		}
		for (Refusal refusal : _refusals.values()) {
			result.add(refusal.line());
		}
		return result;
	}

	/**
	 * Whether applying the plan changes anything (marks included).
	 */
	public boolean isEmpty() {
		for (List<Runnable> operations : _operations.values()) {
			if (!operations.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Derives the installation of the project; must run in a transaction.
	 *
	 * @param confirmed
	 *        The {@link #fingerprint()} of the preview the user confirmed, <code>null</code> to apply
	 *        without one.
	 * @return The plan applied.
	 * @throws TopLogicException
	 *         If the plan differs from the confirmed preview, or a step fails (with its reason).
	 */
	public static Derivation apply(TLObject project, List<String> confirmed) {
		Derivation plan = plan(project);
		if (confirmed != null && !confirmed.equals(plan.fingerprint())) {
			throw new TopLogicException(I18NConstants.ERROR_CHANGED_SINCE_PREVIEW);
		}
		for (Phase phase : Phase.values()) {
			for (Runnable operation : plan._operations.get(phase)) {
				operation.run();
			}
		}
		synchronized (CACHE) {
			CACHE.remove(project);
		}
		return plan;
	}

	// ---------------------------------------------------------------------------------------------
	// Deciding.

	private void compute() {
		_allocator = new Allocator(_project);
		_identifiers = GroupAddressIds.taken(_project);
		List<TLObject> functions = FunctionProposal.functions(_project);
		for (TLObject function : functions) {
			List<TLObject> controls = ExtIds.list(function, "controls");
			if (function.tValueByName("preset") == null && controls.isEmpty()) {
				continue;
			}
			ResKey veto = functionVeto(function);
			Participant participant = null;
			if (veto == null) {
				participant = functionParticipant(function);
				veto = conflictVeto(participant);
			}
			if (veto == null) {
				veto = allocate(participant);
			}
			if (veto != null) {
				refuse(function, label(function), veto);
				for (TLObject control : controls) {
					refuse(control, controlLabel(control),
						I18NConstants.ERROR_CONTROL_FUNCTION__FUNCTION_REASON.fill(label(function), veto));
				}
				continue;
			}
			_participants.add(participant);
			_functions.put(function, participant);
		}
		for (Map.Entry<TLObject, Participant> entry : new ArrayList<>(_functions.entrySet())) {
			for (TLObject control : ExtIds.list(entry.getKey(), "controls")) {
				ResKey veto = controlVeto(control, entry.getValue());
				Participant participant = null;
				if (veto == null) {
					participant = controlParticipant(control, entry.getValue());
					veto = conflictVeto(participant);
				}
				if (veto != null) {
					refuse(control, controlLabel(control), veto);
					continue;
				}
				_participants.add(participant);
			}
		}
		for (Participant function : _functions.values()) {
			deriveFunction(function);
		}
		deriveUses();
		deriveValues();
		deriveConnections();
		removeAddresses();
		for (TLObject mark : _marks) {
			if (!_usedMarks.contains(mark)) {
				// Left over: of a part neither derived nor removed (its object is gone).
				_operations.get(Phase.MARKS).add(mark::tDelete);
			}
		}
	}

	private void refuse(TLObject origin, String label, ResKey reason) {
		_refusals.putIfAbsent(origin, new Refusal(origin, label, reason));
	}

	private ResKey functionVeto(TLObject function) {
		TLObject preset = (TLObject) function.tValueByName("preset");
		if (preset == null) {
			return I18NConstants.ERROR_FUNCTION_NO_PRESET;
		}
		TLObject device = (TLObject) function.tValueByName("device");
		Object channel = function.tValueByName("channel");
		if (device == null || channel == null) {
			return I18NConstants.ERROR_FUNCTION_NO_CHANNEL;
		}
		if (Presets.isSourcePreset(preset)) {
			return I18NConstants.ERROR_FUNCTION_SOURCE_PRESET__PRESET.fill(presetLabel(preset));
		}
		String kind = name(preset.tValueByName("kind"));
		if (!DERIVED_KINDS.contains(kind)) {
			return I18NConstants.ERROR_KIND_NOT_YET__KIND.fill(preset.tValueByName("kind"));
		}
		ResKey offer = Presets.offerVeto(device, channel.toString(), preset);
		if (offer != null) {
			return offer;
		}
		if (!Presets.objects(device, channel.toString(), preset).containsKey(COMMAND)) {
			return I18NConstants.ERROR_PRESET_NO_ROLE_OBJECT__PRESET_ROLE.fill(presetLabel(preset), role(COMMAND));
		}
		return hand(device, channel.toString(), preset, function);
	}

	private Participant functionParticipant(TLObject function) {
		TLObject device = (TLObject) function.tValueByName("device");
		String channel = function.tValueByName("channel").toString();
		TLObject preset = (TLObject) function.tValueByName("preset");
		Participant result = new Participant(function, false, function, device, channel, preset, label(function));
		result._values = Presets.values(device, channel, preset);
		result._objects = Presets.objects(device, channel, preset);
		return result;
	}

	private ResKey controlVeto(TLObject control, Participant function) {
		TLObject operation = (TLObject) control.tValueByName("operation");
		if (operation == null) {
			return I18NConstants.ERROR_CONTROL_NO_OPERATION;
		}
		TLObject device = (TLObject) control.tValueByName("device");
		Object channel = control.tValueByName("channel");
		if (device == null || channel == null) {
			return I18NConstants.ERROR_CONTROL_NO_SOURCE;
		}
		String needed = neededRole(name(operation));
		String kind = name(function._preset.tValueByName("kind"));
		if (!FunctionKinds.roles(kind).contains(needed)) {
			return I18NConstants.ERROR_CONTROL_KIND_NO_ROLE__FUNCTION_OPERATION_KIND.fill(function._label, operation,
				function._preset.tValueByName("kind"));
		}
		if (!function._objects.containsKey(needed)) {
			return I18NConstants.ERROR_CONTROL_NO_ROLE__FUNCTION_OPERATION_ROLE.fill(function._label, operation,
				role(needed));
		}
		TLObject preset = (TLObject) control.tValueByName("preset");
		if (preset == null) {
			return I18NConstants.ERROR_CONTROL_NO_PRESET;
		}
		if (!Presets.isSourcePreset(preset)) {
			return I18NConstants.ERROR_CONTROL_ACTUATOR_PRESET__PRESET.fill(presetLabel(preset));
		}
		if (!Presets.operations(preset).contains(name(operation))) {
			return I18NConstants.ERROR_PRESET_CANNOT__PRESET_OPERATION.fill(presetLabel(preset), operation);
		}
		ResKey offer = Presets.offerVeto(device, channel.toString(), preset);
		if (offer != null) {
			return offer;
		}
		if (!COMMAND.equals(needed) || Presets.objects(device, channel.toString(), preset).containsKey(SEND_COMMAND)) {
			return hand(device, channel.toString(), preset, control);
		}
		return I18NConstants.ERROR_PRESET_NO_SENDER__PRESET.fill(presetLabel(preset));
	}

	/**
	 * The role of the function an operation needs.
	 */
	private static String neededRole(String operation) {
		switch (operation) {
			case "showStatus":
				return STATE;
			case "dim":
				return "step";
			default:
				return COMMAND;
		}
	}

	/**
	 * Why the preset cannot be derived on the channel because of a preset applied there by hand:
	 * another preset than the one the origin's mark recorded.
	 */
	private ResKey hand(TLObject device, String channel, TLObject preset, TLObject origin) {
		TLObject use = Presets.use(device, channel);
		if (use == null || use.tValueByName("preset") == preset) {
			return null;
		}
		TLObject mark = findMark("preset", origin, m -> m.tValueByName("device") == device
			&& channel.equals(m.tValueByName("channel")));
		if (mark != null && mark.tValueByName("preset") == use.tValueByName("preset")) {
			return null;
		}
		return I18NConstants.ERROR_HAND_PRESET__PRESET.fill(presetLabel((TLObject) use.tValueByName("preset")));
	}

	private Participant controlParticipant(TLObject control, Participant function) {
		TLObject device = (TLObject) control.tValueByName("device");
		String channel = control.tValueByName("channel").toString();
		TLObject preset = (TLObject) control.tValueByName("preset");
		Participant result = new Participant(control, true, function._function, device, channel, preset,
			controlLabel(control));
		result._values = Presets.values(device, channel, preset);
		result._objects = Presets.objects(device, channel, preset);
		return result;
	}

	/**
	 * Why the participant cannot be derived with the ones accepted before on its device: the same
	 * channel, or values of their presets in conflict.
	 */
	private ResKey conflictVeto(Participant participant) {
		for (Participant other : _participants) {
			if (other._device != participant._device) {
				continue;
			}
			if (other._channel.equals(participant._channel)) {
				if (other._control && participant._control) {
					return I18NConstants.ERROR_CHANNEL_USED__OTHER.fill(other._label);
				}
				if (other._preset != participant._preset) {
					return I18NConstants.ERROR_CHANNEL_USED__OTHER.fill(other._label);
				}
				continue;
			}
			ResKey conflict = Presets.conflict(participant._device, participant._channel, participant._preset,
				other._channel, other._preset);
			if (conflict != null) {
				return I18NConstants.ERROR_CONFLICT__OTHER_REASON.fill(other._label, conflict);
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------
	// Addresses.

	/**
	 * The addresses of the function's roles: the marked ones, and new ones allocated for the others.
	 */
	private ResKey allocate(Participant function) {
		String kind = name(function._preset.tValueByName("kind"));
		List<Slot> planned = new ArrayList<>();
		for (String role : FunctionKinds.roles(kind)) {
			Interface placed = function._objects.get(role);
			if (placed == null) {
				continue;
			}
			Slot slot = new Slot(role);
			slot._type = ObjectTypes.effective(function._device, placed);
			TLObject mark = findMark("address", function._origin, m -> role.equals(m.tValueByName("role")));
			if (mark != null) {
				slot._mark = mark;
				TLObject address = (TLObject) mark.tValueByName("address");
				if (address == null || !address.tValid()) {
					slot._gone = true;
				} else {
					slot._address = address;
				}
				slot._name = text(mark.tValueByName("name"));
				slot._identifier = text(mark.tValueByName("identifier"));
				slot._number = mark.tValueByName("number") instanceof Number number ? number.intValue() : -1;
			} else if (function._function.tValueByName(role) instanceof TLObject bound && bound.tValid()
				&& ExtIds.isA(bound, "ehome.logical:GroupAddress") && !markedElsewhere(bound)) {
				// The role is bound to an address already (an imported function, a project file imported
				// without its marks): the derivation takes it as it is.
				slot._address = bound;
				slot._adopted = true;
				slot._name = text(bound.tValueByName("name"));
				slot._identifier = text(bound.tValueByName("identifier"));
				slot._number = bound.tValueByName("address") instanceof GA ga ? ga.getValue() : -1;
				Object types = bound.tValueByName("datapointTypes");
				slot._boundType = types instanceof Collection<?> list && !list.isEmpty() ? (TLObject) list.iterator().next()
					: null;
			} else {
				planned.add(slot);
			}
			function._slots.put(role, slot);
		}
		if (!planned.isEmpty()) {
			int main = mainGroup(kind);
			int floor = floorIndex((TLObject) function._function.tValueByName("room"));
			int middle = Math.min(floor, MIDDLE_GROUPS - 1);
			List<Integer> numbers = new ArrayList<>();
			Allocator.Mark undo = _allocator.mark();
			for (int n = 0; n < planned.size(); n++) {
				Integer number = _allocator.allocate(main, middle, kindRange(kind),
					floorName((TLObject) function._function.tValueByName("room")));
				if (number == null) {
					_allocator.reset(undo);
					return I18NConstants.ERROR_NO_FREE_ADDRESS__RANGE.fill(_allocator.blockText(main, middle));
				}
				numbers.add(number);
			}
			for (Allocator.Planned range : _allocator.since(undo)) {
				_items.add(new Item(Action.CREATE, What.RANGE, range.label(), _resources.getString(
					I18NConstants.DETAIL_RANGE__START_END.fill(_allocator.format(range.start()),
						_allocator.format(range.end()))), function._origin, function._label));
				_operations.get(Phase.RANGES).add(range::create);
			}
			String mainRole = FunctionKinds.mainRole(kind);
			for (int n = 0; n < planned.size(); n++) {
				Slot slot = planned.get(n);
				slot._number = numbers.get(n).intValue();
				slot._name = addressName(function._function, slot._role, _resources);
				slot._identifier = GroupAddressIds.proposeFor(function._function, slot._role, mainRole, _identifiers);
			}
		}
		return null;
	}

	/**
	 * The items and operations of an accepted function: its addresses, its kind and roles.
	 */
	private void deriveFunction(Participant function) {
		TLObject f = function._function;
		for (Slot slot : function._slots.values()) {
			if (slot._adopted) {
				_operations.get(Phase.MARKS).add(() -> {
					TLObject mark = newMark("address", function);
					mark.tUpdateByName("role", slot._role);
					mark.tUpdateByName("address", slot._address);
					mark.tUpdateByName("name", slot._name);
					mark.tUpdateByName("identifier", slot._identifier);
					mark.tUpdateByName("datapointType", slot._boundType);
					mark.tUpdateByName("number", Integer.valueOf(slot._number));
					mark.tUpdateByName("functionName", functionName(function._function, _resources));
				});
				continue;
			}
			if (slot._mark == null) {
				createAddress(function, slot);
				continue;
			}
			_usedMarks.add(slot._mark);
			if (slot._gone) {
				_items.add(new Item(Action.KEEP, What.ADDRESS, slot.label(),
					_resources.getString(I18NConstants.KEEP_ADDRESS_REMOVED), function._origin, function._label));
				continue;
			}
			TLObject address = slot._address;
			if (changedByHand(address, slot._mark)) {
				_items.add(new Item(Action.KEEP, What.ADDRESS, addressLabel(address),
					_resources.getString(I18NConstants.KEEP_CHANGED__DERIVED.fill(slot.label())), function._origin,
					function._label));
				continue;
			}
			String name = slot._name;
			String namedAfter = text(slot._mark.tValueByName("functionName"));
			if (namedAfter != null && !isFunctionName(f, namedAfter)) {
				// The function was renamed since: so is its address.
				String newName = addressName(f, slot._role, _resources);
				String functionName = functionName(f, _resources);
				_items.add(new Item(Action.CHANGE, What.ADDRESS, addressLabel(address),
					_resources.getString(I18NConstants.DETAIL_RENAME__OLD_NEW.fill(name, newName)), function._origin,
					function._label));
				TLObject mark = slot._mark;
				_operations.get(Phase.ADDRESSES).add(() -> {
					address.tUpdateByName("name", newName);
					mark.tUpdateByName("name", newName);
					mark.tUpdateByName("functionName", functionName);
				});
				slot._name = newName;
			}
			TLObject markType = (TLObject) slot._mark.tValueByName("datapointType");
			if (slot._type != null && slot._type != markType) {
				TLObject type = slot._type;
				_items.add(new Item(Action.CHANGE, What.ADDRESS, addressLabel(address),
					_resources.getString(I18NConstants.DETAIL_TYPE__OLD_NEW.fill(typeLabel(markType), typeLabel(type))),
					function._origin, function._label));
				TLObject mark = slot._mark;
				_operations.get(Phase.ADDRESSES).add(() -> {
					address.tUpdateByName("datapointTypes", List.of(type));
					mark.tUpdateByName("datapointType", type);
				});
			}
		}

		// The kind and the roles of the function.
		TLObject kind = (TLObject) function._preset.tValueByName("kind");
		if (f.tValueByName("kind") != kind) {
			_items.add(new Item(Action.CHANGE, What.FUNCTION, function._label, _resources.getString(
				I18NConstants.DETAIL_KIND__OLD_NEW.fill(f.tValueByName("kind"), kind)), f, function._label));
			_operations.get(Phase.FUNCTIONS).add(() -> f.tUpdateByName("kind", kind));
		}
		for (Slot slot : function._slots.values()) {
			if (slot._gone) {
				continue;
			}
			Object bound = f.tValueByName(slot._role);
			if (bound != null && bound == slot._address) {
				continue;
			}
			if (bound instanceof TLObject other && other.tValid() && !derivedBy(other, f)) {
				_items.add(new Item(Action.KEEP, What.FUNCTION, function._label, _resources.getString(
					I18NConstants.KEEP_ROLE_BOUND__ROLE_ADDRESS.fill(role(slot._role), addressLabel(other))), f,
					function._label));
				continue;
			}
			_items.add(new Item(Action.CHANGE, What.FUNCTION, function._label, _resources.getString(
				I18NConstants.DETAIL_ROLE__ROLE_ADDRESS.fill(role(slot._role), slot.label())), f, function._label));
			_operations.get(Phase.FUNCTIONS).add(() -> f.tUpdateByName(slot._role, slot._address));
		}
	}

	private void createAddress(Participant function, Slot slot) {
		String functionName = functionName(function._function, _resources);
		String detail = _resources.getString(I18NConstants.DETAIL_ADDRESS__ADDRESS_IDENTIFIER_TYPE
			.fill(_allocator.format(slot._number), slot._identifier == null ? "" : slot._identifier, typeLabel(slot._type)));
		_items.add(new Item(Action.CREATE, What.ADDRESS, slot.label(), detail, function._origin, function._label));
		_operations.get(Phase.ADDRESSES).add(() -> {
			TLObject range = _allocator.rangeOf(slot._number);
			TLObject address = GroupAddresses.create(range, Integer.valueOf(slot._number), slot._name, slot._type);
			address.tUpdateByName("identifier", slot._identifier);
			slot._address = address;
		});
		_operations.get(Phase.MARKS).add(() -> {
			TLObject mark = newMark("address", function);
			mark.tUpdateByName("role", slot._role);
			mark.tUpdateByName("address", slot._address);
			mark.tUpdateByName("name", slot._name);
			mark.tUpdateByName("identifier", slot._identifier);
			mark.tUpdateByName("datapointType", slot._type);
			mark.tUpdateByName("number", Integer.valueOf(slot._number));
			mark.tUpdateByName("functionName", functionName);
		});
	}

	/**
	 * Whether a mark of another function names the address.
	 */
	private boolean markedElsewhere(TLObject address) {
		for (TLObject mark : _marks) {
			if ("address".equals(kindName(mark)) && mark.tValueByName("address") == address) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the given address was derived for the given function (a mark of it names the address).
	 */
	private boolean derivedBy(TLObject address, TLObject function) {
		for (TLObject mark : _marks) {
			if ("address".equals(kindName(mark)) && mark.tValueByName("address") == address
				&& mark.tValueByName("function") == function) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the address differs from its mark in its name, identifier, datapoint type or number.
	 */
	private static boolean changedByHand(TLObject address, TLObject mark) {
		if (!Objects.equals(text(address.tValueByName("name")), text(mark.tValueByName("name")))
			|| !Objects.equals(text(address.tValueByName("identifier")), text(mark.tValueByName("identifier")))) {
			return true;
		}
		Object types = address.tValueByName("datapointTypes");
		Object first = types instanceof Collection<?> list && !list.isEmpty() ? list.iterator().next() : null;
		if (first != mark.tValueByName("datapointType")) {
			return true;
		}
		int number = address.tValueByName("address") instanceof GA ga ? ga.getValue() : -1;
		return !(mark.tValueByName("number") instanceof Number marked) || marked.intValue() != number;
	}

	/**
	 * The name of the address of a role of the function in the language of the given resources:
	 * "Licht Schalten", "Licht Status".
	 */
	static String addressName(TLObject function, String role, Resources resources) {
		String text = functionName(function, resources);
		switch (role) {
			case COMMAND:
				return resources.getString(I18NConstants.ADDRESS_NAME_COMMAND__FUNCTION.fill(text));
			case STATE:
				return resources.getString(I18NConstants.ADDRESS_NAME_STATE__FUNCTION.fill(text));
			default:
				return resources.getString(I18NConstants.ADDRESS_NAME__FUNCTION_ROLE.fill(text, role));
		}
	}

	/**
	 * The name of the function in the language of the given resources (in the user's where it has none
	 * there).
	 */
	private static String functionName(TLObject function, Resources resources) {
		Object name = function.tValueByName("name");
		String text = name instanceof ResKey key ? resources.getString(key, null) : text(name);
		if (text == null || text.isBlank()) {
			text = name instanceof ResKey key ? Resources.getInstance().getString(key) : "?";
		}
		return text.trim();
	}

	/**
	 * Whether the function has the given name in one of the application's languages: it was not
	 * renamed since its address was named after it.
	 */
	private static boolean isFunctionName(TLObject function, String name) {
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			if (functionName(function, Resources.getInstance(locale)).equals(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Removes the addresses no longer derived (of a deleted or refused function, of a role its preset
	 * no longer names) where nothing else uses them.
	 */
	private void removeAddresses() {
		for (TLObject mark : _marks) {
			if (_usedMarks.contains(mark) || !"address".equals(kindName(mark))) {
				continue;
			}
			_usedMarks.add(mark);
			_operations.get(Phase.MARKS).add(mark::tDelete);
			TLObject address = (TLObject) mark.tValueByName("address");
			if (address == null || !address.tValid()) {
				continue;
			}
			TLObject origin = (TLObject) mark.tValueByName("function");
			String originLabel = origin == null ? "" : label(origin);
			if (changedByHand(address, mark)) {
				_items.add(new Item(Action.KEEP, What.ADDRESS, addressLabel(address),
					_resources.getString(I18NConstants.KEEP_LEFT_CHANGED), origin, originLabel));
				continue;
			}
			if (used(address, origin)) {
				_items.add(new Item(Action.KEEP, What.ADDRESS, addressLabel(address),
					_resources.getString(I18NConstants.KEEP_ADDRESS_USED), origin, originLabel));
				continue;
			}
			_items.add(new Item(Action.REMOVE, What.ADDRESS, addressLabel(address), "", origin, originLabel));
			_operations.get(Phase.REMOVE_ADDRESSES).add(() -> {
				if (origin != null && origin.tValid()) {
					for (String role : FunctionKinds.roles(FunctionProposal.kind(origin))) {
						if (origin.tValueByName(role) == address) {
							origin.tUpdateByName(role, null);
						}
					}
				}
				address.tDelete();
			});
		}
	}

	/**
	 * Whether the address is used apart from what the derivation removes: a connection, a role of a
	 * function other than the one it was derived for, a planned connection.
	 */
	private boolean used(TLObject address, TLObject origin) {
		for (TLObject connection : GroupAddressConnections.connections(address)) {
			if (!_removedConnections.contains(connection)) {
				return true;
			}
		}
		for (TLObject function : FunctionProposal.functions(_project)) {
			if (function == origin) {
				continue;
			}
			for (String role : FunctionKinds.roles(FunctionProposal.kind(function))) {
				if (function.tValueByName(role) == address) {
					return true;
				}
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------
	// Preset uses.

	private void deriveUses() {
		Set<List<Object>> wanted = new HashSet<>();
		for (Participant participant : _participants) {
			wanted.add(useKey(participant._device, participant._channel));
			TLObject use = Presets.use(participant._device, participant._channel);
			TLObject mark = findMark("preset", participant._origin, m -> m.tValueByName("device") == participant._device
				&& participant._channel.equals(m.tValueByName("channel")));
			String object = presetObject(participant._device, participant._channel);
			TLObject preset = participant._preset;
			TLObject device = participant._device;
			String channel = participant._channel;
			if (mark != null) {
				_usedMarks.add(mark);
				TLObject marked = (TLObject) mark.tValueByName("preset");
				if (marked != preset) {
					_items.add(new Item(Action.CHANGE, What.PRESET, object, _resources.getString(
						I18NConstants.DETAIL_PRESET_CHANGE__OLD_NEW.fill(presetLabel(marked), presetLabel(preset))),
						participant._origin, participant._label));
					_operations.get(Phase.USES).add(() -> {
						Presets.recordUse(device, channel, preset);
						mark.tUpdateByName("preset", preset);
					});
				} else if (use == null) {
					// The record was taken back by hand: recorded again, the values are what matters.
					_operations.get(Phase.USES).add(() -> Presets.recordUse(device, channel, preset));
				}
				continue;
			}
			if (use == null) {
				_items.add(new Item(Action.CREATE, What.PRESET, object,
					_resources.getString(I18NConstants.DETAIL_PRESET__PRESET.fill(presetLabel(preset))),
					participant._origin, participant._label));
				_operations.get(Phase.USES).add(() -> Presets.recordUse(device, channel, preset));
			}
			_operations.get(Phase.MARKS).add(() -> {
				TLObject created = newMark("preset", participant);
				created.tUpdateByName("device", device);
				created.tUpdateByName("channel", channel);
				created.tUpdateByName("preset", preset);
			});
		}
		for (TLObject mark : _marks) {
			if (_usedMarks.contains(mark) || !"preset".equals(kindName(mark))) {
				continue;
			}
			_usedMarks.add(mark);
			_operations.get(Phase.MARKS).add(mark::tDelete);
			TLObject device = (TLObject) mark.tValueByName("device");
			String channel = text(mark.tValueByName("channel"));
			if (device == null || channel == null || wanted.contains(useKey(device, channel))) {
				continue;
			}
			TLObject use = Presets.use(device, channel);
			TLObject preset = (TLObject) mark.tValueByName("preset");
			if (use != null && use.tValueByName("preset") == preset) {
				_items.add(new Item(Action.REMOVE, What.PRESET, presetObject(device, channel),
					_resources.getString(I18NConstants.DETAIL_PRESET_REMOVE__PRESET.fill(presetLabel(preset))),
					origin(mark), originLabel(mark)));
				_operations.get(Phase.USES).add(use::tDelete);
			}
		}
	}

	private static List<Object> useKey(TLObject device, String channel) {
		return List.of(device, channel);
	}

	private String presetObject(TLObject device, String channel) {
		return _resources.getString(I18NConstants.OBJECT_IN_DEVICE__DEVICE_OBJECT
			.fill(GroupAddressConnections.deviceLabel(device), Presets.channelLabel(device, channel)));
	}

	// ---------------------------------------------------------------------------------------------
	// Values.

	/** A value a participant wants on its device. */
	private record Wanted(Participant participant, ParameterView view, String encoded) {
		// Value.
	}

	private void deriveValues() {
		Map<TLObject, Map<ParameterView, Object>> sets = new LinkedHashMap<>();
		Set<List<Object>> wantedKeys = new HashSet<>();
		List<Wanted> wanted = new ArrayList<>();
		for (Participant participant : _participants) {
			for (Map.Entry<ParameterView, Object> entry : participant._values.entrySet()) {
				ParameterView view = entry.getKey();
				wanted.add(new Wanted(participant, view, Presets.encoded(view, entry.getValue())));
				wantedKeys.add(valueKey(participant._device, view));
			}
		}
		Map<List<Object>, String> planned = new LinkedHashMap<>();
		for (Wanted value : wanted) {
			Participant participant = value.participant();
			TLObject device = participant._device;
			ParameterView view = value.view();
			String current = current(device, view);
			TLObject mark = findMark("value", participant._origin,
				m -> m.tValueByName("device") == device && Objects.equals(ModuleParts.parameter(m), view));
			String object = valueObject(device, view);
			if (mark != null) {
				_usedMarks.add(mark);
				String marked = text(mark.tValueByName("value"));
				if (!Objects.equals(current, marked)) {
					_items.add(new Item(Action.KEEP, What.VALUE, object, _resources.getString(
						I18NConstants.KEEP_VALUE_CHANGED__CURRENT_DERIVED.fill(display(view, current), display(view, marked))),
						participant._origin, participant._label));
					continue;
				}
				if (!Objects.equals(value.encoded(), marked)) {
					change(sets, planned, participant, device, view, current, value.encoded(), object);
					_operations.get(Phase.MARKS).add(() -> mark.tUpdateByName("value", value.encoded()));
				}
				continue;
			}
			if (!Objects.equals(current, value.encoded())) {
				change(sets, planned, participant, device, view, current, value.encoded(), object);
			}
			_operations.get(Phase.MARKS).add(() -> {
				TLObject created = newMark("value", participant);
				created.tUpdateByName("device", device);
				ModuleParts.bind(created, ModuleParts.PARAMETER, view);
				created.tUpdateByName("value", value.encoded());
			});
		}
		for (TLObject mark : _marks) {
			if (_usedMarks.contains(mark) || !"value".equals(kindName(mark))) {
				continue;
			}
			_usedMarks.add(mark);
			_operations.get(Phase.MARKS).add(mark::tDelete);
			TLObject device = (TLObject) mark.tValueByName("device");
			ParameterView view = ModuleParts.parameter(mark);
			if (device == null || !device.tValid() || view == null || wantedKeys.contains(valueKey(device, view))) {
				continue;
			}
			String current = current(device, view);
			String marked = text(mark.tValueByName("value"));
			String object = valueObject(device, view);
			if (!Objects.equals(current, marked)) {
				_items.add(new Item(Action.KEEP, What.VALUE, object, _resources.getString(I18NConstants.KEEP_LEFT_CHANGED),
					origin(mark), originLabel(mark)));
				continue;
			}
			List<Object> key = valueKey(device, view);
			if (planned.containsKey(key)) {
				continue;
			}
			String defaultValue = ParameterValues.encode(DeviceConfiguration.defaultHolder(view));
			planned.put(key, defaultValue);
			if (Objects.equals(current, defaultValue)) {
				continue;
			}
			_items.add(new Item(Action.REMOVE, What.VALUE, object, _resources.getString(
				I18NConstants.DETAIL_RESET__OLD_DEFAULT.fill(display(view, current), display(view, defaultValue))),
				origin(mark), originLabel(mark)));
			sets.computeIfAbsent(device, d -> new LinkedHashMap<>()).put(view, null);
		}
		for (Map.Entry<TLObject, Map<ParameterView, Object>> entry : sets.entrySet()) {
			TLObject device = entry.getKey();
			Map<ParameterView, Object> values = entry.getValue();
			String origin = GroupAddressConnections.deviceLabel(device);
			_operations.get(Phase.VALUES).add(() -> Presets.setValues(device, values, origin));
		}
	}

	private void change(Map<TLObject, Map<ParameterView, Object>> sets, Map<List<Object>, String> planned,
			Participant participant, TLObject device, ParameterView view, String current, String wanted, String object) {
		List<Object> key = valueKey(device, view);
		if (planned.containsKey(key)) {
			// Another participant sets the same value (no conflict, see conflictVeto).
			return;
		}
		planned.put(key, wanted);
		_items.add(new Item(Action.CHANGE, What.VALUE, object,
			_resources.getString(I18NConstants.DETAIL_VALUE__OLD_NEW.fill(display(view, current), display(view, wanted))),
			participant._origin, participant._label));
		sets.computeIfAbsent(device, d -> new LinkedHashMap<>()).put(view, participant._values.get(view));
	}

	private static List<Object> valueKey(TLObject device, ParameterView view) {
		return List.of(device, view);
	}

	private static String current(TLObject device, ParameterView view) {
		return ParameterValues.encode(DeviceConfiguration.valueHolder(device, view));
	}

	private String valueObject(TLObject device, ParameterView view) {
		return _resources.getString(I18NConstants.OBJECT_IN_DEVICE__DEVICE_OBJECT
			.fill(GroupAddressConnections.deviceLabel(device), ChannelShape.parameterLabel(view)));
	}

	/**
	 * The encoded value of a parameter as the user reads it: the text of an entry of an enumeration,
	 * else the value.
	 */
	private String display(ParameterView view, String encoded) {
		if (encoded == null) {
			return "";
		}
		ParameterType type = ParameterValues.valueType(ModuleParts.template(view));
		if (type instanceof EnumerationType enumeration) {
			for (EnumerationValue value : enumeration.getValues()) {
				if (Integer.toString(value.getValue()).equals(encoded)) {
					Object name = value.tValueByName("name");
					return name instanceof ResKey key ? _resources.getString(key) : String.valueOf(name);
				}
			}
		}
		return encoded;
	}

	// ---------------------------------------------------------------------------------------------
	// Connections.

	/** A connection a participant wants. */
	private record Link(Participant participant, Interface placed, Slot slot, String kind, String role) {
		// Value.
	}

	private void deriveConnections() {
		List<Link> links = new ArrayList<>();
		for (Participant participant : _participants) {
			links.addAll(links(participant));
		}
		Map<Link, TLObject> marked = new LinkedHashMap<>();
		List<Link> fresh = new ArrayList<>();
		for (Link link : links) {
			if (link.slot()._gone) {
				continue;
			}
			TLObject device = link.participant()._device;
			TLObject address = link.slot()._address;
			TLObject mark = address == null ? null : findMark("connection", link.participant()._origin,
				m -> m.tValueByName("device") == device && Objects.equals(ModuleParts.port(m), link.placed())
					&& m.tValueByName("address") == address && link.kind().equals(name(m.tValueByName("connectionKind"))));
			if (mark != null) {
				_usedMarks.add(mark);
				marked.put(link, mark);
			} else {
				fresh.add(link);
			}
		}
		for (Map.Entry<Link, TLObject> entry : marked.entrySet()) {
			Link link = entry.getKey();
			if (actual(link.participant()._device, link.placed(), link.slot()._address, link.kind()) == null) {
				_items.add(new Item(Action.KEEP, What.CONNECTION, connectionObject(link),
					_resources.getString(I18NConstants.KEEP_CONNECTION_REMOVED), link.participant()._origin,
					link.participant()._label));
			}
		}
		for (TLObject mark : _marks) {
			if (_usedMarks.contains(mark) || !"connection".equals(kindName(mark))) {
				continue;
			}
			_usedMarks.add(mark);
			_operations.get(Phase.MARKS).add(mark::tDelete);
			TLObject device = (TLObject) mark.tValueByName("device");
			Interface placed = ModuleParts.port(mark);
			TLObject address = (TLObject) mark.tValueByName("address");
			if (device == null || !device.tValid() || placed == null || address == null || !address.tValid()) {
				continue;
			}
			String kind = name(mark.tValueByName("connectionKind"));
			TLObject connection = actual(device, placed, address, kind);
			if (connection == null) {
				continue;
			}
			_removedConnections.add(connection);
			_items.add(new Item(Action.REMOVE, What.CONNECTION, objectLabel(device, placed),
				connectionDetail(kind, addressLabel(address)), origin(mark), originLabel(mark)));
			_operations.get(Phase.DISCONNECT).add(() -> DeviceSettings.disconnect(connection));
		}
		Set<String> sending = new HashSet<>();
		for (Link link : fresh) {
			TLObject device = link.participant()._device;
			Interface placed = link.placed();
			TLObject address = link.slot()._address;
			if (address != null && actual(device, placed, address, link.kind()) != null) {
				// Connected by hand as derived: marked without a change.
				_operations.get(Phase.MARKS).add(() -> connectionMark(link));
				continue;
			}
			if (SEND.equals(link.kind())) {
				String key = device.tId() + ":" + placed.getPort().getIndex();
				TLObject other = sends(device, placed);
				if (other != null || !sending.add(key)) {
					TLObject target = other == null ? null : (TLObject) other.tValueByName("target");
					_items.add(new Item(Action.KEEP, What.CONNECTION, connectionObject(link),
						_resources.getString(I18NConstants.KEEP_SENDS_ALREADY__ADDRESS.fill(
							target == null ? "" : addressLabel(target))),
						link.participant()._origin, link.participant()._label));
					continue;
				}
			}
			_items.add(new Item(Action.CREATE, What.CONNECTION, connectionObject(link),
				connectionDetail(link.kind(), link.slot().label()), link.participant()._origin, link.participant()._label));
			_operations.get(Phase.CONNECT).add(
				() -> DeviceSettings.connect(device, placed, link.slot()._address, connectionKind(link.kind())));
			_operations.get(Phase.MARKS).add(() -> connectionMark(link));
		}
	}

	/**
	 * The connections a participant wants: of a function the actuator's command object receiving and
	 * its state object sending; of a control by its operation.
	 */
	private List<Link> links(Participant participant) {
		List<Link> result = new ArrayList<>();
		Participant function = _functions.get(participant._function);
		Slot command = function._slots.get(COMMAND);
		Slot state = function._slots.get(STATE);
		Map<String, Interface> objects = participant._objects;
		if (!participant._control) {
			result.add(new Link(participant, objects.get(COMMAND), command, RECEIVE, COMMAND));
			if (state != null) {
				result.add(new Link(participant, objects.get(STATE), state, SEND, STATE));
			}
			return result;
		}
		String operation = name(((TLObject) participant._origin).tValueByName("operation"));
		Interface sender = objects.get(SEND_COMMAND);
		switch (operation) {
			case "showStatus":
				Interface display = objects.get(RECEIVE_STATUS);
				if (display != null && state != null) {
					result.add(new Link(participant, display, state, RECEIVE, RECEIVE_STATUS));
				}
				break;
			case "toggle":
				result.add(new Link(participant, sender, command, SEND, SEND_COMMAND));
				if (state != null) {
					Interface listener = objects.getOrDefault(LISTEN_STATUS, sender);
					result.add(new Link(participant, listener, state, RECEIVE, LISTEN_STATUS));
				}
				break;
			default:
				result.add(new Link(participant, sender, command, SEND, SEND_COMMAND));
				break;
		}
		return result;
	}

	private void connectionMark(Link link) {
		TLObject mark = newMark("connection", link.participant());
		mark.tUpdateByName("role", link.role());
		mark.tUpdateByName("device", link.participant()._device);
		ModuleParts.bind(mark, ModuleParts.PORT, link.placed());
		mark.tUpdateByName("address", link.slot()._address);
		mark.tUpdateByName("connectionKind", connectionKind(link.kind()));
	}

	/**
	 * The connection of the object of the device to the address of the given kind, <code>null</code>
	 * if there is none.
	 */
	private static TLObject actual(TLObject device, Interface placed, TLObject address, String kind) {
		for (TLObject connection : DeviceSettings.connections(device, placed)) {
			if (connection.tValueByName("target") == address && kind.equals(name(connection.tValueByName("kind")))) {
				return connection;
			}
		}
		return null;
	}

	/**
	 * The connection the object sends on that the derivation does not remove, <code>null</code> for
	 * none.
	 */
	private TLObject sends(TLObject device, Interface placed) {
		for (TLObject connection : DeviceSettings.connections(device, placed)) {
			if (SEND.equals(name(connection.tValueByName("kind"))) && !_removedConnections.contains(connection)) {
				return connection;
			}
		}
		return null;
	}

	private String connectionObject(Link link) {
		return objectLabel(link.participant()._device, link.placed());
	}

	private String objectLabel(TLObject device, Interface placed) {
		return _resources.getString(I18NConstants.OBJECT_IN_DEVICE__DEVICE_OBJECT
			.fill(GroupAddressConnections.deviceLabel(device), ChannelShape.objectLabel(device, placed)));
	}

	private String connectionDetail(String kind, String address) {
		return _resources.getString(SEND.equals(kind) ? I18NConstants.DETAIL_SENDS__ADDRESS.fill(address)
			: I18NConstants.DETAIL_RECEIVES__ADDRESS.fill(address));
	}

	private static TLClassifier connectionKind(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.physical:ConnectionType")).getClassifier(name);
	}

	// ---------------------------------------------------------------------------------------------
	// Marks.

	private interface MarkTest {
		boolean matches(TLObject mark);
	}

	/**
	 * The first mark of the given kind of the origin not taken yet that passes the test.
	 */
	private TLObject findMark(String kind, TLObject origin, MarkTest test) {
		for (TLObject mark : _marks) {
			if (_usedMarks.contains(mark) || !kind.equals(kindName(mark)) || origin(mark) != origin) {
				continue;
			}
			if (test.matches(mark)) {
				return mark;
			}
		}
		return null;
	}

	/**
	 * The function or control a mark comes from, <code>null</code> once it is deleted.
	 */
	private static TLObject origin(TLObject mark) {
		Object origin = Boolean.TRUE.equals(mark.tValueByName("fromControl")) ? mark.tValueByName("control")
			: mark.tValueByName("function");
		return origin instanceof TLObject obj && obj.tValid() ? obj : null;
	}

	private String originLabel(TLObject mark) {
		TLObject origin = origin(mark);
		if (origin == null) {
			return "";
		}
		return ExtIds.isA(origin, "ehome.runtime:Control") ? controlLabel(origin) : label(origin);
	}

	private TLObject newMark(String kind, Participant participant) {
		TLObject mark = ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(MARK));
		mark.tUpdateByName("kind", TLModelUtil.findPart("ehome.runtime:DerivedKind#" + kind));
		mark.tUpdateByName("fromControl", Boolean.valueOf(participant._control));
		mark.tUpdateByName("function", participant._function);
		mark.tUpdateByName("control", participant._control ? participant._origin : null);
		List<TLObject> marks = ExtIds.list(_project, "derivedParts");
		marks.add(mark);
		_project.tUpdateByName("derivedParts", marks);
		return mark;
	}

	private static String kindName(TLObject mark) {
		return name(mark.tValueByName("kind"));
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers.

	/**
	 * The main group of the addresses of functions of the given kind: light 1, switch 2, and the kinds
	 * of the later steps of haumacher/ehome #131 reserved (blind 3, climate 4, scene 5, sensor 6).
	 */
	public static int mainGroup(String kind) {
		Integer result = MAIN_GROUPS.get(kind);
		return result == null ? 7 : result.intValue();
	}

	/**
	 * The position of the floor the given part of the building is on among the floors of its
	 * building (in the order of the building structure); 0 for a part on no floor.
	 */
	public static int floorIndex(TLObject part) {
		TLObject floor = floor(part);
		if (floor == null || floor.tContainer() == null) {
			return 0;
		}
		int index = 0;
		for (TLObject sibling : ExtIds.list(floor.tContainer(), "parts")) {
			if (sibling == floor) {
				return index;
			}
			if (ExtIds.isA(sibling, "ehome.building:Floor")) {
				index++;
			}
		}
		return 0;
	}

	private static TLObject floor(TLObject part) {
		for (TLObject node = part; node != null; node = node.tContainer()) {
			if (ExtIds.isA(node, "ehome.building:Floor")) {
				return node;
			}
		}
		return null;
	}

	private String floorName(TLObject room) {
		TLObject floor = floor(room);
		String name = floor == null ? null : DatapointNames.label(floor);
		return name == null ? _resources.getString(I18NConstants.RANGE_NO_FLOOR) : name;
	}

	private String kindRange(String kind) {
		return _resources.getString("switch".equals(kind) ? I18NConstants.RANGE_SWITCHING : I18NConstants.RANGE_LIGHTS);
	}

	private String label(TLObject function) {
		String result = DatapointNames.label(function);
		return result == null ? MetaLabelProvider.INSTANCE.getLabel(function) : result;
	}

	/**
	 * The name of a control: its channel, its device and its operation ("Taste 1 of Taster (1.1.2),
	 * toggle").
	 */
	String controlLabel(TLObject control) {
		TLObject device = (TLObject) control.tValueByName("device");
		Object channel = control.tValueByName("channel");
		String channelName = device == null || channel == null ? "" : Presets.channelLabel(device, channel.toString());
		return _resources.getString(I18NConstants.CONTROL_LABEL__CHANNEL_DEVICE_OPERATION.fill(channelName,
			GroupAddressConnections.deviceLabel(device), control.tValueByName("operation")));
	}

	private String presetLabel(TLObject preset) {
		return preset == null ? "" : String.valueOf(preset.tValueByName("name"));
	}

	private String addressLabel(TLObject address) {
		String name = DatapointNames.name(address);
		return name == null ? String.valueOf(address.tValueByName("address")) : name;
	}

	private String typeLabel(TLObject type) {
		return type == null ? "" : MetaLabelProvider.INSTANCE.getLabel(type);
	}

	private static TLObject role(String name) {
		return (TLObject) TLModelUtil.findPart("ehome.template:PresetRole#" + name);
	}

	private static String name(Object classifier) {
		return classifier instanceof TLClassifier value ? value.getName() : null;
	}

	private static String text(Object value) {
		return value == null || value.toString().isBlank() ? null : value.toString();
	}

	/**
	 * The group ranges of a project and the free addresses, with the ranges the derivation plans.
	 */
	static final class Allocator {

		/** A group range of the project or planned. */
		private final class Node {
			final TLObject _range;

			final Planned _planned;

			final int _start;

			final int _end;

			final List<Node> _children = new ArrayList<>();

			Node(TLObject range, Planned planned, int start, int end) {
				_range = range;
				_planned = planned;
				_start = start;
				_end = end;
			}

			boolean contains(int number) {
				return _start <= number && number <= _end;
			}

			boolean overlaps(int start, int end) {
				return _start <= end && start <= _end;
			}

			TLObject range() {
				return _range != null ? _range : _planned._created;
			}
		}

		/** A group range the derivation creates. */
		final class Planned {
			final Node _parent;

			final String _name;

			final int _start;

			final int _end;

			TLObject _created;

			Planned(Node parent, String name, int start, int end) {
				_parent = parent;
				_name = name;
				_start = start;
				_end = end;
			}

			int start() {
				return _start;
			}

			int end() {
				return _end;
			}

			String label() {
				String prefix = _style.rangePrefix(_start, _end);
				return prefix == null ? _name : prefix + " " + _name;
			}

			void create() {
				TLObject range = ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.logical:GroupRange"));
				range.tUpdateByName("name", _name);
				range.tUpdateByName("rangeStart", new GA(_start, _style));
				range.tUpdateByName("rangeEnd", new GA(_end, _style));
				TLObject parent = _parent == null ? _project : _parent.range();
				String reference = _parent == null ? "groupAddresses" : "parts";
				List<TLObject> parts = ExtIds.list(parent, reference);
				parts.add(range);
				parent.tUpdateByName(reference, parts);
				ExtIds.assign(range);
				_created = range;
			}
		}

		/** A state of the allocation to go back to. */
		record Mark(int taken, int planned) {
			// Value.
		}

		final TLObject _project;

		final GroupAddressStyle _style;

		private final List<Node> _roots = new ArrayList<>();

		private final List<Integer> _taken = new ArrayList<>();

		private final Set<Integer> _takenSet = new HashSet<>();

		private final List<Planned> _plannedRanges = new ArrayList<>();

		private final Map<Integer, Node> _placed = new LinkedHashMap<>();

		Allocator(TLObject project) {
			_project = project;
			_style = style(project);
			for (TLObject part : ExtIds.list(project, "groupAddresses")) {
				collect(part, _roots);
			}
		}

		private void collect(TLObject part, List<Node> nodes) {
			if (ExtIds.isA(part, "ehome.logical:GroupAddress")) {
				if (part.tValueByName("address") instanceof GA ga) {
					_takenSet.add(Integer.valueOf(ga.getValue()));
				}
				return;
			}
			if (!ExtIds.isA(part, "ehome.logical:GroupRange")) {
				return;
			}
			int start = part.tValueByName("rangeStart") instanceof GA ga ? ga.getValue() : 0;
			int end = part.tValueByName("rangeEnd") instanceof GA ga ? ga.getValue() : -1;
			Node node = new Node(part, null, start, end);
			nodes.add(node);
			for (TLObject child : ExtIds.list(part, "parts")) {
				collect(child, node._children);
			}
		}

		private static GroupAddressStyle style(TLObject project) {
			Object style = project.tValueByName("groupAddressStyle");
			if (style instanceof TLClassifier classifier) {
				try {
					return GroupAddressStyle.valueOf(classifier.getName());
				} catch (IllegalArgumentException ex) {
					return GroupAddressStyle.DEFAULT;
				}
			}
			return GroupAddressStyle.DEFAULT;
		}

		Mark mark() {
			return new Mark(_taken.size(), _plannedRanges.size());
		}

		void reset(Mark mark) {
			while (_taken.size() > mark.taken()) {
				Integer number = _taken.remove(_taken.size() - 1);
				_takenSet.remove(number);
				_placed.remove(number);
			}
			while (_plannedRanges.size() > mark.planned()) {
				Planned planned = _plannedRanges.remove(_plannedRanges.size() - 1);
				List<Node> nodes = planned._parent == null ? _roots : planned._parent._children;
				nodes.removeIf(node -> node._planned == planned);
			}
		}

		List<Planned> since(Mark mark) {
			return new ArrayList<>(_plannedRanges.subList(mark.planned(), _plannedRanges.size()));
		}

		String format(int number) {
			return _style.format(number);
		}

		String blockText(int main, int middle) {
			int start = main << 11 | middle << 8;
			int end = start + SUB_ADDRESSES - 1;
			String prefix = _style == GroupAddressStyle.ThreeLevel ? _style.rangePrefix(start, end) : null;
			return prefix != null ? prefix : format(start) + " - " + format(end);
		}

		/**
		 * The range of an allocated number, created before by a planned range or existing.
		 */
		TLObject rangeOf(int number) {
			Node node = _placed.get(Integer.valueOf(number));
			return node == null ? null : node.range();
		}

		/**
		 * The next free address of the middle group of the main group, planning the ranges taking it
		 * where the project has none; <code>null</code> if no sub address of the middle group is free in
		 * a range.
		 */
		Integer allocate(int main, int middle, String mainName, String middleName) {
			int mainStart = main << 11;
			int mainEnd = mainStart + MIDDLE_GROUPS * SUB_ADDRESSES - 1;
			int start = mainStart | middle << 8;
			int end = start + SUB_ADDRESSES - 1;
			for (int number = start; number <= end; number++) {
				if (number == 0 || _takenSet.contains(Integer.valueOf(number))) {
					continue;
				}
				Node node = place(number, start, end, mainStart, mainEnd, mainName, middleName);
				if (node != null) {
					_taken.add(Integer.valueOf(number));
					_takenSet.add(Integer.valueOf(number));
					_placed.put(Integer.valueOf(number), node);
					return Integer.valueOf(number);
				}
			}
			return null;
		}

		/**
		 * The range taking the number: the deepest one containing it without ranges below, else a
		 * planned one.
		 */
		private Node place(int number, int start, int end, int mainStart, int mainEnd, String mainName,
				String middleName) {
			Node deepest = null;
			List<Node> level = _roots;
			while (true) {
				Node found = null;
				for (Node node : level) {
					if (node.contains(number)) {
						found = node;
						break;
					}
				}
				if (found == null) {
					break;
				}
				deepest = found;
				level = found._children;
			}
			if (deepest != null && deepest._children.isEmpty()) {
				return deepest;
			}
			if (deepest != null) {
				// A range with ranges below, none containing the number: a middle range of its own in the
				// three-level notation where nothing else lies in the middle group.
				if (_style == GroupAddressStyle.ThreeLevel && deepest._start <= start && end <= deepest._end
					&& deepest._children.stream().noneMatch(node -> node.overlaps(start, end))) {
					return plan(deepest, middleName, start, end);
				}
				return null;
			}
			if (_roots.stream().anyMatch(node -> node.overlaps(mainStart, mainEnd))) {
				// The main group is divided otherwise by the project's ranges.
				return null;
			}
			Node mainNode = plan(null, mainName, mainStart, mainEnd);
			if (_style == GroupAddressStyle.ThreeLevel) {
				return plan(mainNode, middleName, start, end);
			}
			return mainNode;
		}

		private Node plan(Node parent, String name, int start, int end) {
			Planned planned = new Planned(parent, name, start, end);
			_plannedRanges.add(planned);
			Node node = new Node(null, planned, start, end);
			(parent == null ? _roots : parent._children).add(node);
			return node;
		}
	}

}
