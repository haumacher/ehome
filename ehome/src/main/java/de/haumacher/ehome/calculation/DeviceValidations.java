/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.model.operational.CalculationParameter;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ParameterValidation;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * The checks of the manufacturer refusing values a person enters (ETS: <code>ParameterValidation</code>,
 * haumacher/ehome #124), evaluated by E-Home as ETS does.
 *
 * <ul>
 * <li>A validation names a function of the script (ETS: <code>ValidationFunc</code>; the script is the
 * program's, followed by that of the validation's module definition) and the parameters it reads
 * (<code>Parameters/ParameterRefRef</code>), by their aliases, else by the names of their parameters, as
 * the calculations do ({@link DeviceCalculations#variable(CalculationParameter)}). A validation of the
 * program is in effect in every device of the program, one of a module definition once per module
 * instance in effect, with the parameters of that instance.</li>
 * <li>The validation has no language of its own: its function is JavaScript if the script is JavaScript
 * and defines it, else VBScript if the script is of E-Home's VBScript subset and defines it as a
 * <code>Function</code> of four parameters ({@link #language(ParameterValidation)}).</li>
 * <li>When a person changes one of its parameters ({@link #check(TLObject, ParameterView, Object)},
 * before the calculations the value feeds run), the function is called as
 * <code>function(input, changed, prevValue, context)</code>: <code>input</code> the parameters by their
 * names with their values (the changed one with its new value; a number, a time and an enumeration as a
 * number, a text as a text), <code>changed</code> the name of the changed parameter,
 * <code>prevValue</code> its value before, <code>context</code> the parameters of the validation (ETS:
 * <code>ValidationParameters</code>) as a JSON value, else as the text. It answers <code>true</code>
 * (or anything else that is true and no text) to accept the value, a text to refuse it with that text;
 * an answer that is false refuses without a text, a script error or a run beyond the limits refuses
 * naming the error.</li>
 * <li>A validation E-Home cannot evaluate (no such function, a script outside both languages,
 * {@link #problem(ParameterValidation)}) makes its parameters unchangeable in E-Home
 * ({@link #editVeto(DeviceConfiguration, ParameterView)}), with the reason, as a calculation E-Home
 * cannot evaluate does.</li>
 * <li>On import ({@link #imported(TLObject)}), every validation in effect is called once with the values
 * of the file, as if its first parameter had been entered with its value: a refusal is reported, the
 * values are kept (the device was configured so in ETS).</li>
 * </ul>
 */
public final class DeviceValidations {

	private static final Map<String, String> PROBLEMS = new ConcurrentHashMap<>();

	private static final String JAVASCRIPT = "JavaScript";

	private static final String VBSCRIPT = "VBScript";

	private DeviceValidations() {
		// Static only.
	}

	/**
	 * A validation in effect in a device: the validation of the program or of a module definition, with
	 * the module instance whose parameters it checks (<code>null</code> for one of the program).
	 */
	public record Bound(ParameterValidation validation, InstanceInfo instance) {

		/**
		 * The given parameter of the validation, as it is in the instance.
		 */
		public ParameterView view(CalculationParameter parameter) {
			return (ParameterView) ModuleParts.wrap(parameter.getParameter(), instance);
		}

		/**
		 * The parameter of the validation the given placement is in its instance, <code>null</code> if it
		 * is none.
		 */
		CalculationParameter parameter(ParameterView view) {
			if (!Objects.equals(ModuleParts.instance(view), instance)) {
				return null;
			}
			ParameterView template = ModuleParts.template(view);
			for (CalculationParameter parameter : validation.getParameters()) {
				if (parameter.getParameter() == template) {
					return parameter;
				}
			}
			return null;
		}
	}

	/**
	 * The functions the given validation may call: the script of its program, then that of its module
	 * definition (ETS: <code>Static/Script</code>); <code>null</code> for none.
	 */
	static String library(ParameterValidation validation) {
		Program program = ModuleParts.programOf(validation);
		ModuleDefinition definition = ModuleParts.definitionOf(validation);
		String result = program == null ? null : program.getScript();
		String own = definition == null ? null : definition.getScript();
		if (own == null || own.isBlank()) {
			return result;
		}
		return result == null || result.isBlank() ? own : result + "\n" + own;
	}

	/**
	 * The language the function of the given validation is called in, <code>null</code> if E-Home cannot
	 * evaluate it ({@link #problem(ParameterValidation)}).
	 */
	public static ScriptLanguage language(ParameterValidation validation) {
		String verdict = verdict(validation);
		switch (verdict) {
			case JAVASCRIPT:
				return JavaScriptLanguage.INSTANCE;
			case VBSCRIPT:
				return VbScriptLanguage.INSTANCE;
			default:
				return null;
		}
	}

	/**
	 * Why E-Home cannot evaluate the given validation (it names no function, the script defines no such
	 * function, the script is neither JavaScript nor of the VBScript subset), as a person reads it;
	 * <code>null</code> if it can.
	 */
	public static String problem(ParameterValidation validation) {
		String verdict = verdict(validation);
		return JAVASCRIPT.equals(verdict) || VBSCRIPT.equals(verdict) ? null : verdict;
	}

	/**
	 * The language of the given validation's function, or why it cannot be evaluated.
	 */
	private static String verdict(ParameterValidation validation) {
		String function = validation.getFunction();
		if (function == null || function.isBlank()) {
			return "the validation names no function";
		}
		String library = library(validation);
		return PROBLEMS.computeIfAbsent(library + "\u0000" + function, key -> {
			String javaScript = JavaScriptLanguage.INSTANCE.checkValidation(library, function);
			if (javaScript == null) {
				return JAVASCRIPT;
			}
			String vbScript = VbScriptLanguage.INSTANCE.checkValidation(library, function);
			if (vbScript == null) {
				return VBSCRIPT;
			}
			// The reason in the language the script is written in.
			boolean basic = library != null && library.toLowerCase(Locale.ROOT).contains("end function");
			return basic ? VBSCRIPT + ": " + vbScript : JAVASCRIPT + ": " + javaScript;
		});
	}

	/**
	 * The name of the given validation as messages name it: its name and ID.
	 */
	public static String label(ParameterValidation validation) {
		String name = validation.getName();
		String id = validation.getExtId();
		if (name == null || name.isBlank()) {
			return Objects.toString(id, "?");
		}
		return id == null ? name : name + " (" + id + ")";
	}

	/**
	 * The validations of the given program and of its module definitions.
	 */
	public static List<ParameterValidation> all(Program program) {
		List<ParameterValidation> result = new ArrayList<>(program.getValidations());
		List<ModuleDefinition> todo = new ArrayList<>(program.getModules());
		while (!todo.isEmpty()) {
			ModuleDefinition definition = todo.remove(0);
			result.addAll(definition.getValidations());
			todo.addAll(definition.getModules());
		}
		return result;
	}

	/**
	 * Whether the given program has validations, in itself or in its module definitions.
	 */
	public static boolean hasValidations(Program program) {
		return !all(program).isEmpty();
	}

	/**
	 * The validations in effect in the device of the given configuration.
	 */
	public static List<Bound> inEffect(DeviceConfiguration configuration) {
		List<Bound> result = new ArrayList<>();
		Program program = configuration.getProgram();
		if (program == null) {
			return result;
		}
		for (ParameterValidation validation : program.getValidations()) {
			result.add(new Bound(validation, null));
		}
		for (InstanceInfo instance : configuration.getInstances()) {
			for (ParameterValidation validation : instance.getDefinition().getValidations()) {
				result.add(new Bound(validation, instance));
			}
		}
		return result;
	}

	/**
	 * Why the given placement cannot be changed because of a validation: it is a parameter of a
	 * validation in effect that E-Home cannot evaluate; <code>null</code> otherwise.
	 */
	public static ResKey editVeto(DeviceConfiguration configuration, ParameterView view) {
		Program program = configuration.getProgram();
		if (program == null || !hasValidations(program)) {
			return null;
		}
		for (Bound bound : inEffect(configuration)) {
			if (bound.parameter(view) != null) {
				String problem = problem(bound.validation());
				if (problem != null) {
					return I18NConstants.ERROR_NOT_EVALUATED__PARAMETER_VALIDATION_PROBLEM.fill(view,
						label(bound.validation()), problem);
				}
			}
		}
		return null;
	}

	/**
	 * Checks the changed value of the given placement with the validations in effect that read it; the
	 * new value is stored already.
	 *
	 * @param previous
	 *        The value of the placement before the change, typed.
	 * @throws TopLogicException
	 *         With the text of the manufacturer, if a validation refuses the value; with the reason, if a
	 *         validation cannot be evaluated or fails. The caller restores the value before.
	 */
	public static void check(TLObject device, ParameterView view, Object previous) {
		Program program = DeviceConfiguration.program(device);
		if (program == null || !hasValidations(program)) {
			return;
		}
		for (Bound bound : inEffect(new DeviceConfiguration(device))) {
			CalculationParameter changed = bound.parameter(view);
			if (changed == null) {
				continue;
			}
			ParameterValidation validation = bound.validation();
			String answer;
			try {
				answer = evaluate(device, bound, changed, DeviceCalculations.scriptValue(previous));
			} catch (CalculationException ex) {
				throw new TopLogicException(
					I18NConstants.ERROR_FAILED__VALIDATION_PROBLEM.fill(label(validation), ex.getMessage()));
			}
			if (answer != null) {
				throw new TopLogicException(answer.isBlank()
					? I18NConstants.ERROR_REFUSED__VALIDATION.fill(label(validation))
					: I18NConstants.ERROR_REFUSED__VALIDATION_MESSAGE.fill(label(validation), answer));
			}
		}
	}

	/**
	 * Checks the values of an imported device with the validations in effect: each is called once, as if
	 * its first parameter had been entered with its value.
	 *
	 * @return The refusals and failures, one per validation; the device keeps its values. A validation
	 *         E-Home cannot evaluate is reported with the program, not here.
	 */
	public static List<ResKey> imported(TLObject device) {
		List<ResKey> result = new ArrayList<>();
		Program program = DeviceConfiguration.program(device);
		if (program == null || !hasValidations(program)) {
			return result;
		}
		for (Bound bound : inEffect(new DeviceConfiguration(device))) {
			ParameterValidation validation = bound.validation();
			if (problem(validation) != null || validation.getParameters().isEmpty()) {
				continue;
			}
			CalculationParameter first = validation.getParameters().get(0);
			try {
				Object value = DeviceCalculations.scriptValue(DeviceSettings.value(device, bound.view(first)));
				String answer = evaluate(device, bound, first, value);
				if (answer != null) {
					result.add(answer.isBlank() ? I18NConstants.IMPORT_REFUSED__VALIDATION.fill(label(validation))
						: I18NConstants.IMPORT_REFUSED__VALIDATION_MESSAGE.fill(label(validation), answer));
				}
			} catch (CalculationException ex) {
				result.add(I18NConstants.IMPORT_FAILED__VALIDATION_PROBLEM.fill(label(validation), ex.getMessage()));
			} catch (TopLogicException ex) {
				result.add(ex.getErrorKey());
			}
		}
		return result;
	}

	/**
	 * Calls the function of a validation with the device's current values.
	 *
	 * @return <code>null</code> if it accepts them, else its text (empty for none).
	 */
	private static String evaluate(TLObject device, Bound bound, CalculationParameter changed, Object previous)
			throws CalculationException {
		ParameterValidation validation = bound.validation();
		ScriptLanguage language = language(validation);
		if (language == null) {
			throw new CalculationException(problem(validation));
		}
		Map<String, Object> input = new LinkedHashMap<>();
		for (CalculationParameter parameter : validation.getParameters()) {
			String name = DeviceCalculations.variable(parameter);
			if (name == null || parameter.getParameter() == null) {
				throw new TopLogicException(I18NConstants.ERROR_NO_NAME__VALIDATION.fill(label(validation)));
			}
			input.put(name, DeviceCalculations.scriptValue(DeviceSettings.value(device, bound.view(parameter))));
		}
		return language.validate(library(validation), validation.getFunction(), input,
			DeviceCalculations.variable(changed), previous, validation.getArguments());
	}

}
