/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.GroupTables;
import de.haumacher.ehome.programming.GroupTables.Association;
import de.haumacher.ehome.programming.ParameterEncoding;
import de.haumacher.ehome.programming.ParameterEncoding.Bits;

/**
 * Test of {@link ParameterEncoding} and {@link GroupTables} with hand-computed bytes.
 */
@SuppressWarnings("javadoc")
public class TestParameterEncoding extends TestCase {

	public void testIntegersAtBitOffsets() {
		// A 1-bit flag at bit offset 5: bit offsets count from the most significant bit.
		assertEquals("04", write(1, 0, 5, ParameterEncoding.unsigned(1, 1)));
		// Two 4-bit values in one byte.
		byte[] memory = new byte[1];
		ParameterEncoding.write(memory, 0, 0, ParameterEncoding.unsigned(0xA, 4), false);
		ParameterEncoding.write(memory, 0, 4, ParameterEncoding.unsigned(0x5, 4), false);
		assertEquals("a5", hex(memory));
		// A 2-bit value at the end of the byte.
		assertEquals("03", write(1, 0, 6, ParameterEncoding.unsigned(3, 2)));
		// An enumeration value of 3 bits at bit offset 1.
		assertEquals("20", write(1, 0, 1, ParameterEncoding.unsigned(2, 3)));
		// 8, 16 and 32 bits, most significant byte first.
		assertEquals("00c8", write(2, 1, 0, ParameterEncoding.unsigned(200, 8)));
		assertEquals("1234", write(2, 0, 0, ParameterEncoding.unsigned(0x1234, 16)));
		assertEquals("12345678", write(4, 0, 0, ParameterEncoding.unsigned(0x12345678L, 32)));
		// A value spanning bytes at a bit offset.
		assertEquals("0abc", write(2, 0, 4, ParameterEncoding.unsigned(0xABC, 12)));
		// A bit offset beyond the byte continues in the next one (a union member at a bit offset
		// within its union).
		assertEquals("000f", write(2, 0, 12, ParameterEncoding.unsigned(0xF, 4)));
	}

	public void testOtherBitsKept() {
		// 3000 in 14 bits from the start of 2 bytes (the Tastsensor's delays): 0010 1110 1110 00..
		byte[] memory = { (byte) 0xFF, (byte) 0xFF };
		ParameterEncoding.write(memory, 0, 0, ParameterEncoding.unsigned(3000, 14), false);
		assertEquals("2ee3", hex(memory));
		// A 1-bit flag cleared in a byte of ones.
		memory = new byte[] { (byte) 0xFF };
		ParameterEncoding.write(memory, 0, 5, ParameterEncoding.unsigned(0, 1), false);
		assertEquals("fb", hex(memory));
	}

	public void testSigned() {
		assertEquals("ff", hex(ParameterEncoding.signed(-1, 8)));
		assertEquals("80", hex(ParameterEncoding.signed(-128, 8)));
		assertEquals("7f", hex(ParameterEncoding.signed(127, 8)));
		assertEquals("fffe", hex(ParameterEncoding.signed(-2, 16)));
		assertEquals("ffffff85", hex(ParameterEncoding.signed(-123, 32)));
		assertEquals("0d", write(1, 0, 4, ParameterEncoding.signed(-3, 4)));
		assertIllegal(() -> ParameterEncoding.signed(128, 8));
		assertIllegal(() -> ParameterEncoding.signed(-129, 8));
		assertIllegal(() -> ParameterEncoding.unsigned(16, 4));
		assertIllegal(() -> ParameterEncoding.unsigned(-1, 8));
	}

	public void testLittleEndian() {
		byte[] memory = new byte[4];
		ParameterEncoding.write(memory, 0, 0, ParameterEncoding.unsigned(0x1234, 16), true);
		assertEquals("34120000", hex(memory));
		ParameterEncoding.write(memory, 0, 0, ParameterEncoding.unsigned(0x12345678L, 32), true);
		assertEquals("78563412", hex(memory));
		// A value within one byte is the same in both orders.
		memory = new byte[1];
		ParameterEncoding.write(memory, 0, 4, ParameterEncoding.unsigned(0x5, 4), true);
		assertEquals("05", hex(memory));
		// Little endian needs whole bytes.
		assertIllegal(() -> ParameterEncoding.write(new byte[2], 0, 4, ParameterEncoding.unsigned(0xABC, 12), true));
	}

	public void testDpt9() {
		assertEquals("0000", hex(ParameterEncoding.dpt9(0)));
		assertEquals("0064", hex(ParameterEncoding.dpt9(1)));
		// 2175 needs exponent 1: 1087.5 rounds to 1088 = 0x440.
		assertEquals("0c40", hex(ParameterEncoding.dpt9(21.75)));
		assertEquals("0c40", hex(ParameterEncoding.dpt9(21.76)));
		// -550: 12-bit two's complement 0xDDA, its sign in the top bit.
		assertEquals("85da", hex(ParameterEncoding.dpt9(-5.5)));
		assertEquals("7fff", hex(ParameterEncoding.dpt9(670760.96)));
		assertEquals("f800", hex(ParameterEncoding.dpt9(-671088.64)));
		assertIllegal(() -> ParameterEncoding.dpt9(670761));
	}

	public void testIeee754() {
		assertEquals("3fc00000", hex(ParameterEncoding.ieee754Single(1.5)));
		assertEquals("c0000000", hex(ParameterEncoding.ieee754Single(-2)));
		assertEquals("41ae0000", hex(ParameterEncoding.ieee754Single(21.75)));
		assertEquals("3ff0000000000000", hex(ParameterEncoding.ieee754Double(1)));
		assertEquals("c035c00000000000", hex(ParameterEncoding.ieee754Double(-21.75)));
	}

	public void testText() {
		assertEquals("41420000", hex(ParameterEncoding.text("AB", 32, StandardCharsets.ISO_8859_1, false)));
		assertEquals("41424344", hex(ParameterEncoding.text("ABCD", 32, StandardCharsets.ISO_8859_1, false)));
		assertEquals("41424300", hex(ParameterEncoding.text("ABC", 32, StandardCharsets.ISO_8859_1, true)));
		assertEquals("c4d6dc00", hex(ParameterEncoding.text("ÄÖÜ", 32, StandardCharsets.ISO_8859_1, false)));
		assertEquals("00000000", hex(ParameterEncoding.text("", 32, StandardCharsets.ISO_8859_1, false)));
		assertIllegal(() -> ParameterEncoding.text("ABCD", 32, StandardCharsets.ISO_8859_1, true));
		assertIllegal(() -> ParameterEncoding.text("ABCDE", 32, StandardCharsets.ISO_8859_1, false));
		// A text at a byte offset in memory.
		assertEquals("ff4142", write(3, 1, 0, ParameterEncoding.text("AB", 16, StandardCharsets.ISO_8859_1, false), (byte) 0xFF));
	}

	public void testTime() {
		// A time is the count in the unit of its type: 3000 ms in 16 bits.
		assertEquals("0bb8", hex(ParameterEncoding.unsigned(3000, 16)));
		// 20 s in 8 bits at a byte offset.
		assertEquals("0014", write(2, 1, 0, ParameterEncoding.unsigned(20, 8)));
	}

	public void testDateAndIpAddress() {
		assertEquals("060712", hex(ParameterEncoding.date("2018-07-06")));
		assertEquals("1f0c59", hex(ParameterEncoding.date("2089-12-31")));
		assertIllegal(() -> ParameterEncoding.date("1989-12-31"));
		assertIllegal(() -> ParameterEncoding.date("06.07.2018"));
		assertEquals("c0a8b251", hex(ParameterEncoding.ipAddress("192.168.178.81")));
		assertEquals("e000170c", hex(ParameterEncoding.ipAddress("224.0.23.12")));
		assertEquals(16, ParameterEncoding.ipAddress("fe80::1").toBytes().length);
		assertIllegal(() -> ParameterEncoding.ipAddress("router.local"));
	}

	public void testUnionMembersShareBytes() {
		// Two members of an 8-bit union at byte 1: an 8-bit value, and a 4-bit value at the
		// member's bit offset 4. Only the member in effect is written; writing the second after the
		// first changes only its own bits.
		byte[] memory = new byte[2];
		int unionByte = 1;
		ParameterEncoding.write(memory, unionByte, 0, ParameterEncoding.unsigned(0x12, 8), false);
		assertEquals("0012", hex(memory));
		ParameterEncoding.write(memory, unionByte + 0, 0 + 4, ParameterEncoding.unsigned(0x7, 4), false);
		assertEquals("0017", hex(memory));
	}

	public void testAddressTable() {
		List<Integer> addresses = GroupTables.addressOrder(List.of(3, 1, 0x0801, 2, 1));
		assertEquals(List.of(1, 2, 3, 0x0801), addresses);
		assertEquals("051332000100020003" + "0801", HexFormat.of().formatHex(GroupTables.addressTable(0x1332, addresses)));
		assertEquals("011305", HexFormat.of().formatHex(GroupTables.addressTable(0x1305, List.of())));
	}

	public void testAssociationTable() {
		List<Association> order = GroupTables.associationOrder(List.of(
			new Association(3, 4, true),
			new Association(2, 0, false),
			new Association(1, 0, true),
			new Association(1, 2, true),
			new Association(1, 2, false)));
		// By object; the send address first; an address once per object.
		assertEquals(List.of(new Association(1, 0, true), new Association(2, 0, false), new Association(1, 2, true),
			new Association(3, 4, true)), order);
		assertEquals("0401000200010203" + "04", HexFormat.of().formatHex(GroupTables.associationTable(order)));
	}

	public void testObjectConfig() {
		// The Tastsensor's object 0 (transmit, write, communication, low) with an association.
		assertEquals(0x57, GroupTables.objectConfig(0xDF, false, true, true, false, true, GroupTables.priorityCode("Low"), true));
		// Without association the communication flag is cleared.
		assertEquals(0xDB, GroupTables.objectConfig(0xDF, true, true, true, true, true, 3, false));
		// The EEPROM bit of the static data is kept.
		assertEquals(0x23, GroupTables.objectConfig(0x20, false, false, false, false, true, 3, false));
		assertEquals(1, GroupTables.priorityCode("High"));
		assertEquals(2, GroupTables.priorityCode("Alert"));
	}

	public void testCasePatterns() {
		// ETS when@test: values separated by spaces, or comparisons.
		assertTrue(DeviceConfiguration.matches("1", "1"));
		assertTrue(DeviceConfiguration.matches("0 2 3", "2"));
		assertFalse(DeviceConfiguration.matches("0 2 3", "1"));
		assertTrue(DeviceConfiguration.matches("<5", "4"));
		assertFalse(DeviceConfiguration.matches("<5", "5"));
		assertTrue(DeviceConfiguration.matches(">=3", "3"));
		assertTrue(DeviceConfiguration.matches("!=0", "7"));
		assertTrue(DeviceConfiguration.matches("1.5", "1.50"));
		assertFalse(DeviceConfiguration.matches("1", null));
	}

	private static String write(int length, int byteOffset, int bitOffset, Bits value) {
		return write(length, byteOffset, bitOffset, value, (byte) 0);
	}

	private static String write(int length, int byteOffset, int bitOffset, Bits value, byte fill) {
		byte[] memory = new byte[length];
		Arrays.fill(memory, fill);
		ParameterEncoding.write(memory, byteOffset, bitOffset, value, false);
		return hex(memory);
	}

	private static String hex(Bits value) {
		return hex(value.toBytes());
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	private static void assertIllegal(Runnable action) {
		try {
			action.run();
			fail("Expected an IllegalArgumentException.");
		} catch (IllegalArgumentException ex) {
			// Expected.
		}
	}

	public static Test suite() {
		return new TestSuite(TestParameterEncoding.class);
	}

}
