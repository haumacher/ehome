/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

/**
 * The check of a POSIX TZ rule as the controller makes it for its setting <code>tz</code>
 * (homecontrol <code>core/src/tz.cpp</code>): <code>std offset [dst [offset] ,start ,end]</code>,
 * e.g. <code>CET-1CEST,M3.5.0,M10.5.0/3</code>.
 */
final class PosixTz {

	private final String _text;

	private int _pos;

	private PosixTz(String text) {
		_text = text;
	}

	/**
	 * Why the given text is no POSIX TZ rule, <code>null</code> if it is one.
	 */
	static String check(String tz) {
		if (tz.isEmpty()) {
			return "empty time zone";
		}
		if (tz.charAt(0) == ':') {
			return "zone files (\":name\") are not supported, give a POSIX TZ rule";
		}
		PosixTz p = new PosixTz(tz);
		if (!p.name()) {
			return "standard zone name must be 3+ letters or <...>";
		}
		if (!p.time(24)) {
			return "standard offset missing or invalid ([+-]hh[:mm[:ss]], hh <= 24)";
		}
		if (p.atEnd()) {
			return null;
		}
		if (!p.name()) {
			return "daylight zone name must be 3+ letters or <...>";
		}
		if (p.peek() != ',' && !p.atEnd() && !p.time(24)) {
			return "daylight offset invalid ([+-]hh[:mm[:ss]], hh <= 24)";
		}
		if (p.peek() != ',') {
			return "a zone with daylight saving time needs both transition rules (,start,end)";
		}
		p._pos++;
		if (!p.rule()) {
			return "start rule invalid (Jn, n or Mm.w.d, optional /time)";
		}
		if (p.peek() != ',') {
			return "end rule missing";
		}
		p._pos++;
		if (!p.rule()) {
			return "end rule invalid (Jn, n or Mm.w.d, optional /time)";
		}
		if (!p.atEnd()) {
			return "unexpected characters after the end rule";
		}
		return null;
	}

	private boolean atEnd() {
		return _pos >= _text.length();
	}

	private char peek() {
		return atEnd() ? '\0' : _text.charAt(_pos);
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isAlpha(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	/**
	 * An unsigned number of at most the given digits, followed by no further digit; -1 if none.
	 */
	private int number(int maxDigits) {
		int digits = 0;
		int value = 0;
		while (isDigit(peek()) && digits < maxDigits) {
			value = value * 10 + (peek() - '0');
			_pos++;
			digits++;
		}
		return digits > 0 && !isDigit(peek()) ? value : -1;
	}

	private boolean name() {
		int length = 0;
		if (peek() == '<') {
			_pos++;
			while (isAlpha(peek()) || isDigit(peek()) || peek() == '+' || peek() == '-') {
				_pos++;
				length++;
			}
			if (peek() != '>') {
				return false;
			}
			_pos++;
			return length >= 3;
		}
		while (isAlpha(peek())) {
			_pos++;
			length++;
		}
		return length >= 3;
	}

	private boolean time(int maxHours) {
		if (peek() == '+' || peek() == '-') {
			_pos++;
		}
		int value = number(3);
		if (value < 0 || value > maxHours) {
			return false;
		}
		for (int part = 0; part < 2 && peek() == ':'; part++) {
			_pos++;
			value = number(2);
			if (value < 0 || value > 59) {
				return false;
			}
		}
		return true;
	}

	private boolean rule() {
		if (peek() == 'J') {
			_pos++;
			int value = number(3);
			if (value < 1 || value > 365) {
				return false;
			}
		} else if (peek() == 'M') {
			_pos++;
			int month = number(2);
			if (month < 1 || month > 12 || peek() != '.') {
				return false;
			}
			_pos++;
			int week = number(1);
			if (week < 1 || week > 5 || peek() != '.') {
				return false;
			}
			_pos++;
			int day = number(1);
			if (day < 0 || day > 6) {
				return false;
			}
		} else {
			int value = number(3);
			if (value < 0 || value > 365) {
				return false;
			}
		}
		if (peek() == '/') {
			_pos++;
			return time(167);
		}
		return true;
	}

}
