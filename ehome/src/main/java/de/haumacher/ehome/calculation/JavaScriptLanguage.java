/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.json.JsonParser;

/**
 * The scripts of parameter calculations in JavaScript (ETS: <code>Language="JavaScript"</code>),
 * evaluated by Mozilla Rhino in a sandbox.
 *
 * <ul>
 * <li>The scope has the standard objects of ECMAScript only (<code>initSafeStandardObjects</code>:
 * no <code>Packages</code>, no <code>java</code>, no access to Java classes), and a class shutter
 * refuses every Java class besides.</li>
 * <li>The code is interpreted (not compiled to Java classes), so that every run counts its
 * instructions: a run is stopped after {@value #INSTRUCTION_LIMIT} instructions or
 * {@value #TIME_LIMIT_MS} ms, whichever comes first (a script that loops endlessly), with a
 * {@link CalculationException} naming the limit.</li>
 * <li>Inline code: the variables are globals of the scope: the functions of the library
 * (<code>Static/Script</code>, a library that is no JavaScript left out) are loaded first, then the
 * parameters set, then the code runs. A variable the code reads but no parameter defines is an error
 * naming it (a <code>ReferenceError</code>).</li>
 * <li>A function of the library: called as <code>function(input, output, context)</code> with an
 * object of the source parameters, one of the target parameters (their current values) the function
 * sets, and the parameters of the transformation (a JSON value, else the text).</li>
 * </ul>
 */
public final class JavaScriptLanguage implements ScriptLanguage {

	/** The most instructions a run may take. */
	public static final int INSTRUCTION_LIMIT = 2_000_000;

	/** The longest time a run may take, in milliseconds. */
	public static final long TIME_LIMIT_MS = 2000;

	/** Instructions between two checks of the limits. */
	private static final int OBSERVER_THRESHOLD = 10_000;

	/** The deepest nesting of calls (a script recursing endlessly). */
	private static final int STACK_DEPTH = 500;

	/** The single instance. */
	public static final JavaScriptLanguage INSTANCE = new JavaScriptLanguage();

	private static final ContextFactory FACTORY = new SandboxFactory();

	private JavaScriptLanguage() {
		// Singleton.
	}

	@Override
	public String check(String library, String code) {
		try (Context cx = FACTORY.enterContext()) {
			cx.compileString(code == null ? "" : code, "calculation", 1, null);
			return null;
		} catch (RhinoException ex) {
			return message(ex);
		}
	}

	@Override
	public String checkFunction(String library, String function) {
		try (Context cx = FACTORY.enterContext()) {
			start(cx);
			ScriptableObject scope = scope(cx, library);
			if (ScriptableObject.getProperty(scope, function.trim()) instanceof Function) {
				return null;
			}
			return "the script defines no function " + function.trim();
		} catch (RhinoException ex) {
			return message(ex);
		} catch (LimitExceeded ex) {
			return ex.getMessage();
		}
	}

	@Override
	public Map<String, Object> call(String library, String function, Map<String, Object> inputs,
			Map<String, Object> outputs, String parameters) throws CalculationException {
		try (Context cx = FACTORY.enterContext()) {
			start(cx);
			ScriptableObject scope = scope(cx, library);
			if (!(ScriptableObject.getProperty(scope, function.trim()) instanceof Function callee)) {
				throw new CalculationException("the script defines no function " + function.trim());
			}
			Scriptable input = cx.newObject(scope);
			for (Map.Entry<String, Object> entry : inputs.entrySet()) {
				ScriptableObject.putProperty(input, entry.getKey(), entry.getValue());
			}
			Scriptable output = cx.newObject(scope);
			for (Map.Entry<String, Object> entry : outputs.entrySet()) {
				ScriptableObject.putProperty(output, entry.getKey(), entry.getValue());
			}
			Object context = parameters == null ? Undefined.instance : parameters;
			if (parameters != null && !parameters.isBlank()) {
				try {
					context = new JsonParser(cx, scope).parseValue(parameters);
				} catch (JsonParser.ParseException ex) {
					// Not JSON: the text itself.
				}
			}
			callee.call(cx, scope, scope, new Object[] { input, output, context });
			Map<String, Object> result = new LinkedHashMap<>();
			for (String name : outputs.keySet()) {
				result.put(name, value(ScriptableObject.getProperty(output, name)));
			}
			return result;
		} catch (RhinoException ex) {
			throw new CalculationException(message(ex));
		} catch (LimitExceeded ex) {
			throw new CalculationException(ex.getMessage());
		}
	}

	private static void start(Context cx) {
		SandboxContext sandbox = (SandboxContext) cx;
		sandbox._start = System.currentTimeMillis();
		sandbox._instructions = 0;
	}

	/**
	 * A new scope of the standard objects with the functions of the library, if it is JavaScript (a
	 * library in another language is left out).
	 */
	private static ScriptableObject scope(Context cx, String library) {
		ScriptableObject scope = cx.initSafeStandardObjects(null, true);
		if (library != null && !library.isBlank()) {
			Script compiled;
			try {
				compiled = cx.compileString(library, "script", 1, null);
			} catch (EvaluatorException ex) {
				// No JavaScript.
				return scope;
			}
			compiled.exec(cx, scope, scope);
		}
		return scope;
	}

	@Override
	public Map<String, Object> run(String library, String code, Map<String, Object> variables)
			throws CalculationException {
		try (Context cx = FACTORY.enterContext()) {
			start(cx);
			ScriptableObject scope = scope(cx, library);
			for (Map.Entry<String, Object> entry : variables.entrySet()) {
				ScriptableObject.putProperty(scope, entry.getKey(), entry.getValue());
			}
			cx.evaluateString(scope, code == null ? "" : code, "calculation", 1, null);
			Map<String, Object> result = new LinkedHashMap<>();
			for (String name : variables.keySet()) {
				result.put(name, value(ScriptableObject.getProperty(scope, name)));
			}
			return result;
		} catch (RhinoException ex) {
			throw new CalculationException(message(ex));
		} catch (LimitExceeded ex) {
			throw new CalculationException(ex.getMessage());
		}
	}

	private static Object value(Object value) {
		if (value == Scriptable.NOT_FOUND || value instanceof Undefined || value == null) {
			return null;
		}
		if (value instanceof CharSequence text) {
			return text.toString();
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value;
		}
		// An object or a function: its text, which no parameter type takes as a number.
		return Context.toString(value);
	}

	private static String message(RhinoException ex) {
		String details = ex.details();
		int line = ex.lineNumber();
		return line > 0 ? details + " (line " + line + ")" : details;
	}

	/**
	 * The {@link Context} of a run, counting its instructions.
	 */
	private static final class SandboxContext extends Context {

		long _start;

		long _instructions;

		SandboxContext(ContextFactory factory) {
			super(factory);
		}
	}

	/**
	 * Stops a run beyond its limits; an {@link Error}, so that the script cannot catch it.
	 */
	private static final class LimitExceeded extends Error {

		LimitExceeded(String message) {
			super(message, null, false, false);
		}
	}

	/**
	 * Creates the sandboxed contexts.
	 */
	private static final class SandboxFactory extends ContextFactory {

		@Override
		protected Context makeContext() {
			SandboxContext result = new SandboxContext(this);
			result.setLanguageVersion(Context.VERSION_ES6);
			result.setInterpretedMode(true);
			result.setInstructionObserverThreshold(OBSERVER_THRESHOLD);
			result.setMaximumInterpreterStackDepth(STACK_DEPTH);
			result.setClassShutter(className -> false);
			return result;
		}

		@Override
		protected void observeInstructionCount(Context cx, int instructionCount) {
			SandboxContext sandbox = (SandboxContext) cx;
			sandbox._instructions += instructionCount;
			if (sandbox._instructions > INSTRUCTION_LIMIT) {
				throw new LimitExceeded("stopped after " + INSTRUCTION_LIMIT + " instructions (an endless loop?)");
			}
			if (System.currentTimeMillis() - sandbox._start > TIME_LIMIT_MS) {
				throw new LimitExceeded("stopped after " + TIME_LIMIT_MS + " ms (an endless loop?)");
			}
		}
	}

}
