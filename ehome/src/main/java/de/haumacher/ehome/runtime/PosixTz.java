/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;

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
	 * Why a text is no POSIX TZ rule.
	 */
	enum Problem {
		/** The text is empty. */
		EMPTY("empty time zone", I18NConstants.IMAGE_TZ_EMPTY__TZ),
		/** A zone file instead of a rule. */
		ZONE_FILE("zone files (\":name\") are not supported, give a POSIX TZ rule", I18NConstants.IMAGE_TZ_ZONE_FILE__TZ),
		/** The name of the standard zone. */
		STANDARD_NAME("standard zone name must be 3+ letters or <...>", I18NConstants.IMAGE_TZ_STANDARD_NAME__TZ),
		/** The offset of the standard zone. */
		STANDARD_OFFSET("standard offset missing or invalid ([+-]hh[:mm[:ss]], hh <= 24)", I18NConstants.IMAGE_TZ_STANDARD_OFFSET__TZ),
		/** The name of the daylight saving zone. */
		DAYLIGHT_NAME("daylight zone name must be 3+ letters or <...>", I18NConstants.IMAGE_TZ_DAYLIGHT_NAME__TZ),
		/** The offset of the daylight saving zone. */
		DAYLIGHT_OFFSET("daylight offset invalid ([+-]hh[:mm[:ss]], hh <= 24)", I18NConstants.IMAGE_TZ_DAYLIGHT_OFFSET__TZ),
		/** The transition rules are missing. */
		RULES("a zone with daylight saving time needs both transition rules (,start,end)", I18NConstants.IMAGE_TZ_RULES__TZ),
		/** The start rule. */
		START_RULE("start rule invalid (Jn, n or Mm.w.d, optional /time)", I18NConstants.IMAGE_TZ_START_RULE__TZ),
		/** The end rule is missing. */
		END_MISSING("end rule missing", I18NConstants.IMAGE_TZ_END_MISSING__TZ),
		/** The end rule. */
		END_RULE("end rule invalid (Jn, n or Mm.w.d, optional /time)", I18NConstants.IMAGE_TZ_END_RULE__TZ),
		/** Characters after the end rule. */
		TRAILING("unexpected characters after the end rule", I18NConstants.IMAGE_TZ_TRAILING__TZ);

		private final String _text;

		private final ResKey1 _message;

		Problem(String text, ResKey1 message) {
			_text = text;
			_message = message;
		}

		/**
		 * The English reason, as the controller gives it.
		 */
		String text() {
			return _text;
		}

		/**
		 * The whole sentence for the user about the given time zone.
		 */
		ResKey message(String tz) {
			return _message.fill(tz);
		}
	}

	/**
	 * Why the given text is no POSIX TZ rule, <code>null</code> if it is one.
	 */
	static Problem check(String tz) {
		if (tz.isEmpty()) {
			return Problem.EMPTY;
		}
		if (tz.charAt(0) == ':') {
			return Problem.ZONE_FILE;
		}
		PosixTz p = new PosixTz(tz);
		if (!p.name()) {
			return Problem.STANDARD_NAME;
		}
		if (!p.time(24)) {
			return Problem.STANDARD_OFFSET;
		}
		if (p.atEnd()) {
			return null;
		}
		if (!p.name()) {
			return Problem.DAYLIGHT_NAME;
		}
		if (p.peek() != ',' && !p.atEnd() && !p.time(24)) {
			return Problem.DAYLIGHT_OFFSET;
		}
		if (p.peek() != ',') {
			return Problem.RULES;
		}
		p._pos++;
		if (!p.rule()) {
			return Problem.START_RULE;
		}
		if (p.peek() != ',') {
			return Problem.END_MISSING;
		}
		p._pos++;
		if (!p.rule()) {
			return Problem.END_RULE;
		}
		if (!p.atEnd()) {
			return Problem.TRAILING;
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
