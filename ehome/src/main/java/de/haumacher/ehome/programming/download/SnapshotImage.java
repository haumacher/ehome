/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.DownloadImage.PropertyWrite;

/**
 * The download image of the restore of a {@link Snapshot} (haumacher/ehome #194): the image of the
 * device computed from the project, whose bytes are replaced by the snapshot's.
 *
 * <p>
 * The image gives the structure (the blocks with their addresses, labels and masks, the object
 * table, the properties carrying parameters); the bytes come from the snapshot:
 * </p>
 * <ul>
 * <li>A block of the image keeps its place; its bytes are the snapshot's.</li>
 * <li>A table in a segment of its own (group address table, association table) lies where the
 * snapshot found it ({@link Snapshot#getAddressTable()}, {@link Snapshot#getAssociationTable()}) and
 * has the length its count in the snapshot gives (one byte, two per entry), so that the load procedure
 * allocates the snapshot's tables (dynamic table management) instead of the project's. A table within
 * a segment (BCU 1) is part of that segment's bytes.</li>
 * <li>A property carrying parameters has the value the snapshot read.</li>
 * </ul>
 *
 * <p>
 * A byte the snapshot does not hold (not read, or unknown) keeps the project's value and is recorded in
 * {@link #unknown()}. A byte outside the mask of its block (never written) is the project's, what a
 * download expects the device to hold there (the CRC of a download stamp covers it). A property the
 * snapshot does not hold keeps the project's value and is recorded in {@link #unknownProperties()}.
 * The {@link DownloadPlanner} refuses the restore if its plan writes one of them, so every byte and
 * property the restore writes is the snapshot's. A table the snapshot does not hold is a
 * {@link #problems() problem}.
 * </p>
 *
 * <p>
 * Restored into a device of another individual address than the snapshot was read at (the user's
 * explicit choice), the first entry of the group address table, the individual address, is the
 * target device's.
 * </p>
 */
public final class SnapshotImage {

	private final Snapshot _snapshot;

	private final Integer _target;

	private final BitSet _unknown = new BitSet();

	private final Set<List<Integer>> _unknownProperties = new HashSet<>();

	private final List<ResKey> _problems = new ArrayList<>();

	private DownloadImage _image;

	private SnapshotImage(Snapshot snapshot, Integer target) {
		_snapshot = snapshot;
		_target = target;
	}

	/**
	 * The image of the restore of the given snapshot.
	 *
	 * @param image
	 *        The download image of the device computed from the project.
	 * @param snapshot
	 *        The snapshot to restore.
	 * @param target
	 *        The individual address of the device restored into.
	 */
	public static SnapshotImage of(DownloadImage image, Snapshot snapshot, Integer target) {
		SnapshotImage result = new SnapshotImage(snapshot, target);
		result.build(image);
		return result;
	}

	/**
	 * The image whose bytes are the snapshot's.
	 */
	public DownloadImage image() {
		return _image;
	}

	/**
	 * The addresses of bytes of the {@link #image()} (under its masks) the snapshot does not hold.
	 */
	public BitSet unknown() {
		return _unknown;
	}

	/**
	 * The properties (<code>[object, property]</code>) of the image the snapshot does not hold.
	 */
	public Set<List<Integer>> unknownProperties() {
		return _unknownProperties;
	}

	/**
	 * What the snapshot lacks beyond single bytes (a table).
	 */
	public List<ResKey> problems() {
		return _problems;
	}

	private void build(DownloadImage image) {
		List<Block> separate = image.getBlocks();
		List<Block> segments = new ArrayList<>();
		for (Block segment : image.getSegments()) {
			segments.add(copy(segment));
		}
		Block addressTable = table(image.getAddressTable(), separate, _snapshot.getAddressTable());
		if (addressTable != null && separate.contains(image.getAddressTable())) {
			addressTable = individualAddress(addressTable);
		}
		Block associationTable = table(image.getAssociationTable(), separate, _snapshot.getAssociationTable());
		Block objectTable = image.getObjectTable() == null ? null : copy(image.getObjectTable());
		List<PropertyWrite> properties = new ArrayList<>();
		for (PropertyWrite write : image.getPropertyWrites()) {
			byte[] value = write.objectIndex() == null ? null
				: _snapshot.getProperties().get(write.objectIndex() + "/" + write.propertyId());
			if (value == null) {
				if (write.objectIndex() != null) {
					_unknownProperties.add(List.of(write.objectIndex(), write.propertyId()));
				}
				properties.add(write);
			} else {
				properties.add(new PropertyWrite(write.objectIndex(), write.objectType(), write.occurrence(),
					write.propertyId(), value.clone()));
			}
		}
		_image = DownloadImage.of(image.getDevice(), segments, addressTable, associationTable, objectTable, properties);
	}

	/**
	 * The block with the snapshot's bytes; a block of a relative segment (System B) as it is (the
	 * snapshot holds none, the plan refuses it).
	 */
	private Block copy(Block block) {
		if (block.stateMachine() != null) {
			return block;
		}
		return new Block(block.label(), block.address(), null, bytes(block.address(), block.mask(), block.data()),
			block.mask());
	}

	/**
	 * The bytes of the snapshot at the given addresses under the mask. A byte the snapshot does not hold
	 * keeps the image's and is {@link #unknown()} (the plan refuses to write it). Outside the mask (never
	 * written) the image's byte stands, what a download expects the device to hold there (the CRC of a
	 * download stamp covers it), even where the snapshot read the device's (the individual address in
	 * the group address table).
	 *
	 * @param image
	 *        The image's bytes from the given address on (shorter than the mask for a table the snapshot
	 *        has longer: 0 beyond).
	 */
	private byte[] bytes(int address, byte[] mask, byte[] image) {
		byte[] result = new byte[mask.length];
		for (int n = 0; n < mask.length; n++) {
			Integer value = mask[n] != 0 && address + n < Snapshot.MEMORY_SIZE ? _snapshot.byteAt(address + n) : null;
			if (value != null) {
				result[n] = value.byteValue();
			} else {
				result[n] = n < image.length ? image[n] : 0;
				if (mask[n] != 0) {
					_unknown.set(address + n);
				}
			}
		}
		return result;
	}

	/**
	 * A table of the image: in a segment of its own, where the snapshot has it with its length; within a
	 * segment, at its place.
	 *
	 * @param separate
	 *        The blocks of the image written on their own ({@link DownloadImage#getBlocks()}).
	 * @param address
	 *        Where the snapshot found the table.
	 */
	private Block table(Block table, List<Block> separate, Integer address) {
		if (table == null) {
			return null;
		}
		if (!separate.contains(table)) {
			return copy(table);
		}
		Integer count = address == null ? null : _snapshot.byteAt(address.intValue());
		if (count == null) {
			_problems.add(I18NConstants.RESTORE_NO_TABLE__TABLE.fill(table.label()));
			return table;
		}
		int length = 1 + 2 * count.intValue();
		byte[] mask = new byte[length];
		for (int n = 0; n < length; n++) {
			mask[n] = n < table.mask().length ? table.mask()[n] : (byte) 0xFF;
		}
		return new Block(table.label(), address.intValue(), null, bytes(address.intValue(), mask, table.data()), mask);
	}

	/**
	 * The group address table with the target's individual address as its first entry, where the
	 * snapshot's table holds the address the snapshot was read at and the target has another one.
	 */
	private Block individualAddress(Block table) {
		Integer source = _snapshot.getAddress();
		byte[] data = table.data();
		if (_target == null || source == null || Objects.equals(source, _target) || data.length < 3
			|| _unknown.get(table.address() + 1, table.address() + 3).cardinality() > 0
			|| ((data[1] & 0xFF) << 8 | (data[2] & 0xFF)) != source.intValue()) {
			return table;
		}
		byte[] patched = Arrays.copyOf(data, data.length);
		patched[1] = (byte) (_target.intValue() >>> 8);
		patched[2] = (byte) _target.intValue();
		return new Block(table.label(), table.address(), null, patched, table.mask());
	}

}
