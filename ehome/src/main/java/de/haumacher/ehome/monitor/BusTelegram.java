/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.HexFormat;

/**
 * A group telegram seen on the bus (or sent by E-Home itself): what the link layer tells about it,
 * not yet decoded.
 *
 * @param time
 *        When the telegram was received (or sent), in milliseconds since the epoch.
 * @param source
 *        The individual address of the sender (16 bit).
 * @param destination
 *        The group address (16 bit).
 * @param service
 *        The service of the telegram.
 * @param priority
 *        The priority, 0 (system), 1 (normal), 2 (urgent) or 3 (low), as on the bus.
 * @param repeated
 *        Whether the telegram is a repetition.
 * @param hopCount
 *        The routing counter.
 * @param data
 *        The application data (the ASDU): for a value of up to 6 bits sent in the short form one byte
 *        with the value, empty for a read.
 * @param own
 *        Whether E-Home sent this telegram.
 * @param name
 *        The name of the datapoint the link reports for the address (a HomeControl controller names the
 *        datapoint of its runtime image), <code>null</code> if none.
 * @param value
 *        The value the link reports (a HomeControl controller decodes the telegram by the datapoint
 *        of its runtime image) in the JSON form of {@link DatapointValues}, <code>null</code> if
 *        none.
 */
public record BusTelegram(long time, int source, int destination, Service service, int priority, boolean repeated,
		int hopCount, byte[] data, boolean own, String name, Object value) {

	/**
	 * Creates a {@link BusTelegram} the link reports no datapoint for.
	 */
	public BusTelegram(long time, int source, int destination, Service service, int priority, boolean repeated,
			int hopCount, byte[] data, boolean own) {
		this(time, source, destination, service, priority, repeated, hopCount, data, own, null, null);
	}

	/** The priority of telegrams of the system. */
	public static final int PRIORITY_SYSTEM = 0;

	/** The priority low, the priority of normal process communication. */
	public static final int PRIORITY_LOW = 3;

	/** The routing counter a telegram is sent with. */
	public static final int DEFAULT_HOP_COUNT = 6;

	/**
	 * The service of a group telegram.
	 */
	public enum Service {
		/** A value written to the address (A_GroupValue_Write). */
		WRITE("write", 0x80),
		/** A request to answer with the value of the address (A_GroupValue_Read). */
		READ("read", 0x00),
		/** The answer to a read (A_GroupValue_Response). */
		RESPONSE("response", 0x40);

		private final String _classifier;

		private final int _code;

		Service(String classifier, int code) {
			_classifier = classifier;
			_code = code;
		}

		/**
		 * The name of the classifier of <code>ehome.monitor:TelegramService</code>.
		 */
		public String classifier() {
			return _classifier;
		}

		/**
		 * The application layer service code (APCI).
		 */
		public int code() {
			return _code;
		}

		/**
		 * The service with the given application layer service code, <code>null</code> for a service
		 * that is no group value service.
		 */
		public static Service ofCode(int code) {
			for (Service service : values()) {
				if (service._code == code) {
					return service;
				}
			}
			return null;
		}
	}

	/**
	 * The data as hexadecimal bytes separated by spaces, e.g. <code>0C 33</code>.
	 */
	public String hex() {
		return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(data);
	}

	/**
	 * The individual address of the sender in its notation area.line.device.
	 */
	public String sourceText() {
		return individualAddress(source);
	}

	/**
	 * An individual address in its notation area.line.device.
	 */
	public static String individualAddress(int address) {
		return (address >>> 12) + "." + ((address >>> 8) & 0x0F) + "." + (address & 0xFF);
	}

	/**
	 * The name of the classifier of <code>ehome.monitor:TelegramPriority</code> for the priority.
	 */
	public String priorityName() {
		switch (priority) {
			case 0:
				return "system";
			case 1:
				return "normal";
			case 2:
				return "urgent";
			default:
				return "low";
		}
	}

}
