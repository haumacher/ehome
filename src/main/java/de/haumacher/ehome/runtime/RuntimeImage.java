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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.monitor.DatapointCodec;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;

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
 * <li><code>datapoints</code>: one per group address of the project that has a datapoint type (its
 * own, the one of a connected communication object, or a main type its objects' size decides: the
 * rule of {@link GroupAddressTypes#effective(TLObject)}), in
 * the order of the addresses. The id is the address in three levels (<code>1/2/3</code>, stable
 * when the address is renamed), the label its name, the value type and unit follow from the
 * datapoint type. The KNX binding and the flags follow from the communication objects connected to
 * the address, each with its flags in effect (the device's own, else those of the object's placement,
 * else those of the object): an object that receives from the address (write flag) without
 * transmitting on it (transmit flag on its sending address) is an actuator input, which the
 * controller may command: the datapoint is <code>writable</code> and <code>transmit</code>s on
 * <code>send</code>. Otherwise the controller <code>listen</code>s on the address. A datapoint is
 * <code>readOnStart</code> where an object transmits its value on the address and answers a read
 * there (read flag): a status or a measured value.</li>
 * <li><code>locations</code>: the parts of the project's buildings, each with its parent; buildings,
 * floors and rooms by their kind, every other part (corridor, stairway, distribution board, ...) as
 * an area.</li>
 * <li><code>functions</code>, <code>schedules</code>, <code>rules</code>, the time master,
 * <code>settings</code> and <code>history</code> are not authored in E-Home yet and left out (the
 * specification makes every one optional; a missing one means "none").</li>
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

	private static final Pattern INDEXED = Pattern.compile("^(datapoints|locations)\\[(\\d+)\\]");

	private final Map<String, Object> _json = new LinkedHashMap<>();

	private final List<TLObject> _datapointSources = new ArrayList<>();

	private final List<TLObject> _locationSources = new ArrayList<>();

	private final List<String> _notes = new ArrayList<>();

	private String _text;

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
		RuntimeImage result = new RuntimeImage();
		result.image(project, revision, generatedAt);
		result.datapoints(project);
		result.locations(project);
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
	 * The group addresses the datapoints stand for, by the index of the datapoint.
	 */
	public List<TLObject> getDatapointSources() {
		return _datapointSources;
	}

	/**
	 * The object of the project a path of the image leads to: the group address of
	 * <code>datapoints[n]...</code>, the part of a building of <code>locations[n]...</code>;
	 * <code>null</code> for another path.
	 */
	public TLObject elementAt(String path) {
		if (path == null) {
			return null;
		}
		Matcher matcher = INDEXED.matcher(path);
		if (!matcher.find()) {
			return null;
		}
		List<TLObject> sources = matcher.group(1).equals("datapoints") ? _datapointSources : _locationSources;
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
		return ImageValidator.validate(_text);
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
			Map<String, Object> datapoint = datapoint(address);
			if (datapoint != null) {
				datapoints.add(datapoint);
				_datapointSources.add(address);
			}
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

		boolean receiver = false;
		boolean reporter = false;
		for (TLObject connection : GroupAddressConnections.connections(address)) {
			TLObject connector = connection.tContainer();
			if (Boolean.FALSE.equals(connector.tValueByName("active"))) {
				continue;
			}
			Interface placed = GroupAddressConnections.object(connection);
			Port port = placed.getPort();
			if (!enabled("communication", connector, placed, port)) {
				continue;
			}
			boolean send = connection.tValueByName("kind") instanceof TLClassifier kind && "send".equals(kind.getName());
			boolean transmits = send && enabled("transmission", connector, placed, port);
			if (enabled("canWrite", connector, placed, port) && !transmits) {
				receiver = true;
			}
			if (transmits && enabled("canRead", connector, placed, port)) {
				reporter = true;
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		String label = label(address.tValueByName("name"));
		if (label != null) {
			result.put("label", label);
		}
		result.put("type", type);
		String unit = DatapointCodec.unitSymbol(dpt);
		if (unit != null && unit.getBytes(StandardCharsets.UTF_8).length <= 15) {
			result.put("unit", unit);
		}
		if (receiver) {
			result.put("writable", Boolean.TRUE);
			result.put("transmit", Boolean.TRUE);
		}
		if (reporter) {
			result.put("readOnStart", Boolean.TRUE);
		}
		Map<String, Object> knx = new LinkedHashMap<>();
		knx.put("dpt", String.format(Locale.ROOT, "%d.%03d", Integer.valueOf(dpt.main()), Integer.valueOf(sub)));
		if (receiver) {
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

	private static boolean enabled(String flag, TLObject connector, Interface placed, Port port) {
		for (TLObject holder : new TLObject[] { connector, placed, port }) {
			if (holder != null && holder.tValueByName(flag) instanceof TLClassifier classifier) {
				return "Enabled".equals(classifier.getName());
			}
		}
		return false;
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
		List<TLObject> sorted = new ArrayList<>();
		for (Object part : list) {
			if (part instanceof TLObject obj) {
				sorted.add(obj);
			}
		}
		sorted.sort(Comparator.comparing((TLObject p) -> String.valueOf(p.tValueByName("name")))
			.thenComparing(p -> String.valueOf(p.tValueByName("extId"))));
		for (TLObject part : sorted) {
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
		return TLModelUtil.isGeneralization((TLStructuredType) TLModelUtil.findType(typeName), obj.tType());
	}

}
