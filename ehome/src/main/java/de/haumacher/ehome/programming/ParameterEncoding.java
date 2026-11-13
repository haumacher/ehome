/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.DateType;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.IPAddressType;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.model.operational.VoidType;

/**
 * The binary form of parameter values, as ETS writes them into the memory of a device or into a
 * property value.
 *
 * <p>
 * A value is encoded into {@link Bits}: a number of bits and their value. {@link #write(byte[], int,
 * int, Bits, boolean)} places them into memory at a byte offset and a bit offset, where the bit
 * offset counts from the most significant bit of the byte (ETS: <code>BitOffset</code> 0 is the bit
 * 0x80, 7 the bit 0x01). A value of several bytes is written most significant byte first
 * (<code>ParameterByteOrder</code> BigEndian) unless the program asks for little endian.
 * </p>
 *
 * <p>
 * The encodings, as verified against the memory ETS loaded into the devices of
 * <code>samples/EHomeDemo.knxproj</code> where the samples contain the kind:
 * </p>
 * <ul>
 * <li>Integer and time (in the unit of the type): two's complement in the size of the type
 * (verified: 1, 2, 3, 4, 8, 14 and 16 bits, at bit offsets within a byte and spanning bytes).</li>
 * <li>Enumeration: the value of the entry in the size of the type (verified); an entry of a binary
 * enumeration writes its bytes.</li>
 * <li>Floating point: DPT 9 (the 2-byte KNX float), IEEE 754 single or double precision.</li>
 * <li>Text: the characters in the text encoding of the program, padded with zero bytes to the size
 * of the type, the last byte zero if the program asks for zero termination.</li>
 * <li>Date: DPT 11 (day, month, year of the century); IP address: the address bytes.</li>
 * </ul>
 * <p>
 * No sample contains a floating point, date, IP address or binary enumeration parameter: those
 * encodings follow the KNX datapoint types and the ETS schema, not a device read-back.
 * </p>
 */
public class ParameterEncoding {

	/** The text encoding of text parameters if the program names none: the one of DPT 16.001. */
	public static final Charset DEFAULT_TEXT_ENCODING = StandardCharsets.ISO_8859_1;

	/**
	 * An encoded value: its size in bits and the value of these bits (non-negative, less than
	 * <code>2^size</code>).
	 */
	public record Bits(int size, BigInteger value) {

		/**
		 * Creates {@link Bits}, checking that the value fits.
		 */
		public Bits {
			if (size <= 0) {
				throw new ValueException(I18NConstants.ENCODE_NO_BITS__SIZE.fill(size));
			}
			if (value.signum() < 0 || value.bitLength() > size) {
				throw new ValueException(I18NConstants.ENCODE_TOO_LARGE__VALUE_SIZE.fill(String.valueOf(value), size));
			}
		}

		/**
		 * The value as bytes, most significant first, right-aligned in as many bytes as the size
		 * needs.
		 */
		public byte[] toBytes() {
			byte[] result = new byte[(size + 7) / 8];
			byte[] raw = value.toByteArray();
			int copy = Math.min(raw.length, result.length);
			System.arraycopy(raw, raw.length - copy, result, result.length - copy, copy);
			return result;
		}
	}

	/**
	 * An unsigned integer of the given size.
	 */
	public static Bits unsigned(long value, int size) {
		if (value < 0) {
			throw new ValueException(I18NConstants.ENCODE_NEGATIVE__VALUE.fill(String.valueOf(value)));
		}
		return new Bits(size, BigInteger.valueOf(value));
	}

	/**
	 * A signed integer of the given size in two's complement.
	 */
	public static Bits signed(long value, int size) {
		BigInteger big = BigInteger.valueOf(value);
		BigInteger limit = BigInteger.ONE.shiftLeft(size - 1);
		if (big.compareTo(limit.negate()) < 0 || big.compareTo(limit) >= 0) {
			throw new ValueException(I18NConstants.ENCODE_SIGNED_RANGE__VALUE_SIZE.fill(String.valueOf(value), size));
		}
		return new Bits(size, big.signum() < 0 ? big.add(BigInteger.ONE.shiftLeft(size)) : big);
	}

	/**
	 * A 2-byte KNX float (DPT 9): <code>value = 0.01 * M * 2^E</code> with a 12-bit two's complement
	 * mantissa <code>M</code> (its sign in the most significant bit) and a 4-bit exponent
	 * <code>E</code>, the smallest exponent the mantissa fits with.
	 */
	public static Bits dpt9(double value) {
		if (Double.isNaN(value) || value < -671088.64 || value > 670760.96) {
			throw new ValueException(I18NConstants.ENCODE_DPT9_RANGE__VALUE.fill(String.valueOf(value)));
		}
		double scaled = value * 100;
		int exponent = 0;
		long mantissa = Math.round(scaled);
		while (mantissa < -2048 || mantissa > 2047) {
			exponent++;
			mantissa = Math.round(scaled / (1 << exponent));
		}
		long bits = (mantissa < 0 ? 0x8000 : 0) | (exponent << 11) | (mantissa & 0x7FF);
		return new Bits(16, BigInteger.valueOf(bits));
	}

	/**
	 * An IEEE 754 single precision float (4 bytes).
	 */
	public static Bits ieee754Single(double value) {
		return new Bits(32, BigInteger.valueOf(Float.floatToIntBits((float) value) & 0xFFFFFFFFL));
	}

	/**
	 * An IEEE 754 double precision float (8 bytes).
	 */
	public static Bits ieee754Double(double value) {
		return new Bits(64, new BigInteger(1, BigInteger.valueOf(Double.doubleToLongBits(value)).toByteArray()));
	}

	/**
	 * A text of the given size in bits: its characters in the given encoding, padded with zero
	 * bytes; with zero termination, the last byte is always zero.
	 */
	public static Bits text(String text, int size, Charset encoding, boolean zeroTerminated) {
		int length = size / 8;
		byte[] chars = text.getBytes(encoding);
		int max = zeroTerminated ? length - 1 : length;
		if (chars.length > max) {
			throw new ValueException(I18NConstants.ENCODE_TEXT_LENGTH__TEXT_MAX.fill(text, max));
		}
		byte[] bytes = new byte[length];
		System.arraycopy(chars, 0, bytes, 0, chars.length);
		return bytes(bytes, size);
	}

	/**
	 * A date (DPT 11): day, month and year of the century, one byte each; the text in the notation
	 * of XML (<code>2018-07-06</code>).
	 */
	public static Bits date(String text) {
		LocalDate date;
		try {
			date = LocalDate.parse(text.trim());
		} catch (DateTimeParseException ex) {
			throw new ValueException(I18NConstants.ENCODE_NO_DATE__TEXT.fill(text), ex);
		}
		if (date.getYear() < 1990 || date.getYear() > 2089) {
			throw new ValueException(I18NConstants.ENCODE_YEAR_RANGE__YEAR.fill(String.valueOf(date.getYear())));
		}
		return bytes(new byte[] { (byte) date.getDayOfMonth(), (byte) date.getMonthValue(), (byte) (date.getYear() % 100) }, 24);
	}

	/**
	 * An IP address: its 4 (IPv4) or 16 (IPv6) bytes in network order.
	 */
	public static Bits ipAddress(String text) {
		String address = text.trim();
		if (!address.matches("[0-9.]+|[0-9a-fA-F:.]*:[0-9a-fA-F:.]*")) {
			// Only literal addresses, never a host name to resolve.
			throw new ValueException(I18NConstants.ENCODE_NO_IP__TEXT.fill(text));
		}
		try {
			byte[] bytes = InetAddress.getByName(address).getAddress();
			return bytes(bytes, bytes.length * 8);
		} catch (UnknownHostException ex) {
			throw new ValueException(I18NConstants.ENCODE_NO_IP__TEXT.fill(text), ex);
		}
	}

	/**
	 * Raw bytes as a value of the given size: the bytes are right-aligned in it.
	 */
	public static Bits bytes(byte[] bytes, int size) {
		return new Bits(size, new BigInteger(1, bytes));
	}

	/**
	 * Writes a value into memory.
	 *
	 * @param memory
	 *        The memory to write into.
	 * @param byteOffset
	 *        The byte the value starts in.
	 * @param bitOffset
	 *        The bit of that byte the value starts at, counted from its most significant bit; may
	 *        exceed 7 (continues in the next bytes).
	 * @param value
	 *        The value; only its bits are changed in memory.
	 * @param littleEndian
	 *        Whether a value of whole bytes is written least significant byte first.
	 */
	public static void write(byte[] memory, int byteOffset, int bitOffset, Bits value, boolean littleEndian) {
		int start = byteOffset * 8 + bitOffset;
		int size = value.size();
		BigInteger bits = value.value();
		if (littleEndian && size > 8) {
			if (size % 8 != 0 || start % 8 != 0) {
				throw new ValueException(I18NConstants.ENCODE_LITTLE_ENDIAN__SIZE_START.fill(size, start));
			}
			byte[] bytes = value.toBytes();
			for (int n = 0; n < bytes.length / 2; n++) {
				byte tmp = bytes[n];
				bytes[n] = bytes[bytes.length - 1 - n];
				bytes[bytes.length - 1 - n] = tmp;
			}
			bits = new BigInteger(1, bytes);
		}
		if (start + size > memory.length * 8) {
			throw new ValueException(I18NConstants.ENCODE_OUTSIDE_MEMORY__SIZE_START_LENGTH.fill(size, start, memory.length));
		}
		for (int n = 0; n < size; n++) {
			int bit = start + n;
			int mask = 0x80 >>> (bit % 8);
			if (bits.testBit(size - 1 - n)) {
				memory[bit / 8] |= mask;
			} else {
				memory[bit / 8] &= ~mask;
			}
		}
	}

	/**
	 * The binary form of the value the given holder holds for a parameter of the given type.
	 *
	 * @param type
	 *        The type of the parameter.
	 * @param holder
	 *        The {@link de.haumacher.ehome.model.operational.ParameterValue} holding the value: a
	 *        setting of a device, a placement of the parameter or the parameter itself.
	 * @param program
	 *        The program defining the text encoding.
	 * @return The encoded value, <code>null</code> for a type without value ({@link VoidType}).
	 * @throws IllegalArgumentException
	 *         If the value cannot be encoded: none given, it does not fit the type, or the type's
	 *         encoding is not supported.
	 */
	public static Bits encode(ParameterType type, TLObject holder, Program program) {
		if (type instanceof VoidType) {
			return null;
		}
		EnumerationValue choice = holder.tValueByName(ParameterValues.CHOICE) instanceof EnumerationValue entry ? entry : null;
		return encode(type, choice, ParameterValues.encode(holder), program);
	}

	/**
	 * The binary form of a value in ETS notation for a parameter of the given type (an assigned value,
	 * haumacher/ehome #142), see {@link #encode(ParameterType, TLObject, Program)}.
	 */
	public static Bits encode(ParameterType type, String value, Program program) {
		if (type instanceof VoidType) {
			return null;
		}
		return encode(type, null, value, program);
	}

	private static Bits encode(ParameterType type, EnumerationValue choice, String encoded, Program program) {
		String text = encoded;
		if (text == null && type instanceof TextType) {
			// An empty text is held as no text (ETS writes Value="").
			text = "";
		}
		if (text == null) {
			throw new ValueException(I18NConstants.ENCODE_NO_VALUE);
		}
		if (type instanceof EnumerationType enumeration) {
			return encodeEnumeration(enumeration, choice, text);
		}
		if (type instanceof NumericType numeric) {
			long value = parseLong(text);
			return "signedInt".equals(numeric.getKind().getName()) ? signed(value, numeric.getBits())
				: unsigned(value, numeric.getBits());
		}
		if (type instanceof TimeType time) {
			String unit = time.getUnit().getName();
			if (unit.startsWith("Packed")) {
				throw new ValueException(I18NConstants.ENCODE_TIME_UNIT__UNIT.fill(unit));
			}
			return unsigned(parseLong(text), time.getBits());
		}
		if (type instanceof FloatingPointType floating) {
			double value;
			try {
				value = Double.parseDouble(text.trim());
			} catch (NumberFormatException ex) {
				throw new ValueException(I18NConstants.ENCODE_NO_NUMBER__TEXT.fill(text), ex);
			}
			TLClassifier encoding = floating.getEncoding();
			String name = encoding == null ? "DPT9" : encoding.getName();
			switch (name) {
				case "IEEE754Single":
					return ieee754Single(value);
				case "IEEE754Double":
					return ieee754Double(value);
				default:
					return dpt9(value);
			}
		}
		if (type instanceof TextType textType) {
			return text(text, textType.getBits(), textEncoding(program), program.getTextZeroTerminated());
		}
		if (type instanceof DateType) {
			return date(text);
		}
		if (type instanceof IPAddressType) {
			return ipAddress(text);
		}
		throw new ValueException(I18NConstants.ENCODE_TYPE__TYPE.fill(type.tType().getName()));
	}

	private static Bits encodeEnumeration(EnumerationType type, EnumerationValue choice, String text) {
		EnumerationValue entry = choice;
		if (entry == null) {
			long value = parseLong(text);
			for (EnumerationValue candidate : type.getValues()) {
				if (candidate.hasValue(value)) {
					entry = candidate;
					break;
				}
			}
			if (entry == null) {
				// A value without entry is written as ETS gives it.
				return unsigned(value, type.getBits());
			}
		}
		if ("BinaryValue".equals(type.getKind().getName()) && entry.getBinary() != null) {
			return bytes(toBytes(entry.getBinary()), type.getBits());
		}
		BigInteger bits = entry.valueInBits(type.getBits());
		if (bits == null) {
			throw new ValueException(I18NConstants.ENCODE_ENTRY_TOO_LARGE__VALUE_ENTRY_SIZE.fill(
				String.valueOf(entry.getUnsignedValue()), entry.getExtId(), type.getBits()));
		}
		return new Bits(type.getBits(), bits);
	}

	/**
	 * The text encoding of the text parameters of the given program.
	 */
	public static Charset textEncoding(Program program) {
		String name = program.getTextEncoding();
		if (name == null || name.isBlank()) {
			return DEFAULT_TEXT_ENCODING;
		}
		try {
			return Charset.forName(name.trim());
		} catch (IllegalArgumentException ex) {
			throw new ValueException(I18NConstants.ENCODE_TEXT_ENCODING__NAME.fill(name), ex);
		}
	}

	/**
	 * The content of the given binary data; <code>null</code> for none.
	 */
	public static byte[] toBytes(BinaryDataSource data) {
		if (data == null) {
			return null;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			data.deliverTo(out);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	private static long parseLong(String text) {
		try {
			return Long.parseLong(text.trim());
		} catch (NumberFormatException ex) {
			throw new ValueException(I18NConstants.ENCODE_NO_INTEGER__TEXT.fill(text), ex);
		}
	}

}
