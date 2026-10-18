/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.monitor.DatapointCodec.Dpt;

import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.dptxlator.DPTXlator8BitEnum;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;

/**
 * The values of the datapoint types as data: decoding the data of a telegram into a value and
 * encoding a value into the data, strictly by the KNX standard.
 *
 * <p>
 * The rules are those of the DPT conformance corpus that E-Home shares with HomeControl
 * (<code>src/test/fixtures/dpt-corpus/</code>): a value that does not fit the type is refused, never
 * clamped, truncated or replaced; data that is no value of the type (an invalid code, a field out of
 * its range) is refused, never decoded into something; reserved bits are ignored when decoding and
 * written as 0. Calimero's translators do not refuse in all these cases, so the types the corpus
 * covers are coded here; Calimero still names the values for the user ({@link DatapointCodec}) and
 * codes the other types.
 * </p>
 *
 * <p>
 * The values are those of the corpus:
 * </p>
 * <ul>
 * <li>DPT 1: a {@link Boolean}.</li>
 * <li>DPT 2, 3, 18: a map with {@link #CONTROL} (a {@link Boolean}: the control bit, the direction
 * of a step, the learn bit of a scene) and {@link #VALUE} (the value bit, the step code, the scene
 * number).</li>
 * <li>DPT 4, 16: a {@link String}.</li>
 * <li>DPT 5, 6, 7, 8, 9, 12, 13, 14, 17, 20: a {@link Number}, unscaled except 5.001 (percent), 5.003
 * (degrees), 7.003, 7.004, 8.003, 8.004 (milliseconds) and 8.010 (percent).</li>
 * <li>DPT 10: a map with <code>dayOfWeek</code> (0 no day, 1 Monday to 7 Sunday), <code>hour</code>,
 * <code>minute</code>, <code>second</code>.</li>
 * <li>DPT 11: a map with <code>year</code>, <code>month</code>, <code>day</code>.</li>
 * <li>DPT 19: a map with <code>year</code>, <code>month</code>, <code>day</code>,
 * <code>dayOfWeek</code>, <code>hour</code>, <code>minute</code>, <code>second</code> and the flags
 * {@link #DATE_TIME_FLAGS} as {@link Boolean}s (<code>false</code> when absent).</li>
 * <li>DPT 232.600: a map with <code>red</code>, <code>green</code>, <code>blue</code>, levels of 0 to
 * 255.</li>
 * <li>DPT 251.600: a map with <code>red</code>, <code>green</code>, <code>blue</code>,
 * <code>white</code>, levels of 0 to 255, and {@link #VALID}, the list of the channels whose validity
 * bit is set (all four when absent).</li>
 * </ul>
 *
 * <p>
 * The data of the types embedded in the APCI octet (DPT 1, 2, 3) is the one octet holding the 6 data
 * bits, as {@link BusTelegram#data()} holds it.
 * </p>
 *
 * <p>
 * A main type alone (<code>5</code>, see {@link Dpt#isMainType()}), and a subtype this class has no
 * rule for, is its format without the semantics of a subtype: unscaled, the full range of the
 * format, the characters of ISO 8859-1.
 * </p>
 */
public final class DatapointValues {

	/**
	 * Why a value or data is refused.
	 */
	public enum Status {
		/** The number of octets does not match the type. */
		WRONG_LENGTH,

		/** The value lies outside the range of the type, or a field does not fit at all. */
		OUT_OF_RANGE,

		/** The data is no value of the type: a reserved or invalid code, a field out of range. */
		INVALID;
	}

	/**
	 * The refusal to decode or encode, with its {@link Status}.
	 */
	public static class Refusal extends TopLogicException {

		private final Status _status;

		Refusal(Status status, ResKey message) {
			super(message);
			_status = status;
		}

		/**
		 * Why the value or data is refused.
		 */
		public Status status() {
			return _status;
		}
	}

	/** The control bit of DPT 2, 3 and 18. */
	public static final String CONTROL = "control";

	/** The value of DPT 2, 3 and 18 beside the control bit. */
	public static final String VALUE = "value";

	/** The channels of DPT 251.600 whose validity bit is set. */
	public static final String VALID = "valid";

	/** The flags of a date with time (DPT 19), in the order of their bits. */
	public static final List<String> DATE_TIME_FLAGS = List.of("fault", "workingDay", "noWorkingDay", "noYear",
		"noDate", "noDayOfWeek", "noTime", "summerTime", "clockQuality", "syncReliable");

	private static final List<String> RGB = List.of("red", "green", "blue");

	private static final List<String> RGBW = List.of("red", "green", "blue", "white");

	private static final Set<Integer> MAIN_TYPES =
		Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18, 19, 20, 232, 251);

	private static final int STATUS_MODE = 20;

	private DatapointValues() {
		// Functions only.
	}

	/**
	 * Whether this class codes the values of the given type; the others are left to Calimero.
	 */
	public static boolean supports(Dpt dpt) {
		if (dpt == null || !MAIN_TYPES.contains(dpt.main())) {
			return false;
		}
		// The status with mode (6.020) is no number.
		return !(dpt.main() == 6 && dpt.sub() == STATUS_MODE);
	}

	/**
	 * The value of the given data.
	 *
	 * @throws Refusal
	 *         If the data is no value of the type.
	 */
	public static Object decode(Dpt dpt, byte[] data) {
		check(dpt);
		checkLength(dpt, data);
		int b0 = data.length > 0 ? data[0] & 0xFF : 0;
		switch (dpt.main()) {
			case 1:
				return Boolean.valueOf((b0 & 0x01) != 0);
			case 2:
				return control((b0 & 0x02) != 0, b0 & 0x01);
			case 3:
				return control((b0 & 0x08) != 0, b0 & 0x07);
			case 4:
			case 16:
				return decodeString(dpt, data);
			case 5:
				return decodeU8(dpt, data, b0);
			case 6:
				return Long.valueOf((byte) b0);
			case 7:
				return decodeU16(dpt, unsigned(data));
			case 8:
				return decodeV16(dpt, (short) unsigned(data));
			case 9:
				return decodeFloat16(dpt, data);
			case 10:
				return decodeTime(dpt, data);
			case 11:
				return decodeDate(dpt, data);
			case 12:
				return Long.valueOf(unsigned(data));
			case 13:
				return Long.valueOf((int) unsigned(data));
			case 14:
				return decodeFloat32(dpt, data);
			case 17:
				return Long.valueOf(b0 & 0x3F);
			case 18:
				return control((b0 & 0x80) != 0, b0 & 0x3F);
			case 19:
				return decodeDateTime(dpt, data);
			case 20:
				if (!isElement(dpt, b0)) {
					throw invalid(dpt, data);
				}
				return Long.valueOf(b0);
			case 232:
				return levels(RGB, data);
			case 251:
				return decodeRgbw(data);
			default:
				throw new IllegalStateException("No rule for " + dpt.code());
		}
	}

	/**
	 * The data of the given value: one octet for the types embedded in the APCI octet (DPT 1, 2, 3).
	 *
	 * @throws Refusal
	 *         If the value is no value of the type.
	 */
	public static byte[] encode(Dpt dpt, Object value) {
		check(dpt);
		switch (dpt.main()) {
			case 1:
				if (!(value instanceof Boolean bit)) {
					throw outOfRange(dpt, value);
				}
				return octet(bit.booleanValue() ? 1 : 0);
			case 2:
				return encodeControl(dpt, value, 0x02, 1);
			case 3:
				return encodeControl(dpt, value, 0x08, 7);
			case 4:
			case 16:
				return encodeString(dpt, value);
			case 5:
				return octet(encodeU8(dpt, value));
			case 6:
				return octet((int) integer(dpt, value, -128, 127));
			case 7:
				return bytes(encodeU16(dpt, value), 2);
			case 8:
				return bytes(encodeV16(dpt, value), 2);
			case 9:
				return encodeFloat16(dpt, value);
			case 10:
				return encodeTime(dpt, value);
			case 11:
				return encodeDate(dpt, value);
			case 12:
				return bytes(integer(dpt, value, 0, 0xFFFFFFFFL), 4);
			case 13:
				return bytes(integer(dpt, value, Integer.MIN_VALUE, Integer.MAX_VALUE), 4);
			case 14:
				return encodeFloat32(dpt, value);
			case 17:
				return octet((int) integer(dpt, value, 0, 63));
			case 18:
				return encodeControl(dpt, value, 0x80, 63);
			case 19:
				return encodeDateTime(dpt, value);
			case 20: {
				int element = (int) integer(dpt, value, 0, 255);
				if (!isElement(dpt, element)) {
					throw outOfRange(dpt, value);
				}
				return octet(element);
			}
			case 232:
				return encodeLevels(dpt, value, RGB, Set.of(), new byte[3]);
			case 251:
				return encodeRgbw(dpt, value);
			default:
				throw new IllegalStateException("No rule for " + dpt.code());
		}
	}

	private static void check(Dpt dpt) {
		if (!supports(dpt)) {
			throw new IllegalArgumentException("Not supported: " + dpt);
		}
	}

	/**
	 * The number of octets of the data of the given type.
	 */
	private static int size(Dpt dpt) {
		switch (dpt.main()) {
			case 7:
			case 8:
			case 9:
				return 2;
			case 10:
			case 11:
			case 232:
				return 3;
			case 12:
			case 13:
			case 14:
				return 4;
			case 16:
				return 14;
			case 19:
				return 8;
			case 251:
				return 6;
			default:
				return 1;
		}
	}

	private static void checkLength(Dpt dpt, byte[] data) {
		if (data.length != size(dpt)) {
			throw new Refusal(Status.WRONG_LENGTH, I18NConstants.ERROR_WRONG_LENGTH__DATA_TYPE.fill(hex(data), dpt.code()));
		}
	}

	private static Refusal invalid(Dpt dpt, byte[] data) {
		return new Refusal(Status.INVALID, I18NConstants.ERROR_INVALID_DATA__DATA_TYPE.fill(hex(data), dpt.code()));
	}

	private static Refusal outOfRange(Dpt dpt, Object value) {
		return new Refusal(Status.OUT_OF_RANGE, I18NConstants.ERROR_OUT_OF_RANGE__VALUE_TYPE.fill(value, dpt.code()));
	}

	private static String hex(byte[] data) {
		return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(data);
	}

	private static boolean is(Dpt dpt, int sub) {
		return dpt.sub() == sub;
	}

	// DPT 2, 3, 18

	private static Map<String, Object> control(boolean control, int value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put(CONTROL, Boolean.valueOf(control));
		result.put(VALUE, Integer.valueOf(value));
		return result;
	}

	private static byte[] encodeControl(Dpt dpt, Object value, int controlBit, int max) {
		if (!(value instanceof Map<?, ?> map) || !keys(map, Set.of(CONTROL, VALUE), Set.of())
			|| !(map.get(CONTROL) instanceof Boolean control)) {
			throw outOfRange(dpt, value);
		}
		int number = (int) integer(dpt, map.get(VALUE), 0, max);
		return octet((control.booleanValue() ? controlBit : 0) | number);
	}

	// DPT 4, 16

	/**
	 * Whether the characters of the given type are ASCII (4.001, 16.000), not ISO 8859-1.
	 */
	private static boolean ascii(Dpt dpt) {
		return dpt.main() == 4 ? is(dpt, 1) : is(dpt, 0);
	}

	private static String decodeString(Dpt dpt, byte[] data) {
		int limit = ascii(dpt) ? 0x7F : 0xFF;
		StringBuilder result = new StringBuilder();
		for (byte octet : data) {
			int code = octet & 0xFF;
			if (code == 0) {
				break;
			}
			if (code > limit) {
				throw invalid(dpt, data);
			}
			result.append((char) code);
		}
		return result.toString();
	}

	private static byte[] encodeString(Dpt dpt, Object value) {
		if (!(value instanceof String text)) {
			throw outOfRange(dpt, value);
		}
		int limit = ascii(dpt) ? 0x7F : 0xFF;
		byte[] result = new byte[size(dpt)];
		if (text.length() > result.length) {
			throw outOfRange(dpt, value);
		}
		for (int n = 0; n < text.length(); n++) {
			char ch = text.charAt(n);
			if (ch == 0 || ch > limit) {
				throw outOfRange(dpt, value);
			}
			result[n] = (byte) ch;
		}
		return result;
	}

	// DPT 5

	private static Number decodeU8(Dpt dpt, byte[] data, int octet) {
		if (is(dpt, 1)) {
			return Double.valueOf(octet * 100.0 / 255);
		}
		if (is(dpt, 3)) {
			return Double.valueOf(octet * 360.0 / 255);
		}
		if (is(dpt, 6) && octet == 255) {
			// Tariffs are 0 to 254.
			throw invalid(dpt, data);
		}
		return Long.valueOf(octet);
	}

	private static int encodeU8(Dpt dpt, Object value) {
		if (is(dpt, 1)) {
			return (int) scaled(dpt, value, 0, 100, 255, 100);
		}
		if (is(dpt, 3)) {
			return (int) scaled(dpt, value, 0, 360, 255, 360);
		}
		return (int) integer(dpt, value, 0, is(dpt, 6) ? 254 : 255);
	}

	// DPT 7, 8

	/**
	 * The unit of the data of a type of milliseconds (7.003, 7.004, 8.003, 8.004) in milliseconds, 1
	 * for another type.
	 */
	private static int millis(Dpt dpt) {
		return is(dpt, 3) ? 10 : is(dpt, 4) ? 100 : 1;
	}

	private static Number decodeU16(Dpt dpt, long data) {
		return Long.valueOf(data * millis(dpt));
	}

	private static long encodeU16(Dpt dpt, Object value) {
		int unit = millis(dpt);
		if (unit == 1) {
			return integer(dpt, value, 0, 0xFFFF);
		}
		return scaled(dpt, value, 0, 0xFFFF * unit, 1, unit);
	}

	private static Number decodeV16(Dpt dpt, short data) {
		if (is(dpt, 10)) {
			return Double.valueOf(data / 100.0);
		}
		return Long.valueOf(data * (long) millis(dpt));
	}

	private static long encodeV16(Dpt dpt, Object value) {
		if (is(dpt, 10)) {
			return scaled(dpt, value, -327.68, 327.67, 100, 1);
		}
		int unit = millis(dpt);
		if (unit == 1) {
			return integer(dpt, value, Short.MIN_VALUE, Short.MAX_VALUE);
		}
		return scaled(dpt, value, Short.MIN_VALUE * (double) unit, Short.MAX_VALUE * (double) unit, 1, unit);
	}

	// DPT 9

	private static final double FLOAT16_MIN = -671088.64;

	private static final double FLOAT16_MAX = 670433.28;

	/**
	 * The smallest value of a subtype of DPT 9 whose range is narrower than the one of the format.
	 */
	private static double float16Min(Dpt dpt) {
		int sub = dpt.sub();
		if (sub == 1) {
			// Absolute zero in °C.
			return -273;
		}
		if (sub == 27) {
			// Absolute zero in °F.
			return -459.6;
		}
		if ((sub >= 4 && sub <= 8) || (sub >= 28 && sub <= 30)) {
			return 0;
		}
		return FLOAT16_MIN;
	}

	private static Double decodeFloat16(Dpt dpt, byte[] data) {
		int b0 = data[0] & 0xFF;
		int b1 = data[1] & 0xFF;
		if (b0 == 0x7F && b1 == 0xFF) {
			// The invalid value.
			throw invalid(dpt, data);
		}
		int exponent = (b0 >> 3) & 0x0F;
		int mantissa = ((b0 & 0x80) != 0 ? -2048 : 0) + (((b0 & 0x07) << 8) | b1);
		double value = mantissa * (double) (1 << exponent) / 100;
		if (value < float16Min(dpt)) {
			throw invalid(dpt, data);
		}
		return Double.valueOf(value);
	}

	private static byte[] encodeFloat16(Dpt dpt, Object value) {
		double number = real(dpt, value);
		if (!(number >= Math.max(FLOAT16_MIN, float16Min(dpt)) && number <= FLOAT16_MAX)) {
			throw outOfRange(dpt, value);
		}
		double hundredfold = number * 100;
		int exponent = 0;
		while (hundredfold < -2048 || hundredfold > 2047) {
			hundredfold /= 2;
			exponent++;
		}
		if (exponent > 15) {
			throw outOfRange(dpt, value);
		}
		int mantissa = (int) roundHalfUp(hundredfold);
		return new byte[] {
			(byte) ((mantissa < 0 ? 0x80 : 0) | (exponent << 3) | ((mantissa >> 8) & 0x07)),
			(byte) mantissa };
	}

	// DPT 10, 11, 19

	private static Map<String, Object> decodeTime(Dpt dpt, byte[] data) {
		int dayOfWeek = (data[0] & 0xE0) >> 5;
		int hour = data[0] & 0x1F;
		int minute = data[1] & 0x3F;
		int second = data[2] & 0x3F;
		if (hour > 23 || minute > 59 || second > 59) {
			throw invalid(dpt, data);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("dayOfWeek", Integer.valueOf(dayOfWeek));
		result.put("hour", Integer.valueOf(hour));
		result.put("minute", Integer.valueOf(minute));
		result.put("second", Integer.valueOf(second));
		return result;
	}

	private static byte[] encodeTime(Dpt dpt, Object value) {
		Map<?, ?> map = fields(dpt, value, Set.of("dayOfWeek", "hour", "minute", "second"), Set.of());
		int dayOfWeek = field(dpt, value, map, "dayOfWeek", 0, 7);
		int hour = field(dpt, value, map, "hour", 0, 23);
		int minute = field(dpt, value, map, "minute", 0, 59);
		int second = field(dpt, value, map, "second", 0, 59);
		return new byte[] { (byte) (dayOfWeek << 5 | hour), (byte) minute, (byte) second };
	}

	private static Map<String, Object> decodeDate(Dpt dpt, byte[] data) {
		int day = data[0] & 0x1F;
		int month = data[1] & 0x0F;
		int year = data[2] & 0x7F;
		if (day < 1 || month < 1 || month > 12 || year > 99) {
			throw invalid(dpt, data);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("year", Integer.valueOf(year >= 90 ? 1900 + year : 2000 + year));
		result.put("month", Integer.valueOf(month));
		result.put("day", Integer.valueOf(day));
		return result;
	}

	private static byte[] encodeDate(Dpt dpt, Object value) {
		Map<?, ?> map = fields(dpt, value, Set.of("year", "month", "day"), Set.of());
		int year = field(dpt, value, map, "year", 1990, 2089);
		int month = field(dpt, value, map, "month", 1, 12);
		int day = field(dpt, value, map, "day", 1, 31);
		return new byte[] { (byte) day, (byte) month, (byte) (year % 100) };
	}

	private static Map<String, Object> decodeDateTime(Dpt dpt, byte[] data) {
		int year = 1900 + (data[0] & 0xFF);
		int month = data[1] & 0x0F;
		int day = data[2] & 0x1F;
		int dayOfWeek = (data[3] & 0xE0) >> 5;
		int hour = data[3] & 0x1F;
		int minute = data[4] & 0x3F;
		int second = data[5] & 0x3F;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("year", Integer.valueOf(year));
		result.put("month", Integer.valueOf(month));
		result.put("day", Integer.valueOf(day));
		result.put("dayOfWeek", Integer.valueOf(dayOfWeek));
		result.put("hour", Integer.valueOf(hour));
		result.put("minute", Integer.valueOf(minute));
		result.put("second", Integer.valueOf(second));
		int flags = (data[6] & 0xFF) << 8 | (data[7] & 0xC0);
		for (int n = 0; n < DATE_TIME_FLAGS.size(); n++) {
			result.put(DATE_TIME_FLAGS.get(n), Boolean.valueOf((flags & flagBit(n)) != 0));
		}
		if (!flag(result, "noDate") && (month < 1 || month > 12 || day < 1)) {
			throw invalid(dpt, data);
		}
		if (!flag(result, "noTime") && !validTime(hour, minute, second)) {
			throw invalid(dpt, data);
		}
		return result;
	}

	/**
	 * The bit of the flag with the given index of {@link #DATE_TIME_FLAGS} in the last two octets of
	 * a date with time.
	 */
	private static int flagBit(int index) {
		return 1 << (15 - index);
	}

	private static boolean validTime(int hour, int minute, int second) {
		if (hour == 24) {
			// The end of the day.
			return minute == 0 && second == 0;
		}
		return hour < 24 && minute < 60 && second < 60;
	}

	private static boolean flag(Map<?, ?> map, String name) {
		return Boolean.TRUE.equals(map.get(name));
	}

	private static byte[] encodeDateTime(Dpt dpt, Object value) {
		Map<?, ?> map = fields(dpt, value, Set.of("year", "month", "day", "dayOfWeek", "hour", "minute", "second"),
			Set.copyOf(DATE_TIME_FLAGS));
		int flags = 0;
		for (int n = 0; n < DATE_TIME_FLAGS.size(); n++) {
			Object flag = map.get(DATE_TIME_FLAGS.get(n));
			if (flag != null && !(flag instanceof Boolean)) {
				throw outOfRange(dpt, value);
			}
			if (Boolean.TRUE.equals(flag)) {
				flags |= flagBit(n);
			}
		}
		// A field flagged absent is not checked, but must fit its bits.
		boolean noDate = flag(map, "noDate");
		boolean noTime = flag(map, "noTime");
		int year = field(dpt, value, map, "year", 1900, 2155);
		int month = field(dpt, value, map, "month", noDate ? 0 : 1, noDate ? 15 : 12);
		int day = field(dpt, value, map, "day", noDate ? 0 : 1, 31);
		int dayOfWeek = field(dpt, value, map, "dayOfWeek", 0, 7);
		int hour = field(dpt, value, map, "hour", 0, noTime ? 31 : 24);
		int minute = field(dpt, value, map, "minute", 0, noTime ? 63 : 59);
		int second = field(dpt, value, map, "second", 0, noTime ? 63 : 59);
		if (!noTime && !validTime(hour, minute, second)) {
			throw outOfRange(dpt, value);
		}
		return new byte[] { (byte) (year - 1900), (byte) month, (byte) day, (byte) (dayOfWeek << 5 | hour),
			(byte) minute, (byte) second, (byte) (flags >> 8), (byte) flags };
	}

	// DPT 14

	private static Double decodeFloat32(Dpt dpt, byte[] data) {
		float value = Float.intBitsToFloat((int) unsigned(data));
		if (Float.isNaN(value) || Float.isInfinite(value)) {
			throw invalid(dpt, data);
		}
		return Double.valueOf(value);
	}

	private static byte[] encodeFloat32(Dpt dpt, Object value) {
		float number = (float) real(dpt, value);
		if (Float.isNaN(number) || Float.isInfinite(number)) {
			throw outOfRange(dpt, value);
		}
		return bytes(Float.floatToIntBits(number), 4);
	}

	// DPT 20

	/**
	 * Whether the given number is an element of the enumeration, as far as Calimero knows the
	 * subtype; every number of a byte for a subtype Calimero does not know.
	 */
	private static boolean isElement(Dpt dpt, int element) {
		if (dpt.isMainType()) {
			return true;
		}
		String id = String.format(Locale.ROOT, "%d.%03d", dpt.main(), dpt.sub());
		try {
			if (!TranslatorTypes.hasTranslator(dpt.main(), id)
				|| !(TranslatorTypes.createTranslator(dpt.main(), id) instanceof DPTXlator8BitEnum enumeration)) {
				return true;
			}
			enumeration.setValue(element);
			return true;
		} catch (KNXException | KNXIllegalArgumentException ex) {
			return false;
		}
	}

	// DPT 232, 251

	private static Map<String, Object> levels(List<String> channels, byte[] data) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int n = 0; n < channels.size(); n++) {
			result.put(channels.get(n), Integer.valueOf(data[n] & 0xFF));
		}
		return result;
	}

	private static Map<String, Object> decodeRgbw(byte[] data) {
		Map<String, Object> result = levels(RGBW, data);
		List<String> valid = new ArrayList<>();
		for (int n = 0; n < RGBW.size(); n++) {
			if ((data[5] & (0x08 >> n)) != 0) {
				valid.add(RGBW.get(n));
			}
		}
		result.put(VALID, valid);
		return result;
	}

	private static byte[] encodeLevels(Dpt dpt, Object value, List<String> channels, Set<String> optional,
			byte[] result) {
		Map<?, ?> map = fields(dpt, value, Set.copyOf(channels), optional);
		for (int n = 0; n < channels.size(); n++) {
			result[n] = (byte) field(dpt, value, map, channels.get(n), 0, 255);
		}
		return result;
	}

	private static byte[] encodeRgbw(Dpt dpt, Object value) {
		byte[] result = encodeLevels(dpt, value, RGBW, Set.of(VALID), new byte[6]);
		Object valid = ((Map<?, ?>) value).get(VALID);
		int bits = 0x0F;
		if (valid != null) {
			if (!(valid instanceof List<?> list)) {
				throw outOfRange(dpt, value);
			}
			bits = 0;
			for (Object channel : list) {
				int index = RGBW.indexOf(channel);
				if (index < 0) {
					throw outOfRange(dpt, value);
				}
				bits |= 0x08 >> index;
			}
		}
		result[5] = (byte) bits;
		return result;
	}

	// Numbers and fields

	/**
	 * The given value as a map with the given keys, and no others than the optional ones.
	 */
	private static Map<?, ?> fields(Dpt dpt, Object value, Set<String> required, Set<String> optional) {
		if (!(value instanceof Map<?, ?> map) || !keys(map, required, optional)) {
			throw outOfRange(dpt, value);
		}
		return map;
	}

	private static boolean keys(Map<?, ?> map, Set<String> required, Set<String> optional) {
		for (Object key : map.keySet()) {
			if (!required.contains(key) && !optional.contains(key)) {
				return false;
			}
		}
		return map.keySet().containsAll(required);
	}

	private static int field(Dpt dpt, Object value, Map<?, ?> map, String name, int min, int max) {
		Object field = map.get(name);
		if (!(field instanceof Number number) || !isInteger(number.doubleValue())
			|| number.doubleValue() < min || number.doubleValue() > max) {
			throw outOfRange(dpt, value);
		}
		return number.intValue();
	}

	/**
	 * The given value as a number, refusing a value that is none.
	 */
	private static double real(Dpt dpt, Object value) {
		if (!(value instanceof Number number) || Double.isNaN(number.doubleValue())) {
			throw outOfRange(dpt, value);
		}
		return number.doubleValue();
	}

	/**
	 * The given value as an integer of the given range, refusing a fraction.
	 */
	private static long integer(Dpt dpt, Object value, long min, long max) {
		double number = real(dpt, value);
		if (!isInteger(number) || number < min || number > max) {
			throw outOfRange(dpt, value);
		}
		return (long) number;
	}

	/**
	 * The given value of the given range multiplied by the given numerator, divided by the given
	 * denominator (in this order, so that 50 % is exactly 127.5) and rounded a half up.
	 */
	private static long scaled(Dpt dpt, Object value, double min, double max, int numerator, int denominator) {
		double number = real(dpt, value);
		if (!(number >= min && number <= max)) {
			throw outOfRange(dpt, value);
		}
		return roundHalfUp(number * numerator / denominator);
	}

	private static boolean isInteger(double number) {
		return !Double.isInfinite(number) && number == Math.rint(number);
	}

	private static long roundHalfUp(double number) {
		return (long) Math.floor(number + 0.5);
	}

	private static long unsigned(byte[] data) {
		long result = 0;
		for (byte octet : data) {
			result = result << 8 | (octet & 0xFF);
		}
		return result;
	}

	private static byte[] octet(int value) {
		return new byte[] { (byte) value };
	}

	private static byte[] bytes(long value, int size) {
		byte[] result = new byte[size];
		for (int n = size - 1; n >= 0; n--) {
			result[n] = (byte) value;
			value >>= 8;
		}
		return result;
	}

}
