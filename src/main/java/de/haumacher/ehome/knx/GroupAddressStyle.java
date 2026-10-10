/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.knx;

import com.top_logic.model.TLClassifier;
import com.top_logic.util.error.TopLogicException;

/**
 * Notation of a KNX group address.
 *
 * <p>
 * A group address is a 16-bit value. ETS lets a project choose how it is written; the names of the
 * constants are the literals ETS uses in <code>ProjectInformation@GroupAddressStyle</code> and the
 * names of the classifiers of <code>ehome.logical:GroupAddressStyle</code>.
 * </p>
 */
public enum GroupAddressStyle {

	/**
	 * Main group, middle group and sub group: <code>main/middle/sub</code> with 5, 3 and 8 bits.
	 */
	ThreeLevel(3, "main/middle/sub (0-31/0-7/0-255)"),

	/**
	 * Main group and sub group: <code>main/sub</code> with 5 and 11 bits.
	 */
	TwoLevel(2, "main/sub (0-31/0-2047)"),

	/**
	 * The plain 16-bit number.
	 */
	Free(1, "number (0-65535)");

	/**
	 * The style used where nothing states one.
	 */
	public static final GroupAddressStyle DEFAULT = ThreeLevel;

	private final int _levels;

	private final String _description;

	private GroupAddressStyle(int levels, String description) {
		_levels = levels;
		_description = description;
	}

	/**
	 * Writes the given 16-bit group address in this notation.
	 */
	public String format(int value) {
		switch (this) {
			case ThreeLevel:
				return ((value >> 11) & 0x1F) + "/" + ((value >> 8) & 0x07) + "/" + (value & 0xFF);
			case TwoLevel:
				return ((value >> 11) & 0x1F) + "/" + (value & 0x7FF);
			default:
				return Integer.toString(value);
		}
	}

	/**
	 * Reads a group address written in this notation.
	 *
	 * @throws TopLogicException
	 *         If the text is not a group address in this notation.
	 */
	public int parse(String text) {
		String[] parts = text.trim().split("\\s*/\\s*", -1);
		if (parts.length != _levels) {
			throw invalid(text, _description);
		}
		return parseParts(text, parts, _description);
	}

	/**
	 * Reads a group address written in any of the notations, distinguished by the number of its
	 * levels.
	 *
	 * @throws TopLogicException
	 *         If the text is not a group address in any notation.
	 */
	public static int parseAny(String text) {
		String[] parts = text.trim().split("\\s*/\\s*", -1);
		for (GroupAddressStyle style : values()) {
			if (style._levels == parts.length) {
				return parseParts(text, parts, style._description);
			}
		}
		throw invalid(text, ThreeLevel._description + ", " + TwoLevel._description + ", "
			+ Free._description);
	}

	private static int parseParts(String text, String[] parts, String description) {
		try {
			switch (parts.length) {
				case 3:
					return level(parts[0], 31) << 11 | level(parts[1], 7) << 8 | level(parts[2], 255);
				case 2:
					return level(parts[0], 31) << 11 | level(parts[1], 2047);
				default:
					return level(parts[0], 65535);
			}
		} catch (NumberFormatException ex) {
			throw invalid(text, description);
		}
	}

	private static int level(String part, int max) {
		int result = Integer.parseInt(part);
		if (result < 0 || result > max) {
			throw new NumberFormatException(part);
		}
		return result;
	}

	private static TopLogicException invalid(String text, String expected) {
		return new TopLogicException(I18NConstants.ERROR_INVALID_GROUP_ADDRESS__TEXT_EXPECTED.fill(text, expected));
	}

	/**
	 * The style a value from the model stands for: a classifier of
	 * <code>ehome.logical:GroupAddressStyle</code>, its name, or <code>null</code>.
	 *
	 * @return The style, or <code>null</code> if the value names none.
	 */
	public static GroupAddressStyle fromModel(Object value) {
		String name;
		if (value instanceof TLClassifier classifier) {
			name = classifier.getName();
		} else if (value instanceof String text) {
			name = text;
		} else {
			return null;
		}
		for (GroupAddressStyle style : values()) {
			if (style.name().equals(name)) {
				return style;
			}
		}
		return null;
	}

}
