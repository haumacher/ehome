/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.dptxlator.DPT;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.DPTXlator3BitControlled.DPT3BitControlled;
import tuwien.auto.calimero.dptxlator.DPTXlator8BitEnum;
import tuwien.auto.calimero.dptxlator.DPTXlatorBoolean;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;

/**
 * The values of group telegrams by the datapoint type of their address: decoding the data of a
 * received telegram into a text with its unit ("21.5 °C", "on", "73 %") and encoding a value to send
 * into the telegram's data, with the DPT translators of Calimero.
 *
 * <p>
 * The {@link Kind} of a type decides the input of the send form; whatever the input produces (a
 * number, a text, a choice, a point in time, a step) is encoded here by the translator of the type,
 * the one path every value takes to the bus.
 * </p>
 */
public final class DatapointCodec {

	/**
	 * How a value of a datapoint type is entered.
	 */
	public enum Kind {
		/** A value of one bit (DPT 1): two values to choose from. */
		SWITCH("switch"),
		/**
		 * A step of a dimmer or a blind (DPT 3): up or down by a step code of 1 to 7, or a stop.
		 */
		STEP("step"),
		/** A percentage of 0 to 100 % in one byte (DPT 5.001). */
		PERCENT("percent"),
		/** A number of 0 to 255 in one byte (DPT 5.004, 5.005, 5.010). */
		SCALE("scale"),
		/** One of the named values of an enumeration (DPT 20). */
		CHOICE("choice"),
		/** A time of day with an optional day of the week (DPT 10). */
		TIME("time"),
		/** A date (DPT 11). */
		DATE("date"),
		/** A date with a time of day (DPT 19). */
		DATE_TIME("datetime"),
		/** A number, possibly with a unit (DPT 5 to 9, 12 to 14, 17). */
		NUMBER("number"),
		/** A value written as text in the notation of the datapoint type. */
		TEXT("text"),
		/** No datapoint type: the data as hexadecimal bytes. */
		RAW("raw");

		private final String _name;

		Kind(String name) {
			_name = name;
		}

		/**
		 * The name of the kind as the views test it.
		 */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * A datapoint type by its numbers.
	 *
	 * @param main
	 *        The main number, e.g. 9.
	 * @param sub
	 *        The sub number, e.g. 1 (0 is a subtype of its own, e.g. 16.000); {@link #MAIN_TYPE} for a
	 *        main type without a subtype.
	 */
	public record Dpt(int main, int sub) {

		/** The {@link #sub()} number of a main type without a subtype. */
		public static final int MAIN_TYPE = -1;

		/**
		 * The main type with the given number, without a subtype.
		 */
		public static Dpt mainType(int main) {
			return new Dpt(main, MAIN_TYPE);
		}

		/**
		 * Whether this is a main type without a subtype.
		 */
		public boolean isMainType() {
			return sub == MAIN_TYPE;
		}

		/**
		 * The code of the type, e.g. <code>9.001</code>, or <code>9.*</code> for a main type.
		 */
		public String code() {
			return isMainType() ? main + ".*" : String.format(Locale.ROOT, "%d.%03d", main, sub);
		}

		/**
		 * Reads a datapoint type written as <code>9.001</code>, <code>9</code>, <code>9.*</code>,
		 * <code>DPST-9-1</code> or <code>DPT-9</code>; <code>16.000</code> is the subtype 0, not the main
		 * type.
		 *
		 * @return The type, <code>null</code> for an empty text.
		 * @throws TopLogicException
		 *         If the text is no datapoint type.
		 */
		public static Dpt parse(String text) {
			if (text == null || text.isBlank()) {
				return null;
			}
			String value = text.trim().toUpperCase(Locale.ROOT);
			try {
				if (value.startsWith("DPST-") || value.startsWith("DPT-")) {
					String[] parts = value.split("-");
					return new Dpt(Integer.parseInt(parts[1]), parts.length > 2 ? Integer.parseInt(parts[2]) : MAIN_TYPE);
				}
				int dot = value.indexOf('.');
				if (dot < 0) {
					return mainType(Integer.parseInt(value));
				}
				String sub = value.substring(dot + 1);
				return new Dpt(Integer.parseInt(value.substring(0, dot)), sub.equals("*") ? MAIN_TYPE : Integer.parseInt(sub));
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
				throw new TopLogicException(I18NConstants.ERROR_INVALID_DATAPOINT_TYPE__TEXT.fill(text));
			}
		}
	}

	/** The step codes of a step (DPT 3): 1 (100 %) to 7 (1.56 %). */
	public static final int MAX_STEP_CODE = 7;

	/** The value of a step (DPT 3), see {@link #encodeWrite(Dpt, Object)}: the direction. */
	public static final String DIRECTION = "direction";

	/** The value of a step (DPT 3), see {@link #encodeWrite(Dpt, Object)}: the step code. */
	public static final String STEP = "step";

	/** {@link #DIRECTION} of a step upwards: brighter for a dimmer, up for a blind. */
	public static final String UP = "up";

	/** {@link #DIRECTION} of a step downwards: darker for a dimmer, down for a blind. */
	public static final String DOWN = "down";

	/** {@link #DIRECTION} of a stop (the step code 0). */
	public static final String STOP = "stop";

	/** The value of a time of day (DPT 10), see {@link #encodeWrite(Dpt, Object)}: the day. */
	public static final String DAY = "day";

	/** The value of a time of day (DPT 10), see {@link #encodeWrite(Dpt, Object)}: the time. */
	public static final String TIME = "time";

	private DatapointCodec() {
		// Functions only.
	}

	/**
	 * How a value of the given type is entered.
	 */
	public static Kind kind(Dpt dpt) {
		if (dpt == null
			|| !TranslatorTypes.getAllMainTypes().containsKey(dpt.main()) && !DatapointValues.supports(dpt)) {
			return Kind.RAW;
		}
		switch (dpt.main()) {
			case 1:
				return Kind.SWITCH;
			case 3:
				return Kind.STEP;
			case 5:
				return byteKind(dpt);
			case 10:
				return Kind.TIME;
			case 11:
				return Kind.DATE;
			case 19:
				return Kind.DATE_TIME;
			case 6:
			case 7:
			case 8:
			case 9:
			case 12:
			case 13:
			case 14:
			case 17:
				return Kind.NUMBER;
			default:
				return isEnumeration(dpt) || NamedValues.has(dpt) ? Kind.CHOICE : Kind.TEXT;
		}
	}

	/**
	 * The kind of an unsigned byte (DPT 5): a percentage, a number of the full byte range, or a
	 * number of another range (an angle, a tariff), entered as number. The main type alone is a
	 * number of the full byte range, unscaled.
	 */
	private static Kind byteKind(Dpt dpt) {
		if (dpt.isMainType()) {
			return Kind.SCALE;
		}
		try {
			DPT type = translator(dpt).getType();
			if (type.getID().equals("5.001")) {
				return Kind.PERCENT;
			}
			if (type.getLowerValue().equals("0") && type.getUpperValue().equals("255")) {
				return Kind.SCALE;
			}
		} catch (KNXException ex) {
			// Entered as number.
		}
		return Kind.NUMBER;
	}

	private static boolean isEnumeration(Dpt dpt) {
		try {
			return translator(dpt) instanceof DPTXlator8BitEnum;
		} catch (KNXException ex) {
			return false;
		}
	}

	/**
	 * The range of a value of the given type entered with a slider ({@link Kind#PERCENT},
	 * {@link Kind#SCALE}): the smallest and the largest value; <code>null</code> for another type.
	 */
	public static int[] range(Dpt dpt) {
		Kind kind = kind(dpt);
		if (kind != Kind.PERCENT && kind != Kind.SCALE) {
			return null;
		}
		if (dpt.isMainType()) {
			return new int[] { 0, 255 };
		}
		try {
			DPT type = translator(dpt).getType();
			return new int[] { Integer.parseInt(type.getLowerValue()), Integer.parseInt(type.getUpperValue()) };
		} catch (KNXException | NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * The unit of the values of the given type for the user, e.g. "%", "°C"; empty if they have
	 * none.
	 *
	 * <p>
	 * Calimero writes most units as symbols, which are the same in every language; where it writes a
	 * word ("counter pulses", "fan stage"), the word in the user's language, and none where the word
	 * says nothing a number does not (a "ratio") or is the notation of a value rather than its unit
	 * ("yyyy-mm-dd").
	 * </p>
	 */
	public static String unit(Dpt dpt) {
		String unit = calimeroUnit(dpt);
		if (unit == null) {
			return "";
		}
		ResKey word = UNIT_WORDS.get(unit);
		if (word != null) {
			return Resources.getInstance().getString(word);
		}
		return isWord(unit) ? "" : unit;
	}

	/**
	 * The sentence telling the user the range of the values of the given type, with its unit.
	 *
	 * <p>
	 * A unit Calimero writes as a word has a sentence of its own, since a word does not fit into the
	 * sentence of a symbol in every language; a symbol ({@link #unit(Dpt)}) follows the bounds.
	 * </p>
	 */
	public static ResKey rangeText(Dpt dpt, int min, int max) {
		Integer from = Integer.valueOf(min);
		Integer to = Integer.valueOf(max);
		String unit = calimeroUnit(dpt);
		ResKey2 sentence = unit == null ? null : UNIT_RANGES.get(unit);
		if (sentence != null) {
			return sentence.fill(from, to);
		}
		String symbol = unit(dpt);
		return symbol.isEmpty() ? I18NConstants.TARGET_RANGE__MIN_MAX.fill(from, to)
			: I18NConstants.TARGET_RANGE__MIN_MAX_UNIT.fill(from, to, symbol);
	}

	/**
	 * The unit of the given type as Calimero writes it, <code>null</code> if it has none. A main type
	 * alone has none: its values are unscaled, without the unit of a subtype.
	 */
	private static String calimeroUnit(Dpt dpt) {
		if (kind(dpt) == Kind.RAW || dpt.isMainType()) {
			return null;
		}
		try {
			return translator(dpt).getType().getUnit();
		} catch (KNXException ex) {
			return null;
		}
	}

	/**
	 * The sentences of the ranges of values whose unit Calimero writes as a word.
	 */
	private static final Map<String, ResKey2> UNIT_RANGES = Map.of(
		"counter pulses", I18NConstants.TARGET_RANGE_PULSES__MIN_MAX,
		"pulses", I18NConstants.TARGET_RANGE_PULSES__MIN_MAX,
		"fan stage", I18NConstants.TARGET_RANGE_FAN_STAGES__MIN_MAX);

	/**
	 * The units Calimero writes as words, by their translation.
	 */
	private static final Map<String, ResKey> UNIT_WORDS = Map.of(
		"counter pulses", I18NConstants.UNIT_PULSES,
		"pulses", I18NConstants.UNIT_PULSES,
		"fan stage", I18NConstants.UNIT_FAN_STAGE);

	/**
	 * Whether a unit of Calimero is a word or a notation rather than a symbol: it has four letters
	 * in a row in lower case (a symbol has at most three, e.g. "min", "ppm") or describes a format
	 * ("dow, hh:mm:ss").
	 */
	private static boolean isWord(String unit) {
		return unit.contains(":") || unit.matches(".*\\p{Ll}{4,}.*");
	}

	/**
	 * The named values of an enumeration ({@link Kind#CHOICE}), ordered by their numbers; each is a
	 * value {@link #encodeWrite(Dpt, Object)} takes. Empty for another type.
	 *
	 * <p>
	 * The values are those the imported master data name (<code>ehome.datapoint:NamedValue</code>,
	 * ETS 5.5 and later), with their texts in the user's language, else in English, else as Calimero
	 * names the value. For a subtype without named values in the master data (ETS 4), they come from
	 * the enumerations of the KNX standard that Calimero implements, as Calimero writes them (e.g.
	 * "building in use").
	 * </p>
	 */
	public static List<String> choices(Dpt dpt) {
		if (kind(dpt) != Kind.CHOICE) {
			return Collections.emptyList();
		}
		List<NamedValues.Named> named =
			NamedValues.of(dpt, Resources.getInstance().getLocale(), number -> calimeroName(dpt, number));
		if (!named.isEmpty()) {
			return named.stream().map(NamedValues.Named::text).toList();
		}
		try {
			DPTXlator8BitEnum translator = (DPTXlator8BitEnum) translator(dpt);
			List<String> result = new ArrayList<>();
			for (int element = 0; element <= 255; element++) {
				try {
					translator.setValue(element);
				} catch (KNXException ex) {
					continue;
				}
				result.add(translator.getValue());
			}
			return result;
		} catch (KNXException ex) {
			return Collections.emptyList();
		}
	}

	/**
	 * The name Calimero gives the value with the given number of an enumeration, the number itself if
	 * Calimero does not know it.
	 */
	private static String calimeroName(Dpt dpt, int number) {
		try {
			if (translator(dpt) instanceof DPTXlator8BitEnum translator) {
				translator.setValue(number);
				return translator.getValue();
			}
		} catch (KNXException ex) {
			// Not known to Calimero.
		}
		return Integer.toString(number);
	}

	/**
	 * The APDU of a group write of the value of an enumeration named in the master data, <code>null</code>
	 * if the given text names no such value.
	 */
	private static byte[] encodeNamedValue(Dpt dpt, Object value) {
		if (!(value instanceof String text)) {
			return null;
		}
		Integer number = NamedValues.valueOf(dpt, text);
		if (number == null) {
			return null;
		}
		Integer size = NamedValues.sizeInBit(dpt);
		byte[] data = { (byte) number.intValue() };
		if (size != null && size.intValue() <= 6) {
			return DataUnitBuilder.createLengthOptimizedAPDU(BusTelegram.Service.WRITE.code(), data);
		}
		return DataUnitBuilder.createAPDU(BusTelegram.Service.WRITE.code(), data);
	}

	/**
	 * The step sizes of a step ({@link Kind#STEP}) to choose from, one per step code 1 to
	 * {@value #MAX_STEP_CODE}, e.g. "1: 100 %", "4: 12.5 %" (the percentage in the user's
	 * language); each is a step {@link #encodeWrite(Dpt, Object)} takes.
	 */
	public static List<String> stepSizes() {
		NumberFormat format = NumberFormat.getNumberInstance(Resources.getInstance().getLocale());
		format.setMaximumFractionDigits(2);
		List<String> result = new ArrayList<>();
		for (int code = 1; code <= MAX_STEP_CODE; code++) {
			result.add(code + ": " + format.format(100.0 / (1 << (code - 1))) + " %");
		}
		return result;
	}

	/**
	 * What the directions of a step ({@link Kind#STEP}) do, for the user: what {@link #UP} and
	 * {@link #DOWN} send for a dimmer ("brighter", "darker") or a blind ("up", "down"), with the
	 * control bit each sends; <code>null</code> for another type.
	 */
	public static ResKey stepDirections(Dpt dpt) {
		if (kind(dpt) != Kind.STEP) {
			return null;
		}
		try {
			DPT control = ((DPT3BitControlled) translator(dpt).getType()).getControlDPT();
			return blinds(control) ? I18NConstants.TARGET_STEP_BLINDS : I18NConstants.TARGET_STEP_DIMMING;
		} catch (KNXException ex) {
			return null;
		}
	}

	/**
	 * Whether the control of a step is the one of a blind (DPT 1.008), whose value 1 is "down".
	 */
	private static boolean blinds(DPT control) {
		return control.getID().equals(DPTXlatorBoolean.DPT_UPDOWN.getID());
	}

	/**
	 * The days of a time of day ({@link Kind#TIME}) to choose from, in the user's language: "no
	 * day" (the day 0), then Monday (1) to Sunday (7); each is a day
	 * {@link #encodeWrite(Dpt, Object)} takes.
	 */
	public static List<String> days() {
		Locale locale = Resources.getInstance().getLocale();
		List<String> result = new ArrayList<>();
		result.add(Resources.getInstance().getString(I18NConstants.NO_DAY));
		for (DayOfWeek day : DayOfWeek.values()) {
			result.add(day.getDisplayName(TextStyle.FULL, locale));
		}
		return result;
	}

	/**
	 * The day of the week of the given point in time in the user's time zone, as {@link #days()}
	 * offers it.
	 */
	public static String day(Date date) {
		return days().get(local(date).getDayOfWeek().getValue());
	}

	/**
	 * The two values of a one-bit type, the value 0 first, e.g. "off", "on"; <code>null</code> for
	 * another type.
	 */
	public static String[] switchValues(Dpt dpt) {
		if (kind(dpt) != Kind.SWITCH) {
			return null;
		}
		try {
			DPTXlator translator = translator(dpt);
			return new String[] { translator.getType().getLowerValue(), translator.getType().getUpperValue() };
		} catch (KNXException ex) {
			return null;
		}
	}

	/**
	 * The value of a telegram decoded by the given type, with its unit.
	 *
	 * @param dpt
	 *        The datapoint type of the address, <code>null</code> if unknown.
	 * @param service
	 *        The service of the telegram.
	 * @param data
	 *        The data of the telegram, see {@link BusTelegram#data()}.
	 * @return The value as text; the data as hexadecimal bytes where there is no type or the data does
	 *         not fit it; empty for a read.
	 */
	public static String decode(Dpt dpt, BusTelegram.Service service, byte[] data) {
		if (service == BusTelegram.Service.READ) {
			return "";
		}
		String hex = HexFormat.ofDelimiter(" ").withUpperCase().formatHex(data);
		if (kind(dpt) == Kind.RAW) {
			return hex;
		}
		if (DatapointValues.supports(dpt)) {
			Object value;
			try {
				value = DatapointValues.decode(dpt, data);
			} catch (DatapointValues.Refusal ex) {
				return refused(hex, ex.status() == DatapointValues.Status.WRONG_LENGTH
					? I18NConstants.DECODED_WRONG_LENGTH__TYPE.fill(dpt.code())
					: I18NConstants.DECODED_INVALID__TYPE.fill(dpt.code()));
			}
			return text(dpt, value);
		}
		try {
			DPTXlator translator = translator(dpt);
			int size = Math.max(1, translator.getTypeSize());
			if (data.length != size) {
				return refused(hex, I18NConstants.DECODED_WRONG_LENGTH__TYPE.fill(dpt.code()));
			}
			translator.setData(data);
			return translator.getValue();
		} catch (KNXException | KNXIllegalArgumentException | IllegalStateException ex) {
			return refused(hex, I18NConstants.DECODED_INVALID__TYPE.fill(dpt.code()));
		}
	}

	/**
	 * The data that could not be decoded, with the reason.
	 */
	private static String refused(String hex, ResKey reason) {
		return hex + " (" + Resources.getInstance().getString(reason) + ")";
	}

	/**
	 * The value of a telegram decoded by the given type, see {@link DatapointValues#decode(Dpt, byte[])}.
	 *
	 * @throws DatapointValues.Refusal
	 *         If the data is no value of the type.
	 * @throws IllegalArgumentException
	 *         If the values of the type are not decoded by {@link DatapointValues}, see
	 *         {@link DatapointValues#supports(Dpt)}.
	 */
	public static Object decodeValue(Dpt dpt, byte[] data) {
		return DatapointValues.decode(dpt, data);
	}

	/**
	 * The data of a telegram with the given value, see {@link DatapointValues#encode(Dpt, Object)}.
	 *
	 * @throws DatapointValues.Refusal
	 *         If the value is no value of the type.
	 * @throws IllegalArgumentException
	 *         If the values of the type are not encoded by {@link DatapointValues}, see
	 *         {@link DatapointValues#supports(Dpt)}.
	 */
	public static byte[] encodeValue(Dpt dpt, Object value) {
		return DatapointValues.encode(dpt, value);
	}

	/**
	 * The text of a value decoded by {@link DatapointValues}: as Calimero writes it with its unit,
	 * from the data the value encodes to (without reserved bits); a number of a main type alone
	 * without a unit, a character string as it is.
	 */
	private static String text(Dpt dpt, Object value) {
		if (value instanceof String string) {
			return string;
		}
		if (value instanceof Number number && dpt.isMainType()) {
			return numberText(dpt, number);
		}
		try {
			DPTXlator translator = translator(dpt);
			translator.setData(DatapointValues.encode(dpt, value));
			return translator.getValue();
		} catch (KNXException | KNXIllegalArgumentException | IllegalStateException ex) {
			return value instanceof Number number ? numberText(dpt, number) : String.valueOf(value);
		}
	}

	private static String numberText(Dpt dpt, Number number) {
		if (number instanceof Long) {
			return number.toString();
		}
		String digits = dpt.main() == 14 ? Float.toString(number.floatValue()) : Double.toString(number.doubleValue());
		return new BigDecimal(digits).stripTrailingZeros().toPlainString();
	}

	/**
	 * The application data unit of a group write of the given value.
	 *
	 * @param dpt
	 *        The datapoint type of the address, <code>null</code> to send the value as hexadecimal
	 *        bytes.
	 * @param value
	 *        The value: a text in the notation of the type, a number, a boolean for a one-bit type,
	 *        the name of a value of an enumeration ({@link #choices(Dpt)}), a {@link Date} for a date
	 *        or a time of day (read in the user's time zone), a dictionary with {@link #DAY} (one of
	 *        {@link #days()} or its number 0 to 7) and {@link #TIME} (a {@link Date}) for a time of day
	 *        with its day, a dictionary with {@link #DIRECTION} ({@link #UP}, {@link #DOWN},
	 *        {@link #STOP}) and {@link #STEP} (the step code 1 to 7, or one of
	 *        {@link #stepSizes()}) for a step, or a value as {@link DatapointValues} codes it.
	 * @return The APDU: the service and the data, a value of up to 6 bits in the short form.
	 * @throws TopLogicException
	 *         If the value is no value of the type; a value outside the range of the type is
	 *         refused, never clamped, rounded to an integer or replaced.
	 */
	public static byte[] encodeWrite(Dpt dpt, Object value) {
		if (value == null || value.toString().isBlank()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_VALUE);
		}
		Kind kind = kind(dpt);
		if (kind == Kind.RAW) {
			byte[] data = hex(value.toString());
			return DataUnitBuilder.createAPDU(BusTelegram.Service.WRITE.code(), data);
		}
		if (kind == Kind.CHOICE) {
			byte[] named = encodeNamedValue(dpt, value);
			if (named != null) {
				return named;
			}
		}
		if (DatapointValues.supports(dpt)) {
			byte[] data;
			try {
				data = DatapointValues.encode(dpt, toValue(dpt, kind, value));
			} catch (DatapointValues.Refusal ex) {
				throw new TopLogicException(I18NConstants.ERROR_OUT_OF_RANGE__VALUE_TYPE.fill(text(value), dpt.code()), ex);
			}
			if (dpt.main() <= 3) {
				return DataUnitBuilder.createLengthOptimizedAPDU(BusTelegram.Service.WRITE.code(), data);
			}
			return DataUnitBuilder.createAPDU(BusTelegram.Service.WRITE.code(), data);
		}
		DPTXlator translator = calimero(dpt);
		try {
			if (value instanceof Number number) {
				translator.setValue(number.doubleValue());
			} else {
				String text = value.toString().trim();
				if (kind == Kind.NUMBER || kind == Kind.PERCENT || kind == Kind.SCALE) {
					text = text.replace(',', '.');
				}
				translator.setValue(text);
			}
		} catch (KNXException | KNXIllegalArgumentException ex) {
			throw invalidValue(dpt, value, ex);
		}
		byte[] data = translator.getData();
		if (translator.getTypeSize() == 0) {
			return DataUnitBuilder.createLengthOptimizedAPDU(BusTelegram.Service.WRITE.code(), data);
		}
		return DataUnitBuilder.createAPDU(BusTelegram.Service.WRITE.code(), data);
	}

	private static DPTXlator calimero(Dpt dpt) {
		try {
			return translator(dpt);
		} catch (KNXException ex) {
			throw new TopLogicException(I18NConstants.ERROR_UNSUPPORTED_DATAPOINT_TYPE__TYPE.fill(dpt.code()), ex);
		}
	}

	private static TopLogicException invalidValue(Dpt dpt, Object value, Exception ex) {
		return new TopLogicException(
			I18NConstants.ERROR_INVALID_VALUE__VALUE_TYPE_DETAIL.fill(text(value), dpt.code(), ex.getMessage()), ex);
	}

	/**
	 * The value of the send form as {@link DatapointValues} codes it.
	 */
	private static Object toValue(Dpt dpt, Kind kind, Object value) {
		switch (dpt.main()) {
			case 1:
				if (value instanceof Boolean) {
					return value;
				}
				try {
					return Boolean.valueOf(bit(calimero(dpt), value));
				} catch (KNXException ex) {
					throw invalidValue(dpt, value, ex);
				}
			case 3:
				if (value instanceof Map<?, ?> map && map.containsKey(DIRECTION)) {
					return step(dpt, map);
				}
				break;
			case 10:
				if (value instanceof Map<?, ?> map && (map.containsKey(DAY) || map.containsKey(TIME))) {
					return time(map.get(DAY), map.get(TIME));
				}
				if (value instanceof Date date) {
					return time(null, date);
				}
				break;
			case 11:
				if (value instanceof Date date) {
					LocalDateTime local = local(date);
					return Map.of("year", local.getYear(), "month", local.getMonthValue(), "day", local.getDayOfMonth());
				}
				break;
			case 19:
				if (value instanceof Date date) {
					return dateTime(date);
				}
				break;
			case 4:
			case 16:
				return value.toString();
			default:
				break;
		}
		if (value instanceof Map || value instanceof Number || value instanceof Boolean) {
			return value;
		}
		String text = value.toString().trim();
		if (kind == Kind.NUMBER || kind == Kind.PERCENT || kind == Kind.SCALE || kind == Kind.CHOICE) {
			BigDecimal number = number(dpt, text);
			if (number != null) {
				return Double.valueOf(number.doubleValue());
			}
			if (kind != Kind.CHOICE) {
				throw invalidValue(dpt, value, new NumberFormatException(text));
			}
		}
		// A text in the notation of Calimero.
		DPTXlator translator = calimero(dpt);
		try {
			translator.setValue(text);
			return DatapointValues.decode(dpt, translator.getData());
		} catch (KNXException | KNXIllegalArgumentException ex) {
			throw invalidValue(dpt, value, ex);
		}
	}

	/**
	 * The number written in the given text, with a decimal point or comma and optionally the unit of
	 * the type; <code>null</code> if the text is no number.
	 */
	private static BigDecimal number(Dpt dpt, String text) {
		String unit = calimeroUnit(dpt);
		String digits = unit != null && !unit.isEmpty() && text.endsWith(unit)
			? text.substring(0, text.length() - unit.length()).trim()
			: text;
		try {
			return new BigDecimal(digits.replace(',', '.'));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * A step: the direction {@link #UP} is the upper value of the control (increase) for a dimmer and
	 * the lower one (up) for a blind, whose control (DPT 1.008) counts downwards; a {@link #STOP} is
	 * the step code 0 with the control bit 0, as Calimero writes a break.
	 */
	private static Map<String, Object> step(Dpt dpt, Map<?, ?> value) {
		Object direction = value.get(DIRECTION);
		if (STOP.equals(direction)) {
			return Map.of(DatapointValues.CONTROL, Boolean.FALSE, DatapointValues.VALUE, Integer.valueOf(0));
		}
		boolean up = UP.equals(direction);
		if (!up && !DOWN.equals(direction)) {
			throw new TopLogicException(I18NConstants.ERROR_NO_DIRECTION);
		}
		int code = stepCode(value.get(STEP));
		DPT control = ((DPT3BitControlled) calimero(dpt).getType()).getControlDPT();
		boolean controlBit = blinds(control) ? !up : up;
		return Map.of(DatapointValues.CONTROL, Boolean.valueOf(controlBit), DatapointValues.VALUE, Integer.valueOf(code));
	}

	private static int stepCode(Object step) {
		if (step instanceof Number number) {
			return checkStepCode(number.intValue(), step);
		}
		if (step == null || step.toString().isBlank()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_STEP);
		}
		String text = step.toString().trim();
		int end = 0;
		while (end < text.length() && Character.isDigit(text.charAt(end))) {
			end++;
		}
		if (end == 0) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_STEP__VALUE.fill(text));
		}
		return checkStepCode(Integer.parseInt(text.substring(0, end)), text);
	}

	private static int checkStepCode(int code, Object step) {
		if (code < 1 || code > MAX_STEP_CODE) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_STEP__VALUE.fill(step));
		}
		return code;
	}

	private static Map<String, Object> time(Object day, Object time) {
		if (!(time instanceof Date date)) {
			throw new TopLogicException(I18NConstants.ERROR_NO_VALUE);
		}
		LocalDateTime local = local(date);
		return Map.of("dayOfWeek", day(day), "hour", local.getHour(), "minute", local.getMinute(), "second",
			local.getSecond());
	}

	/**
	 * A date with a time of day (DPT 19) of the given point in time in the user's time zone: with its
	 * day of the week and whether it is in summer time; whether it is a working day is not known.
	 */
	private static Map<String, Object> dateTime(Date date) {
		LocalDateTime local = local(date);
		Map<String, Object> result = new HashMap<>();
		result.put("year", local.getYear());
		result.put("month", local.getMonthValue());
		result.put("day", local.getDayOfMonth());
		result.put("dayOfWeek", local.getDayOfWeek().getValue());
		result.put("hour", local.getHour());
		result.put("minute", local.getMinute());
		result.put("second", local.getSecond());
		result.put("noWorkingDay", Boolean.TRUE);
		result.put("summerTime", Boolean.valueOf(ThreadContext.getTimeZone().inDaylightTime(date)));
		return result;
	}

	/**
	 * The number of a day of a time of day: 0 for no day, 1 (Monday) to 7 (Sunday).
	 */
	private static int day(Object day) {
		if (day == null || day.toString().isBlank()) {
			return 0;
		}
		if (day instanceof Number number && number.intValue() >= 0 && number.intValue() <= 7) {
			return number.intValue();
		}
		int index = days().indexOf(day.toString().trim());
		if (index < 0) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_DAY__VALUE.fill(day));
		}
		return index;
	}

	/**
	 * The given point in time in the user's time zone, the one the date and time pickers write it
	 * in.
	 */
	private static LocalDateTime local(Date date) {
		TimeZone zone = ThreadContext.getTimeZone();
		return LocalDateTime.ofInstant(date.toInstant(), zone.toZoneId());
	}

	/**
	 * The value for a message: a point in time in the user's time zone, else its text.
	 */
	private static Object text(Object value) {
		if (value instanceof Date date) {
			return local(date).withNano(0).toString().replace('T', ' ');
		}
		return value;
	}

	/**
	 * The application data unit of a group read.
	 */
	public static byte[] encodeRead() {
		return new byte[2];
	}

	/**
	 * The data of a telegram with the given APDU, as {@link BusTelegram#data()} holds it.
	 */
	public static byte[] data(byte[] apdu) {
		if (DataUnitBuilder.getAPDUService(apdu) == BusTelegram.Service.READ.code()) {
			return new byte[0];
		}
		return DataUnitBuilder.extractASDU(apdu);
	}

	private static boolean bit(DPTXlator translator, Object value) throws KNXException {
		if (value instanceof Boolean flag) {
			return flag.booleanValue();
		}
		if (value instanceof Number number) {
			return number.doubleValue() != 0;
		}
		String text = value.toString().trim();
		if (text.equalsIgnoreCase(translator.getType().getUpperValue())) {
			return true;
		}
		if (text.equalsIgnoreCase(translator.getType().getLowerValue())) {
			return false;
		}
		switch (text.toLowerCase(Locale.ROOT)) {
			case "1":
			case "true":
			case "on":
			case "ein":
				return true;
			case "0":
			case "false":
			case "off":
			case "aus":
				return false;
			default:
				throw new KNXException("expected " + translator.getType().getLowerValue() + " or "
					+ translator.getType().getUpperValue());
		}
	}

	private static byte[] hex(String text) {
		try {
			return HexFormat.of().parseHex(text.replaceAll("[\\s:-]", ""));
		} catch (IllegalArgumentException ex) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_HEX__VALUE.fill(text), ex);
		}
	}

	/**
	 * The translator of the given type; for a main type, or a subtype Calimero does not know, the
	 * translator of the first subtype of the main type (e.g. 1.001 for 1.*).
	 */
	static DPTXlator translator(Dpt dpt) throws KNXException {
		for (int sub : new int[] { dpt.sub(), 1 }) {
			String id = String.format(Locale.ROOT, "%d.%03d", dpt.main(), sub);
			if (sub >= 0 && TranslatorTypes.hasTranslator(dpt.main(), id)) {
				return TranslatorTypes.createTranslator(dpt.main(), id);
			}
		}
		return TranslatorTypes.createTranslator(dpt.main(), 0);
	}

}
