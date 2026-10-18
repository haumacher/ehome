/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import java.util.concurrent.TimeUnit;

import com.top_logic.basic.SubSessionContext;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.react.scripting.ReactWindowReplay;
import com.top_logic.layout.react.window.ReactWindowRegistry;

/**
 * Brings changes of a live source (a bus monitor, a running simulation) into a view from the thread
 * the change happened on, the way a long-running job of the view layer publishes its progress
 * (<code>com.top_logic.layout.view.job.JobRunner</code>).
 *
 * <p>
 * {@link #changed()} only takes note; the update (writing a channel of the view) runs later on a
 * thread of the {@link SchedulerService}, in the sub-session of the request that created this
 * object and under the interaction of the window the view is displayed in
 * (<code>ReactWindowReplay.inWindow</code>), so that the controls reading the channel update and
 * their updates reach the browser through the window's server-sent events, as the result of a
 * command does. The updates are throttled to one per interval: a burst of changes is shown as one
 * update, and the last state of a burst is never lost.
 * </p>
 *
 * <p>
 * When the window is gone, the update is not run and {@link #windowGone()} is called instead.
 * </p>
 *
 * <p>
 * The view layer has no declared way for this (TL #29814, <code>doc/tl-todo.md</code>).
 * </p>
 */
public abstract class WindowUpdates {

	private final ReactContext _context;

	private final long _interval;

	private final SubSessionContext _subSession;

	private final Object _lock = new Object();

	private long _lastDelivery;

	private boolean _scheduled;

	/**
	 * Creates a {@link WindowUpdates}; called in a request of the window.
	 *
	 * @param context
	 *        The context of the window the view is displayed in.
	 * @param interval
	 *        The shortest time in milliseconds between two updates.
	 */
	protected WindowUpdates(ReactContext context, long interval) {
		_context = context;
		_interval = interval;
		_subSession = ThreadContextManager.getSubSession();
	}

	/**
	 * The source changed: the view is updated within the interval.
	 */
	public void changed() {
		long delay;
		synchronized (_lock) {
			if (_scheduled) {
				return;
			}
			_scheduled = true;
			delay = Math.max(0, _lastDelivery + _interval - System.currentTimeMillis());
		}
		schedule(this::deliver, delay);
	}

	/**
	 * Runs the task after the delay on another thread (a thread of the {@link SchedulerService}).
	 */
	protected void schedule(Runnable task, long delay) {
		SchedulerService.getInstance().schedule(task, delay, TimeUnit.MILLISECONDS);
	}

	private void deliver() {
		synchronized (_lock) {
			_scheduled = false;
			_lastDelivery = System.currentTimeMillis();
		}
		if (!runInWindow(this::update)) {
			windowGone();
		}
	}

	/**
	 * Runs the action in the sub-session of the request that created this object and in the
	 * interaction of the window.
	 *
	 * @return Whether it ran: <code>false</code> when the window is gone.
	 */
	protected boolean runInWindow(Runnable action) {
		ReactWindowRegistry registry = _context.getWindowRegistry();
		if (_subSession == null) {
			return ReactWindowReplay.inWindow(registry, _context.getWindowName(), action);
		}
		boolean[] delivered = { false };
		ThreadContextManager.inContext(_subSession, () -> {
			delivered[0] = ReactWindowReplay.inWindow(registry, _context.getWindowName(), action);
		});
		return delivered[0];
	}

	/**
	 * Updates the view (writes its channel); runs in the window's interaction.
	 */
	protected abstract void update();

	/**
	 * The window is gone; the source should forget this object.
	 */
	protected abstract void windowGone();

}
