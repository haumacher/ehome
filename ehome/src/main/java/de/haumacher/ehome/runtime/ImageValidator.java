/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleLoader.LoadException;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.runtime.ImageReport.Kind;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * The checks the controller makes before it loads a runtime image (spec/runtime-image.md, sections
 * 1 to 5), made by E-Home before it exports or deploys one, so that a refusal is shown with the
 * project instead of coming back from the controller.
 *
 * <p>
 * The checks and their reasons follow the controller's loader (homecontrol
 * <code>core/src/image/loader.cpp</code>) and are pinned by the conformance cases of the
 * specification (<code>src/test/fixtures/homecontrol/images/cases/</code>): the paths are the same,
 * the reasons contain the texts the cases expect.
 * </p>
 *
 * <p>
 * The members <code>schedules</code> and <code>rules</code> are checked by the loaders of the
 * reference interpreter ({@link Schedule#load(Object, Schedule.Datapoints)},
 * {@link RuleLoader}), the rules only when everything else is valid (as the controller does: their
 * references are checked against the datapoints and schedules). Not checked: the contents of
 * <code>external</code>; an image holding them gets a {@link ImageReport#note(String) note} saying
 * so.
 * </p>
 */
public final class ImageValidator {

	/** The major version of the schema this validator reads. */
	public static final int SCHEMA_MAJOR = 1;

	/** The minor version of the schema this validator reads. */
	public static final int SCHEMA_MINOR = 0;

	/** The largest image in bytes. */
	public static final int MAX_IMAGE_BYTES = 262144;

	/** The deepest nesting of objects and arrays. */
	public static final int MAX_NESTING = 48;

	/** The most datapoints of an image. */
	public static final int MAX_DATAPOINTS = 1024;

	/** The most locations of an image. */
	public static final int MAX_LOCATIONS = 256;

	/** The most functions of an image. */
	public static final int MAX_FUNCTIONS = 512;

	/** The longest name (datapoint id, label, project name) in bytes. */
	public static final int MAX_NAME = 63;

	/** The most listening addresses of a datapoint. */
	public static final int MAX_LISTEN = 8;

	/** The most history memory a controller (an ESP32-C6) allows. */
	public static final int HISTORY_CAP = 65536;

	private static final int MAX_SETTING = 127;

	private static final int MIN_SPACING = 10;

	private static final int MAX_SPACING = 86400;

	private static final int MAX_RETENTION = 31 * 86400;

	static final int MAX_SAMPLES = 2048;

	private static final int SAMPLE_BYTES = 8;

	private static final String[] RESERVED = { "knx.time", "knx.date", "knx.datetime" };

	private static final List<String> LOCATION_KINDS = List.of("building", "floor", "room", "area", "outside");

	private final ImageReport _report = new ImageReport();

	private final StringBuilder _path = new StringBuilder();

	private boolean _hasTimeMaster;

	private final Map<String, Integer> _timeMaster = new HashMap<>();

	/** Per datapoint: its id (null where refused), value type, flags. */
	private final List<String> _ids = new ArrayList<>();

	private final List<String> _types = new ArrayList<>();

	private final List<Boolean> _writable = new ArrayList<>();

	private final List<Integer> _sends = new ArrayList<>();

	/** Per datapoint: what owns it (the KNX adapter, ...), null for nothing. */
	private final List<String> _owners = new ArrayList<>();

	/** The outputs of the schedule entries: the scheduler owns them. */
	private final Set<String> _scheduleOutputs = new HashSet<>();

	private long _historyBytes;

	private ImageValidator() {
		// Use validate().
	}

	/**
	 * Checks the image given as its JSON text.
	 */
	public static ImageReport validate(String text) {
		ImageValidator validator = new ImageValidator();
		validator.check(text);
		return validator._report;
	}

	private void check(String text) {
		int bytes = text.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_IMAGE_BYTES) {
			_report.add(Kind.TOO_LARGE, "", "the image has " + bytes + " bytes, the limit is " + MAX_IMAGE_BYTES);
			return;
		}
		Object root;
		try {
			root = Json.parse(text, MAX_NESTING);
		} catch (JsonException ex) {
			_report.add(Kind.SYNTAX, "", "not JSON: " + ex.getMessage() + " at character " + ex.getOffset());
			return;
		}
		if (!(root instanceof Map<?, ?> image)) {
			refuse("the image must be a JSON object");
			return;
		}
		if (!image(image)) {
			return;
		}
		settings(image);
		knx(image);
		datapoints(image);
		Set<String> locations = locations(image);
		functions(image, locations);
		history(image);
		if (image.containsKey("ui")) {
			enter("ui");
			object(image.get("ui"), "'ui'");
			leave();
		}
		if (image.containsKey("external")) {
			enter("external");
			if (object(image.get("external"), "'external'")) {
				_report.note("The external values (external) are not checked by E-Home.");
			}
			leave();
		}
		Set<String> schedules = schedules(image);
		rules(image, schedules);
	}

	// ---- schedules and rules -----------------------------------------------------------------

	/**
	 * The datapoints of an image as the rule and schedule loaders see them: the image's own and
	 * those of its time master.
	 */
	public interface Directory extends RuleLoader.DatapointDirectory, Schedule.Datapoints {

		/** Whether the datapoint is the output of a schedule entry of the image. */
		boolean ownsOutput(String datapoint);

		/** Whether a technology owns the datapoint that can be asked for its value. */
		boolean technologyReads(String datapoint);
	}

	/**
	 * The {@link Directory} of the datapoints of the given image text, read as the validator reads
	 * them (a datapoint the validator refuses has no type there).
	 */
	public static Directory directory(String text) {
		return directory(text, null);
	}

	/**
	 * The {@link Directory} of the datapoints of the given image text with the given rules instead of
	 * the image's (<code>null</code>: the image's): the rules imply the datapoints of their parameters
	 * (spec/rules.md 8.6).
	 */
	public static Directory directory(String text, Object rules) {
		ImageValidator validator = new ImageValidator();
		Object root;
		try {
			root = Json.parse(text, MAX_NESTING);
		} catch (JsonException ex) {
			root = Map.of();
		}
		Map<Object, Object> image = new LinkedHashMap<>();
		if (root instanceof Map<?, ?> map) {
			image.putAll(map);
		}
		if (rules != null) {
			image.put("rules", rules);
		}
		validator.knx(image);
		validator.datapoints(image);
		validator.schedules(image);
		return validator.directory(image);
	}

	private Directory directory() {
		return directory(Map.of());
	}

	private Directory directory(Map<?, ?> root) {
		return new Directory() {
			@Override
			public boolean ownsOutput(String datapoint) {
				return _scheduleOutputs.contains(datapoint);
			}

			@Override
			public boolean technologyReads(String datapoint) {
				return reads(root, datapoint);
			}

			@Override
			public ValueType type(String name) {
				return datapointType(name);
			}

			@Override
			public boolean writable(String name) {
				int index = datapointIndex(name);
				return index >= 0 && _writable.get(index).booleanValue();
			}

			@Override
			public String owner(String name) {
				int index = datapointIndex(name);
				return index < 0 ? null : _owners.get(index);
			}
		};
	}

	private int datapointIndex(String name) {
		for (int i = 0; i < _ids.size(); i++) {
			if (name.equals(_ids.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private ValueType datapointType(String name) {
		int index = datapointIndex(name);
		return index < 0 || _types.get(index) == null ? null : ValueType.fromName(_types.get(index));
	}

	private Set<String> schedules(Map<?, ?> root) {
		if (!root.containsKey("schedules")) {
			return Set.of();
		}
		Schedule.Loaded loaded = Schedule.load(root.get("schedules"), directory());
		for (Schedule.Refusal refusal : loaded.refusals()) {
			_report.add(Kind.INVALID, refusal.path(), refusal.reason());
		}
		for (Schedule entry : loaded.entries()) {
			if (entry.output() != null) {
				_scheduleOutputs.add(entry.output());
			}
		}
		return loaded.ids();
	}

	private void rules(Map<?, ?> root, Set<String> schedules) {
		if (!root.containsKey("rules") || !_report.isValid()) {
			// The rules are checked against the datapoints and schedules; with refusals there the
			// check would only repeat them.
			return;
		}
		try {
			RuleLoader.load(root.get("rules"), directory(), new RuleLoader.ScheduleDirectory() {
				@Override
				public boolean hasSchedule(String id) {
					return schedules.contains(id);
				}

				@Override
				public boolean ownsOutput(String datapoint) {
					return _scheduleOutputs.contains(datapoint);
				}

				@Override
				public boolean technologyReads(String datapoint) {
					return reads(root, datapoint);
				}
			});
		} catch (LoadException ex) {
			RuleLoader.Refusal refusal = ex.getRefusal();
			_report.add(Kind.INVALID, refusal.path(),
				"rule '" + refusal.rule() + "': " + refusal.error().externalName() + ": " + refusal.text());
		}
	}

	/**
	 * Whether a technology owns the datapoint that can be asked for its value (spec/rules.md 7.12): a
	 * KNX or Zigbee binding, or a binding of an external source
	 * (<code>external.sources[].bindings[].datapoint</code>); not a virtual datapoint, the time
	 * master's or a rule parameter's.
	 */
	private boolean reads(Map<?, ?> root, String datapoint) {
		int index = datapointIndex(datapoint);
		if (index < 0) {
			return true;
		}
		String owner = _owners.get(index);
		if ("the KNX adapter".equals(owner) || "the Zigbee adapter".equals(owner)) {
			return true;
		}
		if (owner != null || !(root.get("external") instanceof Map<?, ?> external)
			|| !(external.get("sources") instanceof List<?> sources)) {
			return false;
		}
		for (Object source : sources) {
			if (source instanceof Map<?, ?> map && map.get("bindings") instanceof List<?> bindings) {
				for (Object binding : bindings) {
					if (binding instanceof Map<?, ?> b && datapoint.equals(b.get("datapoint"))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	// ---- image -------------------------------------------------------------------------------

	private boolean image(Map<?, ?> root) {
		enter("image");
		try {
			if (!root.containsKey("image")) {
				refuse("the member 'image' (schemaVersion, projectId, revision) is missing");
				return false;
			}
			Object value = root.get("image");
			if (!object(value, "'image'")) {
				return false;
			}
			Map<?, ?> image = (Map<?, ?>) value;
			enter(".schemaVersion");
			try {
				if (!image.containsKey("schemaVersion")) {
					refuse("schemaVersion is missing");
					return false;
				}
				String version = string(image.get("schemaVersion"), 3, 15, "schemaVersion");
				if (version == null) {
					return false;
				}
				int[] parsed = parseVersion(version);
				if (parsed == null || parsed[0] == 0) {
					refuse("schemaVersion \"" + version + "\" is not <major>.<minor> with a major version from 1");
					return false;
				}
				if (parsed[0] > SCHEMA_MAJOR) {
					_report.add(Kind.VERSION, path(), "schemaVersion " + version + " is newer than this firmware reads ("
						+ SCHEMA_MAJOR + ".x, up to " + SCHEMA_MAJOR + "." + SCHEMA_MINOR + "): update the firmware");
					return false;
				}
			} finally {
				leave();
			}
			enter(".projectId");
			if (!image.containsKey("projectId")) {
				refuse("projectId is missing");
			} else {
				string(image.get("projectId"), 1, MAX_NAME, "projectId");
			}
			leave();
			enter(".revision");
			if (!image.containsKey("revision")) {
				refuse("revision is missing");
			} else {
				integer(image.get("revision"), 0, 4294967295.0, "revision");
			}
			leave();
			optionalString(image, "generatedAt", 31);
			optionalString(image, "generator", MAX_NAME);
			optionalString(image, "name", MAX_NAME);
			return true;
		} finally {
			leave();
		}
	}

	private void optionalString(Map<?, ?> object, String member, int max) {
		if (object.containsKey(member)) {
			enter("." + member);
			string(object.get(member), 1, max, member);
			leave();
		}
	}

	private static int[] parseVersion(String text) {
		int dot = text.indexOf('.');
		if (dot <= 0 || dot == text.length() - 1) {
			return null;
		}
		String major = text.substring(0, dot);
		String minor = text.substring(dot + 1);
		if (!major.matches("[0-9]{1,4}") || !minor.matches("[0-9]{1,4}")) {
			return null;
		}
		return new int[] { Integer.parseInt(major), Integer.parseInt(minor) };
	}

	// ---- settings ----------------------------------------------------------------------------

	private void settings(Map<?, ?> root) {
		if (!root.containsKey("settings")) {
			return;
		}
		enter("settings");
		try {
			if (!object(root.get("settings"), "'settings'")) {
				return;
			}
			Map<?, ?> settings = (Map<?, ?>) root.get("settings");
			if (settings.containsKey("tz")) {
				enter(".tz");
				String tz = string(settings.get("tz"), 1, MAX_SETTING, "tz");
				if (tz != null) {
					String error = PosixTz.check(tz);
					if (error != null) {
						refuse("tz \"" + tz + "\" is not a POSIX TZ string: " + error);
					}
				}
				leave();
			}
			if (settings.containsKey("location")) {
				enter(".location");
				Object value = settings.get("location");
				if (object(value, "location")) {
					Map<?, ?> location = (Map<?, ?>) value;
					boolean ok = true;
					if (!location.containsKey("lat") || !location.containsKey("lon")) {
						refuse("location needs lat and lon");
						ok = false;
					} else {
						enter(".lat");
						ok = number(location.get("lat"), -90, 90, "lat");
						leave();
					}
					if (ok) {
						enter(".lon");
						number(location.get("lon"), -180, 180, "lon");
						leave();
					}
					if (location.containsKey("elev")) {
						enter(".elev");
						integer(location.get("elev"), -500, 9000, "elev");
						leave();
					}
				}
				leave();
			}
			if (settings.containsKey("ntp")) {
				enter(".ntp");
				Object value = settings.get("ntp");
				if (object(value, "ntp")) {
					Map<?, ?> ntp = (Map<?, ?>) value;
					if (!ntp.containsKey("server")) {
						refuse("ntp needs server");
					} else {
						enter(".server");
						String server = string(ntp.get("server"), 1, MAX_SETTING, "server");
						if (server != null) {
							String error = hostNameError(server);
							if (error != null) {
								refuse("server \"" + server + "\" is not a host name: " + error);
							}
						}
						leave();
					}
				}
				leave();
			}
		} finally {
			leave();
		}
	}

	static String hostNameError(String name) {
		int label = 0;
		for (int n = 0; n < name.length(); n++) {
			char c = name.charAt(n);
			if (c == '.') {
				if (label == 0) {
					return "host name has an empty label";
				}
				label = 0;
				continue;
			}
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
			if (!ok) {
				return "host name may contain only letters, digits, '-' and '.'";
			}
			if (++label > 63) {
				return "host name label is longer than 63 characters";
			}
		}
		return label == 0 ? "host name ends with '.'" : null;
	}

	// ---- knx (time master) -------------------------------------------------------------------

	private void knx(Map<?, ?> root) {
		if (!root.containsKey("knx")) {
			return;
		}
		enter("knx");
		try {
			if (!object(root.get("knx"), "'knx'")) {
				return;
			}
			Map<?, ?> knx = (Map<?, ?>) root.get("knx");
			if (!knx.containsKey("timeMaster")) {
				return;
			}
			enter(".timeMaster");
			try {
				if (!object(knx.get("timeMaster"), "timeMaster")) {
					return;
				}
				Map<?, ?> tm = (Map<?, ?>) knx.get("timeMaster");
				int named = 0;
				int count = 0;
				for (String member : new String[] { "time", "date", "dateTime" }) {
					if (!tm.containsKey(member)) {
						continue;
					}
					named++;
					enter("." + member);
					Integer address = groupAddress(tm.get(member), member);
					if (address != null) {
						_timeMaster.put(member, address);
						count++;
					}
					leave();
				}
				if (tm.containsKey("interval")) {
					enter(".interval");
					integer(tm.get("interval"), 60, 86400, "interval (seconds)");
					leave();
				}
				flag(tm, "atMidnight");
				flag(tm, "onSynchronized");
				if (named == 0) {
					refuse("timeMaster names no address (time, date, dateTime)");
					return;
				}
				if (count < named) {
					return;
				}
				List<String> members = new ArrayList<>(_timeMaster.keySet());
				for (int i = 0; i < members.size(); i++) {
					for (int j = i + 1; j < members.size(); j++) {
						if (_timeMaster.get(members.get(i)).equals(_timeMaster.get(members.get(j)))) {
							refuse(members.get(i) + " and " + members.get(j) + " use the same group address");
						}
					}
				}
				_hasTimeMaster = true;
			} finally {
				leave();
			}
		} finally {
			leave();
		}
	}

	// ---- datapoints --------------------------------------------------------------------------

	private void datapoints(Map<?, ?> root) {
		if (root.containsKey("datapoints")) {
			enter("datapoints");
			try {
				Object value = root.get("datapoints");
				if (array(value, "'datapoints'")) {
					List<?> list = (List<?>) value;
					if (list.size() > MAX_DATAPOINTS) {
						refuse(list.size() + " datapoints exceed the limit of " + MAX_DATAPOINTS);
						return;
					}
					for (int i = 0; i < list.size(); i++) {
						enter("[" + i + "]");
						datapoint(list.get(i));
						leave();
					}
				}
			} finally {
				leave();
			}
		}
		// The time master's datapoints, after the image's own: functions may name them.
		if (_hasTimeMaster) {
			String[][] implied = { { "time", "knx.time", ValueTypes.TIME_OF_DAY },
				{ "date", "knx.date", ValueTypes.DATE }, { "dateTime", "knx.datetime", ValueTypes.DATE_TIME } };
			for (String[] datapoint : implied) {
				if (_timeMaster.containsKey(datapoint[0])) {
					_owners.add("the time master");
					_ids.add(datapoint[1]);
					_types.add(datapoint[2]);
					_writable.add(Boolean.FALSE);
					_sends.add(null);
				}
			}
		}
		// The datapoints of the rule parameters (spec/rules.md 8.6), after the time master's: the
		// rules, functions and schedules may name them.
		for (RuleLoader.ParameterDatapoint datapoint : RuleLoader.parameterDatapoints(root.get("rules"))) {
			_owners.add("the rule engine");
			_ids.add(datapoint.name());
			_types.add(datapoint.type().externalName());
			_writable.add(Boolean.valueOf(datapoint.writable()));
			_sends.add(null);
		}
		// Unique ids.
		Map<String, Integer> first = new HashMap<>();
		for (int i = 0; i < _ids.size(); i++) {
			String id = _ids.get(i);
			if (id == null) {
				continue;
			}
			Integer before = first.putIfAbsent(id, Integer.valueOf(i));
			if (before != null) {
				_report.add(Kind.INVALID, "datapoints[" + i + "].id",
					"duplicate id '" + id + "' (also datapoints[" + before + "])");
			}
		}
		// The time master's addresses must not be the sending address of a datapoint.
		if (_hasTimeMaster) {
			for (String member : new String[] { "time", "date", "dateTime" }) {
				Integer address = _timeMaster.get(member);
				if (address == null) {
					continue;
				}
				for (int i = 0; i < _sends.size(); i++) {
					if (address.equals(_sends.get(i))) {
						String id = _ids.get(i);
						_report.add(Kind.INVALID, "knx.timeMaster." + member, "the address is the sending address of datapoints["
							+ i + "] '" + (id == null ? "?" : id) + "'");
					}
				}
			}
		}
	}

	private void datapoint(Object item) {
		_ids.add(null);
		_types.add(null);
		_writable.add(Boolean.FALSE);
		_sends.add(null);
		_owners.add(null);
		int index = _ids.size() - 1;
		if (!object(item, "a datapoint")) {
			return;
		}
		Map<?, ?> dp = (Map<?, ?>) item;
		enter(".id");
		if (!dp.containsKey("id")) {
			refuse("id is missing");
		} else {
			String id = string(dp.get("id"), 1, MAX_NAME, "id");
			if (id != null && _hasTimeMaster && List.of(RESERVED).contains(id)) {
				refuse("id '" + id + "' is reserved for the datapoints of knx.timeMaster");
				id = null;
			} else if (id != null && id.startsWith(RuleSet.PARAMETER_DATAPOINT_PREFIX)) {
				refuse("id '" + id + "': the prefix '" + RuleSet.PARAMETER_DATAPOINT_PREFIX
					+ "' is reserved for the datapoints of rule parameters");
				id = null;
			}
			_ids.set(index, id);
		}
		leave();
		String type = null;
		enter(".type");
		if (!dp.containsKey("type")) {
			refuse("type is missing");
		} else {
			String name = string(dp.get("type"), 1, 15, "type");
			if (name != null) {
				if (ValueTypes.ALL.contains(name)) {
					type = name;
				} else {
					refuse("unknown type \"" + name + "\" (" + String.join(", ", ValueTypes.ALL) + ")");
				}
			}
		}
		leave();
		_types.set(index, type);
		if (dp.containsKey("label")) {
			enter(".label");
			label(dp.get("label"));
			leave();
		}
		if (dp.containsKey("unit")) {
			enter(".unit");
			string(dp.get("unit"), 1, 15, "unit");
			leave();
		}
		boolean readable = flag(dp, "readable");
		boolean writable = flag(dp, "writable");
		boolean transmit = flag(dp, "transmit");
		flag(dp, "readOnStart");
		flag(dp, "persist");
		_writable.set(index, Boolean.valueOf(writable));
		if (dp.containsKey("history")) {
			enter(".history");
			history(dp.get("history"), type);
			leave();
		}
		boolean hasVirtual = dp.containsKey("virtual");
		if (hasVirtual) {
			enter(".virtual");
			object(dp.get("virtual"), "virtual");
			leave();
		}
		if (dp.containsKey("zigbee")) {
			enter(".zigbee");
			if (hasVirtual) {
				refuse("a datapoint is either zigbee or virtual");
			} else if (dp.containsKey("knx")) {
				refuse("a datapoint is either knx or zigbee");
			} else {
				_owners.set(index, "the Zigbee adapter");
				_report.note("The Zigbee binding at " + path() + " is not checked by E-Home.");
			}
			leave();
			return;
		}
		if (!dp.containsKey("knx")) {
			return;
		}
		enter(".knx");
		try {
			if (hasVirtual) {
				refuse("a datapoint is either knx or virtual");
				return;
			}
			if (!object(dp.get("knx"), "knx")) {
				return;
			}
			_owners.set(index, "the KNX adapter");
			Map<?, ?> knx = (Map<?, ?>) dp.get("knx");
			enter(".dpt");
			if (!knx.containsKey("dpt")) {
				refuse("dpt is missing");
			} else {
				String text = string(knx.get("dpt"), 1, 15, "dpt");
				if (text != null) {
					int[] dpt = parseDpt(text);
					if (dpt == null) {
						refuse("\"" + text + "\" is not a datapoint type (expected e.g. 1.001, 9.001)");
					} else {
						String unsupported = ValueTypes.unsupported(dpt[0], dpt[1]);
						if (unsupported != null) {
							refuse("DPT " + text + ": " + unsupported);
						} else {
							String carried = ValueTypes.valueType(dpt[0], dpt[1]);
							if (type != null && !carried.equals(type)
								&& !(ValueTypes.isNumeric(carried) && ValueTypes.isNumeric(type))) {
								refuse("DPT " + text + " carries " + carried + " values, the datapoint is " + type);
							}
						}
					}
				}
			}
			leave();
			boolean refusedAddress = false;
			boolean hasSend = false;
			if (knx.containsKey("send")) {
				enter(".send");
				Integer send = groupAddress(knx.get("send"), "send");
				hasSend = send != null;
				refusedAddress = !hasSend;
				_sends.set(index, send);
				leave();
			}
			int listens = 0;
			if (knx.containsKey("listen")) {
				enter(".listen");
				Object value = knx.get("listen");
				if (array(value, "listen")) {
					List<?> list = (List<?>) value;
					for (int n = 0; n < list.size(); n++) {
						enter("[" + n + "]");
						if (n >= MAX_LISTEN) {
							refuse("more than " + MAX_LISTEN + " listening addresses");
							leave();
							break;
						}
						if (groupAddress(list.get(n), "a listening address") != null) {
							listens++;
						} else {
							refusedAddress = true;
						}
						leave();
					}
				}
				leave();
			}
			if (refusedAddress) {
				return;
			}
			if (!hasSend && listens == 0) {
				refuse("a KNX datapoint needs a sending or a listening group address");
			} else if (!hasSend && (transmit || readable)) {
				refuse("a transmitting or readable KNX datapoint needs a sending address (send)");
			}
		} finally {
			leave();
		}
	}

	private void history(Object value, String type) {
		if (!object(value, "history")) {
			return;
		}
		Map<?, ?> history = (Map<?, ?>) value;
		boolean ok = true;
		long retention = 0;
		long spacing = 0;
		if (!history.containsKey("retention") || !history.containsKey("spacing")) {
			refuse("history needs retention and spacing (seconds)");
			ok = false;
		} else {
			enter(".spacing");
			Long s = integer(history.get("spacing"), MIN_SPACING, MAX_SPACING, "spacing (seconds)");
			leave();
			ok = s != null;
			if (ok) {
				spacing = s.longValue();
				enter(".retention");
				Long r = integer(history.get("retention"), spacing, MAX_RETENTION, "retention (seconds, at least the spacing)");
				leave();
				ok = r != null;
				if (ok) {
					retention = r.longValue();
				}
			}
		}
		if (ok && samples(retention, spacing) > MAX_SAMPLES) {
			refuse("retention / spacing gives more than " + MAX_SAMPLES + " samples");
			ok = false;
		}
		if (type != null && !ValueTypes.BOOLEAN.equals(type) && !ValueTypes.UNSIGNED.equals(type)
			&& !ValueTypes.SIGNED.equals(type) && !ValueTypes.REAL.equals(type)) {
			refuse("history needs a boolean, unsigned, signed or real datapoint");
			ok = false;
		}
		if (ok) {
			_historyBytes += samples(retention, spacing) * SAMPLE_BYTES;
		}
	}

	/**
	 * The number of samples of a history ring.
	 */
	public static long samples(long retention, long spacing) {
		return spacing == 0 ? 0 : (retention + spacing - 1) / spacing + 1;
	}

	private static int[] parseDpt(String text) {
		String main;
		String sub = null;
		if (text.startsWith("DPST-") || text.startsWith("DPT-")) {
			String rest = text.substring(text.startsWith("DPST-") ? 5 : 4);
			int dash = rest.indexOf('-');
			main = dash < 0 ? rest : rest.substring(0, dash);
			sub = dash < 0 ? null : rest.substring(dash + 1);
		} else {
			int dot = text.indexOf('.');
			main = dot < 0 ? text : text.substring(0, dot);
			sub = dot < 0 ? null : text.substring(dot + 1);
			if ("*".equals(sub)) {
				sub = null;
			}
		}
		if (!main.matches("[0-9]{1,5}") || (sub != null && !sub.matches("[0-9]{1,5}"))) {
			return null;
		}
		int mainNumber = Integer.parseInt(main);
		if (mainNumber == 0) {
			return null;
		}
		return new int[] { mainNumber, sub == null ? 0 : Integer.parseInt(sub) };
	}

	/**
	 * Reads a group address written with two or three levels, <code>null</code> (after a refusal)
	 * if it is none.
	 */
	private Integer groupAddress(Object value, String what) {
		String text = string(value, 1, 15, what);
		if (text == null) {
			return null;
		}
		String error = null;
		Integer result = null;
		String[] parts = text.split("/", -1);
		if (parts.length > 3) {
			error = "too many levels";
		} else {
			long[] numbers = new long[parts.length];
			for (int n = 0; n < parts.length && error == null; n++) {
				if (!parts[n].matches("[0-9]{1,5}")) {
					error = "not a number";
				} else {
					numbers[n] = Long.parseLong(parts[n]);
				}
			}
			if (error == null) {
				if (parts.length == 1) {
					error = "group address needs two or three levels (1/2/3 or 1/515)";
				} else if (numbers[0] > 31) {
					error = "main group must be 0..31";
				} else if (parts.length == 2) {
					if (numbers[1] > 2047) {
						error = "sub group of a two-level address must be 0..2047";
					} else {
						result = Integer.valueOf((int) (numbers[0] << 11 | numbers[1]));
					}
				} else if (numbers[1] > 7) {
					error = "middle group must be 0..7";
				} else if (numbers[2] > 255) {
					error = "sub group must be 0..255";
				} else {
					result = Integer.valueOf((int) (numbers[0] << 11 | numbers[1] << 8 | numbers[2]));
				}
			}
		}
		if (result == null) {
			refuse(what + " \"" + text + "\" is not a group address (" + error + ")");
		}
		return result;
	}

	// ---- locations and functions -------------------------------------------------------------

	private Set<String> locations(Map<?, ?> root) {
		Set<String> result = new HashSet<>();
		if (!root.containsKey("locations")) {
			return result;
		}
		enter("locations");
		try {
			Object value = root.get("locations");
			if (!array(value, "'locations'")) {
				return result;
			}
			List<?> list = (List<?>) value;
			if (list.size() > MAX_LOCATIONS) {
				refuse(list.size() + " locations exceed the limit of " + MAX_LOCATIONS);
				return result;
			}
			List<String> ids = new ArrayList<>();
			List<String> parents = new ArrayList<>();
			for (int i = 0; i < list.size(); i++) {
				enter("[" + i + "]");
				ids.add(null);
				parents.add(null);
				Object item = list.get(i);
				if (object(item, "a location")) {
					Map<?, ?> location = (Map<?, ?>) item;
					String id = id(location, "location id");
					if (id != null) {
						if (ids.contains(id)) {
							enter(".id");
							refuse("duplicate location id '" + id + "'");
							leave();
						}
						ids.set(i, id);
					}
					if (!location.containsKey("label")) {
						refuse("label is missing");
					} else {
						enter(".label");
						label(location.get("label"));
						leave();
					}
					if (location.containsKey("kind")) {
						enter(".kind");
						String kind = string(location.get("kind"), 1, 15, "kind");
						if (kind != null && !LOCATION_KINDS.contains(kind)) {
							refuse("unknown location kind \"" + kind + "\" (building, floor, room, area, outside)");
						}
						leave();
					}
					if (location.containsKey("parent")) {
						enter(".parent");
						parents.set(i, string(location.get("parent"), 1, 32, "parent"));
						leave();
					}
				}
				leave();
			}
			int[] parentIndex = new int[list.size()];
			for (int k = 0; k < list.size(); k++) {
				parentIndex[k] = -1;
				String parent = parents.get(k);
				if (parent == null) {
					continue;
				}
				int found = ids.indexOf(parent);
				if (found < 0) {
					_report.add(Kind.INVALID, path() + "[" + k + "].parent", "unknown location '" + parent + "'");
				} else {
					parentIndex[k] = found;
				}
			}
			for (int k = 0; k < list.size(); k++) {
				int at = parentIndex[k];
				int steps = 0;
				while (at >= 0 && steps <= list.size()) {
					at = parentIndex[at];
					steps++;
				}
				if (steps > list.size()) {
					_report.add(Kind.INVALID, path() + "[" + k + "].parent", "the parents form a cycle");
					break;
				}
			}
			for (String id : ids) {
				if (id != null) {
					result.add(id);
				}
			}
			return result;
		} finally {
			leave();
		}
	}

	/**
	 * A role of a function kind: the value types it takes (any where <code>null</code>), whether it
	 * needs a writable datapoint and whether the function needs it.
	 */
	private record Role(String name, List<String> types, boolean writable, boolean required) {
		// A record.
	}

	private static final List<String> BOOL = List.of(ValueTypes.BOOLEAN);

	private static final List<String> NUMBER = List.of(ValueTypes.UNSIGNED, ValueTypes.SIGNED, ValueTypes.REAL,
		ValueTypes.ENUMERATION);

	private static final List<String> ENUM_OR_NUMBER = NUMBER;

	private static final Map<String, List<Role>> FUNCTION_KINDS = Map.of(
		"light", List.of(new Role("command", BOOL, true, true), new Role("state", BOOL, false, false)),
		"dimmer", List.of(new Role("levelCommand", NUMBER, true, true), new Role("level", NUMBER, false, false),
			new Role("command", BOOL, true, false), new Role("state", BOOL, false, false),
			new Role("step", List.of(ValueTypes.CONTROLLED), true, false)),
		"blind", List.of(new Role("move", BOOL, true, true), new Role("stop", BOOL, true, false),
			new Role("position", NUMBER, false, false), new Role("positionCommand", NUMBER, true, false),
			new Role("slat", NUMBER, false, false), new Role("slatCommand", NUMBER, true, false)),
		"switch", List.of(new Role("command", BOOL, true, true), new Role("state", BOOL, false, false)),
		"sensor", List.of(new Role("value", null, false, true)),
		"climate", List.of(new Role("temperature", NUMBER, false, true), new Role("setpoint", NUMBER, false, false),
			new Role("setpointCommand", NUMBER, true, false), new Role("mode", ENUM_OR_NUMBER, false, false),
			new Role("modeCommand", ENUM_OR_NUMBER, true, false), new Role("heating", BOOL, false, false)),
		"scene", List.of(new Role("command", List.of(ValueTypes.UNSIGNED, ValueTypes.SIGNED, ValueTypes.REAL,
			ValueTypes.ENUMERATION, ValueTypes.CONTROLLED), true, true)),
		"button", List.of(new Role("press", List.of(ValueTypes.BOOLEAN, ValueTypes.CONTROLLED), false, true),
			new Role("state", BOOL, false, false)));

	private void functions(Map<?, ?> root, Set<String> locations) {
		if (!root.containsKey("functions")) {
			return;
		}
		enter("functions");
		try {
			Object value = root.get("functions");
			if (!array(value, "'functions'")) {
				return;
			}
			List<?> list = (List<?>) value;
			if (list.size() > MAX_FUNCTIONS) {
				refuse(list.size() + " functions exceed the limit of " + MAX_FUNCTIONS);
				return;
			}
			Set<String> ids = new HashSet<>();
			for (int i = 0; i < list.size(); i++) {
				enter("[" + i + "]");
				function(list.get(i), ids, locations);
				leave();
			}
		} finally {
			leave();
		}
	}

	private void function(Object item, Set<String> ids, Set<String> locations) {
		if (!object(item, "a function")) {
			return;
		}
		Map<?, ?> function = (Map<?, ?>) item;
		String id = id(function, "function id");
		if (id != null && !ids.add(id)) {
			enter(".id");
			refuse("duplicate function id '" + id + "'");
			leave();
		}
		if (!function.containsKey("label")) {
			refuse("label is missing");
		} else {
			enter(".label");
			label(function.get("label"));
			leave();
		}
		if (function.containsKey("room")) {
			enter(".room");
			String room = string(function.get("room"), 1, 32, "room");
			if (room != null && !locations.contains(room)) {
				refuse("unknown location '" + room + "'");
			}
			leave();
		}
		if (function.containsKey("unit")) {
			enter(".unit");
			string(function.get("unit"), 1, 15, "unit");
			leave();
		}
		List<Role> roles = null;
		String kind = null;
		enter(".kind");
		if (!function.containsKey("kind")) {
			refuse("kind is missing");
		} else {
			kind = string(function.get("kind"), 1, 15, "kind");
			if (kind != null) {
				roles = FUNCTION_KINDS.get(kind);
				if (roles == null) {
					refuse("unknown function kind \"" + kind
						+ "\" (light, dimmer, blind, switch, sensor, climate, scene, button)");
				}
			}
		}
		leave();
		if (roles != null && "scene".equals(kind)) {
			enter(".scene");
			if (!function.containsKey("scene")) {
				refuse("a scene function needs scene (1..64)");
			} else {
				integer(function.get("scene"), 1, 64, "scene");
			}
			leave();
		}
		if (roles == null) {
			return;
		}
		enter(".datapoints");
		try {
			if (!function.containsKey("datapoints")) {
				refuse("datapoints (the roles of a " + kind + ") is missing");
				return;
			}
			if (!object(function.get("datapoints"), "datapoints")) {
				return;
			}
			Map<?, ?> refs = (Map<?, ?>) function.get("datapoints");
			for (Role role : roles) {
				if (!refs.containsKey(role.name())) {
					if (role.required()) {
						refuse("a " + kind + " needs the role " + role.name());
					}
					continue;
				}
				enter("." + role.name());
				String name = string(refs.get(role.name()), 1, MAX_NAME, "the role");
				if (name != null) {
					int dp = _ids.indexOf(name);
					if (dp < 0) {
						refuse("unknown datapoint '" + name + "'");
					} else {
						String type = _types.get(dp);
						if (role.types() != null && !role.types().contains(type)) {
							refuse("datapoint '" + name + "' is " + (type == null ? "none" : type)
								+ ", which does not fit the role " + role.name() + " of a " + kind);
						}
						if (role.writable() && !_writable.get(dp).booleanValue()) {
							refuse("the role " + role.name() + " needs a writable datapoint; '" + name + "' is not");
						}
					}
				}
				leave();
			}
		} finally {
			leave();
		}
	}

	/**
	 * Reads the id member of a location or function: 1..32 characters of A-Z a-z 0-9 _ . -.
	 */
	private String id(Map<?, ?> object, String what) {
		enter(".id");
		try {
			if (!object.containsKey("id")) {
				refuse("id is missing");
				return null;
			}
			String id = string(object.get("id"), 1, 32, what);
			if (id != null && !id.matches("[A-Za-z0-9_.\\-]{1,32}")) {
				refuse(what + " '" + id + "' must have 1..32 characters of A-Z a-z 0-9 _ . -");
				return null;
			}
			return id;
		} finally {
			leave();
		}
	}

	// ---- history budget ----------------------------------------------------------------------

	private void history(Map<?, ?> root) {
		long needed = _historyBytes;
		if (!root.containsKey("history")) {
			if (needed > HISTORY_CAP) {
				_report.add(Kind.INVALID, "history",
					"the rings need " + needed + " bytes, the device allows at most " + HISTORY_CAP);
			}
			return;
		}
		enter("history");
		try {
			if (!object(root.get("history"), "'history'")) {
				return;
			}
			Map<?, ?> history = (Map<?, ?>) root.get("history");
			long budget = needed;
			if (history.containsKey("budget")) {
				enter(".budget");
				Long bytes = integer(history.get("budget"), 0, 16777216.0, "budget (bytes)");
				if (bytes == null) {
					leave();
					return;
				}
				if (bytes.longValue() > HISTORY_CAP) {
					refuse("the image asks for " + bytes + " bytes of history, the device allows at most " + HISTORY_CAP);
					leave();
					return;
				}
				leave();
				budget = bytes.longValue();
			}
			if (needed > budget) {
				refuse("the rings need " + needed + " bytes, the image's budget is " + budget);
			}
		} finally {
			leave();
		}
	}

	// ---- values ------------------------------------------------------------------------------

	private boolean object(Object value, String what) {
		if (!(value instanceof Map<?, ?>)) {
			refuse(what + " must be an object");
			return false;
		}
		return true;
	}

	private boolean array(Object value, String what) {
		if (!(value instanceof List<?>)) {
			refuse(what + " must be an array");
			return false;
		}
		return true;
	}

	/**
	 * A string of min..max bytes of UTF-8 without control characters, <code>null</code> (after a
	 * refusal) otherwise.
	 */
	private String string(Object value, int min, int max, String what) {
		if (!(value instanceof String text)) {
			refuse(what + " must be a string");
			return null;
		}
		int length = text.getBytes(StandardCharsets.UTF_8).length;
		if (length < min || length > max) {
			refuse(what + " must have " + min + ".." + max + " bytes");
			return null;
		}
		for (int n = 0; n < text.length(); n++) {
			char c = text.charAt(n);
			if (c < 0x20 || c == 0x7F) {
				refuse(what + " contains a control character");
				return null;
			}
		}
		return text;
	}

	private Long integer(Object value, double min, double max, String what) {
		if (!(value instanceof Number number) || Math.floor(number.doubleValue()) != number.doubleValue()) {
			refuse(what + " must be an integer");
			return null;
		}
		double x = number.doubleValue();
		if (x < min || x > max) {
			refuse(what + " must be " + String.format(Locale.ROOT, "%.0f..%.0f", Double.valueOf(min), Double.valueOf(max)));
			return null;
		}
		return Long.valueOf((long) x);
	}

	private boolean number(Object value, double min, double max, String what) {
		if (!(value instanceof Number number)) {
			refuse(what + " must be a number");
			return false;
		}
		double x = number.doubleValue();
		if (x < min || x > max) {
			refuse(what + " must be " + format(min) + ".." + format(max));
			return false;
		}
		return true;
	}

	private static String format(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
	}

	private boolean flag(Map<?, ?> object, String member) {
		if (!object.containsKey(member)) {
			return false;
		}
		Object value = object.get(member);
		if (!(value instanceof Boolean flag)) {
			enter("." + member);
			refuse(member + " must be true or false");
			leave();
			return false;
		}
		return flag.booleanValue();
	}

	private void label(Object value) {
		if (value instanceof String) {
			string(value, 1, MAX_NAME, "the label");
			return;
		}
		if (!(value instanceof Map<?, ?> map)) {
			refuse("the label must be a string or an object of language tags to strings");
			return;
		}
		int entries = 0;
		boolean ok = true;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String tag = String.valueOf(entry.getKey());
			if (!tag.matches("[A-Za-z0-9\\-]{2,8}")) {
				refuse("a label's language tag must have 2..8 characters of A-Z a-z 0-9 -");
				ok = false;
				continue;
			}
			enter("." + tag);
			ok = string(entry.getValue(), 1, MAX_NAME, "the label") != null && ok;
			leave();
			entries++;
		}
		if (entries == 0 && ok) {
			refuse("the label names no language");
		}
	}

	// ---- path and report ---------------------------------------------------------------------

	private void refuse(String reason) {
		_report.add(Kind.INVALID, path(), reason);
	}

	private String path() {
		return _path.toString();
	}

	private final List<Integer> _marks = new ArrayList<>();

	private void enter(String segment) {
		_marks.add(Integer.valueOf(_path.length()));
		_path.append(segment);
	}

	private void leave() {
		_path.setLength(_marks.remove(_marks.size() - 1).intValue());
	}

}
