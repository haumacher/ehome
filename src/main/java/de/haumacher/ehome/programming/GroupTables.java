/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * The formats of the group communication tables of a device with a mask of the BIM M112 family
 * (mask version 7.x, System 7): the group address table, the association table and the
 * configuration byte of a communication object.
 *
 * <p>
 * Verified against the memory ETS wrote into the devices of the user's test system (mask 0x0705, see
 * <code>src/test/fixtures/readback/</code>):
 * </p>
 * <ul>
 * <li>Address table (<code>AddressTable_Bcu1</code>): the number of entries including the
 * individual address, the individual address, then the group addresses, two bytes each, most
 * significant byte first, distinct and in ascending order.</li>
 * <li>Association table (<code>AssociationTable_M112</code>): the number of associations, then per
 * association the index of the group address in the address table (the individual address being
 * index 0) and the number of the communication object, in ascending order of the object
 * number.</li>
 * <li>Object table (<code>GroupObjectTable_M112</code>): the number of objects, the address of the
 * RAM flags (two bytes), then four bytes per object: the address of its value (two bytes), the
 * configuration byte, the type (size) byte. ETS takes the table from the static data of the program
 * and recomputes the configuration byte, and for an object with an association the type byte from
 * the size of the object's reference in effect (a parameter may decide it).</li>
 * </ul>
 */
public class GroupTables {

	/** The size of the header of an object table of the BIM M112 family: count and RAM flags pointer. */
	public static final int OBJECT_TABLE_HEADER = 3;

	/** The size of an object descriptor of the BIM M112 family. */
	public static final int OBJECT_DESCRIPTOR = 4;

	/** The position of the configuration byte in an object descriptor of the BIM M112 family. */
	public static final int OBJECT_CONFIG = 2;

	/** The position of the type (size) byte in an object descriptor of the BIM M112 family. */
	public static final int OBJECT_TYPE = 3;

	/** Configuration bit: the object's value is updated by responses to a read (ETS: UpdateFlag). */
	public static final int UPDATE = 0x80;

	/** Configuration bit: the object sends (ETS: TransmitFlag). */
	public static final int TRANSMIT = 0x40;

	/**
	 * Configuration bit: the value of the object lies in EEPROM instead of RAM; part of the program,
	 * not of the configuration, so ETS keeps it from the static data.
	 */
	public static final int VALUE_IN_EEPROM = 0x20;

	/** Configuration bit: the object's value may be written over the bus (ETS: WriteFlag). */
	public static final int WRITE = 0x10;

	/** Configuration bit: the object's value may be read over the bus (ETS: ReadFlag). */
	public static final int READ = 0x08;

	/**
	 * Configuration bit: the object communicates (ETS: CommunicationFlag); ETS sets it only for
	 * objects with at least one association.
	 */
	public static final int COMMUNICATION = 0x04;

	/**
	 * An association of a group address with a communication object.
	 *
	 * @param addressIndex
	 *        The index of the group address in the address table, from 1 (index 0 is the individual
	 *        address).
	 * @param objectNumber
	 *        The number of the communication object.
	 * @param send
	 *        Whether the object sends to the address.
	 */
	public record Association(int addressIndex, int objectNumber, boolean send) {
		// Value.
	}

	/**
	 * The group addresses in the order of the address table: distinct, ascending.
	 */
	public static List<Integer> addressOrder(Collection<Integer> groupAddresses) {
		return new ArrayList<>(new TreeSet<>(groupAddresses));
	}

	/**
	 * The address table.
	 *
	 * @param individualAddress
	 *        The individual address of the device (area, line and device number in 4, 4 and 8 bits).
	 * @param groupAddresses
	 *        The group addresses in the order of {@link #addressOrder(Collection)}.
	 */
	public static byte[] addressTable(int individualAddress, List<Integer> groupAddresses) {
		int entries = groupAddresses.size() + 1;
		if (entries > 255) {
			throw new IllegalArgumentException("Too many group addresses: " + groupAddresses.size());
		}
		byte[] result = new byte[1 + 2 * entries];
		result[0] = (byte) entries;
		put16(result, 1, individualAddress);
		int position = 3;
		for (int address : groupAddresses) {
			put16(result, position, address);
			position += 2;
		}
		return result;
	}

	/**
	 * The associations in the order of the association table: by object number; for an object with
	 * several addresses, the address it sends to first (the device sends to the first address it
	 * finds for the object), then by the index of the address.
	 *
	 * <p>
	 * The order by object number is verified; no reference device has an object with several
	 * addresses, so the order within an object follows the KNX rule for the sending address.
	 * </p>
	 */
	public static List<Association> associationOrder(Collection<Association> associations) {
		List<Association> result = new ArrayList<>(new LinkedHashSet<>(associations));
		result.sort(Comparator.comparingInt(Association::objectNumber)
			.thenComparing(a -> !a.send())
			.thenComparingInt(Association::addressIndex));
		// An address associated with an object both as send and receive address appears once.
		List<Association> distinct = new ArrayList<>();
		for (Association association : result) {
			boolean known = distinct.stream().anyMatch(
				a -> a.objectNumber() == association.objectNumber() && a.addressIndex() == association.addressIndex());
			if (!known) {
				distinct.add(association);
			}
		}
		return distinct;
	}

	/**
	 * The association table of associations in the order of {@link #associationOrder(Collection)}.
	 */
	public static byte[] associationTable(List<Association> associations) {
		if (associations.size() > 255) {
			throw new IllegalArgumentException("Too many associations: " + associations.size());
		}
		byte[] result = new byte[1 + 2 * associations.size()];
		result[0] = (byte) associations.size();
		int position = 1;
		for (Association association : associations) {
			if (association.addressIndex() > 255 || association.objectNumber() > 255) {
				throw new IllegalArgumentException("Association out of range: " + association);
			}
			result[position++] = (byte) association.addressIndex();
			result[position++] = (byte) association.objectNumber();
		}
		return result;
	}

	/**
	 * The configuration byte of a communication object.
	 *
	 * @param staticConfig
	 *        The configuration byte in the static data of the program; its {@link #VALUE_IN_EEPROM}
	 *        bit is kept.
	 * @param priority
	 *        The priority as {@link #priorityCode(String)} gives it.
	 * @param associated
	 *        Whether the object has an association: without one, ETS clears the communication flag.
	 */
	public static int objectConfig(int staticConfig, boolean update, boolean transmit, boolean write, boolean read,
			boolean communication, int priority, boolean associated) {
		return (staticConfig & VALUE_IN_EEPROM)
			| (update ? UPDATE : 0)
			| (transmit ? TRANSMIT : 0)
			| (write ? WRITE : 0)
			| (read ? READ : 0)
			| (communication && associated ? COMMUNICATION : 0)
			| (priority & 0x03);
	}

	/**
	 * The code of a priority (<code>ehome.physical:Priority</code>) in the configuration byte, the
	 * coding of the priority of a telegram: low 3, high (KNX: normal) 1, alert (KNX: urgent) 2.
	 *
	 * <p>
	 * Only low is verified: no object of the reference devices has another priority.
	 * </p>
	 */
	public static int priorityCode(String priority) {
		if (priority == null) {
			return 3;
		}
		switch (priority) {
			case "High":
				return 1;
			case "Alert":
				return 2;
			default:
				return 3;
		}
	}

	/**
	 * The type code of a communication object of the given size in bits, as the type byte of its
	 * descriptor holds it (KNX: 0 to 6 for 1 to 7 bits, 7 for a byte, 8 to 14 for 2, 3, 4, 6, 8, 10
	 * and 14 bytes, 15 to 20 for 5, 7, 9, 11, 12 and 13 bytes); ETS writes it from the size of the
	 * object's reference in effect, so that an object whose size a parameter decides is described
	 * as the parameter says.
	 *
	 * @return The code, or -1 for a size that has none.
	 */
	public static int objectType(int bits) {
		if (bits >= 1 && bits <= 7) {
			return bits - 1;
		}
		return switch (bits) {
			case 8 -> 7;
			case 16 -> 8;
			case 24 -> 9;
			case 32 -> 10;
			case 48 -> 11;
			case 64 -> 12;
			case 80 -> 13;
			case 112 -> 14;
			case 40 -> 15;
			case 56 -> 16;
			case 72 -> 17;
			case 88 -> 18;
			case 96 -> 19;
			case 104 -> 20;
			default -> -1;
		};
	}

	private static void put16(byte[] buffer, int position, int value) {
		buffer[position] = (byte) (value >>> 8);
		buffer[position + 1] = (byte) value;
	}

}
