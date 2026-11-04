/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.RoomController;
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
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeStatus;

/**
 * Proposes the functions (<code>ehome.runtime:Function</code>) of a project from its device channels
 * (haumacher/ehome #43): what the user then edits.
 *
 * <p>
 * <b>Channels.</b> A channel of a device is a channel of its application program, where the program
 * has several numbered ones; otherwise (a program with one generic channel, or with its objects
 * independent of channels, as most programs written before ETS 5) the communication objects sharing
 * a name, the way ETS lists them ("Kanal A", "Taste 1"). Only objects of the device connected to a
 * group address with a datapoint type count, each with its sending address (the first one).
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
 * (Taste/Taster/Wippe/button/rocker/push) is the press.</li>
 * <li><code>sensor</code>, one per object: an output of a measured value (DPT 9.x, 14.x) or of an
 * alarm, open/closed, occupancy or window contact (1.005, 1.009, 1.018, 1.019).</li>
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
 * a parameter gives no function: the scene number is mandatory in the runtime image. Scene
 * functions are named after the channel and the number ("Channel A: scene 1").
 * </p>
 *
 * <p>
 * An input is an object receiving from its address (write flag, see
 * {@link DatapointSettings#receives(TLObject)}) whose datapoint is writable; an output transmits on
 * it. Where several objects fit a role, the one on the lowest group address takes it. Objects
 * fitting no role (locks, logic, scenes, status LEDs of the device itself) are ignored. A channel
 * whose objects fit no kind gets no function.
 * </p>
 *
 * <p>
 * Each function is named after its channel, gets an identifier derived from the name and the room the
 * device is placed in. The name has a text for each application language the device's program has
 * texts in (<code>Program#languages</code>, haumacher/ehome #51): a program written in German only
 * gives a German name, not the German text standing in for English. The kind is decided on the texts
 * of all these languages, so it does not depend on the language of the user.
 * </p>
 *
 * <p>
 * The proposal never changes a function the project has: it adds a function only for a channel no
 * function of the project was proposed for, and none whose main role an existing function of the
 * same kind already binds (a scene: the same address with the same scene number), nor a second one
 * for a main role another proposal of the same run binds (two devices switched on one address).
 * Running it again therefore adds nothing. A proposed function the user
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
 * The function <code>action</code> gives no <code>button</code>: its datapoint is an enumeration (off,
 * on, toggle, dim up, ...), and a button's press is a truth value or a controlled value
 * (homecontrol <code>spec/runtime-image.md</code> 3.5). Only datapoints the project has bound are
 * proposed; nothing is created for an unbound Zigbee function.
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
	 * A communication object of a channel with the group address it sends on.
	 */
	private record Obj(Interface placed, TLObject connection, TLObject address, Dpt dpt, String type, boolean input,
			boolean output, String text, Map<String, Resources> languages) {

		int addressValue() {
			return address.tValueByName("address") instanceof GA ga ? ga.getValue() : Integer.MAX_VALUE;
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
	 */
	public record Proposal(String kind, Map<String, String> label, Map<String, TLObject> roles, TLObject device,
			String channel, Integer scene) {

		/**
		 * The name as multilingual text.
		 */
		public ResKey name() {
			return FunctionProposal.text(label);
		}
	}

	private FunctionProposal() {
		// Static utility.
	}

	/**
	 * Adds the proposed functions the project lacks; must run in a transaction.
	 *
	 * @return The functions added.
	 */
	public static List<TLObject> apply(TLObject project) {
		List<TLObject> existing = functions(project);
		Set<String> proposedChannels = new HashSet<>();
		Set<String> boundMainRoles = new HashSet<>();
		Set<TLObject> usedDatapoints = new HashSet<>();
		Set<String> identifiers = new HashSet<>();
		for (TLObject function : existing) {
			String identity = identity(function.tValueByName("device"), function.tValueByName("channel"));
			if (identity != null) {
				proposedChannels.add(identity);
			}
			String kind = kind(function);
			String main = FunctionKinds.mainRole(kind);
			if (main != null && function.tValueByName(main) instanceof TLObject address) {
				boundMainRoles.add(mainKey(kind, address, function.tValueByName("scene")));
			}
			for (String role : FunctionKinds.roles(kind)) {
				if (function.tValueByName(role) instanceof TLObject datapoint) {
					usedDatapoints.add(datapoint);
				}
			}
			if (function.tValueByName("identifier") != null) {
				identifiers.add(function.tValueByName("identifier").toString());
			}
		}

		Set<String> dismissed = new HashSet<>();
		for (TLObject dismissal : list(project, DISMISSED)) {
			String identity = identity(dismissal.tValueByName("device"), dismissal.tValueByName("channel"));
			if (identity != null) {
				dismissed.add(kind(dismissal) + " " + identity);
			}
		}

		List<TLObject> added = new ArrayList<>();
		for (Proposal proposal : propose(project)) {
			String identity = identity(proposal.device(), proposal.channel());
			if (proposedChannels.contains(identity)) {
				continue;
			}
			if (dismissed.contains(proposal.kind() + " " + identity)) {
				continue;
			}
			if (zigbee(proposal.channel()) || proposal.channel().startsWith(BLOCK_CHANNEL)) {
				if (proposal.roles().values().stream().anyMatch(usedDatapoints::contains)) {
					continue;
				}
				usedDatapoints.addAll(proposal.roles().values());
			} else {
				TLObject main = proposal.roles().get(FunctionKinds.mainRole(proposal.kind()));
				if (!boundMainRoles.add(mainKey(proposal.kind(), main, proposal.scene()))) {
					continue;
				}
			}
			added.add(create(proposal, identifiers));
		}
		if (!added.isEmpty()) {
			List<TLObject> all = new ArrayList<>(existing);
			all.addAll(added);
			project.tUpdateByName("functions", all);
		}
		return added;
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

	private static TLObject create(Proposal proposal, Set<String> identifiers) {
		TLObject function = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Function"));
		ResKey name = proposal.name();
		function.tUpdateByName("name", name);
		function.tUpdateByName("identifier", uniqueIdentifier(Resources.getInstance().getString(name), identifiers));
		function.tUpdateByName("kind", classifier(proposal.kind()));
		function.tUpdateByName("room",
			proposal.device().tValueByName(BlockInstances.isInstance(proposal.device()) ? "room" : "location"));
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
	 * The functions the channels of the project's devices suggest, by device and channel, followed by
	 * the functions its bound Zigbee datapoints suggest, by device and endpoint.
	 */
	public static List<Proposal> propose(TLObject project) {
		List<Proposal> result = new ArrayList<>();
		for (TLObject device : devices(project)) {
			for (Channel channel : channels(device)) {
				classify(device, channel, result);
			}
		}
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
	 * The climate function of a room controller (haumacher/ehome #75): its filtered temperature, its
	 * active setpoint, the comfort setpoint as the setpoint command and its heat demand as heating,
	 * keyed by the instance's identifier.
	 */
	private static void block(TLObject instance, List<Proposal> result) {
		String id = BlockInstances.identifier(instance);
		if (id == null || !RoomController.TYPE.equals(instance.tValueByName("blockType"))
			|| BlockInstances.type(instance) == null) {
			return;
		}
		Map<String, TLObject> roles = new LinkedHashMap<>();
		put(roles, "temperature", BlockInstances.port(instance, "filtered"));
		put(roles, "setpoint", BlockInstances.port(instance, "activeSetpoint"));
		put(roles, "setpointCommand", BlockInstances.parameter(instance, "comfort"));
		put(roles, "heating", BlockInstances.port(instance, "demand"));
		if (!roles.containsKey("temperature")) {
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
		result.add(new Proposal("climate", label, roles, instance, BLOCK_CHANNEL + id, null));
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
				} else {
					// The level of a dimmer, an action (no truth value), another key: no function.
					continue;
				}
				result.add(new Proposal(kind, zigbeeLabel(device, named, shared ? endpoint.getKey() : null), roles, device,
					ZIGBEE_CHANNEL + device.tValueByName("ieee") + ":" + endpoint.getKey() + ":" + key, null));
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

	private static void classify(TLObject device, Channel channel, List<Proposal> result) {
		List<Obj> objects = new ArrayList<>(channel.objects());
		objects.sort(Comparator.comparingInt(Obj::addressValue));

		scenes(device, channel, objects, result);

		Map<String, TLObject> roles = new LinkedHashMap<>();
		Obj move = first(objects, o -> o.input() && o.is(1, 8));
		if (move != null) {
			roles.put("move", move.address());
			put(roles, "stop", first(objects, o -> o.input() && (o.is(1, 7) || o.is(1, 10))));
			put(roles, "position", first(objects, o -> o.output() && o.is(5, 1) && !o.named(SLAT)));
			put(roles, "positionCommand", first(objects, o -> o.input() && o.is(5, 1) && !o.named(SLAT)));
			put(roles, "slat", first(objects, o -> o.output() && o.is(5, 1) && o.named(SLAT)));
			put(roles, "slatCommand", first(objects, o -> o.input() && o.is(5, 1) && o.named(SLAT)));
			result.add(new Proposal("blind", channel.label(), roles, device, channel.key(), null));
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
			result.add(new Proposal("dimmer", channel.label(), roles, device, channel.key(), null));
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
			result.add(new Proposal("climate", channel.label(), roles, device, channel.key(), null));
			return;
		}

		if (command != null) {
			if (channel.named(CENTRAL)) {
				return;
			}
			roles.put("command", command.address());
			put(roles, "state", state);
			String kind = channel.named(SOCKET) || command.named(SOCKET) ? "switch" : "light";
			result.add(new Proposal(kind, channel.label(), roles, device, channel.key(), null));
			return;
		}

		Obj press = channel.named(KEY) ? first(objects, o -> o.output() && !o.input() && o.bool()) : null;
		if (press != null) {
			roles.put("press", press.address());
			result.add(new Proposal("button", channel.label(), roles, device, channel.key(), null));
			return;
		}

		List<Obj> values = objects.stream().filter(o -> o.output() && !o.input() && measured(o)).toList();
		for (Obj value : values) {
			Map<String, String> label = values.size() == 1 ? channel.label()
				: texts(value.languages(), r -> channel.label().getOrDefault(language(r), "") + " "
					+ GroupAddressConnections.function(value.connection(), r));
			result.add(new Proposal("sensor", label, Map.of("value", value.address()), device, channel.key(), null));
		}
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
			result.add(new Proposal("scene", label, Map.of("command", scene.address()), device,
				channel.key() + " scene " + number, number));
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
