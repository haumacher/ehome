/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.Val.Date;

/**
 * A schedule entry of the runtime image (homecontrol <code>spec/schedules.md</code>) and the
 * loader of the <code>schedules</code> member, a port of the schedule part of the controller's
 * image loader (<code>core/src/image/loader.cpp</code>): strict members, every problem refused with
 * its JSON path and a reason starting with the reason code (<code>malformed: hour must be
 * 0..23</code>), all problems of the member reported, not only the first.
 */
public final class Schedule {

	/** The most entries of an image. */
	public static final int MAX_ENTRIES = 128;

	/** The most dates of a <code>dates</code> entry. */
	public static final int MAX_DATES = 128;

	/** Days of the week as bits: bit 0 Monday .. bit 6 Sunday. */
	public static final int ALL_DAYS = 0x7F;

	/**
	 * The kinds of entries.
	 */
	public enum Kind {
		/** On the given days of the week at a local time. */
		WEEKLY("weekly"),
		/** Every year on a month and day at a local time. */
		YEARLY("yearly"),
		/** On each date of a list at a local time. */
		DATES("dates"),
		/** On the given days at a sun event plus an offset, clamped to a local window. */
		ASTRO("astro"),
		/** On the given days every N minutes from a local start up to an end time. */
		INTERVAL("interval"),
		/** Once, at a local date and time. */
		ONCE("once");

		private final String _name;

		Kind(String name) {
			_name = name;
		}

		/** The name in the image. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * A local time of day, second resolution.
	 */
	public record ClockTime(int hour, int minute, int second) {

		/** Seconds since midnight. */
		public int seconds() {
			return hour * 3600 + minute * 60 + second;
		}
	}

	String _id;

	String _label;

	Kind _kind = Kind.WEEKLY;

	boolean _enabled = true;

	boolean _enabledAdjustable = true;

	boolean _catchUp;

	String _output;

	int _days = ALL_DAYS;

	ClockTime _time = new ClockTime(0, 0, 0);

	int _month;

	int _day;

	List<Date> _dates = List.of();

	SunEvent _sun = SunEvent.SUNRISE;

	int _offsetSeconds;

	ClockTime _notBefore;

	ClockTime _notAfter;

	int _everyMinutes;

	ClockTime _from;

	ClockTime _until;

	Date _atDate;

	ClockTime _atTime;

	/** The id. */
	public String id() {
		return _id;
	}

	/** The label. */
	public String label() {
		return _label;
	}

	/** The kind. */
	public Kind kind() {
		return _kind;
	}

	/** The output datapoint, <code>null</code> for none. */
	public String output() {
		return _output;
	}

	/**
	 * Creates a weekly entry (for code that builds entries without an image).
	 *
	 * @param days
	 *        Bit 0 Monday .. bit 6 Sunday.
	 */
	public static Schedule weekly(String id, int days, ClockTime time) {
		Schedule entry = new Schedule(id, Kind.WEEKLY);
		entry._days = days;
		entry._time = time;
		return entry;
	}

	/** Creates a yearly entry. */
	public static Schedule yearly(String id, int month, int day, ClockTime time) {
		Schedule entry = new Schedule(id, Kind.YEARLY);
		entry._month = month;
		entry._day = day;
		entry._time = time;
		return entry;
	}

	/** Creates a dates entry. */
	public static Schedule dates(String id, List<Date> dates, ClockTime time) {
		Schedule entry = new Schedule(id, Kind.DATES);
		entry._dates = List.copyOf(dates);
		entry._time = time;
		return entry;
	}

	/**
	 * Creates an astro entry.
	 *
	 * @param notBefore
	 *        <code>null</code> for no clamp.
	 * @param notAfter
	 *        <code>null</code> for no clamp.
	 */
	public static Schedule astro(String id, SunEvent sun, int offsetSeconds, ClockTime notBefore, ClockTime notAfter) {
		Schedule entry = new Schedule(id, Kind.ASTRO);
		entry._sun = sun;
		entry._offsetSeconds = offsetSeconds;
		entry._notBefore = notBefore;
		entry._notAfter = notAfter;
		return entry;
	}

	/** Creates an interval entry. */
	public static Schedule interval(String id, int days, int everyMinutes, ClockTime from, ClockTime until) {
		Schedule entry = new Schedule(id, Kind.INTERVAL);
		entry._days = days;
		entry._everyMinutes = everyMinutes;
		entry._from = from;
		entry._until = until;
		return entry;
	}

	/** Creates a once entry. */
	public static Schedule once(String id, Date date, ClockTime time) {
		Schedule entry = new Schedule(id, Kind.ONCE);
		entry._atDate = date;
		entry._atTime = time;
		return entry;
	}

	/** Sets the missed policy: <code>catchUp</code> or <code>skip</code>. */
	public Schedule setCatchUp(boolean catchUp) {
		_catchUp = catchUp;
		return this;
	}

	/** Sets the image's default of the enable switch and whether it is adjustable. */
	public Schedule setEnabled(boolean enabled, boolean adjustable) {
		_enabled = enabled;
		_enabledAdjustable = adjustable;
		return this;
	}

	/** Sets the output datapoint. */
	public Schedule setOutput(String output) {
		_output = output;
		return this;
	}

	private Schedule(String id, Kind kind) {
		_id = id;
		_label = id;
		_kind = kind;
	}

	private Schedule() {
		// For the loader.
	}

	// ---- Loading ----------------------------------------------------------------------------

	/**
	 * A refusal of the loader: the JSON path and the reason, starting with its code.
	 */
	public record Refusal(String path, String reason) {
		@Override
		public String toString() {
			return path + ": " + reason;
		}
	}

	/**
	 * The datapoints of the image as the schedule loader sees them.
	 */
	public interface Datapoints {

		/** The value type, <code>null</code> if there is no such datapoint. */
		ValueType type(String name);

		/**
		 * What owns the datapoint (<code>the KNX adapter</code>, <code>the Zigbee adapter</code>,
		 * <code>the time master</code>), <code>null</code> if nothing does.
		 */
		String owner(String name);
	}

	/**
	 * The result of loading the <code>schedules</code> member: the entries (also those with
	 * refusals, as far as they were read) and the refusals.
	 */
	public record Loaded(List<Schedule> entries, List<Refusal> refusals) {

		/** Whether the member was accepted. */
		public boolean ok() {
			return refusals.isEmpty();
		}

		/** The ids of the entries with a valid id. */
		public Set<String> ids() {
			Set<String> ids = new HashSet<>();
			for (Schedule entry : entries) {
				if (entry._id != null) {
					ids.add(entry._id);
				}
			}
			return ids;
		}
	}

	/**
	 * Loads the <code>schedules</code> member (its parsed JSON value) of an image.
	 */
	public static Loaded load(Object schedules, Datapoints datapoints) {
		Loader loader = new Loader(datapoints);
		loader.schedules(schedules);
		return new Loaded(loader._entries, loader._refusals);
	}

	private static final class Loader {

		private final Datapoints _datapoints;

		private final List<Schedule> _entries = new ArrayList<>();

		private final List<Refusal> _refusals = new ArrayList<>();

		private final StringBuilder _path = new StringBuilder("schedules");

		private final Set<String> _outputs = new HashSet<>();

		Loader(Datapoints datapoints) {
			_datapoints = datapoints;
		}

		private void refuse(String reason) {
			_refusals.add(new Refusal(_path.toString(), reason));
		}

		private int enter(String segment) {
			int mark = _path.length();
			_path.append(segment);
			return mark;
		}

		private void leave(int mark) {
			_path.setLength(mark);
		}

		private String string(Object value, int min, int max, String what) {
			if (!(value instanceof String text)) {
				refuse(what + " must be a string");
				return null;
			}
			int length = text.getBytes(StandardCharsets.UTF_8).length;
			if (length < min || length > max) {
				refuse(what + " must have " + min + ".." + max + " bytes");
				return null;
			}
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c < 0x20 || c == 0x7F) {
					refuse(what + " contains a control character");
					return null;
				}
			}
			return text;
		}

		private Long integer(Object value, long min, long max, String what) {
			if (!(value instanceof Number number) || !Values.integral(number.doubleValue())) {
				refuse(what + " must be an integer");
				return null;
			}
			double d = number.doubleValue();
			if (d < min || d > max) {
				refuse(what + " must be " + min + ".." + max);
				return null;
			}
			return Long.valueOf((long) d);
		}

		private void flag(Map<?, ?> object, String name, boolean[] out) {
			if (object.containsKey(name)) {
				int mark = enter("." + name);
				if (object.get(name) instanceof Boolean value) {
					out[0] = value.booleanValue();
				} else {
					refuse(name + " must be true or false");
				}
				leave(mark);
			}
		}

		private boolean only(Map<?, ?> object, Set<String> allowed) {
			boolean ok = true;
			for (Object key : object.keySet()) {
				if (!allowed.contains(key) && !"description".equals(key) && !"comment".equals(key)) {
					refuse("malformed: unknown member '" + key + "'");
					ok = false;
				}
			}
			return ok;
		}

		private ClockTime clock(Object value, String what) {
			if (!(value instanceof Map<?, ?> map)) {
				refuse("malformed: " + what + " must be {\"hour\", \"minute\", \"second\"}");
				return null;
			}
			if (!only(map, Set.of("hour", "minute", "second"))) {
				return null;
			}
			if (!map.containsKey("hour") || !map.containsKey("minute") || !map.containsKey("second")) {
				refuse("malformed: " + what + " needs hour, minute and second");
				return null;
			}
			Long hour = integer(map.get("hour"), 0, 23, "malformed: hour");
			Long minute = hour == null ? null : integer(map.get("minute"), 0, 59, "malformed: minute");
			Long second = minute == null ? null : integer(map.get("second"), 0, 59, "malformed: second");
			if (second == null) {
				return null;
			}
			return new ClockTime(hour.intValue(), minute.intValue(), second.intValue());
		}

		/** A date; with <code>withTime</code> {date, time}. */
		private Object[] date(Object value, boolean withTime, String what) {
			if (!(value instanceof Map<?, ?> map)) {
				refuse("malformed: " + what + " must be an object");
				return null;
			}
			Set<String> members = withTime ? Set.of("year", "month", "day", "hour", "minute", "second")
				: Set.of("year", "month", "day");
			if (!only(map, members)) {
				return null;
			}
			if (!map.containsKey("year") || !map.containsKey("month") || !map.containsKey("day")) {
				refuse("malformed: " + what + " needs year, month and day");
				return null;
			}
			Long year = integer(map.get("year"), 1970, 2155, "malformed: year");
			Long month = year == null ? null : integer(map.get("month"), 1, 12, "malformed: month");
			Long day = month == null ? null : integer(map.get("day"), 1, 31, "malformed: day");
			if (day == null) {
				return null;
			}
			if (!Values.validDate(year.longValue(), month.intValue(), day.intValue())) {
				refuse(String.format(java.util.Locale.ROOT, "malformed: %04d-%02d-%02d is not a date", year, month, day));
				return null;
			}
			Date date = new Date(year.intValue(), month.intValue(), day.intValue());
			if (!withTime) {
				return new Object[] { date, null };
			}
			if (!map.containsKey("hour") || !map.containsKey("minute") || !map.containsKey("second")) {
				refuse("malformed: " + what + " needs hour, minute and second");
				return null;
			}
			Long hour = integer(map.get("hour"), 0, 23, "malformed: hour");
			Long minute = hour == null ? null : integer(map.get("minute"), 0, 59, "malformed: minute");
			Long second = minute == null ? null : integer(map.get("second"), 0, 59, "malformed: second");
			if (second == null) {
				return null;
			}
			return new Object[] { date, new ClockTime(hour.intValue(), minute.intValue(), second.intValue()) };
		}

		private Integer days(Object value) {
			if (!(value instanceof List<?> list)) {
				refuse("malformed: days must be an array of 1 (Monday) .. 7 (Sunday)");
				return null;
			}
			int days = 0;
			for (Object item : list) {
				Long day = integer(item, 1, 7, "malformed: a day");
				if (day == null) {
					return null;
				}
				int bit = 1 << (day.intValue() - 1);
				if ((days & bit) != 0) {
					refuse("malformed: day " + day + " is listed twice");
					return null;
				}
				days |= bit;
			}
			if (days == 0) {
				refuse("malformed: days must name at least one day");
				return null;
			}
			return Integer.valueOf(days);
		}

		void schedules(Object value) {
			if (!(value instanceof List<?> list)) {
				refuse("malformed: 'schedules' must be an array");
				return;
			}
			if (list.size() > MAX_ENTRIES) {
				refuse("limitExceeded: " + list.size() + " entries exceed the limit of " + MAX_ENTRIES);
				return;
			}
			for (int i = 0; i < list.size(); i++) {
				int mark = enter("[" + i + "]");
				Schedule entry = new Schedule();
				entry(list.get(i), entry);
				_entries.add(entry);
				if (entry._id != null) {
					for (int k = 0; k < i; k++) {
						if (entry._id.equals(_entries.get(k)._id)) {
							int id = enter(".id");
							refuse("duplicateId: schedules[" + k + "] has the id '" + entry._id + "' too");
							leave(id);
						}
					}
				}
				leave(mark);
			}
		}

		private void entry(Object item, Schedule spec) {
			if (!(item instanceof Map<?, ?> map)) {
				refuse("malformed: a schedule entry must be an object");
				return;
			}
			int mark = enter(".id");
			if (!map.containsKey("id")) {
				refuse("malformed: id is missing");
			} else {
				spec._id = string(map.get("id"), 1, 32, "malformed: id");
				if (spec._id != null && !RuleLoader.validRuleId(spec._id)) {
					refuse("malformed: id '" + spec._id + "' must have 1..32 characters of A-Z a-z 0-9 _ -");
					spec._id = null;
				}
			}
			leave(mark);
			mark = enter(".label");
			if (!map.containsKey("label")) {
				refuse("malformed: label is missing");
			} else {
				spec._label = string(map.get("label"), 1, 63, "malformed: label");
			}
			leave(mark);
			mark = enter(".kind");
			if (!map.containsKey("kind")) {
				refuse("malformed: kind is missing");
				leave(mark);
				return;
			}
			String kind = string(map.get("kind"), 1, 15, "malformed: kind");
			if (kind == null) {
				leave(mark);
				return;
			}
			Set<String> members = new HashSet<>(
				Set.of("id", "label", "kind", "enabled", "enabledAdjustable", "missed", "output"));
			switch (kind) {
				case "weekly" -> {
					spec._kind = Kind.WEEKLY;
					members.addAll(Set.of("time", "days"));
				}
				case "yearly" -> {
					spec._kind = Kind.YEARLY;
					members.addAll(Set.of("month", "day", "time"));
				}
				case "dates" -> {
					spec._kind = Kind.DATES;
					members.addAll(Set.of("dates", "time"));
				}
				case "astro" -> {
					spec._kind = Kind.ASTRO;
					members.addAll(Set.of("sun", "offset", "notBefore", "notAfter", "days"));
				}
				case "interval" -> {
					spec._kind = Kind.INTERVAL;
					members.addAll(Set.of("every", "from", "until", "days"));
				}
				case "once" -> {
					spec._kind = Kind.ONCE;
					members.add("at");
				}
				default -> {
					refuse("malformed: unknown kind \"" + kind + "\" (weekly, yearly, dates, astro, interval, once)");
					leave(mark);
					return;
				}
			}
			leave(mark);
			only(map, members);
			boolean[] flag = { spec._enabled };
			flag(map, "enabled", flag);
			spec._enabled = flag[0];
			flag[0] = spec._enabledAdjustable;
			flag(map, "enabledAdjustable", flag);
			spec._enabledAdjustable = flag[0];
			if (map.containsKey("missed")) {
				mark = enter(".missed");
				String missed = string(map.get("missed"), 1, 15, "malformed: missed");
				if ("skip".equals(missed)) {
					spec._catchUp = false;
				} else if ("catchUp".equals(missed)) {
					spec._catchUp = true;
				} else if (missed != null) {
					refuse("malformed: missed must be skip or catchUp");
				}
				leave(mark);
			}
			if (map.containsKey("output")) {
				mark = enter(".output");
				String output = string(map.get("output"), 1, 63, "malformed: output");
				if (output != null) {
					ValueType type = _datapoints.type(output);
					String owner = _datapoints.owner(output);
					if (type == null) {
						refuse("unknownReference: output '" + output + "' is no datapoint of the image");
					} else if (type != ValueType.BOOLEAN) {
						refuse("typeError: output '" + output + "' is " + type.externalName() + ", not boolean");
					} else if (owner != null || _outputs.contains(output)) {
						refuse("outputConflict: output '" + output + "' is owned by "
							+ (owner != null ? owner : "another schedule entry"));
					} else {
						_outputs.add(output);
						spec._output = output;
					}
				}
				leave(mark);
			}
			boolean usesDays = spec._kind == Kind.WEEKLY || spec._kind == Kind.ASTRO || spec._kind == Kind.INTERVAL;
			if (usesDays && map.containsKey("days")) {
				mark = enter(".days");
				Integer days = days(map.get("days"));
				if (days != null) {
					spec._days = days.intValue();
				}
				leave(mark);
			}
			switch (spec._kind) {
				case WEEKLY -> time(map, spec, kind);
				case YEARLY -> {
					time(map, spec, kind);
					if (required(map, "month", kind) && required(map, "day", kind)) {
						mark = enter(".day");
						Long month = integer(map.get("month"), 1, 12, "malformed: month");
						Long day = month == null ? null : integer(map.get("day"), 1, 31, "malformed: day");
						if (day != null) {
							if (day.intValue() > Values.daysInMonth(2024, month.intValue())) {
								refuse("malformed: month " + month + " has no day " + day);
							} else {
								spec._month = month.intValue();
								spec._day = day.intValue();
							}
						}
						leave(mark);
					}
				}
				case DATES -> {
					time(map, spec, kind);
					if (required(map, "dates", kind)) {
						mark = enter(".dates");
						dateList(map.get("dates"), spec);
						leave(mark);
					}
				}
				case ASTRO -> astro(map, spec, kind);
				case INTERVAL -> interval(map, spec, kind);
				case ONCE -> {
					if (required(map, "at", kind)) {
						mark = enter(".at");
						Object[] at = date(map.get("at"), true, "at");
						if (at != null) {
							spec._atDate = (Date) at[0];
							spec._atTime = (ClockTime) at[1];
						}
						leave(mark);
					}
				}
				default -> throw new IllegalStateException();
			}
		}

		private boolean required(Map<?, ?> map, String name, String kind) {
			if (!map.containsKey(name)) {
				refuse("malformed: a " + kind + " entry needs " + name);
				return false;
			}
			return true;
		}

		private void time(Map<?, ?> map, Schedule spec, String kind) {
			if (required(map, "time", kind)) {
				int mark = enter(".time");
				ClockTime time = clock(map.get("time"), "time");
				if (time != null) {
					spec._time = time;
				}
				leave(mark);
			}
		}

		private void dateList(Object value, Schedule spec) {
			if (!(value instanceof List<?> list)) {
				refuse("malformed: dates must be an array of dates");
				return;
			}
			if (list.isEmpty()) {
				refuse("malformed: dates must list at least one date");
				return;
			}
			if (list.size() > MAX_DATES) {
				refuse("limitExceeded: " + list.size() + " dates exceed the limit of " + MAX_DATES);
				return;
			}
			List<Date> dates = new ArrayList<>();
			boolean ok = true;
			for (int n = 0; n < list.size(); n++) {
				int mark = enter("[" + n + "]");
				Object[] date = date(list.get(n), false, "a date");
				if (date == null) {
					ok = false;
					dates.add(null);
				} else {
					if (dates.contains(date[0])) {
						refuse("duplicateId: the date is listed twice");
						ok = false;
					}
					dates.add((Date) date[0]);
				}
				leave(mark);
			}
			if (ok) {
				spec._dates = List.copyOf(dates);
			}
		}

		private void astro(Map<?, ?> map, Schedule spec, String kind) {
			if (required(map, "sun", kind)) {
				int mark = enter(".sun");
				String sun = string(map.get("sun"), 1, 15, "malformed: sun");
				if (sun != null) {
					SunEvent event = SunEvent.fromName(sun);
					if (event == null) {
						refuse("malformed: sun must be dawn, sunrise, sunset or dusk");
					} else {
						spec._sun = event;
					}
				}
				leave(mark);
			}
			if (map.containsKey("offset")) {
				int mark = enter(".offset");
				Long ms = integer(map.get("offset"), -43200000L, 43200000L, "malformed: offset (milliseconds, at most 12 h)");
				if (ms != null) {
					if (ms.longValue() % 1000 != 0) {
						refuse("malformed: offset must be a whole number of seconds");
					} else {
						spec._offsetSeconds = (int) (ms.longValue() / 1000);
					}
				}
				leave(mark);
			}
			if (map.containsKey("notBefore")) {
				int mark = enter(".notBefore");
				spec._notBefore = clock(map.get("notBefore"), "notBefore");
				leave(mark);
			}
			if (map.containsKey("notAfter")) {
				int mark = enter(".notAfter");
				spec._notAfter = clock(map.get("notAfter"), "notAfter");
				leave(mark);
			}
			if (spec._notBefore != null && spec._notAfter != null
				&& spec._notBefore.seconds() > spec._notAfter.seconds()) {
				refuse("malformed: notBefore is later than notAfter");
			}
		}

		private void interval(Map<?, ?> map, Schedule spec, String kind) {
			if (required(map, "every", kind)) {
				int mark = enter(".every");
				Long ms = integer(map.get("every"), 60000L, 43200000L, "malformed: every (milliseconds, 1 min .. 12 h)");
				if (ms != null) {
					if (ms.longValue() % 60000 != 0) {
						refuse("malformed: every must be a whole number of minutes");
					} else {
						spec._everyMinutes = (int) (ms.longValue() / 60000);
					}
				}
				leave(mark);
			}
			if (required(map, "from", kind)) {
				int mark = enter(".from");
				spec._from = clock(map.get("from"), "from");
				leave(mark);
			}
			if (required(map, "until", kind)) {
				int mark = enter(".until");
				spec._until = clock(map.get("until"), "until");
				leave(mark);
			}
			if (spec._from != null && spec._until != null && spec._until.seconds() < spec._from.seconds()) {
				refuse("malformed: until is earlier than from (a window across midnight needs two entries)");
			}
		}
	}

}
