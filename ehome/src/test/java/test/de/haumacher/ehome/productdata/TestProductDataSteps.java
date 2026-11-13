/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.productdata.TestValueForms.byExtId;
import static test.de.haumacher.ehome.productdata.TestValueForms.withExtId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.KnowledgeBaseException;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.ArchiveContents;
import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.ImportProgress;
import de.haumacher.ehome.importer.ImportSteps;
import de.haumacher.ehome.importer.Importer;

/**
 * Test case for the import of a product file step by step (haumacher/ehome #160): the data its
 * programs share first, then each application program in a transaction of its own, a program that
 * cannot be imported rolled back alone and reported.
 *
 * <p>
 * Each test imports a copy of <code>TestValueForms.knxprod</code> with program and hardware IDs of
 * its own (programs <code>M-0008_A-FFB5</code> to <code>FFB8</code>, which no fixture uses), so that its
 * programs are new.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestProductDataSteps extends BasicTestCase {

	private static final String FILE = "src/test/fixtures/TestValueForms.knxprod";

	private static final String PROGRAM_TYPE = "ehome.operational:Program";

	/**
	 * A program whose commit fails is rolled back alone and reported, with the reason; the other
	 * program is imported, the hardware of the failed one runs no program. A second import of the file
	 * imports the failed program and completes its hardware.
	 */
	public void testFailedProgram() throws Exception {
		byte[] file = copy("FFB5", "FFB6", "FEHLER");
		String failing = "M-0008_A-FFB5-01-0000";
		String other = "M-0008_A-FFB6-01-0000";

		Importer importer = importer();
		Steps steps = new Steps(failing);
		try {
			importer.importProductData(BinaryDataFactory.createBinaryData(file), contents(file), ImportProgress.NONE,
				steps);
			steps.commitLast();
		} finally {
			steps.close();
		}

		assertEquals(List.of(failing), new ArrayList<>(importer.getFailedPrograms().keySet()));
		ResKey reason = importer.getFailedPrograms().get(failing);
		String reasonText = Resources.getInstance(Locale.ENGLISH).getString(reason);
		assertTrue(reasonText, reasonText.contains("Refused by the test."));
		assertTrue(withExtId(PROGRAM_TYPE, failing).isEmpty());
		TLObject imported = byExtId(PROGRAM_TYPE, other);
		assertEquals(List.of(imported), importer.getCreated(PROGRAM_TYPE));
		assertSame(imported, assignment("SCHRITTEFEHLER", "FFB6").tValueByName("program"));
		assertNull(assignment("WERTFORMENFEHLER", "FFB5").tValueByName("program"));
		String failure = Resources.getInstance(Locale.ENGLISH).getString(
			I18NConstants.ERROR_PROGRAM_NOT_IMPORTED__PROGRAM_REASON.fill(failing, reason));
		assertTrue(english(importer.getMessages()).toString(), english(importer.getMessages()).contains(failure));

		// A second import, without failures, imports the program and completes its hardware.
		Importer again = importer();
		Steps all = new Steps(null);
		try {
			again.importProductData(BinaryDataFactory.createBinaryData(file), contents(file), ImportProgress.NONE, all);
			all.commitLast();
		} finally {
			all.close();
		}
		assertEquals(Map.of(), again.getFailedPrograms());
		TLObject completed = byExtId(PROGRAM_TYPE, failing);
		assertEquals(List.of(completed), again.getCreated(PROGRAM_TYPE));
		assertSame(completed, assignment("WERTFORMENFEHLER", "FFB5").tValueByName("program"));
		assertSame(imported, byExtId(PROGRAM_TYPE, other));
	}

	/**
	 * A cancel (an {@link AbortExecutionException} from the progress) at the second program keeps the
	 * shared data and the first program, which were committed, and imports nothing of the second.
	 */
	public void testCancelKeepsCommittedSteps() throws Exception {
		byte[] file = copy("FFB7", "FFB8", "ABBRUCH");
		String first = "M-0008_A-FFB7-01-0000";
		String second = "M-0008_A-FFB8-01-0000";

		Importer importer = importer();
		Steps steps = new Steps(null);
		try {
			importer.importProductData(BinaryDataFactory.createBinaryData(file), contents(file), (done, total, message) -> {
				if (Resources.getInstance(Locale.ENGLISH).getString(message).contains("(2 of 2)")) {
					throw new AbortExecutionException("Cancelled by the test.", null);
				}
			}, steps);
			fail("The import must be cancelled.");
		} catch (AbortExecutionException ex) {
			// Expected.
		} finally {
			steps.close();
		}
		assertNotNull(byExtId(PROGRAM_TYPE, first));
		assertTrue(withExtId(PROGRAM_TYPE, second).isEmpty());
		assertNotNull(byExtId("ehome.catalog:Product", "M-0008_H-TEST.20SCHRITTEABBRUCH-1"));
	}

	private static TLObject assignment(String hardware, String program) {
		return byExtId("ehome.catalog:ProgramAssignment",
			"M-0008_H-TEST.20" + hardware + "-1_HP-" + program + "-01-0000");
	}

	/**
	 * A copy of the fixture with the given numbers of its two programs, and its hardware named with
	 * the given suffix.
	 */
	private static byte[] copy(String first, String second, String suffix) throws IOException {
		return renamed(Files.readAllBytes(new File(FILE).toPath()), "FFB3", first, "FFB4", second,
			"WERTFORMEN", "WERTFORMEN" + suffix, "SCHRITTE", "SCHRITTE" + suffix);
	}

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

	private static ArchiveContents contents(byte[] file) throws Exception {
		return ArchiveContents.read(new ByteArrayInputStream(file), null, true);
	}

	private static Importer importer() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		return new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
	}

	private static List<String> english(List<BufferingI18NLog.Entry> messages) {
		Resources english = Resources.getInstance(Locale.ENGLISH);
		return messages.stream().map(m -> english.getString(m.getMessage())).toList();
	}

	/**
	 * Transactions per step, as the job of the catalog has them, whose commit of the given program
	 * fails.
	 */
	private static final class Steps implements ImportSteps, AutoCloseable {

		private final KnowledgeBase _kb = PersistencyLayer.getKnowledgeBase();

		private final String _failing;

		private Transaction _tx;

		Steps(String failing) {
			_failing = failing;
			_tx = _kb.beginTransaction();
		}

		@Override
		public boolean isStepwise() {
			return true;
		}

		@Override
		public void commitShared() throws KnowledgeBaseException {
			commitLast();
			_tx = _kb.beginTransaction();
		}

		@Override
		public void commitProgram(String program) throws KnowledgeBaseException {
			if (program.equals(_failing)) {
				throw new KnowledgeBaseException("Refused by the test.", (Throwable) null);
			}
			commitLast();
			_tx = _kb.beginTransaction();
		}

		@Override
		public void rollback(Throwable cause) {
			_tx.rollback();
			_tx = _kb.beginTransaction();
		}

		void commitLast() throws KnowledgeBaseException {
			Transaction tx = _tx;
			_tx = null;
			tx.commit();
		}

		@Override
		public void close() {
			if (_tx != null) {
				_tx.rollback();
				_tx = null;
			}
		}

	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProductDataSteps.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
