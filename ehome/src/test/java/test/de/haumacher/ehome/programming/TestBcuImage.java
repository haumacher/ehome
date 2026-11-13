/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.Logger;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.I18NConstants;
import de.haumacher.ehome.programming.download.Operation;

/**
 * Test of the download image and the download of BCU 1 programs (mask 1.2, haumacher/ehome #13) on
 * the artificial programs of the fixture <code>TestBcu1.knxproj</code> (see
 * <code>src/test/fixtures/make-fixtures.py</code>), with bytes computed by hand.
 *
 * <p>
 * Both devices are at 1.0.1 and 1.0.2 and have the parameters Betriebsart (8 bits at 0150) = 2 and
 * Verhalten (2 bits at 0151, bit offset 4) = 2 set, Verzögerung (4 bits at 0151) and Zeit (16 bits
 * at 0152) at their defaults 3 and 300. Their object 0 (1 bit; communication, write) sends to 0/0/3
 * and receives 0/0/1, object 1 (1 bit; communication, read, transmit) sends to 0/1/1, object 2 (1
 * byte) has no association.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBcuImage extends BasicTestCase {

	private static final String WITH_DATA = "P-01A0-0_DI-1";

	private static final String WITHOUT_DATA = "P-01A0-0_DI-2";

	/** The block of the segment of the program with static data. */
	private static final String SEGMENT = "Segment M-0008_A-FFF1-01-0000_AS-0100";

	private List<TLObject> _projects;

	public void testBcu1() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestBcu1.knxproj");
		_projects = new ArrayList<>(instances("ehome.project:Project"));
		_projects.removeAll(before);
		assertEquals(1, _projects.size());
		try {
			checkImage();
			checkImageWithoutData();
			checkPlan();
			checkPlanWithoutData();
			checkDownload();
			checkCompare();
		} finally {
			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			try (Transaction tx = kb.beginTransaction()) {
				for (TLObject project : _projects) {
					project.tDelete();
				}
				tx.commit();
			}
		}
	}

	/**
	 * The program with static data: one block 0100-01FF, the static data with the tables, the object
	 * table and the parameters patched in; the individual address and 0101-0103 not written.
	 */
	private void checkImage() {
		DownloadImage image = DownloadImage.compute(device(WITH_DATA));
		Logger.info("Download image of the BCU 1 device with static data:\n" + image.dump(), TestBcuImage.class);
		assertEquals(image.dump(), List.of(), image.getProblems());
		assertEquals(1, image.getBlocks().size());
		Block block = image.getBlocks().get(0);
		assertEquals(0x0100, block.address());
		assertEquals(256, block.data().length);

		// The header as the static data has it, with the pointers to the association table (0125)
		// and the object table (0136).
		assertBytes(block, 0x0100, "ff 00 00 00 08 ff f1 01 ff 00 00 00 00 ff 61 63 2f 25 36 00 00 00");
		assertMask(block, 0x0100, "ff 00 00 00 ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff");

		// The address table at 0116 (the mask's GroupAddressTable): 4 entries, the individual address
		// (not written), 0/0/1, 0/0/3, 0/1/1; the rest of its space keeps the static data.
		assertEquals(0x0116, image.getAddressTable().address());
		assertBytes(block, 0x0116, "04 ff ff 00 01 00 03 01 01 00 00 00 00 00 00");
		assertMask(block, 0x0116, "ff 00 00 ff ff ff ff ff ff ff ff ff ff ff ff");

		// The association table at 0125 (the program's place, named by the pointer 0111): object 0
		// sends to index 2 (0/0/3) first, then index 1 (0/0/1); object 1 to index 3 (0/1/1).
		assertEquals(0x0125, image.getAssociationTable().address());
		assertBytes(block, 0x0125, "03 02 00 01 00 03 01 00");

		// The object table at 0136: 3 objects, RAM flags 00CE; configuration bytes from the flags
		// (object 2 without association: no communication flag, its value-in-EEPROM bit kept); type
		// bytes of the associated objects from their size.
		assertEquals(0x0136, image.getObjectTable().address());
		assertEquals(11, image.getObjectTable().data().length);
		assertBytes(block, 0x0136, "03 ce d0 17 00 d1 4f 00 d2 7b 07");

		// The parameters: 02, 0011 10 11 (the low bits from the static data), 01 2C.
		assertBytes(block, 0x0150, "02 3b 01 2c");
		assertMask(block, 0x0150, "ff ff ff ff");
		assertBytes(block, 0x01FF, "5a");
	}

	/**
	 * The program without static data: the block holds exactly the parameters' bits, the tables and
	 * the pointers; the object table cannot be computed (the addresses of the objects' values are
	 * not known), which is a problem.
	 */
	private void checkImageWithoutData() {
		DownloadImage image = DownloadImage.compute(device(WITHOUT_DATA));
		Logger.info("Download image of the BCU 1 device without static data:\n" + image.dump(), TestBcuImage.class);
		assertEquals(List.of("The object table has no static data in its segment."),
			image.getProblems().stream().map(DownloadPlan::english).toList());
		assertEquals(1, image.getBlocks().size());
		Block block = image.getBlocks().get(0);
		assertEquals(0x0100, block.address());

		byte[] expected = new byte[256];
		byte[] mask = new byte[256];
		put(expected, mask, 0x11, "25 36", "ff ff");
		put(expected, mask, 0x16, "04 00 00 00 01 00 03 01 01", "ff 00 00 ff ff ff ff ff ff");
		put(expected, mask, 0x25, "03 02 00 01 00 03 01", "ff ff ff ff ff ff ff");
		// Verzögerung 3 in bits 0-3, Verhalten 2 in bits 4-5 of 0151; bits 6-7 are nobody's.
		put(expected, mask, 0x50, "02 38 01 2c", "ff fc ff ff");
		assertEquals(hex(expected), hex(block.data()));
		assertEquals(hex(mask), hex(block.mask()));
	}

	/**
	 * The plan of the device with static data: the generic load procedure of mask 0012 (memory based),
	 * its memory writes in chunks of at most 12 bytes, verified, 01FF not loaded.
	 */
	private void checkPlan() {
		DownloadPlan plan = DownloadPlanner.plan(device(WITH_DATA), DownloadPlan.Kind.FULL);
		Logger.info("Download plan of the BCU 1 device:\n" + plan.dump(), TestBcuImage.class);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		List<Operation> operations = plan.getOperations();

		List<String> expected = new ArrayList<>();
		expected.add("#1 Connect: Connect");
		expected.add(": CheckMask 0012");
		expected.add("#3 WriteMem: 010D 00 (Inline data (RunError))");
		expected.add("#4 LoadImageMem: Skip");
		expected.add("#5 WriteMem: 0116 01 (Inline data (GroupAddressTable))");
		expected.add("#6 WriteMem: 0100 ff (" + SEGMENT + ")");
		expected.add("#7 WriteMem: 0104 08fff101ff00000000 (" + SEGMENT + ")");
		expected.add("#8 WriteMem: 010E 61632f2536000000 (" + SEGMENT + ")");
		for (int address = 0x0119; address < 0x01FF; address += 12) {
			int length = Math.min(12, 0x01FF - address);
			// The last chunk 01FD-01FE: zeros of the static data.
			expected.add(String.format("#9 WriteMem: %04X %s (" + SEGMENT + ")", address,
				length == 12 ? "12 bytes" : "0000"));
		}
		expected.add("#10 WriteMem: 00CE 000000000000000000 (Inline data)");
		expected.add("#11 WriteMem: 00D7 000000000000000000 (Inline data)");
		expected.add("#12 WriteMem: 0116 04 (" + SEGMENT + ")");
		expected.add("#13 WriteMem: 010D ff (Inline data (RunError))");
		expected.add("#14 Restart: Restart");
		assertEquals(String.join("\n", expected), operations.stream().map(TestBcuImage::describe)
			.collect(Collectors.joining("\n")));

		// The first chunk of the range 0119-01FE: the group addresses and the free space of the table.
		Operation.WriteMemory first = (Operation.WriteMemory) operations.get(8);
		assertEquals("000100030101000000000000", hex(first.data()));
		for (Operation operation : operations) {
			if (operation instanceof Operation.WriteMemory write) {
				assertTrue(write.describe(), write.verify());
				assertTrue(write.describe(), write.data().length <= DownloadPlanner.MAX_CHUNK);
			}
		}

		// 01FF: static data the procedure of the mask does not write.
		assertEquals(List.of("0x1ff"), plan.getUnloadedBlocks().stream()
			.map(b -> "0x" + Integer.toHexString(b.address()) + (b.data().length > 1 ? "+" + b.data().length : ""))
			.toList());
	}

	/**
	 * The plan of the device without static data is refused, naming the object table and the byte
	 * written only in some of its bits.
	 */
	private void checkPlanWithoutData() {
		DownloadPlan plan = DownloadPlanner.plan(device(WITHOUT_DATA), DownloadPlan.Kind.FULL);
		Logger.info("Download plan of the BCU 1 device without static data:\n" + plan.dump(), TestBcuImage.class);
		assertFalse(plan.isExecutable());
		List<ResKey> problems = plan.getProblems();
		assertTrue(problems.toString(), problems.contains(
			I18NConstants.ERROR_IMAGE__PROBLEM.fill(de.haumacher.ehome.programming.I18NConstants.IMAGE_OBJECT_TABLE_NO_DATA)));
		assertTrue(problems.toString(), problems.contains(I18NConstants.ERROR_PARTIAL_MASK__ADDRESS_MASK.fill("0151", "FC")));
	}

	/**
	 * The executor on a BCU 1 device: the mask checked, every write verified by reading it back, the
	 * restart; afterwards the device holds the image except at 01FF and the individual address.
	 */
	private void checkDownload() throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device(WITH_DATA), DownloadPlan.Kind.FULL);
		MockDevice mock = new MockDevice().setDescriptor("0012");
		mock.setMemory(0x0117, new byte[] { 0x10, 0x01 });
		DownloadExecutor.Result result = new DownloadExecutor(ms -> { /* No wait. */ }).execute(plan, mock,
			(number, count, operation) -> { /* Ignore. */ });
		assertTrue(result.failure(), result.success());

		List<String> calls = mock.calls();
		assertEquals("readDescriptor", calls.get(0));
		assertEquals(List.of("writeMemory 010D 00", "readMemory 010D 1", "writeMemory 0116 01", "readMemory 0116 1",
			"writeMemory 0100 ff", "readMemory 0100 1", "writeMemory 0104 08fff101ff00000000", "readMemory 0104 9"),
			calls.subList(1, 9));
		assertEquals(List.of("writeMemory 0116 04", "readMemory 0116 1", "writeMemory 010D ff", "readMemory 010D 1",
			"restart"), calls.subList(calls.size() - 5, calls.size()));
		// 29 verified writes.
		assertEquals(1 + 2 * 29 + 1, calls.size());
		assertEquals(29, calls.stream().filter(c -> c.startsWith("writeMemory")).count());

		Block block = plan.getImage().getBlocks().get(0);
		byte[] memory = mock.memory();
		for (int n = 0; n < block.data().length; n++) {
			int address = block.address() + n;
			if (address == 0x01FF) {
				assertEquals("01FF is not written.", 0, memory[address]);
			} else if (block.mask()[n] != 0) {
				assertEquals(String.format("%04X", address), block.data()[n], memory[address]);
			}
		}
		assertEquals("The individual address stays.", "1001", hex(memory, 0x0117, 2));
		assertEquals("ff", hex(memory, 0x010D, 1));
	}

	/**
	 * Comparing reads the program identity from memory (mask 0012 has no properties) and the bytes
	 * of the block.
	 */
	private void checkCompare() throws Exception {
		TLObject device = device(WITH_DATA);
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		MockDevice mock = new MockDevice().setDescriptor("0012");
		mock.setMemory(0x0104, new byte[] { 0x08, (byte) 0xFF, (byte) 0xF1, 0x01 });
		DeviceComparison.Result result = DeviceComparison.compare(DeviceConfiguration.program(device), plan.getImage(),
			plan.getUnloadedBlocks(), mock, (number, count, block) -> { /* Ignore. */ });
		assertEquals(0, mock.writes());
		assertTrue(result.facts().toString(),
			result.facts().contains("Program identity (memory 0104): 08 FF F1 01, the image's: 08 FF F1 01"));
		assertEquals(1, result.blocks().size());
		assertFalse(result.blocks().get(0).identical());
	}

	private static String describe(Operation operation) {
		String prefix = operation.step() + ": ";
		if (operation instanceof Operation.WriteMemory write) {
			String data = write.data().length > 9 ? write.data().length + " bytes" : hex(write.data());
			return prefix + String.format("%04X %s (%s)", write.address(), data, DownloadPlan.english(write.what()));
		}
		if (operation instanceof Operation.CheckMask check) {
			return prefix + String.format("CheckMask %04X", check.masks()[0]);
		}
		return prefix + operation.getClass().getSimpleName();
	}

	private static void put(byte[] data, byte[] mask, int offset, String bytes, String bits) {
		byte[] value = HexFormat.ofDelimiter(" ").parseHex(bytes);
		byte[] valueMask = HexFormat.ofDelimiter(" ").parseHex(bits);
		System.arraycopy(value, 0, data, offset, value.length);
		System.arraycopy(valueMask, 0, mask, offset, valueMask.length);
	}

	private static void assertBytes(Block block, int address, String bytes) {
		String expected = bytes.replace(" ", "");
		assertEquals(String.format("%04X", address), expected,
			hex(block.data(), address - block.address(), expected.length() / 2));
	}

	private static void assertMask(Block block, int address, String bytes) {
		String expected = bytes.replace(" ", "");
		assertEquals(String.format("mask %04X", address), expected,
			hex(block.mask(), address - block.address(), expected.length() / 2));
	}

	private static String hex(byte[] data) {
		return HexFormat.of().formatHex(data);
	}

	private static String hex(byte[] data, int offset, int length) {
		return HexFormat.of().formatHex(data, offset, offset + length);
	}

	private TLObject device(String extId) {
		List<TLObject> result = instances("ehome.physical:Device").stream()
			.filter(d -> extId.equals(d.tValueByName("extId")))
			.filter(d -> _projects.contains(root(d)))
			.toList();
		assertEquals(extId, 1, result.size());
		return result.get(0);
	}

	private static TLObject root(TLObject obj) {
		TLObject result = obj;
		while (result.tContainer() != null) {
			result = result.tContainer();
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBcuImage.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
