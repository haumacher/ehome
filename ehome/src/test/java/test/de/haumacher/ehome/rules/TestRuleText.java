/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.runtime.Json;

/**
 * The text syntax of rule expressions ({@link RuleText}, homecontrol <code>spec/rules-grammar.ebnf</code>):
 * the round-trip property over every expression and log message of the rule corpus (print, parse:
 * the same AST; parse, print: the same text), the canonical forms of the specification's examples,
 * the lexer's disambiguation and syntax errors with their position. Needs no knowledge base.
 */
@SuppressWarnings("javadoc")
public class TestRuleText extends TestCase {

	/**
	 * Print and parse every expression of the corpus: the same AST, and the printed text is a fixpoint.
	 */
	public void testCorpusRoundTrip() throws Exception {
		List<String> failures = new ArrayList<>();
		int[] count = { 0, 0 };
		for (File file : corpusFiles()) {
			Map<?, ?> json = (Map<?, ?>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), 100);
			List<Object> images = new ArrayList<>();
			images.add(json.get("rules"));
			if (json.get("timeline") instanceof List<?> timeline) {
				for (Object step : timeline) {
					if (((Map<?, ?>) step).get("image") instanceof Map<?, ?> image) {
						images.add(image.get("rules"));
					}
				}
			}
			for (Object rules : images) {
				walk(file.getName(), "rules", rules, null, failures, count);
			}
		}
		assertEquals(String.join("\n", failures), List.of(), failures);
		assertTrue("expressions: " + count[0], count[0] > 150);
		assertTrue("messages: " + count[1], count[1] > 40);
	}

	private static File[] corpusFiles() {
		File[] files = TestRuleCorpus.CORPUS.listFiles((dir, name) -> name.endsWith(".json"));
		java.util.Arrays.sort(files);
		return files;
	}

	private static final java.util.Set<String> EXPRESSION_MEMBERS =
		java.util.Set.of("condition", "duration", "value");

	private static void walk(String file, String path, Object json, String member, List<String> failures, int[] count) {
		if (json instanceof Map<?, ?> map) {
			if (member != null && EXPRESSION_MEMBERS.contains(member) && map.containsKey("kind")) {
				count[0]++;
				checkExpression(file + " " + path, map, failures);
				return;
			}
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey());
				if (key.equals("message") && entry.getValue() instanceof List<?> message) {
					count[1]++;
					checkMessage(file + " " + path + ".message", message, failures);
				} else if (!key.equals("default") && !key.equals("min") && !key.equals("max")) {
					walk(file, path + "." + key, entry.getValue(), key, failures, count);
				}
			}
		} else if (json instanceof List<?> list) {
			for (int i = 0; i < list.size(); i++) {
				walk(file, path + "[" + i + "]", list.get(i), null, failures, count);
			}
		}
	}

	private static void checkExpression(String where, Map<?, ?> ast, List<String> failures) {
		// The round trip holds on normalized trees (spec/rules.md 4.8).
		Object normalized = RuleText.normalize(ast);
		String text = RuleText.print(normalized);
		try {
			Map<String, Object> parsed = RuleText.parse(text);
			if (!same(normalized, parsed)) {
				failures.add(where + ": '" + text + "' reads back as " + Json.write(parsed).trim());
			}
			String again = RuleText.print(parsed);
			if (!again.equals(text)) {
				failures.add(where + ": '" + text + "' prints as '" + again + "'");
			}
		} catch (SyntaxError ex) {
			failures.add(where + ": '" + text + "': " + ex.describe());
		}
	}

	private static void checkMessage(String where, List<?> message, List<String> failures) {
		String text = RuleText.printMessage(message);
		try {
			List<Object> parsed = RuleText.parseMessage(text);
			List<Object> normalized = new ArrayList<>();
			for (Object part : message) {
				normalized.add(RuleText.normalize(part));
			}
			if (!same(normalized, parsed)) {
				failures.add(where + ": '" + text + "' reads back as " + Json.write(parsed).trim());
			}
		} catch (SyntaxError ex) {
			failures.add(where + ": '" + text + "': " + ex.describe());
		}
	}

	/**
	 * Whether two JSON values are the same, numbers compared by their value (an integer and a real
	 * with the same value are the same, as JSON does not tell them apart).
	 */
	public static boolean same(Object a, Object b) {
		if (a instanceof Number x && b instanceof Number y) {
			return x.doubleValue() == y.doubleValue();
		}
		if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
			if (!x.keySet().equals(y.keySet())) {
				return false;
			}
			for (Object key : x.keySet()) {
				if (!same(x.get(key), y.get(key))) {
					return false;
				}
			}
			return true;
		}
		if (a instanceof List<?> x && b instanceof List<?> y) {
			if (x.size() != y.size()) {
				return false;
			}
			for (int i = 0; i < x.size(); i++) {
				if (!same(x.get(i), y.get(i))) {
					return false;
				}
			}
			return true;
		}
		return a == null ? b == null : a.equals(b);
	}

	/**
	 * The canonical texts of the specification (<code>spec/rules.md</code> 4.2, 4.4, 4.8, 5).
	 */
	public void testCanonicalText() throws SyntaxError {
		assertCanonical("@hall.presence and (@hall.light or @hall.lux < $darkBelow)",
			"@hall.presence and (@hall.light or @hall.lux<$darkBelow)");
		assertCanonical("remaining(off) > 1min30s", "remaining( off )>90s");
		assertCanonical("not @a == @b", "not (@a == @b)");
		assertCanonical("-@x * @y", "(-@x) * @y");
		assertCanonical("-(@x * @y)", "-(@x*@y)");
		assertCanonical("@a - (@b - @c)", "@a - (@b - @c)");
		assertCanonical("@a - @b - @c", "(@a - @b) - @c");
		assertCanonical("@a or (@b or @c)", "@a or (@b or @c)");
		assertCanonical("(@a == @b) == @c", "(@a == @b) == @c");
		// A minus over a number literal folds, also through parentheses and chains (spec 4.8).
		assertCanonical("5", "-(-5)");
		assertCanonical("-5", "- 5");
		assertCanonical("@a - -5", "@a - -5");
		assertCanonical("-5", "-(5)");
		assertCanonical("-1.5", "-(1.5)");
		assertCanonical("-1h", "--(-1h)");
		assertCanonical("-(-@x)", "-(-@x)");
		// A time of day with a day of the week (spec 4.2).
		assertCanonical("time() == mon 07:30", "time()==mon 7:30");
		assertCanonical("sun 23:59:59", "sun 23:59:59");
		assertCanonical("running(mon)", "running( mon )");
		assertEquals(Long.valueOf(1), ((Map<?, ?>) RuleText.parse("mon 07:30").get("value")).get("dayOfWeek"));
		assertCanonical("(not @a) == @b", "(not @a) == @b");
		assertCanonical("@a and not @b", "@a and (not @b)");
		assertCanonical("not not @a", "not (not @a)");
		assertCanonical("1h30min", "90min");
		assertCanonical("0s", "0ms");
		assertCanonical("-1h30min", "-90min");
		assertCanonical("2d500ms", "2d500ms");
		assertCanonical("21.5", "21.50");
		assertCanonical("80.0", "80.0");
		assertCanonical("1.5e20", "150000000000000000000.0");
		assertCanonical("1.0e-5", "0.00001");
		assertCanonical("0.0001", "0.0001");
		assertCanonical("06:05", "6:05");
		assertCanonical("20:30:15", "20:30:15");
		assertCanonical("06:05", "06:05:00");
		assertCanonical("2026-10-17", "2026-10-17");
		assertCanonical("2026-10-17T20:30", "2026-10-17T20:30:00");
		assertCanonical("\"say \\\"hi\\\"\"", "\"say \\\"hi\\\"\"");
		assertCanonical("#ff8000", "#FF8000");
		assertCanonical("#ff8000c0", "#ff8000C0");
		assertCanonical("x\"0AFF\"", "x\"0aff\"");
		assertCanonical("x\"\"", "x\"\"");
		assertCanonical("@\"Living room light 1\"", "@\"Living room light 1\"");
		assertCanonical("@\"1/2/3\"", "@\"1/2/3\"");
		assertCanonical("@\"true\"", "@\"true\"");
		assertCanonical("@a.b - 1", "@a.b-1");
		assertCanonical("state()", "state( )");
		assertCanonical("state(\"stairs\")", "state(\"stairs\")");
		assertCanonical("if(@a, 1, 2)", "if(@a,1,2)");
		assertCanonical("@a % 3", "@a%3");
		assertCanonical("2026 - 10 - 17", "2026 - 10 - 17");
	}

	/**
	 * A parameter id that is no identifier (haumacher/ehome #110, homecontrol spec/rules.md 4.3) is
	 * written <code>$"max-helligkeit"</code>, quoted and escaped as <code>@"…"</code>; an identifier
	 * stays bare, a keyword is quoted.
	 */
	public void testParameterReferences() throws SyntaxError {
		assertEquals(Map.of("kind", "parameter", "id", "x"), RuleText.parse("$x"));
		assertEquals(Map.of("kind", "parameter", "id", "x-y"), RuleText.parse("$\"x-y\""));
		assertEquals("$x", RuleText.print(Map.of("kind", "parameter", "id", "x")));
		assertEquals("$\"x-y\"", RuleText.print(Map.of("kind", "parameter", "id", "x-y")));
		assertEquals("$\"not\"", RuleText.print(Map.of("kind", "parameter", "id", "not")));
		assertEquals("$\"2nd\"", RuleText.print(Map.of("kind", "parameter", "id", "2nd")));
		assertEquals("$\"a\\\"b\"", RuleText.print(Map.of("kind", "parameter", "id", "a\"b")));
		assertEquals("$_a1", RuleText.parameterReference("_a1"));
		assertEquals("$\"max-helligkeit\"", RuleText.parameterReference("max-helligkeit"));
		assertEquals("$\"true\"", RuleText.parameterReference("true"));
		assertEquals(Map.of("kind", "parameter", "id", "not"), RuleText.parse("$\"not\""));
		// A quoted identifier is printed bare.
		assertCanonical("$x", "$\"x\"");
		assertCanonical("@lamp.dim > $\"max-helligkeit\"", "@lamp.dim>$\"max-helligkeit\"");
		assertCanonical("$\"max-helligkeit\" - $max_helligkeit", "($\"max-helligkeit\") - $max_helligkeit");
		assertCanonical("min($\"a-b\", 3)", "min( $\"a-b\" ,3)");
		List<Object> message = RuleText.parseMessage("max {$\"max-helligkeit\"}");
		assertEquals(List.of("max ", Map.of("kind", "parameter", "id", "max-helligkeit")), message);
		assertEquals("max {$\"max-helligkeit\"}", RuleText.printMessage(message));
		assertSyntaxError("$\"open", 1, RuleText::parse);
		assertSyntaxError("$-x", 0, RuleText::parse);
	}

	public void testAst() throws SyntaxError {
		Map<String, Object> ast = RuleText.parse("remaining(off) > 1min30s");
		assertTrue(same(Map.of("kind", "binary", "op", ">",
			"left", Map.of("kind", "call", "function", "remaining", "args", List.of(Map.of("kind", "timer", "id", "off"))),
			"right", Map.of("kind", "literal", "type", "duration", "value", Long.valueOf(90000))), ast));
		assertEquals(Map.of("kind", "literal", "type", "integer", "value", Long.valueOf(-7)), RuleText.parse("-7"));
		assertEquals("real", RuleText.parse("-0.5").get("type"));
		assertEquals(Map.of("kind", "unary", "op", "-", "operand", Map.of("kind", "datapoint", "name", "x")),
			RuleText.parse("-@x"));
		assertEquals(Map.of("kind", "call", "function", "state", "args", List.of()), RuleText.parse("state()"));
	}

	public void testMessages() throws SyntaxError {
		List<Object> parts = RuleText.parseMessage("Light off after {$timeout} {{ok}}");
		assertEquals(List.of("Light off after ", Map.of("kind", "parameter", "id", "timeout"), " {ok}"), parts);
		assertEquals("Light off after {$timeout} {{ok}}", RuleText.printMessage(parts));
		assertEquals(List.of(Map.of("kind", "literal", "type", "string", "value", "a}b")), RuleText.parseMessage("{\"a}b\"}"));
		assertEquals(List.of(), RuleText.parseMessage(""));
		assertSyntaxError("a } b", 2, RuleText::parseMessage);
		assertSyntaxError("a {1 + } b", 7, RuleText::parseMessage);
		assertSyntaxError("a {1 + 2", 8, RuleText::parseMessage);
	}

	public void testValues() throws SyntaxError {
		assertEquals(Long.valueOf(180000), RuleText.parseValue("3min", Type.DURATION));
		assertEquals(Double.valueOf(80.0), RuleText.parseValue("80", Type.REAL));
		assertEquals(Long.valueOf(3), RuleText.parseValue("3", Type.ENUMERATION));
		assertEquals(Map.of("hour", Long.valueOf(6), "minute", Long.valueOf(30), "second", Long.valueOf(0)),
			RuleText.parseValue("06:30", Type.TIME_OF_DAY));
		assertEquals("3min", RuleText.printValue(Long.valueOf(180000), Type.DURATION));
		assertEquals("80.0", RuleText.printValue(Double.valueOf(80), Type.REAL));
		assertEquals("80.0", RuleText.printValue(Long.valueOf(80), Type.REAL));
		assertEquals("3", RuleText.printValue(Long.valueOf(3), Type.ENUMERATION));
		try {
			RuleText.parseValue("3min", Type.REAL);
			fail("a duration is no real");
		} catch (SyntaxError ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("real"));
		}
		try {
			RuleText.parseValue("@a", Type.REAL);
			fail("no literal");
		} catch (SyntaxError ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("literal"));
		}
	}

	/**
	 * The lexer's disambiguation (the closing comment of the grammar) and errors with their offsets.
	 */
	public void testSyntaxErrors() {
		assertSyntaxError("30 min", 3, RuleText::parse);
		assertSyntaxError("1e3", 1, RuleText::parse);
		assertSyntaxError("@a < @b < @c", 8, RuleText::parse);
		assertSyntaxError("@a = 1", 3, RuleText::parse);
		assertSyntaxError("!@a", 0, RuleText::parse);
		assertSyntaxError("1 +", 3, RuleText::parse);
		assertSyntaxError("(1 + 2", 6, RuleText::parse);
		assertSyntaxError("foo", 3, RuleText::parse);
		assertSyntaxError("running(1)", 8, RuleText::parse);
		assertSyntaxError("1min30min", 6, RuleText::parse);
		assertSyntaxError("30m", 2, RuleText::parse);
		assertSyntaxError("#fff", 0, RuleText::parse);
		assertSyntaxError("\"open", 0, RuleText::parse);
		assertSyntaxError("\"a\\x\"", 2, RuleText::parse);
		assertSyntaxError("9007199254740992", 0, RuleText::parse);
		assertSyntaxError("@", 0, RuleText::parse);
		assertSyntaxError("1 2", 2, RuleText::parse);
		assertSyntaxError("", 0, RuleText::parse);
		try {
			RuleText.parse("@a <");
			fail();
		} catch (SyntaxError ex) {
			assertEquals("syntax error at character 5: an operand expected, not end of the text", ex.describe());
		}
	}

	private interface Parse {
		Object parse(String text) throws SyntaxError;
	}

	private static void assertSyntaxError(String text, int offset, Parse parser) {
		try {
			Object result = parser.parse(text);
			fail("'" + text + "' must be refused, read as " + result);
		} catch (SyntaxError ex) {
			assertEquals("'" + text + "': " + ex.getMessage(), offset, ex.getOffset());
		}
	}

	private static void assertCanonical(String expected, String text) throws SyntaxError {
		Map<String, Object> ast = RuleText.parse(text);
		assertEquals(text, expected, RuleText.print(ast));
		assertTrue(expected, same(ast, RuleText.parse(expected)));
	}

	public static Test suite() {
		return new TestSuite(TestRuleText.class);
	}

}
