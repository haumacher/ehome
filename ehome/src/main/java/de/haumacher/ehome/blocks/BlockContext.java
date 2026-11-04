/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Val.DateTime;

/**
 * What a call of a {@link BlockType} may use (homecontrol <code>spec/blocks.md</code> 3.1). Valid
 * only during the call.
 */
public interface BlockContext {

	/**
	 * An input as a call reads it (<code>spec/blocks.md</code> 5.3).
	 *
	 * @param value
	 *        The value as a number (B6).
	 * @param quality
	 *        Good, stale or unknown.
	 * @param updatedMs
	 *        The monotonic time of the latest update of the datapoints combined.
	 * @param bound
	 *        Whether the port is bound.
	 */
	record InputValue(double value, Quality quality, long updatedMs, boolean bound) {

		/** An unbound input. */
		public static final InputValue UNBOUND = new InputValue(0, Quality.UNKNOWN, 0, false);

		/** Whether the quality is good. */
		public boolean good() {
			return quality == Quality.GOOD;
		}

		/** Good and true (a boolean input). */
		public boolean isTrue() {
			return quality == Quality.GOOD && value != 0;
		}
	}

	/** The cascade time, monotonic ms. */
	long now();

	/** The local wall time with its day of the week (1 Monday .. 7 Sunday); <code>null</code> while not synchronized. */
	DateTime wall();

	/** An input. */
	InputValue read(int port);

	/** The number of datapoints bound to an input (<code>spec/blocks.md</code> B20); 0 when unbound. */
	int count(int port);

	/**
	 * The k-th datapoint of an input's binding as a single-datapoint input reads it, whatever its
	 * quality; not bound and unknown outside 0..<code>count(port)</code>-1.
	 */
	InputValue readAt(int port, int k);

	/** A parameter's current value. */
	double param(int index);

	/** Writes an output (<code>spec/blocks.md</code> 5.4); an invalid value faults the instance. */
	void write(int port, double value);

	/** Starts or restarts a timer <code>ms</code> after the cascade time (1 ms .. 30 d, else a fault). */
	void setTimer(int timer, long ms);

	/** Stops a timer. */
	void cancelTimer(int timer);

	/** Whether the timer runs. */
	boolean timerRunning(int timer);

	/** The state record: one double per manifest state field, read and written in place. */
	double[] state();

	/** A log line of the block (code <code>log</code>). */
	void log(String text);

	/** Stops the instance (<code>spec/blocks.md</code> 7); the rest of the call is ignored. */
	void fault(String reason);

}
