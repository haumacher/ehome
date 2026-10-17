/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.schema23;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.virtual.VirtualTesting;

import com.top_logic.basic.Logger;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.importer.PasswordRequiredException;
import de.haumacher.ehome.importer.ProtectedProject;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.TableLocation;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.virtual.trace.TraceOperations;

import net.lingala.zip4j.io.inputstream.ZipInputStream;

/**
 * The ETS 6 export of the user's test system (<code>samples/KNX-Testsystem-ETS6.knxproj</code>,
 * schema 23, all three programs in new editions) against the ETS 4 export of the same installation
 * (<code>samples/KNX-Testsystem.knxproj</code>) and against ETS 6's own full download of the
 * actuator 1.3.5 into the virtual device (<code>src/test/fixtures/ets-traces/ets6-download-1.3.5.txt</code>),
 * and the password-protected ETS 6 export (<code>samples/KNX-Testsystem-ETS6-protected.knxproj</code>,
 * password <code>test1234</code>).
 *
 * <p>
 * The only download-relevant change of the new program editions is the ETS 6 option
 * <code>Options@DownloadInvisibleParameters="Background"</code>: with it, a communication object not
 * in effect keeps the configuration byte of the static data (without the communication flag), where
 * the ETS 4 edition's object gets the byte computed from its flags.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestEts6TestsystemProbe extends BasicTestCase {

	private static final String ETS4 = "samples/KNX-Testsystem.knxproj";

	private static final String ETS6 = "samples/KNX-Testsystem-ETS6.knxproj";

	private static final String PROTECTED = "samples/KNX-Testsystem-ETS6-protected.knxproj";

	private static final String PASSWORD = "test1234";

	private static final String TRACE = "src/test/fixtures/ets-traces/ets6-download-1.3.5.txt";

	/** The configuration bits of the communication flag in an object table entry. */
	private static final int COMMUNICATION = 0x04;

	/**
	 * Header and entry size of the object table of mask 0705 and the offset of the configuration
	 * byte in an entry (<code>GroupObjectTable_M112</code>).
	 */
	private static final int HEADER = 3, DESCRIPTOR = 4, CONFIG = 2;

	/**
	 * The plans of the ETS 6 export equal those of the ETS 4 export operation by operation, but for
	 * the configuration bytes of the objects not in effect; the router's refusals are the same.
	 */
	public void testAsEts4() throws Exception {
		Map<Integer, Plan> ets4 = ets4Plans();
		Map<Integer, Plan> ets6 = ets6Plans();
		assertEquals(Set.of(1, 5, 50), ets4.keySet());
		assertEquals(ets4.keySet(), ets6.keySet());

		// The IP router 1.3.1 is refused from both exports. Its program uses the procedure of its mask
		// MV-091A, whose master data differ: ETS 4 marks the configuration with the property based
		// procedure (merge points, load state machines by object type) as the legacy one, ETS 6 the
		// one writing the coupler's memory, so the procedure in effect and with it some refusals
		// differ. Both share the refusal of the coupler's filter table.
		for (Map<Integer, Plan> plans : List.of(ets4, ets6)) {
			List<String> problems = plans.get(1).problems();
			assertTrue(problems.toString(), problems.stream().anyMatch(p -> p.contains("ClearLCFilterTable")));
			assertTrue(problems.toString(), problems.stream().anyMatch(p -> p.contains("LcFilter")));
		}
		assertTrue(ets6.get(1).problems().stream().anyMatch(p -> p.contains("ERROR_MERGE__STEP_ID")));
		assertTrue(ets4.get(1).problems().stream().noneMatch(p -> p.contains("ERROR_MERGE__STEP_ID")));
		Logger.info("Router 1.3.1, ETS 4: " + ets4.get(1).problems(), TestEts6TestsystemProbe.class);
		Logger.info("Router 1.3.1, ETS 6: " + ets6.get(1).problems(), TestEts6TestsystemProbe.class);

		for (int device : List.of(5, 50)) {
			Plan old = ets4.get(device);
			Plan now = ets6.get(device);
			assertEquals(List.of(), old.problems());
			assertEquals(List.of(), now.problems());
			assertEquals(old.operations().size(), now.operations().size());
			Set<Integer> differing = new TreeSet<>();
			for (int n = 0; n < old.operations().size(); n++) {
				Operation before = old.operations().get(n);
				Operation after = now.operations().get(n);
				if (normalized(before).equals(normalized(after))) {
					continue;
				}
				assertTrue(normalized(after), before instanceof Operation.WriteMemory
					&& after instanceof Operation.WriteMemory);
				Operation.WriteMemory w4 = (Operation.WriteMemory) before;
				Operation.WriteMemory w6 = (Operation.WriteMemory) after;
				assertEquals(w4.address(), w6.address());
				assertEquals(w4.data().length, w6.data().length);
				for (int i = 0; i < w4.data().length; i++) {
					if (w4.data()[i] != w6.data()[i]) {
						differing.add(w4.address() + i);
					}
				}
			}
			// Every differing byte is the configuration of an object not in effect: computed from its
			// flags by the ETS 4 edition, the static one without communication by the ETS 6 edition.
			assertEquals(differing, now.inactiveConfigs().keySet().stream()
				.filter(a -> !now.inactiveConfigs().get(a).equals(old.inactiveConfigs().get(a)))
				.collect(Collectors.toCollection(TreeSet::new)));
			Map<Integer, Integer> written = written(now.operations());
			for (int at : differing) {
				assertEquals(String.format("%04X", at), now.inactiveConfigs().get(at), written.get(at));
			}
			Logger.info("1.3." + device + ": " + differing.size() + " configuration bytes of objects not in effect differ.",
				TestEts6TestsystemProbe.class);
			// The actuator: 22 objects not in effect whose static configuration differs from their flags;
			// the Tastsensor's static configuration of such objects equals the one computed.
			assertEquals(device == 5 ? 22 : 0, differing.size());
		}
	}

	/**
	 * The memory E-Home writes into the actuator 1.3.5 from the ETS 6 export is the memory ETS 6
	 * writes, byte for byte.
	 */
	public void testAsEts6() throws Exception {
		Map<Integer, Plan> plans = ets6Plans();
		List<Operation> ets = TraceOperations.operations(VirtualTesting.read(new File(TRACE)).entries());
		Map<Integer, Integer> theirs = written(ets);
		Map<Integer, Integer> mine = written(plans.get(5).operations());
		assertEquals(626, theirs.size());
		assertEquals(theirs, mine);
	}

	/**
	 * The protected export: refused without and with a wrong password, imported with the right one
	 * as the unprotected export.
	 */
	public void testProtected() throws Exception {
		byte[] file = Files.readAllBytes(Path.of(PROTECTED));
		Ets6Files.clearData();
		try {
			try {
				Ets6Files.importFile(file, null);
				fail("The protected project must be refused without its password.");
			} catch (PasswordRequiredException ex) {
				assertEquals(I18NConstants.ERROR_PASSWORD_REQUIRED__ENTRY.fill("P-0563.zip"), ex.getErrorKey());
			}
			try {
				Ets6Files.importFile(file, "test12345");
				fail("A wrong password must be refused.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_WRONG_PASSWORD__ENTRY.fill("P-0563.zip"), ex.getErrorKey());
			}
			assertEquals(0, instances("ehome.project:Project").size());
		} finally {
			Ets6Files.clearData();
		}

		// The password of the archive is the one ETS 6 derives, not the project password itself.
		byte[] archive = Ets6Files.entries(file).get("P-0563.zip");
		assertTrue(opens(archive, ProtectedProject.derivedPassword(PASSWORD)));
		assertFalse(opens(archive, PASSWORD));
		assertEquals(Ets6Files.entries(Files.readAllBytes(Path.of(ETS6))).get("P-0563/0.xml").length,
			ProtectedProject.read("P-0563.zip", archive, 23, PASSWORD).get("0.xml").length);

		Map<Integer, Plan> plain = ets6Plans();
		Counts plainCounts = _ets6Counts;
		Map<Integer, Plan> decrypted = plans(file, PASSWORD);
		assertEquals(plainCounts, _lastCounts);
		assertEquals(new Counts(1, 3, 54), _lastCounts);
		assertEquals(normalized(plain.get(5).operations()), normalized(decrypted.get(5).operations()));
	}

	private static boolean opens(byte[] archive, String password) {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive), password.toCharArray())) {
			while (in.getNextEntry() != null) {
				in.readAllBytes();
			}
			return true;
		} catch (IOException ex) {
			return false;
		}
	}

	/** The numbers of projects, devices and group addresses of the last import. */
	record Counts(int projects, int devices, int groupAddresses) {
		// Pure data.
	}

	private Counts _lastCounts;

	/**
	 * The plans from the two unprotected exports, computed once for all tests (a plan is pure data,
	 * it outlives the emptied database).
	 */
	private static Map<Integer, Plan> _ets4Plans, _ets6Plans;

	private static Counts _ets6Counts;

	private Map<Integer, Plan> ets4Plans() throws Exception {
		if (_ets4Plans == null) {
			_ets4Plans = plans(Files.readAllBytes(Path.of(ETS4)), null);
		}
		return _ets4Plans;
	}

	private Map<Integer, Plan> ets6Plans() throws Exception {
		if (_ets6Plans == null) {
			_ets6Plans = plans(Files.readAllBytes(Path.of(ETS6)), null);
			_ets6Counts = _lastCounts;
		}
		return _ets6Plans;
	}

	/**
	 * A plan of a device: its problems, its operations, and the configuration bytes the image gives
	 * the objects not in effect, by their address.
	 */
	record Plan(List<String> problems, List<Operation> operations, Map<Integer, Integer> inactiveConfigs) {
		// Pure data.
	}

	/**
	 * Imports the given project file into the emptied database and plans the full download of each
	 * device, by its address on the line.
	 */
	private Map<Integer, Plan> plans(byte[] file, String password) throws Exception {
		Ets6Files.clearData();
		try {
			Importer importer = Ets6Files.importFile(file, password);
			assertEquals(List.of(), Ets6Files.errors(importer.getMessages()));
			List<TLObject> devices = devices();
			_lastCounts = new Counts(instances("ehome.project:Project").size(), devices.size(),
				instances("ehome.logical:GroupAddress").size());
			Map<Integer, Plan> result = new LinkedHashMap<>();
			for (TLObject device : devices) {
				DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
				List<String> problems = plan.getProblems().stream().map(p -> normalized(String.valueOf(p))).toList();
				result.put((Integer) device.tValueByName("address"),
					new Plan(problems, plan.getOperations(), inactiveConfigs(device, written(plan.getOperations()))));
			}
			return result;
		} finally {
			Ets6Files.clearData();
		}
	}

	/**
	 * The configuration bytes written for the objects of the device not in effect (no connector, not
	 * placed in the configuration in effect), by their address; for an object of a program with the
	 * option <code>DownloadInvisibleParameters</code>, the expected static byte without communication.
	 */
	private static Map<Integer, Integer> inactiveConfigs(TLObject device, Map<Integer, Integer> written)
			throws IOException {
		Map<Integer, Integer> result = new TreeMap<>();
		Program program = DeviceConfiguration.program(device);
		if (program == null) {
			return result;
		}
		TableLocation table = program.getObjectTable();
		if (table == null) {
			return result;
		}
		CodeSegment segment = (CodeSegment) table.getSegment();
		int base = segment.getAddress() + table.getOffset();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		segment.getData().deliverTo(buffer);
		byte[] data = buffer.toByteArray();
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Set<Port> inEffect = new HashSet<>(configuration.getActiveInterfaces().keySet());
		for (TLObject connector : configuration.getConnectors()) {
			if (connector.tValueByName("port") instanceof Interface placed) {
				inEffect.add(placed.getPort());
			}
		}
		boolean keepsStatic = program.getDownloadInvisibleParameters() != null;
		for (Port port : program.getPorts()) {
			if (inEffect.contains(port)) {
				continue;
			}
			int at = base + HEADER + DESCRIPTOR * port.getIndex() + CONFIG;
			int expected = keepsStatic ? (data[at - segment.getAddress()] & 0xFF) & ~COMMUNICATION
				: written.getOrDefault(at, -1);
			result.put(at, expected);
		}
		return result;
	}

	private static List<String> normalized(List<Operation> operations) {
		return operations.stream().map(TestEts6TestsystemProbe::normalized).toList();
	}

	private static String normalized(Operation operation) {
		return normalized(operation.describe());
	}

	/**
	 * The text with the IDs of programs replaced: the two exports hold different editions of the
	 * same programs.
	 */
	private static String normalized(String text) {
		return text.replaceAll("M-[0-9A-F]{4}_A-[0-9A-F]{4}-[0-9A-F]{2}-[0-9A-F]{4}(-O[0-9A-F]{4})?", "PROGRAM");
	}

	private static Map<Integer, Integer> written(List<Operation> operations) {
		Map<Integer, Integer> result = new TreeMap<>();
		for (Operation operation : operations) {
			if (operation instanceof Operation.WriteMemory write) {
				for (int n = 0; n < write.data().length; n++) {
					result.put(write.address() + n, write.data()[n] & 0xFF);
				}
			}
		}
		return result;
	}

	private static List<TLObject> devices() {
		List<TLObject> result = new ArrayList<>();
		for (TLObject project : instances("ehome.project:Project")) {
			for (Object area : (Collection<?>) project.tValueByName("areas")) {
				for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
					for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
						result.add((TLObject) device);
					}
				}
			}
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestEts6TestsystemProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
