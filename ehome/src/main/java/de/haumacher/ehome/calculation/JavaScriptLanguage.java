/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.PropertyGet;
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
 * <li>A validation of the library (haumacher/ehome #124): called as
 * <code>function(input, changed, prevValue, context)</code> with an object of its parameters (the
 * changed one with its new value), the name of the changed one, its value before and the parameters of
 * the validation (a JSON value, else the text). <code>true</code>, or anything else that is true and no
 * text, accepts; a text refuses with it; anything false refuses without a text.</li>
 * <li>The handler of a button of the parameter dialog (haumacher/ehome #158): called as
 * <code>function(device, online, progress, context)</code>, see
 * {@link #handle(String, String, ButtonDevice, String)}. <code>device</code> provides
 * {@link #DEVICE_MEMBERS} and a parameter {@link #PARAMETER_MEMBERS}, both in the spellings the
 * programs in the field use; <code>progress</code> provides nothing, <code>online</code> is
 * <code>false</code> (E-Home does not talk to the device while a person edits it). A member E-Home
 * does not provide stops the run with the reason naming it, which the script cannot catch.</li>
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

	/**
	 * The members of <code>device</code> a button's handler may use: a parameter by its ID (MDT
	 * <code>BE-GTSx6Tx-01S</code>: <code>device.GetParameterById("MD-1_P-80_R-2")</code>) or by its name
	 * (MDT <code>GTL-xxxx-02S</code>, <code>BWM-xxxxx-03S</code>:
	 * <code>device.getParameterByName(…)</code>).
	 */
	public static final Set<String> DEVICE_MEMBERS =
		Set.of("GetParameterById", "getParameterById", "GetParameterByName", "getParameterByName");

	/** The members of a parameter a button's handler may use: its value, read and set. */
	public static final Set<String> PARAMETER_MEMBERS = Set.of("Value", "value");

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
			callee.call(cx, scope, scope, new Object[] { input, output, context(cx, scope, parameters) });
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

	@Override
	public String checkValidation(String library, String function) {
		try (Context cx = FACTORY.enterContext()) {
			start(cx);
			if (library != null && !library.isBlank()) {
				try {
					cx.compileString(library, "script", 1, null);
				} catch (EvaluatorException ex) {
					return message(ex);
				}
			}
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
	public String validate(String library, String function, Map<String, Object> input, String changed,
			Object previous, String parameters) throws CalculationException {
		try (Context cx = FACTORY.enterContext()) {
			start(cx);
			ScriptableObject scope = scope(cx, library);
			if (!(ScriptableObject.getProperty(scope, function.trim()) instanceof Function callee)) {
				throw new CalculationException("the script defines no function " + function.trim());
			}
			Scriptable values = cx.newObject(scope);
			for (Map.Entry<String, Object> entry : input.entrySet()) {
				ScriptableObject.putProperty(values, entry.getKey(), entry.getValue());
			}
			Object result = callee.call(cx, scope, scope,
				new Object[] { values, changed, previous, context(cx, scope, parameters) });
			if (result instanceof CharSequence text) {
				return text.toString();
			}
			return Context.toBoolean(result) ? null : "";
		} catch (RhinoException ex) {
			throw new CalculationException(message(ex));
		} catch (LimitExceeded ex) {
			throw new CalculationException(ex.getMessage());
		}
	}

	/**
	 * The parameters of a transformation or validation as the argument <code>context</code>: the value of
	 * a JSON text, else the text, <code>undefined</code> for none.
	 */
	private static Object context(Context cx, Scriptable scope, String parameters) {
		if (parameters == null) {
			return Undefined.instance;
		}
		if (!parameters.isBlank()) {
			try {
				return new JsonParser(cx, scope).parseValue(parameters);
			} catch (JsonParser.ParseException ex) {
				// Not JSON: the text itself.
			}
		}
		return parameters;
	}

	/**
	 * Why the function of the given name cannot be called as the handler of a button (haumacher/ehome
	 * #158): the script is not JavaScript (its syntax error), it defines no such function, or the
	 * function uses a member of <code>device</code>, of a parameter or of <code>progress</code> E-Home
	 * does not provide, or talks to the device through <code>online</code>; <code>null</code> if it can.
	 *
	 * <p>
	 * The members are found in the text of the function (<code>device.X</code>,
	 * <code>device.GetParameterById(…).X</code>, <code>progress.X</code>, <code>online.X</code>); a
	 * member reached otherwise (a parameter in a variable, a function the handler calls) is refused
	 * when the handler runs.
	 * </p>
	 */
	public String checkHandler(String library, String function) {
		if (library == null || library.isBlank()) {
			return "the program has no script";
		}
		String name = function.trim();
		try (Context cx = FACTORY.enterContext()) {
			start(cx);
			try {
				cx.compileString(library, "script", 1, null);
			} catch (EvaluatorException ex) {
				return "the script is not JavaScript: " + message(ex);
			}
			ScriptableObject scope = scope(cx, library);
			if (!(ScriptableObject.getProperty(scope, name) instanceof Function)) {
				return "the script defines no function " + name;
			}
		} catch (RhinoException ex) {
			return message(ex);
		} catch (LimitExceeded ex) {
			return ex.getMessage();
		}
		return unsupportedMember(library, name);
	}

	/**
	 * The first member of the arguments the handler of the given name uses that E-Home does not
	 * provide, as the reason a person reads; <code>null</code> if there is none (or the function is no
	 * declaration of the script, whose text is then not checked).
	 */
	private static String unsupportedMember(String library, String function) {
		CompilerEnvirons environment = new CompilerEnvirons();
		environment.setLanguageVersion(Context.VERSION_ES6);
		AstRoot root;
		try {
			root = new Parser(environment).parse(library, "script", 1);
		} catch (RhinoException ex) {
			return message(ex);
		}
		FunctionNode[] handler = new FunctionNode[1];
		root.visit(node -> {
			if (node instanceof FunctionNode candidate && candidate.getFunctionName() != null
				&& function.equals(candidate.getFunctionName().getIdentifier())) {
				// The last declaration counts, as in the script.
				handler[0] = candidate;
			}
			return true;
		});
		if (handler[0] == null) {
			return null;
		}
		List<AstNode> params = handler[0].getParams();
		String device = param(params, 0);
		String online = param(params, 1);
		String progress = param(params, 2);
		String[] result = new String[1];
		handler[0].getBody().visit(node -> {
			if (result[0] != null || !(node instanceof PropertyGet access)) {
				return result[0] == null;
			}
			String member = access.getProperty().getIdentifier();
			AstNode target = access.getTarget();
			if (target instanceof Name owner) {
				String ownerName = owner.getIdentifier();
				if (ownerName.equals(device) && !DEVICE_MEMBERS.contains(member)) {
					result[0] = "the handler uses " + ownerName + "." + member + ", which E-Home does not provide";
				} else if (ownerName.equals(online)) {
					result[0] = "the handler talks to the device (" + ownerName + "." + member
						+ "), which E-Home does not do";
				} else if (ownerName.equals(progress)) {
					result[0] = "the handler uses " + ownerName + "." + member + ", which E-Home does not provide";
				}
			} else if (target instanceof FunctionCall call && call.getTarget() instanceof PropertyGet lookup
				&& lookup.getTarget() instanceof Name owner && owner.getIdentifier().equals(device)
				&& DEVICE_MEMBERS.contains(lookup.getProperty().getIdentifier())
				&& !PARAMETER_MEMBERS.contains(member)) {
				result[0] = "the handler uses " + owner.getIdentifier() + "." + lookup.getProperty().getIdentifier()
					+ "(…)." + member + ", which E-Home does not provide";
			}
			return result[0] == null;
		});
		return result[0];
	}

	private static String param(List<AstNode> params, int index) {
		return index < params.size() && params.get(index) instanceof Name name ? name.getIdentifier() : null;
	}

	/**
	 * Calls a function of the library as the handler of a button (haumacher/ehome #158):
	 * <code>function(device, online, progress, context)</code>.
	 *
	 * @param device
	 *        What the handler reads and sets of the device, the object <code>device</code>.
	 * @param parameters
	 *        The parameters of the handler (ETS: <code>EventHandlerParameters</code>), as
	 *        <code>context</code>: the value of a JSON text, else the text; <code>null</code> for none.
	 * @throws CalculationException
	 *         If the function cannot be called, fails, runs beyond the limits or uses a member E-Home
	 *         does not provide.
	 */
	public void handle(String library, String function, ButtonDevice device, String parameters)
			throws CalculationException {
		try (Context cx = FACTORY.enterContext()) {
			start(cx);
			ScriptableObject scope = scope(cx, library);
			if (!(ScriptableObject.getProperty(scope, function.trim()) instanceof Function callee)) {
				throw new CalculationException("the script defines no function " + function.trim());
			}
			callee.call(cx, scope, scope, new Object[] { new DeviceObject(scope, device), Boolean.FALSE,
				new Members(scope, "progress"), context(cx, scope, parameters) });
		} catch (RhinoException ex) {
			throw new CalculationException(message(ex));
		} catch (LimitExceeded | Refused ex) {
			throw new CalculationException(ex.getMessage());
		}
	}

	/**
	 * The value of the given JSON text as Java values (a {@link Map} for an object, a {@link List} for an
	 * array, a {@link Double}, a {@link String}, a {@link Boolean}, <code>null</code>); the text itself if
	 * it is no JSON, <code>null</code> for none.
	 */
	public static Object json(String text) {
		if (text == null) {
			return null;
		}
		try (Context cx = FACTORY.enterContext()) {
			start(cx);
			ScriptableObject scope = cx.initSafeStandardObjects(null, true);
			return java(new JsonParser(cx, scope).parseValue(text));
		} catch (JsonParser.ParseException | RhinoException ex) {
			return text;
		}
	}

	private static Object java(Object value) {
		if (value instanceof NativeArray array) {
			List<Object> result = new ArrayList<>();
			for (long n = 0, cnt = array.getLength(); n < cnt; n++) {
				result.add(java(array.get((int) n, array)));
			}
			return result;
		}
		if (value instanceof Scriptable object) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (Object id : object.getIds()) {
				Object member = id instanceof Number index ? object.get(index.intValue(), object)
					: object.get(id.toString(), object);
				result.put(id.toString(), java(member));
			}
			return result;
		}
		if (value instanceof Number number) {
			return Double.valueOf(number.doubleValue());
		}
		return value(value);
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
	 * An object given to a button's handler that provides no member: every member the handler reads or
	 * sets stops the run, naming it.
	 */
	private static class Members extends ScriptableObject {

		private final String _name;

		Members(Scriptable scope, String name) {
			_name = name;
			setParentScope(scope);
		}

		@Override
		public String getClassName() {
			return "Object";
		}

		/**
		 * The member of the given name, {@link Scriptable#NOT_FOUND} for one this object does not
		 * provide.
		 */
		protected Object member(String name) {
			return NOT_FOUND;
		}

		@Override
		public Object get(String name, Scriptable start) {
			Object result = member(name);
			if (result == NOT_FOUND) {
				throw new Refused("the handler uses " + _name + "." + name + ", which E-Home does not provide");
			}
			return result;
		}

		@Override
		public Object get(int index, Scriptable start) {
			throw new Refused("the handler uses " + _name + "[" + index + "], which E-Home does not provide");
		}

		@Override
		public void put(String name, Scriptable start, Object value) {
			throw new Refused("the handler sets " + _name + "." + name + ", which E-Home does not provide");
		}

		@Override
		public void put(int index, Scriptable start, Object value) {
			throw new Refused("the handler sets " + _name + "[" + index + "], which E-Home does not provide");
		}
	}

	/**
	 * The object <code>device</code> of a button's handler: its parameters by ID and by name.
	 */
	private static final class DeviceObject extends Members {

		private final ButtonDevice _device;

		DeviceObject(Scriptable scope, ButtonDevice device) {
			super(scope, "device");
			_device = device;
		}

		@Override
		protected Object member(String name) {
			if (!DEVICE_MEMBERS.contains(name)) {
				return NOT_FOUND;
			}
			boolean byId = name.endsWith("Id");
			Scriptable scope = getParentScope();
			BaseFunction lookup = new BaseFunction() {
				@Override
				public Object call(Context cx, Scriptable callScope, Scriptable thisObj, Object[] args) {
					String key = args.length == 0 ? "" : Context.toString(args[0]);
					Object parameter;
					try {
						parameter = byId ? _device.parameterById(key) : _device.parameterByName(key);
					} catch (CalculationException ex) {
						throw new Refused(ex.getMessage());
					}
					if (parameter == null) {
						throw new Refused("the device has no parameter " + key + " (device." + name + ")");
					}
					return new ParameterObject(scope, _device, parameter, key);
				}
			};
			lookup.setParentScope(scope);
			lookup.setPrototype(ScriptableObject.getFunctionPrototype(scope));
			return lookup;
		}
	}

	/**
	 * A parameter of the device given to a button's handler: its value, read and set.
	 */
	private static final class ParameterObject extends Members {

		private final ButtonDevice _device;

		private final Object _parameter;

		ParameterObject(Scriptable scope, ButtonDevice device, Object parameter, String key) {
			super(scope, "the parameter " + key);
			_device = device;
			_parameter = parameter;
		}

		@Override
		protected Object member(String name) {
			if (!PARAMETER_MEMBERS.contains(name)) {
				return NOT_FOUND;
			}
			Object value = _device.value(_parameter);
			return value == null ? Undefined.instance : value;
		}

		@Override
		public void put(String name, Scriptable start, Object value) {
			if (!PARAMETER_MEMBERS.contains(name)) {
				super.put(name, start, value);
				return;
			}
			_device.setValue(_parameter, value(value));
		}
	}

	/**
	 * Stops a button's handler that uses what E-Home does not provide; an {@link Error}, so that the
	 * script cannot catch it.
	 */
	private static final class Refused extends Error {

		Refused(String message) {
			super(message, null, false, false);
		}
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
