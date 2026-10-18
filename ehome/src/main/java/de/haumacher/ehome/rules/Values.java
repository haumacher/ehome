/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.haumacher.ehome.rules.Val.Color;
import de.haumacher.ehome.rules.Val.Date;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;

/**
 * Reading, writing and converting {@link Val}s: the JSON literal values (<code>spec/rules.md</code>
 * 3.1), the conversions on writing (3.2), the formatting of log messages and
 * <code>toString()</code> (4.9) and the calendar. After homecontrol
 * <code>core/src/rules/value.cpp</code>.
 *
 * <p>
 * JSON values are those of a parsed tree: {@link Map} (objects), {@link List} (arrays),
 * {@link String}, {@link Number}, {@link Boolean} and <code>null</code>.
 * </p>
 */
public final class Values {

	private static final double MAX_SAFE_INTEGER = 9007199254740991.0;

	private Values() {
		// Static helpers.
	}

	// ---- JSON literal values ----------------------------------------------------------------

	/**
	 * Outcome of reading a JSON literal value.
	 */
	public enum Status {
		/** Read. */
		OK,

		/** Wrong JSON kind or members. */
		MALFORMED,

		/** Out of its range (25:00, a level of 256, a string longer than 28 bytes). */
		INVALID;
	}

	/**
	 * A JSON literal value read: the status, the value (when {@link Status#OK}) and why not.
	 */
	public record Literal(Status status, Val value, String reason) {

		static Literal ok(Val value) {
			return new Literal(Status.OK, value, null);
		}

		static Literal malformed(String reason) {
			return new Literal(Status.MALFORMED, null, reason);
		}

		static Literal invalid(String reason) {
			return new Literal(Status.INVALID, null, reason);
		}

		/** Whether the value was read. */
		public boolean isOk() {
			return status == Status.OK;
		}
	}

	/**
	 * Reads the JSON literal value of the given type (<code>spec/rules.md</code> 3.1).
	 */
	public static Literal fromJson(Type type, Object json) {
		switch (type) {
			case BOOLEAN:
				if (!(json instanceof Boolean b)) {
					return Literal.malformed("a boolean literal must be true or false");
				}
				return Literal.ok(Val.ofBool(b.booleanValue()));
			case INTEGER:
			case DURATION: {
				if (!(json instanceof Number n)) {
					return Literal.malformed(
						type == Type.INTEGER ? "an integer literal must be a number" : "a duration must be a number of ms");
				}
				double number = n.doubleValue();
				if (!integral(number) || Math.abs(number) > MAX_SAFE_INTEGER) {
					return Literal.invalid("not an integer within 2^53-1");
				}
				long value = (long) number;
				return Literal.ok(type == Type.INTEGER ? Val.ofInt(value) : Val.ofDuration(value));
			}
			case REAL: {
				if (!(json instanceof Number n)) {
					return Literal.malformed("a real literal must be a number");
				}
				Val value = Val.ofReal(n.doubleValue());
				if (!value.has()) {
					return Literal.invalid("not a finite real");
				}
				return Literal.ok(value);
			}
			case ENUMERATION: {
				if (!(json instanceof Number n)) {
					return Literal.malformed("an enumeration literal must be a number");
				}
				double number = n.doubleValue();
				if (!integral(number) || number < 0 || number > 4294967295.0) {
					return Literal.invalid("an enumeration element must be 0..4294967295");
				}
				return Literal.ok(Val.ofEnum((long) number));
			}
			case STRING: {
				if (!(json instanceof String s)) {
					return Literal.malformed("a string literal must be a JSON string");
				}
				Val value = Val.ofString(s);
				if (!value.has()) {
					return Literal.invalid("a string is longer than 28 bytes or contains a NUL");
				}
				return Literal.ok(value);
			}
			case RAW:
				return raw(json);
			case TIME_OF_DAY:
				return timeOfDay(json);
			case DATE:
			case DATE_TIME:
				return date(type, json);
			case CONTROLLED: {
				if (!(json instanceof Map<?, ?> map) || !onlyMembers(map, Set.of("control", "value"))
					|| !(map.get("control") instanceof Boolean control)) {
					return Literal.malformed("a controlled value is {\"control\": bool, \"value\": n}");
				}
				IntMember value = intMember(map, "value", 0, 255, true);
				if (value.status != Status.OK) {
					return new Literal(value.status, null, "the number of a controlled value must be 0..255");
				}
				return Literal.ok(Val.ofControlled(control.booleanValue(), (int) value.value));
			}
			case COLOR:
				return color(json);
			default:
				return Literal.malformed("unknown type");
		}
	}

	private static Literal raw(Object json) {
		if (!(json instanceof String text)) {
			return Literal.malformed("a raw literal must be a string of hex octets");
		}
		byte[] chars = text.getBytes(StandardCharsets.UTF_8);
		if (chars.length > 3 * Val.MAX_RAW) {
			return Literal.invalid("a raw value is longer than 14 bytes");
		}
		String problem = "raw octets must be two hex digits separated by single spaces, at most 14";
		byte[] bytes = new byte[Val.MAX_RAW];
		int count = 0;
		int i = 0;
		int length = chars.length;
		while (i < length) {
			if (count == Val.MAX_RAW || i + 1 >= length || hexDigit(chars[i]) < 0 || hexDigit(chars[i + 1]) < 0) {
				return Literal.invalid(problem);
			}
			bytes[count++] = (byte) (hexDigit(chars[i]) * 16 + hexDigit(chars[i + 1]));
			i += 2;
			if (i < length) {
				if (chars[i] != ' ' || i + 1 == length) {
					return Literal.invalid(problem);
				}
				i++;
			}
		}
		return Literal.ok(Val.ofRaw(Arrays.copyOf(bytes, count)));
	}

	private static Literal timeOfDay(Object json) {
		if (!(json instanceof Map<?, ?> map) || !onlyMembers(map, Set.of("hour", "minute", "second", "dayOfWeek"))) {
			return Literal.malformed("a time of day is {\"hour\", \"minute\", \"second\"} with an optional \"dayOfWeek\"");
		}
		IntMember h = intMember(map, "hour", 0, 23, true);
		IntMember m = h.status == Status.OK ? intMember(map, "minute", 0, 59, true) : h;
		IntMember s = m.status == Status.OK ? intMember(map, "second", 0, 59, true) : m;
		IntMember d = s.status == Status.OK ? intMember(map, "dayOfWeek", 0, 7, false) : s;
		if (d.status != Status.OK) {
			return new Literal(d.status, null,
				d.status == Status.INVALID ? "a time of day field out of range" : "a time of day field missing");
		}
		return Literal.ok(Val.ofTime(new TimeOfDay((int) h.value, (int) m.value, (int) s.value, (int) d.value)));
	}

	private static Literal date(Type type, Object json) {
		boolean withTime = type == Type.DATE_TIME;
		Set<String> members = withTime ? Set.of("year", "month", "day", "hour", "minute", "second")
			: Set.of("year", "month", "day");
		if (!(json instanceof Map<?, ?> map) || !onlyMembers(map, members)) {
			return Literal.malformed(withTime ? "a date-time is {\"year\", \"month\", \"day\", \"hour\", \"minute\", \"second\"}"
				: "a date is {\"year\", \"month\", \"day\"}");
		}
		IntMember y = intMember(map, "year", 1900, 2155, true);
		IntMember mo = y.status == Status.OK ? intMember(map, "month", 1, 12, true) : y;
		IntMember d = mo.status == Status.OK ? intMember(map, "day", 1, 31, true) : mo;
		IntMember h = d;
		IntMember mi = d;
		IntMember s = d;
		if (withTime && d.status == Status.OK) {
			h = intMember(map, "hour", 0, 23, true);
			mi = h.status == Status.OK ? intMember(map, "minute", 0, 59, true) : h;
			s = mi.status == Status.OK ? intMember(map, "second", 0, 59, true) : mi;
		}
		if (s.status != Status.OK) {
			return new Literal(s.status, null, s.status == Status.INVALID ? "a date field out of range" : "a date field missing");
		}
		int year = (int) y.value;
		int month = (int) mo.value;
		int day = (int) d.value;
		if (!validDate(year, month, day)) {
			return Literal.invalid("not a valid date");
		}
		if (!withTime) {
			return Literal.ok(Val.ofDate(new Date(year, month, day)));
		}
		return Literal.ok(Val.ofDateTime(new DateTime(year, month, day, (int) h.value, (int) mi.value, (int) s.value,
			weekdayOf(year, month, day), 0)));
	}

	private static Literal color(Object json) {
		if (!(json instanceof Map<?, ?> map) || !onlyMembers(map, Set.of("red", "green", "blue", "white"))) {
			return Literal.malformed("a color is {\"red\", \"green\", \"blue\"} with an optional \"white\"");
		}
		IntMember r = intMember(map, "red", 0, 255, true);
		IntMember g = r.status == Status.OK ? intMember(map, "green", 0, 255, true) : r;
		IntMember b = g.status == Status.OK ? intMember(map, "blue", 0, 255, true) : g;
		IntMember w = b.status == Status.OK ? intMember(map, "white", 0, 255, false) : b;
		if (w.status != Status.OK) {
			return new Literal(w.status, null,
				w.status == Status.INVALID ? "a color level must be 0..255" : "a color level missing");
		}
		boolean white = map.containsKey("white");
		return Literal.ok(Val.ofColor(new Color((int) r.value, (int) g.value, (int) b.value, (int) w.value,
			white ? Color.ALL : Color.RGB)));
	}

	private record IntMember(Status status, long value) {
		// The result of intMember().
	}

	private static IntMember intMember(Map<?, ?> map, String name, long low, long high, boolean required) {
		if (!map.containsKey(name)) {
			return new IntMember(required ? Status.MALFORMED : Status.OK, 0);
		}
		if (!(map.get(name) instanceof Number n)) {
			return new IntMember(Status.MALFORMED, 0);
		}
		double number = n.doubleValue();
		if (!integral(number) || number < low || number > high) {
			return new IntMember(Status.INVALID, 0);
		}
		return new IntMember(Status.OK, (long) number);
	}

	private static boolean onlyMembers(Map<?, ?> map, Set<String> allowed) {
		for (Object key : map.keySet()) {
			if (!allowed.contains(key) && !"description".equals(key) && !"comment".equals(key)) {
				return false;
			}
		}
		return true;
	}

	private static int hexDigit(byte c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		if (c >= 'A' && c <= 'F') {
			return c - 'A' + 10;
		}
		return -1;
	}

	/**
	 * Whether the double is finite and has no fraction.
	 */
	public static boolean integral(double value) {
		return Double.isFinite(value) && Math.floor(value) == value;
	}

	// ---- Conversions -----------------------------------------------------------------------

	/**
	 * Whether an expression of the type may be written to a datapoint of the target type
	 * (<code>spec/rules.md</code> 3.2).
	 */
	public static boolean writeAllowed(Type type, ValueType target) {
		switch (type) {
			case BOOLEAN:
				return target == ValueType.BOOLEAN;
			case INTEGER:
			case REAL:
				return target == ValueType.UNSIGNED || target == ValueType.SIGNED || target == ValueType.REAL
					|| target == ValueType.ENUMERATION;
			case ENUMERATION:
				return target == ValueType.ENUMERATION || target == ValueType.UNSIGNED || target == ValueType.SIGNED;
			case DURATION:
				return false;
			default:
				return target.expressionType() == type;
		}
	}

	/**
	 * The value converted exactly to the datapoint type (as the expression type that datapoint
	 * reads as), or <code>null</code> when the conversion is not exact.
	 */
	public static Val convertForDatapoint(Val value, ValueType target) {
		if (!value.has() || !writeAllowed(value.type(), target)) {
			return null;
		}
		switch (value.type()) {
			case INTEGER:
				return integerTo(value.integer(), target);
			case REAL:
				if (target == ValueType.REAL) {
					return value;
				}
				if (integral(value.real()) && Math.abs(value.real()) < 9.2e18) {
					return integerTo((long) value.real(), target);
				}
				return null;
			case ENUMERATION:
				if (target == ValueType.ENUMERATION) {
					return value;
				}
				return integerTo(value.integer(), target);
			default:
				return value;
		}
	}

	private static Val integerTo(long i, ValueType target) {
		switch (target) {
			case UNSIGNED:
				return i >= 0 && i <= 4294967295L ? Val.ofInt(i) : null;
			case ENUMERATION:
				return i >= 0 && i <= 4294967295L ? Val.ofEnum(i) : null;
			case SIGNED:
				return i >= Integer.MIN_VALUE && i <= Integer.MAX_VALUE ? Val.ofInt(i) : null;
			case REAL:
				return i >= -(1L << 53) && i <= (1L << 53) ? Val.ofReal(i) : null;
			default:
				return null;
		}
	}

	/**
	 * Whether an expression of the type may be assigned to a parameter of the target type.
	 */
	public static boolean assignAllowed(Type type, Type target) {
		switch (target) {
			case INTEGER:
			case REAL:
				return type == Type.INTEGER || type == Type.REAL;
			case BOOLEAN:
			case STRING:
			case DURATION:
			case TIME_OF_DAY:
			case ENUMERATION:
			case COLOR:
				return type == target;
			default:
				return false;
		}
	}

	/**
	 * The value converted exactly to the parameter type, <code>null</code> if not exact.
	 */
	public static Val convertForParameter(Val value, Type target) {
		if (!value.has() || !assignAllowed(value.type(), target)) {
			return null;
		}
		if (value.type() == target) {
			return value;
		}
		if (value.type() == Type.INTEGER && target == Type.REAL) {
			long i = value.integer();
			return i >= -(1L << 53) && i <= (1L << 53) ? Val.ofReal(i) : null;
		}
		if (value.type() == Type.REAL && target == Type.INTEGER) {
			double r = value.real();
			return integral(r) && Math.abs(r) < 9.2e18 ? Val.ofInt((long) r) : null;
		}
		return null;
	}

	// ---- Text ------------------------------------------------------------------------------

	/**
	 * Formats a value as log messages and <code>toString()</code> do (<code>spec/rules.md</code>
	 * 4.9; <code>none</code> for no value).
	 */
	public static String format(Val value) {
		if (!value.has()) {
			return "none";
		}
		switch (value.type()) {
			case REAL:
				return formatReal(value.real());
			case STRING:
				return value.text();
			default:
				return literalCommon(value);
		}
	}

	/**
	 * The canonical literal text of a value (<code>spec/rules.md</code> 4.2), reals as Java prints
	 * them (diagnostics only).
	 */
	public static String literalText(Val value) {
		if (!value.has()) {
			return "none";
		}
		switch (value.type()) {
			case REAL:
				return Double.toString(value.real());
			case STRING: {
				StringBuilder out = new StringBuilder("\"");
				for (char c : value.text().toCharArray()) {
					switch (c) {
						case '"' -> out.append("\\\"");
						case '\\' -> out.append("\\\\");
						case '\n' -> out.append("\\n");
						case '\t' -> out.append("\\t");
						default -> out.append(c);
					}
				}
				return out.append('"').toString();
			}
			default:
				return literalCommon(value);
		}
	}

	private static String literalCommon(Val v) {
		switch (v.type()) {
			case BOOLEAN:
				return v.bool() ? "true" : "false";
			case INTEGER:
			case ENUMERATION:
				return Long.toString(v.integer());
			case DURATION:
				return durationText(v.integer());
			case TIME_OF_DAY: {
				TimeOfDay t = v.time();
				String text = time(t.hour(), t.minute(), t.second());
				// A day of the week precedes the time (spec 4.2): "mon 07:30".
				return t.dayOfWeek() >= 1 && t.dayOfWeek() <= 7 ? WEEKDAYS.get(t.dayOfWeek() - 1) + " " + text : text;
			}
			case DATE: {
				Date d = v.date();
				return date(d.year(), d.month(), d.day());
			}
			case DATE_TIME: {
				DateTime d = v.dateTime();
				return date(d.year(), d.month(), d.day()) + "T" + time(d.hour(), d.minute(), d.second());
			}
			case COLOR: {
				Color c = v.color();
				String text = String.format(Locale.ROOT, "#%02x%02x%02x", c.red(), c.green(), c.blue());
				if ((c.valid() & Color.WHITE) != 0) {
					text += String.format(Locale.ROOT, "%02x", c.white());
				}
				return text;
			}
			case RAW: {
				StringBuilder out = new StringBuilder("x\"");
				for (byte b : v.bytes()) {
					out.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
				}
				return out.append('"').toString();
			}
			case CONTROLLED:
				return "controlled(" + v.controlled().control() + ", " + v.controlled().value() + ")";
			default:
				return "?";
		}
	}

	/**
	 * The names of the days of the week in the text of a time of day (<code>spec/rules.md</code>
	 * 4.2), Monday first.
	 */
	public static final List<String> WEEKDAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

	/**
	 * The JSON literal value of a value (<code>spec/rules.md</code> 3.1; the inverse of
	 * {@link #fromJson(Type, Object)}); <code>null</code> for no value.
	 */
	public static Object toJson(Val value) {
		if (!value.has()) {
			return null;
		}
		switch (value.type()) {
			case BOOLEAN:
				return Boolean.valueOf(value.bool());
			case INTEGER:
			case DURATION:
			case ENUMERATION:
				return Long.valueOf(value.integer());
			case REAL:
				return Double.valueOf(value.real());
			case STRING:
				return value.text();
			case RAW: {
				StringBuilder out = new StringBuilder();
				for (byte b : value.bytes()) {
					if (out.length() > 0) {
						out.append(' ');
					}
					out.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
				}
				return out.toString();
			}
			case TIME_OF_DAY: {
				TimeOfDay t = value.time();
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("hour", Long.valueOf(t.hour()));
				result.put("minute", Long.valueOf(t.minute()));
				result.put("second", Long.valueOf(t.second()));
				if (t.dayOfWeek() != 0) {
					result.put("dayOfWeek", Long.valueOf(t.dayOfWeek()));
				}
				return result;
			}
			case DATE: {
				Date d = value.date();
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("year", Long.valueOf(d.year()));
				result.put("month", Long.valueOf(d.month()));
				result.put("day", Long.valueOf(d.day()));
				return result;
			}
			case DATE_TIME: {
				DateTime d = value.dateTime();
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("year", Long.valueOf(d.year()));
				result.put("month", Long.valueOf(d.month()));
				result.put("day", Long.valueOf(d.day()));
				result.put("hour", Long.valueOf(d.hour()));
				result.put("minute", Long.valueOf(d.minute()));
				result.put("second", Long.valueOf(d.second()));
				return result;
			}
			case CONTROLLED: {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("control", Boolean.valueOf(value.controlled().control()));
				result.put("value", Long.valueOf(value.controlled().value()));
				return result;
			}
			case COLOR: {
				Color c = value.color();
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("red", Long.valueOf(c.red()));
				result.put("green", Long.valueOf(c.green()));
				result.put("blue", Long.valueOf(c.blue()));
				if ((c.valid() & Color.WHITE) != 0) {
					result.put("white", Long.valueOf(c.white()));
				}
				return result;
			}
			default:
				return null;
		}
	}

	private static String time(int hour, int minute, int second) {
		String text = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
		return second != 0 ? text + String.format(Locale.ROOT, ":%02d", second) : text;
	}

	private static String date(int year, int month, int day) {
		return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
	}

	/**
	 * A real rounded to 6 decimals from its exact binary value, a tie away from zero, trailing zeros
	 * and a trailing point removed; zero is <code>0</code>, never <code>-0</code>.
	 */
	public static String formatReal(double value) {
		if (!Double.isFinite(value)) {
			return "none";
		}
		BigDecimal rounded = new BigDecimal(Math.abs(value)).setScale(6, RoundingMode.HALF_UP);
		if (rounded.signum() == 0) {
			return "0";
		}
		String text = rounded.stripTrailingZeros().toPlainString();
		return value < 0 ? "-" + text : text;
	}

	/**
	 * A duration normalized into <code>d h min s ms</code>; <code>0s</code> for zero.
	 */
	public static String durationText(long ms) {
		if (ms == 0) {
			return "0s";
		}
		StringBuilder out = new StringBuilder();
		if (ms < 0) {
			out.append('-');
		}
		// Unsigned magnitude, also of Long.MIN_VALUE.
		long rest = ms < 0 ? -ms : ms;
		long[] units = { 86400000L, 3600000L, 60000L, 1000L, 1L };
		String[] names = { "d", "h", "min", "s", "ms" };
		for (int i = 0; i < units.length; i++) {
			long count = Long.divideUnsigned(rest, units[i]);
			rest = Long.remainderUnsigned(rest, units[i]);
			if (count != 0) {
				out.append(Long.toUnsignedString(count)).append(names[i]);
			}
		}
		return out.toString();
	}

	// ---- Calendar --------------------------------------------------------------------------

	/**
	 * Days since 1970-01-01 of a proleptic Gregorian date.
	 */
	public static long daysOf(long year, int month, int day) {
		// Howard Hinnant's days_from_civil.
		long y = month <= 2 ? year - 1 : year;
		long era = (y >= 0 ? y : y - 399) / 400;
		long yoe = y - era * 400;
		long doy = (153L * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
		long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
		return era * 146097 + doe - 719468;
	}

	/**
	 * The date of the given days since 1970-01-01.
	 */
	public static Date civilOf(long days) {
		// Howard Hinnant's civil_from_days.
		long z = days + 719468;
		long era = (z >= 0 ? z : z - 146096) / 146097;
		long doe = z - era * 146097;
		long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
		long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
		long mp = (5 * doy + 2) / 153;
		int day = (int) (doy - (153 * mp + 2) / 5 + 1);
		int month = (int) (mp < 10 ? mp + 3 : mp - 9);
		int year = (int) (yoe + era * 400 + (month <= 2 ? 1 : 0));
		return new Date(year, month, day);
	}

	/**
	 * The ISO day of the week 1 (Monday) .. 7 (Sunday) of a date.
	 */
	public static int weekdayOf(long year, int month, int day) {
		return weekdayOfDays(daysOf(year, month, day));
	}

	/**
	 * The ISO day of the week 1 (Monday) .. 7 (Sunday) of the given days since 1970-01-01 (a
	 * Thursday).
	 */
	public static int weekdayOfDays(long days) {
		return (int) Math.floorMod(days + 3, 7L) + 1;
	}

	/**
	 * The days of the month (February of leap years 29).
	 */
	public static int daysInMonth(long year, int month) {
		int[] days = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
		if (month == 2 && ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0)) {
			return 29;
		}
		return days[month - 1];
	}

	/**
	 * Whether the fields name a date.
	 */
	public static boolean validDate(long year, int month, int day) {
		return month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth(year, month);
	}

	/**
	 * <code>round()</code> of C: half away from zero.
	 */
	public static double round(double x) {
		if (!Double.isFinite(x)) {
			return x;
		}
		double magnitude = Math.abs(x);
		double whole = Math.floor(magnitude);
		if (magnitude - whole >= 0.5) {
			whole += 1;
		}
		return Math.copySign(whole, x);
	}

}
