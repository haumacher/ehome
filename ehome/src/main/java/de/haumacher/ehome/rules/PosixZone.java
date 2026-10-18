/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import de.haumacher.ehome.rules.Val.DateTime;

/**
 * A time zone given as a POSIX TZ rule, the form of the image's setting <code>tz</code>
 * (<code>CET-1CEST,M3.5.0,M10.5.0/3</code>, <code>UTC0</code>, <code>&lt;+0330&gt;-3:30</code>):
 * the UTC offset at an instant, as the controller's C library computes it.
 */
public final class PosixZone {

	/** UTC. */
	public static final PosixZone UTC = new PosixZone(0, 0, null, null);

	/** Standard and daylight saving time offsets east of UTC in seconds. */
	private final int _std;

	private final int _dst;

	private final Rule _start;

	private final Rule _end;

	/**
	 * A transition rule: <code>Jn</code> (kind 'J', day 1..365 without 29 February), <code>n</code>
	 * (kind 'n', zero-based day 0..365), or <code>Mm.w.d</code> (kind 'M'); with the local time of
	 * the transition in seconds.
	 */
	private record Rule(char kind, int day, int month, int week, int weekday, int time) {

		/** The local transition as seconds since the epoch of local time in the given year. */
		long local(int year) {
			long days;
			switch (kind) {
				case 'J': {
					long jan1 = Values.daysOf(year, 1, 1);
					boolean leap = Values.daysInMonth(year, 2) == 29;
					days = jan1 + day - 1 + (leap && day >= 60 ? 1 : 0);
					break;
				}
				case 'n':
					days = Values.daysOf(year, 1, 1) + day;
					break;
				default: {
					long first = Values.daysOf(year, month, 1);
					int firstWeekday = Values.weekdayOfDays(first) % 7; // 0 Sunday .. 6 Saturday
					int dayOfMonth = 1 + Math.floorMod(weekday - firstWeekday, 7) + (week - 1) * 7;
					int limit = Values.daysInMonth(year, month);
					while (dayOfMonth > limit) {
						dayOfMonth -= 7;
					}
					days = first + dayOfMonth - 1;
				}
			}
			return days * 86400 + time;
		}
	}

	private PosixZone(int std, int dst, Rule start, Rule end) {
		_std = std;
		_dst = dst;
		_start = start;
		_end = end;
	}

	/**
	 * The offset of local time east of UTC in seconds at the UTC instant.
	 */
	public int offset(long utc) {
		if (_start == null) {
			return _std;
		}
		int year = Values.civilOf(Math.floorDiv(utc + _std, 86400L)).year();
		long start = _start.local(year) - _std;
		long end = _end.local(year) - _dst;
		boolean dst = start < end ? utc >= start && utc < end : utc < end || utc >= start;
		return dst ? _dst : _std;
	}

	/**
	 * Whether daylight saving time (KNX: summer time) is in effect at the UTC instant.
	 */
	public boolean summerTime(long utc) {
		return _start != null && _dst != _std && offset(utc) == _dst;
	}

	/**
	 * The local civil time of a UTC instant with its day of the week and the flag
	 * {@link DateTime#SUMMER_TIME} while daylight saving time is in effect; <code>null</code> for a
	 * year outside 1900..2155.
	 */
	public DateTime local(long utc) {
		long local = utc + offset(utc);
		long days = Math.floorDiv(local, 86400L);
		long rest = local - days * 86400;
		Val.Date date = Values.civilOf(days);
		if (date.year() < 1900 || date.year() > 2155) {
			return null;
		}
		return new DateTime(date.year(), date.month(), date.day(), (int) (rest / 3600), (int) (rest % 3600 / 60),
			(int) (rest % 60), Values.weekdayOf(date.year(), date.month(), date.day()),
			summerTime(utc) ? DateTime.SUMMER_TIME : 0);
	}

	/**
	 * The UTC instant of a local civil time (<code>spec/rules.md</code> 4.6): a time that occurs twice
	 * (the hour before the end of summer time) is its summer-time occurrence when the flag
	 * {@link DateTime#SUMMER_TIME} is set, its standard-time occurrence otherwise; a time in the gap
	 * at the start of summer time is the end of the gap.
	 */
	public long utc(DateTime local) {
		return utc(local.seconds(), local.summerTime());
	}

	/**
	 * The UTC instant of local seconds since the epoch (see {@link #utc(DateTime)}).
	 */
	public long utc(long local, boolean summer) {
		long asStd = local - _std;
		if (_start == null || _std == _dst) {
			return asStd;
		}
		long asDst = local - _dst;
		boolean std = offset(asStd) == _std;
		boolean dst = offset(asDst) == _dst;
		if (std && dst) {
			return summer ? asDst : asStd;
		}
		if (std) {
			return asStd;
		}
		if (dst) {
			return asDst;
		}
		// In the gap: the transition between the two readings.
		long low = Math.min(asStd, asDst);
		long high = Math.max(asStd, asDst);
		int before = offset(low);
		while (high - low > 1) {
			long middle = low + (high - low) / 2;
			if (offset(middle) == before) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return high;
	}

	/**
	 * Parses a POSIX TZ rule.
	 *
	 * @throws IllegalArgumentException
	 *         When the text is no POSIX TZ rule with both transition rules for a DST zone.
	 */
	public static PosixZone parse(String tz) {
		Parser p = new Parser(tz);
		p.name();
		int std = -p.offset();
		if (p.atEnd()) {
			return new PosixZone(std, std, null, null);
		}
		p.name();
		int dst = std + 3600;
		if (!p.at(',')) {
			dst = -p.offset();
		}
		p.expect(',');
		Rule start = p.rule();
		p.expect(',');
		Rule end = p.rule();
		if (!p.atEnd()) {
			throw new IllegalArgumentException("Trailing text in time zone '" + tz + "'.");
		}
		return new PosixZone(std, dst, start, end);
	}

	private static final class Parser {
		private final String _text;

		private int _pos;

		Parser(String text) {
			_text = text;
		}

		boolean atEnd() {
			return _pos == _text.length();
		}

		boolean at(char c) {
			return _pos < _text.length() && _text.charAt(_pos) == c;
		}

		void expect(char c) {
			if (!at(c)) {
				throw error("'" + c + "' expected");
			}
			_pos++;
		}

		IllegalArgumentException error(String what) {
			return new IllegalArgumentException("Time zone '" + _text + "' at " + _pos + ": " + what + ".");
		}

		void name() {
			if (at('<')) {
				int close = _text.indexOf('>', _pos);
				if (close < 0) {
					throw error("'>' expected");
				}
				_pos = close + 1;
				return;
			}
			int start = _pos;
			while (_pos < _text.length() && Character.isLetter(_text.charAt(_pos))) {
				_pos++;
			}
			if (_pos - start < 3) {
				throw error("a zone name of 3 or more letters expected");
			}
		}

		int number() {
			int start = _pos;
			int value = 0;
			while (_pos < _text.length() && Character.isDigit(_text.charAt(_pos))) {
				value = value * 10 + (_text.charAt(_pos++) - '0');
			}
			if (_pos == start) {
				throw error("a number expected");
			}
			return value;
		}

		/** [+-]hh[:mm[:ss]] in seconds. */
		int offset() {
			int sign = 1;
			if (at('+')) {
				_pos++;
			} else if (at('-')) {
				sign = -1;
				_pos++;
			}
			int seconds = number() * 3600;
			if (at(':')) {
				_pos++;
				seconds += number() * 60;
				if (at(':')) {
					_pos++;
					seconds += number();
				}
			}
			return sign * seconds;
		}

		Rule rule() {
			char kind;
			int day = 0;
			int month = 0;
			int week = 0;
			int weekday = 0;
			if (at('J')) {
				_pos++;
				kind = 'J';
				day = number();
			} else if (at('M')) {
				_pos++;
				kind = 'M';
				month = number();
				expect('.');
				week = number();
				expect('.');
				weekday = number();
			} else {
				kind = 'n';
				day = number();
			}
			int time = 2 * 3600;
			if (at('/')) {
				_pos++;
				time = offset();
			}
			return new Rule(kind, day, month, week, weekday, time);
		}
	}

}
