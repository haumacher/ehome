/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The scripts of parameter calculations in VBScript (ETS: <code>Language="VBScript"</code>),
 * evaluated by an interpreter of a subset of the language, without an engine and without access to
 * anything but the variables of the calculation.
 *
 * <p>
 * The subset (keywords and names are case-insensitive):
 * </p>
 * <ul>
 * <li>Statements separated by line breaks or <code>:</code>; a line continued by <code> _</code>;
 * comments with <code>'</code> or <code>Rem</code>; <code>Dim a, b</code> (a declared variable
 * reads as 0 until it is assigned), <code>Const a = 1</code>.</li>
 * <li>The assignment <code>name = expression</code>.</li>
 * <li><code>If c Then … [ElseIf c Then …] [Else …] End If</code> over several lines, and the
 * single-line <code>If c Then statements [Else statements]</code>.</li>
 * <li>Procedures, in the code or in the library (ETS: <code>Static/Script</code>, loaded first):
 * <code>Function f(a, b) … f = … End Function</code> and <code>Sub s(a) … End Sub</code>, with
 * <code>Exit Function</code>/<code>Exit Sub</code>, called as <code>f(x)</code> in an expression and as
 * <code>s(x)</code> or <code>Call s(x)</code> as a statement; their arguments are passed by value; a name
 * a procedure assigns is the variable of the script if the script has one, else a variable of the
 * call.</li>
 * <li>Expressions: numbers (also <code>&amp;H1F</code>), texts in quotes, <code>True</code>,
 * <code>False</code>, names, parentheses; <code>^</code>, unary <code>-</code> and <code>+</code>,
 * <code>*</code> and <code>/</code>, the integer division <code>\</code> (both operands rounded to
 * whole numbers, half to even, then the quotient truncated), <code>Mod</code> (operands rounded the
 * same way, the remainder with the sign of the dividend), <code>+</code> and <code>-</code>,
 * <code>&amp;</code> (texts joined), the comparisons <code>= &lt;&gt; &lt; &gt; &lt;= &gt;=</code>,
 * <code>Not</code>, <code>And</code>, <code>Or</code>, <code>Xor</code> (logical on truth values,
 * bitwise on numbers), in the precedence of VBScript.</li>
 * <li>The functions <code>Int</code>, <code>Fix</code>, <code>Abs</code>, <code>Sgn</code>,
 * <code>Sqr</code>, <code>Round</code> (half to even, optionally to a number of decimals),
 * <code>CInt</code>, <code>CLng</code> (rounded half to even), <code>CDbl</code>.</li>
 * <li>Reading a member of an object the caller passes (<code>input.RangeMin</code>,
 * <code>context.Limit</code>; names case-insensitive): the objects of a validation. A script cannot
 * create objects or assign their members.</li>
 * </ul>
 *
 * <p>
 * Anything else (loops, <code>Select Case</code>, creating objects or assigning their members, other
 * functions) is a construct not supported: {@link #check(String, String)} names it. A name read before
 * anything gave it a value is an error naming it; so is a division by zero. True is -1 as in VBScript. A
 * script of the subset has no loops; calls nest at most {@value #MAX_DEPTH} deep, so it always ends. A
 * function of the library as the transformation (ETS: <code>LRTransformationFunc</code>) would assign
 * the members of its object <code>output</code>, which the subset does not support: it is not
 * supported.
 * </p>
 *
 * <p>
 * A validation (haumacher/ehome #124) is a <code>Function</code> of the library with four parameters,
 * called as <code>f(input, changed, prevValue, context)</code>: <code>input</code> an object of the
 * parameters of the validation by their names, <code>changed</code> the name of the changed one (a
 * text), <code>prevValue</code> its value before, <code>context</code> the parameters of the
 * validation, an object for a JSON object, else the text. The function answers by assigning its name:
 * <code>True</code> (or a number other than 0) accepts, a text refuses with it, <code>False</code> (or
 * no assignment) refuses without a text.
 * </p>
 */
public final class VbScriptLanguage implements ScriptLanguage {

	/** The single instance. */
	public static final VbScriptLanguage INSTANCE = new VbScriptLanguage();

	/** The deepest nesting of calls. */
	public static final int MAX_DEPTH = 64;

	private static final Set<String> FUNCTIONS =
		Set.of("int", "fix", "abs", "sgn", "sqr", "round", "cint", "clng", "cdbl");

	private static final Set<String> RESERVED = Set.of("if", "then", "else", "elseif", "end", "and", "or", "not",
		"xor", "mod", "dim", "true", "false", "rem", "select", "case", "for", "next", "to", "step", "do", "loop",
		"while", "wend", "until", "function", "sub", "call", "set", "exit", "on", "error", "option", "explicit",
		"redim", "const", "with", "class", "new", "nothing", "is", "eqv", "imp", "each", "in", "goto", "resume",
		"public", "private", "let", "get", "property", "empty", "null", "byval", "byref");

	private VbScriptLanguage() {
		// Singleton.
	}

	@Override
	public String check(String library, String code) {
		try {
			Program lib = library(library);
			new Parser(code, lib.procedures().keySet()).parse();
			return null;
		} catch (CalculationException ex) {
			return ex.getMessage();
		}
	}

	@Override
	public String checkFunction(String library, String function) {
		return "a function of the script as the transformation (" + function
			+ ") assigns the members of an object, which E-Home's VBScript does not support";
	}

	@Override
	public Map<String, Object> call(String library, String function, Map<String, Object> inputs,
			Map<String, Object> outputs, String parameters) throws CalculationException {
		throw new CalculationException(checkFunction(library, function));
	}

	@Override
	public String checkValidation(String library, String function) {
		Program lib;
		try {
			lib = new Parser(library, Set.of()).parse();
		} catch (CalculationException ex) {
			return ex.getMessage();
		}
		Procedure procedure = lib.procedures().get(key(function.trim()));
		if (procedure == null || !procedure.function()) {
			return "the script defines no Function " + function.trim();
		}
		if (procedure.parameters().size() != 4) {
			return "the Function " + function.trim() + " takes " + procedure.parameters().size()
				+ " arguments instead of the 4 of a validation (input, changed, prevValue, context)";
		}
		return null;
	}

	@Override
	public String validate(String library, String function, Map<String, Object> input, String changed,
			Object previous, String parameters) throws CalculationException {
		String problem = checkValidation(library, function);
		if (problem != null) {
			throw new CalculationException(problem);
		}
		Program lib = library(library);
		Env env = new Env(new HashMap<>(), null, lib.procedures(), 0);
		runAll(lib.statements(), env);
		Object context = parameters == null ? "" : JavaScriptLanguage.json(parameters);
		List<Expression> arguments = new ArrayList<>();
		arguments.add(new Literal(new LinkedHashMap<>(input)));
		arguments.add(new Literal(changed));
		arguments.add(new Literal(previous == null ? "" : previous));
		arguments.add(new Literal(context == null ? "" : context));
		Object result = new Invocation(function.trim(), arguments, 0).eval(env);
		if (result instanceof String text) {
			return text;
		}
		if (result instanceof Map<?, ?> || result instanceof List<?>) {
			throw new CalculationException("the Function " + function.trim() + " answers an object");
		}
		return truth(result) ? null : "";
	}

	@Override
	public Map<String, Object> run(String library, String code, Map<String, Object> variables)
			throws CalculationException {
		Program lib = library(library);
		Program program = new Parser(code, lib.procedures().keySet()).parse();
		Map<String, Procedure> procedures = new HashMap<>(lib.procedures());
		procedures.putAll(program.procedures());
		Env env = new Env(new HashMap<>(), null, procedures, 0);
		for (Map.Entry<String, Object> entry : variables.entrySet()) {
			env.globals().put(key(entry.getKey()), entry.getValue());
		}
		runAll(lib.statements(), env);
		runAll(program.statements(), env);
		Map<String, Object> result = new LinkedHashMap<>();
		for (String name : variables.keySet()) {
			Object value = env.globals().get(key(name));
			result.put(name, value instanceof Boolean flag ? Double.valueOf(flag.booleanValue() ? -1 : 0) : value);
		}
		return result;
	}

	/**
	 * The procedures and statements of the library; none if it is no VBScript of the subset (a library
	 * in another language, which the calculations of another language use).
	 */
	private static Program library(String library) {
		if (library == null || library.isBlank()) {
			return new Program(List.of(), Map.of());
		}
		try {
			return new Parser(library, Set.of()).parse();
		} catch (CalculationException ex) {
			return new Program(List.of(), Map.of());
		}
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	// Tokens.

	private enum Kind {
		NUMBER, TEXT, NAME, OPERATOR, COLON, NEWLINE, END
	}

	private record Token(Kind kind, String text, Object value, int line) {

		boolean is(String word) {
			return (kind == Kind.NAME || kind == Kind.OPERATOR) && text.equalsIgnoreCase(word);
		}

		boolean endsStatement() {
			return kind == Kind.COLON || kind == Kind.NEWLINE || kind == Kind.END;
		}
	}

	private static List<Token> tokens(String code) throws CalculationException {
		List<Token> result = new ArrayList<>();
		String text = code == null ? "" : code;
		int line = 1;
		int pos = 0;
		int length = text.length();
		while (pos < length) {
			char c = text.charAt(pos);
			if (c == ' ' || c == '\t' || c == ' ') {
				pos++;
			} else if (c == '\r' || c == '\n') {
				if (c == '\r' && pos + 1 < length && text.charAt(pos + 1) == '\n') {
					pos++;
				}
				pos++;
				result.add(new Token(Kind.NEWLINE, "\n", null, line));
				line++;
			} else if (c == ':') {
				pos++;
				result.add(new Token(Kind.COLON, ":", null, line));
			} else if (c == '\'') {
				while (pos < length && text.charAt(pos) != '\n' && text.charAt(pos) != '\r') {
					pos++;
				}
			} else if (c == '_' && continuation(text, pos + 1)) {
				// A line continued on the next one.
				pos++;
				while (pos < length && text.charAt(pos) != '\n') {
					pos++;
				}
				pos++;
				line++;
			} else if (Character.isDigit(c) || c == '.' && pos + 1 < length && Character.isDigit(text.charAt(pos + 1))) {
				int start = pos;
				while (pos < length && Character.isDigit(text.charAt(pos))) {
					pos++;
				}
				if (pos < length && text.charAt(pos) == '.') {
					pos++;
					while (pos < length && Character.isDigit(text.charAt(pos))) {
						pos++;
					}
				}
				if (pos < length && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
					int mark = pos++;
					if (pos < length && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
						pos++;
					}
					if (pos < length && Character.isDigit(text.charAt(pos))) {
						while (pos < length && Character.isDigit(text.charAt(pos))) {
							pos++;
						}
					} else {
						pos = mark;
					}
				}
				String number = text.substring(start, pos);
				result.add(new Token(Kind.NUMBER, number, Double.valueOf(number), line));
			} else if (c == '.' && pos + 1 < length && Character.isLetter(text.charAt(pos + 1))) {
				// The member of an object.
				pos++;
				result.add(new Token(Kind.OPERATOR, ".", null, line));
			} else if (c == '&' && pos + 1 < length && (text.charAt(pos + 1) == 'H' || text.charAt(pos + 1) == 'h')) {
				int start = pos + 2;
				pos = start;
				while (pos < length && Character.digit(text.charAt(pos), 16) >= 0) {
					pos++;
				}
				if (pos < length && text.charAt(pos) == '&') {
					pos++;
				}
				String digits = text.substring(start, text.charAt(pos - 1) == '&' ? pos - 1 : pos);
				if (digits.isEmpty()) {
					throw new CalculationException("hexadecimal number without digits (line " + line + ")");
				}
				result.add(new Token(Kind.NUMBER, text.substring(start - 2, pos),
					Double.valueOf(Long.parseLong(digits, 16)), line));
			} else if (c == '"') {
				StringBuilder value = new StringBuilder();
				pos++;
				while (true) {
					if (pos >= length || text.charAt(pos) == '\n') {
						throw new CalculationException("unterminated text (line " + line + ")");
					}
					char d = text.charAt(pos++);
					if (d == '"') {
						if (pos < length && text.charAt(pos) == '"') {
							value.append('"');
							pos++;
						} else {
							break;
						}
					} else {
						value.append(d);
					}
				}
				result.add(new Token(Kind.TEXT, value.toString(), value.toString(), line));
			} else if (Character.isLetter(c)) {
				int start = pos;
				while (pos < length && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
					pos++;
				}
				String name = text.substring(start, pos);
				if (name.equalsIgnoreCase("rem")) {
					while (pos < length && text.charAt(pos) != '\n' && text.charAt(pos) != '\r') {
						pos++;
					}
				} else {
					result.add(new Token(Kind.NAME, name, null, line));
				}
			} else {
				String operator = null;
				for (String candidate : List.of("<>", "<=", ">=", "=", "<", ">", "+", "-", "*", "/", "\\", "^", "&",
					"(", ")", ",")) {
					if (text.startsWith(candidate, pos)) {
						operator = candidate;
						break;
					}
				}
				if (operator == null) {
					throw new CalculationException("unsupported character '" + c + "' (line " + line + ")");
				}
				pos += operator.length();
				result.add(new Token(Kind.OPERATOR, operator, null, line));
			}
		}
		result.add(new Token(Kind.END, "", null, line));
		return result;
	}

	private static boolean continuation(String text, int pos) {
		for (int n = pos; n < text.length(); n++) {
			char c = text.charAt(n);
			if (c == '\n' || c == '\r') {
				return true;
			}
			if (c != ' ' && c != '\t') {
				return false;
			}
		}
		return false;
	}

	// Syntax tree.

	/** The statements and procedures of a script. */
	private record Program(List<Statement> statements, Map<String, Procedure> procedures) {
		// Value.
	}

	/** A Function or Sub. */
	private record Procedure(String name, List<String> parameters, List<Statement> body, boolean function) {
		// Value.
	}

	/**
	 * The variables of a run: those of the script, and those of the call running (<code>null</code>
	 * outside a call).
	 */
	private record Env(Map<String, Object> globals, Map<String, Object> locals, Map<String, Procedure> procedures,
			int depth) {

		Object get(String key) {
			if (locals != null && locals.containsKey(key)) {
				return locals.get(key);
			}
			return globals.get(key);
		}

		void set(String key, Object value) {
			if (locals != null && (locals.containsKey(key) || !globals.containsKey(key))) {
				locals.put(key, value);
			} else {
				globals.put(key, value);
			}
		}

		void declare(String key) {
			(locals != null ? locals : globals).putIfAbsent(key, Double.valueOf(0));
		}
	}

	/** Leaves the procedure running (Exit Function, Exit Sub). */
	private static final class ExitProcedure extends RuntimeException {
		ExitProcedure() {
			super(null, null, false, false);
		}
	}

	private interface Statement {
		void run(Env env) throws CalculationException;
	}

	private interface Expression {
		Object eval(Env env) throws CalculationException;
	}

	private record Assignment(String name, Expression value) implements Statement {
		@Override
		public void run(Env env) throws CalculationException {
			env.set(key(name), value.eval(env));
		}
	}

	private record Declaration(String name) implements Statement {
		@Override
		public void run(Env env) {
			env.declare(key(name));
		}
	}

	private record Exit() implements Statement {
		@Override
		public void run(Env env) {
			throw new ExitProcedure();
		}
	}

	private record CallStatement(Invocation call) implements Statement {
		@Override
		public void run(Env env) throws CalculationException {
			call.eval(env);
		}
	}

	private record Branch(Expression condition, List<Statement> body) {
		// Value.
	}

	private record Conditional(List<Branch> branches, List<Statement> otherwise) implements Statement {
		@Override
		public void run(Env env) throws CalculationException {
			for (Branch branch : branches) {
				if (truth(branch.condition().eval(env))) {
					runAll(branch.body(), env);
					return;
				}
			}
			runAll(otherwise, env);
		}
	}

	private static void runAll(List<Statement> statements, Env env) throws CalculationException {
		for (Statement statement : statements) {
			statement.run(env);
		}
	}

	private record Literal(Object value) implements Expression {
		@Override
		public Object eval(Env env) {
			return value;
		}
	}

	private record Variable(String name, int line) implements Expression {
		@Override
		public Object eval(Env env) throws CalculationException {
			Object result = env.get(key(name));
			if (result == null) {
				throw new CalculationException("the name '" + name + "' has no value (line " + line + ")");
			}
			return result;
		}
	}

	/** A member of an object the caller passed (<code>input.RangeMin</code>). */
	private record Member(Expression object, String name, int line) implements Expression {
		@Override
		public Object eval(Env env) throws CalculationException {
			Object value = object.eval(env);
			if (!(value instanceof Map<?, ?> members)) {
				throw new CalculationException("'" + text(value) + "' is no object, it has no member '" + name
					+ "' (line " + line + ")");
			}
			for (Map.Entry<?, ?> entry : members.entrySet()) {
				if (String.valueOf(entry.getKey()).equalsIgnoreCase(name)) {
					if (entry.getValue() == null) {
						throw new CalculationException("the member '" + name + "' has no value (line " + line + ")");
					}
					return entry.getValue();
				}
			}
			throw new CalculationException("the object has no member '" + name + "' (line " + line + ")");
		}
	}

	/** A call of a procedure of the script. */
	private record Invocation(String name, List<Expression> arguments, int line) implements Expression {
		@Override
		public Object eval(Env env) throws CalculationException {
			Procedure procedure = env.procedures().get(key(name));
			if (procedure == null) {
				throw new CalculationException("unknown procedure '" + name + "' (line " + line + ")");
			}
			if (procedure.parameters().size() != arguments.size()) {
				throw new CalculationException("wrong number of arguments of '" + name + "' (line " + line + ")");
			}
			if (env.depth() >= MAX_DEPTH) {
				throw new CalculationException("calls nest deeper than " + MAX_DEPTH + " (line " + line + ")");
			}
			Map<String, Object> locals = new HashMap<>();
			for (int n = 0; n < arguments.size(); n++) {
				locals.put(key(procedure.parameters().get(n)), arguments.get(n).eval(env));
			}
			String result = key(procedure.name());
			if (procedure.function()) {
				locals.put(result, Double.valueOf(0));
			}
			Env inner = new Env(env.globals(), locals, env.procedures(), env.depth() + 1);
			try {
				runAll(procedure.body(), inner);
			} catch (ExitProcedure ex) {
				// Left.
			}
			return procedure.function() ? locals.get(result) : Double.valueOf(0);
		}
	}

	private record Unary(String operator, Expression operand, int line) implements Expression {
		@Override
		public Object eval(Env env) throws CalculationException {
			Object value = operand.eval(env);
			switch (operator) {
				case "-":
					return Double.valueOf(-number(value, line));
				case "+":
					return Double.valueOf(number(value, line));
				default:
					// Not.
					if (value instanceof Boolean flag) {
						return Boolean.valueOf(!flag.booleanValue());
					}
					return Double.valueOf(~whole(value, line));
			}
		}
	}

	private record Binary(String operator, Expression left, Expression right, int line) implements Expression {
		@Override
		public Object eval(Env env) throws CalculationException {
			Object a = left.eval(env);
			Object b = right.eval(env);
			switch (operator) {
				case "^":
					return Double.valueOf(Math.pow(number(a, line), number(b, line)));
				case "*":
					return Double.valueOf(number(a, line) * number(b, line));
				case "/": {
					double divisor = number(b, line);
					if (divisor == 0) {
						throw new CalculationException("division by zero (line " + line + ")");
					}
					return Double.valueOf(number(a, line) / divisor);
				}
				case "\\": {
					long divisor = whole(b, line);
					if (divisor == 0) {
						throw new CalculationException("division by zero (line " + line + ")");
					}
					return Double.valueOf(whole(a, line) / divisor);
				}
				case "mod": {
					long divisor = whole(b, line);
					if (divisor == 0) {
						throw new CalculationException("division by zero (line " + line + ")");
					}
					return Double.valueOf(whole(a, line) % divisor);
				}
				case "+":
					if (a instanceof String x && b instanceof String y) {
						return x + y;
					}
					return Double.valueOf(number(a, line) + number(b, line));
				case "-":
					return Double.valueOf(number(a, line) - number(b, line));
				case "&":
					return text(a) + text(b);
				case "=":
				case "<>":
				case "<":
				case ">":
				case "<=":
				case ">=":
					return Boolean.valueOf(compare(operator, a, b, line));
				default: {
					// And, Or, Xor.
					if (a instanceof Boolean x && b instanceof Boolean y) {
						boolean p = x.booleanValue();
						boolean q = y.booleanValue();
						return Boolean.valueOf(operator.equals("and") ? p && q : operator.equals("or") ? p || q : p ^ q);
					}
					long p = whole(a, line);
					long q = whole(b, line);
					return Double.valueOf(operator.equals("and") ? p & q : operator.equals("or") ? p | q : p ^ q);
				}
			}
		}
	}

	private record Call(String function, List<Expression> arguments, int line) implements Expression {
		@Override
		public Object eval(Env env) throws CalculationException {
			List<Object> args = new ArrayList<>();
			for (Expression argument : arguments) {
				args.add(argument.eval(env));
			}
			double x = number(args.get(0), line);
			switch (function) {
				case "int":
					return Double.valueOf(Math.floor(x));
				case "fix":
					return Double.valueOf(x < 0 ? Math.ceil(x) : Math.floor(x));
				case "abs":
					return Double.valueOf(Math.abs(x));
				case "sgn":
					return Double.valueOf(Math.signum(x));
				case "sqr":
					if (x < 0) {
						throw new CalculationException("Sqr of a negative number (line " + line + ")");
					}
					return Double.valueOf(Math.sqrt(x));
				case "round": {
					double scale = args.size() > 1 ? Math.pow(10, whole(args.get(1), line)) : 1;
					return Double.valueOf(Math.rint(x * scale) / scale);
				}
				case "cint":
				case "clng":
					return Double.valueOf(Math.rint(x));
				default:
					// CDbl.
					return Double.valueOf(x);
			}
		}
	}

	static double number(Object value, int line) throws CalculationException {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof Boolean flag) {
			return flag.booleanValue() ? -1 : 0;
		}
		String text = value.toString().trim();
		try {
			return Double.parseDouble(text);
		} catch (NumberFormatException ex) {
			throw new CalculationException("'" + text + "' is no number (line " + line + ")");
		}
	}

	/** The value rounded to a whole number, half to even, as VBScript does for \ and Mod. */
	static long whole(Object value, int line) throws CalculationException {
		return (long) Math.rint(number(value, line));
	}

	private static String text(Object value) {
		if (value instanceof Double number && number.doubleValue() == Math.rint(number.doubleValue())
			&& !Double.isInfinite(number.doubleValue())) {
			return Long.toString(number.longValue());
		}
		if (value instanceof Boolean flag) {
			return flag.booleanValue() ? "True" : "False";
		}
		return String.valueOf(value);
	}

	private static boolean compare(String operator, Object a, Object b, int line) throws CalculationException {
		int comparison;
		if (a instanceof String x && b instanceof String y) {
			comparison = x.compareTo(y);
		} else {
			comparison = Double.compare(number(a, line), number(b, line));
		}
		switch (operator) {
			case "=":
				return comparison == 0;
			case "<>":
				return comparison != 0;
			case "<":
				return comparison < 0;
			case ">":
				return comparison > 0;
			case "<=":
				return comparison <= 0;
			default:
				return comparison >= 0;
		}
	}

	static boolean truth(Object value) throws CalculationException {
		if (value instanceof Boolean flag) {
			return flag.booleanValue();
		}
		if (value instanceof String text) {
			if (text.equalsIgnoreCase("true")) {
				return true;
			}
			if (text.equalsIgnoreCase("false")) {
				return false;
			}
		}
		return number(value, 0) != 0;
	}

	// Parser.

	private static final class Parser {

		private final List<Token> _tokens;

		/** The names of the procedures the code may call (of the library and of the code itself). */
		private final Set<String> _procedures = new HashSet<>();

		private final Map<String, Procedure> _defined = new LinkedHashMap<>();

		private int _pos;

		Parser(String code, Set<String> known) throws CalculationException {
			_tokens = tokens(code);
			_procedures.addAll(known);
			// Procedures are known before their definitions (VBScript defines them before it runs).
			for (int n = 0; n + 1 < _tokens.size(); n++) {
				Token token = _tokens.get(n);
				boolean start = n == 0 || _tokens.get(n - 1).endsStatement();
				if (start && (token.is("function") || token.is("sub")) && _tokens.get(n + 1).kind() == Kind.NAME) {
					_procedures.add(key(_tokens.get(n + 1).text()));
				}
			}
		}

		Program parse() throws CalculationException {
			List<Statement> result = block(Set.of(), false);
			Token next = peek();
			if (next.kind() != Kind.END) {
				throw unsupported(next);
			}
			return new Program(result, _defined);
		}

		/**
		 * Statements up to (not including) one of the given words at the start of a statement, or the
		 * end.
		 */
		private List<Statement> block(Set<String> terminators, boolean inProcedure) throws CalculationException {
			List<Statement> result = new ArrayList<>();
			while (true) {
				Token token = peek();
				if (token.kind() == Kind.NEWLINE || token.kind() == Kind.COLON) {
					_pos++;
					continue;
				}
				if (token.kind() == Kind.END) {
					return result;
				}
				if (token.kind() == Kind.NAME && terminators.contains(token.text().toLowerCase(Locale.ROOT))) {
					return result;
				}
				statement(result, false, inProcedure);
			}
		}

		private void statement(List<Statement> result, boolean singleLine, boolean inProcedure)
				throws CalculationException {
			Token token = next();
			if (token.is("dim")) {
				do {
					Token name = next();
					if (name.kind() != Kind.NAME || reserved(name)) {
						throw unsupported(name);
					}
					result.add(new Declaration(name.text()));
				} while (accept(","));
				endOfStatement(singleLine);
			} else if (token.is("const")) {
				do {
					Token name = next();
					if (name.kind() != Kind.NAME || reserved(name) || !accept("=")) {
						throw unsupported(name);
					}
					result.add(new Assignment(name.text(), expression()));
				} while (accept(","));
				endOfStatement(singleLine);
			} else if (token.is("if")) {
				result.add(conditional(token, inProcedure));
			} else if ((token.is("function") || token.is("sub")) && !singleLine && !inProcedure) {
				procedure(token);
			} else if (token.is("exit") && inProcedure && (peek().is("function") || peek().is("sub"))) {
				next();
				result.add(new Exit());
				endOfStatement(singleLine);
			} else if (token.is("call")) {
				Token name = next();
				if (name.kind() != Kind.NAME || !_procedures.contains(key(name.text()))) {
					throw unsupported(name);
				}
				result.add(new CallStatement(invocation(name)));
				endOfStatement(singleLine);
			} else if (token.kind() == Kind.NAME && !reserved(token)) {
				if (_procedures.contains(key(token.text())) && !peek().is("=")) {
					result.add(new CallStatement(invocation(token)));
					endOfStatement(singleLine);
					return;
				}
				if (!accept("=")) {
					throw unsupported(peek().endsStatement() || peek().is("(") ? token : peek());
				}
				result.add(new Assignment(token.text(), expression()));
				endOfStatement(singleLine);
			} else {
				throw unsupported(token);
			}
		}

		private void endOfStatement(boolean singleLine) throws CalculationException {
			Token next = peek();
			if (next.endsStatement() || singleLine && next.is("else")) {
				return;
			}
			throw unsupported(next);
		}

		private void procedure(Token start) throws CalculationException {
			boolean function = start.is("function");
			Token name = next();
			if (name.kind() != Kind.NAME || reserved(name)) {
				throw unsupported(name);
			}
			List<String> parameters = new ArrayList<>();
			if (accept("(")) {
				if (!accept(")")) {
					do {
						Token parameter = next();
						if (parameter.is("byval") || parameter.is("byref")) {
							parameter = next();
						}
						if (parameter.kind() != Kind.NAME || reserved(parameter)) {
							throw unsupported(parameter);
						}
						parameters.add(parameter.text());
					} while (accept(","));
					Token close = next();
					if (!close.is(")")) {
						throw expected(")", close);
					}
				}
			}
			List<Statement> body = block(Set.of("end"), true);
			Token end = next();
			Token kind = next();
			if (!end.is("end") || !kind.is(function ? "function" : "sub")) {
				throw expected(function ? "End Function" : "End Sub", kind);
			}
			_defined.put(key(name.text()), new Procedure(name.text(), parameters, body, function));
		}

		private Statement conditional(Token start, boolean inProcedure) throws CalculationException {
			Expression condition = expression();
			Token then = next();
			if (!then.is("then")) {
				throw expected("Then", then);
			}
			if (peek().kind() != Kind.NEWLINE && peek().kind() != Kind.END) {
				// Single line: If c Then statements [Else statements].
				List<Statement> body = new ArrayList<>();
				List<Statement> otherwise = new ArrayList<>();
				singleLine(body, inProcedure);
				if (accept("else")) {
					singleLine(otherwise, inProcedure);
				}
				return new Conditional(List.of(new Branch(condition, body)), otherwise);
			}
			List<Branch> branches = new ArrayList<>();
			List<Statement> otherwise = List.of();
			branches.add(new Branch(condition, block(Set.of("elseif", "else", "end"), inProcedure)));
			while (true) {
				Token token = next();
				if (token.is("elseif")) {
					Expression next = expression();
					Token keyword = next();
					if (!keyword.is("then")) {
						throw expected("Then", keyword);
					}
					branches.add(new Branch(next, block(Set.of("elseif", "else", "end"), inProcedure)));
				} else if (token.is("else")) {
					otherwise = block(Set.of("end"), inProcedure);
				} else if (token.is("end")) {
					Token keyword = next();
					if (!keyword.is("if")) {
						throw expected("End If", keyword);
					}
					return new Conditional(branches, otherwise);
				} else {
					throw new CalculationException("If without End If (line " + start.line() + ")");
				}
			}
		}

		private void singleLine(List<Statement> body, boolean inProcedure) throws CalculationException {
			while (true) {
				statement(body, true, inProcedure);
				if (peek().kind() == Kind.COLON) {
					_pos++;
					if (peek().kind() == Kind.NEWLINE || peek().kind() == Kind.END) {
						return;
					}
					continue;
				}
				return;
			}
		}

		private Invocation invocation(Token name) throws CalculationException {
			List<Expression> arguments = new ArrayList<>();
			if (accept("(")) {
				if (!accept(")")) {
					do {
						arguments.add(expression());
					} while (accept(","));
					Token close = next();
					if (!close.is(")")) {
						throw expected(")", close);
					}
				}
			}
			return new Invocation(name.text(), arguments, name.line());
		}

		private Expression expression() throws CalculationException {
			return binary(0);
		}

		/** The operators by precedence, lowest first; #not and #unary stand for the unary operators. */
		private static final List<List<String>> LEVELS = List.of(
			List.of("xor"),
			List.of("or"),
			List.of("and"),
			List.of("#not"),
			List.of("=", "<>", "<", ">", "<=", ">="),
			List.of("&"),
			List.of("+", "-"),
			List.of("mod"),
			List.of("\\"),
			List.of("*", "/"),
			List.of("#unary"),
			List.of("^"));

		private Expression binary(int level) throws CalculationException {
			if (level >= LEVELS.size()) {
				return primary();
			}
			List<String> operators = LEVELS.get(level);
			if (operators.get(0).equals("#not")) {
				Token token = peek();
				if (token.is("not")) {
					_pos++;
					return new Unary("not", binary(level), token.line());
				}
				return binary(level + 1);
			}
			if (operators.get(0).equals("#unary")) {
				Token token = peek();
				if (token.is("-") || token.is("+")) {
					_pos++;
					return new Unary(token.text(), binary(level), token.line());
				}
				return binary(level + 1);
			}
			Expression result = binary(level + 1);
			while (true) {
				Token token = peek();
				String operator = null;
				for (String candidate : operators) {
					if (token.is(candidate)) {
						operator = candidate;
					}
				}
				if (operator == null) {
					return result;
				}
				_pos++;
				Expression right = binary(level + 1);
				result = new Binary(operator.toLowerCase(Locale.ROOT), result, right, token.line());
			}
		}

		private Expression primary() throws CalculationException {
			Token token = next();
			switch (token.kind()) {
				case NUMBER:
				case TEXT:
					return new Literal(token.value());
				case OPERATOR:
					if (token.is("(")) {
						Expression result = expression();
						Token close = next();
						if (!close.is(")")) {
							throw expected(")", close);
						}
						return result;
					}
					throw unsupported(token);
				case NAME: {
					if (token.is("true")) {
						return new Literal(Boolean.TRUE);
					}
					if (token.is("false")) {
						return new Literal(Boolean.FALSE);
					}
					if (reserved(token)) {
						throw unsupported(token);
					}
					if (_procedures.contains(key(token.text()))) {
						return invocation(token);
					}
					if (peek().is("(")) {
						String function = token.text().toLowerCase(Locale.ROOT);
						if (!FUNCTIONS.contains(function)) {
							throw new CalculationException(
								"unsupported function '" + token.text() + "' (line " + token.line() + ")");
						}
						_pos++;
						List<Expression> arguments = new ArrayList<>();
						if (!accept(")")) {
							do {
								arguments.add(expression());
							} while (accept(","));
							Token close = next();
							if (!close.is(")")) {
								throw expected(")", close);
							}
						}
						int max = function.equals("round") ? 2 : 1;
						if (arguments.isEmpty() || arguments.size() > max) {
							throw new CalculationException("wrong number of arguments of '" + token.text() + "' (line "
								+ token.line() + ")");
						}
						return new Call(function, arguments, token.line());
					}
					Expression result = new Variable(token.text(), token.line());
					while (peek().is(".")) {
						_pos++;
						Token member = next();
						if (member.kind() != Kind.NAME) {
							throw unsupported(member);
						}
						result = new Member(result, member.text(), member.line());
					}
					return result;
				}
				default:
					throw unsupported(token);
			}
		}

		private boolean accept(String word) {
			if (peek().is(word)) {
				_pos++;
				return true;
			}
			return false;
		}

		private Token peek() {
			return _tokens.get(_pos);
		}

		private Token next() {
			Token result = _tokens.get(_pos);
			if (result.kind() != Kind.END) {
				_pos++;
			}
			return result;
		}

		private static boolean reserved(Token token) {
			return RESERVED.contains(token.text().toLowerCase(Locale.ROOT));
		}

		private static CalculationException unsupported(Token token) {
			if (token.kind() == Kind.END) {
				return new CalculationException("unexpected end of the script (line " + token.line() + ")");
			}
			if (token.kind() == Kind.NEWLINE || token.kind() == Kind.COLON) {
				return new CalculationException("unexpected end of the statement (line " + token.line() + ")");
			}
			return new CalculationException(
				"unsupported construct '" + token.text() + "' (line " + token.line() + ")");
		}

		private static CalculationException expected(String what, Token token) {
			return new CalculationException(
				what + " expected instead of '" + token.text() + "' (line " + token.line() + ")");
		}
	}

}
