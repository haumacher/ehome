/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestSuite;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.Logger;
import com.top_logic.basic.listener.Listener;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.scan.CatalogMatch;
import de.haumacher.ehome.scan.DeviceNumberRepair;
import de.haumacher.ehome.scan.DeviceStubs;
import de.haumacher.ehome.scan.FoundDevice;
import de.haumacher.ehome.scan.InstallationBuilder;

/**
 * The device number of a device in its line and its individual address (haumacher/ehome #203):
 * <code>ehome.physical:BusDevice#address</code> is the number in the line (0..255, a whole individual
 * address is refused), the derived <code>ehome.physical:BusDevice#individualAddress</code> is computed
 * from the line and area holding the device (empty in no line), a scan and the planning put a device
 * into its line, and {@link DeviceNumberRepair}, the startup action of the migration
 * <code>Issue_203_Device_numbers</code>, places an unplaced device holding a whole individual address.
 *
 * <p>
 * The project is <code>samples/KNX-Testsystem.knxproj</code> (devices 1.3.1, 1.3.5 and 1.3.50, the
 * catalog of GIRA with the program 10D7 version 11 of the Tastsensor); the other projects are created
 * empty and deleted again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDeviceNumbers extends BasicTestCase {

	private static final int GIRA = 0x08;

	/** The stub of the issue: 1.1.7 as a whole individual address. */
	private static final int STUB_ADDRESS = 0x1107;

	private static final byte[] SERIAL = HexFormat.of().parseHex("000a0476250b");

	static List<TLObject> _before;

	/** The test system. */
	static TLObject _original;

	/**
	 * The individual addresses of the test system and of a stub found at 1.1.7; the label of a device
	 * names it; a device in no line has none.
	 */
	public void testDerivedAddress() throws Exception {
		Map<String, Object> addresses = new TreeMap<>();
		for (TLObject device : SharedData.within("ehome.physical:Device", List.of(_original))) {
			addresses.put(ExtIds.extId(device), derived(device, "individualAddress"));
		}
		assertEquals(Map.of("P-0563-0_DI-1", "1.3.1", "P-0563-0_DI-2", "1.3.50", "P-0563-0_DI-3", "1.3.5"), addresses);
		TLObject tastsensor = InstallationBuilder.device(_original, 0x1332);
		assertEquals(Integer.valueOf(50), tastsensor.tValueByName("address"));
		assertTrue(String.valueOf(derived(tastsensor, "name")), String.valueOf(derived(tastsensor, "name")).endsWith(" (1.3.50)"));

		TLObject project = TestScanTakeOver.emptyProject("Device numbers: stub");
		try {
			TLObject stub;
			try (Transaction tx = kb().beginTransaction()) {
				stub = DeviceStubs.record(project, found(STUB_ADDRESS, 0x20BD));
				tx.commit();
			}
			assertEquals("1.1", stub.tContainer().tValueByName("name"));
			assertEquals(Integer.valueOf(7), stub.tValueByName("address"));
			assertEquals("1.1.7", derived(stub, "individualAddress"));
			assertEquals("1.1.7", GroupAddressConnections.addressText(stub));

			// In no line, the device has its number and no individual address.
			try (Transaction tx = kb().beginTransaction()) {
				InstallationBuilder.line(project, 1, 1).tUpdateByName("devices", List.of());
				((Project) project).addUnassignedDevice(stub);
				tx.commit();
			}
			assertSame(project, stub.tContainer());
			assertEquals(Integer.valueOf(7), stub.tValueByName("address"));
			assertNull(derived(stub, "individualAddress"));
			assertEquals("–", GroupAddressConnections.addressText(stub));
			assertFalse(GroupAddressConnections.deviceLabel(stub), GroupAddressConnections.deviceLabel(stub).contains("("));
		} finally {
			TestScanTakeOver.delete(project);
		}
	}

	/**
	 * A device number above 255, a whole individual address as 4359 for 1.1.7, is refused.
	 */
	public void testNumberRange() throws Exception {
		TLObject tastsensor = InstallationBuilder.device(_original, 0x1332);
		for (int number : new int[] { STUB_ADDRESS, 256, -1 }) {
			try (Transaction tx = kb().beginTransaction()) {
				tastsensor.tUpdateByName("address", Integer.valueOf(number));
				tx.commit();
				fail("The device number " + number + " is accepted.");
			} catch (RuntimeException ex) {
				// Refused by the constraint of the device number.
			}
			assertEquals(Integer.valueOf(50), tastsensor.tValueByName("address"));
		}
		for (int number : new int[] { 255, 50 }) {
			try (Transaction tx = kb().beginTransaction()) {
				tastsensor.tUpdateByName("address", Integer.valueOf(number));
				tx.commit();
			}
			assertEquals("1.3." + number, derived(tastsensor, "individualAddress"));
		}
	}

	/**
	 * A take-over of a device found on the bus, a new stub and a planned device land in the line of
	 * their address, which is created with its area where the project has none.
	 */
	public void testPlacedInTheirLine() throws Exception {
		TLObject project = TestScanTakeOver.emptyProject("Device numbers: placement");
		try {
			TLObject taken;
			TLObject stub;
			TLObject planned;
			try (Transaction tx = kb().beginTransaction()) {
				// The Tastsensor of the test system; the shared catalog may offer further candidates.
				TLObject tastsensor = InstallationBuilder.device(_original, 0x1332);
				FoundDevice found = found(0x2405, 0x10D7);
				CatalogMatch.Candidate candidate = CatalogMatch.match(project, found).candidates().stream()
					.filter(c -> c.variant() == tastsensor.tValueByName("type")).findFirst().orElseThrow();
				taken = InstallationBuilder.apply(project, found, candidate).device();
				stub = DeviceStubs.record(project, found(0x2507, 0x20BD));
				planned = DevicePlanning.create(InstallationBuilder.line(project, 3, 6),
					(TLObject) tastsensor.tValueByName("type"), null, Integer.valueOf(12), null, null);
				tx.commit();
			}
			assertEquals("2.4.5", derived(taken, "individualAddress"));
			assertEquals(Integer.valueOf(5), taken.tValueByName("address"));
			assertEquals("2.5.7", derived(stub, "individualAddress"));
			assertEquals(Integer.valueOf(7), stub.tValueByName("address"));
			assertEquals("3.6.12", derived(planned, "individualAddress"));
			assertEquals(Integer.valueOf(12), planned.tValueByName("address"));
			assertTrue("Nothing unplaced.", ((Project) project).getUnassignedDevices().isEmpty());
		} finally {
			TestScanTakeOver.delete(project);
		}
	}

	/**
	 * An unplaced stub holding the whole individual address 4359 (1.1.7) is placed in line 1.1, which
	 * is created, as stub 7 with the serial number as ETS stores it; where 1.1.7 holds a device, it
	 * stays unplaced with the number 7, and the log names 1.1.7.
	 */
	public void testRepair() throws Exception {
		TLObject project = TestScanTakeOver.emptyProject("Device numbers: repair");
		List<String> log = new ArrayList<>();
		Listener<Logger.LogEntry> listener = entry -> {
			if (entry.getCaller() == DeviceNumberRepair.class) {
				log.add(entry.getMessage());
			}
		};
		Logger.getListenerRegistry().register(listener);
		try {
			TLObject placed = withoutModelChecks(() -> record(project, STUB_ADDRESS, "000A0476250B"));
			assertEquals(Integer.valueOf(STUB_ADDRESS), placed.tValueByName("address"));
			repair();
			TLObject line = placed.tContainer();
			assertEquals("1.1", line.tValueByName("name"));
			assertEquals(Integer.valueOf(1), line.tContainer().tValueByName("address"));
			assertEquals(Integer.valueOf(7), placed.tValueByName("address"));
			assertEquals("1.1.7", derived(placed, "individualAddress"));
			assertEquals(Base64.getEncoder().encodeToString(SERIAL), placed.tValueByName("serialNumber"));
			assertNotNull(ExtIds.extId(placed));
			assertTrue(((Project) project).getUnassignedDevices().isEmpty());
			assertTrue(log.toString(), log.stream().anyMatch(m -> m.contains("1.1.7") && m.contains("placed in its line")));

			// Again: nothing left to repair.
			log.clear();
			repair();
			assertSame(line, placed.tContainer());
			assertEquals(Integer.valueOf(7), placed.tValueByName("address"));
			assertTrue(log.toString(), log.stream().noneMatch(m -> m.contains("1.1.7")));

			// 1.1.7 is taken: the record stays unplaced with its number, the log names its address.
			TLObject unplaced = withoutModelChecks(() -> record(project, STUB_ADDRESS, null));
			repair();
			assertEquals(List.of(unplaced), new ArrayList<>(((Project) project).getUnassignedDevices()));
			assertEquals(Integer.valueOf(7), unplaced.tValueByName("address"));
			assertNull(derived(unplaced, "individualAddress"));
			assertSame(placed, InstallationBuilder.device(project, STUB_ADDRESS));
			assertTrue(log.toString(), log.stream().anyMatch(m -> m.contains("1.1.7")
				&& m.contains("left among the unplaced devices") && m.contains("device at 1.1.7")));
		} finally {
			Logger.getListenerRegistry().unregister(listener);
			TestScanTakeOver.delete(project);
		}
	}

	private static void repair() {
		try (Transaction tx = kb().beginTransaction()) {
			new DeviceNumberRepair().perform();
			tx.commit();
		}
	}

	/**
	 * A record of a device a scan did not identify as it was before haumacher/ehome #183, among the
	 * unplaced devices of the project: a stub with the whole individual address and the serial number
	 * in hex. Created by {@link #withoutModelChecks(Supplier)}: the model refuses such a number.
	 */
	static TLObject record(TLObject project, int address, String serialHex) {
		TLObject stub = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType(DeviceStubs.STUB_TYPE));
		stub.tUpdateByName("address", Integer.valueOf(address));
		stub.tUpdateByName("serialNumber", serialHex);
		stub.tUpdateByName("manufacturerId", Integer.valueOf(GIRA));
		stub.tUpdateByName("programVersion", "000820BD11");
		((Project) project).addUnassignedDevice(stub);
		return stub;
	}

	/**
	 * Computes and commits the given value in a transaction without the constraint checks of the
	 * model, to create data as an earlier version stored it.
	 */
	static <T> T withoutModelChecks(Supplier<T> value) {
		KnowledgeBase kb = kb();
		ModelService checks = ModelService.getInstance();
		kb.removeCommitChecker(checks);
		try (Transaction tx = kb.beginTransaction()) {
			T result = value.get();
			tx.commit();
			return result;
		} finally {
			kb.addCommitChecker(checks);
		}
	}

	/** A GIRA device found on the bus running the given application number, version 11. */
	private static FoundDevice found(int address, int application) {
		byte[] program = { 0x00, GIRA, (byte) (application >>> 8), (byte) application, 0x11 };
		return new FoundDevice(address, FoundDevice.Source.BUS, Integer.valueOf(0x0705), Integer.valueOf(GIRA), SERIAL,
			program, null, null, List.of(), List.of());
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	/**
	 * Imports the test system for the tests of this class, and deletes it again.
	 */
	private static Test withProject(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestDeviceNumbers.class, () -> {
					_before = instances("ehome.project:Project");
					importSample("samples/KNX-Testsystem.knxproj");
					List<TLObject> projects = SharedData.newProjects(_before);
					assertEquals(1, projects.size());
					_original = projects.get(0);
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestDeviceNumbers.class, () -> {
					SharedData.deleteProjects(SharedData.newProjects(_before));
					return null;
				});
				_original = null;
				_before = null;
			}
		};
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(withProject(new TestSuite(TestDeviceNumbers.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
