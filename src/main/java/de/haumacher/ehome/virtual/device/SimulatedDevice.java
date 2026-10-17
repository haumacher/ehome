/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.haumacher.ehome.programming.download.LoadControls;

/**
 * The state of the one device behind a virtual interface: its memory, its interface object
 * properties and its load state machines, answering the management services a programming tool
 * sends.
 *
 * <p>
 * The class knows nothing of the bus; the virtual interface decodes the services and calls it.
 * Its semantics on the wire are those of the test double <code>MockDevice</code> of the download
 * tests (checked by <code>TestVirtualDevice</code>):
 * </p>
 * <ul>
 * <li>{@link LoadControlKind#PROPERTIES} (masks with <code>PropertyMappedLsms</code>, mask 7.5): the
 * load state machines of the interface objects 1 to 4 follow the load controls written into their
 * property {@link LoadControls#PID_LOAD_STATE_CONTROL} (unload: unloaded; start loading: loading;
 * additional load controls: stay loading, else error; load completed: loaded if loading, else
 * error). The additional load control of a task segment also sets the table reference
 * (<code>PID_TABLE_REFERENCE</code>, the segment's address) and the program version
 * (<code>PID_PROGRAM_VERSION</code>, the application ID it carries) of the object, as the test
 * devices showed after ETS had loaded them (see {@link LoadControls}).</li>
 * <li>{@link LoadControlKind#MEMORY} (masks 0700 and 0701, BIM M112): the load controls are written
 * to memory {@value #MANAGEMENT_CONTROL} (state machine in the high, event in the low nibble of the
 * first byte, as Calimero's device implementation reads them), the load states are read at
 * {@value #LOAD_STATES} and the following bytes. The device records the writes and answers the
 * reads by its {@link MappedLoadStateRule}; the true answers of such a device are not known.</li>
 * <li>{@link LoadControlKind#NONE} (BCU 1, BCU 2): plain memory; a property access is answered as
 * not present.</li>
 * </ul>
 *
 * <p>
 * A property the device does not hold is answered as not present (a negative response); such reads
 * are counted by {@link #unknownReads()} so that a person sees what a tool asked for beyond the
 * project data.
 * </p>
 *
 * <p>
 * Programming mode (the programming button of a real device): off at the start; the button
 * ({@link #pressProgrammingButton()}) switches it on and off, a restart ({@link #reset()}) ends it.
 * Only in programming mode does the device answer <code>A_IndividualAddress_Read</code> and take the
 * address of <code>A_IndividualAddress_Write</code> ({@link #changeAddress(int)}); the address is the
 * device's address on the virtual bus from then on, nothing of it goes back into the project.
 * </p>
 */
public class SimulatedDevice {

	/** The address of the management control of a memory-mapped load state machine (BIM M112). */
	public static final int MANAGEMENT_CONTROL = 0x0104;

	/** The address of the load state of the first memory-mapped load state machine (BIM M112). */
	public static final int LOAD_STATES = 0xB6EA;

	/** The number of memory-mapped load state machines (address table, association table, application, PEI program). */
	public static final int MAPPED_STATE_MACHINES = 4;

	/** The interface object property naming the type of the object. */
	public static final int PID_OBJECT_TYPE = 1;

	/** The interface object property of the manufacturer (device object). */
	public static final int PID_MANUFACTURER_ID = 12;

	/** The interface object property of the device control (device object). */
	public static final int PID_DEVICE_CONTROL = 14;

	/** The interface object property of the serial number (device object). */
	public static final int PID_SERIAL_NUMBER = 11;

	/** The interface object property of the maximum length of an APDU (device object). */
	public static final int PID_MAX_APDU_LENGTH = 56;

	/** The interface object property of the hardware type (device object). */
	public static final int PID_HARDWARE_TYPE = 78;

	/** The size of the memory. */
	public static final int MEMORY_SIZE = 0x10000;

	/**
	 * How the load state machines of a device are controlled.
	 */
	public enum LoadControlKind {
		/** Through the load state control property of the interface objects 1 to 4 (mask 7.5). */
		PROPERTIES,

		/** Through memory 0104, the states at B6EA (BIM M112 masks 0700, 0701). */
		MEMORY,

		/** No load state machines: plain memory (BCU 1, BCU 2). */
		NONE;
	}

	/** The interface objects with a load state machine of a {@link LoadControlKind#PROPERTIES} device. */
	private static final int FIRST_LSM_OBJECT = 1;

	private static final int LAST_LSM_OBJECT = 4;

	private final String _name;

	/** The individual address, changed by {@link #changeAddress(int)}. */
	private int _address;

	/** The individual address the device was created with (the project's). */
	private final int _initialAddress;

	private boolean _programmingMode;

	/** Where values of the device come from that the project does not hold, by subject. */
	private final Map<String, String> _notes = new LinkedHashMap<>();

	private final int _descriptor;

	private final LoadControlKind _kind;

	private final MappedLoadStateRule _rule;

	private final byte[] _memory = new byte[MEMORY_SIZE];

	/** The properties by object index and property ID, each a map of the elements by index from 1. */
	private final Map<Integer, Map<Integer, TreeMap<Integer, byte[]>>> _properties = new HashMap<>();

	/** The number of interface objects; a property of an object beyond them is refused. */
	private final int _objectCount;

	/** The load states by interface object (properties) or by state machine (memory). */
	private final int[] _states = new int[16];

	private final List<String> _unknownReads = new ArrayList<>();

	/**
	 * Creates a {@link SimulatedDevice} with empty memory, all load state machines unloaded.
	 *
	 * @param name
	 *        The device for a person (address and ID).
	 * @param address
	 *        The individual address.
	 * @param descriptor
	 *        The device descriptor type 0 (the mask version).
	 * @param kind
	 *        How the load state machines are controlled.
	 * @param rule
	 *        How a {@link LoadControlKind#MEMORY} device answers the reads of its load states.
	 */
	public SimulatedDevice(String name, int address, int descriptor, LoadControlKind kind, MappedLoadStateRule rule) {
		_name = name;
		_address = address;
		_initialAddress = address;
		_descriptor = descriptor;
		_kind = kind;
		_rule = rule;
		_objectCount = kind == LoadControlKind.PROPERTIES ? LAST_LSM_OBJECT + 1 : 0;
		for (int object = 0; object < _objectCount; object++) {
			// Device object, address table, association table, application program, PEI program
			// (the System B layout the download of mask 7.5 uses).
			putElements(object, PID_OBJECT_TYPE, 1, 1, new byte[] { 0, (byte) object });
		}
	}

	/**
	 * The device for a person.
	 */
	public String name() {
		return _name;
	}

	/**
	 * The individual address on the virtual bus.
	 */
	public synchronized int address() {
		return _address;
	}

	/**
	 * The individual address the device was created with (the one of the project).
	 */
	public int initialAddress() {
		return _initialAddress;
	}

	/**
	 * Whether the device is in programming mode.
	 */
	public synchronized boolean programmingMode() {
		return _programmingMode;
	}

	/**
	 * Presses the programming button: switches the programming mode on, or off if it is on.
	 *
	 * @return Whether the device is in programming mode now.
	 */
	public synchronized boolean pressProgrammingButton() {
		_programmingMode = !_programmingMode;
		return _programmingMode;
	}

	/**
	 * Takes a new individual address (A_IndividualAddress_Write), only in programming mode.
	 *
	 * @return Whether the device took it: it is in programming mode.
	 */
	public synchronized boolean changeAddress(int address) {
		if (!_programmingMode) {
			return false;
		}
		_address = address;
		return true;
	}

	/**
	 * Records where a value of the device comes from that the project does not hold (a substitute),
	 * for the header of a trace.
	 *
	 * @param subject
	 *        What the value is, e.g. <code>property 0/11</code>.
	 * @param text
	 *        The value and its source.
	 */
	public synchronized SimulatedDevice note(String subject, String text) {
		_notes.put(subject, text);
		return this;
	}

	/**
	 * The notes of {@link #note(String, String)}, by subject.
	 */
	public synchronized Map<String, String> notes() {
		return new LinkedHashMap<>(_notes);
	}

	/**
	 * How the load state machines are controlled.
	 */
	public LoadControlKind kind() {
		return _kind;
	}

	/**
	 * How the reads of memory-mapped load states are answered.
	 */
	public MappedLoadStateRule rule() {
		return _rule;
	}

	/**
	 * The device descriptor type 0 (2 bytes).
	 */
	public synchronized byte[] descriptor() {
		return new byte[] { (byte) (_descriptor >>> 8), (byte) _descriptor };
	}

	/**
	 * The mask version.
	 */
	public int mask() {
		return _descriptor;
	}

	/**
	 * Sets memory without recording it (the initial content).
	 */
	public synchronized SimulatedDevice initMemory(int address, byte[] data) {
		System.arraycopy(data, 0, _memory, address, data.length);
		return this;
	}

	/**
	 * Sets a property value of one element without recording it (the initial content).
	 */
	public synchronized SimulatedDevice initProperty(int object, int property, byte[] value) {
		putElements(object, property, 1, 1, value);
		return this;
	}

	/**
	 * Sets the load state of an interface object (properties) or a state machine (memory) without
	 * recording it (the initial state).
	 */
	public synchronized SimulatedDevice initState(int index, int state) {
		_states[index] = state;
		return this;
	}

	/**
	 * A copy of the memory.
	 */
	public synchronized byte[] memorySnapshot() {
		return _memory.clone();
	}

	/**
	 * The load state of an interface object (properties) or a state machine (memory).
	 */
	public synchronized int state(int index) {
		return _states[index];
	}

	/**
	 * The property reads answered as not present, as <code>object/property</code>, in the order
	 * they happened.
	 */
	public synchronized List<String> unknownReads() {
		return Collections.unmodifiableList(new ArrayList<>(_unknownReads));
	}

	/**
	 * Reads memory (A_Memory_Read).
	 *
	 * @return The bytes, <code>null</code> if the range is outside the memory.
	 */
	public synchronized byte[] memory(int address, int length) {
		if (address < 0 || length < 0 || address + length > MEMORY_SIZE) {
			return null;
		}
		byte[] result = Arrays.copyOfRange(_memory, address, address + length);
		if (_kind == LoadControlKind.MEMORY) {
			for (int machine = 1; machine <= MAPPED_STATE_MACHINES; machine++) {
				int at = LOAD_STATES + machine - 1 - address;
				if (at >= 0 && at < length) {
					result[at] = (byte) _rule.answer(_states[machine]);
				}
			}
		}
		return result;
	}

	/**
	 * Writes memory (A_Memory_Write).
	 *
	 * @return Whether the device accepts the write (the range lies in the memory).
	 */
	public synchronized boolean putMemory(int address, byte[] data) {
		if (address < 0 || address + data.length > MEMORY_SIZE) {
			return false;
		}
		System.arraycopy(data, 0, _memory, address, data.length);
		if (_kind == LoadControlKind.MEMORY && address == MANAGEMENT_CONTROL && data.length > 0) {
			int machine = (data[0] & 0xF0) >>> 4;
			int event = data[0] & 0x0F;
			if (machine >= 1 && machine <= MAPPED_STATE_MACHINES) {
				_states[machine] = next(_states[machine], event);
			}
		}
		return true;
	}

	/**
	 * Reads elements of a property (A_PropertyValue_Read).
	 *
	 * @param start
	 *        The first element, from 1; 0 reads the number of elements (2 bytes).
	 * @return The value, <code>null</code> if the device does not hold it (a negative response).
	 */
	public synchronized byte[] property(int object, int property, int start, int count) {
		if (_kind == LoadControlKind.PROPERTIES && property == LoadControls.PID_LOAD_STATE_CONTROL && lsmObject(object)) {
			if (start == 0) {
				return new byte[] { 0, 1 };
			}
			return new byte[] { (byte) _states[object] };
		}
		TreeMap<Integer, byte[]> elements = elements(object, property);
		if (elements == null || elements.isEmpty()) {
			_unknownReads.add(object + "/" + property);
			return null;
		}
		if (start == 0) {
			int number = elements.lastKey();
			return new byte[] { (byte) (number >>> 8), (byte) number };
		}
		byte[][] parts = new byte[count][];
		int length = 0;
		for (int n = 0; n < count; n++) {
			parts[n] = elements.get(start + n);
			if (parts[n] == null) {
				_unknownReads.add(object + "/" + property + "[" + (start + n) + "]");
				return null;
			}
			length += parts[n].length;
		}
		byte[] result = new byte[length];
		int position = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, result, position, part.length);
			position += part.length;
		}
		return result;
	}

	/**
	 * Writes elements of a property (A_PropertyValue_Write).
	 *
	 * @return The value the device answers with: the value written, the new load state (1 byte)
	 *         for a load control; <code>null</code> if the device refuses the write.
	 */
	public synchronized byte[] putProperty(int object, int property, int start, int count, byte[] data) {
		if (!objectExists(object) || count <= 0 || start <= 0 || data.length % count != 0) {
			return null;
		}
		if (_kind == LoadControlKind.PROPERTIES && property == LoadControls.PID_LOAD_STATE_CONTROL && lsmObject(object)) {
			if (data.length != LoadControls.LENGTH) {
				return null;
			}
			_states[object] = loadControl(object, data);
			return new byte[] { (byte) _states[object] };
		}
		putElements(object, property, start, count, data);
		return data;
	}

	/**
	 * Whether the device is in verify mode: bit 2 of its device control (device object, property
	 * {@value #PID_DEVICE_CONTROL}) is set; then it answers a memory write with the memory written.
	 */
	public synchronized boolean verifyMode() {
		TreeMap<Integer, byte[]> control = elements(0, PID_DEVICE_CONTROL);
		byte[] value = control == null ? null : control.get(1);
		return value != null && value.length > 0 && (value[0] & 0x04) != 0;
	}

	/**
	 * Restarts the device (A_Restart): memory, properties and load states stay, the programming mode
	 * ends (as on a real device, whose programming LED goes off with the restart that ends the
	 * programming of its address).
	 *
	 * @return Whether the restart ended the programming mode.
	 */
	public synchronized boolean reset() {
		boolean wasOn = _programmingMode;
		_programmingMode = false;
		return wasOn;
	}

	private boolean objectExists(int object) {
		return object >= 0 && object < _objectCount;
	}

	private static boolean lsmObject(int object) {
		return object >= FIRST_LSM_OBJECT && object <= LAST_LSM_OBJECT;
	}

	private int loadControl(int object, byte[] control) {
		int state = next(_states[object], control[0]);
		if (control[0] == LoadControls.EVENT_ADDITIONAL && control[1] == LoadControls.SEGMENT_TASK
			&& state == LoadControls.STATE_LOADING) {
			// The task segment: the address of the table (4 bytes) and the application ID.
			putElements(object, LoadControls.PID_TABLE_REFERENCE, 1, 1, new byte[] { 0, 0, control[2], control[3] });
			putElements(object, LoadControls.PID_PROGRAM_VERSION, 1, 1, Arrays.copyOfRange(control, 5, 10));
		}
		return state;
	}

	/**
	 * The load state after a load event, as the KNX resources specification describes the load
	 * state machine.
	 */
	public static int next(int state, int event) {
		switch (event) {
			case LoadControls.EVENT_UNLOAD:
				return LoadControls.STATE_UNLOADED;
			case LoadControls.EVENT_LOAD:
				return LoadControls.STATE_LOADING;
			case LoadControls.EVENT_ADDITIONAL:
				return state == LoadControls.STATE_LOADING ? state : LoadControls.STATE_ERROR;
			case LoadControls.EVENT_LOAD_COMPLETED:
				return state == LoadControls.STATE_LOADING ? LoadControls.STATE_LOADED : LoadControls.STATE_ERROR;
			default:
				return LoadControls.STATE_ERROR;
		}
	}

	private TreeMap<Integer, byte[]> elements(int object, int property) {
		Map<Integer, TreeMap<Integer, byte[]>> byProperty = _properties.get(object);
		return byProperty == null ? null : byProperty.get(property);
	}

	private void putElements(int object, int property, int start, int count, byte[] data) {
		TreeMap<Integer, byte[]> elements =
			_properties.computeIfAbsent(object, o -> new HashMap<>()).computeIfAbsent(property, p -> new TreeMap<>());
		int size = data.length / count;
		for (int n = 0; n < count; n++) {
			elements.put(start + n, Arrays.copyOfRange(data, n * size, (n + 1) * size));
		}
	}

	@Override
	public String toString() {
		return _name;
	}

}
