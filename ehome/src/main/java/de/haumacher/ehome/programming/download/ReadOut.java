/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * Reads a device into a {@link Snapshot} (haumacher/ehome #193); nothing is written.
 *
 * <p>
 * Reads, in this order: the device descriptor (a device that does not answer it fails the read-out);
 * the program version (the property <code>ApplicationId</code> of the mask, or, BCU 1, the memory of
 * the program's identity); the states of the load state machines (through properties or in memory,
 * {@link LoadStateMachines}); the memory of every block of the download image a download writes, the
 * bytes of its mask, in requests of at most {@link DeviceReader#memoryChunk()} bytes (as the
 * comparison did; haumacher/ehome #196); the group address table and the association table where the device's table
 * pointers name them (property 7 of the interface objects 1 and 2, else where the image has them, as
 * <code>ReadBack</code> reads them), with their lengths from their counts; and the properties the
 * image writes parameters into.
 * </p>
 *
 * <p>
 * A read that fails leaves its bytes unknown, with a note, and the read-out goes on; after
 * {@value #MAX_FAILURES} failures in a row in a block, the rest of the block is unknown (a device that
 * stopped answering costs a timeout per read). The {@link Listener} is told before every read of the
 * memory and may cancel there ({@link AbortExecutionException}, also while a read waits for the
 * device): the snapshot then holds what was read up to then, and is not
 * {@link Snapshot#isComplete() complete}.
 * </p>
 */
public final class ReadOut {

	/** The failed reads in a row after which the rest of a block is not read. */
	public static final int MAX_FAILURES = 3;

	/**
	 * Is told which block is read, and before every read of the memory.
	 */
	public interface Listener {

		/**
		 * The block of the given number (from 1) of the given count is read next.
		 */
		void block(int number, int count, Block block);

		/**
		 * The memory of the given block is read next, at the given address; may end the read-out with
		 * its cancel ({@link AbortExecutionException}).
		 *
		 * @param done
		 *        The bytes read so far.
		 * @param total
		 *        The bytes to read in all (those of the masks of the blocks).
		 */
		default void read(Block block, int address, long done, long total) {
			// Ignored by default.
		}

		/**
		 * Something else than a block is read next (tables, properties), for a person.
		 */
		default void step(ResKey what) {
			// Ignored by default.
		}
	}

	private final Program _program;

	private final DownloadImage _image;

	private final Collection<Block> _skip;

	private final DeviceReader _reader;

	private final Listener _listener;

	private final Snapshot _snapshot = new Snapshot();

	private final Resources _resources = Resources.getInstance();

	private long _done;

	private long _total;

	private ReadOut(Program program, DownloadImage image, Collection<Block> skip, DeviceReader reader,
			Listener listener) {
		_program = program;
		_image = image;
		_skip = skip;
		_reader = reader;
		_listener = listener;
	}

	/**
	 * Reads the device (<code>ehome.physical:Device</code>) with its full download plan.
	 *
	 * @throws BusException
	 *         If the device does not answer the read of its descriptor, or has no program.
	 */
	public static Snapshot read(TLObject device, DeviceReader reader, Listener listener) throws BusException {
		Program program = DeviceConfiguration.program(device);
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		if (program == null || plan.getImage() == null) {
			throw new BusException(I18NConstants.ERROR_NO_PROGRAM);
		}
		return read(program, plan, reader, listener);
	}

	/**
	 * Reads the device of the given plan: the blocks of its image the plan loads.
	 */
	public static Snapshot read(Program program, DownloadPlan plan, DeviceReader reader, Listener listener)
			throws BusException {
		Snapshot result = read(program, plan.getImage(), plan.getUnloadedBlocks(), reader, listener);
		result.setAddress(plan.getIndividualAddress());
		return result;
	}

	/**
	 * Reads the device of the given image.
	 *
	 * @param skip
	 *        The blocks a download does not write, not read.
	 */
	public static Snapshot read(Program program, DownloadImage image, Collection<Block> skip, DeviceReader reader,
			Listener listener) throws BusException {
		return new ReadOut(program, image, skip, reader, listener).run();
	}

	private Snapshot run() throws BusException {
		_snapshot.setProgram(_program == null ? null : _program.getExtId());
		_snapshot.setDescriptor(Snapshot.hex(_reader.readDescriptor()));
		try {
			identity();
			loadStates();
			if (!blocks()) {
				return _snapshot;
			}
			tables();
			properties();
		} catch (AbortExecutionException ex) {
			// Cancelled between the reads of blocks: what was read stands.
			_snapshot.abort(null);
			return _snapshot;
		}
		try {
			_reader.disconnect();
		} catch (BusException ex) {
			// The connection ends anyway when the reader is closed.
		}
		return _snapshot;
	}

	private void note(ResKey note) {
		_snapshot.note(_resources.getString(note));
	}

	/**
	 * Reads what identifies the program in the device (program version or, BCU 1, identity in
	 * memory).
	 */
	private void identity() {
		MaskVersion mask = _program == null ? null : _program.getMask();
		if (mask == null) {
			return;
		}
		for (MaskResource resource : mask.getResources()) {
			if (!"ApplicationId".equals(resource.getName()) || resource.getLocation() == null) {
				continue;
			}
			if (resource.getLocation().getInterfaceObject() != null && resource.getLocation().getPropertyId() != null) {
				int object = resource.getLocation().getInterfaceObject();
				int property = resource.getLocation().getPropertyId();
				try {
					byte[] value = _reader.readProperty(object, property, 1, 1);
					_snapshot.setProgramVersion(Snapshot.hex(value));
					_snapshot.setProperty(object, property, value);
				} catch (BusException ex) {
					note(I18NConstants.READOUT_PROGRAM_VERSION_FAILED__REASON.fill(ex.getErrorKey()));
				}
			} else if (resource.getLocation().getAddressSpace() != null
				&& "StandardMemory".equals(resource.getLocation().getAddressSpace().getName())
				&& resource.getLocation().getStartAddress() != null && resource.getLength() != null) {
				int address = resource.getLocation().getStartAddress().intValue();
				int length = resource.getLength().intValue();
				try {
					byte[] value = _reader.readMemory(address, length);
					_snapshot.put(address, value);
					_snapshot.setProgramVersion(Snapshot.hex(value));
				} catch (BusException ex) {
					_snapshot.unknown(address, length);
					note(I18NConstants.READOUT_PROGRAM_VERSION_FAILED__REASON.fill(ex.getErrorKey()));
				}
			}
		}
	}

	/**
	 * Reads the states of the load state machines: through properties the load state machines 1 to 3,
	 * in memory every one the mask names.
	 */
	private void loadStates() {
		MaskVersion mask = _program == null ? null : _program.getMask();
		if (mask == null) {
			return;
		}
		if (LoadStateMachines.propertyMapped(mask)) {
			for (int lsm = 1; lsm <= 3; lsm++) {
				try {
					byte[] value = _reader.readProperty(lsm, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1);
					_snapshot.setLoadState(lsm, value.length == 0 ? null : Integer.valueOf(value[0] & 0xFF));
				} catch (BusException ex) {
					_snapshot.setLoadState(lsm, null);
					note(I18NConstants.READOUT_LOAD_STATE_FAILED__LSM_REASON.fill(lsm, ex.getErrorKey()));
				}
			}
			return;
		}
		for (LoadStateMachines.InMemory lsm : LoadStateMachines.inMemory(mask)) {
			try {
				byte[] value = _reader.readMemory(lsm.status(), 1);
				_snapshot.setLoadState(lsm.stateMachine(), value.length == 0 ? null : Integer.valueOf(value[0] & 0xFF));
			} catch (BusException ex) {
				_snapshot.setLoadState(lsm.stateMachine(), null);
				note(I18NConstants.READOUT_LOAD_STATE_FAILED__LSM_REASON.fill(lsm.stateMachine(), ex.getErrorKey()));
			}
		}
	}

	/**
	 * Reads the blocks of the image.
	 *
	 * @return Whether every block was read; <code>false</code> if the read-out was cancelled.
	 */
	private boolean blocks() {
		List<Block> blocks = new ArrayList<>(_image.getBlocks());
		blocks.removeAll(_skip);
		_total = 0;
		for (Block block : blocks) {
			if (block.stateMachine() != null) {
				continue;
			}
			for (byte bits : block.mask()) {
				if (bits != 0) {
					_total++;
				}
			}
		}
		int number = 1;
		for (Block block : blocks) {
			_listener.block(number++, blocks.size(), block);
			if (block.stateMachine() != null) {
				note(I18NConstants.READOUT_RELATIVE_SEGMENT__BLOCK.fill(block.label()));
				continue;
			}
			if (!block(block)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Reads the bytes of the mask of a block.
	 *
	 * @return Whether the block was read to its end; <code>false</code> if the read-out was
	 *         cancelled.
	 */
	private boolean block(Block block) {
		byte[] mask = block.mask();
		int failures = 0;
		int n = 0;
		while (n < mask.length) {
			if (mask[n] == 0) {
				n++;
				continue;
			}
			int start = n;
			while (n < mask.length && mask[n] != 0 && n - start < _reader.memoryChunk()) {
				n++;
			}
			int address = block.address() + start;
			int length = n - start;
			if (failures >= MAX_FAILURES) {
				_snapshot.unknown(address, length);
				_done += length;
				continue;
			}
			try {
				_listener.read(block, address, _done, _total);
				byte[] data = _reader.readMemory(address, length);
				if (data.length != length) {
					throw new BusException(I18NConstants.READOUT_SHORT_ANSWER__ADDRESS_COUNT_EXPECTED.fill(
						String.format("%04X", address), data.length, length));
				}
				_snapshot.put(address, data);
				failures = 0;
			} catch (BusException ex) {
				_snapshot.unknown(address, length);
				note(I18NConstants.READOUT_MEMORY_FAILED__RANGE_REASON.fill(range(address, length), ex.getErrorKey()));
				failures++;
				if (failures == MAX_FAILURES) {
					note(I18NConstants.READOUT_BLOCK_GIVEN_UP__BLOCK_ADDRESS.fill(block.label(),
						String.format("%04X", address + length)));
				}
			} catch (AbortExecutionException ex) {
				_snapshot.abort(Integer.valueOf(address));
				return false;
			}
			_done += length;
		}
		return true;
	}

	private static String range(int address, int length) {
		return length == 1 ? String.format("%04X", address) : String.format("%04X-%04X", address, address + length - 1);
	}

	/**
	 * Reads the group address table and the association table where the device has them.
	 */
	private void tables() {
		Block addressBlock = _image.getAddressTable();
		Block associationBlock = _image.getAssociationTable();
		if (_program == null || (addressBlock == null && associationBlock == null)) {
			return;
		}
		boolean propertyMapped = _program.getMask() != null && LoadStateMachines.propertyMapped(_program.getMask());
		_listener.step(de.haumacher.ehome.programming.I18NConstants.BLOCK_ADDRESS_TABLE);
		Integer addressTable = null;
		if (addressBlock != null) {
			// A table in a segment of its own is where its load state machine's table reference says; one
			// within a segment holding more (BCU 1, BCU 2 loaded by the mask's procedure) at its place there.
			addressTable = propertyMapped && _image.getBlocks().contains(addressBlock) ? tablePointer(1) : null;
			if (addressTable == null) {
				addressTable = Integer.valueOf(addressBlock.address());
			}
			table(addressTable.intValue());
		}
		_listener.step(de.haumacher.ehome.programming.I18NConstants.BLOCK_ASSOCIATION_TABLE);
		Integer associationTable = null;
		if (associationBlock != null) {
			associationTable = propertyMapped && _image.getBlocks().contains(associationBlock) ? tablePointer(2) : null;
			if (associationTable == null) {
				// A mask keeping the pointer in memory (BCU 1): the place the device names, read with the
				// segment holding the pointer (haumacher/ehome #222).
				associationTable = LoadStateMachines.associationTablePointer(_program, _snapshot.memory(), _snapshot.known());
			}
			if (associationTable == null) {
				boolean separate = _image.getBlocks().contains(associationBlock);
				Integer count = addressTable == null ? null : _snapshot.byteAt(addressTable.intValue());
				if (_program.getDynamicTableManagement() && separate && count != null) {
					associationTable = Integer.valueOf(addressTable.intValue() + 1 + 2 * count.intValue()
						+ DownloadImage.tableChecksum(_program));
				} else {
					associationTable = Integer.valueOf(associationBlock.address());
				}
			}
			table(associationTable.intValue());
		}
		_snapshot.setAddressTable(addressTable);
		_snapshot.setAssociationTable(associationTable);
	}

	private Integer tablePointer(int object) {
		try {
			byte[] value = _reader.readProperty(object, LoadControls.PID_TABLE_REFERENCE, 1, 1);
			if (value.length == 2 || value.length == 4) {
				_snapshot.setProperty(object, LoadControls.PID_TABLE_REFERENCE, value);
				return Integer.valueOf(new BigInteger(1, value).intValue());
			}
			note(I18NConstants.READOUT_TABLE_POINTER_LENGTH__OBJECT_LENGTH.fill(object, value.length));
		} catch (BusException ex) {
			note(I18NConstants.READOUT_TABLE_POINTER_FAILED__OBJECT_REASON.fill(object, ex.getErrorKey()));
		}
		return null;
	}

	/**
	 * Reads a table: its count (first byte), then two bytes per entry, where they are not read yet.
	 */
	private void table(int address) {
		if (!readMissing(address, 1)) {
			return;
		}
		int count = _snapshot.byteAt(address).intValue();
		readMissing(address + 1, 2 * count);
	}

	/**
	 * Reads the bytes of the given addresses not known yet.
	 *
	 * @return Whether all of them are known afterwards.
	 */
	private boolean readMissing(int address, int length) {
		int end = Math.min(address + length, Snapshot.MEMORY_SIZE);
		int position = address;
		boolean complete = true;
		while (position < end) {
			if (_snapshot.isKnown(position)) {
				position++;
				continue;
			}
			int start = position;
			while (position < end && !_snapshot.isKnown(position) && position - start < _reader.memoryChunk()) {
				position++;
			}
			try {
				byte[] data = _reader.readMemory(start, position - start);
				if (data.length != position - start) {
					throw new BusException(I18NConstants.READOUT_SHORT_ANSWER__ADDRESS_COUNT_EXPECTED.fill(
						String.format("%04X", start), data.length, position - start));
				}
				_snapshot.put(start, data);
			} catch (BusException ex) {
				_snapshot.unknown(start, position - start);
				note(I18NConstants.READOUT_MEMORY_FAILED__RANGE_REASON.fill(range(start, position - start),
					ex.getErrorKey()));
				complete = false;
			}
		}
		return complete;
	}

	/**
	 * Reads the properties the image writes parameters into.
	 */
	private void properties() {
		for (DownloadImage.PropertyWrite write : _image.getPropertyWrites()) {
			_listener.step(I18NConstants.READOUT_PROPERTY__OBJECT_PROPERTY.fill(
				write.objectIndex() == null ? "?" : write.objectIndex().toString(), write.propertyId()));
			if (write.objectIndex() == null) {
				note(I18NConstants.READOUT_PROPERTY_BY_TYPE__PROPERTY_TYPE.fill(write.propertyId(), write.objectType()));
				continue;
			}
			try {
				_snapshot.setProperty(write.objectIndex(), write.propertyId(),
					_reader.readProperty(write.objectIndex(), write.propertyId(), 1, 1));
			} catch (BusException ex) {
				note(I18NConstants.READOUT_PROPERTY_FAILED__OBJECT_PROPERTY_REASON.fill(write.objectIndex(),
					write.propertyId(), ex.getErrorKey()));
			}
		}
	}

}
