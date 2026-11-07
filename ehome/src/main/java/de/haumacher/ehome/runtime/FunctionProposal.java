/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.blocks.SunShading;
import de.haumacher.ehome.importer.ProgramLanguages;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeCatalog;
import de.haumacher.ehome.zigbee.ZigbeeFunctionTable;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeStatus;

/**
 * Proposes the functions (<code>ehome.runtime:Function</code>) of a project from its device channels
 * (haumacher/ehome #43): what the user then edits.
 *
 * <p>
 * <b>Candidates, linked by group addresses</b> (haumacher/ehome #98). Every channel of every device
 * gives its candidate from its own objects, without looking at the group addresses beyond their
 * datapoint types: an actuator channel a light, a switch, a dimmer, a blind or a climate with its
 * roles, a key channel a button, a measured value a sensor (the kinds below). Then the candidates are
 * linked: candidates sharing a group address that is the sending address (the first one, connection
 * kind <code>send</code>) of an object taking a role in each are one function ({@link #link(List)}).
 * A secondary address links nothing: a central address received by every channel does not merge
 * them, nor does the status address a toggle key listens to. The key "Taste 1" sending on the
 * address the actuator's channel receives as its first and the channel are one function; so are two
 * actuators switched on one address. The receiving side decides the kind, its candidate brings the
 * roles (its state, position and so on from its own channel); a key whose sending addresses no
 * candidate receives as its first stays a button (a central address, an address only the controller
 * reacts to). Each function is named after the group address of its main role, the same text in
 * every application language ("KanalA", "Raumtemperatur Wohnzimmer EG Tür"). Scenes are not linked.
 * </p>
 *
 * <p>
 * <b>Unused functions.</b> A channel none of whose objects connected to a group address gives a
 * candidate is classified again with all its objects in effect, connected or not: a candidate whose
 * main object has no group address is an unused function, named "&lt;device&gt;: &lt;channel&gt;"
 * ("AKK-04 16.03: Channel D"), with its device, channel and kind but no role bound. "Unused" is no
 * attribute of its own: it follows from the content ({@link #unused(TLObject)}, the derived attribute
 * <code>Function#unused</code>). The runtime image leaves unused functions out without refusing them
 * and its summary counts them; they are a hint where the installation is incomplete. A channel
 * whose connected objects give a candidate gives no unused one besides (the actuator channel only
 * switched, whose dimming objects are connected to nothing, is a light).
 * </p>
 *
 * <p>
 * <b>Channels.</b> A channel of a device is a channel of its application program, where the program
 * has several numbered ones; otherwise (a program with one generic channel, or with its objects
 * independent of channels, as most programs written before ETS 5) the communication objects sharing
 * a name, the way ETS lists them ("Kanal A", "Taste 1"). An object counts with its sending address
 * (the first one) and a datapoint type: the one of the address in effect, for an object connected
 * to none its own (of the project, its placement or the object) or the main type its size decides.
 * </p>
 *
 * <p>
 * <b>The kind</b> of a channel follows from the datapoint types of its objects, their direction and
 * their names (German and English words), tried in this order:
 * </p>
 * <ol>
 * <li><code>blind</code>: an input of DPT 1.008 (up/down) moves; 1.007/1.010 stops; 5.001 objects
 * are the position or, named slat/Lamelle, the slats; inputs set, outputs report.</li>
 * <li><code>dimmer</code>: an input of DPT 5.001 sets the level; a 5.001 output reports it; a 1.001
 * input switches, a state (below) reports; a 3.007 input dims relatively (the role
 * <code>step</code>).</li>
 * <li><code>climate</code>: an output of DPT 9.001 (the temperature) together with a setpoint
 * (9.001 named Soll/setpoint) or an operating mode (20.102); an output named Heiz/heat is heating.</li>
 * <li><code>light</code>, or <code>switch</code> where the channel is named for a socket
 * (Steckdose/socket/outlet): an input of DPT 1.001 (switch) is the command; an output of DPT 1.011
 * (state), or a truth value named Status/state/Rückmeldung, is the state. A channel named
 * central/Zentral (a central object commanding several channels) gets no function. Without a 1.001
 * input, an input whose address has no datapoint type but the main type 1 its size decides (1 bit,
 * {@link GroupAddressTypes#bySize(TLObject)}) is the command where the rest of the channel says it
 * switches: the channel has a state, or it is named for a switched output
 * (Schalten/switch/Licht/light/Leuchte/Lampe/Steckdose/socket/Ausgang/output/Relais/relay), and is
 * no key; an input named for a lock (Sperre/lock/block) is never the command. Such an untyped
 * input is the switching command of a dimmer as well. (A push-button sensor's status LEDs, inputs
 * of 1 bit named "Status-LED 1", stay without a function.)</li>
 * <li><code>button</code>: an output of a truth value in a channel named for a key
 * (Taste/Taster/Wippe/button/rocker/push) is the press. A function is built from the receiving
 * side (haumacher/ehome #92): a key linked with an actuator's channel is an operating element of
 * that function ("Taste 1" switching the light "KanalA"; a dismissed light does not turn its key
 * into a button), and a key channel gives no button either where any address one of its objects
 * sends on is a command role ({@link FunctionKinds#COMMAND_ROLES}) of a function of another device
 * the project has (one the user made); the buttons are therefore decided after all other functions
 * of the run. A key sending on an address only central objects without a function or other keys'
 * status LEDs receive stays a button: nothing else represents it on the controller. A button the
 * project has already stays as well; {@link #redundancy(TLObject)} says why it is redundant.</li>
 * <li><code>sensor</code>, one per object: an output of a measured value (DPT 9.x, 14.x) or of an
 * alarm, open/closed, occupancy or window contact (1.005, 1.009, 1.018, 1.019). Where the channel has
 * several, each is keyed by its object (a dismissal of the whole channel, as earlier proposals made
 * it, still holds).</li>
 * </ol>
 * <p>
 * Besides the kind of its channel, a scene input of a channel (DPT 17.001 scene number or 18.001
 * scene control) gives a <code>scene</code> function for each scene number the project gives the
 * input: a parameter in effect with the device's values whose text names a scene number
 * (Szenennummer/scene number), in the smallest part of the program's structure around the scene
 * input that has one (its channel, parameter block or case; the actuator of the test system keeps
 * the scene object and the active scenes' numbers in the block "Scenes" of each channel), unless
 * that part holds another object's scene input as well. The number is the one its value's text
 * shows (an enumeration such as "1" ... "64" for the codes 0 ... 63) or its value (a numeric
 * parameter counting from 1, or from 0 up to 63 as the datapoint does). A scene input without such
 * a parameter gives no function: the scene number is mandatory in the runtime image, and so is its
 * group address. Scene functions are named after the channel and the number ("Channel A: scene 1"),
 * in the languages of the device's program.
 * </p>
 *
 * <p>
 * An input is an object receiving from its address (write flag, see
 * {@link DatapointSettings#receives(TLObject)}) whose datapoint is writable; an output transmits on
 * it. An object connected to no address is an input where its write flag is set and its transmit
 * flag is not, an output where its transmit flag is set. Where several objects fit a role, the one
 * on the lowest group address takes it, one without an address after all others. Objects fitting no
 * role (locks, logic, scenes, status LEDs of the device itself) are ignored and link nothing. A
 * channel whose objects fit no kind gets no function.
 * </p>
 *
 * <p>
 * Each function gets an identifier derived from its name and a room (haumacher/ehome #93): the
 * location of most of its senders, the devices with an object sending
 * (transmit flag, on its sending address) on the address of the function's main role or of one of
 * its command roles ({@link FunctionKinds#COMMAND_ROLES}); each device counts once, at its location,
 * one without a location not at all. Status addresses, which the actuator sends itself, do not
 * count. A light is thus in the room of the keys switching it, not in the distribution board of
 * its actuator; a sensor and a button are where their device is. Between locations with as many
 * senders, the one first in the order of the building structure (depth-first, haumacher/ehome #71)
 * wins. Without a placed sender, the function is in the location of its device, unless that is a
 * distribution board (then it has no room); an unused function has no senders, so it is there. The
 * name of a scene or an unused function has a text for each application language the device's
 * program has texts in (<code>Program#languages</code>, haumacher/ehome #51): a program written in
 * German only gives a German name, not the German text standing in for English. The kind is decided
 * on the texts of all these languages, so it does not depend on the language of the user.
 * </p>
 *
 * <p>
 * The proposal adds a function only for a channel no function of the project was proposed for, and
 * none whose main role an existing function of the same kind already binds (a scene: the same
 * address with the same scene number), nor a second one for a main role another proposal of the same
 * run binds. The only change it makes to a function the project has (haumacher/ehome #98): the
 * existing function of a proposal (of the same kind, proposed for one of its linked channels or
 * binding its main role) that is unused gets the roles of the proposal it has not bound, once its
 * channel is linked; and one whose name is still a generated one is renamed after the group address
 * of its main role. A name is generated where it reads, in every application language, as the
 * unused function's name the proposal gives the channel now, or as the channel's name earlier
 * proposals gave it ("Kanal H"); a name the user edited stays, as do the roles a function binds (an
 * unused function renamed gets the identifier of its new name as well, a function in use keeps its
 * identifier, which the controller knows). Running it again therefore adds and changes nothing. A
 * proposed function the user
 * deletes is remembered as dismissed for its channel and kind (<code>Project#dismissedProposals</code>,
 * #49, {@link #dismiss(TLObject)}), and the proposal does not bring it back until the dismissal is
 * deleted.
 * </p>
 *
 * <p>
 * <b>Zigbee datapoints</b> (haumacher/ehome #68). An application datapoint of the project bound to a
 * function of one of its Zigbee devices (<code>ApplicationDatapoint#zigbeeFunction</code>) is proposed
 * as well, by the key of the function: <code>temperature</code>, <code>humidity</code>,
 * <code>illuminance</code>, <code>occupancy</code>, <code>battery</code> and <code>ias_zone</code>
 * give a <code>sensor</code> (role <code>value</code>); <code>on_off</code> gives a
 * <code>switch</code>, a <code>light</code> where the device's type in the Zigbee catalog is named for
 * a light (its name or the name of the function, Licht/light/Leuchte/Lampe/lamp/bulb), with the
 * datapoint as command and state; <code>on_off</code> with <code>level</code> on the same endpoint
 * gives a <code>dimmer</code> (the level datapoint as level command and level, the on/off datapoint as
 * command and state). A command role needs a writable datapoint, as the controller says: an on/off
 * datapoint that is not writable gives no function, a level datapoint that is not writable no dimmer.
 * The function <code>action</code> of a remote gives a <code>button</code> named for the device, its
 * enumeration datapoint (off, on, toggle, dim up, dim down, dim stop) as the press, which the image
 * gives the remote's <code>presses</code> (homecontrol <code>spec/runtime-image.md</code> 3.5, schema
 * 1.4; haumacher/ehome #70); not where the device's type in the catalog hides the action (the
 * controller endpoint of a Hue motion sensor is no button), and not for a datapoint of another type.
 * Only datapoints the project has bound are proposed; nothing is created for an unbound Zigbee
 * function.
 * </p>
 *
 * <p>
 * Such a function is named after the device and the function ("Flur Bewegung: Temperatur"), the
 * function by the name the catalog gives it, else the name of its key, in each application language;
 * a dimmer after the device alone, as a KNX dimmer is named after its channel. Where the device has
 * the function key (for a dimmer on/off or level) on more than one endpoint, bound or not, the name
 * ends with the endpoint ("Doppelsteckdose: Ein/Aus (Endpunkt 2)"), the number the controller's
 * Zigbee page shows. The function is placed in the room of the device. It has no <code>device</code> (which is a KNX device); its
 * <code>channel</code> is {@link #ZIGBEE_CHANNEL} followed by the device's IEEE address, the endpoint
 * and the function key (of the on/off function for a dimmer), which a new import of the controller's
 * export keeps, so that a dismissal of it stays as the dismissal of a KNX channel does. A Zigbee
 * function is not proposed where an existing function (or one proposed before it in the same run)
 * uses one of its datapoints in any role.
 * </p>
 */
public final class FunctionProposal {

	private static final Pattern SLAT = words("lamell", "slat");

	private static final Pattern STATE = words("status", "state", "rückmeld", "feedback", "zustand");

	private static final Pattern SETPOINT = words("soll", "setpoint");

	private static final Pattern HEATING = words("heiz", "heat");

	private static final Pattern KEY = words("taste", "taster", "wippe", "button", "rocker", "push");

	private static final Pattern CENTRAL = words("zentral", "central");

	private static final Pattern SOCKET = words("steckdose", "socket", "outlet");

	private static final Pattern SWITCHED = words("schalt", "switch", "licht", "light", "leuchte", "lampe", "lamp",
		"steckdose", "socket", "outlet", "ausgang", "output", "relais", "relay");

	private static final Pattern LOCK = words("sperr", "lock", "block");

	private static final Pattern LIGHT = words("licht", "light", "leuchte", "lampe", "lamp", "bulb");

	/**
	 * The beginning of the channel of a function proposed for a Zigbee datapoint, followed by the
	 * device's IEEE address, the endpoint and the function key, separated by colons.
	 */
	public static final String ZIGBEE_CHANNEL = "zigbee:";

	/**
	 * The beginning of the channel of a function proposed for a virtual device (haumacher/ehome #75),
	 * followed by the instance's identifier, which never changes: the stable key of its dismissal.
	 */
	public static final String BLOCK_CHANNEL = "block:";

	/** The Zigbee function keys giving a sensor. */
	private static final Set<String> ZIGBEE_SENSORS =
		Set.of("temperature", "humidity", "illuminance", "occupancy", "battery", "ias_zone");

	private static final String ON_OFF = "on_off";

	private static final String LEVEL = "level";

	private static final Pattern SCENE_NUMBER =
		Pattern.compile("(?<![\\p{L}])(szenennummer|szenen-nummer|(scene|szene)\\s*(number|nummer|no\\b|nr\\b))");

	private static final Pattern NUMBER = Pattern.compile("^\\D*?(\\d{1,2})\\D*$");

	/**
	 * A communication object of a channel with the group address it sends on (its first address),
	 * <code>null</code> for an object connected to no address.
	 */
	private record Obj(Interface placed, TLObject connection, TLObject address, Dpt dpt, String type, boolean input,
			boolean output, String text, Map<String, Resources> languages) {

		int addressValue() {
			return address != null && address.tValueByName("address") instanceof GA ga ? ga.getValue()
				: Integer.MAX_VALUE;
		}

		int number() {
			return placed.getPort().getIndex();
		}

		boolean is(int main, int sub) {
			return dpt.main() == main && dpt.sub() == sub;
		}

		boolean bool() {
			return ValueTypes.BOOLEAN.equals(type);
		}

		/**
		 * Whether the address has no datapoint type but the given main type its objects' size
		 * decides.
		 */
		boolean untyped(int main) {
			return dpt.isMainType() && dpt.main() == main;
		}

		boolean scene() {
			return dpt.main() == 17 || dpt.main() == 18;
		}

		boolean named(Pattern pattern) {
			return pattern.matcher(text).find();
		}
	}

	/**
	 * The objects of one channel of a device.
	 */
	private record Channel(String key, Map<String, String> label, List<Obj> objects) {
		boolean named(Pattern pattern) {
			return pattern.matcher(String.join(" ", label.values()).toLowerCase(Locale.ROOT)).find();
		}
	}

	/**
	 * A proposed function.
	 *
	 * @param kind
	 *        The function kind.
	 * @param label
	 *        Its name by language (<code>de</code>, <code>en</code>), in the languages of the
	 *        device's program.
	 * @param roles
	 *        The group addresses by role, in the order of the kind's roles.
	 * @param device
	 *        The device it was proposed for: a KNX device (<code>ehome.physical:Device</code>) or a
	 *        Zigbee device (<code>ehome.zigbee:ZigbeeDevice</code>).
	 * @param channel
	 *        The key of the channel it was proposed for; for a scene function the key of the channel
	 *        with the scene number; for a Zigbee datapoint its {@link FunctionProposal#ZIGBEE_CHANNEL}
	 *        key.
	 * @param scene
	 *        The scene number of a scene function, <code>null</code> for another kind.
	 * @param sends
	 *        For a button of a key channel, the group addresses the channel's objects send on (each
	 *        object's sending address); empty for another function.
	 * @param members
	 *        For a function of KNX device channels, the candidates linked into it (haumacher/ehome
	 *        #98), the one giving its kind first; empty for another function.
	 */
	public record Proposal(String kind, Map<String, String> label, Map<String, TLObject> roles, TLObject device,
			String channel, Integer scene, List<TLObject> sends, List<Member> members) {

		/**
		 * A proposal of no KNX device channel: a Zigbee datapoint, a virtual device.
		 */
		public Proposal(String kind, Map<String, String> label, Map<String, TLObject> roles, TLObject device,
				String channel, Integer scene, List<TLObject> sends) {
			this(kind, label, roles, device, channel, scene, sends, List.of());
		}

		/**
		 * The name as multilingual text.
		 */
		public ResKey name() {
			return FunctionProposal.text(label);
		}

		/**
		 * Whether the proposed function is unused: its main role has no datapoint (a candidate of a
		 * channel whose main object has no group address, haumacher/ehome #98).
		 */
		public boolean unused() {
			return roles.get(FunctionKinds.mainRole(kind)) == null;
		}
	}

	/**
	 * A candidate of a device channel linked into a proposed function (haumacher/ehome #98).
	 *
	 * @param device
	 *        The KNX device (<code>ehome.physical:Device</code>).
	 * @param channel
	 *        The key of the channel (<code>Function#channel</code>).
	 * @param aliases
	 *        Further keys a dismissal of the channel may carry: the key of the whole channel for a
	 *        sensor of a channel with several measured values, which earlier proposals shared.
	 * @param generated
	 *        The names the proposal gives or gave a function of the candidate: the name of an unused
	 *        function ("AKK-04 16.03: Channel D") and the name of its channel, as proposals before
	 *        haumacher/ehome #98 named it ("Kanal H"); a function with one of them is renamed after its
	 *        group address.
	 */
	public record Member(TLObject device, String channel, List<String> aliases, List<Map<String, String>> generated) {
		// A record.
	}

	private FunctionProposal() {
		// Static utility.
	}

	/**
	 * Adds the proposed functions the project lacks, and updates the existing ones of the proposals
	 * ({@link #run(TLObject)}); must run in a transaction.
	 *
	 * <p>
	 * The buttons are decided last, after all other functions of the run (haumacher/ehome #92): a
	 * button gives no function where one of the group addresses its keys send on is a command role
	 * ({@link FunctionKinds#COMMAND_ROLES}) of a function of another device, one the project has or
	 * one this run proposes (added or not: a dismissed light does not turn its key into a button);
	 * the key is then an operating element of that function. A key linked with an actuator's channel
	 * is part of the channel's function anyway (haumacher/ehome #98).
	 * </p>
	 *
	 * @return The functions added.
	 * @see #run(TLObject)
	 */
	public static List<TLObject> apply(TLObject project) {
		return run(project).added();
	}

	/**
	 * What a run of the proposal did.
	 *
	 * @param added
	 *        The functions added.
	 * @param updated
	 *        The existing functions the run changed: an unused function whose channel got its group
	 *        address, whose roles it filled, or a function with a generated name it renamed after the
	 *        group address of its main role (haumacher/ehome #98).
	 */
	public record Run(List<TLObject> added, List<TLObject> updated) {
		// A record.
	}

	/**
	 * Adds the proposed functions the project lacks and updates the existing functions of the
	 * proposed ones (see the class comment); must run in a transaction.
	 */
	public static Run run(TLObject project) {
		List<TLObject> existing = functions(project);
		Decision decision = new Decision();
		for (TLObject function : existing) {
			String identity = identity(function.tValueByName("device"), function.tValueByName("channel"));
			String kind = kind(function);
			if (identity != null) {
				decision._proposedChannels.add(identity);
				decision._byChannel.putIfAbsent(kind + " " + identity, function);
			}
			String main = FunctionKinds.mainRole(kind);
			if (main != null && function.tValueByName(main) instanceof TLObject address) {
				String key = mainKey(kind, address, function.tValueByName("scene"));
				decision._boundMainRoles.add(key);
				decision._byMainRole.putIfAbsent(key, function);
			}
			for (String role : FunctionKinds.roles(kind)) {
				if (function.tValueByName(role) instanceof TLObject datapoint) {
					decision._usedDatapoints.add(datapoint);
				}
			}
			for (String role : FunctionKinds.commandRoles(kind)) {
				if (function.tValueByName(role) instanceof TLObject datapoint) {
					decision.commands(datapoint, function.tValueByName("device"));
				}
			}
			if (function.tValueByName("identifier") != null) {
				decision._identifiers.add(function.tValueByName("identifier").toString());
			}
		}

		for (TLObject dismissal : list(project, DISMISSED)) {
			String identity = identity(dismissal.tValueByName("device"), dismissal.tValueByName("channel"));
			if (identity != null) {
				decision._dismissed.add(kind(dismissal) + " " + identity);
			}
		}

		List<Proposal> proposals = propose(project);
		boolean[] accepted = new boolean[proposals.size()];
		Set<TLObject> updated = new LinkedHashSet<>();
		for (int n = 0; n < proposals.size(); n++) {
			Proposal proposal = proposals.get(n);
			if (proposal.sends().isEmpty()) {
				TLObject known = decision.existing(proposal);
				if (known != null) {
					if (update(known, proposal, decision._identifiers)) {
						updated.add(known);
					}
					decision.bound(proposal);
				} else {
					accepted[n] = decision.accept(proposal);
				}
				// Added or not (dismissed, bound already): the key operates the actuator's channel.
				for (String role : FunctionKinds.commandRoles(proposal.kind())) {
					TLObject datapoint = proposal.roles().get(role);
					if (datapoint != null) {
						decision.commands(datapoint, proposal.device());
					}
				}
			}
		}
		for (int n = 0; n < proposals.size(); n++) {
			Proposal proposal = proposals.get(n);
			if (proposal.sends().isEmpty()) {
				continue;
			}
			TLObject known = decision.existing(proposal);
			if (known != null) {
				if (update(known, proposal, decision._identifiers)) {
					updated.add(known);
				}
				decision.bound(proposal);
			} else if (!decision.commandedElsewhere(proposal) && decision.accept(proposal)) {
				accepted[n] = true;
			}
		}

		Map<TLObject, Integer> buildingOrder = new HashMap<>();
		for (TLObject part : RuntimeFunctions.buildingParts(project)) {
			buildingOrder.putIfAbsent(part, Integer.valueOf(buildingOrder.size()));
		}
		List<TLObject> added = new ArrayList<>();
		for (int n = 0; n < proposals.size(); n++) {
			if (accepted[n]) {
				added.add(create(proposals.get(n), decision._identifiers, buildingOrder));
			}
		}
		if (!added.isEmpty()) {
			List<TLObject> all = new ArrayList<>(existing);
			all.addAll(added);
			project.tUpdateByName("functions", all);
		}
		return new Run(added, new ArrayList<>(updated));
	}

	/**
	 * Updates an existing function of a proposal (haumacher/ehome #98): an unused function gets the
	 * roles of the proposal it has not bound, once its channel has its group address; a function
	 * whose name is still a generated one ({@link Member#generated()}, compared in every application
	 * language) is renamed after the group address of the proposal's main role, an unused one gets
	 * the identifier of the new name as well (the controller never had it). A name the user edited
	 * and the roles the function binds stay.
	 *
	 * @return Whether the function was changed.
	 */
	private static boolean update(TLObject function, Proposal proposal, Set<String> identifiers) {
		if (proposal.unused() || proposal.members().isEmpty()) {
			return false;
		}
		String kind = kind(function);
		if (!proposal.kind().equals(kind)) {
			return false;
		}
		boolean changed = false;
		boolean wasUnused = unused(function);
		if (wasUnused) {
			for (Map.Entry<String, TLObject> role : proposal.roles().entrySet()) {
				if (function.tValueByName(role.getKey()) == null) {
					function.tUpdateByName(role.getKey(), role.getValue());
					changed = true;
				}
			}
		}
		ResKey name = proposal.name();
		Object current = function.tValueByName("name");
		if (name != null && !sameText(current, name) && generatedName(function, proposal)) {
			function.tUpdateByName("name", name);
			if (wasUnused) {
				Object identifier = function.tValueByName("identifier");
				if (identifier != null) {
					identifiers.remove(identifier.toString());
				}
				function.tUpdateByName("identifier",
					uniqueIdentifier(Resources.getInstance().getString(name), identifiers));
			}
			changed = true;
		}
		return changed;
	}

	/**
	 * Whether the name of the function is one the proposal generates or generated for the member
	 * of the proposal the function was proposed for (its device and channel), compared in every
	 * application language.
	 */
	private static boolean generatedName(TLObject function, Proposal proposal) {
		Object device = function.tValueByName("device");
		Object channel = function.tValueByName("channel");
		for (Member member : proposal.members()) {
			if (member.device() == device && member.channel().equals(channel)) {
				for (Map<String, String> generated : member.generated()) {
					if (!generated.isEmpty() && sameText(function.tValueByName("name"), text(generated))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Whether the given name reads as the given text in every application language.
	 */
	private static boolean sameText(Object name, ResKey text) {
		if (!(name instanceof ResKey key)) {
			return false;
		}
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			Resources resources = Resources.getInstance(locale);
			if (!String.valueOf(resources.getString(key, null)).equals(String.valueOf(resources.getString(text, null)))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether the given function is unused (haumacher/ehome #98): one the proposal made (it has a
	 * channel: of a KNX device, a Zigbee datapoint or a virtual device) whose main role has no
	 * datapoint, as the proposal makes it for a channel whose main object has no group address. The
	 * runtime image leaves it out. A function the user made without its main role is no unused one
	 * but incomplete: the controller refuses it, and says why.
	 */
	public static boolean unused(TLObject function) {
		String main = FunctionKinds.mainRole(kind(function));
		return main != null && function.tValueByName("channel") != null && function.tValueByName(main) == null;
	}

	/**
	 * What decides whether a proposal of a run of {@link FunctionProposal#apply(TLObject)} is
	 * added: what the project's functions and the proposals added before bind.
	 */
	private static final class Decision {

		final Set<String> _proposedChannels = new HashSet<>();

		final Set<String> _boundMainRoles = new HashSet<>();

		/** The functions of the project by their kind and channel ({@link #identity(Object, Object)}). */
		final Map<String, TLObject> _byChannel = new HashMap<>();

		/** The functions of the project by what they bind with their main role ({@link #mainKey}). */
		final Map<String, TLObject> _byMainRole = new HashMap<>();

		/**
		 * The function of the project the given proposal of KNX device channels stands for: the one
		 * of the same kind proposed for one of the linked channels, else one of the same kind binding
		 * the proposal's main role (a function of an earlier run for another channel of the
		 * function); <code>null</code> for none.
		 */
		TLObject existing(Proposal proposal) {
			for (Member member : proposal.members()) {
				TLObject function = _byChannel.get(proposal.kind() + " " + identity(member.device(), member.channel()));
				if (function != null) {
					return function;
				}
			}
			if (proposal.members().isEmpty() || proposal.unused()) {
				return null;
			}
			TLObject main = proposal.roles().get(FunctionKinds.mainRole(proposal.kind()));
			return _byMainRole.get(mainKey(proposal.kind(), main, proposal.scene()));
		}

		final Set<TLObject> _usedDatapoints = new HashSet<>();

		final Set<String> _identifiers = new HashSet<>();

		final Set<String> _dismissed = new HashSet<>();

		/**
		 * The datapoints bound to a command role, each with the devices of the functions binding it
		 * (<code>null</code> for a function without a device).
		 */
		final Map<TLObject, List<Object>> _commanded = new HashMap<>();

		/**
		 * Records what the proposal of an existing function binds with its main role.
		 */
		void bound(Proposal proposal) {
			if (!proposal.unused()) {
				_boundMainRoles.add(mainKey(proposal.kind(), proposal.roles().get(FunctionKinds.mainRole(proposal.kind())),
					proposal.scene()));
			}
		}

		void commands(TLObject datapoint, Object device) {
			_commanded.computeIfAbsent(datapoint, d -> new ArrayList<>()).add(device);
		}

		/**
		 * Whether one of the addresses the key channel of the button sends on is a command role of a
		 * function of another device.
		 */
		boolean commandedElsewhere(Proposal button) {
			for (TLObject address : button.sends()) {
				for (Object device : _commanded.getOrDefault(address, List.of())) {
					if (device != button.device()) {
						return true;
					}
				}
			}
			return false;
		}

		/**
		 * Whether the proposal is added: no function was proposed for its channel, it is not
		 * dismissed, and no function binds what it binds; records what it binds if so.
		 */
		boolean accept(Proposal proposal) {
			String identity = identity(proposal.device(), proposal.channel());
			if (_proposedChannels.contains(identity)) {
				return false;
			}
			if (_dismissed.contains(proposal.kind() + " " + identity)) {
				return false;
			}
			for (Member member : proposal.members()) {
				for (String alias : member.aliases()) {
					if (_dismissed.contains(proposal.kind() + " " + identity(member.device(), alias))) {
						return false;
					}
				}
			}
			if (proposal.unused()) {
				// Nothing bound: one function for the channel.
				return _proposedChannels.add(identity);
			}
			TLObject main = proposal.roles().get(FunctionKinds.mainRole(proposal.kind()));
			if (zigbee(proposal.channel()) || proposal.channel().startsWith(BLOCK_CHANNEL)) {
				if (proposal.roles().values().stream().anyMatch(_usedDatapoints::contains)) {
					return false;
				}
				if (proposal.channel().startsWith(BLOCK_CHANNEL)
					&& !_boundMainRoles.add(mainKey(proposal.kind(), main, proposal.scene()))) {
					// A sun shading's blind moves the actuator whose channel was proposed a blind already.
					return false;
				}
				_usedDatapoints.addAll(proposal.roles().values());
				return true;
			}
			return _boundMainRoles.add(mainKey(proposal.kind(), main, proposal.scene()));
		}
	}

	/**
	 * Why the given function is redundant on the controller, as text in the user's language;
	 * <code>null</code> if it is not (haumacher/ehome #92): a button whose press is bound to a command
	 * role of another function of the project (of another device, where both have one), which the
	 * proposal would not have added. The text names the address, the role and the function.
	 */
	public static String redundancy(TLObject function) {
		if (!"button".equals(kind(function)) || !(function.tValueByName("press") instanceof TLObject press)
			|| !(function.tContainer() instanceof TLObject project)) {
			return null;
		}
		Object device = function.tValueByName("device");
		for (TLObject other : functions(project)) {
			if (other == function || (device != null && other.tValueByName("device") == device)) {
				continue;
			}
			for (String role : FunctionKinds.commandRoles(kind(other))) {
				if (other.tValueByName(role) == press) {
					Resources resources = Resources.getInstance();
					return resources.getString(I18NConstants.BUTTON_REPEATS_COMMAND__ADDRESS_ROLE_FUNCTION.fill(
						MetaLabelProvider.INSTANCE.getLabel(press),
						MetaLabelProvider.INSTANCE.getLabel(other.tType().getPart(role)),
						other.tValueByName("name") instanceof ResKey name ? resources.getString(name)
							: String.valueOf(other.tValueByName("identifier"))));
				}
			}
		}
		return null;
	}

	/**
	 * Deletes the given function of its project; must run in a transaction. A function the proposal
	 * made (one with a device and a channel, or with the channel of a Zigbee datapoint) is remembered
	 * as dismissed, so the proposal does not bring it back: a {@link #DISMISSED dismissal} with its
	 * name, kind, device and channel, unless the project has one for them already.
	 *
	 * @return The dismissal recorded, <code>null</code> for none.
	 */
	public static TLObject dismiss(TLObject function) {
		TLObject project = function.tContainer();
		TLObject result = null;
		Object device = function.tValueByName("device");
		String identity = identity(device, function.tValueByName("channel"));
		if (project != null && identity != null && function.tValueByName("kind") != null) {
			boolean known = false;
			for (TLObject dismissal : list(project, DISMISSED)) {
				known |= identity.equals(identity(dismissal.tValueByName("device"), dismissal.tValueByName("channel")))
					&& dismissal.tValueByName("kind") == function.tValueByName("kind");
			}
			if (!known) {
				result = ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.runtime:DismissedProposal"));
				result.tUpdateByName("name", function.tValueByName("name"));
				result.tUpdateByName("kind", function.tValueByName("kind"));
				result.tUpdateByName("device", device);
				result.tUpdateByName("channel", function.tValueByName("channel"));
				List<TLObject> dismissals = list(project, DISMISSED);
				dismissals.add(result);
				project.tUpdateByName(DISMISSED, dismissals);
			}
		}
		function.tDelete();
		return result;
	}

	/**
	 * The attribute of a project holding the dismissed proposals.
	 */
	public static final String DISMISSED = "dismissedProposals";

	/**
	 * The functions of the project.
	 */
	public static List<TLObject> functions(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		if (project.tValueByName("functions") instanceof Collection<?> list) {
			for (Object element : list) {
				result.add((TLObject) element);
			}
		}
		return result;
	}

	/**
	 * The name of the kind of the given function, <code>null</code> for none.
	 */
	public static String kind(TLObject function) {
		return function.tValueByName("kind") instanceof TLClassifier kind ? kind.getName() : null;
	}

	/**
	 * What a function binds with its main role: the kind and the address, for a scene also the scene
	 * number.
	 */
	private static String mainKey(String kind, TLObject address, Object scene) {
		String key = kind + " " + address.tId();
		return "scene".equals(kind) && scene instanceof Number number ? key + " " + number.intValue() : key;
	}

	/**
	 * What identifies the channel a function was proposed for, across runs of the proposal: the
	 * device and the channel of a KNX device; the channel alone for a Zigbee datapoint (the device's
	 * IEEE address, the endpoint and the function key, which a new import of the Zigbee devices
	 * keeps); <code>null</code> for a function the proposal did not make.
	 */
	private static String identity(Object device, Object channel) {
		if (!(channel instanceof String text)) {
			return null;
		}
		if (zigbee(text) || text.startsWith(BLOCK_CHANNEL)) {
			return text;
		}
		return device instanceof TLObject knx && isA(knx, "ehome.physical:Device") ? knx.tId() + " " + text : null;
	}

	private static boolean zigbee(String channel) {
		return channel != null && channel.startsWith(ZIGBEE_CHANNEL);
	}

	private static TLObject create(Proposal proposal, Set<String> identifiers, Map<TLObject, Integer> buildingOrder) {
		TLObject function = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Function"));
		ResKey name = proposal.name();
		function.tUpdateByName("name", name);
		function.tUpdateByName("identifier", uniqueIdentifier(Resources.getInstance().getString(name), identifiers));
		function.tUpdateByName("kind", classifier(proposal.kind()));
		function.tUpdateByName("room", room(proposal, buildingOrder));
		if (isA(proposal.device(), "ehome.physical:Device")) {
			function.tUpdateByName("device", proposal.device());
		}
		function.tUpdateByName("channel", proposal.channel());
		if (proposal.scene() != null) {
			function.tUpdateByName("scene", proposal.scene());
		}
		for (Map.Entry<String, TLObject> role : proposal.roles().entrySet()) {
			function.tUpdateByName(role.getKey(), role.getValue());
		}
		return function;
	}

	/**
	 * The room of a proposed function: for a KNX channel the location of most of its senders (see the
	 * class comment, haumacher/ehome #93); for a Zigbee datapoint the location of its device, for a
	 * virtual device the room of its instance.
	 *
	 * @param buildingOrder
	 *        The parts of the project's buildings by their position in the building structure.
	 */
	private static Object room(Proposal proposal, Map<TLObject, Integer> buildingOrder) {
		TLObject device = proposal.device();
		if (BlockInstances.isInstance(device)) {
			return device.tValueByName("room");
		}
		if (!isA(device, "ehome.physical:Device")) {
			return device.tValueByName("location");
		}
		List<String> roles = new ArrayList<>();
		roles.add(FunctionKinds.mainRole(proposal.kind()));
		roles.addAll(FunctionKinds.commandRoles(proposal.kind()));
		Set<TLObject> senders = new HashSet<>();
		Map<TLObject, Integer> counts = new LinkedHashMap<>();
		for (String role : roles) {
			TLObject address = proposal.roles().get(role);
			if (address == null || !isA(address, "ehome.logical:GroupAddress")) {
				continue;
			}
			for (TLObject connection : GroupAddressConnections.connections(address)) {
				TLObject sender = GroupAddressConnections.device(connection);
				if (DatapointSettings.transmits(connection) && senders.add(sender)
					&& sender.tValueByName("location") instanceof TLObject location) {
					counts.merge(location, Integer.valueOf(1), Integer::sum);
				}
			}
		}
		if (!counts.isEmpty()) {
			return counts.entrySet().stream()
				.min(Comparator.comparing((Map.Entry<TLObject, Integer> e) -> -e.getValue().intValue())
					.thenComparing(e -> buildingOrder.getOrDefault(e.getKey(), Integer.valueOf(Integer.MAX_VALUE))))
				.get().getKey();
		}
		Object location = device.tValueByName("location");
		return location instanceof TLObject part && isA(part, "ehome.building:DistributionBoard") ? null : location;
	}

	/**
	 * The given texts by language as multilingual text, <code>null</code> for none.
	 */
	public static ResKey text(Map<String, String> texts) {
		if (texts.isEmpty()) {
			return null;
		}
		ResKey.Builder builder = ResKey.builder();
		for (Map.Entry<String, String> entry : texts.entrySet()) {
			builder.add(new Locale(entry.getKey()), entry.getValue());
		}
		return builder.build();
	}

	/**
	 * The texts of the application languages, each in its language: the given text for every
	 * language (a user's text, which has no language of its own).
	 */
	public static ResKey text(ResKey key) {
		Map<String, String> texts = new TreeMap<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			texts.put(locale.getLanguage(), Resources.getInstance(locale).getString(key));
		}
		return text(texts);
	}

	/**
	 * The classifier of the function kind of the given name.
	 */
	public static TLClassifier classifier(String kind) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:FunctionKind")).getClassifier(kind);
	}

	/**
	 * An identifier for the runtime image derived from the given name, not among the given ones (which
	 * it is added to).
	 */
	public static String uniqueIdentifier(String name, Set<String> identifiers) {
		String base = slug(name);
		String result = base;
		for (int n = 2; !identifiers.add(result); n++) {
			String suffix = "-" + n;
			result = (base.length() + suffix.length() > 32 ? base.substring(0, 32 - suffix.length()) : base) + suffix;
		}
		return result;
	}

	/**
	 * The name in letters, digits and hyphens (at most 32): "Kanal Ä 1" becomes "kanal-ae-1".
	 */
	public static String slug(String name) {
		String text = String.valueOf(name).toLowerCase(Locale.ROOT)
			.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
		text = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		text = text.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (text.length() > 32) {
			text = text.substring(0, 32).replaceAll("-+$", "");
		}
		return text.isEmpty() ? "function" : text;
	}

	/**
	 * The functions the channels of the project's devices suggest: their candidates linked by their
	 * sending group addresses ({@link #link(List)}, haumacher/ehome #98), in the order of their first
	 * candidate by device and channel, then the unused functions of the channels whose connected
	 * objects gave no candidate; followed by the functions its bound Zigbee datapoints suggest, by
	 * device and endpoint, and those of its virtual devices.
	 */
	public static List<Proposal> propose(TLObject project) {
		List<Proposal> candidates = new ArrayList<>();
		List<Proposal> unused = new ArrayList<>();
		for (TLObject device : devices(project)) {
			Map<String, Channel> all = allChannels(device);
			Set<String> classified = new HashSet<>();
			for (Channel channel : channels(device)) {
				int before = candidates.size();
				classify(device, channel, all.get(channel.key()), candidates, true);
				for (Proposal candidate : candidates.subList(before, candidates.size())) {
					if (candidate.scene() == null) {
						classified.add(channel.key());
					}
				}
			}
			for (Channel channel : all.values()) {
				if (!classified.contains(channel.key())) {
					unused(device, channel, unused);
				}
			}
		}
		List<Proposal> result = link(candidates);
		result.addAll(unused);
		Set<TLObject> datapoints = new HashSet<>(ApplicationDatapoints.of(project));
		for (TLObject device : ZigbeeImport.devices(project)) {
			zigbee(device, datapoints, result);
		}
		for (TLObject instance : BlockInstances.of(project)) {
			block(instance, result);
		}
		return result;
	}

	/**
	 * The function of a virtual device, keyed by its identifier (<code>block:&lt;id&gt;</code>):
	 * <ul>
	 * <li>A room controller (haumacher/ehome #75) is a climate function: its filtered temperature,
	 * its active setpoint, the comfort setpoint as the setpoint command and its heat demand as
	 * heating.</li>
	 * <li>A sun shading (haumacher/ehome #78) is a blind: the datapoints its outputs command are
	 * the blind it moves, so the function operates and shows them - its up/down output's datapoint
	 * moves (the role the blind cannot do without, a command the device needs writable, which the
	 * shading's own outputs are not), its position and slat outputs' datapoints are position and
	 * slat, each as state and command. Without a datapoint on the up/down output it proposes
	 * nothing; it has no stop. The inhabitant's commands reach the blind as the shading's do; a
	 * manual operation input of the shading bound to them holds the automatic off.</li>
	 * </ul>
	 */
	private static void block(TLObject instance, List<Proposal> result) {
		String id = BlockInstances.identifier(instance);
		if (id == null || BlockInstances.type(instance) == null) {
			return;
		}
		Object type = instance.tValueByName("blockType");
		String kind;
		Map<String, TLObject> roles = new LinkedHashMap<>();
		if (RoomController.TYPE.equals(type)) {
			kind = "climate";
			put(roles, "temperature", BlockInstances.port(instance, "filtered"));
			put(roles, "setpoint", BlockInstances.port(instance, "activeSetpoint"));
			put(roles, "setpointCommand", BlockInstances.parameter(instance, "comfort"));
			put(roles, "heating", BlockInstances.port(instance, "demand"));
		} else if (SunShading.TYPE.equals(type)) {
			kind = "blind";
			put(roles, "move", boundTo(instance, "upDown"));
			TLObject position = boundTo(instance, "position");
			put(roles, "position", position);
			put(roles, "positionCommand", position);
			TLObject slat = boundTo(instance, "slat");
			put(roles, "slat", slat);
			put(roles, "slatCommand", slat);
		} else {
			return;
		}
		if (!roles.containsKey(FunctionKinds.mainRole(kind))) {
			return;
		}
		Map<String, String> label = new TreeMap<>();
		Object labels = RuntimeImage.labels(instance.tValueByName("name"));
		if (labels instanceof Map<?, ?> map) {
			map.forEach((language, text) -> label.put((String) language, (String) text));
		} else if (labels instanceof String text) {
			label.put("en", text);
		} else {
			label.put("en", id);
		}
		result.add(new Proposal(kind, label, roles, instance, BLOCK_CHANNEL + id, null, List.of()));
	}

	/** The datapoint the output of the instance is bound to, <code>null</code> for none. */
	private static TLObject boundTo(TLObject instance, String output) {
		TLObject port = BlockInstances.port(instance, output);
		List<TLObject> bound = port == null ? List.of() : BlockInstances.bound(port);
		return bound.isEmpty() ? null : bound.get(0);
	}

	/**
	 * Adds the functions the datapoints bound to the functions of the Zigbee device suggest, endpoint
	 * by endpoint in the order of the device's functions.
	 *
	 * @param datapoints
	 *        The application datapoints of the project, the only ones proposed.
	 */
	private static void zigbee(TLObject device, Set<TLObject> datapoints, List<Proposal> result) {
		Map<Integer, Map<String, TLObject>> endpoints = new LinkedHashMap<>();
		Map<TLObject, TLObject> bound = new LinkedHashMap<>();
		Map<String, Set<Integer>> endpointsByKey = new TreeMap<>();
		for (TLObject function : list(device, "functions")) {
			if (!(function.tValueByName("endpoint") instanceof Number endpoint)
				|| !(function.tValueByName("function") instanceof String key)) {
				continue;
			}
			endpointsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(Integer.valueOf(endpoint.intValue()));
			TLObject datapoint = ZigbeeBindings.boundDatapoints(function).stream().filter(datapoints::contains)
				.findFirst().orElse(null);
			if (datapoint != null) {
				endpoints.computeIfAbsent(Integer.valueOf(endpoint.intValue()), e -> new LinkedHashMap<>())
					.putIfAbsent(key, function);
				bound.putIfAbsent(function, datapoint);
			}
		}
		for (Map.Entry<Integer, Map<String, TLObject>> endpoint : endpoints.entrySet()) {
			Map<String, TLObject> functions = endpoint.getValue();
			TLObject onOff = functions.get(ON_OFF);
			TLObject level = functions.get(LEVEL);
			for (Map.Entry<String, TLObject> entry : functions.entrySet()) {
				String key = entry.getKey();
				TLObject function = entry.getValue();
				TLObject datapoint = bound.get(function);
				Map<String, TLObject> roles = new LinkedHashMap<>();
				String kind;
				TLObject named = function;
				boolean shared = endpointsByKey.get(key).size() > 1;
				if (ZIGBEE_SENSORS.contains(key)) {
					kind = "sensor";
					roles.put("value", datapoint);
				} else if (function == onOff && ApplicationDatapoints.writable(datapoint)) {
					TLObject levelDatapoint = level == null ? null : bound.get(level);
					if (levelDatapoint != null && ApplicationDatapoints.writable(levelDatapoint)
						&& ValueTypes.isNumeric(ApplicationDatapoints.valueType(levelDatapoint))) {
						kind = "dimmer";
						// Named for the device, as a KNX dimmer is named for its channel.
						named = null;
						shared |= endpointsByKey.get(LEVEL).size() > 1;
						roles.put("levelCommand", levelDatapoint);
						roles.put("level", levelDatapoint);
					} else {
						kind = light(device, function) ? "light" : "switch";
					}
					roles.put("command", datapoint);
					roles.put("state", datapoint);
				} else if (ZigbeeFunctionTable.ACTION.equals(key)
					&& ValueTypes.ENUMERATION.equals(ApplicationDatapoints.valueType(datapoint))
					&& !ZigbeeCatalog.hidden(function)) {
					// A remote (haumacher/ehome #70): its action is the press of a button, named for the
					// device as a dimmer is; the image gives it its presses (RuntimeImage.ACTION_PRESSES).
					kind = "button";
					named = null;
					roles.put("press", datapoint);
				} else {
					// The level of a dimmer, an action the catalog hides or of another type, another key:
					// no function.
					continue;
				}
				result.add(new Proposal(kind, zigbeeLabel(device, named, shared ? endpoint.getKey() : null), roles, device,
					ZIGBEE_CHANNEL + device.tValueByName("ieee") + ":" + endpoint.getKey() + ":" + key, null, List.of()));
			}
		}
	}

	/**
	 * Whether the device's type in the Zigbee catalog says the on/off function switches a light: the
	 * type, or the catalog's name of the function, is named for one.
	 */
	private static boolean light(TLObject device, TLObject function) {
		TLObject type = ZigbeeCatalog.type(device);
		if (type == null) {
			return false;
		}
		TLObject entry = ZigbeeCatalog.entry(function);
		StringBuilder text = new StringBuilder();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			Resources resources = Resources.getInstance(locale);
			for (Object name : new Object[] { type.tValueByName("name"),
				entry == null ? null : entry.tValueByName("name") }) {
				if (name instanceof ResKey key) {
					text.append(' ').append(resources.getString(key, ""));
				}
			}
		}
		return LIGHT.matcher(text.toString().toLowerCase(Locale.ROOT)).find();
	}

	/**
	 * The name of a function proposed for a Zigbee datapoint in each application language: the
	 * device's name and the name of the function ("Flur Bewegung: Temperatur"), the device's name
	 * alone for a dimmer; followed by the endpoint where the device has the function on several
	 * endpoints ("Doppelsteckdose: Ein/Aus (Endpunkt 2)"), so that the names of its functions differ.
	 *
	 * @param function
	 *        The function the name is given by, <code>null</code> for the device alone.
	 * @param endpoint
	 *        The endpoint to name, <code>null</code> for none.
	 */
	private static Map<String, String> zigbeeLabel(TLObject device, TLObject function, Integer endpoint) {
		Map<String, Resources> languages = new TreeMap<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			languages.put(locale.getLanguage(), Resources.getInstance(locale));
		}
		Object deviceName = device.tValueByName("name");
		String prefix = String.valueOf(deviceName == null ? device.tValueByName("ieee") : deviceName);
		return texts(languages, r -> {
			String name = function == null ? prefix : prefix + ": " + ZigbeeStatus.functionLabel(function, r);
			if (endpoint == null) {
				return name;
			}
			// The endpoint is kept where the name is cut to the longest label of the image.
			int suffix = utf8(r.getString(I18NConstants.ZIGBEE_ENDPOINT__NAME_ENDPOINT.fill("", endpoint)));
			return r.getString(I18NConstants.ZIGBEE_ENDPOINT__NAME_ENDPOINT.fill(
				RuntimeImage.truncate(name, Math.max(0, ImageValidator.MAX_NAME - suffix)), endpoint));
		});
	}

	private static int utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8).length;
	}

	private static List<TLObject> devices(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject area : list(project, "areas")) {
			for (TLObject line : list(area, "lines")) {
				result.addAll(list(line, "devices"));
			}
		}
		result.addAll(list(project, "unassignedDevices"));
		result.sort(Comparator.comparing((TLObject d) -> DownloadImage.individualAddress(d),
			Comparator.nullsLast(Comparator.naturalOrder())));
		return result;
	}

	private static List<TLObject> list(TLObject obj, String attribute) {
		List<TLObject> result = new ArrayList<>();
		if (obj.tValueByName(attribute) instanceof Collection<?> values) {
			for (Object value : values) {
				result.add((TLObject) value);
			}
		}
		return result;
	}

	private static List<Channel> channels(TLObject device) {
		Map<String, Channel> channels = new LinkedHashMap<>();
		List<TLObject> connectors = list(device, "connectors");
		connectors.sort(Comparator.comparingInt(c -> ((Interface) c.tValueByName("port")).getPort().getIndex()));
		for (TLObject connector : connectors) {
			if (Boolean.FALSE.equals(connector.tValueByName("active"))) {
				continue;
			}
			Interface placed = (Interface) connector.tValueByName("port");
			TLObject connection = sendingConnection(connector);
			if (connection == null || !(connection.tValueByName("target") instanceof TLObject address)
				|| !DatapointSettings.communicates(connection)) {
				continue;
			}
			Dpt dpt = GroupAddressTypes.effective(address);
			if (dpt == null) {
				continue;
			}
			String type = ValueTypes.valueType(dpt.main(), dpt.isMainType() ? 0 : dpt.sub());
			if (type == null) {
				continue;
			}
			Map<String, Resources> languages = languages(placed);
			Map<String, String> objectName = texts(languages, r -> GroupAddressConnections.objectName(placed, r));
			TLObject programChannel = programChannel(placed);
			String key;
			Map<String, String> label;
			if (programChannel != null) {
				key = "channel:" + programChannel.tValueByName("extId");
				label = texts(languages, r -> channelLabel(programChannel, r));
			} else {
				// Keyed by a text that does not depend on the user's language.
				key = "object:" + GroupAddressConnections.objectName(placed,
					Resources.getInstance(ResourcesModule.getInstance().getFallbackLocale()));
				label = objectName;
			}
			boolean input = DatapointSettings.receives(connection) && DatapointSettings.writable(address);
			boolean output = DatapointSettings.transmits(connection);
			String text = (String.join(" ", objectName.values()) + " "
				+ String.join(" ", texts(languages, r -> GroupAddressConnections.function(connection, r)).values())
				+ " " + address.tValueByName("name")).toLowerCase(Locale.ROOT);
			channels.computeIfAbsent(key, k -> new Channel(k, label, new ArrayList<>()))
				.objects().add(new Obj(placed, connection, address, dpt, type, input, output, text, languages));
		}
		return new ArrayList<>(channels.values());
	}

	/**
	 * The channels of the device with all their objects in effect in its configuration, connected
	 * to a group address or not (haumacher/ehome #98), by their keys; an object whose connector is
	 * inactive, or whose datapoint type neither its address nor the object names nor its size
	 * decides, is left out. An object without an address is an input where its write flag is set and
	 * its transmit flag is not, an output where its transmit flag is set.
	 */
	private static Map<String, Channel> allChannels(TLObject device) {
		Map<String, Channel> channels = new LinkedHashMap<>();
		if (DeviceConfiguration.program(device) == null) {
			return channels;
		}
		Map<Object, TLObject> connectors = new HashMap<>();
		for (TLObject connector : list(device, "connectors")) {
			if (connector.tValueByName("port") instanceof Interface placed) {
				connectors.putIfAbsent(placed.getPort(), connector);
			}
		}
		List<Interface> objects = new ArrayList<>(new DeviceConfiguration(device).getActiveInterfaces().values());
		objects.sort(Comparator.comparingInt(placed -> placed.getPort().getIndex()));
		for (Interface placed : objects) {
			TLObject connector = connectors.get(placed.getPort());
			if (connector != null && Boolean.FALSE.equals(connector.tValueByName("active"))) {
				continue;
			}
			TLObject connection = connector == null ? null : sendingConnection(connector);
			TLObject address = connection != null && connection.tValueByName("target") instanceof TLObject target
				&& DatapointSettings.communicates(connection) ? target : null;
			Dpt dpt = address != null ? GroupAddressTypes.effective(address) : objectType(connector, placed);
			if (dpt == null) {
				continue;
			}
			String type = ValueTypes.valueType(dpt.main(), dpt.isMainType() ? 0 : dpt.sub());
			if (type == null) {
				continue;
			}
			boolean input;
			boolean output;
			if (address != null) {
				input = DatapointSettings.receives(connection) && DatapointSettings.writable(address);
				output = DatapointSettings.transmits(connection);
			} else {
				boolean communicates = DatapointSettings.enabled("communication", connector, placed);
				boolean transmits = DatapointSettings.enabled("transmission", connector, placed);
				input = communicates && DatapointSettings.enabled("canWrite", connector, placed) && !transmits;
				output = communicates && transmits;
			}
			Map<String, Resources> languages = languages(placed);
			Map<String, String> objectName = texts(languages, r -> GroupAddressConnections.objectName(placed, r));
			String key = channelKey(placed);
			Map<String, String> label = channelLabel(placed, languages, objectName);
			String text = (String.join(" ", objectName.values()) + " "
				+ String.join(" ", texts(languages, r -> connection != null
					? GroupAddressConnections.function(connection, r) : objectFunction(connector, placed, r)).values())
				+ (address == null ? "" : " " + address.tValueByName("name"))).toLowerCase(Locale.ROOT);
			channels.computeIfAbsent(key, k -> new Channel(k, label, new ArrayList<>()))
				.objects().add(new Obj(placed, address == null ? null : connection, address, dpt, type, input, output, text,
					languages));
		}
		return channels;
	}

	/**
	 * The datapoint type of an object connected to no group address: the one of its connector, its
	 * placement or the object, else the main type its size decides (1 bit: DPT 1, 4 bits: DPT 3);
	 * <code>null</code> for none.
	 */
	private static Dpt objectType(TLObject connector, Interface placed) {
		for (TLObject holder : new TLObject[] { connector, placed, placed.getPort() }) {
			List<TLObject> types = holder == null ? List.of() : list(holder, "datapointTypes");
			Dpt dpt = types.isEmpty() ? null : GroupAddressTypes.dpt(types.get(0));
			if (dpt != null) {
				return dpt;
			}
		}
		Integer size = placed.getSize();
		switch (size != null ? size.intValue() : placed.getPort().getSize()) {
			case 1:
				return Dpt.mainType(1);
			case 4:
				return Dpt.mainType(3);
			default:
				return null;
		}
	}

	/**
	 * The key of the channel of a placed object: its program channel, where the program has several,
	 * else the name of the object in the fallback language.
	 */
	private static String channelKey(Interface placed) {
		TLObject programChannel = programChannel(placed);
		if (programChannel != null) {
			return "channel:" + programChannel.tValueByName("extId");
		}
		// Keyed by a text that does not depend on the user's language.
		return "object:" + GroupAddressConnections.objectName(placed,
			Resources.getInstance(ResourcesModule.getInstance().getFallbackLocale()));
	}

	/**
	 * The name of the channel of a placed object in its program's languages: its program channel's,
	 * else the name of the object.
	 */
	private static Map<String, String> channelLabel(Interface placed, Map<String, Resources> languages,
			Map<String, String> objectName) {
		TLObject programChannel = programChannel(placed);
		return programChannel != null ? texts(languages, r -> channelLabel(programChannel, r)) : objectName;
	}

	private static TLObject sendingConnection(TLObject connector) {
		TLObject first = null;
		for (TLObject connection : list(connector, "connections")) {
			if (connection.tValueByName("kind") instanceof TLClassifier kind && "send".equals(kind.getName())) {
				return connection;
			}
			if (first == null) {
				first = connection;
			}
		}
		return first;
	}

	/**
	 * The numbered channel of the program the placement is in, where the program has several;
	 * <code>null</code> otherwise.
	 */
	private static TLObject programChannel(Interface placed) {
		for (TLObject node = placed.tContainer(); node != null; node = node.tContainer()) {
			if (isA(node, "ehome.operational:Channel")) {
				if (!(node.tValueByName("number") instanceof Number number) || number.intValue() <= 0) {
					return null;
				}
				TLObject program = node.tContainer();
				long channels = program == null ? 0
					: list(program, "contents").stream().filter(c -> isA(c, "ehome.operational:Channel")).count();
				return channels > 1 ? node : null;
			}
		}
		return null;
	}

	/**
	 * The application languages the texts of the program of the given placement are written in, with
	 * the resources of each: the languages the program records, all application languages for a
	 * program recording none.
	 */
	private static Map<String, Resources> languages(Interface placed) {
		Set<String> recorded = Set.of();
		for (TLObject node = placed.tContainer(); node != null; node = node.tContainer()) {
			if (isA(node, "ehome.operational:Program")) {
				recorded = ProgramLanguages.languages(node);
				break;
			}
		}
		Map<String, Resources> result = new TreeMap<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			if (recorded.isEmpty() || recorded.contains(locale.getLanguage())) {
				result.put(locale.getLanguage(), Resources.getInstance(locale));
			}
		}
		return result;
	}

	/**
	 * The texts the given function gives in the given languages, each without control characters and
	 * cut to the longest label of the runtime image; empty texts left out.
	 */
	private static Map<String, String> texts(Map<String, Resources> languages, Function<Resources, String> text) {
		Map<String, String> result = new TreeMap<>();
		for (Map.Entry<String, Resources> language : languages.entrySet()) {
			String value = text.apply(language.getValue());
			if (value != null) {
				value = value.replaceAll("\\p{Cntrl}", " ").trim();
				if (!value.isEmpty()) {
					result.put(language.getKey(), RuntimeImage.truncate(value, ImageValidator.MAX_NAME));
				}
			}
		}
		return result;
	}

	private static String channelLabel(TLObject channel, Resources resources) {
		String text = channel.tValueByName("description") instanceof ResKey description
			? resources.getString(description, null) : null;
		if (text == null || text.isBlank()) {
			text = String.valueOf(channel.tValueByName("name"));
		}
		return text;
	}

	/**
	 * Adds the candidates of the channel's objects (haumacher/ehome #98): the kind and roles of the
	 * channel (see the class comment), a sensor for each measured value, and (with
	 * <code>withScenes</code>) a scene function for each scene number of its scene input. Where
	 * several objects fit a role, the one on the lowest group address takes it, an object without
	 * one after all others, by its number.
	 *
	 * @param all
	 *        The channel with all its objects in effect, connected to a group address or not;
	 *        <code>null</code> for none known.
	 */
	private static void classify(TLObject device, Channel channel, Channel all, List<Proposal> result,
			boolean withScenes) {
		List<Obj> objects = new ArrayList<>(channel.objects());
		objects.sort(Comparator.comparingInt(Obj::addressValue).thenComparingInt(Obj::number));

		if (withScenes) {
			scenes(device, channel, objects, result);
		}

		Map<String, TLObject> roles = new LinkedHashMap<>();
		Obj move = first(objects, o -> o.input() && o.is(1, 8));
		if (move != null) {
			roles.put("move", move.address());
			put(roles, "stop", first(objects, o -> o.input() && (o.is(1, 7) || o.is(1, 10))));
			put(roles, "position", first(objects, o -> o.output() && o.is(5, 1) && !o.named(SLAT)));
			put(roles, "positionCommand", first(objects, o -> o.input() && o.is(5, 1) && !o.named(SLAT)));
			put(roles, "slat", first(objects, o -> o.output() && o.is(5, 1) && o.named(SLAT)));
			put(roles, "slatCommand", first(objects, o -> o.input() && o.is(5, 1) && o.named(SLAT)));
			result.add(candidate("blind", channel.label(), roles, device, channel.key(), List.of(), List.of()));
			return;
		}

		Obj state = first(objects, o -> o.output() && !o.input() && o.bool() && (o.is(1, 11) || o.named(STATE)));
		Obj command = first(objects, o -> o.input() && o.is(1, 1));
		if (command == null && !channel.named(KEY) && (state != null || channel.named(SWITCHED))) {
			command = first(objects, o -> o.input() && o.untyped(1) && !o.named(LOCK) && !o.named(CENTRAL));
		}
		Obj levelCommand = first(objects, o -> o.input() && o.is(5, 1));
		if (levelCommand != null) {
			roles.put("levelCommand", levelCommand.address());
			put(roles, "level", first(objects, o -> o.output() && !o.input() && o.is(5, 1)));
			put(roles, "command", command);
			put(roles, "state", state);
			put(roles, "step", first(objects, o -> o.input() && o.is(3, 7)));
			result.add(candidate("dimmer", channel.label(), roles, device, channel.key(), List.of(), List.of()));
			return;
		}

		Obj temperature = first(objects, o -> o.output() && !o.input() && o.is(9, 1) && !o.named(SETPOINT));
		Obj setpoint = first(objects, o -> o.output() && !o.input() && o.is(9, 1) && o.named(SETPOINT));
		Obj setpointCommand = first(objects, o -> o.input() && o.is(9, 1) && o.named(SETPOINT));
		Obj mode = first(objects, o -> o.output() && !o.input() && o.is(20, 102));
		Obj modeCommand = first(objects, o -> o.input() && o.is(20, 102));
		if (temperature != null
			&& (setpoint != null || setpointCommand != null || mode != null || modeCommand != null)) {
			roles.put("temperature", temperature.address());
			put(roles, "setpoint", setpoint);
			put(roles, "setpointCommand", setpointCommand);
			put(roles, "mode", mode);
			put(roles, "modeCommand", modeCommand);
			put(roles, "heating", first(objects, o -> o.output() && !o.input() && o.bool() && o.named(HEATING)));
			result.add(candidate("climate", channel.label(), roles, device, channel.key(), List.of(), List.of()));
			return;
		}

		if (command != null) {
			if (channel.named(CENTRAL)) {
				return;
			}
			roles.put("command", command.address());
			put(roles, "state", state);
			String kind = channel.named(SOCKET) || command.named(SOCKET) ? "switch" : "light";
			result.add(candidate(kind, channel.label(), roles, device, channel.key(), List.of(), List.of()));
			return;
		}

		Obj press = channel.named(KEY) ? first(objects, o -> o.output() && !o.input() && o.bool()) : null;
		if (press != null) {
			roles.put("press", press.address());
			List<TLObject> sends = new ArrayList<>();
			for (Obj obj : objects) {
				if (obj.output() && !sends.contains(obj.address())) {
					sends.add(obj.address());
				}
			}
			result.add(candidate("button", channel.label(), roles, device, channel.key(), List.of(), sends));
			return;
		}

		List<Obj> values = objects.stream().filter(o -> o.output() && !o.input() && measured(o)).toList();
		long measured = all == null ? values.size()
			: all.objects().stream().filter(o -> o.output() && !o.input() && measured(o)).count();
		for (Obj value : values) {
			Map<String, String> label = values.size() == 1 ? channel.label()
				: texts(value.languages(), r -> channel.label().getOrDefault(language(r), "") + " "
					+ function(value, r));
			Map<String, TLObject> sensor = new LinkedHashMap<>();
			put(sensor, "value", value);
			// Each value of a channel with several is a function of its own, also before it is linked.
			String key = measured > 1 ? channel.key() + " value " + value.number() : channel.key();
			result.add(candidate("sensor", label, sensor, device, key,
				measured > 1 ? List.of(channel.key()) : List.of(), List.of()));
		}
	}

	/**
	 * The function text of the object: its connection's, else the one of its placement or its
	 * object.
	 */
	private static String function(Obj obj, Resources resources) {
		if (obj.connection() != null) {
			return GroupAddressConnections.function(obj.connection(), resources);
		}
		return objectFunction(null, obj.placed(), resources);
	}

	/**
	 * The function text of an object: the one of its connector (the project's own), else the one of
	 * its placement, else the object's; <code>null</code> for none.
	 */
	private static String objectFunction(TLObject connector, Interface placed, Resources resources) {
		Object own = connector == null ? null : connector.tValueByName("function");
		if (own != null && !own.toString().isEmpty()) {
			return own.toString();
		}
		String result = placed.getFunction() == null ? null : resources.getString(placed.getFunction(), null);
		if (result == null && placed.getPort().getFunction() != null) {
			result = resources.getString(placed.getPort().getFunction(), null);
		}
		return result;
	}

	/**
	 * A candidate of a device channel: a proposal of its own, before it is linked with others
	 * ({@link #link(List)}).
	 *
	 * @param label
	 *        The name of the channel (or of the channel and the object, for a value of several),
	 *        which proposals before haumacher/ehome #98 gave the function.
	 */
	private static Proposal candidate(String kind, Map<String, String> label, Map<String, TLObject> roles,
			TLObject device, String key, List<String> aliases, List<TLObject> sends) {
		Member member = new Member(device, key, aliases, List.of(unusedLabel(device, label), label));
		return new Proposal(kind, label, roles, device, key, null, sends, List.of(member));
	}

	/**
	 * Adds the unused functions of a channel no candidate with a group address came from
	 * (haumacher/ehome #98): the candidates of all its objects whose main object has no group
	 * address, without roles, named after the device and the channel.
	 */
	private static void unused(TLObject device, Channel channel, List<Proposal> result) {
		List<Proposal> found = new ArrayList<>();
		classify(device, channel, channel, found, false);
		for (Proposal candidate : found) {
			// A main object with an address the channel's connected objects did not make a function of
			// gives no function either.
			if (candidate.unused()) {
				result.add(new Proposal(candidate.kind(), unusedLabel(device, candidate.label()), Map.of(), device,
					candidate.channel(), null, List.of(), candidate.members()));
			}
		}
	}

	/**
	 * The name of an unused function in each of the label's languages: the device's and the
	 * channel's ("AKK-04 16.03: Channel D").
	 */
	private static Map<String, String> unusedLabel(TLObject device, Map<String, String> label) {
		Map<String, String> result = new TreeMap<>();
		for (Map.Entry<String, String> entry : label.entrySet()) {
			Resources resources = Resources.getInstance(new Locale(entry.getKey()));
			result.put(entry.getKey(), RuntimeImage.truncate(resources.getString(
				I18NConstants.UNUSED_FUNCTION__DEVICE_CHANNEL.fill(deviceName(device, resources), entry.getValue())),
				ImageValidator.MAX_NAME));
		}
		return result;
	}

	/**
	 * The name of the device in the language of the resources: the one the user gave it, else the
	 * name of its product, else its order number, else its individual address.
	 */
	private static String deviceName(TLObject device, Resources resources) {
		Object title = device.tValueByName("title");
		if (title != null && !title.toString().isBlank()) {
			return title.toString().trim();
		}
		if (device.tValueByName("type") instanceof TLObject type) {
			String name = type.tValueByName("name") instanceof ResKey key ? resources.getString(key, null) : null;
			if (name != null && !name.isBlank()) {
				return name.trim();
			}
			Object order = type.tValueByName("orderNumber");
			if (order != null && !order.toString().isBlank()) {
				return order.toString().trim();
			}
		}
		return GroupAddressConnections.addressText(device);
	}

	/**
	 * Links the candidates of the device channels into functions (haumacher/ehome #98): candidates
	 * sharing a group address that is the sending (first) address of an object taking a role in
	 * each (for a key the addresses its objects send on) are one function. A secondary address of
	 * an object (a central address, the status a toggle key listens to) links nothing; neither do
	 * objects taking no role (a lock, a logic input). The candidate of the receiving side (a light,
	 * a switch, a dimmer, a blind, a climate) decides the kind, its roles are the function's (filled
	 * by those of further candidates of the kind: two actuators on one address), and the function
	 * is named after the group address of its main role, in every application language. A group of
	 * keys only is a button, a sensor a sensor. Scenes are not linked. The functions are in the order
	 * of their first candidate.
	 */
	private static List<Proposal> link(List<Proposal> candidates) {
		int[] parent = new int[candidates.size()];
		Map<TLObject, Integer> first = new HashMap<>();
		for (int n = 0; n < candidates.size(); n++) {
			parent[n] = n;
			Proposal candidate = candidates.get(n);
			if (candidate.scene() != null) {
				continue;
			}
			List<TLObject> addresses = "button".equals(candidate.kind()) ? candidate.sends()
				: new ArrayList<>(candidate.roles().values());
			for (TLObject address : addresses) {
				if (address == null) {
					continue;
				}
				Integer other = first.putIfAbsent(address, Integer.valueOf(n));
				if (other != null) {
					int a = root(parent, n);
					int b = root(parent, other.intValue());
					parent[Math.max(a, b)] = Math.min(a, b);
				}
			}
		}
		Map<Integer, List<Proposal>> groups = new LinkedHashMap<>();
		for (int n = 0; n < candidates.size(); n++) {
			groups.computeIfAbsent(Integer.valueOf(root(parent, n)), r -> new ArrayList<>()).add(candidates.get(n));
		}
		List<Proposal> result = new ArrayList<>();
		for (List<Proposal> group : groups.values()) {
			result.add(merge(group));
		}
		return result;
	}

	private static int root(int[] parent, int n) {
		int result = n;
		while (parent[result] != result) {
			result = parent[result];
		}
		return result;
	}

	/** The kinds of the receiving side of a linked function, which decides its kind. */
	private static final Set<String> RECEIVING = Set.of("light", "switch", "dimmer", "blind", "climate");

	/**
	 * The function of the linked candidates, see {@link #link(List)}.
	 */
	private static Proposal merge(List<Proposal> group) {
		Proposal primary = group.stream().filter(p -> RECEIVING.contains(p.kind())).findFirst().orElse(group.get(0));
		if (primary.scene() != null) {
			return primary;
		}
		Map<String, TLObject> roles = new LinkedHashMap<>(primary.roles());
		List<TLObject> sends = new ArrayList<>(primary.sends());
		List<Member> members = new ArrayList<>(primary.members());
		for (Proposal other : group) {
			if (other == primary) {
				continue;
			}
			members.addAll(other.members());
			if (other.kind().equals(primary.kind())) {
				for (String role : FunctionKinds.roles(primary.kind())) {
					if (roles.get(role) == null && other.roles().get(role) != null) {
						roles.put(role, other.roles().get(role));
					}
				}
				for (TLObject address : other.sends()) {
					if (!sends.contains(address)) {
						sends.add(address);
					}
				}
			}
		}
		Map<String, String> label = addressLabel(roles.get(FunctionKinds.mainRole(primary.kind())));
		return new Proposal(primary.kind(), label.isEmpty() ? primary.label() : label, roles, primary.device(),
			primary.channel(), null, sends, members);
	}

	/**
	 * The name of the group address in every application language (a text of the project, which has
	 * no language of its own); empty for an address without a name.
	 */
	private static Map<String, String> addressLabel(TLObject address) {
		Object name = address == null ? null : address.tValueByName("name");
		if (name == null || name.toString().isBlank()) {
			return Map.of();
		}
		Map<String, Resources> languages = new TreeMap<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			languages.put(locale.getLanguage(), Resources.getInstance(locale));
		}
		return texts(languages, r -> name.toString());
	}

	/**
	 * Adds a scene function for each scene number the project gives the channel's scene input.
	 */
	private static void scenes(TLObject device, Channel channel, List<Obj> objects, List<Proposal> result) {
		Obj scene = first(objects, o -> o.input() && o.scene());
		if (scene == null) {
			return;
		}
		for (Integer number : sceneNumbers(device, scene)) {
			Map<String, String> label = new TreeMap<>();
			for (Map.Entry<String, String> entry : channel.label().entrySet()) {
				Resources resources = scene.languages().get(entry.getKey());
				if (resources != null) {
					label.put(entry.getKey(), RuntimeImage.truncate(resources.getString(
						I18NConstants.SCENE_FUNCTION__CHANNEL_NUMBER.fill(entry.getValue(), number)), ImageValidator.MAX_NAME));
				}
			}
			String key = channel.key() + " scene " + number;
			result.add(new Proposal("scene", label, Map.of("command", scene.address()), device, key, number,
				List.of(), List.of(new Member(device, key, List.of(), List.of()))));
		}
	}

	/**
	 * The scene numbers (1 ... 64) the parameters of the scene input's part of the program give with
	 * the device's values: the parameters in effect whose text names a scene number, in the smallest
	 * part of the program's structure around the scene input that has any (a channel, a parameter
	 * block, a case), unless that part holds the scene input of another object as well; never the
	 * parameters of the whole program.
	 */
	private static Set<Integer> sceneNumbers(TLObject device, Obj scene) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		List<ParameterView> numbered = new ArrayList<>();
		for (ParameterView view : configuration.getActiveViews().values()) {
			Parameter parameter = configuration.parameter(view);
			ResKey text = view.getDescription() != null ? view.getDescription()
				: parameter == null ? null : parameter.getDescription();
			if (text != null && texts(scene.languages(), r -> r.getString(text, null)).values().stream()
				.anyMatch(t -> SCENE_NUMBER.matcher(t.toLowerCase(Locale.ROOT)).find())) {
				numbered.add(view);
			}
		}
		Set<Integer> result = new TreeSet<>();
		for (TLObject scope = scene.placed().tContainer(); scope != null && !isA(scope, "ehome.operational:Program")
			&& !isA(scope, "ehome.operational:ChannelIndependent"); scope = scope.tContainer()) {
			for (ParameterView view : numbered) {
				if (within(view, scope)) {
					Integer number = sceneNumber(ParameterValues.value(configuration.valueHolder(view)),
						configuration.parameter(view), scene.languages());
					if (number != null) {
						result.add(number);
					}
				}
			}
			if (!result.isEmpty()) {
				for (Interface other : configuration.getActiveInterfaces().values()) {
					Dpt dpt = placementType(other);
					if (other.getPort() != scene.placed().getPort() && within(other, scope) && dpt != null
						&& (dpt.main() == 17 || dpt.main() == 18)) {
						// The part is shared with the scenes of another object: whose numbers are whose is
						// not known.
						return Set.of();
					}
				}
				return result;
			}
		}
		return result;
	}

	/**
	 * The scene number (1 ... 64) of a value of a parameter naming one: the number the text of an
	 * enumeration value shows; a number counting from 1, or from 0 for a parameter of the range 0 ...
	 * 63 (the codes of the datapoint); <code>null</code> for none ("not active").
	 */
	private static Integer sceneNumber(Object value, Parameter parameter, Map<String, Resources> languages) {
		Integer result = null;
		if (value instanceof EnumerationValue choice && choice.tValueByName("name") instanceof ResKey name) {
			for (String text : texts(languages, r -> r.getString(name, null)).values()) {
				Matcher matcher = NUMBER.matcher(text);
				if (matcher.matches()) {
					result = Integer.valueOf(matcher.group(1));
					break;
				}
			}
		} else if (value instanceof Number number && parameter != null
			&& parameter.getType() instanceof NumericType type) {
			if (type.getMin() >= 1) {
				result = Integer.valueOf(number.intValue());
			} else if (type.getMin() == 0 && type.getMax() == 63) {
				result = Integer.valueOf(number.intValue() + 1);
			}
		}
		return result != null && result.intValue() >= 1 && result.intValue() <= 64 ? result : null;
	}

	/**
	 * The datapoint type of a placed object: the placement's, else the object's; <code>null</code>
	 * for none.
	 */
	private static Dpt placementType(Interface placed) {
		List<TLObject> types = list(placed, "datapointTypes");
		if (types.isEmpty()) {
			types = list(placed.getPort(), "datapointTypes");
		}
		return types.isEmpty() ? null : GroupAddressTypes.dpt(types.get(0));
	}

	private static boolean within(TLObject node, TLObject ancestor) {
		for (TLObject current = node; current != null; current = current.tContainer()) {
			if (current == ancestor) {
				return true;
			}
		}
		return false;
	}

	private static String language(Resources resources) {
		return resources.getLocale().getLanguage();
	}

	private static boolean measured(Obj obj) {
		int main = obj.dpt().main();
		return main == 9 || main == 14 || obj.is(1, 5) || obj.is(1, 9) || obj.is(1, 18) || obj.is(1, 19);
	}

	private static Obj first(List<Obj> objects, Predicate<Obj> test) {
		for (Obj obj : objects) {
			if (test.test(obj)) {
				return obj;
			}
		}
		return null;
	}

	private static void put(Map<String, TLObject> roles, String role, TLObject datapoint) {
		if (datapoint != null) {
			roles.put(role, datapoint);
		}
	}

	private static void put(Map<String, TLObject> roles, String role, Obj obj) {
		if (obj != null) {
			roles.put(role, obj.address());
		}
	}

	private static Pattern words(String... beginnings) {
		return Pattern.compile("(?<![\\p{L}])(" + String.join("|", beginnings) + ")");
	}

	private static boolean isA(TLObject obj, String typeName) {
		return ExtIds.isA(obj, typeName);
	}

}
