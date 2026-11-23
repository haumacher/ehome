/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupTables;
import de.haumacher.ehome.programming.GroupTables.Association;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;
import de.haumacher.ehome.programming.download.ReadOut;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.scan.ReadBack;

/**
 * The read-back of a BCU 1 device (mask 0012) whose association table lies where its pointer 0111
 * names it, not where the project's image would place it (haumacher/ehome #222): a device of the
 * artificial fixture <code>TestBcu1.knxproj</code> downloaded into a device in memory, then the
 * project changed to fewer group addresses, so that its image would place the table elsewhere.
 * Taken over from the bus, from a snapshot, and from a snapshot that recorded the image's place
 * (as E-Home did before), the project gets the device's connections back, and its image is the
 * device's memory.
 *
 * <p>
 * Also the order of the associations as ETS writes it: first the address each object sends to, by
 * object, then the further addresses, by object.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBcu1ReadBack extends BasicTestCase {

	/** The individual address 1.0.1 of the device with static data. */
	private static final int DEVICE = 0x1001;

	public void testEtsOrder() {
		List<Association> order = GroupTables.associationOrder(List.of(new Association(4, 5, false),
			new Association(3, 5, true), new Association(2, 4, false), new Association(1, 4, true),
			new Association(5, 0, true)));
		assertEquals(List.of(new Association(5, 0, true), new Association(1, 4, true), new Association(3, 5, true),
			new Association(2, 4, false), new Association(4, 5, false)), order);
	}

	public void testReadBackAtThePointer() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestBcu1.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject device = SharedData.within("ehome.physical:Device", projects).stream()
				.filter(d -> Integer.valueOf(DEVICE).equals(DownloadImage.individualAddress(d))).findFirst()
				.orElseThrow();
			MockDevice mock = downloaded(device);
			// The device holds its association table right after its address table of 4 entries, at
			// 011F, the pointer 0111 names it.
			assertEquals("1f", hex(mock.memory(), 0x0111, 1));
			String table = hex(mock.memory(), 0x011F, 7);
			assertEquals("03020003010100", table);

			// From a snapshot: the read-out records the table where the device's pointer names it.
			fewerAddresses(device, mock);
			Snapshot snapshot = ReadOut.read(device, mock, (number, count, block) -> {
				// No progress.
			});
			assertEquals(Integer.valueOf(0x011F), snapshot.getAssociationTable());
			takenOver(device, ReadBack.Memory.of(snapshot, device), table);

			// From a snapshot recording the image's place: the pointer in its memory decides.
			fewerAddresses(device, mock);
			snapshot.setAssociationTable(Integer.valueOf(0x011B));
			takenOver(device, ReadBack.Memory.of(snapshot, device), table);

			// From the bus.
			fewerAddresses(device, mock);
			takenOver(device, ReadBack.read(device, mock, (done, total, what) -> {
				// No progress.
			}), table);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static void takenOver(TLObject device, ReadBack.Memory memory, String table) {
		ReadBack.Report report = ReadBack.takeOver(device, memory);
		assertTrue(report.problems() + " " + report.differences(), report.identical());
		DownloadImage image = DownloadImage.compute(device);
		assertEquals(0x011F, image.getAssociationTable().address());
		assertEquals(table, hex(image.getAssociationTable().data(), 0, 7));
	}

	/**
	 * Wires the device to one group address only (object 0 sending to 0/0/3), by the take-over from a
	 * device in memory holding such tables: its image places the association table after an
	 * address table of 2 entries, at 011B.
	 */
	private static void fewerAddresses(TLObject device, MockDevice mock) {
		MockDevice other = new MockDevice().setDescriptor("0012");
		other.setMemory(0x0100, Arrays.copyOfRange(mock.memory(), 0x0100, 0x0200));
		other.setMemory(0x0111, HexFormat.of().parseHex("1b"));
		other.setMemory(0x0116, HexFormat.of().parseHex("0210010003010100"));
		try {
			ReadBack.takeOver(device, ReadBack.read(device, other, (done, total, what) -> {
				// No progress.
			}));
		} catch (Exception ex) {
			throw new AssertionError(ex);
		}
		assertEquals(0x011B, DownloadImage.compute(device).getAssociationTable().address());
	}

	private static MockDevice downloaded(TLObject device) throws InterruptedException {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		MockDevice mock = new MockDevice().setDescriptor("0012");
		mock.setMemory(0x0117, new byte[] { 0x10, 0x01 });
		DownloadExecutor.Result result = DownloadService.download(device, plan, mock,
			new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
		assertTrue(result.failure(), result.success());
		return mock;
	}

	private static String hex(byte[] data, int offset, int length) {
		return HexFormat.of().formatHex(Arrays.copyOfRange(data, offset, offset + length));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBcu1ReadBack.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
