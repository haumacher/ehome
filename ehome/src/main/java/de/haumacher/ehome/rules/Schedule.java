/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey3;

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

	/** The prefix of the datapoints the schedules imply (<code>spec/schedules.md</code> 2.2). */
	public static final String DATAPOINT_PREFIX = "schedule/";

	/** The largest offset of an astro entry, in seconds. */
	static final int MAX_OFFSET_S = 12 * 3600;

	/** The local seconds since 1970 of the latest <code>at</code>, 2155-12-31 23:59:59. */
	static final long MAX_AT = Values.daysOf(2155, 12, 31) * 86400 + 86399;

	/**
	 * The members of an entry the image may declare adjustable (<code>spec/schedules.md</code> 2.1,
	 * homecontrol #58), in the order of the controller's <code>ScheduleMember</code>. A value of a
	 * member is handled as its <i>scalar</i>: a time of day as seconds since midnight, the days as bits
	 * (bit 0 Monday .. bit 6 Sunday), <code>offset</code> and <code>every</code> in seconds,
	 * <code>at</code> as local seconds since 1970-01-01 00:00.
	 */
	public enum Member {
		/** weekly, yearly, dates: the time. */
		TIME("time"),
		/** weekly, astro, interval: the days. */
		DAYS("days"),
		/** astro: the offset. */
		OFFSET("offset"),
		/** astro: the earliest firing. */
		NOT_BEFORE("notBefore"),
		/** astro: the latest firing. */
		NOT_AFTER("notAfter"),
		/** interval: the interval. */
		EVERY("every"),
		/** interval: the first firing of a day. */
		FROM("from"),
		/** interval: the latest firing of a day. */
		UNTIL("until"),
		/** once: the date and time. */
		AT("at");

		private final String _name;

		Member(String name) {
			_name = name;
		}

		/** The name in the image. */
		public String externalName() {
			return _name;
		}

		/** The bit of the member in a set of members. */
		public int bit() {
			return 1 << ordinal();
		}

		/** Whether the member is a time of day (time, notBefore, notAfter, from, until). */
		public boolean isTime() {
			return this == TIME || this == NOT_BEFORE || this == NOT_AFTER || this == FROM || this == UNTIL;
		}

		/** The member of the name, <code>null</code> for an unknown one. */
		public static Member fromName(String name) {
			for (Member member : values()) {
				if (member._name.equals(name)) {
					return member;
				}
			}
			return null;
		}

		/** The members an entry of the kind may declare adjustable, as bits. */
		public static int allowed(Kind kind) {
			switch (kind) {
				case WEEKLY:
					return TIME.bit() | DAYS.bit();
				case YEARLY:
				case DATES:
					return TIME.bit();
				case ASTRO:
					return OFFSET.bit() | NOT_BEFORE.bit() | NOT_AFTER.bit() | DAYS.bit();
				case INTERVAL:
					return EVERY.bit() | FROM.bit() | UNTIL.bit() | DAYS.bit();
				case ONCE:
					return AT.bit();
				default:
					return 0;
			}
		}

		/** The members of the bits, in member order. */
		public static List<Member> of(int bits) {
			List<Member> result = new ArrayList<>();
			for (Member member : values()) {
				if ((bits & member.bit()) != 0) {
					result.add(member);
				}
			}
			return result;
		}

		/** The smallest scalar the member allows without bounds. */
		public long rangeMin() {
			return isTime() ? 0 : this == OFFSET ? -MAX_OFFSET_S : this == EVERY ? 60 : this == DAYS ? 1 : 0;
		}

		/** The largest scalar the member allows without bounds. */
		public long rangeMax() {
			return isTime() ? 86399 : this == OFFSET ? MAX_OFFSET_S : this == EVERY ? 43200 : this == DAYS ? ALL_DAYS : MAX_AT;
		}

		/** Whether the scalar is a value of the member at all (its range, before any bounds). */
		public boolean inRange(long value) {
			if (this == EVERY) {
				return value >= 60 && value <= 720 * 60 && value % 60 == 0;
			}
			return value >= rangeMin() && value <= rangeMax();
		}

		/** The scalar of the member's value in its image form, <code>null</code> if it is none. */
		public Long scalar(Object json) {
			if (isTime()) {
				if (json instanceof Map<?, ?> map && map.size() == 3 && whole(map.get("hour"), 0, 23)
					&& whole(map.get("minute"), 0, 59) && whole(map.get("second"), 0, 59)) {
					return Long.valueOf(number(map.get("hour")) * 3600 + number(map.get("minute")) * 60
						+ number(map.get("second")));
				}
				return null;
			}
			switch (this) {
				case DAYS: {
					if (!(json instanceof List<?> list)) {
						return null;
					}
					long days = 0;
					for (Object item : list) {
						if (!whole(item, 1, 7) || (days & (1L << (number(item) - 1))) != 0) {
							return null;
						}
						days |= 1L << (number(item) - 1);
					}
					return days == 0 ? null : Long.valueOf(days);
				}
				case OFFSET:
				case EVERY: {
					long unit = this == EVERY ? 60000 : 1000;
					if (!whole(json, Long.MIN_VALUE, Long.MAX_VALUE) || number(json) % unit != 0) {
						return null;
					}
					long seconds = number(json) / 1000;
					return inRange(seconds) ? Long.valueOf(seconds) : null;
				}
				case AT: {
					if (!(json instanceof Map<?, ?> map) || map.size() != 6 || !whole(map.get("year"), 1970, 2155)
						|| !whole(map.get("month"), 1, 12) || !whole(map.get("day"), 1, 31) || !whole(map.get("hour"), 0, 23)
						|| !whole(map.get("minute"), 0, 59) || !whole(map.get("second"), 0, 59)
						|| !Values.validDate(number(map.get("year")), (int) number(map.get("month")),
							(int) number(map.get("day")))) {
						return null;
					}
					return Long.valueOf(Values.daysOf(number(map.get("year")), (int) number(map.get("month")),
						(int) number(map.get("day"))) * 86400 + number(map.get("hour")) * 3600
						+ number(map.get("minute")) * 60 + number(map.get("second")));
				}
				default:
					return null;
			}
		}

		/** The image form of a scalar of the member. */
		public Object json(long value) {
			if (isTime()) {
				return timeJson(value);
			}
			switch (this) {
				case DAYS: {
					List<Object> days = new ArrayList<>();
					for (int day = 1; day <= 7; day++) {
						if ((value & (1L << (day - 1))) != 0) {
							days.add(Long.valueOf(day));
						}
					}
					return days;
				}
				case OFFSET:
				case EVERY:
					return Long.valueOf(value * 1000);
				default: {
					Date date = Values.civilOf(Math.floorDiv(value, 86400L));
					long rest = Math.floorMod(value, 86400L);
					Map<String, Object> at = new LinkedHashMap<>();
					at.put("year", Long.valueOf(date.year()));
					at.put("month", Long.valueOf(date.month()));
					at.put("day", Long.valueOf(date.day()));
					at.put("hour", Long.valueOf(rest / 3600));
					at.put("minute", Long.valueOf(rest % 3600 / 60));
					at.put("second", Long.valueOf(rest % 60));
					return at;
				}
			}
		}

		/** The value of the member for people: 06:30:00, 1,2,3, -1800 s, 15 min, 2026-12-31 23:59:30. */
		public String format(long value) {
			if (isTime()) {
				return String.format(Locale.ROOT, "%02d:%02d:%02d", value / 3600, value % 3600 / 60, value % 60);
			}
			switch (this) {
				case DAYS: {
					StringBuilder text = new StringBuilder();
					for (int day = 1; day <= 7; day++) {
						if ((value & (1L << (day - 1))) != 0) {
							text.append(text.length() > 0 ? "," : "").append(day);
						}
					}
					return text.toString();
				}
				case OFFSET:
					return String.format(Locale.ROOT, "%+d s", Long.valueOf(value));
				case EVERY:
					return value / 60 + " min";
				default: {
					Date date = Values.civilOf(Math.floorDiv(value, 86400L));
					long rest = Math.floorMod(value, 86400L);
					return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d", date.year(), date.month(),
						date.day(), rest / 3600, rest % 3600 / 60, rest % 60);
				}
			}
		}

		private static boolean whole(Object value, long min, long max) {
			return value instanceof Number number && Values.integral(number.doubleValue()) && number.doubleValue() >= min
				&& number.doubleValue() <= max;
		}

		private static long number(Object value) {
			return ((Number) value).longValue();
		}
	}

	/** A time of day as its image form <code>{"hour", "minute", "second"}</code>. */
	static Map<String, Object> timeJson(long seconds) {
		Map<String, Object> time = new LinkedHashMap<>();
		time.put("hour", Long.valueOf(seconds / 3600));
		time.put("minute", Long.valueOf(seconds % 3600 / 60));
		time.put("second", Long.valueOf(seconds % 60));
		return time;
	}

	/**
	 * The bounds of an adjustable member (<code>spec/schedules.md</code> 2.1) in the member's scalar
	 * ({@link Member}), inclusive; <code>step</code> 0 for none.
	 */
	public record Bound(Member member, long min, long max, long step) {

		/** Whether the scalar lies within the bounds and on the step. */
		public boolean fits(long value) {
			return value >= min && value <= max && (step <= 0 || value % step == 0);
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

	/** The {@link Member} bits the inhabitant may change. */
	int _adjustable;

	/** The bounds of adjustable members, at most one per member. */
	List<Bound> _bounds = List.of();

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

	/** The {@link Member} bits the image declares adjustable. */
	public int adjustable() {
		return _adjustable;
	}

	/** The bounds of the adjustable members. */
	public List<Bound> bounds() {
		return _bounds;
	}

	/** The bounds of the member, <code>null</code> for none. */
	public Bound bound(Member member) {
		for (Bound bound : _bounds) {
			if (bound.member() == member) {
				return bound;
			}
		}
		return null;
	}

	/**
	 * Whether the entry implies the datapoint <code>schedule/&lt;id&gt;/time</code> (<code>spec/schedules.md</code>
	 * 2.2): a weekly, yearly or dates entry whose time is adjustable.
	 */
	public boolean hasTimeDatapoint() {
		return (_adjustable & Member.TIME.bit()) != 0
			&& (_kind == Kind.WEEKLY || _kind == Kind.YEARLY || _kind == Kind.DATES);
	}

	/** The name of the datapoint mirroring the adjustable time of the entry with the id. */
	public static String timeDatapointName(String id) {
		return DATAPOINT_PREFIX + id + "/time";
	}

	/**
	 * The ids of the entries of the member <code>schedules</code> whose adjustable time is a datapoint
	 * (<code>spec/schedules.md</code> 2.2), in image order. Lenient (as the controller's
	 * <code>schedule_time_ids</code>): an entry that is not well-formed, or whose id came before, is
	 * skipped, since {@link #load(Object, Datapoints)} refuses it with the precise reason.
	 */
	public static List<String> timeDatapointIds(Object schedules) {
		List<String> result = new ArrayList<>();
		if (!(schedules instanceof List<?> list)) {
			return result;
		}
		Set<Object> seen = new HashSet<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> map)) {
				continue;
			}
			Object id = map.get("id");
			boolean first = seen.add(id);
			if (!first || !(id instanceof String text) || !RuleLoader.validRuleId(text)
				|| !List.of("weekly", "yearly", "dates").contains(map.get("kind"))
				|| !(map.get("adjustable") instanceof List<?> adjustable) || !adjustable.contains("time")) {
				continue;
			}
			result.add(text);
		}
		return result;
	}

	/** The scalar of a member of the entry ({@link Member}). */
	public long memberValue(Member member) {
		switch (member) {
			case TIME:
				return _time.seconds();
			case DAYS:
				return _days;
			case OFFSET:
				return _offsetSeconds;
			case NOT_BEFORE:
				return _notBefore == null ? 0 : _notBefore.seconds();
			case NOT_AFTER:
				return _notAfter == null ? 0 : _notAfter.seconds();
			case EVERY:
				return _everyMinutes * 60L;
			case FROM:
				return _from == null ? 0 : _from.seconds();
			case UNTIL:
				return _until == null ? 0 : _until.seconds();
			case AT:
				return _atDate == null || _atTime == null ? 0 : _atDate.days() * 86400 + _atTime.seconds();
			default:
				return 0;
		}
	}

	/** Sets a member of the entry from its scalar (no validation). */
	void setMemberValue(Member member, long value) {
		switch (member) {
			case TIME:
				_time = clockOf(value);
				break;
			case DAYS:
				_days = (int) value;
				break;
			case OFFSET:
				_offsetSeconds = (int) value;
				break;
			case NOT_BEFORE:
				_notBefore = clockOf(value);
				break;
			case NOT_AFTER:
				_notAfter = clockOf(value);
				break;
			case EVERY:
				_everyMinutes = (int) (value / 60);
				break;
			case FROM:
				_from = clockOf(value);
				break;
			case UNTIL:
				_until = clockOf(value);
				break;
			case AT:
				_atDate = Values.civilOf(Math.floorDiv(value, 86400L));
				_atTime = clockOf(Math.floorMod(value, 86400L));
				break;
			default:
				break;
		}
	}

	private static ClockTime clockOf(long seconds) {
		return new ClockTime((int) (seconds / 3600), (int) (seconds % 3600 / 60), (int) (seconds % 60));
	}

	/** A copy of the entry, whose members can be changed without changing this one. */
	Schedule copy() {
		Schedule copy = new Schedule();
		copy._id = _id;
		copy._label = _label;
		copy._kind = _kind;
		copy._enabled = _enabled;
		copy._enabledAdjustable = _enabledAdjustable;
		copy._catchUp = _catchUp;
		copy._output = _output;
		copy._days = _days;
		copy._time = _time;
		copy._month = _month;
		copy._day = _day;
		copy._dates = _dates;
		copy._sun = _sun;
		copy._offsetSeconds = _offsetSeconds;
		copy._notBefore = _notBefore;
		copy._notAfter = _notAfter;
		copy._everyMinutes = _everyMinutes;
		copy._from = _from;
		copy._until = _until;
		copy._atDate = _atDate;
		copy._atTime = _atTime;
		copy._adjustable = _adjustable;
		copy._bounds = _bounds;
		return copy;
	}

	/**
	 * Why the adjustable members and bounds of an entry are refused ({@link #adjustableProblem()}).
	 */
	public enum AdjustableProblem {
		/** An adjustable member the kind does not adjust. */
		ADJUSTABLE_KIND("adjustable names a member this kind cannot adjust", I18NConstants.SCHEDULE_ADJUSTABLE_KIND),

		/** An adjustable notBefore the entry does not set. */
		NOT_BEFORE_UNSET("notBefore is adjustable, but the entry sets none", I18NConstants.SCHEDULE_NOT_BEFORE_UNSET),

		/** An adjustable notAfter the entry does not set. */
		NOT_AFTER_UNSET("notAfter is adjustable, but the entry sets none", I18NConstants.SCHEDULE_NOT_AFTER_UNSET),

		/** Bounds of a member the kind does not have. */
		BOUNDS_KIND("bounds name a member this kind does not have", I18NConstants.SCHEDULE_BOUNDS_KIND),

		/** Two bounds of one member. */
		BOUNDS_TWICE("bounds name a member twice", I18NConstants.SCHEDULE_BOUNDS_TWICE),

		/** Bounds of a member that is not adjustable. */
		BOUNDS_UNADJUSTABLE("bounds name a member that is not adjustable", I18NConstants.SCHEDULE_BOUNDS_UNADJUSTABLE),

		/** Bounds of the days. */
		DAYS_BOUNDS("days take no bounds", I18NConstants.SCHEDULE_DAYS_NO_BOUNDS),

		/** A bound outside the range of its member. */
		BOUND_RANGE("a bound is not a value of its member", I18NConstants.SCHEDULE_BOUND_RANGE),

		/** A bound whose min is greater than its max. */
		BOUND_MIN_MAX("a bound's min is greater than its max", I18NConstants.SCHEDULE_BOUND_MIN_MAX),

		/** A negative step. */
		STEP_NEGATIVE("a step must be positive", I18NConstants.SCHEDULE_STEP_NEGATIVE),

		/** A step of at. */
		AT_STEP("at takes no step", I18NConstants.SCHEDULE_AT_NO_STEP),

		/** A step of every that is no whole number of minutes. */
		EVERY_STEP("the step of every must be a whole number of minutes", I18NConstants.SCHEDULE_EVERY_STEP),

		/** A step of a time of day longer than a day. */
		TIME_STEP("the step of a time is at most a day", I18NConstants.SCHEDULE_TIME_STEP),

		/** The image's value outside its bounds. */
		VALUE_OUTSIDE("the image's value lies outside its bounds", I18NConstants.SCHEDULE_VALUE_OUTSIDE),

		/** The image's value not on its step. */
		VALUE_OFF_STEP("the image's value is not on its step", I18NConstants.SCHEDULE_VALUE_OFF_STEP);

		private final String _text;

		private final ResKey _message;

		AdjustableProblem(String text, ResKey message) {
			_text = text;
			_message = message;
		}

		/** The reason as the controller gives it (without the reason code). */
		public String text() {
			return _text;
		}

		/** The same for the user. */
		public ResKey message() {
			return _message;
		}
	}

	/**
	 * The {@link AdjustableProblem#text() reason} of {@link #adjustableProblem()}.
	 *
	 * @return <code>null</code> when valid, else the reason (without the reason code).
	 */
	public String adjustableError() {
		AdjustableProblem problem = adjustableProblem();
		return problem == null ? null : problem.text();
	}

	/**
	 * Checks the adjustable members and bounds of an entry whose other members are valid (the loader
	 * and the {@link Scheduler} share it, as the controller's <code>schedule_adjustable_error</code>):
	 * every adjustable member allowed for the kind, an adjustable clamp set in the image, bounds only
	 * for adjustable members other than <code>days</code>, at most one per member, within the member's
	 * range, <code>min &lt;= max</code>, a positive step (whole minutes for <code>every</code>, none for
	 * <code>at</code>), and the image's own value within its bounds and on the step.
	 *
	 * @return <code>null</code> when valid, else the problem.
	 */
	public AdjustableProblem adjustableProblem() {
		int allowed = Member.allowed(_kind);
		if ((_adjustable & ~allowed) != 0) {
			return AdjustableProblem.ADJUSTABLE_KIND;
		}
		if ((_adjustable & Member.NOT_BEFORE.bit()) != 0 && _notBefore == null) {
			return AdjustableProblem.NOT_BEFORE_UNSET;
		}
		if ((_adjustable & Member.NOT_AFTER.bit()) != 0 && _notAfter == null) {
			return AdjustableProblem.NOT_AFTER_UNSET;
		}
		int seen = 0;
		for (Bound b : _bounds) {
			if ((b.member().bit() & allowed) == 0) {
				return AdjustableProblem.BOUNDS_KIND;
			}
			if ((b.member().bit() & seen) != 0) {
				return AdjustableProblem.BOUNDS_TWICE;
			}
			seen |= b.member().bit();
			if ((b.member().bit() & _adjustable) == 0) {
				return AdjustableProblem.BOUNDS_UNADJUSTABLE;
			}
			if (b.member() == Member.DAYS) {
				return AdjustableProblem.DAYS_BOUNDS;
			}
			if (!b.member().inRange(b.min()) || !b.member().inRange(b.max())) {
				return AdjustableProblem.BOUND_RANGE;
			}
			if (b.min() > b.max()) {
				return AdjustableProblem.BOUND_MIN_MAX;
			}
			if (b.step() < 0 || (b.step() > 0 && b.member() == Member.AT)) {
				return b.step() < 0 ? AdjustableProblem.STEP_NEGATIVE : AdjustableProblem.AT_STEP;
			}
			if (b.step() > 0 && b.member() == Member.EVERY && b.step() % 60 != 0) {
				return AdjustableProblem.EVERY_STEP;
			}
			if (b.step() > 0 && b.member().isTime() && b.step() > 86400) {
				return AdjustableProblem.TIME_STEP;
			}
			long value = memberValue(b.member());
			if (value < b.min() || value > b.max()) {
				return AdjustableProblem.VALUE_OUTSIDE;
			}
			if (b.step() > 0 && value % b.step() != 0) {
				return AdjustableProblem.VALUE_OFF_STEP;
			}
		}
		return null;
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
	 * A refusal of the loader: the JSON path, the reason as the controller gives it (English,
	 * starting with its code) and the same as a whole sentence for the user, in the languages of the
	 * application.
	 */
	public record Refusal(String path, String reason, ResKey message) {
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

		/** The unit of an integer value, as the message of a value out of its range names it. */
		private enum Unit {
			/** A plain number. */
			NONE(I18NConstants.SCHEDULE_RANGE__NAME_MIN_MAX),
			/** Seconds. */
			SECONDS(I18NConstants.SCHEDULE_RANGE_SECONDS__NAME_MIN_MAX),
			/** Milliseconds. */
			MILLISECONDS(I18NConstants.SCHEDULE_RANGE_MILLISECONDS__NAME_MIN_MAX),
			/** The milliseconds of an astro offset, at most 12 hours. */
			OFFSET(I18NConstants.SCHEDULE_OFFSET_RANGE),
			/** The milliseconds of the period of an interval, 1 minute to 12 hours. */
			EVERY(I18NConstants.SCHEDULE_EVERY_RANGE);

			private final ResKey3 _range;

			private final ResKey _fixed;

			Unit(ResKey3 range) {
				_range = range;
				_fixed = null;
			}

			Unit(ResKey fixed) {
				_range = null;
				_fixed = fixed;
			}

			/** That the value is not within the range. */
			ResKey message(String member, long min, long max) {
				return _fixed != null ? _fixed : _range.fill(member, String.valueOf(min), String.valueOf(max));
			}
		}

		private void refuse(String reason, ResKey message) {
			_refusals.add(new Refusal(_path.toString(), reason, message));
		}

		private int enter(String segment) {
			int mark = _path.length();
			_path.append(segment);
			return mark;
		}

		private void leave(int mark) {
			_path.setLength(mark);
		}

		/**
		 * A string value.
		 *
		 * @param what
		 *        The value as the reason names it.
		 * @param member
		 *        The value as the message names it.
		 */
		private String string(Object value, int min, int max, String what, String member) {
			if (!(value instanceof String text)) {
				refuse(what + " must be a string", I18NConstants.SCHEDULE_NOT_A_STRING__NAME.fill(member));
				return null;
			}
			int length = text.getBytes(StandardCharsets.UTF_8).length;
			if (length < min || length > max) {
				refuse(what + " must have " + min + ".." + max + " bytes",
					I18NConstants.SCHEDULE_STRING_LENGTH__NAME_MIN_MAX.fill(member, String.valueOf(min), String.valueOf(max)));
				return null;
			}
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c < 0x20 || c == 0x7F) {
					refuse(what + " contains a control character",
						I18NConstants.SCHEDULE_CONTROL_CHARACTER__NAME.fill(member));
					return null;
				}
			}
			return text;
		}

		/**
		 * An integer value.
		 *
		 * @param what
		 *        The value as the reason names it.
		 * @param member
		 *        The value as the message names it.
		 * @param unit
		 *        The unit of the value.
		 */
		private Long integer(Object value, long min, long max, String what, String member, Unit unit) {
			if (!(value instanceof Number number) || !Values.integral(number.doubleValue())) {
				refuse(what + " must be an integer", I18NConstants.SCHEDULE_NOT_AN_INTEGER__NAME.fill(member));
				return null;
			}
			double d = number.doubleValue();
			if (d < min || d > max) {
				refuse(what + " must be " + min + ".." + max, unit.message(member, min, max));
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
					refuse(name + " must be true or false", I18NConstants.SCHEDULE_NOT_A_FLAG__NAME.fill(name));
				}
				leave(mark);
			}
		}

		private boolean only(Map<?, ?> object, Set<String> allowed) {
			boolean ok = true;
			for (Object key : object.keySet()) {
				if (!allowed.contains(key) && !"description".equals(key) && !"comment".equals(key)) {
					refuse("malformed: unknown member '" + key + "'",
						I18NConstants.SCHEDULE_UNKNOWN_MEMBER__NAME.fill(String.valueOf(key)));
					ok = false;
				}
			}
			return ok;
		}

		private ClockTime clock(Object value, String what) {
			if (!(value instanceof Map<?, ?> map)) {
				refuse("malformed: " + what + " must be {\"hour\", \"minute\", \"second\"}",
					I18NConstants.SCHEDULE_CLOCK_NOT_AN_OBJECT__NAME.fill(what));
				return null;
			}
			if (!only(map, Set.of("hour", "minute", "second"))) {
				return null;
			}
			if (!map.containsKey("hour") || !map.containsKey("minute") || !map.containsKey("second")) {
				refuse("malformed: " + what + " needs hour, minute and second",
					I18NConstants.SCHEDULE_CLOCK_INCOMPLETE__NAME.fill(what));
				return null;
			}
			Long hour = integer(map.get("hour"), 0, 23, "malformed: hour", "hour", Unit.NONE);
			Long minute = hour == null ? null : integer(map.get("minute"), 0, 59, "malformed: minute", "minute", Unit.NONE);
			Long second = minute == null ? null : integer(map.get("second"), 0, 59, "malformed: second", "second", Unit.NONE);
			if (second == null) {
				return null;
			}
			return new ClockTime(hour.intValue(), minute.intValue(), second.intValue());
		}

		/**
		 * A date; with <code>withTime</code> {date, time}.
		 *
		 * @param what
		 *        The value as the reason names it.
		 * @param member
		 *        The value as the message names it.
		 */
		private Object[] date(Object value, boolean withTime, String what, String member) {
			if (!(value instanceof Map<?, ?> map)) {
				refuse("malformed: " + what + " must be an object",
					I18NConstants.SCHEDULE_NOT_AN_OBJECT__NAME.fill(member));
				return null;
			}
			Set<String> members = withTime ? Set.of("year", "month", "day", "hour", "minute", "second")
				: Set.of("year", "month", "day");
			if (!only(map, members)) {
				return null;
			}
			if (!map.containsKey("year") || !map.containsKey("month") || !map.containsKey("day")) {
				refuse("malformed: " + what + " needs year, month and day",
					I18NConstants.SCHEDULE_DATE_INCOMPLETE__NAME.fill(member));
				return null;
			}
			Long year = integer(map.get("year"), 1970, 2155, "malformed: year", "year", Unit.NONE);
			Long month = year == null ? null : integer(map.get("month"), 1, 12, "malformed: month", "month", Unit.NONE);
			Long day = month == null ? null : integer(map.get("day"), 1, 31, "malformed: day", "day", Unit.NONE);
			if (day == null) {
				return null;
			}
			if (!Values.validDate(year.longValue(), month.intValue(), day.intValue())) {
				refuse(String.format(java.util.Locale.ROOT, "malformed: %04d-%02d-%02d is not a date", year, month, day),
					I18NConstants.SCHEDULE_NOT_A_DATE__DATE.fill(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)));
				return null;
			}
			Date date = new Date(year.intValue(), month.intValue(), day.intValue());
			if (!withTime) {
				return new Object[] { date, null };
			}
			if (!map.containsKey("hour") || !map.containsKey("minute") || !map.containsKey("second")) {
				refuse("malformed: " + what + " needs hour, minute and second",
					I18NConstants.SCHEDULE_CLOCK_INCOMPLETE__NAME.fill(member));
				return null;
			}
			Long hour = integer(map.get("hour"), 0, 23, "malformed: hour", "hour", Unit.NONE);
			Long minute = hour == null ? null : integer(map.get("minute"), 0, 59, "malformed: minute", "minute", Unit.NONE);
			Long second = minute == null ? null : integer(map.get("second"), 0, 59, "malformed: second", "second", Unit.NONE);
			if (second == null) {
				return null;
			}
			return new Object[] { date, new ClockTime(hour.intValue(), minute.intValue(), second.intValue()) };
		}

		private Integer days(Object value) {
			if (!(value instanceof List<?> list)) {
				refuse("malformed: days must be an array of 1 (Monday) .. 7 (Sunday)",
					I18NConstants.SCHEDULE_DAYS_NOT_AN_ARRAY);
				return null;
			}
			int days = 0;
			for (Object item : list) {
				Long day = integer(item, 1, 7, "malformed: a day", "days", Unit.NONE);
				if (day == null) {
					return null;
				}
				int bit = 1 << (day.intValue() - 1);
				if ((days & bit) != 0) {
					refuse("malformed: day " + day + " is listed twice",
						I18NConstants.SCHEDULE_DAY_TWICE__DAY.fill(String.valueOf(day)));
					return null;
				}
				days |= bit;
			}
			if (days == 0) {
				refuse("malformed: days must name at least one day", I18NConstants.SCHEDULE_DAYS_EMPTY);
				return null;
			}
			return Integer.valueOf(days);
		}

		void schedules(Object value) {
			if (!(value instanceof List<?> list)) {
				refuse("malformed: 'schedules' must be an array", I18NConstants.SCHEDULE_NOT_AN_ARRAY);
				return;
			}
			if (list.size() > MAX_ENTRIES) {
				refuse("limitExceeded: " + list.size() + " entries exceed the limit of " + MAX_ENTRIES,
					I18NConstants.SCHEDULE_TOO_MANY_ENTRIES__COUNT_LIMIT.fill(String.valueOf(list.size()), String.valueOf(MAX_ENTRIES)));
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
							refuse("duplicateId: schedules[" + k + "] has the id '" + entry._id + "' too",
								I18NConstants.SCHEDULE_ID_TWICE__INDEX_ID.fill(String.valueOf(k), entry._id));
							leave(id);
						}
					}
				}
				leave(mark);
			}
		}

		private void entry(Object item, Schedule spec) {
			if (!(item instanceof Map<?, ?> map)) {
				refuse("malformed: a schedule entry must be an object", I18NConstants.SCHEDULE_ENTRY_NOT_AN_OBJECT);
				return;
			}
			int before = _refusals.size();
			if (kindMembers(map, spec)) {
				adjustable(map, spec, before);
			}
		}

		/** The members of the entry but adjustable and bounds; whether its kind is known. */
		private boolean kindMembers(Map<?, ?> map, Schedule spec) {
			int mark = enter(".id");
			if (!map.containsKey("id")) {
				refuse("malformed: id is missing", I18NConstants.SCHEDULE_MEMBER_MISSING__NAME.fill("id"));
			} else {
				spec._id = string(map.get("id"), 1, 32, "malformed: id", "id");
				if (spec._id != null && !RuleLoader.validRuleId(spec._id)) {
					refuse("malformed: id '" + spec._id + "' must have 1..32 characters of A-Z a-z 0-9 _ -",
						I18NConstants.SCHEDULE_ID_PATTERN__ID.fill(spec._id));
					spec._id = null;
				}
			}
			leave(mark);
			mark = enter(".label");
			if (!map.containsKey("label")) {
				refuse("malformed: label is missing", I18NConstants.SCHEDULE_MEMBER_MISSING__NAME.fill("label"));
			} else {
				spec._label = string(map.get("label"), 1, 63, "malformed: label", "label");
			}
			leave(mark);
			mark = enter(".kind");
			if (!map.containsKey("kind")) {
				refuse("malformed: kind is missing", I18NConstants.SCHEDULE_MEMBER_MISSING__NAME.fill("kind"));
				leave(mark);
				return false;
			}
			String kind = string(map.get("kind"), 1, 15, "malformed: kind", "kind");
			if (kind == null) {
				leave(mark);
				return false;
			}
			Set<String> members = new HashSet<>(Set.of("id", "label", "kind", "enabled", "enabledAdjustable", "missed",
				"output", "adjustable", "bounds"));
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
					refuse("malformed: unknown kind \"" + kind + "\" (weekly, yearly, dates, astro, interval, once)",
						I18NConstants.SCHEDULE_UNKNOWN_KIND__KIND.fill(kind));
					leave(mark);
					return false;
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
				String missed = string(map.get("missed"), 1, 15, "malformed: missed", "missed");
				if ("skip".equals(missed)) {
					spec._catchUp = false;
				} else if ("catchUp".equals(missed)) {
					spec._catchUp = true;
				} else if (missed != null) {
					refuse("malformed: missed must be skip or catchUp", I18NConstants.SCHEDULE_MISSED);
				}
				leave(mark);
			}
			if (map.containsKey("output")) {
				mark = enter(".output");
				String output = string(map.get("output"), 1, 63, "malformed: output", "output");
				if (output != null) {
					ValueType type = _datapoints.type(output);
					String owner = _datapoints.owner(output);
					if (type == null) {
						refuse("unknownReference: output '" + output + "' is no datapoint of the image",
							I18NConstants.SCHEDULE_OUTPUT_UNKNOWN__NAME.fill(DatapointId.of(output)));
					} else if (type != ValueType.BOOLEAN) {
						refuse("typeError: output '" + output + "' is " + type.externalName() + ", not boolean",
							I18NConstants.SCHEDULE_OUTPUT_TYPE__NAME_TYPE.fill(DatapointId.of(output), type.externalName()));
					} else if (owner != null || _outputs.contains(output)) {
						refuse("outputConflict: output '" + output + "' is owned by "
							+ (owner != null ? owner : "another schedule entry"),
								I18NConstants.SCHEDULE_OUTPUT_OWNED__NAME_OWNER.fill(DatapointId.of(output), OwnerLabel.of(owner != null ? owner : "another schedule entry")));
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
						Long month = integer(map.get("month"), 1, 12, "malformed: month", "month", Unit.NONE);
						Long day = month == null ? null : integer(map.get("day"), 1, 31, "malformed: day", "day", Unit.NONE);
						if (day != null) {
							if (day.intValue() > Values.daysInMonth(2024, month.intValue())) {
								refuse("malformed: month " + month + " has no day " + day,
									I18NConstants.SCHEDULE_NO_SUCH_DAY__MONTH_DAY.fill(String.valueOf(month), String.valueOf(day)));
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
						Object[] at = date(map.get("at"), true, "at", "at");
						if (at != null) {
							spec._atDate = (Date) at[0];
							spec._atTime = (ClockTime) at[1];
						}
						leave(mark);
					}
				}
				default -> throw new IllegalStateException();
			}
			return true;
		}

		/** <code>adjustable</code> and <code>bounds</code> of an entry (homecontrol #58), after its kind members. */
		private void adjustable(Map<?, ?> map, Schedule spec, int before) {
			int allowed = Member.allowed(spec._kind);
			String kind = spec._kind.externalName();
			if (map.containsKey("adjustable")) {
				int mark = enter(".adjustable");
				try {
					if (!(map.get("adjustable") instanceof List<?> list)) {
						refuse("malformed: adjustable must be an array",
							I18NConstants.SCHEDULE_ADJUSTABLE_NOT_AN_ARRAY);
						return;
					}
					for (int n = 0; n < list.size(); n++) {
						int item = enter("[" + n + "]");
						String name = string(list.get(n), 1, 15, "malformed: a member name", "adjustable[" + n + "]");
						if (name != null) {
							Member member = Member.fromName(name);
							if (member == null || (member.bit() & allowed) == 0) {
								List<String> names = new ArrayList<>();
								for (Member m : Member.of(allowed)) {
									names.add(m.externalName());
								}
								refuse("malformed: '" + name + "' cannot be adjustable in a " + kind + " entry ("
									+ String.join(", ", names) + ")",
										I18NConstants.SCHEDULE_NOT_ADJUSTABLE_IN_KIND__NAME_KIND_NAMES.fill(name, kind, String.join(", ", names)));
							} else if ((spec._adjustable & member.bit()) != 0) {
								refuse("malformed: '" + name + "' is listed twice",
									I18NConstants.SCHEDULE_LISTED_TWICE__NAME.fill(name));
							} else {
								spec._adjustable |= member.bit();
							}
						}
						leave(item);
					}
				} finally {
					leave(mark);
				}
			}
			if (map.containsKey("bounds")) {
				int mark = enter(".bounds");
				try {
					if (!(map.get("bounds") instanceof Map<?, ?> bounds)) {
						refuse("malformed: bounds must be an object", I18NConstants.SCHEDULE_BOUNDS_NOT_AN_OBJECT);
						return;
					}
					List<Bound> result = new ArrayList<>();
					for (Map.Entry<?, ?> entry : bounds.entrySet()) {
						String name = String.valueOf(entry.getKey());
						int key = enter("." + name);
						Bound bound = bound(spec, Member.fromName(name), name, entry.getValue());
						if (bound != null) {
							result.add(bound);
						}
						leave(key);
					}
					spec._bounds = List.copyOf(result);
				} finally {
					leave(mark);
				}
			}
			if (_refusals.size() != before) {
				// The entry's own refusals say enough.
				return;
			}
			AdjustableProblem error = spec.adjustableProblem();
			if (error != null) {
				int mark = enter(map.containsKey("bounds") ? ".bounds" : ".adjustable");
				refuse("malformed: " + error.text(), error.message());
				leave(mark);
			}
		}

		private Bound bound(Schedule spec, Member member, String name, Object value) {
			if (member == null || (member.bit() & spec._adjustable) == 0) {
				refuse("malformed: bounds for '" + name + "', which is not adjustable",
					I18NConstants.SCHEDULE_BOUNDS_NOT_ADJUSTABLE__NAME.fill(name));
				return null;
			}
			if (member == Member.DAYS) {
				refuse("malformed: days take no bounds", I18NConstants.SCHEDULE_DAYS_NO_BOUNDS);
				return null;
			}
			if (!(value instanceof Map<?, ?> map)) {
				refuse("malformed: a bound must be an object", I18NConstants.SCHEDULE_BOUND_NOT_AN_OBJECT);
				return null;
			}
			if (!only(map, Set.of("min", "max", "step"))) {
				return null;
			}
			// Absent: the member's whole range.
			long[] min = { member.rangeMin() };
			long[] max = { member.rangeMax() };
			long step = 0;
			boolean ok = true;
			if (map.containsKey("min")) {
				int mark = enter(".min");
				ok = boundValue(map.get("min"), member, min, "min") && ok;
				leave(mark);
			}
			if (map.containsKey("max")) {
				int mark = enter(".max");
				ok = boundValue(map.get("max"), member, max, "max") && ok;
				leave(mark);
			}
			if (map.containsKey("step")) {
				int mark = enter(".step");
				if (member == Member.AT) {
					refuse("malformed: at takes no step", I18NConstants.SCHEDULE_AT_NO_STEP);
					ok = false;
				} else if (member.isTime()) {
					Long seconds = integer(map.get("step"), 1, 86400, "malformed: step (seconds)", "step", Unit.SECONDS);
					ok = seconds != null && ok;
					step = seconds == null ? 0 : seconds.longValue();
				} else {
					long unit = member == Member.EVERY ? 60000 : 1000;
					Long ms = integer(map.get("step"), 1, 43200000, "malformed: step (milliseconds)", "step", Unit.MILLISECONDS);
					if (ms == null) {
						ok = false;
					} else if (ms.longValue() % unit != 0) {
						refuse("malformed: step must be a whole number of " + (unit == 60000 ? "minutes" : "seconds"), unit == 60000 ? I18NConstants.SCHEDULE_STEP_MINUTES : I18NConstants.SCHEDULE_STEP_SECONDS);
						ok = false;
					} else {
						step = ms.longValue() / 1000;
					}
				}
				leave(mark);
			}
			if (ok && min[0] > max[0]) {
				refuse("malformed: min is later than max", I18NConstants.SCHEDULE_MIN_AFTER_MAX);
				ok = false;
			}
			return ok ? new Bound(member, min[0], max[0], step) : null;
		}

		/** One bound of a member as its scalar ({@link Member}). */
		private boolean boundValue(Object value, Member member, long[] out, String what) {
			if (member.isTime()) {
				ClockTime time = clock(value, what);
				if (time == null) {
					return false;
				}
				out[0] = time.seconds();
				return true;
			}
			if (member == Member.AT) {
				Object[] at = date(value, true, what, what);
				if (at == null) {
					return false;
				}
				out[0] = ((Date) at[0]).days() * 86400 + ((ClockTime) at[1]).seconds();
				return true;
			}
			long low = member == Member.OFFSET ? -43200000L : 60000L;
			Long ms = integer(value, low, 43200000L, "malformed: " + what + " (milliseconds)", what, Unit.MILLISECONDS);
			if (ms == null) {
				return false;
			}
			long unit = member == Member.EVERY ? 60000 : 1000;
			if (ms.longValue() % unit != 0) {
				refuse("malformed: " + what + " must be a whole number of " + (unit == 60000 ? "minutes" : "seconds"), (unit == 60000 ? I18NConstants.SCHEDULE_WHOLE_MINUTES__NAME : I18NConstants.SCHEDULE_WHOLE_SECONDS__NAME).fill(what));
				return false;
			}
			out[0] = ms.longValue() / 1000;
			return true;
		}

		private boolean required(Map<?, ?> map, String name, String kind) {
			if (!map.containsKey(name)) {
				refuse("malformed: a " + kind + " entry needs " + name,
					I18NConstants.SCHEDULE_KIND_NEEDS__KIND_NAME.fill(kind, name));
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
				refuse("malformed: dates must be an array of dates", I18NConstants.SCHEDULE_DATES_NOT_AN_ARRAY);
				return;
			}
			if (list.isEmpty()) {
				refuse("malformed: dates must list at least one date", I18NConstants.SCHEDULE_DATES_EMPTY);
				return;
			}
			if (list.size() > MAX_DATES) {
				refuse("limitExceeded: " + list.size() + " dates exceed the limit of " + MAX_DATES,
					I18NConstants.SCHEDULE_TOO_MANY_DATES__COUNT_LIMIT.fill(String.valueOf(list.size()), String.valueOf(MAX_DATES)));
				return;
			}
			List<Date> dates = new ArrayList<>();
			boolean ok = true;
			for (int n = 0; n < list.size(); n++) {
				int mark = enter("[" + n + "]");
				Object[] date = date(list.get(n), false, "a date", "dates[" + n + "]");
				if (date == null) {
					ok = false;
					dates.add(null);
				} else {
					if (dates.contains(date[0])) {
						refuse("duplicateId: the date is listed twice", I18NConstants.SCHEDULE_DATE_TWICE);
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
				String sun = string(map.get("sun"), 1, 15, "malformed: sun", "sun");
				if (sun != null) {
					SunEvent event = SunEvent.fromName(sun);
					if (event == null) {
						refuse("malformed: sun must be dawn, sunrise, sunset or dusk", I18NConstants.SCHEDULE_SUN);
					} else {
						spec._sun = event;
					}
				}
				leave(mark);
			}
			if (map.containsKey("offset")) {
				int mark = enter(".offset");
				Long ms = integer(map.get("offset"), -43200000L, 43200000L, "malformed: offset (milliseconds, at most 12 h)", "offset", Unit.OFFSET);
				if (ms != null) {
					if (ms.longValue() % 1000 != 0) {
						refuse("malformed: offset must be a whole number of seconds",
							I18NConstants.SCHEDULE_WHOLE_SECONDS__NAME.fill("offset"));
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
				refuse("malformed: notBefore is later than notAfter",
					I18NConstants.SCHEDULE_NOT_BEFORE_AFTER_NOT_AFTER);
			}
		}

		private void interval(Map<?, ?> map, Schedule spec, String kind) {
			if (required(map, "every", kind)) {
				int mark = enter(".every");
				Long ms = integer(map.get("every"), 60000L, 43200000L, "malformed: every (milliseconds, 1 min .. 12 h)", "every", Unit.EVERY);
				if (ms != null) {
					if (ms.longValue() % 60000 != 0) {
						refuse("malformed: every must be a whole number of minutes",
							I18NConstants.SCHEDULE_WHOLE_MINUTES__NAME.fill("every"));
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
				refuse("malformed: until is earlier than from (a window across midnight needs two entries)",
					I18NConstants.SCHEDULE_UNTIL_BEFORE_FROM);
			}
		}
	}

}
