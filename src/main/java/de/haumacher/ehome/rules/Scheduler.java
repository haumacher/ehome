/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import de.haumacher.ehome.rules.Astro.GeoLocation;
import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.Schedule.ClockTime;
import de.haumacher.ehome.rules.Schedule.Kind;
import de.haumacher.ehome.rules.Val.Date;

/**
 * Runs the schedule entries of a runtime image (homecontrol <code>spec/schedules.md</code>): the
 * next firing of each entry in local civil time with the DST rules of S1, the firings in the order
 * of their moments (image order on ties), missed firings by the entry's policy, the enable switch,
 * the "fired today" output datapoints and the persisted record of each entry. A port of homecontrol
 * <code>core/src/scheduler.cpp</code>.
 *
 * <p>
 * The scheduler has no timer of its own: the driver asks {@link #wakeup()} when to call
 * {@link #fireDue()}, and calls {@link #evaluate()} after the wall clock was set or stepped, the
 * location changed, and at each local midnight (for the outputs). Time comes from the injected
 * {@link TimeSource}.
 * </p>
 */
public final class Scheduler implements DatapointModel.Technology {

	/** A firing up to this late (seconds) is on time; an older due firing was missed. */
	public static final long GRACE_S = 60;

	/** How far ahead (days) the search for the next firing looks for weekly, astro and interval entries. */
	public static final int SEARCH_DAYS = 8;

	private static final long NONE = Long.MIN_VALUE;

	private static final int MAX_WALK = 2000;

	private static final int MAX_OFFSET_S = 12 * 3600;

	/**
	 * The wall clock, the time zone and the place.
	 */
	public interface TimeSource {

		/** The current UTC time in seconds since the epoch, <code>null</code> while not synchronized. */
		Long nowUtc();

		/** The offset of local time east of UTC in seconds at the UTC instant. */
		int utcOffset(long utc);

		/** The place for the astro entries, <code>null</code> without one. */
		GeoLocation location();
	}

	/**
	 * Receives the firings.
	 */
	public interface Listener {

		/** The entry fired. */
		void scheduleFired(String id);
	}

	/** Thrown by {@link Scheduler#load(List)} when the entries are refused. */
	public static final class RefusedException extends Exception {

		private final String _reason;

		RefusedException(String reason, String text) {
			super(reason + ": " + text);
			_reason = reason;
		}

		/** The reason code (<code>malformed</code>, <code>duplicateId</code>, ...). */
		public String getReason() {
			return _reason;
		}
	}

	private static final class Entry {
		Schedule _spec;

		String _key;

		long _lastFired = NONE;

		long _accounted = NONE;

		long _next = NONE;

		boolean _enabled = true;

		boolean _persistable = true;

		boolean _astroRefusalLogged;

		boolean _storedIdle;
	}

	private final TimeSource _time;

	private final Map<String, String> _records;

	private final DatapointModel _model;

	private final Consumer<String> _log;

	private final List<Listener> _listeners = new ArrayList<>();

	private List<Entry> _entries = List.of();

	private boolean _firing;

	private boolean _waitingLogged;

	/**
	 * Creates a scheduler.
	 *
	 * @param records
	 *        The persisted records, by key (<code>sch.</code> + 8 hex digits of the id's hash); keep
	 *        the map across a reboot.
	 * @param model
	 *        The model holding the output datapoints, <code>null</code> for none.
	 * @param log
	 *        Receives the log lines, prefixed by their level (<code>I </code>, <code>W </code>,
	 *        <code>E </code>).
	 */
	public Scheduler(TimeSource time, Map<String, String> records, DatapointModel model, Consumer<String> log) {
		_time = time;
		_records = records;
		_model = model;
		_log = log;
	}

	/** Registers a listener. */
	public void addListener(Listener listener) {
		_listeners.add(listener);
	}

	/** The settings key of an entry's record: <code>sch.</code> and the FNV-1a hash of the id. */
	public static String recordKey(String id) {
		int hash = 0x811c9dc5;
		for (byte b : id.getBytes(StandardCharsets.UTF_8)) {
			hash ^= b & 0xFF;
			hash *= 16777619;
		}
		return String.format(Locale.ROOT, "sch.%08x", hash);
	}

	// ---- Time ------------------------------------------------------------------------------

	private Date localDate(long utc) {
		return Values.civilOf(Math.floorDiv(utc + _time.utcOffset(utc), 86400L));
	}

	/**
	 * The UTC instant of a local date and time: a time in a spring-forward gap is the end of the gap,
	 * a time that occurs twice its first occurrence.
	 */
	long localToUtc(Date date, int hour, int minute, int second) {
		long naive = date.days() * 86400 + hour * 3600 + minute * 60 + second;
		int before = _time.utcOffset(naive - 86400);
		int after = _time.utcOffset(naive + 86400);
		if (before == after) {
			return naive - before;
		}
		long withBefore = naive - before;
		long withAfter = naive - after;
		boolean validBefore = _time.utcOffset(withBefore) == before;
		boolean validAfter = _time.utcOffset(withAfter) == after;
		if (validBefore && validAfter) {
			return Math.min(withBefore, withAfter);
		}
		if (validBefore) {
			return withBefore;
		}
		if (validAfter) {
			return withAfter;
		}
		// A gap: the first instant that has the new offset.
		long low = Math.min(withBefore, withAfter);
		long high = Math.max(withBefore, withAfter);
		while (low < high) {
			long middle = low + (high - low) / 2;
			if (_time.utcOffset(middle) == after) {
				high = middle;
			} else {
				low = middle + 1;
			}
		}
		return low;
	}

	private String formatLocal(long utc) {
		int offset = _time.utcOffset(utc);
		long local = utc + offset;
		Date date = Values.civilOf(Math.floorDiv(local, 86400L));
		long rest = Math.floorMod(local, 86400L);
		int minutes = Math.abs(offset) / 60;
		return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d (UTC%s%02d:%02d)", date.year(), date.month(),
			date.day(), rest / 3600, rest % 3600 / 60, rest % 60, offset < 0 ? "-" : "+", minutes / 60, minutes % 60);
	}

	// ---- Loading ----------------------------------------------------------------------------

	/**
	 * Replaces the entries. Validates everything first; a refusal keeps the previous entries.
	 * Restores each entry's persisted record, deletes the records of ids no longer present and
	 * evaluates (missed firings are fired or logged by the next {@link #fireDue()}, never from
	 * within the load).
	 */
	public void load(List<Schedule> specs) throws RefusedException {
		if (_firing) {
			throw new RefusedException("busy", "load from within a firing");
		}
		if (specs.size() > Schedule.MAX_ENTRIES) {
			throw new RefusedException("limitExceeded", specs.size() + " entries exceed the limit of " + Schedule.MAX_ENTRIES);
		}
		Set<String> ids = new HashSet<>();
		Set<String> outputs = new HashSet<>();
		for (int i = 0; i < specs.size(); i++) {
			Schedule spec = specs.get(i);
			validate(spec, i);
			if (!ids.add(spec._id)) {
				throw new RefusedException("duplicateId", "entry " + i + ": id " + spec._id + " used twice");
			}
			if (spec._output != null) {
				Datapoint datapoint = _model == null ? null : _model.get(spec._output);
				if (datapoint == null) {
					throw new RefusedException("unknownReference",
						"entry " + spec._id + ": output datapoint '" + spec._output + "' does not exist");
				}
				if (datapoint.type() != ValueType.BOOLEAN) {
					throw new RefusedException("typeError",
						"entry " + spec._id + ": output datapoint '" + spec._output + "' is not boolean");
				}
				if (datapoint.technology() != null && datapoint.technology() != this) {
					throw new RefusedException("outputConflict",
						"entry " + spec._id + ": output datapoint '" + spec._output + "' is owned by another technology");
				}
				if (!outputs.add(spec._output)) {
					throw new RefusedException("outputConflict", "two entries share the output datapoint '" + spec._output + "'");
				}
			}
		}
		List<Entry> entries = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (Schedule spec : specs) {
			Entry entry = new Entry();
			entry._spec = spec;
			entry._enabled = spec._enabled;
			entry._key = recordKey(spec._id);
			if (!keys.add(entry._key)) {
				entry._persistable = false;
				log("W schedule " + spec._id + " shares its settings key " + entry._key
					+ ": its last firing and enable switch are not persisted");
			}
			entries.add(entry);
		}
		_entries = entries;
		_waitingLogged = false;
		for (Entry entry : _entries) {
			restore(entry);
		}
		_records.keySet().removeIf(key -> key.startsWith("sch.") && !keys.contains(key));
		evaluate();
	}

	private void validate(Schedule spec, int index) throws RefusedException {
		String id = spec._id != null ? spec._id : "?";
		String prefix = "entry " + index + " (" + id + "): ";
		if (spec._id == null || !RuleLoader.validRuleId(spec._id)) {
			throw new RefusedException("malformed", prefix + "id must be 1..32 characters of A-Z a-z 0-9 _ -");
		}
		if (spec._label == null || spec._label.isEmpty() || spec._label.getBytes(StandardCharsets.UTF_8).length > 63) {
			throw new RefusedException("malformed", prefix + "label must be 1..63 printable bytes");
		}
		boolean usesDays = spec._kind == Kind.WEEKLY || spec._kind == Kind.ASTRO || spec._kind == Kind.INTERVAL;
		if (usesDays && (spec._days == 0 || (spec._days & ~Schedule.ALL_DAYS) != 0)) {
			throw new RefusedException("malformed", prefix + "days must name 1..7 days of the week");
		}
		switch (spec._kind) {
			case WEEKLY:
				checkTime(spec._time, prefix);
				break;
			case YEARLY:
				checkTime(spec._time, prefix);
				if (spec._month < 1 || spec._month > 12 || spec._day < 1 || spec._day > Values.daysInMonth(2024, spec._month)) {
					throw new RefusedException("malformed", prefix + "month/day is not a date");
				}
				break;
			case DATES:
				checkTime(spec._time, prefix);
				if (spec._dates.isEmpty()) {
					throw new RefusedException("malformed", prefix + "dates must list at least one date");
				}
				if (spec._dates.size() > Schedule.MAX_DATES) {
					throw new RefusedException("limitExceeded", prefix + "more dates than the limit of 128");
				}
				if (new HashSet<>(spec._dates).size() < spec._dates.size()) {
					throw new RefusedException("duplicateId", prefix + "a date is listed twice");
				}
				for (Date date : spec._dates) {
					if (date.year() < 1970 || date.year() > 2155 || !Values.validDate(date.year(), date.month(), date.day())) {
						throw new RefusedException("malformed", prefix + "a date is not a valid date in 1970..2155");
					}
				}
				break;
			case ASTRO:
				if (spec._offsetSeconds < -MAX_OFFSET_S || spec._offsetSeconds > MAX_OFFSET_S) {
					throw new RefusedException("malformed", prefix + "offset beyond 12 h");
				}
				if (spec._notBefore != null) {
					checkTime(spec._notBefore, prefix);
				}
				if (spec._notAfter != null) {
					checkTime(spec._notAfter, prefix);
				}
				if (spec._notBefore != null && spec._notAfter != null && spec._notBefore.seconds() > spec._notAfter.seconds()) {
					throw new RefusedException("malformed", prefix + "notBefore is later than notAfter");
				}
				break;
			case INTERVAL:
				if (spec._everyMinutes < 1 || spec._everyMinutes > 720) {
					throw new RefusedException("malformed", prefix + "every must be 1 to 720 minutes");
				}
				if (spec._from == null || spec._until == null) {
					throw new RefusedException("malformed", prefix + "from/until out of range");
				}
				checkTime(spec._from, prefix);
				checkTime(spec._until, prefix);
				if (spec._until.seconds() < spec._from.seconds()) {
					throw new RefusedException("malformed",
						prefix + "until is earlier than from (a window across midnight needs two entries)");
				}
				break;
			case ONCE:
				if (spec._atDate == null || spec._atTime == null || spec._atDate.year() < 1970 || spec._atDate.year() > 2155
					|| !Values.validDate(spec._atDate.year(), spec._atDate.month(), spec._atDate.day())) {
					throw new RefusedException("malformed", prefix + "at is not a valid date and time");
				}
				checkTime(spec._atTime, prefix);
				break;
			default:
				throw new RefusedException("malformed", prefix + "unknown kind");
		}
	}

	private static void checkTime(ClockTime time, String prefix) throws RefusedException {
		if (time == null || time.hour() < 0 || time.hour() > 23 || time.minute() < 0 || time.minute() > 59
			|| time.second() < 0 || time.second() > 59) {
			throw new RefusedException("malformed", prefix + "time out of range");
		}
	}

	private void restore(Entry entry) {
		if (!entry._persistable) {
			return;
		}
		String value = _records.get(entry._key);
		if (value == null) {
			return;
		}
		String[] fields = value.split(";", 5);
		if (fields.length != 5 || !fields[0].equals("1")) {
			log("W schedule " + entry._spec._id + ": record " + entry._key + " unreadable (format); starting without it");
			return;
		}
		long last;
		long accounted;
		try {
			last = fields[1].isEmpty() ? NONE : Long.parseLong(fields[1]);
			accounted = fields[2].isEmpty() ? NONE : Long.parseLong(fields[2]);
		} catch (NumberFormatException ex) {
			log("W schedule " + entry._spec._id + ": record " + entry._key + " unreadable (format); starting without it");
			return;
		}
		String flags = fields[3];
		if (!fields[4].equals(entry._spec._id)) {
			log("W schedule " + entry._spec._id + ": record " + entry._key + " belongs to schedule " + fields[4]
				+ "; starting without it");
			return;
		}
		boolean wasIdle = flags.contains("i");
		entry._lastFired = last;
		// An entry idle when its record was written stayed idle until the controller stopped.
		entry._accounted = wasIdle ? NONE : accounted;
		entry._storedIdle = wasIdle;
		if (flags.startsWith("0") || flags.startsWith("1")) {
			if (!entry._spec._enabledAdjustable) {
				log("W schedule " + entry._spec._id + ": enable override removed: the image fixes the switch");
				persist(entry);
			} else {
				entry._enabled = flags.startsWith("1");
			}
		}
	}

	private void persist(Entry entry) {
		if (!entry._persistable) {
			return;
		}
		String override = entry._enabled == entry._spec._enabled ? "" : entry._enabled ? "1" : "0";
		entry._storedIdle = isIdle(entry);
		_records.put(entry._key, "1;" + (entry._lastFired == NONE ? "" : Long.toString(entry._lastFired)) + ";"
			+ (entry._accounted == NONE ? "" : Long.toString(entry._accounted)) + ";" + override
			+ (entry._storedIdle ? "i" : "") + ";" + entry._spec._id);
	}

	// ---- Accessors --------------------------------------------------------------------------

	/** The number of entries. */
	public int size() {
		return _entries.size();
	}

	/** The index of the entry, -1 if none. */
	public int find(String id) {
		for (int i = 0; i < _entries.size(); i++) {
			if (_entries.get(i)._spec._id.equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** Whether the entry is switched on. */
	public boolean enabled(int index) {
		return _entries.get(index)._enabled;
	}

	/** The next firing as scheduled (UTC seconds), <code>null</code> if none. */
	public Long nextFiring(int index) {
		long next = _entries.get(index)._next;
		return next == NONE ? null : Long.valueOf(next);
	}

	/** The first firing strictly after the UTC instant by the calendar alone; <code>null</code> if none. */
	public Long nextAfter(int index, long utc) {
		long next = occurrenceAfter(_entries.get(index), utc);
		return next == NONE ? null : Long.valueOf(next);
	}

	/** The UTC time of the entry's last firing, also from before a reboot; <code>null</code> if unknown. */
	public Long lastFired(int index) {
		long last = _entries.get(index)._lastFired;
		return last == NONE ? null : Long.valueOf(last);
	}

	/**
	 * Switches an entry on or off as the UI does; refused (an error text) for an unknown id or an
	 * entry the image fixes. <code>null</code> on success.
	 */
	public String setEnabled(String id, boolean value) {
		int index = find(id);
		String reason = index < 0 ? "no such schedule"
			: !_entries.get(index)._spec._enabledAdjustable ? "the image fixes this schedule's enable switch" : null;
		if (reason != null) {
			log("E refusing to switch schedule " + id + " " + (value ? "on" : "off") + ": " + reason);
			return reason;
		}
		Entry entry = _entries.get(index);
		if (entry._enabled == value) {
			return null;
		}
		entry._enabled = value;
		if (value) {
			// What passed while the entry was off is not missed.
			Long now = _time.nowUtc();
			entry._accounted = now != null ? now.longValue() : NONE;
		}
		persist(entry);
		evaluate();
		return null;
	}

	@Override
	public boolean command(Datapoint datapoint, Val value) {
		log("E refusing command to '" + datapoint.name() + "': a schedule output is set by the scheduler only");
		return false;
	}

	// ---- The calendar -----------------------------------------------------------------------

	private boolean hasDay(Entry entry, long days) {
		return (entry._spec._days & (1 << (Values.weekdayOfDays(days) - 1))) != 0;
	}

	private long occurrenceOn(Entry entry, Date date) {
		Schedule spec = entry._spec;
		switch (spec._kind) {
			case WEEKLY:
			case YEARLY:
			case DATES:
				return localToUtc(date, spec._time.hour(), spec._time.minute(), spec._time.second());
			case ONCE:
				return localToUtc(date, spec._atTime.hour(), spec._atTime.minute(), spec._atTime.second());
			case ASTRO: {
				GeoLocation where = _time.location();
				Long sun = where == null ? null : Astro.sunTime(date.year(), date.month(), date.day(), where, spec._sun);
				if (sun == null) {
					return NONE;
				}
				long at = sun.longValue() + spec._offsetSeconds;
				if (spec._notBefore != null) {
					at = Math.max(at, localToUtc(date, spec._notBefore.hour(), spec._notBefore.minute(), spec._notBefore.second()));
				}
				if (spec._notAfter != null) {
					at = Math.min(at, localToUtc(date, spec._notAfter.hour(), spec._notAfter.minute(), spec._notAfter.second()));
				}
				return at;
			}
			default:
				return NONE;
		}
	}

	private long intervalAfter(Entry entry, Date date, long utc) {
		Schedule spec = entry._spec;
		long step = spec._everyMinutes * 60L;
		long from = spec._from.seconds();
		long until = spec._until.seconds();
		long lastSlot = (until - from) / step;
		// Start two hours before the local time of `utc` (more than any UTC offset change).
		long localOfUtc = utc + _time.utcOffset(utc) - date.days() * 86400;
		long firstSlot = 0;
		long margin = localOfUtc - 7200 - from;
		if (margin > 0) {
			firstSlot = margin / step;
		}
		for (long k = firstSlot; k <= lastSlot; k++) {
			long local = from + k * step;
			long at = localToUtc(date, (int) (local / 3600), (int) (local % 3600 / 60), (int) (local % 60));
			if (at > utc) {
				return at;
			}
		}
		return NONE;
	}

	private long occurrenceAfter(Entry entry, long utc) {
		Schedule spec = entry._spec;
		Date local = localDate(utc);
		long today = local.days();
		switch (spec._kind) {
			case WEEKLY:
			case INTERVAL:
				for (int d = -1; d <= SEARCH_DAYS; d++) {
					long days = today + d;
					if (!hasDay(entry, days)) {
						continue;
					}
					Date date = Values.civilOf(days);
					long at = spec._kind == Kind.INTERVAL ? intervalAfter(entry, date, utc) : occurrenceOn(entry, date);
					if (at != NONE && at > utc) {
						return at;
					}
				}
				return NONE;
			case ASTRO: {
				// The earliest over the window: an offset or a clamp may shift a day's firing across a neighbour's.
				long best = NONE;
				for (int d = -1; d <= SEARCH_DAYS; d++) {
					long days = today + d;
					if (!hasDay(entry, days)) {
						continue;
					}
					long at = occurrenceOn(entry, Values.civilOf(days));
					if (at != NONE && at > utc && (best == NONE || at < best)) {
						best = at;
					}
				}
				return best;
			}
			case YEARLY:
				for (int year = local.year() - 1; year <= local.year() + 8; year++) {
					if (spec._day > Values.daysInMonth(year, spec._month)) {
						// 29 February outside a leap year.
						continue;
					}
					long at = occurrenceOn(entry, new Date(year, spec._month, spec._day));
					if (at != NONE && at > utc) {
						return at;
					}
				}
				return NONE;
			case DATES: {
				List<Date> sorted = new ArrayList<>(spec._dates);
				sorted.sort((a, b) -> Long.compare(a.days(), b.days()));
				for (Date date : sorted) {
					if (date.days() < today - 1) {
						continue;
					}
					long at = occurrenceOn(entry, date);
					if (at != NONE && at > utc) {
						return at;
					}
				}
				return NONE;
			}
			case ONCE: {
				long at = occurrenceOn(entry, spec._atDate);
				return at != NONE && at > utc ? at : NONE;
			}
			default:
				return NONE;
		}
	}

	private long latestUpTo(Entry entry, long first, long now) {
		long cursor = first;
		// Dense kinds: start from a recent occurrence instead of walking the whole gap.
		for (long back : new long[] { 3600, 86400, 8 * 86400 }) {
			if (now - back <= cursor) {
				break;
			}
			long probe = occurrenceAfter(entry, now - back);
			if (probe != NONE && probe <= now) {
				cursor = probe;
				break;
			}
		}
		for (int step = 0; step < MAX_WALK; step++) {
			long next = occurrenceAfter(entry, cursor);
			if (next == NONE || next > now) {
				break;
			}
			cursor = next;
		}
		return cursor;
	}

	// ---- Evaluation -------------------------------------------------------------------------

	private static boolean isIdle(Entry entry) {
		return !entry._enabled || entry._astroRefusalLogged;
	}

	private void idle(Entry entry, long now) {
		// An entry that does not run misses nothing: its accounted moment follows the clock.
		if (entry._accounted == NONE || entry._accounted < now - 1) {
			entry._accounted = now - 1;
		}
		if (!entry._storedIdle) {
			persist(entry);
		}
	}

	private void updateOutputs(long now) {
		if (_model == null) {
			return;
		}
		Date today = localDate(now);
		for (Entry entry : _entries) {
			if (entry._spec._output == null) {
				continue;
			}
			boolean value = entry._lastFired != NONE && localDate(entry._lastFired).equals(today);
			Datapoint datapoint = _model.get(entry._spec._output);
			if (datapoint != null && datapoint.quality() == DatapointModel.Quality.GOOD
				&& Val.identical(datapoint.value(), Val.ofBool(value))) {
				continue;
			}
			_model.report(entry._spec._output, Val.ofBool(value));
		}
	}

	/**
	 * Re-evaluates all entries now: what every change of the wall clock, the time zone or the
	 * location and every local midnight requires.
	 */
	public void evaluate() {
		if (_firing) {
			return;
		}
		Long wall = _time.nowUtc();
		if (wall == null) {
			for (Entry entry : _entries) {
				entry._next = NONE;
			}
			if (!_entries.isEmpty() && !_waitingLogged) {
				_waitingLogged = true;
				log("I schedules wait for a synchronized wall clock");
			}
			return;
		}
		long now = wall.longValue();
		_waitingLogged = false;
		updateOutputs(now);
		for (Entry entry : _entries) {
			entry._next = NONE;
			if (!entry._enabled) {
				idle(entry, now);
				continue;
			}
			if (entry._spec._kind == Kind.ASTRO) {
				if (_time.location() == null) {
					if (!entry._astroRefusalLogged) {
						entry._astroRefusalLogged = true;
						log("E schedule " + entry._spec._id + " (astro " + entry._spec._sun.externalName()
							+ ") refused: astronomy needs a location (settings location.lat, location.lon)");
					}
					idle(entry, now);
					continue;
				}
				if (entry._astroRefusalLogged) {
					entry._astroRefusalLogged = false;
					persist(entry);
				}
			}
			if (entry._accounted == NONE) {
				// A new entry: from now on.
				entry._accounted = now - 1;
				persist(entry);
			}
			long first = occurrenceAfter(entry, entry._accounted);
			if (first != NONE && first <= now) {
				entry._next = first;
				continue;
			}
			entry._next = occurrenceAfter(entry, Math.max(entry._accounted, now));
		}
	}

	/**
	 * When {@link #fireDue()} should run next (UTC seconds): now if an entry is due, the earliest
	 * next firing otherwise; <code>null</code> if nothing is scheduled.
	 */
	public Long wakeup() {
		long earliest = NONE;
		for (Entry entry : _entries) {
			if (entry._next != NONE && (earliest == NONE || entry._next < earliest)) {
				earliest = entry._next;
			}
		}
		return earliest == NONE ? null : Long.valueOf(earliest);
	}

	/**
	 * Fires the due entries in the order of their moments (image order on ties), handles missed
	 * firings by the entries' policies, and evaluates.
	 */
	public void fireDue() {
		Long wall = _time.nowUtc();
		if (wall == null) {
			evaluate();
			return;
		}
		long now = wall.longValue();
		for (Entry entry : _entries) {
			entry._next = NONE;
			if (!entry._enabled || entry._accounted == NONE) {
				continue;
			}
			long first = occurrenceAfter(entry, entry._accounted);
			if (first == NONE || first > now) {
				continue;
			}
			long last = latestUpTo(entry, first, now);
			boolean onTime = now - last <= GRACE_S;
			if (first < last || !onTime) {
				long lastMissed = last;
				if (onTime) {
					// The latest is on time; the missed ones end before it.
					long before = first;
					for (int step = 0; step < MAX_WALK; step++) {
						long next = occurrenceAfter(entry, before);
						if (next == NONE || next >= last) {
							break;
						}
						before = next;
					}
					lastMissed = before;
				}
				String action = onTime ? "firing the current one" : entry._spec._catchUp ? "catching up once" : "skipped";
				if (first == lastMissed) {
					log("W schedule " + entry._spec._id + " missed its firing of " + formatLocal(first) + "; " + action);
				} else {
					log("W schedule " + entry._spec._id + " missed its firings from " + formatLocal(first) + " to "
						+ formatLocal(lastMissed) + "; " + action);
				}
			}
			if (onTime || entry._spec._catchUp) {
				entry._next = last;
			} else {
				entry._accounted = last;
				persist(entry);
			}
		}
		_firing = true;
		try {
			while (true) {
				Entry pick = null;
				for (Entry entry : _entries) {
					if (entry._next != NONE && entry._enabled && (pick == null || entry._next < pick._next)) {
						pick = entry;
					}
				}
				if (pick == null) {
					break;
				}
				long occurrence = pick._next;
				pick._next = NONE;
				boolean caughtUp = now - occurrence > GRACE_S;
				pick._accounted = occurrence;
				fire(pick, now, caughtUp);
			}
		} finally {
			_firing = false;
		}
		evaluate();
	}

	private void fire(Entry entry, long now, boolean caughtUp) {
		entry._lastFired = now;
		persist(entry);
		if (caughtUp) {
			log("I schedule " + entry._spec._id + " fired (catching up the firing of " + formatLocal(entry._accounted) + ")");
		} else {
			log("I schedule " + entry._spec._id + " fired");
		}
		if (entry._spec._output != null && _model != null) {
			_model.report(entry._spec._output, Val.ofBool(true));
		}
		for (Listener listener : List.copyOf(_listeners)) {
			listener.scheduleFired(entry._spec._id);
		}
	}

	private void log(String line) {
		_log.accept(line);
	}

	/** The local date of a UTC instant in the scheduler's time zone. */
	public Date localDateOf(long utc) {
		return localDate(utc);
	}

	/** The local sun event of today, for the rules' sunrise() etc. */
	public Val.TimeOfDay sunTime(long utc, SunEvent event) {
		GeoLocation where = _time.location();
		if (where == null) {
			return null;
		}
		Date today = localDate(utc);
		Long at = Astro.sunTime(today.year(), today.month(), today.day(), where, event);
		if (at == null) {
			return null;
		}
		long local = Math.floorMod(at.longValue() + _time.utcOffset(at.longValue()), 86400L);
		return new Val.TimeOfDay((int) (local / 3600), (int) (local % 3600 / 60), (int) Math.min(59, local % 60), 0);
	}

}
