/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A compiled node of an expression (<code>spec/rules.md</code> 5): its kind, operator or function,
 * static type and children. Built by the {@link RuleLoader}; read-only afterwards.
 */
public final class Node {

	/**
	 * The node kinds of the JSON AST.
	 */
	public enum Kind {
		/** A literal value. */
		LITERAL,
		/** A datapoint reference. */
		DATAPOINT,
		/** A parameter of the rule. */
		PARAMETER,
		/** A timer of the rule (only as the argument of running() and remaining()). */
		TIMER,
		/** <code>-x</code>, <code>not x</code>. */
		UNARY,
		/** <code>a op b</code>. */
		BINARY,
		/** <code>f(a, b)</code>. */
		CALL;
	}

	/**
	 * The operators of unary and binary nodes, with their text.
	 */
	public enum Op {
		/** Prefix minus. */
		NEG("-"),
		/** Prefix not. */
		NOT("not"),
		/** Three-valued or. */
		OR("or"),
		/** Three-valued and. */
		AND("and"),
		/** Equality. */
		EQ("=="),
		/** Inequality. */
		NE("!="),
		/** Less. */
		LT("<"),
		/** Less or equal. */
		LE("<="),
		/** Greater. */
		GT(">"),
		/** Greater or equal. */
		GE(">="),
		/** Addition. */
		ADD("+"),
		/** Subtraction. */
		SUB("-"),
		/** Multiplication. */
		MUL("*"),
		/** Division. */
		DIV("/"),
		/** Truncated remainder. */
		MOD("%");

		private final String _text;

		Op(String text) {
			_text = text;
		}

		/** The operator's text. */
		public String text() {
			return _text;
		}

		/** The binary operator with the given text, <code>null</code> if none. */
		public static Op binary(String text) {
			for (Op op : values()) {
				if (op.ordinal() >= OR.ordinal() && op._text.equals(text)) {
					return op;
				}
			}
			return null;
		}
	}

	/**
	 * The functions of <code>spec/rules.md</code> 4.7, with their names.
	 */
	public enum Fn {
		/** The function <code>if(c, a, b)</code>. */
		IF("if"),
		/** The function <code>known(x)</code>. */
		KNOWN("known"),
		/** The function <code>coalesce(x, y)</code>. */
		COALESCE("coalesce"),
		/** The function <code>between(x, low, high)</code>. */
		BETWEEN("between"),
		/** The function <code>hysteresis(x, low, high, previous)</code>. */
		HYSTERESIS("hysteresis"),
		/** The function <code>abs(x)</code>. */
		ABS("abs"),
		/** The function <code>min(a, b, ...)</code>. */
		MIN("min"),
		/** The function <code>max(a, b, ...)</code>. */
		MAX("max"),
		/** The function <code>clamp(x, low, high)</code>. */
		CLAMP("clamp"),
		/** The function <code>round(x)</code>. */
		ROUND("round"),
		/** The function <code>floor(x)</code>. */
		FLOOR("floor"),
		/** The function <code>ceil(x)</code>. */
		CEIL("ceil"),
		/** The function <code>trunc(x)</code>. */
		TRUNC("trunc"),
		/** The function <code>roundTo(x, step)</code>. */
		ROUND_TO("roundTo"),
		/** The function <code>toInt(x)</code>. */
		TO_INT("toInt"),
		/** The function <code>now()</code>. */
		NOW("now"),
		/** The function <code>today()</code>. */
		TODAY("today"),
		/** The function <code>time()</code>. */
		TIME("time"),
		/** The function <code>weekday(), weekday(x)</code>. */
		WEEKDAY("weekday"),
		/** The function <code>hour(x)</code>. */
		HOUR("hour"),
		/** The function <code>minute(x)</code>. */
		MINUTE("minute"),
		/** The function <code>second(x)</code>. */
		SECOND("second"),
		/** The function <code>year(x)</code>. */
		YEAR("year"),
		/** The function <code>month(x)</code>. */
		MONTH("month"),
		/** The function <code>day(x)</code>. */
		DAY("day"),
		/** The function <code>dateOf(x)</code>. */
		DATE_OF("dateOf"),
		/** The function <code>timeOf(x)</code>. */
		TIME_OF("timeOf"),
		/** The function <code>inWindow(t, from, until)</code>. */
		IN_WINDOW("inWindow"),
		/** The function <code>sunrise()</code>. */
		SUNRISE("sunrise"),
		/** The function <code>sunset()</code>. */
		SUNSET("sunset"),
		/** The function <code>dawn()</code>. */
		DAWN("dawn"),
		/** The function <code>dusk()</code>. */
		DUSK("dusk"),
		/**
		 * The function <code>sunAzimuth()</code>: the sun's azimuth in degrees from north through east.
		 */
		SUN_AZIMUTH("sunAzimuth"),
		/**
		 * The function <code>sunElevation()</code>: the sun's elevation in degrees above the horizon.
		 */
		SUN_ELEVATION("sunElevation"),
		/** The function <code>sinceUpdate(@d)</code>. */
		SINCE_UPDATE("sinceUpdate"),
		/** The function <code>sinceChange(@d)</code>. */
		SINCE_CHANGE("sinceChange"),
		/** The function <code>changedWithin(@d, t)</code>. */
		CHANGED_WITHIN("changedWithin"),
		/** The function <code>lastKnown(@d)</code>. */
		LAST_KNOWN("lastKnown"),
		/** The function <code>quality(@d)</code>. */
		QUALITY("quality"),
		/** The function <code>running(t)</code>. */
		RUNNING("running"),
		/** The function <code>remaining(t)</code>. */
		REMAINING("remaining"),
		/** The function <code>state(), state("rule")</code>. */
		STATE("state"),
		/** The function <code>length(s)</code>. */
		LENGTH("length"),
		/** The function <code>contains(s, part)</code>. */
		CONTAINS("contains"),
		/** The function <code>startsWith(s, part)</code>. */
		STARTS_WITH("startsWith"),
		/** The function <code>endsWith(s, part)</code>. */
		ENDS_WITH("endsWith"),
		/** The function <code>toString(x)</code>. */
		TO_STRING("toString"),
		/** The function <code>rgb(r, g, b)</code>. */
		RGB("rgb"),
		/** The function <code>rgbw(r, g, b, w)</code>. */
		RGBW("rgbw"),
		/** The function <code>red(c)</code>. */
		RED("red"),
		/** The function <code>green(c)</code>. */
		GREEN("green"),
		/** The function <code>blue(c)</code>. */
		BLUE("blue"),
		/** The function <code>white(c)</code>. */
		WHITE("white"),
		/** The function <code>controlled(bit, n)</code>. */
		CONTROLLED("controlled"),
		/** The function <code>controlBit(x)</code>. */
		CONTROL_BIT("controlBit"),
		/** The function <code>controlValue(x)</code>. */
		CONTROL_VALUE("controlValue");

		private final String _name;

		Fn(String name) {
			_name = name;
		}

		/** The function's name in the image. */
		public String externalName() {
			return _name;
		}

		/** The function with the given name, <code>null</code> if none. */
		public static Fn fromName(String name) {
			for (Fn fn : values()) {
				if (fn._name.equals(name)) {
					return fn;
				}
			}
			return null;
		}

		/**
		 * A function of the wall clock or the sun: its times and its position (not allowed in a match
		 * condition).
		 */
		public boolean isTime() {
			return ordinal() >= NOW.ordinal() && ordinal() <= SUN_ELEVATION.ordinal();
		}

		/** A function of a datapoint's history. */
		public boolean isHistory() {
			return ordinal() >= SINCE_UPDATE.ordinal() && ordinal() <= QUALITY.ordinal();
		}
	}

	final Kind _kind;

	final Op _op;

	final Fn _fn;

	final Type _type;

	final List<Node> _args;

	final Val _literal;

	final String _name;

	final int _ref;

	/**
	 * The index of an expression root in its {@link RuleSet} (for the "once" flags of the
	 * diagnostics), -1 for an inner node.
	 */
	int _root = -1;

	Node(Kind kind, Op op, Fn fn, Type type, List<Node> args, Val literal, String name, int ref) {
		_kind = kind;
		_op = op;
		_fn = fn;
		_type = type;
		_args = args;
		_literal = literal;
		_name = name;
		_ref = ref;
	}

	/** The node kind. */
	public Kind kind() {
		return _kind;
	}

	/** The operator of a unary or binary node. */
	public Op op() {
		return _op;
	}

	/** The function of a call. */
	public Fn fn() {
		return _fn;
	}

	/** The static result type; <code>null</code> for a timer node. */
	public Type type() {
		return _type;
	}

	/** The children: the operand(s) or the arguments. */
	public List<Node> args() {
		return _args;
	}

	/** The value of a literal. */
	public Val literal() {
		return _literal;
	}

	/** The datapoint name of a datapoint node. */
	public String name() {
		return _name;
	}

	/**
	 * The parameter index (0 is <code>enabled</code>) or timer index in the rule; the rule index of
	 * <code>state("id")</code>.
	 */
	public int ref() {
		return _ref;
	}

	/**
	 * Visits this node and all below it in pre-order.
	 */
	public void visit(Consumer<Node> visitor) {
		visitor.accept(this);
		for (Node arg : _args) {
			arg.visit(visitor);
		}
	}

	/**
	 * The JSON AST of this node (<code>spec/rules.md</code> 5), in the normalized form the loader
	 * compiled it to (<code>spec/rules.md</code> 4.8).
	 */
	public Map<String, Object> toJson() {
		Map<String, Object> result = new LinkedHashMap<>();
		switch (_kind) {
			case LITERAL:
				result.put("kind", "literal");
				result.put("type", _type.externalName());
				result.put("value", Values.toJson(_literal));
				break;
			case DATAPOINT:
				result.put("kind", "datapoint");
				result.put("name", _name);
				break;
			case PARAMETER:
				result.put("kind", "parameter");
				result.put("id", _name);
				break;
			case TIMER:
				result.put("kind", "timer");
				result.put("id", _name);
				break;
			case UNARY:
				result.put("kind", "unary");
				result.put("op", _op.text());
				result.put("operand", _args.get(0).toJson());
				break;
			case BINARY:
				result.put("kind", "binary");
				result.put("op", _op.text());
				result.put("left", _args.get(0).toJson());
				result.put("right", _args.get(1).toJson());
				break;
			default: {
				result.put("kind", "call");
				result.put("function", _fn.externalName());
				List<Object> args = new ArrayList<>();
				for (Node arg : _args) {
					args.add(arg.toJson());
				}
				result.put("args", args);
			}
		}
		return result;
	}

	/**
	 * A short description for diagnostics.
	 */
	@Override
	public String toString() {
		switch (_kind) {
			case LITERAL:
				return Values.literalText(_literal);
			case DATAPOINT:
				return "@" + _name;
			case PARAMETER:
				return "$" + _ref;
			case TIMER:
				return "timer " + _ref;
			case UNARY:
				return _op == Op.NOT ? "not " + _args.get(0) : "-" + _args.get(0);
			case BINARY:
				return "(" + _args.get(0) + " " + _op.text() + " " + _args.get(1) + ")";
			default: {
				StringBuilder out = new StringBuilder(_fn.externalName()).append('(');
				for (int i = 0; i < _args.size(); i++) {
					out.append(i > 0 ? ", " : "").append(_args.get(i));
				}
				return out.append(')').toString();
			}
		}
	}

}
