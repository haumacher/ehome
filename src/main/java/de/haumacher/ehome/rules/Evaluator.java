/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.util.Arrays;

/**
 * Comparisons of the rule language (<code>spec/rules.md</code> 4.5), shared by the loader (bounds
 * of parameters) and the {@link Engine}.
 */
public final class Evaluator {

	private Evaluator() {
		// Static helpers.
	}

	/**
	 * Ordering of two present values of ordered types; a numeric mix compares as reals.
	 */
	public static int order(Val a, Val b) {
		if (a.type().isNumeric() && b.type().isNumeric()) {
			if (a.type() == Type.REAL || b.type() == Type.REAL) {
				double x = a.number();
				double y = b.number();
				return x < y ? -1 : x > y ? 1 : 0;
			}
			return Long.compare(a.integer(), b.integer());
		}
		switch (a.type()) {
			case DURATION:
				return Long.compare(a.integer(), b.integer());
			case TIME_OF_DAY:
				return Integer.compare(a.time().secondsOfDay(), b.time().secondsOfDay());
			case DATE:
				return Long.compare(a.date().days(), b.date().days());
			case DATE_TIME:
				return Long.compare(a.dateTime().seconds(), b.dateTime().seconds());
			case STRING:
				return Integer.signum(Arrays.compareUnsigned(a.bytes(), b.bytes()));
			default:
				return 0;
		}
	}

	/**
	 * Equality of the language, numeric and enumeration/integer mixes included.
	 */
	public static boolean equal(Val a, Val b) {
		if (a.type().isNumeric() && b.type().isNumeric()) {
			if (a.type() == Type.REAL || b.type() == Type.REAL) {
				return a.number() == b.number();
			}
			return a.integer() == b.integer();
		}
		if ((a.type() == Type.ENUMERATION && b.type() == Type.INTEGER)
			|| (a.type() == Type.INTEGER && b.type() == Type.ENUMERATION)) {
			return a.integer() == b.integer();
		}
		return Val.equal(a, b);
	}

	/**
	 * An integer promoted to a real where the static type is real.
	 */
	public static Val promote(Val value, Type type) {
		if (value.has() && type == Type.REAL && value.type() == Type.INTEGER) {
			return Val.ofReal(value.integer());
		}
		return value;
	}

}
