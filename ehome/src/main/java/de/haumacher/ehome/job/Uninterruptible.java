/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.job;

import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.top_logic.base.context.TLSubSessionContext;
import com.top_logic.basic.Logger;
import com.top_logic.basic.SubSessionContext;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.layout.react.scripting.ReactWindowReplay;
import com.top_logic.util.TLContextManager;

/**
 * Keeps the interrupt of a cancelled job away from its database access (TL #30196).
 *
 * <p>
 * A cancelled <code>&lt;start-job&gt;</code> is woken by an interrupt of its worker thread
 * (<code>JobRunner#cancel</code>). An interrupt in the middle of a database access - a write or a
 * read - closes the file channel of the embedded H2 database, which then fails for the whole
 * application until a restart. Every cancelable job of the app that touches the knowledge base
 * therefore runs its body with {@link #run(Callable)}: the body runs on a thread of its own, the
 * worker only waits for it, and the interrupt the worker receives is kept as a cancel request instead
 * of reaching the body.
 * </p>
 *
 * <p>
 * The body runs in a sub-session of its own for the person of the window that started the job, with
 * the window's locale and time zone, not in the window's sub-session: a commit in the window while
 * the job runs would otherwise break the job's view of the database (TL #30216).
 * </p>
 *
 * <p>
 * The body answers a cancel at its next report to the job's monitor, as before (every report checks
 * the cancel flag of the job). A blocking bus access, which reports nothing while it waits, declares
 * itself with {@link #interruptible()}: only there the body's thread is interrupted, at
 * once if the cancel arrives while it waits, or when it enters such an access after the cancel. On
 * leaving it, the interrupt status is cleared again, so that no database access after it is hit.
 * </p>
 *
 * <p>
 * Callers: {@link de.haumacher.ehome.etsImport.ImportEtsProjectJob},
 * {@link de.haumacher.ehome.etsImport.ImportProductDataJob} (whole import),
 * {@link de.haumacher.ehome.scan.ScanJob}, {@link de.haumacher.ehome.scan.ReadBackJob},
 * {@link de.haumacher.ehome.programming.download.CompareJob} (whole body; their bus accesses in
 * {@link de.haumacher.ehome.scan.CalimeroScanBus} and
 * {@link de.haumacher.ehome.programming.download.CalimeroReader} are interruptible).
 * </p>
 */
public final class Uninterruptible {

	/**
	 * The shield of the current thread, <code>null</code> outside {@link #run(Callable)}.
	 */
	private static final ThreadLocal<Shield> SHIELD = new ThreadLocal<>();

	/**
	 * The time in milliseconds a finished job waits at most for the window to bring it up to date
	 * with what the job committed, see {@link #inOwnSubSession(SubSessionContext, Callable)}.
	 */
	private static final long CATCH_UP_TIMEOUT = 10_000;

	private Uninterruptible() {
		// Static helpers.
	}

	/**
	 * The section of a blocking access a cancelled job may interrupt, see
	 * {@link Uninterruptible#interruptible()}; closing it ends the section.
	 */
	public interface Section extends AutoCloseable {

		@Override
		void close();
	}

	private static final Section NONE = () -> {
		// Outside a shield: nothing to do.
	};

	/**
	 * Runs the given work of a job where the interrupt of a cancelled job does not reach it, and
	 * waits for it.
	 *
	 * @param work
	 *        The body of the job.
	 * @return What the work returned.
	 * @throws Exception
	 *         What the work threw (a cancelled job's <code>AbortExecutionException</code> among it).
	 */
	public static <T> T run(Callable<T> work) throws Exception {
		SubSessionContext subSession = ThreadContextManager.getSubSession();
		Shield shield = new Shield();
		CompletableFuture<T> result = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			SHIELD.set(shield);
			try {
				result.complete(inOwnSubSession(subSession, work));
			} catch (Throwable ex) {
				result.completeExceptionally(ex);
			} finally {
				SHIELD.remove();
			}
		}, "E-Home job");
		thread.setDaemon(true);
		shield.setThread(thread);
		thread.start();
		while (true) {
			try {
				return result.get();
			} catch (InterruptedException ex) {
				// Cancelled: the work stops at its next report, or in its blocking bus access.
				shield.cancel();
			} catch (ExecutionException ex) {
				Throwable cause = ex.getCause();
				if (cause instanceof Exception exception) {
					throw exception;
				}
				if (cause instanceof Error error) {
					throw error;
				}
				throw ex;
			}
		}
	}

	/**
	 * Runs the given work in a sub-session of its own for the person of the given window's
	 * sub-session.
	 *
	 * <p>
	 * Workaround for TL #30216: <code>JobRunner</code> runs the body of a job in the sub-session of
	 * the window that started it. A commit of another interaction on that sub-session - a save in the
	 * same window, a second job started from it - moves the session revision of the sub-session
	 * past the revision the job's interaction fixed at its first database access. A KB cache that
	 * the job then has to rebuild (its soft reference cleared, or never filled) fails with "Session
	 * revision invalid" (<code>AbstractKBCache#getCurrentCache</code>), as a long ETS import did at
	 * 95 %. In a sub-session of its own, nobody but the job moves its revision.
	 * </p>
	 *
	 * <p>
	 * The sub-session is the one of {@link TLContextManager#inPersonContext(Person,
	 * com.top_logic.basic.util.ComputationEx2)}: the window's person, access checks on - as on the
	 * body's thread before, which never bypassed them -, a session of its own that is released
	 * afterwards. The person gives it its default locale and time zone; those of the window, which
	 * the user may have switched, replace them, so that the texts the body localizes (progress,
	 * import report) stay in the user's language.
	 * </p>
	 *
	 * <p>
	 * Before the job ends, the window is brought up to date with what the job committed, see
	 * {@link #catchUp(TLSubSessionContext)}.
	 * </p>
	 *
	 * <p>
	 * A sub-session without a person (a test in the system context, a request before the login)
	 * has no window that commits concurrently: the work runs in it as before.
	 * </p>
	 *
	 * <p>
	 * <b>When TL #30216 is fixed</b> (<code>JobRunner</code> gives a job a sub-session of its own):
	 * run the work directly again.
	 * </p>
	 */
	private static <T> T inOwnSubSession(SubSessionContext window, Callable<T> work) throws Exception {
		if (window == null) {
			return work.call();
		}
		Person person = window instanceof TLSubSessionContext tlWindow ? tlWindow.getPerson() : null;
		if (person == null) {
			return ThreadContextManager.inContext(window, work::call);
		}
		Locale locale = window.getCurrentLocale();
		TimeZone timeZone = window.getCurrentTimeZone();
		try {
			return TLContextManager.inPersonContext(person, () -> {
				SubSessionContext own = ThreadContextManager.getSubSession();
				if (locale != null) {
					own.setCurrentLocale(locale);
				}
				if (timeZone != null) {
					own.setCurrentTimeZone(timeZone);
				}
				return work.call();
			});
		} finally {
			catchUp((TLSubSessionContext) window);
		}
	}

	/**
	 * Brings the window up to date with what the job committed, before the job ends.
	 *
	 * <p>
	 * The commits of the job no longer move the session revision of the window. The window learns
	 * about them through its model events, which the engine synthesizes after what a job's end sets
	 * off in the window (<code>ChannelPublisher</code>, <code>ReactWindowReplay#enter</code>), not
	 * before: the <code>&lt;with-result&gt;</code> of the import would select the imported project
	 * in a table that does not show it yet. Entering the window here synthesizes them, within the
	 * window's interaction, as a request of the window does. A window busy for longer than
	 * {@link #CATCH_UP_TIMEOUT} is left to catch up on its own.
	 * </p>
	 */
	private static void catchUp(TLSubSessionContext window) {
		try {
			ReactWindowReplay.inWindow(window, CATCH_UP_TIMEOUT, root -> {
				// The window's model events are synthesized before and after.
			});
		} catch (RuntimeException ex) {
			// The job's outcome stands; the window catches up with its next request.
			Logger.error("Failed to bring the window up to date after a job.", ex, Uninterruptible.class);
		}
	}

	/**
	 * Opens the section of a blocking access (on the bus) that a cancelled job is to interrupt:
	 * <code>try (Section section = Uninterruptible.interruptible()) { … }</code>.
	 *
	 * <p>
	 * Outside {@link #run(Callable)} the section does nothing. Inside, the thread is interrupted only
	 * while it is in the section; the access in the section must not touch the knowledge base.
	 * </p>
	 */
	public static Section interruptible() {
		Shield shield = SHIELD.get();
		if (shield == null) {
			return NONE;
		}
		shield.enter();
		return shield::leave;
	}

	private static final class Shield {

		private Thread _thread;

		private boolean _cancelled;

		private boolean _interruptible;

		synchronized void setThread(Thread thread) {
			_thread = thread;
		}

		synchronized void cancel() {
			_cancelled = true;
			if (_interruptible) {
				_thread.interrupt();
			}
		}

		synchronized void enter() {
			_interruptible = true;
			if (_cancelled) {
				Thread.currentThread().interrupt();
			}
		}

		synchronized void leave() {
			_interruptible = false;
			// No interrupt may outlive the access: the next database access would be hit.
			Thread.interrupted();
		}
	}

}
