/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.job;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.top_logic.basic.SubSessionContext;
import com.top_logic.basic.thread.ThreadContextManager;

/**
 * Keeps the interrupt of a cancelled job away from its database access (TL #30196).
 *
 * <p>
 * A cancelled <code>&lt;start-job&gt;</code> is woken by an interrupt of its worker thread
 * (<code>JobRunner#cancel</code>). An interrupt in the middle of a database access - a write or a
 * read - closes the file channel of the embedded H2 database, which then fails for the whole
 * application until a restart. Every cancelable job of the app that touches the knowledge base
 * therefore runs its body with {@link #run(Callable)}: the body runs on a thread of its own in the
 * job's sub-session, the worker only waits for it, and the interrupt the worker receives is kept as a
 * cancel request instead of reaching the body.
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
				result.complete(subSession == null ? work.call() : ThreadContextManager.inContext(subSession, work::call));
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
