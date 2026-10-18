/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.layout.react.control.IReactControl;
import com.top_logic.layout.react.control.ReactControl;
import com.top_logic.layout.view.ContainerElement;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelRef;
import com.top_logic.layout.view.channel.ChannelRefFormat;
import com.top_logic.layout.view.channel.ViewChannel;
import com.top_logic.layout.view.element.ContentControls;
import com.top_logic.model.TLObject;

/**
 * Shows its content and, while it is displayed, keeps a view of a live source of a project up to date
 * (a running simulation, the Zigbee network of the project's controller): every change of the source
 * writes a new value to the <code>refresh</code> channel, from which the view computes what it shows
 * again. The updates come at most every {@link #updateInterval()} ms ({@link WindowUpdates}), so a
 * burst of changes is one update per interval.
 *
 * <p>
 * The element listens to the source of the project on the <code>project</code> channel
 * ({@link #subscribe(TLObject, Runnable)}) while its content is attached (displayed), follows a change
 * of the channel, and stops listening when the content is detached or disposed. When it is displayed
 * again, it updates once.
 * </p>
 */
public abstract class ProjectUpdatesElement extends ContainerElement {

	/**
	 * Configuration of {@link ProjectUpdatesElement}.
	 */
	public interface Config extends ContainerElement.Config {

		/**
		 * The channel holding the project whose live source is shown.
		 */
		@Name("project")
		@Mandatory
		@Format(ChannelRefFormat.class)
		ChannelRef getProject();

		/**
		 * The channel written on every change of the source.
		 */
		@Name("refresh")
		@Mandatory
		@Format(ChannelRefFormat.class)
		ChannelRef getRefresh();
	}

	private final ChannelRef _project;

	private final ChannelRef _refresh;

	/**
	 * Creates a {@link ProjectUpdatesElement} from configuration.
	 */
	@CalledByReflection
	public ProjectUpdatesElement(InstantiationContext context, Config config) {
		super(context, config);
		_project = config.getProject();
		_refresh = config.getRefresh();
	}

	/**
	 * The shortest time in milliseconds between two updates of the view.
	 */
	protected abstract long updateInterval();

	/**
	 * Starts telling the listener of the changes of the project's source; called in a request of the
	 * window. The listener is called on any thread and does not block.
	 *
	 * @return What stops it.
	 */
	protected abstract Runnable subscribe(TLObject project, Runnable listener);

	/**
	 * Called on the thread of an update, in the window's interaction, before the
	 * <code>refresh</code> channel is written.
	 */
	protected void beforeUpdate() {
		// Nothing by default.
	}

	@Override
	public IReactControl createControl(ViewContext context) {
		ReactControl content = ContentControls.combine(context, createChildControls(context));
		Watch watch = new Watch(context, context.resolveChannel(_project), context.resolveChannel(_refresh));
		content.addAttachListener(watch::attach);
		content.addDetachListener(watch::detach);
		content.addCleanupAction(watch::detach);
		return content;
	}

	/**
	 * The listener of the source of one displayed view.
	 */
	private final class Watch extends WindowUpdates implements Runnable, ViewChannel.ChannelListener {

		private final ViewChannel _projectChannel;

		private final ViewChannel _refreshChannel;

		private Runnable _unsubscribe;

		private boolean _attached;

		private long _updates;

		Watch(ViewContext context, ViewChannel project, ViewChannel refresh) {
			super(context, updateInterval());
			_projectChannel = project;
			_refreshChannel = refresh;
		}

		synchronized void attach() {
			if (_attached) {
				return;
			}
			_attached = true;
			_projectChannel.addListener(this);
			listenTo(_projectChannel.get());
			// What happened while it was not displayed.
			changed();
		}

		synchronized void detach() {
			if (!_attached) {
				return;
			}
			_attached = false;
			_projectChannel.removeListener(this);
			listenTo(null);
		}

		private void listenTo(Object value) {
			if (_unsubscribe != null) {
				_unsubscribe.run();
				_unsubscribe = null;
			}
			if (value instanceof TLObject project && project.tValid()) {
				_unsubscribe = subscribe(project, this);
			}
		}

		@Override
		public synchronized void handleNewValue(ViewChannel sender, Object oldValue, Object newValue) {
			if (_attached) {
				listenTo(newValue);
			}
		}

		@Override
		public void run() {
			changed();
		}

		@Override
		protected void update() {
			beforeUpdate();
			_refreshChannel.set(Long.valueOf(++_updates));
		}

		@Override
		protected void windowGone() {
			detach();
		}
	}

}
