/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A limit of attempts per time window, per key (the remote address) and in total: the guard of the
 * open enrollment route against guessing codes.
 */
public final class RateLimit {

	private final int _perKey;

	private final int _total;

	private final long _windowNanos;

	private final Map<String, Deque<Long>> _attempts = new HashMap<>();

	private final Deque<Long> _all = new ArrayDeque<>();

	/**
	 * Creates a {@link RateLimit}.
	 *
	 * @param perKey
	 *        The attempts one key may make within the window.
	 * @param total
	 *        The attempts all keys together may make within the window.
	 * @param windowMillis
	 *        The window in milliseconds.
	 */
	public RateLimit(int perKey, int total, long windowMillis) {
		_perKey = perKey;
		_total = total;
		_windowNanos = windowMillis * 1_000_000L;
	}

	/**
	 * Records an attempt of the given key at the given nanosecond time: whether it is allowed. A
	 * refused attempt is not counted.
	 */
	public synchronized boolean allow(String key, long now) {
		expire(_all, now);
		for (Iterator<Deque<Long>> it = _attempts.values().iterator(); it.hasNext();) {
			Deque<Long> times = it.next();
			expire(times, now);
			if (times.isEmpty()) {
				it.remove();
			}
		}
		Deque<Long> times = _attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
		if (times.size() >= _perKey || _all.size() >= _total) {
			return false;
		}
		times.addLast(Long.valueOf(now));
		_all.addLast(Long.valueOf(now));
		return true;
	}

	/**
	 * The seconds until the oldest attempt of the window leaves it.
	 */
	public long retryAfterSeconds() {
		return Math.max(1, _windowNanos / 1_000_000_000L);
	}

	private void expire(Deque<Long> times, long now) {
		while (!times.isEmpty() && now - times.peekFirst().longValue() >= _windowNanos) {
			times.removeFirst();
		}
	}

}
