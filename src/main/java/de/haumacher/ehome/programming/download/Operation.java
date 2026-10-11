/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.HexFormat;

/**
 * One operation of a {@link DownloadPlan}: what is sent to the device, with the bytes and addresses,
 * and which step of the load procedure it comes from.
 *
 * <p>
 * Operations are immutable values computed without any bus; the {@link DownloadExecutor} performs
 * them on a {@link DeviceAccess}.
 * </p>
 */
public sealed interface Operation {

	/**
	 * The step of the load procedure the operation performs, for a person (e.g.
	 * <code>14 AbsSegment LSM 3</code>); empty for an operation the planner adds (a check, a write of
	 * the image).
	 */
	String step();

	/**
	 * The operation for a person: what it does, with addresses and bytes.
	 */
	String describe();

	/**
	 * The number of bytes the operation writes into the device (memory or property), 0 for none.
	 */
	default int bytesWritten() {
		return 0;
	}

	/**
	 * Whether the operation changes the device.
	 */
	default boolean writes() {
		return bytesWritten() > 0;
	}

	/**
	 * Opens the connection to the device (ETS: <code>LdCtrlConnect</code>).
	 */
	record Connect(String step) implements Operation {
		@Override
		public String describe() {
			return "Connect";
		}
	}

	/**
	 * Closes the connection to the device (ETS: <code>LdCtrlDisconnect</code>).
	 */
	record Disconnect(String step) implements Operation {
		@Override
		public String describe() {
			return "Disconnect";
		}
	}

	/**
	 * Reads the device descriptor (type 0, the mask version) and aborts unless it is one of the given
	 * mask versions.
	 *
	 * @param masks
	 *        The mask versions the program runs on: its own first, then those compatible with it.
	 */
	record CheckMask(String step, int[] masks) implements Operation {
		@Override
		public String describe() {
			StringBuilder result = new StringBuilder("Check that the device has mask version");
			for (int n = 0; n < masks.length; n++) {
				result.append(n == 0 ? " " : " or ").append(String.format("%04X", masks[n]));
			}
			return result.toString();
		}
	}

	/**
	 * Reads a property and aborts unless it starts with the expected bytes (ETS:
	 * <code>LdCtrlCompareProp</code>, e.g. the hardware type; after a load, the program version).
	 *
	 * <p>
	 * The value read is compared with the expected bytes as far as both reach; expected bytes beyond
	 * the value read must be zero (ETS gives the hardware type of 6 bytes as 10 bytes of inline data,
	 * 4 of them zero).
	 * </p>
	 *
	 * @param objectIndex
	 *        The index of the interface object.
	 * @param propertyId
	 *        The property.
	 * @param start
	 *        The first element, from 1.
	 * @param count
	 *        The number of elements.
	 * @param expected
	 *        The expected value.
	 */
	record CompareProperty(String step, int objectIndex, int propertyId, int start, int count, byte[] expected)
			implements Operation {
		@Override
		public String describe() {
			return "Compare object " + objectIndex + " property " + propertyId + " with " + hex(expected);
		}
	}

	/**
	 * Writes a load control (10 bytes) into the load state control property (5) of the interface
	 * object of a load state machine and checks the state the device reports afterwards.
	 *
	 * @param stateMachine
	 *        The load state machine (ETS: <code>LsmIdx</code>).
	 * @param objectIndex
	 *        The interface object of the load state machine.
	 * @param control
	 *        The load control, see {@link LoadControls}.
	 * @param expectedState
	 *        The load state the device must report afterwards.
	 */
	record LoadControl(String step, int stateMachine, int objectIndex, byte[] control, int expectedState)
			implements Operation {
		@Override
		public String describe() {
			return "Load control of load state machine " + stateMachine + " (object " + objectIndex + "): "
				+ LoadControls.describe(control) + " [" + hex(control) + "], then " + LoadControls.stateName(expectedState);
		}

		@Override
		public int bytesWritten() {
			return control.length;
		}
	}

	/**
	 * Writes memory, at most {@link DownloadPlanner#MAX_CHUNK} bytes; with verification, the bytes
	 * are read back and compared.
	 *
	 * @param what
	 *        The block of the download image (or other source) the bytes belong to.
	 * @param address
	 *        The address of the first byte.
	 * @param data
	 *        The bytes.
	 * @param verify
	 *        Whether to read the bytes back and compare them.
	 */
	record WriteMemory(String step, String what, int address, byte[] data, boolean verify) implements Operation {
		@Override
		public String describe() {
			return String.format("Write memory %04X-%04X (%d bytes, %s)%s: %s", address, address + data.length - 1,
				data.length, what, verify ? ", verified" : "", hex(data));
		}

		@Override
		public int bytesWritten() {
			return data.length;
		}
	}

	/**
	 * Reads memory and aborts unless it equals the expected bytes (ETS: <code>LdCtrlCompareMem</code>).
	 */
	record CompareMemory(String step, int address, byte[] expected) implements Operation {
		@Override
		public String describe() {
			return String.format("Compare memory %04X-%04X with %s", address, address + expected.length - 1, hex(expected));
		}
	}

	/**
	 * Writes elements of a property of an interface object; the device's answer must repeat the
	 * value.
	 *
	 * @param what
	 *        What is written, for a person.
	 */
	record WriteProperty(String step, String what, int objectIndex, int propertyId, int start, int count, byte[] data)
			implements Operation {
		@Override
		public String describe() {
			return "Write object " + objectIndex + " property " + propertyId + " element " + start
				+ (count > 1 ? "-" + (start + count - 1) : "") + " (" + what + "): " + hex(data);
		}

		@Override
		public int bytesWritten() {
			return data.length;
		}
	}

	/**
	 * Restarts the device (ETS: <code>LdCtrlRestart</code>); the connection ends with it.
	 */
	record Restart(String step) implements Operation {
		@Override
		public String describe() {
			return "Restart the device";
		}

		@Override
		public boolean writes() {
			return true;
		}
	}

	/**
	 * Waits (ETS: <code>LdCtrlDelay</code>).
	 */
	record Delay(String step, int milliseconds) implements Operation {
		@Override
		public String describe() {
			return "Wait " + milliseconds + " ms";
		}
	}

	/**
	 * A step of the procedure that is not executed, with the reason; shown in the plan so that
	 * nothing is left out silently.
	 */
	record Skip(String step, String reason) implements Operation {
		@Override
		public String describe() {
			return "Not executed: " + reason;
		}
	}

	/**
	 * Writes the individual address into the one device in programming mode (the user presses its
	 * programming button); the device must be the only one in programming mode.
	 */
	record WriteIndividualAddress(String step, int address) implements Operation {
		@Override
		public String describe() {
			return "Write the individual address " + DownloadPlanner.formatAddress(address)
				+ " into the device in programming mode";
		}

		@Override
		public int bytesWritten() {
			return 2;
		}
	}

	/**
	 * The bytes as hexadecimal text, separated by spaces.
	 */
	static String hex(byte[] data) {
		return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(data);
	}

}
