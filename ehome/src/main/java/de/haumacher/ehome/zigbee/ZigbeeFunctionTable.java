/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.util.List;

import de.haumacher.ehome.runtime.ValueTypes;

/**
 * The functions the controller's cluster mapping knows (homecontrol <code>doc/zigbee.md</code>, the
 * function table; <code>core/src/zigbee/cluster_mapping.cpp</code>): which cluster and attribute is
 * which function, the value type it carries and the cluster its commands go to.
 *
 * <p>
 * The table decides what a Zigbee binding of the runtime image may name
 * ({@link de.haumacher.ehome.runtime.ImageValidator}, as the controller's loader does).
 * </p>
 */
public final class ZigbeeFunctionTable {

	/** The On/Off cluster. */
	public static final int ON_OFF = 0x0006;

	/** The Level Control cluster. */
	public static final int LEVEL_CONTROL = 0x0008;

	/** The key of the function of a client binding: the commands of a button. */
	public static final String ACTION = "action";

	/** The key of the multistate input, which the controller does not read at start. */
	public static final String MULTISTATE = "multistate";

	/**
	 * A function of the table.
	 *
	 * @param key
	 *        The key (<code>temperature</code>), as the export names it.
	 * @param cluster
	 *        The cluster.
	 * @param attribute
	 *        The attribute; <code>null</code> for the commands of a client cluster.
	 * @param valueType
	 *        The value type (the image's names).
	 * @param server
	 *        Whether it is an attribute of a server cluster (not the commands of a client cluster).
	 * @param commandCluster
	 *        The cluster its commands go to; <code>null</code> for a read-only function.
	 */
	public record Info(String key, int cluster, Integer attribute, String valueType, boolean server,
			Integer commandCluster) {

		/**
		 * Whether a binding of the function may send its commands to the given cluster: its own
		 * command cluster, or Level Control for On/Off.
		 */
		public boolean canCommand(int command) {
			return (commandCluster != null && commandCluster.intValue() == command)
				|| ("on_off".equals(key) && command == LEVEL_CONTROL);
		}

		/** Whether the controller reads the value after a start (a server attribute but the multistate input). */
		public boolean readOnStart() {
			return server && !MULTISTATE.equals(key);
		}
	}

	/** The functions in the order of the controller's table. */
	public static final List<Info> FUNCTIONS = List.of(
		new Info("on_off", ON_OFF, 0x0000, ValueTypes.BOOLEAN, true, ON_OFF),
		new Info("level", LEVEL_CONTROL, 0x0000, ValueTypes.REAL, true, LEVEL_CONTROL),
		new Info("color_temperature", 0x0300, 0x0007, ValueTypes.UNSIGNED, true, 0x0300),
		new Info("temperature", 0x0402, 0x0000, ValueTypes.REAL, true, null),
		new Info("humidity", 0x0405, 0x0000, ValueTypes.REAL, true, null),
		new Info("illuminance", 0x0400, 0x0000, ValueTypes.REAL, true, null),
		new Info("occupancy", 0x0406, 0x0000, ValueTypes.BOOLEAN, true, null),
		new Info("ias_zone", 0x0500, 0x0002, ValueTypes.BOOLEAN, true, null),
		new Info("battery", 0x0001, 0x0021, ValueTypes.REAL, true, null),
		new Info(MULTISTATE, 0x0012, 0x0055, ValueTypes.UNSIGNED, true, null),
		new Info(ACTION, ON_OFF, null, ValueTypes.ENUMERATION, false, null));

	private ZigbeeFunctionTable() {
		// Static table.
	}

	/**
	 * The server function of the given cluster and attribute, <code>null</code> for none.
	 */
	public static Info byAttribute(int cluster, int attribute) {
		for (Info info : FUNCTIONS) {
			if (info.server() && info.cluster() == cluster && info.attribute().intValue() == attribute) {
				return info;
			}
		}
		return null;
	}

	/**
	 * The function of the given key, <code>null</code> for an unknown one.
	 */
	public static Info byKey(String key) {
		for (Info info : FUNCTIONS) {
			if (info.key().equals(key)) {
				return info;
			}
		}
		return null;
	}

	/**
	 * The IEEE address in its normalized form (16 lower-case hex digits), <code>null</code> if the
	 * text is none: 16 hex digits, or 8 pairs separated by colons; not 0 and not all ones
	 * (homecontrol <code>parse_ieee</code>).
	 */
	public static String normalizeIeee(String text) {
		if (text == null) {
			return null;
		}
		boolean colons = text.length() == 23;
		if (text.length() != 16 && !colons) {
			return null;
		}
		StringBuilder digits = new StringBuilder(16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (colons && i % 3 == 2) {
				if (c != ':') {
					return null;
				}
				continue;
			}
			if (Character.digit(c, 16) < 0) {
				return null;
			}
			digits.append(Character.toLowerCase(c));
		}
		String result = digits.toString();
		if (result.length() != 16 || result.equals("0000000000000000") || result.equals("ffffffffffffffff")) {
			return null;
		}
		return result;
	}

}
