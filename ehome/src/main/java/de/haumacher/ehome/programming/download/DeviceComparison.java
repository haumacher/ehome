/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * Compares a device with its download image without writing anything: on a {@link Snapshot} of the
 * device (haumacher/ehome #193), read before by {@link ReadOut} or stored with the device, so the
 * comparison runs without the bus as often as wanted. Compares the memory of every block of the image
 * (only the bytes of its mask) and reports the differences with the device's and the plan's values,
 * and the device descriptor, the program version and the load states read.
 *
 * <p>
 * Compared with a {@link DownloadPlan}, the expected memory is what the download leaves: the image,
 * except where the load procedure writes inline data of its own over it (BCU 1: FF into
 * <code>RunError</code> at its end), there the value the plan writes last.
 * </p>
 *
 * <p>
 * A byte the snapshot does not know (its read failed) is not compared; the block says so. A snapshot
 * of a cancelled read-out (haumacher/ehome #190) is compared up to where its reading stopped: its
 * {@link Result} holds what was compared ({@link Result#aborted()}).
 * </p>
 */
public class DeviceComparison {

	/** The maximal number of bytes of one {@link Difference}. */
	public static final int DIFFERENCE_LENGTH = 16;

	/**
	 * A run of differing bytes with the values of both sides.
	 *
	 * @param address
	 *        The first address.
	 * @param length
	 *        The number of bytes.
	 * @param device
	 *        The device's bytes, hex.
	 * @param plan
	 *        The plan's bytes, hex.
	 */
	public record Difference(int address, int length, String device, String plan) {

		/**
		 * The addresses, hexadecimal (<code>4600-460F</code>).
		 */
		public String range() {
			return length == 1 ? String.format("%04X", address)
				: String.format("%04X-%04X", address, address + length - 1);
		}
	}

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
	 *        Why the block could not be compared completely, <code>null</code> if it could.
	 * @param abortedAt
	 *        The address at which the comparison was aborted in this block (the first address not
	 *        read), <code>null</code> if it was not.
	 * @param differences
	 *        The differing bytes in runs, with the device's and the plan's values.
	 */
	public record BlockResult(ResKey label, int address, int length, int compared, List<Integer> different,
			ResKey failureKey, Integer abortedAt, List<Difference> differences) {

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
	 * Is told which block is read, and before every read of the memory ({@link ReadOut.Listener}).
	 */
	public interface Listener extends ReadOut.Listener {
		// The listener of the read-out.
	}

	/**
	 * Compares the device with the memory the given download plan leaves in it: the image of the
	 * plan, where the plan writes over it the value it writes last, without the blocks the plan
	 * does not load. Reads the device first ({@link ReadOut}).
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
		return compare(program, plan, ReadOut.read(program, plan, reader, listener));
	}

	/**
	 * Compares the device with the image, reading it first ({@link ReadOut}).
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
		return compare(program, image, skip, Map.of(), ReadOut.read(program, image, skip, reader, listener));
	}

	/**
	 * Compares a snapshot of the device with the memory the given download plan leaves in it, without
	 * the bus (haumacher/ehome #193).
	 *
	 * @param program
	 *        The program of the device, for the expected program version and the mask.
	 * @param plan
	 *        The full download plan of the device.
	 * @param snapshot
	 *        What was read from the device.
	 */
	public static Result compare(Program program, DownloadPlan plan, Snapshot snapshot) {
		return compare(program, plan.getImage(), plan.getUnloadedBlocks(), written(plan), snapshot);
	}

	/**
	 * The bytes the plan writes into memory, by address: the value it writes last.
	 */
	private static Map<Integer, Byte> written(DownloadPlan plan) {
		Map<Integer, Byte> written = new HashMap<>();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.WriteMemory write) {
				byte[] data = write.data();
				for (int n = 0; n < data.length; n++) {
					written.put(write.address() + n, data[n]);
				}
			}
		}
		return written;
	}

	private static Result compare(Program program, DownloadImage image, Collection<Block> skip,
			Map<Integer, Byte> written, Snapshot snapshot) {
		String descriptor = snapshot.getDescriptor() == null ? null : spaced(snapshot.getDescriptor());
		List<ResKey> facts = facts(program, image, snapshot);
		List<BlockResult> results = new ArrayList<>();
		List<Block> blocks = new ArrayList<>(image.getBlocks());
		blocks.removeAll(skip);
		// A skipped block may be a part of a block (the static bytes the mask's procedure does not
		// write, DownloadPlanner): its addresses are not compared in the block it belongs to.
		BitSet skipped = new BitSet();
		for (Block part : skip) {
			skipped.set(part.address(), part.end());
		}
		for (Block block : blocks) {
			BlockResult result = compare(block, written, snapshot, skipped);
			if (result == null) {
				// Not reached by a cancelled read-out.
				return new Result(descriptor, facts, results, true);
			}
			results.add(result);
			if (result.abortedAt() != null) {
				return new Result(descriptor, facts, results, true);
			}
		}
		return new Result(descriptor, facts, results, !snapshot.isComplete());
	}

	/**
	 * What identifies the program in the device (program version or identity, load states), as read.
	 */
	private static List<ResKey> facts(Program program, DownloadImage image, Snapshot snapshot) {
		List<ResKey> facts = new ArrayList<>();
		MaskVersion mask = program == null ? null : program.getMask();
		if (mask == null) {
			return facts;
		}
		facts.add(I18NConstants.FACT_MASK__NUMBER_NAME.fill(String.format("%04X", mask.getNumber()), mask.getName()));
		for (MaskResource resource : mask.getResources()) {
			if ("ApplicationId".equals(resource.getName()) && resource.getLocation() != null
				&& resource.getLocation().getInterfaceObject() != null && resource.getLocation().getPropertyId() != null) {
				facts.add(I18NConstants.FACT_PROGRAM_VERSION__DEVICE_PROGRAM.fill(version(snapshot),
					Operation.hex(DownloadPlanner.applicationId(program))));
			} else if ("ApplicationId".equals(resource.getName()) && resource.getLocation() != null
				&& resource.getLocation().getAddressSpace() != null
				&& "StandardMemory".equals(resource.getLocation().getAddressSpace().getName())
				&& resource.getLocation().getStartAddress() != null && resource.getLength() != null) {
				// BCU 1: the identity of the program lies in memory, as the image has it.
				int address = resource.getLocation().getStartAddress().intValue();
				int length = resource.getLength().intValue();
				facts.add(I18NConstants.FACT_PROGRAM_IDENTITY__ADDRESS_DEVICE_IMAGE.fill(String.format("%04X", address),
					version(snapshot), imageBytes(image, address, length)));
			}
		}
		for (Snapshot.LoadState state : snapshot.getLoadStates()) {
			facts.add(I18NConstants.FACT_LOAD_STATE__LSM_STATE.fill(state.stateMachine(),
				state.state() == null ? I18NConstants.FACT_NOT_READ : LoadControls.stateName(state.state().intValue())));
		}
		return facts;
	}

	private static ResKey version(Snapshot snapshot) {
		return snapshot.getProgramVersion() == null ? I18NConstants.FACT_NOT_READ
			: ResKey.text(spaced(snapshot.getProgramVersion()));
	}

	/**
	 * Hex digits as bytes separated by spaces: <code>07 05</code>.
	 */
	static String spaced(String hex) {
		StringBuilder result = new StringBuilder();
		for (int n = 0; n + 1 < hex.length(); n += 2) {
			if (n > 0) {
				result.append(' ');
			}
			result.append(hex, n, n + 2);
		}
		return result.toString();
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

	/**
	 * Compares a block with the snapshot.
	 *
	 * @return The comparison; <code>null</code> if the snapshot was cancelled before the block.
	 */
	private static BlockResult compare(Block block, Map<Integer, Byte> written, Snapshot snapshot, BitSet skipped) {
		byte[] data = block.data();
		byte[] mask = block.mask();
		List<Integer> different = new ArrayList<>();
		List<Integer> unknown = new ArrayList<>();
		int compared = 0;
		if (block.stateMachine() != null) {
			return new BlockResult(block.label(), block.address(), data.length, 0, different,
				I18NConstants.COMPARE_RELATIVE_SEGMENT, null, List.of());
		}
		Map<Integer, int[]> values = new HashMap<>();
		for (int k = 0; k < data.length; k++) {
			if (mask[k] == 0 || skipped.get(block.address() + k)) {
				continue;
			}
			int address = block.address() + k;
			if (!snapshot.isComplete() && !snapshot.isRead(address)) {
				// Where the cancelled read-out stopped.
				if (compared == 0 && unknown.isEmpty()) {
					return null;
				}
				return new BlockResult(block.label(), block.address(), data.length, compared, different, null,
					Integer.valueOf(address), differences(different, values));
			}
			Integer actual = snapshot.byteAt(address);
			if (actual == null) {
				unknown.add(address);
				continue;
			}
			compared++;
			int bits = mask[k] & 0xFF;
			int expected = written.getOrDefault(address, data[k]) & 0xFF;
			if (((actual.intValue() ^ expected) & bits) != 0) {
				different.add(address);
				values.put(address, new int[] { actual.intValue(), expected });
			}
		}
		ResKey failure = unknown.isEmpty() ? null : I18NConstants.COMPARE_NOT_READ__COUNT_RANGES.fill(unknown.size(),
			ranges(unknown));
		return new BlockResult(block.label(), block.address(), data.length, compared, different, failure, null,
			differences(different, values));
	}

	/**
	 * The differing addresses as runs of at most {@value #DIFFERENCE_LENGTH} bytes, with the device's
	 * and the plan's values.
	 */
	private static List<Difference> differences(List<Integer> different, Map<Integer, int[]> values) {
		List<Difference> result = new ArrayList<>();
		int n = 0;
		while (n < different.size()) {
			int start = different.get(n);
			StringBuilder device = new StringBuilder();
			StringBuilder plan = new StringBuilder();
			int next = start;
			while (n < different.size() && different.get(n) == next && next - start < DIFFERENCE_LENGTH) {
				int[] value = values.get(next);
				if (device.length() > 0) {
					device.append(' ');
					plan.append(' ');
				}
				device.append(String.format("%02X", value[0]));
				plan.append(String.format("%02X", value[1]));
				next++;
				n++;
			}
			result.add(new Difference(start, next - start, device.toString(), plan.toString()));
		}
		return result;
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
