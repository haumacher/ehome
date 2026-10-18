/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A value of the rule language: a {@link Type} and, unless it has <i>no value</i>
 * (<code>spec/rules.md</code> 3.3), the value. Immutable.
 *
 * <p>
 * Booleans, integers, durations and enumerations are kept in a <code>long</code>, reals in a
 * <code>double</code>, strings and raw values as bytes (strings in UTF-8), the structured types as
 * the records of this class.
 * </p>
 */
public final class Val {

	/** The longest string in bytes. */
	public static final int MAX_STRING = 28;

	/** The longest raw value in bytes. */
	public static final int MAX_RAW = 14;

	/**
	 * A time of day; <code>dayOfWeek</code> 1 (Monday) .. 7 (Sunday), 0 for none.
	 */
	public record TimeOfDay(int hour, int minute, int second, int dayOfWeek) {

		/** Seconds since midnight. */
		public int secondsOfDay() {
			return hour * 3600 + minute * 60 + second;
		}
	}

	/**
	 * A calendar date.
	 */
	public record Date(int year, int month, int day) {

		/** Days since 1970-01-01. */
		public long days() {
			return Values.daysOf(year, month, day);
		}
	}

	/**
	 * A date and time with its day of the week and the flags of a received value.
	 */
	public record DateTime(int year, int month, int day, int hour, int minute, int second, int dayOfWeek, int flags) {

		/**
		 * The flag summer time (KNX DPT 19 SUTI, in the order of the DPT 19 flags from bit 0): which of
		 * the two occurrences of an ambiguous local time is meant (<code>spec/rules.md</code> 3.1).
		 */
		public static final int SUMMER_TIME = 1 << 7;

		/** Whether the summer-time flag is set. */
		public boolean summerTime() {
			return (flags & SUMMER_TIME) != 0;
		}

		/** Seconds since 1970-01-01T00:00 of the local fields. */
		public long seconds() {
			return Values.daysOf(year, month, day) * 86400 + hour * 3600 + minute * 60 + second;
		}
	}

	/**
	 * A controlled value: a control bit and a number 0..255.
	 */
	public record Controlled(boolean control, int value) {
		// A value.
	}

	/**
	 * A color: levels 0..255 and the bit set of valid channels.
	 */
	public record Color(int red, int green, int blue, int white, int valid) {

		/** The white channel. */
		public static final int WHITE = 0x01;

		/** The blue channel. */
		public static final int BLUE = 0x02;

		/** The green channel. */
		public static final int GREEN = 0x04;

		/** The red channel. */
		public static final int RED = 0x08;

		/** Red, green and blue. */
		public static final int RGB = RED | GREEN | BLUE;

		/** All four channels. */
		public static final int ALL = RGB | WHITE;
	}

	private static final Val[] NONE = new Val[Type.values().length];

	static {
		for (Type type : Type.values()) {
			NONE[type.ordinal()] = new Val(type, false, 0, 0, null);
		}
	}

	private final Type _type;

	private final boolean _present;

	private final long _long;

	private final double _real;

	private final Object _object;

	private Val(Type type, boolean present, long l, double real, Object object) {
		_type = type;
		_present = present;
		_long = l;
		_real = real;
		_object = object;
	}

	/**
	 * <i>No value</i> of the given type.
	 */
	public static Val none(Type type) {
		return NONE[type.ordinal()];
	}

	/** A boolean. */
	public static Val ofBool(boolean value) {
		return new Val(Type.BOOLEAN, true, value ? 1 : 0, 0, null);
	}

	/** An integer. */
	public static Val ofInt(long value) {
		return new Val(Type.INTEGER, true, value, 0, null);
	}

	/** A real; no value if it is not finite. */
	public static Val ofReal(double value) {
		if (!Double.isFinite(value)) {
			return none(Type.REAL);
		}
		return new Val(Type.REAL, true, 0, value, null);
	}

	/** A duration in milliseconds. */
	public static Val ofDuration(long ms) {
		return new Val(Type.DURATION, true, ms, 0, null);
	}

	/** An enumeration element 0..2^32-1. */
	public static Val ofEnum(long element) {
		return new Val(Type.ENUMERATION, true, element & 0xFFFFFFFFL, 0, null);
	}

	/** A time of day. */
	public static Val ofTime(TimeOfDay value) {
		return new Val(Type.TIME_OF_DAY, true, 0, 0, value);
	}

	/** A date. */
	public static Val ofDate(Date value) {
		return new Val(Type.DATE, true, 0, 0, value);
	}

	/** A date and time. */
	public static Val ofDateTime(DateTime value) {
		return new Val(Type.DATE_TIME, true, 0, 0, value);
	}

	/** A controlled value. */
	public static Val ofControlled(boolean control, int value) {
		return new Val(Type.CONTROLLED, true, 0, 0, new Controlled(control, value));
	}

	/** A color. */
	public static Val ofColor(Color value) {
		return new Val(Type.COLOR, true, 0, 0, value);
	}

	/** A string of UTF-8 bytes; no value if longer than {@link #MAX_STRING} or containing a NUL. */
	public static Val ofString(byte[] utf8) {
		if (utf8.length > MAX_STRING) {
			return none(Type.STRING);
		}
		for (byte b : utf8) {
			if (b == 0) {
				return none(Type.STRING);
			}
		}
		return new Val(Type.STRING, true, 0, 0, utf8.clone());
	}

	/** A string; no value if longer than {@link #MAX_STRING} bytes or containing a NUL. */
	public static Val ofString(String text) {
		return ofString(text.getBytes(StandardCharsets.UTF_8));
	}

	/** Raw bytes; no value if longer than {@link #MAX_RAW}. */
	public static Val ofRaw(byte[] bytes) {
		if (bytes.length > MAX_RAW) {
			return none(Type.RAW);
		}
		return new Val(Type.RAW, true, 0, 0, bytes.clone());
	}

	/** The type, also of no value. */
	public Type type() {
		return _type;
	}

	/** Whether there is a value. */
	public boolean has() {
		return _present;
	}

	/** The boolean. */
	public boolean bool() {
		return _long != 0;
	}

	/** The integer, the duration in milliseconds or the enumeration element. */
	public long integer() {
		return _long;
	}

	/** The real. */
	public double real() {
		return _real;
	}

	/** The integer or real as a double. */
	public double number() {
		return _type == Type.REAL ? _real : _long;
	}

	/** The time of day. */
	public TimeOfDay time() {
		return (TimeOfDay) _object;
	}

	/** The date. */
	public Date date() {
		return (Date) _object;
	}

	/** The date and time. */
	public DateTime dateTime() {
		return (DateTime) _object;
	}

	/** The controlled value. */
	public Controlled controlled() {
		return (Controlled) _object;
	}

	/** The color. */
	public Color color() {
		return (Color) _object;
	}

	/** The bytes of a string (UTF-8) or a raw value; do not modify. */
	public byte[] bytes() {
		return (byte[]) _object;
	}

	/** The string. */
	public String text() {
		return new String(bytes(), StandardCharsets.UTF_8);
	}

	/**
	 * Equality of two present values of the same type as the language's <code>==</code> on equal
	 * types (<code>spec/rules.md</code> 4.5): reals exactly, times of day without the day of the
	 * week, date-times by their fields without the day of the week and the flags.
	 */
	public static boolean equal(Val a, Val b) {
		if (a._type != b._type || !a._present || !b._present) {
			return false;
		}
		switch (a._type) {
			case REAL:
				return a._real == b._real;
			case TIME_OF_DAY:
				return a.time().secondsOfDay() == b.time().secondsOfDay();
			case DATE_TIME: {
				DateTime x = a.dateTime();
				DateTime y = b.dateTime();
				return x.year() == y.year() && x.month() == y.month() && x.day() == y.day() && x.hour() == y.hour()
					&& x.minute() == y.minute() && x.second() == y.second();
			}
			case STRING:
			case RAW:
				return Arrays.equals(a.bytes(), b.bytes());
			case DATE:
			case CONTROLLED:
			case COLOR:
				return a._object.equals(b._object);
			default:
				return a._long == b._long;
		}
	}

	/**
	 * Exact identity, also of the day of the week and the flags: what a stored value is. Two values
	 * without value of the same type are identical.
	 */
	public static boolean identical(Val a, Val b) {
		if (a._type != b._type || a._present != b._present) {
			return false;
		}
		if (!a._present) {
			return true;
		}
		switch (a._type) {
			case TIME_OF_DAY:
			case DATE_TIME:
				return a._object.equals(b._object);
			default:
				return equal(a, b);
		}
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof Val other && identical(this, other);
	}

	@Override
	public int hashCode() {
		if (!_present) {
			return _type.hashCode();
		}
		Object object = _object instanceof byte[] bytes ? Integer.valueOf(Arrays.hashCode(bytes)) : _object;
		return _type.hashCode() * 31 + Long.hashCode(_long) + Double.hashCode(_real + 0.0)
			+ (object == null ? 0 : object.hashCode());
	}

	@Override
	public String toString() {
		return _present ? _type + " " + Values.literalText(this) : "none";
	}

}
