/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import junit.framework.Assert;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;

/**
 * Helpers for tests that share one database with the other tests of the suite (haumacher/ehome #36).
 *
 * <p>
 * The data shared between projects (master data, manufacturers with their catalogs and programs)
 * stays in the database: it is imported once per test run, and a further import of a sample reuses
 * it (an import into an empty database costs seconds per program, a further import about one). A
 * test deletes only what it created, its projects, and asserts the state it is interested in, the
 * objects of its project or the shared objects by their IDs, never the numbers of a fresh database.
 * </p>
 *
 * <p>
 * Which shared data the database holds depends on the tests that ran before. A test that needs a
 * specific state of it establishes that state itself: {@link #resetMaskVersions(byte[])} for the mask
 * versions of the master data of one file, and <code>TestRoundTrip.clearData()</code> (the only full
 * deletion) for the round trip, which compares an export with the original file.
 * </p>
 */
public final class SharedData {

	private SharedData() {
		// Static helpers.
	}

	/**
	 * The projects that exist now and not in the given list.
	 */
	public static List<TLObject> newProjects(Collection<TLObject> before) {
		Set<TLObject> known = new HashSet<>(before);
		List<TLObject> result = new ArrayList<>();
		for (TLObject project : instances("ehome.project:Project")) {
			if (!known.contains(project)) {
				result.add(project);
			}
		}
		return result;
	}

	/**
	 * Deletes the given projects that still exist, in a transaction of their own.
	 */
	public static void deleteProjects(Collection<TLObject> projects) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			for (TLObject project : projects) {
				if (project.tValid()) {
					project.tDelete();
				}
			}
			tx.commit();
		}
	}

	/**
	 * The objects of the given type within one of the given projects.
	 */
	public static List<TLObject> within(String typeName, Collection<TLObject> projects) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject obj : instances(typeName)) {
			if (isWithin(obj, projects)) {
				result.add(obj);
			}
		}
		return result;
	}

	/**
	 * Whether the given object is (transitively) part of one of the given projects.
	 */
	public static boolean isWithin(TLObject obj, Collection<TLObject> projects) {
		for (TLObject current = obj; current != null; current = current.tContainer()) {
			if (projects.contains(current)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The IDs of the elements with the given tag in the entries of the given project file whose names
	 * match the given pattern, e.g. the datapoint types of its master data:
	 * <code>ids(file, "knx_master.xml", "DatapointType")</code>.
	 */
	public static Set<String> ids(String file, String entryPattern, String tag) throws IOException {
		Pattern id = Pattern.compile("<" + tag + " Id=\"([^\"]+)\"");
		Set<String> result = new TreeSet<>();
		try (ZipFile zip = new ZipFile(file)) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().matches(entryPattern)) {
					continue;
				}
				try (InputStream in = zip.getInputStream(entry)) {
					Matcher matcher = id.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
					while (matcher.find()) {
						result.add(matcher.group(1));
					}
				}
			}
		}
		return result;
	}

	/**
	 * Asserts that the database holds exactly one object of the given type with each of the given
	 * IDs: the import created the shared objects of a file and duplicated none.
	 */
	public static void assertOnce(String typeName, Collection<String> ids) {
		Map<String, Integer> counts = new HashMap<>();
		for (TLObject obj : instances(typeName)) {
			counts.merge(String.valueOf(obj.tValueByName("extId")), 1, Integer::sum);
		}
		Assert.assertFalse("No " + typeName + " expected.", ids.isEmpty());
		for (String id : ids) {
			Assert.assertEquals("Number of " + typeName + " " + id + ".", Integer.valueOf(1), counts.get(id));
		}
	}

	/**
	 * Gives the mask versions the master data of the given project file define the definition and the
	 * version of these master data, as an import of the file into an empty database creates them.
	 *
	 * <p>
	 * Newer master data update a known mask version in place and older ones keep it (haumacher/ehome
	 * #34), so the mask versions in the database depend on the files imported before. Of the master
	 * data, the download of a device depends on the mask versions only. The reset forgets the version
	 * the file's mask versions were imported from and imports the file's master data again: master
	 * data of a known version update a mask version of an unknown one in place
	 * (<code>MasterDataUpdates</code>), keeping its identity and the references of the programs to it.
	 * Mask versions the file does not define are left as they are. Only the mask versions the file
	 * defines differently are changed, which costs a fraction of a second; deleting the shared data (to
	 * import the file into an empty database) costs as much as importing its programs again.
	 * </p>
	 */
	public static void resetMaskVersions(byte[] projectFile) throws Exception {
		byte[] masterData = masterData(projectFile);
		Set<String> defined = maskIds(masterData);
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			for (TLObject mask : instances("ehome.operational:MaskVersion")) {
				if (defined.contains(mask.tValueByName("extId"))) {
					mask.tUpdateByName("masterDataVersion", null);
				}
			}
			tx.commit();
		}

		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new ByteArrayInputStream(masterData)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		for (TLObject mask : instances("ehome.operational:MaskVersion")) {
			if (defined.contains(mask.tValueByName("extId"))) {
				Assert.assertNotNull("Mask version " + mask.tValueByName("extId") + " not reset.",
					mask.tValueByName("masterDataVersion"));
			}
		}
	}

	/**
	 * The IDs of the mask versions in the given archive holding master data.
	 */
	private static Set<String> maskIds(byte[] masterData) throws IOException {
		Pattern id = Pattern.compile("<MaskVersion Id=\"([^\"]+)\"");
		Set<String> result = new HashSet<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(masterData))) {
			while (in.getNextEntry() != null) {
				Matcher matcher = id.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				while (matcher.find()) {
					result.add(matcher.group(1));
				}
			}
		}
		return result;
	}

	/**
	 * An archive holding only the master data (<code>knx_master.xml</code>) of the given project file.
	 */
	private static byte[] masterData(byte[] projectFile) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(projectFile));
				ZipOutputStream out = new ZipOutputStream(buffer)) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (entry.getName().equals("knx_master.xml")) {
					out.putNextEntry(new ZipEntry(entry.getName()));
					out.write(in.readAllBytes());
					out.closeEntry();
				}
			}
		}
		return buffer.toByteArray();
	}

}
