/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.programming.MockDevice;

import com.top_logic.basic.Logger;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.scan.BusScanner;
import de.haumacher.ehome.scan.CatalogMatch;
import de.haumacher.ehome.scan.FoundDevice;
import de.haumacher.ehome.scan.InstallationBuilder;
import de.haumacher.ehome.scan.ReadBack;
import de.haumacher.ehome.scan.ScanBus;

/**
 * Building a project from an installation (haumacher/ehome #9): the devices of the user's test
 * system as they answer a scan (the recorded answers of <code>src/test/fixtures/readback/</code>),
 * matched against the catalog of <code>samples/KNX-Testsystem.knxproj</code>, created in a new
 * project, and their configuration read back from their memory, which must reproduce the project
 * the devices were programmed from.
 *
 * <p>
 * The test takes longer than the budget of a test class (5 s): reading back the configuration of the
 * Tastsensor searches the values of its parameters without memory (<code>ReadBack.apply</code>,
 * several seconds for its program of 2187 parameters).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBusScan extends BasicTestCase {

	private static final File READBACK = new File("src/test/fixtures/readback");

	private static final int ROUTER = 0x1301;

	private static final int ACTUATOR = 0x1305;

	private static final int TASTSENSOR = 0x1332;

	public void testScan() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject original = projects.get(0);
		TLObject scanned = null;
		try {
			FakeBus bus = new FakeBus();
			BusScanner.Result result = BusScanner.scan(bus, new BusScanner.Request(1, 3, false, true, false), listener());
			assertEquals(result.notes().toString(), 0, result.notes().size());
			assertEquals(3, result.devices().size());
			FoundDevice router = result.devices().get(0);
			FoundDevice actuator = result.devices().get(1);
			FoundDevice tastsensor = result.devices().get(2);
			assertEquals(FoundDevice.Source.LOCAL, router.source());
			assertEquals("1.3.1", router.addressText());
			assertEquals("1.3.5", actuator.addressText());
			assertEquals("1.3.50", tastsensor.addressText());
			assertEquals("0705", actuator.descriptorText());
			assertEquals("00 83 00 0C 21", actuator.programVersionText());
			assertEquals("00 08 10 D7 11", tastsensor.programVersionText());
			assertEquals(Integer.valueOf(0x83), actuator.manufacturerId());
			assertEquals("0083:00001234", actuator.serialText());
			assertEquals(List.of("1: loaded", "2: loaded", "3: loaded"), actuator.loadStates());
			// Scanning is reading.
			assertEquals(0, bus.writes());

			// Matching.
			CatalogMatch.Match actuatorMatch = CatalogMatch.match(actuator);
			assertMatch(actuatorMatch, "M-0083_A-000C-21-83B5", "AKK-0416.03");
			CatalogMatch.Match tastsensorMatch = CatalogMatch.match(tastsensor);
			assertMatch(tastsensorMatch, "M-0008_A-10D7-11-A0E5-O000A", "5133 00");
			assertNull(router.programVersion());
			assertEquals("00C5:0101BC95", router.serialText());
			CatalogMatch.Match routerMatch = CatalogMatch.match(router);
			assertMatch(routerMatch, "M-006A_A-0703-10-FD20-O00C5", "N000402");
			FoundDevice unknown = new FoundDevice(0x1307, FoundDevice.Source.BUS, 0x0705, 0x08, null,
				HexFormat.of().parseHex("000810d712"), null, null, List.of(), List.of());
			CatalogMatch.Match unknownMatch = CatalogMatch.match(unknown);
			assertEquals(CatalogMatch.Kind.UNIDENTIFIED, unknownMatch.kind());
			assertTrue(unknownMatch.reason(), unknownMatch.reason().contains("10D7"));
			FoundDevice foreign = new FoundDevice(0x1308, FoundDevice.Source.BUS, 0x0705, 0x7FFE, null,
				HexFormat.of().parseHex("7ffe000101"), null, null, List.of(), List.of());
			assertEquals(CatalogMatch.Kind.UNIDENTIFIED, CatalogMatch.match(foreign).kind());

			// Creation.
			try (Transaction tx = kb.beginTransaction()) {
				scanned = ModelService.getInstance().getFactory()
					.createObject((com.top_logic.model.TLClass) TLModelUtil.findType("ehome.project:Project"));
				scanned.tUpdateByName("name", "Scan");
				scanned.tUpdateByName("groupAddressStyle", TLModelUtil.findPart("ehome.logical:GroupAddressStyle#ThreeLevel"));
				ExtIds.assign(scanned);
				tx.commit();
			}
			// The programs of the project the devices were programmed from: the shared catalog may hold
			// other editions of them as well (the ETS 6 export of the test system).
			try (Transaction tx = kb.beginTransaction()) {
				assertEquals(InstallationBuilder.Action.CREATED,
					InstallationBuilder.apply(scanned, actuator, candidate(actuatorMatch,"M-0083_A-000C-21-83B5")).action());
				assertEquals(InstallationBuilder.Action.CREATED,
					InstallationBuilder.apply(scanned, tastsensor, candidate(tastsensorMatch, "M-0008_A-10D7-11-A0E5-O000A")).action());
				assertEquals(InstallationBuilder.Action.CREATED,
					InstallationBuilder.apply(scanned, router, candidate(routerMatch, "M-006A_A-0703-10-FD20-O00C5")).action());
				InstallationBuilder.recordUnidentified(scanned, unknown, unknownMatch.reason());
				tx.commit();
			}
			assertEquals(1, list(scanned, "areas").size());
			TLObject line = list(list(scanned, "areas").get(0), "lines").get(0);
			assertEquals("1.3", line.tValueByName("name"));
			assertEquals(3, list(line, "devices").size());
			TLObject device = InstallationBuilder.device(scanned, ACTUATOR);
			assertNotNull(device);
			assertEquals(Integer.valueOf(5), device.tValueByName("address"));
			assertEquals(actuator.serialStored(), device.tValueByName("serialNumber"));
			assertNotNull(ExtIds.extId(device));

			// A second scan updates, a different program is a conflict.
			try (Transaction tx = kb.beginTransaction()) {
				assertEquals(InstallationBuilder.Action.UPDATED,
					InstallationBuilder.apply(scanned, actuator, candidate(actuatorMatch, "M-0083_A-000C-21-83B5")).action());
				FoundDevice moved = new FoundDevice(TASTSENSOR, FoundDevice.Source.BUS, 0x0705, 0x83, null,
					actuator.programVersion(), null, null, List.of(), List.of());
				assertEquals(InstallationBuilder.Action.CONFLICT,
					InstallationBuilder.apply(scanned, moved, candidate(actuatorMatch, "M-0083_A-000C-21-83B5")).action());
				tx.commit();
			}
			assertEquals(3, list(line, "devices").size());
			assertEquals(1, list(scanned, "unidentifiedDevices").size());
			FoundDevice recorded = InstallationBuilder.found(list(scanned, "unidentifiedDevices").get(0));
			assertEquals(unknown.programVersionText(), recorded.programVersionText());
			assertEquals(CatalogMatch.Kind.UNIDENTIFIED, CatalogMatch.match(recorded).kind());

			// Read-back reproduces the project the devices were programmed from.
			checkReadBack(kb, scanned, original, ACTUATOR, "DI-2-1.3.5");
			checkReadBack(kb, scanned, original, TASTSENSOR, "DI-3-1.3.50");
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				original.tDelete();
				if (scanned != null) {
					scanned.tDelete();
				}
				tx.commit();
			}
		}
	}

	private static void checkReadBack(KnowledgeBase kb, TLObject scanned, TLObject original, int address, String fixture)
			throws Exception {
		TLObject device = InstallationBuilder.device(scanned, address);
		MockDevice mock = mock(fixture);
		ReadBack.Memory memory = ReadBack.read(device, mock, (done, total, what) -> {
			// Ignore.
		});
		assertEquals(0, mock.writes());
		ReadBack.Report report;
		try (Transaction tx = kb.beginTransaction()) {
			report = ReadBack.apply(device, memory);
			tx.commit();
		}
		Logger.info(fixture + ": " + report, TestBusScan.class);
		assertEquals(report.problems().toString(), 0, report.problems().size());
		assertTrue(report.compared() > 0);
		assertEquals(fixture + " differs at " + report.differences(), 0, report.differences().size());

		// The comparison with the device reports every block identical.
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		DeviceComparison.Result result = DeviceComparison.compare(DeviceConfiguration.program(device), plan.getImage(),
			plan.getUnloadedBlocks(), mock, (number, count, block) -> {
				// Ignore.
			});
		for (DeviceComparison.BlockResult block : result.blocks()) {
			assertTrue(fixture + " " + block.name() + ": " + block.ranges() + " " + block.failure(), block.identical());
		}

		// The connections and the values in effect are those of the project ETS programmed.
		TLObject programmed = InstallationBuilder.device(original, address);
		assertEquals(connections(programmed), connections(device));
		// The values of the parameters in effect in both are those of the project. Parameters without
		// memory leave no trace in the device but through what they open; where two choices yield the
		// same bytes (a union member of the same value), the read-back keeps the default.
		Map<String, String> expected = values(programmed);
		Map<String, String> actual = values(device);
		StringBuilder others = new StringBuilder();
		int common = 0;
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			if (actual.containsKey(entry.getKey()) && !entry.getKey().startsWith("(ui) ")) {
				assertEquals(fixture + " " + entry.getKey(), entry.getValue(), actual.get(entry.getKey()));
				common++;
			} else if (!java.util.Objects.equals(entry.getValue(), actual.get(entry.getKey()))) {
				others.append(' ').append(entry.getKey()).append('=').append(entry.getValue()).append(" read ")
					.append(actual.get(entry.getKey()));
			}
		}
		for (Map.Entry<String, String> entry : actual.entrySet()) {
			if (!expected.containsKey(entry.getKey())) {
				others.append(" read ").append(entry.getKey()).append('=').append(entry.getValue());
			}
		}
		Logger.info(fixture + ": " + common + " parameter values equal the project's; in effect in one only:" + others
			+ "; values chosen for parameters without memory: " + report.inferred(), TestBusScan.class);
		long withMemory = expected.keySet().stream().filter(k -> !k.startsWith("(ui) ")).count();
		assertTrue(common + " of " + withMemory, common * 10 >= withMemory * 9);
		assertEquals(0, mock.writes());
	}

	private static TreeSet<String> connections(TLObject device) {
		TreeSet<String> result = new TreeSet<>();
		for (TLObject connector : new DeviceConfiguration(device).getConnectors()) {
			Interface placed = (Interface) connector.tValueByName("port");
			for (Object element : (Collection<?>) connector.tValueByName("connections")) {
				TLObject connection = (TLObject) element;
				GA ga = (GA) ((TLObject) connection.tValueByName("target")).tValueByName("address");
				result.add(placed.getPort().getIndex() + " " + ga.getValue() + " "
					+ ((TLClassifier) connection.tValueByName("kind")).getName());
			}
		}
		return result;
	}

	private static Map<String, String> values(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Map<String, String> result = new TreeMap<>();
		for (ParameterView view : configuration.getActiveViews().values()) {
			// Parameters without memory are marked: the device holds them only through what they open.
			result.put((view.getParameter().getAddress() == null ? "(ui) " : "") + view.getParameter().getExtId(),
				ParameterValues.encode(configuration.valueHolder(view)));
		}
		return result;
	}

	private static void assertMatch(CatalogMatch.Match match, String program, String orderNumber) {
		assertNotSame(match.reason(), CatalogMatch.Kind.UNIDENTIFIED, match.kind());
		CatalogMatch.Candidate candidate = candidate(match, program);
		if (orderNumber != null) {
			assertEquals(orderNumber, candidate.variant().tValueByName("orderNumber"));
		}
	}

	private static CatalogMatch.Candidate candidate(CatalogMatch.Match match, String program) {
		for (CatalogMatch.Candidate candidate : match.candidates()) {
			if (program.equals(candidate.program().getExtId())) {
				return candidate;
			}
		}
		fail("No candidate " + program + " among " + match.candidates().stream().map(CatalogMatch.Candidate::label).toList());
		return null;
	}

	private static List<TLObject> list(TLObject obj, String reference) {
		return ExtIds.list(obj, reference);
	}

	private static BusScanner.Listener listener() {
		return new BusScanner.Listener() {
			@Override
			public void message(String text) {
				// Ignore.
			}

			@Override
			public void progress(int done, int total) {
				// Ignore.
			}
		};
	}

	/**
	 * A device of the test system as its read-back fixtures give it.
	 */
	static MockDevice mock(String fixture) throws IOException {
		MockDevice result = new MockDevice();
		File[] files = READBACK.listFiles((dir, name) -> name.startsWith(fixture + "-") && name.matches(".*-[0-9A-F]{4}\\.hex"));
		for (File file : files) {
			TreeMap<Integer, byte[]> lines = new TreeMap<>();
			for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
				if (line.startsWith("#") || line.isBlank()) {
					continue;
				}
				int colon = line.indexOf(':');
				lines.put(Integer.parseInt(line.substring(0, colon), 16),
					HexFormat.of().parseHex(line.substring(colon + 1).replace(" ", "")));
			}
			lines.forEach(result::setMemory);
		}
		for (String line : Files.readAllLines(new File(READBACK, fixture + "-properties.txt").toPath(), StandardCharsets.UTF_8)) {
			if (line.startsWith("#") || line.contains("#")) {
				continue;
			}
			String[] key = line.substring(0, line.indexOf(':')).split(" ");
			String value = line.substring(line.indexOf(':') + 1).trim();
			int object = Integer.parseInt(key[0]);
			int property = Integer.parseInt(key[1]);
			if (property == 5) {
				result.setState(object, Integer.parseInt(value, 16));
			} else {
				result.setProperty(object, property, value);
			}
		}
		String descriptor = Files.readAllLines(new File(READBACK, fixture + "-descriptor.hex").toPath(), StandardCharsets.UTF_8)
			.stream().filter(l -> !l.startsWith("#") && !l.isBlank()).findFirst().orElseThrow();
		result.setDescriptor(descriptor.trim());
		return result;
	}

	/**
	 * The test line 1.3 as a scan finds it: the actuator and the Tastsensor answer, the router
	 * 1.3.1 (the gateway) only through local device management.
	 */
	static class FakeBus implements ScanBus {

		private final List<MockDevice> _devices = new ArrayList<>();

		@Override
		public String gateway() {
			return "test";
		}

		@Override
		public List<Integer> scanLine(int area, int line, Progress progress) {
			assertEquals(1, area);
			assertEquals(3, line);
			// The gateway answers a connection through its own tunnel, but not its reads.
			return List.of(ROUTER, ACTUATOR, TASTSENSOR);
		}

		@Override
		public List<Integer> scanRouters(Progress progress) {
			return List.of(ROUTER);
		}

		@Override
		public List<Integer> programmingMode() {
			return List.of();
		}

		@Override
		public MockDevice device(int address) throws BusException {
			MockDevice device;
			try {
				if (address == ACTUATOR) {
					// No serial number in the fixtures: the one of a device of this manufacturer.
					device = mock("DI-2-1.3.5").setProperty(0, BusScanner.PID_SERIAL_NUMBER, "008300001234")
						.setProperty(0, BusScanner.PID_MANUFACTURER_ID, "0083");
				} else if (address == TASTSENSOR) {
					device = mock("DI-3-1.3.50");
				} else {
					throw new BusException("No answer of " + FoundDevice.formatAddress(address) + ".");
				}
			} catch (IOException ex) {
				throw new BusException(ex.getMessage(), ex);
			}
			_devices.add(device);
			return device;
		}

		@Override
		public LocalProperties gatewayProperties() {
			return new LocalProperties() {
				@Override
				public byte[] readProperty(int objectType, int propertyId, int start, int count) throws BusException {
					if (objectType == BusScanner.KNXNETIP_OBJECT_TYPE && propertyId == BusScanner.PID_KNX_INDIVIDUAL_ADDRESS) {
						return new byte[] { 0x13, 0x01 };
					}
					if (objectType == 0 && propertyId == BusScanner.PID_MANUFACTURER_ID) {
						return new byte[] { 0x00, 0x6A };
					}
					if (objectType == 0 && propertyId == BusScanner.PID_ORDER_INFO) {
						// As the router of the test system reports it (no application object).
						return "N000402000".getBytes(StandardCharsets.ISO_8859_1);
					}
					if (objectType == 0 && propertyId == BusScanner.PID_SERIAL_NUMBER) {
						return HexFormat.of().parseHex("00c50101bc95");
					}
					throw new BusException("No property " + propertyId + " of object type " + objectType + ".");
				}

				@Override
				public void close() {
					// Nothing to release.
				}
			};
		}

		long writes() {
			return _devices.stream().mapToLong(MockDevice::writes).sum();
		}

		@Override
		public void close() {
			// Nothing to release.
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBusScan.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
