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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import junit.framework.Assert;

import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.event.ChangeSet;
import com.top_logic.knowledge.event.ItemChange;
import com.top_logic.knowledge.event.ItemEvent;
import com.top_logic.knowledge.event.ItemUpdate;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.objects.identifier.ObjectBranchId;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.service.UpdateEvent;
import com.top_logic.knowledge.service.event.CommitChecker;
import com.top_logic.knowledge.service.event.CommitVetoException;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.core.Externalized;
import de.haumacher.ehome.model.operational.MaskVersion;

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
 *
 * <p>
 * A test that only reads its project uses a project imported once per database and file,
 * {@link #sharedProject(String)}, instead of importing the file itself (haumacher/ehome #213): the
 * project exists from the first test asking for it to the end of the database, and no test changes or
 * deletes it. A guard of the database refuses every commit that touches a shared project, and the test
 * that tried it fails (see {@link SharedProjectTestCase}). A test that changes its project imports its
 * own and deletes it.
 * </p>
 */
public final class SharedData {

	private SharedData() {
		// Static helpers.
	}

	/** The type of the projects. */
	private static final String PROJECT = "ehome.project:Project";

	/**
	 * A project imported once into a database, by its key and the revision it was created in.
	 */
	private record Shared(KnowledgeBase kb, ObjectKey key, long created) {
		// Pure data.
	}

	/** The shared projects, by the file they are imported from. */
	private static final Map<String, Shared> SHARED = new HashMap<>();

	/** The guard of the shared projects in the current database, <code>null</code> before the first. */
	private static Guard _guard;

	/** The number of commits refused by the guards of all databases so far. */
	private static int _vetoes;

	/** The last change refused, <code>null</code> before the first. */
	private static String _lastChange;

	/**
	 * The project imported from the given ETS project file for the tests that only read it: imported
	 * on the first call in the current database, the same project on every later call.
	 *
	 * <p>
	 * It is imported lazily, so that it exists only after <code>TestRoundTrip</code>, which empties the
	 * E-Home data of the database before its samples (see <code>TestRoundTrip.clearData()</code>), and
	 * only in a database that has tests using it. Its objects (the project and everything it is
	 * composed of, transitively) are guarded: the database refuses a commit that creates, changes or
	 * deletes one of them, or that creates, changes or deletes any object with a value naming one of
	 * them (a new part names its container, a link names its ends). The commit fails with a
	 * {@link CommitVetoException}, and the test with it, or, if the test does not see the failure, by
	 * {@link SharedProjectTestCase}. So no test changes the project, and every test reads it as
	 * imported.
	 * </p>
	 *
	 * <p>
	 * The guard is complete: a change of the stored project is a commit, every commit passes the
	 * commit checkers of the database with all its created, changed and deleted objects and their
	 * values, and a change of the project either changes or deletes one of its objects (known by their
	 * keys after the import), or creates, changes or deletes an object with a value naming one of them.
	 * </p>
	 */
	public static synchronized TLObject sharedProject(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Shared shared = SHARED.get(file);
		TLObject project = shared == null ? null : resolve(kb, shared);
		if (project != null) {
			return project;
		}
		if (shared != null) {
			// A new database: the projects and the guard of the former one are forgotten.
			SHARED.clear();
			if (_guard != null) {
				_guard.clear();
			}
		}
		List<TLObject> before = instances(PROJECT);
		TestEtsImport.importSample(file);
		List<TLObject> created = newProjects(before);
		Assert.assertEquals("Projects imported from " + file + ".", 1, created.size());
		project = created.get(0);
		SHARED.put(file, new Shared(kb, project.tId(), project.tHandle().getCreateCommitNumber()));
		guard(kb).protect(project);
		return project;
	}

	private static TLObject resolve(KnowledgeBase kb, Shared shared) {
		if (shared.kb() != kb) {
			return null;
		}
		KnowledgeItem item = kb.resolveObjectKey(shared.key());
		if (item == null || item.getCreateCommitNumber() != shared.created()) {
			return null;
		}
		TLObject result = item.getWrapper();
		return result != null && result.tValid() ? result : null;
	}

	private static Guard guard(KnowledgeBase kb) {
		if (_guard == null || _guard.getKnowledgeBase() != kb) {
			_guard = new Guard(kb);
			kb.addCommitChecker(_guard);
		}
		return _guard;
	}

	/**
	 * The number of commits refused so far because they touched a shared project, see
	 * {@link #sharedProject(String)}.
	 */
	public static synchronized int sharedProjectVetoes() {
		return _vetoes;
	}

	/**
	 * The last change of a shared project refused, <code>null</code> if none was.
	 */
	public static synchronized String lastSharedProjectChange() {
		return _lastChange;
	}

	private static synchronized void refused(String change) {
		_vetoes++;
		_lastChange = change;
	}

	/**
	 * Refuses every commit that touches one of the objects of the shared projects, see
	 * {@link SharedData#sharedProject(String)}.
	 */
	private static final class Guard implements CommitChecker {

		private final KnowledgeBase _kb;

		private final Set<ObjectBranchId> _protected = ConcurrentHashMap.newKeySet();

		Guard(KnowledgeBase kb) {
			_kb = kb;
		}

		KnowledgeBase getKnowledgeBase() {
			return _kb;
		}

		void clear() {
			_protected.clear();
		}

		/**
		 * Guards the given project and everything it is composed of.
		 */
		void protect(TLObject project) {
			for (TLObject obj : composition(project)) {
				_protected.add(ObjectBranchId.toObjectBranchId(obj.tId()));
			}
		}

		@Override
		public void checkCommit(UpdateEvent event) throws CommitVetoException {
			if (_protected.isEmpty()) {
				return;
			}
			ChangeSet changes = event.getChanges();
			String touched = touched(changes.getCreations(), "created");
			if (touched == null) {
				touched = touched(changes.getUpdates(), "changed");
			}
			if (touched == null) {
				touched = touched(changes.getDeletions(), "deleted");
			}
			if (touched != null) {
				refused(touched);
				throw new CommitVetoException("A test changed a shared project (SharedData.sharedProject): "
					+ touched, event, this);
			}
		}

		private String touched(List<? extends ItemEvent> events, String kind) {
			for (ItemEvent evt : events) {
				if (_protected.contains(evt.getObjectId())) {
					return kind + " " + evt.getObjectId();
				}
				if (evt instanceof ItemChange change) {
					String value = referring(change.getValues());
					if (value == null && change instanceof ItemUpdate update) {
						value = referring(update.getOldValues());
					}
					if (value != null) {
						return kind + " " + evt.getObjectId() + " referring to " + value;
					}
				}
			}
			return null;
		}

		private String referring(Map<String, Object> values) {
			if (values == null) {
				return null;
			}
			for (Map.Entry<String, Object> entry : values.entrySet()) {
				ObjectBranchId id = id(entry.getValue());
				if (id != null && _protected.contains(id)) {
					return id + " by " + entry.getKey();
				}
			}
			return null;
		}

		private static ObjectBranchId id(Object value) {
			if (value instanceof ObjectBranchId id) {
				return id;
			}
			if (value instanceof ObjectKey key) {
				return ObjectBranchId.toObjectBranchId(key);
			}
			if (value instanceof KnowledgeItem item) {
				return ObjectBranchId.toObjectBranchId(item.tId());
			}
			return null;
		}
	}

	/**
	 * The given object and every object it is composed of, transitively.
	 */
	public static List<TLObject> composition(TLObject root) {
		List<TLObject> result = new ArrayList<>();
		Map<TLClass, List<TLReference>> composites = new HashMap<>();
		Deque<TLObject> todo = new ArrayDeque<>();
		todo.add(root);
		while (!todo.isEmpty()) {
			TLObject obj = todo.removeFirst();
			result.add(obj);
			TLClass type = (TLClass) obj.tType();
			for (TLReference reference : composites.computeIfAbsent(type, SharedData::compositeReferences)) {
				Object value = obj.tValue(reference);
				if (value instanceof Iterable<?> parts) {
					for (Object part : parts) {
						todo.add((TLObject) part);
					}
				} else if (value != null) {
					todo.add((TLObject) value);
				}
			}
		}
		return result;
	}

	private static List<TLReference> compositeReferences(TLClass type) {
		List<TLReference> result = new ArrayList<>();
		for (TLStructuredTypePart part : type.getAllParts()) {
			if (part instanceof TLReference reference && reference.isComposite() && !reference.isBackwards()
				&& !reference.isDerived()) {
				result.add(reference);
			}
		}
		return result;
	}

	/**
	 * The projects that exist now and not in the given list.
	 */
	public static List<TLObject> newProjects(Collection<TLObject> before) {
		Set<TLObject> known = new HashSet<>(before);
		List<TLObject> result = new ArrayList<>();
		for (TLObject project : instances(PROJECT)) {
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
		// Another thread may still commit to the project's parts (the controller connections' writer
		// records closes after the test saw them): the deletion refetches first and retries a conflict.
		for (int attempt = 1;; attempt++) {
			try {
				kb.refetch();
			} catch (com.top_logic.knowledge.service.RefetchTimeout ex) {
				throw new RuntimeException("refetch before deleting the test projects timed out", ex);
			}
			try (Transaction tx = kb.beginTransaction()) {
				for (TLObject project : projects) {
					if (project.tValid()) {
						project.tDelete();
					}
				}
				tx.commit();
				return;
			} catch (com.top_logic.knowledge.service.merge.MergeConflictException ex) {
				if (attempt == 3) {
					throw ex;
				}
				try {
					Thread.sleep(200L * attempt);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw ex;
				}
			}
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
			counts.merge(String.valueOf(((Externalized) obj).getExtId()), 1, Integer::sum);
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
			for (TLObject obj : instances("ehome.operational:MaskVersion")) {
				MaskVersion mask = (MaskVersion) obj;
				if (defined.contains(mask.getExtId())) {
					mask.setMasterDataVersion(null);
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
		for (TLObject obj : instances("ehome.operational:MaskVersion")) {
			MaskVersion mask = (MaskVersion) obj;
			if (defined.contains(mask.getExtId())) {
				Assert.assertNotNull("Mask version " + mask.getExtId() + " not reset.",
					mask.getMasterDataVersion());
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
