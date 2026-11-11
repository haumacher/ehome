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

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;

import de.haumacher.ehome.blocks.BlockSet;
import de.haumacher.ehome.rules.DatapointId;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleLoader.LoadException;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.runtime.ImageReport.Kind;
import de.haumacher.ehome.runtime.Json.JsonException;
import de.haumacher.ehome.zigbee.ZigbeeFunctionTable;
import de.haumacher.ehome.zigbee.ZigbeeReporting;

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

	/**
	 * The minor version of the schema this validator reads (1.2: the adjustable schedule members,
	 * homecontrol #58; 1.3: the blocks; 1.4: the enumeration press of a button with its
	 * <code>presses</code>, the Zigbee remotes, haumacher/ehome #70; 1.5: the parameter ids of the rule
	 * id characters, haumacher/ehome #110; 1.6: the roles atTop, atBottom and heatingLevel, a heating of a
	 * number, a dimmer with its switching command alone, rules writing an application datapoint that is
	 * not writable, haumacher/ehome #134, #136, #138).
	 */
	public static final int SCHEMA_MINOR = 6;

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

	/** The most Zigbee bindings of an image (spec/zigbee.md, "Bindings"). */
	public static final int MAX_ZIGBEE_BINDINGS = 512;

	/** The most control pages of an image (ui.pages, homecontrol #54). */
	public static final int MAX_PAGES = 16;

	/** The most elements of all control pages together, a room counted by its functions. */
	public static final int MAX_PAGE_ELEMENTS = 512;

	/** The most sections of all control pages together. */
	public static final int MAX_PAGE_SECTIONS = 128;

	/** The most series of all charts of the control pages together. */
	public static final int MAX_CHART_SERIES = 256;

	/** The most reference lines of all charts of the control pages together. */
	public static final int MAX_CHART_REFERENCES = 128;

	/** The time ranges of a chart. */
	public static final List<String> CHART_RANGES = List.of("1h", "24h", "7d", "30d", "1y");

	/** The consolidations of a chart series. */
	public static final List<String> CHART_CONSOLIDATIONS = List.of("mean", "last", "band", "consumption");

	/** The axes of a chart. */
	public static final List<String> CHART_AXES = List.of("left", "right");

	/** The range a chart shows first when it names none. */
	public static final String DEFAULT_CHART_RANGE = "24h";

	/** The largest magnitude of a chart's axis bound and of a reference line's value. */
	public static final double MAX_CHART_VALUE = 1e9;

	/** The most characters of a page's icon. */
	public static final int MAX_ICON = 31;

	/** The value types a reference line may follow. */
	private static final Set<String> NUMERIC_TYPES = Set.of(ValueTypes.UNSIGNED, ValueTypes.SIGNED, ValueTypes.REAL);

	/**
	 * The most history RAM a controller (an ESP32-C6) allows, <code>history.budget</code>: the raw rings
	 * and the archives' open slots (homecontrol <code>HistoryLimits::cap_bytes</code>).
	 */
	public static final int HISTORY_CAP = 65536;

	/**
	 * The most flash the archives of a controller (an ESP32-C6) may use,
	 * <code>history.archiveBudget</code> (homecontrol <code>HistoryLimits::archive_cap_bytes</code>, 16
	 * blocks of 4 KB).
	 */
	public static final int ARCHIVE_CAP = 65536;

	private static final int MAX_SETTING = 127;

	private static final int MIN_SPACING = 10;

	static final int MAX_SPACING = 86400;

	private static final int MAX_RETENTION = 31 * 86400;

	static final int MAX_SAMPLES = 2048;

	private static final int SAMPLE_BYTES = 8;

	/** The most archives of a datapoint's history (homecontrol #56). */
	static final int MAX_ARCHIVES = 4;

	/** The most slots of an archive. */
	static final int MAX_SLOTS = 2048;

	/** The bytes of a slot of an archive. */
	static final int SLOT_BYTES = 32;

	/**
	 * The seconds of closed slots an archive keeps in RAM before they are written to flash (homecontrol
	 * <code>History::kPendingS</code>).
	 */
	static final int PENDING_SECONDS = 3600;

	/** The slots of a segment of an archive on flash (homecontrol <code>History::kSlotsPerSegment</code>). */
	static final int SEGMENT_SLOTS = 127;

	/** The bytes of a segment of an archive on flash: a header and its slots, one 4 KB block. */
	static final int SEGMENT_BYTES = 4096;

	private static final int MIN_RESOLUTION = 60;

	private static final int MAX_RESOLUTION = 31 * 86400;

	private static final long MAX_ARCHIVE_RETENTION = 3650L * 86400;

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

	/**
	 * The indexes of the values of the application: datapoints declared <code>virtual</code> or without a
	 * technology member, which rules write also when they are not writable (homecontrol
	 * <code>spec/runtime-image.md</code> 3.3, schema 1.6, haumacher/ehome #138).
	 */
	private final Set<Integer> _application = new HashSet<>();

	/** The valid Zigbee bindings, in the order of their datapoints. */
	private final List<ZigbeeDraft> _zigbee = new ArrayList<>();

	/** The outputs of the schedule entries: the scheduler owns them. */
	private final Set<String> _scheduleOutputs = new HashSet<>();

	/** The RAM of the valid histories: their rings and their archives' open slots. */
	private long _historyRam;

	/** The flash of the archives of the valid histories. */
	private long _historyFlash;

	/** The RAM of the valid histories by the id of their datapoint, in the order of the datapoints. */
	private final Map<String, Long> _ramConsumers = new LinkedHashMap<>();

	/** The flash of the valid histories by the id of their datapoint, in the order of the datapoints. */
	private final Map<String, Long> _flashConsumers = new LinkedHashMap<>();

	/** The RAM of the history checked last. */
	private long _lastHistoryRam;

	/** The flash of the history checked last. */
	private long _lastHistoryFlash;

	/** The indexes of the datapoints with a valid history. */
	private final Set<Integer> _withHistory = new HashSet<>();

	/** The ids of the valid functions. */
	private final Set<String> _functionIds = new HashSet<>();

	/** The image's minor version when it is newer than this validator reads (same major), else 0. */
	private int _newerMinor;

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
			_report.add(Kind.TOO_LARGE, "", "the image has " + bytes + " bytes, the limit is " + MAX_IMAGE_BYTES,
				I18NConstants.IMAGE_TOO_LARGE__BYTES_LIMIT.fill(Integer.valueOf(bytes), Integer.valueOf(MAX_IMAGE_BYTES)));
			return;
		}
		Object root;
		try {
			root = Json.parse(text, MAX_NESTING);
		} catch (JsonException ex) {
			_report.add(Kind.SYNTAX, "", "not JSON: " + ex.getMessage() + " at character " + ex.getOffset(),
				I18NConstants.IMAGE_NOT_JSON__ERROR_OFFSET.fill(ex.getMessage(), String.valueOf(ex.getOffset())));
			return;
		}
		if (!(root instanceof Map<?, ?> image)) {
			refuse("the image must be a JSON object", I18NConstants.IMAGE_NOT_A_JSON_OBJECT);
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
			ui(image.get("ui"));
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
		blocks(image);
	}

	/**
	 * The blocks (homecontrol spec/blocks.md 11), checked last, against the datapoints, the
	 * schedules and the rules, by the loader's port {@link BlockSet}: the first refusal with the
	 * device's path and reason.
	 */
	private void blocks(Map<?, ?> root) {
		if (!root.containsKey("blocks") || !_report.isValid()) {
			return;
		}
		Directory directory = directory();
		try {
			BlockSet.load(root.get("blocks"), directory, datapoint -> {
				if (_scheduleOutputs.contains(datapoint)) {
					return "a schedule entry";
				}
				String owner = directory.owner(datapoint);
				if (owner == null) {
					return null;
				}
				switch (owner) {
					case "the time master":
						return "the KNX time master";
					case "the rule engine":
						return "the rule engine (a rule parameter)";
					case "the scheduler":
						return "a schedule entry";
					case BLOCK_OWNER:
						return "the block runtime (a block's datapoint)";
					default:
						return null;
				}
			}, BlockInstances.registry());
		} catch (BlockSet.LoadException ex) {
			BlockSet.Refusal refusal = ex.getRefusal();
			String reason = "block '" + refusal.block() + "': " + refusal.error().externalName() + ": " + refusal.text();
			ResKey message = refusal.block().isEmpty() ? refusal.message()
				: I18NConstants.IMAGE_IN_BLOCK__BLOCK_MESSAGE.fill(refusal.block(), refusal.message());
			if (refusal.strict()) {
				_report.add(Kind.INVALID, refusal.path(), strict(reason), strict(reason, message));
			} else {
				_report.add(Kind.INVALID, refusal.path(), reason, message);
			}
		}
	}

	/** The owner of the blocks' datapoints. */
	private static final String BLOCK_OWNER = "the block runtime";

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
			public boolean ruleWritable(String name) {
				int index = datapointIndex(name);
				return index >= 0 && (_writable.get(index).booleanValue() || _application.contains(Integer.valueOf(index)));
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
			_report.add(Kind.INVALID, refusal.path(), strict(refusal.reason()), strict(refusal.reason(), refusal.message()));
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
			String reason = "rule '" + refusal.rule() + "': " + refusal.error().externalName() + ": " + refusal.text();
			ResKey message = refusal.rule().isEmpty() ? refusal.message()
				: I18NConstants.IMAGE_IN_RULE__RULE_MESSAGE.fill(refusal.rule(), refusal.message());
			_report.add(Kind.INVALID, refusal.path(), strict(reason), strict(reason, message));
		}
	}

	/**
	 * The reason of a refusal of a rule or schedule entry: an unknown member of an image of a newer
	 * minor version adds that the firmware is older than the image (spec/runtime-image.md I4).
	 */
	private String strict(String reason) {
		if (!newerMember(reason)) {
			return reason;
		}
		return reason + " (the image's schema " + SCHEMA_MAJOR + "." + _newerMinor
			+ " is newer than this firmware reads, up to " + SCHEMA_MAJOR + "." + SCHEMA_MINOR + ": update the firmware)";
	}

	/**
	 * The message for the user of a refusal of a loader with the given reason: for an unknown member
	 * of an image of a newer minor version with the hint of {@link #strict(String)}.
	 */
	private ResKey strict(String reason, ResKey message) {
		if (!newerMember(reason)) {
			return message;
		}
		return I18NConstants.IMAGE_LOADER_NEWER__MESSAGE_SCHEMA_SUPPORTED.fill(message, SCHEMA_MAJOR + "." + _newerMinor,
			SCHEMA_MAJOR + "." + SCHEMA_MINOR);
	}

	private boolean newerMember(String reason) {
		return _newerMinor != 0 && reason.contains("malformed: unknown member");
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
				refuse("the member 'image' (schemaVersion, projectId, revision) is missing", I18NConstants.IMAGE_HEADER_MISSING);
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
					missing("schemaVersion");
					return false;
				}
				String version = string(image.get("schemaVersion"), 3, 15, "schemaVersion");
				if (version == null) {
					return false;
				}
				int[] parsed = parseVersion(version);
				if (parsed == null || parsed[0] == 0) {
					refuse("schemaVersion \"" + version + "\" is not <major>.<minor> with a major version from 1",
							I18NConstants.IMAGE_VERSION_MALFORMED__VERSION.fill(version));
					return false;
				}
				if (parsed[0] > SCHEMA_MAJOR) {
					_report.add(Kind.VERSION, path(), "schemaVersion " + version + " is newer than this firmware reads ("
						+ SCHEMA_MAJOR + ".x, up to " + SCHEMA_MAJOR + "." + SCHEMA_MINOR + "): update the firmware",
							I18NConstants.IMAGE_VERSION_NEWER__VERSION_SUPPORTED.fill(version, SCHEMA_MAJOR + "." + SCHEMA_MINOR));
					return false;
				}
				if (parsed[0] == SCHEMA_MAJOR && parsed[1] > SCHEMA_MINOR) {
					_newerMinor = parsed[1];
				}
			} finally {
				leave();
			}
			enter(".projectId");
			if (!image.containsKey("projectId")) {
				missing("projectId");
			} else {
				string(image.get("projectId"), 1, MAX_NAME, "projectId");
			}
			leave();
			enter(".revision");
			if (!image.containsKey("revision")) {
				missing("revision");
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
					PosixTz.Problem error = PosixTz.check(tz);
					if (error != null) {
						refuse("tz \"" + tz + "\" is not a POSIX TZ string: " + error.text(), error.message(tz));
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
						refuse("location needs lat and lon", I18NConstants.IMAGE_LOCATION_NEEDS_LAT_LON);
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
						refuse("ntp needs server", I18NConstants.IMAGE_NTP_NEEDS_SERVER);
					} else {
						enter(".server");
						String server = string(ntp.get("server"), 1, MAX_SETTING, "server");
						if (server != null) {
							HostProblem error = hostNameError(server);
							if (error != null) {
								refuse("server \"" + server + "\" is not a host name: " + error.text(), error.message(server));
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

	/**
	 * Why a text is no host name, as the controller's loader says it.
	 */
	enum HostProblem {
		/** Two dots in a row, or a dot first. */
		EMPTY_LABEL("host name has an empty label", I18NConstants.IMAGE_HOST_EMPTY_LABEL__SERVER),
		/** A character other than a letter, digit, '-' or '.'. */
		CHARACTER("host name may contain only letters, digits, '-' and '.'", I18NConstants.IMAGE_HOST_CHARACTER__SERVER),
		/** A label of more than 63 characters. */
		LONG_LABEL("host name label is longer than 63 characters", I18NConstants.IMAGE_HOST_LONG_LABEL__SERVER),
		/** A dot last. */
		TRAILING_DOT("host name ends with '.'", I18NConstants.IMAGE_HOST_TRAILING_DOT__SERVER);

		private final String _text;

		private final ResKey1 _message;

		HostProblem(String text, ResKey1 message) {
			_text = text;
			_message = message;
		}

		/**
		 * The English reason, as the controller gives it.
		 */
		String text() {
			return _text;
		}

		/**
		 * The whole sentence for the user about the given server.
		 */
		ResKey message(String server) {
			return _message.fill(server);
		}
	}

	static HostProblem hostNameError(String name) {
		int label = 0;
		for (int n = 0; n < name.length(); n++) {
			char c = name.charAt(n);
			if (c == '.') {
				if (label == 0) {
					return HostProblem.EMPTY_LABEL;
				}
				label = 0;
				continue;
			}
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
			if (!ok) {
				return HostProblem.CHARACTER;
			}
			if (++label > 63) {
				return HostProblem.LONG_LABEL;
			}
		}
		return label == 0 ? HostProblem.TRAILING_DOT : null;
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
					integer(tm.get("interval"), 60, 86400, "interval (seconds)", Unit.SECONDS);
					leave();
				}
				flag(tm, "atMidnight");
				flag(tm, "onSynchronized");
				if (named == 0) {
					refuse("timeMaster names no address (time, date, dateTime)", I18NConstants.IMAGE_TIME_MASTER_NO_ADDRESS);
					return;
				}
				if (count < named) {
					return;
				}
				List<String> members = new ArrayList<>(_timeMaster.keySet());
				for (int i = 0; i < members.size(); i++) {
					for (int j = i + 1; j < members.size(); j++) {
						if (_timeMaster.get(members.get(i)).equals(_timeMaster.get(members.get(j)))) {
							refuse(members.get(i) + " and " + members.get(j) + " use the same group address",
								I18NConstants.IMAGE_TIME_MASTER_SAME_ADDRESS__FIRST_SECOND.fill(members.get(i), members.get(j)));
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
						limit(list.size(), MAX_DATAPOINTS, "datapoints", I18NConstants.IMAGE_TOO_MANY_DATAPOINTS__COUNT_LIMIT);
						return;
					}
					for (int i = 0; i < list.size(); i++) {
						enter("[" + i + "]");
						datapoint(list.get(i));
						leave();
					}
					zigbeeBindings();
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
		// The time datapoints of the schedules (spec/schedules.md 2.2), after the rule parameters'.
		for (String id : Schedule.timeDatapointIds(root.get("schedules"))) {
			_owners.add("the scheduler");
			_ids.add(Schedule.timeDatapointName(id));
			_types.add(ValueTypes.TIME_OF_DAY);
			_writable.add(Boolean.TRUE);
			_sends.add(null);
		}
		// The datapoints of the blocks (spec/blocks.md 6), after the schedules'.
		for (BlockSet.ImpliedDatapoint datapoint : BlockSet.impliedDatapoints(root.get("blocks"), BlockInstances.registry())) {
			_owners.add(BLOCK_OWNER);
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
					"duplicate id '" + id + "' (also datapoints[" + before + "])",
					I18NConstants.IMAGE_DUPLICATE_DATAPOINT_ID__ID_INDEX.fill(id, String.valueOf(before)));
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
							+ i + "] '" + (id == null ? "?" : id) + "'",
							I18NConstants.IMAGE_TIME_MASTER_ADDRESS_SENT__INDEX_ID.fill(String.valueOf(i), id == null ? "?" : DatapointId.of(id)));
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
			missing("id");
		} else {
			String id = string(dp.get("id"), 1, MAX_NAME, "id");
			if (id != null && _hasTimeMaster && List.of(RESERVED).contains(id)) {
				refuse("id '" + id + "' is reserved for the datapoints of knx.timeMaster",
					I18NConstants.IMAGE_ID_RESERVED_TIME_MASTER__ID.fill(id));
				id = null;
			} else if (id != null && id.startsWith(RuleSet.PARAMETER_DATAPOINT_PREFIX)) {
				refuse("id '" + id + "': the prefix '" + RuleSet.PARAMETER_DATAPOINT_PREFIX
					+ "' is reserved for the datapoints of rule parameters",
					I18NConstants.IMAGE_ID_RESERVED_RULES__ID_PREFIX.fill(id, RuleSet.PARAMETER_DATAPOINT_PREFIX));
				id = null;
			} else if (id != null && id.startsWith(Schedule.DATAPOINT_PREFIX)) {
				refuse("id '" + id + "': the prefix '" + Schedule.DATAPOINT_PREFIX
					+ "' is reserved for the datapoints of the schedules",
					I18NConstants.IMAGE_ID_RESERVED_SCHEDULES__ID_PREFIX.fill(id, Schedule.DATAPOINT_PREFIX));
				id = null;
			} else if (id != null && id.startsWith("block/")) {
				refuse("id '" + id + "': the prefix 'block/' is reserved for the datapoints of the blocks",
					I18NConstants.IMAGE_ID_RESERVED_BLOCKS__ID_PREFIX.fill(id, "block/"));
				id = null;
			}
			_ids.set(index, id);
		}
		leave();
		String type = null;
		enter(".type");
		if (!dp.containsKey("type")) {
			missing("type");
		} else {
			String name = string(dp.get("type"), 1, 15, "type");
			if (name != null) {
				if (ValueTypes.ALL.contains(name)) {
					type = name;
				} else {
					refuse("unknown type \"" + name + "\" (" + String.join(", ", ValueTypes.ALL) + ")",
						I18NConstants.IMAGE_UNKNOWN_TYPE__TYPE_TYPES.fill(name, String.join(", ", ValueTypes.ALL)));
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
			if (history(dp.get("history"), type)) {
				_withHistory.add(Integer.valueOf(index));
				String name = _ids.get(index);
				String key = name != null ? name : "datapoints[" + index + "]";
				_ramConsumers.put(key, Long.valueOf(_lastHistoryRam));
				_flashConsumers.put(key, Long.valueOf(_lastHistoryFlash));
			}
			leave();
		}
		boolean hasVirtual = dp.containsKey("virtual");
		if (hasVirtual) {
			enter(".virtual");
			object(dp.get("virtual"), "virtual");
			leave();
		}
		if (hasVirtual || (!dp.containsKey("zigbee") && !dp.containsKey("knx"))) {
			// A value of the application: rules write it also when it is not writable (1.6).
			_application.add(Integer.valueOf(index));
		}
		if (dp.containsKey("zigbee")) {
			enter(".zigbee");
			if (hasVirtual) {
				refuse("a datapoint is either zigbee or virtual", I18NConstants.IMAGE_ZIGBEE_OR_VIRTUAL);
			} else if (dp.containsKey("knx")) {
				refuse("a datapoint is either knx or zigbee", I18NConstants.IMAGE_KNX_OR_ZIGBEE);
			} else {
				_owners.set(index, "the Zigbee adapter");
				zigbee(dp.get("zigbee"), index, type, transmit);
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
				refuse("a datapoint is either knx or virtual", I18NConstants.IMAGE_KNX_OR_VIRTUAL);
				return;
			}
			if (!object(dp.get("knx"), "knx")) {
				return;
			}
			_owners.set(index, "the KNX adapter");
			Map<?, ?> knx = (Map<?, ?>) dp.get("knx");
			enter(".dpt");
			if (!knx.containsKey("dpt")) {
				missing("dpt");
			} else {
				String text = string(knx.get("dpt"), 1, 15, "dpt");
				if (text != null) {
					int[] dpt = parseDpt(text);
					if (dpt == null) {
						refuse("\"" + text + "\" is not a datapoint type (expected e.g. 1.001, 9.001)",
							I18NConstants.IMAGE_NOT_A_DPT__TEXT.fill(text));
					} else {
						ValueTypes.Unsupported unsupported = ValueTypes.unsupportedType(dpt[0], dpt[1]);
						if (unsupported != null) {
							refuse("DPT " + text + ": " + unsupported.text(), unsupported.message(text));
						} else {
							String carried = ValueTypes.valueType(dpt[0], dpt[1]);
							if (type != null && !carried.equals(type)
								&& !(ValueTypes.isNumeric(carried) && ValueTypes.isNumeric(type))) {
								refuse("DPT " + text + " carries " + carried + " values, the datapoint is " + type,
									I18NConstants.IMAGE_DPT_TYPE_MISMATCH__DPT_CARRIED_TYPE.fill(text, carried, type));
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
							refuse("more than " + MAX_LISTEN + " listening addresses",
								I18NConstants.IMAGE_TOO_MANY_LISTEN__MAX.fill(Integer.valueOf(MAX_LISTEN)));
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
				refuse("a KNX datapoint needs a sending or a listening group address", I18NConstants.IMAGE_KNX_NEEDS_ADDRESS);
			} else if (!hasSend && (transmit || readable)) {
				refuse("a transmitting or readable KNX datapoint needs a sending address (send)",
					I18NConstants.IMAGE_KNX_NEEDS_SEND);
			}
		} finally {
			leave();
		}
	}

	/**
	 * The <code>zigbee</code> member of a datapoint (spec/zigbee.md "Bindings"; homecontrol
	 * <code>parse_zigbee</code>): the device function it stands for. Checks the address, the endpoint,
	 * the cluster/attribute against the function table ({@link ZigbeeFunctionTable}), the command
	 * cluster, the function's value type against the datapoint's, that a transmitting datapoint can
	 * be commanded, and the reporting override ({@link #reporting(Object, ZigbeeFunctionTable.Info)}).
	 */
	private void zigbee(Object value, int index, String type, boolean transmit) {
		if (!object(value, "zigbee")) {
			return;
		}
		Map<?, ?> binding = (Map<?, ?>) value;
		boolean ok = true;
		String ieee = null;
		enter(".ieee");
		if (!binding.containsKey("ieee")) {
			missing("ieee");
			ok = false;
		} else {
			String text = string(binding.get("ieee"), 1, 23, "ieee");
			ieee = text == null ? null : ZigbeeFunctionTable.normalizeIeee(text);
			if (text != null && ieee == null) {
				refuse("\"" + text + "\" is not an IEEE address (16 hex digits, colons allowed; not 0 and not all ones)",
					I18NConstants.IMAGE_NOT_IEEE__TEXT.fill(text));
			}
			ok &= ieee != null;
		}
		leave();
		Long endpoint = null;
		enter(".endpoint");
		if (!binding.containsKey("endpoint")) {
			missing("endpoint");
		} else {
			endpoint = integer(binding.get("endpoint"), 1, 240, "endpoint");
		}
		ok &= endpoint != null;
		leave();
		boolean client = false;
		if (binding.containsKey("client")) {
			ok &= binding.get("client") instanceof Boolean;
			client = flag(binding, "client");
		}
		Long cluster = null;
		enter(".cluster");
		if (!binding.containsKey("cluster")) {
			missing("cluster");
		} else {
			cluster = integer(binding.get("cluster"), 0, 65535, "cluster");
		}
		ok &= cluster != null;
		leave();
		Long attribute = null;
		if (binding.containsKey("attribute")) {
			enter(".attribute");
			attribute = integer(binding.get("attribute"), 0, 65535, "attribute");
			ok &= attribute != null;
			leave();
		} else if (!client) {
			refuse("attribute is missing (only a client binding, a button's commands, has none)",
				I18NConstants.IMAGE_ZIGBEE_ATTRIBUTE_MISSING);
			ok = false;
		}
		Long command = null;
		if (binding.containsKey("command")) {
			enter(".command");
			command = integer(binding.get("command"), 0, 65534, "command (the cluster commands go to)", Unit.NONE);
			ok &= command != null;
			leave();
		}
		if (!ok) {
			return;
		}
		String why = null;
		ResKey message = null;
		ZigbeeFunctionTable.Info function = null;
		if (client) {
			if (cluster.intValue() != ZigbeeFunctionTable.ON_OFF && cluster.intValue() != ZigbeeFunctionTable.LEVEL_CONTROL) {
				why = "a client binding (button commands) needs cluster 6 (On/Off) or 8 (Level Control)";
				message = I18NConstants.IMAGE_ZIGBEE_CLIENT_CLUSTER__CLUSTER.fill(String.valueOf(cluster));
			} else if (command != null) {
				why = "a client binding (button commands) cannot be commanded";
				message = I18NConstants.IMAGE_ZIGBEE_CLIENT_COMMANDED__CLUSTER.fill(String.valueOf(cluster));
			} else {
				function = ZigbeeFunctionTable.byKey(ZigbeeFunctionTable.ACTION);
			}
		} else {
			function = ZigbeeFunctionTable.byAttribute(cluster.intValue(), attribute.intValue());
			if (function == null) {
				why = "no known function for this cluster and attribute (doc/zigbee.md lists them)";
				message = I18NConstants.IMAGE_ZIGBEE_UNKNOWN_FUNCTION__CLUSTER_ATTRIBUTE.fill(String.valueOf(cluster),
					String.valueOf(attribute));
			} else if (command != null && !function.canCommand(command.intValue())) {
				why = "the command cluster cannot set this attribute";
				message = I18NConstants.IMAGE_ZIGBEE_CANNOT_SET__CLUSTER_ATTRIBUTE.fill(String.valueOf(cluster),
					String.valueOf(attribute));
			}
		}
		if (why != null) {
			refuse((client ? "client cluster " + cluster : "cluster " + cluster + " attribute " + attribute) + ": " + why,
				message);
			return;
		}
		String carried = function.valueType();
		if (type != null && !carried.equals(type) && !(ValueTypes.isNumeric(carried) && ValueTypes.isNumeric(type))) {
			refuse("the function " + function.key() + " carries " + carried + " values, the datapoint is " + type,
				I18NConstants.IMAGE_ZIGBEE_TYPE_MISMATCH__FUNCTION_CARRIED_TYPE.fill(function.key(), carried, type));
			return;
		}
		if (transmit && command == null) {
			refuse("a transmitting Zigbee datapoint needs the cluster its commands go to (command); "
				+ (client ? "a client binding (button commands) cannot be commanded" : "without it the binding is read-only"),
				client ? I18NConstants.IMAGE_ZIGBEE_TRANSMIT_CLIENT : I18NConstants.IMAGE_ZIGBEE_TRANSMIT);
			return;
		}
		if (binding.containsKey("reporting")) {
			enter(".reporting");
			boolean valid = reporting(binding.get("reporting"), function);
			leave();
			if (!valid) {
				return;
			}
		}
		if (_ids.get(index) != null) {
			_zigbee.add(new ZigbeeDraft(index, ieee, endpoint.longValue(), function.key(), _ids.get(index)));
		}
	}

	/**
	 * The <code>reporting</code> member of a Zigbee binding of the function (spec/zigbee.md "Reporting
	 * overrides"; homecontrol <code>parse_zigbee_reporting</code>): an object, of a function that
	 * takes an override, whose members have the right types, then the check of the override
	 * ({@link ZigbeeReporting#check(ZigbeeFunctionTable.Info, Long, Long, Double)}), refused at the
	 * member it concerns.
	 *
	 * @return Whether the member is valid.
	 */
	private boolean reporting(Object value, ZigbeeFunctionTable.Info function) {
		if (!object(value, "reporting")) {
			return false;
		}
		ZigbeeReporting.Refusal whole = ZigbeeReporting.wholeRefusal(function);
		if (whole != null) {
			refuse(whole.text(), whole.reason());
			return false;
		}
		Map<?, ?> reporting = (Map<?, ?>) value;
		boolean ok = true;
		Long min = null;
		if (reporting.containsKey("min")) {
			enter(".min");
			min = integer(reporting.get("min"), 0, ZigbeeReporting.MAX_MIN, "min");
			ok &= min != null;
			leave();
		}
		Long max = null;
		if (reporting.containsKey("max")) {
			enter(".max");
			max = integer(reporting.get("max"), 0, ZigbeeReporting.NO_REPORTS, "max");
			ok &= max != null;
			leave();
		}
		Double change = null;
		if (reporting.containsKey("change")) {
			enter(".change");
			if (number(reporting.get("change"), -Double.MAX_VALUE, Double.MAX_VALUE, "change")) {
				change = Double.valueOf(((Number) reporting.get("change")).doubleValue());
			} else {
				ok = false;
			}
			leave();
		}
		if (!ok) {
			return false;
		}
		ZigbeeReporting.Refusal refusal = ZigbeeReporting.check(function, min, max, change);
		if (refusal != null) {
			enter(refusal.member().path());
			refuse(refusal.text(), refusal.reason());
			leave();
			return false;
		}
		return true;
	}

	/**
	 * The checks of all Zigbee bindings together: at most {@link #MAX_ZIGBEE_BINDINGS}, no function
	 * of an endpoint bound twice. The path is the one of the datapoints.
	 */
	private void zigbeeBindings() {
		if (_zigbee.size() > MAX_ZIGBEE_BINDINGS) {
			limit(_zigbee.size(), MAX_ZIGBEE_BINDINGS, "Zigbee bindings", I18NConstants.IMAGE_TOO_MANY_ZIGBEE_BINDINGS__COUNT_LIMIT);
			return;
		}
		for (int i = 0; i < _zigbee.size(); i++) {
			ZigbeeDraft d = _zigbee.get(i);
			for (int k = 0; k < i; k++) {
				ZigbeeDraft e = _zigbee.get(k);
				if (e.ieee().equals(d.ieee()) && e.endpoint() == d.endpoint() && e.function().equals(d.function())) {
					_report.add(Kind.INVALID, "datapoints[" + d.index() + "].zigbee", "device " + d.ieee() + " endpoint "
						+ d.endpoint() + ": the function " + d.function() + " is bound twice (also datapoints[" + e.index()
						+ "] '" + e.name() + "')",
						I18NConstants.IMAGE_ZIGBEE_BOUND_TWICE__DEVICE_ENDPOINT_FUNCTION_INDEX_NAME.fill(d.ieee(),
							String.valueOf(d.endpoint()), d.function(), String.valueOf(e.index()), DatapointId.of(e.name())));
					break;
				}
			}
		}
	}

	/** A valid Zigbee binding of the datapoint with the given index. */
	private record ZigbeeDraft(int index, String ieee, long endpoint, String function, String name) {
		// A record.
	}

	private boolean history(Object value, String type) {
		if (!object(value, "history")) {
			return false;
		}
		Map<?, ?> history = (Map<?, ?>) value;
		boolean ok = true;
		long retention = 0;
		long spacing = 0;
		if (!history.containsKey("retention") || !history.containsKey("spacing")) {
			refuse("history needs retention and spacing (seconds)", I18NConstants.IMAGE_HISTORY_NEEDS_RETENTION_SPACING);
			ok = false;
		} else {
			enter(".spacing");
			Long s = integer(history.get("spacing"), MIN_SPACING, MAX_SPACING, "spacing (seconds)", Unit.SECONDS);
			leave();
			ok = s != null;
			if (ok) {
				spacing = s.longValue();
				enter(".retention");
				Long r = integer(history.get("retention"), spacing, MAX_RETENTION, "retention (seconds, at least the spacing)",
					Unit.SECONDS);
				leave();
				ok = r != null;
				if (ok) {
					retention = r.longValue();
				}
			}
		}
		if (ok && samples(retention, spacing) > MAX_SAMPLES) {
			refuse("retention / spacing gives more than " + MAX_SAMPLES + " samples",
				I18NConstants.IMAGE_HISTORY_TOO_MANY_SAMPLES__MAX.fill(Integer.valueOf(MAX_SAMPLES)));
			ok = false;
		}
		if (type != null && !ValueTypes.BOOLEAN.equals(type) && !ValueTypes.UNSIGNED.equals(type)
			&& !ValueTypes.SIGNED.equals(type) && !ValueTypes.REAL.equals(type)) {
			refuse("history needs a boolean, unsigned, signed or real datapoint", I18NConstants.IMAGE_HISTORY_TYPE);
			ok = false;
		}
		long ram = ok ? samples(retention, spacing) * SAMPLE_BYTES : 0;
		long flash = 0;
		if (history.containsKey("archives")) {
			enter(".archives");
			long[] archives = archives(history.get("archives"), ok ? spacing : 0);
			leave();
			if (archives == null) {
				ok = false;
			} else {
				ram += archives[0];
				flash += archives[1];
			}
		}
		if (ok) {
			_historyRam += ram;
			_historyFlash += flash;
			_lastHistoryRam = ram;
			_lastHistoryFlash = flash;
		}
		return ok;
	}

	/**
	 * The member <code>archives</code> of a datapoint's history (schema 1.1, homecontrol #56): at most
	 * {@value #MAX_ARCHIVES} objects of a <code>resolution</code> from 60 s to 31 days coarser than the
	 * raw ring's spacing, and a <code>retention</code> of at least the resolution, at most 10 years and
	 * at most {@value #MAX_SLOTS} slots; no two of the same resolution (an archive is compared with the
	 * archives before it that were not refused, as the device does).
	 *
	 * @param spacing
	 *        The raw ring's spacing; 0 where it was refused.
	 * @return The RAM and the flash of the archives ({@link #archiveRamBytes(long)},
	 *         {@link #archiveFlashBytes(long, long)}); <code>null</code> where they are refused.
	 */
	private long[] archives(Object value, long spacing) {
		if (!array(value, "archives")) {
			return null;
		}
		List<?> list = (List<?>) value;
		if (list.size() > MAX_ARCHIVES) {
			refuse("more than " + MAX_ARCHIVES + " archives", I18NConstants.IMAGE_TOO_MANY_ARCHIVES__MAX.fill(Integer.valueOf(MAX_ARCHIVES)));
			return null;
		}
		long ram = 0;
		long flash = 0;
		Set<Long> resolutions = new HashSet<>();
		boolean ok = true;
		for (int n = 0; n < list.size(); n++) {
			enter("[" + n + "]");
			try {
				if (!object(list.get(n), "an archive")) {
					ok = false;
					continue;
				}
				Map<?, ?> archive = (Map<?, ?>) list.get(n);
				if (!archive.containsKey("resolution") || !archive.containsKey("retention")) {
					refuse("an archive needs resolution and retention (seconds)", I18NConstants.IMAGE_ARCHIVE_NEEDS_RESOLUTION_RETENTION);
					ok = false;
					continue;
				}
				enter(".resolution");
				Long resolution = integer(archive.get("resolution"), MIN_RESOLUTION, MAX_RESOLUTION, "resolution (seconds)",
					Unit.SECONDS);
				if (resolution != null && resolution.longValue() <= spacing) {
					refuse("the resolution must be larger than the raw spacing (" + spacing + " s)",
						I18NConstants.IMAGE_ARCHIVE_RESOLUTION__SPACING.fill(Long.valueOf(spacing)));
					resolution = null;
				}
				if (resolution != null && resolutions.contains(resolution)) {
					refuse("two archives of the resolution " + resolution + " s",
						I18NConstants.IMAGE_ARCHIVE_SAME_RESOLUTION__RESOLUTION.fill(resolution));
					resolution = null;
				}
				leave();
				if (resolution == null) {
					ok = false;
					continue;
				}
				enter(".retention");
				Long retention = integer(archive.get("retention"), resolution.longValue(), MAX_ARCHIVE_RETENTION,
					"retention (seconds, at least the resolution, at most 10 years)", Unit.SECONDS);
				leave();
				if (retention == null) {
					ok = false;
					continue;
				}
				long slots = samples(retention.longValue(), resolution.longValue());
				if (slots > MAX_SLOTS) {
					refuse("retention / resolution gives more than " + MAX_SLOTS + " slots",
						I18NConstants.IMAGE_ARCHIVE_TOO_MANY_SLOTS__MAX.fill(Integer.valueOf(MAX_SLOTS)));
					ok = false;
					continue;
				}
				resolutions.add(resolution);
				ram += archiveRamBytes(resolution.longValue());
				flash += archiveFlashBytes(retention.longValue(), resolution.longValue());
			} finally {
				leave();
			}
		}
		return ok ? new long[] { ram, flash } : null;
	}

	/**
	 * The number of samples of a history ring, or of slots of an archive.
	 */
	public static long samples(long retention, long spacing) {
		return spacing == 0 ? 0 : (retention + spacing - 1) / spacing + 1;
	}

	/**
	 * The RAM of an archive against <code>history.budget</code> (homecontrol
	 * <code>History::archive_ram_bytes</code>): its open slot and the closed slots of up to
	 * {@value #PENDING_SECONDS} seconds not yet written, <code>(ceil(3600 / resolution) + 2) * 32</code>
	 * (96 bytes for a resolution of an hour or more).
	 */
	public static long archiveRamBytes(long resolution) {
		return resolution == 0 ? 0 : ((PENDING_SECONDS + resolution - 1) / resolution + 2) * SLOT_BYTES;
	}

	/**
	 * The segments of an archive on flash: <code>ceil(slots / 127)</code>.
	 */
	public static long archiveSegments(long retention, long resolution) {
		return (samples(retention, resolution) + SEGMENT_SLOTS - 1) / SEGMENT_SLOTS;
	}

	/**
	 * The flash of an archive against <code>history.archiveBudget</code> (homecontrol
	 * <code>History::archive_flash_bytes</code>): 4096 bytes per segment.
	 */
	public static long archiveFlashBytes(long retention, long resolution) {
		return archiveSegments(retention, resolution) * SEGMENT_BYTES;
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
		ResKey2 message = null;
		Integer result = null;
		String[] parts = text.split("/", -1);
		if (parts.length > 3) {
			error = "too many levels";
			message = I18NConstants.IMAGE_ADDRESS_LEVELS__PATH_TEXT;
		} else {
			long[] numbers = new long[parts.length];
			for (int n = 0; n < parts.length && error == null; n++) {
				if (!parts[n].matches("[0-9]{1,5}")) {
					error = "not a number";
					message = I18NConstants.IMAGE_ADDRESS_NUMBER__PATH_TEXT;
				} else {
					numbers[n] = Long.parseLong(parts[n]);
				}
			}
			if (error == null) {
				if (parts.length == 1) {
					error = "group address needs two or three levels (1/2/3 or 1/515)";
					message = I18NConstants.IMAGE_ADDRESS_ONE_LEVEL__PATH_TEXT;
				} else if (numbers[0] > 31) {
					error = "main group must be 0..31";
					message = I18NConstants.IMAGE_ADDRESS_MAIN__PATH_TEXT;
				} else if (parts.length == 2) {
					if (numbers[1] > 2047) {
						error = "sub group of a two-level address must be 0..2047";
						message = I18NConstants.IMAGE_ADDRESS_SUB_TWO_LEVELS__PATH_TEXT;
					} else {
						result = Integer.valueOf((int) (numbers[0] << 11 | numbers[1]));
					}
				} else if (numbers[1] > 7) {
					error = "middle group must be 0..7";
					message = I18NConstants.IMAGE_ADDRESS_MIDDLE__PATH_TEXT;
				} else if (numbers[2] > 255) {
					error = "sub group must be 0..255";
					message = I18NConstants.IMAGE_ADDRESS_SUB__PATH_TEXT;
				} else {
					result = Integer.valueOf((int) (numbers[0] << 11 | numbers[1] << 8 | numbers[2]));
				}
			}
		}
		if (result == null) {
			refuse(what + " \"" + text + "\" is not a group address (" + error + ")", message.fill(path(), text));
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
				limit(list.size(), MAX_LOCATIONS, "locations", I18NConstants.IMAGE_TOO_MANY_LOCATIONS__COUNT_LIMIT);
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
							refuse("duplicate location id '" + id + "'", I18NConstants.IMAGE_DUPLICATE_LOCATION_ID__ID.fill(id));
							leave();
						}
						ids.set(i, id);
					}
					if (!location.containsKey("label")) {
						missing("label");
					} else {
						enter(".label");
						label(location.get("label"));
						leave();
					}
					if (location.containsKey("kind")) {
						enter(".kind");
						String kind = string(location.get("kind"), 1, 15, "kind");
						if (kind != null && !LOCATION_KINDS.contains(kind)) {
							refuse("unknown location kind \"" + kind + "\" (building, floor, room, area, outside)",
								I18NConstants.IMAGE_UNKNOWN_LOCATION_KIND__KIND.fill(kind));
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
					_report.add(Kind.INVALID, path() + "[" + k + "].parent", "unknown location '" + parent + "'",
						I18NConstants.IMAGE_UNKNOWN_LOCATION__ID.fill(parent));
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
					_report.add(Kind.INVALID, path() + "[" + k + "].parent", "the parents form a cycle",
						I18NConstants.IMAGE_LOCATION_CYCLE);
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

		/**
		 * The value types the role takes, for a refusal, as the controller's loader names them
		 * (<code>role_types_text</code>).
		 */
		String typesText() {
			if (types == null) {
				return "any type";
			}
			if (types == PRESS) {
				return "boolean, controlled or enumeration";
			}
			if (types == BOOL) {
				return "boolean";
			}
			if (types == BOOL_OR_NUMBER) {
				return "boolean or a number (unsigned, signed or real)";
			}
			if (types == NUMBER) {
				return "a number (unsigned, signed or real)";
			}
			if (types == ENUM_OR_NUMBER) {
				return "enumeration or a number";
			}
			if (types == CONTROLLED) {
				return "controlled";
			}
			return "a number, enumeration or controlled";
		}
	}

	private static final List<String> BOOL = List.of(ValueTypes.BOOLEAN);

	/** A climate's heating (1.6): a boolean, or a number, the actuating value, heating while above 0. */
	private static final List<String> BOOL_OR_NUMBER = List.of(ValueTypes.BOOLEAN, ValueTypes.UNSIGNED, ValueTypes.SIGNED,
		ValueTypes.REAL, ValueTypes.ENUMERATION);

	private static final List<String> NUMBER = List.of(ValueTypes.UNSIGNED, ValueTypes.SIGNED, ValueTypes.REAL,
		ValueTypes.ENUMERATION);

	private static final List<String> ENUM_OR_NUMBER = List.of(ValueTypes.ENUMERATION, ValueTypes.UNSIGNED,
		ValueTypes.SIGNED, ValueTypes.REAL);

	private static final List<String> CONTROLLED = List.of(ValueTypes.CONTROLLED);

	/** The press of a button: an enumeration (1.4) is a Zigbee remote's action, with presses. */
	private static final List<String> PRESS = List.of(ValueTypes.BOOLEAN, ValueTypes.CONTROLLED,
		ValueTypes.ENUMERATION);

	/**
	 * The kinds of press of a button's <code>presses</code> (spec/runtime-image.md 3.5, 1.4), in the
	 * order the controller's loader reads them.
	 */
	public static final List<String> PRESS_KINDS = List.of("press", "on", "off", "up", "down", "release");

	/** The largest element of a Zigbee action (5, dim stop; homecontrol doc/zigbee.md). */
	public static final int MAX_ACTION_ELEMENT = 5;

	private static final Map<String, List<Role>> FUNCTION_KINDS = Map.of(
		"light", List.of(new Role("command", BOOL, true, true), new Role("state", BOOL, false, false)),
		// A dimmer needs command or levelCommand, at least one (1.6), checked after its roles.
		"dimmer", List.of(new Role("command", BOOL, true, false), new Role("state", BOOL, false, false),
			new Role("levelCommand", NUMBER, true, false), new Role("level", NUMBER, false, false),
			new Role("step", CONTROLLED, true, false)),
		"blind", List.of(new Role("move", BOOL, true, true), new Role("stop", BOOL, true, false),
			new Role("position", NUMBER, false, false), new Role("positionCommand", NUMBER, true, false),
			new Role("slat", NUMBER, false, false), new Role("slatCommand", NUMBER, true, false),
			new Role("atTop", BOOL, false, false), new Role("atBottom", BOOL, false, false)),
		"switch", List.of(new Role("command", BOOL, true, true), new Role("state", BOOL, false, false)),
		"sensor", List.of(new Role("value", null, false, true)),
		"climate", List.of(new Role("temperature", NUMBER, false, true), new Role("setpoint", NUMBER, false, false),
			new Role("setpointCommand", NUMBER, true, false), new Role("mode", ENUM_OR_NUMBER, false, false),
			new Role("modeCommand", ENUM_OR_NUMBER, true, false), new Role("heating", BOOL_OR_NUMBER, false, false),
			new Role("heatingLevel", NUMBER, false, false)),
		"scene", List.of(new Role("command", List.of(ValueTypes.UNSIGNED, ValueTypes.SIGNED, ValueTypes.REAL,
			ValueTypes.ENUMERATION, ValueTypes.CONTROLLED), true, true)),
		"button", List.of(new Role("press", PRESS, false, true),
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
				limit(list.size(), MAX_FUNCTIONS, "functions", I18NConstants.IMAGE_TOO_MANY_FUNCTIONS__COUNT_LIMIT);
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
			refuse("duplicate function id '" + id + "'", I18NConstants.IMAGE_DUPLICATE_FUNCTION_ID__ID.fill(id));
			leave();
		} else if (id != null) {
			_functionIds.add(id);
		}
		if (!function.containsKey("label")) {
			missing("label");
		} else {
			enter(".label");
			label(function.get("label"));
			leave();
		}
		if (function.containsKey("room")) {
			enter(".room");
			String room = string(function.get("room"), 1, 32, "room");
			if (room != null && !locations.contains(room)) {
				refuse("unknown location '" + room + "'", I18NConstants.IMAGE_UNKNOWN_LOCATION__ID.fill(room));
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
			missing("kind");
		} else {
			kind = string(function.get("kind"), 1, 15, "kind");
			if (kind != null) {
				roles = FUNCTION_KINDS.get(kind);
				if (roles == null) {
					refuse("unknown function kind \"" + kind
						+ "\" (light, dimmer, blind, switch, sensor, climate, scene, button)",
						I18NConstants.IMAGE_UNKNOWN_FUNCTION_KIND__KIND.fill(kind));
				}
			}
		}
		leave();
		if (roles != null && "scene".equals(kind)) {
			enter(".scene");
			if (!function.containsKey("scene")) {
				refuse("a scene function needs scene (1..64)", I18NConstants.IMAGE_SCENE_NEEDS_NUMBER);
			} else {
				integer(function.get("scene"), 1, 64, "scene");
			}
			leave();
		}
		if (roles == null) {
			return;
		}
		String pressName = null;
		String pressType = null;
		boolean action = false;
		enter(".datapoints");
		try {
			if (!function.containsKey("datapoints")) {
				refuse("datapoints (the roles of a " + kind + ") is missing", I18NConstants.IMAGE_FUNCTION_ROLES_MISSING__KIND.fill(kind));
				return;
			}
			if (!object(function.get("datapoints"), "datapoints")) {
				return;
			}
			Map<?, ?> refs = (Map<?, ?>) function.get("datapoints");
			boolean button = "button".equals(kind);
			for (Role role : roles) {
				if (!refs.containsKey(role.name())) {
					if (role.required()) {
						refuse("a " + kind + " needs the role " + role.name(),
							I18NConstants.IMAGE_ROLE_MISSING__KIND_ROLE.fill(kind, role.name()));
					}
					continue;
				}
				enter("." + role.name());
				String name = string(refs.get(role.name()), 1, MAX_NAME, "the role");
				if (name != null) {
					int dp = _ids.indexOf(name);
					if (dp < 0) {
						refuse("unknown datapoint '" + name + "'", I18NConstants.IMAGE_UNKNOWN_DATAPOINT__NAME.fill(DatapointId.of(name)));
					} else {
						String type = _types.get(dp);
						if (role.types() != null && !role.types().contains(type)) {
							String types = String.join(", ", role.types());
							refuse("datapoint '" + name + "' is " + (type == null ? "none" : type) + ", but the role "
								+ role.name() + " of a " + kind + " takes " + role.typesText(),
								type == null ? I18NConstants.IMAGE_ROLE_TYPE_NONE__NAME_ROLE_KIND_TYPES.fill(DatapointId.of(name), role.name(), kind, types)
									: I18NConstants.IMAGE_ROLE_TYPE__NAME_TYPE_ROLE_KIND_TYPES.fill(DatapointId.of(name), type, role.name(), kind, types));
						}
						if (role.writable() && !_writable.get(dp).booleanValue()) {
							refuse("the role " + role.name() + " needs a writable datapoint; '" + name + "' is not",
								I18NConstants.IMAGE_ROLE_WRITABLE__ROLE_NAME.fill(role.name(), DatapointId.of(name)));
						}
						if (button && "press".equals(role.name())) {
							pressType = type;
							pressName = name;
							action = zigbeeAction(dp);
							if (ValueTypes.ENUMERATION.equals(type) && !action) {
								refuse("an enumeration press needs a Zigbee action datapoint (a client binding), whose "
									+ "elements are known; '" + name + "' is not one",
									I18NConstants.IMAGE_PRESS_NEEDS_ACTION__NAME.fill(DatapointId.of(name)));
							}
						}
					}
				}
				leave();
			}
			if ("dimmer".equals(kind) && !refs.containsKey("command") && !refs.containsKey("levelCommand")) {
				// Its switching command is its main role; an image of 1.5 had its level command (1.6).
				refuse("a dimmer needs the role command or levelCommand", I18NConstants.IMAGE_DIMMER_NEEDS_COMMAND);
			}
		} finally {
			leave();
		}
		if (pressName != null) {
			presses(function, pressName, pressType, action);
		}
	}

	/**
	 * Whether the datapoint of the index is bound to a Zigbee client cluster (the action of a remote,
	 * homecontrol <code>zigbee_action</code>), whose elements are 0..{@value #MAX_ACTION_ELEMENT}.
	 */
	private boolean zigbeeAction(int index) {
		for (ZigbeeDraft draft : _zigbee) {
			if (draft.index() == index && ZigbeeFunctionTable.ACTION.equals(draft.function())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The member <code>presses</code> of a button (spec/runtime-image.md 3.5, 1.4; homecontrol
	 * <code>parse_presses</code>): required for an enumeration press, refused for another; strict
	 * inside.
	 *
	 * @param action
	 *        Whether the press is a Zigbee action, whose elements the elements must be; an enumeration
	 *        press that is none was refused at its role.
	 */
	private void presses(Map<?, ?> function, String press, String type, boolean action) {
		boolean has = function.containsKey("presses");
		Object value = function.get("presses");
		enter(".presses");
		try {
			if (!ValueTypes.ENUMERATION.equals(type)) {
				if (has) {
					refuse("presses is only for an enumeration press; '" + press + "' is " + (type == null ? "none" : type),
						type == null ? I18NConstants.IMAGE_PRESSES_NOT_ENUMERATION_NONE__NAME.fill(DatapointId.of(press))
							: I18NConstants.IMAGE_PRESSES_NOT_ENUMERATION__NAME_TYPE.fill(DatapointId.of(press), type));
				}
				return;
			}
			if (!has) {
				refuse("a button with an enumeration press needs presses (which elements are which press)",
					I18NConstants.IMAGE_PRESSES_MISSING);
				return;
			}
			if (!(value instanceof Map<?, ?> presses)) {
				refuse("malformed: presses must be an object of press kinds (press, on, off, up, down, release) to "
					+ "arrays of elements", I18NConstants.IMAGE_PRESSES_MALFORMED);
				return;
			}
			boolean known = true;
			for (Object member : presses.keySet()) {
				String name = String.valueOf(member);
				if (!PRESS_KINDS.contains(name) && !"description".equals(name) && !"comment".equals(name)) {
					refuse(strict("malformed: unknown member '" + name + "'"), _newerMinor == 0
						? I18NConstants.IMAGE_UNKNOWN_MEMBER__NAME.fill(name)
						: I18NConstants.IMAGE_UNKNOWN_MEMBER_NEWER__NAME_SCHEMA_SUPPORTED.fill(name,
							SCHEMA_MAJOR + "." + _newerMinor, SCHEMA_MAJOR + "." + SCHEMA_MINOR));
					known = false;
				}
			}
			if (!known) {
				return;
			}
			Map<Long, String> owners = new HashMap<>();
			int named = 0;
			for (String kind : PRESS_KINDS) {
				if (!presses.containsKey(kind)) {
					continue;
				}
				named++;
				enter("." + kind);
				try {
					if (!(presses.get(kind) instanceof List<?> elements) || elements.isEmpty()) {
						refuse("malformed: " + kind + " must be a non-empty array of elements",
							I18NConstants.IMAGE_PRESS_ELEMENTS__KIND.fill(kind));
						continue;
					}
					for (int k = 0; k < elements.size(); k++) {
						enter("[" + k + "]");
						element(elements.get(k), kind, press, action, owners);
						leave();
					}
				} finally {
					leave();
				}
			}
			if (named == 0) {
				refuse("presses names no press", I18NConstants.IMAGE_PRESSES_NONE);
			}
		} finally {
			leave();
		}
	}

	private void element(Object item, String kind, String press, boolean action, Map<Long, String> owners) {
		if (!(item instanceof Number number) || Math.floor(number.doubleValue()) != number.doubleValue()) {
			refuse("malformed: an element must be an integer", I18NConstants.IMAGE_ELEMENT_NOT_INTEGER);
			return;
		}
		if (!action) {
			return;
		}
		double value = number.doubleValue();
		if (value < 0 || value > MAX_ACTION_ELEMENT) {
			String element = String.format(Locale.ROOT, "%.0f", Double.valueOf(value));
			refuse("element " + element + " is no element of '" + press + "' (a Zigbee action: 0.." + MAX_ACTION_ELEMENT + ")",
				I18NConstants.IMAGE_ELEMENT_RANGE__ELEMENT_NAME_MAX.fill(element, press, Integer.valueOf(MAX_ACTION_ELEMENT)));
			return;
		}
		Long e = Long.valueOf((long) value);
		String owner = owners.putIfAbsent(e, kind);
		if (owner != null) {
			refuse("element " + e + " is a press of " + owner + " already",
				I18NConstants.IMAGE_ELEMENT_TAKEN__ELEMENT_KIND.fill(String.valueOf(e), owner));
		}
	}

	/**
	 * Reads the id member of a location or function: 1..32 characters of A-Z a-z 0-9 _ . -.
	 */
	private String id(Map<?, ?> object, String what) {
		enter(".id");
		try {
			if (!object.containsKey("id")) {
				missing("id");
				return null;
			}
			String id = string(object.get("id"), 1, 32, what);
			if (id != null && !id.matches("[A-Za-z0-9_.\\-]{1,32}")) {
				refuse(what + " '" + id + "' must have 1..32 characters of A-Z a-z 0-9 _ . -",
					I18NConstants.IMAGE_ID_PATTERN__PATH_ID.fill(path(), id));
				return null;
			}
			return id;
		} finally {
			leave();
		}
	}

	// ---- control pages -----------------------------------------------------------------------

	/**
	 * The member <code>ui</code> (schema 1.1, homecontrol #54, spec/runtime-image.md 3.9): an object;
	 * its <code>pages</code>, each with a unique id, an optional label and icon and its optional
	 * sections, each section with an optional label and its optional elements. An element has exactly
	 * one of <code>function</code> (a function of the image), <code>datapoint</code> (a datapoint of the
	 * image, a rule parameter's or a rule's enable switch included) and <code>chart</code>, an optional
	 * label and, on a datapoint element only, <code>readOnly</code>.
	 *
	 * <p>
	 * The limits of section 5 hold for the whole image and are checked first, at <code>ui.pages</code>:
	 * beyond one of them nothing else of the pages is checked, as the controller's loader does
	 * (<code>parse_ui</code> of homecontrol's <code>core/src/image/loader.cpp</code>, whose reasons
	 * the refusals of the pages repeat).
	 * </p>
	 */
	private void ui(Object value) {
		if (!object(value, "'ui'")) {
			return;
		}
		Map<?, ?> ui = (Map<?, ?>) value;
		if (!ui.containsKey("pages")) {
			return;
		}
		enter(".pages");
		try {
			Object pages = ui.get("pages");
			if (!array(pages, "'pages'")) {
				return;
			}
			List<?> list = (List<?>) pages;
			int sections = 0;
			int elements = 0;
			int series = 0;
			int references = 0;
			for (Object page : list) {
				for (Object section : members(page, "sections")) {
					sections++;
					for (Object element : members(section, "elements")) {
						elements++;
						Object chart = element instanceof Map<?, ?> map ? map.get("chart") : null;
						series += members(chart, "series").size();
						references += members(chart, "references").size();
					}
				}
			}
			boolean within = limit(list.size(), MAX_PAGES, "pages", I18NConstants.IMAGE_TOO_MANY_PAGES__COUNT_LIMIT);
			within &= limit(sections, MAX_PAGE_SECTIONS, "sections", I18NConstants.IMAGE_TOO_MANY_SECTIONS__COUNT_LIMIT);
			within &= limit(elements, MAX_PAGE_ELEMENTS, "elements", I18NConstants.IMAGE_TOO_MANY_ELEMENTS__COUNT_LIMIT);
			within &= limit(series, MAX_CHART_SERIES, "chart series", I18NConstants.IMAGE_TOO_MANY_CHART_SERIES__COUNT_LIMIT);
			within &= limit(references, MAX_CHART_REFERENCES, "reference lines",
				I18NConstants.IMAGE_TOO_MANY_REFERENCE_LINES__COUNT_LIMIT);
			if (!within) {
				return;
			}
			Set<String> ids = new HashSet<>();
			for (int i = 0; i < list.size(); i++) {
				enter("[" + i + "]");
				page(list.get(i), ids);
				leave();
			}
		} finally {
			leave();
		}
	}

	/**
	 * Refuses a count beyond its limit; whether it is within.
	 *
	 * @param what
	 *        What is counted, in English, for the reason.
	 * @param message
	 *        The sentence for the user, of the count and the limit.
	 */
	private boolean limit(int count, int max, String what, ResKey2 message) {
		if (count > max) {
			refuse(count + " " + what + " exceed the limit of " + max,
				message.fill(Integer.valueOf(count), Integer.valueOf(max)));
			return false;
		}
		return true;
	}

	/** The list member <code>name</code> of an object, empty where either is of another type. */
	private static List<?> members(Object object, String name) {
		return object instanceof Map<?, ?> map && map.get(name) instanceof List<?> list ? list : List.of();
	}

	private void page(Object item, Set<String> ids) {
		if (!object(item, "a page")) {
			return;
		}
		Map<?, ?> page = (Map<?, ?>) item;
		if (!page.containsKey("id")) {
			missing("id");
		} else {
			String id = id(page, "page id");
			if (id != null && !ids.add(id)) {
				enter(".id");
				refuse("duplicate page id '" + id + "'", I18NConstants.IMAGE_DUPLICATE_PAGE_ID__ID.fill(id));
				leave();
			}
		}
		if (page.containsKey("label")) {
			enter(".label");
			label(page.get("label"));
			leave();
		}
		if (page.containsKey("icon")) {
			enter(".icon");
			String icon = string(page.get("icon"), 1, MAX_ICON, "icon");
			if (icon != null && !icon.matches("[A-Za-z0-9_.\\-]{1," + MAX_ICON + "}")) {
				refuse("icon '" + icon + "' must have 1.." + MAX_ICON + " characters of A-Z a-z 0-9 _ . -",
					I18NConstants.IMAGE_ICON_PATTERN__ICON_MAX.fill(icon, Integer.valueOf(MAX_ICON)));
			}
			leave();
		}
		if (!page.containsKey("sections")) {
			return;
		}
		enter(".sections");
		if (array(page.get("sections"), "sections")) {
			List<?> sections = (List<?>) page.get("sections");
			for (int i = 0; i < sections.size(); i++) {
				enter("[" + i + "]");
				section(sections.get(i));
				leave();
			}
		}
		leave();
	}

	private void section(Object item) {
		if (!object(item, "a section")) {
			return;
		}
		Map<?, ?> section = (Map<?, ?>) item;
		if (section.containsKey("label")) {
			enter(".label");
			label(section.get("label"));
			leave();
		}
		if (!section.containsKey("elements")) {
			return;
		}
		enter(".elements");
		if (array(section.get("elements"), "elements")) {
			List<?> elements = (List<?>) section.get("elements");
			for (int i = 0; i < elements.size(); i++) {
				enter("[" + i + "]");
				element(elements.get(i));
				leave();
			}
		}
		leave();
	}

	private void element(Object item) {
		if (!object(item, "an element")) {
			return;
		}
		Map<?, ?> element = (Map<?, ?>) item;
		boolean datapoint = element.containsKey("datapoint");
		int kinds = (element.containsKey("function") ? 1 : 0) + (datapoint ? 1 : 0)
			+ (element.containsKey("chart") ? 1 : 0);
		if (kinds != 1) {
			refuse("an element names exactly one of function, datapoint or chart; this one names "
				+ (kinds == 0 ? "none" : "several"),
				kinds == 0 ? I18NConstants.IMAGE_ELEMENT_KIND_NONE : I18NConstants.IMAGE_ELEMENT_KIND_SEVERAL);
			return;
		}
		if (element.containsKey("label")) {
			enter(".label");
			label(element.get("label"));
			leave();
		}
		if (element.containsKey("readOnly") && element.get("readOnly") instanceof Boolean && !datapoint) {
			enter(".readOnly");
			refuse("readOnly applies to a datapoint element only", I18NConstants.IMAGE_READ_ONLY_DATAPOINT);
			leave();
		} else {
			flag(element, "readOnly");
		}
		if (element.containsKey("function")) {
			enter(".function");
			String function = string(element.get("function"), 1, 32, "function");
			if (function != null && !_functionIds.contains(function)) {
				refuse("unknown function '" + function + "'", I18NConstants.IMAGE_UNKNOWN_FUNCTION__ID.fill(function));
			}
			leave();
		}
		if (datapoint) {
			enter(".datapoint");
			knownDatapoint(element.get("datapoint"));
			leave();
		}
		if (element.containsKey("chart")) {
			enter(".chart");
			chart(element.get("chart"));
			leave();
		}
	}

	/** The index of the named datapoint, -1 (after a refusal) for an unknown one. */
	private int knownDatapoint(Object value) {
		String name = string(value, 1, MAX_NAME, "datapoint");
		if (name == null) {
			return -1;
		}
		int index = datapointIndex(name);
		if (index < 0) {
			refuse("unknown datapoint '" + name + "'", I18NConstants.IMAGE_UNKNOWN_DATAPOINT__NAME.fill(DatapointId.of(name)));
		}
		return index;
	}

	/**
	 * A chart (the amendment "charts" of homecontrol #54): label, width (1..4 or full), height (2 or
	 * 4), range (default 24h) and presets (1h, 24h, 7d, 30d, 1y, each once, the range among them), the
	 * axes left and right with a min and max each (a number or null; other members are ignored), the
	 * series (required, may be empty: a datapoint with history, label, axis, consolidation) and the
	 * reference lines (a value or a numeric datapoint, axis, label).
	 */
	private void chart(Object value) {
		if (!object(value, "a chart")) {
			return;
		}
		Map<?, ?> chart = (Map<?, ?>) value;
		if (chart.containsKey("label")) {
			enter(".label");
			label(chart.get("label"));
			leave();
		}
		if (chart.containsKey("width")) {
			enter(".width");
			Object width = chart.get("width");
			if (!"full".equals(width) && !(width instanceof Number number && Math.floor(number.doubleValue()) == number.doubleValue()
				&& number.doubleValue() >= 1 && number.doubleValue() <= 4)) {
				refuse("width must be 1, 2, 3, 4 (columns) or \"full\"", I18NConstants.IMAGE_CHART_WIDTH);
			}
			leave();
		}
		if (chart.containsKey("height")) {
			enter(".height");
			Object height = chart.get("height");
			if (!(height instanceof Number number && (number.doubleValue() == 2 || number.doubleValue() == 4))) {
				refuse("height must be 2 or 4 (tile rows)", I18NConstants.IMAGE_CHART_HEIGHT);
			}
			leave();
		}
		String range = DEFAULT_CHART_RANGE;
		if (chart.containsKey("range")) {
			enter(".range");
			range = choice(chart.get("range"), "range", CHART_RANGES);
			leave();
		}
		if (chart.containsKey("presets")) {
			enter(".presets");
			if (array(chart.get("presets"), "presets")) {
				List<?> presets = (List<?>) chart.get("presets");
				Set<String> seen = new HashSet<>();
				for (int i = 0; i < presets.size(); i++) {
					enter("[" + i + "]");
					String preset = choice(presets.get(i), "range", CHART_RANGES);
					if (preset != null && !seen.add(preset)) {
						refuse("the preset " + preset + " is listed twice", I18NConstants.IMAGE_PRESET_TWICE__PRESET.fill(preset));
					}
					leave();
				}
				if (presets.isEmpty()) {
					refuse("presets must name at least one range (or be left out: the range alone)", I18NConstants.IMAGE_PRESETS_EMPTY);
				} else if (range != null && !seen.contains(range)) {
					refuse("the presets do not offer the chart's range " + range, I18NConstants.IMAGE_PRESETS_LACK_RANGE__RANGE.fill(range));
				}
			}
			leave();
		}
		if (chart.containsKey("axes")) {
			enter(".axes");
			axes(chart.get("axes"));
			leave();
		}
		if (!chart.containsKey("series")) {
			refuse("a chart needs its series (an empty array draws an empty chart)", I18NConstants.IMAGE_CHART_NEEDS_SERIES);
		} else {
			enter(".series");
			if (array(chart.get("series"), "series")) {
				List<?> series = (List<?>) chart.get("series");
				for (int i = 0; i < series.size(); i++) {
					enter("[" + i + "]");
					series(series.get(i));
					leave();
				}
			}
			leave();
		}
		if (chart.containsKey("references")) {
			enter(".references");
			if (array(chart.get("references"), "references")) {
				List<?> references = (List<?>) chart.get("references");
				for (int i = 0; i < references.size(); i++) {
					enter("[" + i + "]");
					reference(references.get(i));
					leave();
				}
			}
			leave();
		}
	}

	/**
	 * A member naming one of the given choices, as the controller's <code>read_choice</code> reads it:
	 * the choice, <code>null</code> after a refusal.
	 */
	private String choice(Object value, String what, List<String> choices) {
		String name = string(value, 1, 15, what);
		if (name != null && !choices.contains(name)) {
			refuse("unknown " + what + " \"" + name + "\" (" + String.join(", ", choices) + ")",
				I18NConstants.IMAGE_UNKNOWN_CHOICE__MEMBER_NAME_CHOICES.fill(what, name, String.join(", ", choices)));
			return null;
		}
		return name;
	}

	/** The axes of a chart: the members <code>left</code> and <code>right</code>, others are ignored. */
	private void axes(Object value) {
		if (!object(value, "axes")) {
			return;
		}
		Map<?, ?> axes = (Map<?, ?>) value;
		for (String axis : CHART_AXES) {
			if (!axes.containsKey(axis)) {
				continue;
			}
			enter("." + axis);
			if (object(axes.get(axis), "an axis")) {
				Map<?, ?> bounds = (Map<?, ?>) axes.get(axis);
				Double min = bound(bounds, "min");
				Double max = bound(bounds, "max");
				if (min != null && max != null && min.doubleValue() >= max.doubleValue()) {
					refuse("min must be less than max", I18NConstants.IMAGE_AXIS_MIN_MAX);
				}
			}
			leave();
		}
	}

	private Double bound(Map<?, ?> bounds, String member) {
		Object value = bounds.get(member);
		if (value == null) {
			return null;
		}
		enter("." + member);
		boolean ok = number(value, -MAX_CHART_VALUE, MAX_CHART_VALUE, member);
		leave();
		return ok ? Double.valueOf(((Number) value).doubleValue()) : null;
	}

	private void series(Object item) {
		if (!object(item, "a series")) {
			return;
		}
		Map<?, ?> series = (Map<?, ?>) item;
		int index = -1;
		if (!series.containsKey("datapoint")) {
			refuse("a series needs its datapoint", I18NConstants.IMAGE_SERIES_NEEDS_DATAPOINT);
		} else {
			enter(".datapoint");
			index = knownDatapoint(series.get("datapoint"));
			if (index >= 0 && !_withHistory.contains(Integer.valueOf(index))) {
				refuse("datapoint '" + _ids.get(index) + "' keeps no history: a series needs one",
					I18NConstants.IMAGE_SERIES_NO_HISTORY__NAME.fill(DatapointId.of(_ids.get(index))));
				index = -1;
			}
			leave();
		}
		if (series.containsKey("label")) {
			enter(".label");
			label(series.get("label"));
			leave();
		}
		axis(series);
		if (series.containsKey("consolidation")) {
			enter(".consolidation");
			String consolidation = choice(series.get("consolidation"), "consolidation", CHART_CONSOLIDATIONS);
			if ("consumption".equals(consolidation) && index >= 0 && ValueTypes.BOOLEAN.equals(_types.get(index))) {
				refuse("consumption needs a numeric datapoint; '" + _ids.get(index) + "' is boolean",
					I18NConstants.IMAGE_CONSUMPTION_NUMERIC__NAME.fill(DatapointId.of(_ids.get(index))));
			}
			leave();
		}
	}

	private void reference(Object item) {
		if (!object(item, "a reference line")) {
			return;
		}
		Map<?, ?> reference = (Map<?, ?>) item;
		boolean value = reference.containsKey("value");
		if (value == reference.containsKey("datapoint")) {
			refuse("a reference line has exactly one of value (a constant) or datapoint (its current value)",
				I18NConstants.IMAGE_REFERENCE_ONE);
		} else if (value) {
			enter(".value");
			number(reference.get("value"), -MAX_CHART_VALUE, MAX_CHART_VALUE, "value");
			leave();
		} else {
			enter(".datapoint");
			int index = knownDatapoint(reference.get("datapoint"));
			String type = index < 0 ? null : _types.get(index);
			if (index >= 0 && !NUMERIC_TYPES.contains(type)) {
				refuse("a reference line needs a numeric datapoint; '" + _ids.get(index) + "' is "
					+ (type == null ? "none" : type),
					type == null ? I18NConstants.IMAGE_REFERENCE_NUMERIC_NONE__NAME.fill(DatapointId.of(_ids.get(index)))
						: I18NConstants.IMAGE_REFERENCE_NUMERIC__NAME_TYPE.fill(DatapointId.of(_ids.get(index)), type));
			}
			leave();
		}
		if (reference.containsKey("label")) {
			enter(".label");
			label(reference.get("label"));
			leave();
		}
		axis(reference);
	}

	private void axis(Map<?, ?> object) {
		if (object.containsKey("axis")) {
			enter(".axis");
			choice(object.get("axis"), "axis", CHART_AXES);
			leave();
		}
	}

	// ---- history budget ----------------------------------------------------------------------

	/**
	 * The three biggest of the given histories for a refusal of a budget:
	 * <code>; the biggest: 'id' (n bytes), ...</code>.
	 */
	private static String biggest(Map<String, Long> consumers) {
		List<Map.Entry<String, Long>> entries = biggestEntries(consumers);
		List<String> names = new ArrayList<>();
		for (Map.Entry<String, Long> entry : entries) {
			names.add("'" + entry.getKey() + "' (" + entry.getValue() + " bytes)");
		}
		return names.isEmpty() ? "" : "; the biggest: " + String.join(", ", names);
	}

	/**
	 * The three biggest of the given histories for the message of a refusal of a budget, without
	 * words: <code>id: n, ...</code>, the datapoints as {@link DatapointId} (named where the message is
	 * shown, haumacher/ehome #118); <code>-</code> for none.
	 */
	private static ResKey biggestList(Map<String, Long> consumers) {
		ResKey result = null;
		for (Map.Entry<String, Long> entry : biggestEntries(consumers)) {
			ResKey item = I18NConstants.IMAGE_BIGGEST__DATAPOINT_BYTES.fill(DatapointId.of(entry.getKey()),
				String.valueOf(entry.getValue()));
			result = result == null ? item : I18NConstants.IMAGE_BIGGEST_MORE__LIST_ITEM.fill(result, item);
		}
		return result == null ? ResKey.text("-") : result;
	}

	private static List<Map.Entry<String, Long>> biggestEntries(Map<String, Long> consumers) {
		List<Map.Entry<String, Long>> entries = new ArrayList<>(consumers.entrySet());
		entries.removeIf(entry -> entry.getValue().longValue() == 0);
		entries.sort(Map.Entry.<String, Long> comparingByValue().reversed());
		return entries.subList(0, Math.min(3, entries.size()));
	}

	/**
	 * The member <code>history</code> of the image (3.7) against what the datapoints' histories need, as
	 * the device's loader checks it (homecontrol <code>parse_history</code>): the RAM of the rings and
	 * the archives' open slots against <code>budget</code> (default: what they need) and the device's
	 * cap (with or without <code>budget</code>), the flash of the archives against
	 * <code>archiveBudget</code> (default: what they need) and the device's cap.
	 */
	private void history(Map<?, ?> root) {
		long ram = _historyRam;
		long flash = _historyFlash;
		if (!root.containsKey("history")) {
			if (ram > HISTORY_CAP) {
				_report.add(Kind.INVALID, "history", "the histories need " + ram
					+ " bytes of RAM, the device allows at most " + HISTORY_CAP + biggest(_ramConsumers),
					I18NConstants.IMAGE_HISTORY_RAM__NEED_CAP_BIGGEST.fill(Long.valueOf(ram), Integer.valueOf(HISTORY_CAP),
						biggestList(_ramConsumers)));
			}
			if (flash > ARCHIVE_CAP) {
				_report.add(Kind.INVALID, "history", "the archives need " + flash
					+ " bytes of flash, the device allows at most " + ARCHIVE_CAP + biggest(_flashConsumers),
					I18NConstants.IMAGE_HISTORY_FLASH__NEED_CAP_BIGGEST.fill(Long.valueOf(flash), Integer.valueOf(ARCHIVE_CAP),
						biggestList(_flashConsumers)));
			}
			return;
		}
		enter("history");
		try {
			if (!object(root.get("history"), "'history'")) {
				return;
			}
			Map<?, ?> history = (Map<?, ?>) root.get("history");
			long budget = ram;
			if (history.containsKey("budget")) {
				enter(".budget");
				try {
					Long bytes = integer(history.get("budget"), 0, 16777216.0, "budget (bytes)", Unit.BYTES);
					if (bytes == null) {
						return;
					}
					if (bytes.longValue() > HISTORY_CAP) {
						refuse("the image asks for " + bytes + " bytes of history RAM, the device allows at most "
							+ HISTORY_CAP, I18NConstants.IMAGE_HISTORY_BUDGET_CAP__BYTES_CAP.fill(bytes, Integer.valueOf(HISTORY_CAP)));
						return;
					}
					budget = bytes.longValue();
				} finally {
					leave();
				}
			} else if (ram > HISTORY_CAP) {
				refuse("the histories need " + ram + " bytes of RAM, the device allows at most " + HISTORY_CAP
					+ biggest(_ramConsumers), I18NConstants.IMAGE_HISTORY_RAM__NEED_CAP_BIGGEST.fill(Long.valueOf(ram),
						Integer.valueOf(HISTORY_CAP), biggestList(_ramConsumers)));
			}
			long archiveBudget = flash;
			if (history.containsKey("archiveBudget")) {
				enter(".archiveBudget");
				try {
					Long bytes = integer(history.get("archiveBudget"), 0, 16777216.0, "archiveBudget (bytes)", Unit.BYTES);
					if (bytes == null) {
						return;
					}
					if (bytes.longValue() > ARCHIVE_CAP) {
						refuse("the image asks for " + bytes + " bytes of archive flash, the device allows at most "
							+ ARCHIVE_CAP, I18NConstants.IMAGE_ARCHIVE_BUDGET_CAP__BYTES_CAP.fill(bytes, Integer.valueOf(ARCHIVE_CAP)));
						return;
					}
					archiveBudget = bytes.longValue();
				} finally {
					leave();
				}
			} else if (flash > ARCHIVE_CAP) {
				refuse("the archives need " + flash + " bytes of flash, the device allows at most " + ARCHIVE_CAP
					+ biggest(_flashConsumers), I18NConstants.IMAGE_HISTORY_FLASH__NEED_CAP_BIGGEST.fill(Long.valueOf(flash),
						Integer.valueOf(ARCHIVE_CAP), biggestList(_flashConsumers)));
			}
			if (ram > budget) {
				refuse("the rings and archives need " + ram + " bytes of RAM, the image's budget is " + budget
					+ biggest(_ramConsumers), I18NConstants.IMAGE_HISTORY_OVER_BUDGET__NEED_BUDGET_BIGGEST.fill(
						Long.valueOf(ram), Long.valueOf(budget), biggestList(_ramConsumers)));
			}
			if (flash > archiveBudget) {
				refuse("the archives need " + flash + " bytes of flash, the image's archive budget is " + archiveBudget
					+ biggest(_flashConsumers), I18NConstants.IMAGE_ARCHIVE_OVER_BUDGET__NEED_BUDGET_BIGGEST.fill(
						Long.valueOf(flash), Long.valueOf(archiveBudget), biggestList(_flashConsumers)));
			}
		} finally {
			leave();
		}
	}

	// ---- values ------------------------------------------------------------------------------

	private boolean object(Object value, String what) {
		if (!(value instanceof Map<?, ?>)) {
			refuse(what + " must be an object", I18NConstants.IMAGE_NOT_AN_OBJECT__PATH.fill(subject()));
			return false;
		}
		return true;
	}

	private boolean array(Object value, String what) {
		if (!(value instanceof List<?>)) {
			refuse(what + " must be an array", I18NConstants.IMAGE_NOT_AN_ARRAY__PATH.fill(subject()));
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
			refuse(what + " must be a string", I18NConstants.IMAGE_NOT_A_STRING__PATH.fill(subject()));
			return null;
		}
		int length = text.getBytes(StandardCharsets.UTF_8).length;
		if (length < min || length > max) {
			refuse(what + " must have " + min + ".." + max + " bytes",
				I18NConstants.IMAGE_STRING_LENGTH__PATH_MIN_MAX.fill(subject(), Integer.valueOf(min), Integer.valueOf(max)));
			return null;
		}
		for (int n = 0; n < text.length(); n++) {
			char c = text.charAt(n);
			if (c < 0x20 || c == 0x7F) {
				refuse(what + " contains a control character", I18NConstants.IMAGE_CONTROL_CHARACTER__PATH.fill(subject()));
				return null;
			}
		}
		return text;
	}

	private Long integer(Object value, double min, double max, String what) {
		return integer(value, min, max, what, Unit.NONE);
	}

	/**
	 * An integer of min..max, <code>null</code> (after a refusal) otherwise.
	 *
	 * @param what
	 *        What the value is, in English, for the reason.
	 * @param unit
	 *        The unit of the value, for the message.
	 */
	private Long integer(Object value, double min, double max, String what, Unit unit) {
		if (!(value instanceof Number number) || Math.floor(number.doubleValue()) != number.doubleValue()) {
			refuse(what + " must be an integer", I18NConstants.IMAGE_NOT_AN_INTEGER__PATH.fill(subject()));
			return null;
		}
		double x = number.doubleValue();
		if (x < min || x > max) {
			String low = String.format(Locale.ROOT, "%.0f", Double.valueOf(min));
			String high = String.format(Locale.ROOT, "%.0f", Double.valueOf(max));
			refuse(what + " must be " + low + ".." + high, unit.range().fill(subject(), low, high));
			return null;
		}
		return Long.valueOf((long) x);
	}

	/**
	 * The unit of an integer member, in the message of a refusal of its range.
	 */
	private enum Unit {
		/** A number without unit. */
		NONE(I18NConstants.IMAGE_RANGE__PATH_MIN_MAX),
		/** Seconds. */
		SECONDS(I18NConstants.IMAGE_RANGE_SECONDS__PATH_MIN_MAX),
		/** Bytes. */
		BYTES(I18NConstants.IMAGE_RANGE_BYTES__PATH_MIN_MAX);

		private final ResKey3 _range;

		Unit(ResKey3 range) {
			_range = range;
		}

		/** The message of a value outside its range. */
		ResKey3 range() {
			return _range;
		}
	}

	private boolean number(Object value, double min, double max, String what) {
		if (!(value instanceof Number number)) {
			refuse(what + " must be a number", I18NConstants.IMAGE_NOT_A_NUMBER__PATH.fill(subject()));
			return false;
		}
		double x = number.doubleValue();
		if (x < min || x > max) {
			refuse(what + " must be " + format(min) + ".." + format(max),
				I18NConstants.IMAGE_RANGE__PATH_MIN_MAX.fill(subject(), format(min), format(max)));
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
			refuse(member + " must be true or false", I18NConstants.IMAGE_NOT_A_FLAG__PATH.fill(subject()));
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
			refuse("the label must be a string or an object of language tags to strings",
				I18NConstants.IMAGE_LABEL_MALFORMED__PATH.fill(subject()));
			return;
		}
		int entries = 0;
		boolean ok = true;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String tag = String.valueOf(entry.getKey());
			if (!tag.matches("[A-Za-z0-9\\-]{2,8}")) {
				refuse("a label's language tag must have 2..8 characters of A-Z a-z 0-9 -",
					I18NConstants.IMAGE_LABEL_LANGUAGE_TAG__PATH_TAG.fill(subject(), tag));
				ok = false;
				continue;
			}
			enter("." + tag);
			ok = string(entry.getValue(), 1, MAX_NAME, "the label") != null && ok;
			leave();
			entries++;
		}
		if (entries == 0 && ok) {
			refuse("the label names no language", I18NConstants.IMAGE_LABEL_NO_LANGUAGE__PATH.fill(subject()));
		}
	}

	// ---- path and report ---------------------------------------------------------------------

	/**
	 * Refuses the value at the current path.
	 *
	 * @param reason
	 *        The English reason, as the controller's loader gives it.
	 * @param message
	 *        The same as a whole sentence for the user.
	 */
	private void refuse(String reason, ResKey message) {
		_report.add(Kind.INVALID, path(), reason, message);
	}

	/**
	 * Refuses a missing member of the object at the current path.
	 */
	private void missing(String member) {
		refuse(member + " is missing", I18NConstants.IMAGE_MISSING__MEMBER.fill(member));
	}

	/**
	 * The current path as the subject of a message: the JSON path, the same in every language;
	 * <code>image</code> for the document.
	 */
	private String subject() {
		return _path.length() == 0 ? "image" : path();
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
