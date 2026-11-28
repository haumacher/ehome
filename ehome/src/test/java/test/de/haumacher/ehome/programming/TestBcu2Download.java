/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.Logger;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.GroupTables;
import de.haumacher.ehome.programming.GroupTables.Association;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.programming.download.SnapshotFile;

/**
 * Test of the download into devices of BCU 2 (masks 2.0, 2.1 and 2.5; haumacher/ehome #191): the three
 * artificial programs of <code>TestBcu2.knxproj</code> (see <code>make-fixtures.py</code>).
 *
 * <ul>
 * <li>The additional load controls of BCU 2 (task pointer, task control 2) and the association table
 * of BCU 2 with a place per object, against the bytes computed by hand.</li>
 * <li>Mask 2.0 by the mask's procedure: <code>EnableSegmentWrite</code> off while the load state
 * machine allocates its segments (nothing written there), the memory written by the procedure's
 * memory writes, the tables in the one segment 0100-046F.</li>
 * <li>Mask 2.1 by a procedure of the program with dynamic table management: the address table's
 * segment one byte longer for its checksum (<code>AllocExtraByte</code>, segment flag 80), the
 * association table after that byte.</li>
 * <li>Mask 2.5 in the property-mapped style: segments allocated without flags are written (EEPROM),
 * a segment allocated while <code>EnableSegmentWrite</code> is off is written by the procedure's own
 * memory write; the task segment of load state machine 5 left out.</li>
 * <li>Every plan performed on a {@link MockDevice}: the image in its memory, load state machines 1 to
 * 3 loaded (2.0: only 3, as the mask's procedure loads it).</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestBcu2Download extends BasicTestCase {

	private static final String MASK_20 = "P-01A0-0_DI-1";

	private static final String MASK_21 = "P-01A0-0_DI-2";

	private static final String MASK_25 = "P-01A0-0_DI-3";

	private static final String UNLOAD_1 = "1 04000000000000000000 0";

	private static final String UNLOAD_2 = "2 04000000000000000000 0";

	private static final String UNLOAD_3 = "3 04000000000000000000 0";

	private final List<TLObject> _projects = new ArrayList<>();

	public void testLoadControls() {
		assertEquals("0303011c011d00000000", hex(LoadControls.taskPointer(0x011C, 0x011D, 0)));
		assertEquals("03055081024000c80200", hex(LoadControls.taskControl2(0x5081, 0x0240, 0x00C8, 0x0200)));
		assertEquals("Task pointer, initialisation 011C, save 011D, serial interface 0000",
			DownloadPlan.english(LoadControls.describe(LoadControls.taskPointer(0x011C, 0x011D, 0))));
		assertEquals("Task control 2, callback 5081, object table 0240, segment 0 at 00C8, segment 1 at 0200",
			DownloadPlan.english(LoadControls.describe(LoadControls.taskControl2(0x5081, 0x0240, 0x00C8, 0x0200))));
	}

	public void testAssociationSlots() {
		// Object 0 sends to index 2 and receives from 1, object 1 sends to 3, object 2 has nothing: the
		// receive association takes the free place of object 2.
		List<Association> slots = GroupTables.associationSlots(List.of(new Association(1, 0, false),
			new Association(2, 0, true), new Association(3, 1, true)), 3);
		assertEquals("03020003010100", hex(GroupTables.associationTable(slots)));
		// Decoded: the place of an object holds its send address.
		assertEquals(List.of(new Association(2, 0, true), new Association(3, 1, true), new Association(1, 0, false)),
			GroupTables.associations(GroupTables.associationTable(slots), true));

		// An object with receive associations only sends to its first; a free place stays FE.
		slots = GroupTables.associationSlots(List.of(new Association(4, 2, false), new Association(5, 2, false)), 4);
		assertEquals("04 0502 fe01 0402 fe03".replace(" ", ""), hex(GroupTables.associationTable(slots)));
		assertEquals(List.of(new Association(4, 2, true), new Association(5, 2, false)),
			GroupTables.associations(GroupTables.associationTable(slots), true));

		// A free place does not take an association whose address an object sends to from its own place.
		slots = GroupTables.associationSlots(List.of(new Association(1, 0, true), new Association(1, 2, false),
			new Association(2, 2, true)), 3);
		assertEquals("04 0100 fe01 0202 0102".replace(" ", ""), hex(GroupTables.associationTable(slots)));
	}

	public void testDownload() throws Exception {
		TLObject project = importProject("src/test/fixtures/TestBcu2.knxproj");
		try {
			checkMask20(device(project, MASK_20));
			checkMask21(device(project, MASK_21));
			checkMask25(device(project, MASK_25));
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (TLObject p : _projects) {
					p.tDelete();
				}
				tx.commit();
			}
		}
	}

	private static void checkMask20(TLObject device) throws Exception {
		DownloadPlan plan = plan(device);
		DownloadImage image = plan.getImage();
		// The tables in the one segment 0100-046F, at their places in the static data.
		assertEquals("0116 04 ---- 0001 0003 0101", table(image.getAddressTable(), 1, 2));
		assertEquals("0125 03 0200 0301 0100", table(image.getAssociationTable(), 1, 2));
		assertEquals("0136 03 00 001700 014f00 025b07", table(image.getObjectTable(), 2, 3));
		Block segment = image.getBlocks().get(0);
		assertEquals(0x0100, segment.address());
		assertEquals(880, segment.data().length);
		assertEquals("02 38 012c", hex(segment.data(), 0x50, 1) + " " + hex(segment.data(), 0x51, 1) + " "
			+ hex(segment.data(), 0x52, 2));

		assertEquals(List.of(UNLOAD_1, UNLOAD_2, UNLOAD_3, "3 01000000000000000000 2",
			"3 030000c8001833010000 2", "3 03000972004a33020000 2", "3 0300011a000233030000 2",
			"3 03000100001633030000 2", "3 0300011c035433038000 2", "3 0302011e000008ff2001 2",
			"3 0303011c011d00000000 2", "3 03040000000000000000 2", "3 03055081011a00d000d0 2",
			"3 02000000000000000000 1"), loadControls(plan));
		List<Operation> operations = plan.getOperations();
		assertEquals(102, operations.size());
		assertAuthorize(operations);
		// EnableSegmentWrite off: nothing written into the segments before the load completes.
		int completed = indexOf(operations, "#16 LoadCompleted LSM 3");
		assertTrue(operations.subList(0, completed).stream().noneMatch(Operation.WriteMemory.class::isInstance));
		assertEquals("Compare object 3 property 13 with 00 08 FF 20 01", operations.get(completed + 1).describe());
		// Then the memory writes of the mask's procedure, as on BCU 1.
		assertEquals(List.of("010D 00", "0116 01", "0100 FF", "0103 00 08 FF 20 01 FF 00 00 00 00",
			"010E 00 00 00 25 36 00 00 00", "0119 00 01 00 03 01 01 00 00 00 00 00 00"),
			writes(operations).subList(0, 6));
		assertEquals(List.of("0464 00 00 00 00 00 00 00 00 00 00 00 00", "0116 04", "010D FF"),
			writes(operations).subList(writes(operations).size() - 3, writes(operations).size()));
		assertEquals("Restart the device", operations.get(operations.size() - 1).describe());
		// The byte of the static data the mask's procedure does not write.
		assertEquals(1, plan.getUnloadedBlocks().size());
		assertEquals(0x01FF, plan.getUnloadedBlocks().get(0).address());

		MockDevice mock = execute(plan, "0020", "0008ff2001");
		assertEquals(LoadControls.STATE_UNLOADED, mock.state(1));
		assertEquals(LoadControls.STATE_UNLOADED, mock.state(2));
		assertEquals(LoadControls.STATE_LOADED, mock.state(3));
		checkReadOut(device, plan, mock);
	}

	private static void checkMask21(TLObject device) throws Exception {
		Program program = DeviceConfiguration.program(device);
		assertEquals(1, DownloadImage.tableChecksum(program));
		assertTrue(DownloadImage.associationSlots(program));
		DownloadPlan plan = plan(device);
		DownloadImage image = plan.getImage();
		// Dynamic tables: the association table after the address table and its checksum byte (011F... 0121).
		assertEquals("0116 05 1002 0001 0002 0003 0101", table(image.getAddressTable(), 1, 2));
		assertEquals("0122 04 0300 0401 0202 0100", table(image.getAssociationTable(), 1, 2));
		assertEquals("0240 03 00 001700 014f00 025f07", table(image.getObjectTable(), 2, 3));
		assertEquals(List.of(UNLOAD_1, UNLOAD_2, UNLOAD_3,
			"1 01000000000000000000 2", "1 03000116000c33038000 2", "1 03020116000008ff2101 2",
			"1 02000000000000000000 1",
			"2 01000000000000000000 2", "2 03000122004833038000 2", "2 03020122000008ff2101 2",
			"2 02000000000000000000 1",
			"3 01000000000000000000 2", "3 030000c8001833010000 2", "3 03000200006033038000 2",
			"3 03020210000008ff2101 2", "3 03030220022800000000 2", "3 03040000000000000000 2",
			"3 03055081024000c80200 2", "3 02000000000000000000 1"), loadControls(plan));
		List<Operation> operations = plan.getOperations();
		assertEquals(36, operations.size());
		assertAuthorize(operations);
		assertEquals(List.of("0116 05 10 02 00 01 00 02 00 03 01 01", "0122 04 03 00 04 01 02 02 01 00",
			"0200 02 38 01 2C 00 00 00 00 00 00 00 00", "020C 00 00 00 00 81 00 00 00 00 00 00 00",
			"0218 00 00 00 00 00 00 00 00 00 00 00 00", "0224 00 00 00 00 00 00 00 00 00 00 00 00",
			"0230 00 00 00 00 00 00 00 00 00 00 00 00", "023C 00 00 00 00 03 00 00 17 00 01 4F 00",
			"0248 02 5F 07 00 00 00 00 00 00 00 00 00", "0254 00 00 00 00 00 00 00 00 00 00 00 00"), writes(operations));
		assertTrue(plan.getUnloadedBlocks().isEmpty());

		MockDevice mock = execute(plan, "0021", "0008ff2101");
		for (int lsm = 1; lsm <= 3; lsm++) {
			assertEquals(LoadControls.STATE_LOADED, mock.state(lsm));
		}
		checkReadOut(device, plan, mock);
	}

	private static void checkMask25(TLObject device) throws Exception {
		Program program = DeviceConfiguration.program(device);
		assertEquals(0, DownloadImage.tableChecksum(program));
		DownloadPlan plan = plan(device);
		DownloadImage image = plan.getImage();
		assertEquals("0116 04 1003 0001 0003 0101", table(image.getAddressTable(), 1, 2));
		assertEquals("0160 03 0200 0301 0100", table(image.getAssociationTable(), 1, 2));
		assertEquals("0400 03 00 001700 014f00 025b07", table(image.getObjectTable(), 2, 3));
		assertEquals(List.of(UNLOAD_1, UNLOAD_2, UNLOAD_3,
			"1 01000000000000000000 2", "1 03000116002b33030000 2",
			"1 03020116000008ff2501 2",
			"2 01000000000000000000 2", "2 03000160002933030000 2", "2 03020160000008ff2501 2",
			"3 01000000000000000000 2", "3 03000400002033030000 2", "3 03003c00001033030000 2",
			"3 03020000000008ff2501 2", "3 03030000000000000000 2", "3 03050000040000000000 2",
			"1 02000000000000000000 1", "2 02000000000000000000 1", "3 02000000000000000000 1"), loadControls(plan));
		List<Operation> operations = plan.getOperations();
		assertEquals(32, operations.size());
		assertAuthorize(operations);
		assertEquals("Compare object 0 property 78 with 00 08 00 00 25 F3 00 00 00 00", operations.get(4).describe());
		assertFalse(plan.dump(), plan.dump().contains("LSM 5"));
		// The tables and the object table loaded with their load state machines; the parameter segment,
		// allocated while EnableSegmentWrite is off, by the WriteMem after the load.
		assertEquals(List.of("0116 04 10 03 00 01 00 03 01 01", "0160 03 02 00 03 01 01 00",
			"0400 03 00 00 17 00 01 4F 00 02 5B 07 00", "040C 00 00 00 00 00 00 00 00 00 00 00 00",
			"0418 00 00 00 00 00 00 00 00", "3C00 02 38 01 2C 00 00 00 00 00 00 00 00", "3C0C 00 00 00 00"),
			writes(operations));
		assertTrue(indexOf(operations, "#24 WriteMem") > indexOf(operations, "#23 LoadCompleted LSM 3"));

		MockDevice mock = execute(plan, "0025", "0008ff2501");
		for (int lsm = 1; lsm <= 3; lsm++) {
			assertEquals(LoadControls.STATE_LOADED, mock.state(lsm));
		}
		checkReadOut(device, plan, mock);
	}

	/**
	 * Probe of the read-out (haumacher/ehome #193) on a BCU 2 device: the snapshot of the loaded device
	 * compares identical offline, survives its file, and a changed byte of the association table shows
	 * with the device's and the plan's value.
	 */
	private static void checkReadOut(TLObject device, DownloadPlan plan, MockDevice mock) throws Exception {
		Program program = DeviceConfiguration.program(device);
		Snapshot snapshot = ReadOut.read(program, plan, mock, (number, count, block) -> {
			// No progress.
		});
		DeviceComparison.Result same = DeviceComparison.compare(program, plan, snapshot);
		assertEquals(same.blocks().toString(), same.blocks().size(), same.identicalBlocks());
		assertEquals(same.identicalBlocks(),
			DeviceComparison.compare(program, plan, SnapshotFile.parse(SnapshotFile.write(snapshot))).identicalBlocks());

		Block table = plan.getImage().getAssociationTable();
		int address = table.address() + 1;
		byte old = mock.memory()[address];
		mock.setMemory(address, new byte[] { (byte) (old ^ 0x01) });
		try {
			DeviceComparison.Result changed = DeviceComparison.compare(program, plan, ReadOut.read(program, plan, mock,
				(number, count, block) -> {
					// No progress.
				}));
			DeviceComparison.BlockResult differing =
				changed.blocks().stream().filter(r -> !r.identical()).findFirst().orElseThrow();
			assertEquals(List.of(address), differing.different());
			assertEquals(hex(new byte[] { (byte) (old ^ 0x01) }), differing.differences().get(0).device().replace(" ", "").toLowerCase());
			assertEquals(hex(new byte[] { old }), differing.differences().get(0).plan().replace(" ", "").toLowerCase());
		} finally {
			mock.setMemory(address, new byte[] { old });
		}
	}

	private static DownloadPlan plan(TLObject device) {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		Logger.info("Download plan of the BCU 2 device " + ((Device) device).getExtId() + ":\n" + plan.dump(),
			TestBcu2Download.class);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		return plan;
	}

	/**
	 * The plan authorizes with the key of the delivery state right after the mask check.
	 */
	private static void assertAuthorize(List<Operation> operations) {
		assertTrue(operations.get(1) instanceof Operation.CheckMask);
		Operation.Authorize authorize = (Operation.Authorize) operations.get(2);
		// The manufacturer of the device compared after the authorization (haumacher/ehome #224).
		assertTrue(operations.get(3) instanceof Operation.CheckManufacturer);
		assertEquals("ffffffff", hex(authorize.key()));
	}

	/**
	 * Performs the plan on a mock device of the given mask, with the hardware type of the 2.5 program
	 * and the program version the task segment sets: every operation succeeds, the device holds the
	 * image wherever the plan writes it.
	 */
	private static MockDevice execute(DownloadPlan plan, String mask, String applicationId) throws Exception {
		MockDevice mock = new MockDevice().setDescriptor(mask).manufacturerOf(plan).setProperty(0, 78, "0008000025f3")
			.setProperty(3, 13, applicationId);
		DownloadExecutor.Result result = new DownloadExecutor(ms -> {
			// No wait.
		}).execute(plan, mock, (number, count, operation) -> {
			// No progress.
		});
		assertTrue(result.failure(), result.success());
		assertEquals("authorize FFFFFFFF", mock.calls().get(1));
		for (Block block : plan.getImage().getBlocks()) {
			for (int n = 0; n < block.data().length; n++) {
				int address = block.address() + n;
				boolean unloaded = plan.getUnloadedBlocks().stream().anyMatch(b -> b.contains(address));
				if (block.mask()[n] != 0 && !unloaded) {
					assertEquals(String.format("%04X", address), block.data()[n], mock.memory()[address]);
				}
			}
		}
		return mock;
	}

	private static List<String> loadControls(DownloadPlan plan) {
		List<String> result = new ArrayList<>();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.LoadControl control) {
				assertEquals(control.stateMachine(), control.objectIndex());
				result.add(control.objectIndex() + " " + hex(control.control()) + " " + control.expectedState());
			}
		}
		return result;
	}

	private static List<String> writes(List<Operation> operations) {
		List<String> result = new ArrayList<>();
		for (Operation operation : operations) {
			if (operation instanceof Operation.WriteMemory write) {
				result.add(String.format("%04X ", write.address()) + HexFormat.ofDelimiter(" ").withUpperCase().formatHex(write.data()));
			}
		}
		return result;
	}

	private static int indexOf(List<Operation> operations, String step) {
		for (int n = 0; n < operations.size(); n++) {
			if (operations.get(n).step().equals(step)) {
				return n;
			}
		}
		fail("No step " + step);
		return -1;
	}

	/**
	 * A table as its address, the bytes of its header one by one and its entries; bytes outside the
	 * mask as <code>--</code>.
	 */
	private static String table(Block block, int header, int entry) {
		StringBuilder result = new StringBuilder(String.format("%04X", block.address()));
		byte[] data = block.data();
		for (int n = 0; n < data.length; n += n < header ? 1 : entry) {
			result.append(' ');
			for (int k = n; k < (n < header ? n + 1 : n + entry) && k < data.length; k++) {
				result.append(block.mask()[k] == 0 ? "--" : String.format("%02x", data[k]));
			}
		}
		return result.toString();
	}

	private TLObject importProject(String file) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(file);
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		_projects.add(projects.get(0));
		return projects.get(0);
	}

	private static TLObject device(TLObject project, String extId) {
		List<TLObject> result = instances("ehome.physical:Device").stream()
			.filter(d -> extId.equals(((Device) d).getExtId()))
			.filter(d -> root(d) == project)
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

	private static String hex(byte[] data) {
		return HexFormat.of().formatHex(data);
	}

	private static String hex(byte[] data, int offset, int length) {
		return HexFormat.of().formatHex(data, offset, offset + length);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBcu2Download.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
