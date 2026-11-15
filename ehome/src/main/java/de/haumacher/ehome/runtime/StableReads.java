/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A stretch of reads in which the data does not change, though it holds uncommitted changes: the
 * checks of a change of the assistant's tools after the change was made (a rule check, a function's
 * dry run, haumacher/ehome #128).
 *
 * <p>
 * The caches of derived data ({@link ImageCache}, the objects in effect of a device) are valid for a
 * committed revision only, and compute their result anew whenever the transaction holds changes. A
 * check of several rules asked for the image of the project once per rule, and the image asked for
 * the configuration of a device once per connection of it: a check of one rule took 13 s for the
 * 29 devices of the ETS 6 demo project, minutes in a larger project, and the MCP client gave up
 * (production report of 2026-11-15). Inside {@link #run(Runnable)} a result is computed once per key
 * and kept until the stretch ends.
 * </p>
 *
 * <p>
 * The stretch must not change the data: a change inside it makes the kept results stale. It is bound
 * to the thread.
 * </p>
 */
public final class StableReads {

	private static final ThreadLocal<Map<Object, Object>> MEMO = new ThreadLocal<>();

	private StableReads() {
		// Static only.
	}

	/**
	 * Runs the given reads as a stretch of stable data; nested, it is part of the enclosing one.
	 */
	public static void run(Runnable reads) {
		if (MEMO.get() != null) {
			reads.run();
			return;
		}
		MEMO.set(new HashMap<>());
		try {
			reads.run();
		} finally {
			MEMO.remove();
		}
	}

	/**
	 * The result for the given key: computed once inside a stretch of {@link #run(Runnable)}, every
	 * time outside one.
	 *
	 * @param key
	 *        What the result is of; equal keys share the result.
	 * @param computation
	 *        Computes the result from the current data.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T get(Object key, Supplier<T> computation) {
		Map<Object, Object> memo = MEMO.get();
		if (memo == null) {
			return computation.get();
		}
		if (memo.containsKey(key)) {
			return (T) memo.get(key);
		}
		// Not computeIfAbsent: the computation may ask for other keys.
		T result = computation.get();
		memo.put(key, result);
		return result;
	}

}
