/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

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
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;

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
 * input switches, a state (below) reports.</li>
 * <li><code>climate</code>: an output of DPT 9.001 (the temperature) together with a setpoint
 * (9.001 named Soll/setpoint) or an operating mode (20.102); an output named Heiz/heat is heating.</li>
 * <li><code>light</code>, or <code>switch</code> where the channel is named for a socket
 * (Steckdose/socket/outlet): an input of DPT 1.001 (switch) is the command; an output of DPT 1.011
 * (state), or a truth value named Status/state/Rückmeldung, is the state. A channel named
 * central/Zentral (a central object commanding several channels) gets no function.</li>
 * <li><code>button</code>: an output of a truth value in a channel named for a key
 * (Taste/Taster/Wippe/button/rocker/push) is the press.</li>
 * <li><code>sensor</code>, one per object: an output of a measured value (DPT 9.x, 14.x) or of an
 * alarm, open/closed, occupancy or window contact (1.005, 1.009, 1.018, 1.019).</li>
 * </ol>
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
 * device is placed in. The proposal never changes a function the project has: it adds a function only
 * for a channel no function of the project was proposed for, and none whose main role an existing
 * function of the same kind already binds. Running it again therefore adds nothing; a deleted
 * proposal comes back.
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

	/**
	 * A communication object of a channel with the group address it sends on.
	 */
	private record Obj(TLObject connection, TLObject address, Dpt dpt, String type, boolean input,
			boolean output, String text) {

		int addressValue() {
			return address.tValueByName("address") instanceof GA ga ? ga.getValue() : Integer.MAX_VALUE;
		}

		boolean is(int main, int sub) {
			return dpt.main() == main && dpt.sub() == sub;
		}

		boolean bool() {
			return ValueTypes.BOOLEAN.equals(type);
		}

		boolean named(Pattern pattern) {
			return pattern.matcher(text).find();
		}
	}

	/**
	 * The objects of one channel of a device.
	 */
	private record Channel(String key, String label, List<Obj> objects) {
		boolean named(Pattern pattern) {
			return pattern.matcher(label.toLowerCase(Locale.ROOT)).find();
		}
	}

	/**
	 * A proposed function.
	 *
	 * @param kind
	 *        The function kind.
	 * @param label
	 *        Its name.
	 * @param roles
	 *        The group addresses by role, in the order of the kind's roles.
	 * @param device
	 *        The device it was proposed for.
	 * @param channel
	 *        The key of the channel it was proposed for.
	 */
	public record Proposal(String kind, String label, Map<String, TLObject> roles, TLObject device, String channel) {
		// A record.
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
		Set<String> identifiers = new HashSet<>();
		for (TLObject function : existing) {
			if (function.tValueByName("device") instanceof TLObject device && function.tValueByName("channel") != null) {
				proposedChannels.add(channelKey(device, function.tValueByName("channel").toString()));
			}
			String kind = kind(function);
			String main = FunctionKinds.mainRole(kind);
			if (main != null && function.tValueByName(main) instanceof TLObject address) {
				boundMainRoles.add(kind + " " + address.tId());
			}
			if (function.tValueByName("identifier") != null) {
				identifiers.add(function.tValueByName("identifier").toString());
			}
		}

		List<TLObject> added = new ArrayList<>();
		for (Proposal proposal : propose(project)) {
			if (proposedChannels.contains(channelKey(proposal.device(), proposal.channel()))) {
				continue;
			}
			TLObject main = proposal.roles().get(FunctionKinds.mainRole(proposal.kind()));
			if (boundMainRoles.contains(proposal.kind() + " " + main.tId())) {
				continue;
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

	private static String channelKey(TLObject device, String channel) {
		return device.tId() + " " + channel;
	}

	private static TLObject create(Proposal proposal, Set<String> identifiers) {
		TLObject function = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Function"));
		function.tUpdateByName("name", RuntimeImage.truncate(proposal.label(), ImageValidator.MAX_NAME));
		function.tUpdateByName("identifier", uniqueIdentifier(proposal.label(), identifiers));
		function.tUpdateByName("kind", classifier(proposal.kind()));
		function.tUpdateByName("room", proposal.device().tValueByName("location"));
		function.tUpdateByName("device", proposal.device());
		function.tUpdateByName("channel", proposal.channel());
		for (Map.Entry<String, TLObject> role : proposal.roles().entrySet()) {
			function.tUpdateByName(role.getKey(), role.getValue());
		}
		return function;
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
	static String uniqueIdentifier(String name, Set<String> identifiers) {
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
	 * The functions the channels of the project's devices suggest, by device and channel.
	 */
	public static List<Proposal> propose(TLObject project) {
		List<Proposal> result = new ArrayList<>();
		for (TLObject device : devices(project)) {
			for (Channel channel : channels(device)) {
				classify(device, channel, result);
			}
		}
		return result;
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
			String objectName = GroupAddressConnections.objectName(placed);
			TLObject programChannel = programChannel(placed);
			String key;
			String label;
			if (programChannel != null) {
				key = "channel:" + programChannel.tValueByName("extId");
				label = channelLabel(programChannel);
			} else {
				key = "object:" + objectName;
				label = objectName;
			}
			boolean input = DatapointSettings.receives(connection) && DatapointSettings.writable(address);
			boolean output = DatapointSettings.transmits(connection);
			String text = (objectName + " " + GroupAddressConnections.function(connection) + " "
				+ address.tValueByName("name")).toLowerCase(Locale.ROOT);
			channels.computeIfAbsent(key, k -> new Channel(k, label, new ArrayList<>()))
				.objects().add(new Obj(connection, address, dpt, type, input, output, text));
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

	private static String channelLabel(TLObject channel) {
		String text = channel.tValueByName("description") instanceof ResKey description
			? Resources.getInstance().getString(description, null) : null;
		if (text == null || text.isBlank()) {
			text = String.valueOf(channel.tValueByName("name"));
		}
		return text;
	}

	private static void classify(TLObject device, Channel channel, List<Proposal> result) {
		List<Obj> objects = new ArrayList<>(channel.objects());
		objects.sort(Comparator.comparingInt(Obj::addressValue));

		Map<String, TLObject> roles = new LinkedHashMap<>();
		Obj move = first(objects, o -> o.input() && o.is(1, 8));
		if (move != null) {
			roles.put("move", move.address());
			put(roles, "stop", first(objects, o -> o.input() && (o.is(1, 7) || o.is(1, 10))));
			put(roles, "position", first(objects, o -> o.output() && o.is(5, 1) && !o.named(SLAT)));
			put(roles, "positionCommand", first(objects, o -> o.input() && o.is(5, 1) && !o.named(SLAT)));
			put(roles, "slat", first(objects, o -> o.output() && o.is(5, 1) && o.named(SLAT)));
			put(roles, "slatCommand", first(objects, o -> o.input() && o.is(5, 1) && o.named(SLAT)));
			result.add(new Proposal("blind", channel.label(), roles, device, channel.key()));
			return;
		}

		Obj command = first(objects, o -> o.input() && o.is(1, 1));
		Obj state = first(objects, o -> o.output() && !o.input() && o.bool() && (o.is(1, 11) || o.named(STATE)));
		Obj levelCommand = first(objects, o -> o.input() && o.is(5, 1));
		if (levelCommand != null) {
			roles.put("levelCommand", levelCommand.address());
			put(roles, "level", first(objects, o -> o.output() && !o.input() && o.is(5, 1)));
			put(roles, "command", command);
			put(roles, "state", state);
			result.add(new Proposal("dimmer", channel.label(), roles, device, channel.key()));
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
			result.add(new Proposal("climate", channel.label(), roles, device, channel.key()));
			return;
		}

		if (command != null) {
			if (channel.named(CENTRAL)) {
				return;
			}
			roles.put("command", command.address());
			put(roles, "state", state);
			String kind = channel.named(SOCKET) || command.named(SOCKET) ? "switch" : "light";
			result.add(new Proposal(kind, channel.label(), roles, device, channel.key()));
			return;
		}

		Obj press = channel.named(KEY) ? first(objects, o -> o.output() && !o.input() && o.bool()) : null;
		if (press != null) {
			roles.put("press", press.address());
			result.add(new Proposal("button", channel.label(), roles, device, channel.key()));
			return;
		}

		List<Obj> values = objects.stream().filter(o -> o.output() && !o.input() && measured(o)).toList();
		for (Obj value : values) {
			String label = values.size() == 1 ? channel.label()
				: channel.label() + " " + GroupAddressConnections.function(value.connection());
			result.add(new Proposal("sensor", label, Map.of("value", value.address()), device, channel.key()));
		}
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

	private static void put(Map<String, TLObject> roles, String role, Obj obj) {
		if (obj != null) {
			roles.put(role, obj.address());
		}
	}

	private static Pattern words(String... beginnings) {
		return Pattern.compile("(?<![\\p{L}])(" + String.join("|", beginnings) + ")");
	}

	private static boolean isA(TLObject obj, String typeName) {
		return TLModelUtil.isGeneralization((TLClass) TLModelUtil.findType(typeName), obj.tType());
	}

}
