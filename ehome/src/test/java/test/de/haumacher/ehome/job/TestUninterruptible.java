/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.job;

import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.base.context.TLSubSessionContext;
import com.top_logic.basic.SubSessionContext;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.service.UpdateEvent;
import com.top_logic.knowledge.service.db2.AbstractKBCache;
import com.top_logic.knowledge.service.db2.DBKnowledgeBase;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.job.Uninterruptible;

/**
 * The body of a job run with {@link Uninterruptible#run(java.util.concurrent.Callable)} runs in a
 * sub-session of its own for the person of the window that started it (TL #30216,
 * haumacher/ehome #107).
 *
 * <p>
 * The window is a sub-session of its own here, as the browser window of a user is; the job is
 * started in it, as <code>JobRunner</code> starts a job.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestUninterruptible extends BasicTestCase {

	/**
	 * A commit in the window while the job runs does not break the job's view of the database: the
	 * job fixes its revision at its first read, another thread commits in the window, and a KB cache
	 * the job builds afterwards (as one whose soft reference the garbage collector cleared) is built
	 * without "Session revision invalid".
	 */
	public void testCommitInWindowDuringJob() throws Exception {
		TLContext window = window(PersonManager.getManager().getRoot());
		DBKnowledgeBase kb = (DBKnowledgeBase) PersistencyLayer.getKnowledgeBase();
		AtomicReference<Throwable> commitProblem = new AtomicReference<>();
		Long value = inWindow(window, () -> Uninterruptible.run(() -> {
			// The job's interaction fixes its revision.
			kb.getSessionRevision();

			// A save in the same window, from another request.
			Thread request = new Thread(() -> {
				try {
					ThreadContextManager.inContext(window, TestUninterruptible::createAndDeleteProject);
				} catch (Throwable ex) {
					commitProblem.set(ex);
				}
			}, "Request of the window");
			request.start();
			request.join();

			TestCache cache = new TestCache(kb);
			try {
				return cache.getValue();
			} finally {
				cache.invalidate();
			}
		}));
		if (commitProblem.get() != null) {
			throw new AssertionError("The commit in the window failed.", commitProblem.get());
		}
		assertEquals(Long.valueOf(42), value);
	}

	/**
	 * The job runs in a sub-session other than the window's, with the window's person, locale and
	 * time zone, and with access checks on, as on the job's thread before.
	 */
	public void testIdentity() throws Exception {
		Person root = PersonManager.getManager().getRoot();
		TLContext window = window(root);
		Locale locale = Locale.GERMAN.equals(Resources.findBestLocale(root)) ? Locale.ENGLISH : Locale.GERMAN;
		TimeZone timeZone = TimeZone.getTimeZone("Pacific/Chatham");
		window.setCurrentLocale(locale);
		window.setCurrentTimeZone(timeZone);

		Object[] seen = inWindow(window, () -> Uninterruptible.run(() -> new Object[] {
			TLContextManager.getSubSession(),
			TLContext.currentUser(),
			ThreadContext.getLocale(),
			Resources.getInstance().getLocale(),
			ThreadContext.getTimeZone(),
			Boolean.valueOf(ThreadContext.isSecurityBypassed()),
		}));
		assertNotSame("A sub-session of its own.", window, seen[0]);
		assertNotNull(seen[0]);
		assertSame(root, seen[1]);
		assertEquals(locale, seen[2]);
		assertEquals(locale, seen[3]);
		assertEquals(timeZone, seen[4]);
		assertEquals("Access checks are on.", Boolean.FALSE, seen[5]);

		// The window is left as it was.
		assertSame(root, window.getPerson());
		assertEquals(locale, window.getCurrentLocale());
		assertEquals(timeZone, window.getCurrentTimeZone());
	}

	/**
	 * A sub-session without a person (a test in the system context) runs the job in itself, as
	 * before.
	 */
	public void testWithoutPerson() throws Exception {
		TLContext window = window(null);
		SubSessionContext seen = inWindow(window, () -> Uninterruptible.run(TLContextManager::getSubSession));
		assertSame(window, seen);
	}

	private static TLContext window(Person person) {
		TLContextManager manager = TLContextManager.getManager();
		TLContext window = (TLContext) manager.newSubSessionContext();
		window.setSessionContext(manager.newSessionContext());
		if (person != null) {
			window.setPerson(person);
		}
		return window;
	}

	/**
	 * Computes the given value in the given window on a thread without interaction, as a request of
	 * the window does.
	 */
	private static <T> T inWindow(TLSubSessionContext window, java.util.concurrent.Callable<T> computation)
			throws Exception {
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Throwable> problem = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				result.set(ThreadContextManager.inContext(window, computation::call));
			} catch (Throwable ex) {
				problem.set(ex);
			}
		}, "Request starting the job");
		thread.start();
		thread.join();
		Throwable ex = problem.get();
		if (ex instanceof Exception exception) {
			throw exception;
		}
		if (ex instanceof Error error) {
			throw error;
		}
		return result.get();
	}

	private static void createAndDeleteProject() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject project;
		try (Transaction tx = kb.beginTransaction()) {
			project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", "TL #30216");
			project.tUpdateByName("extId", "P-30216");
			tx.commit();
		}
		try (Transaction tx = kb.beginTransaction()) {
			project.tDelete();
			tx.commit();
		}
	}

	/**
	 * A KB cache that is built at its first access, as one is whose soft reference the garbage
	 * collector cleared.
	 */
	private static final class TestCache extends AbstractKBCache<Long> {

		private final DBKnowledgeBase _kb;

		TestCache(DBKnowledgeBase kb) {
			_kb = kb;
		}

		@Override
		protected DBKnowledgeBase kb() {
			return _kb;
		}

		@Override
		protected Long newGlobalCacheValue() {
			return Long.valueOf(42);
		}

		@Override
		protected Long adaptToTransaction(Long cacheValue) {
			return cacheValue;
		}

		@Override
		protected Long newGlobalCacheValue(Long globalCacheValue, UpdateEvent event) {
			return null;
		}

		@Override
		protected Long newLocalCacheValue() {
			return Long.valueOf(42);
		}

		@Override
		protected KBCacheValue<Long> newCache(long minValidity, Long cacheValue) {
			return new KBCacheValue<>(minValidity) {
				@Override
				public Long globalCacheValue() {
					return cacheValue;
				}
			};
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestUninterruptible.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
