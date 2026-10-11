/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Performs a {@link DownloadPlan} on a device through a {@link DeviceAccess}, operation by operation,
 * and stops at the first failure.
 *
 * <p>
 * A load control is followed by the check of the load state the device reports: the state in its
 * answer, or, if the answer holds none or a transitional state (unloading, load completing), the
 * state read from the load state control property until it settles. A verified memory write is
 * read back and compared.
 * </p>
 */
public class DownloadExecutor {

	/** How often a transitional load state is read again before giving up. */
	private static final int STATE_POLLS = 20;

	/** The time between two reads of a transitional load state, in milliseconds. */
	private static final int STATE_POLL_INTERVAL = 250;

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
	 * @param failure
	 *        Why it failed (with the device's answer), <code>null</code> on success.
	 * @param duration
	 *        The time the download took.
	 */
	public record Result(int done, int count, Operation failed, String failure, Duration duration) {

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
		Mismatch(String message) {
			super(message);
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
		int done = 0;
		for (Operation operation : operations) {
			listener.operation(done + 1, operations.size(), operation);
			try {
				perform(operation, access);
			} catch (BusException | Mismatch ex) {
				return new Result(done, operations.size(), operation, ex.getMessage(), elapsed(start));
			}
			done++;
		}
		return new Result(done, operations.size(), null, null, elapsed(start));
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
				throw new Mismatch(String.format("The device has the mask version %s, the program needs %04X.",
					Operation.hex(descriptor), check.masks()[0]));
			}
		} else if (operation instanceof Operation.CompareProperty compare) {
			byte[] value = access.readProperty(compare.objectIndex(), compare.propertyId(), compare.start(), compare.count());
			if (!startsWith(value, compare.expected())) {
				throw new Mismatch("Object " + compare.objectIndex() + " property " + compare.propertyId() + " is "
					+ Operation.hex(value) + ", expected " + Operation.hex(compare.expected()) + ".");
			}
		} else if (operation instanceof Operation.LoadControl control) {
			loadControl(control, access);
		} else if (operation instanceof Operation.WriteMemory write) {
			access.writeMemory(write.address(), write.data());
			if (write.verify()) {
				byte[] read = access.readMemory(write.address(), write.data().length);
				if (!Arrays.equals(read, write.data())) {
					throw new Mismatch(String.format("Verification of %04X failed: the device holds %s, written was %s.",
						write.address(), Operation.hex(read), Operation.hex(write.data())));
				}
			}
		} else if (operation instanceof Operation.CompareMemory compare) {
			byte[] read = access.readMemory(compare.address(), compare.expected().length);
			if (!Arrays.equals(read, compare.expected())) {
				throw new Mismatch(String.format("Memory %04X holds %s, expected %s.", compare.address(),
					Operation.hex(read), Operation.hex(compare.expected())));
			}
		} else if (operation instanceof Operation.WriteProperty write) {
			access.writeProperty(write.objectIndex(), write.propertyId(), write.start(), write.count(), write.data());
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
			throw new Mismatch("Load state machine " + control.stateMachine() + " is "
				+ (state == null ? "in no known state" : LoadControls.stateName(state)) + " after "
				+ LoadControls.describe(control.control()) + ", expected " + LoadControls.stateName(control.expectedState())
				+ ".");
		}
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
