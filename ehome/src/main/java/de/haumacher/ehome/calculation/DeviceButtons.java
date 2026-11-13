/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.calculation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Button;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * The buttons of the parameter dialog (ETS 6: <code>Button</code> in the dynamic part,
 * haumacher/ehome #158), pressed by a person in the editor of a device.
 *
 * <ul>
 * <li>A button names a function of the script (ETS: <code>EventHandler</code>; the script is the
 * program's, followed by that of the button's module definition, as for the calculations), which is
 * called in the sandbox of the calculations ({@link JavaScriptLanguage}) as
 * <code>function(device, online, progress, context)</code>: <code>device</code> gives the parameters of
 * the device by their IDs as the program writes them (<code>device.GetParameterById("MD-1_P-80_R-2")</code>,
 * within a module instance the parameter of that instance) or by their names, each with its
 * <code>Value</code> to read and to set; <code>online</code> is <code>false</code>; <code>context</code>
 * the parameters of the handler (ETS: <code>EventHandlerParameters</code>) as a JSON value, else as the
 * text.</li>
 * <li>What the handler sets is an input: when the handler has run, each value it set (the last one per
 * parameter, in the order the parameters were first set) takes the way of a person's change
 * ({@link DeviceSettings#setValue(TLObject, ParameterView, Object)}: the type, the access, the
 * validations and the calculations it feeds). A refusal anywhere (a script error, a member E-Home does
 * not provide, a value refused) is thrown with the reason; the press is one change, so the caller's
 * transaction is rolled back and nothing of it stays (the view runs it in a transaction of its
 * own).</li>
 * <li>A handler E-Home cannot run ({@link #problem(Button)}: no such function, a script that is not
 * JavaScript, a member of <code>device</code>, of a parameter or of <code>progress</code> E-Home does not
 * provide, a handler talking to the device through <code>online</code>) is reported at import; the
 * button is disabled with the reason ({@link #veto(TLObject, Button)}).</li>
 * </ul>
 */
public final class DeviceButtons {

	private static final Map<String, String> PROBLEMS = new ConcurrentHashMap<>();

	private DeviceButtons() {
		// Static only.
	}

	/**
	 * The functions the handler of the given button may call: the script of its program, then that of
	 * its module definition; <code>null</code> for none.
	 */
	static String library(Button button) {
		Button template = ModuleParts.template(button);
		Program program = ModuleParts.programOf(template);
		ModuleDefinition definition = ModuleParts.definitionOf(template);
		String result = program == null ? null : program.getScript();
		String own = definition == null ? null : definition.getScript();
		if (own == null || own.isBlank()) {
			return result;
		}
		return result == null || result.isBlank() ? own : result + "\n" + own;
	}

	/**
	 * Why E-Home cannot run the handler of the given button (it names no handler, the script defines no
	 * such function or is not JavaScript, the handler uses a member E-Home does not provide or talks to
	 * the device), as a person reads it; <code>null</code> if it can.
	 */
	public static String problem(Button button) {
		Button template = ModuleParts.template(button);
		String handler = template.getHandler();
		if (handler == null || handler.isBlank()) {
			return "the button names no handler";
		}
		String library = library(template);
		String result = PROBLEMS.computeIfAbsent(library + "\u0000" + handler,
			key -> Objects.toString(JavaScriptLanguage.INSTANCE.checkHandler(library, handler), ""));
		return result.isEmpty() ? null : result;
	}

	/**
	 * The buttons of the given program and of its module definitions, in the order of the structure.
	 */
	public static List<Button> all(Program program) {
		List<Button> result = new ArrayList<>();
		collect(program, result);
		List<ModuleDefinition> todo = new ArrayList<>(program.getModules());
		while (!todo.isEmpty()) {
			ModuleDefinition definition = todo.remove(0);
			collect(definition, result);
			todo.addAll(definition.getModules());
		}
		return result;
	}

	private static void collect(TLObject container, List<Button> result) {
		Iterable<?> elements;
		if (container instanceof ConfigFragment fragment) {
			elements = fragment.getContents();
		} else if (container instanceof Switch choice) {
			elements = choice.getAlternatives();
		} else {
			return;
		}
		for (Object element : elements) {
			if (element instanceof Button button) {
				result.add(button);
			} else if (element instanceof TLObject inner && !(element instanceof ModuleDefinition)) {
				collect(inner, result);
			}
		}
	}

	/**
	 * The text of the given button as messages name it: its text and ID.
	 */
	public static String label(Button button) {
		ResKey text = button.getText();
		String id = ModuleParts.template(button).getExtId();
		String shown = text == null ? null : Resources.getInstance().getString(text);
		if (shown == null || shown.isBlank()) {
			return Objects.toString(id, "?");
		}
		return id == null ? shown : shown + " (" + id + ")";
	}

	/**
	 * Why the given button cannot be pressed for the device: the current user may not change the
	 * device, or E-Home cannot run its handler; <code>null</code> if it can.
	 */
	public static ResKey veto(TLObject device, Button button) {
		ResKey denied = DeviceSettings.writeVeto(device);
		if (denied != null) {
			return denied;
		}
		String problem = problem(button);
		if (problem != null) {
			return I18NConstants.ERROR_CANNOT_RUN__BUTTON_PROBLEM.fill(label(button), problem);
		}
		return null;
	}

	/**
	 * Presses the given button of the device: runs its handler and applies the values it sets as a
	 * person's changes.
	 *
	 * <p>
	 * Must run in a transaction, which the caller rolls back on a refusal: the values applied before
	 * the refusal are in it.
	 * </p>
	 *
	 * @param button
	 *        A button shown in the configuration of the device (of a module instance: the button in
	 *        its instance, as the configuration gives it).
	 * @throws TopLogicException
	 *         With the reason, if the button cannot be pressed, its handler fails, or a value it sets is
	 *         refused.
	 */
	public static void press(TLObject device, Button button) {
		ResKey veto = veto(device, button);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Button shown = null;
		for (Option item : configuration.getShownItems().keySet()) {
			if (item instanceof Button candidate && candidate.equals(button)) {
				shown = candidate;
				break;
			}
		}
		if (shown == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_IN_EFFECT__BUTTON.fill(label(button)));
		}
		Button template = ModuleParts.template(shown);
		Access access = new Access(device, configuration, ModuleParts.instance(shown));
		try {
			JavaScriptLanguage.INSTANCE.handle(library(template), template.getHandler(), access,
				shown.getHandlerParameters());
		} catch (CalculationException ex) {
			throw new TopLogicException(I18NConstants.ERROR_FAILED__BUTTON_PROBLEM.fill(label(shown), ex.getMessage()));
		}
		for (Map.Entry<ParameterView, Object> write : access._writes.entrySet()) {
			ParameterView view = write.getKey();
			try {
				ParameterType type = ParameterValues.valueType(view);
				Object typed = DeviceSettings.typed(type, input(type, write.getValue()));
				if (Objects.equals(ParameterValues.encodeValue(type, typed),
					ParameterValues.encodeValue(type, DeviceSettings.effectiveValue(device, view)))) {
					// The value it has: no change.
					continue;
				}
				DeviceSettings.setValue(device, view, typed);
			} catch (TopLogicException ex) {
				throw new TopLogicException(
					I18NConstants.ERROR_REFUSED__BUTTON_PARAMETER_REASON.fill(label(shown), view, ex.getErrorKey()));
			}
		}
	}

	/**
	 * The value a handler set as the input of a person's change: a whole number as a whole number (a
	 * script has no other), a truth value as 1 or 0, nothing as the empty text for a text.
	 */
	private static Object input(ParameterType type, Object value) {
		if (value == null) {
			if (ParameterValues.TEXT.equals(ParameterValues.valueAttribute(type))) {
				return "";
			}
			throw new TopLogicException(I18NConstants.ERROR_NO_VALUE);
		}
		if (value instanceof Boolean flag) {
			return Long.valueOf(flag.booleanValue() ? 1 : 0);
		}
		if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
			&& !Double.isInfinite(number.doubleValue())) {
			return Long.valueOf(number.longValue());
		}
		return value;
	}

	/**
	 * The device as the handler of a button sees it: the parameters of the device (within the module
	 * instance of the button), with the values the handler set so far.
	 */
	private static final class Access implements ButtonDevice {

		private final TLObject _device;

		private final DeviceConfiguration _configuration;

		private final InstanceInfo _instance;

		private final Program _program;

		/** The values the handler set, in the order of the parameters first set. */
		final Map<ParameterView, Object> _writes = new LinkedHashMap<>();

		private Map<String, ParameterView> _byId;

		Access(TLObject device, DeviceConfiguration configuration, InstanceInfo instance) {
			_device = device;
			_configuration = configuration;
			_instance = instance;
			_program = configuration.getProgram();
		}

		@Override
		public Object parameterById(String id) throws CalculationException {
			String key = id.trim();
			String programId = _program.getExtId();
			String full = key.startsWith(programId + "_") ? key : programId + "_" + key;
			ParameterView template = index().get(full);
			return template == null ? null : place(template, key);
		}

		@Override
		public Object parameterByName(String name) throws CalculationException {
			String key = name.trim();
			Set<? extends Parameter> parameters =
				_instance == null ? _program.getParameters() : _instance.getDefinition().getParameters();
			for (Parameter parameter : parameters) {
				if (key.equals(parameter.tValueByName("name"))) {
					ParameterView active =
						_configuration.getActiveViews().get(ModuleParts.wrap(parameter, _instance));
					if (active != null) {
						return active;
					}
					if (!parameter.getViews().isEmpty()) {
						return place(parameter.getViews().iterator().next(), key);
					}
				}
			}
			return null;
		}

		/**
		 * The given placement of the program or of a module definition as the button reaches it: in the
		 * button's module instance (or in an instance enclosing it).
		 */
		private ParameterView place(ParameterView template, String key) throws CalculationException {
			ModuleDefinition definition = ModuleParts.definitionOf(template);
			if (definition == null) {
				return template;
			}
			for (InstanceInfo current = _instance; current != null; current = current.getParent()) {
				if (current.getDefinition() == definition) {
					return (ParameterView) ModuleParts.wrap(template, current);
				}
			}
			throw new CalculationException("the parameter " + key + " belongs to another module than the button");
		}

		private Map<String, ParameterView> index() {
			if (_byId == null) {
				_byId = new HashMap<>();
				addViews(_program.getParameters());
				List<ModuleDefinition> todo = new ArrayList<>(_program.getModules());
				while (!todo.isEmpty()) {
					ModuleDefinition definition = todo.remove(0);
					addViews(definition.getParameters());
					todo.addAll(definition.getModules());
				}
			}
			return _byId;
		}

		private void addViews(Iterable<? extends Parameter> parameters) {
			for (Parameter parameter : parameters) {
				for (ParameterView view : parameter.getViews()) {
					String id = view.getExtId();
					if (id != null) {
						_byId.putIfAbsent(id, view);
					}
				}
			}
		}

		@Override
		public Object value(Object parameter) {
			ParameterView view = (ParameterView) parameter;
			if (_writes.containsKey(view)) {
				return _writes.get(view);
			}
			Object value = DeviceCalculations.scriptValue(DeviceSettings.effectiveValue(_device, view));
			if (value == null
				&& ParameterValues.TEXT.equals(ParameterValues.valueAttribute(ParameterValues.valueType(view)))) {
				return "";
			}
			return value;
		}

		@Override
		public void setValue(Object parameter, Object value) {
			_writes.put((ParameterView) parameter, value);
		}
	}

}
