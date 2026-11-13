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
	 * What a scan should do.
	 *
	 * @param area
	 *        The area of the line to scan, <code>null</code> to scan no line.
	 * @param line
	 *        The line to scan.
	 * @param routers
	 *        Whether to scan for couplers and routers (device 0 of every line).
	 * @param gateway
	 *        Whether to read the gateway itself through local device management.
	 * @param programmingMode
	 *        Whether to look for the devices in programming mode.
	 */
	public record Request(Integer area, int line, boolean routers, boolean gateway, boolean programmingMode) {
		// Value.
	}

	/**
	 * The outcome of a scan.
	 *
	 * @param devices
	 *        The devices found, by address (the gateway first, if it was read).
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
	 * <code>PHASE_*</code> constants.
	 */
	public static List<String> phases(Request request) {
		List<String> result = new ArrayList<>();
		if (request.gateway()) {
			result.add(PHASE_GATEWAY);
		}
		if (request.area() != null) {
			result.add(PHASE_LINE);
		}
		if (request.routers()) {
			result.add(PHASE_ROUTERS);
		}
		if (request.programmingMode()) {
			result.add(PHASE_PROGRAMMING);
		}
		result.add(PHASE_DEVICES);
		return result;
	}

	/**
	 * Scans the bus.
	 */
	public static Result scan(ScanBus bus, Request request, Listener listener) throws BusException {
		List<ResKey> notes = new ArrayList<>();
		List<FoundDevice> devices = new ArrayList<>();
		TreeSet<Integer> addresses = new TreeSet<>();
		Integer gatewayAddress = null;

		if (request.gateway()) {
			listener.phase(PHASE_GATEWAY);
			listener.message(I18NConstants.MESSAGE_READING_GATEWAY__PATH.fill(bus.gateway()));
			FoundDevice gateway = null;
			try (ScanBus.LocalProperties local = bus.gatewayProperties()) {
				if (local == null) {
					notes.add(I18NConstants.NOTE_NO_LOCAL_MANAGEMENT__PATH.fill(bus.gateway()));
				} else {
					gateway = identifyLocal(local);
				}
			} catch (BusException ex) {
				notes.add(I18NConstants.NOTE_LOCAL_MANAGEMENT_FAILED__PATH_REASON.fill(bus.gateway(), ex.getErrorKey()));
			}
			if (gateway != null) {
				devices.add(gateway);
				gatewayAddress = gateway.address();
			}
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
		if (request.area() != null) {
			String line = request.area() + "." + request.line();
			listener.phase(PHASE_LINE);
			listener.message(I18NConstants.MESSAGE_SCANNING_LINE__LINE.fill(line));
			addresses.addAll(bus.scanLine(request.area().intValue(), request.line(), progress));
		}
		if (request.routers()) {
			listener.phase(PHASE_ROUTERS);
			listener.message(I18NConstants.MESSAGE_SCANNING_ROUTERS);
			addresses.addAll(bus.scanRouters(progress));
		}
		List<Integer> programming = List.of();
		if (request.programmingMode()) {
			listener.phase(PHASE_PROGRAMMING);
			listener.message(I18NConstants.MESSAGE_PROGRAMMING_MODE);
			listener.indeterminate();
			programming = bus.programmingMode();
			addresses.addAll(programming);
		}
		if (gatewayAddress != null) {
			// The gateway does not answer through its own tunnel; it was read above.
			addresses.remove(gatewayAddress);
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
	 * Reads what a device tells about itself, point to point.
	 */
	public static FoundDevice identify(DeviceReader reader, int address) {
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
			disconnect(reader);
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
		disconnect(reader);
		return new FoundDevice(address, FoundDevice.Source.BUS, descriptor, manufacturer, serial, programVersion,
			hardwareType, orderInfo, loadStates, notes);
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

	private static void disconnect(DeviceReader reader) {
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
