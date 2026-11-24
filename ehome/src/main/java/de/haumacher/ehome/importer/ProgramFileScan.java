/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the completions of known programs ask of a program file before they read it, found in one pass
 * over its bytes (haumacher/ehome #213): which of the elements they complete it mentions, the IDs of
 * its application programs, and whether a channel may have a number that is not an integer.
 *
 * <p>
 * A program file has megabytes. The completions read it (as XML, see <code>Importer.ProgramFacts</code>)
 * only for the known programs an earlier import left incomplete; the programs of the file are mostly
 * complete (created by this import or completed before). The pass tells that without reading the file
 * as XML.
 * </p>
 *
 * <p>
 * The pass errs only to the side of reading: an element it mentions is written as by
 * <code>Importer.mentions(byte[], byte[])</code> (the name after <code>&lt;</code>, followed by a
 * space); the IDs are those of every start tag <code>ApplicationProgram</code>, also within comments,
 * so a superset of the programs of the file. If an element name has a namespace prefix, or an
 * <code>ApplicationProgram</code> tag has no ID of plain characters, the IDs are not known
 * ({@link #getProgramIds()} is <code>null</code>). A channel number with a character reference counts
 * as one that may not be an integer.
 * </p>
 */
final class ProgramFileScan {

	private static final byte[] ASSIGN = name("Assign");

	private static final byte[] BUTTON = name("Button");

	private static final byte[] CHANNEL = name("Channel");

	private static final byte[] EXTENSION = name("Extension");

	private static final byte[] APPLICATION_PROGRAM = name("ApplicationProgram");

	private static final byte[] ID = name("Id");

	private static final byte[] NUMBER = name("Number");

	private static final byte[] OPTIONS = name("Options");

	private static final byte[] NO_MANUFACTURER_CHECK = name("LegacyDoNotCheckManufacturerId");

	private boolean _assigns;

	private boolean _buttons;

	private boolean _channels;

	private boolean _extensions;

	private boolean _textChannelNumbers;

	private boolean _noManufacturerCheck;

	private List<String> _programIds = new ArrayList<>();

	private ProgramFileScan() {
		// See scan(byte[]).
	}

	/**
	 * Scans the given content of a program file.
	 */
	static ProgramFileScan scan(byte[] content) {
		ProgramFileScan result = new ProgramFileScan();
		result.read(content);
		return result;
	}

	/**
	 * Whether the file mentions an element <code>Assign</code>.
	 */
	boolean mentionsAssigns() {
		return _assigns;
	}

	/**
	 * Whether the file mentions an element <code>Button</code>.
	 */
	boolean mentionsButtons() {
		return _buttons;
	}

	/**
	 * Whether the file mentions an element <code>Channel</code>.
	 */
	boolean mentionsChannels() {
		return _channels;
	}

	/**
	 * Whether the file mentions an element <code>Extension</code>.
	 */
	boolean mentionsExtensions() {
		return _extensions;
	}

	/**
	 * Whether an element <code>Options</code> of the file switches off the check of the device's
	 * manufacturer (<code>LegacyDoNotCheckManufacturerId</code> other than <code>0</code> and
	 * <code>false</code>).
	 */
	boolean mayHaveNoManufacturerCheck() {
		return _noManufacturerCheck;
	}

	/**
	 * Whether a channel of the file may have a number that is not an integer; <code>true</code> also
	 * if the IDs of the file are not known (see {@link #getProgramIds()}).
	 */
	boolean mayHaveTextChannelNumbers() {
		return _textChannelNumbers || _programIds == null;
	}

	/**
	 * The IDs of the application programs of the file (and possibly more), <code>null</code> if not
	 * known.
	 */
	List<String> getProgramIds() {
		return _programIds == null ? null : Collections.unmodifiableList(_programIds);
	}

	private void read(byte[] content) {
		int length = content.length;
		for (int n = 0; n < length; n++) {
			if (content[n] != '<') {
				continue;
			}
			int nameStart = n + 1;
			if (nameStart >= length) {
				break;
			}
			byte first = content[nameStart];
			if (first == '!' || first == '?' || first == '/') {
				continue;
			}
			int nameEnd = nameStart;
			while (nameEnd < length && !isNameEnd(content[nameEnd])) {
				if (content[nameEnd] == ':') {
					_programIds = null;
				}
				nameEnd++;
			}
			boolean spaced = nameEnd < length && content[nameEnd] == ' ';
			if (is(content, nameStart, nameEnd, ASSIGN)) {
				_assigns |= spaced;
			} else if (is(content, nameStart, nameEnd, BUTTON)) {
				_buttons |= spaced;
			} else if (is(content, nameStart, nameEnd, EXTENSION)) {
				_extensions |= spaced;
			} else if (is(content, nameStart, nameEnd, CHANNEL)) {
				_channels |= spaced;
				String number = attribute(content, nameEnd, NUMBER);
				if (number != null && !number.isBlank() && !number.trim().matches("-?[0-9]+")) {
					_textChannelNumbers = true;
				}
			} else if (is(content, nameStart, nameEnd, OPTIONS)) {
				_noManufacturerCheck |= isSet(attribute(content, nameEnd, NO_MANUFACTURER_CHECK));
			} else if (is(content, nameStart, nameEnd, APPLICATION_PROGRAM)) {
				String id = attribute(content, nameEnd, ID);
				if (id == null || id.indexOf('&') >= 0) {
					_programIds = null;
				} else if (_programIds != null) {
					_programIds.add(id);
				}
			}
			n = nameEnd - 1;
		}
	}

	/**
	 * Whether the given value of a boolean attribute is set: present and neither <code>0</code> nor
	 * <code>false</code> (the scan errs to the side of reading).
	 */
	static boolean isSet(String value) {
		if (value == null) {
			return false;
		}
		String trimmed = value.trim();
		return !trimmed.isEmpty() && !"0".equals(trimmed) && !"false".equals(trimmed);
	}

	/**
	 * The value of the attribute with the given name in the tag whose attributes start at the given
	 * position, as written; <code>null</code> if the tag has none.
	 */
	private static String attribute(byte[] content, int start, byte[] name) {
		int length = content.length;
		int n = start;
		while (n < length) {
			while (n < length && isSpace(content[n])) {
				n++;
			}
			if (n >= length || content[n] == '>' || content[n] == '/') {
				return null;
			}
			int attrStart = n;
			while (n < length && content[n] != '=' && !isSpace(content[n]) && content[n] != '>') {
				n++;
			}
			int attrEnd = n;
			while (n < length && isSpace(content[n])) {
				n++;
			}
			if (n >= length || content[n] != '=') {
				return null;
			}
			n++;
			while (n < length && isSpace(content[n])) {
				n++;
			}
			if (n >= length || (content[n] != '"' && content[n] != '\'')) {
				return null;
			}
			byte quote = content[n++];
			int valueStart = n;
			while (n < length && content[n] != quote) {
				n++;
			}
			if (n >= length) {
				return null;
			}
			if (is(content, attrStart, attrEnd, name)) {
				return new String(content, valueStart, n - valueStart, StandardCharsets.UTF_8);
			}
			n++;
		}
		return null;
	}

	private static boolean is(byte[] content, int start, int end, byte[] name) {
		if (end - start != name.length) {
			return false;
		}
		for (int k = 0; k < name.length; k++) {
			if (content[start + k] != name[k]) {
				return false;
			}
		}
		return true;
	}

	private static boolean isNameEnd(byte b) {
		return isSpace(b) || b == '>' || b == '/';
	}

	private static boolean isSpace(byte b) {
		return b == ' ' || b == '\t' || b == '\n' || b == '\r';
	}

	private static byte[] name(String name) {
		return name.getBytes(StandardCharsets.US_ASCII);
	}

}
