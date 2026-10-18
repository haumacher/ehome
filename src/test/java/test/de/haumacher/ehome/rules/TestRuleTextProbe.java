/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import java.util.List;
import java.util.Map;
import java.util.Random;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;

/**
 * Probe of the rule text syntax (haumacher/ehome #57) with expressions nobody wrote by hand: random
 * expressions over operators, literals and datapoint references print to a text that parses to the
 * same tree; parentheses the precedence makes redundant do not change the tree; random text is either
 * an expression or a {@link SyntaxError}, never another exception.
 */
@SuppressWarnings("javadoc")
public class TestRuleTextProbe extends TestCase {

	private static final List<String> BINARY = List.of("+", "-", "*", "/", "and", "or", "==", "!=", "<", "<=", ">", ">=");

	private static final List<String> ATOMS =
		List.of("1", "2.5", "-3", "true", "false", "@\"0/0/1\"", "@\"1/2/3\"", "\"text\"", "0", "100");

	public void testRandomExpressions() throws Exception {
		Random random = new Random(7);
		int parsed = 0;
		for (int n = 0; n < 3000; n++) {
			String text = expression(random, 4);
			Map<String, Object> ast;
			try {
				ast = RuleText.parse(text);
			} catch (SyntaxError ex) {
				// The generator may build what the grammar does not allow (e.g. chained comparisons).
				continue;
			}
			parsed++;
			String printed = RuleText.print(ast);
			assertEquals(text + " -> " + printed, ast, RuleText.parse(printed));
			assertEquals(printed, RuleText.print(RuleText.parse(printed)));
		}
		assertTrue(String.valueOf(parsed), parsed > 1000);
	}

	public void testPrecedence() throws Exception {
		assertEquals(RuleText.parse("1 + (2 * 3)"), RuleText.parse("1 + 2 * 3"));
		assertEquals(RuleText.parse("(1 - 2) - 3"), RuleText.parse("1 - 2 - 3"));
		assertEquals(RuleText.parse("true or (false and true)"), RuleText.parse("true or false and true"));
		assertFalse(RuleText.parse("(1 + 2) * 3").equals(RuleText.parse("1 + 2 * 3")));
	}

	public void testRandomText() {
		Random random = new Random(11);
		String alphabet = "@\"0/1 ()+-*/<>=!andornot.,{}[]:;truefalse12345abc";
		for (int n = 0; n < 20000; n++) {
			StringBuilder text = new StringBuilder();
			int length = random.nextInt(25);
			for (int k = 0; k < length; k++) {
				text.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}
			try {
				RuleText.parse(text.toString());
			} catch (SyntaxError ex) {
				// Refused.
			} catch (RuntimeException ex) {
				fail("'" + text + "' fails with " + ex);
			}
		}
	}

	private static String expression(Random random, int depth) {
		if (depth == 0 || random.nextInt(3) == 0) {
			return ATOMS.get(random.nextInt(ATOMS.size()));
		}
		switch (random.nextInt(4)) {
			case 0:
				return "not (" + expression(random, depth - 1) + ")";
			case 1:
				return "(" + expression(random, depth - 1) + ")";
			default:
				return expression(random, depth - 1) + " " + BINARY.get(random.nextInt(BINARY.size())) + " "
					+ expression(random, depth - 1);
		}
	}

	public static Test suite() {
		return new TestSuite(TestRuleTextProbe.class);
	}

}
