/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.server;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.cemi.CEMILData;

import de.haumacher.ehome.virtual.trace.TraceEntry;
import de.haumacher.ehome.virtual.trace.TraceEntry.Direction;
import de.haumacher.ehome.virtual.trace.TraceFormat;

/**
 * Decodes a frame of the virtual subnet into a {@link TraceEntry}: the transport-layer control
 * (connect, disconnect) and the application-layer service with its parameters.
 *
 * <p>
 * Group telegrams are not recorded.
 * </p>
 */
class FrameDecoder {

	private static final int T_CONNECT = 0x80;

	private static final int T_DISCONNECT = 0x81;

	private static final int T_ACK = 0xC2;

	private static final int T_NAK = 0xC3;

	private static final int MEMORY_READ = 0x0200;

	private static final int MEMORY_RESPONSE = 0x0240;

	private static final int MEMORY_WRITE = 0x0280;

	private static final int DESCRIPTOR_READ = 0x0300;

	private static final int DESCRIPTOR_RESPONSE = 0x0340;

	private static final int RESTART = 0x0380;

	private static final int PROPERTY_READ = 0x03D5;

	private static final int PROPERTY_RESPONSE = 0x03D6;

	private static final int PROPERTY_WRITE = 0x03D7;

	private static final int INDIVIDUAL_ADDRESS_WRITE = 0x00C0;

	/**
	 * The entry of a frame, <code>null</code> if it is not recorded.
	 *
	 * @param incoming
	 *        Whether the device receives the frame.
	 */
	static TraceEntry decode(Instant time, CEMILData frame, boolean incoming) {
		Direction direction = incoming ? Direction.RECEIVED : Direction.SENT;
		byte[] tpdu = frame.getPayload();
		if (tpdu.length == 0) {
			return null;
		}
		int source = frame.getSource().getRawAddress();
		KNXAddress destinationAddress = frame.getDestination();
		int destination = destinationAddress instanceof IndividualAddress ? destinationAddress.getRawAddress() : 0;
		if (!(destinationAddress instanceof IndividualAddress) && destinationAddress.getRawAddress() != 0) {
			// A group telegram.
			return null;
		}
		int control = tpdu[0] & 0xFF;
		if ((control & 0x80) != 0) {
			// Control data of the transport layer.
			if (control == T_CONNECT) {
				return new TraceEntry(time, direction, source, destination, TraceEntry.T_CONNECT, null, Map.of(), null);
			}
			if (control == T_DISCONNECT) {
				return new TraceEntry(time, direction, source, destination, TraceEntry.T_DISCONNECT, null, Map.of(), null);
			}
			Integer ackSequence = Integer.valueOf((control >>> 2) & 0x0F);
			if ((control & 0xC3) == T_ACK) {
				return new TraceEntry(time, direction, source, destination, TraceEntry.T_ACK, ackSequence, Map.of(), null);
			}
			if ((control & 0xC3) == T_NAK) {
				return new TraceEntry(time, direction, source, destination, TraceEntry.T_NAK, ackSequence, Map.of(), null);
			}
			return null;
		}
		if (tpdu.length < 2) {
			return null;
		}
		Integer sequence = (control & 0x40) != 0 ? Integer.valueOf((control >>> 2) & 0x0F) : null;
		int service = DataUnitBuilder.getAPDUService(tpdu);
		byte[] asdu = DataUnitBuilder.extractASDU(tpdu);
		Map<String, String> fields = new LinkedHashMap<>();
		String name;
		byte[] data = null;
		if (service == MEMORY_READ || service == MEMORY_RESPONSE || service == MEMORY_WRITE) {
			name = service == MEMORY_READ ? TraceEntry.MEMORY_READ
				: service == MEMORY_WRITE ? TraceEntry.MEMORY_WRITE : TraceEntry.MEMORY_RESPONSE;
			if (asdu.length < 3) {
				return other(time, direction, source, destination, sequence, service, asdu);
			}
			fields.put(TraceEntry.ADDRESS, String.format("%04X", (asdu[1] & 0xFF) << 8 | asdu[2] & 0xFF));
			fields.put(TraceEntry.COUNT, Integer.toString(asdu[0] & 0x3F));
			data = Arrays.copyOfRange(asdu, 3, asdu.length);
		} else if ((service & 0x3C0) == DESCRIPTOR_READ || (service & 0x3C0) == DESCRIPTOR_RESPONSE) {
			name = (service & 0x3C0) == DESCRIPTOR_READ ? TraceEntry.DESCRIPTOR_READ : TraceEntry.DESCRIPTOR_RESPONSE;
			// Calimero's ASDU starts with the 6 low bits of the APCI: the descriptor type.
			if (asdu.length < 1) {
				return other(time, direction, source, destination, sequence, service, asdu);
			}
			fields.put(TraceEntry.TYPE, Integer.toString(asdu[0] & 0x3F));
			data = Arrays.copyOfRange(asdu, 1, asdu.length);
		} else if (service == PROPERTY_READ || service == PROPERTY_RESPONSE || service == PROPERTY_WRITE) {
			name = service == PROPERTY_READ ? TraceEntry.PROPERTY_READ
				: service == PROPERTY_WRITE ? TraceEntry.PROPERTY_WRITE : TraceEntry.PROPERTY_RESPONSE;
			if (asdu.length < 4) {
				return other(time, direction, source, destination, sequence, service, asdu);
			}
			fields.put(TraceEntry.OBJECT, Integer.toString(asdu[0] & 0xFF));
			fields.put(TraceEntry.PROPERTY, Integer.toString(asdu[1] & 0xFF));
			fields.put(TraceEntry.START, Integer.toString((asdu[2] & 0x0F) << 8 | asdu[3] & 0xFF));
			fields.put(TraceEntry.COUNT, Integer.toString((asdu[2] & 0xF0) >>> 4));
			data = Arrays.copyOfRange(asdu, 4, asdu.length);
		} else if ((service & 0x3C0) == RESTART) {
			name = TraceEntry.RESTART;
			data = Arrays.copyOfRange(tpdu, 1, tpdu.length);
			// The APCI with the restart type.
			data[0] &= 0x3F;
			if (data.length == 1 && data[0] == 0) {
				data = null;
			}
		} else if (service == INDIVIDUAL_ADDRESS_WRITE && asdu.length >= 2) {
			name = TraceEntry.INDIVIDUAL_ADDRESS_WRITE;
			fields.put(TraceEntry.INDIVIDUAL, TraceFormat.individual((asdu[0] & 0xFF) << 8 | asdu[1] & 0xFF));
		} else {
			return other(time, direction, source, destination, sequence, service, asdu);
		}
		return new TraceEntry(time, direction, source, destination, name, sequence, fields, data);
	}

	private static TraceEntry other(Instant time, Direction direction, int source, int destination, Integer sequence,
			int service, byte[] asdu) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put(TraceEntry.APCI, String.format("%03X", service));
		return new TraceEntry(time, direction, source, destination, TraceEntry.OTHER, sequence, fields, asdu);
	}

}
