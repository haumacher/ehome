/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import java.util.concurrent.atomic.AtomicLong;

import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.listener.Registration;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelRef;
import com.top_logic.layout.view.channel.ChannelRefFormat;
import com.top_logic.layout.view.channel.ChannelSink;
import com.top_logic.layout.view.channel.ChannelSource;
import com.top_logic.layout.view.channel.ViewChannel;
import com.top_logic.model.TLObject;

/**
 * {@link ChannelSource} of a <code>&lt;source-channel&gt;</code> following a live source of a project
 * (a running simulation, the Zigbee network of the project's controller): every change of the source
 * publishes a new number into the channel, from which the view computes what it shows again.
 *
 * <p>
 * A subscription listens to the source of the project on the channel named by
 * {@link Config#getProject()} ({@link #listen(TLObject, Runnable)}), follows a change of that channel,
 * and publishes once right away, so that a view displayed again shows what happened meanwhile. The
 * channel declares how often it takes a value (<code>update-interval</code>); a burst of changes is
 * one update per interval.
 * </p>
 */
public abstract class ProjectSource implements ChannelSource {

	/**
	 * Configuration of a {@link ProjectSource}.
	 */
	public interface Config<I extends ProjectSource> extends PolymorphicConfiguration<I> {

		/**
		 * The channel holding the project whose live source is followed.
		 */
		@Name("project")
		@Mandatory
		@Format(ChannelRefFormat.class)
		ChannelRef getProject();
	}

	private final ChannelRef _project;

	/**
	 * Creates a {@link ProjectSource}.
	 *
	 * @param project
	 *        The channel holding the project.
	 */
	protected ProjectSource(ChannelRef project) {
		_project = project;
	}

	/**
	 * Starts telling the listener of the changes of the project's source; called in a request of the
	 * window. The listener is called on any thread and does not block.
	 *
	 * @return What stops it.
	 */
	protected abstract Runnable listen(TLObject project, Runnable listener);

	@Override
	public Registration subscribe(ViewContext context, ChannelSink sink) {
		Subscription subscription = new Subscription(context.resolveChannel(_project), sink);
		subscription.start();
		return Registration.onDispose(subscription::stop);
	}

	/**
	 * The listener of the source of one displayed view.
	 */
	private final class Subscription implements Runnable, ViewChannel.ChannelListener {

		private final ViewChannel _projectChannel;

		private final ChannelSink _sink;

		private Runnable _unlisten;

		/** Read without the lock: a source calls {@link #run()} while holding locks of its own. */
		private volatile boolean _active;

		private final AtomicLong _changes = new AtomicLong();

		Subscription(ViewChannel project, ChannelSink sink) {
			_projectChannel = project;
			_sink = sink;
		}

		synchronized void start() {
			_active = true;
			_projectChannel.addListener(this);
			listenTo(_projectChannel.get());
			run();
		}

		synchronized void stop() {
			_active = false;
			_projectChannel.removeListener(this);
			listenTo(null);
		}

		private void listenTo(Object value) {
			if (_unlisten != null) {
				_unlisten.run();
				_unlisten = null;
			}
			if (value instanceof TLObject project && project.tValid()) {
				_unlisten = listen(project, this);
			}
		}

		@Override
		public synchronized void handleNewValue(ViewChannel sender, Object oldValue, Object newValue) {
			if (_active) {
				listenTo(newValue);
				run();
			}
		}

		@Override
		public void run() {
			if (_active) {
				_sink.publish(Long.valueOf(_changes.incrementAndGet()));
			}
		}
	}

}
