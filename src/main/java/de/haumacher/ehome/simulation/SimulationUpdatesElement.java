/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.control.IReactControl;
import com.top_logic.layout.react.control.ReactControl;
import com.top_logic.layout.view.ContainerElement;
import com.top_logic.layout.view.UIElement;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelRef;
import com.top_logic.layout.view.channel.ChannelRefFormat;
import com.top_logic.layout.view.channel.ViewChannel;
import com.top_logic.layout.view.element.ContentControls;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.view.WindowUpdates;

/**
 * Shows its content and, while it is displayed, keeps a view of the simulation of a project up to
 * date (haumacher/ehome #62): every change of the simulation (it starts or stops, a telegram
 * arrives, the clock moves, a rule acts) writes a new value to the <code>refresh</code> channel, from
 * which the view computes the state of the simulation again. The updates come at most every
 * {@link #UPDATE_INTERVAL} ms ({@link WindowUpdates}), so a burst of telegrams or a clock running at
 * 60 times real time is one update per interval.
 *
 * <p>
 * The element listens to the simulation of the project on the <code>project</code> channel
 * ({@link Simulations#addListener(TLObject, Runnable)}) while its content is attached (displayed),
 * follows a change of the channel, and stops listening when the content is detached or disposed.
 * When it is displayed again, it updates once.
 * </p>
 */
public class SimulationUpdatesElement extends ContainerElement {

	/** The shortest time in milliseconds between two updates of the view. */
	public static final long UPDATE_INTERVAL = 500;

	/**
	 * Configuration of {@link SimulationUpdatesElement}.
	 */
	@TagName("simulation-updates")
	public interface Config extends ContainerElement.Config {

		@Override
		@ClassDefault(SimulationUpdatesElement.class)
		Class<? extends UIElement> getImplementationClass();

		/**
		 * The channel holding the project whose simulation is shown.
		 */
		@Name("project")
		@Mandatory
		@Format(ChannelRefFormat.class)
		ChannelRef getProject();

		/**
		 * The channel written on every change of the simulation.
		 */
		@Name("refresh")
		@Mandatory
		@Format(ChannelRefFormat.class)
		ChannelRef getRefresh();
	}

	private final ChannelRef _project;

	private final ChannelRef _refresh;

	/**
	 * Creates a {@link SimulationUpdatesElement} from configuration.
	 */
	@CalledByReflection
	public SimulationUpdatesElement(InstantiationContext context, Config config) {
		super(context, config);
		_project = config.getProject();
		_refresh = config.getRefresh();
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
	 * The listener of the simulation of one displayed view.
	 */
	private static final class Watch extends WindowUpdates implements Runnable, ViewChannel.ChannelListener {

		private final ViewChannel _projectChannel;

		private final ViewChannel _refresh;

		private TLObject _project;

		private boolean _attached;

		private long _updates;

		Watch(ViewContext context, ViewChannel project, ViewChannel refresh) {
			super(context, UPDATE_INTERVAL);
			_projectChannel = project;
			_refresh = refresh;
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
			if (_project != null) {
				Simulations.getInstance().removeListener(_project, this);
				_project = null;
			}
			if (value instanceof TLObject project && project.tValid()) {
				_project = project;
				Simulations.getInstance().addListener(project, this);
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
			_refresh.set(Long.valueOf(++_updates));
		}

		@Override
		protected void windowGone() {
			detach();
		}
	}

}
