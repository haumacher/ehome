/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.trace;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One service of a trace of the virtual device: a transport connection opened or closed, or an
 * application-layer service with its parameters and data.
 *
 * @param time
 *        When the virtual device received or sent it.
 * @param direction
 *        Whether the device received it, sent it, or whether it is an event of the device itself
 *        (a timer of its transport layer, a frame it ignored).
 * @param source
 *        The individual address of the sender.
 * @param destination
 *        The address of the receiver (individual address, 0 for a broadcast).
 * @param service
 *        The service, e.g. <code>T_Connect</code>, <code>A_Memory_Write</code>.
 * @param sequence
 *        The sequence number of a service in a transport connection, <code>null</code> outside one.
 * @param fields
 *        The parameters of the service by name, in the order they are written (see
 *        {@link TraceFormat}).
 * @param data
 *        The data of the service (memory, property value), empty for none.
 */
public record TraceEntry(Instant time, Direction direction, int source, int destination, String service, Integer sequence,
		Map<String, String> fields, byte[] data) {

	/**
	 * Where a {@link TraceEntry} comes from.
	 */
	public enum Direction {
		/** Received by the device (from the programming tool): <code>&gt;</code>. */
		RECEIVED('>'),

		/** Sent by the device: <code>&lt;</code>. */
		SENT('<'),

		/** An event of the device itself, nothing on the wire: <code>*</code>. */
		EVENT('*');

		private final char _symbol;

		Direction(char symbol) {
			_symbol = symbol;
		}

		/**
		 * The character of the direction in a trace.
		 */
		public char symbol() {
			return _symbol;
		}

		/**
		 * The direction of the given character, <code>null</code> if there is none.
		 */
		public static Direction of(String symbol) {
			for (Direction direction : values()) {
				if (symbol.length() == 1 && symbol.charAt(0) == direction._symbol) {
					return direction;
				}
			}
			return null;
		}
	}

	/** Event: no acknowledgement within the timeout, the frame is sent again (field {@link #REPEAT}). */
	public static final String TL_ACK_TIMEOUT = "TL_AckTimeout";

	/** Event: no acknowledgement after the last repetition, the device ends the connection. */
	public static final String TL_REPETITIONS_EXHAUSTED = "TL_RepetitionsExhausted";

	/** Event: no service within the connection timeout, the device ends the connection. */
	public static final String TL_CONNECTION_TIMEOUT = "TL_ConnectionTimeout";

	/** Event: an acknowledgement of the previous sequence number arrived again and was ignored. */
	public static final String TL_STALE_ACK = "TL_StaleAck";

	/** Event: a frame not fitting the connection (field {@link #REASON}); the device ends the connection. */
	public static final String TL_PROTOCOL_ERROR = "TL_ProtocolError";

	/** Event: a connect of another partner while a connection is open; refused with a disconnect. */
	public static final String TL_REJECTED = "TL_Rejected";

	/** Field: the number of a repetition. */
	public static final String REPEAT = "repeat";

	/** Field: why an event happened. */
	public static final String REASON = "reason";

	/** Field: the type of a device descriptor. */
	public static final String TYPE = "type";

	/** Field: the interface object of a property access. */
	public static final String OBJECT = "object";

	/** Field: the property of a property access. */
	public static final String PROPERTY = "property";

	/** Field: the first element of a property access. */
	public static final String START = "start";

	/** Field: the number of elements or bytes. */
	public static final String COUNT = "count";

	/** Field: the memory address (hexadecimal). */
	public static final String ADDRESS = "address";

	/** Field: the new individual address of A_IndividualAddress_Write. */
	public static final String INDIVIDUAL = "individual";

	/** Field: the APCI of a service without a name of its own (hexadecimal). */
	public static final String APCI = "apci";

	/** Service: open a transport connection. */
	public static final String T_CONNECT = "T_Connect";

	/** Service: close a transport connection. */
	public static final String T_DISCONNECT = "T_Disconnect";

	/** Service: positive acknowledgement of a service in a connection (the sequence number acknowledged). */
	public static final String T_ACK = "T_Ack";

	/** Service: negative acknowledgement of a service in a connection. */
	public static final String T_NAK = "T_Nak";

	/** Service: read the device descriptor. */
	public static final String DESCRIPTOR_READ = "A_DeviceDescriptor_Read";

	/** Service: the device descriptor. */
	public static final String DESCRIPTOR_RESPONSE = "A_DeviceDescriptor_Response";

	/** Service: read a property. */
	public static final String PROPERTY_READ = "A_PropertyValue_Read";

	/** Service: write a property. */
	public static final String PROPERTY_WRITE = "A_PropertyValue_Write";

	/** Service: a property value. */
	public static final String PROPERTY_RESPONSE = "A_PropertyValue_Response";

	/** Service: read memory. */
	public static final String MEMORY_READ = "A_Memory_Read";

	/** Service: write memory. */
	public static final String MEMORY_WRITE = "A_Memory_Write";

	/** Service: memory content. */
	public static final String MEMORY_RESPONSE = "A_Memory_Response";

	/** Service: restart. */
	public static final String RESTART = "A_Restart";

	/** Service: write the individual address (broadcast). */
	public static final String INDIVIDUAL_ADDRESS_WRITE = "A_IndividualAddress_Write";

	/** Service: an application-layer service without a name of its own. */
	public static final String OTHER = "A_Other";

	/**
	 * Creates a {@link TraceEntry}; the fields are copied.
	 */
	public TraceEntry {
		fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
		data = data == null ? new byte[0] : data.clone();
	}

	/**
	 * Whether the device received the entry.
	 */
	public boolean incoming() {
		return direction == Direction.RECEIVED;
	}

	/**
	 * Whether the device sent the entry.
	 */
	public boolean outgoing() {
		return direction == Direction.SENT;
	}

	/**
	 * The data, a copy.
	 */
	@Override
	public byte[] data() {
		return data.clone();
	}

	/**
	 * A field as text, <code>null</code> if the entry has none of that name.
	 */
	public String field(String name) {
		return fields.get(name);
	}

	/**
	 * A numeric field: decimal, the {@link #ADDRESS} and {@link #APCI} hexadecimal.
	 *
	 * @return The value, -1 if the entry has no such field.
	 */
	public int number(String name) {
		String value = fields.get(name);
		if (value == null) {
			return -1;
		}
		return ADDRESS.equals(name) || APCI.equals(name) ? Integer.parseInt(value, 16) : Integer.parseInt(value);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof TraceEntry entry && time.equals(entry.time) && direction == entry.direction
			&& source == entry.source && destination == entry.destination && service.equals(entry.service)
			&& Objects.equals(sequence, entry.sequence) && fields.equals(entry.fields) && Arrays.equals(data, entry.data);
	}

	@Override
	public int hashCode() {
		return Objects.hash(time, direction, source, destination, service, sequence, fields) * 31 + Arrays.hashCode(data);
	}

	@Override
	public String toString() {
		return TraceFormat.format(this);
	}

}
