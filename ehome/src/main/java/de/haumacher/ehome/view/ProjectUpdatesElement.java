/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.listener.Registration;
import com.top_logic.layout.react.control.IReactControl;
import com.top_logic.layout.react.control.ReactControl;
import com.top_logic.layout.view.ContainerElement;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelPublisher;
import com.top_logic.layout.view.channel.ChannelRef;
import com.top_logic.layout.view.channel.ChannelRefFormat;
import com.top_logic.layout.view.channel.ViewChannel;
import com.top_logic.layout.view.element.ContentControls;
import com.top_logic.model.TLObject;

/**
 * Shows its content and, while it is displayed, keeps a view of a live source of a project up to date
 * (the Zigbee network of the project's controller): every change of the source writes a new value to
 * the <code>refresh</code> channel, from which the view computes what it shows again. The updates come
 * at most every {@link #updateInterval()} ms, so a burst of changes is one update per interval.
 *
 * <p>
 * While its content is attached (displayed), the element subscribes the <code>refresh</code> channel
 * to a {@link ProjectSource} over {@link #subscribe(TLObject, Runnable)}, delivered by a
 * {@link ChannelPublisher} that runs {@link #beforeUpdate()} before it writes the channel; it ends the
 * subscription when the content is detached or disposed. A <code>&lt;source-channel&gt;</code> with a
 * {@link ProjectSource} does the same without an element, as the simulation does
 * (<code>SimulationSource</code>), but cannot run code on the thread writing the channel.
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

	private final ChannelRef _refresh;

	private final ProjectSource _source;

	/**
	 * Creates a {@link ProjectUpdatesElement} from configuration.
	 */
	@CalledByReflection
	public ProjectUpdatesElement(InstantiationContext context, Config config) {
		super(context, config);
		_refresh = config.getRefresh();
		_source = new ProjectSource(config.getProject()) {
			@Override
			protected Runnable listen(TLObject project, Runnable listener) {
				return ProjectUpdatesElement.this.subscribe(project, listener);
			}
		};
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
		Watch watch = new Watch(context, context.resolveChannel(_refresh));
		content.addAttachListener(watch::attach);
		content.addDetachListener(watch::detach);
		content.addCleanupAction(watch::detach);
		return content;
	}

	/**
	 * The subscription of one displayed view.
	 */
	private final class Watch {

		private final ViewContext _context;

		private final ViewChannel _refreshChannel;

		private ChannelPublisher _publisher;

		private Registration _registration;

		Watch(ViewContext context, ViewChannel refresh) {
			_context = context;
			_refreshChannel = refresh;
		}

		synchronized void attach() {
			if (_registration != null) {
				return;
			}
			_publisher = new ChannelPublisher(_context, updateInterval(), this::update, false);
			_registration = _source.subscribe(_context, _publisher);
		}

		synchronized void detach() {
			if (_registration == null) {
				return;
			}
			_publisher.close();
			_registration.dispose();
			_publisher = null;
			_registration = null;
		}

		private void update(Object value) {
			beforeUpdate();
			_refreshChannel.set(value);
		}
	}

}
