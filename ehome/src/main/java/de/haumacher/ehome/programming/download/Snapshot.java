/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What was read from a device (haumacher/ehome #193): its identity (individual address, device
 * descriptor, program, program version), the states of its load state machines, its memory as the
 * ranges read, the properties read, and what could not be read.
 *
 * <p>
 * The memory is addressable byte by byte ({@link #byteAt(int)}): an address is either outside every
 * range read (not read at all), or in a range and then known or unknown (a read of it failed;
 * {@link #isKnown(int)}). An unknown byte is never zero. The ranges ({@link #ranges()}) are the
 * maximal runs of addresses read.
 * </p>
 *
 * <p>
 * A snapshot is computed by {@link ReadOut} from the bus, stored with the device by
 * {@link Snapshots}, written to and read from a file by {@link SnapshotFile}, compared with the
 * plan by {@link DeviceComparison#compare(de.haumacher.ehome.model.operational.Program, DownloadPlan, Snapshot)},
 * and decoded into the project by <code>ReadBack.Memory.of(Snapshot, TLObject)</code>. Its bytes
 * per range with the known/unknown information and its identity are what a download from a
 * snapshot (restore, haumacher/ehome #194) writes and checks.
 * </p>
 */
public final class Snapshot {

	/** The size of the address space of the memory. */
	public static final int MEMORY_SIZE = 0x10000;

	/** The text of an unknown byte in the hex form of a range. */
	public static final String UNKNOWN = "??";

	/**
	 * A run of addresses read.
	 *
	 * @param address
	 *        The first address.
	 * @param data
	 *        The bytes; a byte not {@link #known() known} is 0 here, but never reported as a value.
	 * @param known
	 *        The positions (relative to <code>address</code>) of the bytes read.
	 */
	public record Range(int address, byte[] data, BitSet known) {

		/**
		 * The address after the last byte.
		 */
		public int end() {
			return address + data.length;
		}

		/**
		 * The bytes in hex, two digits each, {@value Snapshot#UNKNOWN} for an unknown one.
		 */
		public String hex() {
			return Snapshot.hex(data, known);
		}
	}

	/**
	 * The state of a load state machine as read.
	 *
	 * @param stateMachine
	 *        The load state machine (1 to 4).
	 * @param state
	 *        The state, <code>null</code> if it could not be read.
	 */
	public record LoadState(int stateMachine, Integer state) {
		// A value.
	}

	private final byte[] _memory = new byte[MEMORY_SIZE];

	private final BitSet _read = new BitSet();

	private final BitSet _known = new BitSet();

	private final Map<String, byte[]> _properties = new LinkedHashMap<>();

	private final Map<Integer, LoadState> _loadStates = new TreeMap<>();

	private final List<String> _notes = new ArrayList<>();

	private Date _time = new Date();

	private String _account;

	private String _busPath;

	private String _file;

	private Integer _address;

	private String _descriptor;

	private String _program;

	private String _programVersion;

	private Integer _addressTable;

	private Integer _associationTable;

	private boolean _complete = true;

	private Integer _abortedAt;

	/**
	 * When the device was read.
	 */
	public Date getTime() {
		return _time;
	}

	/**
	 * @see #getTime()
	 */
	public Snapshot setTime(Date time) {
		_time = time;
		return this;
	}

	/**
	 * The login name of who read the device, <code>null</code> if not known.
	 */
	public String getAccount() {
		return _account;
	}

	/**
	 * @see #getAccount()
	 */
	public Snapshot setAccount(String account) {
		_account = account;
		return this;
	}

	/**
	 * The path to the bus the device was read through, for a person.
	 */
	public String getBusPath() {
		return _busPath;
	}

	/**
	 * @see #getBusPath()
	 */
	public Snapshot setBusPath(String busPath) {
		_busPath = busPath;
		return this;
	}

	/**
	 * The name of the file the snapshot was uploaded from, <code>null</code> for a read-out.
	 */
	public String getFile() {
		return _file;
	}

	/**
	 * @see #getFile()
	 */
	public Snapshot setFile(String file) {
		_file = file;
		return this;
	}

	/**
	 * The individual address the device was read at (area, line and device in 4, 4 and 8 bits).
	 */
	public Integer getAddress() {
		return _address;
	}

	/**
	 * @see #getAddress()
	 */
	public Snapshot setAddress(Integer address) {
		_address = address;
		return this;
	}

	/**
	 * The device descriptor type 0 (the mask version) in hex (<code>0705</code>), <code>null</code>
	 * if it was not read.
	 */
	public String getDescriptor() {
		return _descriptor;
	}

	/**
	 * @see #getDescriptor()
	 */
	public Snapshot setDescriptor(String descriptor) {
		_descriptor = descriptor;
		return this;
	}

	/**
	 * The ID of the application program the device was read for (ETS: <code>M-0002_A-…</code>).
	 */
	public String getProgram() {
		return _program;
	}

	/**
	 * @see #getProgram()
	 */
	public Snapshot setProgram(String program) {
		_program = program;
		return this;
	}

	/**
	 * The program version (identity) the device reports, in hex, <code>null</code> if it was not
	 * read.
	 */
	public String getProgramVersion() {
		return _programVersion;
	}

	/**
	 * @see #getProgramVersion()
	 */
	public Snapshot setProgramVersion(String programVersion) {
		_programVersion = programVersion;
		return this;
	}

	/**
	 * The address of the group address table in the device, <code>null</code> if the program has
	 * none.
	 */
	public Integer getAddressTable() {
		return _addressTable;
	}

	/**
	 * @see #getAddressTable()
	 */
	public Snapshot setAddressTable(Integer addressTable) {
		_addressTable = addressTable;
		return this;
	}

	/**
	 * The address of the association table in the device, <code>null</code> if the program has none.
	 */
	public Integer getAssociationTable() {
		return _associationTable;
	}

	/**
	 * @see #getAssociationTable()
	 */
	public Snapshot setAssociationTable(Integer associationTable) {
		_associationTable = associationTable;
		return this;
	}

	/**
	 * Whether the read-out ran to its end; <code>false</code> if it was cancelled.
	 */
	public boolean isComplete() {
		return _complete;
	}

	/**
	 * @see #isComplete()
	 */
	public Snapshot setComplete(boolean complete) {
		_complete = complete;
		return this;
	}

	/**
	 * The first address not read when the read-out was cancelled in a block, <code>null</code> if
	 * it was not or this is not known (a stored snapshot).
	 */
	public Integer getAbortedAt() {
		return _abortedAt;
	}

	/**
	 * Marks the snapshot as cancelled at the given address (<code>null</code>: between blocks).
	 */
	public Snapshot abort(Integer address) {
		_complete = false;
		_abortedAt = address;
		return this;
	}

	/**
	 * The states of the load state machines read, by load state machine.
	 */
	public List<LoadState> getLoadStates() {
		return new ArrayList<>(_loadStates.values());
	}

	/**
	 * Records the state of a load state machine, <code>null</code> if it could not be read.
	 */
	public Snapshot setLoadState(int stateMachine, Integer state) {
		_loadStates.put(stateMachine, new LoadState(stateMachine, state));
		return this;
	}

	/**
	 * The properties read, by <code>object/property</code> (the index of the interface object).
	 */
	public Map<String, byte[]> getProperties() {
		return _properties;
	}

	/**
	 * Records the value of a property.
	 */
	public Snapshot setProperty(int object, int property, byte[] value) {
		_properties.put(object + "/" + property, value);
		return this;
	}

	/**
	 * What could not be read, for a person (in the language of the reader).
	 */
	public List<String> getNotes() {
		return _notes;
	}

	/**
	 * Adds a note.
	 */
	public Snapshot note(String note) {
		_notes.add(note);
		return this;
	}

	/**
	 * Records bytes read at the given address.
	 */
	public Snapshot put(int address, byte[] data) {
		System.arraycopy(data, 0, _memory, address, data.length);
		_read.set(address, address + data.length);
		_known.set(address, address + data.length);
		return this;
	}

	/**
	 * Records that the given addresses were read but could not be read (unknown).
	 */
	public Snapshot unknown(int address, int length) {
		for (int n = address; n < address + length; n++) {
			if (!_known.get(n)) {
				_read.set(n);
				_memory[n] = 0;
			}
		}
		return this;
	}

	/**
	 * Whether the given address lies in a range read (its byte known or unknown).
	 */
	public boolean isRead(int address) {
		return _read.get(address);
	}

	/**
	 * Whether the byte at the given address was read.
	 */
	public boolean isKnown(int address) {
		return _known.get(address);
	}

	/**
	 * Whether every byte of the given addresses was read.
	 */
	public boolean isKnown(int address, int length) {
		return length <= 0 || _known.nextClearBit(address) >= address + length;
	}

	/**
	 * The byte at the given address, <code>null</code> if it was not read.
	 */
	public Integer byteAt(int address) {
		return _known.get(address) ? Integer.valueOf(_memory[address] & 0xFF) : null;
	}

	/**
	 * A copy of the memory: the bytes read where {@link #known()} is set, 0 elsewhere.
	 */
	public byte[] memory() {
		return _memory.clone();
	}

	/**
	 * A copy of the addresses whose byte was read.
	 */
	public BitSet known() {
		return (BitSet) _known.clone();
	}

	/**
	 * The ranges read, in ascending order.
	 */
	public List<Range> ranges() {
		List<Range> result = new ArrayList<>();
		int start = _read.nextSetBit(0);
		while (start >= 0) {
			int end = _read.nextClearBit(start);
			BitSet known = _known.get(start, end);
			result.add(new Range(start, Arrays.copyOfRange(_memory, start, end), known));
			start = _read.nextSetBit(end);
		}
		return result;
	}

	/**
	 * Records a range in its hex form ({@link Range#hex()}).
	 *
	 * @throws IllegalArgumentException
	 *         If the text is no hex form or the range leaves the address space.
	 */
	public Snapshot putHex(int address, String hex) {
		if (hex.length() % 2 != 0 || address < 0 || address + hex.length() / 2 > MEMORY_SIZE) {
			throw new IllegalArgumentException(String.format("%04X: %s", address, hex));
		}
		for (int n = 0; n < hex.length() / 2; n++) {
			String digits = hex.substring(2 * n, 2 * n + 2);
			if (UNKNOWN.equals(digits)) {
				unknown(address + n, 1);
			} else {
				put(address + n, new byte[] { (byte) Integer.parseInt(digits, 16) });
			}
		}
		return this;
	}

	/**
	 * The bytes in hex, two digits each, {@value #UNKNOWN} where <code>known</code> is not set.
	 */
	public static String hex(byte[] data, BitSet known) {
		StringBuilder result = new StringBuilder(2 * data.length);
		for (int n = 0; n < data.length; n++) {
			if (known.get(n)) {
				result.append(String.format("%02X", data[n] & 0xFF));
			} else {
				result.append(UNKNOWN);
			}
		}
		return result.toString();
	}

	/**
	 * The bytes in hex without separator.
	 */
	public static String hex(byte[] data) {
		return HexFormat.of().withUpperCase().formatHex(data);
	}

	/**
	 * The bytes of a hex text (digits only, any case).
	 *
	 * @throws IllegalArgumentException
	 *         If the text is no hex.
	 */
	public static byte[] parseHex(String hex) {
		return HexFormat.of().parseHex(hex.replace(" ", ""));
	}

	/**
	 * Whether the given snapshot holds the same as this one: identity, load states, memory (bytes
	 * and what is known), properties, table addresses and completeness. Time, account, path, file
	 * and notes are not compared.
	 */
	public boolean sameContent(Snapshot other) {
		if (!_read.equals(other._read) || !_known.equals(other._known)) {
			return false;
		}
		for (int address = _known.nextSetBit(0); address >= 0; address = _known.nextSetBit(address + 1)) {
			if (_memory[address] != other._memory[address]) {
				return false;
			}
		}
		if (_properties.size() != other._properties.size()) {
			return false;
		}
		for (Map.Entry<String, byte[]> entry : _properties.entrySet()) {
			if (!Arrays.equals(entry.getValue(), other._properties.get(entry.getKey()))) {
				return false;
			}
		}
		return java.util.Objects.equals(_address, other._address)
			&& java.util.Objects.equals(_descriptor, other._descriptor)
			&& java.util.Objects.equals(_program, other._program)
			&& java.util.Objects.equals(_programVersion, other._programVersion)
			&& java.util.Objects.equals(_addressTable, other._addressTable)
			&& java.util.Objects.equals(_associationTable, other._associationTable)
			&& _loadStates.equals(other._loadStates)
			&& _complete == other._complete;
	}

}
