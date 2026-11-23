/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The formats of the group communication tables: the group address table, the association table,
 * the object table and the configuration byte of a communication object, for the BIM M112 family
 * (mask version 7.x, System 7), for BCU 1 (mask version 1.2, <code>MV-0012</code>) and for BCU 2 (mask
 * versions 2.0, 2.1, 2.5).
 *
 * <p>
 * Verified against the memory ETS wrote into the devices of the user's test system (mask 0x0705, see
 * <code>src/test/fixtures/readback/</code>):
 * </p>
 * <ul>
 * <li>Address table (<code>AddressTable_Bcu1</code>): the number of entries including the first
 * one, the first entry, then the group addresses, two bytes each, most significant byte first,
 * distinct and in ascending order. The first entry is the individual address on a mask that keeps
 * it there, else what the program's static data holds there (see below, "The first entry of the
 * address table").</li>
 * <li>Association table (<code>AssociationTable_M112</code>): the number of associations, then per
 * association the index of the group address in the address table (the first entry being index 0)
 * and the number of the communication object, in ascending order of the object number.</li>
 * <li>Object table (<code>GroupObjectTable_M112</code>, {@link ObjectTableFormat#M112}): the number
 * of objects, the address of the RAM flags (two bytes), then four bytes per object: the address of
 * its value (two bytes), the configuration byte, the type (size) byte. ETS takes the table from the
 * static data of the program and recomputes the configuration byte, and for an object with an
 * association the type byte from the size of the object's reference in effect (a parameter may
 * decide it).</li>
 * </ul>
 *
 * <h2>The first entry of the address table</h2>
 * <p>
 * The download never writes the individual address: only the programming of the individual address
 * does. Whether the device keeps it in the first entry of the address table, the master data of the
 * mask say (<code>knx_master.xml</code>, {@link DownloadImage#individualAddressInTable(de.haumacher.ehome.model.operational.MaskVersion, int)}):
 * </p>
 * <ul>
 * <li>BCU 1 and BCU 2 (masks 0010 to 0013, 0020, 0021, 0025): the mask's resource
 * <code>IndividualAddress</code> lies in memory right after the count of the address table (0117,
 * the table at 0116).</li>
 * <li>BIM M112 with download stamps (masks 0705, 2705, 5705): the mask has the resource
 * <code>GroupAddressTableStamp</code>, the stamp of the address table, whose checksum covers the
 * table as the device holds it, the individual address in the first entry. Verified: the devices
 * of the test system (mask 0705) hold their individual address there, ETS does not write it (the
 * programs' static data mask it out, ETS writes the count and the group addresses only,
 * <code>ets6-download-1.3.5.txt</code>), and the checksum of the stamp ETS wrote covers it.</li>
 * <li>Every other mask, among them 0700, 0701 and 0310: the mask names neither, nothing in its
 * resources places the individual address in the table. The first entry is the program's static
 * data. Verified on a device of mask 0701 (the read-back <code>Mask0701-1.1.74-*.hex</code>): it
 * holds <code>FFFF</code> there, the static data of its program, and answers at its individual
 * address all the same.</li>
 * </ul>
 *
 * <h2>The order of the associations</h2>
 * <p>
 * ETS writes first one association per object, the address the object sends to, in ascending order
 * of the object number, then the further addresses of the objects that have several, again by
 * object ({@link #associationOrder(Collection)}). Verified on three devices: the device of mask
 * 0701 holds (3, 1), (1, 8), (4, 1), (2, 8) (object 1 sends to address 3 and receives 4, object 8
 * sends to 1 and receives 2), a device of mask 0705 with two such objects holds them after all
 * others (haumacher/ehome #222), and the devices of mask 0705 without such an object hold their
 * associations by object number.
 * </p>
 *
 * <h2>BCU 1 (mask 0012)</h2>
 * <p>
 * Verified against a device of mask 0012 that ETS programmed (a Siemens 5WG1 562-2AB31, program
 * with dynamic table management, haumacher/ehome #222); see the points below. The sources: the resources of mask
 * <code>MV-0012</code> in <code>knx_master.xml</code>, and the static data ETS ships with BCU 1
 * programs (the EEPROM image 0100-01FF of the program's only segment), which holds the tables'
 * templates at the places the program's <code>AddressTable</code>, <code>AssociationTable</code> and
 * <code>ComObjectTable</code> name and the pointers to them:
 * </p>
 * <ul>
 * <li>The tables lie in the EEPROM 0100-01FF together with the system header, the parameters and
 * the application. The address table lies at the fixed address of the mask's resource
 * <code>GroupAddressTable</code> (0116); the association table and the object table where the
 * pointers <code>GroupAssociationTablePtr</code> (0111) and <code>GroupObjectTablePtr</code> (0112)
 * point, one byte each of flavour <code>Ptr_StandardMemory100</code>: the table's address minus
 * 0100.</li>
 * <li>Address table: the format <code>AddressTable_Bcu1</code> above. Its individual address
 * (0117-0118, the mask's resource <code>IndividualAddress</code>) is not written by a download of the
 * application (the static data masks it out, the mask's load procedure skips it).</li>
 * <li>Association table (<code>AssociationTable_Bcu1</code>): a place per object as BCU 2 has it
 * ({@link #associationSlots(Collection, int)}), <code>FE</code> for an object without an
 * association (the device holds 24 entries for its 24 objects). With dynamic table management ETS
 * places it right after the address table, the pointer 0111 naming that place (the device: 0127
 * after an address table of 8 entries at 0116, where its program's static data has the template
 * at 014D); the static data after the table stays.</li>
 * <li>The program's identity: the resource <code>ApplicationId</code> (0104-0107) holds the low
 * byte of the manufacturer, the application number and its version (the device:
 * <code>01 20 71 01</code>), not the placeholder of the static data.</li>
 * <li>Object table (<code>GroupObjectTable_Bcu11</code>, {@link ObjectTableFormat#BCU1}): the number
 * of objects, the address of the RAM flags (one byte), then three bytes per object: the address of
 * its value (one byte), the configuration byte (the bits of the BIM M112's, as the static data of
 * BCU 1 programs shows them: D7 for update, transmit, write, communication, low priority), the type
 * byte (codes 0 to 14 only). An object not in effect keeps the configuration of the static data,
 * without the communication flag (the device: D3 where the static data has D7).</li>
 * </ul>
 *
 * <h2>BCU 2 (masks 0020, 0021, 0025)</h2>
 * <p>
 * Not verified against a device. The address table is <code>AddressTable_Bcu1</code>. The
 * association table (<code>AssociationTable_Bcu2</code>) has the bytes above with a reserved place
 * per object first ({@link #associationSlots(Collection, int)}). The object table
 * (<code>GroupObjectTable_Bcu2</code>, {@link ObjectTableFormat#BCU2}) has the bytes of BCU 1: the
 * static data of BCU 2 programs parse as count, RAM flags pointer of one byte and three bytes per
 * object with configuration bytes of the bits above; the value pointers are offsets into the
 * segments the load control "task control 2" names (its <code>Seg0</code>, <code>Seg1</code>), which
 * E-Home takes from the static data as it is.
 * </p>
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

	/**
	 * The layout of an object table.
	 *
	 * @param flavour
	 *        The name of the format as the mask's resource <code>GroupObjectTable</code> gives it.
	 * @param header
	 *        The size of the header: the number of objects and the address of the RAM flags.
	 * @param descriptor
	 *        The size of the descriptor of an object.
	 * @param config
	 *        The position of the configuration byte in a descriptor.
	 * @param type
	 *        The position of the type (size) byte in a descriptor.
	 * @param maxType
	 *        The largest type code the format knows (see {@link GroupTables#objectType(int)}).
	 */
	public record ObjectTableFormat(String flavour, int header, int descriptor, int config, int type, int maxType) {

		/** The object table of the BIM M112 family (mask 7.x). */
		public static final ObjectTableFormat M112 =
			new ObjectTableFormat("GroupObjectTable_M112", OBJECT_TABLE_HEADER, OBJECT_DESCRIPTOR, OBJECT_CONFIG, OBJECT_TYPE, 20);

		/** The object table of BCU 1 (mask 1.2): one-byte pointers, no types beyond 14 bytes. */
		public static final ObjectTableFormat BCU1 = new ObjectTableFormat("GroupObjectTable_Bcu11", 2, 3, 1, 2, 14);

		/**
		 * The object table of BCU 2 (masks 2.0, 2.1, 2.5): the bytes of {@link #BCU1}; the value pointers
		 * are offsets into the segments the load control "task control 2" names (see
		 * {@link GroupTables}).
		 */
		public static final ObjectTableFormat BCU2 = new ObjectTableFormat("GroupObjectTable_Bcu2", 2, 3, 1, 2, 14);

		/**
		 * The format of the given name, <code>null</code> if it is not supported.
		 */
		public static ObjectTableFormat byFlavour(String flavour) {
			for (ObjectTableFormat format : new ObjectTableFormat[] { M112, BCU1, BCU2 }) {
				if (format.flavour().equals(flavour)) {
					return format;
				}
			}
			return null;
		}
	}

	/**
	 * The address index of an entry of a BCU 2 association table holding no association: the
	 * reserved place of an object that sends to no group address.
	 */
	public static final int UNUSED_SLOT = 0xFE;

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
	 *        The index of the group address in the address table, from 1 (index 0 is the first entry,
	 *        no group address).
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
	 * @param firstEntry
	 *        The first entry: the individual address of the device (area, line and device number in 4,
	 *        4 and 8 bits) on a mask that keeps it in the table, else the program's static data there
	 *        ({@link DownloadImage#individualAddressInTable(de.haumacher.ehome.model.operational.MaskVersion, int)}).
	 * @param groupAddresses
	 *        The group addresses in the order of {@link #addressOrder(Collection)}.
	 */
	public static byte[] addressTable(int firstEntry, List<Integer> groupAddresses) {
		int entries = groupAddresses.size() + 1;
		if (entries > 255) {
			throw new IllegalArgumentException("Too many group addresses: " + groupAddresses.size());
		}
		byte[] result = new byte[1 + 2 * entries];
		result[0] = (byte) entries;
		put16(result, 1, firstEntry);
		int position = 3;
		for (int address : groupAddresses) {
			put16(result, position, address);
			position += 2;
		}
		return result;
	}

	/**
	 * The associations in the order of the association table, as ETS writes it: first one
	 * association per object, the address the object sends to (the device sends to the first address
	 * it finds for the object; without a send connection the receive address of the lowest index), in
	 * the order of the object numbers; then the further associations of objects with several
	 * addresses, by object number and the index of the address.
	 *
	 * <p>
	 * Verified on the devices ETS programmed: a device of mask 0705 with two objects of two addresses
	 * each holds <code>(0D, 04) (0E, 05) ... (0F, 04) (10, 05)</code> (haumacher/ehome #222), the device
	 * of mask 0701 <code>(3, 1) (1, 8) (4, 1) (2, 8)</code>; the devices without such an object hold the
	 * associations by object number.
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
		// The first association of each object (the address it sends to) in the order of the objects,
		// then the further ones, again by object.
		List<Association> primaries = new ArrayList<>();
		List<Association> further = new ArrayList<>();
		Set<Integer> objects = new HashSet<>();
		for (Association association : distinct) {
			if (objects.add(Integer.valueOf(association.objectNumber()))) {
				primaries.add(association);
			} else {
				further.add(association);
			}
		}
		primaries.addAll(further);
		return primaries;
	}

	/**
	 * The associations in the order of the association table of BCU 2
	 * (<code>AssociationTable_Bcu2</code>): one reserved place per communication object first, the
	 * place of object <i>n</i> being entry <i>n</i>; it holds the address the object sends to, an
	 * entry {@link #UNUSED_SLOT} <i>n</i> if the object has no association. The other associations
	 * follow in the order of {@link #associationOrder(Collection)}; while there are any, a free place
	 * takes the first of them whose address no object sends to from its own place.
	 *
	 * <p>
	 * The address an object sends to is the one of its send connection, else the first of its
	 * receive connections (an object with associations always sends to one of them); a second send
	 * connection is received only. The order within the other associations, and the use of the free
	 * places, are the rules ETS follows as the open-source download tool xknx-editor recorded them
	 * against ETS (<code>association_slots</code>); the reserved places themselves are shown by the
	 * templates of the association table BCU 2 programs ship (100 entries <code>FE 00</code>,
	 * <code>FE 01</code>, ... with the address indexes of the template's connections between them).
	 * </p>
	 *
	 * @param objectCount
	 *        The number of communication objects (the count of the object table).
	 */
	public static List<Association> associationSlots(Collection<Association> associations, int objectCount) {
		List<Association> ordered = associationOrder(associations);
		Association[] slots = new Association[objectCount];
		List<Association> rest = new ArrayList<>();
		Set<Integer> placed = new HashSet<>();
		for (Association association : ordered) {
			int object = association.objectNumber();
			if (object < objectCount && placed.add(object)) {
				slots[object] = new Association(association.addressIndex(), object, true);
			} else {
				rest.add(new Association(association.addressIndex(), object, false));
			}
		}
		for (int slot = 0; slot < objectCount && !rest.isEmpty(); slot++) {
			if (slots[slot] != null) {
				continue;
			}
			for (int candidate = 0; candidate < rest.size(); candidate++) {
				int index = rest.get(candidate).addressIndex();
				boolean sendAddress = false;
				for (int other = 0; other < objectCount; other++) {
					Association own = slots[other];
					if (own != null && own.objectNumber() == other && own.addressIndex() == index) {
						sendAddress = true;
						break;
					}
				}
				if (!sendAddress) {
					slots[slot] = rest.remove(candidate);
					break;
				}
			}
		}
		List<Association> result = new ArrayList<>();
		for (int slot = 0; slot < objectCount; slot++) {
			result.add(slots[slot] != null ? slots[slot] : new Association(UNUSED_SLOT, slot, false));
		}
		result.addAll(rest);
		return result;
	}

	/**
	 * The associations an association table holds, in the order of the table, without the free
	 * places of BCU 2: per entry the address index and the object number; the send flag tells the
	 * address the object sends to.
	 *
	 * @param table
	 *        The association table: its count, then two bytes per entry.
	 * @param slots
	 *        Whether the table is one of BCU 2 ({@link #associationSlots(Collection, int)}): the entry
	 *        <i>n</i> of object <i>n</i> holds its send address, the entries {@link #UNUSED_SLOT} no
	 *        association. Otherwise the first entry of an object is its send address.
	 */
	public static List<Association> associations(byte[] table, boolean slots) {
		List<Association> result = new ArrayList<>();
		int count = table[0] & 0xFF;
		Set<Integer> seen = new HashSet<>();
		for (int n = 0; n < count; n++) {
			int index = table[1 + 2 * n] & 0xFF;
			int object = table[2 + 2 * n] & 0xFF;
			boolean send;
			if (slots) {
				if (index == UNUSED_SLOT) {
					continue;
				}
				send = object == n;
			} else {
				send = seen.add(object);
			}
			result.add(new Association(index, object, send));
		}
		if (slots) {
			// The send address of an object first.
			List<Association> sorted = new ArrayList<>();
			for (Association association : result) {
				if (association.send()) {
					sorted.add(association);
				}
			}
			for (Association association : result) {
				if (!association.send()) {
					sorted.add(association);
				}
			}
			return sorted;
		}
		return result;
	}

	/**
	 * The association table of associations in the order of {@link #associationOrder(Collection)}
	 * (or of {@link #associationSlots(Collection, int)}).
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
