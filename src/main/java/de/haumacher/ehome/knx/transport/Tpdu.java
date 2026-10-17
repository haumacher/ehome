/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx.transport;

import tuwien.auto.calimero.DataUnitBuilder;

/**
 * The transport protocol data units of KNX: the control of a connection (connect, disconnect,
 * acknowledgements) and the data in a connection with its sequence number, and the application
 * services in them.
 *
 * <p>
 * Shared by E-Home's client side ({@link TransportClient}) and the virtual device, so that both read
 * and write the same bits.
 * </p>
 */
public final class Tpdu {

	/** T_Connect. */
	public static final int CONNECT = 0x80;

	/** T_Disconnect. */
	public static final int DISCONNECT = 0x81;

	/** T_ACK, with the sequence number in bits 2 to 5. */
	public static final int ACK = 0xC2;

	/** T_NAK, with the sequence number in bits 2 to 5. */
	public static final int NAK = 0xC3;

	/** T_Data_Connected, with the sequence number in bits 2 to 5. */
	public static final int DATA_CONNECTED = 0x40;

	/** A_Memory_Read. */
	public static final int MEMORY_READ = 0x0200;

	/** A_Memory_Response. */
	public static final int MEMORY_RESPONSE = 0x0240;

	/** A_Memory_Write. */
	public static final int MEMORY_WRITE = 0x0280;

	/** A_DeviceDescriptor_Read. */
	public static final int DESCRIPTOR_READ = 0x0300;

	/** A_DeviceDescriptor_Response. */
	public static final int DESCRIPTOR_RESPONSE = 0x0340;

	/** A_IndividualAddress_Write (broadcast). */
	public static final int INDIVIDUAL_ADDRESS_WRITE = 0x00C0;

	/** A_IndividualAddress_Read (broadcast). */
	public static final int INDIVIDUAL_ADDRESS_READ = 0x0100;

	/** A_IndividualAddress_Response (broadcast). */
	public static final int INDIVIDUAL_ADDRESS_RESPONSE = 0x0140;

	/** A_Restart. */
	public static final int RESTART = 0x0380;

	/** A_Authorize_Request. */
	public static final int AUTHORIZE_REQUEST = 0x03D1;

	/** A_Authorize_Response. */
	public static final int AUTHORIZE_RESPONSE = 0x03D2;

	/** A_PropertyValue_Read. */
	public static final int PROPERTY_READ = 0x03D5;

	/** A_PropertyValue_Response. */
	public static final int PROPERTY_RESPONSE = 0x03D6;

	/** A_PropertyValue_Write. */
	public static final int PROPERTY_WRITE = 0x03D7;

	/** A_PropertyDescription_Read. */
	public static final int PROPERTY_DESCRIPTION_READ = 0x03D8;

	/** A_PropertyDescription_Response. */
	public static final int PROPERTY_DESCRIPTION_RESPONSE = 0x03D9;

	private Tpdu() {
		// Constants and functions only.
	}

	/**
	 * Whether the unit is control data of a connection (connect, disconnect, ACK, NAK).
	 */
	public static boolean isControl(byte[] tpdu) {
		return tpdu.length > 0 && (tpdu[0] & 0x80) != 0;
	}

	/**
	 * Whether the unit is data in a connection.
	 */
	public static boolean isConnectedData(byte[] tpdu) {
		return tpdu.length > 1 && (tpdu[0] & 0xC0) == DATA_CONNECTED;
	}

	/**
	 * Whether the unit is the control of the given kind ({@link #CONNECT}, {@link #DISCONNECT}).
	 */
	public static boolean is(byte[] tpdu, int control) {
		return tpdu.length > 0 && (tpdu[0] & 0xFF) == control;
	}

	/**
	 * Whether the unit is an acknowledgement ({@link #ACK} or {@link #NAK}) of the given kind.
	 */
	public static boolean isAcknowledgement(byte[] tpdu, int kind) {
		return tpdu.length > 0 && (tpdu[0] & 0xC3) == kind;
	}

	/**
	 * The sequence number of data in a connection or of an acknowledgement.
	 */
	public static int sequence(byte[] tpdu) {
		return (tpdu[0] >>> 2) & 0x0F;
	}

	/**
	 * A control unit without sequence number ({@link #CONNECT}, {@link #DISCONNECT}).
	 */
	public static byte[] control(int control) {
		return new byte[] { (byte) control };
	}

	/**
	 * An acknowledgement ({@link #ACK} or {@link #NAK}) of the given sequence number.
	 */
	public static byte[] acknowledgement(int kind, int sequence) {
		return new byte[] { (byte) (kind | (sequence & 0x0F) << 2) };
	}

	/**
	 * The application unit sent in a connection with the given sequence number.
	 */
	public static byte[] connected(byte[] apdu, int sequence) {
		byte[] result = apdu.clone();
		result[0] = (byte) (apdu[0] & 0x03 | DATA_CONNECTED | (sequence & 0x0F) << 2);
		return result;
	}

	/**
	 * The application unit sent without connection (the transport bits cleared).
	 */
	public static byte[] connectionless(byte[] apdu) {
		byte[] result = apdu.clone();
		result[0] = (byte) (apdu[0] & 0x03);
		return result;
	}

	/**
	 * The application service of a unit with application data.
	 */
	public static int service(byte[] tpdu) {
		return DataUnitBuilder.getAPDUService(tpdu);
	}

	/**
	 * The service data of a unit with application data (for the services with data in the low bits
	 * of the APCI, those bits are the first byte).
	 */
	public static byte[] asdu(byte[] tpdu) {
		return DataUnitBuilder.extractASDU(tpdu);
	}

}
