/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.knx;

/**
 * Value of the model type <code>ehome.logical:GA</code>: a KNX group address.
 *
 * <p>
 * The database stores the plain 16-bit number (see {@link GAStorageMapping}). The notation the
 * address is written in is not part of the value but of the project it belongs to; the attribute
 * storage ({@link GroupAddressStorage}) attaches it when the value is read, so that every display
 * of the value ({@link #toString()}, {@link GALabelProvider}, {@link GAControlProvider}) writes it
 * the same way. Equality and order only consider the number.
 * </p>
 */
public final class GA implements Comparable<GA> {

	private final int _value;

	private final GroupAddressStyle _style;

	/**
	 * Creates a {@link GA}.
	 *
	 * @param value
	 *        The 16-bit group address.
	 * @param style
	 *        The notation to write the address in, <code>null</code> if unknown (written in
	 *        {@link GroupAddressStyle#DEFAULT}).
	 */
	public GA(int value, GroupAddressStyle style) {
		_value = value;
		_style = style;
	}

	/**
	 * The 16-bit group address.
	 */
	public int getValue() {
		return _value;
	}

	/**
	 * The notation of the address, <code>null</code> if unknown.
	 */
	public GroupAddressStyle getStyle() {
		return _style;
	}

	/**
	 * This address written in the given notation.
	 */
	public GA withStyle(GroupAddressStyle style) {
		return style == _style ? this : new GA(_value, style);
	}

	/**
	 * Reads a group address in the given notation, or in any notation if the style is
	 * <code>null</code>.
	 */
	public static GA parse(String text, GroupAddressStyle style) {
		int value = style == null ? GroupAddressStyle.parseAny(text) : style.parse(text);
		return new GA(value, style);
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof GA other && other._value == _value;
	}

	@Override
	public int hashCode() {
		return Integer.hashCode(_value);
	}

	@Override
	public int compareTo(GA other) {
		return Integer.compare(_value, other._value);
	}

	/**
	 * The address in its notation, e.g. <code>8/0/9</code>.
	 */
	@Override
	public String toString() {
		return (_style == null ? GroupAddressStyle.DEFAULT : _style).format(_value);
	}

}
