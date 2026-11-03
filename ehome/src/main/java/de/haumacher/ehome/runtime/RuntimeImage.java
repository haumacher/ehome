/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.rules.RuleLoader;

/**
 * The runtime image of a project: the JSON document a HomeControl controller runs
 * (homecontrol <code>spec/runtime-image.md</code>, schema 1.0), derived from the project.
 *
 * <p>
 * The mapping:
 * </p>
 * <ul>
 * <li><code>image</code>: the project's external ID as <code>projectId</code>, its name, the
 * revision of the deployment, the time of the export and E-Home as generator.</li>
 * <li><code>settings</code>: the project's time zone, location and NTP server, each where set.</li>
 * <li><code>datapoints</code>: one per group address of the project that has a datapoint type (its
 * own, the one of a connected communication object, or a main type its objects' size decides: the
 * rule of {@link GroupAddressTypes#effective(TLObject)}), in
 * the order of the addresses, except the addresses of the time master (whose datapoints the
 * controller adds itself). The id is the address in three levels (<code>1/2/3</code>, stable
 * when the address is renamed), the label its name (a text of the project in the one language it is
 * written in, so a plain string, which the specification reads as the same label in every language),
 * the value type and unit follow from the
 * datapoint type. The flags are the address's runtime settings ({@link DatapointSettings}): writable
 * and read on start derived from the connected objects unless the user set them, readable, persist,
 * history and unit as set. A writable datapoint <code>transmit</code>s; a writable or readable one
 * sends and answers on its address (<code>send</code>), any other <code>listen</code>s on it. After
 * them the project's application datapoints (haumacher/ehome #59, {@link ApplicationDatapoints}),
 * owned by no technology: their own id, label, value type and flags, an empty <code>virtual</code>
 * member in place of a binding; one bound to a function of a Zigbee device (haumacher/ehome #40) with
 * its <code>zigbee</code> binding instead.</li>
 * <li><code>locations</code>: the parts of the project's buildings, each with its parent, in the order
 * of the building structure, depth-first (a building, then each of its floors followed by its rooms,
 * haumacher/ehome #71); buildings, floors and rooms by their kind, every other part (corridor,
 * stairway, distribution board, ...) as an area.</li>
 * <li><code>functions</code>: the project's functions (<code>ehome.runtime:Function</code>), each
 * with its identifier (derived from its name where it has none), its label (its name as a map of the
 * languages it has to its texts, haumacher/ehome #51: <code>{"de": "Taste 1", "en": "Button 1"}</code>;
 * a name of a program written only in German has only the German text), its room and the datapoints of the
 * roles of its kind; a role bound to an address of the time master names the controller's datapoint
 * of it (<code>knx.time</code>, ...), one bound to an application datapoint its id (haumacher/ehome
 * #61). The unit of a function without one is the one of its main
 * datapoint.</li>
 * <li><code>knx.timeMaster</code>: the project's time, date and date-time addresses with the
 * interval and the flags (unset flags are written as the specification's default, true).</li>
 * <li><code>history</code>: the project's history budget, where set.</li>
 * <li><code>schedules</code> and <code>rules</code>: the project's schedule entries and rules
 * (haumacher/ehome #57, {@link RuleExport}), each member only where the project has some.</li>
 * </ul>
 *
 * <p>
 * A group address without a datapoint type is left out and named in the {@link #getNotes() notes}.
 * Which object of the project a path of the image stands for is answered by
 * {@link #elementAt(String)}, so a refusal of the image is shown against the project.
 * </p>
 */
public final class RuntimeImage {

	/** The version of the schema of the images E-Home writes. */
	public static final String SCHEMA_VERSION = "1.0";

	/** The content type of an image. */
	public static final String CONTENT_TYPE = "application/json";

	private static final Pattern INDEXED = Pattern.compile("^(datapoints|locations|functions)\\[(\\d+)\\]");

	private final Map<String, Object> _json = new LinkedHashMap<>();

	private final List<TLObject> _datapointSources = new ArrayList<>();

	private final List<TLObject> _locationSources = new ArrayList<>();

	private final List<TLObject> _functionSources = new ArrayList<>();

	/** The ids of the locations by the building part they stand for. */
	private final Map<TLObject, String> _locationIds = new HashMap<>();

	/** The ids of the datapoints by the group address they stand for. */
	private final Map<TLObject, String> _datapointIds = new HashMap<>();

	/** The time master's addresses with the id of the datapoint the controller adds for each. */
	private final Map<TLObject, String> _timeMaster = new LinkedHashMap<>();

	private TLObject _project;

	private final List<String> _notes = new ArrayList<>();

	private String _text;

	/** The rules and schedules, with the objects their paths stem from. */
	private final RuleExport _automation = new RuleExport();

	private RuntimeImage() {
		// See build().
	}

	/**
	 * Derives the image of the given project.
	 *
	 * @param project
	 *        The project (<code>ehome.project:Project</code>).
	 * @param revision
	 *        The revision the image carries: the number of the deployment.
	 * @param generatedAt
	 *        The time of the export.
	 */
	public static RuntimeImage build(TLObject project, long revision, Instant generatedAt) {
		return build(project, revision, generatedAt, true);
	}

	/**
	 * Derives the image of the given project, with or without its rules and schedules.
	 *
	 * @param automation
	 *        Whether the image carries the rules and schedules; without, it serves to look up the
	 *        datapoints the rules see ({@link RuleExport}).
	 */
	public static RuntimeImage build(TLObject project, long revision, Instant generatedAt, boolean automation) {
		RuntimeImage result = new RuntimeImage();
		result._project = project;
		result.image(project, revision, generatedAt);
		result.settings(project);
		result.timeMasterAddresses(project);
		result.datapoints(project);
		result.locations(project);
		result.functions(project);
		result.knx(project);
		if (automation) {
			result.automation(project);
		}
		result.history(project);
		result._text = Json.write(result._json);
		return result;
	}

	/**
	 * The image as JSON objects (maps and lists).
	 */
	public Map<String, Object> getJson() {
		return _json;
	}

	/**
	 * The image as JSON text.
	 */
	public String getText() {
		return _text;
	}

	/**
	 * The image as it is sent: UTF-8.
	 */
	public byte[] getBytes() {
		return _text.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * What the export left out of the project, for the user.
	 */
	public List<String> getNotes() {
		return _notes;
	}

	/**
	 * The group addresses and application datapoints the datapoints stand for, by the index of the
	 * datapoint.
	 */
	public List<TLObject> getDatapointSources() {
		return _datapointSources;
	}

	/**
	 * The functions of the project by the index of the function in the image.
	 */
	public List<TLObject> getFunctionSources() {
		return _functionSources;
	}

	/**
	 * The object of the project a path of the image leads to: the group address or application
	 * datapoint of <code>datapoints[n]...</code>, the part of a building of <code>locations[n]...</code>, the
	 * function of <code>functions[n]...</code>, the project for its settings, time master and history
	 * budget; <code>null</code> for another path.
	 */
	public TLObject elementAt(String path) {
		if (path == null) {
			return null;
		}
		if (path.startsWith("settings") || path.startsWith("knx") || path.startsWith("history")) {
			return _project;
		}
		if (path.startsWith("rules") || path.startsWith("schedules")) {
			RuleExport.Source source = _automation.sourceAt(path);
			return source == null ? null : source.object();
		}
		Matcher matcher = INDEXED.matcher(path);
		if (!matcher.find()) {
			return null;
		}
		List<TLObject> sources = switch (matcher.group(1)) {
			case "datapoints" -> _datapointSources;
			case "locations" -> _locationSources;
			default -> _functionSources;
		};
		int index;
		try {
			index = Integer.parseInt(matcher.group(2));
		} catch (NumberFormatException ex) {
			return null;
		}
		return index < sources.size() ? sources.get(index) : null;
	}

	/**
	 * Checks the image as the controller would (see {@link ImageValidator}).
	 */
	public ImageReport validate() {
		List<RuleExport.Problem> problems = _automation.getProblems();
		ImageReport report = ImageValidator.validate(_text);
		if (problems.isEmpty()) {
			return report;
		}
		// The texts that do not parse first; the validator's refusals of the same values (a text where
		// an expression is expected) would only repeat them.
		ImageReport result = new ImageReport();
		for (RuleExport.Problem problem : problems) {
			result.add(ImageReport.Kind.INVALID, problem.path(), problem.reason());
		}
		for (ImageReport.Refusal refusal : report.getRefusals()) {
			boolean repeated = false;
			for (RuleExport.Problem problem : problems) {
				repeated |= refusal.path().startsWith(problem.path());
			}
			if (!repeated) {
				result.add(report.getKind() == ImageReport.Kind.NONE ? ImageReport.Kind.INVALID : report.getKind(),
					refusal.path(), refusal.reason());
			}
		}
		for (String note : report.getNotes()) {
			result.note(note);
		}
		return result;
	}

	/**
	 * The texts of the rules and schedules that do not parse (they are refused by
	 * {@link #validate()}).
	 */
	public List<RuleExport.Problem> getProblems() {
		return _automation.getProblems();
	}

	/**
	 * The file name of the image of the given project: its name with the extension
	 * <code>.json</code>, without characters a file system may refuse.
	 */
	public static String fileName(TLObject project) {
		Object name = project.tValueByName("name");
		String base = name == null || name.toString().isBlank() ? String.valueOf(project.tValueByName("extId"))
			: name.toString();
		return base.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + ".runtime.json";
	}

	// ---- image -------------------------------------------------------------------------------

	private void image(TLObject project, long revision, Instant generatedAt) {
		Map<String, Object> image = new LinkedHashMap<>();
		image.put("schemaVersion", SCHEMA_VERSION);
		image.put("projectId", text(project.tValueByName("extId"), "project"));
		image.put("revision", Long.valueOf(revision));
		image.put("generatedAt", generatedAt.truncatedTo(ChronoUnit.SECONDS).toString());
		image.put("generator", generator());
		String name = label(project.tValueByName("name"));
		if (name != null) {
			image.put("name", name);
		}
		_json.put("image", image);
	}

	/**
	 * The exporting tool and its version.
	 */
	static String generator() {
		String version = RuntimeImage.class.getPackage().getImplementationVersion();
		return version == null ? "E-Home" : "E-Home " + version;
	}

	// ---- datapoints --------------------------------------------------------------------------

	private void datapoints(TLObject project) {
		List<TLObject> addresses = new ArrayList<>();
		collectAddresses(project.tValueByName("groupAddresses"), addresses);
		addresses.sort(Comparator.comparingInt(RuntimeImage::addressValue)
			.thenComparing(a -> String.valueOf(a.tValueByName("name"))));
		List<Object> datapoints = new ArrayList<>();
		for (TLObject address : addresses) {
			if (_timeMaster.containsKey(address)) {
				continue;
			}
			Map<String, Object> datapoint = datapoint(address);
			if (datapoint != null) {
				datapoints.add(datapoint);
				_datapointSources.add(address);
				_datapointIds.put(address, (String) datapoint.get("id"));
			}
		}
		for (TLObject application : ApplicationDatapoints.of(project)) {
			Map<String, Object> datapoint = ApplicationDatapoints.json(application);
			datapoints.add(datapoint);
			_datapointSources.add(application);
			_datapointIds.put(application, (String) datapoint.get("id"));
		}
		_json.put("datapoints", datapoints);
	}

	private static void collectAddresses(Object definitions, List<TLObject> result) {
		if (!(definitions instanceof Collection<?> list)) {
			return;
		}
		for (Object element : list) {
			if (!(element instanceof TLObject definition)) {
				continue;
			}
			if (isA(definition, "ehome.logical:GroupAddress")) {
				result.add(definition);
			} else {
				collectAddresses(definition.tValueByName("parts"), result);
			}
		}
	}

	private static int addressValue(TLObject address) {
		return address.tValueByName("address") instanceof GA ga ? ga.getValue() : Integer.MAX_VALUE;
	}

	private Map<String, Object> datapoint(TLObject address) {
		if (!(address.tValueByName("address") instanceof GA ga)) {
			_notes.add(message(I18NConstants.NOTE_NO_ADDRESS__NAME.fill(String.valueOf(address.tValueByName("name")))));
			return null;
		}
		String id = threeLevel(ga.getValue());
		Dpt dpt = GroupAddressTypes.effective(address);
		if (dpt == null) {
			_notes.add(message(I18NConstants.NOTE_NO_DATAPOINT_TYPE__ADDRESS.fill(ga.toString() + " "
				+ String.valueOf(address.tValueByName("name")))));
			return null;
		}
		int sub = dpt.isMainType() ? 0 : dpt.sub();
		String type = ValueTypes.valueType(dpt.main(), sub);
		if (type == null || ValueTypes.unsupported(dpt.main(), sub) != null) {
			_notes.add(message(I18NConstants.NOTE_UNSUPPORTED_DATAPOINT_TYPE__ADDRESS_TYPE.fill(
				ga.toString() + " " + String.valueOf(address.tValueByName("name")), dpt.code())));
			return null;
		}

		boolean writable = DatapointSettings.writable(address);
		boolean readable = DatapointSettings.readable(address);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		String label = label(address.tValueByName("name"));
		if (label != null) {
			result.put("label", label);
		}
		result.put("type", type);
		String unit = DatapointSettings.unit(address);
		if (unit != null) {
			result.put("unit", unit);
		}
		if (readable) {
			result.put("readable", Boolean.TRUE);
		}
		if (writable) {
			result.put("writable", Boolean.TRUE);
			result.put("transmit", Boolean.TRUE);
		}
		if (DatapointSettings.readOnStart(address)) {
			result.put("readOnStart", Boolean.TRUE);
		}
		if (DatapointSettings.persist(address)) {
			result.put("persist", Boolean.TRUE);
		}
		Integer retention = DatapointSettings.historyRetention(address);
		Integer spacing = DatapointSettings.historySpacing(address);
		if (retention != null || spacing != null) {
			Map<String, Object> history = new LinkedHashMap<>();
			if (retention != null) {
				history.put("retention", Long.valueOf(retention.longValue()));
			}
			if (spacing != null) {
				history.put("spacing", Long.valueOf(spacing.longValue()));
			}
			result.put("history", history);
		}
		Map<String, Object> knx = new LinkedHashMap<>();
		knx.put("dpt", String.format(Locale.ROOT, "%d.%03d", Integer.valueOf(dpt.main()), Integer.valueOf(sub)));
		if (writable || readable) {
			knx.put("send", id);
		} else {
			knx.put("listen", List.of(id));
		}
		result.put("knx", knx);
		return result;
	}

	/**
	 * A group address in three levels (main/middle/sub), the notation of the runtime image.
	 */
	public static String threeLevel(int address) {
		return ((address >>> 11) & 0x1F) + "/" + ((address >>> 8) & 0x07) + "/" + (address & 0xFF);
	}

	// ---- locations ---------------------------------------------------------------------------

	private void locations(TLObject project) {
		List<Object> locations = new ArrayList<>();
		addLocations(project.tValueByName("buildings"), null, locations);
		_json.put("locations", locations);
	}

	private void addLocations(Object parts, String parent, List<Object> result) {
		if (!(parts instanceof Collection<?> list)) {
			return;
		}
		for (Object element : list) {
			if (!(element instanceof TLObject part)) {
				continue;
			}
			String id = locationId(part, result.size());
			Map<String, Object> location = new LinkedHashMap<>();
			location.put("id", id);
			String label = label(part.tValueByName("name"));
			location.put("label", label != null ? label : id);
			location.put("kind", locationKind(part));
			if (parent != null) {
				location.put("parent", parent);
			}
			result.add(location);
			_locationSources.add(part);
			_locationIds.put(part, id);
			addLocations(part.tValueByName("parts"), id, result);
		}
	}

	/**
	 * The id of a location: the external ID of the part of the building (ETS writes e.g.
	 * <code>P-0563-0_BP-1</code>), reduced to the characters an id may have.
	 */
	private static String locationId(TLObject part, int index) {
		Object extId = part.tValueByName("extId");
		String id = extId == null ? "" : extId.toString().replaceAll("[^A-Za-z0-9_.\\-]", "_");
		if (id.isEmpty()) {
			id = "location-" + (index + 1);
		}
		if (id.length() > 32) {
			id = id.substring(id.length() - 32);
		}
		return id;
	}

	private static String locationKind(TLObject part) {
		switch (part.tType().getName()) {
			case "Building":
				return "building";
			case "Floor":
				return "floor";
			case "Room":
				return "room";
			default:
				return "area";
		}
	}

	// ---- settings ----------------------------------------------------------------------------

	private void settings(TLObject project) {
		Map<String, Object> settings = new LinkedHashMap<>();
		String tz = text(project.tValueByName("timeZone"));
		if (tz != null) {
			settings.put("tz", tz);
		}
		Object lat = project.tValueByName("latitude");
		Object lon = project.tValueByName("longitude");
		if (lat instanceof Number latitude && lon instanceof Number longitude) {
			Map<String, Object> location = new LinkedHashMap<>();
			location.put("lat", Double.valueOf(latitude.doubleValue()));
			location.put("lon", Double.valueOf(longitude.doubleValue()));
			if (project.tValueByName("elevation") instanceof Number elevation) {
				location.put("elev", Long.valueOf(elevation.longValue()));
			}
			settings.put("location", location);
		} else if (lat != null || lon != null) {
			_notes.add(message(I18NConstants.NOTE_LOCATION_INCOMPLETE));
		}
		String ntp = text(project.tValueByName("ntpServer"));
		if (ntp != null) {
			settings.put("ntp", Map.of("server", ntp));
		}
		if (!settings.isEmpty()) {
			_json.put("settings", settings);
		}
	}

	// ---- time master -------------------------------------------------------------------------

	private static final String[][] TIME_MASTER = {
		{ "timeMasterTime", "time", "knx.time", "10" },
		{ "timeMasterDate", "date", "knx.date", "11" },
		{ "timeMasterDateTime", "dateTime", "knx.datetime", "19" },
	};

	private void timeMasterAddresses(TLObject project) {
		for (String[] member : TIME_MASTER) {
			if (project.tValueByName(member[0]) instanceof TLObject address) {
				_timeMaster.putIfAbsent(address, member[2]);
			}
		}
	}

	private void knx(TLObject project) {
		Map<String, Object> timeMaster = new LinkedHashMap<>();
		for (String[] member : TIME_MASTER) {
			if (!(project.tValueByName(member[0]) instanceof TLObject address)) {
				continue;
			}
			if (!(address.tValueByName("address") instanceof GA ga)) {
				_notes.add(message(I18NConstants.NOTE_NO_ADDRESS__NAME.fill(String.valueOf(address.tValueByName("name")))));
				continue;
			}
			Dpt dpt = GroupAddressTypes.effective(address);
			if (dpt != null && dpt.main() != Integer.parseInt(member[3])) {
				_notes.add(message(I18NConstants.NOTE_TIME_MASTER_TYPE__ADDRESS_TYPE_EXPECTED.fill(
					ga.toString() + " " + address.tValueByName("name"), dpt.code(), member[3] + ".001")));
			}
			timeMaster.put(member[1], threeLevel(ga.getValue()));
		}
		if (timeMaster.isEmpty()) {
			return;
		}
		Object interval = project.tValueByName("timeMasterInterval");
		timeMaster.put("interval", Long.valueOf(interval instanceof Number number ? number.longValue() : 600));
		timeMaster.put("atMidnight", Boolean.valueOf(!Boolean.FALSE.equals(project.tValueByName("timeMasterAtMidnight"))));
		timeMaster.put("onSynchronized",
			Boolean.valueOf(!Boolean.FALSE.equals(project.tValueByName("timeMasterOnSynchronized"))));
		_json.put("knx", Map.of("timeMaster", timeMaster));
	}

	// ---- functions ---------------------------------------------------------------------------

	private void functions(TLObject project) {
		List<TLObject> functions = FunctionProposal.functions(project);
		if (functions.isEmpty()) {
			return;
		}
		Set<String> ids = new HashSet<>();
		for (TLObject function : functions) {
			Object identifier = function.tValueByName("identifier");
			if (identifier != null && !identifier.toString().isBlank()) {
				ids.add(identifier.toString());
			}
		}
		List<Object> result = new ArrayList<>();
		for (TLObject function : functions) {
			String kind = FunctionProposal.kind(function);
			Map<String, Object> entry = new LinkedHashMap<>();
			Object identifier = function.tValueByName("identifier");
			entry.put("id", identifier != null && !identifier.toString().isBlank() ? identifier.toString()
				: FunctionProposal.uniqueIdentifier(String.valueOf(text(function.tValueByName("name"))), ids));
			Object label = labels(function.tValueByName("name"));
			entry.put("label", label != null ? label : entry.get("id"));
			if (kind != null) {
				entry.put("kind", kind);
			}
			if (function.tValueByName("room") instanceof TLObject room && _locationIds.containsKey(room)) {
				entry.put("room", _locationIds.get(room));
			}
			Map<String, Object> roles = new LinkedHashMap<>();
			for (String role : FunctionKinds.roles(kind)) {
				if (function.tValueByName(role) instanceof TLObject bound) {
					String datapoint = datapointId(bound);
					if (datapoint != null) {
						roles.put(role, datapoint);
					}
				}
			}
			String unit = text(function.tValueByName("unit"));
			if (unit == null && roles.get(FunctionKinds.mainRole(kind)) != null) {
				unit = DatapointSettings.unit((TLObject) function.tValueByName(FunctionKinds.mainRole(kind)));
			}
			if (unit != null) {
				entry.put("unit", unit);
			}
			if ("scene".equals(kind) && function.tValueByName("scene") instanceof Number scene) {
				entry.put("scene", Long.valueOf(scene.longValue()));
			}
			entry.put("datapoints", roles);
			result.add(entry);
			_functionSources.add(function);
		}
		_json.put("functions", result);
	}

	/**
	 * The id of the datapoint a role of a function refers to in the image (haumacher/ehome #61): for
	 * an application datapoint its id; for a group address the datapoint's, the controller's datapoint
	 * of a time master address, else the address in three levels (which the controller then refuses
	 * as unknown, naming the function).
	 */
	private String datapointId(TLObject datapoint) {
		if (ApplicationDatapoints.isApplicationDatapoint(datapoint)) {
			return ApplicationDatapoints.id(datapoint);
		}
		String id = _timeMaster.get(datapoint);
		if (id == null) {
			id = _datapointIds.get(datapoint);
		}
		if (id == null && datapoint.tValueByName("address") instanceof GA ga) {
			id = threeLevel(ga.getValue());
		}
		return id;
	}

	// ---- schedules and rules -----------------------------------------------------------------

	private void automation(TLObject project) {
		List<Object> schedules = _automation.schedules(project);
		if (schedules != null) {
			_json.put("schedules", schedules);
		}
		List<Object> rules = _automation.rules(project);
		if (rules != null) {
			_json.put("rules", rules);
			_json.put("rules", normalized(rules, schedules));
		}
	}

	/**
	 * The rules with their expressions in the normalized form of spec/rules.md 4.8 (an integer literal
	 * partnering an enumeration as an enumeration literal), as the controller's loader normalizes
	 * them; unchanged when the loader refuses them (the validation reports why).
	 */
	private List<Object> normalized(List<Object> rules, List<Object> schedules) {
		ImageValidator.Directory directory = ImageValidator.directory(Json.write(_json));
		Set<String> ids = new HashSet<>();
		if (schedules != null) {
			for (Object entry : schedules) {
				if (entry instanceof Map<?, ?> map && map.get("id") instanceof String id) {
					ids.add(id);
				}
			}
		}
		try {
			return RuleLoader.normalized(rules, directory, new RuleLoader.ScheduleDirectory() {
				@Override
				public boolean hasSchedule(String id) {
					return ids.contains(id);
				}

				@Override
				public boolean ownsOutput(String datapoint) {
					return directory.ownsOutput(datapoint);
				}

				@Override
				public boolean technologyReads(String datapoint) {
					return directory.technologyReads(datapoint);
				}
			});
		} catch (RuleLoader.LoadException ex) {
			return rules;
		}
	}

	// ---- history -----------------------------------------------------------------------------

	private void history(TLObject project) {
		if (project.tValueByName("historyBudget") instanceof Number budget) {
			_json.put("history", Map.of("budget", Long.valueOf(budget.longValue())));
		}
	}

	// ---- texts -------------------------------------------------------------------------------

	/**
	 * A label of at most 63 bytes without control characters, <code>null</code> for an empty one.
	 */
	private static String label(Object value) {
		if (value == null) {
			return null;
		}
		String text = value.toString().replaceAll("\\p{Cntrl}", " ").trim();
		if (text.isEmpty()) {
			return null;
		}
		return truncate(text, ImageValidator.MAX_NAME);
	}

	/**
	 * A label of the image from a multilingual text: the map of its languages (<code>de</code>,
	 * <code>en</code>) to its texts, each a {@link #label(Object) label}, in the order of the
	 * languages; a plain text as {@link #label(Object) label}; <code>null</code> for none.
	 */
	static Object labels(Object value) {
		if (value instanceof LiteralKey literal) {
			Map<String, String> result = new TreeMap<>();
			for (Map.Entry<Locale, String> entry : literal.getTranslations().entrySet()) {
				String text = label(entry.getValue());
				if (text != null) {
					result.putIfAbsent(entry.getKey().getLanguage(), text);
				}
			}
			return result.isEmpty() ? null : result;
		}
		return label(text(value));
	}

	private static String text(Object value) {
		if (value instanceof ResKey key) {
			return text(com.top_logic.util.Resources.getInstance().getString(key, null));
		}
		return value == null || value.toString().isBlank() ? null : value.toString().trim();
	}

	private static String text(Object value, String fallback) {
		String text = label(value);
		return text == null ? fallback : text;
	}

	/**
	 * The longest beginning of the text with at most the given number of bytes in UTF-8.
	 */
	static String truncate(String text, int maxBytes) {
		if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
			return text;
		}
		int end = 0;
		int bytes = 0;
		while (end < text.length()) {
			int codePoint = text.codePointAt(end);
			int length = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
			if (bytes + length > maxBytes) {
				break;
			}
			bytes += length;
			end += Character.charCount(codePoint);
		}
		return text.substring(0, end);
	}

	private static String message(ResKey key) {
		return com.top_logic.util.Resources.getInstance().getString(key);
	}

	private static boolean isA(TLObject obj, String typeName) {
		return ExtIds.isA(obj, typeName);
	}

}
