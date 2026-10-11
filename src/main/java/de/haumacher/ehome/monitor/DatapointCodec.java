/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.HexFormat;
import java.util.Locale;

import com.top_logic.util.error.TopLogicException;

import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.dptxlator.DPTXlator;
import tuwien.auto.calimero.dptxlator.TranslatorTypes;

/**
 * The values of group telegrams by the datapoint type of their address: decoding the data of a
 * received telegram into a text with its unit ("21.5 °C", "on", "73 %") and encoding a value to send
 * into the telegram's data, with the DPT translators of Calimero.
 */
public final class DatapointCodec {

	/**
	 * How a value of a datapoint type is entered.
	 */
	public enum Kind {
		/** A value of one bit (DPT 1): two values to choose from. */
		SWITCH("switch"),
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
			case 5:
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
				return Kind.TEXT;
		}
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
	 *        The value: a text in the notation of the type, a number, or a boolean for a one-bit type.
	 * @return The APDU: the service and the data, a value of up to 6 bits in the short form.
	 * @throws TopLogicException
	 *         If the value is no value of the type.
	 */
	public static byte[] encodeWrite(Dpt dpt, Object value) {
		if (value == null || value.toString().isBlank()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_VALUE);
		}
		if (kind(dpt) == Kind.RAW) {
			byte[] data = hex(value.toString());
			return DataUnitBuilder.createAPDU(BusTelegram.Service.WRITE.code(), data);
		}
		DPTXlator translator;
		try {
			translator = translator(dpt);
		} catch (KNXException ex) {
			throw new TopLogicException(I18NConstants.ERROR_UNSUPPORTED_DATAPOINT_TYPE__TYPE.fill(dpt.code()), ex);
		}
		try {
			if (kind(dpt) == Kind.SWITCH) {
				translator.setData(new byte[] { (byte) (bit(translator, value) ? 1 : 0) });
			} else if (value instanceof Number number) {
				translator.setValue(number.doubleValue());
			} else {
				String text = value.toString().trim();
				if (kind(dpt) == Kind.NUMBER) {
					text = text.replace(',', '.');
				}
				translator.setValue(text);
			}
		} catch (KNXException | KNXIllegalArgumentException ex) {
			throw new TopLogicException(
				I18NConstants.ERROR_INVALID_VALUE__VALUE_TYPE_DETAIL.fill(value, dpt.code(), ex.getMessage()), ex);
		}
		byte[] data = translator.getData();
		if (translator.getTypeSize() == 0) {
			return DataUnitBuilder.createLengthOptimizedAPDU(BusTelegram.Service.WRITE.code(), data);
		}
		return DataUnitBuilder.createAPDU(BusTelegram.Service.WRITE.code(), data);
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
