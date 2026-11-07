/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.db2.DBKnowledgeBase;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;

/**
 * The image of a project the checks of the rules and schedules and the datapoint lists of their
 * editors read (haumacher/ehome #89): built once per state of the data and reused by every field
 * check ({@link RuleExport#fieldProblem(Object, String, Object, boolean)}), {@link RuleFunctions#datapoints(TLObject)}
 * and the option lists, instead of once per field and check.
 *
 * <p>
 * An {@link Entry} holds the image without rules and schedules
 * ({@link RuntimeImage#build(TLObject, long, Instant, boolean)}), the rules of the project as the
 * image exports them ({@link RuleExport#rules(TLObject)}), the {@link ImageValidator.Directory} of
 * both, and values derived from them ({@link Entry#derived(Object, Function)}). It is valid for:
 * </p>
 * <ul>
 * <li>the project,</li>
 * <li>the last revision of the knowledge base ({@link com.top_logic.knowledge.service.HistoryManager#getLastRevision()}):
 * a commit by anyone invalidates every entry (the revision is read before the build, so a build that
 * sees a newer state is only rebuilt once more),</li>
 * <li>the reading person and whether the security checks are bypassed and the thread runs in a system
 * context: the build reads the model through the reader's access rights (a derived attribute or a
 * label it reaches is computed with them and reads as <code>null</code> without a person outside the
 * system context, CLAUDE.md), so its result may differ between readers even where it does not today;
 * the rights themselves (roles, group memberships) are data of the knowledge base and change with its
 * revision,</li>
 * <li>the locale of the reader ({@link Resources#getLocale()}): the build and the datapoint list
 * resolve labels and notes in it.</li>
 * </ul>
 * <p>
 * The build reads no clock and no time zone of the reader (the image is built for
 * {@link Instant#EPOCH}, the time zone is the project's setting). A cached entry never answers for
 * another person, rights context or locale.
 * </p>
 *
 * <p>
 * While the current thread has changes not yet committed (a check during a commit, an import), the
 * stored state differs from the revision's: the entry is built fresh and not cached. The values the
 * form of a field shows but has not stored do not enter an entry at all; the checks put them over the
 * entry themselves (<code>RuleExport._overlay</code>).
 * </p>
 *
 * <p>
 * The values of an entry are shared between threads and must not be changed.
 * </p>
 */
public final class ImageCache {

	/** How many entries are kept (the least recently used one is dropped first). */
	private static final int CAPACITY = 32;

	private static final AtomicLong BUILDS = new AtomicLong();

	private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
			return size() > CAPACITY;
		}
	};

	private ImageCache() {
		// Static only.
	}

	/**
	 * The entry of the project for the current state of the data and the current reader.
	 */
	public static Entry get(TLObject project) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		if (!(kb instanceof DBKnowledgeBase db) || db.hasUncommittedChanges() || project.tId() == null) {
			return new Entry(project, -1);
		}
		long revision = kb.getHistoryManager().getLastRevision();
		Key key = key(project);
		synchronized (CACHE) {
			Entry cached = CACHE.get(key);
			if (cached != null && cached._revision == revision) {
				return cached;
			}
		}
		Entry result = new Entry(project, revision);
		synchronized (CACHE) {
			Entry cached = CACHE.get(key);
			if (cached == null || cached._revision < revision) {
				CACHE.put(key, result);
			}
		}
		return result;
	}

	/**
	 * How many entries have been built (each one image build): for tests.
	 */
	public static long builds() {
		return BUILDS.get();
	}

	private static Key key(TLObject project) {
		Person person = TLContext.currentUser();
		return new Key(project.tId(), person == null ? null : person.tId(), ThreadContext.isSecurityBypassed(),
			ThreadContext.isSystemContext(), Resources.getInstance().getLocale());
	}

	/** What an {@link Entry} is valid for besides the revision. */
	private record Key(ObjectKey project, ObjectKey person, boolean bypassed, boolean system, Locale locale) {
		// Value only.
	}

	/**
	 * The image of a project in one state of the data for one reader.
	 */
	public static final class Entry {

		private final long _revision;

		private final RuntimeImage _image;

		private final List<Object> _rules;

		private final ImageValidator.Directory _directory;

		private final Map<Object, Object> _derived = new ConcurrentHashMap<>();

		Entry(TLObject project, long revision) {
			BUILDS.incrementAndGet();
			_revision = revision;
			_image = RuntimeImage.build(project, 0, Instant.EPOCH, false);
			_rules = new RuleExport().rules(project);
			// With the rules of the project: they imply the datapoints of their parameters (spec/rules.md 8.6).
			_directory = ImageValidator.directory(_image.getText(), _rules);
		}

		/**
		 * The image of the project without its rules and schedules.
		 */
		public RuntimeImage image() {
			return _image;
		}

		/**
		 * The rules of the project as the image exports them (<code>null</code> for none).
		 */
		public List<Object> rules() {
			return _rules;
		}

		/**
		 * The datapoints of the image and of the rules' parameters as the loaders see them.
		 */
		public ImageValidator.Directory directory() {
			return _directory;
		}

		/**
		 * A value derived from this entry, computed once.
		 *
		 * @param key
		 *        What the value is, unique among the values derived from an entry (a constant of the
		 *        caller).
		 * @param computation
		 *        Computes the value; it reads only this entry and the committed state of the project.
		 */
		@SuppressWarnings("unchecked")
		public <T> T derived(Object key, Function<Entry, T> computation) {
			return (T) _derived.computeIfAbsent(key, k -> computation.apply(this));
		}
	}

}
