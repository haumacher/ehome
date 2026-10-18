/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx.transport;

/**
 * The sequence numbers of one connection of the KNX transport layer, as both partners keep them:
 * the number of the next data unit to send and of the next one expected, each counting modulo 16.
 *
 * <p>
 * The rules of the specification, with one tolerance: an acknowledgement of the number before the
 * one awaited is {@link Ack#STALE} (the partner received a data unit twice and acknowledged it
 * twice), not out of sequence. The strict rule would end the connection, which a stranded frame
 * of the partner's transport layer (Calimero 2.6, haumacher/ehome #31) turns into a failed download.
 * </p>
 */
public final class ConnectionSequence {

	/**
	 * What a received data unit is.
	 */
	public enum Data {
		/** The one expected: to be acknowledged and processed. */
		NEW,

		/** The one before (the partner missed the acknowledgement): to be acknowledged again, not processed. */
		REPEAT,

		/** Any other: to be answered with T_NAK. */
		OUT_OF_SEQUENCE;
	}

	/**
	 * What a received acknowledgement is.
	 */
	public enum Ack {
		/** Of the data unit awaiting it. */
		CURRENT,

		/** Of the data unit before (acknowledged before): to be ignored. */
		STALE,

		/** Of any other: the connection is broken. */
		OUT_OF_SEQUENCE;
	}

	private int _send;

	private int _receive;

	/**
	 * Starts over (a new connection).
	 */
	public void reset() {
		_send = 0;
		_receive = 0;
	}

	/**
	 * The sequence number of the next data unit to send.
	 */
	public int send() {
		return _send;
	}

	/**
	 * The sequence number of the next data unit expected.
	 */
	public int receive() {
		return _receive;
	}

	/**
	 * Classifies a received data unit; a {@link Data#NEW} one advances the number expected.
	 */
	public Data received(int sequence) {
		if (sequence == _receive) {
			_receive = (_receive + 1) & 0x0F;
			return Data.NEW;
		}
		if (sequence == ((_receive - 1) & 0x0F)) {
			return Data.REPEAT;
		}
		return Data.OUT_OF_SEQUENCE;
	}

	/**
	 * Classifies a received acknowledgement; a {@link Ack#CURRENT} one advances the number to send.
	 *
	 * @param awaiting
	 *        Whether a data unit sent awaits its acknowledgement.
	 */
	public Ack acknowledged(int sequence, boolean awaiting) {
		if (awaiting && sequence == _send) {
			_send = (_send + 1) & 0x0F;
			return Ack.CURRENT;
		}
		if (sequence == ((_send - 1) & 0x0F)) {
			return Ack.STALE;
		}
		return Ack.OUT_OF_SEQUENCE;
	}

}
