/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.CalculationParameter;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ParameterCalculation;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * The parameter calculations of the manufacturer (ETS 6: <code>ParameterCalculation</code>,
 * haumacher/ehome #123) in a device: E-Home evaluates them as ETS does, so that every device stays
 * configurable and the memory written by a download (the R parameters) follows the values a person
 * edits (the L parameters).
 *
 * <ul>
 * <li>A calculation of the program is in effect in every device of the program, a calculation of a
 * module definition once per module instance in effect, with the parameters of that instance.</li>
 * <li>A transformation is its code inline (ETS: <code>LRTransformation</code>,
 * <code>RLTransformation</code>), or a function of the script called with the source parameters, the
 * target parameters and the parameters of the transformation (<code>LRTransformationFunc</code>,
 * <code>…Parameters</code>), see {@link ScriptLanguage}. The script is the program's, followed by that of
 * the calculation's module definition ({@link #library(ParameterCalculation)}).</li>
 * <li>The scripts know the parameters as variables named by the alias of their reference
 * (<code>AliasName</code>), else by the name of their parameter (<code>Parameter@Name</code>). A
 * variable holds the parameter's value: a number for a number, a time or the entry of an
 * enumeration (its value), a text for a text.</li>
 * <li><b>LR</b> runs when an L parameter of the calculation changes (in the editor, by a reset, by a
 * return to the state of the device): it assigns the R parameters. <b>RL</b> runs when an R parameter
 * changes (the read-back of a device's memory) and assigns the L parameters. A parameter the run does
 * not assign keeps its value.</li>
 * <li>A result goes into its parameter by the parameter's type: a number into a whole number type
 * truncated toward zero (KNX does not say how ETS rounds; JavaScript's <code>/</code> gives
 * fractions), into an enumeration as the value of an entry, a text as it is. A result the type does
 * not take (outside its range, no entry, no number), a script error or a run beyond its limits refuses
 * the change with the reason naming the calculation; nothing is changed then.</li>
 * <li>A calculation E-Home cannot evaluate (another language, a construct the interpreter does not
 * support, a syntax error; {@link #problem(ParameterCalculation)}) makes its parameters
 * unchangeable in E-Home ({@link #editVeto(DeviceConfiguration, ParameterView)}), with the reason;
 * an imported device keeps the values of its file.</li>
 * <li>On import ({@link #imported(TLObject)}), the R values of the file are the memory of the
 * device: where the L values of the file give them by LR, nothing changes; otherwise RL derives the L
 * values from the R values.</li>
 * <li>A device created in E-Home (planned, duplicated, found by a scan) or given another program
 * (<code>ProgramChanges</code>) gets its R values computed from its L values
 * ({@link #recompute(TLObject)}), so that its download is consistent also where the defaults of the
 * program are not.</li>
 * </ul>
 */
public final class DeviceCalculations {

	/** The most calculations one change may run (calculations feeding each other). */
	private static final int MAX_RUNS = 100;

	private static final Map<String, String> PROBLEMS = new java.util.concurrent.ConcurrentHashMap<>();

	private DeviceCalculations() {
		// Static only.
	}

	/**
	 * A calculation in effect in a device: the calculation of the program or of a module definition,
	 * with the module instance whose parameters it computes (<code>null</code> for one of the program).
	 */
	public record Bound(ParameterCalculation calculation, InstanceInfo instance) {

		/**
		 * The given parameter of the calculation, as it is in the instance.
		 */
		public ParameterView view(CalculationParameter parameter) {
			return (ParameterView) ModuleParts.wrap(parameter.getParameter(), instance);
		}

		/**
		 * Whether the given placement (a part of a module instance or not) is a parameter of the
		 * given side of the calculation in its instance.
		 */
		boolean has(List<? extends CalculationParameter> side, ParameterView view) {
			ParameterView template = ModuleParts.template(view);
			if (!Objects.equals(ModuleParts.instance(view), instance)) {
				return false;
			}
			for (CalculationParameter parameter : side) {
				if (parameter.getParameter() == template) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * The language of the given name (ETS: <code>Language</code>), <code>null</code> for one E-Home
	 * does not support.
	 */
	public static ScriptLanguage language(String name) {
		if (name == null) {
			return null;
		}
		switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "javascript":
			case "jscript":
			case "ecmascript":
				return JavaScriptLanguage.INSTANCE;
			case "vbscript":
				return VbScriptLanguage.INSTANCE;
			default:
				return null;
		}
	}

	/**
	 * Why E-Home cannot evaluate the given calculation (a language it does not support, a construct of
	 * a script the interpreter does not support, a syntax error), as a person reads it;
	 * <code>null</code> if it can.
	 */
	public static String problem(ParameterCalculation calculation) {
		String languageName = calculation.getLanguage();
		ScriptLanguage language = language(languageName);
		if (language == null) {
			return "the language " + (languageName == null ? "(none)" : languageName) + " is not supported";
		}
		String library = library(calculation);
		for (boolean lr : new boolean[] { true, false }) {
			String code = lr ? calculation.getLrCode() : calculation.getRlCode();
			String function = lr ? calculation.getLrFunction() : calculation.getRlFunction();
			String problem;
			if (code != null && !code.isBlank()) {
				problem = PROBLEMS.computeIfAbsent(languageName + "\u0000" + library + "\u0000" + code,
					key -> Objects.toString(language.check(library, code), ""));
			} else if (function != null && !function.isBlank()) {
				problem = PROBLEMS.computeIfAbsent(languageName + "\u0001" + library + "\u0000" + function,
					key -> Objects.toString(language.checkFunction(library, function), ""));
			} else {
				// No transformation in this direction: nothing to compute.
				problem = "";
			}
			if (!problem.isEmpty()) {
				return (lr ? "LR" : "RL") + ": " + problem;
			}
		}
		return null;
	}

	/**
	 * The functions the given calculation may call: the script of its program, then that of its module
	 * definition (ETS: <code>Static/Script</code>); <code>null</code> for none.
	 */
	static String library(ParameterCalculation calculation) {
		Program program = ModuleParts.programOf(calculation);
		ModuleDefinition definition = ModuleParts.definitionOf(calculation);
		String result = program == null ? null : program.getScript();
		String own = definition == null ? null : definition.getScript();
		if (own == null || own.isBlank()) {
			return result;
		}
		return result == null || result.isBlank() ? own : result + "\n" + own;
	}

	/**
	 * The name of the given calculation as messages name it: its name and ID.
	 */
	public static String label(ParameterCalculation calculation) {
		Object name = calculation.tValueByName("name");
		String id = calculation.getExtId();
		if (name == null || name.toString().isBlank()) {
			return Objects.toString(id, "?");
		}
		return id == null ? name.toString() : name + " (" + id + ")";
	}

	/**
	 * The name the scripts know the given parameter of a calculation by: its alias, else the name of
	 * its parameter.
	 */
	public static String variable(CalculationParameter parameter) {
		String alias = parameter.getAlias();
		if (alias != null && !alias.isBlank()) {
			return alias.trim();
		}
		ParameterView view = parameter.getParameter();
		Object name = view == null || view.getParameter() == null ? null : view.getParameter().tValueByName("name");
		return name == null ? null : name.toString();
	}

	/**
	 * The calculations in effect in the device of the given configuration.
	 */
	public static List<Bound> inEffect(DeviceConfiguration configuration) {
		List<Bound> result = new ArrayList<>();
		Program program = configuration.getProgram();
		if (program == null) {
			return result;
		}
		for (ParameterCalculation calculation : program.getCalculations()) {
			result.add(new Bound(calculation, null));
		}
		for (InstanceInfo instance : configuration.getInstances()) {
			for (ParameterCalculation calculation : instance.getDefinition().getCalculations()) {
				result.add(new Bound(calculation, instance));
			}
		}
		return result;
	}

	/**
	 * Why the given placement cannot be changed because of a calculation: it is a parameter of a
	 * calculation in effect that E-Home cannot evaluate; <code>null</code> otherwise.
	 */
	public static ResKey editVeto(DeviceConfiguration configuration, ParameterView view) {
		Program program = configuration.getProgram();
		if (program == null || !hasCalculations(program)) {
			return null;
		}
		for (Bound bound : inEffect(configuration)) {
			ParameterCalculation calculation = bound.calculation();
			if (bound.has(calculation.getInputs(), view) || bound.has(calculation.getOutputs(), view)) {
				String problem = problem(calculation);
				if (problem != null) {
					return I18NConstants.ERROR_NOT_EVALUATED__PARAMETER_CALCULATION_PROBLEM.fill(view,
						label(calculation), problem);
				}
			}
		}
		return null;
	}

	/**
	 * What the editor says beside the given placement about the calculations of the device: why it
	 * cannot be changed (a calculation E-Home cannot evaluate), or that a calculation computes it (an R
	 * parameter); <code>null</code> for a placement no calculation in effect concerns otherwise.
	 */
	public static ResKey hint(TLObject device, ParameterView view) {
		Program program = DeviceConfiguration.program(device);
		if (program == null || !hasCalculations(program)) {
			return null;
		}
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		ResKey veto = editVeto(configuration, view);
		if (veto != null) {
			return veto;
		}
		return computedBy(configuration, view);
	}

	/**
	 * That a calculation in effect computes the given placement (an R parameter), <code>null</code> if
	 * none does.
	 */
	public static ResKey computedBy(DeviceConfiguration configuration, ParameterView view) {
		Program program = configuration.getProgram();
		if (program == null || !hasCalculations(program)) {
			return null;
		}
		for (Bound bound : inEffect(configuration)) {
			if (bound.has(bound.calculation().getOutputs(), view)) {
				return I18NConstants.COMPUTED_BY__CALCULATION.fill(label(bound.calculation()));
			}
		}
		return null;
	}

	/**
	 * Whether the given program has calculations, in itself or in its module definitions.
	 */
	public static boolean hasCalculations(Program program) {
		return !all(program).isEmpty();
	}

	/**
	 * The calculations of the given program and of its module definitions.
	 */
	public static List<ParameterCalculation> all(Program program) {
		List<ParameterCalculation> result = new ArrayList<>(program.getCalculations());
		List<ModuleDefinition> todo = new ArrayList<>(program.getModules());
		while (!todo.isEmpty()) {
			ModuleDefinition definition = todo.remove(0);
			result.addAll(definition.getCalculations());
			todo.addAll(definition.getModules());
		}
		return result;
	}

	/**
	 * Runs the calculations the given changed parameters of the device feed (LR for an L parameter,
	 * RL for an R parameter), and those the results feed in turn, each once, and stores their results.
	 *
	 * <p>
	 * Must run in a transaction. On a refusal, the values the calculations stored are restored before
	 * the exception is thrown; the changed values themselves are the caller's.
	 * </p>
	 *
	 * @throws TopLogicException
	 *         With the reason naming the calculation, if a calculation fails or gives a value its
	 *         parameter does not take.
	 */
	public static void changed(TLObject device, Collection<? extends ParameterView> changed) {
		Program program = DeviceConfiguration.program(device);
		if (program == null || !hasCalculations(program) || changed.isEmpty()) {
			return;
		}
		Map<ParameterView, Object> before = new LinkedHashMap<>();
		try {
			List<ParameterView> todo = new ArrayList<>(changed);
			Set<Bound> done = new LinkedHashSet<>();
			int runs = 0;
			while (!todo.isEmpty()) {
				ParameterView view = todo.remove(0);
				DeviceConfiguration configuration = new DeviceConfiguration(device);
				for (Bound bound : inEffect(configuration)) {
					if (done.contains(bound)) {
						continue;
					}
					ParameterCalculation calculation = bound.calculation();
					boolean lr = bound.has(calculation.getInputs(), view);
					if (!lr && !bound.has(calculation.getOutputs(), view)) {
						continue;
					}
					if (++runs > MAX_RUNS) {
						throw new TopLogicException(I18NConstants.ERROR_TOO_MANY_RUNS__CALCULATION.fill(label(calculation)));
					}
					done.add(bound);
					Map<ParameterView, Object> results = evaluate(device, bound, lr);
					for (Map.Entry<ParameterView, Object> result : results.entrySet()) {
						ParameterView target = result.getKey();
						Object current = DeviceSettings.value(device, target);
						if (Objects.equals(encode(target, current), encode(target, result.getValue()))) {
							continue;
						}
						before.putIfAbsent(target, current);
						DeviceSettings.storeComputed(device, target, result.getValue());
						todo.add(target);
					}
				}
			}
		} catch (TopLogicException ex) {
			for (Map.Entry<ParameterView, Object> entry : before.entrySet()) {
				DeviceSettings.storeComputed(device, entry.getKey(), entry.getValue());
			}
			throw ex;
		}
	}

	/**
	 * Computes the R values of every calculation in effect in the device from its L values (LR), for a
	 * device created in E-Home.
	 *
	 * @throws TopLogicException
	 *         With the reason, if a calculation cannot be evaluated or fails; nothing is changed then.
	 */
	public static void recompute(TLObject device) {
		Program program = DeviceConfiguration.program(device);
		if (program == null || !hasCalculations(program)) {
			return;
		}
		List<ParameterView> inputs = new ArrayList<>();
		for (Bound bound : inEffect(new DeviceConfiguration(device))) {
			String problem = problem(bound.calculation());
			if (problem != null) {
				throw new TopLogicException(I18NConstants.ERROR_CANNOT_COMPUTE__CALCULATION_PROBLEM.fill(
					label(bound.calculation()), problem));
			}
			if (!bound.calculation().getInputs().isEmpty()) {
				inputs.add(bound.view(bound.calculation().getInputs().get(0)));
			}
		}
		changed(device, inputs);
	}

	/**
	 * Brings the values of an imported device in line with its calculations: where the L values of
	 * the file do not give its R values (LR), RL derives the L values from the R values, which are the
	 * memory of the device.
	 *
	 * @return Why a calculation could not be checked (it cannot be evaluated, it fails, or a result does
	 *         not fit), one entry per calculation; the device keeps the values of its file there.
	 */
	public static List<ResKey> imported(TLObject device) {
		List<ResKey> result = new ArrayList<>();
		Program program = DeviceConfiguration.program(device);
		if (program == null || !hasCalculations(program)) {
			return result;
		}
		for (Bound bound : inEffect(new DeviceConfiguration(device))) {
			ParameterCalculation calculation = bound.calculation();
			if (problem(calculation) != null) {
				// Reported with the program; the values of the file stay.
				continue;
			}
			try {
				Map<ParameterView, Object> computed = evaluate(device, bound, true);
				boolean consistent = true;
				for (Map.Entry<ParameterView, Object> entry : computed.entrySet()) {
					consistent &= Objects.equals(encode(entry.getKey(), entry.getValue()),
						encode(entry.getKey(), DeviceSettings.value(device, entry.getKey())));
				}
				if (consistent) {
					continue;
				}
				Map<ParameterView, Object> derived = evaluate(device, bound, false);
				// The memory of the device is the file's: the L values derived from it change nothing the
				// device holds, so the device stays as modified as the file says.
				Object modified = device.tValueByName("lastModified");
				for (Map.Entry<ParameterView, Object> entry : derived.entrySet()) {
					DeviceSettings.storeComputed(device, entry.getKey(), entry.getValue());
				}
				device.tUpdateByName("lastModified", modified);
			} catch (TopLogicException ex) {
				result.add(ex.getErrorKey());
			}
		}
		return result;
	}

	/**
	 * Evaluates one direction of a calculation with the device's current values.
	 *
	 * @param lr
	 *        LR (the R parameters from the L parameters), else RL.
	 * @return The values of the parameters of the target side, typed.
	 */
	public static Map<ParameterView, Object> evaluate(TLObject device, Bound bound, boolean lr) {
		ParameterCalculation calculation = bound.calculation();
		String problem = problem(calculation);
		if (problem != null) {
			throw new TopLogicException(I18NConstants.ERROR_CANNOT_COMPUTE__CALCULATION_PROBLEM.fill(
				label(calculation), problem));
		}
		ScriptLanguage language = language(calculation.getLanguage());
		// The variables of inline code; the members of input and output of a function, which may have the
		// same names (programs in the field give a parameter the same alias on both sides).
		Map<String, Object> variables = new LinkedHashMap<>();
		Map<String, Object> sources = new LinkedHashMap<>();
		Map<String, Object> current = new LinkedHashMap<>();
		Map<String, ParameterView> targets = new LinkedHashMap<>();
		List<? extends CalculationParameter> inputs = calculation.getInputs();
		List<? extends CalculationParameter> outputs = calculation.getOutputs();
		for (boolean input : new boolean[] { true, false }) {
			for (CalculationParameter parameter : input ? inputs : outputs) {
				String name = variable(parameter);
				if (name == null) {
					throw new TopLogicException(I18NConstants.ERROR_NO_NAME__CALCULATION.fill(label(calculation)));
				}
				ParameterView view = bound.view(parameter);
				Object value = scriptValue(DeviceSettings.value(device, view));
				variables.put(name, value);
				if (input != lr) {
					targets.put(name, view);
					current.put(name, value);
				} else {
					sources.put(name, value);
				}
			}
		}
		String library = library(calculation);
		String code = lr ? calculation.getLrCode() : calculation.getRlCode();
		String function = lr ? calculation.getLrFunction() : calculation.getRlFunction();
		Map<String, Object> values;
		try {
			if (code != null && !code.isBlank()) {
				values = language.run(library, code, variables);
			} else if (function != null && !function.isBlank()) {
				values = language.call(library, function, sources, current,
					lr ? calculation.getLrParameters() : calculation.getRlParameters());
			} else {
				// No transformation in this direction: the targets keep their values.
				values = variables;
			}
		} catch (CalculationException ex) {
			throw new TopLogicException(I18NConstants.ERROR_FAILED__CALCULATION_DIRECTION_PROBLEM.fill(
				label(calculation), lr ? "LR" : "RL", ex.getMessage()));
		}
		Map<ParameterView, Object> result = new HashMap<>();
		for (Map.Entry<String, ParameterView> target : targets.entrySet()) {
			ParameterView view = target.getValue();
			Object raw = values.get(target.getKey());
			try {
				result.put(view, typed(view, raw));
			} catch (TopLogicException ex) {
				throw new TopLogicException(I18NConstants.ERROR_RESULT__CALCULATION_PARAMETER_VALUE_REASON.fill(
					label(calculation), view, raw, ex.getErrorKey()));
			}
		}
		return result;
	}

	/**
	 * The value of a parameter as a script reads it: a number as a {@link Double}, the entry of an
	 * enumeration as its value, a text as it is.
	 */
	static Object scriptValue(Object value) {
		if (value instanceof EnumerationValue entry) {
			return Double.valueOf(entry.getValue());
		}
		if (value instanceof Number number) {
			return Double.valueOf(number.doubleValue());
		}
		return value;
	}

	/**
	 * The result of a script as a value of the given placement's type.
	 */
	static Object typed(ParameterView view, Object raw) {
		ParameterType type = ParameterValues.valueType(view);
		String attribute = ParameterValues.valueAttribute(type);
		if (raw == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_RESULT);
		}
		Object value = raw instanceof Boolean flag ? Double.valueOf(flag.booleanValue() ? 1 : 0) : raw;
		if (ParameterValues.NUMBER.equals(attribute) || ParameterValues.CHOICE.equals(attribute)) {
			double number = number(value);
			// Truncated toward zero.
			value = Long.valueOf((long) number);
		} else if (ParameterValues.DECIMAL.equals(attribute) || type instanceof FloatingPointType) {
			value = Double.valueOf(number(value));
		} else if (value instanceof Double number && number.doubleValue() == Math.rint(number.doubleValue())) {
			value = Long.toString(number.longValue());
		} else {
			value = value.toString();
		}
		return DeviceSettings.typed(type, value);
	}

	private static double number(Object value) {
		double result;
		if (value instanceof Number number) {
			result = number.doubleValue();
		} else {
			try {
				result = Double.parseDouble(value.toString().trim());
			} catch (NumberFormatException ex) {
				throw new TopLogicException(I18NConstants.ERROR_NOT_A_NUMBER);
			}
		}
		if (Double.isNaN(result) || Double.isInfinite(result)) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_A_NUMBER);
		}
		return result;
	}

	private static String encode(ParameterView view, Object value) {
		return ParameterValues.encodeValue(ParameterValues.valueType(view), value);
	}

}
