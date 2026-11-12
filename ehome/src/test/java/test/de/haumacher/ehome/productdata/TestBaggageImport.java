/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.etsImport.I18NConstants;
import de.haumacher.ehome.etsImport.ImportEtsProjectJob;
import de.haumacher.ehome.etsImport.ImportProductDataJob;
import de.haumacher.ehome.importer.Importer;

/**
 * Test case for the files a manufacturer ships with its product data (ETS: baggage,
 * haumacher/ehome #154): kept with the manufacturer from a product file and from a project file,
 * reused by their IDs, replaced by a newer version only, named by the programs and products.
 *
 * <p>
 * The fixtures (<code>src/test/fixtures/make-fixtures.py</code>) are made for the test, with
 * pictures and archives of their own: <code>TestBaggage.knxprod</code>,
 * <code>TestBaggageNewer.knxprod</code>, <code>TestBaggageDriver.knxprod</code> (no program) and
 * <code>TestBaggage.knxproj</code>. Each test imports what it needs; the shared data stays, the
 * project is deleted again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBaggageImport extends BasicTestCase {

	private static final String FIXTURES = "src/test/fixtures/";

	private static final String BAGGAGE = "ehome.catalog:Baggage";

	private static final String PROGRAM = "M-0008_A-FFB0-01-0000";

	private static final String PICTURE = "M-0008_BG--TEST.5FBild.2Epng";

	private static final String HELP = "M-0008_BG-hilfe-TEST.5FHilfe.2Ezip";

	private static final String MISSING = "M-0008_BG--TEST.5FFehlt.2Epng";

	private static final String DRIVER = "M-0008_BG-inf-TEST.20Treiber.2Emsi";

	/**
	 * A product file with files: they are kept with their ID, name, folder, time and content; the
	 * program names them; a second import adds nothing; a program without them gains them; a newer
	 * file replaces the content, an older one does not.
	 */
	public void testProductFile() throws Exception {
		assertTrue("Fixture already imported.", withExtId("ehome.operational:Program", PROGRAM).isEmpty());
		Importer importer = importProduct(FIXTURES + "TestBaggage.knxprod", null);

		TLObject manufacturer = byExtId("ehome.catalog:Manufacturer", "M-0008");
		TLObject picture = byExtId(BAGGAGE, PICTURE);
		TLObject help = byExtId(BAGGAGE, HELP);
		TLObject missing = byExtId(BAGGAGE, MISSING);
		assertEquals(Set.of(picture, help, missing), new HashSet<>(importer.getCreated(BAGGAGE)));
		for (TLObject file : List.of(picture, help, missing)) {
			assertSame(manufacturer, file.tContainer());
		}

		byte[] fixture = entry(FIXTURES + "TestBaggage.knxprod", "M-0008/Baggages/TEST_Bild.png");
		assertEquals("TEST_Bild.png", picture.tValueByName("name"));
		assertNull(picture.tValueByName("path"));
		assertEquals(Date.from(Instant.parse("2026-11-01T10:00:00Z")), picture.tValueByName("time"));
		assertContent(fixture, picture);
		assertEquals("image/png", ((BinaryData) picture.tValueByName("content")).getContentType());
		assertEquals("TEST_Bild.png", ((BinaryData) picture.tValueByName("content")).getName());

		assertEquals("TEST_Hilfe.zip", help.tValueByName("name"));
		assertEquals("hilfe", help.tValueByName("path"));
		// Seven decimal places in the file; the model keeps milliseconds.
		assertEquals(Date.from(Instant.parse("2026-11-02T09:03:26.872Z")), help.tValueByName("time"));
		byte[] helpContent = entry(FIXTURES + "TestBaggage.knxprod", "M-0008/Baggages/hilfe/TEST_Hilfe.zip");
		assertContent(helpContent, help);

		// Listed but not in the file: kept without content, and reported.
		assertNull(missing.tValueByName("content"));
		assertNull(missing.tValueByName("size"));
		assertEquals(List.of(
			"The file \"M-0008/TEST_Fehlt.png\" of the manufacturer is listed in the product data, but the "
				+ "imported file does not hold it: the catalog lists the file without its content.",
			"The entry \"M-0008/Baggages/TEST_Ungelistet.txt\" lies among the files of the manufacturer, but the "
				+ "product data do not list it: it is not imported."),
		texts(importer.getMessages()));

		// The program names its files in the order of the file.
		TLObject program = byExtId("ehome.operational:Program", PROGRAM);
		assertEquals(List.of(picture, help, missing), list(program, "baggage"));
		assertEquals(1, importer.getProgramFiles());

		// A picture of the parameter dialog: a type without value naming its file.
		TLObject pictureType = byExtId("ehome.operational:PictureType", PROGRAM + "_PT-Bild");
		assertTrue(pictureType instanceof de.haumacher.ehome.model.operational.VoidType);
		assertSame(picture, pictureType.tValueByName("picture"));
		TLObject parameter = byExtId("ehome.operational:Parameter", PROGRAM + "_P-9");
		assertSame(pictureType, parameter.tValueByName("type"));
		assertSame(program, parameter.tContainer());

		// Twice: nothing is added, nothing changes.
		int count = instances(BAGGAGE).size();
		Importer again = importProduct(FIXTURES + "TestBaggage.knxprod", null);
		assertEquals(count, instances(BAGGAGE).size());
		assertEquals(List.of(), again.getCreated(BAGGAGE));
		assertEquals(List.of(), again.getReplacedBaggage());
		assertContent(fixture, picture);
		assertEquals(List.of(picture, help, missing), list(program, "baggage"));
		assertEquals(3,
			list(manufacturer, "baggage").stream().filter(b -> List.of(picture, help, missing).contains(b)).count());

		// A program an earlier import left without its files gains them from the next import.
		update(program, "baggage", List.of());
		Importer completing = importProduct(FIXTURES + "TestBaggage.knxprod", null);
		assertEquals(List.of(picture, help, missing), list(program, "baggage"));
		assertTrue(texts(completing.getMessages()).contains("The application program \"" + PROGRAM
			+ "\", imported earlier without the files of its manufacturer it names, is completed: it names 3 files now."));

		// A newer version replaces the picture; the older archive leaves the known one; nothing is added.
		Map<?, ?> report = importAction(FIXTURES + "TestBaggageNewer.knxprod");
		assertEquals(count, instances(BAGGAGE).size());
		assertContent(entry(FIXTURES + "TestBaggageNewer.knxprod", "M-0008/Baggages/TEST_Bild.png"), picture);
		assertEquals(Date.from(Instant.parse("2026-11-05T08:00:00Z")), picture.tValueByName("time"));
		assertContent(helpContent, help);
		assertEquals(Date.from(Instant.parse("2026-11-02T09:03:26.872Z")), help.tValueByName("time"));
		List<String> messages = reportTexts(report);
		assertTrue(messages.toString(), messages.contains("A newer version of the file \"M-0008/TEST_Bild.png\" of the "
			+ "manufacturer, of 2026-11-05T08:00:00Z, replaces the content of the version of 2026-11-01T10:00:00Z."));
		String summary = (String) report.get(ImportEtsProjectJob.SUMMARY);
		assertTrue(summary, summary.contains(
			Resources.getInstance().getString(I18NConstants.BAGGAGE_REPLACED__NAMES.fill("TEST_Bild.png"))));
		assertFalse(summary, summary.contains(Resources.getInstance().getString(I18NConstants.NO_PROGRAMS)));
	}

	/**
	 * A product file without an application program imports its files and says it brought no
	 * program; a file above the size limit is kept without content and reported, and completed by an
	 * import without the limit.
	 */
	public void testWithoutProgram() throws Exception {
		assertTrue("Fixture already imported.", withExtId(BAGGAGE, DRIVER).isEmpty());
		Importer limited = importProduct(FIXTURES + "TestBaggageDriver.knxprod", 1024L);
		TLObject driver = byExtId(BAGGAGE, DRIVER);
		assertEquals("TEST Treiber.msi", driver.tValueByName("name"));
		assertEquals("inf", driver.tValueByName("path"));
		assertNull(driver.tValueByName("content"));
		assertEquals(0, limited.getProgramFiles());
		assertEquals(List.of(
			"The file \"M-0008/inf/TEST Treiber.msi\" of the manufacturer is larger than 1024 bytes, the limit for "
				+ "one file, and its content is not imported: the catalog lists the file without it.",
			"The product file holds no application program: only its products and the files of the manufacturer "
				+ "are imported."),
			texts(limited.getMessages()));
		// The product names its driver.
		TLObject variant = byExtId("ehome.catalog:Variant", "M-0008_H-TEST.20TREIBER-1_P-TEST.20Treiber");
		assertEquals(List.of(driver), list(variant, "baggage"));

		// Without the limit: the content completes the known file.
		Map<?, ?> report = importAction(FIXTURES + "TestBaggageDriver.knxprod");
		assertContent(entry(FIXTURES + "TestBaggageDriver.knxprod", "M-0008/Baggages/inf/TEST Treiber.msi"), driver);
		assertEquals(List.of(driver), list(variant, "baggage"));
		assertEquals("application/octet-stream", ((BinaryData) driver.tValueByName("content")).getContentType());
		assertTrue(reportTexts(report).contains(
			"The file \"M-0008/inf/TEST Treiber.msi\" of the manufacturer, kept earlier without its content, gains it now."));
		String summary = (String) report.get(ImportEtsProjectJob.SUMMARY);
		assertTrue(summary, summary.contains(Resources.getInstance().getString(I18NConstants.NO_PROGRAMS)));
	}

	/** A project file keeps the files of its manufacturers as well. */
	public void testProjectFile() throws Exception {
		List<TLObject> projects = instances("ehome.project:Project");
		List<BufferingI18NLog.Entry> messages;
		try {
			messages = test.de.haumacher.ehome.importer.TestEtsImport.importSample(FIXTURES + "TestBaggage.knxproj");
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(projects));
		}
		assertEquals(List.of(), texts(test.de.haumacher.ehome.importer.TestEtsImport.withoutMasterData(messages)));
		TLObject picture = byExtId(BAGGAGE, "M-0008_BG--TEST.5FProjekt.2Epng");
		assertEquals("TEST_Projekt.png", picture.tValueByName("name"));
		assertEquals(Date.from(Instant.parse("2026-11-03T12:00:00Z")), picture.tValueByName("time"));
		assertContent(entry(FIXTURES + "TestBaggage.knxproj", "M-0008/Baggages/TEST_Projekt.png"), picture);
		assertSame(byExtId("ehome.catalog:Manufacturer", "M-0008"), picture.tContainer());
		TLObject program = byExtId("ehome.operational:Program", "M-0008_A-FFB1-01-0000");
		assertEquals(List.of(picture), list(program, "baggage"));
	}

	/**
	 * Review probe: the project file imported twice into the same data; its file exists once, under
	 * the one manufacturer, with its content, and survives the deletion of the projects (it is shared
	 * data of the catalog).
	 */
	public void testProbeProjectTwiceWithProduct() throws Exception {
		List<TLObject> projects = instances("ehome.project:Project");
		try {
			test.de.haumacher.ehome.importer.TestEtsImport.importSample(FIXTURES + "TestBaggage.knxproj");
			test.de.haumacher.ehome.importer.TestEtsImport.importSample(FIXTURES + "TestBaggage.knxproj");
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(projects));
		}
		TLObject manufacturer = byExtId("ehome.catalog:Manufacturer", "M-0008");
		TLObject file = byExtId(BAGGAGE, "M-0008_BG--TEST.5FProjekt.2Epng");
		assertSame(manufacturer, file.tContainer());
		assertNotNull(file.tValueByName("content"));
		assertEquals(List.of(byExtId(BAGGAGE, "M-0008_BG--TEST.5FProjekt.2Epng")),
			list(byExtId("ehome.operational:Program", "M-0008_A-FFB1-01-0000"), "baggage"));
	}

	private static void assertContent(byte[] expected, TLObject baggage) throws Exception {
		BinaryData content = (BinaryData) baggage.tValueByName("content");
		assertNotNull(content);
		try (InputStream in = content.getStream()) {
			assertTrue(java.util.Arrays.equals(expected, in.readAllBytes()));
		}
		assertEquals(Long.valueOf(expected.length), baggage.tValueByName("size"));
	}

	private static byte[] entry(String file, String name) throws Exception {
		try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals(name)) {
					return zip.readAllBytes();
				}
			}
		}
		throw new AssertionError("No entry " + name + " in " + file);
	}

	private static List<String> texts(List<BufferingI18NLog.Entry> messages) {
		Resources english = Resources.getInstance(Locale.ENGLISH);
		return messages.stream()
			.filter(m -> !String.valueOf(m.getMessage()).contains("_MASTER_DATA_"))
			.map(m -> english.getString(m.getMessage()))
			.toList();
	}

	private static List<String> reportTexts(Map<?, ?> report) {
		List<String> result = new ArrayList<>();
		for (Object message : (List<?>) report.get(ImportEtsProjectJob.MESSAGES)) {
			result.add((String) ((Map<?, ?>) message).get(ImportEtsProjectJob.MESSAGE));
		}
		return result;
	}

	private static void update(TLObject obj, String attribute, Object value) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static List<Object> list(TLObject obj, String part) {
		return new ArrayList<>((Collection<?>) obj.tValueByName(part));
	}

	/**
	 * Imports the given product file in a transaction of its own, with the given size limit of a
	 * file of the baggage (<code>null</code> for the default).
	 */
	private static Importer importProduct(String file, Long limit) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		if (limit != null) {
			importer.setBaggageSizeLimit(limit);
		}
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importProductData(in);
				tx.commit();
			}
		}
		return importer;
	}

	private static Map<?, ?> importAction(String file) throws Exception {
		ImportProductDataJob job = new ImportProductDataJob(null, null);
		File source = new File(file);
		assertTrue(Files.exists(source.toPath()));
		return (Map<?, ?>) new RecordingJobMonitor().run(job,
			List.of(BinaryDataFactory.createBinaryDataWithName(source, source.getName())));
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = withExtId(typeName, extId);
		assertEquals(typeName + " " + extId, 1, result.size());
		return result.get(0);
	}

	private static List<TLObject> withExtId(String typeName, String extId) {
		return instances(typeName).stream()
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBaggageImport.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
