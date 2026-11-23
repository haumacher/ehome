/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime.binary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The layout of the binary runtime image (homecontrol <code>spec/runtime-image.md</code> section 9,
 * format 1.1): the header, the section table and the records of every section, field by field, as
 * the compiler ({@link ImageCompiler}) writes them and the decoder ({@link ImageDecoder}) reads them.
 */
public final class Layout {

	/** The magic at the start of an image: "HCIM". */
	public static final byte[] MAGIC = { 0x48, 0x43, 0x49, 0x4D };

	/** The major version of the format this layout describes. */
	public static final int MAJOR = 1;

	/**
	 * The newest minor version of the format this layout describes (1.1: the anonymous pages,
	 * homecontrol #91, haumacher/ehome #184; 1.2: the initial values of datapoints, homecontrol #107,
	 * haumacher/ehome #217; 1.3: the meter flag of a history, homecontrol #115, haumacher/ehome #221). An
	 * image is written in the oldest minor version that has what it uses: 3 with a meter, else 2 with an
	 * initial value, else 1 with the anonymous pages, else 0.
	 */
	public static final int MINOR = 3;

	/** The bit of a history's <code>flags</code>: the datapoint is a meter reading (9.9, format 1.3). */
	public static final int HISTORY_METER = 0x01;

	/** The bit of a datapoint's <code>init</code>: its <code>initial</code> names its initial value (9.7). */
	public static final int INIT_INITIAL = 0x01;

	/** The newest format version this layout describes, as the controller reports it. */
	public static final String FORMAT = MAJOR + "." + MINOR;

	/** The bytes of the header. */
	public static final int HEADER_SIZE = 32;

	/** The bytes of an entry of the section table. */
	public static final int ENTRY_SIZE = 16;

	/** The bytes of the Ed25519 signature at the end of a signed image. */
	public static final int SIGNATURE_SIZE = 64;

	/** The header flag of a signed image. */
	public static final int FLAG_SIGNED = 1;

	/** The offset of the CRC-32 in the header. */
	public static final int CRC_OFFSET = 12;

	/** The longest string of an image in bytes. */
	public static final int MAX_STRING = 511;

	/** The most languages of a label. */
	public static final int MAX_LABEL_LANGUAGES = 16;

	/** "None" of a 16-bit index. */
	public static final int NONE = 0xFFFF;

	/** "None" of an 8-bit index. */
	public static final int NONE8 = 0xFF;

	/** The kind of a field. */
	public enum Kind {
		/** An unsigned byte. */
		U8(1),
		/** An unsigned 16-bit number. */
		U16(2),
		/** An unsigned 32-bit number. */
		U32(4),
		/** A signed 32-bit number. */
		I32(4),
		/** A signed 64-bit number. */
		I64(8),
		/** An unsigned 64-bit number (as a long). */
		U64(8),
		/** An IEEE 754 binary64. */
		F64(8),
		/** A string reference (u32 offset into <code>strings</code>). */
		STR(4),
		/** A label reference (u32: first entry, number of entries). */
		LABEL(4),
		/** Bytes given as they are. */
		RAW(0);

		private final int _size;

		Kind(int size) {
			_size = size;
		}

		/** The bytes of a field of this kind; 0 for {@link #RAW}. */
		public int size() {
			return _size;
		}
	}

	/**
	 * A field of a record.
	 *
	 * @param name
	 *        The name of the specification, used in refusal paths.
	 * @param offset
	 *        The offset within the record.
	 * @param kind
	 *        How it is stored.
	 * @param length
	 *        The bytes of a {@link Kind#RAW} field.
	 */
	public record Field(String name, int offset, Kind kind, int length) {

		/** The bytes of the field. */
		public int size() {
			return kind == Kind.RAW ? length : kind.size();
		}
	}

	/**
	 * A section of the image.
	 *
	 * @param id
	 *        The section id of the table.
	 * @param name
	 *        The name of the specification (the paths of section 10).
	 * @param size
	 *        The bytes of a record; 0 for a byte section.
	 * @param single
	 *        Whether the section holds exactly one record.
	 * @param fields
	 *        The fields in ascending order of their offsets.
	 */
	public record Section(int id, String name, int size, boolean single, List<Field> fields) {

		/** Whether this is a byte section (<code>strings</code>, <code>ui</code>, <code>ui.anonymous</code>). */
		public boolean bytes() {
			return size == 0;
		}

		/** The field of the given name, <code>null</code> if there is none. */
		public Field field(String fieldName) {
			for (Field field : fields) {
				if (field.name().equals(fieldName)) {
					return field;
				}
			}
			return null;
		}
	}

	/** <code>image</code>. */
	public static final int IMAGE = 0x01;

	/** <code>strings</code>. */
	public static final int STRINGS = 0x02;

	/** <code>labels</code>. */
	public static final int LABELS = 0x03;

	/** <code>settings</code>. */
	public static final int SETTINGS = 0x04;

	/** <code>datapoints</code>. */
	public static final int DATAPOINTS = 0x10;

	/** <code>locations</code>. */
	public static final int LOCATIONS = 0x11;

	/** <code>functions</code>. */
	public static final int FUNCTIONS = 0x12;

	/** <code>roles</code>. */
	public static final int ROLES = 0x13;

	/** <code>history</code>. */
	public static final int HISTORY = 0x14;

	/** <code>archives</code>. */
	public static final int ARCHIVES = 0x15;

	/** <code>knx.addresses</code>. */
	public static final int KNX_ADDRESSES = 0x20;

	/** <code>knx.bindings</code>. */
	public static final int KNX_BINDINGS = 0x21;

	/** <code>knx.listen</code>. */
	public static final int KNX_LISTEN = 0x22;

	/** <code>knx.listeners</code>. */
	public static final int KNX_LISTENERS = 0x23;

	/** <code>knx.timeMaster</code>. */
	public static final int KNX_TIME_MASTER = 0x24;

	/** <code>zigbee.bindings</code>. */
	public static final int ZIGBEE_BINDINGS = 0x28;

	/** <code>external.sources</code>. */
	public static final int EXTERNAL_SOURCES = 0x2C;

	/** <code>external.bindings</code>. */
	public static final int EXTERNAL_BINDINGS = 0x2D;

	/** <code>mqtt</code>. */
	public static final int MQTT = 0x2E;

	/** <code>schedules</code>. */
	public static final int SCHEDULES = 0x30;

	/** <code>schedules.dates</code>. */
	public static final int SCHEDULE_DATES = 0x31;

	/** <code>schedules.bounds</code>. */
	public static final int SCHEDULE_BOUNDS = 0x32;

	/** <code>rules</code>. */
	public static final int RULES = 0x40;

	/** <code>rules.parameters</code>. */
	public static final int RULE_PARAMETERS = 0x41;

	/** <code>rules.choices</code>. */
	public static final int RULE_CHOICES = 0x42;

	/** <code>rules.timers</code>. */
	public static final int RULE_TIMERS = 0x43;

	/** <code>rules.states</code>. */
	public static final int RULE_STATES = 0x44;

	/** <code>rules.reactions</code>. */
	public static final int RULE_REACTIONS = 0x45;

	/** <code>rules.triggers</code>. */
	public static final int RULE_TRIGGERS = 0x46;

	/** <code>rules.actions</code>. */
	public static final int RULE_ACTIONS = 0x47;

	/** <code>rules.logParts</code>. */
	public static final int RULE_LOG_PARTS = 0x48;

	/** <code>rules.timelines</code>. */
	public static final int RULE_TIMELINES = 0x49;

	/** <code>rules.plans</code>. */
	public static final int RULE_PLANS = 0x4A;

	/** <code>rules.points</code>. */
	public static final int RULE_POINTS = 0x4B;

	/** <code>rules.assignments</code>. */
	public static final int RULE_ASSIGNMENTS = 0x4C;

	/** <code>nodes</code>. */
	public static final int NODES = 0x4D;

	/** <code>constants</code>. */
	public static final int CONSTANTS = 0x4E;

	/** <code>blocks</code>. */
	public static final int BLOCKS = 0x50;

	/** <code>blocks.ports</code>. */
	public static final int BLOCK_PORTS = 0x51;

	/** <code>blocks.bound</code>. */
	public static final int BLOCK_BOUND = 0x52;

	/** <code>blocks.parameters</code>. */
	public static final int BLOCK_PARAMETERS = 0x53;

	/** <code>ui</code>. */
	public static final int UI = 0x60;

	/** <code>ui.anonymous</code>: the body of <code>GET /api/pages</code> for <code>anonymous</code> (format 1.1). */
	public static final int UI_ANONYMOUS = 0x62;

	/** <code>ui.anonymous.datapoints</code>: the datapoints the anonymous pages show (format 1.1). */
	public static final int UI_ANONYMOUS_DATAPOINTS = 0x63;

	/** <code>ui.anonymous.functions</code>: the functions the anonymous pages show (format 1.1). */
	public static final int UI_ANONYMOUS_FUNCTIONS = 0x64;

	/** The flag of an entry of <code>ui.anonymous.datapoints</code>: <code>anonymous</code> may command it. */
	public static final int ANONYMOUS_OPERATE = 0x01;

	private static final Map<Integer, Section> SECTIONS = new TreeMap<>();

	static {
		table(IMAGE, "image", 32, true, "projectId:0:STR", "revision:4:U32", "schemaVersion:8:STR", "name:12:STR",
			"generator:16:STR", "generatedAt:20:STR", "historyBudget:24:U32", "archiveBudget:28:U32");
		SECTIONS.put(Integer.valueOf(STRINGS), new Section(STRINGS, "strings", 0, false, List.of()));
		table(LABELS, "labels", 8, false, "tag:0:STR", "text:4:STR");
		table(SETTINGS, "settings", 32, true, "tz:0:STR", "ntp:4:STR", "flags:8:U8", "elev:12:I32", "lat:16:F64",
			"lon:24:F64");
		table(DATAPOINTS, "datapoints", 24, false, "name:0:STR", "label:4:LABEL", "unit:8:STR", "type:12:U8",
			"flags:13:U8", "owner:14:U8", "init:15:U8", "ownerIndex:16:U16", "ownerMember:18:U16", "history:20:U16",
			"initial:22:U16");
		table(LOCATIONS, "locations", 12, false, "id:0:STR", "label:4:LABEL", "parent:8:U16", "kind:10:U8");
		table(FUNCTIONS, "functions", 28, false, "id:0:STR", "label:4:LABEL", "unit:8:STR", "room:12:U16",
			"firstRole:14:U16", "kind:16:U8", "scene:17:U8", "roleCount:18:U8", "presses:20:RAW6");
		table(ROLES, "roles", 8, false, "name:0:STR", "datapoint:4:U16");
		table(HISTORY, "history", 16, false, "datapoint:0:U16", "archiveCount:2:U8", "flags:3:U8", "retention:4:U32",
			"spacing:8:U32", "firstArchive:12:U16");
		table(ARCHIVES, "archives", 8, false, "resolution:0:U32", "retention:4:U32");
		table(KNX_ADDRESSES, "knx.addresses", 8, false, "address:0:U16", "firstListener:2:U16",
			"listenerCount:4:U16");
		table(KNX_BINDINGS, "knx.bindings", 12, false, "datapoint:0:U16", "dptMain:2:U16", "dptSub:4:U16",
			"send:6:U16", "firstListen:8:U16", "listenCount:10:U8");
		table(KNX_LISTEN, "knx.listen", 2, false, "address:0:U16");
		table(KNX_LISTENERS, "knx.listeners", 2, false, "binding:0:U16");
		table(KNX_TIME_MASTER, "knx.timeMaster", 12, true, "time:0:U16", "date:2:U16", "dateTime:4:U16",
			"flags:6:U8", "interval:8:U32");
		table(ZIGBEE_BINDINGS, "zigbee.bindings", 32, false, "ieee:0:U64", "datapoint:8:U16", "cluster:10:U16",
			"attribute:12:U16", "command:14:U16", "reportMin:16:U16", "reportMax:18:U16", "endpoint:20:U8",
			"flags:21:U8", "reportChange:24:F64");
		table(EXTERNAL_SOURCES, "external.sources", 20, false, "id:0:STR", "url:4:STR", "preset:8:STR",
			"interval:12:U32", "firstBinding:16:U16", "bindingCount:18:U8");
		table(EXTERNAL_BINDINGS, "external.bindings", 32, false, "datapoint:0:U16", "path:4:STR", "variable:8:STR",
			"unit:12:STR", "scale:16:F64", "offset:24:F64");
		table(MQTT, "mqtt", 20, true, "broker:0:STR", "username:4:STR", "baseTopic:8:STR", "discoveryPrefix:12:STR",
			"historyInterval:16:U16", "flags:18:U8");
		table(SCHEDULES, "schedules", 64, false, "id:0:STR", "label:4:STR", "kind:8:U8", "flags:9:U8", "days:10:U8",
			"sun:11:U8", "output:12:U16", "timeDatapoint:14:U16", "adjustable:16:U16", "every:18:U16",
			"firstDate:20:U16", "dateCount:22:U16", "firstBound:24:U16", "boundCount:26:U8", "month:27:U8",
			"day:28:U8", "offset:32:I32", "time:36:U32", "notBefore:40:U32", "notAfter:44:U32", "from:48:U32",
			"until:52:U32", "atYear:56:U16", "atMonth:58:U8", "atDay:59:U8", "atTime:60:U32");
		table(SCHEDULE_DATES, "schedules.dates", 4, false, "year:0:U16", "month:2:U8", "day:3:U8");
		table(SCHEDULE_BOUNDS, "schedules.bounds", 32, false, "member:0:U16", "min:8:I64", "max:16:I64",
			"step:24:I64");
		table(RULES, "rules", 28, false, "id:0:STR", "name:4:LABEL", "firstParameter:8:U16", "firstTimer:10:U16",
			"firstState:12:U16", "firstReaction:14:U16", "parameterCount:16:U8", "timerCount:17:U8",
			"stateCount:18:U8", "reactionCount:19:U8", "initialState:20:U8", "timeline:22:U16",
			"stateDatapoint:24:U16");
		table(RULE_PARAMETERS, "rules.parameters", 28, false, "id:0:STR", "label:4:LABEL", "unit:8:STR",
			"type:12:U8", "adjustable:13:U8", "choiceCount:14:U8", "firstChoice:16:U16", "default:18:U16",
			"min:20:U16", "max:22:U16", "step:24:U16", "datapoint:26:U16");
		table(RULE_CHOICES, "rules.choices", 8, false, "value:0:U16", "label:4:LABEL");
		table(RULE_TIMERS, "rules.timers", 8, false, "id:0:STR", "duration:4:U16");
		table(RULE_STATES, "rules.states", 16, false, "id:0:STR", "label:4:LABEL", "firstEntry:8:U16",
			"entryCount:10:U8", "firstExit:12:U16", "exitCount:14:U8");
		table(RULE_REACTIONS, "rules.reactions", 16, false, "firstTrigger:0:U16", "triggerCount:2:U8", "to:3:U8",
			"condition:4:U16", "from:6:U16", "firstAction:8:U16", "actionCount:10:U8", "firstElse:12:U16",
			"elseCount:14:U8");
		table(RULE_TRIGGERS, "rules.triggers", 8, false, "kind:0:U8", "flags:1:U8", "ref:2:U16", "ref2:4:U16");
		table(RULE_ACTIONS, "rules.actions", 8, false, "kind:0:U8", "flags:1:U8", "target:2:U16", "expr:4:U16",
			"count:6:U16");
		table(RULE_LOG_PARTS, "rules.logParts", 8, false, "text:0:STR", "expr:4:U16");
		table(RULE_TIMELINES, "rules.timelines", 8, false, "firstPlan:0:U16", "firstAssignment:2:U16",
			"planCount:4:U8", "assignmentCount:5:U8", "flags:6:U8");
		table(RULE_PLANS, "rules.plans", 12, false, "id:0:STR", "label:4:LABEL", "firstPoint:8:U16",
			"pointCount:10:U8");
		table(RULE_POINTS, "rules.points", 16, false, "state:0:U8", "sun:1:U8", "flags:2:U8", "seconds:4:I32",
			"notBefore:8:I32", "notAfter:12:I32");
		table(RULE_ASSIGNMENTS, "rules.assignments", 2, false, "plan:0:U8", "days:1:U8");
		table(NODES, "nodes", 16, false, "kind:0:U8", "op:1:U8", "type:2:U8", "argc:3:U8", "a:4:U16", "size:6:U16",
			"lit:8:RAW8");
		table(CONSTANTS, "constants", 40, false, "type:0:U8", "present:1:U8", "length:2:U8", "value:8:RAW32");
		table(BLOCKS, "blocks", 28, false, "id:0:STR", "type:4:STR", "label:8:LABEL", "major:12:U16", "minor:14:U16",
			"firstPort:16:U16", "firstParameter:18:U16", "portCount:20:U8", "parameterCount:21:U8", "flags:22:U8",
			"enabledDatapoint:24:U16");
		table(BLOCK_PORTS, "blocks.ports", 12, false, "id:0:STR", "firstBound:4:U16", "boundCount:6:U8",
			"aggregation:7:U8", "mirror:8:U16");
		table(BLOCK_BOUND, "blocks.bound", 2, false, "datapoint:0:U16");
		table(BLOCK_PARAMETERS, "blocks.parameters", 16, false, "id:0:STR", "datapoint:4:U16", "flags:6:U8",
			"value:8:F64");
		SECTIONS.put(Integer.valueOf(UI), new Section(UI, "ui", 0, false, List.of()));
		SECTIONS.put(Integer.valueOf(UI_ANONYMOUS), new Section(UI_ANONYMOUS, "ui.anonymous", 0, false, List.of()));
		table(UI_ANONYMOUS_DATAPOINTS, "ui.anonymous.datapoints", 4, false, "datapoint:0:U16", "flags:2:U8");
		table(UI_ANONYMOUS_FUNCTIONS, "ui.anonymous.functions", 2, false, "function:0:U16");
	}

	private static void table(int id, String name, int size, boolean single, String... fields) {
		List<Field> list = new ArrayList<>();
		for (String spec : fields) {
			String[] parts = spec.split(":");
			String kind = parts[2];
			if (kind.startsWith("RAW")) {
				list.add(new Field(parts[0], Integer.parseInt(parts[1]), Kind.RAW, Integer.parseInt(kind.substring(3))));
			} else {
				list.add(new Field(parts[0], Integer.parseInt(parts[1]), Kind.valueOf(kind), 0));
			}
		}
		SECTIONS.put(Integer.valueOf(id), new Section(id, name, size, single, Collections.unmodifiableList(list)));
	}

	private Layout() {
		// Constants only.
	}

	/** The section of the id, <code>null</code> for an id format 1.1 does not know. */
	public static Section section(int id) {
		return SECTIONS.get(Integer.valueOf(id));
	}

	/** Every section of format 1.1, in ascending order of the ids. */
	public static Collection<Section> sections() {
		return Collections.unmodifiableCollection(SECTIONS.values());
	}

	/** The section of the name, <code>null</code> if there is none. */
	public static Section section(String name) {
		for (Section section : SECTIONS.values()) {
			if (section.name().equals(name)) {
				return section;
			}
		}
		return null;
	}

}
