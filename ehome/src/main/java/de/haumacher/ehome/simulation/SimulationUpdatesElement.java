/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.view.UIElement;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.view.ProjectUpdatesElement;
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
 * When it is displayed again, it updates once ({@link ProjectUpdatesElement}).
 * </p>
 */
public class SimulationUpdatesElement extends ProjectUpdatesElement {

	/** The shortest time in milliseconds between two updates of the view. */
	public static final long UPDATE_INTERVAL = 500;

	/**
	 * Configuration of {@link SimulationUpdatesElement}.
	 */
	@TagName("simulation-updates")
	public interface Config extends ProjectUpdatesElement.Config {

		@Override
		@ClassDefault(SimulationUpdatesElement.class)
		Class<? extends UIElement> getImplementationClass();
	}

	/**
	 * Creates a {@link SimulationUpdatesElement} from configuration.
	 */
	@CalledByReflection
	public SimulationUpdatesElement(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	protected long updateInterval() {
		return UPDATE_INTERVAL;
	}

	@Override
	protected Runnable subscribe(TLObject project, Runnable listener) {
		Simulations.getInstance().addListener(project, listener);
		return () -> Simulations.getInstance().removeListener(project, listener);
	}

}
