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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
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
 */
@SuppressWarnings("javadoc")
public class TestRoundTrip extends BasicTestCase {

	/**
	 * The rules of the comparison, the machine-readable form of the "not modeled" list of
	 * <code>doc/knx/schema-coverage.md</code>.
	 */
	public static final Path NOT_MODELED = Path.of("doc/knx/not-modeled.txt");

	private static final Path OUTPUT = Path.of("target/roundtrip");

	public void testTest() throws Exception {
		roundTrip("samples/Test.knxproj");
	}

	public void testDemo() throws Exception {
		roundTrip("samples/EHomeDemo.knxproj");
	}

	public void testTestsystem() throws Exception {
		roundTrip("samples/KNX-Testsystem.knxproj");
	}

	private void roundTrip(String sample) throws Exception {
		byte[] original = Files.readAllBytes(Path.of(sample));
		clearData();
		List<TLObject> projects = new ArrayList<>();
		try {
			TLObject first = importProject(original, projects);
			byte[] exported = export(first);
			String name = Path.of(sample).getFileName().toString().replace(".knxproj", "");
			Files.createDirectories(OUTPUT);
			Files.write(OUTPUT.resolve(name + ".knxproj"), exported);

			List<KnxprojDiff.Difference> differences = diff(original, exported);
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
	 * Imports the export of the given project again and checks that the second project equals the
	 * first: the same objects with the same external IDs, no new shared objects, and the same
	 * download image of every device.
	 */
	static void checkReimport(TLObject first, byte[] exported, List<TLObject> projects) throws Exception {
		Map<String, Integer> countsBefore = counts();
		Map<String, Integer> owned = owned(first);
		List<String> ids = extIds(first);
		Map<String, DownloadImage> images = images(first);

		int imported = projects.size();
		TLObject second = importProject(exported, projects);
		assertEquals(imported + 1, projects.size());

		// The same objects with the same IDs, and nothing more of the shared data.
		assertEquals(owned, owned(second));
		assertEquals(ids, extIds(second));
		Map<String, Integer> countsAfter = counts();
		for (Map.Entry<String, Integer> entry : countsAfter.entrySet()) {
			int expected = countsBefore.getOrDefault(entry.getKey(), 0) + owned.getOrDefault(entry.getKey(), 0);
			assertEquals("Number of " + entry.getKey() + " after importing the export.", expected,
				entry.getValue().intValue());
		}

		Map<String, DownloadImage> reimported = images(second);
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
	 * from another sample keeps the texts and names of the file it was first imported from (ETS
	 * writes the texts of a program in the language of its installation). The round trip of a sample
	 * therefore needs the shared data of that sample. This test must remain the last one to use the
	 * E-Home data of the shared test database.
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
		return created.get(0);
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
	 * The number of instances per class within the given project.
	 */
	private static Map<String, Integer> owned(TLObject project) {
		Map<String, Integer> result = new TreeMap<>();
		for (TLObject obj : contents(project)) {
			result.merge(TLModelUtil.qualifiedName(obj.tType()), 1, Integer::sum);
		}
		return result;
	}

	/**
	 * The external IDs of the objects within the given project, sorted, with their types.
	 */
	private static List<String> extIds(TLObject project) {
		List<String> result = new ArrayList<>();
		for (TLObject obj : contents(project)) {
			if (obj.tType().getPart("extId") != null) {
				result.add(TLModelUtil.qualifiedName(obj.tType()) + " " + obj.tValueByName("extId"));
			}
		}
		result.sort(null);
		return result;
	}

	private static List<TLObject> contents(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (TLModule module : ModelService.getApplicationModel().getModules()) {
			if (!module.getName().startsWith("ehome.")) {
				continue;
			}
			for (TLClass type : module.getClasses()) {
				for (TLObject obj : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
					if (isWithin(obj, project)) {
						result.add(obj);
					}
				}
			}
		}
		return result;
	}

	private static boolean isWithin(TLObject obj, TLObject project) {
		for (TLObject current = obj; current != null; current = current.tContainer()) {
			if (current == project) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, DownloadImage> images(TLObject project) {
		Map<String, DownloadImage> result = new TreeMap<>();
		for (TLObject obj : contents(project)) {
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRoundTrip.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
