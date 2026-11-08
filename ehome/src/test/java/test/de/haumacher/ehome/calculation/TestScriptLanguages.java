/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.calculation;

import java.util.LinkedHashMap;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.calculation.CalculationException;
import de.haumacher.ehome.calculation.JavaScriptLanguage;
import de.haumacher.ehome.calculation.ScriptLanguage;
import de.haumacher.ehome.calculation.VbScriptLanguage;

/**
 * The script languages of parameter calculations (haumacher/ehome #123) without a device: the subset
 * of VBScript the interpreter supports, with the numbers of VBScript, and the JavaScript sandbox.
 */
@SuppressWarnings("javadoc")
public class TestScriptLanguages extends TestCase {

	private static final ScriptLanguage VB = VbScriptLanguage.INSTANCE;

	private static final ScriptLanguage JS = JavaScriptLanguage.INSTANCE;

	/** The demo's s2hms, with names as ETS gives them without aliases. */
	public void testS2hms() throws CalculationException {
		String lr = "h = (ValueDisplay \\ 3600)\r\nmin = (ValueDisplay - (3600 * h)) \\ 60\r\n"
			+ "s = ValueDisplay - (3600 * h) - (60 * min)";
		Map<String, Object> result = VB.run(null, lr, vars("ValueDisplay", 3725.0, "h", 0.0, "min", 0.0, "s", 0.0));
		assertEquals(1.0, result.get("h"));
		assertEquals(2.0, result.get("min"));
		assertEquals(5.0, result.get("s"));
		Map<String, Object> back = VB.run(null, "ValueDisplay = (3600 * h) + (60 * min) + s",
			vars("ValueDisplay", 0.0, "h", 2.0, "min", 0.0, "s", 30.0));
		assertEquals(7230.0, back.get("ValueDisplay"));
	}

	/** \ and Mod round their operands half to even before dividing; / does not. */
	public void testIntegerDivision() throws CalculationException {
		assertEquals(2.0, eval("7 \\ 3"));
		assertEquals(-2.0, eval("-7 \\ 3"));
		// 2.5 rounds to 2, 3.5 to 4.
		assertEquals(1.0, eval("2.5 \\ 2"));
		assertEquals(2.0, eval("3.5 \\ 2"));
		assertEquals(1.0, eval("7 Mod 3"));
		assertEquals(-1.0, eval("-7 MOD 3"));
		assertEquals(2.5, eval("5 / 2"));
		assertEquals(-4.0, eval("-2 ^ 2"));
		assertEquals(14.0, eval("2 + 3 * 4"));
		assertEquals(3.0, eval("Int(3.7)"));
		assertEquals(-4.0, eval("Int(-3.7)"));
		assertEquals(-3.0, eval("Fix(-3.7)"));
		assertEquals(2.0, eval("Round(2.5)"));
		assertEquals(4.0, eval("CInt(3.5)"));
		assertEquals(31.0, eval("&H1F"));
	}

	public void testConditionals() throws CalculationException {
		String code = "If a > 10 Then\n  b = 1\nElseIf a > 5 Then\n  b = 2\nElse\n  b = 3\nEnd If";
		assertEquals(1.0, VB.run(null, code, vars("a", 11.0, "b", 0.0)).get("b"));
		assertEquals(2.0, VB.run(null, code, vars("a", 6.0, "b", 0.0)).get("b"));
		assertEquals(3.0, VB.run(null, code, vars("a", 1.0, "b", 0.0)).get("b"));
		String single = "if a = 1 and not b <> 0 then c = 5 : d = 6 else c = 7";
		Map<String, Object> taken = VB.run(null, single, vars("a", 1.0, "b", 0.0, "c", 0.0, "d", 0.0));
		assertEquals(5.0, taken.get("c"));
		assertEquals(6.0, taken.get("d"));
		Map<String, Object> other = VB.run(null, single, vars("a", 2.0, "b", 0.0, "c", 0.0, "d", 0.0));
		assertEquals(7.0, other.get("c"));
		assertEquals("A variable no statement assigns keeps its value.", 0.0, other.get("d"));
		// Comments, Dim, a continued line, keywords in any case.
		assertEquals(3.0, VB.run(null, "' Kommentar\nDIM x\nx = 1 + _\n 2 ' Rest\nREM Ende", vars("x", 0.0)).get("x"));
		assertEquals("True is -1.", -1.0, VB.run(null, "x = (1 < 2)", vars("x", 0.0)).get("x"));
	}

	public void testUnsupported() throws CalculationException {
		assertNull(VB.check(null, "a = b \\ 2"));
		assertContains("Select", VB.check(null, "Select Case a\nCase 1\nb = 1\nEnd Select"));
		assertContains("For", VB.check(null, "For i = 1 To 3\nb = i\nNext"));
		assertContains("MsgBox", VB.check(null, "x = MsgBox(1)"));
		assertContains("End If", VB.check(null, "If a Then\nb = 1\nEnd Sub"));
		assertContains("If without End If", VB.check(null, "If a Then\nb = 1"));
		try {
			VB.run(null, "b = a + 1", vars("b", 0.0));
			fail("Unbound name.");
		} catch (CalculationException ex) {
			assertContains("'a'", ex.getMessage());
		}
		try {
			VB.run(null, "b = 1 \\ 0", vars("b", 0.0));
			fail("Division by zero.");
		} catch (CalculationException ex) {
			assertContains("division by zero", ex.getMessage());
		}
	}

	/** Procedures of the code and of the library (ETS: Static/Script). */
	public void testProcedures() throws CalculationException {
		String library = "Function InSekunden(m)\n  InSekunden = m * 60\nEnd Function\n"
			+ "Function Begrenzt(x, max)\n  Begrenzt = x\n  If x > max Then\n    Begrenzt = max\n    Exit Function\n"
			+ "  End If\nEnd Function\nSub Merke(x)\n  letzter = x\nEnd Sub\nConst Basis = 10";
		Map<String, Object> result = VB.run(library, "s = InSekunden(m) + Basis : b = Begrenzt(m, 3) : Call Merke(m)",
			vars("m", 5.0, "s", 0.0, "b", 0.0, "letzter", 0.0));
		assertEquals(310.0, result.get("s"));
		assertEquals(3.0, result.get("b"));
		assertEquals("A Sub assigns the variable of the script.", 5.0, result.get("letzter"));
		// A procedure of the code itself, defined after its use; a variable local to the call.
		assertEquals(4.0, VB.run(null, "r = Doppelt(2)\nFunction Doppelt(a)\n  t = a * 2\n  Doppelt = t\nEnd Function",
			vars("r", 0.0)).get("r"));
		assertNull(VB.check(library, "s = InSekunden(m)"));
		assertContains("InSekunden", VB.check(null, "s = InSekunden(m)"));
		assertContains("deeper", runError("r = F(1)\nFunction F(a)\n  F = F(a)\nEnd Function"));
		// A library in another language is left out.
		assertNull(VB.check("function f(x) { return x; }", "a = 1"));
		assertNotNull(VB.checkFunction(library, "InSekunden"));
	}

	/** A function of the library as the transformation: function(input, output, context). */
	public void testJavaScriptFunction() throws CalculationException {
		String library = "function lr(input, output, context) { output.r = input.l * context.f; }\n"
			+ "function text(input, output, context) { output.r = context; }";
		Map<String, Object> result = JS.call(library, "lr", vars("l", 3.0), vars("r", 0.0, "s", 7.0), "{\"f\": 2}");
		assertEquals(6.0, ((Number) result.get("r")).doubleValue());
		assertEquals("Not assigned: kept.", 7.0, ((Number) result.get("s")).doubleValue());
		assertEquals("abc", JS.call(library, "text", vars(), vars("r", 0.0), "abc").get("r"));
		assertNull(JS.checkFunction(library, "lr"));
		assertContains("nichtda", JS.checkFunction(library, "nichtda"));
	}

	private static String runError(String code) {
		try {
			VB.run(null, code, vars("r", 0.0));
			fail("Not refused: " + code);
			return null;
		} catch (CalculationException ex) {
			return ex.getMessage();
		}
	}

	public void testJavaScript() throws CalculationException {
		Map<String, Object> result = JS.run(null, "p2 = p1 / p3; if (p2 < 1) p2 = 1; p4 = p1 / p3; if (p4 < 1) p4 = 0",
			vars("p1", 55.0, "p3", 10.0, "p2", 0.0, "p4", 0.0));
		assertEquals(5.5, ((Number) result.get("p2")).doubleValue());
		assertEquals(5.5, ((Number) result.get("p4")).doubleValue());
		// A function of the program's script.
		assertEquals(2.0, ((Number) JS.run("function verdoppeln(x) { return x * 2; }",
			"if (t == 3) { s = verdoppeln(1) }", vars("t", 3.0, "s", 0.0)).get("s")).doubleValue());
		assertEquals("Not assigned: kept.", 0.0,
			((Number) JS.run(null, "if (t == 3) { s = 1 }", vars("t", 2.0, "s", 0.0)).get("s")).doubleValue());
		assertNotNull(JS.check(null, "if (a { b = 1 }"));
	}

	/** No Java, no host objects, no endless loop. */
	public void testSandbox() {
		for (String code : new String[] { "x = java.lang.System.exit(0)", "x = Packages.java.io.File",
			"x = new java.io.File('/')", "x = Java.type('java.io.File')", "x = undefinedName + 1" }) {
			try {
				JS.run(null, code, vars("x", 0.0));
				fail("Not refused: " + code);
			} catch (CalculationException ex) {
				// Expected.
			}
		}
		long start = System.currentTimeMillis();
		try {
			JS.run(null, "while (true) {}", vars("x", 0.0));
			fail("Endless loop not stopped.");
		} catch (CalculationException ex) {
			assertContains("stopped", ex.getMessage());
		}
		assertTrue(System.currentTimeMillis() - start < JavaScriptLanguage.TIME_LIMIT_MS + 1000);
		try {
			JS.run(null, "function f() { return f(); } x = f();", vars("x", 0.0));
			fail("Endless recursion not stopped.");
		} catch (CalculationException ex) {
			// Expected.
		}
		try {
			JS.run(null, "try { while (true) {} } catch (e) {} x = 1;", vars("x", 0.0));
			fail("The limit was caught by the script.");
		} catch (CalculationException ex) {
			assertContains("stopped", ex.getMessage());
		}
	}

	private static Object eval(String expression) throws CalculationException {
		return VB.run(null, "r = " + expression, vars("r", 0.0)).get("r");
	}

	private static Map<String, Object> vars(Object... pairs) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int n = 0; n < pairs.length; n += 2) {
			result.put((String) pairs[n], pairs[n + 1]);
		}
		return result;
	}

	private static void assertContains(String part, String text) {
		assertNotNull("Expected a text containing " + part, text);
		assertTrue(text, text.contains(part));
	}

	public static Test suite() {
		return new TestSuite(TestScriptLanguages.class);
	}

}
