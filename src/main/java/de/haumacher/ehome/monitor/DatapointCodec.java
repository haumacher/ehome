/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
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
import tuwien.auto.calimero.dptxlator.DPTXlator3BitControlled;
import tuwien.auto.calimero.dptxlator.DPTXlator3BitControlled.DPT3BitControlled;
import tuwien.auto.calimero.dptxlator.DPTXlator8BitEnum;
import tuwien.auto.calimero.dptxlator.DPTXlatorBoolean;
import tuwien.auto.calimero.dptxlator.DPTXlatorDate;
import tuwien.auto.calimero.dptxlator.DPTXlatorDateTime;
import tuwien.auto.calimero.dptxlator.DPTXlatorTime;
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
	 *        The sub number, e.g. 1; 0 for a main type without a subtype.
	 */
	public record Dpt(int main, int sub) {

		/**
		 * The code of the type, e.g. <code>9.001</code>, or <code>9.*</code> for a main type.
		 */
		public String code() {
			return sub == 0 ? main + ".*" : String.format(Locale.ROOT, "%d.%03d", main, sub);
		}

		/**
		 * Reads a datapoint type written as <code>9.001</code>, <code>9</code>, <code>DPST-9-1</code> or
		 * <code>DPT-9</code>.
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
					return new Dpt(Integer.parseInt(parts[1]), parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
				}
				int dot = value.indexOf('.');
				if (dot < 0) {
					return new Dpt(Integer.parseInt(value), 0);
				}
				String sub = value.substring(dot + 1);
				return new Dpt(Integer.parseInt(value.substring(0, dot)), sub.equals("*") ? 0 : Integer.parseInt(sub));
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
		if (dpt == null || !TranslatorTypes.getAllMainTypes().containsKey(dpt.main())) {
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
	 * number of another range (an angle, a tariff), entered as number.
	 */
	private static Kind byteKind(Dpt dpt) {
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
	 * The unit of the given type as Calimero writes it, <code>null</code> if it has none.
	 */
	private static String calimeroUnit(Dpt dpt) {
		if (kind(dpt) == Kind.RAW) {
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
		try {
			DPTXlator translator = translator(dpt);
			int size = Math.max(1, translator.getTypeSize());
			if (data.length != size) {
				return hex + " (?)";
			}
			translator.setData(data);
			return translator.getValue();
		} catch (KNXException | KNXIllegalArgumentException ex) {
			return hex + " (?)";
		}
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
	 *        with its day, and a dictionary with {@link #DIRECTION} ({@link #UP}, {@link #DOWN},
	 *        {@link #STOP}) and {@link #STEP} (the step code 1 to 7, or one of
	 *        {@link #stepSizes()}) for a step.
	 * @return The APDU: the service and the data, a value of up to 6 bits in the short form.
	 * @throws TopLogicException
	 *         If the value is no value of the type.
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
		DPTXlator translator;
		try {
			translator = translator(dpt);
		} catch (KNXException ex) {
			throw new TopLogicException(I18NConstants.ERROR_UNSUPPORTED_DATAPOINT_TYPE__TYPE.fill(dpt.code()), ex);
		}
		try {
			if (kind == Kind.SWITCH) {
				translator.setData(new byte[] { (byte) (bit(translator, value) ? 1 : 0) });
			} else if (value instanceof Map<?, ?> map && translator instanceof DPTXlator3BitControlled step) {
				setStep(step, map);
			} else if (value instanceof Map<?, ?> map && translator instanceof DPTXlatorTime time) {
				setTime(time, map.get(DAY), map.get(TIME));
			} else if (value instanceof Date date && translator instanceof DPTXlatorTime time) {
				setTime(time, null, date);
			} else if (value instanceof Date date && translator instanceof DPTXlatorDate day) {
				LocalDateTime local = local(date);
				day.setValue(local.getYear(), local.getMonthValue(), local.getDayOfMonth());
			} else if (value instanceof Date date && translator instanceof DPTXlatorDateTime dateTime) {
				dateTime.setValue(local(date).withNano(0));
			} else if (value instanceof Number number) {
				translator.setValue(number.doubleValue());
			} else {
				String text = value.toString().trim();
				if (kind == Kind.NUMBER || kind == Kind.PERCENT || kind == Kind.SCALE) {
					text = text.replace(',', '.');
				}
				translator.setValue(text);
			}
		} catch (KNXException | KNXIllegalArgumentException ex) {
			throw new TopLogicException(
				I18NConstants.ERROR_INVALID_VALUE__VALUE_TYPE_DETAIL.fill(text(value), dpt.code(), ex.getMessage()), ex);
		}
		byte[] data = translator.getData();
		if (translator.getTypeSize() == 0) {
			return DataUnitBuilder.createLengthOptimizedAPDU(BusTelegram.Service.WRITE.code(), data);
		}
		return DataUnitBuilder.createAPDU(BusTelegram.Service.WRITE.code(), data);
	}

	/**
	 * Sets a step: the direction {@link #UP} is the upper value of the control (increase) for a
	 * dimmer and the lower one (up) for a blind, whose control (DPT 1.008) counts downwards; a
	 * {@link #STOP} is the step code 0 with the control bit 0, as Calimero writes a break.
	 */
	private static void setStep(DPTXlator3BitControlled translator, Map<?, ?> value) {
		Object direction = value.get(DIRECTION);
		if (STOP.equals(direction)) {
			translator.setValue(false, 0);
			return;
		}
		boolean up = UP.equals(direction);
		if (!up && !DOWN.equals(direction)) {
			throw new TopLogicException(I18NConstants.ERROR_NO_DIRECTION);
		}
		int code = stepCode(value.get(STEP));
		DPT control = ((DPT3BitControlled) translator.getType()).getControlDPT();
		boolean controlBit = blinds(control) ? !up : up;
		translator.setValue(controlBit, code);
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

	private static void setTime(DPTXlatorTime translator, Object day, Object time) {
		if (!(time instanceof Date date)) {
			throw new TopLogicException(I18NConstants.ERROR_NO_VALUE);
		}
		LocalDateTime local = local(date);
		translator.setValue(day(day), local.getHour(), local.getMinute(), local.getSecond());
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
			if (sub != 0 && TranslatorTypes.hasTranslator(dpt.main(), id)) {
				return TranslatorTypes.createTranslator(dpt.main(), id);
			}
		}
		return TranslatorTypes.createTranslator(dpt.main(), 0);
	}

}
