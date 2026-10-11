/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

/**
 * The load controls of a KNX load state machine and the download stamp of a segment.
 *
 * <p>
 * A load control is the 10 bytes written into the load state control property
 * (<code>PID_LOAD_STATE_CONTROL</code>, 5) of the interface object of a load state machine: the load
 * event, for the event "additional load controls" the kind of control and its data, padded with
 * zero bytes. The events and states are those of the KNX resources specification (Load State
 * Machine, as in BCU 2 / BIM M112 with <code>PropertyMappedLsms</code>):
 * </p>
 * <ul>
 * <li>Events: 1 start loading, 2 load completed, 3 additional load controls, 4 unload.</li>
 * <li>States: 0 unloaded, 1 loaded, 2 loading, 3 error, 4 unloading, 5 load completing.</li>
 * <li>Additional load controls (event 3), by segment type: 0 and 1 allocate an absolute segment
 * (code/data resp. stack): address (2 bytes), length (2 bytes), access (read and write level, one
 * nibble each), memory type, attributes (ETS: <code>SegFlags</code>), a zero byte; 2 task segment:
 * address (2 bytes), PEI type, the application ID (manufacturer, 2 bytes; application number, 2
 * bytes; version); 4 task control 1: address of the event handler (2 bytes) and the number of
 * objects.</li>
 * </ul>
 *
 * <p>
 * Verified on the test devices (mask 7.5, read-back of what ETS wrote): the table pointers
 * (<code>PID_TABLE_REFERENCE</code>) equal the task segment addresses, the program version of the
 * application (<code>PID_PROGRAM_VERSION</code>) equals the application ID the task segment carries
 * (manufacturer, number, version), and the download stamps (<code>PID_MCB_TABLE</code>, 27) are
 * {@link #stamp(int, int, byte[]) computed} as below. Not verified: the order of the PEI type
 * before the application ID in the task segment (KNX specification; the PEI type of both reference
 * programs' devices could not be told apart from 0).
 * </p>
 */
public class LoadControls {

	/** The interface object property controlling a load state machine (<code>PID_LOAD_STATE_CONTROL</code>). */
	public static final int PID_LOAD_STATE_CONTROL = 5;

	/** The interface object property of a table's address (<code>PID_TABLE_REFERENCE</code>). */
	public static final int PID_TABLE_REFERENCE = 7;

	/** The interface object property of the program version (<code>PID_PROGRAM_VERSION</code>). */
	public static final int PID_PROGRAM_VERSION = 13;

	/** The length of a load control. */
	public static final int LENGTH = 10;

	/** Load event: start loading. */
	public static final int EVENT_LOAD = 1;

	/** Load event: load completed. */
	public static final int EVENT_LOAD_COMPLETED = 2;

	/** Load event: additional load controls. */
	public static final int EVENT_ADDITIONAL = 3;

	/** Load event: unload. */
	public static final int EVENT_UNLOAD = 4;

	/** Load state: unloaded. */
	public static final int STATE_UNLOADED = 0;

	/** Load state: loaded. */
	public static final int STATE_LOADED = 1;

	/** Load state: loading. */
	public static final int STATE_LOADING = 2;

	/** Load state: error. */
	public static final int STATE_ERROR = 3;

	/** Load state: unloading. */
	public static final int STATE_UNLOADING = 4;

	/** Load state: load completing. */
	public static final int STATE_LOAD_COMPLETING = 5;

	/** Additional load control: allocate an absolute code/data segment. */
	public static final int SEGMENT_ABSOLUTE = 0;

	/** Additional load control: task segment. */
	public static final int SEGMENT_TASK = 2;

	/** Additional load control: task control 1. */
	public static final int SEGMENT_TASK_CONTROL_1 = 4;

	/** The size of an element of the download stamp of a segment. */
	public static final int STAMP_LENGTH = 8;

	/**
	 * A load control of one of the events without data.
	 */
	public static byte[] event(int event) {
		byte[] result = new byte[LENGTH];
		result[0] = (byte) event;
		return result;
	}

	/**
	 * The additional load control allocating an absolute segment.
	 *
	 * @param segmentType
	 *        0 for code/data, 1 for a stack.
	 * @param access
	 *        The read level (high nibble) and write level (low nibble).
	 * @param memoryType
	 *        The memory type as the device codes it.
	 * @param flags
	 *        The attributes of the segment (ETS: <code>SegFlags</code>).
	 */
	public static byte[] absoluteSegment(int segmentType, int address, int length, int access, int memoryType, int flags) {
		byte[] result = additional(segmentType);
		put16(result, 2, address);
		put16(result, 4, length);
		result[6] = (byte) access;
		result[7] = (byte) memoryType;
		result[8] = (byte) flags;
		return result;
	}

	/**
	 * The additional load control of a task segment: its address and the identity of the program.
	 */
	public static byte[] taskSegment(int address, int peiType, int manufacturer, int applicationNumber, int version) {
		byte[] result = additional(SEGMENT_TASK);
		put16(result, 2, address);
		result[4] = (byte) peiType;
		byte[] id = applicationId(manufacturer, applicationNumber, version);
		System.arraycopy(id, 0, result, 5, id.length);
		return result;
	}

	/**
	 * The additional load control "task control 1": the address of the event handler and the
	 * number of objects.
	 */
	public static byte[] taskControl1(int address, int count) {
		byte[] result = additional(SEGMENT_TASK_CONTROL_1);
		put16(result, 2, address);
		result[4] = (byte) count;
		return result;
	}

	/**
	 * The application ID as a device reports it in <code>PID_PROGRAM_VERSION</code>: manufacturer
	 * (2 bytes), application number (2 bytes), version.
	 */
	public static byte[] applicationId(int manufacturer, int applicationNumber, int version) {
		byte[] result = new byte[5];
		put16(result, 0, manufacturer);
		put16(result, 2, applicationNumber);
		result[4] = (byte) version;
		return result;
	}

	private static byte[] additional(int segmentType) {
		byte[] result = event(EVENT_ADDITIONAL);
		result[1] = (byte) segmentType;
		return result;
	}

	/**
	 * The download stamp of a segment, an element of <code>PID_MCB_TABLE</code> (27): the length
	 * (4 bytes), a zero byte, the access byte of the segment, the CRC of its memory (2 bytes).
	 *
	 * <p>
	 * Verified: the stamps ETS wrote into the Tastsensor of the test system equal this computation
	 * over the device's memory, for all eight stamped segments.
	 * </p>
	 *
	 * @param access
	 *        The access byte of the segment's allocation.
	 * @param memory
	 *        The memory of the segment as it is in the device after the download.
	 */
	public static byte[] stamp(int access, byte[] memory) {
		byte[] result = new byte[STAMP_LENGTH];
		int length = memory.length;
		result[0] = (byte) (length >>> 24);
		result[1] = (byte) (length >>> 16);
		result[2] = (byte) (length >>> 8);
		result[3] = (byte) length;
		result[4] = 0;
		result[5] = (byte) access;
		put16(result, 6, crc16(memory));
		return result;
	}

	/**
	 * CRC-16/CCITT-FALSE (polynomial 0x1021, initial value 0xFFFF, no reflection, no final XOR).
	 */
	public static int crc16(byte[] data) {
		int crc = 0xFFFF;
		for (byte b : data) {
			crc ^= (b & 0xFF) << 8;
			for (int bit = 0; bit < 8; bit++) {
				crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
				crc &= 0xFFFF;
			}
		}
		return crc;
	}

	/**
	 * A load control for a person.
	 */
	public static String describe(byte[] control) {
		switch (control[0]) {
			case EVENT_LOAD:
				return "start loading";
			case EVENT_LOAD_COMPLETED:
				return "load completed";
			case EVENT_UNLOAD:
				return "unload";
			case EVENT_ADDITIONAL:
				int address = (control[2] & 0xFF) << 8 | control[3] & 0xFF;
				switch (control[1]) {
					case 0:
					case 1:
						int length = (control[4] & 0xFF) << 8 | control[5] & 0xFF;
						return String.format("allocate %s segment %04X-%04X (%d bytes, access %02X, memory type %d, flags %02X)",
							control[1] == 0 ? "absolute" : "stack", address, address + length - 1, length, control[6] & 0xFF,
							control[7] & 0xFF, control[8] & 0xFF);
					case SEGMENT_TASK:
						return String.format("task segment %04X, PEI type %d, application %02X%02X %02X%02X %02X", address,
							control[4] & 0xFF, control[5] & 0xFF, control[6] & 0xFF, control[7] & 0xFF, control[8] & 0xFF,
							control[9] & 0xFF);
					case SEGMENT_TASK_CONTROL_1:
						return String.format("task control 1, event handler %04X, %d objects", address, control[4] & 0xFF);
					default:
						return "additional load control " + control[1];
				}
			default:
				return "load event " + control[0];
		}
	}

	/**
	 * A load state for a person.
	 */
	public static String stateName(int state) {
		switch (state) {
			case STATE_UNLOADED:
				return "unloaded";
			case STATE_LOADED:
				return "loaded";
			case STATE_LOADING:
				return "loading";
			case STATE_ERROR:
				return "error";
			case STATE_UNLOADING:
				return "unloading";
			case STATE_LOAD_COMPLETING:
				return "load completing";
			default:
				return "state " + state;
		}
	}

	private static void put16(byte[] buffer, int position, int value) {
		buffer[position] = (byte) (value >>> 8);
		buffer[position + 1] = (byte) value;
	}

}
