/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.concurrent.TimeUnit;

import com.top_logic.base.context.SubSessionListener;
import com.top_logic.base.context.TLSubSessionContext;
import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.Logger;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.listener.Registration;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelRef;
import com.top_logic.layout.view.channel.ChannelRefFormat;
import com.top_logic.layout.view.channel.ChannelSink;
import com.top_logic.layout.view.channel.ChannelSource;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContextManager;

/**
 * Source of the <code>state</code> channel of the group monitor (<code>projects/monitor.view.xml</code>):
 * while the view is displayed, every change of the monitor of the project in the session
 * ({@link MonitorSessions}) publishes its {@link GroupMonitor#snapshot(Resources) snapshot}, with the
 * texts in the language of the user who displays it. A subscription publishes the current snapshot
 * right away, so that a view displayed again (or a page loaded again) shows the monitor as it is,
 * connected or not. The channel takes a value at most every <code>update-interval</code> ms.
 *
 * <p>
 * The monitor stays attached to the bus while its page is open, also while its tab is hidden. When
 * the last window that showed it is gone, the monitor is detached from the bus, so that a closed page
 * does not keep a tunnelling connection of the gateway. The open window is its sub-session, as the
 * engine's <code>ChannelSource</code> documents (TL #30088): the first subscription in a window binds
 * a {@link WindowHold} to the monitor, which keeps it attached while the view is hidden, and which the
 * sub-session tells when it ends - the window closed, its page unloaded and not come back, its client
 * stayed away (TL #29869), or the session ended.
 * </p>
 */
public class MonitorSource implements ChannelSource {

	/**
	 * Configuration of {@link MonitorSource}.
	 */
	public interface Config extends PolymorphicConfiguration<MonitorSource> {

		@Override
		@ClassDefault(MonitorSource.class)
		Class<? extends MonitorSource> getImplementationClass();

		/**
		 * The channel holding the project whose monitor is shown.
		 */
		@Name("project")
		@Mandatory
		@Format(ChannelRefFormat.class)
		ChannelRef getProject();
	}

	private final ChannelRef _project;

	/**
	 * Creates a {@link MonitorSource} from configuration.
	 */
	@CalledByReflection
	public MonitorSource(InstantiationContext context, Config config) {
		_project = config.getProject();
	}

	@Override
	public Registration subscribe(ViewContext context, ChannelSink sink) {
		if (!(context.resolveChannel(_project).get() instanceof TLObject project) || !project.tValid()) {
			return Registration.onDispose(() -> {
				// Nothing shown.
			});
		}
		GroupMonitor monitor = MonitorSessions.monitor(project);
		return subscribe(monitor, TLContextManager.getSubSession(), sink, Resources.getInstance());
	}

	/**
	 * Publishes the snapshots of the given monitor into the sink until the registration is disposed.
	 *
	 * @param monitor
	 *        The monitor to show.
	 * @param window
	 *        The sub-session of the window showing it, which keeps the monitor attached until it
	 *        ends; <code>null</code> without a window (as in a test without an interaction): then
	 *        only the displayed view keeps it attached.
	 * @param sink
	 *        Where the snapshots go.
	 * @param resources
	 *        The language of the texts of the snapshots.
	 */
	public static Registration subscribe(GroupMonitor monitor, TLSubSessionContext window, ChannelSink sink,
			Resources resources) {
		if (window != null && !isHeld(monitor, window)) {
			WindowHold hold = new WindowHold(monitor, window);
			monitor.addPublisher(hold);
			window.addUnboundListener(hold);
		}
		GroupMonitor.Publisher viewer = () -> sink.publish(monitor.snapshot(resources));
		monitor.addPublisher(viewer);
		sink.publish(monitor.snapshot(resources));
		return Registration.onDispose(() -> {
			monitor.removePublisher(viewer);
			// The window may have ended before its view was torn down.
			disconnectIfUnseen(monitor);
		});
	}

	private static boolean isHeld(GroupMonitor monitor, TLSubSessionContext window) {
		for (GroupMonitor.Publisher publisher : monitor.getPublishers()) {
			if (publisher instanceof WindowHold hold && hold.isBoundTo(window)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Detaches the monitor from the bus, unless a view still shows it or a window still holds it.
	 */
	static void disconnectIfUnseen(GroupMonitor monitor) {
		if (monitor.getPublishers().isEmpty() && monitor.isConnected()) {
			// Not on the thread of the bus link, which may be the one telling the change.
			SchedulerService.getInstance().schedule(() -> {
				try {
					if (monitor.getPublishers().isEmpty()) {
						monitor.disconnect();
					}
				} catch (RuntimeException ex) {
					Logger.error("Detaching the group monitor of a closed page failed.", ex, MonitorSource.class);
				}
			}, 0, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Keeps the monitor attached while a window that showed it is open, also while the window hides
	 * its view; publishes nothing. Released when the sub-session of the window ends.
	 */
	private static final class WindowHold implements GroupMonitor.Publisher, SubSessionListener {

		private final GroupMonitor _monitor;

		private final TLSubSessionContext _window;

		WindowHold(GroupMonitor monitor, TLSubSessionContext window) {
			_monitor = monitor;
			_window = window;
		}

		boolean isBoundTo(TLSubSessionContext window) {
			return _window == window;
		}

		@Override
		public void changed() {
			// Publishes nothing, the views of the window do.
		}

		@Override
		public void notifySubSessionUnbound(TLSubSessionContext context) {
			if (_monitor.removePublisher(this)) {
				disconnectIfUnseen(_monitor);
			}
		}
	}

}
