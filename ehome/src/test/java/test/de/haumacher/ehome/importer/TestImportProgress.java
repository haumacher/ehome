/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.etsImport.ImportEtsProjectJob;
import de.haumacher.ehome.importer.ArchiveContents;
import de.haumacher.ehome.importer.ImportProgress;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.importer.ProtectedProject;

/**
 * Test of the progress of an ETS import (haumacher/ehome #84): the counting pass
 * ({@link ArchiveContents}), the progress callback of the {@link Importer} ({@link ImportProgress})
 * and the import job ({@link ImportEtsProjectJob}), and that a cancelled import leaves nothing in
 * the database.
 *
 * <p>
 * The test imports the ETS 4 test system and the protected ETS 6 file. The application programs of
 * the ETS 6 file cost seconds each where no earlier test of the database has imported them, and the
 * import of the ETS 6 file is rolled back (its newer master data would change the texts other tests
 * assert), so it pays them on every run.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImportProgress extends BasicTestCase {

	private static final String TESTSYSTEM = "samples/KNX-Testsystem.knxproj";

	private static final String PROTECTED = "samples/KNX-Testsystem-ETS6-protected.knxproj";

	private static final String PROTECTED_PASSWORD = "test1234";

	private static final String LANGUAGES = "src/test/fixtures/TestLanguagesA.knxprod";

	private static final String PROGRAM_TYPE = "ehome.operational:Program";

	/**
	 * A message of an application program: imported (with its number) or already known.
	 */
	private static final Pattern PROGRAM_MESSAGE =
		Pattern.compile("Manufacturer (.+): application program (.+) (\\((\\d) of (\\d)\\)|already known)");

	/**
	 * The importer counts the XML entries of the ETS 4 test system, reports before each of them in
	 * the user's terms, and ends with all of them done.
	 */
	public void testProgress() throws Exception {
		ArchiveContents contents;
		try (InputStream in = new FileInputStream(TESTSYSTEM)) {
			contents = ArchiveContents.read(in, null, false);
		}
		assertEquals(xmlEntries(TESTSYSTEM), contents.getEntries());
		assertEquals(12, contents.getEntries());
		assertEquals(3, contents.getPrograms());

		List<TLObject> before = instances("ehome.project:Project");
		List<int[]> progress = new ArrayList<>();
		List<String> messages = new ArrayList<>();
		try {
			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			Importer importer = importer();
			try (InputStream in = new FileInputStream(TESTSYSTEM); Transaction tx = kb.beginTransaction()) {
				importer.importStream(in, null, contents, (done, total, message) -> {
					progress.add(new int[] { done, total });
					messages.add(english(message));
				});
				tx.commit();
			}
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}

		assertProgress(12, progress);
		assertEquals("Master data", messages.get(0));
		assertTrue(messages.toString(), messages.contains("Hardware and catalog of GIRA Giersiepen"));
		assertTrue(messages.toString(), messages.contains("Hardware and catalog of MDT technologies"));
		assertTrue(messages.toString(), messages.contains("Project KNX-Testsystem"));
		assertPrograms(messages, "GIRA Giersiepen", "Tastsensor 3 Komfort 3fach 10D711", 1, 3);
		assertPrograms(messages, "EIBMARKT GmbH", "N000402_IPRO_10", 2, 3);
	}

	/**
	 * The importer on the protected ETS 6 test system: the counting pass opens the project archive
	 * with the password and counts its entries, and the progress covers them.
	 *
	 * <p>
	 * The import is rolled back: its master data are newer than those of the other samples, and
	 * other tests of the database assert texts of the master data they imported.
	 * </p>
	 */
	public void testProgressProtected() throws Exception {
		byte[] file = Files.readAllBytes(new File(PROTECTED).toPath());
		int expected = xmlEntries(PROTECTED) + projectArchiveEntries(file);
		assertTrue(expected > xmlEntries(PROTECTED));

		ArchiveContents contents = ArchiveContents.read(new ByteArrayInputStream(file), PROTECTED_PASSWORD, false);
		assertEquals(expected, contents.getEntries());
		assertEquals(3, contents.getPrograms());

		List<int[]> progress = new ArrayList<>();
		List<String> messages = new ArrayList<>();
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			importer().importStream(new ByteArrayInputStream(file), PROTECTED_PASSWORD, contents,
				(done, total, message) -> {
					progress.add(new int[] { done, total });
					messages.add(english(message));
				});
			// Not committed.
		}

		assertProgress(expected, progress);
		assertEquals("Master data", messages.get(0));
		assertTrue(messages.toString(), messages.contains("Hardware and catalog of MDT technologies"));
		assertTrue(messages.toString(), messages.contains("Project KNX-Testsystem"));
		assertPrograms(messages, "GIRA Giersiepen", "Tastsensor 3 Komfort 3fach 10D711", 1, 3);
	}

	/**
	 * The import job reports the phases reading, importing and saving with the progress of the
	 * importer, and runs its work where the interrupt of a cancelled job does not reach it (an
	 * interrupted database access closes the embedded H2 database, TL #30196): with the interrupt of
	 * its worker pending, the job imports the file and the database stays usable.
	 */
	public void testInterruptedWorker() throws Exception {
		byte[] file = Files.readAllBytes(new File(TESTSYSTEM).toPath());
		List<TLObject> before = instances("ehome.project:Project");
		RecordingJobMonitor job = new RecordingJobMonitor();
		try {
			Thread.currentThread().interrupt();
			Map<?, ?> report = (Map<?, ?>) job.run(new ImportEtsProjectJob(null, null),
				List.of(BinaryDataFactory.createBinaryData(file, "application/zip", "Testsystem.knxproj")));
			assertFalse("The interrupt of the worker is dropped.", Thread.interrupted());
			assertEquals(1, SharedData.newProjects(before).size());
			// The project the import created (haumacher/ehome #107).
			assertSame(SharedData.newProjects(before).get(0), report.get(ImportEtsProjectJob.PROJECT));
		} finally {
			Thread.interrupted();
			SharedData.deleteProjects(SharedData.newProjects(before));
		}

		assertEquals(List.of("read", "import", "save"), job.getPhases());
		List<int[]> progress = new ArrayList<>();
		for (double[] report : job.getProgress()) {
			progress.add(new int[] { (int) report[0], (int) report[1] });
		}
		assertProgress(12, progress);
		List<String> messages = job.getMessages();
		assertEquals("Reading Testsystem.knxproj", messages.get(0));
		assertEquals("Master data", messages.get(1));
		assertEquals("Saving", messages.get(messages.size() - 1));
		assertTrue(messages.toString(), messages.contains("Project KNX-Testsystem"));
	}

	/**
	 * An import cancelled after its first application program (the callback throws an
	 * {@link AbortExecutionException}, as the job does when it is cancelled) leaves no new program in
	 * the database. The product file is a variant of a fixture with program IDs no other test imports,
	 * so its programs are new.
	 */
	public void testCancelAfterFirstProgram() throws Exception {
		byte[] file = renamed(Files.readAllBytes(new File(LANGUAGES).toPath()),
			"FFF3-01-0000", "FFC3-01-0000", "FFF4-01-0000", "FFC4-01-0000");
		String first = "M-0008_A-FFC3-01-0000";
		String second = "M-0008_A-FFC4-01-0000";
		assertTrue(withExtId(PROGRAM_TYPE, first).isEmpty());
		assertTrue(withExtId(PROGRAM_TYPE, second).isEmpty());

		ArchiveContents contents = ArchiveContents.read(new ByteArrayInputStream(file), null, true);
		assertEquals(3, contents.getEntries());
		assertEquals(2, contents.getPrograms());

		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = importer();
		List<String> createdBeforeCancel = new ArrayList<>();
		List<String> messages = new ArrayList<>();
		try (Transaction tx = kb.beginTransaction()) {
			importer.importProductData(new ByteArrayInputStream(file), contents, (done, total, message) -> {
				String text = english(message);
				messages.add(text);
				if (text.contains("(2 of 2)")) {
					for (TLObject program : importer.getCreated(PROGRAM_TYPE)) {
						createdBeforeCancel.add((String) program.tValueByName("extId"));
					}
					throw new AbortExecutionException("Cancelled by the test.", null);
				}
			});
			tx.commit();
			fail("The import must be cancelled.");
		} catch (AbortExecutionException ex) {
			// Expected.
		}

		// The first program was imported in the transaction, which the cancel rolled back.
		assertEquals(List.of(first), createdBeforeCancel);
		// The master data follows the programs in this file: the manufacturer is named by its folder.
		assertEquals("Manufacturer M-0008: application program " + first + " (1 of 2)", messages.get(0));
		assertTrue(withExtId(PROGRAM_TYPE, first).isEmpty());
		assertTrue(withExtId(PROGRAM_TYPE, second).isEmpty());
	}

	/**
	 * An import of a project cancelled at its last entry, after the project was created in the
	 * transaction, leaves no project.
	 */
	public void testCancelInProject() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		Set<TLObject> programsBefore = new HashSet<>(instances(PROGRAM_TYPE));
		ArchiveContents contents;
		try (InputStream in = new FileInputStream(TESTSYSTEM)) {
			contents = ArchiveContents.read(in, null, false);
		}
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = importer();
		List<TLObject> createdBeforeCancel = new ArrayList<>();
		try {
			try (InputStream in = new FileInputStream(TESTSYSTEM); Transaction tx = kb.beginTransaction()) {
				importer.importStream(in, null, contents, (done, total, message) -> {
					if (done == total - 1) {
						createdBeforeCancel.addAll(importer.getCreated("ehome.project:Project"));
						throw new AbortExecutionException("Cancelled by the test.", null);
					}
				});
				tx.commit();
				fail("The import must be cancelled.");
			} catch (AbortExecutionException ex) {
				// Expected.
			}
			assertEquals(1, createdBeforeCancel.size());
			assertEquals(List.of(), SharedData.newProjects(before));
			assertEquals(programsBefore, new HashSet<>(instances(PROGRAM_TYPE)));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private static void assertProgress(int total, List<int[]> progress) {
		assertFalse(progress.isEmpty());
		int last = 0;
		for (int[] report : progress) {
			assertEquals(total, report[1]);
			assertTrue("Progress must not fall back.", report[0] >= last);
			last = report[0];
		}
		assertEquals(total, last);
	}

	/**
	 * Asserts that the given program of the given manufacturer is reported, with its number among
	 * the programs of the file, or as already known.
	 */
	private static void assertPrograms(List<String> messages, String manufacturer, String program, int number,
			int count) {
		String imported = "Manufacturer " + manufacturer + ": application program " + program + " (" + number + " of "
			+ count + ")";
		String known = "Manufacturer " + manufacturer + ": application program " + program + " already known";
		assertTrue(messages.toString(), messages.contains(imported) || messages.contains(known));
		assertTrue(messages.stream().filter(m -> m.startsWith("Manufacturer ")).allMatch(
			m -> PROGRAM_MESSAGE.matcher(m).matches()));
	}

	private static Importer importer() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		return new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
	}

	private static String english(ResKey message) {
		return Resources.getInstance(Locale.ENGLISH).getString(message);
	}

	/**
	 * The number of XML entries of the given file outside the baggage, not counting a project
	 * archive's.
	 */
	private static int xmlEntries(String file) throws IOException {
		int result = 0;
		try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.isDirectory() && entry.getName().endsWith(".xml") && !entry.getName().contains("Baggages")) {
					result++;
				}
			}
		}
		return result;
	}

	/**
	 * The number of XML entries of the project archive of the given protected file.
	 */
	private static int projectArchiveEntries(byte[] file) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().startsWith("P-") && entry.getName().endsWith(".zip")) {
					return (int) ProtectedProject.read(entry.getName(), zip.readAllBytes(), ProtectedProject.ETS6_SCHEMA,
						PROTECTED_PASSWORD).keySet().stream().filter(name -> name.endsWith(".xml")).count();
				}
			}
		}
		throw new AssertionError("No project archive in " + PROTECTED);
	}

	/**
	 * The given archive with the given texts replaced (pairs of the text and its replacement) in the
	 * names and contents of its entries.
	 */
	private static byte[] renamed(byte[] archive, String... replacements) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive));
				ZipOutputStream out = new ZipOutputStream(buffer)) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				String name = entry.getName();
				String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				for (int n = 0; n < replacements.length; n += 2) {
					name = name.replace(replacements[n], replacements[n + 1]);
					content = content.replace(replacements[n], replacements[n + 1]);
				}
				out.putNextEntry(new ZipEntry(name));
				out.write(content.getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		return buffer.toByteArray();
	}

	private static List<TLObject> withExtId(String typeName, String extId) {
		return instances(typeName).stream().filter(obj -> extId.equals(obj.tValueByName("extId"))).toList();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestImportProgress.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
