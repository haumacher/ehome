/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * Compares a device with its download image without writing anything: reads the device descriptor,
 * the program version and the load states (through properties or in memory, see
 * {@link LoadStateMachines}), and the memory of every block of the image (only the
 * bytes of its mask), and reports the differences.
 *
 * <p>
 * Compared with a {@link DownloadPlan}, the expected memory is what the download leaves: the image,
 * except where the load procedure writes inline data of its own over it (BCU 1: FF into
 * <code>RunError</code> at its end), there the value the plan writes last.
 * </p>
 *
 * <p>
 * The memory is read in requests of at most {@link DownloadPlanner#MAX_CHUNK} bytes; the
 * {@link Listener} is told before every one (haumacher/ehome #190), and may end the comparison there
 * by its cancel ({@link AbortExecutionException}, as the cancel of a job does, also while a read waits
 * for the device). An aborted comparison is no failure: its {@link Result} holds what was compared up
 * to then ({@link Result#aborted()}).
 * </p>
 */
public class DeviceComparison {

	/**
	 * The comparison of one block of the image with the device's memory.
	 *
	 * @param label
	 *        The block, for a person.
	 * @param address
	 *        Its first address.
	 * @param length
	 *        Its length.
	 * @param compared
	 *        The number of bytes compared (those of the block's mask).
	 * @param different
	 *        The addresses at which the device differs from the image, in ascending order.
	 * @param failureKey
	 *        Why the block could not be read completely, <code>null</code> if it could.
	 * @param abortedAt
	 *        The address at which the comparison was aborted in this block (the first address not
	 *        compared), <code>null</code> if it was not.
	 */
	public record BlockResult(ResKey label, int address, int length, int compared, List<Integer> different,
			ResKey failureKey, Integer abortedAt) {

		/**
		 * The {@link #label()} in English, for the log.
		 */
		public String name() {
			return DownloadPlan.english(label);
		}

		/**
		 * The {@link #failureKey()} in English, for the log; <code>null</code> if the block was read
		 * completely.
		 */
		public String failure() {
			return failureKey == null ? null : DownloadPlan.english(failureKey);
		}

		/**
		 * Whether the device holds the image in this block.
		 */
		public boolean identical() {
			return failureKey == null && abortedAt == null && different.isEmpty();
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
	 * @param factKeys
	 *        Further things read from the device, for a person (program version, load states).
	 * @param blocks
	 *        The comparison of each block of the image; of an aborted comparison the blocks compared
	 *        before and the block it was aborted in, up to the address of the abort.
	 * @param aborted
	 *        Whether the comparison was aborted (its cancel) before it compared every block.
	 */
	public record Result(String descriptor, List<ResKey> factKeys, List<BlockResult> blocks, boolean aborted) {

		/**
		 * The {@link #factKeys()} in English, for the log.
		 */
		public List<String> facts() {
			return factKeys.stream().map(DownloadPlan::english).toList();
		}

		/**
		 * The number of blocks the device holds identically.
		 */
		public int identicalBlocks() {
			return (int) blocks.stream().filter(BlockResult::identical).count();
		}
	}

	/**
	 * Is told which block is read, and before every read of the memory.
	 */
	public interface Listener {

		/**
		 * The block of the given number (from 1) of the given count is read next.
		 */
		void block(int number, int count, Block block);

		/**
		 * The memory of the given block is read next, at the given address; may end the comparison
		 * with its cancel ({@link AbortExecutionException}).
		 *
		 * @param done
		 *        The bytes compared so far.
		 * @param total
		 *        The bytes to compare in all (those of the masks of the blocks compared).
		 */
		default void read(Block block, int address, long done, long total) {
			// Ignored by default.
		}
	}

	/**
	 * How far the comparison of the blocks has come.
	 */
	private static final class Progress {

		final long _total;

		long _done;

		boolean _aborted;

		Progress(long total) {
			_total = total;
		}
	}

	/**
	 * Compares the device with the memory the given download plan leaves in it: the image of the
	 * plan, where the plan writes over it the value it writes last, without the blocks the plan
	 * does not load.
	 *
	 * @param program
	 *        The program of the device, for the expected program version and the mask.
	 * @param plan
	 *        The full download plan of the device.
	 * @param reader
	 *        The access to the device.
	 */
	public static Result compare(Program program, DownloadPlan plan, DeviceReader reader, Listener listener)
			throws BusException {
		Map<Integer, Byte> written = new HashMap<>();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.WriteMemory write) {
				byte[] data = write.data();
				for (int n = 0; n < data.length; n++) {
					written.put(write.address() + n, data[n]);
				}
			}
		}
		return compare(program, plan.getImage(), plan.getUnloadedBlocks(), written, reader, listener);
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
		return compare(program, image, skip, Map.of(), reader, listener);
	}

	private static Result compare(Program program, DownloadImage image, Collection<Block> skip,
			Map<Integer, Byte> written, DeviceReader reader, Listener listener)
			throws BusException {
		String descriptor = null;
		List<ResKey> facts = new ArrayList<>();
		List<BlockResult> results = new ArrayList<>();
		try {
			descriptor = Operation.hex(reader.readDescriptor());
			facts(program, image, reader, facts);

			List<Block> blocks = new ArrayList<>(image.getBlocks());
			blocks.removeAll(skip);
			Progress progress = new Progress(bytes(blocks));
			int number = 1;
			for (Block block : blocks) {
				listener.block(number++, blocks.size(), block);
				results.add(compare(block, written, reader, listener, progress));
				if (progress._aborted) {
					return new Result(descriptor, facts, results, true);
				}
			}
		} catch (AbortExecutionException ex) {
			// Cancelled before the blocks or between them: what was compared stands.
			return new Result(descriptor, facts, results, true);
		}
		return new Result(descriptor, facts, results, false);
	}

	/**
	 * The bytes the comparison of the given blocks reads: those of their masks, without the blocks
	 * of a load state machine (relative segments, which are not compared).
	 */
	private static long bytes(List<Block> blocks) {
		long result = 0;
		for (Block block : blocks) {
			if (block.stateMachine() != null) {
				continue;
			}
			for (byte bits : block.mask()) {
				if (bits != 0) {
					result++;
				}
			}
		}
		return result;
	}

	/**
	 * Reads what identifies the program in the device (program version or identity, load states).
	 */
	private static void facts(Program program, DownloadImage image, DeviceReader reader, List<ResKey> facts) {
		MaskVersion mask = program == null ? null : program.getMask();
		if (mask != null) {
			facts.add(I18NConstants.FACT_MASK__NUMBER_NAME.fill(String.format("%04X", mask.getNumber()), mask.getName()));
			for (MaskResource resource : mask.getResources()) {
				if ("ApplicationId".equals(resource.getName()) && resource.getLocation() != null
					&& resource.getLocation().getInterfaceObject() != null && resource.getLocation().getPropertyId() != null) {
					facts.add(I18NConstants.FACT_PROGRAM_VERSION__DEVICE_PROGRAM.fill(property(reader,
						resource.getLocation().getInterfaceObject(), resource.getLocation().getPropertyId()),
						Operation.hex(DownloadPlanner.applicationId(program))));
				} else if ("ApplicationId".equals(resource.getName()) && resource.getLocation() != null
					&& resource.getLocation().getAddressSpace() != null
					&& "StandardMemory".equals(resource.getLocation().getAddressSpace().getName())
					&& resource.getLocation().getStartAddress() != null && resource.getLength() != null) {
					// BCU 1: the identity of the program lies in memory, as the image has it.
					int address = resource.getLocation().getStartAddress().intValue();
					int length = resource.getLength().intValue();
					facts.add(I18NConstants.FACT_PROGRAM_IDENTITY__ADDRESS_DEVICE_IMAGE.fill(String.format("%04X", address),
						memory(reader, address, length), imageBytes(image, address, length)));
				}
			}
			// Through properties (mask 7.5) or in memory (mask 7.1, haumacher/ehome #22).
			facts.addAll(LoadStateMachines.readStates(mask, reader));
		}
	}

	private static ResKey property(DeviceReader reader, int object, int property) {
		try {
			return ResKey.text(Operation.hex(reader.readProperty(object, property, 1, 1)));
		} catch (BusException ex) {
			return I18NConstants.FACT_NOT_READABLE__REASON.fill(ex.getErrorKey());
		}
	}

	private static ResKey memory(DeviceReader reader, int address, int length) {
		try {
			return ResKey.text(Operation.hex(reader.readMemory(address, length)));
		} catch (BusException ex) {
			return I18NConstants.FACT_NOT_READABLE__REASON.fill(ex.getErrorKey());
		}
	}

	private static String imageBytes(DownloadImage image, int address, int length) {
		StringBuilder result = new StringBuilder();
		for (int n = 0; n < length; n++) {
			Integer value = image.byteAt(address + n);
			if (n > 0) {
				result.append(' ');
			}
			result.append(value == null ? "--" : String.format("%02X", value));
		}
		return result.toString();
	}

	private static BlockResult compare(Block block, Map<Integer, Byte> written, DeviceReader reader, Listener listener,
			Progress progress) {
		byte[] data = block.data();
		byte[] mask = block.mask();
		List<Integer> different = new ArrayList<>();
		int compared = 0;
		if (block.stateMachine() != null) {
			return new BlockResult(block.label(), block.address(), data.length, 0, different,
				I18NConstants.COMPARE_RELATIVE_SEGMENT, null);
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
				listener.read(block, block.address() + start, progress._done, progress._total);
				read = reader.readMemory(block.address() + start, n - start);
			} catch (BusException ex) {
				return new BlockResult(block.label(), block.address(), data.length, compared, different,
					I18NConstants.COMPARE_READ_FAILED__ADDRESS_REASON.fill(String.format("%04X", block.address() + start),
						ex.getErrorKey()), null);
			} catch (AbortExecutionException ex) {
				progress._aborted = true;
				return new BlockResult(block.label(), block.address(), data.length, compared, different, null,
					Integer.valueOf(block.address() + start));
			}
			progress._done += n - start;
			for (int k = start; k < n; k++) {
				compared++;
				int bits = mask[k] & 0xFF;
				byte expected = written.getOrDefault(block.address() + k, data[k]);
				if (k - start >= read.length || ((read[k - start] ^ expected) & bits) != 0) {
					different.add(block.address() + k);
				}
			}
		}
		return new BlockResult(block.label(), block.address(), data.length, compared, different, null, null);
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
