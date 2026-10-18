/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.view;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.thread.ThreadContextManager;

import de.haumacher.ehome.view.WindowUpdates;

/**
 * The throttling of {@link WindowUpdates} (haumacher/ehome #62), which brings the changes of the
 * group monitor and of a running simulation into their views: a burst of changes is one update, the
 * next update waits for the interval, a change after an update is never lost, and a window that is
 * gone is reported instead of updated.
 */
@SuppressWarnings("javadoc")
public class TestWindowUpdates extends BasicTestCase {

	private static final long INTERVAL = 500;

	private static final class Probe extends WindowUpdates {

		final List<Runnable> _tasks = new ArrayList<>();

		final List<Long> _delays = new ArrayList<>();

		int _updates;

		int _gone;

		boolean _window = true;

		Probe() {
			super(null, INTERVAL);
		}

		@Override
		protected void schedule(Runnable task, long delay) {
			_tasks.add(task);
			_delays.add(Long.valueOf(delay));
		}

		@Override
		protected boolean runInWindow(Runnable action) {
			if (_window) {
				action.run();
			}
			return _window;
		}

		@Override
		protected void update() {
			_updates++;
		}

		@Override
		protected void windowGone() {
			_gone++;
		}
	}

	public void testThrottling() {
		Probe probe = new Probe();
		for (int n = 0; n < 5; n++) {
			probe.changed();
		}
		assertEquals(1, probe._tasks.size());
		assertEquals(Long.valueOf(0), probe._delays.get(0));
		assertEquals(0, probe._updates);

		probe._tasks.get(0).run();
		assertEquals(1, probe._updates);

		probe.changed();
		probe.changed();
		assertEquals(2, probe._tasks.size());
		long delay = probe._delays.get(1).longValue();
		assertTrue("Delay " + delay, delay > INTERVAL - 200 && delay <= INTERVAL);

		probe._tasks.get(1).run();
		assertEquals(2, probe._updates);

		probe._window = false;
		probe.changed();
		probe._tasks.get(2).run();
		assertEquals(2, probe._updates);
		assertEquals(1, probe._gone);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(
			ServiceTestSetup.createSetup(TestWindowUpdates.class, ThreadContextManager.Module.INSTANCE));
	}

}
