/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

/**
 * The downloads of a project run one at a time (haumacher/ehome #200): a single download (every kind
 * of {@link DownloadJob}) and the programming of several devices ({@link DownloadQueue}) hold the
 * project's lock while they write over the project's path to the bus; another one is refused with
 * what holds the lock, never queued behind it.
 *
 * <p>
 * The lock lives in this application (runtime state, no model): it serializes the downloads of all
 * sessions of this E-Home. Its changes are announced to the listeners ({@link BusStateSource}), so
 * that the vetoes of the download commands are decided anew when a download ends.
 * </p>
 */
public final class DownloadLock implements AutoCloseable {

	/** What holds the lock of a project, by the project's identity. */
	private static final Map<Object, ResKey> HELD = new ConcurrentHashMap<>();

	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

	private final Object _key;

	private DownloadLock(Object key) {
		_key = key;
	}

	/**
	 * Takes the lock of the project of the given object (a device or the project).
	 *
	 * @param holder
	 *        What holds the lock, for a person: the reason another download of the project is
	 *        refused meanwhile.
	 * @return The lock, released by {@link #close()}.
	 * @throws TopLogicException
	 *         With the reason of the holder, if a download of the project runs.
	 */
	public static DownloadLock acquire(TLObject object, ResKey holder) {
		Object key = key(object);
		ResKey running = HELD.putIfAbsent(key, holder);
		if (running != null) {
			throw new TopLogicException(running);
		}
		changed();
		return new DownloadLock(key);
	}

	/**
	 * Why a download of the project of the given object (a device or the project) cannot start now: what
	 * holds the lock; <code>null</code> if nothing does.
	 */
	public static ResKey veto(TLObject object) {
		return object == null ? null : HELD.get(key(object));
	}

	/**
	 * Releases the lock.
	 */
	@Override
	public void close() {
		HELD.remove(_key);
		changed();
	}

	/**
	 * Calls the given listener whenever a lock is taken or released.
	 */
	public static void addListener(Runnable listener) {
		LISTENERS.add(listener);
	}

	/**
	 * Removes a listener added by {@link #addListener(Runnable)}.
	 */
	public static void removeListener(Runnable listener) {
		LISTENERS.remove(listener);
	}

	private static void changed() {
		for (Runnable listener : LISTENERS) {
			listener.run();
		}
	}

	private static Object key(TLObject object) {
		return BusPath.project(object).tId();
	}

}
