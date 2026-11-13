/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.programming.download.DownloadPlan;

/**
 * The bytes ETS writes into a device: the memory of its program's segments with the parameter
 * values patched in, the group address and association tables, the object table, and the values
 * of the parameters stored in properties of interface objects.
 *
 * <p>
 * Computed from the project data alone by {@link #compute(TLObject)}; what cannot be computed is
 * listed in {@link #getProblems()} instead of being guessed. {@link #dump()} shows the image for a
 * person, grouped by memory block with addresses, in the form of the device read-backs in
 * <code>src/test/fixtures/readback/</code>.
 * </p>
 */
public class DownloadImage {

	/**
	 * A range of memory to be written.
	 *
	 * @param label
	 *        What the block holds, for a person.
	 * @param address
	 *        The address of the first byte; for a block of a relative segment (System B), the offset
	 *        within the memory of its load state machine.
	 * @param stateMachine
	 *        The load state machine of a relative segment, <code>null</code> for absolute memory.
	 * @param data
	 *        The bytes.
	 * @param mask
	 *        Per byte, the bits that are written (ETS: <code>Mask</code> of the segment); a bit not
	 *        set keeps the device's content.
	 */
	public record Block(ResKey label, int address, Integer stateMachine, byte[] data, byte[] mask) {

		/**
		 * The {@link #label()} in English, for the log and the dump of the image.
		 */
		public String name() {
			return DownloadPlan.english(label);
		}

		/**
		 * The address after the last byte.
		 */
		public int end() {
			return address + data.length;
		}

		/**
		 * Whether the block contains the given address.
		 */
		public boolean contains(int memoryAddress) {
			return memoryAddress >= address && memoryAddress < end();
		}
	}

	/**
	 * A parameter value stored in a property of an interface object.
	 *
	 * @param objectIndex
	 *        The index of the interface object, <code>null</code> if it is given by type.
	 * @param objectType
	 *        The type of the interface object, <code>null</code> if it is given by index.
	 * @param occurrence
	 *        Which object of the type, <code>null</code> for the first.
	 * @param propertyId
	 *        The property.
	 * @param value
	 *        The bytes of the property value carrying the parameters.
	 */
	public record PropertyWrite(Integer objectIndex, Integer objectType, Integer occurrence, int propertyId, byte[] value) {
		// Value.
	}

	private final String _device;

	private final List<Block> _segments = new ArrayList<>();

	private Block _addressTable;

	private Block _associationTable;

	private Block _objectTable;

	private final List<PropertyWrite> _properties = new ArrayList<>();

	private final List<ResKey> _problems = new ArrayList<>();

	DownloadImage(String device) {
		_device = device;
	}

	/**
	 * Computes the download image of the given device (<code>ehome.physical:Device</code>).
	 */
	public static DownloadImage compute(TLObject device) {
		return new DownloadImageBuilder(device).build();
	}

	/**
	 * Computes the image of the device of the given configuration, which must describe the device's
	 * current settings (saves walking the program's structure again).
	 */
	public static DownloadImage compute(DeviceConfiguration configuration) {
		return new DownloadImageBuilder(configuration).build();
	}

	/**
	 * The individual address of a device (<code>ehome.physical:Device</code>): area, line and device
	 * number in 4, 4 and 8 bits; <code>null</code> if one of them is missing.
	 */
	public static Integer individualAddress(TLObject device) {
		return DownloadImageBuilder.individualAddress(device);
	}

	/**
	 * The device the image is for (individual address and ID), for a person.
	 */
	public String getDevice() {
		return _device;
	}

	/**
	 * The memory of the program's segments that carry static data, with the parameter values and the
	 * object table patched in; the segments of the address and association table are not among them
	 * (ETS writes the tables, not the template the program has for them), unless they hold more than
	 * the tables (BCU 1), then the tables are patched in as well.
	 */
	public List<Block> getSegments() {
		return Collections.unmodifiableList(_segments);
	}

	/**
	 * The group address table, <code>null</code> if the device has none.
	 */
	public Block getAddressTable() {
		return _addressTable;
	}

	/**
	 * The association table, <code>null</code> if the device has none.
	 */
	public Block getAssociationTable() {
		return _associationTable;
	}

	/**
	 * The communication object table, a part of one of the {@link #getSegments() segments};
	 * <code>null</code> if the program has none.
	 */
	public Block getObjectTable() {
		return _objectTable;
	}

	/**
	 * Everything to be written into memory, by address: the segments and the tables. A table lying
	 * within a segment (BCU 1: the EEPROM holds the tables with the parameters) is part of that
	 * segment's block, like the object table.
	 */
	public List<Block> getBlocks() {
		List<Block> result = new ArrayList<>(_segments);
		for (Block table : Arrays.asList(_addressTable, _associationTable)) {
			if (table != null && !inSegment(table)) {
				result.add(table);
			}
		}
		result.sort(Comparator.comparing((Block b) -> b.stateMachine() == null ? -1 : b.stateMachine().intValue())
			.thenComparingInt(Block::address));
		return result;
	}

	private boolean inSegment(Block table) {
		for (Block segment : _segments) {
			if (segment.stateMachine() == null && table.stateMachine() == null && segment.address() <= table.address()
				&& table.end() <= segment.end()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The parameter values to be written into properties.
	 */
	public List<PropertyWrite> getPropertyWrites() {
		return Collections.unmodifiableList(_properties);
	}

	/**
	 * What could not be computed; empty if the image is complete.
	 */
	public List<ResKey> getProblems() {
		return Collections.unmodifiableList(_problems);
	}

	/**
	 * The byte the image writes to the given absolute address, <code>null</code> if it writes none
	 * there.
	 */
	public Integer byteAt(int address) {
		for (Block block : getBlocks()) {
			if (block.stateMachine() == null && block.contains(address)) {
				return Integer.valueOf(block.data()[address - block.address()] & 0xFF);
			}
		}
		return null;
	}

	void addSegment(Block block) {
		_segments.add(block);
	}

	void setAddressTable(Block block) {
		_addressTable = block;
	}

	void setAssociationTable(Block block) {
		_associationTable = block;
	}

	void setObjectTable(Block block) {
		_objectTable = block;
	}

	void addPropertyWrite(PropertyWrite write) {
		_properties.add(write);
	}

	void addProblem(ResKey problem) {
		_problems.add(problem);
	}

	/**
	 * The image as text in English, for the log and the tests, see {@link #dump(Resources)}.
	 */
	public String dump() {
		return dump(Resources.getInstance(Locale.ENGLISH));
	}

	/**
	 * The image as text in the language of the given resources: per block a header and the bytes, 16
	 * per line with their address; a byte that is not written at all (mask 0) shows as
	 * <code>--</code>.
	 */
	public String dump(Resources resources) {
		StringBuilder out = new StringBuilder();
		out.append(resources.getString(I18NConstants.DUMP_TITLE__DEVICE.fill(_device))).append('\n');
		for (Block block : getBlocks()) {
			String from = String.format("%04X", block.address());
			String to = String.format("%04X", block.end() - 1);
			out.append('\n').append(resources.getString(block.stateMachine() == null
				? I18NConstants.DUMP_BLOCK__BLOCK_FROM_TO_LENGTH.fill(block.label(), from, to, block.data().length)
				: I18NConstants.DUMP_RELATIVE_BLOCK__BLOCK_LSM_FROM_TO_LENGTH.fill(block.label(), block.stateMachine(), from,
					to, block.data().length)));
			for (Block table : Arrays.asList(_addressTable, _associationTable, _objectTable)) {
				if (table != null && block != table && block.contains(table.address()) && block.stateMachine() == null) {
					out.append(resources.getString(I18NConstants.DUMP_CONTAINED_TABLE__TABLE_ADDRESS_LENGTH.fill(
						table.label(), String.format("%04X", table.address()), table.data().length)));
				}
			}
			out.append('\n');
			dumpBytes(out, block);
		}
		if (!_properties.isEmpty()) {
			out.append('\n').append(resources.getString(I18NConstants.DUMP_PROPERTIES)).append('\n');
			for (PropertyWrite write : _properties) {
				ResKey header;
				if (write.objectIndex() != null) {
					header = I18NConstants.DUMP_PROPERTY__OBJECT_PROPERTY.fill(write.objectIndex(), write.propertyId());
				} else if (write.occurrence() != null) {
					header = I18NConstants.DUMP_PROPERTY_OF_TYPE_OCCURRENCE__TYPE_OCCURRENCE_PROPERTY.fill(write.objectType(),
						write.occurrence(), write.propertyId());
				} else {
					header = I18NConstants.DUMP_PROPERTY_OF_TYPE__TYPE_PROPERTY.fill(write.objectType(), write.propertyId());
				}
				out.append(resources.getString(header));
				for (byte b : write.value()) {
					out.append(String.format(" %02X", b & 0xFF));
				}
				out.append('\n');
			}
		}
		if (!_problems.isEmpty()) {
			out.append('\n').append(resources.getString(I18NConstants.DUMP_PROBLEMS)).append('\n');
			for (ResKey problem : _problems) {
				out.append("- ").append(resources.getString(problem)).append('\n');
			}
		}
		return out.toString();
	}

	private static void dumpBytes(StringBuilder out, Block block) {
		byte[] data = block.data();
		for (int offset = 0; offset < data.length; offset += 16) {
			out.append(String.format("%04X:", block.address() + offset));
			for (int n = offset; n < Math.min(data.length, offset + 16); n++) {
				if (block.mask() != null && block.mask()[n] == 0) {
					out.append(" --");
				} else {
					out.append(String.format(" %02X", data[n] & 0xFF));
				}
			}
			out.append('\n');
		}
	}

	@Override
	public String toString() {
		return dump();
	}

}
