/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.concurrent.TimeUnit;

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
import com.top_logic.layout.react.window.ReactWindowRegistry;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelRef;
import com.top_logic.layout.view.channel.ChannelRefFormat;
import com.top_logic.layout.view.channel.ChannelSink;
import com.top_logic.layout.view.channel.ChannelSource;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

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
 * does not keep a tunnelling connection of the gateway. Whether a window is gone is the engine's
 * decision: it tears down a window that was closed, whose page was unloaded and did not come back, or
 * whose client has stayed away (TL #29869), and the window then leaves the
 * {@link ReactWindowRegistry registry}. A displayed view's subscription ends with that teardown, which
 * detaches the monitor at once. A hidden view's subscription has ended already when the view was
 * hidden, and the engine tells a source nothing about the teardown of a window whose view it no longer
 * shows: such a window keeps a {@link Dormant} publisher with the monitor, which notices that its
 * window is gone at the next change of the monitor, and for a bus without traffic every
 * {@link ReactWindowRegistry#DISCONNECT_GRACE_MILLIS} ms.
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
		Window window = new Window(context.getWindowRegistry(), context.getWindowName());
		return subscribe(monitor, window, sink, Resources.getInstance());
	}

	/**
	 * Publishes the snapshots of the given monitor into the sink until the registration is disposed.
	 *
	 * @param monitor
	 *        The monitor to show.
	 * @param window
	 *        The window showing it.
	 * @param sink
	 *        Where the snapshots go.
	 * @param resources
	 *        The language of the texts of the snapshots.
	 */
	public static Registration subscribe(GroupMonitor monitor, Window window, ChannelSink sink, Resources resources) {
		for (GroupMonitor.Publisher publisher : monitor.getPublishers()) {
			if (publisher instanceof Dormant dormant && dormant._window.equals(window)) {
				monitor.removePublisher(dormant);
			}
		}
		GroupMonitor.Publisher viewer = () -> sink.publish(monitor.snapshot(resources));
		monitor.addPublisher(viewer);
		sink.publish(monitor.snapshot(resources));
		return Registration.onDispose(() -> {
			monitor.removePublisher(viewer);
			if (window.isGone()) {
				disconnectIfUnseen(monitor);
			} else {
				Dormant dormant = new Dormant(monitor, window);
				monitor.addPublisher(dormant);
				dormant.scheduleCheck();
			}
		});
	}

	/**
	 * Detaches the monitor from the bus, unless another window still shows it (or hides its view).
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
	 * The window a view of the monitor is displayed in.
	 *
	 * @param registry
	 *        The windows of the session; <code>null</code> without a window (a view that is not
	 *        displayed, as in a test), which is never gone.
	 * @param name
	 *        The name of the window.
	 */
	public record Window(ReactWindowRegistry registry, String name) {

		/**
		 * Whether the engine has torn the window down: it was closed, its page was unloaded and did
		 * not come back, or its client stayed away.
		 */
		public boolean isGone() {
			return registry != null && registry.getWindow(name) == null;
		}
	}

	/**
	 * Stands for a window that hid the view of the monitor: keeps the monitor attached while the
	 * window is open, and detaches it at the first change after the window is gone, unless another
	 * window shows it.
	 */
	private static final class Dormant implements GroupMonitor.Publisher {

		private final GroupMonitor _monitor;

		final Window _window;

		Dormant(GroupMonitor monitor, Window window) {
			_monitor = monitor;
			_window = window;
		}

		@Override
		public void changed() {
			if (_window.isGone() && _monitor.removePublisher(this)) {
				disconnectIfUnseen(_monitor);
			}
		}

		/**
		 * Checks the window again later, for a bus without traffic, as long as this publisher stays
		 * with the monitor.
		 */
		void scheduleCheck() {
			SchedulerService.getInstance().schedule(() -> {
				changed();
				if (_monitor.getPublishers().contains(this)) {
					scheduleCheck();
				}
			}, ReactWindowRegistry.DISCONNECT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

}
