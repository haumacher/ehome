/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.DeviceAccess;
import de.haumacher.ehome.programming.download.LoadControls;

/**
 * A device of mask 7.5 in memory for the tests of the download: it records every call, answers from
 * its memory and properties, and runs the load state machines of objects 1 to 4 like the KNX
 * resources specification describes them. A script may replace the load state the device answers a
 * load control with.
 *
 * <p>
 * With {@link #loadControlInMemory(int, int)} a device of mask 7.1: the load controls are written as
 * records of 11 bytes into the memory of the load control (the load state machine in the high, the
 * event in the low nibble of the first byte, see the KNX management procedures 3.31.2), the load state
 * of load state machine <i>n</i> is the byte <i>n</i> - 1 after the address of the load states. The
 * record is decoded here independently of the encoding of the download.
 * </p>
 */
@SuppressWarnings("javadoc")
public class MockDevice implements DeviceAccess {

	private final byte[] _memory = new byte[0x10000];

	private final Map<String, byte[]> _properties = new HashMap<>();

	private final int[] _states = new int[8];

	private final List<String> _calls = new ArrayList<>();

	private final java.util.Set<Integer> _refusedWrites = new java.util.HashSet<>();

	private byte[] _descriptor = { 0x07, 0x05 };

	/** Script: (object index, control) to the state to answer, or null for the regular one. */
	private BiFunction<Integer, byte[], Integer> _loadControlScript = (o, c) -> null;

	/** The address of the load control in memory, -1 for load controls through properties. */
	private int _memoryControl = -1;

	/** The address of the load state of load state machine 1 in memory. */
	private int _memoryStates = -1;

	public List<String> calls() {
		return _calls;
	}

	public byte[] memory() {
		return _memory;
	}

	public MockDevice setMemory(int address, byte[] data) {
		System.arraycopy(data, 0, _memory, address, data.length);
		return this;
	}

	public MockDevice setProperty(int object, int property, String hex) {
		_properties.put(object + "/" + property, HexFormat.of().parseHex(hex));
		return this;
	}

	/**
	 * Sets one element of a property, as a device holds it without a write (e.g. a download stamp the
	 * device computes itself).
	 */
	public MockDevice setPropertyElement(int object, int property, int start, byte[] value) {
		_properties.put(object + "/" + property + "/" + start, value);
		return this;
	}

	/**
	 * Refuses every write of the given property, as a device that computes it itself does.
	 */
	public MockDevice refuseWrites(int property) {
		_refusedWrites.add(Integer.valueOf(property));
		return this;
	}

	public MockDevice setDescriptor(String hex) {
		_descriptor = HexFormat.of().parseHex(hex);
		return this;
	}

	public MockDevice setState(int object, int state) {
		_states[object] = state;
		return this;
	}

	/**
	 * Makes the device one controlling its load state machines in memory (mask 7.1).
	 *
	 * @param control
	 *        The address of the load control (0104).
	 * @param states
	 *        The address of the load state of load state machine 1 (B6EA), the others following.
	 */
	public MockDevice loadControlInMemory(int control, int states) {
		_memoryControl = control;
		_memoryStates = states;
		return this;
	}

	public int state(int lsm) {
		return _states[lsm];
	}

	/**
	 * The script receives the load state machine (object index or, in memory, the high nibble of the
	 * record) and the load control in the form of the property (10 bytes).
	 */
	public MockDevice scriptLoadControl(BiFunction<Integer, byte[], Integer> script) {
		_loadControlScript = script;
		return this;
	}

	/**
	 * The number of calls changing the device.
	 */
	public long writes() {
		return _calls.stream().filter(c -> c.startsWith("write") || c.startsWith("restart")).count();
	}

	@Override
	public byte[] readDescriptor() {
		_calls.add("readDescriptor");
		return _descriptor;
	}

	@Override
	public byte[] readProperty(int objectIndex, int propertyId, int start, int count) throws BusException {
		_calls.add("readProperty " + objectIndex + " " + propertyId + " " + start + " " + count);
		if (propertyId == LoadControls.PID_LOAD_STATE_CONTROL && objectIndex < _states.length) {
			return new byte[] { (byte) _states[objectIndex] };
		}
		byte[] value = _properties.get(objectIndex + "/" + propertyId + "/" + start);
		if (value == null) {
			value = _properties.get(objectIndex + "/" + propertyId);
		}
		if (value == null) {
			throw new BusException("property access OI " + objectIndex + " PID " + propertyId + " failed/forbidden");
		}
		return value;
	}

	@Override
	public byte[] readMemory(int address, int length) {
		_calls.add(String.format("readMemory %04X %d", address, length));
		byte[] result = Arrays.copyOfRange(_memory, address, address + length);
		if (_memoryControl >= 0) {
			for (int lsm = 1; lsm <= 4; lsm++) {
				int at = _memoryStates + lsm - 1 - address;
				if (at >= 0 && at < length) {
					result[at] = (byte) _states[lsm];
				}
			}
		}
		return result;
	}

	@Override
	public void disconnect() {
		_calls.add("disconnect");
	}

	@Override
	public void close() {
		// Nothing to release.
	}

	@Override
	public int authorize(byte[] key) {
		_calls.add("authorize " + HexFormat.of().withUpperCase().formatHex(key));
		return 0;
	}

	@Override
	public Integer writeLoadControl(int objectIndex, byte[] control) {
		_calls.add("writeLoadControl " + objectIndex + " " + HexFormat.of().formatHex(control));
		return loadControl(objectIndex, control);
	}

	private int loadControl(int objectIndex, byte[] control) {
		int state = _states[objectIndex];
		switch (control[0]) {
			case LoadControls.EVENT_UNLOAD:
				state = LoadControls.STATE_UNLOADED;
				break;
			case LoadControls.EVENT_LOAD:
				state = LoadControls.STATE_LOADING;
				break;
			case LoadControls.EVENT_ADDITIONAL:
				state = state == LoadControls.STATE_LOADING ? state : LoadControls.STATE_ERROR;
				break;
			case LoadControls.EVENT_LOAD_COMPLETED:
				state = state == LoadControls.STATE_LOADING ? LoadControls.STATE_LOADED : LoadControls.STATE_ERROR;
				break;
			default:
				state = LoadControls.STATE_ERROR;
		}
		Integer scripted = _loadControlScript.apply(objectIndex, control);
		_states[objectIndex] = scripted != null ? scripted : state;
		return _states[objectIndex];
	}

	@Override
	public void writeProperty(int objectIndex, int propertyId, int start, int count, byte[] data) throws BusException {
		_calls.add("writeProperty " + objectIndex + " " + propertyId + " " + start + " " + count + " "
			+ HexFormat.of().formatHex(data));
		if (_refusedWrites.contains(Integer.valueOf(propertyId))) {
			throw new BusException("property access OI " + objectIndex + " PID " + propertyId + " failed/forbidden");
		}
		_properties.put(objectIndex + "/" + propertyId + "/" + start, data);
	}

	@Override
	public void writeMemory(int address, byte[] data) {
		_calls.add(String.format("writeMemory %04X %s", address, HexFormat.of().formatHex(data)));
		if (address == _memoryControl) {
			if (data.length != 11) {
				throw new AssertionError("A load control in memory has 11 bytes: " + HexFormat.of().formatHex(data));
			}
			int lsm = (data[0] & 0xF0) >>> 4;
			// The control as written into a property: the event, for additional load controls the
			// segment type and the data after the segment ID.
			byte[] control = new byte[10];
			control[0] = (byte) (data[0] & 0x0F);
			if (control[0] == LoadControls.EVENT_ADDITIONAL) {
				control[1] = data[1];
				if (data[2] != 0) {
					throw new AssertionError("Segment ID not 0: " + HexFormat.of().formatHex(data));
				}
				System.arraycopy(data, 3, control, 2, 8);
			} else {
				System.arraycopy(data, 1, control, 1, 9);
				if (data[10] != 0) {
					throw new AssertionError("Byte 11 of a load event not 0: " + HexFormat.of().formatHex(data));
				}
			}
			loadControl(lsm, control);
			return;
		}
		System.arraycopy(data, 0, _memory, address, data.length);
	}

	@Override
	public void restart() {
		_calls.add("restart");
	}

	@Override
	public void writeIndividualAddress(int address) {
		_calls.add("writeIndividualAddress " + Integer.toHexString(address));
	}

}
