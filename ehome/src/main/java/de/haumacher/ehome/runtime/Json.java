/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading and writing the JSON of a runtime image.
 *
 * <p>
 * Objects are read as {@link LinkedHashMap}s in the order of their members (a member written twice
 * keeps its last value), arrays as {@link List}s, numbers as {@link Long} where written without
 * fraction and exponent and in range, otherwise as {@link Double}. The nesting is limited as the
 * controller limits it (spec/runtime-image.md, I3).
 * </p>
 *
 * <p>
 * Written JSON is indented with tabs; an object or array holding only plain values (and arrays of
 * them) is written on one line, so a datapoint reads as one line per member.
 * </p>
 */
public final class Json {

	/**
	 * A text that is no JSON, with the byte offset where it fails.
	 */
	public static final class JsonException extends Exception {

		private final int _offset;

		/**
		 * Creates a {@link JsonException}.
		 */
		public JsonException(String message, int offset) {
			super(message);
			_offset = offset;
		}

		/**
		 * The character offset of the failure.
		 */
		public int getOffset() {
			return _offset;
		}
	}

	private final String _text;

	private final int _maxDepth;

	private int _pos;

	private Json(String text, int maxDepth) {
		_text = text;
		_maxDepth = maxDepth;
	}

	/**
	 * Reads a JSON document.
	 *
	 * @param maxDepth
	 *        The deepest nesting of objects and arrays accepted.
	 */
	public static Object parse(String text, int maxDepth) throws JsonException {
		Json parser = new Json(text, maxDepth);
		parser.skipSpace();
		Object result = parser.value(0);
		parser.skipSpace();
		if (parser._pos < text.length()) {
			throw parser.error("unexpected text after the document");
		}
		return result;
	}

	private Object value(int depth) throws JsonException {
		if (_pos >= _text.length()) {
			throw error("unexpected end");
		}
		char c = _text.charAt(_pos);
		switch (c) {
			case '{':
				return object(depth + 1);
			case '[':
				return array(depth + 1);
			case '"':
				return string();
			case 't':
				return literal("true", Boolean.TRUE);
			case 'f':
				return literal("false", Boolean.FALSE);
			case 'n':
				return literal("null", null);
			default:
				if (c == '-' || (c >= '0' && c <= '9')) {
					return number();
				}
				throw error("unexpected character '" + c + "'");
		}
	}

	private Map<String, Object> object(int depth) throws JsonException {
		checkDepth(depth);
		_pos++;
		Map<String, Object> result = new LinkedHashMap<>();
		skipSpace();
		if (peek() == '}') {
			_pos++;
			return result;
		}
		while (true) {
			skipSpace();
			if (peek() != '"') {
				throw error("a member name expected");
			}
			String name = string();
			skipSpace();
			expect(':');
			skipSpace();
			result.remove(name);
			result.put(name, value(depth));
			skipSpace();
			char c = peek();
			_pos++;
			if (c == '}') {
				return result;
			}
			if (c != ',') {
				_pos--;
				throw error("',' or '}' expected");
			}
		}
	}

	private List<Object> array(int depth) throws JsonException {
		checkDepth(depth);
		_pos++;
		List<Object> result = new ArrayList<>();
		skipSpace();
		if (peek() == ']') {
			_pos++;
			return result;
		}
		while (true) {
			skipSpace();
			result.add(value(depth));
			skipSpace();
			char c = peek();
			_pos++;
			if (c == ']') {
				return result;
			}
			if (c != ',') {
				_pos--;
				throw error("',' or ']' expected");
			}
		}
	}

	private void checkDepth(int depth) throws JsonException {
		if (depth > _maxDepth) {
			throw error("nested deeper than " + _maxDepth);
		}
	}

	private String string() throws JsonException {
		_pos++;
		StringBuilder result = new StringBuilder();
		while (true) {
			if (_pos >= _text.length()) {
				throw error("unterminated string");
			}
			char c = _text.charAt(_pos++);
			if (c == '"') {
				return result.toString();
			}
			if (c < 0x20) {
				_pos--;
				throw error("control character in a string");
			}
			if (c != '\\') {
				result.append(c);
				continue;
			}
			if (_pos >= _text.length()) {
				throw error("unterminated string");
			}
			char e = _text.charAt(_pos++);
			switch (e) {
				case '"':
				case '\\':
				case '/':
					result.append(e);
					break;
				case 'b':
					result.append('\b');
					break;
				case 'f':
					result.append('\f');
					break;
				case 'n':
					result.append('\n');
					break;
				case 'r':
					result.append('\r');
					break;
				case 't':
					result.append('\t');
					break;
				case 'u':
					if (_pos + 4 > _text.length()) {
						throw error("invalid escape");
					}
					try {
						result.append((char) Integer.parseInt(_text.substring(_pos, _pos + 4), 16));
					} catch (NumberFormatException ex) {
						throw error("invalid escape");
					}
					_pos += 4;
					break;
				default:
					_pos--;
					throw error("invalid escape");
			}
		}
	}

	private Object literal(String word, Object value) throws JsonException {
		if (!_text.startsWith(word, _pos)) {
			throw error("unexpected character '" + _text.charAt(_pos) + "'");
		}
		_pos += word.length();
		return value;
	}

	private Number number() throws JsonException {
		int start = _pos;
		if (peek() == '-') {
			_pos++;
		}
		int digits = digits();
		if (digits == 0) {
			throw error("invalid number");
		}
		boolean integral = true;
		if (peek() == '.') {
			_pos++;
			integral = false;
			if (digits() == 0) {
				throw error("invalid number");
			}
		}
		if (peek() == 'e' || peek() == 'E') {
			_pos++;
			integral = false;
			if (peek() == '+' || peek() == '-') {
				_pos++;
			}
			if (digits() == 0) {
				throw error("invalid number");
			}
		}
		String text = _text.substring(start, _pos);
		if (integral) {
			try {
				return Long.valueOf(Long.parseLong(text));
			} catch (NumberFormatException ex) {
				// Out of the range of a long: read as a double.
			}
		}
		return Double.valueOf(new BigDecimal(text).doubleValue());
	}

	private int digits() {
		int count = 0;
		while (_pos < _text.length() && Character.isDigit(_text.charAt(_pos)) && _text.charAt(_pos) < 128) {
			_pos++;
			count++;
		}
		return count;
	}

	private char peek() {
		return _pos < _text.length() ? _text.charAt(_pos) : '\0';
	}

	private void expect(char c) throws JsonException {
		if (peek() != c) {
			throw error("'" + c + "' expected");
		}
		_pos++;
	}

	private void skipSpace() {
		while (_pos < _text.length()) {
			char c = _text.charAt(_pos);
			if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
				return;
			}
			_pos++;
		}
	}

	private JsonException error(String message) {
		return new JsonException(message, _pos);
	}

	/**
	 * Writes a value (maps, collections, texts, numbers, truth values and <code>null</code>) as
	 * indented JSON.
	 */
	public static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(out, value, 0);
		out.append('\n');
		return out.toString();
	}

	private static void write(StringBuilder out, Object value, int indent) {
		if (value instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				out.append("{}");
				return;
			}
			boolean inline = isFlat(map.values());
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					out.append(',');
					if (inline) {
						out.append(' ');
					}
				}
				first = false;
				if (!inline) {
					newLine(out, indent + 1);
				}
				string(out, String.valueOf(entry.getKey()));
				out.append(": ");
				write(out, entry.getValue(), indent + 1);
			}
			if (!inline) {
				newLine(out, indent);
			}
			out.append('}');
		} else if (value instanceof Collection<?> list) {
			if (list.isEmpty()) {
				out.append("[]");
				return;
			}
			boolean inline = isFlat(list);
			out.append('[');
			boolean first = true;
			for (Object element : list) {
				if (!first) {
					out.append(',');
					if (inline) {
						out.append(' ');
					}
				}
				first = false;
				if (!inline) {
					newLine(out, indent + 1);
				}
				write(out, element, indent + 1);
			}
			if (!inline) {
				newLine(out, indent);
			}
			out.append(']');
		} else if (value instanceof String text) {
			string(out, text);
		} else if (value instanceof Double || value instanceof Float) {
			double number = ((Number) value).doubleValue();
			if (number == Math.rint(number) && Math.abs(number) < 1e15) {
				out.append((long) number);
			} else {
				out.append(BigDecimal.valueOf(number).toPlainString());
			}
		} else if (value == null) {
			out.append("null");
		} else {
			out.append(value);
		}
	}

	/**
	 * Writes a value as {@link #write(Object)} does, on one line without spaces and without a final
	 * line break: the form of a message on a WebSocket.
	 */
	public static String compact(Object value) {
		StringBuilder out = new StringBuilder();
		compact(out, value);
		return out.toString();
	}

	private static void compact(StringBuilder out, Object value) {
		if (value instanceof Map<?, ?> map) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				string(out, String.valueOf(entry.getKey()));
				out.append(':');
				compact(out, entry.getValue());
			}
			out.append('}');
		} else if (value instanceof Collection<?> list) {
			out.append('[');
			boolean first = true;
			for (Object element : list) {
				if (!first) {
					out.append(',');
				}
				first = false;
				compact(out, element);
			}
			out.append(']');
		} else {
			write(out, value, 0);
		}
	}

	/**
	 * The text of the value of a member of the JSON object in the given text, as it is written there
	 * (not read and written again); <code>null</code> if the object has no such member.
	 *
	 * @throws JsonException
	 *         If the text is no JSON object.
	 */
	public static String member(String text, String name, int maxDepth) throws JsonException {
		Json parser = new Json(text, maxDepth);
		parser.skipSpace();
		if (parser._pos >= text.length() || text.charAt(parser._pos) != '{') {
			throw parser.error("no object");
		}
		parser._pos++;
		String result = null;
		parser.skipSpace();
		if (parser._pos < text.length() && text.charAt(parser._pos) == '}') {
			return null;
		}
		while (true) {
			parser.skipSpace();
			if (parser._pos >= text.length() || text.charAt(parser._pos) != '"') {
				throw parser.error("expected a member name");
			}
			String key = parser.string();
			parser.skipSpace();
			if (parser._pos >= text.length() || text.charAt(parser._pos) != ':') {
				throw parser.error("expected ':'");
			}
			parser._pos++;
			parser.skipSpace();
			int start = parser._pos;
			parser.value(1);
			if (key.equals(name)) {
				result = text.substring(start, parser._pos);
			}
			parser.skipSpace();
			if (parser._pos >= text.length()) {
				throw parser.error("unexpected end");
			}
			char c = text.charAt(parser._pos++);
			if (c == '}') {
				return result;
			}
			if (c != ',') {
				throw parser.error("expected ',' or '}'");
			}
		}
	}

	/**
	 * Whether the values are plain values or arrays of plain values.
	 */
	private static boolean isFlat(Collection<?> values) {
		for (Object value : values) {
			if (value instanceof Map<?, ?> map && !map.isEmpty()) {
				return false;
			}
			if (value instanceof Collection<?> list) {
				for (Object element : list) {
					if (element instanceof Map<?, ?> || element instanceof Collection<?>) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private static void newLine(StringBuilder out, int indent) {
		out.append('\n');
		for (int n = 0; n < indent; n++) {
			out.append('\t');
		}
	}

	private static void string(StringBuilder out, String text) {
		out.append('"');
		for (int n = 0; n < text.length(); n++) {
			char c = text.charAt(n);
			switch (c) {
				case '"':
					out.append("\\\"");
					break;
				case '\\':
					out.append("\\\\");
					break;
				case '\n':
					out.append("\\n");
					break;
				case '\r':
					out.append("\\r");
					break;
				case '\t':
					out.append("\\t");
					break;
				default:
					if (c < 0x20 || c == 0x7F) {
						out.append(String.format("\\u%04x", Integer.valueOf(c)));
					} else {
						out.append(c);
					}
			}
		}
		out.append('"');
	}

}
