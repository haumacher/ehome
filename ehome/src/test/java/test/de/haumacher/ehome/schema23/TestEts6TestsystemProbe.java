/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.schema23;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.Logger;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
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

import net.lingala.zip4j.io.inputstream.ZipInputStream;

/**
 * The ETS 6 export of the user's test system (<code>samples/KNX-Testsystem-ETS6.knxproj</code>,
 * schema 23, all three programs in new editions) against the ETS 4 export of the same installation
 * (<code>samples/KNX-Testsystem.knxproj</code>), and the password-protected ETS 6 export
 * (<code>samples/KNX-Testsystem-ETS6-protected.knxproj</code>, password <code>test1234</code>). ETS 6's
 * own full download of the actuator 1.3.5 into the virtual device is compared with E-Home's plan in
 * the development tooling (<code>TestEts6Download</code> of the module <code>ehome-virtual</code>).
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
	 * The protected export: refused without and with a wrong password, imported with the right one
	 * as the unprotected export.
	 */
	public void testProtected() throws Exception {
		byte[] file = Files.readAllBytes(Path.of(PROTECTED));
		List<TLObject> before = instances("ehome.project:Project");
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
			assertEquals(List.of(), SharedData.newProjects(before));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}

		// The password of the archive is the one ETS 6 derives, not the project password itself.
		byte[] archive = Ets6Files.entries(file).get("P-0563.zip");
		assertTrue(opens(archive, ProtectedProject.derivedPassword(PASSWORD)));
		assertFalse(opens(archive, PASSWORD));
		assertEquals(Ets6Files.entries(Files.readAllBytes(Path.of(ETS6))).get("P-0563/0.xml").length,
			ProtectedProject.read("P-0563.zip", archive, 23, PASSWORD).get("0.xml").length);

		Map<Integer, Plan> plain = ets6Plans();
		Map<Integer, Plan> decrypted = protectedPlans();
		assertEquals(new Counts(1, 3, 54), _protectedCounts);
		assertEquals(normalized(plain.get(5).operations()), normalized(decrypted.get(5).operations()));
	}

	/**
	 * The mask versions by the version of the master data (haumacher/ehome #34), with the imports the
	 * other tests make anyway: the ETS 6 export (master data version 198) imported onto the ETS 4
	 * export (version 92, see {@link #ets6Plans()}) updates MV-091A and the other mask versions its
	 * master data define differently, so its plans are those of the (protected) ETS 6 export imported
	 * with the mask versions of its own master data only. The ETS 4 export imported onto that keeps
	 * them, warning about MV-091A, and the plans of the ETS 6 project stay.
	 */
	public void testImportOrder() throws Exception {
		Map<Integer, Plan> empty = protectedPlans();
		assertEquals(summary(empty), summary(ets6Plans()));
		assertEquals(summary(empty), summary(_ets6BeforeEts4));
		// The mask versions the two master data define differently, each reported once.
		List<ResKey> expected = new ArrayList<>();
		for (String mask : List.of("MV-0012", "MV-0705", "MV-07B0", "MV-0912", "MV-091A", "MV-17B0", "MV-1900",
			"MV-5705", "MV-57B0")) {
			expected.add(I18NConstants.WARN_MASTER_DATA_DIFFER__ID_VERSION_KNOWN.fill(mask, "92", "198"));
		}
		List<ResKey> warnings = new ArrayList<>();
		for (BufferingI18NLog.Entry entry : _ets4AfterEts6Messages) {
			Object[] arguments = entry.getMessage().arguments();
			Object detail = arguments.length == 0 ? null : arguments[arguments.length - 1];
			if (String.valueOf(detail).contains("_MASTER_DATA_")) {
				assertEquals(Level.WARN, entry.getLevel());
				warnings.add((ResKey) detail);
			}
		}
		assertEquals(expected, warnings);
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

	/** The numbers of projects, devices and group addresses the last import created. */
	record Counts(int projects, int devices, int groupAddresses) {
		// Pure data.
	}

	private Counts _lastCounts;

	/**
	 * The plans from the two unprotected exports, computed once for all tests (a plan is pure data,
	 * it outlives the deleted projects).
	 */
	private static Map<Integer, Plan> _ets4Plans, _ets6Plans;

	/**
	 * The plans of the protected ETS 6 export imported with the mask versions of its own master data,
	 * and its counts.
	 */
	private static Map<Integer, Plan> _protectedPlans;

	private static Counts _protectedCounts;

	/**
	 * The plans of the ETS 6 project after the ETS 4 export was imported onto it, with the messages of
	 * that import.
	 */
	private static Map<Integer, Plan> _ets6BeforeEts4;

	private static List<BufferingI18NLog.Entry> _ets4AfterEts6Messages;

	private Map<Integer, Plan> ets4Plans() throws Exception {
		if (_ets4Plans == null) {
			_ets4Plans = plans(Files.readAllBytes(Path.of(ETS4)), null, first -> {
				// The ETS 6 export onto the ETS 4 export, saving a reset of the mask versions.
				Importer importer = Ets6Files.importFile(Files.readAllBytes(Path.of(ETS6)), null);
				assertEquals(List.of(), Ets6Files.errors(importer.getMessages()));
				_ets6Plans = plans(devices(importer.getCreated("ehome.project:Project")));
			});
		}
		return _ets4Plans;
	}

	/**
	 * The plans of the ETS 6 export imported onto the ETS 4 export.
	 */
	private Map<Integer, Plan> ets6Plans() throws Exception {
		ets4Plans();
		return _ets6Plans;
	}

	private Map<Integer, Plan> protectedPlans() throws Exception {
		if (_protectedPlans == null) {
			_protectedPlans = plans(Files.readAllBytes(Path.of(PROTECTED)), PASSWORD, first -> {
				_protectedCounts = _lastCounts;
				Importer importer = Ets6Files.importFile(Files.readAllBytes(Path.of(ETS4)), null);
				assertEquals(List.of(), Ets6Files.errors(importer.getMessages()));
				_ets4AfterEts6Messages = importer.getMessages();
				_ets6BeforeEts4 = plans(devices(first.getCreated("ehome.project:Project")));
			});
		}
		return _protectedPlans;
	}

	/**
	 * What is done in the database of an import before its projects are deleted again.
	 */
	interface FollowUp {
		/**
		 * @param first
		 *        The import before.
		 */
		void run(Importer first) throws Exception;
	}

	/**
	 * The problems and the normalized operations of the plans by device.
	 */
	private static Map<Integer, List<String>> summary(Map<Integer, Plan> plans) {
		Map<Integer, List<String>> result = new TreeMap<>();
		plans.forEach((device, plan) -> {
			List<String> lines = new ArrayList<>(plan.problems());
			lines.addAll(normalized(plan.operations()));
			result.put(device, lines);
		});
		return result;
	}

	/**
	 * A plan of a device: its problems, its operations, and the configuration bytes the image gives
	 * the objects not in effect, by their address.
	 */
	record Plan(List<String> problems, List<Operation> operations, Map<Integer, Integer> inactiveConfigs) {
		// Pure data.
	}

	/**
	 * Imports the given project file with the mask versions of its own master data (as into an empty
	 * database, see {@link SharedData#resetMaskVersions(byte[])}) and plans the full download of each
	 * device of the project, by its address on the line; then runs the given follow-up (if any) on
	 * that database. The projects imported are deleted again; the shared data stays.
	 */
	private Map<Integer, Plan> plans(byte[] file, String password, FollowUp followUp) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		SharedData.resetMaskVersions(file);
		try {
			Importer importer = Ets6Files.importFile(file, password);
			assertEquals(List.of(), Ets6Files.errors(importer.getMessages()));
			List<TLObject> created = SharedData.newProjects(before);
			List<TLObject> devices = devices(created);
			_lastCounts = new Counts(created.size(), devices.size(),
				SharedData.within("ehome.logical:GroupAddress", created).size());
			Map<Integer, Plan> result = plans(devices);
			if (followUp != null) {
				followUp.run(importer);
			}
			return result;
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private static Map<Integer, Plan> plans(List<TLObject> devices) throws IOException {
		Map<Integer, Plan> result = new LinkedHashMap<>();
		for (TLObject device : devices) {
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			List<String> problems = plan.getProblems().stream().map(p -> normalized(String.valueOf(p))).toList();
			result.put((Integer) device.tValueByName("address"),
				new Plan(problems, plan.getOperations(), inactiveConfigs(device, written(plan.getOperations()))));
		}
		return result;
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

	private static List<TLObject> devices(List<TLObject> projects) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject project : projects) {
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
