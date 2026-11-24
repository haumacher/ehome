/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadJob;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;
import de.haumacher.ehome.programming.download.Operation;

/**
 * Test of the checks before a download writes (haumacher/ehome #224), on the actuator 1.3.5 of
 * <code>TestPartialSteps.knxproj</code>, whose download "Parameters only" leaves its code segment
 * (4700) to the device.
 *
 * <ul>
 * <li>Every download compares the manufacturer of the device with the program's right after the mask,
 * before anything is written; a program switching that off (ETS:
 * <code>LegacyDoNotCheckManufacturerId</code>) does not, and an import of a program file switching it
 * off completes a program imported before.</li>
 * <li>"Parameters only" reads the code it leaves to the device. A device holding the program version
 * but other code (another build of the application), or another program version, gets the complete
 * download instead, on the same connection, with the reason in the report; a device holding the
 * project's application gets the parameters only.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestApplicationCheck extends BasicTestCase {

	/** The actuator 1.3.5. */
	private static final String ACTUATOR = "P-0563-0_DI-3";

	/** The code segment of the actuator's program. */
	private static final int CODE = 0x4700;

	private static final String FIXTURE = "src/test/fixtures/TestPartialSteps.knxproj";

	public void testChecks() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		TLObject project = created(before);
		try {
			TLObject device = TestParametersDownload.device(project, ACTUATOR);
			checkPlans(device);
			checkSameApplication(device);
			checkOtherBuild(device);
			checkOtherVersion(device);
			checkOtherManufacturer(device);
			checkNoManufacturerCheck(device);
			checkCompletion();
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	/**
	 * The manufacturer compared right after the mask in both plans; the code compared before the first
	 * write of "Parameters only" alone.
	 */
	private static void checkPlans(TLObject device) {
		for (DownloadPlan.Kind kind : List.of(DownloadPlan.Kind.FULL, DownloadPlan.Kind.PARAMETERS)) {
			DownloadPlan plan = DownloadPlanner.plan(device, kind);
			assertEquals(plan.dump(), List.of(), plan.getProblems());
			List<Operation> operations = plan.getOperations();
			int mask = indexOf(operations, Operation.CheckMask.class);
			Operation.CheckManufacturer check = (Operation.CheckManufacturer) operations.get(mask + 1);
			assertEquals(0, check.objectIndex());
			assertEquals(12, check.propertyId());
			assertEquals(0x0083, check.manufacturer());
			assertFalse(check.application());
			List<Operation.CheckApplicationMemory> code = operations.stream()
				.filter(Operation.CheckApplicationMemory.class::isInstance).map(Operation.CheckApplicationMemory.class::cast)
				.toList();
			if (kind == DownloadPlan.Kind.FULL) {
				assertEquals(List.of(), code);
				continue;
			}
			Block block = codeBlock(plan);
			assertEquals(1, code.size());
			assertEquals(CODE, code.get(0).address());
			assertEquals(hex(block.data()), hex(code.get(0).expected()));
			int firstWrite = -1;
			for (int n = 0; n < operations.size() && firstWrite < 0; n++) {
				if (operations.get(n).writes()) {
					firstWrite = n;
				}
			}
			assertTrue(plan.dump(), operations.indexOf(code.get(0)) < firstWrite);
		}
	}

	/**
	 * The device holds the project's application: the parameters only, the code not written.
	 */
	private static void checkSameApplication(TLObject device) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
		MockDevice mock = loaded(device, plan);
		DownloadService.Outcome outcome = perform(device, plan, mock);
		assertTrue(outcome.result().failure(), outcome.result().success());
		assertNull(outcome.completeInstead());
		assertSame(plan, outcome.plan());
		assertTrue(mock.calls().toString(), mock.calls().stream().noneMatch(c -> c.startsWith("writeMemory 47")));
	}

	/**
	 * The device holds the program version, but other code (another build of the same application):
	 * the complete download, which writes the code.
	 */
	private static void checkOtherBuild(TLObject device) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
		MockDevice mock = loaded(device, plan);
		byte[] code = codeBlock(plan).data();
		int differing = 5;
		mock.setMemory(CODE + differing, new byte[] { (byte) (code[differing] ^ 0x5A) });
		DownloadService.Outcome outcome = perform(device, plan, mock);
		assertTrue(outcome.result().failure(), outcome.result().success());
		assertEquals(DownloadPlan.Kind.FULL, outcome.plan().getKind());
		assertNotNull(outcome.completeInstead());
		String reason = DownloadPlan.english(outcome.completeInstead());
		assertTrue(reason, reason.startsWith(String.format("The device holds %02X ", (code[differing] ^ 0x5A) & 0xFF)));
		assertTrue(reason, reason.contains(String.format(" at %04X, the application of the program has %02X ", CODE + differing,
			code[differing])));
		assertEquals(hex(code), hex(Arrays.copyOfRange(mock.memory(), CODE, CODE + code.length)));
		// Nothing written before the complete download started.
		List<String> calls = mock.calls();
		int firstWrite = indexOfPrefix(calls, "write");
		int secondDescriptor = calls.subList(1, calls.size()).indexOf("readDescriptor") + 1;
		assertTrue(calls.toString(), secondDescriptor > 0 && firstWrite > secondDescriptor);
		// The report names the reason.
		Map<String, Object> report = DownloadJob.report(outcome, "test");
		assertTrue(report.values().toString(), report.values().toString().contains("the complete download was performed"));
	}

	/**
	 * The device holds another version of the program: the complete download.
	 */
	private static void checkOtherVersion(TLObject device) throws Exception {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
		byte[] other = DownloadPlanner.applicationId(DeviceConfiguration.program(device));
		other[4]++;
		// Also the element the comparisons of the procedure read.
		MockDevice mock = loaded(device, plan).setProperty(3, 13, hex(other)).setPropertyElement(3, 13, 1, other);
		DownloadService.Outcome outcome = perform(device, plan, mock);
		assertEquals(DownloadPlan.Kind.FULL, outcome.plan().getKind());
		assertNotNull(outcome.completeInstead());
		assertTrue(DownloadPlan.english(outcome.completeInstead()),
			DownloadPlan.english(outcome.completeInstead()).contains(hex(other).substring(8).toUpperCase()));
		// The complete download ran to its last check: the device of the test does not take the program
		// version from the task segment (a real one does), so it still answers the other version there.
		assertTrue(outcome.result().failed() instanceof Operation.CompareProperty compare
			&& compare.objectIndex() == 3 && compare.propertyId() == 13);
		assertTrue(mock.writes() > 0);
	}

	/**
	 * A device of another manufacturer: refused before anything is written, both downloads.
	 */
	private static void checkOtherManufacturer(TLObject device) throws Exception {
		for (DownloadPlan.Kind kind : List.of(DownloadPlan.Kind.FULL, DownloadPlan.Kind.PARAMETERS)) {
			DownloadPlan plan = DownloadPlanner.plan(device, kind);
			MockDevice mock = loaded(device, plan).setProperty(0, 12, "0002");
			DownloadService.Outcome outcome = perform(device, plan, mock);
			assertFalse(outcome.result().success());
			assertNull(outcome.completeInstead());
			assertTrue(outcome.result().failed() instanceof Operation.CheckManufacturer);
			assertEquals("The device is of the manufacturer 0002, the program of the manufacturer 0083: "
				+ "the device is not the one the program is for.", outcome.result().failure());
			assertEquals(0, mock.writes());
		}
	}

	/**
	 * A program whose product data switch the check of the manufacturer off.
	 */
	private static void checkNoManufacturerCheck(TLObject device) {
		Program program = DeviceConfiguration.program(device);
		setNoManufacturerCheck(program, true);
		try {
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertEquals(plan.dump(), List.of(), plan.getProblems());
			assertTrue(plan.getOperations().stream().noneMatch(Operation.CheckManufacturer.class::isInstance));
		} finally {
			setNoManufacturerCheck(program, false);
		}
	}

	/**
	 * An import of the program file switching the check off completes the known program.
	 */
	private static void checkCompletion() throws Exception {
		File variant = File.createTempFile("TestApplicationCheck", ".knxproj");
		List<TLObject> before = instances("ehome.project:Project");
		Program program = null;
		try {
			writeVariant(new File(FIXTURE), variant);
			importSample(variant.getPath());
			TLObject project = created(before);
			try {
				program = DeviceConfiguration.program(TestParametersDownload.device(project, ACTUATOR));
				assertTrue(program.getNoManufacturerCheck());
			} finally {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					project.tDelete();
					tx.commit();
				}
			}
		} finally {
			variant.delete();
			if (program != null) {
				setNoManufacturerCheck(program, false);
			}
		}
	}

	/**
	 * The fixture with the program of the actuator switching the check of the manufacturer off.
	 */
	private static void writeVariant(File fixture, File target) throws IOException {
		byte[] from = "LegacyDoNotCheckManufacturerId=\"0\"".getBytes(StandardCharsets.UTF_8);
		byte[] to = "LegacyDoNotCheckManufacturerId=\"1\"".getBytes(StandardCharsets.UTF_8);
		try (ZipInputStream in = new ZipInputStream(new FileInputStream(fixture));
				ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				byte[] content = readAll(in);
				if (entry.getName().startsWith("M-0083/M-0083_A-FF73")) {
					content = replace(content, from, to);
				}
				out.putNextEntry(new ZipEntry(entry.getName()));
				out.write(content);
				out.closeEntry();
			}
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		in.transferTo(buffer);
		return buffer.toByteArray();
	}

	private static byte[] replace(byte[] content, byte[] from, byte[] to) {
		String text = new String(content, StandardCharsets.ISO_8859_1);
		String replaced = text.replace(new String(from, StandardCharsets.ISO_8859_1), new String(to, StandardCharsets.ISO_8859_1));
		assertFalse("The fixture has the option.", text.equals(replaced));
		return replaced.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static void setNoManufacturerCheck(Program program, boolean value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			program.setNoManufacturerCheck(value);
			tx.commit();
		}
	}

	private static DownloadService.Outcome perform(TLObject device, DownloadPlan plan, MockDevice mock)
			throws InterruptedException {
		return DownloadService.perform(device, plan, mock, new DownloadExecutor(ms -> {
			// No wait.
		}), (number, count, operation) -> {
			// No progress.
		});
	}

	/**
	 * A device of mask 7.5 of the program's manufacturer holding the project's application: the
	 * program version, every load state machine loaded, the code of the image, and the values the
	 * procedure compares.
	 */
	private static MockDevice loaded(TLObject device, DownloadPlan plan) {
		Program program = DeviceConfiguration.program(device);
		MockDevice result = new MockDevice().setState(1, 1).setState(2, 1).setState(3, 1).manufacturerOf(plan)
			.setProperty(3, 13, hex(DownloadPlanner.applicationId(program)));
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.CompareProperty compare) {
				result.setPropertyElement(compare.objectIndex(), compare.propertyId(), compare.start(), compare.expected());
			}
		}
		Block code = codeBlock(DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS));
		result.setMemory(code.address(), code.data());
		return result;
	}

	private static Block codeBlock(DownloadPlan plan) {
		for (Block block : plan.getImage().getBlocks()) {
			if (block.address() == CODE) {
				return block;
			}
		}
		throw new AssertionError("No code block in " + plan.dump());
	}

	private static int indexOf(List<Operation> operations, Class<?> type) {
		for (int n = 0; n < operations.size(); n++) {
			if (type.isInstance(operations.get(n))) {
				return n;
			}
		}
		throw new AssertionError("No " + type.getSimpleName());
	}

	private static int indexOfPrefix(List<String> calls, String prefix) {
		for (int n = 0; n < calls.size(); n++) {
			if (calls.get(n).startsWith(prefix)) {
				return n;
			}
		}
		return -1;
	}

	private static String hex(byte[] data) {
		StringBuilder result = new StringBuilder();
		for (byte b : data) {
			result.append(String.format("%02x", b & 0xFF));
		}
		return result.toString();
	}

	private static TLObject created(List<TLObject> before) {
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		return projects.get(0);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestApplicationCheck.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
