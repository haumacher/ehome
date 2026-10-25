/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.view.ProjectSource;

/**
 * Source of the <code>refresh</code> channel of the simulation of a project (haumacher/ehome #62,
 * <code>projects/simulation.view.xml</code>): every change of the simulation (it starts or stops, a
 * telegram arrives, the clock moves, a rule acts) publishes a new value, from which the view
 * computes the state of the simulation again. The channel takes a value at most every 500 ms (its
 * <code>update-interval</code>), so a burst of telegrams or a clock running at 60 times real time
 * is one update per interval.
 *
 * <p>
 * It listens to the simulation of the project on the <code>project</code> channel
 * ({@link Simulations#addListener(TLObject, Runnable)}) while the view is displayed.
 * </p>
 */
public class SimulationSource extends ProjectSource {

	/**
	 * Configuration of {@link SimulationSource}.
	 */
	public interface Config extends ProjectSource.Config<SimulationSource> {

		@Override
		@ClassDefault(SimulationSource.class)
		Class<? extends SimulationSource> getImplementationClass();
	}

	/**
	 * Creates a {@link SimulationSource} from configuration.
	 */
	@CalledByReflection
	public SimulationSource(InstantiationContext context, Config config) {
		super(config.getProject());
	}

	@Override
	protected Runnable listen(TLObject project, Runnable listener) {
		Simulations.getInstance().addListener(project, listener);
		return () -> Simulations.getInstance().removeListener(project, listener);
	}

}
