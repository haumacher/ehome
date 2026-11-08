/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.RuleText;

/**
 * Probe of the quoted parameter reference <code>$"…"</code> (haumacher/ehome #110) composed with the
 * rest of the rule text: canonical printing of a needlessly quoted identifier, references inside
 * arithmetic and log messages, string literals that look like references, and an empty reference.
 */
@SuppressWarnings("javadoc")
public class TestParameterReferenceProbe extends TestCase {

	private static String canonical(String text) throws RuleText.SyntaxError {
		return RuleText.print(RuleText.parse(text));
	}

	public void testNeedlessQuotesDisappear() throws Exception {
		assertEquals("$level + 1", canonical("$\"level\" + 1"));
	}

	public void testQuotedInArithmetic() throws Exception {
		assertEquals("$\"max-helligkeit\" - 1", canonical("$\"max-helligkeit\"-1"));
		assertEquals("@\"1/2/3\" < $\"max-helligkeit\" and $level > 0",
			canonical("@\"1/2/3\" < $\"max-helligkeit\" and $level > 0"));
	}

	public void testLeadingDigit() throws Exception {
		assertEquals("$\"2nd\"", canonical("$\"2nd\""));
	}

	public void testKeywordQuoted() throws Exception {
		assertEquals("$\"not\"", canonical("$\"not\""));
	}

	public void testStringLiteralUntouched() throws Exception {
		assertEquals("\"$\\\"max-helligkeit\\\"\"", canonical("\"$\\\"max-helligkeit\\\"\""));
	}

	public void testMessage() throws Exception {
		String message = "Level {$\"max-helligkeit\"} of {$\"level\"}";
		assertEquals("Level {$\"max-helligkeit\"} of {$level}",
			RuleText.printMessage(RuleText.parseMessage(message)));
	}

	/**
	 * An empty quoted name is no syntax error, as for <code>@""</code>: the loader refuses it as an
	 * unknown reference. It prints quoted, so it does not turn into a bare <code>$</code>.
	 */
	public void testEmptyPrintsQuoted() throws Exception {
		assertEquals("$\"\" > 1", canonical("$\"\" > 1"));
	}

	public static Test suite() {
		return new TestSuite(TestParameterReferenceProbe.class);
	}

}
