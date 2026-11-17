/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.LoadControls;

/**
 * Scans the bus behind a gateway for the devices of an installation and reads what each tells
 * about itself ({@link FoundDevice}), without writing anything.
 *
 * <p>
 * Per device, point to point: the device descriptor (mask version), then from the device object
 * (interface object 0) the manufacturer (property 12), the serial number (11), the order info (15)
 * and the hardware type (78), from the application object (3) the program version (13) and the run
 * state (6), and the load states of the load state machines 1 to 3 (property 5). A device of
 * BCU 1 (mask 001x) has no properties: its program identity lies in memory at 0104-0107
 * (manufacturer, application number, version, as the program's static data has it), the
 * manufacturer of the device is that of its program. A device that does not answer its descriptor
 * is not read further (each read would wait for the timeout).
 * </p>
 *
 * <p>
 * The gateway itself (a KNXnet/IP router or interface) does not answer point-to-point reads through
 * its own tunnel; it is read through KNXnet/IP local device management instead, its individual
 * address from the KNXnet/IP parameter object (type 11, property 52).
 * </p>
 */
public class BusScanner {

	/** The device object. */
	public static final int DEVICE_OBJECT = 0;

	/** The application program object (index and type 3). */
	public static final int APPLICATION_OBJECT = 3;

	/** The type of the KNXnet/IP parameter object. */
	public static final int KNXNETIP_OBJECT_TYPE = 11;

	/** Device object: the serial number (6 bytes). */
	public static final int PID_SERIAL_NUMBER = 11;

	/** Device object: the KNX manufacturer ID (2 bytes). */
	public static final int PID_MANUFACTURER_ID = 12;

	/** Device object: the order info (10 bytes). */
	public static final int PID_ORDER_INFO = 15;

	/** Device object: the programming mode (1 byte, bit 0). */
	public static final int PID_PROGRAMMING_MODE = 54;

	/** Device object: the hardware type (6 bytes). */
	public static final int PID_HARDWARE_TYPE = 78;

	/** Application object: the run state. */
	public static final int PID_RUN_STATE = 6;

	/** KNXnet/IP parameter object: the individual address of the gateway. */
	public static final int PID_KNX_INDIVIDUAL_ADDRESS = 52;

	/** BCU 1: the program identity in memory (manufacturer, application number, version). */
	public static final int BCU1_APPLICATION_ID = 0x0104;

	/** BCU 1: the memory byte holding the programming mode (bit 0, with an even parity in bit 7). */
	public static final int BCU1_PROGRAMMING_MODE = 0x0060;

	/** The phase reading the gateway itself through local device management. */
	public static final String PHASE_GATEWAY = "gateway";

	/** The phase scanning a line. */
	public static final String PHASE_LINE = "line";

	/** The phase scanning for couplers and routers. */
	public static final String PHASE_ROUTERS = "routers";

	/** The phase looking for the devices in programming mode. */
	public static final String PHASE_PROGRAMMING = "programming";

	/** The phase reading the devices found. */
	public static final String PHASE_DEVICES = "devices";

	/**
	 * Is told what the scan does.
	 */
	public interface Listener {

		/**
		 * The scan enters the phase of the given name (one of the <code>PHASE_*</code> constants); the
		 * phases of a request are {@link BusScanner#phases(Request)}, in this order.
		 */
		default void phase(String name) {
			// Ignored by default.
		}

		/**
		 * What is done now, for a person.
		 */
		void message(ResKey text);

		/**
		 * How far the current phase got.
		 */
		void progress(int done, int total);

		/**
		 * It is not known how far the current phase got: it takes longer than expected.
		 */
		default void indeterminate() {
			// Ignored by default.
		}

		/**
		 * Throws if the scan is to stop (the job was cancelled).
		 */
		default void checkCancelled() {
			// Not cancellable.
		}
	}

	/**
	 * A line of the installation, written <code>area.line</code> (e.g. <code>1.1</code>), both
	 * parts 0 to 15.
	 *
	 * @param area
	 *        The area, 0 to 15.
	 * @param line
	 *        The line in the area, 0 to 15.
	 */
	public record Line(int area, int line) {

		/**
		 * Creates a {@link Line}.
		 */
		public Line {
			if (area < 0 || area > 15 || line < 0 || line > 15) {
				throw new IllegalArgumentException("No line: " + area + "." + line);
			}
		}

		/**
		 * The line written as the given text, <code>null</code> if the text is no line (two
		 * numbers 0 to 15 separated by a dot; surrounding white space is ignored).
		 */
		public static Line parse(String text) {
			if (text == null) {
				return null;
			}
			String[] parts = text.strip().split("\\.", -1);
			if (parts.length != 2) {
				return null;
			}
			Integer area = part(parts[0]);
			Integer line = part(parts[1]);
			if (area == null || line == null) {
				return null;
			}
			return new Line(area.intValue(), line.intValue());
		}

		private static Integer part(String text) {
			if (text.isEmpty() || text.length() > 2 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
				return null;
			}
			int value = Integer.parseInt(text);
			return value <= 15 ? Integer.valueOf(value) : null;
		}

		@Override
		public String toString() {
			return area + "." + line;
		}
	}

	/**
	 * What a scan should do: search for the one {@link ScanTarget target}, then read the devices
	 * found.
	 *
	 * @param target
	 *        What to search for.
	 * @param line
	 *        The line to scan, given exactly for {@link ScanTarget#LINE}.
	 */
	public record Request(ScanTarget target, Line line) {

		/**
		 * Creates a {@link Request}.
		 */
		public Request {
			if (target == null) {
				throw new IllegalArgumentException("No target.");
			}
			if ((target == ScanTarget.LINE) != (line != null)) {
				throw new IllegalArgumentException("A line is given exactly for a scan of a line: " + target + ", " + line);
			}
		}

		/**
		 * A request searching for the given target, which needs no line.
		 */
		public static Request of(ScanTarget target) {
			return new Request(target, null);
		}

		/**
		 * A request scanning the given line.
		 */
		public static Request of(Line line) {
			return new Request(ScanTarget.LINE, line);
		}
	}

	/**
	 * The outcome of a scan.
	 *
	 * @param devices
	 *        The devices found, by address.
	 * @param inProgrammingMode
	 *        The addresses of the devices in programming mode.
	 * @param notes
	 *        What went wrong, for a person.
	 */
	public record Result(List<FoundDevice> devices, List<Integer> inProgrammingMode, List<ResKey> notes) {
		// Value.
	}

	/**
	 * The phases a scan of the given request goes through, in order: the names of the
	 * <code>PHASE_*</code> constants. The search of the target, then the reading of the devices
	 * found; the gateway is read in its own phase.
	 */
	public static List<String> phases(Request request) {
		return switch (request.target()) {
			case GATEWAY -> List.of(PHASE_GATEWAY);
			case LINE -> List.of(PHASE_LINE, PHASE_DEVICES);
			case ROUTERS -> List.of(PHASE_ROUTERS, PHASE_DEVICES);
			case PROGRAMMING_MODE -> List.of(PHASE_PROGRAMMING, PHASE_DEVICES);
		};
	}

	/**
	 * Scans the bus.
	 */
	public static Result scan(ScanBus bus, Request request, Listener listener) throws BusException {
		List<ResKey> notes = new ArrayList<>();
		List<FoundDevice> devices = new ArrayList<>();
		TreeSet<Integer> addresses = new TreeSet<>();

		if (request.target() == ScanTarget.GATEWAY) {
			listener.phase(PHASE_GATEWAY);
			listener.message(I18NConstants.MESSAGE_READING_GATEWAY__PATH.fill(bus.gateway()));
			try (ScanBus.LocalProperties local = bus.gatewayProperties()) {
				if (local == null) {
					notes.add(I18NConstants.NOTE_NO_LOCAL_MANAGEMENT__PATH.fill(bus.gateway()));
				} else {
					devices.add(identifyLocal(local));
				}
			} catch (BusException ex) {
				notes.add(I18NConstants.NOTE_LOCAL_MANAGEMENT_FAILED__PATH_REASON.fill(bus.gateway(), ex.getErrorKey()));
			}
			return new Result(devices, List.of(), notes);
		}

		ScanBus.Progress progress = new ScanBus.Progress() {
			@Override
			public void progress(int done, int total) {
				listener.checkCancelled();
				listener.progress(done, total);
			}

			@Override
			public void indeterminate() {
				listener.checkCancelled();
				listener.indeterminate();
			}

			@Override
			public void found(int address) {
				listener.message(I18NConstants.MESSAGE_FOUND__ADDRESS.fill(FoundDevice.formatAddress(address)));
			}
		};
		List<Integer> programming = List.of();
		switch (request.target()) {
			case LINE: {
				Line line = request.line();
				listener.phase(PHASE_LINE);
				listener.message(I18NConstants.MESSAGE_SCANNING_LINE__LINE.fill(line.toString()));
				addresses.addAll(bus.scanLine(line.area(), line.line(), progress));
				break;
			}
			case ROUTERS:
				listener.phase(PHASE_ROUTERS);
				listener.message(I18NConstants.MESSAGE_SCANNING_ROUTERS);
				addresses.addAll(bus.scanRouters(progress));
				break;
			case PROGRAMMING_MODE:
				listener.phase(PHASE_PROGRAMMING);
				listener.message(I18NConstants.MESSAGE_PROGRAMMING_MODE);
				listener.indeterminate();
				programming = bus.programmingMode();
				addresses.addAll(programming);
				break;
			case GATEWAY:
				throw new IllegalStateException("Read above.");
		}

		listener.phase(PHASE_DEVICES);
		int number = 0;
		for (Integer address : addresses) {
			listener.checkCancelled();
			listener.progress(number++, addresses.size());
			listener.message(I18NConstants.MESSAGE_READING__ADDRESS.fill(FoundDevice.formatAddress(address)));
			try {
				FoundDevice found;
				try (DeviceReader reader = bus.device(address)) {
					found = identify(reader, address);
				}
				if (found.descriptor() == null) {
					// A device may still be busy with the connection of the scan: once more.
					listener.message(I18NConstants.MESSAGE_READING_AGAIN__ADDRESS.fill(FoundDevice.formatAddress(address)));
					try (DeviceReader reader = bus.device(address)) {
						found = identify(reader, address);
					}
				}
				devices.add(found);
			} catch (BusException ex) {
				notes.add(I18NConstants.NOTE__LABEL_VALUE.fill(FoundDevice.formatAddress(address), ex.getErrorKey()));
			}
		}
		listener.progress(addresses.size(), addresses.size());
		return new Result(devices, programming, notes);
	}

	/**
	 * Reads what a device tells about itself, point to point, and ends the connection to it.
	 */
	public static FoundDevice identify(DeviceReader reader, int address) {
		FoundDevice result = read(reader, address);
		disconnect(reader);
		return result;
	}

	/**
	 * Reads what a device tells about itself, point to point, and leaves the connection to it open
	 * for further reads (a device that does not answer its descriptor is not read further).
	 */
	public static FoundDevice read(DeviceReader reader, int address) {
		List<ResKey> notes = new ArrayList<>();
		List<ResKey> loadStates = new ArrayList<>();
		Integer descriptor = null;
		try {
			byte[] value = reader.readDescriptor();
			if (value.length >= 2) {
				descriptor = Integer.valueOf((value[0] & 0xFF) << 8 | (value[1] & 0xFF));
			}
		} catch (BusException ex) {
			notes.add(I18NConstants.NOTE__LABEL_VALUE.fill(I18NConstants.READ_DESCRIPTOR, ex.getErrorKey()));
			return new FoundDevice(address, FoundDevice.Source.BUS, null, null, null, null, null, null, loadStates, notes);
		}

		Integer manufacturer = null;
		byte[] serial = null;
		byte[] programVersion = null;
		byte[] hardwareType = null;
		String orderInfo = null;
		if (descriptor != null && isBcu1(descriptor.intValue())) {
			try {
				byte[] id = reader.readMemory(BCU1_APPLICATION_ID, 4);
				if (id.length == 4) {
					programVersion = new byte[] { 0, id[0], id[1], id[2], id[3] };
					manufacturer = Integer.valueOf(id[0] & 0xFF);
				}
			} catch (BusException ex) {
				notes.add(I18NConstants.NOTE__LABEL_VALUE.fill(
					I18NConstants.READ_PROGRAM_IDENTITY__ADDRESS.fill(String.format("%04X", BCU1_APPLICATION_ID)),
					ex.getErrorKey()));
			}
		} else {
			byte[] id = property(reader, DEVICE_OBJECT, PID_MANUFACTURER_ID, I18NConstants.READ_MANUFACTURER, notes);
			if (id != null && id.length >= 2) {
				manufacturer = Integer.valueOf((id[0] & 0xFF) << 8 | (id[1] & 0xFF));
			}
			serial = property(reader, DEVICE_OBJECT, PID_SERIAL_NUMBER, I18NConstants.READ_SERIAL_NUMBER, notes);
			programVersion = property(reader, APPLICATION_OBJECT, LoadControls.PID_PROGRAM_VERSION,
				I18NConstants.READ_PROGRAM_VERSION, notes);
			hardwareType = property(reader, DEVICE_OBJECT, PID_HARDWARE_TYPE, I18NConstants.READ_HARDWARE_TYPE, notes);
			orderInfo = text(property(reader, DEVICE_OBJECT, PID_ORDER_INFO, I18NConstants.READ_ORDER_INFO, notes));
			for (int lsm = 1; lsm <= 3; lsm++) {
				byte[] state = property(reader, lsm, LoadControls.PID_LOAD_STATE_CONTROL,
					I18NConstants.READ_LOAD_STATE__LSM.fill(lsm), notes);
				if (state != null && state.length > 0) {
					loadStates.add(I18NConstants.NOTE__LABEL_VALUE.fill(lsm, LoadControls.stateName(state[0] & 0xFF)));
				}
			}
			byte[] run = property(reader, APPLICATION_OBJECT, PID_RUN_STATE, I18NConstants.READ_RUN_STATE, notes);
			if (run != null && run.length > 0) {
				loadStates.add(I18NConstants.NOTE__LABEL_VALUE.fill(I18NConstants.READ_RUN_STATE, runStateName(run[0] & 0xFF)));
			}
		}
		if (manufacturer == null && programVersion != null && programVersion.length >= 5) {
			manufacturer = Integer.valueOf((programVersion[0] & 0xFF) << 8 | (programVersion[1] & 0xFF));
		}
		return new FoundDevice(address, FoundDevice.Source.BUS, descriptor, manufacturer, serial, programVersion,
			hardwareType, orderInfo, loadStates, notes);
	}

	/**
	 * Reads whether a device is in programming mode: property {@value #PID_PROGRAMMING_MODE} of the
	 * device object, bit 0; for BCU 1 (mask 001x, no properties) bit 0 of the memory byte 0060.
	 *
	 * @param descriptor
	 *        The device descriptor read before.
	 * @param notes
	 *        Takes why the state could not be read.
	 * @return Whether the programming mode is on, <code>null</code> if it could not be read.
	 */
	public static Boolean programmingMode(DeviceReader reader, int descriptor, List<ResKey> notes) {
		try {
			byte[] value = isBcu1(descriptor) ? reader.readMemory(BCU1_PROGRAMMING_MODE, 1)
				: reader.readProperty(DEVICE_OBJECT, PID_PROGRAMMING_MODE, 1, 1);
			if (value.length == 0) {
				return null;
			}
			return Boolean.valueOf((value[0] & 0x01) != 0);
		} catch (BusException ex) {
			notes.add(I18NConstants.NOTE__LABEL_VALUE.fill(I18NConstants.READ_PROGRAMMING_MODE, ex.getErrorKey()));
			return null;
		}
	}

	/**
	 * Reads what the gateway tells about itself through local device management.
	 */
	public static FoundDevice identifyLocal(ScanBus.LocalProperties local) throws BusException {
		List<ResKey> notes = new ArrayList<>();
		byte[] address = local.readProperty(KNXNETIP_OBJECT_TYPE, PID_KNX_INDIVIDUAL_ADDRESS, 1, 1);
		if (address.length < 2) {
			throw new BusException(I18NConstants.SCAN_GATEWAY_NO_ADDRESS);
		}
		int individual = (address[0] & 0xFF) << 8 | (address[1] & 0xFF);
		byte[] id = localProperty(local, 0, PID_MANUFACTURER_ID, I18NConstants.READ_MANUFACTURER, notes);
		Integer manufacturer = id != null && id.length >= 2 ? Integer.valueOf((id[0] & 0xFF) << 8 | (id[1] & 0xFF)) : null;
		byte[] serial = localProperty(local, 0, PID_SERIAL_NUMBER, I18NConstants.READ_SERIAL_NUMBER, notes);
		byte[] hardwareType = localProperty(local, 0, PID_HARDWARE_TYPE, I18NConstants.READ_HARDWARE_TYPE, notes);
		String orderInfo = text(localProperty(local, 0, PID_ORDER_INFO, I18NConstants.READ_ORDER_INFO, notes));
		byte[] programVersion = localProperty(local, APPLICATION_OBJECT, LoadControls.PID_PROGRAM_VERSION,
			I18NConstants.READ_PROGRAM_VERSION, notes);
		return new FoundDevice(individual, FoundDevice.Source.LOCAL, null, manufacturer, serial, programVersion,
			hardwareType, orderInfo, List.of(), notes);
	}

	/**
	 * Whether a mask version is one of BCU 1 (<code>001x</code>), whose identity lies in memory.
	 */
	public static boolean isBcu1(int descriptor) {
		return (descriptor & 0xFFF0) == 0x0010;
	}

	private static byte[] property(DeviceReader reader, int object, int property, ResKey what, List<ResKey> notes) {
		try {
			byte[] value = reader.readProperty(object, property, 1, 1);
			return value.length == 0 ? null : value;
		} catch (BusException ex) {
			notes.add(I18NConstants.NOTE__LABEL_VALUE.fill(what, ex.getErrorKey()));
			return null;
		}
	}

	private static byte[] localProperty(ScanBus.LocalProperties local, int objectType, int property, ResKey what,
			List<ResKey> notes) {
		try {
			byte[] value = local.readProperty(objectType, property, 1, 1);
			return value.length == 0 ? null : value;
		} catch (BusException ex) {
			notes.add(I18NConstants.NOTE__LABEL_VALUE.fill(what, ex.getErrorKey()));
			return null;
		}
	}

	/**
	 * Ends the connection to a device; a failure is ignored, the connection ends anyway.
	 */
	public static void disconnect(DeviceReader reader) {
		try {
			reader.disconnect();
		} catch (BusException ex) {
			// The connection ends anyway.
		}
	}

	/**
	 * Printable order info as text, otherwise as hex.
	 */
	private static String text(byte[] value) {
		if (value == null) {
			return null;
		}
		int end = value.length;
		while (end > 0 && (value[end - 1] == 0 || value[end - 1] == ' ')) {
			end--;
		}
		byte[] trimmed = Arrays.copyOf(value, end);
		for (byte b : trimmed) {
			if (b < 0x20 || b > 0x7E) {
				return FoundDevice.hex(value);
			}
		}
		return new String(trimmed, StandardCharsets.ISO_8859_1);
	}

	private static ResKey runStateName(int state) {
		return switch (state) {
			case 0 -> I18NConstants.RUN_STATE_HALTED;
			case 1 -> I18NConstants.RUN_STATE_RUNNING;
			case 2 -> I18NConstants.RUN_STATE_READY;
			case 3 -> I18NConstants.RUN_STATE_TERMINATED;
			case 4 -> I18NConstants.RUN_STATE_STARTING;
			case 5 -> I18NConstants.RUN_STATE_SHUTTING_DOWN;
			default -> de.haumacher.ehome.programming.download.I18NConstants.LOAD_STATE_OTHER__NUMBER.fill(state);
		};
	}

}
