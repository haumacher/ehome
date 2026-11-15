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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.job.JobHarness;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.job.JobState;
import com.top_logic.layout.view.job.JobStatus;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
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
 * the database; the cancel of the import job run by the engine's job runner ({@link JobHarness}):
 * in the database, past the point of no return, and between the files of a multi-file import.
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
	 * importer, declares the commit of its file as its point of no return right before saving, and
	 * returns the report naming the project it created.
	 */
	public void testPhases() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		RecordingJobMonitor job = new RecordingJobMonitor();
		try {
			Map<?, ?> report = (Map<?, ?>) job.run(new ImportEtsProjectJob(null, null), List.of(testsystem("Testsystem.knxproj")));
			assertEquals(1, SharedData.newProjects(before).size());
			// The project the import created (haumacher/ehome #107).
			assertSame(SharedData.newProjects(before).get(0), report.get(ImportEtsProjectJob.PROJECT));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}

		assertEquals(List.of("read", "import", "uncancelable", "save"), job.getPhases());
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
	 * A cancel while the import job is in the database (in the middle of the import of its file, in
	 * the transaction of the import) reaches the job through its <code>CancelScope</code>: the worker
	 * thread is not interrupted (an interrupt in a database access closes the embedded H2 database,
	 * TL #30196), the job ends at its next report as cancelled, without a result, the transaction is
	 * rolled back, and the database stays usable.
	 */
	public void testCancelInDatabase() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		JobHarness job = new JobHarness(JobHarness.importPhases());
		List<Boolean> interrupted = new ArrayList<>();
		job.onEvent(event -> {
			if (event.equals("message:Master data")) {
				job.cancel();
				interrupted.add(Boolean.valueOf(Thread.currentThread().isInterrupted()));
				// A read of the database right after the cancel, in the transaction of the import.
				instances("ehome.project:Project");
				interrupted.add(Boolean.valueOf(Thread.currentThread().isInterrupted()));
			}
		});
		try {
			job.start(new ImportEtsProjectJob(null, null), List.of(testsystem("Testsystem.knxproj")));
			JobState state = job.await();

			assertEquals(JobStatus.CANCELLED, state.status());
			assertNull(state.result());
			assertEquals("The worker is not interrupted.", List.of(Boolean.FALSE, Boolean.FALSE), interrupted);
			assertFalse(job.events().toString(), job.events().contains("phase:save"));
			assertEquals("Nothing is imported.", List.of(), SharedData.newProjects(before));
			assertDatabaseUsable();
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * A cancel after the import job has passed its point of no return (TL #30194) - right before
	 * its commit, and once more while it saves - is refused: the job ends as completed with its
	 * report, and the project is imported.
	 */
	public void testCancelAfterPointOfNoReturn() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		JobHarness job = new JobHarness(JobHarness.importPhases());
		job.onEvent(event -> {
			if (event.equals("uncancelable") || event.equals("message:Saving")) {
				job.cancel();
			}
		});
		try {
			job.start(new ImportEtsProjectJob(null, null), List.of(testsystem("Testsystem.knxproj")));
			JobState state = job.await();

			assertEquals(JobStatus.COMPLETED, state.status());
			assertFalse(state.cancelable());
			List<TLObject> created = SharedData.newProjects(before);
			assertEquals(1, created.size());
			assertSame(created.get(0), ((Map<?, ?>) state.result()).get(ImportEtsProjectJob.PROJECT));
			List<String> events = job.events();
			assertTrue(events.toString(), events.indexOf("uncancelable") < events.indexOf("phase:save"));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * A multi-file import cancelled at its second file ends as cancelled with the report of the
	 * first file, which stays imported (TL #30195); nothing of the second file is imported.
	 */
	public void testCancelledMultiFileImport() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		JobHarness job = new JobHarness(JobHarness.importPhases());
		job.onEvent(event -> {
			if (event.equals("message:Reading Second.knxproj")) {
				job.cancel();
			}
		});
		try {
			job.start(new ImportEtsProjectJob(null, null),
				List.of(List.of(testsystem("First.knxproj"), testsystem("Second.knxproj"))));
			JobState state = job.await();

			assertEquals(JobStatus.CANCELLED, state.status());
			List<TLObject> created = SharedData.newProjects(before);
			assertEquals("The first file stays imported, the second is not.", 1, created.size());
			Map<?, ?> report = (Map<?, ?>) state.result();
			assertNotNull("The cancelled job reports the file imported before.", report);
			assertSame(created.get(0), report.get(ImportEtsProjectJob.PROJECT));
			assertTrue(String.valueOf(report.get(ImportEtsProjectJob.SUMMARY)),
				String.valueOf(report.get(ImportEtsProjectJob.SUMMARY)).contains("KNX-Testsystem"));
			String messages = String.valueOf(report.get(ImportEtsProjectJob.MESSAGES));
			assertTrue("The report says that the second file was left out: " + messages,
				messages.contains("Second.knxproj") && messages.contains("cancelled"));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * A cancel when the first of two files enters its save: no point of no return is declared there,
	 * so the job ends cancelled, the second file is not imported, and the report names exactly what
	 * was saved - nothing and no report if the cancel came before the commit of the first file, that
	 * file if its commit was already under way. The database stays usable.
	 */
	public void testCancelDuringSaveOfAnEarlierFile() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		JobHarness job = new JobHarness(JobHarness.importPhases());
		AtomicBoolean cancelled = new AtomicBoolean();
		job.onEvent(event -> {
			if (event.equals("phase:save") && cancelled.compareAndSet(false, true)) {
				job.cancel();
			}
		});
		try {
			job.start(new ImportEtsProjectJob(null, null),
				List.of(List.of(testsystem("First.knxproj"), testsystem("Second.knxproj"))));
			JobState state = job.await();

			assertTrue("The cancel was requested.", cancelled.get());
			assertEquals(JobStatus.CANCELLED, state.status());
			List<TLObject> created = SharedData.newProjects(before);
			assertTrue("The second file is not imported: " + created, created.size() <= 1);
			Map<?, ?> report = (Map<?, ?>) state.result();
			if (created.isEmpty()) {
				assertNull("Nothing saved, nothing reported: " + report, report);
			} else {
				assertNotNull("The cancelled job reports the file saved.", report);
				assertSame(created.get(0), report.get(ImportEtsProjectJob.PROJECT));
			}
			assertDatabaseUsable();
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * A file that holds no ETS project - no archive at all, or an archive without a project - fails
	 * the import with the reason, before anything is imported (haumacher/ehome #169; it used to end as
	 * completed with an empty report, a silent refusal).
	 */
	public void testFileWithoutProject() throws Exception {
		BinaryData text = BinaryDataFactory.createBinaryData(
			"No ETS project.".getBytes(StandardCharsets.UTF_8), "text/plain", "README.md");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("knx_master.xml"));
			zip.write("<KNX xmlns=\"http://knx.org/xml/project/21\"/>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		BinaryData noProject = BinaryDataFactory.createBinaryData(bytes.toByteArray(), "application/zip", "broken.knxproj");
		for (BinaryData file : List.of(text, noProject)) {
			List<TLObject> before = instances("ehome.project:Project");
			JobHarness job = new JobHarness(JobHarness.importPhases());
			try {
				job.start(new ImportEtsProjectJob(null, null), List.of(List.of(file)));
				JobState state = job.await();
				assertEquals(file.getName(), JobStatus.FAILED, state.status());
				String error = Resources.getInstance(Locale.ENGLISH).getString(state.error())
					+ " " + state.errorDetails().stream().map(d -> Resources.getInstance(Locale.ENGLISH).getString(d)).toList();
				assertTrue(error, error.contains("\"" + file.getName() + "\" holds no ETS project"));
				assertTrue("Nothing imported.", SharedData.newProjects(before).isEmpty());
			} finally {
				SharedData.deleteProjects(SharedData.newProjects(before));
			}
		}
	}

	private static BinaryData testsystem(String name) throws IOException {
		return BinaryDataFactory.createBinaryData(Files.readAllBytes(new File(TESTSYSTEM).toPath()), "application/zip", name);
	}

	/**
	 * A read and a commit of the database succeed.
	 */
	static void assertDatabaseUsable() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject project;
		try (Transaction tx = kb.beginTransaction()) {
			project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", "Database usable");
			tx.commit();
		}
		assertTrue(instances("ehome.project:Project").contains(project));
		try (Transaction tx = kb.beginTransaction()) {
			project.tDelete();
			tx.commit();
		}
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
		// The master data follows the programs in this file; it is read first (haumacher/ehome #160), so
		// the manufacturer is named by its name.
		assertEquals("Master data", messages.get(0));
		assertEquals("Manufacturer GIRA Giersiepen: application program " + first + " (1 of 2)", messages.get(1));
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
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SchedulerService.Module.INSTANCE));
	}

}
