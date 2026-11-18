/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.history;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.HistoryUtils;

/**
 * Runs work in a thread whose view of the knowledge base (its session revision) is older than the
 * last commit, as a background job that runs for minutes while others commit (haumacher/ehome #199).
 */
public class OlderView {

	/**
	 * The work of the thread with the older view.
	 */
	public interface Work {

		/**
		 * Runs in the thread; its view is the revision <code>view</code>, older than the last commit.
		 */
		void run(long view) throws Exception;
	}

	/**
	 * Starts a thread in a system context, which takes its view; runs <code>foreign</code> (a commit)
	 * in the calling thread; then lets the thread run <code>work</code> and waits for it.
	 *
	 * @throws AssertionError
	 *         If the work failed, with its failure as cause.
	 */
	public static void run(Runnable foreign, Work work) throws InterruptedException {
		CountDownLatch viewed = new CountDownLatch(1);
		CountDownLatch committed = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = new Thread(() -> ThreadContext.inSystemContext(OlderView.class, () -> {
			try {
				long view = HistoryUtils.getSessionRevision().getCommitNumber();
				viewed.countDown();
				committed.await();
				if (HistoryUtils.getLastRevision().getCommitNumber() <= view) {
					throw new AssertionError("The foreign work committed nothing; the view is not older.");
				}
				work.run(view);
			} catch (Throwable ex) {
				failure.set(ex);
			}
		}));
		thread.start();
		viewed.await();
		try {
			foreign.run();
		} finally {
			committed.countDown();
			thread.join();
		}
		if (failure.get() != null) {
			throw new AssertionError("The work in a thread with an older view failed.", failure.get());
		}
	}

}
