/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.top_logic.basic.util.ResKey;

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
	ResKey description();

	/**
	 * The {@link #description()} in English, for the log and the dump of a plan.
	 */
	default String describe() {
		return DownloadPlan.english(description());
	}

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
		public ResKey description() {
			return I18NConstants.OP_CONNECT;
		}
	}

	/**
	 * Closes the connection to the device (ETS: <code>LdCtrlDisconnect</code>).
	 */
	record Disconnect(String step) implements Operation {
		@Override
		public ResKey description() {
			return I18NConstants.OP_DISCONNECT;
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
		public ResKey description() {
			if (masks.length == 1) {
				return I18NConstants.OP_CHECK_MASK__MASK.fill(String.format("%04X", masks[0]));
			}
			List<String> names = new ArrayList<>();
			for (int mask : masks) {
				names.add(String.format("%04X", mask));
			}
			return I18NConstants.OP_CHECK_MASKS__MASKS.fill(String.join(", ", names));
		}
	}

	/**
	 * Authorizes the connection with a key (<code>A_Authorize_Request</code>); the device answers with
	 * the access level it grants for the key. The download goes on with whatever level the device
	 * grants: a write the level does not permit is refused by the device, and the download stops
	 * there with the device's answer.
	 *
	 * @param key
	 *        The key (4 bytes).
	 */
	record Authorize(String step, byte[] key) implements Operation {
		@Override
		public ResKey description() {
			return I18NConstants.OP_AUTHORIZE__KEY.fill(hex(key));
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
		public ResKey description() {
			return I18NConstants.OP_COMPARE_PROPERTY__OBJECT_PROPERTY_VALUE.fill(objectIndex, propertyId, hex(expected));
		}
	}

	/**
	 * Reads the program version of the device (<code>PID_PROGRAM_VERSION</code>) and aborts unless it
	 * is the program's: a download of the parameters only needs the program in the device
	 * (haumacher/ehome #195).
	 *
	 * @param objectIndex
	 *        The interface object of the program version (the mask's resource
	 *        <code>ApplicationId</code>).
	 * @param propertyId
	 *        Its property.
	 * @param expected
	 *        The application ID of the program.
	 */
	record CheckProgramVersion(String step, int objectIndex, int propertyId, byte[] expected) implements Operation {
		@Override
		public ResKey description() {
			return I18NConstants.OP_CHECK_PROGRAM_VERSION__OBJECT_PROPERTY_VALUE.fill(objectIndex, propertyId, hex(expected));
		}
	}

	/**
	 * Reads the identity of the program in the memory of the device (BCU 1: the mask's resource
	 * <code>ApplicationId</code> in memory) and aborts unless it holds the image's bytes there: a
	 * download of the parameters only needs the program in the device (haumacher/ehome #195).
	 */
	record CheckProgramIdentity(String step, int address, byte[] expected) implements Operation {
		@Override
		public ResKey description() {
			return I18NConstants.OP_CHECK_PROGRAM_IDENTITY__FROM_TO_VALUE.fill(String.format("%04X", address),
				String.format("%04X", address + expected.length - 1), hex(expected));
		}
	}

	/**
	 * Reads the load state of a load state machine and aborts unless it is
	 * {@link LoadControls#STATE_LOADED loaded}: a download of the parameters only needs the program
	 * in the device (haumacher/ehome #195).
	 *
	 * @param stateMachine
	 *        The load state machine.
	 * @param objectIndex
	 *        The interface object whose load state control property holds its state (load state
	 *        machines through properties), <code>null</code> for one in memory.
	 * @param status
	 *        The address of its load state in memory (mask 7.1), <code>null</code> for one through
	 *        properties.
	 */
	record CheckLoadState(String step, int stateMachine, Integer objectIndex, Integer status) implements Operation {
		@Override
		public ResKey description() {
			return I18NConstants.OP_CHECK_LOADED__LSM.fill(stateMachine);
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
		public ResKey description() {
			return I18NConstants.OP_LOAD_CONTROL__LSM_OBJECT_CONTROL_BYTES_STATE.fill(stateMachine, objectIndex,
				LoadControls.describe(control), hex(control), LoadControls.stateName(expectedState));
		}

		@Override
		public int bytesWritten() {
			return control.length;
		}
	}

	/**
	 * Writes a load control into the memory of the load control of a mask controlling its load state
	 * machines in memory (mask 7.1, see {@link LoadStateMachines}) and checks the load state the device
	 * holds afterwards at the address of the load state.
	 *
	 * @param stateMachine
	 *        The load state machine (ETS: <code>LsmIdx</code>), the object type of the loaded part.
	 * @param address
	 *        The address of the load control.
	 * @param status
	 *        The address of the load state of the load state machine (one byte).
	 * @param control
	 *        The load control as written into a property ({@link LoadControls}); the bytes written are
	 *        its {@link #record()}.
	 * @param expectedState
	 *        The load state the device must hold afterwards.
	 */
	record MemoryLoadControl(String step, int stateMachine, int address, int status, byte[] control, int expectedState)
			implements Operation {

		/**
		 * The bytes written into the memory of the load control.
		 */
		public byte[] record() {
			return LoadControls.memoryRecord(stateMachine, control);
		}

		@Override
		public ResKey description() {
			return I18NConstants.OP_MEMORY_LOAD_CONTROL__LSM_ADDRESS_CONTROL_BYTES_STATUS_STATE.fill(stateMachine,
				String.format("%04X", address), LoadControls.describe(control), hex(record()), String.format("%04X", status),
				LoadControls.stateName(expectedState));
		}

		@Override
		public int bytesWritten() {
			return LoadControls.MEMORY_RECORD_LENGTH;
		}
	}

	/**
	 * Writes memory, at most {@link DownloadPlanner#MAX_CHUNK} bytes; with verification, the bytes
	 * are read back and compared.
	 *
	 * @param what
	 *        The block of the download image (or other source) the bytes belong to, for a person.
	 * @param address
	 *        The address of the first byte.
	 * @param data
	 *        The bytes.
	 * @param verify
	 *        Whether to read the bytes back and compare them.
	 */
	record WriteMemory(String step, ResKey what, int address, byte[] data, boolean verify) implements Operation {
		@Override
		public ResKey description() {
			return (verify ? I18NConstants.OP_WRITE_MEMORY_VERIFIED__FROM_TO_LENGTH_WHAT_DATA
				: I18NConstants.OP_WRITE_MEMORY__FROM_TO_LENGTH_WHAT_DATA).fill(String.format("%04X", address),
					String.format("%04X", address + data.length - 1), data.length, what, hex(data));
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
		public ResKey description() {
			return I18NConstants.OP_COMPARE_MEMORY__FROM_TO_VALUE.fill(String.format("%04X", address),
				String.format("%04X", address + expected.length - 1), hex(expected));
		}
	}

	/**
	 * Writes elements of a property of an interface object; the device's answer must repeat the
	 * value.
	 *
	 * @param what
	 *        What is written, for a person.
	 */
	record WriteProperty(String step, ResKey what, int objectIndex, int propertyId, int start, int count, byte[] data)
			implements Operation {
		@Override
		public ResKey description() {
			return I18NConstants.OP_WRITE_PROPERTY__OBJECT_PROPERTY_ELEMENTS_WHAT_DATA.fill(objectIndex, propertyId,
				start + (count > 1 ? "-" + (start + count - 1) : ""), what, hex(data));
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
		public ResKey description() {
			return I18NConstants.OP_RESTART;
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
		public ResKey description() {
			return I18NConstants.OP_DELAY__MILLISECONDS.fill(milliseconds);
		}
	}

	/**
	 * A step of the procedure that is not executed, with the reason; shown in the plan so that
	 * nothing is left out silently.
	 */
	record Skip(String step, ResKey reason) implements Operation {
		@Override
		public ResKey description() {
			return I18NConstants.OP_SKIP__REASON.fill(reason);
		}
	}

	/**
	 * Writes the individual address into the one device in programming mode (the user presses its
	 * programming button); the device must be the only one in programming mode.
	 */
	record WriteIndividualAddress(String step, int address) implements Operation {
		@Override
		public ResKey description() {
			return I18NConstants.OP_WRITE_ADDRESS__ADDRESS.fill(DownloadPlanner.formatAddress(address));
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
