/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.roundtrip;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKeyUtil;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * The round trip of the ETS samples as the completeness check of the import (haumacher/ehome #12):
 * import a sample, export it with {@link KnxprojExporter}, and compare the exported files with the
 * original ones ({@link KnxprojDiff}): apart from what <code>doc/knx/not-modeled.txt</code> lists,
 * there must be no difference. Then import the export again: the second project has the same objects
 * with the same external IDs as the first, the shared data (catalogs, programs, master data) gains
 * no object, and the download image of every device is the same.
 *
 * <p>
 * The export and the differences of each sample are written to <code>target/roundtrip/</code>.
 * </p>
 *
 * <p>
 * The samples are imported one after the other into a database emptied once (see
 * {@link #clearData()}), in a fixed order ({@link #SAMPLES}), the first test running them all; each
 * test reports the result of its sample. A sample whose application program is known already from an
 * earlier sample exports the program as it is stored: with the definition of the file that brought
 * it first, and the texts of the languages the later file added (haumacher/ehome #38). Of the file
 * of such a program only the texts of the added languages are compared (see
 * {@link #ownDifferences(List, Importer)}).
 * </p>
 *
 * <p>
 * The test runs first in the database of the suite: its setup starts the model service only, and the
 * test framework runs the tests with fewer services first (<code>TestUtils.rearrange</code>; no other
 * test of the database has this setup). The deletion then finds nothing to delete, and the programs
 * of the samples imported here stay for the tests after it, which reuse them. It is the longest test
 * of the suite, beyond the budget of a test class (5 s): the first import of the suite (which pays
 * for loading and compiling the import) and the imports of the programs of the samples into the
 * empty database. Run after other tests, the deletion of their shared data costs as much as
 * importing it.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRoundTrip extends BasicTestCase {

	/**
	 * The rules of the comparison, the machine-readable form of the "not modeled" list of
	 * <code>doc/knx/schema-coverage.md</code>.
	 */
	public static final Path NOT_MODELED = Path.of("../doc/knx/not-modeled.txt");

	private static final Path OUTPUT = Path.of("target/roundtrip");

	private static final String TEST = "samples/Test.knxproj";

	private static final String DEMO = "samples/EHomeDemo.knxproj";

	private static final String TESTSYSTEM = "samples/KNX-Testsystem.knxproj";

	/**
	 * The samples, in the order of their import into a database emptied before the first.
	 * <code>Test.knxproj</code> and <code>KNX-Testsystem.knxproj</code> have the same master data and
	 * the same files of the manufacturer M-0008 (catalog, hardware and the program of the
	 * Tastsensor), so the second finds its shared data as an import into an empty database creates
	 * it. The demo holds the same programs from files of another ETS installation: the Tastsensor's
	 * in English with German translations, where the test system's is German only. It adds the
	 * English texts to the known program, and its older master data leave the known mask versions as
	 * they are.
	 */
	private static final List<String> SAMPLES = List.of(TEST, TESTSYSTEM, DEMO);

	/**
	 * The program of the Tastsensor, which all samples hold.
	 */
	private static final String TASTSENSOR = "M-0008_A-10D7-11-A0E5-O000A";

	/**
	 * The failure of the round trip of each sample, <code>null</code> before the first test ran them.
	 */
	private static Map<String, Throwable> _failures;

	/**
	 * The languages the import of each sample added to known programs, by the program's ID.
	 */
	private static Map<String, Map<String, Set<String>>> _added = new TreeMap<>();

	public void testTest() throws Throwable {
		check(TEST);
	}

	public void testDemo() throws Throwable {
		check(DEMO);
	}

	/**
	 * The demo, imported after the Tastsensor's program is known in German only, adds the English
	 * texts and keeps the German ones (haumacher/ehome #38).
	 */
	public void testDemoAddsEnglish() throws Throwable {
		check(DEMO);
		assertEquals(Map.of(TASTSENSOR, Set.of("en")), _added.get(DEMO));
		assertEquals(Map.of(), _added.get(TESTSYSTEM));

		TLObject program = shared("ehome.operational:Program", TASTSENSOR);
		// German from Test.knxproj (the demo writes "komfort").
		assertTexts("Tastsensor 3 Komfort 3fach 10D711", "Push button sensor 3 comfort 3-gang 10D711",
			program, "name");
		assertTexts("Kanal 1 oder Kanal 2", "Channel 1 or channel 2",
			shared("ehome.operational:EnumerationValue", TASTSENSOR + "_PT-2Kanal.5FBedienkonzept_EN-0"), "name");
		TLObject function = shared("ehome.operational:Interface", TASTSENSOR + "_O-0_R-1031");
		assertTexts("Kanal 1 Schalten", "Channel 1 switching", function, "function");
		assertTexts("Wippe 1", "Rocker 1", shared("ehome.operational:Interface", TASTSENSOR + "_O-0_R-1662"), "name");
		assertTexts("Funktion_SNST", "SNST_function",
			shared("ehome.operational:ParameterView", TASTSENSOR + "_P-147_R-231"), "description");
	}

	private static TLObject shared(String type, String extId) {
		List<TLObject> result = instances(type).stream().filter(obj -> extId.equals(obj.tValueByName("extId"))).toList();
		assertEquals(extId, 1, result.size());
		return result.get(0);
	}

	private static void assertTexts(String german, String english, TLObject obj, String attribute) {
		ResKey value = (ResKey) obj.tValueByName(attribute);
		assertEquals(german, ResKeyUtil.getTranslation(value, Locale.GERMAN));
		assertEquals(english, ResKeyUtil.getTranslation(value, Locale.ENGLISH));
	}

	public void testTestsystem() throws Throwable {
		check(TESTSYSTEM);
	}

	private void check(String name) throws Throwable {
		if (_failures == null) {
			Map<String, Throwable> failures = new TreeMap<>();
			clearData();
			for (String sample : SAMPLES) {
				try {
					roundTrip(sample);
				} catch (Throwable ex) {
					failures.put(sample, ex);
				}
			}
			_failures = failures;
		}
		Throwable failure = _failures.get(name);
		if (failure != null) {
			throw failure;
		}
	}

	private void roundTrip(String sample) throws Exception {
		byte[] original = Files.readAllBytes(Path.of(sample));
		List<TLObject> projects = new ArrayList<>();
		try {
			Importer importer = importArchive(original, projects);
			_added.put(sample, added(importer));
			TLObject first = projects.get(projects.size() - 1);
			byte[] exported = export(first);
			String name = Path.of(sample).getFileName().toString().replace(".knxproj", "");
			Files.createDirectories(OUTPUT);
			Files.write(OUTPUT.resolve(name + ".knxproj"), exported);

			List<KnxprojDiff.Difference> differences = ownDifferences(diff(original, exported), importer);
			String summary = KnxprojDiff.summary(differences, 3);
			Files.writeString(OUTPUT.resolve(name + "-diff.txt"), summary + "\n" + all(differences),
				StandardCharsets.UTF_8);
			assertTrue("Round trip of " + sample + ": " + summary, differences.isEmpty());

			checkReimport(first, exported, projects);
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (TLObject project : projects) {
					if (project.tValid()) {
						project.tDelete();
					}
				}
				tx.commit();
			}
		}
	}

	/**
	 * The differences of a sample's round trip without those in the files of application programs
	 * known before its import, except for the texts of the languages the import added.
	 *
	 * <p>
	 * A known program keeps the definition of the file it was imported from first (ETS installations
	 * may write the same program differently: the Tastsensor's of the demo has other German texts,
	 * other names of two communication objects, and leaves out defaults that of the test system
	 * writes), and gains the texts of the languages it lacked (haumacher/ehome #38). Its own round
	 * trip is checked with the sample that brought it.
	 * </p>
	 */
	static List<KnxprojDiff.Difference> ownDifferences(List<KnxprojDiff.Difference> differences, Importer importer) {
		Map<String, Set<String>> known = new HashMap<>();
		Map<String, Set<String>> added = added(importer);
		for (TLObject program : importer.getKnown("ehome.operational:Program")) {
			String id = (String) program.tValueByName("extId");
			known.put(id.substring(0, id.indexOf('_')) + "/" + id + ".xml", added.getOrDefault(id, Set.of()));
		}
		List<KnxprojDiff.Difference> result = new ArrayList<>();
		for (KnxprojDiff.Difference difference : differences) {
			Set<String> languages = known.get(difference.file());
			if (languages == null || languages.stream().anyMatch(language -> difference.kind().startsWith("text ")
				&& difference.kind().endsWith("(" + language + ") differs"))) {
				result.add(difference);
			}
		}
		return result;
	}

	/**
	 * The languages the given import added to known programs, by the program's ID.
	 */
	private static Map<String, Set<String>> added(Importer importer) {
		Map<String, Set<String>> result = new TreeMap<>();
		for (Map.Entry<TLObject, Set<Locale>> entry : importer.getAddedLanguages().entrySet()) {
			Set<String> languages = new TreeSet<>();
			for (Locale locale : entry.getValue()) {
				languages.add(locale.getLanguage());
			}
			result.put((String) entry.getKey().tValueByName("extId"), languages);
		}
		return result;
	}

	/**
	 * Imports the export of the given project again and checks that the second project equals the
	 * first: the same objects with the same external IDs, no new shared objects, and the same
	 * download image of every device.
	 */
	static void checkReimport(TLObject first, byte[] exported, List<TLObject> projects) throws Exception {
		Map<String, Integer> countsBefore = counts();
		List<TLObject> firstContents = contents(first);
		Map<String, Integer> owned = owned(firstContents);
		List<String> ids = extIds(firstContents);
		Map<String, DownloadImage> images = images(firstContents);

		int imported = projects.size();
		TLObject second = importProject(exported, projects);
		assertEquals(imported + 1, projects.size());

		// The same objects with the same IDs, and nothing more of the shared data.
		List<TLObject> secondContents = contents(second);
		assertEquals(owned, owned(secondContents));
		assertEquals(ids, extIds(secondContents));
		Map<String, Integer> countsAfter = counts();
		for (Map.Entry<String, Integer> entry : countsAfter.entrySet()) {
			int expected = countsBefore.getOrDefault(entry.getKey(), 0) + owned.getOrDefault(entry.getKey(), 0);
			assertEquals("Number of " + entry.getKey() + " after importing the export.", expected,
				entry.getValue().intValue());
		}

		Map<String, DownloadImage> reimported = images(secondContents);
		assertEquals(images.keySet(), reimported.keySet());
		for (String device : images.keySet()) {
			assertSameImage(device, images.get(device), reimported.get(device));
		}
	}

	/**
	 * Deletes all projects and the shared data (master data, manufacturers with their catalogs,
	 * hardware and programs), so that a sample is imported as into a new installation.
	 *
	 * <p>
	 * The shared data is imported once and reused by later imports (by its IDs); a program known
	 * from another sample keeps the definition of the file it was first imported from, newer master
	 * data update the mask versions, and the export writes every known subtype of a datapoint type it
	 * uses. The round trip of the samples therefore starts from no shared data, in the order of
	 * {@link #SAMPLES}. The other tests delete only their projects (see <code>SharedData</code>);
	 * they must not rely on shared data imported by an earlier test.
	 * </p>
	 */
	static void clearData() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			List<KnowledgeItem> items = new ArrayList<>();
			for (String type : List.of("ehome.project:Project", "ehome.catalog:Manufacturer",
				"ehome.datapoint:DatapointMainType", "ehome.operational:MaskVersion")) {
				for (TLObject obj : instances(type)) {
					if (obj.tValid()) {
						items.add(obj.tHandle());
					}
				}
			}
			kb.deleteAll(items);
			tx.commit();
		}
	}

	/**
	 * Exports the given project to a byte array.
	 */
	public static byte[] export(TLObject project) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		KnxprojExporter.export(project, buffer);
		return buffer.toByteArray();
	}

	/**
	 * Compares an original archive with its export by the rules of {@link #NOT_MODELED}.
	 */
	public static List<KnxprojDiff.Difference> diff(byte[] original, byte[] exported) throws Exception {
		KnxprojDiff diff = new KnxprojDiff(KnxprojDiff.Rules.read(NOT_MODELED));
		return diff.compare(KnxprojDiff.unzip(original), KnxprojDiff.unzip(exported));
	}

	private static String all(List<KnxprojDiff.Difference> differences) {
		StringBuilder out = new StringBuilder();
		for (KnxprojDiff.Difference difference : differences) {
			out.append(difference).append('\n');
		}
		return out.toString();
	}

	/**
	 * Imports the given archive and returns the project it created, adding it to the given list.
	 */
	public static TLObject importProject(byte[] archive, List<TLObject> projects) throws Exception {
		importArchive(archive, projects);
		return projects.get(projects.size() - 1);
	}

	/**
	 * Imports the given archive, adding the project it created to the given list, and returns the
	 * importer, which tells what the import found known.
	 */
	public static Importer importArchive(byte[] archive, List<TLObject> projects) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new ByteArrayInputStream(archive)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		List<TLObject> created = new ArrayList<>(instances("ehome.project:Project"));
		created.removeAll(before);
		assertEquals(1, created.size());
		projects.add(created.get(0));
		for (BufferingI18NLog.Entry entry : importer.getMessages()) {
			assertFalse(ResourcesModule.getInstance().getBundle(Locale.ENGLISH).getString(entry.getMessage()),
				entry.getLevel().is(Level.ERROR));
		}
		return importer;
	}

	/**
	 * The number of instances per class of the E-Home modules.
	 */
	private static Map<String, Integer> counts() {
		Map<String, Integer> result = new TreeMap<>();
		for (TLModule module : ModelService.getApplicationModel().getModules()) {
			if (!module.getName().startsWith("ehome.")) {
				continue;
			}
			for (TLClass type : module.getClasses()) {
				int count = MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class).size();
				if (count > 0) {
					result.put(TLModelUtil.qualifiedName(type), count);
				}
			}
		}
		return result;
	}

	/**
	 * The number of instances per class of the given contents of a project.
	 */
	private static Map<String, Integer> owned(List<TLObject> contents) {
		Map<String, Integer> result = new TreeMap<>();
		for (TLObject obj : contents) {
			result.merge(TLModelUtil.qualifiedName(obj.tType()), 1, Integer::sum);
		}
		return result;
	}

	/**
	 * The external IDs of the given contents of a project, sorted, with their types.
	 */
	private static List<String> extIds(List<TLObject> contents) {
		List<String> result = new ArrayList<>();
		for (TLObject obj : contents) {
			if (obj.tType().getPart("extId") != null) {
				result.add(TLModelUtil.qualifiedName(obj.tType()) + " " + obj.tValueByName("extId"));
			}
		}
		result.sort(null);
		return result;
	}

	/**
	 * The objects within the given project, the project included: the parts reached from it by
	 * composite references (whose container chain leads to the project).
	 */
	private static List<TLObject> contents(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		ArrayDeque<TLObject> todo = new ArrayDeque<>(List.of(project));
		while (!todo.isEmpty()) {
			TLObject obj = todo.removeFirst();
			if (TLModelUtil.qualifiedName(obj.tType()).startsWith("ehome.")) {
				result.add(obj);
			}
			for (TLStructuredTypePart part : obj.tType().getAllParts()) {
				if (part instanceof TLReference reference && reference.isComposite() && !reference.isBackwards()
					&& !reference.isDerived()) {
					Object value = obj.tValue(reference);
					if (value instanceof Collection<?> parts) {
						for (Object element : parts) {
							todo.add((TLObject) element);
						}
					} else if (value != null) {
						todo.add((TLObject) value);
					}
				}
			}
		}
		return result;
	}

	private static Map<String, DownloadImage> images(List<TLObject> contents) {
		Map<String, DownloadImage> result = new TreeMap<>();
		for (TLObject obj : contents) {
			if (TLModelUtil.qualifiedName(obj.tType()).equals("ehome.physical:Device")) {
				result.put((String) obj.tValueByName("extId"), DownloadImage.compute(obj));
			}
		}
		return result;
	}

	private static void assertSameImage(String device, DownloadImage expected, DownloadImage actual) {
		assertEquals("Download image of " + device, expected.dump(), actual.dump());
		List<Block> left = expected.getBlocks();
		List<Block> right = actual.getBlocks();
		assertEquals(left.size(), right.size());
		for (int n = 0; n < left.size(); n++) {
			Block a = left.get(n);
			Block b = right.get(n);
			assertEquals(a.name(), b.name());
			assertEquals(a.address(), b.address());
			assertEquals(a.stateMachine(), b.stateMachine());
			assertTrue("Data of " + a.name() + " of " + device, Arrays.equals(a.data(), b.data()));
			assertTrue("Mask of " + a.name() + " of " + device, Arrays.equals(a.mask(), b.mask()));
		}
	}

	public static Test suite() {
		// The model service only: the test runs first in the database (see the class comment).
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRoundTrip.class,
			ModelService.Module.INSTANCE));
	}

}
