/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.programming.MockDevice;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.scan.BusScanner;
import de.haumacher.ehome.scan.DeviceInfo;
import de.haumacher.ehome.scan.DeviceInfoFunctions;
import de.haumacher.ehome.scan.DeviceInfoJob;
import de.haumacher.ehome.scan.I18NConstants;
import de.haumacher.ehome.scan.InstallationBuilder;

/**
 * The device info of a device of the project (haumacher/ehome #175): the device at the address of
 * the actuator 1.3.5 of the test system (<code>samples/KNX-Testsystem.knxproj</code>) answers from its
 * read-back fixtures (<code>src/test/fixtures/readback/</code>), read by the job of the command as it
 * runs in the tab "Programming", over the project's gateway (the reader replaced). Identity and state
 * read back equal what the device holds and compare equal with the project; the serial number the
 * project does not know is taken over; the Tastsensor's device at that address is marked as different
 * and its serial number not taken over; a device that does not answer is the "no answer" result, a
 * path that cannot be opened a failure. Nothing is written to the device, and the write switch does
 * not apply.
 *
 * <p>
 * The same reading through the virtual device, over a gateway and through a controller, is
 * <code>test.de.haumacher.ehome.virtual.tooling.TestVirtualDeviceInfo</code> of the tooling.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDeviceInfo extends BasicTestCase {

	private static final int ACTUATOR = 0x1305;

	private static final String PROGRAMMING = "src/main/webapp/WEB-INF/views/projects/device-programming.view.xml";

	private static final String DIALOG = "src/main/webapp/WEB-INF/views/projects/device-info.view.xml";

	private static final String SERIAL = "008300001234";

	public void testDeviceInfo() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			TLObject device = InstallationBuilder.device(project, ACTUATOR);
			assertNotNull(device);
			assertNull("The test system's project knows no serial numbers.", device.tValueByName("serialNumber"));

			// No path: refused with the reason of the path.
			assertEquals(de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_BUS_PATH,
				DeviceInfo.veto(device));
			inTransaction(() -> project.tUpdateByName("gateway", "192.0.2.1"));
			// Nothing is written: the write switch (off in the tests) does not apply.
			assertFalse(CalimeroWriter.writeEnabled());
			assertNull(DeviceInfo.veto(device));
			String path = BusPath.describe(device);
			assertEquals("Gateway 192.0.2.1:3671", path);

			// The actuator answers: its identity is the project's, its serial number new to the project.
			MockDevice actuator = actuator();
			Map<?, ?> result = run(device, address -> actuator);
			DeviceInfo info = DeviceInfo.of(result);
			assertNotNull(info);
			assertTrue(info.answered());
			assertEquals("1.3.5", info.addressText());
			assertEquals(path, info.path());
			assertSame(project, result.get(DeviceInfo.PROJECT));
			assertSame(device, result.get(DeviceInfo.DEVICE));
			// What the device holds.
			assertEquals("0705", info.found().descriptorText());
			assertEquals("0083:00001234", info.found().serialText());
			assertEquals("00 83 00 0C 21", info.found().programVersionText());
			assertEquals("00 00 00 00 03 0D", info.found().hardwareTypeText());
			assertEquals(Boolean.FALSE, info.programmingMode());
			assertEquals(List.of("1: Loaded", "2: Loaded", "3: Loaded", "Programming mode: Off"), english(info.state()));
			// Compared with the project.
			Map<String, DeviceInfo.Row> rows = rows(info);
			assertEquals(List.of("mask", "manufacturer", "serialNumber", "orderInfo", "hardwareType", "program"),
				List.copyOf(rows.keySet()));
			assertEquals(DeviceInfo.Comparison.EQUAL, rows.get("mask").comparison());
			assertEquals("0705", rows.get("mask").project());
			assertEquals(DeviceInfo.Comparison.EQUAL, rows.get("manufacturer").comparison());
			assertTrue(rows.get("manufacturer").device(), rows.get("manufacturer").device().contains("M-0083"));
			assertEquals(DeviceInfo.Comparison.NOT_IN_PROJECT, rows.get("serialNumber").comparison());
			assertEquals("The device reports no order info.", DeviceInfo.Comparison.NOT_READ,
				rows.get("orderInfo").comparison());
			assertNotSame(DeviceInfo.Comparison.DIFFERENT, rows.get("hardwareType").comparison());
			assertEquals(DeviceInfo.Comparison.EQUAL, rows.get("program").comparison());
			assertTrue(rows.get("program").project(), rows.get("program").project().startsWith("M-0083_A-000C-21"));
			assertTrue(rows.get("program").device(), rows.get("program").device().startsWith("00 83 00 0C 21"));
			assertEquals(0, info.count(DeviceInfo.Comparison.DIFFERENT));
			assertEquals(I18NConstants.INFO_DONE__ADDRESS_PATH_DIFFERENT_UNKNOWN.fill("1.3.5", path, 0L, 1L),
				info.summary());
			assertEquals(0, actuator.writes());
			assertTrue(actuator.calls().toString(), actuator.calls().contains("readProperty 0 54 1 1"));
			assertEquals("disconnect", actuator.calls().get(actuator.calls().size() - 1));
			// The rows of the dialog: the serial number new to the project as a note, nothing differs.
			List<Map<String, Object>> shown = DeviceInfoFunctions.rows(info);
			assertEquals(6, shown.size());
			assertEquals(List.of("info"), shown.stream().map(r -> r.get("role")).filter(r -> r != null).toList());

			// The serial number is taken over: the project knows it afterwards.
			assertNull(info.serialVeto());
			inTransaction(info::takeSerial);
			assertEquals(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(SERIAL)),
				device.tValueByName("serialNumber"));
			assertEquals(DeviceInfo.Comparison.EQUAL, rows(info).get("serialNumber").comparison());
			assertEquals(I18NConstants.ERROR_INFO_SERIAL_KNOWN__SERIAL.fill("0083:00001234"), info.serialVeto());
			assertEquals(I18NConstants.INFO_DONE__ADDRESS_PATH_DIFFERENT_UNKNOWN.fill("1.3.5", path, 0L, 0L),
				info.summary());

			// Another device at the address (the Tastsensor's program): marked as different, its serial
			// number is not taken over.
			MockDevice other = TestBusScan.mock("DI-3-1.3.50")
				.setProperty(0, BusScanner.PID_SERIAL_NUMBER, "000800005678")
				.setProperty(0, BusScanner.PID_PROGRAMMING_MODE, "01");
			DeviceInfo otherInfo = DeviceInfo.of(run(device, address -> other));
			Map<String, DeviceInfo.Row> otherRows = rows(otherInfo);
			assertEquals(DeviceInfo.Comparison.DIFFERENT, otherRows.get("program").comparison());
			assertEquals(DeviceInfo.Comparison.DIFFERENT, otherRows.get("manufacturer").comparison());
			assertEquals(DeviceInfo.Comparison.DIFFERENT, otherRows.get("serialNumber").comparison());
			assertEquals("The same mask.", DeviceInfo.Comparison.EQUAL, otherRows.get("mask").comparison());
			assertEquals(Boolean.TRUE, otherInfo.programmingMode());
			assertTrue(english(otherInfo.state()).contains("Programming mode: On"));
			assertTrue(DeviceInfoFunctions.rows(otherInfo).stream()
				.anyMatch(r -> "program".equals(r.get("name")) && "warning".equals(r.get("role"))));
			assertEquals(I18NConstants.ERROR_INFO_OTHER_DEVICE__ADDRESS_WHAT.fill("1.3.5", I18NConstants.INFO_PROGRAM),
				otherInfo.serialVeto());
			assertRefused(I18NConstants.ERROR_INFO_OTHER_DEVICE__ADDRESS_WHAT.fill("1.3.5", I18NConstants.INFO_PROGRAM),
				() -> inTransaction(otherInfo::takeSerial));
			assertEquals(0, other.writes());

			// No device at the address: a result, not an error.
			List<String> asked = new ArrayList<>();
			DeviceInfo none = DeviceInfo.of(run(device, address -> new DeviceReader() {
				@Override
				public byte[] readDescriptor() throws BusException {
					asked.add("readDescriptor");
					throw new BusException("no answer");
				}

				@Override
				public byte[] readProperty(int objectIndex, int propertyId, int start, int count) {
					asked.add("readProperty");
					return new byte[0];
				}

				@Override
				public byte[] readMemory(int memoryAddress, int length) {
					asked.add("readMemory");
					return new byte[0];
				}

				@Override
				public void disconnect() {
					asked.add("disconnect");
				}

				@Override
				public void close() {
					// Nothing to release.
				}
			}));
			assertFalse(none.answered());
			assertEquals(I18NConstants.INFO_NO_ANSWER__ADDRESS_PATH.fill("1.3.5", path), none.summary());
			assertEquals("No device answers at 1.3.5 through Gateway 192.0.2.1:3671.", DownloadPlan.english(none.summary()));
			assertEquals("Nothing is read of a device that does not answer.", List.of("readDescriptor", "disconnect"), asked);
			assertEquals(I18NConstants.INFO_NO_ANSWER__ADDRESS_PATH.fill("1.3.5", path), none.serialVeto());

			// The path cannot be opened: the job ends with the failure.
			Map<?, ?> failed = run(device, address -> {
				throw new BusException("gateway down");
			});
			assertNull(DeviceInfo.of(failed));
			assertTrue(String.valueOf(failed.get(DeviceInfo.SUMMARY)),
				String.valueOf(failed.get(DeviceInfo.SUMMARY)).contains("gateway down"));

			// The command needs Read on the project, the take-over Write (nothing else of the command changes data).
			assertEquals("Read", access(PROGRAMMING, "deviceInfoVeto("));
			assertEquals("Write", access(DIALOG, "deviceInfoSerialVeto("));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static MockDevice actuator() throws Exception {
		return TestBusScan.mock("DI-2-1.3.5")
			.setProperty(0, BusScanner.PID_SERIAL_NUMBER, SERIAL)
			.setProperty(0, BusScanner.PID_MANUFACTURER_ID, "0083")
			.setProperty(0, BusScanner.PID_PROGRAMMING_MODE, "00");
	}

	private interface Readers {
		DeviceReader open(int address) throws BusException;
	}

	/**
	 * Runs the job of the command for the device, the reader opened by the given function.
	 */
	private static Map<?, ?> run(TLObject device, Readers readers) throws Exception {
		DeviceInfoJob job = new DeviceInfoJob(null, TypedConfiguration.newConfigItem(DeviceInfoJob.Config.class)) {
			@Override
			protected DeviceReader openReader(BusPath path, int address) throws BusException {
				assertEquals(ACTUATOR, address);
				return readers.open(address);
			}
		};
		Object result = new RecordingJobMonitor().run(job, List.of(device));
		assertTrue(String.valueOf(result), result instanceof Map<?, ?>);
		return (Map<?, ?>) result;
	}

	private static Map<String, DeviceInfo.Row> rows(DeviceInfo info) {
		Map<String, DeviceInfo.Row> result = new LinkedHashMap<>();
		for (DeviceInfo.Row row : info.identity()) {
			result.put(row.name(), row);
		}
		return result;
	}

	private static List<String> english(List<ResKey> keys) {
		return keys.stream().map(DownloadPlan::english).toList();
	}

	/**
	 * The operation of the access check of the command whose executability names the given function.
	 */
	private static String access(String file, String function) throws Exception {
		NodeList rules = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(file))
			.getElementsByTagName("disabled-if");
		List<String> result = new ArrayList<>();
		for (int n = 0; n < rules.getLength(); n++) {
			Element rule = (Element) rules.item(n);
			if (rule.getAttribute("expr").contains(function)) {
				Element command = (Element) rule.getParentNode().getParentNode();
				Element operation = (Element) command.getElementsByTagName("operation").item(0);
				result.add(operation.getAttribute("name"));
			}
		}
		assertEquals(file + " " + function, 1, result.size());
		return result.get(0);
	}

	private interface Body {
		void run() throws Exception;
	}

	private static void inTransaction(Body body) throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			body.run();
			tx.commit();
		}
	}

	private static void assertRefused(ResKey reason, Body body) throws Exception {
		try {
			body.run();
			fail("Refusal expected: " + reason);
		} catch (TopLogicException ex) {
			assertEquals(reason, ex.getErrorKey());
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeviceInfo.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
