/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

/**
 * The value types of datapoints (homecontrol <code>doc/datapoints.md</code>, the names of
 * <code>spec/rules.md</code> 3.1) and the expression type each reads as.
 */
public enum ValueType {

	/** A boolean. */
	BOOLEAN("boolean", Type.BOOLEAN),

	/** 0..4294967295, read as integer. */
	UNSIGNED("unsigned", Type.INTEGER),

	/** -2147483648..2147483647, read as integer. */
	SIGNED("signed", Type.INTEGER),

	/** A real. */
	REAL("real", Type.REAL),

	/** A string of at most 28 bytes. */
	STRING("string", Type.STRING),

	/** A time of day. */
	TIME_OF_DAY("timeOfDay", Type.TIME_OF_DAY),

	/** A date. */
	DATE("date", Type.DATE),

	/** A date and time. */
	DATE_TIME("dateTime", Type.DATE_TIME),

	/** An enumeration element. */
	ENUMERATION("enumeration", Type.ENUMERATION),

	/** A controlled value. */
	CONTROLLED("controlled", Type.CONTROLLED),

	/** A color. */
	COLOR("color", Type.COLOR),

	/** Raw bytes. */
	RAW("raw", Type.RAW);

	private final String _name;

	private final Type _type;

	ValueType(String name, Type type) {
		_name = name;
		_type = type;
	}

	/**
	 * The name in the image (<code>unsigned</code>, <code>timeOfDay</code>).
	 */
	public String externalName() {
		return _name;
	}

	/**
	 * The expression type a datapoint of this type reads as.
	 */
	public Type expressionType() {
		return _type;
	}

	/**
	 * The value type with the given {@link #externalName()}, <code>null</code> if there is none.
	 */
	public static ValueType fromName(String name) {
		for (ValueType type : values()) {
			if (type._name.equals(name)) {
				return type;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return _name;
	}

}
