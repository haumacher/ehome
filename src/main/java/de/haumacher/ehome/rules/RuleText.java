/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The text syntax of the rule expressions (homecontrol <code>spec/rules-grammar.ebnf</code>,
 * <code>spec/rules.md</code> 4) and its JSON AST (<code>spec/rules.md</code> 5): the parser from
 * text to the AST and the canonical printer from the AST to text (<code>spec/rules.md</code> 4.8).
 *
 * <p>
 * Printing an AST and parsing the text gives the same AST. The AST is the parsed JSON form of the
 * image: {@link Map}s with the members in the order of the specification, {@link List}s,
 * {@link String}s, {@link Long}s for integers and durations, {@link Double}s for reals and
 * {@link Boolean}s. The parser checks the syntax only; types, references and the ranges of literal
 * values are the {@link RuleLoader}'s, which E-Home runs on the AST.
 * </p>
 *
 * <p>
 * Besides expressions: log messages (texts with <code>{expression}</code> placeholders,
 * <code>{{</code> and <code>}}</code> for literal braces, <code>spec/rules.md</code> 5) and the
 * literal values of parameters and schedule entries, written as the literals of the language
 * (<code>3min</code>, <code>21.5</code>, <code>06:30</code>, <code>2026-12-31T23:59:30</code>).
 * </p>
 *
 * <p>
 * Three ASTs have no text of their own and do not survive a print: a literal of the type
 * <code>enumeration</code> (printed as its number, which reads back as an integer), a literal of the
 * type <code>controlled</code> (printed as the call <code>controlled(bit, n)</code>) and a time of day
 * with a day of the week (printed without it). The parser never produces them.
 * </p>
 */
public final class RuleText {

	private static final long MAX_SAFE_INTEGER = 9007199254740991L;

	/**
	 * A text that is not an expression of the grammar, with the character offset where it fails.
	 */
	public static final class SyntaxError extends Exception {

		private final int _offset;

		/**
		 * Creates a {@link SyntaxError}.
		 */
		public SyntaxError(String message, int offset) {
			super(message);
			_offset = offset;
		}

		/**
		 * The offset (0-based) of the character where the text fails.
		 */
		public int getOffset() {
			return _offset;
		}

		/**
		 * The message with the position for a person: <code>at character 7: ...</code>.
		 */
		public String describe() {
			return "syntax error at character " + (_offset + 1) + ": " + getMessage();
		}
	}

	private RuleText() {
		// Static API.
	}

	// ---- API ---------------------------------------------------------------------------------

	/**
	 * Parses an expression into its JSON AST.
	 */
	public static Map<String, Object> parse(String text) throws SyntaxError {
		Parser parser = new Parser(text, 0);
		Map<String, Object> result = parser.expression();
		if (parser._token.kind != TokenKind.END) {
			throw parser.error("unexpected " + parser._token.describe() + " after the expression");
		}
		return result;
	}

	/**
	 * The canonical text of an expression given as its JSON AST.
	 */
	public static String print(Object node) {
		StringBuilder out = new StringBuilder();
		print(out, node);
		return out.toString();
	}

	/**
	 * Parses a log message: verbatim text with expressions in braces (<code>Light off after
	 * {$timeout}</code>), into its parts, texts and AST nodes.
	 */
	public static List<Object> parseMessage(String text) throws SyntaxError {
		List<Object> result = new ArrayList<>();
		StringBuilder verbatim = new StringBuilder();
		int pos = 0;
		while (pos < text.length()) {
			char c = text.charAt(pos);
			if (c == '{' && pos + 1 < text.length() && text.charAt(pos + 1) == '{') {
				verbatim.append('{');
				pos += 2;
			} else if (c == '}' && pos + 1 < text.length() && text.charAt(pos + 1) == '}') {
				verbatim.append('}');
				pos += 2;
			} else if (c == '}') {
				throw new SyntaxError("a single '}' in the text (write '}}' for a brace)", pos);
			} else if (c == '{') {
				if (verbatim.length() > 0) {
					result.add(verbatim.toString());
					verbatim.setLength(0);
				}
				Parser parser = new Parser(text, pos + 1);
				result.add(parser.expression());
				if (parser._token.kind != TokenKind.CLOSE_BRACE) {
					throw parser.error("'}' expected after the expression, not " + parser._token.describe());
				}
				pos = parser._token.end;
			} else {
				verbatim.append(c);
				pos++;
			}
		}
		if (verbatim.length() > 0) {
			result.add(verbatim.toString());
		}
		return result;
	}

	/**
	 * The text of a log message given as its parts (see {@link #parseMessage(String)}).
	 */
	public static String printMessage(List<?> parts) {
		StringBuilder out = new StringBuilder();
		for (Object part : parts) {
			if (part instanceof String text) {
				out.append(text.replace("{", "{{").replace("}", "}}"));
			} else {
				out.append('{');
				print(out, part);
				out.append('}');
			}
		}
		return out.toString();
	}

	/**
	 * Parses a literal of the language as a JSON literal value of the given type
	 * (<code>spec/rules.md</code> 3.1): a number for an integer, real or enumeration (an integer
	 * literal is also a real or an enumeration element), milliseconds for a duration, the object forms
	 * of the times, dates and colors, the hex octets of a raw value.
	 */
	public static Object parseValue(String text, Type type) throws SyntaxError {
		Map<String, Object> node = parse(text);
		if (!"literal".equals(node.get("kind"))) {
			throw new SyntaxError("a literal value expected", 0);
		}
		String literalType = (String) node.get("type");
		Object value = node.get("value");
		if (literalType.equals(type.externalName())) {
			return value;
		}
		if (literalType.equals("integer")) {
			if (type == Type.REAL) {
				return Double.valueOf(((Long) value).doubleValue());
			}
			if (type == Type.ENUMERATION) {
				return value;
			}
		}
		throw new SyntaxError("a " + type.externalName() + " literal expected, not a " + literalType + " literal", 0);
	}

	/**
	 * The text of a JSON literal value of the given type (see {@link #parseValue(String, Type)}).
	 */
	public static String printValue(Object json, Type type) {
		if (type == Type.ENUMERATION && json instanceof Number number) {
			return Long.toString(number.longValue());
		}
		return print(literal(type.externalName(), json));
	}

	/**
	 * A literal node of the AST.
	 */
	public static Map<String, Object> literal(String type, Object value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("kind", "literal");
		result.put("type", type);
		result.put("value", value);
		return result;
	}

	// ---- Printer -----------------------------------------------------------------------------

	private static final int LEVEL_OR = 1;

	private static final int LEVEL_AND = 2;

	private static final int LEVEL_NOT = 3;

	private static final int LEVEL_COMPARISON = 4;

	private static final int LEVEL_ADDITIVE = 5;

	private static final int LEVEL_MULTIPLICATIVE = 6;

	private static final int LEVEL_NEGATION = 7;

	private static final int LEVEL_PRIMARY = 8;

	private static int level(Object node) {
		if (!(node instanceof Map<?, ?> map)) {
			return LEVEL_PRIMARY;
		}
		Object kind = map.get("kind");
		if ("binary".equals(kind)) {
			return binaryLevel(String.valueOf(map.get("op")));
		}
		if ("unary".equals(kind)) {
			return "not".equals(map.get("op")) ? LEVEL_NOT : LEVEL_NEGATION;
		}
		return LEVEL_PRIMARY;
	}

	private static int binaryLevel(String op) {
		switch (op) {
			case "or":
				return LEVEL_OR;
			case "and":
				return LEVEL_AND;
			case "+":
			case "-":
				return LEVEL_ADDITIVE;
			case "*":
			case "/":
			case "%":
				return LEVEL_MULTIPLICATIVE;
			default:
				return LEVEL_COMPARISON;
		}
	}

	private static void print(StringBuilder out, Object node) {
		if (!(node instanceof Map<?, ?> map)) {
			out.append(String.valueOf(node));
			return;
		}
		String kind = String.valueOf(map.get("kind"));
		switch (kind) {
			case "literal":
				printLiteral(out, String.valueOf(map.get("type")), map.get("value"));
				return;
			case "datapoint":
				out.append('@');
				String name = String.valueOf(map.get("name"));
				if (isBareName(name)) {
					out.append(name);
				} else {
					quote(out, name);
				}
				return;
			case "parameter":
				out.append('$').append(map.get("id"));
				return;
			case "timer":
				out.append(map.get("id"));
				return;
			case "unary": {
				Object operand = map.get("operand");
				if ("not".equals(map.get("op"))) {
					out.append("not ");
					operand(out, operand, level(operand) < LEVEL_NOT);
				} else {
					out.append('-');
					String text = print(operand);
					boolean literal = operand instanceof Map<?, ?> o && "literal".equals(o.get("kind"))
						&& isSigned(String.valueOf(o.get("type")));
					operand(out, operand, level(operand) < LEVEL_PRIMARY || text.startsWith("-") || literal);
				}
				return;
			}
			case "binary": {
				String op = String.valueOf(map.get("op"));
				int level = binaryLevel(op);
				Object left = map.get("left");
				Object right = map.get("right");
				int leftLevel = level(left);
				operand(out, left, leftLevel < level || (level == LEVEL_COMPARISON && leftLevel == LEVEL_COMPARISON));
				out.append(' ').append(op).append(' ');
				operand(out, right, level(right) <= level);
				return;
			}
			case "call": {
				out.append(map.get("function")).append('(');
				if (map.get("args") instanceof List<?> args) {
					for (int i = 0; i < args.size(); i++) {
						if (i > 0) {
							out.append(", ");
						}
						print(out, args.get(i));
					}
				}
				out.append(')');
				return;
			}
			default:
				out.append('?').append(kind);
		}
	}

	private static boolean isSigned(String type) {
		return type.equals("integer") || type.equals("real") || type.equals("duration");
	}

	private static void operand(StringBuilder out, Object node, boolean parenthesize) {
		if (parenthesize) {
			out.append('(');
		}
		print(out, node);
		if (parenthesize) {
			out.append(')');
		}
	}

	/**
	 * Whether a datapoint name can be written without quotes: an identifier (not a keyword) and
	 * further parts of letters, digits and underscores, each after a dot.
	 */
	public static boolean isBareName(String name) {
		String[] parts = name.split("\\.", -1);
		if (!isIdentifier(parts[0])) {
			return false;
		}
		for (int i = 1; i < parts.length; i++) {
			if (parts[i].isEmpty() || !parts[i].matches("[A-Za-z0-9_]+")) {
				return false;
			}
		}
		return true;
	}

	private static boolean isIdentifier(String text) {
		return text.matches("[A-Za-z_][A-Za-z0-9_]*") && !isKeyword(text);
	}

	private static boolean isKeyword(String text) {
		switch (text) {
			case "and":
			case "or":
			case "not":
			case "true":
			case "false":
				return true;
			default:
				return false;
		}
	}

	private static void quote(StringBuilder out, String text) {
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\t' -> out.append("\\t");
				default -> out.append(c);
			}
		}
		out.append('"');
	}

	private static void printLiteral(StringBuilder out, String type, Object value) {
		switch (type) {
			case "boolean":
				out.append(Boolean.TRUE.equals(value) ? "true" : "false");
				return;
			case "integer":
			case "enumeration":
				out.append(value instanceof Number n ? Long.toString(n.longValue()) : String.valueOf(value));
				return;
			case "real":
				out.append(value instanceof Number n ? realText(n.doubleValue()) : String.valueOf(value));
				return;
			case "duration":
				out.append(value instanceof Number n ? Values.durationText(n.longValue()) : String.valueOf(value));
				return;
			case "string":
				quote(out, String.valueOf(value));
				return;
			case "timeOfDay":
				if (value instanceof Map<?, ?> t) {
					time(out, t);
					return;
				}
				break;
			case "date":
				if (value instanceof Map<?, ?> d) {
					date(out, d);
					return;
				}
				break;
			case "dateTime":
				if (value instanceof Map<?, ?> d) {
					date(out, d);
					out.append('T');
					time(out, d);
					return;
				}
				break;
			case "color":
				if (value instanceof Map<?, ?> c) {
					out.append('#');
					for (String channel : new String[] { "red", "green", "blue", "white" }) {
						if (c.get(channel) instanceof Number level) {
							out.append(String.format(Locale.ROOT, "%02x", Long.valueOf(level.longValue())));
						}
					}
					return;
				}
				break;
			case "raw":
				out.append("x\"").append(String.valueOf(value).replace(" ", "").toUpperCase(Locale.ROOT)).append('"');
				return;
			case "controlled":
				if (value instanceof Map<?, ?> c) {
					out.append("controlled(").append(Boolean.TRUE.equals(c.get("control"))).append(", ")
						.append(c.get("value") instanceof Number n ? n.longValue() : 0).append(')');
					return;
				}
				break;
			default:
				break;
		}
		out.append(String.valueOf(value));
	}

	private static long number(Map<?, ?> map, String member) {
		return map.get(member) instanceof Number n ? n.longValue() : 0;
	}

	private static void time(StringBuilder out, Map<?, ?> t) {
		out.append(String.format(Locale.ROOT, "%02d:%02d", Long.valueOf(number(t, "hour")), Long.valueOf(number(t, "minute"))));
		long second = number(t, "second");
		if (second != 0) {
			out.append(String.format(Locale.ROOT, ":%02d", Long.valueOf(second)));
		}
	}

	private static void date(StringBuilder out, Map<?, ?> d) {
		out.append(String.format(Locale.ROOT, "%04d-%02d-%02d", Long.valueOf(number(d, "year")),
			Long.valueOf(number(d, "month")), Long.valueOf(number(d, "day"))));
	}

	/**
	 * The canonical text of a real (<code>spec/rules.md</code> 4.2): the shortest decimal that reads
	 * back as the same double, with at least one digit after the point, in exponent form when the
	 * magnitude is at least 1e15 or below 1e-4.
	 */
	public static String realText(double value) {
		if (!Double.isFinite(value)) {
			return Double.toString(value);
		}
		if (value == 0) {
			return (1 / value < 0) ? "-0.0" : "0.0";
		}
		double magnitude = Math.abs(value);
		BigDecimal exact = new BigDecimal(magnitude);
		BigDecimal shortest = exact;
		for (int digits = 1; digits <= 17; digits++) {
			BigDecimal candidate = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
			if (candidate.doubleValue() == magnitude) {
				shortest = candidate;
				break;
			}
		}
		shortest = shortest.stripTrailingZeros();
		String sign = value < 0 ? "-" : "";
		if (magnitude >= 1e15 || magnitude < 1e-4) {
			String digits = shortest.unscaledValue().toString();
			int exponent = digits.length() - 1 - shortest.scale();
			String mantissa = digits.charAt(0) + "." + (digits.length() > 1 ? digits.substring(1) : "0");
			return sign + mantissa + "e" + exponent;
		}
		String plain = shortest.toPlainString();
		if (plain.indexOf('.') < 0) {
			plain += ".0";
		}
		return sign + plain;
	}

	// ---- Lexer -------------------------------------------------------------------------------

	private enum TokenKind {
		END, CLOSE_BRACE, OPEN, CLOSE, COMMA, OPERATOR, KEYWORD, IDENTIFIER, DATAPOINT, PARAMETER, LITERAL;
	}

	private static final class Token {

		final TokenKind kind;

		final int start;

		final int end;

		/** The operator, keyword, identifier, name or the literal's type. */
		final String text;

		/** The JSON value of a literal. */
		final Object value;

		Token(TokenKind kind, int start, int end, String text, Object value) {
			this.kind = kind;
			this.start = start;
			this.end = end;
			this.text = text;
			this.value = value;
		}

		String describe() {
			switch (kind) {
				case END:
					return "end of the text";
				case CLOSE_BRACE:
					return "'}'";
				case LITERAL:
					return "a " + text + " literal";
				case IDENTIFIER:
					return "identifier '" + text + "'";
				case DATAPOINT:
					return "datapoint reference";
				case PARAMETER:
					return "parameter reference";
				default:
					return "'" + text + "'";
			}
		}
	}

	private static final class Lexer {

		private final String _text;

		private int _pos;

		Lexer(String text, int pos) {
			_text = text;
			_pos = pos;
		}

		private SyntaxError error(String message, int pos) {
			return new SyntaxError(message, pos);
		}

		private char at(int pos) {
			return pos < _text.length() ? _text.charAt(pos) : '\0';
		}

		private static boolean isDigit(char c) {
			return c >= '0' && c <= '9';
		}

		private static boolean isLetter(char c) {
			return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
		}

		private static boolean isWordChar(char c) {
			return isLetter(c) || isDigit(c) || c == '_';
		}

		private static boolean isHex(char c) {
			return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
		}

		Token next() throws SyntaxError {
			while (_pos < _text.length()) {
				char c = _text.charAt(_pos);
				if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
					break;
				}
				_pos++;
			}
			int start = _pos;
			if (_pos >= _text.length()) {
				return new Token(TokenKind.END, start, start, "", null);
			}
			char c = _text.charAt(_pos);
			switch (c) {
				case '}':
					_pos++;
					return new Token(TokenKind.CLOSE_BRACE, start, _pos, "}", null);
				case '(':
					_pos++;
					return new Token(TokenKind.OPEN, start, _pos, "(", null);
				case ')':
					_pos++;
					return new Token(TokenKind.CLOSE, start, _pos, ")", null);
				case ',':
					_pos++;
					return new Token(TokenKind.COMMA, start, _pos, ",", null);
				case '+':
				case '-':
				case '*':
				case '/':
				case '%':
					_pos++;
					return new Token(TokenKind.OPERATOR, start, _pos, String.valueOf(c), null);
				case '=':
				case '!':
					if (at(_pos + 1) != '=') {
						throw error(c == '=' ? "'=' is no operator, compare with '=='" : "'!' is no operator, write 'not' or '!='",
							start);
					}
					_pos += 2;
					return new Token(TokenKind.OPERATOR, start, _pos, c + "=", null);
				case '<':
				case '>':
					_pos++;
					if (at(_pos) == '=') {
						_pos++;
						return new Token(TokenKind.OPERATOR, start, _pos, c + "=", null);
					}
					return new Token(TokenKind.OPERATOR, start, _pos, String.valueOf(c), null);
				case '"':
					return new Token(TokenKind.LITERAL, start, _pos = stringEnd(_pos), "string", string(start));
				case '#':
					return color(start);
				case '@':
					return datapoint(start);
				case '$': {
					_pos++;
					String id = word();
					if (id.isEmpty() || !isLetter(id.charAt(0)) && id.charAt(0) != '_') {
						throw error("a parameter id expected after '$'", start);
					}
					return new Token(TokenKind.PARAMETER, start, _pos, id, null);
				}
				default:
					break;
			}
			if (isDigit(c)) {
				return number(start);
			}
			if (c == 'x' && at(_pos + 1) == '"') {
				return raw(start);
			}
			if (isLetter(c) || c == '_') {
				String word = word();
				if (isKeyword(word)) {
					if (word.equals("true") || word.equals("false")) {
						return new Token(TokenKind.LITERAL, start, _pos, "boolean", Boolean.valueOf(word.equals("true")));
					}
					return new Token(TokenKind.KEYWORD, start, _pos, word, null);
				}
				return new Token(TokenKind.IDENTIFIER, start, _pos, word, null);
			}
			throw error("unexpected character '" + c + "'", start);
		}

		private String word() {
			int start = _pos;
			while (_pos < _text.length() && isWordChar(_text.charAt(_pos))) {
				_pos++;
			}
			return _text.substring(start, _pos);
		}

		/** The end of the string literal starting at the quote. */
		private int stringEnd(int start) throws SyntaxError {
			int pos = start + 1;
			while (true) {
				char c = at(pos);
				if (pos >= _text.length()) {
					throw error("the string is not closed", start);
				}
				if (c == '"') {
					return pos + 1;
				}
				if (c == '\\') {
					pos += 2;
				} else {
					pos++;
				}
			}
		}

		/** The value of the string literal starting at the quote. */
		private String string(int start) throws SyntaxError {
			StringBuilder out = new StringBuilder();
			int pos = start + 1;
			while (true) {
				char c = _text.charAt(pos);
				if (c == '"') {
					return out.toString();
				}
				if (c < 0x20) {
					throw error("a control character in a string (write \\n or \\t)", pos);
				}
				if (c == '\\') {
					char escaped = at(pos + 1);
					switch (escaped) {
						case '"' -> out.append('"');
						case '\\' -> out.append('\\');
						case 'n' -> out.append('\n');
						case 't' -> out.append('\t');
						default -> throw error("unknown escape '\\" + escaped + "' (only \\\" \\\\ \\n \\t)", pos);
					}
					pos += 2;
				} else {
					out.append(c);
					pos++;
				}
			}
		}

		private Token color(int start) throws SyntaxError {
			int pos = start + 1;
			while (isHex(at(pos))) {
				pos++;
			}
			int digits = pos - start - 1;
			if ((digits != 6 && digits != 8) || isWordChar(at(pos))) {
				throw error("a color is # with 6 or 8 hex digits (#rrggbb, #rrggbbww)", start);
			}
			Map<String, Object> value = new LinkedHashMap<>();
			String[] channels = { "red", "green", "blue", "white" };
			for (int i = 0; i < digits / 2; i++) {
				value.put(channels[i], Long.valueOf(Long.parseLong(_text.substring(start + 1 + 2 * i, start + 3 + 2 * i), 16)));
			}
			_pos = pos;
			return new Token(TokenKind.LITERAL, start, pos, "color", value);
		}

		private Token raw(int start) throws SyntaxError {
			int pos = start + 2;
			StringBuilder value = new StringBuilder();
			while (at(pos) != '"') {
				if (!isHex(at(pos)) || !isHex(at(pos + 1))) {
					throw error("a raw literal is x\"...\" with pairs of hex digits", pos);
				}
				if (value.length() > 0) {
					value.append(' ');
				}
				value.append(_text.substring(pos, pos + 2).toUpperCase(Locale.ROOT));
				pos += 2;
			}
			_pos = pos + 1;
			return new Token(TokenKind.LITERAL, start, _pos, "raw", value.toString());
		}

		private Token datapoint(int start) throws SyntaxError {
			_pos++;
			if (at(_pos) == '"') {
				int end = stringEnd(_pos);
				String name = string(_pos);
				_pos = end;
				return new Token(TokenKind.DATAPOINT, start, _pos, name, null);
			}
			char first = at(_pos);
			if (!isLetter(first) && first != '_') {
				throw error("a datapoint name or a quoted name expected after '@'", start);
			}
			int nameStart = _pos;
			word();
			while (at(_pos) == '.' && isWordChar(at(_pos + 1))) {
				_pos++;
				word();
			}
			return new Token(TokenKind.DATAPOINT, start, _pos, _text.substring(nameStart, _pos), null);
		}

		private int digits(int pos) {
			while (isDigit(at(pos))) {
				pos++;
			}
			return pos;
		}

		private int value(int from, int to) {
			return Integer.parseInt(_text.substring(from, to));
		}

		private Token number(int start) throws SyntaxError {
			int end = digits(start);
			int count = end - start;
			// date time literal, date literal
			if (count == 4 && at(end) == '-' && isDigit(at(end + 1)) && isDigit(at(end + 2)) && at(end + 3) == '-'
				&& isDigit(at(end + 4)) && isDigit(at(end + 5)) && !isDigit(at(end + 6))) {
				Map<String, Object> value = new LinkedHashMap<>();
				value.put("year", Long.valueOf(value(start, end)));
				value.put("month", Long.valueOf(value(end + 1, end + 3)));
				value.put("day", Long.valueOf(value(end + 4, end + 6)));
				int pos = end + 6;
				if (at(pos) == 'T' && isDigit(at(pos + 1)) && isDigit(at(pos + 2)) && at(pos + 3) == ':'
					&& isDigit(at(pos + 4)) && isDigit(at(pos + 5))) {
					value.put("hour", Long.valueOf(value(pos + 1, pos + 3)));
					value.put("minute", Long.valueOf(value(pos + 4, pos + 6)));
					pos += 6;
					long second = 0;
					if (at(pos) == ':' && isDigit(at(pos + 1)) && isDigit(at(pos + 2))) {
						second = value(pos + 1, pos + 3);
						pos += 3;
					}
					value.put("second", Long.valueOf(second));
					return literalEnd(start, pos, "dateTime", value);
				}
				return literalEnd(start, pos, "date", value);
			}
			// time literal
			if (count <= 2 && at(end) == ':' && isDigit(at(end + 1)) && isDigit(at(end + 2))) {
				Map<String, Object> value = new LinkedHashMap<>();
				value.put("hour", Long.valueOf(value(start, end)));
				value.put("minute", Long.valueOf(value(end + 1, end + 3)));
				int pos = end + 3;
				long second = 0;
				if (at(pos) == ':' && isDigit(at(pos + 1)) && isDigit(at(pos + 2))) {
					second = value(pos + 1, pos + 3);
					pos += 3;
				}
				value.put("second", Long.valueOf(second));
				return literalEnd(start, pos, "timeOfDay", value);
			}
			// duration literal
			if (isLetter(at(end))) {
				return duration(start);
			}
			// real literal
			if (at(end) == '.' && isDigit(at(end + 1))) {
				int pos = digits(end + 1);
				if ((at(pos) == 'e' || at(pos) == 'E')) {
					int exponent = pos + 1;
					if (at(exponent) == '+' || at(exponent) == '-') {
						exponent++;
					}
					if (isDigit(at(exponent))) {
						pos = digits(exponent);
					}
				}
				double value = Double.parseDouble(_text.substring(start, pos));
				if (!Double.isFinite(value)) {
					throw error("the real is not finite", start);
				}
				return literalEnd(start, pos, "real", Double.valueOf(value));
			}
			// integer literal
			String digits = _text.substring(start, end);
			if (digits.length() > 16 || Long.parseLong(digits) > MAX_SAFE_INTEGER) {
				throw error("an integer beyond 9007199254740991", start);
			}
			return literalEnd(start, end, "integer", Long.valueOf(Long.parseLong(digits)));
		}

		private Token literalEnd(int start, int end, String type, Object value) throws SyntaxError {
			if (isWordChar(at(end)) || at(end) == '.' || at(end) == ':') {
				throw error("unexpected '" + at(end) + "' after the " + type + " literal", end);
			}
			_pos = end;
			return new Token(TokenKind.LITERAL, start, end, type, value);
		}

		private static final String[] UNITS = { "d", "h", "min", "s", "ms" };

		private static final long[] UNIT_MS = { 86400000L, 3600000L, 60000L, 1000L, 1L };

		private Token duration(int start) throws SyntaxError {
			int pos = start;
			int lastUnit = -1;
			long total = 0;
			while (isDigit(at(pos))) {
				int numberEnd = digits(pos);
				int unitEnd = numberEnd;
				while (isLetter(at(unitEnd))) {
					unitEnd++;
				}
				String unitText = _text.substring(numberEnd, unitEnd);
				int unit = -1;
				for (int i = 0; i < UNITS.length; i++) {
					if (UNITS[i].equals(unitText)) {
						unit = i;
					}
				}
				if (unit < 0) {
					throw error(unitText.isEmpty() ? "a duration unit (d, h, min, s, ms) expected"
						: "'" + unitText + "' is no duration unit (d, h, min, s, ms)", numberEnd);
				}
				if (unit <= lastUnit) {
					throw error("the units of a duration must decrease, each at most once (1h30min)", numberEnd);
				}
				lastUnit = unit;
				String number = _text.substring(pos, numberEnd);
				if (number.length() > 16) {
					throw error("the duration is too long", pos);
				}
				total += Long.parseLong(number) * UNIT_MS[unit];
				if (total > MAX_SAFE_INTEGER || total < 0) {
					throw error("the duration is too long", start);
				}
				pos = unitEnd;
			}
			return literalEnd(start, pos, "duration", Long.valueOf(total));
		}
	}

	// ---- Parser ------------------------------------------------------------------------------

	private static final class Parser {

		private final Lexer _lexer;

		Token _token;

		Parser(String text, int start) throws SyntaxError {
			_lexer = new Lexer(text, start);
			_token = _lexer.next();
		}

		SyntaxError error(String message) {
			return new SyntaxError(message, _token.start);
		}

		private void advance() throws SyntaxError {
			_token = _lexer.next();
		}

		private boolean isKeyword(String keyword) {
			return _token.kind == TokenKind.KEYWORD && _token.text.equals(keyword);
		}

		private boolean isOperator(String... ops) {
			if (_token.kind != TokenKind.OPERATOR) {
				return false;
			}
			for (String op : ops) {
				if (_token.text.equals(op)) {
					return true;
				}
			}
			return false;
		}

		Map<String, Object> expression() throws SyntaxError {
			Map<String, Object> left = and();
			while (isKeyword("or")) {
				advance();
				left = binary("or", left, and());
			}
			return left;
		}

		private Map<String, Object> and() throws SyntaxError {
			Map<String, Object> left = not();
			while (isKeyword("and")) {
				advance();
				left = binary("and", left, not());
			}
			return left;
		}

		private Map<String, Object> not() throws SyntaxError {
			if (isKeyword("not")) {
				advance();
				return unary("not", not());
			}
			return comparison();
		}

		private Map<String, Object> comparison() throws SyntaxError {
			Map<String, Object> left = additive();
			if (isOperator("==", "!=", "<", "<=", ">", ">=")) {
				String op = _token.text;
				advance();
				Map<String, Object> result = binary(op, left, additive());
				if (isOperator("==", "!=", "<", "<=", ">", ">=")) {
					throw error("comparisons do not chain; use parentheses or 'and'");
				}
				return result;
			}
			return left;
		}

		private Map<String, Object> additive() throws SyntaxError {
			Map<String, Object> left = multiplicative();
			while (isOperator("+", "-")) {
				String op = _token.text;
				advance();
				left = binary(op, left, multiplicative());
			}
			return left;
		}

		private Map<String, Object> multiplicative() throws SyntaxError {
			Map<String, Object> left = unaryNode();
			while (isOperator("*", "/", "%")) {
				String op = _token.text;
				advance();
				left = binary(op, left, unaryNode());
			}
			return left;
		}

		private Map<String, Object> unaryNode() throws SyntaxError {
			return negation()._node;
		}

		/** A unary expression and whether it is a literal written directly, which a minus folds. */
		private record Operand(Map<String, Object> _node, boolean _foldable) {
			// A record.
		}

		private Operand negation() throws SyntaxError {
			if (isOperator("-")) {
				advance();
				Operand operand = negation();
				Map<String, Object> node = operand._node;
				if (operand._foldable) {
					Object value = node.get("value");
					Object negated;
					if (value instanceof Double d) {
						negated = Double.valueOf(-d.doubleValue());
					} else {
						negated = Long.valueOf(-((Long) value).longValue());
					}
					return new Operand(literal((String) node.get("type"), negated), false);
				}
				return new Operand(unary("-", node), false);
			}
			if (_token.kind == TokenKind.LITERAL) {
				boolean foldable = isSigned(_token.text);
				Map<String, Object> node = literal(_token.text, _token.value);
				advance();
				return new Operand(node, foldable);
			}
			return new Operand(primary(), false);
		}

		private Map<String, Object> primary() throws SyntaxError {
			switch (_token.kind) {
				case DATAPOINT: {
					Map<String, Object> node = new LinkedHashMap<>();
					node.put("kind", "datapoint");
					node.put("name", _token.text);
					advance();
					return node;
				}
				case PARAMETER: {
					Map<String, Object> node = new LinkedHashMap<>();
					node.put("kind", "parameter");
					node.put("id", _token.text);
					advance();
					return node;
				}
				case OPEN: {
					advance();
					Map<String, Object> node = expression();
					if (_token.kind != TokenKind.CLOSE) {
						throw error("')' expected, not " + _token.describe());
					}
					advance();
					return node;
				}
				case IDENTIFIER:
					return call();
				default:
					throw error("an operand expected, not " + _token.describe());
			}
		}

		private Map<String, Object> call() throws SyntaxError {
			String name = _token.text;
			advance();
			if (_token.kind != TokenKind.OPEN) {
				throw error("'(' expected after '" + name + "' (a name stands only for a function)");
			}
			advance();
			List<Object> args = new ArrayList<>();
			if (name.equals("running") || name.equals("remaining")) {
				if (_token.kind != TokenKind.IDENTIFIER) {
					throw error("a timer id expected in " + name + "()");
				}
				Map<String, Object> timer = new LinkedHashMap<>();
				timer.put("kind", "timer");
				timer.put("id", _token.text);
				args.add(timer);
				advance();
			} else if (_token.kind != TokenKind.CLOSE) {
				args.add(expression());
				while (_token.kind == TokenKind.COMMA) {
					advance();
					args.add(expression());
				}
			}
			if (_token.kind != TokenKind.CLOSE) {
				throw error("')' or ',' expected, not " + _token.describe());
			}
			advance();
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("kind", "call");
			node.put("function", name);
			node.put("args", args);
			return node;
		}

		private static Map<String, Object> unary(String op, Map<String, Object> operand) {
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("kind", "unary");
			node.put("op", op);
			node.put("operand", operand);
			return node;
		}

		private static Map<String, Object> binary(String op, Map<String, Object> left, Map<String, Object> right) {
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("kind", "binary");
			node.put("op", op);
			node.put("left", left);
			node.put("right", right);
			return node;
		}
	}

}
