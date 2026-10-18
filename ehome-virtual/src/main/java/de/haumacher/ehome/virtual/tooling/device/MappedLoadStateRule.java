/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.tooling.device;

import de.haumacher.ehome.programming.download.LoadControls;

/**
 * How a simulated device with memory-mapped load state machines (BIM M112, masks 0700 and 0701)
 * answers the reads of its load states (memory B6EA to B6ED).
 *
 * <p>
 * What such a device really answers after each load control is not known: ETS's traces against
 * the virtual device are to find it out (haumacher/ehome #22). The rule is therefore a choice, not
 * a claim.
 * </p>
 */
public enum MappedLoadStateRule {

	/** The state the last load control implies (the load state machine of the KNX specification). */
	ECHO(-1),

	/** Always "loaded". */
	LOADED(LoadControls.STATE_LOADED),

	/** Always "loading". */
	LOADING(LoadControls.STATE_LOADING),

	/** Always "unloaded". */
	UNLOADED(LoadControls.STATE_UNLOADED),

	/** Always "error". */
	ERROR(LoadControls.STATE_ERROR);

	private final int _fixed;

	MappedLoadStateRule(int fixed) {
		_fixed = fixed;
	}

	/**
	 * The state answered for a load state machine in the given state.
	 */
	public int answer(int state) {
		return _fixed < 0 ? state : _fixed;
	}

}
