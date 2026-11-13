/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

/**
 * A factory of named daemon threads for an executor that remembers the threads it made, so that the
 * end of the application can wait for them to have ended (haumacher/ehome #81).
 *
 * <p>
 * The termination of an executor (<code>awaitTermination</code>) does not say that its threads have
 * ended: a worker counts as gone before its thread has run its last instructions, so the thread may
 * still be alive for a moment after it. {@link #join(Duration)} waits for the threads themselves.
 * </p>
 */
public final class DaemonThreads implements ThreadFactory {

	private final String _name;

	private final List<Thread> _threads = new ArrayList<>();

	/**
	 * Creates a factory whose threads all have the given name.
	 */
	public DaemonThreads(String name) {
		_name = name;
	}

	@Override
	public synchronized Thread newThread(Runnable runnable) {
		Thread thread = new Thread(runnable, _name);
		thread.setDaemon(true);
		_threads.add(thread);
		return thread;
	}

	/**
	 * Waits until every thread made by this factory has ended, at most the given time in all (after
	 * the executor using them was shut down).
	 *
	 * @throws InterruptedException
	 *         If the waiting thread is interrupted.
	 */
	public void join(Duration timeout) throws InterruptedException {
		List<Thread> threads;
		synchronized (this) {
			threads = new ArrayList<>(_threads);
		}
		long end = System.nanoTime() + timeout.toNanos();
		for (Thread thread : threads) {
			long left = Math.max(1, (end - System.nanoTime()) / 1_000_000);
			thread.join(left);
		}
	}

}
