/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

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
