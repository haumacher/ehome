/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.top_logic.basic.util.ResKey;

/**
 * Performs a {@link DownloadPlan} on a device through a {@link DeviceAccess}, operation by operation,
 * and stops at the first failure.
 *
 * <p>
 * A load control is followed by the check of the load state the device reports: the state in its
 * answer, or, if the answer holds none or a transitional state (unloading, load completing), the
 * state read from the load state control property until it settles. A load control in memory (mask
 * 7.1) is followed by the read of the load state byte of its load state machine: a transitional state
 * is read again until it settles, another state than the expected one up to
 * {@value #MEMORY_STATE_REREADS} times more (KNX 3/5/2 3.31.2: the device takes the control written
 * into its memory without an answer, and its state follows). A verified memory write is read back and
 * compared; the load control in memory is not (the mask gives no read access to it).
 * </p>
 *
 * <p>
 * The memory writes of the plan carry at most a standard frame's {@value MemoryChunk#STANDARD} bytes
 * each, whatever the path (the plan is the same for every path). Where the device and the path take
 * longer memory accesses ({@link DeviceAccess#memoryChunk()}, asked before the first memory write;
 * haumacher/ehome #196), a run of memory writes following each other at addresses following each
 * other (and verified alike) is written in chunks of that length instead, each verified chunk read
 * back as written. The progress names the operations of the plan, and a failure the operation
 * holding the first byte of the chunk that failed. A longer chunk failing without an answer of the
 * device is repeated by the access in standard frames, which the rest of the download uses
 * ({@link CalimeroWriter#writeMemory(int, byte[])}); its verification reads back what was written
 * then, so nothing of the failed chunk is taken as written. With chunks of a standard frame, every memory
 * write of the plan is a memory write on the bus, as planned.
 * </p>
 *
 * <p>
 * The checks of a download of the parameters only (haumacher/ehome #195, #224) read the program
 * version (or, BCU 1, the program's identity in memory), the load states and the code the download
 * leaves to the device, once each; a device holding anything else stops the download before anything
 * is written, as a {@link Result#otherApplication() mismatch} the {@link DownloadService} answers with
 * the complete download. The manufacturer of the device is compared before every download writes.
 * </p>
 */
public class DownloadExecutor {

	/** How often a transitional load state is read again before giving up. */
	private static final int STATE_POLLS = 20;

	/** The time between two reads of a transitional load state, in milliseconds. */
	private static final int STATE_POLL_INTERVAL = 250;

	/**
	 * How often the load state of a load state machine in memory is read again when it is not yet the
	 * expected one (and not transitional).
	 */
	private static final int MEMORY_STATE_REREADS = 3;

	/** The interface object of the device. */
	private static final int DEVICE_OBJECT = 0;

	/** The property of the device object holding its hardware type (<code>PID_HARDWARE_TYPE</code>). */
	private static final int PID_HARDWARE_TYPE = 78;

	/**
	 * Is told about each operation before it is performed.
	 */
	public interface Listener {

		/**
		 * The operation of the given number (from 1) of the given count is performed next.
		 */
		void operation(int number, int count, Operation operation);
	}

	/**
	 * Waits; tests wait no real time.
	 */
	public interface Sleeper {

		/**
		 * Waits the given time.
		 */
		void sleep(int milliseconds) throws InterruptedException;
	}

	/**
	 * The outcome of a download.
	 *
	 * @param done
	 *        The number of operations performed successfully.
	 * @param count
	 *        The number of operations of the plan.
	 * @param failed
	 *        The operation that failed, <code>null</code> on success.
	 * @param reason
	 *        Why it failed (with the device's answer), for a person; <code>null</code> on success.
	 * @param duration
	 *        The time the download took.
	 * @param mismatch
	 *        Whether the operation failed because the device answered with another value than the
	 *        expected (not for a failure of the access itself).
	 */
	public record Result(int done, int count, Operation failed, ResKey reason, Duration duration, boolean mismatch) {

		/**
		 * Creates a {@link Result} of a download that succeeded, or failed in the access.
		 */
		public Result(int done, int count, Operation failed, ResKey reason, Duration duration) {
			this(done, count, failed, reason, duration, false);
		}

		/**
		 * Whether the download stopped at a check of a download of the parameters only finding that the
		 * application loaded in the device is not the project's ({@link Operation.ApplicationCheck}),
		 * before anything was written.
		 */
		public boolean otherApplication() {
			return mismatch && failed instanceof Operation.ApplicationCheck;
		}

		/**
		 * The {@link #reason()} in English, for the log; <code>null</code> on success.
		 */
		public String failure() {
			return reason == null ? null : DownloadPlan.english(reason);
		}

		/**
		 * Whether every operation succeeded.
		 */
		public boolean success() {
			return failed == null;
		}
	}

	/**
	 * A failure of an operation found by the executor itself (a value differing from the
	 * expected).
	 */
	private static final class Mismatch extends Exception {

		private final ResKey _reason;

		Mismatch(ResKey reason) {
			super(DownloadPlan.english(reason));
			_reason = reason;
		}

		ResKey reason() {
			return _reason;
		}
	}

	private final Sleeper _sleeper;

	/**
	 * Creates a {@link DownloadExecutor}.
	 *
	 * @param sleeper
	 *        How to wait for a delay of the procedure or a settling load state.
	 */
	public DownloadExecutor(Sleeper sleeper) {
		_sleeper = sleeper;
	}

	/**
	 * Creates a {@link DownloadExecutor} waiting real time.
	 */
	public DownloadExecutor() {
		this(Thread::sleep);
	}

	/**
	 * Performs the plan.
	 *
	 * @throws IllegalArgumentException
	 *         If the plan has problems.
	 */
	public Result execute(DownloadPlan plan, DeviceAccess access, Listener listener) throws InterruptedException {
		if (!plan.isExecutable()) {
			throw new IllegalArgumentException("The plan is refused: " + plan.getProblems());
		}
		long start = System.nanoTime();
		List<Operation> operations = plan.getOperations();
		int count = operations.size();
		int done = 0;
		while (done < count) {
			Operation operation = operations.get(done);
			int[] position = { done };
			try {
				if (operation instanceof Operation.WriteMemory && access.memoryChunk() > MemoryChunk.STANDARD) {
					done = writeRun(operations, done, access, listener, position);
					continue;
				}
				listener.operation(done + 1, count, operation);
				perform(operation, access);
			} catch (BusException ex) {
				return new Result(position[0], count, operations.get(position[0]), ex.getErrorKey(), elapsed(start));
			} catch (Mismatch ex) {
				return new Result(position[0], count, operations.get(position[0]), ex.reason(), elapsed(start), true);
			}
			done++;
		}
		return new Result(done, count, null, null, elapsed(start));
	}

	/**
	 * Writes the memory writes from the given one on, as long as each continues the one before it (at
	 * the address following it, verified alike), in chunks of the access's length
	 * ({@link DeviceAccess#memoryChunk()}, asked for every chunk: after a fall back to standard frames
	 * the rest of the run goes in those).
	 *
	 * @param first
	 *        The index of the first memory write.
	 * @param position
	 *        Takes the index of the operation holding the first byte of the chunk written: the
	 *        operation failing with it.
	 * @return The index of the operation after the run.
	 */
	private static int writeRun(List<Operation> operations, int first, DeviceAccess access, Listener listener,
			int[] position) throws BusException, Mismatch {
		Operation.WriteMemory head = (Operation.WriteMemory) operations.get(first);
		int end = first + 1;
		int length = head.data().length;
		while (end < operations.size() && operations.get(end) instanceof Operation.WriteMemory next
			&& next.verify() == head.verify() && next.address() == head.address() + length) {
			length += next.data().length;
			end++;
		}
		byte[] data = new byte[length];
		int[] offsets = new int[end - first];
		int offset = 0;
		for (int index = first; index < end; index++) {
			byte[] part = ((Operation.WriteMemory) operations.get(index)).data();
			offsets[index - first] = offset;
			System.arraycopy(part, 0, data, offset, part.length);
			offset += part.length;
		}
		int announced = first;
		int from = 0;
		while (from < length) {
			int to = Math.min(length, from + access.memoryChunk());
			while (announced < end && offsets[announced - first] < to) {
				listener.operation(announced + 1, operations.size(), operations.get(announced));
				announced++;
			}
			int holder = first;
			while (holder + 1 < end && offsets[holder + 1 - first] <= from) {
				holder++;
			}
			position[0] = holder;
			int address = head.address() + from;
			byte[] written = Arrays.copyOfRange(data, from, to);
			access.writeMemory(address, written);
			if (head.verify()) {
				verify(access, address, written);
			}
			from = to;
		}
		return end;
	}

	private static void verify(DeviceAccess access, int address, byte[] written) throws BusException, Mismatch {
		byte[] read = access.readMemory(address, written.length);
		if (!Arrays.equals(read, written)) {
			throw new Mismatch(I18NConstants.EXEC_VERIFY__ADDRESS_READ_WRITTEN.fill(String.format("%04X", address),
				Operation.hex(read), Operation.hex(written)));
		}
	}

	private static void checkManufacturer(Operation.CheckManufacturer check, DeviceAccess access)
			throws BusException, Mismatch {
		byte[] value;
		try {
			value = access.readProperty(check.objectIndex(), check.propertyId(), 1, 1);
		} catch (Refused ex) {
			if (check.application()) {
				// The device gives no program version: it holds no application naming a manufacturer.
				return;
			}
			throw ex;
		}
		if (value.length < 2) {
			throw new Mismatch(I18NConstants.EXEC_NO_MANUFACTURER__VALUE.fill(Operation.hex(value)));
		}
		int manufacturer = (value[0] & 0xFF) << 8 | value[1] & 0xFF;
		if (manufacturer == check.manufacturer()) {
			return;
		}
		String device = String.format("%04X", manufacturer);
		String program = String.format("%04X", check.manufacturer());
		if (!check.application()) {
			throw new Mismatch(I18NConstants.EXEC_MANUFACTURER__DEVICE_PROGRAM.fill(device, program));
		}
		if (manufacturer == 0 || manufacturer == 0xFFFF) {
			// No application loaded: nothing names the manufacturer.
			return;
		}
		throw new Mismatch(I18NConstants.EXEC_APPLICATION_MANUFACTURER__DEVICE_PROGRAM.fill(device, program));
	}

	private static void checkApplicationMemory(Operation.CheckApplicationMemory check, DeviceAccess access)
			throws BusException, Mismatch {
		byte[] expected = check.expected();
		int from = 0;
		while (from < expected.length) {
			int length = Math.min(expected.length - from, access.memoryChunk());
			int address = check.address() + from;
			byte[] read = access.readMemory(address, length);
			byte[] part = Arrays.copyOfRange(expected, from, from + length);
			if (!Arrays.equals(read, part)) {
				int at = 0;
				while (at < length && at < read.length && read[at] == part[at]) {
					at++;
				}
				// The first differing bytes, at most 8.
				int end = Math.min(at + 8, length);
				throw new Mismatch(I18NConstants.EXEC_APPLICATION_MEMORY__ADDRESS_READ_EXPECTED.fill(
					String.format("%04X", address + at),
					Operation.hex(Arrays.copyOfRange(read, Math.min(at, read.length), Math.min(end, read.length))),
					Operation.hex(Arrays.copyOfRange(part, at, end))));
			}
			from += length;
		}
	}

	private static Duration elapsed(long start) {
		return Duration.ofNanos(System.nanoTime() - start);
	}

	private void perform(Operation operation, DeviceAccess access) throws BusException, Mismatch, InterruptedException {
		if (operation instanceof Operation.Connect) {
			// The connection is opened by the first access.
		} else if (operation instanceof Operation.Disconnect) {
			access.disconnect();
		} else if (operation instanceof Operation.CheckMask check) {
			byte[] descriptor = access.readDescriptor();
			int mask = descriptor.length < 2 ? -1 : (descriptor[0] & 0xFF) << 8 | descriptor[1] & 0xFF;
			if (Arrays.stream(check.masks()).noneMatch(m -> m == mask)) {
				throw new Mismatch(I18NConstants.EXEC_MASK__DESCRIPTOR_NEEDED.fill(Operation.hex(descriptor),
					String.format("%04X", check.masks()[0])));
			}
		} else if (operation instanceof Operation.Authorize authorize) {
			access.authorize(authorize.key());
		} else if (operation instanceof Operation.CompareProperty compare) {
			byte[] value = access.readProperty(compare.objectIndex(), compare.propertyId(), compare.start(), compare.count());
			if (!startsWith(value, compare.expected())) {
				if (compare.objectIndex() == DEVICE_OBJECT && compare.propertyId() == PID_HARDWARE_TYPE) {
					throw new Mismatch(I18NConstants.EXEC_HARDWARE_TYPE__VALUE_EXPECTED.fill(Operation.hex(value),
						Operation.hex(compare.expected())));
				}
				throw new Mismatch(I18NConstants.EXEC_PROPERTY__OBJECT_PROPERTY_VALUE_EXPECTED.fill(compare.objectIndex(),
					compare.propertyId(), Operation.hex(value), Operation.hex(compare.expected())));
			}
		} else if (operation instanceof Operation.CheckManufacturer check) {
			checkManufacturer(check, access);
		} else if (operation instanceof Operation.CheckApplicationMemory check) {
			checkApplicationMemory(check, access);
		} else if (operation instanceof Operation.CheckProgramVersion check) {
			byte[] value = access.readProperty(check.objectIndex(), check.propertyId(), 1, 1);
			if (!startsWith(value, check.expected())) {
				throw new Mismatch(I18NConstants.EXEC_PROGRAM_VERSION__VALUE_EXPECTED.fill(Operation.hex(value),
					Operation.hex(check.expected())));
			}
		} else if (operation instanceof Operation.CheckProgramIdentity check) {
			byte[] value = access.readMemory(check.address(), check.expected().length);
			if (!Arrays.equals(value, check.expected())) {
				throw new Mismatch(I18NConstants.EXEC_PROGRAM_IDENTITY__ADDRESS_VALUE_EXPECTED.fill(
					String.format("%04X", check.address()), Operation.hex(value), Operation.hex(check.expected())));
			}
		} else if (operation instanceof Operation.CheckLoadState check) {
			byte[] value = check.objectIndex() != null
				? access.readProperty(check.objectIndex(), LoadControls.PID_LOAD_STATE_CONTROL, 1, 1)
				: access.readMemory(check.status(), 1);
			if (value.length == 0 || (value[0] & 0xFF) != LoadControls.STATE_LOADED) {
				throw new Mismatch(I18NConstants.EXEC_NOT_LOADED__LSM_STATE.fill(check.stateMachine(),
					value.length == 0 ? I18NConstants.FACT_NO_ANSWER : LoadControls.stateName(value[0] & 0xFF)));
			}
		} else if (operation instanceof Operation.LoadControl control) {
			loadControl(control, access);
		} else if (operation instanceof Operation.MemoryLoadControl control) {
			memoryLoadControl(control, access);
		} else if (operation instanceof Operation.WriteMemory write) {
			access.writeMemory(write.address(), write.data());
			if (write.verify()) {
				verify(access, write.address(), write.data());
			}
		} else if (operation instanceof Operation.CompareMemory compare) {
			byte[] read = access.readMemory(compare.address(), compare.expected().length);
			if (!Arrays.equals(read, compare.expected())) {
				throw new Mismatch(I18NConstants.EXEC_MEMORY__ADDRESS_READ_EXPECTED.fill(String.format("%04X", compare.address()),
					Operation.hex(read), Operation.hex(compare.expected())));
			}
		} else if (operation instanceof Operation.WriteProperty write) {
			writeProperty(write, access);
		} else if (operation instanceof Operation.Restart) {
			access.restart();
		} else if (operation instanceof Operation.Delay delay) {
			_sleeper.sleep(delay.milliseconds());
		} else if (operation instanceof Operation.Skip) {
			// Not executed, see its reason.
		} else if (operation instanceof Operation.WriteIndividualAddress write) {
			access.writeIndividualAddress(write.address());
		} else {
			throw new IllegalArgumentException("Unknown operation: " + operation);
		}
	}

	/**
	 * Writes a property; a write the device refuses counts as done when the device already holds the
	 * value.
	 *
	 * <p>
	 * A device may compute a property itself and refuse its write: the Gira Tastsensor 3 of the test
	 * system (mask 7.5) computes the download stamps (<code>PID_MCB_TABLE</code>) when a load state
	 * machine completes and answers the write of the stamp ETS's load procedure names with
	 * "failed/forbidden", while it holds exactly that stamp (read back on 2026-11-08). The read-back
	 * keeps a refusal of another value an error.
	 * </p>
	 */
	private static void writeProperty(Operation.WriteProperty write, DeviceAccess access) throws BusException {
		try {
			access.writeProperty(write.objectIndex(), write.propertyId(), write.start(), write.count(), write.data());
		} catch (BusException ex) {
			byte[] held;
			try {
				held = access.readProperty(write.objectIndex(), write.propertyId(), write.start(), write.count());
			} catch (BusException readFailure) {
				throw ex;
			}
			if (!Arrays.equals(held, write.data())) {
				throw ex;
			}
		}
	}

	private void loadControl(Operation.LoadControl control, DeviceAccess access)
			throws BusException, Mismatch, InterruptedException {
		Integer state = access.writeLoadControl(control.objectIndex(), control.control());
		int polls = 0;
		while (state == null || transitional(state) && state != control.expectedState()) {
			if (polls++ >= STATE_POLLS) {
				break;
			}
			if (state != null) {
				_sleeper.sleep(STATE_POLL_INTERVAL);
			}
			byte[] value = access.readProperty(control.objectIndex(), LoadControls.PID_LOAD_STATE_CONTROL, 1, 1);
			state = value.length == 0 ? null : Integer.valueOf(value[0] & 0xFF);
		}
		if (state == null || state != control.expectedState()) {
			throw new Mismatch(state == null
				? I18NConstants.EXEC_NO_LOAD_STATE__LSM_CONTROL_EXPECTED.fill(control.stateMachine(),
					LoadControls.describe(control.control()), LoadControls.stateName(control.expectedState()))
				: I18NConstants.EXEC_LOAD_STATE__LSM_STATE_CONTROL_EXPECTED.fill(control.stateMachine(),
					LoadControls.stateName(state), LoadControls.describe(control.control()),
					LoadControls.stateName(control.expectedState())));
		}
	}

	private void memoryLoadControl(Operation.MemoryLoadControl control, DeviceAccess access)
			throws BusException, Mismatch, InterruptedException {
		access.writeMemory(control.address(), control.record());
		Integer state = memoryState(control, access);
		int polls = 0;
		int rereads = 0;
		while (state == null || state != control.expectedState()) {
			if (state != null && transitional(state)) {
				if (polls++ >= STATE_POLLS) {
					break;
				}
			} else if (rereads++ >= MEMORY_STATE_REREADS) {
				break;
			}
			_sleeper.sleep(STATE_POLL_INTERVAL);
			state = memoryState(control, access);
		}
		if (state == null || state != control.expectedState()) {
			throw new Mismatch(state == null
				? I18NConstants.EXEC_NO_LOAD_STATE__LSM_CONTROL_EXPECTED.fill(control.stateMachine(),
					LoadControls.describe(control.control()), LoadControls.stateName(control.expectedState()))
				: I18NConstants.EXEC_LOAD_STATE__LSM_STATE_CONTROL_EXPECTED.fill(control.stateMachine(),
					LoadControls.stateName(state), LoadControls.describe(control.control()),
					LoadControls.stateName(control.expectedState())));
		}
	}

	private static Integer memoryState(Operation.MemoryLoadControl control, DeviceAccess access) throws BusException {
		byte[] value = access.readMemory(control.status(), 1);
		return value.length == 0 ? null : Integer.valueOf(value[0] & 0xFF);
	}

	private static boolean transitional(int state) {
		return state == LoadControls.STATE_UNLOADING || state == LoadControls.STATE_LOAD_COMPLETING;
	}

	/**
	 * Whether a value read equals the expected one as far as both reach, the rest of the longer
	 * being zero.
	 */
	static boolean startsWith(byte[] value, byte[] expected) {
		for (int n = 0; n < Math.max(value.length, expected.length); n++) {
			int actual = n < value.length ? value[n] : 0;
			int wanted = n < expected.length ? expected[n] : 0;
			if (actual != wanted) {
				return false;
			}
		}
		return true;
	}

}
