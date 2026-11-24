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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import junit.framework.Test;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.importer.SharedProjectTestCase;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.MaskConfiguration;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.ReadOut;

/**
 * The configuration of a coupler mask in effect for a program, chosen by the program's programming
 * style (<code>Options@LineCoupler0912NewProgrammingStyle</code>, haumacher/ehome #229).
 *
 * <p>
 * The mask MV-091A of the IP router 1.3.1 of the test system describes itself twice, once per style.
 * The ETS 4 master data (<code>samples/KNX-Testsystem.knxproj</code>) mark the new style legacy, the
 * ETS 6 master data (<code>samples/KNX-Testsystem-ETS6.knxproj</code>) the old one. The router's
 * program is of the old style in both exports: whichever master data define the mask, its download
 * plan uses the old style's procedure (memory writes to the coupler, no merge points) and its
 * read-out reads no load state through properties. The same program of the new style (in a
 * transaction that is rolled back, and by an import of a copy of the export whose program says so,
 * completing the known program) gets the configuration with load state machines through properties.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCouplerProgrammingStyle extends SharedProjectTestCase {

	private static final String ETS4 = "samples/KNX-Testsystem.knxproj";

	private static final String ETS6 = "samples/KNX-Testsystem-ETS6.knxproj";

	/** The address of the IP router on its line. */
	private static final int ROUTER = 1;

	/** The memory of the coupler's configuration the old style's procedure writes first. */
	private static final int LC_CONFIG = 0x010E;

	private static final String OPTION = "LineCoupler0912NewProgrammingStyle";

	public void testStyles() throws Exception {
		List<TLObject> projects = List.of(sharedProject(ETS4), sharedProject(ETS6));
		// The mask versions of either master data, independent of the order the files came in.
		for (String masterData : List.of(ETS6, ETS4)) {
			SharedData.resetMaskVersions(Files.readAllBytes(Path.of(masterData)));
			for (TLObject project : projects) {
				TLObject router = device(project, ROUTER);
				String context = masterData + ", " + (project == projects.get(0) ? ETS4 : ETS6) + " project";
				checkOldStyle(context, router);
				checkNewStyle(context, router);
			}
		}
		checkCompletion();
	}

	/**
	 * The router's program of the old style: the configuration without load state machines through
	 * properties, the procedure writing the coupler's memory, no load state read through properties.
	 */
	private static void checkOldStyle(String context, TLObject router) throws Exception {
		Program program = DeviceConfiguration.program(router);
		assertEquals(context, 0x091A, program.getMask().getNumber());
		assertFalse(context, Boolean.TRUE.equals(program.getLineCouplerNewProgrammingStyleOrNull()));
		MaskConfiguration configuration = program.getMaskConfiguration();
		assertFalse(context, MaskVersion.propertyMapped(configuration));
		LoadProcedure all = configuration.getProcedures().get(0);
		assertEquals(context, "all", all.getScope().getName());
		assertTrue(context, all.getSteps().stream().anyMatch(step -> "WriteMem".equals(step.getKind().getName())
			&& step.getAddress() != null && step.getAddress().intValue() == LC_CONFIG));
		assertTrue(context, all.getSteps().stream().noneMatch(step -> "Merge".equals(step.getKind().getName())));
		assertEquals(context, configuration.getProcedures(), program.getMask().getProcedures(program));

		List<String> problems = problems(router);
		assertTrue(context + ": " + problems, problems.stream().noneMatch(p -> p.contains("ERROR_MERGE")));
		assertTrue(context + ": " + problems, problems.stream().anyMatch(p -> p.contains("LcFilter")));

		List<String> calls = readOut(router);
		assertTrue(context + ": " + calls, calls.stream().noneMatch(TestCouplerProgrammingStyle::readsLoadState));
	}

	/**
	 * The same program of the new style, in a transaction that is not committed: the configuration with
	 * load state machines through properties.
	 */
	private static void checkNewStyle(String context, TLObject router) throws Exception {
		Program program = DeviceConfiguration.program(router);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			program.setLineCouplerNewProgrammingStyle(true);
			checkPropertyBased(context, router);
		}
		assertFalse(context, Boolean.TRUE.equals(program.getLineCouplerNewProgrammingStyleOrNull()));
	}

	private static void checkPropertyBased(String context, TLObject router) throws Exception {
		Program program = DeviceConfiguration.program(router);
		MaskConfiguration configuration = program.getMaskConfiguration();
		assertTrue(context, MaskVersion.propertyMapped(configuration));
		assertTrue(context, configuration.getProcedures().get(0).getSteps().stream()
			.anyMatch(step -> "Merge".equals(step.getKind().getName())));
		List<String> problems = problems(router);
		assertTrue(context + ": " + problems, problems.stream().anyMatch(p -> p.contains("ERROR_MERGE")));
		List<String> calls = readOut(router);
		assertTrue(context + ": " + calls, calls.stream().anyMatch(TestCouplerProgrammingStyle::readsLoadState));
	}

	/**
	 * An import of a copy of the ETS 4 export whose router program says the new style completes the
	 * known program (imported before by the shared project).
	 */
	private static void checkCompletion() throws Exception {
		File variant = File.createTempFile("TestCouplerProgrammingStyle", ".knxproj");
		List<TLObject> before = instances("ehome.project:Project");
		Program program = null;
		try {
			writeVariant(new File(ETS4), variant);
			importSample(variant.getPath());
			List<TLObject> created = SharedData.newProjects(before);
			assertEquals(1, created.size());
			try {
				TLObject router = device(created.get(0), ROUTER);
				program = DeviceConfiguration.program(router);
				assertTrue(Boolean.TRUE.equals(program.getLineCouplerNewProgrammingStyleOrNull()));
				checkPropertyBased("completed", router);
			} finally {
				SharedData.deleteProjects(created);
			}
		} finally {
			variant.delete();
			if (program != null) {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					program.setLineCouplerNewProgrammingStyle(false);
					tx.commit();
				}
			}
		}
	}

	private static boolean readsLoadState(String call) {
		for (int lsm = 1; lsm <= 3; lsm++) {
			if (call.startsWith("readProperty " + lsm + " " + LoadControls.PID_LOAD_STATE_CONTROL + " ")) {
				return true;
			}
		}
		return false;
	}

	private static List<String> problems(TLObject device) {
		return DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getProblems().stream().map(String::valueOf).toList();
	}

	/** The accesses of a read-out of the device's image from a coupler of mask 091A. */
	private static List<String> readOut(TLObject device) throws Exception {
		MockDevice mock = new MockDevice().setDescriptor("091A");
		ReadOut.read(DeviceConfiguration.program(device), DownloadImage.compute(device), List.of(), mock,
			(number, count, block) -> {
				// No progress.
			});
		return mock.calls();
	}

	private static TLObject device(TLObject project, int address) {
		TLObject result = TestTestsystemImage.devicesByAddress(project).get(Integer.valueOf(address));
		assertNotNull("No device " + address, result);
		return result;
	}

	/**
	 * The export with the program of the router (manufacturer M-006A) of the new style.
	 */
	private static void writeVariant(File source, File target) throws IOException {
		String from = OPTION + "=\"0\"";
		String to = OPTION + "=\"1\"";
		try (ZipInputStream in = new ZipInputStream(new FileInputStream(source));
				ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				in.transferTo(buffer);
				byte[] content = buffer.toByteArray();
				if (entry.getName().startsWith("M-006A/M-006A_A-")) {
					String text = new String(content, StandardCharsets.ISO_8859_1);
					String replaced = text.replace(from, to);
					assertFalse("The program has the option.", text.equals(replaced));
					content = replaced.getBytes(StandardCharsets.ISO_8859_1);
				}
				out.putNextEntry(new ZipEntry(entry.getName()));
				out.write(content);
				out.closeEntry();
			}
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestCouplerProgrammingStyle.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
