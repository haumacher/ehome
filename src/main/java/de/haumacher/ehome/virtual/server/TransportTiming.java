/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.server;

import java.time.Duration;

/**
 * The timing of the transport layer of the virtual device.
 *
 * <p>
 * The device has no bus whose timing it must keep, so it waits generously for a slow programming
 * tool or a slow host: many repetitions of an unacknowledged service and a long idle time before it
 * ends a connection. The time before a repetition stays the KNX value (3 s): a tool that missed an
 * answer gets it again before its own wait for the answer ends (E-Home waits 5 s). A timer that
 * fires is recorded in the trace.
 * </p>
 *
 * @param ackTimeout
 *        How long the device waits for the acknowledgement of a service it sent before sending it
 *        again.
 * @param repetitions
 *        How often it sends a service again before it ends the connection.
 * @param connectionTimeout
 *        How long a connection may be idle before the device ends it.
 * @param disconnectOnRestart
 *        Whether the device ends the connection itself after a basic restart (sends
 *        <code>T_Disconnect</code>). The KNX specification allows both; which real devices do is not
 *        known (to be checked on hardware), so the default is not to, and the choice is written into
 *        the header of the trace.
 */
public record TransportTiming(Duration ackTimeout, int repetitions, Duration connectionTimeout,
		boolean disconnectOnRestart) {

	/** The timing of the application: 3 s, 10 repetitions, 60 s idle, no disconnect on a restart. */
	public static final TransportTiming DEFAULT = new TransportTiming(Duration.ofSeconds(3), 10, Duration.ofSeconds(60), false);

	/**
	 * The same timing, with the given choice of ending the connection after a restart.
	 */
	public TransportTiming withDisconnectOnRestart(boolean disconnect) {
		return new TransportTiming(ackTimeout, repetitions, connectionTimeout, disconnect);
	}

	/**
	 * The timing for a person (the header of a trace).
	 */
	public String describe() {
		return "repetition after " + ackTimeout.toMillis() + " ms, " + repetitions + " repetitions, idle timeout "
			+ connectionTimeout.toSeconds() + " s, disconnect after restart: " + (disconnectOnRestart ? "yes" : "no");
	}

}
