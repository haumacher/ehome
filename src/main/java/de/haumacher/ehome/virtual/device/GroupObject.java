/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.device;

import java.util.Arrays;
import java.util.List;

/**
 * A communication object of a {@link GroupDevice}: its number, size, flags and group addresses, and
 * the value it holds.
 *
 * @param number
 *        The number of the object in its device.
 * @param name
 *        The name of the object, for a person.
 * @param bits
 *        The size of the value in bits: up to 6 bits a value travels in the octet of the service
 *        (the short form), above as <code>bits / 8</code> octets of its own.
 * @param flags
 *        The flags, see {@link #COMMUNICATION}, {@link #READ}, {@link #WRITE}, {@link #TRANSMIT},
 *        {@link #UPDATE}.
 * @param send
 *        The group address the object sends and answers on, -1 for none.
 * @param addresses
 *        All group addresses of the object (the sending one included) whose telegrams reach it.
 */
public record GroupObject(int number, String name, int bits, int flags, int send, List<Integer> addresses) {

	/** The object communicates at all (ETS: CommunicationFlag). */
	public static final int COMMUNICATION = 0x01;

	/** The object answers a read on its sending address (ETS: ReadFlag). */
	public static final int READ = 0x02;

	/** A write to one of its addresses sets the object's value (ETS: WriteFlag). */
	public static final int WRITE = 0x04;

	/** The object sends its value when it changes (ETS: TransmitFlag). */
	public static final int TRANSMIT = 0x08;

	/** A response to a read on one of its addresses sets the object's value (ETS: UpdateFlag). */
	public static final int UPDATE = 0x10;

	/** The flags of an object that is written by the bus (an actuator's input). */
	public static final int INPUT = COMMUNICATION | WRITE;

	/** The flags of an object that sends and answers reads (a sensor, an actuator's status). */
	public static final int OUTPUT = COMMUNICATION | READ | TRANSMIT;

	/**
	 * Creates a {@link GroupObject}.
	 */
	public GroupObject {
		addresses = List.copyOf(addresses);
		if (send >= 0 && !addresses.contains(Integer.valueOf(send))) {
			throw new IllegalArgumentException("The sending address is not one of the object's addresses: " + this);
		}
	}

	/**
	 * An object with a sending address only.
	 */
	public static GroupObject of(int number, String name, int bits, int flags, int send) {
		return new GroupObject(number, name, bits, flags, send, List.of(Integer.valueOf(send)));
	}

	/**
	 * Whether the object has all the given flags.
	 */
	public boolean has(int flag) {
		return (flags & flag) == flag;
	}

	/**
	 * Whether the value travels in the octet of the service.
	 */
	public boolean shortForm() {
		return bits <= 6;
	}

	/**
	 * Whether telegrams to the address reach the object.
	 */
	public boolean listensTo(int address) {
		return addresses.contains(Integer.valueOf(address));
	}

	/**
	 * Whether the data has the length of the object's value (one octet in the short form).
	 */
	public boolean fits(byte[] data) {
		return data.length == (shortForm() ? 1 : (bits + 7) / 8);
	}

	@Override
	public String toString() {
		return number + " " + name + " (" + bits + " bit, flags " + Integer.toHexString(flags) + ", send " + send
			+ ", addresses " + Arrays.toString(addresses.toArray()) + ")";
	}

}
