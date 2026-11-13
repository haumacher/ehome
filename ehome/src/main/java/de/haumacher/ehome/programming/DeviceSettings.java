/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.security.ModelAccessRights;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.simple.SimpleBoundCommandGroup;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.calculation.DeviceCalculations;
import de.haumacher.ehome.calculation.DeviceValidations;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.modules.ModuleParts;

/**
 * Changes of the configuration of a device (<code>ehome.physical:Device</code>): its parameter
 * values and the group addresses its communication objects are connected to.
 *
 * <p>
 * A device keeps a setting (<code>ehome.physical:ParameterSetting</code>) exactly for the values that
 * differ from their default (the placement's own, else the parameter's, see
 * {@link DeviceConfiguration#defaultHolder(ParameterView)}): {@link #setValue(TLObject, ParameterView, Object)}
 * creates the setting for a value other than the default and removes it when the value is set back
 * to the default, so that the settings stay "what the project changed" and a re-import finds
 * nothing to duplicate. A setting stores the typed value; its ETS notation (<code>encodedValue</code>)
 * is the one the import reads.
 * </p>
 *
 * <p>
 * Every change marks the device as modified (<code>lastModified</code>), so that it shows a pending
 * download. The operations must run in a transaction; they refuse with a {@link TopLogicException}
 * naming the reason.
 * </p>
 */
public class DeviceSettings {

	private static final String SETTINGS = "settings";

	private static final String CONNECTORS = "connectors";

	private static final String CONNECTIONS = "connections";

	private static final String PARAMETER = "parameter";

	private static final String PORT = "port";

	private static final String KIND = "kind";

	private static final String TARGET = "target";

	private static final String SEND = "send";

	private static final String RECEIVE = "receive";

	/**
	 * Why the value of the given placement cannot be changed for the device, <code>null</code> if it
	 * can.
	 */
	public static ResKey editVeto(TLObject device, ParameterView view) {
		ResKey denied = writeVeto(device);
		if (denied != null) {
			return denied;
		}
		return editVeto(new DeviceConfiguration(device), view);
	}

	/**
	 * Why the current user may not change the given device (the model access rights, see
	 * <code>WEB-INF/autoconf/ehome.security.config.xml</code>), <code>null</code> if the user may.
	 *
	 * <p>
	 * The values of a device are changed by Java code, which the model access check of TL-Script does
	 * not cover, so the operations check the right themselves. Code running without a user (a test, a
	 * background job) is not checked.
	 * </p>
	 */
	public static ResKey writeVeto(TLObject device) {
		Person person = TLContext.getContext() == null ? null : TLContext.getContext().getPerson();
		if (person == null || device.tTransient()) {
			return null;
		}
		if (ModelAccessRights.getInstance().isAllowed(person, device, SimpleBoundCommandGroup.WRITE)) {
			return null;
		}
		return I18NConstants.ERROR_NOT_ALLOWED__DEVICE.fill(device);
	}

	private static ResKey editVeto(DeviceConfiguration configuration, ParameterView view) {
		if (!configuration.isShown(view)) {
			// A hidden R parameter a calculation computes (haumacher/ehome #123) is in the configuration.
			ResKey computed = DeviceCalculations.computedBy(configuration, view);
			if (computed != null) {
				return computed;
			}
			return I18NConstants.ERROR_NOT_IN_CONFIGURATION__ELEMENT.fill(view);
		}
		if (configuration.isAssigned(view)) {
			// The program gives the value (haumacher/ehome #142): a person changes the source.
			ParameterView source = configuration.getAssignmentSource(view);
			return source == null ? I18NConstants.ERROR_ASSIGNED__PARAMETER.fill(view)
				: I18NConstants.ERROR_ASSIGNED_FROM__PARAMETER_SOURCE.fill(view, source);
		}
		if (!DeviceConfiguration.isEditable(view)) {
			if (ParameterValues.valueAttribute(ParameterValues.valueType(view)) == null) {
				return I18NConstants.ERROR_NO_VALUE__PARAMETER.fill(view);
			}
			return I18NConstants.ERROR_READ_ONLY__PARAMETER.fill(view);
		}
		// A parameter of a calculation E-Home cannot evaluate (haumacher/ehome #123).
		ResKey calculation = DeviceCalculations.editVeto(configuration, view);
		if (calculation != null) {
			return calculation;
		}
		// A parameter of a validation E-Home cannot evaluate (haumacher/ehome #124).
		return DeviceValidations.editVeto(configuration, view);
	}

	/**
	 * The device's setting of the given placement, <code>null</code> if the device has its default.
	 */
	public static TLObject setting(TLObject device, ParameterView view) {
		List<TLObject> settings = settings(device, view);
		return settings.isEmpty() ? null : settings.get(0);
	}

	private static List<TLObject> settings(TLObject device, ParameterView view) {
		List<TLObject> result = new ArrayList<>();
		for (Object setting : (Collection<?>) device.tValueByName(SETTINGS)) {
			if (ModuleParts.refersTo((TLObject) setting, PARAMETER, view)) {
				result.add((TLObject) setting);
			}
		}
		return result;
	}

	/**
	 * The effective value of the given placement for the device, typed (see
	 * {@link ParameterValues#value(TLObject)}).
	 */
	public static Object value(TLObject device, ParameterView view) {
		return ParameterValues.value(DeviceConfiguration.valueHolder(device, view));
	}

	/**
	 * The effective value of the given placement for the device as its configuration has it, typed:
	 * for a placement an assignment of the program in effect gives its value (haumacher/ehome #142) the
	 * assigned value, otherwise {@link #value(TLObject, ParameterView)}.
	 */
	public static Object effectiveValue(TLObject device, ParameterView view) {
		return ParameterValues.value(effectiveHolder(device, view));
	}

	/**
	 * The object holding the effective value of the given placement for the device, see
	 * {@link DeviceConfiguration#effectiveHolder(ParameterView)}; computes the configuration only for a
	 * placement the program assigns somewhere.
	 */
	public static TLObject effectiveHolder(TLObject device, ParameterView view) {
		if (ModuleParts.template(view).getAssigns().isEmpty()) {
			return DeviceConfiguration.valueHolder(device, view);
		}
		return new DeviceConfiguration(device).effectiveHolder(view);
	}

	/**
	 * Sets the value of the given placement for the device.
	 *
	 * @param device
	 *        The device.
	 * @param view
	 *        A placement of a parameter shown in the device's configuration, whose value can be
	 *        changed.
	 * @param value
	 *        The value fitting the type of the parameter: an entry of an enumeration (or its numeric
	 *        value), a whole number for an integer or a time, a number for a floating point type, a
	 *        text for every other type; <code>null</code> for the default.
	 * @return The setting holding the value, <code>null</code> if the device has the default now.
	 */
	public static TLObject setValue(TLObject device, ParameterView view, Object value) {
		ResKey denied = writeVeto(device);
		if (denied != null) {
			throw new TopLogicException(denied);
		}
		List<TLObject> settings = settings(device, view);
		ParameterType type = ParameterValues.valueType(view);
		Object before = settings.isEmpty() ? null : ParameterValues.value(settings.get(0));
		String old = ParameterValues.encode(DeviceConfiguration.valueHolder(device, view));
		Object previous = value(device, view);
		if (value == null) {
			// Back to the default is always possible, also for a setting the structure no longer shows
			// (unless a calculation the default feeds refuses it, or a check of the manufacturer refuses
			// the default with the other values).
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			ResKey calculationVeto = DeviceCalculations.editVeto(configuration, view);
			if (calculationVeto == null) {
				calculationVeto = DeviceValidations.editVeto(configuration, view);
			}
			if (calculationVeto != null && !settings.isEmpty()) {
				throw new TopLogicException(calculationVeto);
			}
			TLObject result = store(device, view, settings, null, null);
			ModuleInstances.update(device);
			validate(device, view, type, before, old, previous);
			calculate(device, view, type, before, old);
			return result;
		}
		ResKey veto = editVeto(device, view);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		Object typed = typed(type, value);
		TLObject result = store(device, view, settings, type, typed);
		List<ResKey> problems = ModuleInstances.update(device);
		if (!problems.isEmpty()) {
			// The value would give the device's modules no valid layout: it is refused, the value before
			// restored.
			store(device, view, settings(device, view), type, before);
			ModuleInstances.update(device);
			throw new TopLogicException(problems.get(0));
		}
		validate(device, view, type, before, old, previous);
		calculate(device, view, type, before, old);
		return result;
	}

	/**
	 * Checks the changed value of the given placement with the validations of the manufacturer that read
	 * it (haumacher/ehome #124), before the calculations it feeds run: a person's input is checked as
	 * entered. If one refuses, the value before is restored and the refusal thrown.
	 *
	 * @param previous
	 *        The value in effect before the change.
	 */
	private static void validate(TLObject device, ParameterView view, ParameterType type, Object before, String old,
			Object previous) {
		if (Objects.equals(old, ParameterValues.encode(DeviceConfiguration.valueHolder(device, view)))) {
			// Nothing changed.
			return;
		}
		try {
			DeviceValidations.check(device, view, previous);
		} catch (TopLogicException ex) {
			store(device, view, settings(device, view), type, before);
			ModuleInstances.update(device);
			throw ex;
		}
	}

	/**
	 * Runs the calculations the changed value of the given placement feeds (haumacher/ehome #123); if
	 * one refuses, the value before is restored and the refusal thrown.
	 */
	private static void calculate(TLObject device, ParameterView view, ParameterType type, Object before,
			String old) {
		if (Objects.equals(old, ParameterValues.encode(DeviceConfiguration.valueHolder(device, view)))) {
			// Nothing changed.
			return;
		}
		try {
			DeviceCalculations.changed(device, List.of(view));
		} catch (TopLogicException ex) {
			store(device, view, settings(device, view), type, before);
			ModuleInstances.update(device);
			throw ex;
		}
	}

	/**
	 * Stores a value a calculation computed for the given placement (haumacher/ehome #123): as
	 * {@link #setValue(TLObject, ParameterView, Object)} keeps it (a setting only where it differs from
	 * the default), without checking the access of the placement (an R parameter is usually hidden) and
	 * without running calculations.
	 *
	 * @param value
	 *        The typed value (see {@link #typed(ParameterType, Object)}); <code>null</code> for the
	 *        default.
	 */
	public static void storeComputed(TLObject device, ParameterView view, Object value) {
		store(device, view, settings(device, view), ParameterValues.valueType(view), value);
	}

	/**
	 * Sets the value of the given placement for the device like
	 * {@link #setValue(TLObject, ParameterView, Object)}, but reports a refusal instead of throwing
	 * it.
	 *
	 * @return <code>null</code> if the value was applied, otherwise why not; nothing is changed then.
	 */
	public static ResKey applyValue(TLObject device, ParameterView view, Object value) {
		try {
			setValue(device, view, value);
			return null;
		} catch (TopLogicException ex) {
			return ex.getErrorKey();
		}
	}

	/**
	 * Restores a value the device had before (see {@link DeviceChanges}): like
	 * {@link #setValue(TLObject, ParameterView, Object)}, but also for a placement the current
	 * values do not show (a value that opens it may be restored after it), and without the checks of the
	 * manufacturer (haumacher/ehome #124): the value is one the device had, not a person's input.
	 *
	 * @return The setting holding the value, <code>null</code> if the device has the default now.
	 */
	public static TLObject restoreValue(TLObject device, ParameterView view, Object value) {
		return restoreValue(device, view, value, true);
	}

	/**
	 * Restores a value like {@link #restoreValue(TLObject, ParameterView, Object)}.
	 *
	 * @param calculate
	 *        Whether the calculations the value feeds run (haumacher/ehome #123): not when a former
	 *        state of all values is restored, whose computed values are restored with it.
	 */
	public static TLObject restoreValue(TLObject device, ParameterView view, Object value, boolean calculate) {
		List<TLObject> settings = settings(device, view);
		ParameterType type = ParameterValues.valueType(view);
		Object before = settings.isEmpty() ? null : ParameterValues.value(settings.get(0));
		String old = ParameterValues.encode(DeviceConfiguration.valueHolder(device, view));
		TLObject result;
		if (value == null) {
			result = store(device, view, settings, null, null);
		} else {
			result = store(device, view, settings, type, typed(type, value));
		}
		ModuleInstances.update(device);
		if (calculate) {
			calculate(device, view, type, before, old);
		}
		return result;
	}

	private static TLObject store(TLObject device, ParameterView view, List<TLObject> settings, ParameterType type,
			Object value) {
		String standard = ParameterValues.encode(DeviceConfiguration.defaultHolder(view));
		if (value == null || Objects.equals(ParameterValues.encodeValue(type, value), standard)) {
			if (!settings.isEmpty()) {
				for (TLObject setting : settings) {
					setting.tDelete();
				}
				touch(device);
			}
			return null;
		}
		TLObject setting;
		if (settings.isEmpty()) {
			setting = create("ehome.physical:ParameterSetting");
			ModuleParts.bind(setting, PARAMETER, view);
			add(device, SETTINGS, setting);
		} else {
			setting = settings.get(0);
			for (TLObject duplicate : settings.subList(1, settings.size())) {
				duplicate.tDelete();
			}
			if (Objects.equals(ParameterValues.value(setting), value)) {
				return setting;
			}
		}
		for (String attribute : List.of(ParameterValues.CHOICE, ParameterValues.NUMBER, ParameterValues.DECIMAL,
			ParameterValues.TEXT)) {
			setting.tUpdateByName(attribute, null);
		}
		setting.tUpdateByName(ParameterValues.valueAttribute(type), value);
		touch(device);
		return setting;
	}

	/**
	 * The given value as a value of the given type, see {@link #setValue(TLObject, ParameterView, Object)}.
	 *
	 * @throws TopLogicException
	 *         With the reason, if the value does not fit the type (not a whole number, outside the
	 *         range, no entry, a text too long).
	 */
	public static Object typed(ParameterType type, Object value) {
		String attribute = ParameterValues.valueAttribute(type);
		if (attribute == null) {
			return null;
		}
		switch (attribute) {
			case ParameterValues.CHOICE: {
				EnumerationType enumeration = (EnumerationType) type;
				for (EnumerationValue entry : enumeration.getValues()) {
					if (entry == value
						|| (value instanceof Number number && (number.doubleValue() == entry.getValue()
							|| number.doubleValue() == entry.getUnsignedValue()))) {
						return entry;
					}
				}
				throw new TopLogicException(I18NConstants.ERROR_NO_ENTRY__VALUE_TYPE.fill(value, type.getName()));
			}
			case ParameterValues.NUMBER: {
				Number number = number(type, value);
				if (number.doubleValue() != Math.rint(number.doubleValue())) {
					throw new TopLogicException(I18NConstants.ERROR_NOT_INTEGER__VALUE.fill(value));
				}
				long result = number.longValue();
				long min = type instanceof NumericType numeric ? numeric.getMin() : ((TimeType) type).getMin();
				long max = type instanceof NumericType numeric ? numeric.getMax() : ((TimeType) type).getMax();
				if (result < min || result > max) {
					throw new TopLogicException(I18NConstants.ERROR_OUT_OF_RANGE__VALUE_MIN_MAX.fill(result, min, max));
				}
				return Long.valueOf(result);
			}
			case ParameterValues.DECIMAL: {
				double result = number(type, value).doubleValue();
				FloatingPointType floating = (FloatingPointType) type;
				if (Double.isNaN(result) || result < floating.getMin() || result > floating.getMax()) {
					throw new TopLogicException(I18NConstants.ERROR_OUT_OF_RANGE__VALUE_MIN_MAX.fill(
						ParameterValues.encodeDecimal(result), ParameterValues.encodeDecimal(floating.getMin()),
						ParameterValues.encodeDecimal(floating.getMax())));
				}
				return Double.valueOf(result);
			}
			default: {
				String text = value.toString();
				if (text.isEmpty()) {
					return null;
				}
				if (type instanceof TextType textType && text.length() > textType.getBits() / 8) {
					throw new TopLogicException(I18NConstants.ERROR_TEXT_TOO_LONG__MAX.fill(textType.getBits() / 8));
				}
				return text;
			}
		}
	}

	private static Number number(ParameterType type, Object value) {
		if (value instanceof Number number) {
			return number;
		}
		try {
			return Double.valueOf(value.toString().trim());
		} catch (NumberFormatException ex) {
			throw new TopLogicException(I18NConstants.ERROR_WRONG_VALUE__VALUE_TYPE.fill(value, type.getName()));
		}
	}

	/**
	 * Removes a setting of a device: its parameter takes its default again.
	 */
	public static void reset(TLObject setting) {
		TLObject device = setting == null ? null : setting.tContainer();
		ParameterView view = setting == null ? null : ModuleParts.parameter(setting);
		if (device == null || view == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_A_SETTING__ELEMENT.fill(setting));
		}
		setValue(device, view, null);
	}

	/**
	 * The communication objects of the device that can be connected: those in effect, then those
	 * no longer in effect that still have connections, so that these can be removed.
	 */
	public static List<Interface> interfaces(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Map<Port, Interface> result = new LinkedHashMap<>(configuration.getActiveInterfaces());
		for (Object connector : (Collection<?>) device.tValueByName(CONNECTORS)) {
			TLObject value = (TLObject) connector;
			Interface placed = ModuleParts.port(value);
			if (placed != null && !((Collection<?>) value.tValueByName(CONNECTIONS)).isEmpty()) {
				result.putIfAbsent(placed.getPort(), placed);
			}
		}
		return new ArrayList<>(result.values());
	}

	/**
	 * The connections of the given communication object of the device (of every connector of the
	 * object's definition).
	 */
	public static List<TLObject> connections(TLObject device, Interface placed) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject connector : connectors(device, placed.getPort())) {
			for (Object connection : (Collection<?>) connector.tValueByName(CONNECTIONS)) {
				result.add((TLObject) connection);
			}
		}
		return result;
	}

	private static List<TLObject> connectors(TLObject device, Port port) {
		List<TLObject> result = new ArrayList<>();
		for (Object connector : (Collection<?>) device.tValueByName(CONNECTORS)) {
			TLObject value = (TLObject) connector;
			Interface placed = ModuleParts.port(value);
			if (placed != null && Objects.equals(placed.getPort(), port)) {
				result.add(value);
			}
		}
		return result;
	}

	/**
	 * The kind a new connection of the given communication object has unless chosen otherwise:
	 * sending as long as the object sends to no group address, otherwise receiving.
	 */
	public static TLClassifier defaultKind(TLObject device, Interface placed) {
		for (TLObject connection : connections(device, placed)) {
			if (isSend(connection)) {
				return kind(RECEIVE);
			}
		}
		return kind(SEND);
	}

	/**
	 * The group addresses of the project the device belongs to, in ascending order.
	 */
	public static List<TLObject> groupAddresses(TLObject device) {
		List<TLObject> result = new ArrayList<>();
		TLObject project = project(device);
		if (project != null) {
			collectAddresses((Collection<?>) project.tValueByName("groupAddresses"), result);
		}
		result.sort(Comparator.comparingInt(DeviceSettings::addressValue));
		return result;
	}

	private static void collectAddresses(Collection<?> definitions, List<TLObject> result) {
		for (Object definition : definitions) {
			TLObject value = (TLObject) definition;
			if (isA(value, "ehome.logical:GroupAddress")) {
				result.add(value);
			} else if (isA(value, "ehome.logical:GroupRange")) {
				collectAddresses((Collection<?>) value.tValueByName("parts"), result);
			}
		}
	}

	private static int addressValue(TLObject groupAddress) {
		return groupAddress.tValueByName("address") instanceof GA address ? address.getValue() : Integer.MAX_VALUE;
	}

	private static TLObject project(TLObject obj) {
		for (TLObject current = obj; current != null; current = current.tContainer()) {
			if (isA(current, "ehome.project:Project")) {
				return current;
			}
		}
		return null;
	}

	/**
	 * Connects the given communication object of the device to a group address.
	 *
	 * <p>
	 * The connection is made at the device's connector of the object, which is created if the
	 * project has none yet; the connector is in use from then on (<code>active</code>).
	 * </p>
	 *
	 * @param device
	 *        The device.
	 * @param placed
	 *        A communication object in effect in the device's configuration.
	 * @param groupAddress
	 *        A group address of the device's project (<code>ehome.logical:GroupAddress</code>).
	 * @param kind
	 *        Whether the object sends to the address or receives from it
	 *        (<code>ehome.physical:ConnectionType</code>); an object sends to one address only.
	 * @return The new connection (<code>ehome.physical:Connection</code>).
	 */
	public static TLObject connect(TLObject device, Interface placed, TLObject groupAddress, TLObject kind) {
		return connect(new DeviceConfiguration(device), placed, groupAddress, kind);
	}

	/**
	 * Connects a communication object of the device of the given configuration to a group address,
	 * see {@link #connect(TLObject, Interface, TLObject, TLObject)}.
	 *
	 * @param configuration
	 *        The current configuration of the device (a connection does not change it).
	 */
	public static TLObject connect(DeviceConfiguration configuration, Interface placed, TLObject groupAddress,
			TLObject kind) {
		TLObject device = configuration.getDevice();
		if (!configuration.getActiveInterfaces().containsKey(placed.getPort())) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_IN_CONFIGURATION__ELEMENT.fill(placed));
		}
		if (groupAddress == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_GROUP_ADDRESS);
		}
		if (!(kind instanceof TLClassifier classifier)
			|| !(SEND.equals(classifier.getName()) || RECEIVE.equals(classifier.getName()))) {
			throw new TopLogicException(I18NConstants.ERROR_NO_CONNECTION_KIND);
		}
		if (!isA(groupAddress, "ehome.logical:GroupAddress") || project(groupAddress) != project(device)) {
			throw new TopLogicException(I18NConstants.ERROR_FOREIGN_GROUP_ADDRESS__ADDRESS.fill(groupAddress));
		}
		for (TLObject connection : connections(device, placed)) {
			if (connection.tValueByName(TARGET) == groupAddress) {
				throw new TopLogicException(
					I18NConstants.ERROR_ALREADY_CONNECTED__OBJECT_ADDRESS.fill(placed, groupAddress));
			}
			if (SEND.equals(classifier.getName()) && isSend(connection)) {
				throw new TopLogicException(
					I18NConstants.ERROR_SENDS_ALREADY__OBJECT_ADDRESS.fill(placed, connection.tValueByName(TARGET)));
			}
		}
		List<TLObject> connectors = connectors(device, placed.getPort());
		TLObject connector;
		if (connectors.isEmpty()) {
			connector = create("ehome.physical:Connector");
			ModuleParts.bind(connector, PORT, placed);
			add(device, CONNECTORS, connector);
		} else {
			connector = connectors.get(0);
		}
		if (!Boolean.TRUE.equals(connector.tValueByName("active"))) {
			connector.tUpdateByName("active", Boolean.TRUE);
		}
		TLObject connection = create("ehome.physical:Connection");
		connection.tUpdateByName(KIND, kind);
		connection.tUpdateByName(TARGET, groupAddress);
		add(connector, CONNECTIONS, connection);
		touch(device);
		return connection;
	}

	/**
	 * Removes a connection of a communication object of a device to a group address.
	 *
	 * <p>
	 * The connector stays: it may carry the project's own flags or texts of the object.
	 * </p>
	 */
	public static void disconnect(TLObject connection) {
		TLObject connector = connection == null ? null : connection.tContainer();
		TLObject device = connector == null ? null : connector.tContainer();
		if (device == null || !isA(connection, "ehome.physical:Connection")) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_A_CONNECTION__ELEMENT.fill(connection));
		}
		connection.tDelete();
		touch(device);
	}

	private static boolean isSend(TLObject connection) {
		return connection.tValueByName(KIND) instanceof TLClassifier classifier && SEND.equals(classifier.getName());
	}

	private static TLClassifier kind(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.physical:ConnectionType")).getClassifier(name);
	}

	/**
	 * Removes settings of the device (<code>ehome.physical:ParameterSetting</code>): their placements
	 * take their defaults again.
	 */
	public static void removeSettings(TLObject device, Collection<TLObject> settings) {
		if (settings.isEmpty()) {
			return;
		}
		for (TLObject setting : settings) {
			setting.tDelete();
		}
		touch(device);
		ModuleInstances.update(device);
	}

	private static void touch(TLObject device) {
		device.tUpdateByName("lastModified", new Date());
	}

	private static boolean isA(TLObject obj, String typeName) {
		return ExtIds.isA(obj, typeName);
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	private static void add(TLObject container, String reference, TLObject element) {
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValueByName(reference));
		elements.add(element);
		container.tUpdateByName(reference, elements);
	}

}
