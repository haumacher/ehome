/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.channel.ViewChannel;

import de.haumacher.ehome.view.WindowUpdates;

/**
 * {@link GroupMonitor.Publisher} writing the {@link GroupMonitor#snapshot()} to a channel of a view
 * from the thread the change happened on ({@link WindowUpdates}), at most every
 * {@link #UPDATE_INTERVAL} ms: a burst of telegrams is shown as one update, and the last state of a
 * burst is never lost.
 *
 * <p>
 * When the window is gone, the publisher removes itself and detaches the monitor from the bus, so
 * that a closed page does not keep a tunnelling connection of the gateway.
 * </p>
 */
public class WindowPublisher extends WindowUpdates implements GroupMonitor.Publisher {

	/** The shortest time in milliseconds between two updates of the view. */
	public static final long UPDATE_INTERVAL = 250;

	private final ViewChannel _channel;

	private final GroupMonitor _monitor;

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
		super(context, UPDATE_INTERVAL);
		_channel = channel;
		_monitor = monitor;
	}

	@Override
	protected void update() {
		_channel.set(_monitor.snapshot());
	}

	@Override
	protected void windowGone() {
		if (_monitor.removePublisher(this)) {
			_monitor.disconnect();
		}
	}

}
