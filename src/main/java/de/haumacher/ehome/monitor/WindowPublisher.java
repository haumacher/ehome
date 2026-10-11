/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.concurrent.TimeUnit;

import com.top_logic.basic.SubSessionContext;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.react.scripting.ReactWindowReplay;
import com.top_logic.layout.react.window.ReactWindowRegistry;
import com.top_logic.layout.view.channel.ViewChannel;

/**
 * {@link GroupMonitor.Publisher} writing the {@link GroupMonitor#snapshot()} to a channel of a view
 * from the thread the change happened on, the way a long-running job of the view layer publishes its
 * progress (<code>com.top_logic.layout.view.job.JobRunner</code>).
 *
 * <p>
 * The snapshot is written in the sub-session of the request that attached the monitor and under
 * the interaction of the window the view is displayed in (<code>ReactWindowReplay.inWindow</code>),
 * so that the controls reading the channel update and their updates reach the browser through the
 * window's server-sent events, as the result of a command does. The updates are throttled to one
 * per {@link #UPDATE_INTERVAL} ms: a burst of telegrams is shown as one update, and the last state
 * of a burst is never lost.
 * </p>
 *
 * <p>
 * When the window is gone, the publisher removes itself and detaches the monitor from the bus, so
 * that a closed page does not keep a tunnelling connection of the gateway.
 * </p>
 */
public class WindowPublisher implements GroupMonitor.Publisher {

	/** The shortest time in milliseconds between two updates of the view. */
	public static final long UPDATE_INTERVAL = 250;

	private final ReactContext _context;

	private final ViewChannel _channel;

	private final GroupMonitor _monitor;

	private final SubSessionContext _subSession;

	private final Object _lock = new Object();

	private long _lastDelivery;

	private boolean _scheduled;

	/**
	 * Creates a {@link WindowPublisher}; called in the request attaching the monitor.
	 *
	 * @param context
	 *        The context of the window the view is displayed in.
	 * @param channel
	 *        The channel receiving the snapshots.
	 * @param monitor
	 *        The monitor to publish.
	 */
	public WindowPublisher(ReactContext context, ViewChannel channel, GroupMonitor monitor) {
		_context = context;
		_channel = channel;
		_monitor = monitor;
		_subSession = ThreadContextManager.getSubSession();
	}

	@Override
	public void changed() {
		long delay;
		synchronized (_lock) {
			if (_scheduled) {
				return;
			}
			_scheduled = true;
			delay = Math.max(0, _lastDelivery + UPDATE_INTERVAL - System.currentTimeMillis());
		}
		SchedulerService.getInstance().schedule(this::deliver, delay, TimeUnit.MILLISECONDS);
	}

	private void deliver() {
		synchronized (_lock) {
			_scheduled = false;
			_lastDelivery = System.currentTimeMillis();
		}
		if (_subSession == null) {
			publish();
		} else {
			ThreadContextManager.inContext(_subSession, this::publish);
		}
	}

	private void publish() {
		ReactWindowRegistry registry = _context.getWindowRegistry();
		boolean delivered = ReactWindowReplay.inWindow(registry, _context.getWindowName(),
			() -> _channel.set(_monitor.snapshot()));
		if (!delivered && _monitor.removePublisher(this)) {
			_monitor.disconnect();
		}
	}

}
