/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import de.haumacher.ehome.programming.download.BusException;

/**
 * The link of the group monitor to the bus: delivers the group telegrams it receives and sends
 * group telegrams.
 *
 * <p>
 * {@link CalimeroBusLink} is the link through a KNXnet/IP gateway, {@link ControllerBusLink} the
 * monitor stream of a HomeControl controller; tests use a link of their own.
 * </p>
 */
public interface BusLink extends AutoCloseable {

	/**
	 * Receiver of what a {@link BusLink} reports. Called on the link's own thread.
	 */
	interface Listener {

		/**
		 * A group telegram was received.
		 */
		void received(BusTelegram telegram);

		/**
		 * The link was closed from the other side (or failed); nothing is received any more.
		 *
		 * @param reason
		 *        Why, for a person.
		 */
		void closed(String reason);

		/**
		 * An event that is no group telegram was reported (a HomeControl controller reports the
		 * Zigbee traffic, its rules' commands, ...).
		 */
		default void event(LinkEvent event) {
			// Ignored by default.
		}

		/**
		 * The state of the link changed (see {@link BusLink#problem()}).
		 */
		default void stateChanged() {
			// Ignored by default.
		}
	}

	/**
	 * Sets the receiver of the telegrams.
	 */
	void setListener(Listener listener);

	/**
	 * The individual address the link sends with (assigned by the gateway); 0 while it is not known
	 * (before the first telegram was sent).
	 */
	int localAddress();

	/**
	 * What the link is connected to, for a person (the gateway's address).
	 */
	String description();

	/**
	 * Why the link does not deliver right now although it is not closed (it reconnects),
	 * <code>null</code> if it does.
	 */
	default String problem() {
		return null;
	}

	/**
	 * Whether the link is the monitor stream of a HomeControl controller rather than a connection to
	 * a gateway (the monitor names it so).
	 */
	default boolean viaController() {
		return false;
	}

	/**
	 * Whether the link reports the telegrams sent through it as received telegrams (marked
	 * {@link BusTelegram#own()}); otherwise the monitor shows a sent telegram itself.
	 */
	default boolean reportsOwnTelegrams() {
		return false;
	}

	/**
	 * Sends a group telegram.
	 *
	 * @param destination
	 *        The group address.
	 * @param apdu
	 *        The application protocol data unit: service and data.
	 */
	void send(int destination, byte[] apdu) throws BusException;

	/**
	 * Closes the link; nothing is reported afterwards.
	 */
	@Override
	void close();

}
