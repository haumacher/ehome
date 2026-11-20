/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import com.top_logic.basic.util.ResKey;

/**
 * What a device on the bus told about itself when it was scanned ({@link BusScanner}).
 *
 * <p>
 * Every value is <code>null</code> where the device did not answer or does not have it; the reasons
 * are in {@link #notes()}.
 * </p>
 *
 * @param address
 *        The individual address (area, line, device in 4, 4 and 8 bits).
 * @param source
 *        How the device was reached.
 * @param descriptor
 *        The device descriptor type 0 (the mask version, e.g. <code>0x0705</code>).
 * @param manufacturerId
 *        The KNX manufacturer ID of the device (device object, property 12), or of its program if
 *        the device object does not tell it.
 * @param serialNumber
 *        The serial number, 6 bytes (device object, property 11).
 * @param programVersion
 *        The application program the device runs: manufacturer, application number and version, 5
 *        bytes (application object, property 13; memory 0104-0107 for BCU 1 with the manufacturer
 *        of the device).
 * @param hardwareType
 *        The hardware type (device object, property 78); <code>null</code> also where the device
 *        reports <code>FF</code> bytes only, the value of a type not set (haumacher/ehome #206).
 * @param orderInfo
 *        The order info (device object, property 15), as text where it is printable, otherwise as
 *        its bytes in hex ({@link #hex(byte[])}); <code>null</code> also where the device reports
 *        <code>FF</code> bytes only, an order info not set (haumacher/ehome #206). Only the text is an order number
 *        ({@link #orderNumber()}): many devices report a manufacturer-specific encoding there
 *        (haumacher/ehome #179).
 * @param loadStates
 *        The states of the load state machines 1 to 3, for a person.
 * @param notes
 *        What could not be read, for a person.
 */
public record FoundDevice(int address, Source source, Integer descriptor, Integer manufacturerId, byte[] serialNumber,
		byte[] programVersion, byte[] hardwareType, String orderInfo, List<ResKey> loadStates, List<ResKey> notes) {

	/**
	 * How a device was reached.
	 */
	public enum Source {
		/** Point to point over the bus (management connection). */
		BUS,

		/** The gateway itself through KNXnet/IP local device management. */
		LOCAL;
	}

	/**
	 * The individual address as text (<code>1.3.5</code>).
	 */
	public String addressText() {
		return formatAddress(address);
	}

	/**
	 * The device descriptor as text (<code>0705</code>), <code>null</code> if not read.
	 */
	public String descriptorText() {
		return descriptor == null ? null : String.format("%04X", descriptor);
	}

	/**
	 * The serial number as KNX writes it (<code>0083:1234ABCD</code>), <code>null</code> if not read.
	 */
	public String serialText() {
		if (serialNumber == null || serialNumber.length != 6) {
			return serialNumber == null ? null : hex(serialNumber);
		}
		String hex = HexFormat.of().withUpperCase().formatHex(serialNumber);
		return hex.substring(0, 4) + ":" + hex.substring(4);
	}

	/**
	 * The serial number as ETS stores it in a project (<code>DeviceInstance/@SerialNumber</code>,
	 * base64), the form of <code>ehome.physical:Device#serialNumber</code>; <code>null</code> if not
	 * read.
	 */
	public String serialStored() {
		return serialNumber == null ? null : Base64.getEncoder().encodeToString(serialNumber);
	}

	/**
	 * The program version as text (<code>00 83 00 0C 21</code>), <code>null</code> if not read.
	 */
	public String programVersionText() {
		return programVersion == null ? null : hex(programVersion);
	}

	/**
	 * The manufacturer named by the program version, <code>null</code> if not read.
	 */
	public Integer programManufacturer() {
		return programVersion == null || programVersion.length < 5 ? null
			: Integer.valueOf((programVersion[0] & 0xFF) << 8 | (programVersion[1] & 0xFF));
	}

	/**
	 * The application number of the program version, <code>null</code> if not read.
	 */
	public Integer applicationNumber() {
		return programVersion == null || programVersion.length < 5 ? null
			: Integer.valueOf((programVersion[2] & 0xFF) << 8 | (programVersion[3] & 0xFF));
	}

	/**
	 * The application version of the program version, <code>null</code> if not read.
	 */
	public Integer applicationVersion() {
		return programVersion == null || programVersion.length < 5 ? null : Integer.valueOf(programVersion[4] & 0xFF);
	}

	/**
	 * The hardware type as text, <code>null</code> if not read.
	 */
	public String hardwareTypeText() {
		return hardwareType == null ? null : hex(hardwareType);
	}

	/**
	 * The order info as an order number to compare with a catalog's, <code>null</code> if it was not
	 * read or the device reports bytes that are no text (MDT, for example, reports
	 * <code>14 30 34 31 36 FF FF 03 0F 60</code> for <code>AKK-0416.03</code>).
	 *
	 * <p>
	 * The scan writes the order info in hex only where a byte is not printable; so a text that reads
	 * as hex digits of printable bytes only was text in the first place.
	 * </p>
	 */
	public String orderNumber() {
		if (orderInfo == null) {
			return null;
		}
		if (!orderInfo.matches("[0-9A-F]{2}( [0-9A-F]{2})*")) {
			return orderInfo;
		}
		byte[] bytes = HexFormat.ofDelimiter(" ").parseHex(orderInfo);
		int end = bytes.length;
		while (end > 0 && (bytes[end - 1] == 0 || bytes[end - 1] == ' ')) {
			end--;
		}
		for (int n = 0; n < end; n++) {
			if (bytes[n] < 0x20 || bytes[n] > 0x7E) {
				return null;
			}
		}
		return orderInfo;
	}

	/**
	 * Bytes as text, two hex digits each, separated by blanks.
	 */
	public static String hex(byte[] data) {
		return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(data);
	}

	/**
	 * An individual address as text (<code>1.3.5</code>).
	 */
	public static String formatAddress(int address) {
		return (address >>> 12 & 0x0F) + "." + (address >>> 8 & 0x0F) + "." + (address & 0xFF);
	}

}
