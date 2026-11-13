/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.programming.TestDownloadImage.readBack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
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
import de.haumacher.ehome.programming.download.DownloadService;
import de.haumacher.ehome.programming.download.Gateways;
import de.haumacher.ehome.programming.download.I18NConstants;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.Operation;

/**
 * Test of the download into a device (haumacher/ehome #7): the plans of the devices of
 * <code>samples/EHomeDemo.knxproj</code> (the IP router DI-1, the MDT actuator DI-2 at 1.3.5, the
 * Gira Tastsensor 3 DI-3 at 1.3.50), the stamps against what ETS wrote into the Tastsensor of the
 * test system, the executor against a {@link MockDevice}, and the comparison with the read-back of
 * the actuator.
 */
@SuppressWarnings("javadoc")
public class TestDownloadPlan extends BasicTestCase {

	private static final File READBACK = new File("src/test/fixtures/readback");

	private static final String ROUTER = "P-0353-0_DI-1";

	private static final String ACTUATOR = "P-0353-0_DI-2";

	private static final String SENSOR = "P-0353-0_DI-3";

	private List<TLObject> _projects;

	public void testDownload() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/EHomeDemo.knxproj");
		_projects = new ArrayList<>(instances("ehome.project:Project"));
		_projects.removeAll(before);
		try {
			checkSensorPlan();
			checkActuatorPlan();
			checkRouterRefused();
			checkAddressPlan();
			checkStampsAgainstDevice();
			checkDownload();
			checkRefusedStamps();
			checkFailure();
			checkHardwareMismatch();
			checkCompareActuator();
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

	public void testGateways() {
		assertEquals("192.168.178.81:3671", Gateways.format(Gateways.parse("192.168.178.81")));
		assertEquals("192.168.178.81:3672", Gateways.format(Gateways.parse(" 192.168.178.81:3672 ")));
		assertEquals("knx-gw.local:3671", Gateways.format(Gateways.parse("knx-gw.local")));
		assertNull(Gateways.parse(""));
		assertNull(Gateways.parse("192.168.178.81:x"));
		assertNull(Gateways.parse("192.168.178.81:70000"));
		assertNull(Gateways.parse("a b"));
	}

	public void testLoadControls() {
		assertEquals("04000000000000000000", hex(LoadControls.event(LoadControls.EVENT_UNLOAD)));
		assertEquals("03004000000 9f2038000".replace(" ", ""), hex(LoadControls.absoluteSegment(0, 0x4000, 9, 0xF2, 3, 0x80)));
		assertEquals("03024354000008 10d711".replace(" ", ""), hex(LoadControls.taskSegment(0x4354, 0, 8, 0x10D7, 0x11)));
		assertEquals("03040000000000000000", hex(LoadControls.taskControl1(0, 0)));
		// CRC-16/CCITT-FALSE check value.
		assertEquals(0x29B1, LoadControls.crc16("123456789".getBytes(StandardCharsets.US_ASCII)));
	}

	/**
	 * The Tastsensor: its own load procedure (30 steps) with the segments of the dynamic tables
	 * placed by their contents, the memory of the loaded segments in chunks of at most 12 bytes, the
	 * stamps of the stamped segments, the task segment of load state machine 5 not executed.
	 */
	private void checkSensorPlan() {
		DownloadPlan plan = DownloadPlanner.plan(device(SENSOR), DownloadPlan.Kind.FULL);
		Logger.info("Download plan of the Tastsensor:\n" + plan.dump(), TestDownloadPlan.class);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		assertEquals(
			"Connect CheckMask Unload1 Unload2 Unload3 Load1 Segment1 Task1 Write Completed1 Load2 Segment2 Task2 Write "
				+ "Completed2 Load3 Segment3*8 Task3 TaskControl3 Write Completed3 CompareProperty WriteProperty*8 Restart "
				+ "Skip Disconnect Skip",
			signature(plan));

		// The load controls: the tables of the demo wiring (three group addresses) placed by their
		// contents, the application's segments as the procedure names them.
		assertEquals(List.of(
			"1 04000000000000000000", "2 04000000000000000000", "3 04000000000000000000",
			"1 01000000000000000000", "1 0300400000 09f2038000", "1 03024000000008 10d711", "1 02000000000000000000",
			"2 01000000000000000000", "2 030040 0901dbf2038000", "2 03024009000008 10d711", "2 02000000000000000000",
			"3 01000000000000000000",
			"3 0300070000faf2020000", "3 030041e50177f2038000", "3 0300435c000df3038000", "3 03004369 0006f3038000",
			"3 0300436f00caf3038000", "3 030044390015f3038000", "3 0300444e0100f3038000", "3 0300454e0003f3030000",
			"3 03024354000008 10d711", "3 03040000000000000000", "3 02000000000000000000").stream()
			.map(s -> s.replace(" ", "")).map(s -> s.substring(0, 1) + " " + s.substring(1)).toList(),
			loadControls(plan));

		DownloadImage image = plan.getImage();
		Block unloaded = plan.getUnloadedBlocks().get(0);
		assertEquals("Segment 454E of SegFlags 0 is not loaded.", List.of(0x454E),
			plan.getUnloadedBlocks().stream().map(Block::address).toList());

		// The memory writes: exactly the bytes of the masks of the loaded blocks, and the zero fill of
		// the stamped segment of the association table up to its end (41E3).
		BitSet expected = new BitSet();
		for (Block block : image.getBlocks()) {
			if (block == unloaded) {
				continue;
			}
			for (int n = 0; n < block.data().length; n++) {
				if (block.mask()[n] != 0) {
					expected.set(block.address() + n);
				}
			}
		}
		expected.set(image.getAssociationTable().end(), 0x41E4);
		assertEquals(expected, written(plan));
		for (Operation.WriteMemory write : writes(plan)) {
			assertTrue(write.describe(), write.data().length <= DownloadPlanner.MAX_CHUNK);
			assertTrue(write.verify());
			Block block = blockAt(image, write.address());
			if (block != null) {
				// Within one block, under its mask, with the image's bytes.
				assertTrue(write.describe(), write.address() + write.data().length <= block.end());
				byte[] imageBytes = Arrays.copyOfRange(block.data(), write.address() - block.address(),
					write.address() - block.address() + write.data().length);
				assertEquals(write.describe(), hex(imageBytes), hex(write.data()));
			} else {
				assertEquals(write.describe(), "Zero fill of a stamped segment", DownloadPlan.english(write.what()));
			}
		}
		// The individual address (4001-4002) is not written by the download.
		assertFalse(written(plan).get(0x4001));
		assertFalse(written(plan).get(0x4002));

		// The stamps: one per segment with SegFlags 0x80, 8 bytes each.
		List<Operation.WriteProperty> stamps = plan.getOperations().stream()
			.filter(Operation.WriteProperty.class::isInstance).map(Operation.WriteProperty.class::cast).toList();
		assertEquals(List.of("1 27 1", "2 27 1", "3 27 1", "3 27 2", "3 27 3", "3 27 4", "3 27 5", "3 27 6"),
			stamps.stream().map(s -> s.objectIndex() + " " + s.propertyId() + " " + s.start()).toList());
		assertEquals("0000000900f2", hex(stamps.get(0).data()).substring(0, 12));
		assertEquals("000001db00f2", hex(stamps.get(1).data()).substring(0, 12));

		// The program version after the application is loaded.
		assertTrue(plan.dump(), plan.dump().contains("Compare object 3 property 13 with 00 08 10 D7 11"));
		// Load state machine 5 has no interface object on mask 7.5.
		assertTrue(plan.dump(), plan.dump().contains("[#29 TaskSegment LSM 5] Not executed"));
		// The hardware type parameter (object 0, property 78) is not written.
		assertTrue(plan.dump(), plan.dump().contains("Not executed: The parameter value 00 0A 00 00 00 3D of object 0 property 78"));
	}

	/**
	 * The actuator: the hardware type compared before anything is unloaded.
	 */
	private void checkActuatorPlan() {
		DownloadPlan plan = DownloadPlanner.plan(device(ACTUATOR), DownloadPlan.Kind.FULL);
		Logger.info("Download plan of the actuator:\n" + plan.dump(), TestDownloadPlan.class);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		assertEquals(
			"Connect CheckMask CompareProperty Unload1 Unload2 Unload3 Load1 Segment1 Task1 Write Completed1 Load2 Segment2 "
				+ "Task2 Write Completed2 Load3 Segment3*4 Task3 Write Completed3 CompareProperty Restart Disconnect",
			signature(plan));
		Operation.CompareProperty hardware = (Operation.CompareProperty) plan.getOperations().get(2);
		assertEquals(0, hardware.objectIndex());
		assertEquals(78, hardware.propertyId());
		assertEquals("00000000030d00000000", hex(hardware.expected()));
		assertEquals(List.of(
			"1 0300400002 01ff038000", "1 03024000010083000c21",
			"2 0300420101ffff038000", "2 03024201010083000c21",
			"3 03000700 0084000200 00", "3 0301078400010002 0000", "3 0300440000ecff038000", "3 030044ec0130ff038000",
			"3 03024400010083000c21").stream().map(s -> s.replace(" ", "")).map(s -> s.substring(0, 1) + " " + s.substring(1))
			.toList(),
			loadControls(plan).stream().filter(c -> c.substring(2).startsWith("03")).toList());
		assertEquals(List.of(), plan.getUnloadedBlocks());
	}

	/**
	 * The IP router (mask 9.1A, Bcu1): its generic procedure writes the coupler's slave and filter
	 * memories, which is refused with the steps named.
	 */
	private void checkRouterRefused() {
		DownloadPlan plan = DownloadPlanner.plan(device(ROUTER), DownloadPlan.Kind.FULL);
		assertFalse(plan.isExecutable());
		List<ResKey> problems = plan.getProblems();
		assertTrue(problems.toString(), problems.contains(I18NConstants.ERROR_MEMORY_SPACE__STEP_SPACE.fill("#5 WriteMem", "LcSlave")));
		assertTrue(problems.toString(), problems.contains(I18NConstants.ERROR_MEMORY_SPACE__STEP_SPACE.fill("#10 WriteMem", "LcFilter")));
		assertTrue(problems.toString(), problems.contains(I18NConstants.ERROR_UNSUPPORTED_STEP__STEP_KIND_MASK
			.fill("#9 ClearLCFilterTable", "ClearLCFilterTable", "Coupler 091A (MV-091A)")));
		assertTrue(problems.toString(), problems.contains(I18NConstants.ERROR_PARTIAL_MASK__ADDRESS_MASK.fill("0120", "80")));
	}

	private void checkAddressPlan() throws InterruptedException {
		DownloadPlan plan = DownloadPlanner.plan(device(ACTUATOR), DownloadPlan.Kind.ADDRESS);
		assertTrue(plan.isExecutable());
		assertEquals(1, plan.getOperations().size());
		assertEquals(0x1305, ((Operation.WriteIndividualAddress) plan.getOperations().get(0)).address());
		MockDevice device = new MockDevice();
		assertTrue(new DownloadExecutor(ms -> { /* No wait. */ }).execute(plan, device, (n, c, o) -> { /* Ignore. */ })
			.success());
		assertEquals(List.of("writeIndividualAddress 1305"), device.calls());
	}

	/**
	 * The Tastsensor wired as the read-back shows: the stamps of the tables equal those ETS wrote, the
	 * tables and the zero fill equal the device's memory, and the CRC of every stamp of the
	 * application equals the CRC over the device's memory.
	 */
	private void checkStampsAgainstDevice() throws IOException {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject device = device(SENSOR);
		byte[][] memory = readBack("DI-3-1.3.50-4000.hex");
		int addressTableLength = 1 + 2 * (memory[1][0] & 0xFF);
		int associationStart = 0x4000 + addressTableLength;
		byte[][] associations = { { (byte) (associationStart >>> 8), (byte) associationStart },
			Arrays.copyOfRange(memory[1], addressTableLength, memory[1].length) };
		byte[] deviceMemory = deviceMemory("DI-3-1.3.50");
		List<String> stamps = stamps();
		try (Transaction tx = kb.beginTransaction()) {
			TestDownloadImage.rewire(device, memory, associations);
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertEquals(plan.dump(), List.of(), plan.getProblems());
			List<Operation.WriteProperty> written = plan.getOperations().stream()
				.filter(Operation.WriteProperty.class::isInstance).map(Operation.WriteProperty.class::cast).toList();
			assertEquals(stamps.get(0), hex(written.get(0).data()));
			assertEquals(stamps.get(1), hex(written.get(1).data()));

			// Tables and fill: what is written below 41E4 is what the device holds.
			for (Operation.WriteMemory write : writes(plan)) {
				if (write.address() < 0x41E4) {
					assertEquals(write.describe(), hex(Arrays.copyOfRange(deviceMemory, write.address(),
						write.address() + write.data().length)), hex(write.data()));
				}
			}

			// The application: lengths and access as ETS wrote them; the CRC computed over the
			// device's memory equals the device's stamp (the parameters of this project differ).
			int[][] segments = { { 0x41E5, 375, 0xF2 }, { 0x435C, 13, 0xF3 }, { 0x4369, 6, 0xF3 }, { 0x436F, 202, 0xF3 },
				{ 0x4439, 21, 0xF3 }, { 0x444E, 256, 0xF3 } };
			for (int n = 0; n < segments.length; n++) {
				int[] segment = segments[n];
				assertEquals(stamps.get(2 + n).substring(0, 12), hex(written.get(2 + n).data()).substring(0, 12));
				assertEquals(stamps.get(2 + n), hex(LoadControls.stamp(segment[2],
					Arrays.copyOfRange(deviceMemory, segment[0], segment[0] + segment[1]))));
			}
		}
	}

	/**
	 * A full download into the actuator: the calls of the plan on a device of mask 7.5, the memory of
	 * the device equal to the image afterwards, and the download recorded at the device.
	 */
	private void checkDownload() throws Exception {
		TLObject device = device(ACTUATOR);
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		MockDevice mock = actuatorDevice();
		List<Integer> progress = new ArrayList<>();
		DownloadExecutor.Result result = DownloadService.download(device, plan, mock,
			new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> progress.add(number));
		assertTrue(result.failure(), result.success());
		assertEquals(plan.getOperations().size(), result.done());
		assertEquals(plan.getOperations().size(), progress.size());

		assertEquals(
			"readDescriptor readProperty0 writeLoadControl1 writeLoadControl2 writeLoadControl3 writeLoadControl1*3 "
				+ "(writeMemory readMemory)* writeLoadControl1 writeLoadControl2*3 (writeMemory readMemory)* "
				+ "writeLoadControl2 writeLoadControl3*6 (writeMemory readMemory)* writeLoadControl3 readProperty3 restart "
				+ "disconnect",
			callSignature(mock.calls()));
		for (Block block : plan.getImage().getBlocks()) {
			for (int n = 0; n < block.data().length; n++) {
				if (block.mask()[n] != 0) {
					assertEquals(String.format("%04X", block.address() + n), block.data()[n],
						mock.memory()[block.address() + n]);
				}
			}
		}
		Collection<?> loaded = (Collection<?>) device.tValueByName("loaded");
		assertEquals(5, loaded.size());
		assertNotNull(device.tValueByName("lastDownload"));
	}

	/**
	 * The device refuses to start loading the association table: the download stops there, nothing
	 * after it is sent, and the device's record stays as it was.
	 */
	private void checkFailure() throws Exception {
		TLObject device = device(SENSOR);
		Object loadedBefore = device.tValueByName("loaded");
		Object downloadBefore = device.tValueByName("lastDownload");
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		MockDevice mock = new MockDevice().scriptLoadControl(
			(object, control) -> object == 2 && control[0] == LoadControls.EVENT_LOAD ? LoadControls.STATE_ERROR : null);
		DownloadExecutor.Result result = DownloadService.download(device, plan, mock,
			new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
		assertFalse(result.success());
		Operation.LoadControl failed = (Operation.LoadControl) result.failed();
		assertEquals("#9 Load LSM 2", failed.step());
		assertEquals(plan.getOperations().indexOf(failed), result.done());
		assertEquals("Load state machine 2 reports the state \"Error\" after the load control \"Start loading\", expected \"Loading\".",
			result.failure());
		assertEquals("writeLoadControl 2 01000000000000000000", mock.calls().get(mock.calls().size() - 1));
		assertEquals(loadedBefore, device.tValueByName("loaded"));
		assertEquals(downloadBefore, device.tValueByName("lastDownload"));
	}

	/**
	 * A device of another hardware type than the actuator program expects: the download stops before
	 * writing anything.
	 */
	private void checkHardwareMismatch() throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device(ACTUATOR), DownloadPlan.Kind.FULL);
		MockDevice mock = actuatorDevice().setProperty(0, 78, "000a0000003d");
		DownloadExecutor.Result result = new DownloadExecutor(ms -> { /* No wait. */ }).execute(plan, mock,
			(number, count, operation) -> { /* Ignore. */ });
		assertFalse(result.success());
		assertTrue(result.failed() instanceof Operation.CompareProperty);
		assertEquals("Object 0 property 78 is 00 0A 00 00 00 3D, expected 00 00 00 00 03 0D 00 00 00 00.", result.failure());
		assertEquals(0, mock.writes());

		MockDevice wrongMask = actuatorDevice().setDescriptor("0701");
		result = new DownloadExecutor(ms -> { /* No wait. */ }).execute(plan, wrongMask, (n, c, o) -> { /* Ignore. */ });
		assertEquals("The device has the mask version 07 01, the program needs 0705.", result.failure());
		assertEquals(0, wrongMask.writes());
	}

	/**
	 * The comparison of the actuator wired as the read-back shows with its read-back: the tables
	 * identical, the parameter segments different only at parameters (the device holds another
	 * project's configuration).
	 */
	private void checkCompareActuator() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject device = device(ACTUATOR);
		try (Transaction tx = kb.beginTransaction()) {
			TestDownloadImage.rewire(device, readBack("DI-2-1.3.5-4000.hex"), readBack("DI-2-1.3.5-4201.hex"));
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			MockDevice mock = actuatorDevice();
			byte[] memory = deviceMemory("DI-2-1.3.5");
			mock.setMemory(0, memory);
			DeviceComparison.Result result = DeviceComparison.compare(DeviceConfiguration.program(device), plan.getImage(),
				plan.getUnloadedBlocks(), mock, (number, count, block) -> { /* Ignore. */ });
			assertEquals(0, mock.writes());
			assertEquals("07 05", result.descriptor());
			assertTrue(result.facts().toString(), result.facts().contains(
				"Program version: 00 83 00 0C 21, the program's: 00 83 00 0C 21"));
			assertTrue(result.facts().toString(), result.facts().contains("Load state machine 3: Loaded"));

			BitSet parameters = TestDownloadImage.parameterBytes(DeviceConfiguration.program(device));
			int different = 0;
			for (DeviceComparison.BlockResult block : result.blocks()) {
				Logger.info("Comparison " + block.name() + ": " + (block.identical() ? "identical" : block.ranges()),
					TestDownloadPlan.class);
				assertNull(block.failure());
				if (block.address() == 0x4000 || block.address() == 0x4201) {
					assertTrue(block.name() + ": " + block.ranges(), block.identical());
				}
				for (int address : block.different()) {
					assertTrue(String.format("%04X differs but is no parameter.", address), parameters.get(address));
				}
				different += block.different().size();
			}
			assertTrue("The device holds another project's parameters.", different > 0);
		}
	}

	/**
	 * A device that computes its download stamps itself and refuses their write (the Gira
	 * Tastsensor 3 of the test system, mask 7.5): the download goes on where the device already holds
	 * the stamp, and fails where it holds another value.
	 */
	private void checkRefusedStamps() throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device(SENSOR), DownloadPlan.Kind.FULL);
		List<Operation.WriteProperty> stamps = plan.getOperations().stream()
			.filter(o -> o instanceof Operation.WriteProperty w && w.propertyId() == 27)
			.map(Operation.WriteProperty.class::cast).toList();
		assertFalse(stamps.isEmpty());

		MockDevice computing = sensorDevice(plan).refuseWrites(27);
		for (Operation.WriteProperty stamp : stamps) {
			computing.setPropertyElement(stamp.objectIndex(), 27, stamp.start(), stamp.data());
		}
		DownloadExecutor.Result result =
			new DownloadExecutor(ms -> { /* No wait. */ }).execute(plan, computing, (n, c, o) -> { /* Ignore. */ });
		assertTrue(result.failure(), result.success());

		MockDevice other = sensorDevice(plan).refuseWrites(27);
		Operation.WriteProperty first = stamps.get(0);
		other.setPropertyElement(first.objectIndex(), 27, first.start(), new byte[first.data().length]);
		result = new DownloadExecutor(ms -> { /* No wait. */ }).execute(plan, other, (n, c, o) -> { /* Ignore. */ });
		assertFalse(result.success());
		assertSame(first, result.failed());
	}

	/**
	 * A device of mask 7.5 holding what the plan compares.
	 */
	private MockDevice sensorDevice(DownloadPlan plan) {
		MockDevice result = new MockDevice().setState(1, 1).setState(2, 1).setState(3, 1);
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.CompareProperty compare) {
				result.setProperty(compare.objectIndex(), compare.propertyId(), hex(compare.expected()).replace(" ", ""));
			}
		}
		return result;
	}

	private MockDevice actuatorDevice() {
		return new MockDevice()
			.setProperty(0, 78, "00000000030d")
			.setProperty(3, 13, "0083000c21")
			.setState(1, 1).setState(2, 1).setState(3, 1);
	}

	/**
	 * The memory of a device as its read-back fixtures give it.
	 */
	private static byte[] deviceMemory(String device) throws IOException {
		byte[] result = new byte[0x10000];
		File[] files = READBACK.listFiles((dir, name) -> name.startsWith(device + "-") && name.matches(".*-[0-9A-F]{4}\\.hex"));
		for (File file : files) {
			byte[][] readBack = readBack(file.getName());
			int start = (readBack[0][0] & 0xFF) << 8 | readBack[0][1] & 0xFF;
			System.arraycopy(readBack[1], 0, result, start, readBack[1].length);
		}
		return result;
	}

	/**
	 * The stamps ETS wrote into the Tastsensor: object 1, object 2, the six of object 3.
	 */
	private static List<String> stamps() throws IOException {
		List<String> result = new ArrayList<>();
		for (String line : Files.readAllLines(new File(READBACK, "DI-3-1.3.50-stamps.txt").toPath(), StandardCharsets.UTF_8)) {
			if (!line.startsWith("#")) {
				result.addAll(Arrays.asList(line.substring(line.indexOf(':') + 1).trim().split(" ")));
			}
		}
		return result;
	}

	/**
	 * The kinds of the operations of a plan, the load controls with their load state machine,
	 * repetitions counted, a run of memory writes as one.
	 */
	private static String signature(DownloadPlan plan) {
		List<String> tokens = new ArrayList<>();
		for (Operation operation : plan.getOperations()) {
			String token;
			if (operation instanceof Operation.LoadControl control) {
				token = controlName(control.control()) + control.stateMachine();
			} else if (operation instanceof Operation.WriteMemory) {
				token = "Write";
				if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals(token)) {
					continue;
				}
			} else {
				token = operation.getClass().getSimpleName();
			}
			tokens.add(token);
		}
		return collapse(tokens);
	}

	private static String controlName(byte[] control) {
		switch (control[0]) {
			case LoadControls.EVENT_UNLOAD:
				return "Unload";
			case LoadControls.EVENT_LOAD:
				return "Load";
			case LoadControls.EVENT_LOAD_COMPLETED:
				return "Completed";
			default:
				return control[1] == LoadControls.SEGMENT_TASK ? "Task"
					: control[1] == LoadControls.SEGMENT_TASK_CONTROL_1 ? "TaskControl" : "Segment";
		}
	}

	/**
	 * The calls of a mock: the name and the object of property accesses and load controls, a run of
	 * verified memory writes as one.
	 */
	private static String callSignature(List<String> calls) {
		List<String> tokens = new ArrayList<>();
		for (String call : calls) {
			String[] parts = call.split(" ");
			String token;
			switch (parts[0]) {
				case "writeMemory":
				case "readMemory":
					token = "(writeMemory readMemory)*";
					if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals(token)) {
						continue;
					}
					break;
				case "writeLoadControl":
				case "readProperty":
				case "writeProperty":
					token = parts[0] + parts[1];
					break;
				default:
					token = parts[0];
			}
			tokens.add(token);
		}
		return collapse(tokens);
	}

	private static String collapse(List<String> tokens) {
		List<String> result = new ArrayList<>();
		int n = 0;
		while (n < tokens.size()) {
			int count = 1;
			while (n + count < tokens.size() && tokens.get(n + count).equals(tokens.get(n))) {
				count++;
			}
			result.add(tokens.get(n) + (count > 1 ? "*" + count : ""));
			n += count;
		}
		return String.join(" ", result);
	}

	private static List<String> loadControls(DownloadPlan plan) {
		return plan.getOperations().stream()
			.filter(Operation.LoadControl.class::isInstance).map(Operation.LoadControl.class::cast)
			.map(c -> c.objectIndex() + " " + hex(c.control()))
			.collect(Collectors.toList());
	}

	private static List<Operation.WriteMemory> writes(DownloadPlan plan) {
		return plan.getOperations().stream()
			.filter(Operation.WriteMemory.class::isInstance).map(Operation.WriteMemory.class::cast).toList();
	}

	private static BitSet written(DownloadPlan plan) {
		BitSet result = new BitSet();
		for (Operation.WriteMemory write : writes(plan)) {
			result.set(write.address(), write.address() + write.data().length);
		}
		return result;
	}

	private static Block blockAt(DownloadImage image, int address) {
		return image.getBlocks().stream().filter(b -> b.contains(address)).findFirst().orElse(null);
	}

	private static String hex(byte[] data) {
		return HexFormat.of().formatHex(data);
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDownloadPlan.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
