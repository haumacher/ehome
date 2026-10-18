/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

/**
 * The expression types of the rule language (homecontrol <code>spec/rules.md</code> 3.1).
 */
public enum Type {

	/** <code>true</code>, <code>false</code>. */
	BOOLEAN("boolean"),

	/** 64-bit signed integer; datapoints of the types unsigned and signed read as this. */
	INTEGER("integer"),

	/** A finite IEEE 754 double. */
	REAL("real"),

	/** UTF-8, at most 28 bytes. */
	STRING("string"),

	/** Signed milliseconds. */
	DURATION("duration"),

	/** Hour, minute, second and a day of the week (0: none). */
	TIME_OF_DAY("timeOfDay"),

	/** Year, month, day. */
	DATE("date"),

	/** A date, a time of day, the day of the week and flags. */
	DATE_TIME("dateTime"),

	/** An element number 0..2^32-1. */
	ENUMERATION("enumeration"),

	/** A control bit and a number 0..255. */
	CONTROLLED("controlled"),

	/** Red, green, blue, white levels and the valid channels. */
	COLOR("color"),

	/** At most 14 bytes. */
	RAW("raw");

	private final String _name;

	Type(String name) {
		_name = name;
	}

	/**
	 * The name of the type in the specification and the image (<code>timeOfDay</code>).
	 */
	public String externalName() {
		return _name;
	}

	/**
	 * The type with the given {@link #externalName()}, <code>null</code> if there is none.
	 */
	public static Type fromName(String name) {
		for (Type type : values()) {
			if (type._name.equals(name)) {
				return type;
			}
		}
		return null;
	}

	/**
	 * Whether the type is <code>integer</code> or <code>real</code>.
	 */
	public boolean isNumeric() {
		return this == INTEGER || this == REAL;
	}

	@Override
	public String toString() {
		return _name;
	}

}
