/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import de.haumacher.ehome.model.operational.MaskFeature;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * Compares a device with its download image without writing anything: reads the device descriptor,
 * the program version and the load states, and the memory of every block of the image (only the
 * bytes of its mask), and reports the differences.
 */
public class DeviceComparison {

	/**
	 * The comparison of one block of the image with the device's memory.
	 *
	 * @param name
	 *        The block.
	 * @param address
	 *        Its first address.
	 * @param length
	 *        Its length.
	 * @param compared
	 *        The number of bytes compared (those of the block's mask).
	 * @param different
	 *        The addresses at which the device differs from the image, in ascending order.
	 * @param failure
	 *        Why the block could not be read completely, <code>null</code> if it could.
	 */
	public record BlockResult(String name, int address, int length, int compared, List<Integer> different, String failure) {

		/**
		 * Whether the device holds the image in this block.
		 */
		public boolean identical() {
			return failure == null && different.isEmpty();
		}

		/**
		 * The differing addresses as ranges (e.g. <code>4023-4025, 4100</code>).
		 */
		public String ranges() {
			return DeviceComparison.ranges(different);
		}
	}

	/**
	 * The outcome of a comparison.
	 *
	 * @param descriptor
	 *        The device descriptor (mask version) read, or why it could not be read.
	 * @param facts
	 *        Further things read from the device, for a person (program version, load states).
	 * @param blocks
	 *        The comparison of each block of the image.
	 */
	public record Result(String descriptor, List<String> facts, List<BlockResult> blocks) {

		/**
		 * The number of blocks the device holds identically.
		 */
		public int identicalBlocks() {
			return (int) blocks.stream().filter(BlockResult::identical).count();
		}
	}

	/**
	 * Is told which block is read.
	 */
	public interface Listener {

		/**
		 * The block of the given number (from 1) of the given count is read next.
		 */
		void block(int number, int count, Block block);
	}

	/**
	 * Compares the device with the image.
	 *
	 * @param program
	 *        The program of the device, for the expected program version and the mask.
	 * @param image
	 *        The download image of the device.
	 * @param skip
	 *        The blocks not to compare, because a download does not write them
	 *        ({@link DownloadPlan#getUnloadedBlocks()}).
	 * @param reader
	 *        The access to the device.
	 */
	public static Result compare(Program program, DownloadImage image, Collection<Block> skip, DeviceReader reader,
			Listener listener)
			throws BusException {
		String descriptor = Operation.hex(reader.readDescriptor());
		List<String> facts = new ArrayList<>();
		MaskVersion mask = program == null ? null : program.getMask();
		if (mask != null) {
			facts.add(String.format("Mask version of the program: %04X (%s)", mask.getNumber(), mask.getName()));
			for (MaskResource resource : mask.getResources()) {
				if ("ApplicationId".equals(resource.getName()) && resource.getLocation() != null
					&& resource.getLocation().getInterfaceObject() != null && resource.getLocation().getPropertyId() != null) {
					facts.add("Program version: " + property(reader, resource.getLocation().getInterfaceObject(),
						resource.getLocation().getPropertyId()) + ", the program's: "
						+ Operation.hex(DownloadPlanner.applicationId(program)));
				}
			}
			if (propertyMapped(mask)) {
				for (int lsm = 1; lsm <= 3; lsm++) {
					String state;
					try {
						byte[] value = reader.readProperty(lsm, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1);
						state = value.length == 0 ? "no answer" : LoadControls.stateName(value[0] & 0xFF);
					} catch (BusException ex) {
						state = ex.getMessage();
					}
					facts.add("Load state machine " + lsm + ": " + state);
				}
			}
		}

		List<Block> blocks = new ArrayList<>(image.getBlocks());
		blocks.removeAll(skip);
		List<BlockResult> results = new ArrayList<>();
		int number = 1;
		for (Block block : blocks) {
			listener.block(number++, blocks.size(), block);
			results.add(compare(block, reader));
		}
		return new Result(descriptor, facts, results);
	}

	private static String property(DeviceReader reader, int object, int property) {
		try {
			return Operation.hex(reader.readProperty(object, property, 1, 1));
		} catch (BusException ex) {
			return "not readable (" + ex.getMessage() + ")";
		}
	}

	private static boolean propertyMapped(MaskVersion mask) {
		for (MaskFeature feature : mask.getFeatures()) {
			if ("PropertyMappedLsms".equals(feature.getName()) && Objects.equals(feature.getValue(), 1)) {
				return true;
			}
		}
		return false;
	}

	private static BlockResult compare(Block block, DeviceReader reader) {
		byte[] data = block.data();
		byte[] mask = block.mask();
		List<Integer> different = new ArrayList<>();
		int compared = 0;
		if (block.stateMachine() != null) {
			return new BlockResult(block.name(), block.address(), data.length, 0, different,
				"a relative segment, its place in the device is not known");
		}
		int n = 0;
		while (n < data.length) {
			if (mask[n] == 0) {
				n++;
				continue;
			}
			int start = n;
			while (n < data.length && mask[n] != 0 && n - start < DownloadPlanner.MAX_CHUNK) {
				n++;
			}
			byte[] read;
			try {
				read = reader.readMemory(block.address() + start, n - start);
			} catch (BusException ex) {
				return new BlockResult(block.name(), block.address(), data.length, compared, different,
					String.format("reading %04X failed: %s", block.address() + start, ex.getMessage()));
			}
			for (int k = start; k < n; k++) {
				compared++;
				int bits = mask[k] & 0xFF;
				if (k - start >= read.length || ((read[k - start] ^ data[k]) & bits) != 0) {
					different.add(block.address() + k);
				}
			}
		}
		return new BlockResult(block.name(), block.address(), data.length, compared, different, null);
	}

	/**
	 * Addresses in ascending order as ranges, hexadecimal.
	 */
	static String ranges(List<Integer> addresses) {
		StringBuilder result = new StringBuilder();
		int n = 0;
		while (n < addresses.size()) {
			int start = addresses.get(n);
			int end = start;
			while (n + 1 < addresses.size() && addresses.get(n + 1) == end + 1) {
				end = addresses.get(++n);
			}
			n++;
			if (result.length() > 0) {
				result.append(", ");
			}
			result.append(String.format("%04X", start));
			if (end > start) {
				result.append(String.format("-%04X", end));
			}
		}
		return result.toString();
	}

}
