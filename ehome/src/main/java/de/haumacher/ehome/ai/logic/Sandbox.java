/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.util.TLContext;
import com.top_logic.util.TLContextManager;

/**
 * Runs a change where nothing of it can stay: on a thread of its own, as the run's user, in a
 * transaction that is always rolled back. The dry runs of the assistant's tools for E-Home's logic
 * (haumacher/ehome #128): checking rules before they are saved, trying rules not saved yet in the
 * simulation, the functions a proposal would add.
 *
 * <p>
 * A dry run is a read for the agent loop (no approval, no part in the turn's transaction), so it
 * cannot use the thread of the call: that thread may hold the turn's open transaction, whose
 * rollback is not a nested one. The sandbox's transaction is the first one of its own interaction,
 * and leaving it uncommitted drops every change of it, as the dry run of the AI modules'
 * <code>changeData</code> does.
 * </p>
 *
 * <p>
 * Consequence: a dry run sees the data as committed. A change an earlier call of the same turn made
 * and the turn has not committed yet is not seen.
 * </p>
 */
final class Sandbox {

	private static final ResKey DRY_RUN = ResKey.text("Assistant dry run (rolled back)");

	private static final long POLL_MS = 200;

	private static final ExecutorService DRY_RUNS = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "E-Home assistant dry run");
		thread.setDaemon(true);
		return thread;
	});

	private Sandbox() {
		// Static only.
	}

	/**
	 * Runs the computation as the run's user in a transaction that is rolled back when it ends.
	 *
	 * @param context
	 *        The run's context; its cancellation gives the dry run up (it is not interrupted: an
	 *        interrupt during a file read of the database closes H2's file for the whole application).
	 * @param computation
	 *        What runs; its result must not hold objects created in the sandbox (they are gone
	 *        afterwards).
	 */
	static <T> T run(ToolContext context, Supplier<T> computation) {
		Person person = TLContext.currentUser();
		Future<T> future = DRY_RUNS.submit(() -> TLContextManager.inPersonContext(person, () -> sandbox(computation)));
		while (true) {
			if (context != null && context.cancellationToken().isCancelled()) {
				future.cancel(false);
				throw new CancellationException("The dry run was cancelled.");
			}
			try {
				return future.get(POLL_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ex) {
				// Still running - look at the cancellation again.
			} catch (InterruptedException ex) {
				future.cancel(false);
				Thread.currentThread().interrupt();
				throw new CancellationException("The dry run was interrupted.");
			} catch (ExecutionException ex) {
				Throwable cause = ex.getCause();
				if (cause instanceof RuntimeException failure) {
					throw failure;
				}
				throw new IllegalStateException("The dry run failed: " + cause.getMessage(), cause);
			}
		}
	}

	private static <T> T sandbox(Supplier<T> computation) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction(DRY_RUN)) {
			return computation.get();
		}
	}

}
