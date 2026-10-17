/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.TestEtsImport;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKeyUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.etsImport.ImportEtsProjectAction;
import de.haumacher.ehome.etsImport.ImportProductDataAction;
import de.haumacher.ehome.importer.Importer;

/**
 * Test case for the import of the product file of a manufacturer (<code>.knxprod</code>) with
 * {@link Importer#importProductData(InputStream)} and {@link ImportProductDataAction}.
 *
 * <p>
 * The test runs in the database shared with the tests before it, which hold the data of the Test
 * project. The fixtures (<code>src/test/fixtures/make-fixtures.py</code>):
 * <code>TestProduct.knxprod</code> is the product data of the Test project,
 * <code>TestNewProduct.knxprod</code> the same with new IDs, imported as new products, and
 * <code>TestNewProduct.knxproj</code> a project using them.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestProductDataImport extends BasicTestCase {

	private static final String FIXTURES = "src/test/fixtures/";

	private static final String TEST_PROGRAM = "M-0008_A-10D7-11-A0E5-O000A";

	private static final String NEW_PROGRAM = "M-0008_A-10D7-11-A0E5-O00F0";

	private static final String NEW_HARDWARE = "M-0008_H-8.20.2F.2F.20TEST-1";

	private static final String NEW_VARIANT = NEW_HARDWARE + "_P-5133.2000";

	/** The types whose objects an import of product data creates or reuses. */
	private static final List<String> COUNTED = List.of(
		"ehome.project:Project",
		"ehome.catalog:Manufacturer",
		"ehome.catalog:Catalog",
		"ehome.catalog:Product",
		"ehome.catalog:Variant",
		"ehome.catalog:ProgramAssignment",
		"ehome.operational:Program",
		"ehome.operational:Parameter",
		"ehome.operational:MaskVersion",
		"ehome.datapoint:DatapointMainType",
		"ehome.datapoint:DatapointSubtype");

	private final List<TLObject> _createdProjects = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		// The product data of the Test project, as the tests before this one leave it.
		if (withExtId("ehome.operational:Program", TEST_PROGRAM).isEmpty()) {
			importProject("samples/Test.knxproj");
		}
	}

	@Override
	protected void tearDown() throws Exception {
		// The projects are deleted again, the shared product data stays.
		if (!_createdProjects.isEmpty()) {
			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			try (Transaction tx = kb.beginTransaction()) {
				for (TLObject project : _createdProjects) {
					project.tDelete();
				}
				tx.commit();
			}
			_createdProjects.clear();
		}
		super.tearDown();
	}

	/**
	 * The product data of a project already imported is reused completely: nothing is created, no
	 * project, and the report names everything as known.
	 */
	public void testProductOfKnownProject() throws Exception {
		Map<String, Integer> before = counts();
		Importer importer = importProduct(FIXTURES + "TestProduct.knxprod");
		assertEquals(before, counts());
		assertEquals(Set.of("M-0008"), importer.getManufacturerIds());
		assertEquals(List.of(), TestEtsImport.withoutMasterData(importer.getMessages()));

		assertEquals(List.of(), importer.getCreated("ehome.catalog:Product"));
		assertEquals(List.of(), importer.getCreated("ehome.operational:Program"));
		assertEquals(List.of(byExtId("ehome.operational:Program", TEST_PROGRAM)),
			importer.getKnown("ehome.operational:Program"));
		assertEquals(List.of(byExtId("ehome.catalog:Product", "M-0008_H-8.20.2F.2F.20zxy3.20xx-1")),
			importer.getKnown("ehome.catalog:Product"));

		// Twice: nothing changes.
		int masterData = importProduct(FIXTURES + "TestProduct.knxprod").getMessages().size();
		assertEquals(before, counts());

		// The report of the view action.
		Map<?, ?> report = importAction(FIXTURES + "TestProduct.knxprod");
		assertEquals(before, counts());
		assertSame(byExtId("ehome.catalog:Manufacturer", "M-0008"), report.get(ImportProductDataAction.MANUFACTURER));
		assertNull(report.get(ImportEtsProjectAction.PROJECT));
		// Only the messages about the mask versions the database holds from other tests' imports.
		assertEquals(masterData, ((List<?>) report.get(ImportEtsProjectAction.MESSAGES)).size());
		String summary = (String) report.get(ImportEtsProjectAction.SUMMARY);
		String manufacturer = label(byExtId("ehome.catalog:Manufacturer", "M-0008"));
		String program = label(byExtId("ehome.operational:Program", TEST_PROGRAM));
		assertEquals(summary, en("Imported product data: TestProduct.knxprod (Manufacturer already known: "
			+ manufacturer + "; Products already known: Tastsensor 3 Komfort 3fach; "
			+ "Application programs already known: " + program + "). Messages of the import: " + masterData + ".",
			"Importierte Produktdaten: TestProduct.knxprod (Hersteller bereits bekannt: " + manufacturer
				+ "; Produkte bereits bekannt: Tastsensor 3 Komfort 3fach; "
				+ "Applikationsprogramme bereits bekannt: " + program + "). Meldungen des Imports: " + masterData + "."),
			summary);
	}

	/**
	 * New product data creates its catalog entries, product and program, with the same content as
	 * the import of a project using it; a following import of the project, and a second import of
	 * the product data, reuse them.
	 */
	public void testNewProduct() throws Exception {
		assertTrue("Fixture already imported.", withExtId("ehome.operational:Program", NEW_PROGRAM).isEmpty());
		Map<String, Integer> before = counts();
		Importer importer = importProduct(FIXTURES + "TestNewProduct.knxprod");
		assertEquals(List.of(), TestEtsImport.withoutMasterData(importer.getMessages()));

		TLObject testProgram = byExtId("ehome.operational:Program", TEST_PROGRAM);
		TLObject program = byExtId("ehome.operational:Program", NEW_PROGRAM);
		int parameters = ((Collection<?>) testProgram.tValueByName("parameters")).size();

		Map<String, Integer> expected = new LinkedHashMap<>(before);
		expected.merge("ehome.catalog:Catalog", 2, Integer::sum);
		expected.merge("ehome.catalog:Product", 1, Integer::sum);
		expected.merge("ehome.catalog:Variant", 1, Integer::sum);
		expected.merge("ehome.catalog:ProgramAssignment", 1, Integer::sum);
		expected.merge("ehome.operational:Program", 1, Integer::sum);
		expected.merge("ehome.operational:Parameter", parameters, Integer::sum);
		assertEquals(expected, counts());

		// The report of the importer.
		TLObject manufacturer = byExtId("ehome.catalog:Manufacturer", "M-0008");
		TLObject product = byExtId("ehome.catalog:Product", NEW_HARDWARE);
		assertEquals(List.of(product), importer.getCreated("ehome.catalog:Product"));
		assertEquals(List.of(program), importer.getCreated("ehome.operational:Program"));
		assertEquals(List.of(), importer.getKnown("ehome.operational:Program"));
		assertTrue(importer.getKnown("ehome.catalog:Manufacturer").contains(manufacturer));

		// The program has the content of the program of the Test project, which differs in its IDs
		// and names only.
		for (String part : List.of("parameterTypes", "parameters", "ports", "segments", "loadProcedures",
			"unplaced")) {
			assertEquals(part, size(testProgram, part), size(program, part));
		}
		assertEquals(descendants(testProgram).size(), descendants(program).size());
		assertEquals(testProgram.tValueByName("mask"), program.tValueByName("mask"));
		assertSame(manufacturer, program.tContainer());
		assertEquals("TEST: Produktdaten 10D711 (künstliche Testdaten)", german(program, "name"));

		// The catalog: a section with a subsection listing the variant of the product, which is
		// assigned the program.
		TLObject section = byExtId("ehome.catalog:Catalog", "M-0008_CS-TEST");
		assertTrue(((Collection<?>) manufacturer.tValueByName("catalogs")).contains(section));
		assertEquals("TEST: Taster", german(section, "name"));
		TLObject subsection = byExtId("ehome.catalog:Catalog", "M-0008_CS-TEST-P3");
		assertEquals(List.of(subsection), list(section, "sections"));
		TLObject variant = byExtId("ehome.catalog:Variant", NEW_VARIANT);
		assertEquals(List.of(variant), list(subsection, "variants"));
		assertEquals("TEST: Produktdaten (künstliche Testdaten)", german(variant, "name"));
		assertEquals("5133 00", variant.tValueByName("orderNumber"));
		assertSame(product, variant.tContainer());
		assertTrue(((Collection<?>) manufacturer.tValueByName("products")).contains(product));
		TLObject assignment = single(product.tValueByName("programs"));
		assertSame(program, assignment.tValueByName("program"));

		// Twice: nothing changes, everything is known.
		Map<String, Integer> imported = counts();
		Importer again = importProduct(FIXTURES + "TestNewProduct.knxprod");
		assertEquals(imported, counts());
		assertEquals(List.of(), again.getCreated("ehome.catalog:Product"));
		assertEquals(List.of(product), again.getKnown("ehome.catalog:Product"));
		assertEquals(List.of(program), again.getKnown("ehome.operational:Program"));

		// The project using the product reuses the product data: one product, one program.
		Set<TLObject> devicesBefore = new HashSet<>(instances("ehome.physical:Device"));
		importProject(FIXTURES + "TestNewProduct.knxproj");
		Map<String, Integer> withProject = new LinkedHashMap<>(imported);
		withProject.merge("ehome.project:Project", 1, Integer::sum);
		assertEquals(withProject, counts());
		List<TLObject> devices = instances("ehome.physical:Device").stream()
			.filter(d -> !devicesBefore.contains(d))
			.toList();
		assertEquals(1, devices.size());
		assertSame(variant, devices.get(0).tValueByName("type"));
		assertSame(assignment, devices.get(0).tValueByName("program"));

		// The product data after the project: still nothing new.
		importProduct(FIXTURES + "TestNewProduct.knxprod");
		assertEquals(withProject, counts());
	}

	/** A product file with an encrypted entry is refused, and nothing of it is kept. */
	public void testEncrypted() throws Exception {
		Map<String, Integer> before = counts();
		assertRefused(FIXTURES + "Encrypted.knxprod",
			"The file cannot be read: it holds an encrypted (password protected) entry, which follows the entry "
				+ "\"M-0008/M-0008_A-10D7-11-A0E5-O000A.xml\". Only unencrypted files can be imported.");
		assertEquals(before, counts());
	}

	/** A product file with baggage is refused. */
	public void testBaggages() throws Exception {
		Map<String, Integer> before = counts();
		File file = zip("Baggage.knxprod", "M-0008/Catalog.xml",
			"<KNX xmlns=\"http://knx.org/xml/project/11\"/>", "M-0008/Baggages/Pictures/device.png", "");
		assertRefused(file.getPath(), "The product file holds baggage (entry \"M-0008/Baggages/Pictures/device.png\"): "
			+ "pictures, help or plug-in files of ETS 5 or 6, which E-Home cannot import. "
			+ "Only product files without baggage can be imported.");
		assertEquals(before, counts());
	}

	/** A project file is no product file. */
	public void testProjectRefused() throws Exception {
		Map<String, Integer> before = counts();
		assertRefused("samples/Test.knxproj", "The product file holds a project (entry \"P-01A0.signature\"). "
			+ "Import a project file (.knxproj) in the projects view.");
		assertEquals(before, counts());
	}

	/** A file without the data of a manufacturer is refused. */
	public void testNoManufacturer() throws Exception {
		File file = zip("Empty.knxprod", "readme.txt", "No product data.");
		assertRefused(file.getPath(),
			"The file holds no product data of a manufacturer (no folder \"M-xxxx\").");
	}

	private File zip(String name, String... entries) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(buffer)) {
			for (int n = 0; n < entries.length; n += 2) {
				out.putNextEntry(new ZipEntry(entries[n]));
				out.write(entries[n + 1].getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		File file = new File(Files.createTempDirectory("productdata").toFile(), name);
		Files.write(file.toPath(), buffer.toByteArray());
		file.deleteOnExit();
		file.getParentFile().deleteOnExit();
		return file;
	}

	private void assertRefused(String file, String expected) throws Exception {
		try {
			importProduct(file);
			fail("Not refused: " + file);
		} catch (TopLogicException ex) {
			assertEquals(expected,
				Resources.getInstance(Locale.ENGLISH).getString(ex.getErrorKey()));
		}
	}

	private static String en(String english, String german) {
		return Resources.getInstance().getLocale().getLanguage().equals("de") ? german : english;
	}

	private static String label(TLObject obj) {
		return MetaLabelProvider.INSTANCE.getLabel(obj);
	}

	private static String german(TLObject obj, String attribute) {
		return ResKeyUtil.getTranslation((ResKey) obj.tValueByName(attribute), Locale.GERMAN);
	}

	private static List<Object> list(TLObject obj, String part) {
		return new ArrayList<>((Collection<?>) obj.tValueByName(part));
	}

	private static int size(TLObject obj, String part) {
		return ((Collection<?>) obj.tValueByName(part)).size();
	}

	private static TLObject single(Object collection) {
		Collection<?> values = (Collection<?>) collection;
		assertEquals(1, values.size());
		return (TLObject) values.iterator().next();
	}

	private static List<TLObject> descendants(TLObject node) {
		List<TLObject> result = new ArrayList<>();
		List<TLObject> todo = new ArrayList<>(List.of(node));
		while (!todo.isEmpty()) {
			TLObject current = todo.remove(todo.size() - 1);
			result.add(current);
			for (String part : List.of("contents", "alternatives")) {
				if (current.tType().getPart(part) != null) {
					for (Object child : (Collection<?>) current.tValueByName(part)) {
						todo.add((TLObject) child);
					}
				}
			}
		}
		return result;
	}

	private static Map<String, Integer> counts() {
		Map<String, Integer> result = new LinkedHashMap<>();
		for (String type : COUNTED) {
			result.put(type, instances(type).size());
		}
		return result;
	}

	private void importProject(String file) throws Exception {
		Set<TLObject> before = new HashSet<>(instances("ehome.project:Project"));
		importSample(file);
		instances("ehome.project:Project").stream().filter(p -> !before.contains(p)).forEach(_createdProjects::add);
	}

	/**
	 * Imports the given product file in a transaction of its own.
	 */
	private static Importer importProduct(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importProductData(in);
				tx.commit();
			}
		}
		return importer;
	}

	private static Map<?, ?> importAction(String file) throws Exception {
		ImportProductDataAction action = new ImportProductDataAction(null, null);
		File source = new File(file);
		return (Map<?, ?>) action.execute(null,
			BinaryDataFactory.createBinaryDataWithName(source, source.getName()));
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProductDataImport.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
