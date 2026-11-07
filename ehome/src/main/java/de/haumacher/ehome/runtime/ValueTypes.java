/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.List;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;

/**
 * The value types of the controller's datapoints and the KNX datapoint types it supports, as the
 * controller's codec maps them (homecontrol <code>doc/datapoints.md</code>, "DPT codecs", and
 * <code>core/src/dpt.cpp</code>).
 */
public final class ValueTypes {

	/** A truth value (DPT 1). */
	public static final String BOOLEAN = "boolean";

	/** An unsigned integer. */
	public static final String UNSIGNED = "unsigned";

	/** A signed integer. */
	public static final String SIGNED = "signed";

	/** A floating point number. */
	public static final String REAL = "real";

	/** A text. */
	public static final String STRING = "string";

	/** A time of day (DPT 10). */
	public static final String TIME_OF_DAY = "timeOfDay";

	/** A date (DPT 11). */
	public static final String DATE = "date";

	/** A date with time (DPT 19). */
	public static final String DATE_TIME = "dateTime";

	/** An element number of an enumeration (DPT 20). */
	public static final String ENUMERATION = "enumeration";

	/** A control bit with a small number (DPT 2, 3, 18). */
	public static final String CONTROLLED = "controlled";

	/** A color (232.600, 251.600). */
	public static final String COLOR = "color";

	/** Bytes without a type. */
	public static final String RAW = "raw";

	/**
	 * All value types, in the order the controller lists them.
	 */
	public static final List<String> ALL = List.of(BOOLEAN, UNSIGNED, SIGNED, REAL, STRING, TIME_OF_DAY, DATE,
		DATE_TIME, ENUMERATION, RAW, CONTROLLED, COLOR);

	private ValueTypes() {
		// Static helpers.
	}

	/**
	 * Whether the value type is a number: numbers convert into each other (and into an enumeration).
	 */
	public static boolean isNumeric(String type) {
		return UNSIGNED.equals(type) || SIGNED.equals(type) || REAL.equals(type) || ENUMERATION.equals(type);
	}

	/**
	 * Why the controller does not support the datapoint type, <code>null</code> if it does.
	 *
	 * @param sub
	 *        The sub number, 0 for the main type alone.
	 */
	public static String unsupported(int main, int sub) {
		Unsupported unsupported = unsupportedType(main, sub);
		return unsupported == null ? null : unsupported.text();
	}

	/**
	 * Why the controller does not support the datapoint type, <code>null</code> if it does.
	 *
	 * @param sub
	 *        The sub number, 0 for the main type alone.
	 */
	public static Unsupported unsupportedType(int main, int sub) {
		switch (main) {
			case 6:
				return sub == 20 ? Unsupported.STATUS_WITH_MODE : null;
			case 232:
				return sub == 0 || sub == 600 ? null : Unsupported.RGB;
			case 251:
				return sub == 0 || sub == 600 ? null : Unsupported.RGBW;
			default:
				return valueType(main, sub) == null ? Unsupported.MAIN_TYPE : null;
		}
	}

	/**
	 * Why the controller does not support a datapoint type.
	 */
	public enum Unsupported {
		/** 6.020. */
		STATUS_WITH_MODE("6.020 (status with mode) is not implemented", I18NConstants.IMAGE_DPT_6020_UNSUPPORTED__DPT),
		/** 232 other than 232.600. */
		RGB("of main type 232 only 232.600 (RGB) is implemented", I18NConstants.IMAGE_DPT_232_UNSUPPORTED__DPT),
		/** 251 other than 251.600. */
		RGBW("of main type 251 only 251.600 (RGBW) is implemented", I18NConstants.IMAGE_DPT_251_UNSUPPORTED__DPT),
		/** A main type without codec. */
		MAIN_TYPE("main type not implemented", I18NConstants.IMAGE_DPT_MAIN_UNSUPPORTED__DPT);

		private final String _text;

		private final ResKey1 _message;

		Unsupported(String text, ResKey1 message) {
			_text = text;
			_message = message;
		}

		/**
		 * The English reason, as the controller gives it.
		 */
		public String text() {
			return _text;
		}

		/**
		 * The whole sentence for the user about the datapoint type as written.
		 */
		public ResKey message(String dpt) {
			return _message.fill(dpt);
		}
	}

	/**
	 * The value type of the datapoint type, <code>null</code> for one the controller does not
	 * support.
	 *
	 * @param sub
	 *        The sub number, 0 for the main type alone.
	 */
	public static String valueType(int main, int sub) {
		switch (main) {
			case 1:
				return BOOLEAN;
			case 2:
			case 3:
			case 18:
				return CONTROLLED;
			case 4:
			case 16:
				return STRING;
			case 5:
				return sub == 1 || sub == 3 ? REAL : UNSIGNED;
			case 6:
				return sub == 20 ? null : SIGNED;
			case 7:
			case 12:
			case 17:
				return UNSIGNED;
			case 8:
				return sub == 10 ? REAL : SIGNED;
			case 13:
				return SIGNED;
			case 9:
			case 14:
				return REAL;
			case 10:
				return TIME_OF_DAY;
			case 11:
				return DATE;
			case 19:
				return DATE_TIME;
			case 20:
				return ENUMERATION;
			case 232:
			case 251:
				return sub == 0 || sub == 600 ? COLOR : null;
			default:
				return null;
		}
	}

}
