/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime.binary;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import de.haumacher.ehome.blocks.BlockRegistry;
import de.haumacher.ehome.blocks.BlockSet;
import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.rules.Node;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleLoader.LoadException;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.RuleSet.Action;
import de.haumacher.ehome.rules.RuleSet.ActionKind;
import de.haumacher.ehome.rules.RuleSet.Parameter;
import de.haumacher.ehome.rules.RuleSet.Reaction;
import de.haumacher.ehome.rules.RuleSet.Rule;
import de.haumacher.ehome.rules.RuleSet.StartReason;
import de.haumacher.ehome.rules.RuleSet.Trigger;
import de.haumacher.ehome.rules.RuleSet.TriggerKind;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;

/**
 * The compiler of the runtime image (homecontrol <code>spec/runtime-image.md</code> I1, section 9,
 * haumacher/ehome #176): the source form, the JSON document E-Home exports, simulates and tests, into
 * the binary image the controller keeps in its image slot and works from.
 *
 * <p>
 * Every reference is resolved to a table index, every string written once, the rules as flat
 * expression trees, the control pages as the finished body of <code>GET /api/pages</code> (and the
 * pages of minimum role <code>anonymous</code> as a second one with the tables of what they show,
 * format 1.1); the layout
 * is the canonical one of 9.18, so the same source gives the same bytes. The source must have been
 * validated ({@link ImageValidator}): the compiler resolves, it does not check. What it cannot resolve
 * is a {@link CompileException}.
 * </p>
 */
public final class ImageCompiler {

	/** The context of a signature over a binary image (<code>spec/runtime-image.md</code> 9.17). */
	public static final String SIGNATURE_CONTEXT = "homecontrol-image-v2";

	/** The kinds of a function, by their codes (9.8). */
	static final List<String> FUNCTION_KINDS =
		List.of("light", "dimmer", "blind", "switch", "sensor", "climate", "scene", "button");

	/** The kinds of a location, by their codes (9.8); code 0 is none. */
	static final List<String> LOCATION_KINDS = List.of("", "building", "floor", "room", "area", "outside");

	/** The kinds of a schedule entry, by their codes (9.12). */
	static final List<String> SCHEDULE_KINDS = List.of("weekly", "yearly", "dates", "astro", "interval", "once");

	/** The sun events of an astro entry, by their codes (9.12). */
	static final List<String> SUN_EVENTS = List.of("dawn", "sunrise", "sunset", "dusk");

	/** The kinds of press of a button's <code>presses</code>, in the order of the record (9.8). */
	static final List<String> PRESS_KINDS = List.of("press", "on", "off", "up", "down", "release");

	/** The roles of a function the compiler writes, by kind (3.5); others are not compiled. */
	static final Map<String, List<String>> ROLES = Map.of(
		"light", List.of("command", "state"),
		"dimmer", List.of("command", "state", "levelCommand", "level", "step"),
		"blind", List.of("move", "stop", "position", "positionCommand", "slat", "slatCommand", "atTop", "atBottom"),
		"switch", List.of("command", "state"),
		"sensor", List.of("value"),
		"climate", List.of("temperature", "setpoint", "setpointCommand", "mode", "modeCommand", "heating",
			"heatingLevel"),
		"scene", List.of("command"),
		"button", List.of("press", "state"));

	/** The value types of datapoints by their codes (9.14); code 0 is none. */
	static final List<String> VALUE_TYPES = List.of("", "boolean", "unsigned", "signed", "real", "string", "timeOfDay",
		"date", "dateTime", "enumeration", "raw", "controlled", "color");

	/** Owners of datapoints (9.7). */
	static final int OWNER_NONE = 0;

	static final int OWNER_KNX = 1;

	static final int OWNER_SCHEDULER = 2;

	static final int OWNER_TIME_MASTER = 3;

	static final int OWNER_ZIGBEE = 4;

	static final int OWNER_RULES = 5;

	static final int OWNER_BLOCKS = 6;

	static final int OWNER_EXTERNAL = 7;

	/** The member of a rule's state datapoint. */
	static final int STATE_MEMBER = 0x8000;

	/** Datapoint flags (9.7). */
	static final int READABLE = 0x01;

	static final int WRITABLE = 0x02;

	static final int TRANSMIT = 0x04;

	static final int READ_ON_START = 0x08;

	static final int PERSIST = 0x10;

	static final int HAS_HISTORY = 0x20;

	static final int APPLICATION = 0x40;

	static final int VIRTUAL = 0x80;

	/** The member of a control page naming its minimum role (3.9, schema 1.8). */
	static final String MINIMUM_ROLE = "minimumRole";

	/** The minimum role of a control page shown and operated without sign-in. */
	static final String ANONYMOUS = "anonymous";

	/** The time master's datapoints: member, id, type. */
	static final String[][] TIME_MASTER = { { "time", "knx.time", "timeOfDay" }, { "date", "knx.date", "date" },
		{ "dateTime", "knx.datetime", "dateTime" } };

	/** The default period of the time master's broadcasts in seconds. */
	static final int TIME_MASTER_INTERVAL = 600;

	/** The default interval of an external source in seconds. */
	static final int EXTERNAL_INTERVAL = 900;

	/** The default period of the MQTT history export in minutes. */
	static final int MQTT_HISTORY_INTERVAL = 15;

	/**
	 * How to compile.
	 *
	 * @param seed
	 *        The private installation key to sign with (its seed, 64 hex digits), <code>null</code>
	 *        for an unsigned image.
	 * @param registry
	 *        The block types the controller has.
	 * @param harness
	 *        Datapoints owned by a test technology (the rule and block corpora of
	 *        <code>spec/runtime-image.md</code> 13.1): no technology member and yet no application flag.
	 * @param reads
	 *        Whether a technology can be asked for the value of a datapoint (the rule loader's
	 *        <code>notReadable</code>), <code>null</code> for the image's technologies.
	 */
	public record Options(String seed, BlockRegistry registry, Set<String> harness, Predicate<String> reads) {

		/** The options of an unsigned image for the controller's built-in block types. */
		public static Options unsigned() {
			return new Options(null, BlockInstances.registry(), Set.of(), null);
		}

		/** The options of an image signed with the given seed, <code>null</code> for unsigned. */
		public static Options signed(String seed) {
			return new Options(seed, BlockInstances.registry(), Set.of(), null);
		}

		/** These options signed with the given seed. */
		public Options withSeed(String newSeed) {
			return new Options(newSeed, registry, harness, reads);
		}
	}

	/** A datapoint of the model. */
	private static final class Dp {
		final String _name;

		final String _type;

		Object _label;

		String _unit;

		int _flags;

		int _owner = OWNER_NONE;

		int _ownerIndex = Layout.NONE;

		int _ownerMember;

		int _history = Layout.NONE;

		/** The constant of the initial value (9.7, format 1.2), {@link Layout#NONE} without. */
		int _initial = Layout.NONE;

		final Map<?, ?> _source;

		Dp(String name, String type, Map<?, ?> source) {
			_name = name;
			_type = type;
			_source = source;
		}
	}

	private final Map<?, ?> _src;

	private final Options _options;

	private final List<Dp> _dps = new ArrayList<>();

	private final Map<String, Integer> _dpIndex = new HashMap<>();

	private final SortedMap<Integer, List<Map<String, Object>>> _tables = new TreeMap<>();

	private final List<String> _scheduleIds = new ArrayList<>();

	private final Set<String> _scheduleOutputs = new HashSet<>();

	private final Set<String> _externalBound = new HashSet<>();

	private List<Map<?, ?>> _ruleJson = List.of();

	private ImageCompiler(Map<?, ?> source, Options options) {
		_src = source;
		_options = options;
		for (Layout.Section section : Layout.sections()) {
			if (!section.bytes() && section.id() != Layout.LABELS) {
				_tables.put(Integer.valueOf(section.id()), new ArrayList<>());
			}
		}
	}

	/**
	 * The binary image of the given source, unsigned, for the built-in block types.
	 */
	public static byte[] compile(Map<?, ?> source) {
		return compile(source, Options.unsigned());
	}

	/**
	 * The binary image of the given source.
	 *
	 * @throws CompileException
	 *         If the source cannot be compiled.
	 */
	public static byte[] compile(Map<?, ?> source, Options options) {
		return new ImageCompiler(source, options).run();
	}

	/**
	 * The binary image of the source given as JSON text.
	 */
	public static byte[] compile(String source, Options options) {
		Object json;
		try {
			json = Json.parse(source, ImageValidator.MAX_NESTING);
		} catch (Json.JsonException ex) {
			throw new CompileException("", "not JSON: " + ex.getMessage());
		}
		if (!(json instanceof Map<?, ?> map)) {
			throw new CompileException("", "the image must be a JSON object");
		}
		return compile(map, options);
	}

	private byte[] run() {
		scheduleIds();
		externalBound();
		datapoints();
		RuleSet rules = loadRules();
		Map<String, Object> header = imageRecord();
		settings();
		locationsAndFunctions();
		knx();
		zigbee();
		external();
		mqtt();
		schedules();
		rules(rules);
		blocks();
		history(header);
		datapointRecords();
		return ImageWriter.write(_tables, ui(), _options.seed());
	}

	private List<Map<String, Object>> table(int id) {
		return _tables.get(Integer.valueOf(id));
	}

	private static Map<String, Object> record() {
		return new LinkedHashMap<>();
	}

	// ---- datapoints --------------------------------------------------------------------------

	private void scheduleIds() {
		for (Map<?, ?> entry : maps(_src.get("schedules"))) {
			_scheduleIds.add(string(entry.get("id")));
			if (entry.get("output") instanceof String output) {
				_scheduleOutputs.add(output);
			}
		}
	}

	private void externalBound() {
		if (_src.get("external") instanceof Map<?, ?> external) {
			for (Map<?, ?> source : maps(external.get("sources"))) {
				for (Map<?, ?> binding : maps(source.get("bindings"))) {
					_externalBound.add(string(binding.get("datapoint")));
				}
			}
		}
	}

	private void datapoints() {
		for (Map<?, ?> dp : maps(_src.get("datapoints"))) {
			String id = string(dp.get("id"));
			Dp result = new Dp(id, string(dp.get("type")), dp);
			result._label = dp.get("label");
			result._unit = dp.get("unit") instanceof String unit ? unit : null;
			int flags = 0;
			flags |= flag(dp, "readable", READABLE);
			flags |= flag(dp, "writable", WRITABLE);
			flags |= flag(dp, "transmit", TRANSMIT);
			flags |= flag(dp, "readOnStart", READ_ON_START);
			flags |= flag(dp, "persist", PERSIST);
			if (dp.get("history") instanceof Map<?, ?>) {
				flags |= HAS_HISTORY;
			}
			boolean virtual = dp.containsKey("virtual");
			boolean technology = dp.containsKey("knx") || dp.containsKey("zigbee");
			if (virtual || (!technology && !_options.harness().contains(id))) {
				flags |= APPLICATION;
			}
			if (virtual) {
				flags |= VIRTUAL;
				if (dp.containsKey("initial")) {
					result._initial = initialConstant(dp, _dps.size());
				}
			}
			result._flags = flags;
			add(result);
		}
		Map<?, ?> timeMaster = timeMaster();
		if (timeMaster != null) {
			for (int member = 0; member < TIME_MASTER.length; member++) {
				if (timeMaster.get(TIME_MASTER[member][0]) != null) {
					Dp dp = new Dp(TIME_MASTER[member][1], TIME_MASTER[member][2], null);
					dp._flags = READABLE | TRANSMIT;
					dp._owner = OWNER_TIME_MASTER;
					dp._ownerMember = member;
					add(dp);
				}
			}
		}
		for (RuleLoader.ParameterDatapoint implied : RuleLoader.parameterDatapoints(_src.get("rules"))) {
			Dp dp = new Dp(implied.name(), implied.type().externalName(), null);
			dp._flags = implied.writable() ? WRITABLE | TRANSMIT : 0;
			dp._owner = OWNER_RULES;
			add(dp);
		}
		for (String id : Schedule.timeDatapointIds(_src.get("schedules"))) {
			Dp dp = new Dp(Schedule.timeDatapointName(id), ValueType.TIME_OF_DAY.externalName(), null);
			dp._flags = WRITABLE | TRANSMIT;
			dp._owner = OWNER_SCHEDULER;
			dp._ownerIndex = _scheduleIds.indexOf(id);
			dp._ownerMember = 1;
			add(dp);
		}
		int blockIndex = 0;
		for (Map<?, ?> block : maps(_src.get("blocks"))) {
			impliedOfBlock(block, blockIndex++);
		}
	}

	/**
	 * The implied datapoints of a block (<code>spec/blocks.md</code> 6, the order of
	 * {@link BlockSet#impliedDatapoints(Object, BlockRegistry)}): the enable switch, the adjustable
	 * parameters, the outputs, the inputs bound to more than one datapoint. A type the registry does not
	 * know (an image of the corpora the controller refuses, 13.1) gets the enable switch and the
	 * mirrors of the ports its source binds.
	 */
	private void impliedOfBlock(Map<?, ?> block, int index) {
		String id = string(block.get("id"));
		boolean adjustable = !Boolean.FALSE.equals(block.get("enabledAdjustable"));
		blockDp(id, "enabled", ValueType.BOOLEAN.externalName(), adjustable ? WRITABLE | TRANSMIT : 0, index, 0, 0);
		Manifest m = manifest(block);
		Map<?, ?> inputs = block.get("inputs") instanceof Map<?, ?> map ? map : Map.of();
		if (m != null) {
			for (int p = 0; p < m.params().size(); p++) {
				Param param = m.params().get(p);
				if (param.adjustable()) {
					blockDp(id, param.id(), param.type().datapointType().externalName(), WRITABLE | TRANSMIT, index, 1, p);
				}
			}
			for (int p = 0; p < m.ports().size(); p++) {
				Port port = m.ports().get(p);
				if (!port.isInput()) {
					blockDp(id, port.id(), port.type().datapointType().externalName(), 0, index, 2, p);
				}
			}
			for (int p = 0; p < m.ports().size(); p++) {
				Port port = m.ports().get(p);
				if (port.isInput() && inputs.get(port.id()) instanceof List<?> bound && bound.size() > 1) {
					blockDp(id, port.id(), port.type().datapointType().externalName(), 0, index, 3, p);
				}
			}
		} else {
			List<String> ports = unknownPorts(block);
			int inputCount = inputs.size();
			for (int p = inputCount; p < ports.size(); p++) {
				String bound = string(((Map<?, ?>) block.get("outputs")).get(ports.get(p)));
				blockDp(id, ports.get(p), typeOf(bound), 0, index, 2, p);
			}
			for (int p = 0; p < inputCount; p++) {
				if (inputs.get(ports.get(p)) instanceof List<?> bound && bound.size() > 1) {
					blockDp(id, ports.get(p), typeOf(string(bound.get(0))), 0, index, 3, p);
				}
			}
		}
	}

	private String typeOf(String datapoint) {
		Integer index = _dpIndex.get(datapoint);
		return index == null ? ValueType.REAL.externalName() : _dps.get(index.intValue())._type;
	}

	private void blockDp(String block, String member, String type, int flags, int index, int kind, int position) {
		Dp dp = new Dp(BlockSet.mirrorName(block, member), type, null);
		dp._flags = flags;
		dp._owner = OWNER_BLOCKS;
		dp._ownerIndex = index;
		dp._ownerMember = kind * 256 + position;
		add(dp);
	}

	private void add(Dp dp) {
		_dpIndex.putIfAbsent(dp._name, Integer.valueOf(_dps.size()));
		_dps.add(dp);
	}

	private Map<?, ?> timeMaster() {
		return _src.get("knx") instanceof Map<?, ?> knx && knx.get("timeMaster") instanceof Map<?, ?> tm ? tm : null;
	}

	private int dp(Object name) {
		Integer index = _dpIndex.get(string(name));
		if (index == null) {
			throw new CompileException("datapoints", "unknown datapoint '" + name + "'");
		}
		return index.intValue();
	}

	/**
	 * Appends the initial value of the declared datapoint as a constant (9.7, 9.14): the literal value
	 * of the expression type its type is read as. The constants of the initial values come first, in
	 * datapoint order (9.18).
	 */
	private int initialConstant(Map<?, ?> dp, int index) {
		ValueType type = ValueType.fromName(string(dp.get("type")));
		Values.Literal literal = type == null ? null : Values.fromJson(type.expressionType(), dp.get("initial"));
		if (literal == null || !literal.isOk() || Values.convertForDatapoint(literal.value(), type) == null) {
			throw new CompileException("datapoints[" + index + "].initial", "not a value of type " + dp.get("type"));
		}
		return constant(literal.value());
	}

	private void datapointRecords() {
		List<Map<String, Object>> records = table(Layout.DATAPOINTS);
		for (Dp dp : _dps) {
			Map<String, Object> rec = record();
			rec.put("name", dp._name);
			rec.put("label", dp._label);
			rec.put("unit", dp._unit);
			rec.put("type", Integer.valueOf(valueType(dp._type)));
			rec.put("flags", Integer.valueOf(dp._flags));
			rec.put("owner", Integer.valueOf(dp._owner));
			rec.put("ownerIndex", Integer.valueOf(dp._ownerIndex));
			rec.put("ownerMember", Integer.valueOf(dp._ownerMember));
			rec.put("history", Integer.valueOf(dp._history));
			if (dp._initial != Layout.NONE) {
				rec.put("init", Integer.valueOf(Layout.INIT_INITIAL));
				rec.put("initial", Integer.valueOf(dp._initial));
			}
			records.add(rec);
		}
	}

	static int valueType(String name) {
		int code = VALUE_TYPES.indexOf(name);
		if (code <= 0) {
			throw new CompileException("datapoints", "unknown value type '" + name + "'");
		}
		return code;
	}

	// ---- image, settings ---------------------------------------------------------------------

	private Map<String, Object> imageRecord() {
		Map<?, ?> image = _src.get("image") instanceof Map<?, ?> map ? map : Map.of();
		Map<String, Object> rec = record();
		rec.put("projectId", image.get("projectId"));
		rec.put("revision", Long.valueOf(number(image.get("revision"))));
		rec.put("schemaVersion", image.get("schemaVersion"));
		rec.put("name", image.get("name"));
		rec.put("generator", image.get("generator"));
		rec.put("generatedAt", image.get("generatedAt"));
		table(Layout.IMAGE).add(rec);
		return rec;
	}

	private void settings() {
		if (!(_src.get("settings") instanceof Map<?, ?> settings)) {
			return;
		}
		Map<String, Object> rec = record();
		rec.put("tz", settings.get("tz"));
		if (settings.get("ntp") instanceof Map<?, ?> ntp) {
			rec.put("ntp", ntp.get("server"));
		}
		int flags = 0;
		if (settings.get("location") instanceof Map<?, ?> location) {
			flags |= 1;
			rec.put("lat", Double.valueOf(real(location.get("lat"))));
			rec.put("lon", Double.valueOf(real(location.get("lon"))));
			if (location.get("elev") != null) {
				flags |= 2;
				rec.put("elev", Long.valueOf(number(location.get("elev"))));
			}
		}
		rec.put("flags", Integer.valueOf(flags));
		if (rec.get("tz") == null && rec.get("ntp") == null && flags == 0) {
			// The image fixes no setting.
			return;
		}
		table(Layout.SETTINGS).add(rec);
	}

	// ---- locations, functions ------------------------------------------------------------------

	private void locationsAndFunctions() {
		List<Map<?, ?>> locations = maps(_src.get("locations"));
		List<String> locationIds = new ArrayList<>();
		for (Map<?, ?> location : locations) {
			locationIds.add(string(location.get("id")));
		}
		for (Map<?, ?> location : locations) {
			Map<String, Object> rec = record();
			rec.put("id", location.get("id"));
			rec.put("label", location.get("label"));
			rec.put("parent", Integer.valueOf(location.get("parent") == null ? Layout.NONE
				: index(locationIds, location.get("parent"), "locations")));
			rec.put("kind", Integer.valueOf(location.get("kind") == null ? 0
				: index(LOCATION_KINDS, location.get("kind"), "locations")));
			table(Layout.LOCATIONS).add(rec);
		}
		List<Map<String, Object>> roles = table(Layout.ROLES);
		for (Map<?, ?> function : maps(_src.get("functions"))) {
			String kind = string(function.get("kind"));
			Map<String, Object> rec = record();
			rec.put("id", function.get("id"));
			rec.put("label", function.get("label"));
			rec.put("unit", function.get("unit"));
			rec.put("room", Integer.valueOf(function.get("room") == null ? Layout.NONE
				: index(locationIds, function.get("room"), "functions")));
			rec.put("firstRole", Integer.valueOf(roles.size()));
			rec.put("kind", Integer.valueOf(index(FUNCTION_KINDS, kind, "functions")));
			rec.put("scene", Long.valueOf("scene".equals(kind) ? number(function.get("scene")) : 0));
			List<String> known = ROLES.get(kind);
			int count = 0;
			if (function.get("datapoints") instanceof Map<?, ?> refs) {
				for (Map.Entry<?, ?> role : refs.entrySet()) {
					if (!known.contains(role.getKey())) {
						// A role E-Home's validation does not know is not compiled (9.8).
						continue;
					}
					Map<String, Object> roleRec = record();
					roleRec.put("name", role.getKey());
					roleRec.put("datapoint", Integer.valueOf(dp(role.getValue())));
					roles.add(roleRec);
					count++;
				}
			}
			rec.put("roleCount", Integer.valueOf(count));
			byte[] presses = new byte[6];
			if (function.get("presses") instanceof Map<?, ?> map) {
				for (int k = 0; k < PRESS_KINDS.size(); k++) {
					if (map.get(PRESS_KINDS.get(k)) instanceof List<?> elements) {
						for (Object element : elements) {
							presses[k] |= (byte) (1 << number(element));
						}
					}
				}
			}
			rec.put("presses", presses);
			table(Layout.FUNCTIONS).add(rec);
		}
	}

	// ---- KNX ---------------------------------------------------------------------------------

	private void knx() {
		Map<?, ?> timeMaster = timeMaster();
		// The bindings in ascending order of their datapoints: the KNX datapoints, then the time master's.
		List<Integer> bindingDps = new ArrayList<>();
		List<int[]> dpts = new ArrayList<>();
		List<Integer> sends = new ArrayList<>();
		List<List<Integer>> listens = new ArrayList<>();
		for (int i = 0; i < _dps.size(); i++) {
			Dp dp = _dps.get(i);
			if (dp._source != null && dp._source.get("knx") instanceof Map<?, ?> knx) {
				bindingDps.add(Integer.valueOf(i));
				dpts.add(dpt(string(knx.get("dpt"))));
				Integer send = knx.get("send") == null ? null : Integer.valueOf(groupAddress(knx.get("send")));
				sends.add(send);
				List<Integer> listen = new ArrayList<>();
				if (knx.get("listen") instanceof List<?> list) {
					for (Object address : list) {
						Integer value = Integer.valueOf(groupAddress(address));
						if (!value.equals(send) && !listen.contains(value)) {
							listen.add(value);
						}
					}
				}
				listens.add(listen);
				dp._owner = OWNER_KNX;
				dp._ownerIndex = bindingDps.size() - 1;
			} else if (dp._owner == OWNER_TIME_MASTER) {
				bindingDps.add(Integer.valueOf(i));
				String member = TIME_MASTER[dp._ownerMember][0];
				dpts.add(new int[] { dp._ownerMember == 0 ? 10 : dp._ownerMember == 1 ? 11 : 19, 1 });
				sends.add(Integer.valueOf(groupAddress(timeMaster.get(member))));
				listens.add(List.of());
				dp._ownerIndex = bindingDps.size() - 1;
			}
		}
		TreeSet<Integer> addresses = new TreeSet<>();
		for (int b = 0; b < bindingDps.size(); b++) {
			if (sends.get(b) != null) {
				addresses.add(sends.get(b));
			}
			addresses.addAll(listens.get(b));
		}
		List<Integer> addressList = new ArrayList<>(addresses);
		List<Map<String, Object>> listeners = table(Layout.KNX_LISTENERS);
		for (Integer address : addressList) {
			Map<String, Object> rec = record();
			rec.put("address", address);
			rec.put("firstListener", Integer.valueOf(listeners.size()));
			int count = 0;
			for (int b = 0; b < bindingDps.size(); b++) {
				if (address.equals(sends.get(b)) || listens.get(b).contains(address)) {
					listeners.add(Map.of("binding", Integer.valueOf(b)));
					count++;
				}
			}
			rec.put("listenerCount", Integer.valueOf(count));
			table(Layout.KNX_ADDRESSES).add(rec);
		}
		List<Map<String, Object>> listen = table(Layout.KNX_LISTEN);
		for (int b = 0; b < bindingDps.size(); b++) {
			Map<String, Object> rec = record();
			rec.put("datapoint", bindingDps.get(b));
			rec.put("dptMain", Integer.valueOf(dpts.get(b)[0]));
			rec.put("dptSub", Integer.valueOf(dpts.get(b)[1]));
			rec.put("send", Integer.valueOf(sends.get(b) == null ? Layout.NONE : addressList.indexOf(sends.get(b))));
			rec.put("firstListen", Integer.valueOf(listen.size()));
			for (Integer address : listens.get(b)) {
				listen.add(Map.of("address", Integer.valueOf(addressList.indexOf(address))));
			}
			rec.put("listenCount", Integer.valueOf(listens.get(b).size()));
			table(Layout.KNX_BINDINGS).add(rec);
		}
		if (timeMaster != null) {
			Map<String, Object> rec = record();
			for (int member = 0; member < TIME_MASTER.length; member++) {
				Integer index = timeMaster.get(TIME_MASTER[member][0]) == null ? null : _dpIndex.get(TIME_MASTER[member][1]);
				rec.put(TIME_MASTER[member][0], Integer.valueOf(index == null ? Layout.NONE : index.intValue()));
			}
			int flags = (Boolean.FALSE.equals(timeMaster.get("atMidnight")) ? 0 : 1)
				| (Boolean.FALSE.equals(timeMaster.get("onSynchronized")) ? 0 : 2);
			rec.put("flags", Integer.valueOf(flags));
			rec.put("interval", Long.valueOf(timeMaster.get("interval") == null ? TIME_MASTER_INTERVAL
				: number(timeMaster.get("interval"))));
			table(Layout.KNX_TIME_MASTER).add(rec);
		}
	}

	/** The 16-bit value of a group address: <code>main/middle/sub</code>, <code>main/sub</code> or a number. */
	static int groupAddress(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		String[] parts = string(value).trim().split("/");
		try {
			switch (parts.length) {
				case 3:
					return Integer.parseInt(parts[0]) << 11 | Integer.parseInt(parts[1]) << 8 | Integer.parseInt(parts[2]);
				case 2:
					return Integer.parseInt(parts[0]) << 11 | Integer.parseInt(parts[1]);
				case 1:
					return Integer.parseInt(parts[0]);
				default:
					break;
			}
		} catch (NumberFormatException ex) {
			// Refused below.
		}
		throw new CompileException("knx", "not a group address: '" + value + "'");
	}

	/** The group address of the 16-bit value as the image writes it: <code>main/middle/sub</code>. */
	static String groupAddress(int value) {
		return (value >> 11 & 0x1F) + "/" + (value >> 8 & 0x7) + "/" + (value & 0xFF);
	}

	private static int[] dpt(String text) {
		String[] parts = text.trim().split("\\.");
		try {
			return new int[] { Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0 };
		} catch (NumberFormatException ex) {
			throw new CompileException("knx", "not a DPT: '" + text + "'");
		}
	}

	// ---- Zigbee, external, MQTT --------------------------------------------------------------

	private void zigbee() {
		for (int i = 0; i < _dps.size(); i++) {
			Dp dp = _dps.get(i);
			if (dp._source == null || !(dp._source.get("zigbee") instanceof Map<?, ?> zigbee)) {
				continue;
			}
			Map<String, Object> rec = record();
			rec.put("ieee", Long.valueOf(Long.parseUnsignedLong(string(zigbee.get("ieee")).replace(":", ""), 16)));
			rec.put("datapoint", Integer.valueOf(i));
			rec.put("cluster", Long.valueOf(number(zigbee.get("cluster"))));
			boolean client = Boolean.TRUE.equals(zigbee.get("client"));
			rec.put("attribute", Long.valueOf(client || zigbee.get("attribute") == null ? 0 : number(zigbee.get("attribute"))));
			rec.put("command", Long.valueOf(zigbee.get("command") == null ? Layout.NONE : number(zigbee.get("command"))));
			int flags = client ? 1 : 0;
			if (zigbee.get("reporting") instanceof Map<?, ?> reporting) {
				if (reporting.get("min") != null) {
					flags |= 2;
					rec.put("reportMin", Long.valueOf(number(reporting.get("min"))));
				}
				if (reporting.get("max") != null) {
					flags |= 4;
					rec.put("reportMax", Long.valueOf(number(reporting.get("max"))));
				}
				if (reporting.get("change") != null) {
					flags |= 8;
					rec.put("reportChange", Double.valueOf(real(reporting.get("change"))));
				}
			}
			rec.put("endpoint", Long.valueOf(number(zigbee.get("endpoint"))));
			rec.put("flags", Integer.valueOf(flags));
			dp._owner = OWNER_ZIGBEE;
			dp._ownerIndex = table(Layout.ZIGBEE_BINDINGS).size();
			table(Layout.ZIGBEE_BINDINGS).add(rec);
		}
	}

	private void external() {
		if (!(_src.get("external") instanceof Map<?, ?> external)) {
			return;
		}
		List<Map<String, Object>> bindings = table(Layout.EXTERNAL_BINDINGS);
		for (Map<?, ?> source : maps(external.get("sources"))) {
			Map<String, Object> rec = record();
			rec.put("id", source.get("id"));
			rec.put("url", source.get("url"));
			rec.put("preset", source.get("preset"));
			rec.put("interval", Long.valueOf(source.get("interval") == null ? EXTERNAL_INTERVAL : number(source.get("interval"))));
			rec.put("firstBinding", Integer.valueOf(bindings.size()));
			int count = 0;
			for (Map<?, ?> binding : maps(source.get("bindings"))) {
				int index = dp(binding.get("datapoint"));
				Dp dp = _dps.get(index);
				dp._owner = OWNER_EXTERNAL;
				dp._ownerIndex = bindings.size();
				Map<String, Object> b = record();
				b.put("datapoint", Integer.valueOf(index));
				b.put("path", binding.get("path"));
				b.put("variable", binding.get("variable"));
				b.put("unit", binding.get("unit"));
				b.put("scale", Double.valueOf(binding.get("scale") == null ? 1 : real(binding.get("scale"))));
				b.put("offset", Double.valueOf(binding.get("offset") == null ? 0 : real(binding.get("offset"))));
				bindings.add(b);
				count++;
			}
			rec.put("bindingCount", Integer.valueOf(count));
			table(Layout.EXTERNAL_SOURCES).add(rec);
		}
	}

	private void mqtt() {
		if (!(_src.get("mqtt") instanceof Map<?, ?> mqtt)) {
			return;
		}
		Map<?, ?> discovery = mqtt.get("discovery") instanceof Map<?, ?> map ? map : Map.of();
		Map<?, ?> history = mqtt.get("history") instanceof Map<?, ?> map ? map : Map.of();
		Map<String, Object> rec = record();
		rec.put("broker", mqtt.get("broker"));
		rec.put("username", mqtt.get("username"));
		rec.put("baseTopic", mqtt.get("baseTopic"));
		rec.put("discoveryPrefix", discovery.get("prefix"));
		rec.put("historyInterval", Long.valueOf(history.get("interval") == null ? MQTT_HISTORY_INTERVAL
			: number(history.get("interval"))));
		int flags = (Boolean.FALSE.equals(mqtt.get("enabled")) ? 0 : 1) | (Boolean.FALSE.equals(mqtt.get("retain")) ? 0 : 2)
			| (Boolean.FALSE.equals(discovery.get("enabled")) ? 0 : 4) | (Boolean.FALSE.equals(history.get("enabled")) ? 0 : 8);
		rec.put("flags", Integer.valueOf(flags));
		table(Layout.MQTT).add(rec);
	}

	// ---- schedules ---------------------------------------------------------------------------

	private void schedules() {
		List<Map<String, Object>> dates = table(Layout.SCHEDULE_DATES);
		List<Map<String, Object>> bounds = table(Layout.SCHEDULE_BOUNDS);
		int index = 0;
		for (Map<?, ?> entry : maps(_src.get("schedules"))) {
			String id = string(entry.get("id"));
			String kind = string(entry.get("kind"));
			Map<String, Object> rec = record();
			rec.put("id", id);
			rec.put("label", entry.get("label"));
			rec.put("kind", Integer.valueOf(index(SCHEDULE_KINDS, kind, "schedules")));
			int flags = (Boolean.FALSE.equals(entry.get("enabled")) ? 0 : 1)
				| (Boolean.FALSE.equals(entry.get("enabledAdjustable")) ? 0 : 2)
				| ("catchUp".equals(entry.get("missed")) ? 4 : 0) | (entry.get("notBefore") != null ? 8 : 0)
				| (entry.get("notAfter") != null ? 16 : 0);
			rec.put("flags", Integer.valueOf(flags));
			if (List.of("weekly", "astro", "interval").contains(kind)) {
				rec.put("days", Long.valueOf(entry.get("days") == null ? Schedule.ALL_DAYS : Schedule.Member.DAYS.scalar(entry.get("days")).longValue()));
			}
			if ("astro".equals(kind)) {
				rec.put("sun", Integer.valueOf(index(SUN_EVENTS, entry.get("sun"), "schedules")));
				rec.put("offset", Long.valueOf(entry.get("offset") == null ? 0 : number(entry.get("offset")) / 1000));
			}
			if (entry.get("output") != null) {
				int output = dp(entry.get("output"));
				rec.put("output", Integer.valueOf(output));
				Dp dp = _dps.get(output);
				dp._owner = OWNER_SCHEDULER;
				dp._ownerIndex = index;
				dp._ownerMember = 0;
			} else {
				rec.put("output", Integer.valueOf(Layout.NONE));
			}
			Integer time = _dpIndex.get(Schedule.timeDatapointName(id));
			rec.put("timeDatapoint", Integer.valueOf(time == null ? Layout.NONE : time.intValue()));
			int adjustable = 0;
			for (Object name : list(entry.get("adjustable"))) {
				Schedule.Member member = Schedule.Member.fromName(string(name));
				if (member != null) {
					adjustable |= member.bit();
				}
			}
			rec.put("adjustable", Integer.valueOf(adjustable));
			if ("interval".equals(kind)) {
				rec.put("every", Long.valueOf(number(entry.get("every")) / 60000));
			}
			rec.put("firstDate", Integer.valueOf(dates.size()));
			int dateCount = 0;
			if ("dates".equals(kind)) {
				for (Map<?, ?> date : maps(entry.get("dates"))) {
					Map<String, Object> d = record();
					d.put("year", Long.valueOf(number(date.get("year"))));
					d.put("month", Long.valueOf(number(date.get("month"))));
					d.put("day", Long.valueOf(number(date.get("day"))));
					dates.add(d);
					dateCount++;
				}
			}
			rec.put("dateCount", Integer.valueOf(dateCount));
			rec.put("firstBound", Integer.valueOf(bounds.size()));
			int boundCount = 0;
			Map<?, ?> boundJson = entry.get("bounds") instanceof Map<?, ?> map ? map : Map.of();
			for (Schedule.Member member : Schedule.Member.values()) {
				if (!(boundJson.get(member.externalName()) instanceof Map<?, ?> bound)) {
					continue;
				}
				Map<String, Object> b = record();
				b.put("member", Integer.valueOf(member.bit()));
				Long min = bound.get("min") == null ? null : member.scalar(bound.get("min"));
				Long max = bound.get("max") == null ? null : member.scalar(bound.get("max"));
				b.put("min", Long.valueOf(min == null ? member.rangeMin() : min.longValue()));
				b.put("max", Long.valueOf(max == null ? member.rangeMax() : max.longValue()));
				long step = 0;
				if (bound.get("step") != null) {
					step = member.isTime() ? number(bound.get("step")) : number(bound.get("step")) / 1000;
				}
				b.put("step", Long.valueOf(step));
				bounds.add(b);
				boundCount++;
			}
			rec.put("boundCount", Integer.valueOf(boundCount));
			if ("yearly".equals(kind)) {
				rec.put("month", Long.valueOf(number(entry.get("month"))));
				rec.put("day", Long.valueOf(number(entry.get("day"))));
			}
			if (List.of("weekly", "yearly", "dates").contains(kind)) {
				rec.put("time", seconds(entry.get("time")));
			}
			if ("astro".equals(kind)) {
				rec.put("notBefore", entry.get("notBefore") == null ? null : seconds(entry.get("notBefore")));
				rec.put("notAfter", entry.get("notAfter") == null ? null : seconds(entry.get("notAfter")));
			}
			if ("interval".equals(kind)) {
				rec.put("from", seconds(entry.get("from")));
				rec.put("until", seconds(entry.get("until")));
			}
			if ("once".equals(kind) && entry.get("at") instanceof Map<?, ?> at) {
				rec.put("atYear", Long.valueOf(number(at.get("year"))));
				rec.put("atMonth", Long.valueOf(number(at.get("month"))));
				rec.put("atDay", Long.valueOf(number(at.get("day"))));
				rec.put("atTime", Long.valueOf(number(at.get("hour")) * 3600 + number(at.get("minute")) * 60
					+ number(at.get("second"))));
			}
			table(Layout.SCHEDULES).add(rec);
			index++;
		}
	}

	private static Long seconds(Object time) {
		if (!(time instanceof Map<?, ?> map)) {
			return Long.valueOf(0);
		}
		return Long.valueOf(number(map.get("hour")) * 3600 + number(map.get("minute")) * 60 + number(map.get("second")));
	}

	// ---- rules -------------------------------------------------------------------------------

	private RuleSet loadRules() {
		if (!(_src.get("rules") instanceof List<?> list) || list.isEmpty()) {
			return null;
		}
		_ruleJson = maps(list);
		RuleLoader.DatapointDirectory datapoints = new RuleLoader.DatapointDirectory() {
			@Override
			public ValueType type(String name) {
				Integer index = _dpIndex.get(name);
				return index == null ? null : ValueType.fromName(_dps.get(index.intValue())._type);
			}

			@Override
			public boolean writable(String name) {
				Integer index = _dpIndex.get(name);
				return index != null && (_dps.get(index.intValue())._flags & WRITABLE) != 0;
			}

			@Override
			public boolean ruleWritable(String name) {
				Integer index = _dpIndex.get(name);
				return index != null && (_dps.get(index.intValue())._flags & (WRITABLE | APPLICATION)) != 0;
			}
		};
		RuleLoader.ScheduleDirectory schedules = new RuleLoader.ScheduleDirectory() {
			@Override
			public boolean hasSchedule(String id) {
				return _scheduleIds.contains(id);
			}

			@Override
			public boolean ownsOutput(String datapoint) {
				return _scheduleOutputs.contains(datapoint);
			}

			@Override
			public boolean technologyReads(String datapoint) {
				if (_options.reads() != null) {
					return _options.reads().test(datapoint);
				}
				Integer index = _dpIndex.get(datapoint);
				if (index == null) {
					return true;
				}
				Map<?, ?> source = _dps.get(index.intValue())._source;
				return source != null && (source.containsKey("knx") || source.containsKey("zigbee")
					|| _externalBound.contains(datapoint));
			}
		};
		try {
			return RuleLoader.load(_src.get("rules"), datapoints, schedules);
		} catch (LoadException ex) {
			RuleLoader.Refusal refusal = ex.getRefusal();
			throw new CompileException(refusal.path(), refusal.error().externalName() + ": " + refusal.text());
		}
	}

	private void rules(RuleSet set) {
		if (set == null) {
			return;
		}
		for (int r = 0; r < set.rules().size(); r++) {
			rule(set, r);
		}
	}

	private void rule(RuleSet set, int r) {
		Rule rule = set.rules().get(r);
		Map<?, ?> json = _ruleJson.get(r);
		Map<String, Object> rec = record();
		rec.put("id", rule.id());
		rec.put("name", json.get("name"));
		table(Layout.RULES).add(rec);

		// The parameters, enabled first, each with its constants.
		List<Map<?, ?>> parameterJson = maps(json.get("parameters"));
		rec.put("firstParameter", Integer.valueOf(table(Layout.RULE_PARAMETERS).size()));
		rec.put("parameterCount", Integer.valueOf(rule.parameters().size()));
		boolean stateParameter = false;
		for (int p = 0; p < rule.parameters().size(); p++) {
			Parameter parameter = rule.parameters().get(p);
			Map<?, ?> pj = p == 0 ? Map.of() : parameterJson.get(p - 1);
			stateParameter |= p > 0 && RuleSet.STATE_DATAPOINT.equals(parameter.id());
			Map<String, Object> par = record();
			par.put("id", parameter.id());
			par.put("label", p == 0 ? null : pj.get("label"));
			par.put("unit", parameter.unit());
			par.put("type", Integer.valueOf(parameter.type().ordinal()));
			par.put("adjustable", Integer.valueOf(parameter.adjustable() ? 1 : 0));
			table(Layout.RULE_PARAMETERS).add(par);
			par.put("default", Integer.valueOf(constant(parameter.defaultValue())));
			par.put("min", Integer.valueOf(parameter.min() == null ? Layout.NONE : constant(parameter.min())));
			par.put("max", Integer.valueOf(parameter.max() == null ? Layout.NONE : constant(parameter.max())));
			par.put("step", Integer.valueOf(pj.get("step") instanceof Number step ? constant(step(parameter.type(), step))
				: Layout.NONE));
			List<Map<?, ?>> choiceJson = maps(pj.get("choices"));
			par.put("firstChoice", Integer.valueOf(table(Layout.RULE_CHOICES).size()));
			par.put("choiceCount", Integer.valueOf(parameter.choices().size()));
			for (int c = 0; c < parameter.choices().size(); c++) {
				Map<String, Object> choice = record();
				choice.put("value", Integer.valueOf(constant(parameter.choices().get(c))));
				choice.put("label", c < choiceJson.size() ? choiceJson.get(c).get("label") : null);
				table(Layout.RULE_CHOICES).add(choice);
			}
			int datapoint = dp(RuleLoader.parameterDatapointName(rule.id(), parameter.id()));
			par.put("datapoint", Integer.valueOf(datapoint));
			Dp dp = _dps.get(datapoint);
			dp._ownerIndex = r;
			dp._ownerMember = p;
		}

		// The timers, each with its duration.
		rec.put("firstTimer", Integer.valueOf(table(Layout.RULE_TIMERS).size()));
		rec.put("timerCount", Integer.valueOf(rule.timers().size()));
		for (RuleSet.Timer timer : rule.timers()) {
			Map<String, Object> t = record();
			t.put("id", timer.id());
			table(Layout.RULE_TIMERS).add(t);
			t.put("duration", Integer.valueOf(expression(timer.duration())));
		}

		// The states, each with its entry and exit actions.
		List<Map<?, ?>> stateJson = maps(json.get("states"));
		rec.put("firstState", Integer.valueOf(table(Layout.RULE_STATES).size()));
		rec.put("stateCount", Integer.valueOf(rule.states().size()));
		for (int s = 0; s < rule.states().size(); s++) {
			RuleSet.State state = rule.states().get(s);
			Map<String, Object> st = record();
			st.put("id", state.id());
			st.put("label", stateJson.get(s).get("label"));
			table(Layout.RULE_STATES).add(st);
			st.put("firstEntry", Integer.valueOf(actions(state.entry())));
			st.put("entryCount", Integer.valueOf(state.entry().size()));
			st.put("firstExit", Integer.valueOf(actions(state.exit())));
			st.put("exitCount", Integer.valueOf(state.exit().size()));
		}
		rec.put("initialState", Integer.valueOf(rule.initialState() < 0 ? Layout.NONE8 : rule.initialState()));

		// The reactions, each with its triggers, condition and actions.
		rec.put("firstReaction", Integer.valueOf(table(Layout.RULE_REACTIONS).size()));
		rec.put("reactionCount", Integer.valueOf(rule.reactions().size()));
		for (Reaction reaction : rule.reactions()) {
			Map<String, Object> re = record();
			table(Layout.RULE_REACTIONS).add(re);
			re.put("firstTrigger", Integer.valueOf(table(Layout.RULE_TRIGGERS).size()));
			re.put("triggerCount", Integer.valueOf(reaction.triggers().size()));
			List<Map<String, Object>> triggers = new ArrayList<>();
			for (Trigger trigger : reaction.triggers()) {
				Map<String, Object> tr = trigger(trigger);
				table(Layout.RULE_TRIGGERS).add(tr);
				triggers.add(tr);
			}
			for (int t = 0; t < triggers.size(); t++) {
				Trigger trigger = reaction.triggers().get(t);
				if (trigger.kind() == TriggerKind.MATCH) {
					triggers.get(t).put("ref", Integer.valueOf(expression(trigger.condition())));
				} else if (trigger.kind() == TriggerKind.SILENCE) {
					triggers.get(t).put("ref2", Integer.valueOf(expression(trigger.after())));
				}
			}
			re.put("to", Integer.valueOf(reaction.to() < 0 ? Layout.NONE8 : reaction.to()));
			re.put("condition", Integer.valueOf(expression(reaction.condition())));
			re.put("from", Integer.valueOf(reaction.fromMask() < 0 ? Layout.NONE : reaction.fromMask()));
			re.put("firstAction", Integer.valueOf(actions(reaction.actions())));
			re.put("actionCount", Integer.valueOf(reaction.actions().size()));
			re.put("firstElse", Integer.valueOf(actions(reaction.elseActions())));
			re.put("elseCount", Integer.valueOf(reaction.elseActions().size()));
		}

		// The timeline: plans with their points, then the assignments.
		RuleSet.Timeline timeline = rule.timeline();
		if (timeline == null) {
			rec.put("timeline", Integer.valueOf(Layout.NONE));
		} else {
			Map<?, ?> timelineJson = (Map<?, ?>) json.get("timeline");
			List<Map<?, ?>> planJson = maps(timelineJson.get("plans"));
			Map<String, Object> tl = record();
			rec.put("timeline", Integer.valueOf(table(Layout.RULE_TIMELINES).size()));
			table(Layout.RULE_TIMELINES).add(tl);
			tl.put("firstPlan", Integer.valueOf(table(Layout.RULE_PLANS).size()));
			tl.put("planCount", Integer.valueOf(timeline.plans().size()));
			tl.put("flags", Integer.valueOf(timeline.adjustable() ? 1 : 0));
			for (int p = 0; p < timeline.plans().size(); p++) {
				RuleSet.Plan plan = timeline.plans().get(p);
				Map<String, Object> pl = record();
				pl.put("id", plan.id());
				pl.put("label", planJson.get(p).get("label"));
				pl.put("firstPoint", Integer.valueOf(table(Layout.RULE_POINTS).size()));
				pl.put("pointCount", Integer.valueOf(plan.points().size()));
				table(Layout.RULE_PLANS).add(pl);
				for (RuleSet.Point point : plan.points()) {
					Map<String, Object> pt = record();
					pt.put("state", Integer.valueOf(point.state()));
					if (point.sun() == null) {
						pt.put("sun", Integer.valueOf(0));
						pt.put("seconds", Integer.valueOf(point.time()));
					} else {
						pt.put("sun", Integer.valueOf(1 + point.sun().ordinal()));
						pt.put("seconds", Integer.valueOf(point.offset()));
					}
					int flags = 0;
					if (point.notBefore() >= 0) {
						flags |= 1;
						pt.put("notBefore", Integer.valueOf(point.notBefore()));
					}
					if (point.notAfter() >= 0) {
						flags |= 2;
						pt.put("notAfter", Integer.valueOf(point.notAfter()));
					}
					pt.put("flags", Integer.valueOf(flags));
					table(Layout.RULE_POINTS).add(pt);
				}
			}
			tl.put("firstAssignment", Integer.valueOf(table(Layout.RULE_ASSIGNMENTS).size()));
			tl.put("assignmentCount", Integer.valueOf(timeline.assignments().size()));
			for (RuleSet.Assignment assignment : timeline.assignments()) {
				Map<String, Object> as = record();
				as.put("plan", Integer.valueOf(assignment.plan()));
				as.put("days", Integer.valueOf(assignment.days()));
				table(Layout.RULE_ASSIGNMENTS).add(as);
			}
		}

		Integer state = rule.states().isEmpty() || stateParameter ? null
			: _dpIndex.get(RuleLoader.stateDatapointName(rule.id()));
		rec.put("stateDatapoint", Integer.valueOf(state == null ? Layout.NONE : state.intValue()));
		if (state != null) {
			Dp dp = _dps.get(state.intValue());
			dp._ownerIndex = r;
			dp._ownerMember = STATE_MEMBER;
		}
	}

	/** The constant of a parameter's step: of its type where that is a number, else a real. */
	private static Val step(Type type, Number step) {
		switch (type) {
			case DURATION:
				return Val.ofDuration(step.longValue());
			case INTEGER:
				if (Values.integral(step.doubleValue())) {
					return Val.ofInt(step.longValue());
				}
				return Val.ofReal(step.doubleValue());
			default:
				return Val.ofReal(step.doubleValue());
		}
	}

	private Map<String, Object> trigger(Trigger trigger) {
		Map<String, Object> rec = record();
		rec.put("kind", Integer.valueOf(trigger.kind().ordinal()));
		int flags = 0;
		int ref = Layout.NONE;
		switch (trigger.kind()) {
			case DATAPOINT:
				flags = (trigger.onUpdate() ? 1 : 0) | (trigger.originReport() ? 2 : 0) | (trigger.originCommand() ? 4 : 0);
				ref = dp(trigger.name());
				break;
			case SCHEDULE:
				ref = _scheduleIds.indexOf(trigger.name());
				break;
			case TIMER:
			case PARAMETER:
				ref = trigger.ref();
				break;
			case START:
				flags = (trigger.reasons().contains(StartReason.BOOT) ? 1 : 0)
					| (trigger.reasons().contains(StartReason.IMAGE) ? 2 : 0)
					| (trigger.reasons().contains(StartReason.ENABLE) ? 4 : 0);
				break;
			case SILENCE:
				ref = dp(trigger.name());
				break;
			default:
				break;
		}
		rec.put("flags", Integer.valueOf(flags));
		rec.put("ref", Integer.valueOf(ref));
		rec.put("ref2", Integer.valueOf(Layout.NONE));
		return rec;
	}

	/**
	 * Appends an action list: the actions together, then the log parts and expressions of each action
	 * in order (9.18).
	 *
	 * @return The index of the first action.
	 */
	private int actions(List<Action> list) {
		List<Map<String, Object>> actions = table(Layout.RULE_ACTIONS);
		int first = actions.size();
		List<Map<String, Object>> records = new ArrayList<>();
		for (Action action : list) {
			Map<String, Object> rec = record();
			rec.put("kind", Integer.valueOf(action.kind().ordinal()));
			int flags = 0;
			int target = Layout.NONE;
			switch (action.kind()) {
				case WRITE:
					flags = action.onlyOnChange() ? 1 : 0;
					target = dp(action.datapoint());
					break;
				case READ:
					target = dp(action.datapoint());
					break;
				case START_TIMER:
				case RESTART_TIMER:
				case CANCEL_TIMER:
				case SET_PARAMETER:
					target = action.ref();
					break;
				case LOG:
					flags = action.level().ordinal();
					break;
				default:
					break;
			}
			rec.put("flags", Integer.valueOf(flags));
			rec.put("target", Integer.valueOf(target));
			rec.put("expr", Integer.valueOf(Layout.NONE));
			actions.add(rec);
			records.add(rec);
		}
		for (int i = 0; i < list.size(); i++) {
			Action action = list.get(i);
			Map<String, Object> rec = records.get(i);
			if (action.kind() == ActionKind.LOG) {
				List<Map<String, Object>> parts = table(Layout.RULE_LOG_PARTS);
				rec.put("target", Integer.valueOf(parts.size()));
				rec.put("count", Integer.valueOf(action.parts().size()));
				List<Map<String, Object>> partRecords = new ArrayList<>();
				for (Object part : action.parts()) {
					Map<String, Object> pr = record();
					pr.put("text", part instanceof String text ? text : null);
					pr.put("expr", Integer.valueOf(Layout.NONE));
					parts.add(pr);
					partRecords.add(pr);
				}
				for (int k = 0; k < action.parts().size(); k++) {
					if (action.parts().get(k) instanceof Node node) {
						partRecords.get(k).put("expr", Integer.valueOf(expression(node)));
					}
				}
			} else if (action.expr() != null) {
				rec.put("expr", Integer.valueOf(expression(action.expr())));
			}
		}
		return first;
	}

	// ---- expressions -------------------------------------------------------------------------

	/**
	 * Appends the nodes of an expression in the allocation order of 9.14: the root first; the children
	 * of a node as one block when the node is written, before their subtrees.
	 *
	 * @return The index of the root, {@link Layout#NONE} for none.
	 */
	private int expression(Node root) {
		if (root == null) {
			return Layout.NONE;
		}
		List<Map<String, Object>> nodes = table(Layout.NODES);
		int at = nodes.size();
		nodes.add(record());
		node(root, at);
		return at;
	}

	private int node(Node node, int at) {
		List<Map<String, Object>> nodes = table(Layout.NODES);
		Map<String, Object> rec = nodes.get(at);
		rec.put("kind", Integer.valueOf(node.kind().ordinal()));
		int op = node.kind() == Node.Kind.CALL ? node.fn().ordinal() : node.op() == null ? 0 : node.op().ordinal();
		rec.put("op", Integer.valueOf(op));
		// A timer node has no value; its type is written 0 (spec/runtime-image.md 10.6 allows only codes).
		rec.put("type", Integer.valueOf(node.type() == null ? 0 : node.type().ordinal()));
		rec.put("argc", Integer.valueOf(node.args().size()));
		byte[] lit = new byte[8];
		int a = Layout.NONE;
		switch (node.kind()) {
			case LITERAL:
				if (isLong(node.type())) {
					a = constant(node.literal());
				} else {
					payload(node.literal(), lit);
				}
				break;
			case DATAPOINT:
				a = dp(node.name());
				break;
			case PARAMETER:
			case TIMER:
				a = node.ref();
				break;
			default:
				if (node.kind() == Node.Kind.CALL && node.fn() == Node.Fn.STATE) {
					ByteBuffer.wrap(lit).order(ByteOrder.LITTLE_ENDIAN).putInt(0, node.ref());
				}
				break;
		}
		rec.put("lit", lit);
		int size = 1;
		if (!node.args().isEmpty()) {
			int first = nodes.size();
			for (int i = 0; i < node.args().size(); i++) {
				nodes.add(record());
			}
			a = first;
			for (int i = 0; i < node.args().size(); i++) {
				size += node(node.args().get(i), first + i);
			}
		}
		rec.put("a", Integer.valueOf(a));
		rec.put("size", Integer.valueOf(size));
		return size;
	}

	/** Whether a literal of the type is a constant (9.14): string, raw, dateTime. */
	static boolean isLong(Type type) {
		return type == Type.STRING || type == Type.RAW || type == Type.DATE_TIME;
	}

	/** Appends the value as a constant (the firmware's <code>hc::rules::Val</code>, 9.14). */
	private int constant(Val value) {
		Map<String, Object> rec = record();
		rec.put("type", Integer.valueOf(value.type().ordinal()));
		rec.put("present", Integer.valueOf(1));
		byte[] payload = new byte[32];
		int length = 0;
		switch (value.type()) {
			case STRING: {
				byte[] bytes = value.bytes();
				System.arraycopy(bytes, 0, payload, 0, bytes.length);
				length = bytes.length;
				break;
			}
			case RAW: {
				byte[] bytes = value.bytes();
				System.arraycopy(bytes, 0, payload, 0, bytes.length);
				length = bytes.length;
				break;
			}
			case DATE_TIME: {
				Val.DateTime dt = value.dateTime();
				ByteBuffer out = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
				out.putShort(0, (short) dt.year());
				out.put(2, (byte) dt.month());
				out.put(3, (byte) dt.day());
				out.put(4, (byte) dt.dayOfWeek());
				out.put(5, (byte) dt.hour());
				out.put(6, (byte) dt.minute());
				out.put(7, (byte) dt.second());
				out.putShort(8, (short) dt.flags());
				break;
			}
			default:
				payload(value, payload);
				break;
		}
		rec.put("length", Integer.valueOf(length));
		rec.put("value", payload);
		List<Map<String, Object>> constants = table(Layout.CONSTANTS);
		constants.add(rec);
		return constants.size() - 1;
	}

	/** Writes the value of a short type as the node payload of 9.14. */
	private static void payload(Val value, byte[] out) {
		ByteBuffer buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
		switch (value.type()) {
			case BOOLEAN:
				buffer.put(0, (byte) (value.bool() ? 1 : 0));
				break;
			case INTEGER:
			case DURATION:
				buffer.putLong(0, value.integer());
				break;
			case REAL:
				buffer.putDouble(0, value.real());
				break;
			case ENUMERATION:
				buffer.putInt(0, (int) value.integer());
				break;
			case TIME_OF_DAY: {
				Val.TimeOfDay time = value.time();
				buffer.put(0, (byte) time.dayOfWeek());
				buffer.put(1, (byte) time.hour());
				buffer.put(2, (byte) time.minute());
				buffer.put(3, (byte) time.second());
				break;
			}
			case DATE: {
				Val.Date date = value.date();
				buffer.putShort(0, (short) date.year());
				buffer.put(2, (byte) date.month());
				buffer.put(3, (byte) date.day());
				break;
			}
			case CONTROLLED:
				buffer.put(0, (byte) (value.controlled().control() ? 1 : 0));
				buffer.put(1, (byte) value.controlled().value());
				break;
			case COLOR: {
				Val.Color color = value.color();
				buffer.put(0, (byte) color.red());
				buffer.put(1, (byte) color.green());
				buffer.put(2, (byte) color.blue());
				buffer.put(3, (byte) color.white());
				buffer.put(4, (byte) color.valid());
				break;
			}
			default:
				throw new CompileException("constants", "no short payload for " + value.type());
		}
	}

	// ---- blocks ------------------------------------------------------------------------------

	private Manifest manifest(Map<?, ?> block) {
		int[] version = block.get("version") instanceof String text ? BlockSet.parseVersion(text) : null;
		BlockType type = version == null ? null : _options.registry().find(string(block.get("type")), version[0]);
		return type == null ? null : type.manifest();
	}

	/** The ports of a block of an unknown type: those its source binds, the inputs first. */
	private static List<String> unknownPorts(Map<?, ?> block) {
		List<String> result = new ArrayList<>();
		if (block.get("inputs") instanceof Map<?, ?> inputs) {
			inputs.keySet().forEach(key -> result.add(key.toString()));
		}
		if (block.get("outputs") instanceof Map<?, ?> outputs) {
			outputs.keySet().forEach(key -> result.add(key.toString()));
		}
		return result;
	}

	private void blocks() {
		List<Map<String, Object>> ports = table(Layout.BLOCK_PORTS);
		List<Map<String, Object>> bound = table(Layout.BLOCK_BOUND);
		List<Map<String, Object>> parameters = table(Layout.BLOCK_PARAMETERS);
		for (Map<?, ?> block : maps(_src.get("blocks"))) {
			String id = string(block.get("id"));
			int[] version = BlockSet.parseVersion(string(block.get("version")));
			if (version == null) {
				throw new CompileException("blocks", "block '" + id + "': no version");
			}
			Manifest m = manifest(block);
			Map<?, ?> inputs = block.get("inputs") instanceof Map<?, ?> map ? map : Map.of();
			Map<?, ?> outputs = block.get("outputs") instanceof Map<?, ?> map ? map : Map.of();
			Map<?, ?> aggregation = block.get("aggregation") instanceof Map<?, ?> map ? map : Map.of();
			Map<?, ?> values = block.get("parameters") instanceof Map<?, ?> map ? map : Map.of();
			Map<String, Object> rec = record();
			rec.put("id", id);
			rec.put("type", block.get("type"));
			rec.put("label", block.get("label"));
			rec.put("major", Integer.valueOf(version[0]));
			rec.put("minor", Integer.valueOf(version[1]));
			rec.put("firstPort", Integer.valueOf(ports.size()));
			rec.put("firstParameter", Integer.valueOf(parameters.size()));
			rec.put("flags", Integer.valueOf((Boolean.FALSE.equals(block.get("enabled")) ? 0 : 1)
				| (Boolean.FALSE.equals(block.get("enabledAdjustable")) ? 0 : 2)));
			rec.put("enabledDatapoint", Integer.valueOf(dp(BlockSet.mirrorName(id, "enabled"))));
			List<String> portIds = new ArrayList<>();
			List<Boolean> inputFlags = new ArrayList<>();
			List<Aggregation> defaults = new ArrayList<>();
			if (m != null) {
				for (Port port : m.ports()) {
					portIds.add(port.id());
					inputFlags.add(Boolean.valueOf(port.isInput()));
					defaults.add(port.defaultAggregation());
				}
			} else {
				for (String port : unknownPorts(block)) {
					portIds.add(port);
					inputFlags.add(Boolean.valueOf(inputs.containsKey(port)));
					defaults.add(Aggregation.MEAN);
				}
			}
			for (int p = 0; p < portIds.size(); p++) {
				String port = portIds.get(p);
				boolean input = inputFlags.get(p).booleanValue();
				List<Object> names = new ArrayList<>();
				Object binding = input ? inputs.get(port) : outputs.get(port);
				if (binding instanceof List<?> list) {
					names.addAll(list);
				} else if (binding != null) {
					names.add(binding);
				}
				Map<String, Object> pr = record();
				pr.put("id", port);
				pr.put("firstBound", Integer.valueOf(bound.size()));
				pr.put("boundCount", Integer.valueOf(names.size()));
				for (Object name : names) {
					bound.add(Map.of("datapoint", Integer.valueOf(dp(name))));
				}
				int code = 0;
				Integer mirror = null;
				if (!input) {
					mirror = _dpIndex.get(BlockSet.mirrorName(id, port));
				} else if (names.size() > 1) {
					String named = aggregation.get(port) instanceof String text ? text : null;
					code = named == null ? defaults.get(p).ordinal() : aggregationCode(named);
					mirror = _dpIndex.get(BlockSet.mirrorName(id, port));
				}
				pr.put("aggregation", Integer.valueOf(code));
				pr.put("mirror", Integer.valueOf(mirror == null ? Layout.NONE : mirror.intValue()));
				ports.add(pr);
			}
			rec.put("portCount", Integer.valueOf(portIds.size()));
			int count = 0;
			if (m != null) {
				for (Param param : m.params()) {
					Map<String, Object> pr = record();
					pr.put("id", param.id());
					Integer mirror = param.adjustable() ? _dpIndex.get(BlockSet.mirrorName(id, param.id())) : null;
					pr.put("datapoint", Integer.valueOf(mirror == null ? Layout.NONE : mirror.intValue()));
					if (values.containsKey(param.id())) {
						pr.put("flags", Integer.valueOf(1));
						pr.put("value", Double.valueOf(paramValue(param, values.get(param.id()))));
					}
					parameters.add(pr);
					count++;
				}
			} else {
				for (Map.Entry<?, ?> value : values.entrySet()) {
					Map<String, Object> pr = record();
					pr.put("id", value.getKey());
					pr.put("datapoint", Integer.valueOf(Layout.NONE));
					pr.put("flags", Integer.valueOf(1));
					pr.put("value", Double.valueOf(rawParamValue(value.getValue())));
					parameters.add(pr);
					count++;
				}
			}
			rec.put("parameterCount", Integer.valueOf(count));
			table(Layout.BLOCKS).add(rec);
		}
	}

	/** The code of a named aggregation (9.15), the names of {@link Aggregation}. */
	private static int aggregationCode(String name) {
		for (Aggregation aggregation : Aggregation.values()) {
			if (aggregation.externalName().equals(name)) {
				return aggregation.ordinal();
			}
		}
		throw new CompileException("blocks", "unknown aggregation '" + name + "'");
	}

	private static double paramValue(Param param, Object json) {
		Double value = BlockSet.paramFromJson(param, json, new BlockSet.ParamProblem[1]);
		return value != null ? value.doubleValue() : rawParamValue(json);
	}

	private static double rawParamValue(Object json) {
		if (json instanceof Number number) {
			return number.doubleValue();
		}
		if (json instanceof Boolean b) {
			return b.booleanValue() ? 1 : 0;
		}
		throw new CompileException("blocks", "a parameter value that is no number: " + json);
	}

	// ---- history -----------------------------------------------------------------------------

	private void history(Map<String, Object> header) {
		List<Map<String, Object>> rings = table(Layout.HISTORY);
		List<Map<String, Object>> archives = table(Layout.ARCHIVES);
		long ram = 0;
		long flash = 0;
		for (int i = 0; i < _dps.size(); i++) {
			Dp dp = _dps.get(i);
			if (dp._source == null || !(dp._source.get("history") instanceof Map<?, ?> history)) {
				continue;
			}
			long retention = number(history.get("retention"));
			long spacing = number(history.get("spacing"));
			ram += 8 * ImageValidator.samples(retention, spacing);
			Map<String, Object> rec = record();
			rec.put("datapoint", Integer.valueOf(i));
			rec.put("retention", Long.valueOf(retention));
			rec.put("spacing", Long.valueOf(spacing));
			rec.put("firstArchive", Integer.valueOf(archives.size()));
			int count = 0;
			for (Map<?, ?> archive : maps(history.get("archives"))) {
				long resolution = number(archive.get("resolution"));
				long kept = number(archive.get("retention"));
				ram += ImageValidator.archiveRamBytes(resolution);
				flash += ImageValidator.archiveFlashBytes(kept, resolution);
				Map<String, Object> a = record();
				a.put("resolution", Long.valueOf(resolution));
				a.put("retention", Long.valueOf(kept));
				archives.add(a);
				count++;
			}
			rec.put("archiveCount", Integer.valueOf(count));
			dp._history = rings.size();
			rings.add(rec);
		}
		Map<?, ?> budgets = _src.get("history") instanceof Map<?, ?> map ? map : Map.of();
		header.put("historyBudget", Long.valueOf(budgets.get("budget") == null ? ram : number(budgets.get("budget"))));
		header.put("archiveBudget",
			Long.valueOf(budgets.get("archiveBudget") == null ? flash : number(budgets.get("archiveBudget"))));
	}

	// ---- ui ----------------------------------------------------------------------------------

	/**
	 * The minor version of the binary format the image of the given source is written in (section 8):
	 * 2 with an initial value of a datapoint (3.3, homecontrol #107, haumacher/ehome #217), else 1 with
	 * a control page of minimum role <code>anonymous</code> (3.9, I12, homecontrol #91, haumacher/ehome
	 * #184), else 0.
	 */
	public static int formatMinor(Map<?, ?> source) {
		for (Map<?, ?> dp : maps(source.get("datapoints"))) {
			if (dp.containsKey("virtual") && dp.containsKey("initial")) {
				return 2;
			}
		}
		return anonymousPages(source).isEmpty() ? 0 : 1;
	}

	/** The control pages of minimum role <code>anonymous</code> of the source, in their order. */
	private static List<Map<?, ?>> anonymousPages(Map<?, ?> source) {
		List<Map<?, ?>> result = new ArrayList<>();
		if (source.get("ui") instanceof Map<?, ?> ui) {
			for (Map<?, ?> page : maps(ui.get("pages"))) {
				if (ANONYMOUS.equals(page.get(MINIMUM_ROLE))) {
					result.add(page);
				}
			}
		}
		return result;
	}

	/**
	 * The byte sections of the control pages (9.16): <code>ui</code>, and with a page of minimum role
	 * <code>anonymous</code> <code>ui.anonymous</code> with the tables of what those pages show
	 * (format 1.1); none without pages.
	 */
	private Map<Integer, byte[]> ui() {
		Map<Integer, byte[]> result = new TreeMap<>();
		if (!(_src.get("ui") instanceof Map<?, ?> ui) || !(ui.get("pages") instanceof List<?> pageList)
			|| pageList.isEmpty()) {
			return result;
		}
		List<Map<?, ?>> all = maps(pageList);
		result.put(Integer.valueOf(Layout.UI), pagesBody(all));
		for (int p = 0; p < all.size(); p++) {
			Object role = all.get(p).get(MINIMUM_ROLE);
			if (role != null && !ImageValidator.PAGE_ROLES.contains(role)) {
				throw new CompileException("ui.pages[" + p + "]." + MINIMUM_ROLE,
					"'" + role + "' is neither anonymous nor operator");
			}
		}
		List<Map<?, ?>> anonymous = anonymousPages(_src);
		if (!anonymous.isEmpty()) {
			result.put(Integer.valueOf(Layout.UI_ANONYMOUS), pagesBody(anonymous));
			anonymousTables(anonymous);
		}
		return result;
	}

	/**
	 * The tables <code>ui.anonymous.datapoints</code> and <code>ui.anonymous.functions</code> (9.16):
	 * every datapoint the given pages show, operated unless it is only read (a datapoint element with
	 * <code>readOnly</code>, a chart's series and reference lines), every role of a function element's
	 * function operated; the functions of the function elements. Both in ascending order of their
	 * indices.
	 */
	private void anonymousTables(List<Map<?, ?>> pages) {
		SortedMap<Integer, Integer> datapoints = new TreeMap<>();
		Set<Integer> functions = new TreeSet<>();
		List<Map<String, Object>> functionTable = table(Layout.FUNCTIONS);
		List<Map<String, Object>> roles = table(Layout.ROLES);
		for (Map<?, ?> page : pages) {
			for (Map<?, ?> section : maps(page.get("sections"))) {
				for (Map<?, ?> element : maps(section.get("elements"))) {
					if (element.get("function") != null) {
						int f = functionIndex(functionTable, element.get("function"));
						functions.add(Integer.valueOf(f));
						// Every role of the function, operated: the tile is the function's control.
						Map<String, Object> function = functionTable.get(f);
						int first = ((Number) function.get("firstRole")).intValue();
						int count = ((Number) function.get("roleCount")).intValue();
						for (int k = first; k < first + count; k++) {
							show(datapoints, ((Number) roles.get(k).get("datapoint")).intValue(), true);
						}
					} else if (element.get("datapoint") != null) {
						show(datapoints, dp(element.get("datapoint")), !Boolean.TRUE.equals(element.get("readOnly")));
					} else if (element.get("chart") instanceof Map<?, ?> chart) {
						for (Map<?, ?> item : maps(chart.get("series"))) {
							show(datapoints, dp(item.get("datapoint")), false);
						}
						for (Map<?, ?> item : maps(chart.get("references"))) {
							if (item.get("datapoint") != null) {
								show(datapoints, dp(item.get("datapoint")), false);
							}
						}
					}
				}
			}
		}
		for (Map.Entry<Integer, Integer> entry : datapoints.entrySet()) {
			Map<String, Object> rec = record();
			rec.put("datapoint", entry.getKey());
			rec.put("flags", entry.getValue());
			table(Layout.UI_ANONYMOUS_DATAPOINTS).add(rec);
		}
		for (Integer function : functions) {
			Map<String, Object> rec = record();
			rec.put("function", function);
			table(Layout.UI_ANONYMOUS_FUNCTIONS).add(rec);
		}
	}

	/** Notes a datapoint the anonymous pages show; one shown both ways is operated. */
	private static void show(SortedMap<Integer, Integer> datapoints, int index, boolean operate) {
		datapoints.merge(Integer.valueOf(index), Integer.valueOf(operate ? Layout.ANONYMOUS_OPERATE : 0),
			(a, b) -> Integer.valueOf(a.intValue() | b.intValue()));
	}

	private static int functionIndex(List<Map<String, Object>> functionTable, Object id) {
		for (int f = 0; f < functionTable.size(); f++) {
			if (functionTable.get(f).get("id").equals(id)) {
				return f;
			}
		}
		throw new CompileException("ui", "no function '" + id + "'");
	}

	/**
	 * The given control pages as the body of <code>GET /api/pages</code> (9.16, homecontrol
	 * <code>spec/api.md</code>): five flat lists, the defaults resolved, the datapoints by their index.
	 */
	private byte[] pagesBody(List<Map<?, ?>> pageList) {
		List<Object> pages = new ArrayList<>();
		List<Object> sections = new ArrayList<>();
		List<Object> elements = new ArrayList<>();
		List<Object> series = new ArrayList<>();
		List<Object> references = new ArrayList<>();
		for (Map<?, ?> page : pageList) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("id", page.get("id"));
			putIf(p, "label", page.get("label"));
			putIf(p, "icon", page.get("icon"));
			List<Map<?, ?>> sectionList = maps(page.get("sections"));
			p.put("sections", Long.valueOf(sectionList.size()));
			int pageIndex = pages.size();
			pages.add(p);
			for (Map<?, ?> section : sectionList) {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("page", Long.valueOf(pageIndex));
				putIf(s, "label", section.get("label"));
				List<Map<?, ?>> elementList = maps(section.get("elements"));
				s.put("elements", Long.valueOf(elementList.size()));
				int sectionIndex = sections.size();
				sections.add(s);
				for (Map<?, ?> element : elementList) {
					int elementIndex = elements.size();
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("section", Long.valueOf(sectionIndex));
					if (element.get("function") != null) {
						e.put("function", element.get("function"));
					} else if (element.get("datapoint") != null) {
						e.put("datapoint", Long.valueOf(dp(element.get("datapoint"))));
					} else if (element.get("chart") instanceof Map<?, ?> chart) {
						e.put("chart", chart(chart, elementIndex, series, references));
					}
					putIf(e, "label", element.get("label"));
					if (Boolean.TRUE.equals(element.get("readOnly"))) {
						e.put("readOnly", Boolean.TRUE);
					}
					elements.add(e);
				}
			}
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("pages", pages);
		body.put("sections", sections);
		body.put("elements", elements);
		body.put("series", series);
		body.put("references", references);
		return Json.writeCompact(body).getBytes(StandardCharsets.UTF_8);
	}

	private Map<String, Object> chart(Map<?, ?> chart, int element, List<Object> series, List<Object> references) {
		Map<String, Object> c = new LinkedHashMap<>();
		putIf(c, "label", chart.get("label"));
		c.put("width", chart.get("width") == null ? "full" : chart.get("width"));
		c.put("height", chart.get("height") == null ? Long.valueOf(2) : chart.get("height"));
		Object range = chart.get("range") == null ? ImageValidator.DEFAULT_CHART_RANGE : chart.get("range");
		c.put("range", range);
		c.put("presets", chart.get("presets") == null ? List.of(range) : chart.get("presets"));
		Map<?, ?> axes = chart.get("axes") instanceof Map<?, ?> map ? map : Map.of();
		Map<String, Object> resolved = new LinkedHashMap<>();
		for (String axis : ImageValidator.CHART_AXES) {
			Map<?, ?> bounds = axes.get(axis) instanceof Map<?, ?> map ? map : Map.of();
			Map<String, Object> b = new LinkedHashMap<>();
			b.put("min", bounds.get("min"));
			b.put("max", bounds.get("max"));
			resolved.put(axis, b);
		}
		c.put("axes", resolved);
		for (Map<?, ?> item : maps(chart.get("series"))) {
			int index = dp(item.get("datapoint"));
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("element", Long.valueOf(element));
			s.put("datapoint", Long.valueOf(index));
			putIf(s, "label", item.get("label"));
			s.put("axis", item.get("axis") == null ? "left" : item.get("axis"));
			Object consolidation = item.get("consolidation");
			if (consolidation == null) {
				String type = _dps.get(index)._type;
				consolidation = "unsigned".equals(type) || "signed".equals(type) ? "last" : "mean";
			}
			s.put("consolidation", consolidation);
			series.add(s);
		}
		for (Map<?, ?> item : maps(chart.get("references"))) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("element", Long.valueOf(element));
			if (item.get("datapoint") != null) {
				r.put("datapoint", Long.valueOf(dp(item.get("datapoint"))));
			} else {
				r.put("value", item.get("value"));
			}
			putIf(r, "label", item.get("label"));
			r.put("axis", item.get("axis") == null ? "left" : item.get("axis"));
			references.add(r);
		}
		return c;
	}

	private static void putIf(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	// ---- helpers -----------------------------------------------------------------------------

	private static int flag(Map<?, ?> map, String member, int bit) {
		return Boolean.TRUE.equals(map.get(member)) ? bit : 0;
	}

	private static int index(List<String> values, Object value, String where) {
		int index = values.indexOf(string(value));
		if (index < 0) {
			throw new CompileException(where, "unknown '" + value + "'");
		}
		return index;
	}

	static String string(Object value) {
		return value == null ? null : value.toString();
	}

	static long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0;
	}

	static double real(Object value) {
		return value instanceof Number number ? number.doubleValue() : 0;
	}

	static List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	static List<Map<?, ?>> maps(Object value) {
		List<Map<?, ?>> result = new ArrayList<>();
		for (Object item : list(value)) {
			if (item instanceof Map<?, ?> map) {
				result.add(map);
			}
		}
		return result;
	}

}
