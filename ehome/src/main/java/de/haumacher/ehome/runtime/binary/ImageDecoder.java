/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime.binary;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import de.haumacher.ehome.blocks.BlockRegistry;
import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.rules.Node;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.binary.Layout.Field;
import de.haumacher.ehome.runtime.binary.Layout.Section;

/**
 * Reads a binary runtime image (homecontrol <code>spec/runtime-image.md</code> section 9) back into
 * its source form, the JSON view E-Home shows and compares (haumacher/ehome #176: "Abbild ansehen" and
 * the comparison of the image a controller runs).
 *
 * <p>
 * Compiling the view again gives the same bytes (signed with the same key: the same image). The view
 * is the source in its normal form, which differs from a source written by hand where the format
 * keeps no choice:
 * </p>
 * <ul>
 * <li>the implied datapoints (time master, rule parameters and states, schedule times, block mirrors)
 * are not listed: the source implies them;</li>
 * <li>defaults are written out where the binary image writes them out: the time master's interval and
 * flags, a schedule bound's <code>min</code> and <code>max</code>, a block input's aggregation, an
 * external source's interval, MQTT's flags and history interval, a chart's width, height, range,
 * presets, axes, a series' axis and consolidation, a reference line's axis;</li>
 * <li>the minimum role of a page is written where it is <code>anonymous</code> (a page of the section
 * <code>ui.anonymous</code>), the default <code>operator</code> left out;</li>
 * <li>values the source may write in several ways are written in one: group addresses as
 * <code>main/middle/sub</code>, a DPT with three digits of sub (<code>9.001</code>), an IEEE address as
 * 16 lower-case hex digits, a listen address equal to the send address dropped, an input bound to one
 * datapoint as its name, a block parameter of a choice by the choice's id, the members of
 * <code>adjustable</code> and of <code>bounds</code> in the order of {@link Schedule.Member}, rule
 * expressions in their normal form (<code>spec/rules.md</code> 4.8), a parameter's <code>step</code>
 * of its constant's type;</li>
 * <li>what the binary image does not keep is gone: <code>description</code> and <code>comment</code>,
 * roles a function's kind does not have, unknown members, an empty <code>settings</code>, an empty
 * <code>ui</code>; the history budgets only where they differ from what the histories need.</li>
 * </ul>
 */
public final class ImageDecoder {

	/**
	 * The header of a binary image (9.2).
	 *
	 * @param major
	 *        The format's major version.
	 * @param minor
	 *        Its minor version.
	 * @param length
	 *        The image's size in bytes, the signature included.
	 * @param crc
	 *        The CRC-32 the header names.
	 * @param signed
	 *        Whether the image ends with a signature.
	 */
	public record Header(int major, int minor, int length, int crc, boolean signed) {

		/** The format version, <code>major.minor</code>. */
		public String format() {
			return major + "." + minor;
		}

		/** The CRC as the controller writes it: 8 lower-case hex digits. */
		public String crcText() {
			return String.format("%08x", Integer.valueOf(crc));
		}
	}

	/**
	 * Thrown when bytes are no binary image this E-Home reads.
	 */
	public static class DecodeException extends Exception {

		/**
		 * Creates a {@link DecodeException}.
		 */
		public DecodeException(String message) {
			super(message);
		}
	}

	private final byte[] _bytes;

	private final ByteBuffer _in;

	private final Map<Integer, List<Map<String, Object>>> _tables = new TreeMap<>();

	private byte[] _strings = new byte[0];

	private byte[] _ui;

	private byte[] _uiAnonymous;

	private final BlockRegistry _registry;

	private ImageDecoder(byte[] bytes, BlockRegistry registry) {
		_bytes = bytes;
		_in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		_registry = registry;
	}

	/**
	 * The header of the image.
	 *
	 * @throws DecodeException
	 *         If the bytes are no binary image (another magic, shorter than its header).
	 */
	public static Header header(byte[] bytes) throws DecodeException {
		if (bytes.length < Layout.HEADER_SIZE) {
			throw new DecodeException("not a runtime image: " + bytes.length + " bytes, the header alone has 32");
		}
		if (!Arrays.equals(Arrays.copyOf(bytes, 4), Layout.MAGIC)) {
			throw new DecodeException("not a runtime image: the magic is " + hex(Arrays.copyOf(bytes, 4))
				+ ", not 48 43 49 4D (\"HCIM\")" + (bytes[0] == '{' ? ": a JSON text" : ""));
		}
		ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		return new Header(in.getShort(4) & 0xFFFF, in.getShort(6) & 0xFFFF, in.getInt(8), in.getInt(Layout.CRC_OFFSET),
			(in.getInt(16) & Layout.FLAG_SIGNED) != 0);
	}

	/**
	 * The source form of the image, for the built-in block types.
	 */
	public static Map<String, Object> decode(byte[] bytes) throws DecodeException {
		return decode(bytes, BlockInstances.registry());
	}

	/**
	 * The source form of the image.
	 *
	 * @param registry
	 *        The block types, whose manifests give the forms of the blocks' parameters.
	 * @throws DecodeException
	 *         If the bytes are no image of format 1 or their structure does not hold.
	 */
	public static Map<String, Object> decode(byte[] bytes, BlockRegistry registry) throws DecodeException {
		ImageDecoder decoder = new ImageDecoder(bytes, registry);
		decoder.read();
		try {
			return decoder.source();
		} catch (RuntimeException ex) {
			throw new DecodeException("the image's records do not fit together: " + ex.getMessage());
		}
	}

	private void read() throws DecodeException {
		Header header = header(_bytes);
		if (header.major() != Layout.MAJOR) {
			throw new DecodeException("format " + header.format() + ": E-Home reads format " + Layout.MAJOR);
		}
		if (header.length() != _bytes.length) {
			throw new DecodeException("the header says " + header.length() + " bytes, the image has " + _bytes.length);
		}
		int signedEnd = header.signed() ? header.length() - Layout.SIGNATURE_SIZE : header.length();
		if (signedEnd < Layout.HEADER_SIZE || ImageWriter.crc(_bytes, signedEnd) != header.crc()) {
			throw new DecodeException("the CRC-32 does not match: the image is damaged");
		}
		int headerSize = _in.getShort(20) & 0xFFFF;
		int count = _in.getShort(22) & 0xFFFF;
		if (headerSize < Layout.HEADER_SIZE || headerSize + count * Layout.ENTRY_SIZE > signedEnd) {
			throw new DecodeException("the section table does not fit the image");
		}
		// The strings first: the records before them reference them.
		for (int pass = 0; pass < 2; pass++) {
			readSections(headerSize, count, signedEnd, pass == 0);
		}
		if (!_tables.containsKey(Integer.valueOf(Layout.IMAGE))) {
			throw new DecodeException("the section image (0x01) is missing");
		}
		resolveLabels();
	}

	private void readSections(int headerSize, int count, int signedEnd, boolean strings) throws DecodeException {
		for (int k = 0; k < count; k++) {
			int at = headerSize + k * Layout.ENTRY_SIZE;
			int id = _in.getShort(at) & 0xFFFF;
			int offset = _in.getInt(at + 4);
			int length = _in.getInt(at + 8);
			int entrySize = _in.getShort(at + 12) & 0xFFFF;
			if (offset < 0 || length < 0 || (long) offset + length > signedEnd) {
				throw new DecodeException("section 0x" + Integer.toHexString(id) + " lies beyond the image");
			}
			Section section = Layout.section(id);
			if (section == null || (id == Layout.STRINGS) != strings) {
				// An unknown section of a newer minor version is skipped (section 11).
				continue;
			}
			if (id == Layout.STRINGS) {
				_strings = Arrays.copyOfRange(_bytes, offset, offset + length);
			} else if (id == Layout.UI) {
				_ui = Arrays.copyOfRange(_bytes, offset, offset + length);
			} else if (id == Layout.UI_ANONYMOUS) {
				_uiAnonymous = Arrays.copyOfRange(_bytes, offset, offset + length);
			} else {
				if (entrySize < section.size() || length % entrySize != 0) {
					throw new DecodeException("section " + section.name() + " has records of " + entrySize + " bytes");
				}
				List<Map<String, Object>> records = new ArrayList<>();
				for (int r = 0; r < length / entrySize; r++) {
					records.add(readRecord(section, offset + r * entrySize));
				}
				_tables.put(Integer.valueOf(id), records);
			}
		}
	}

	private void resolveLabels() throws DecodeException {
		List<Map<String, Object>> labels = table(Layout.LABELS);
		for (Map.Entry<Integer, List<Map<String, Object>>> entry : _tables.entrySet()) {
			if (entry.getKey().intValue() == Layout.LABELS) {
				continue;
			}
			Section section = Layout.section(entry.getKey().intValue());
			for (Map<String, Object> record : entry.getValue()) {
				for (Field field : section.fields()) {
					if (field.kind() == Layout.Kind.LABEL) {
						record.put(field.name(), label(((Number) record.get(field.name())).longValue(), labels));
					}
				}
			}
		}
	}

	private static Object label(long reference, List<Map<String, Object>> labels) throws DecodeException {
		if (reference == 0) {
			return null;
		}
		int first = (int) (reference & 0xFFFFFF);
		int count = (int) (reference >>> 24);
		if (first + count > labels.size()) {
			throw new DecodeException("label entries " + first + ".." + (first + count - 1) + " do not exist");
		}
		if (count == 1 && labels.get(first).get("tag") == null) {
			// A plain label: one entry with the empty tag.
			return labels.get(first).get("text");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = first; i < first + count; i++) {
			Object tag = labels.get(i).get("tag");
			if (tag == null) {
				throw new DecodeException("a language map with an empty tag");
			}
			result.put((String) tag, labels.get(i).get("text"));
		}
		return result;
	}

	private Map<String, Object> readRecord(Section section, int at) throws DecodeException {
		Map<String, Object> result = new HashMap<>();
		for (Field field : section.fields()) {
			int pos = at + field.offset();
			Object value;
			switch (field.kind()) {
				case U8:
					value = Integer.valueOf(_in.get(pos) & 0xFF);
					break;
				case U16:
					value = Integer.valueOf(_in.getShort(pos) & 0xFFFF);
					break;
				case U32:
				case LABEL:
					value = Long.valueOf(_in.getInt(pos) & 0xFFFFFFFFL);
					break;
				case I32:
					value = Integer.valueOf(_in.getInt(pos));
					break;
				case I64:
				case U64:
					value = Long.valueOf(_in.getLong(pos));
					break;
				case F64:
					value = Double.valueOf(_in.getDouble(pos));
					break;
				case STR:
					value = string(_in.getInt(pos) & 0xFFFFFFFFL);
					break;
				case RAW:
					value = Arrays.copyOfRange(_bytes, pos, pos + field.length());
					break;
				default:
					throw new IllegalStateException(field.kind().name());
			}
			result.put(field.name(), value);
		}
		return result;
	}

	/** The string at the offset, <code>null</code> for the empty string. */
	private String string(long offset) throws DecodeException {
		if (offset == 0) {
			return null;
		}
		if (offset >= _strings.length || _strings[(int) offset - 1] != 0) {
			throw new DecodeException(offset + " is no string");
		}
		int end = (int) offset;
		while (end < _strings.length && _strings[end] != 0) {
			end++;
		}
		return new String(_strings, (int) offset, end - (int) offset, StandardCharsets.UTF_8);
	}

	private List<Map<String, Object>> table(int id) {
		List<Map<String, Object>> result = _tables.get(Integer.valueOf(id));
		return result == null ? List.of() : result;
	}

	private Map<String, Object> single(int id) {
		List<Map<String, Object>> records = table(id);
		return records.isEmpty() ? null : records.get(0);
	}

	private static int i(Map<String, Object> record, String field) {
		return ((Number) record.get(field)).intValue();
	}

	private static long l(Map<String, Object> record, String field) {
		return ((Number) record.get(field)).longValue();
	}

	private static void putIf(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	// ---- the source form -----------------------------------------------------------------------

	private List<Map<String, Object>> _dps;

	private Map<String, Object> source() throws DecodeException {
		_dps = table(Layout.DATAPOINTS);
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, Object> image = single(Layout.IMAGE);
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("schemaVersion", image.get("schemaVersion"));
		header.put("projectId", image.get("projectId"));
		header.put("revision", Long.valueOf(l(image, "revision")));
		putIf(header, "generatedAt", image.get("generatedAt"));
		putIf(header, "generator", image.get("generator"));
		putIf(header, "name", image.get("name"));
		result.put("image", header);

		Map<String, Object> settings = single(Layout.SETTINGS);
		if (settings != null) {
			Map<String, Object> s = new LinkedHashMap<>();
			putIf(s, "tz", settings.get("tz"));
			int flags = i(settings, "flags");
			if ((flags & 1) != 0) {
				Map<String, Object> location = new LinkedHashMap<>();
				location.put("lat", settings.get("lat"));
				location.put("lon", settings.get("lon"));
				if ((flags & 2) != 0) {
					location.put("elev", Long.valueOf(i(settings, "elev")));
				}
				s.put("location", location);
			}
			if (settings.get("ntp") != null) {
				s.put("ntp", Map.of("server", settings.get("ntp")));
			}
			result.put("settings", s);
		}

		List<Object> datapoints = datapoints();
		if (!datapoints.isEmpty()) {
			result.put("datapoints", datapoints);
		}
		List<Object> locations = locations();
		if (!locations.isEmpty()) {
			result.put("locations", locations);
		}
		List<Object> functions = functions();
		if (!functions.isEmpty()) {
			result.put("functions", functions);
		}
		Map<String, Object> timeMaster = timeMaster();
		if (timeMaster != null) {
			result.put("knx", Map.of("timeMaster", timeMaster));
		}
		List<Object> schedules = schedules();
		if (!schedules.isEmpty()) {
			result.put("schedules", schedules);
		}
		List<Object> rules = rules();
		if (!rules.isEmpty()) {
			result.put("rules", rules);
		}
		List<Object> blocks = blocks();
		if (!blocks.isEmpty()) {
			result.put("blocks", blocks);
		}
		Map<String, Object> history = history(image);
		if (history != null) {
			result.put("history", history);
		}
		Map<String, Object> external = external();
		if (external != null) {
			result.put("external", external);
		}
		Map<String, Object> mqtt = mqtt();
		if (mqtt != null) {
			result.put("mqtt", mqtt);
		}
		if (_ui != null) {
			result.put("ui", ui());
		}
		return result;
	}

	private String dpName(int index) {
		return (String) _dps.get(index).get("name");
	}

	/** Whether a datapoint is one the source declares, not one it implies. */
	private static boolean declared(Map<String, Object> dp) {
		int owner = i(dp, "owner");
		return owner == ImageCompiler.OWNER_NONE || owner == ImageCompiler.OWNER_KNX || owner == ImageCompiler.OWNER_ZIGBEE
			|| owner == ImageCompiler.OWNER_EXTERNAL || (owner == ImageCompiler.OWNER_SCHEDULER && i(dp, "ownerMember") == 0);
	}

	private List<Object> datapoints() {
		List<Map<String, Object>> knx = table(Layout.KNX_BINDINGS);
		List<Map<String, Object>> addresses = table(Layout.KNX_ADDRESSES);
		List<Map<String, Object>> listen = table(Layout.KNX_LISTEN);
		List<Map<String, Object>> zigbee = table(Layout.ZIGBEE_BINDINGS);
		List<Map<String, Object>> rings = table(Layout.HISTORY);
		List<Map<String, Object>> archives = table(Layout.ARCHIVES);
		List<Object> result = new ArrayList<>();
		for (int index = 0; index < _dps.size(); index++) {
			Map<String, Object> dp = _dps.get(index);
			if (!declared(dp)) {
				continue;
			}
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", dp.get("name"));
			putIf(json, "label", dp.get("label"));
			json.put("type", ImageCompiler.VALUE_TYPES.get(i(dp, "type")));
			putIf(json, "unit", dp.get("unit"));
			int flags = i(dp, "flags");
			flag(json, "readable", flags, ImageCompiler.READABLE);
			flag(json, "writable", flags, ImageCompiler.WRITABLE);
			flag(json, "transmit", flags, ImageCompiler.TRANSMIT);
			flag(json, "readOnStart", flags, ImageCompiler.READ_ON_START);
			flag(json, "persist", flags, ImageCompiler.PERSIST);
			int history = i(dp, "history");
			if (history != Layout.NONE) {
				Map<String, Object> ring = rings.get(history);
				Map<String, Object> h = new LinkedHashMap<>();
				h.put("retention", Long.valueOf(l(ring, "retention")));
				h.put("spacing", Long.valueOf(l(ring, "spacing")));
				int first = i(ring, "firstArchive");
				if (i(ring, "archiveCount") > 0) {
					List<Object> list = new ArrayList<>();
					for (int a = first; a < first + i(ring, "archiveCount"); a++) {
						Map<String, Object> archive = new LinkedHashMap<>();
						archive.put("resolution", Long.valueOf(l(archives.get(a), "resolution")));
						archive.put("retention", Long.valueOf(l(archives.get(a), "retention")));
						list.add(archive);
					}
					h.put("archives", list);
				}
				json.put("history", h);
			}
			int owner = i(dp, "owner");
			if (owner == ImageCompiler.OWNER_KNX) {
				Map<String, Object> binding = knx.get(i(dp, "ownerIndex"));
				Map<String, Object> k = new LinkedHashMap<>();
				k.put("dpt", i(binding, "dptMain") + "." + String.format("%03d", Integer.valueOf(i(binding, "dptSub"))));
				if (i(binding, "send") != Layout.NONE) {
					k.put("send", ImageCompiler.groupAddress(i(addresses.get(i(binding, "send")), "address")));
				}
				if (i(binding, "listenCount") > 0) {
					List<Object> list = new ArrayList<>();
					int first = i(binding, "firstListen");
					for (int a = first; a < first + i(binding, "listenCount"); a++) {
						list.add(ImageCompiler.groupAddress(i(addresses.get(i(listen.get(a), "address")), "address")));
					}
					k.put("listen", list);
				}
				json.put("knx", k);
			} else if (owner == ImageCompiler.OWNER_ZIGBEE) {
				Map<String, Object> binding = zigbee.get(i(dp, "ownerIndex"));
				Map<String, Object> z = new LinkedHashMap<>();
				z.put("ieee", String.format("%016x", Long.valueOf(l(binding, "ieee"))));
				z.put("endpoint", Long.valueOf(i(binding, "endpoint")));
				z.put("cluster", Long.valueOf(i(binding, "cluster")));
				int zflags = i(binding, "flags");
				if ((zflags & 1) != 0) {
					z.put("client", Boolean.TRUE);
				} else {
					z.put("attribute", Long.valueOf(i(binding, "attribute")));
				}
				if (i(binding, "command") != Layout.NONE) {
					z.put("command", Long.valueOf(i(binding, "command")));
				}
				if ((zflags & 14) != 0) {
					Map<String, Object> reporting = new LinkedHashMap<>();
					if ((zflags & 2) != 0) {
						reporting.put("min", Long.valueOf(i(binding, "reportMin")));
					}
					if ((zflags & 4) != 0) {
						reporting.put("max", Long.valueOf(i(binding, "reportMax")));
					}
					if ((zflags & 8) != 0) {
						reporting.put("change", binding.get("reportChange"));
					}
					z.put("reporting", reporting);
				}
				json.put("zigbee", z);
			}
			if ((flags & ImageCompiler.VIRTUAL) != 0) {
				json.put("virtual", new LinkedHashMap<>());
			}
			result.add(json);
		}
		return result;
	}

	private static void flag(Map<String, Object> json, String name, int flags, int bit) {
		if ((flags & bit) != 0) {
			json.put(name, Boolean.TRUE);
		}
	}

	private List<Object> locations() {
		List<Map<String, Object>> locations = table(Layout.LOCATIONS);
		List<Object> result = new ArrayList<>();
		for (Map<String, Object> location : locations) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", location.get("id"));
			json.put("label", location.get("label"));
			if (i(location, "kind") != 0) {
				json.put("kind", ImageCompiler.LOCATION_KINDS.get(i(location, "kind")));
			}
			if (i(location, "parent") != Layout.NONE) {
				json.put("parent", locations.get(i(location, "parent")).get("id"));
			}
			result.add(json);
		}
		return result;
	}

	private List<Object> functions() {
		List<Map<String, Object>> locations = table(Layout.LOCATIONS);
		List<Map<String, Object>> roles = table(Layout.ROLES);
		List<Object> result = new ArrayList<>();
		for (Map<String, Object> function : table(Layout.FUNCTIONS)) {
			Map<String, Object> json = new LinkedHashMap<>();
			String kind = ImageCompiler.FUNCTION_KINDS.get(i(function, "kind"));
			json.put("id", function.get("id"));
			json.put("label", function.get("label"));
			json.put("kind", kind);
			if (i(function, "room") != Layout.NONE) {
				json.put("room", locations.get(i(function, "room")).get("id"));
			}
			putIf(json, "unit", function.get("unit"));
			if ("scene".equals(kind)) {
				json.put("scene", Long.valueOf(i(function, "scene")));
			}
			byte[] presses = (byte[]) function.get("presses");
			Map<String, Object> pressMap = new LinkedHashMap<>();
			for (int k = 0; k < ImageCompiler.PRESS_KINDS.size(); k++) {
				List<Object> elements = new ArrayList<>();
				for (int e = 0; e < 8; e++) {
					if ((presses[k] & (1 << e)) != 0) {
						elements.add(Long.valueOf(e));
					}
				}
				if (!elements.isEmpty()) {
					pressMap.put(ImageCompiler.PRESS_KINDS.get(k), elements);
				}
			}
			if (!pressMap.isEmpty()) {
				json.put("presses", pressMap);
			}
			Map<String, Object> refs = new LinkedHashMap<>();
			int first = i(function, "firstRole");
			for (int r = first; r < first + i(function, "roleCount"); r++) {
				refs.put((String) roles.get(r).get("name"), dpName(i(roles.get(r), "datapoint")));
			}
			json.put("datapoints", refs);
			result.add(json);
		}
		return result;
	}

	private Map<String, Object> timeMaster() {
		Map<String, Object> tm = single(Layout.KNX_TIME_MASTER);
		if (tm == null) {
			return null;
		}
		List<Map<String, Object>> knx = table(Layout.KNX_BINDINGS);
		List<Map<String, Object>> addresses = table(Layout.KNX_ADDRESSES);
		Map<String, Object> json = new LinkedHashMap<>();
		for (String[] member : ImageCompiler.TIME_MASTER) {
			int index = i(tm, member[0]);
			if (index != Layout.NONE) {
				Map<String, Object> binding = knx.get(i(_dps.get(index), "ownerIndex"));
				json.put(member[0], ImageCompiler.groupAddress(i(addresses.get(i(binding, "send")), "address")));
			}
		}
		json.put("interval", Long.valueOf(l(tm, "interval")));
		json.put("atMidnight", Boolean.valueOf((i(tm, "flags") & 1) != 0));
		json.put("onSynchronized", Boolean.valueOf((i(tm, "flags") & 2) != 0));
		return json;
	}

	// ---- schedules ---------------------------------------------------------------------------

	private List<Object> schedules() {
		List<Map<String, Object>> dates = table(Layout.SCHEDULE_DATES);
		List<Map<String, Object>> bounds = table(Layout.SCHEDULE_BOUNDS);
		List<Object> result = new ArrayList<>();
		for (Map<String, Object> entry : table(Layout.SCHEDULES)) {
			Map<String, Object> json = new LinkedHashMap<>();
			String kind = ImageCompiler.SCHEDULE_KINDS.get(i(entry, "kind"));
			json.put("id", entry.get("id"));
			json.put("label", entry.get("label"));
			json.put("kind", kind);
			int flags = i(entry, "flags");
			if ((flags & 1) == 0) {
				json.put("enabled", Boolean.FALSE);
			}
			if ((flags & 2) == 0) {
				json.put("enabledAdjustable", Boolean.FALSE);
			}
			if ((flags & 4) != 0) {
				json.put("missed", "catchUp");
			}
			switch (kind) {
				case "weekly":
					json.put("days", Schedule.Member.DAYS.json(i(entry, "days")));
					json.put("time", Schedule.Member.TIME.json(l(entry, "time")));
					break;
				case "yearly":
					json.put("month", Long.valueOf(i(entry, "month")));
					json.put("day", Long.valueOf(i(entry, "day")));
					json.put("time", Schedule.Member.TIME.json(l(entry, "time")));
					break;
				case "dates": {
					List<Object> list = new ArrayList<>();
					int first = i(entry, "firstDate");
					for (int d = first; d < first + i(entry, "dateCount"); d++) {
						Map<String, Object> date = new LinkedHashMap<>();
						date.put("year", Long.valueOf(i(dates.get(d), "year")));
						date.put("month", Long.valueOf(i(dates.get(d), "month")));
						date.put("day", Long.valueOf(i(dates.get(d), "day")));
						list.add(date);
					}
					json.put("dates", list);
					json.put("time", Schedule.Member.TIME.json(l(entry, "time")));
					break;
				}
				case "astro":
					json.put("sun", ImageCompiler.SUN_EVENTS.get(i(entry, "sun")));
					json.put("offset", Long.valueOf(i(entry, "offset") * 1000L));
					if ((flags & 8) != 0) {
						json.put("notBefore", Schedule.Member.NOT_BEFORE.json(l(entry, "notBefore")));
					}
					if ((flags & 16) != 0) {
						json.put("notAfter", Schedule.Member.NOT_AFTER.json(l(entry, "notAfter")));
					}
					json.put("days", Schedule.Member.DAYS.json(i(entry, "days")));
					break;
				case "interval":
					json.put("every", Long.valueOf(i(entry, "every") * 60000L));
					json.put("from", Schedule.Member.FROM.json(l(entry, "from")));
					json.put("until", Schedule.Member.UNTIL.json(l(entry, "until")));
					json.put("days", Schedule.Member.DAYS.json(i(entry, "days")));
					break;
				default: {
					Map<String, Object> at = new LinkedHashMap<>();
					long time = l(entry, "atTime");
					at.put("year", Long.valueOf(i(entry, "atYear")));
					at.put("month", Long.valueOf(i(entry, "atMonth")));
					at.put("day", Long.valueOf(i(entry, "atDay")));
					at.put("hour", Long.valueOf(time / 3600));
					at.put("minute", Long.valueOf(time % 3600 / 60));
					at.put("second", Long.valueOf(time % 60));
					json.put("at", at);
				}
			}
			if (i(entry, "output") != Layout.NONE) {
				json.put("output", dpName(i(entry, "output")));
			}
			int adjustable = i(entry, "adjustable");
			if (adjustable != 0) {
				List<Object> names = new ArrayList<>();
				for (Schedule.Member member : Schedule.Member.of(adjustable)) {
					names.add(member.externalName());
				}
				json.put("adjustable", names);
			}
			if (i(entry, "boundCount") > 0) {
				Map<String, Object> b = new LinkedHashMap<>();
				int first = i(entry, "firstBound");
				for (int k = first; k < first + i(entry, "boundCount"); k++) {
					Map<String, Object> bound = bounds.get(k);
					Schedule.Member member = Schedule.Member.of(i(bound, "member")).get(0);
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("min", member.json(l(bound, "min")));
					m.put("max", member.json(l(bound, "max")));
					long step = l(bound, "step");
					if (step != 0) {
						m.put("step", Long.valueOf(member.isTime() ? step : step * 1000));
					}
					b.put(member.externalName(), m);
				}
				json.put("bounds", b);
			}
			result.add(json);
		}
		return result;
	}

	// ---- rules -------------------------------------------------------------------------------

	private List<Object> rules() throws DecodeException {
		List<Map<String, Object>> rules = table(Layout.RULES);
		List<Map<String, Object>> parameters = table(Layout.RULE_PARAMETERS);
		List<Map<String, Object>> choices = table(Layout.RULE_CHOICES);
		List<Map<String, Object>> timers = table(Layout.RULE_TIMERS);
		List<Map<String, Object>> states = table(Layout.RULE_STATES);
		List<Map<String, Object>> reactions = table(Layout.RULE_REACTIONS);
		List<Map<String, Object>> triggers = table(Layout.RULE_TRIGGERS);
		List<Object> result = new ArrayList<>();
		for (Map<String, Object> rule : rules) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", rule.get("id"));
			putIf(json, "name", rule.get("name"));
			int firstParameter = i(rule, "firstParameter");
			Map<String, Object> enabled = parameters.get(firstParameter);
			Val enabledDefault = constant(i(enabled, "default"));
			if (!enabledDefault.bool()) {
				json.put("enabled", Boolean.FALSE);
			}
			if (i(enabled, "adjustable") == 0) {
				json.put("enabledAdjustable", Boolean.FALSE);
			}
			Context cx = new Context(rule, parameters, timers, states);
			List<Object> parameterList = new ArrayList<>();
			for (int p = firstParameter + 1; p < firstParameter + i(rule, "parameterCount"); p++) {
				Map<String, Object> parameter = parameters.get(p);
				Map<String, Object> pj = new LinkedHashMap<>();
				pj.put("id", parameter.get("id"));
				putIf(pj, "label", parameter.get("label"));
				pj.put("type", Type.values()[i(parameter, "type")].externalName());
				pj.put("default", Values.toJson(constant(i(parameter, "default"))));
				if (i(parameter, "min") != Layout.NONE) {
					pj.put("min", Values.toJson(constant(i(parameter, "min"))));
				}
				if (i(parameter, "max") != Layout.NONE) {
					pj.put("max", Values.toJson(constant(i(parameter, "max"))));
				}
				if (i(parameter, "step") != Layout.NONE) {
					pj.put("step", Values.toJson(constant(i(parameter, "step"))));
				}
				putIf(pj, "unit", parameter.get("unit"));
				if (i(parameter, "adjustable") == 0) {
					pj.put("adjustable", Boolean.FALSE);
				}
				if (i(parameter, "choiceCount") > 0) {
					List<Object> list = new ArrayList<>();
					int first = i(parameter, "firstChoice");
					for (int c = first; c < first + i(parameter, "choiceCount"); c++) {
						Map<String, Object> choice = new LinkedHashMap<>();
						choice.put("value", Values.toJson(constant(i(choices.get(c), "value"))));
						putIf(choice, "label", choices.get(c).get("label"));
						list.add(choice);
					}
					pj.put("choices", list);
				}
				parameterList.add(pj);
			}
			if (!parameterList.isEmpty()) {
				json.put("parameters", parameterList);
			}
			if (i(rule, "timerCount") > 0) {
				List<Object> list = new ArrayList<>();
				int first = i(rule, "firstTimer");
				for (int t = first; t < first + i(rule, "timerCount"); t++) {
					Map<String, Object> timer = new LinkedHashMap<>();
					timer.put("id", timers.get(t).get("id"));
					if (i(timers.get(t), "duration") != Layout.NONE) {
						timer.put("duration", expression(i(timers.get(t), "duration"), cx));
					}
					list.add(timer);
				}
				json.put("timers", list);
			}
			if (i(rule, "stateCount") > 0) {
				List<Object> list = new ArrayList<>();
				int first = i(rule, "firstState");
				for (int s = first; s < first + i(rule, "stateCount"); s++) {
					Map<String, Object> state = states.get(s);
					Map<String, Object> sj = new LinkedHashMap<>();
					sj.put("id", state.get("id"));
					putIf(sj, "label", state.get("label"));
					if (i(state, "entryCount") > 0) {
						sj.put("entry", actions(i(state, "firstEntry"), i(state, "entryCount"), cx));
					}
					if (i(state, "exitCount") > 0) {
						sj.put("exit", actions(i(state, "firstExit"), i(state, "exitCount"), cx));
					}
					list.add(sj);
				}
				json.put("states", list);
				json.put("initialState", cx.state(i(rule, "initialState")));
			}
			List<Object> reactionList = new ArrayList<>();
			int firstReaction = i(rule, "firstReaction");
			for (int r = firstReaction; r < firstReaction + i(rule, "reactionCount"); r++) {
				Map<String, Object> reaction = reactions.get(r);
				Map<String, Object> rj = new LinkedHashMap<>();
				List<Object> triggerList = new ArrayList<>();
				int first = i(reaction, "firstTrigger");
				for (int t = first; t < first + i(reaction, "triggerCount"); t++) {
					triggerList.add(trigger(triggers.get(t), cx));
				}
				rj.put("triggers", triggerList);
				if (i(reaction, "condition") != Layout.NONE) {
					rj.put("condition", expression(i(reaction, "condition"), cx));
				}
				rj.put("actions", actions(i(reaction, "firstAction"), i(reaction, "actionCount"), cx));
				if (i(reaction, "elseCount") > 0) {
					rj.put("elseActions", actions(i(reaction, "firstElse"), i(reaction, "elseCount"), cx));
				}
				if (i(reaction, "from") != Layout.NONE) {
					List<Object> from = new ArrayList<>();
					for (int s = 0; s < 16; s++) {
						if ((i(reaction, "from") & (1 << s)) != 0) {
							from.add(cx.state(s));
						}
					}
					rj.put("from", from);
				}
				if (i(reaction, "to") != Layout.NONE8) {
					rj.put("to", cx.state(i(reaction, "to")));
				}
				reactionList.add(rj);
			}
			json.put("reactions", reactionList);
			if (i(rule, "timeline") != Layout.NONE) {
				json.put("timeline", timeline(table(Layout.RULE_TIMELINES).get(i(rule, "timeline")), cx));
			}
			result.add(json);
		}
		return result;
	}

	/** The rule an expression or action is read in: its local parameters, timers and states. */
	private static final class Context {
		final Map<String, Object> _rule;

		final List<Map<String, Object>> _parameters;

		final List<Map<String, Object>> _timers;

		final List<Map<String, Object>> _states;

		Context(Map<String, Object> rule, List<Map<String, Object>> parameters, List<Map<String, Object>> timers,
				List<Map<String, Object>> states) {
			_rule = rule;
			_parameters = parameters;
			_timers = timers;
			_states = states;
		}

		Object parameter(int local) {
			return _parameters.get(i(_rule, "firstParameter") + local).get("id");
		}

		Object timer(int local) {
			return _timers.get(i(_rule, "firstTimer") + local).get("id");
		}

		Object state(int local) {
			return _states.get(i(_rule, "firstState") + local).get("id");
		}
	}

	private Map<String, Object> timeline(Map<String, Object> timeline, Context cx) {
		List<Map<String, Object>> plans = table(Layout.RULE_PLANS);
		List<Map<String, Object>> points = table(Layout.RULE_POINTS);
		List<Map<String, Object>> assignments = table(Layout.RULE_ASSIGNMENTS);
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("adjustable", Boolean.valueOf((i(timeline, "flags") & 1) != 0));
		List<Object> planList = new ArrayList<>();
		int firstPlan = i(timeline, "firstPlan");
		for (int p = firstPlan; p < firstPlan + i(timeline, "planCount"); p++) {
			Map<String, Object> plan = plans.get(p);
			Map<String, Object> pj = new LinkedHashMap<>();
			pj.put("id", plan.get("id"));
			putIf(pj, "label", plan.get("label"));
			List<Object> pointList = new ArrayList<>();
			int first = i(plan, "firstPoint");
			for (int k = first; k < first + i(plan, "pointCount"); k++) {
				Map<String, Object> point = points.get(k);
				Map<String, Object> pt = new LinkedHashMap<>();
				int sun = i(point, "sun");
				if (sun == 0) {
					pt.put("time", Schedule.Member.TIME.json(i(point, "seconds")));
				} else {
					pt.put("sun", ImageCompiler.SUN_EVENTS.get(sun - 1));
					if (i(point, "seconds") != 0) {
						pt.put("offset", Long.valueOf(i(point, "seconds") * 1000L));
					}
				}
				if ((i(point, "flags") & 1) != 0) {
					pt.put("notBefore", Schedule.Member.TIME.json(i(point, "notBefore")));
				}
				if ((i(point, "flags") & 2) != 0) {
					pt.put("notAfter", Schedule.Member.TIME.json(i(point, "notAfter")));
				}
				pt.put("state", cx.state(i(point, "state")));
				pointList.add(pt);
			}
			pj.put("points", pointList);
			planList.add(pj);
		}
		json.put("plans", planList);
		List<Object> assignmentList = new ArrayList<>();
		int first = i(timeline, "firstAssignment");
		for (int a = first; a < first + i(timeline, "assignmentCount"); a++) {
			Map<String, Object> assignment = assignments.get(a);
			Map<String, Object> aj = new LinkedHashMap<>();
			if (i(assignment, "days") != 0) {
				aj.put("days", Schedule.Member.DAYS.json(i(assignment, "days")));
			}
			aj.put("plan", plans.get(firstPlan + i(assignment, "plan")).get("id"));
			assignmentList.add(aj);
		}
		json.put("assignments", assignmentList);
		return json;
	}

	private static final List<String> TRIGGER_KINDS =
		List.of("datapoint", "match", "schedule", "timer", "start", "parameter", "silence");

	private static final List<String> ACTION_KINDS =
		List.of("write", "startTimer", "restartTimer", "cancelTimer", "setParameter", "log", "read");

	private static final List<String> LEVELS = List.of("info", "warning", "error");

	private Map<String, Object> trigger(Map<String, Object> trigger, Context cx) throws DecodeException {
		Map<String, Object> json = new LinkedHashMap<>();
		int kind = i(trigger, "kind");
		int flags = i(trigger, "flags");
		json.put("kind", TRIGGER_KINDS.get(kind));
		switch (TRIGGER_KINDS.get(kind)) {
			case "datapoint":
				json.put("datapoint", dpName(i(trigger, "ref")));
				if ((flags & 1) != 0) {
					json.put("on", "update");
				}
				if ((flags & 2) != 0) {
					json.put("origin", "report");
				} else if ((flags & 4) != 0) {
					json.put("origin", "command");
				}
				break;
			case "match":
				json.put("condition", expression(i(trigger, "ref"), cx));
				break;
			case "schedule":
				json.put("schedule", table(Layout.SCHEDULES).get(i(trigger, "ref")).get("id"));
				break;
			case "timer":
				json.put("timer", cx.timer(i(trigger, "ref")));
				break;
			case "start":
				if (flags != 7) {
					List<Object> reasons = new ArrayList<>();
					for (RuleSet.StartReason reason : RuleSet.StartReason.values()) {
						if ((flags & (1 << reason.ordinal())) != 0) {
							reasons.add(reason.externalName());
						}
					}
					json.put("reasons", reasons);
				}
				break;
			case "parameter":
				json.put("parameter", cx.parameter(i(trigger, "ref")));
				break;
			default:
				json.put("datapoint", dpName(i(trigger, "ref")));
				json.put("after", expression(i(trigger, "ref2"), cx));
				break;
		}
		return json;
	}

	private List<Object> actions(int first, int count, Context cx) throws DecodeException {
		List<Map<String, Object>> actions = table(Layout.RULE_ACTIONS);
		List<Map<String, Object>> parts = table(Layout.RULE_LOG_PARTS);
		List<Object> result = new ArrayList<>();
		for (int a = first; a < first + count; a++) {
			Map<String, Object> action = actions.get(a);
			String kind = ACTION_KINDS.get(i(action, "kind"));
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("kind", kind);
			int expr = i(action, "expr");
			switch (kind) {
				case "write":
					json.put("datapoint", dpName(i(action, "target")));
					json.put("value", expression(expr, cx));
					if ((i(action, "flags") & 1) != 0) {
						json.put("onlyOnChange", Boolean.TRUE);
					}
					break;
				case "read":
					json.put("datapoint", dpName(i(action, "target")));
					break;
				case "startTimer":
				case "restartTimer":
				case "cancelTimer":
					json.put("timer", cx.timer(i(action, "target")));
					if (expr != Layout.NONE) {
						json.put("duration", expression(expr, cx));
					}
					break;
				case "setParameter":
					json.put("parameter", cx.parameter(i(action, "target")));
					json.put("value", expression(expr, cx));
					break;
				default: {
					json.put("level", LEVELS.get(i(action, "flags")));
					List<Object> message = new ArrayList<>();
					int firstPart = i(action, "target");
					for (int p = firstPart; p < firstPart + i(action, "count"); p++) {
						Map<String, Object> part = parts.get(p);
						message.add(i(part, "expr") != Layout.NONE ? expression(i(part, "expr"), cx) : part.get("text"));
					}
					json.put("message", message);
				}
			}
			result.add(json);
		}
		return result;
	}

	private Map<String, Object> expression(int index, Context cx) throws DecodeException {
		List<Map<String, Object>> nodes = table(Layout.NODES);
		Map<String, Object> node = nodes.get(index);
		Map<String, Object> json = new LinkedHashMap<>();
		Node.Kind kind = Node.Kind.values()[i(node, "kind")];
		int a = i(node, "a");
		int argc = i(node, "argc");
		Type type = Type.values()[i(node, "type")];
		switch (kind) {
			case LITERAL:
				json.put("kind", "literal");
				json.put("type", type.externalName());
				json.put("value", Values.toJson(ImageCompiler.isLong(type) ? constant(a) : payload(type, (byte[]) node.get("lit"))));
				break;
			case DATAPOINT:
				json.put("kind", "datapoint");
				json.put("name", dpName(a));
				break;
			case PARAMETER:
				json.put("kind", "parameter");
				json.put("id", cx.parameter(a));
				break;
			case TIMER:
				json.put("kind", "timer");
				json.put("id", cx.timer(a));
				break;
			case UNARY:
				json.put("kind", "unary");
				json.put("op", Node.Op.values()[i(node, "op")].text());
				json.put("operand", expression(a, cx));
				break;
			case BINARY:
				json.put("kind", "binary");
				json.put("op", Node.Op.values()[i(node, "op")].text());
				json.put("left", expression(a, cx));
				json.put("right", expression(a + 1, cx));
				break;
			default: {
				json.put("kind", "call");
				json.put("function", Node.Fn.values()[i(node, "op")].externalName());
				List<Object> args = new ArrayList<>();
				for (int c = 0; c < argc; c++) {
					args.add(expression(a + c, cx));
				}
				json.put("args", args);
			}
		}
		return json;
	}

	/** The value of a constant (9.14). */
	private Val constant(int index) throws DecodeException {
		Map<String, Object> constant = table(Layout.CONSTANTS).get(index);
		Type type = Type.values()[i(constant, "type")];
		byte[] value = (byte[]) constant.get("value");
		int length = i(constant, "length");
		switch (type) {
			case STRING:
				return Val.ofString(Arrays.copyOf(value, length));
			case RAW:
				return Val.ofRaw(Arrays.copyOf(value, length));
			case DATE_TIME: {
				ByteBuffer in = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
				return Val.ofDateTime(new Val.DateTime(in.getShort(0) & 0xFFFF, in.get(2), in.get(3), in.get(5), in.get(6),
					in.get(7), in.get(4), in.getShort(8) & 0xFFFF));
			}
			default:
				return payload(type, value);
		}
	}

	/** The value of a short type from its payload (9.14). */
	private static Val payload(Type type, byte[] bytes) throws DecodeException {
		ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		switch (type) {
			case BOOLEAN:
				return Val.ofBool(in.get(0) != 0);
			case INTEGER:
				return Val.ofInt(in.getLong(0));
			case DURATION:
				return Val.ofDuration(in.getLong(0));
			case REAL:
				return Val.ofReal(in.getDouble(0));
			case ENUMERATION:
				return Val.ofEnum(in.getInt(0) & 0xFFFFFFFFL);
			case TIME_OF_DAY:
				return Val.ofTime(new Val.TimeOfDay(in.get(1), in.get(2), in.get(3), in.get(0)));
			case DATE:
				return Val.ofDate(new Val.Date(in.getShort(0) & 0xFFFF, in.get(2), in.get(3)));
			case CONTROLLED:
				return Val.ofControlled(in.get(0) != 0, in.get(1) & 0xFF);
			case COLOR:
				return Val.ofColor(new Val.Color(in.get(0) & 0xFF, in.get(1) & 0xFF, in.get(2) & 0xFF, in.get(3) & 0xFF,
					in.get(4) & 0xFF));
			default:
				throw new DecodeException("no short value of type " + type);
		}
	}

	// ---- blocks ------------------------------------------------------------------------------

	private List<Object> blocks() {
		List<Map<String, Object>> ports = table(Layout.BLOCK_PORTS);
		List<Map<String, Object>> bound = table(Layout.BLOCK_BOUND);
		List<Map<String, Object>> parameters = table(Layout.BLOCK_PARAMETERS);
		List<Object> result = new ArrayList<>();
		for (Map<String, Object> block : table(Layout.BLOCKS)) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", block.get("id"));
			json.put("type", block.get("type"));
			json.put("version", i(block, "major") + "." + i(block, "minor"));
			putIf(json, "label", block.get("label"));
			if ((i(block, "flags") & 1) == 0) {
				json.put("enabled", Boolean.FALSE);
			}
			if ((i(block, "flags") & 2) == 0) {
				json.put("enabledAdjustable", Boolean.FALSE);
			}
			BlockType type = _registry.find((String) block.get("type"), i(block, "major"));
			Manifest m = type == null ? null : type.manifest();
			Map<String, Object> inputs = new LinkedHashMap<>();
			Map<String, Object> aggregation = new LinkedHashMap<>();
			Map<String, Object> outputs = new LinkedHashMap<>();
			int firstPort = i(block, "firstPort");
			for (int p = firstPort; p < firstPort + i(block, "portCount"); p++) {
				Map<String, Object> port = ports.get(p);
				int count = i(port, "boundCount");
				if (count == 0) {
					continue;
				}
				List<Object> names = new ArrayList<>();
				for (int b = i(port, "firstBound"); b < i(port, "firstBound") + count; b++) {
					names.add(dpName(i(bound.get(b), "datapoint")));
				}
				int mirror = i(port, "mirror");
				boolean output = mirror != Layout.NONE && i(_dps.get(mirror), "ownerMember") >> 8 == 2;
				String id = (String) port.get("id");
				if (output) {
					outputs.put(id, names.get(0));
				} else if (count == 1) {
					inputs.put(id, names.get(0));
				} else {
					inputs.put(id, names);
					aggregation.put(id, Manifest.Aggregation.values()[i(port, "aggregation")].externalName());
				}
			}
			if (!inputs.isEmpty()) {
				json.put("inputs", inputs);
			}
			if (!aggregation.isEmpty()) {
				json.put("aggregation", aggregation);
			}
			if (!outputs.isEmpty()) {
				json.put("outputs", outputs);
			}
			Map<String, Object> values = new LinkedHashMap<>();
			int firstParameter = i(block, "firstParameter");
			for (int p = firstParameter; p < firstParameter + i(block, "parameterCount"); p++) {
				Map<String, Object> parameter = parameters.get(p);
				if ((i(parameter, "flags") & 1) == 0) {
					continue;
				}
				String id = (String) parameter.get("id");
				double value = ((Number) parameter.get("value")).doubleValue();
				int position = p - firstParameter;
				Param decl = m != null && position < m.params().size() ? m.params().get(position) : null;
				values.put(id, paramJson(decl, value));
			}
			if (!values.isEmpty()) {
				json.put("parameters", values);
			}
			result.add(json);
		}
		return result;
	}

	private static Object paramJson(Param decl, double value) {
		if (decl != null) {
			switch (decl.type()) {
				case BOOLEAN:
					return Boolean.valueOf(value != 0);
				case TIME_OF_DAY:
					return Schedule.Member.TIME.json((long) value);
				default:
					Choice choice = decl.choice(value);
					if (choice != null) {
						return choice.id();
					}
					break;
			}
		}
		return Values.integral(value) && Math.abs(value) < 1e15 ? (Object) Long.valueOf((long) value) : Double.valueOf(value);
	}

	// ---- history, external, MQTT, ui ---------------------------------------------------------

	private Map<String, Object> history(Map<String, Object> image) {
		long ram = 0;
		long flash = 0;
		List<Map<String, Object>> archives = table(Layout.ARCHIVES);
		for (Map<String, Object> ring : table(Layout.HISTORY)) {
			ram += 8 * ImageValidator.samples(l(ring, "retention"), l(ring, "spacing"));
			int first = i(ring, "firstArchive");
			for (int a = first; a < first + i(ring, "archiveCount"); a++) {
				ram += ImageValidator.archiveRamBytes(l(archives.get(a), "resolution"));
				flash += ImageValidator.archiveFlashBytes(l(archives.get(a), "retention"), l(archives.get(a), "resolution"));
			}
		}
		Map<String, Object> json = new LinkedHashMap<>();
		if (l(image, "historyBudget") != ram) {
			json.put("budget", Long.valueOf(l(image, "historyBudget")));
		}
		if (l(image, "archiveBudget") != flash) {
			json.put("archiveBudget", Long.valueOf(l(image, "archiveBudget")));
		}
		return json.isEmpty() ? null : json;
	}

	private Map<String, Object> external() {
		List<Map<String, Object>> sources = table(Layout.EXTERNAL_SOURCES);
		if (sources.isEmpty()) {
			return null;
		}
		List<Map<String, Object>> bindings = table(Layout.EXTERNAL_BINDINGS);
		List<Object> list = new ArrayList<>();
		for (Map<String, Object> source : sources) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", source.get("id"));
			putIf(json, "url", source.get("url"));
			putIf(json, "preset", source.get("preset"));
			json.put("interval", Long.valueOf(l(source, "interval")));
			List<Object> bindingList = new ArrayList<>();
			int first = i(source, "firstBinding");
			for (int b = first; b < first + i(source, "bindingCount"); b++) {
				Map<String, Object> binding = bindings.get(b);
				Map<String, Object> bj = new LinkedHashMap<>();
				bj.put("datapoint", dpName(i(binding, "datapoint")));
				putIf(bj, "path", binding.get("path"));
				putIf(bj, "variable", binding.get("variable"));
				putIf(bj, "unit", binding.get("unit"));
				double scale = ((Number) binding.get("scale")).doubleValue();
				double offset = ((Number) binding.get("offset")).doubleValue();
				if (scale != 1) {
					bj.put("scale", Double.valueOf(scale));
				}
				if (offset != 0) {
					bj.put("offset", Double.valueOf(offset));
				}
				bindingList.add(bj);
			}
			json.put("bindings", bindingList);
			list.add(json);
		}
		return Map.of("sources", list);
	}

	private Map<String, Object> mqtt() {
		Map<String, Object> mqtt = single(Layout.MQTT);
		if (mqtt == null) {
			return null;
		}
		int flags = i(mqtt, "flags");
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("enabled", Boolean.valueOf((flags & 1) != 0));
		json.put("broker", mqtt.get("broker"));
		putIf(json, "username", mqtt.get("username"));
		putIf(json, "baseTopic", mqtt.get("baseTopic"));
		json.put("retain", Boolean.valueOf((flags & 2) != 0));
		Map<String, Object> discovery = new LinkedHashMap<>();
		discovery.put("enabled", Boolean.valueOf((flags & 4) != 0));
		putIf(discovery, "prefix", mqtt.get("discoveryPrefix"));
		json.put("discovery", discovery);
		Map<String, Object> history = new LinkedHashMap<>();
		history.put("enabled", Boolean.valueOf((flags & 8) != 0));
		history.put("interval", Long.valueOf(i(mqtt, "historyInterval")));
		json.put("history", history);
		return json;
	}

	private static Map<?, ?> pagesBody(byte[] bytes) throws DecodeException {
		try {
			return (Map<?, ?>) Json.parse(new String(bytes, StandardCharsets.UTF_8), ImageValidator.MAX_NESTING);
		} catch (Json.JsonException | ClassCastException ex) {
			throw new DecodeException("the control pages are no JSON object");
		}
	}

	private Map<String, Object> ui() throws DecodeException {
		Map<?, ?> body = pagesBody(_ui);
		// The pages of minimum role anonymous are those of the second body (format 1.1); their tables follow from them.
		Set<Object> anonymous = new HashSet<>();
		if (_uiAnonymous != null) {
			for (Map<?, ?> page : ImageCompiler.maps(pagesBody(_uiAnonymous).get("pages"))) {
				anonymous.add(page.get("id"));
			}
		}
		List<Map<?, ?>> pages = ImageCompiler.maps(body.get("pages"));
		List<Map<?, ?>> sections = ImageCompiler.maps(body.get("sections"));
		List<Map<?, ?>> elements = ImageCompiler.maps(body.get("elements"));
		List<Map<?, ?>> series = ImageCompiler.maps(body.get("series"));
		List<Map<?, ?>> references = ImageCompiler.maps(body.get("references"));
		List<Map<String, Object>> pageList = new ArrayList<>();
		List<List<Object>> sectionsOfPage = new ArrayList<>();
		for (Map<?, ?> page : pages) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("id", page.get("id"));
			putIf(json, "label", page.get("label"));
			putIf(json, "icon", page.get("icon"));
			if (anonymous.contains(page.get("id"))) {
				json.put("minimumRole", "anonymous");
			}
			List<Object> list = new ArrayList<>();
			json.put("sections", list);
			sectionsOfPage.add(list);
			pageList.add(json);
		}
		List<List<Object>> elementsOfSection = new ArrayList<>();
		for (Map<?, ?> section : sections) {
			Map<String, Object> json = new LinkedHashMap<>();
			putIf(json, "label", section.get("label"));
			List<Object> list = new ArrayList<>();
			json.put("elements", list);
			elementsOfSection.add(list);
			sectionsOfPage.get(((Number) section.get("page")).intValue()).add(json);
		}
		List<Map<String, Object>> charts = new ArrayList<>();
		for (Map<?, ?> element : elements) {
			Map<String, Object> json = new LinkedHashMap<>();
			Map<String, Object> chart = null;
			if (element.get("function") != null) {
				json.put("function", element.get("function"));
			} else if (element.get("datapoint") != null) {
				json.put("datapoint", dpName(((Number) element.get("datapoint")).intValue()));
				if (Boolean.TRUE.equals(element.get("readOnly"))) {
					json.put("readOnly", Boolean.TRUE);
				}
			} else if (element.get("chart") instanceof Map<?, ?> c) {
				chart = new LinkedHashMap<>();
				for (Map.Entry<?, ?> entry : c.entrySet()) {
					chart.put(entry.getKey().toString(), entry.getValue());
				}
				chart.put("series", new ArrayList<>());
				json.put("chart", chart);
			}
			charts.add(chart);
			putIf(json, "label", element.get("label"));
			elementsOfSection.get(((Number) element.get("section")).intValue()).add(json);
		}
		for (Map<?, ?> item : series) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("datapoint", dpName(((Number) item.get("datapoint")).intValue()));
			putIf(json, "label", item.get("label"));
			json.put("axis", item.get("axis"));
			json.put("consolidation", item.get("consolidation"));
			chartList(charts, item, "series").add(json);
		}
		for (Map<?, ?> item : references) {
			Map<String, Object> json = new LinkedHashMap<>();
			if (item.get("datapoint") != null) {
				json.put("datapoint", dpName(((Number) item.get("datapoint")).intValue()));
			} else {
				json.put("value", item.get("value"));
			}
			putIf(json, "label", item.get("label"));
			json.put("axis", item.get("axis"));
			chartList(charts, item, "references").add(json);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("pages", pageList);
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> chartList(List<Map<String, Object>> charts, Map<?, ?> item, String member) {
		Map<String, Object> chart = charts.get(((Number) item.get("element")).intValue());
		return (List<Object>) chart.computeIfAbsent(member, k -> new ArrayList<>());
	}

	private static String hex(byte[] bytes) {
		StringBuilder out = new StringBuilder();
		for (byte b : bytes) {
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(String.format("%02X", Integer.valueOf(b & 0xFF)));
		}
		return out.toString();
	}

}
