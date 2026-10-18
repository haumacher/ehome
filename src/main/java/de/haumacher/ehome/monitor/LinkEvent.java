/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.Map;

/**
 * An event a {@link BusLink} reports that is no group telegram: what a HomeControl controller
 * reports besides the KNX bus (homecontrol <code>spec/api.md</code>, "Monitor").
 *
 * @param time
 *        When it happened, in milliseconds since the epoch.
 * @param kind
 *        The kind of the event.
 * @param fields
 *        The members of the event as the controller sent them (e.g. <code>ieee</code>,
 *        <code>rule</code>, <code>origin</code>, <code>count</code>).
 * @param name
 *        The name of the datapoint the event concerns, <code>null</code> if none.
 * @param value
 *        The value the event carries, in the JSON form of {@link DatapointValues}; <code>null</code> if
 *        none.
 */
public record LinkEvent(long time, Kind kind, Map<String, Object> fields, String name, Object value) {

	/**
	 * The kind of a {@link LinkEvent}.
	 */
	public enum Kind {
		/** A Zigbee frame: an attribute report, an action of a device or a command sent to one. */
		ZIGBEE("zigbee"),
		/** A rule commanded a datapoint. */
		RULE("rule"),
		/** A datapoint was commanded from outside the rules (the controller's UI, its API). */
		COMMAND("command"),
		/** A runtime image was loaded or unloaded, or the model rebuilt. */
		IMAGE("image"),
		/** Events were lost: the monitor fell behind. */
		GAP("gap"),
		/** The controller restarted while the monitor reconnected; its events start anew. */
		RESET("reset"),
		/** An event of a kind this version does not know. */
		OTHER("other");

		private final String _name;

		Kind(String name) {
			_name = name;
		}

		/**
		 * The name of the kind in the controller's events.
		 */
		public String externalName() {
			return _name;
		}

		/**
		 * The kind of the given name; {@link #OTHER} for an unknown one.
		 */
		public static Kind of(String name) {
			for (Kind kind : values()) {
				if (kind._name.equals(name) && kind != RESET) {
					return kind;
				}
			}
			return OTHER;
		}
	}

}
