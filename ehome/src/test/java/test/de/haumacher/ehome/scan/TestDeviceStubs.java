/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestSuite;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.programming.MockDevice;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.scan.CatalogMatch;
import de.haumacher.ehome.scan.DeviceStubMigration;
import de.haumacher.ehome.scan.DeviceStubs;
import de.haumacher.ehome.scan.FoundDevice;
import de.haumacher.ehome.scan.I18NConstants;
import de.haumacher.ehome.scan.InstallationBuilder;
import de.haumacher.ehome.scan.ScanFunctions;
import de.haumacher.ehome.scan.ScanRows;

/**
 * The stub of a device a scan found that the catalog does not identify (haumacher/ehome #183,
 * {@link DeviceStubs}): "Take over" creates it in its line without program, "Assign" replaces it by a
 * device keeping name, address, serial number and location once the catalog has the product data, and
 * refuses naming manufacturer and order number before; a take-over of the device identified replaces
 * it as well; the export as <code>.knxproj</code> leaves it out and names it; the migration of the
 * former records places them in their lines; the devices view shows a stub without the tabs that need
 * a program.
 *
 * <p>
 * The catalog is that of <code>samples/KNX-Testsystem.knxproj</code>: GIRA (M-0008) with the program
 * 10D7 version 11 of the Tastsensor. The product data "imported" by {@link #testAssign()} is a
 * product of GIRA with a program 20BD version 11 the test creates in the catalog and deletes again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDeviceStubs extends BasicTestCase {

	private static final int GIRA = 0x08;

	/** The device of the issue: 1.1.7, GIRA program 20BD version 11, order number 5133 00. */
	private static final int STUB_ADDRESS = 0x1107;

	private static final String ORDER_NUMBER = "5133 00";

	private static final byte[] SERIAL = HexFormat.of().parseHex("000a0476250b");

	private static final byte[] HARDWARE_TYPE = HexFormat.of().parseHex("000a00000034");

	private static final String DEVICES_VIEW = "src/main/webapp/WEB-INF/views/projects/devices.view.xml";

	static List<TLObject> _before;

	/** The test system, whose catalog the devices are matched against. */
	static TLObject _original;

	/**
	 * "Take over" of a row the catalog does not identify creates a stub in the line of its address,
	 * without program and without reading it; "Assign" refuses with the manufacturer and the order
	 * number, and after the product data is imported replaces the stub by a device with the same name,
	 * address, serial number and location.
	 */
	public void testAssign() throws Exception {
		TLObject project = TestScanTakeOver.emptyProject("Stubs");
		List<TLObject> productData = new ArrayList<>();
		try {
			FoundDevice found = device(STUB_ADDRESS, 0x20BD, ORDER_NUMBER);
			Map<String, Object> row = ScanRows.row(project, found, CatalogMatch.match(project, found));
			assertEquals(CatalogMatch.Kind.UNIDENTIFIED, ScanRows.match(row).kind());
			assertNull("An unidentified device is taken over.", ScanFunctions.applyVeto(row));
			assertEquals("The order number as read.", ORDER_NUMBER, row.get("order"));

			Map<String, MockDevice> read = new HashMap<>();
			Map<String, Object> channels = new HashMap<>();
			channels.put("project", project);
			channels.put("selected", row);
			channels.put("result", result(row));
			Map<?, ?> taken = TestScanTakeOver.takeOver(TestScanTakeOver.takeOverCommand(false).jobValues(channels), false, read);
			assertEquals(text(I18NConstants.TAKE_OVER_DONE__TAKEN_READ_REFUSED.fill(1, 0, 0)), taken.get("takeOver"));
			assertTrue("Its configuration is not read: " + read, read.isEmpty());

			TLObject stub = InstallationBuilder.device(project, STUB_ADDRESS);
			assertTrue("A stub: " + stub, DeviceStubs.isStub(stub));
			TLObject line = stub.tContainer();
			assertEquals("1.1", line.tValueByName("name"));
			assertEquals(Integer.valueOf(7), stub.tValueByName("address"));
			assertEquals(found.serialStored(), stub.tValueByName("serialNumber"));
			assertEquals("000A:0476250B", stub.tValueByName("serialText"));
			assertEquals("000820BD11", stub.tValueByName("programVersion"));
			assertEquals(ORDER_NUMBER, stub.tValueByName("orderNumber"));
			String manufacturer = (String) CatalogMatch.manufacturer(GIRA).tValueByName("name");
			assertEquals(manufacturer, stub.tValueByName("manufacturer"));
			assertEquals(manufacturer + " " + ORDER_NUMBER + " (1.1.7)", GroupAddressConnections.deviceLabel(stub));
			assertEquals(manufacturer + " " + ORDER_NUMBER + " (1.1.7)", MetaLabelProvider.INSTANCE.getLabel(stub));
			assertNull("No program.", DeviceConfiguration.program(stub));
			assertNotNull(ExtIds.extId(stub));
			assertTrue("Numbered as a device of the project.", ExtIds.isCreatedInApp(stub));
			assertEquals(ScanRows.row(project, found, ScanRows.match(row)).get(ScanRows.STATE),
				text(I18NConstants.STATE_STUB__DEVICE.fill(stub.tValueByName("name"))));

			TLObject building;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				building = create("ehome.building:Building");
				building.tUpdateByName("name", "Haus");
				project.tUpdateByName("buildings", List.of(building));
				stub.tUpdateByName("title", "Taster Flur");
				stub.tUpdateByName("location", building);
				tx.commit();
			}
			assertEquals("The building lists the stub.", List.of(stub), list(building, "devices"));
			String extId = ExtIds.extId(stub);

			// Without the product data.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				DeviceStubs.assign(stub);
				fail("The catalog has no product for the device.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_NO_PRODUCT_DATA__MANUFACTURER_ORDER.fill(manufacturer, ORDER_NUMBER),
					ex.getErrorKey());
				assertEquals("No product data for " + manufacturer + ", order number 5133 00 – import the manufacturer's"
					+ " product data.", Resources.getInstance(Locale.ENGLISH).getString(ex.getErrorKey()));
			}
			assertTrue(stub.tValid());

			// The product data imported.
			TLObject program = productData(productData, 0x20BD, 0x11, ORDER_NUMBER);
			TLObject device;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				device = DeviceStubs.assign(stub);
				tx.commit();
			}
			assertFalse("Replaced.", stub.tValid());
			assertTrue(DeviceStubs.isDevice(device));
			assertSame(device, InstallationBuilder.device(project, STUB_ADDRESS));
			assertSame(line, device.tContainer());
			assertEquals(Integer.valueOf(7), device.tValueByName("address"));
			assertEquals("Taster Flur", device.tValueByName("title"));
			assertEquals(found.serialStored(), device.tValueByName("serialNumber"));
			assertSame(building, device.tValueByName("location"));
			assertEquals(List.of(device), list(building, "devices"));
			assertEquals("The device keeps the ID of the stub.", extId, ExtIds.extId(device));
			assertSame(program, DeviceConfiguration.program(device));
			assertEquals(1, list(line, "devices").size());
		} finally {
			TestScanTakeOver.delete(project);
			deleteAll(productData);
		}
	}

	/**
	 * A device reporting no text as its order info is named and refused by its hardware type
	 * (haumacher/ehome #183, the device 1.1.7 of the test system reports <code>FF</code> bytes).
	 */
	public void testHardwareTypeWhereNoOrderNumber() throws Exception {
		TLObject project = TestScanTakeOver.emptyProject("Stub without order number");
		try {
			FoundDevice found = device(STUB_ADDRESS, 0x20BE, "FF FF FF FF FF FF FF FF FF FF");
			assertNull(found.orderNumber());
			assertEquals("00 0A 00 00 00 34", found.orderText());
			TLObject stub;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stub = DeviceStubs.record(project, found);
				tx.commit();
			}
			assertEquals("00 0A 00 00 00 34", stub.tValueByName("orderNumber"));
			assertEquals("FF FF FF FF FF FF FF FF FF FF", stub.tValueByName("orderInfo"));
			String manufacturer = (String) CatalogMatch.manufacturer(GIRA).tValueByName("name");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				DeviceStubs.assign(stub);
				fail("The catalog has no product for the device.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_NO_PRODUCT_DATA_HARDWARE__MANUFACTURER_TYPE.fill(manufacturer,
					"00 0A 00 00 00 34"), ex.getErrorKey());
			}
		} finally {
			TestScanTakeOver.delete(project);
		}
	}

	/**
	 * A take-over of the device identified at the address of a stub replaces the stub, keeping its
	 * name and location; a row the catalog does not identify at the address of a device of the
	 * catalog is refused.
	 */
	public void testTakeOverReplacesStub() throws Exception {
		TLObject project = TestScanTakeOver.emptyProject("Stub replaced");
		try {
			FoundDevice unknown = device(0x1332, 0x10D7, null, 0x12);
			TLObject stub;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stub = DeviceStubs.record(project, unknown);
				stub.tUpdateByName("title", "Tastsensor Küche");
				tx.commit();
			}
			FoundDevice tastsensor = device(0x1332, 0x10D7, null, 0x11);
			CatalogMatch.Match match = CatalogMatch.match(project, tastsensor);
			assertNotSame(CatalogMatch.Kind.UNIDENTIFIED, match.kind());
			InstallationBuilder.Outcome outcome;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				outcome = InstallationBuilder.apply(project, tastsensor, match.candidates().get(0));
				tx.commit();
			}
			assertEquals(InstallationBuilder.Action.CREATED, outcome.action());
			assertFalse(stub.tValid());
			TLObject device = outcome.device();
			assertSame(device, InstallationBuilder.device(project, 0x1332));
			assertEquals("Tastsensor Küche", device.tValueByName("title"));
			assertEquals(Integer.valueOf(50), device.tValueByName("address"));

			Map<String, Object> row = ScanRows.row(project, unknown, CatalogMatch.match(project, unknown));
			assertEquals(I18NConstants.ERROR_UNIDENTIFIED_EXISTS__DEVICE_REASON.fill(device.tValueByName("name"),
				ScanRows.match(row).reasonKey()), ScanFunctions.applyVeto(row));
		} finally {
			TestScanTakeOver.delete(project);
		}
	}

	/**
	 * Round trip (<code>doc/knx/export.md</code>): a project with a stub is exported without it, and the
	 * export names it; the file imported again has the devices of the catalog and no stub.
	 */
	public void testExportLeavesOutStubs() throws Exception {
		TLObject stub;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			stub = DeviceStubs.record(_original, device(0x1307, 0x20BD, ORDER_NUMBER));
			tx.commit();
		}
		File export = File.createTempFile("stub", ".knxproj");
		List<TLObject> leftOut;
		try {
			try (OutputStream out = new FileOutputStream(export)) {
				leftOut = KnxprojExporter.export(_original, out);
			}
			assertEquals(List.of(stub), leftOut);
			String label = MetaLabelProvider.INSTANCE.getLabel(stub);
			assertEquals(de.haumacher.ehome.exporter.I18NConstants.EXPORT_LEFT_OUT__DEVICES.fill(label),
				KnxprojExporter.leftOutNote(_original));
			assertTrue(text(KnxprojExporter.leftOutNote(_original)), text(KnxprojExporter.leftOutNote(_original)).contains(label));

			List<TLObject> before = instances("ehome.project:Project");
			ThreadContext.inSystemContext(TestDeviceStubs.class, () -> {
				importSample(export.getPath());
				return null;
			});
			List<TLObject> reimported = SharedData.newProjects(before);
			try {
				assertEquals(1, reimported.size());
				TLObject copy = reimported.get(0);
				assertNotNull(InstallationBuilder.device(copy, 0x1305));
				assertNotNull(InstallationBuilder.device(copy, 0x1332));
				assertNull("The stub is left out.", InstallationBuilder.device(copy, 0x1307));
				assertNull(KnxprojExporter.leftOutNote(copy));
			} finally {
				ThreadContext.inSystemContext(TestDeviceStubs.class, () -> {
					SharedData.deleteProjects(reimported);
					return null;
				});
			}
		} finally {
			export.delete();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stub.tDelete();
				tx.commit();
			}
		}
		assertNull(KnxprojExporter.leftOutNote(_original));
	}

	/**
	 * The migration <code>Issue_183_Device_stubs</code> leaves the former records as stubs among the
	 * unplaced devices of their project, with the whole individual address and the serial number in
	 * hex; its startup action places each in the line of its address, creating the line where the
	 * project has none, with its device number and the serial number as ETS stores it; a record whose
	 * address holds a device of the project stays among the unplaced devices.
	 */
	public void testMigration() throws Exception {
		TLObject project = TestScanTakeOver.emptyProject("Migrated stubs");
		try {
			TLObject line;
			TLObject placed;
			TLObject created;
			TLObject occupied;
			TLObject unplaced;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				line = InstallationBuilder.line(project, 1, 1);
				occupied = DeviceStubs.record(project, device(0x1105, 0x20BD, null));
				placed = record(project, 0x1107, "000A0476250B");
				created = record(project, 0x2205, null);
				unplaced = record(project, 0x1105, null);
				tx.commit();
			}
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				new DeviceStubMigration().perform();
				tx.commit();
			}
			assertSame(line, placed.tContainer());
			assertEquals(Integer.valueOf(7), placed.tValueByName("address"));
			assertEquals(Base64.getEncoder().encodeToString(SERIAL), placed.tValueByName("serialNumber"));
			assertEquals("000A:0476250B", placed.tValueByName("serialText"));
			assertNotNull(ExtIds.extId(placed));
			assertSame(placed, InstallationBuilder.device(project, 0x1107));

			assertSame("The line 2.2 is created.", created, InstallationBuilder.device(project, 0x2205));
			assertEquals("2.2", created.tContainer().tValueByName("name"));
			assertEquals(Integer.valueOf(5), created.tValueByName("address"));

			assertSame(occupied, InstallationBuilder.device(project, 0x1105));
			assertEquals(List.of(unplaced), list(project, "unassignedDevices"));
			assertEquals(Integer.valueOf(5), unplaced.tValueByName("address"));
			assertNotNull(ExtIds.extId(unplaced));
		} finally {
			TestScanTakeOver.delete(project);
		}
	}

	/**
	 * The devices view shows a stub in a case of its own without the tab bar of a device (parameters,
	 * objects, programming, and the tab the development tooling adds to the bar), with "Assign".
	 */
	public void testViewOfAStub() throws Exception {
		NodeList cases = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(DEVICES_VIEW))
			.getElementsByTagName("case");
		Element stubCase = null;
		Element deviceCase = null;
		for (int n = 0; n < cases.getLength(); n++) {
			Element element = (Element) cases.item(n);
			if (element.getAttribute("test").contains("ehome.physical:DeviceStub")) {
				stubCase = element;
			} else if (element.getAttribute("test").contains("`ehome.physical:Device`")) {
				deviceCase = element;
			}
		}
		assertNotNull(stubCase);
		assertNotNull(deviceCase);
		assertEquals(0, stubCase.getElementsByTagName("tab-bar").getLength());
		assertEquals(1, deviceCase.getElementsByTagName("tab-bar").getLength());
		assertEquals("device-tabs", ((Element) deviceCase.getElementsByTagName("tab-bar").item(0)).getAttribute("id"));
		boolean assign = false;
		NodeList scripts = stubCase.getElementsByTagName("execute-script");
		for (int n = 0; n < scripts.getLength(); n++) {
			Node script = scripts.item(n);
			assign |= ((Element) script).getAttribute("function").startsWith("deviceStubAssign(");
		}
		assertTrue(assign);
	}

	/** A stub as the migration leaves a former record: among the unplaced devices, with the whole address. */
	private static TLObject record(TLObject project, int address, String serialHex) {
		TLObject stub = create(DeviceStubs.STUB_TYPE);
		stub.tUpdateByName("address", Integer.valueOf(address));
		stub.tUpdateByName("serialNumber", serialHex);
		stub.tUpdateByName("manufacturerId", Integer.valueOf(GIRA));
		stub.tUpdateByName("programVersion", "000820BD11");
		List<TLObject> unassigned = list(project, "unassignedDevices");
		unassigned.add(stub);
		project.tUpdateByName("unassignedDevices", unassigned);
		return stub;
	}

	/** A GIRA device found on the bus running the given application number, version 11. */
	private static FoundDevice device(int address, int application, String orderInfo) {
		return device(address, application, orderInfo, 0x11);
	}

	private static FoundDevice device(int address, int application, String orderInfo, int version) {
		byte[] program = { 0x00, GIRA, (byte) (application >>> 8), (byte) application, (byte) version };
		return new FoundDevice(address, FoundDevice.Source.BUS, Integer.valueOf(0x0705), Integer.valueOf(GIRA), SERIAL,
			program, HARDWARE_TYPE, orderInfo, List.of(), List.of());
	}

	/**
	 * Creates the product data of a GIRA device in the catalog, as an import of the manufacturer's
	 * product data would: a program with the given application number and version, and a product
	 * with a variant of the given order number carrying it.
	 *
	 * @param created
	 *        Takes the objects created, to be deleted.
	 * @return The program.
	 */
	private static TLObject productData(List<TLObject> created, int application, int version, String orderNumber) {
		TLObject manufacturer = CatalogMatch.manufacturer(GIRA);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject program = create("ehome.operational:Program");
			program.tUpdateByName("applicationNumber", Integer.valueOf(application));
			program.tUpdateByName("applicationVersion", Integer.valueOf(version));
			List<TLObject> programs = list(manufacturer, "programs");
			programs.add(program);
			manufacturer.tUpdateByName("programs", programs);

			TLObject product = create("ehome.catalog:Product");
			product.tUpdateByName("name", "Test product " + orderNumber);
			TLObject variant = create("ehome.catalog:Variant");
			variant.tUpdateByName("orderNumber", orderNumber);
			TLObject assignment = create("ehome.catalog:ProgramAssignment");
			assignment.tUpdateByName("program", program);
			product.tUpdateByName("variants", List.of(variant));
			product.tUpdateByName("programs", List.of(assignment));
			List<TLObject> products = list(manufacturer, "products");
			products.add(product);
			manufacturer.tUpdateByName("products", products);
			tx.commit();
			created.add(product);
			created.add(program);
			return program;
		}
	}

	private static void deleteAll(List<TLObject> objects) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject obj : objects) {
				if (obj.tValid()) {
					obj.tDelete();
				}
			}
			tx.commit();
		}
	}

	private static Map<String, Object> result(Map<String, Object> row) {
		Map<String, Object> result = new HashMap<>();
		result.put("summary", "Scan");
		result.put("rows", List.of(row));
		result.put("notes", List.of());
		return result;
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static List<TLObject> list(TLObject obj, String reference) {
		List<TLObject> result = new ArrayList<>();
		for (Object element : (Collection<?>) obj.tValueByName(reference)) {
			result.add((TLObject) element);
		}
		return result;
	}

	private static String text(com.top_logic.basic.util.ResKey key) {
		return Resources.getInstance().getString(key);
	}

	/**
	 * Imports the test system for the tests of this class, and deletes it again.
	 */
	private static Test withProjects(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestDeviceStubs.class, () -> {
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
				ThreadContext.inSystemContext(TestDeviceStubs.class, () -> {
					SharedData.deleteProjects(SharedData.newProjects(_before));
					return null;
				});
				_original = null;
				_before = null;
			}
		};
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(withProjects(new TestSuite(TestDeviceStubs.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SchedulerService.Module.INSTANCE));
	}

}
