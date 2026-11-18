/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.template.ObjectTypes;

/**
 * The flags and the priority of a communication object of a device (haumacher/ehome #201): the
 * flags K, L, S, Ü, A, I (communication, read, write, transmit, update, read on init) and the
 * priority, as ETS edits them per object of a device.
 *
 * <p>
 * The device's connector of the object (<code>ehome.physical:Connector</code>) stores only what
 * differs from the program: an attribute without value means the program's value, that of the
 * placement of the object (<code>ehome.operational:Interface</code>), else that of the object
 * (<code>ehome.operational:Port</code>). Every value set here is normalized: a value equal to the
 * program's is stored as no value, so that "the object differs from its program" is exactly "the
 * connector holds a value". The import keeps what ETS wrote (also a value equal to the program's,
 * for the round trip), which means the same.
 * </p>
 */
public final class ObjectFlags {

	/** The attribute of the priority (<code>ehome.physical:Priority</code>). */
	public static final String PRIORITY = "priority";

	/**
	 * The attributes of the flags (<code>ehome.common:Enablement</code>), in the order of ETS: K,
	 * L, S, Ü, A, I.
	 */
	public static final List<String> FLAGS =
		List.of("communication", "canRead", "canWrite", "transmission", "update", "readOnInit");

	/** The flags and the priority. */
	public static final List<String> ATTRIBUTES =
		List.of("communication", "canRead", "canWrite", "transmission", "update", "readOnInit", PRIORITY);

	private static final String ENABLED = "Enabled";

	private static final String DISABLED = "Disabled";

	private ObjectFlags() {
		// Static utility.
	}

	/**
	 * The flags and the priority of an object, by the names of their classifiers (no entry for an
	 * attribute without value anywhere).
	 *
	 * @param values
	 *        Per attribute of {@link #ATTRIBUTES}, the name of its classifier.
	 */
	public record State(Map<String, String> values) {

		/**
		 * Whether the given flag is set.
		 */
		public boolean enabled(String flag) {
			return ENABLED.equals(values.get(flag));
		}

		/**
		 * The flags and the priority for a person: the letters of the flags set, in the order of
		 * ETS, and the priority ("K L Ü, Niedrig"); "–" without any flag.
		 */
		public String text() {
			Resources resources = Resources.getInstance();
			List<String> letters = new ArrayList<>();
			for (int n = 0; n < FLAGS.size(); n++) {
				if (enabled(FLAGS.get(n))) {
					letters.add(resources.getString(LETTERS.get(n)));
				}
			}
			String result = letters.isEmpty() ? "–" : String.join(" ", letters);
			String priority = values.get(PRIORITY);
			if (priority != null) {
				result += ", " + MetaLabelProvider.INSTANCE.getLabel(classifier(PRIORITY, priority));
			}
			return result;
		}
	}

	private static final List<ResKey> LETTERS = List.of(I18NConstants.FLAG_COMMUNICATION, I18NConstants.FLAG_READ,
		I18NConstants.FLAG_WRITE, I18NConstants.FLAG_TRANSMIT, I18NConstants.FLAG_UPDATE,
		I18NConstants.FLAG_READ_ON_INIT);

	/**
	 * The program's value of an attribute of {@link #ATTRIBUTES} at the given placement of an
	 * object: the placement's, else the object's; <code>null</code> if neither has one.
	 */
	public static String programValue(Interface placed, String attribute) {
		if (placed == null) {
			return null;
		}
		for (TLObject holder : new TLObject[] { placed, placed.getPort() }) {
			if (holder != null && holder.tValueByName(attribute) instanceof TLClassifier classifier) {
				return classifier.getName();
			}
		}
		return null;
	}

	/**
	 * The value of an attribute in effect: the connector's, else the program's.
	 *
	 * @param connector
	 *        The connector of the object, <code>null</code> for none; also one of an earlier
	 *        revision.
	 */
	public static String value(TLObject connector, Interface placed, String attribute) {
		if (connector != null && connector.tValueByName(attribute) instanceof TLClassifier classifier) {
			return classifier.getName();
		}
		return programValue(placed, attribute);
	}

	/**
	 * The flags and the priority in effect at the given connector (<code>null</code> for the
	 * program's).
	 */
	public static State state(TLObject connector, Interface placed) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String attribute : ATTRIBUTES) {
			String value = value(connector, placed, attribute);
			if (value != null) {
				values.put(attribute, value);
			}
		}
		return new State(values);
	}

	/**
	 * Whether the flags or the priority in effect at the given connector differ from the
	 * program's.
	 */
	public static boolean differs(TLObject connector, Interface placed) {
		return connector != null && !state(connector, placed).equals(state(null, placed));
	}

	/**
	 * Stores a value of an attribute at the connector, normalized: nothing where it is the
	 * program's. Must run in a transaction.
	 *
	 * @param value
	 *        The name of the classifier, <code>null</code> for the program's value.
	 * @return Whether the connector changed.
	 */
	public static boolean store(TLObject connector, Interface placed, String attribute, String value) {
		String stored = Objects.equals(value, programValue(placed, attribute)) ? null : value;
		Object before = connector.tValueByName(attribute);
		String current = before instanceof TLClassifier classifier ? classifier.getName() : null;
		if (Objects.equals(current, stored)) {
			return false;
		}
		connector.tUpdateByName(attribute, stored == null ? null : classifier(attribute, stored));
		return true;
	}

	/**
	 * Stores the given flags and priority at the connector, normalized, see
	 * {@link #store(TLObject, Interface, String, String)}.
	 *
	 * @return Whether the connector changed.
	 */
	public static boolean store(TLObject connector, Interface placed, State state) {
		boolean changed = false;
		for (String attribute : ATTRIBUTES) {
			changed |= store(connector, placed, attribute, state.values().get(attribute));
		}
		return changed;
	}

	/**
	 * The value of a flag or the priority of the given object of the device in effect: a
	 * {@link Boolean} for a flag, the classifier of <code>ehome.physical:Priority</code> for the
	 * priority.
	 */
	public static Object get(TLObject device, Interface placed, String attribute) {
		checkAttribute(attribute);
		String value = value(ObjectTypes.connector(device, placed), placed, attribute);
		if (PRIORITY.equals(attribute)) {
			return value == null ? null : classifier(attribute, value);
		}
		return Boolean.valueOf(ENABLED.equals(value));
	}

	/**
	 * Sets a flag or the priority of the given object of the device (the connector of the object
	 * is created where the project holds none). The device is changed since its last download
	 * when the value in effect changes. Must run in a transaction.
	 *
	 * @param value
	 *        A {@link Boolean} for a flag, a classifier of <code>ehome.physical:Priority</code> for
	 *        the priority; <code>null</code> for the program's value.
	 * @return The value in effect afterwards, see {@link #get(TLObject, Interface, String)}.
	 */
	public static Object set(TLObject device, Interface placed, String attribute, Object value) {
		checkAttribute(attribute);
		if (!ExtIds.isA(device, "ehome.physical:Device") || placed == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_AN_OBJECT__ELEMENT.fill(placed));
		}
		String name;
		if (value == null) {
			name = null;
		} else if (PRIORITY.equals(attribute)) {
			name = value instanceof TLClassifier classifier ? classifier.getName() : null;
		} else {
			name = Boolean.TRUE.equals(value) ? ENABLED : DISABLED;
		}
		TLObject connector = ObjectTypes.connector(device, placed);
		if (connector == null) {
			if (name == null || name.equals(programValue(placed, attribute))) {
				return get(device, placed, attribute);
			}
			connector = createConnector(device, placed);
		}
		if (store(connector, placed, attribute, name)) {
			touch(device);
		}
		return get(device, placed, attribute);
	}

	/**
	 * Sets the flags and the priority of the given object of the device back to the program's.
	 * Must run in a transaction.
	 */
	public static void reset(TLObject device, Interface placed) {
		restore(device, placed, state(null, placed));
	}

	/**
	 * Sets the flags and the priority of the given object of the device to the given ones (the
	 * connector of the object is created where the project holds none and they differ from the
	 * program's). Must run in a transaction.
	 */
	public static void restore(TLObject device, Interface placed, State state) {
		TLObject connector = ObjectTypes.connector(device, placed);
		if (connector == null) {
			if (state.equals(state(null, placed))) {
				return;
			}
			connector = createConnector(device, placed);
		}
		if (store(connector, placed, state)) {
			touch(device);
		}
	}

	/**
	 * The program's flags and priority for a person where the object of the device differs from
	 * them, <code>null</code> where it does not.
	 */
	public static String programText(TLObject device, Interface placed) {
		TLObject connector = ObjectTypes.connector(device, placed);
		return differs(connector, placed) ? state(null, placed).text() : null;
	}

	/**
	 * The flags and the priority in effect of the given object of the device, for a person.
	 */
	public static String text(TLObject device, Interface placed) {
		return state(ObjectTypes.connector(device, placed), placed).text();
	}

	/**
	 * Whether the given object of the device differs from the program in its flags or priority.
	 */
	public static boolean objectDiffers(TLObject device, Interface placed) {
		return differs(ObjectTypes.connector(device, placed), placed);
	}

	private static TLObject createConnector(TLObject device, Interface placed) {
		TLObject connector =
			ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType("ehome.physical:Connector"));
		ModuleParts.bind(connector, ModuleParts.PORT, placed);
		if (new DeviceConfiguration(device).getActiveInterfaces().containsKey(placed.getPort())) {
			connector.tUpdateByName("active", Boolean.TRUE);
		}
		List<Object> connectors = new ArrayList<>((Collection<?>) device.tValueByName("connectors"));
		connectors.add(connector);
		device.tUpdateByName("connectors", connectors);
		return connector;
	}

	private static void touch(TLObject device) {
		device.tUpdateByName("lastModified", new Date());
	}

	private static void checkAttribute(String attribute) {
		if (!ATTRIBUTES.contains(attribute)) {
			throw new IllegalArgumentException("No flag of a communication object: " + attribute);
		}
	}

	private static TLClassifier classifier(String attribute, String name) {
		String type = PRIORITY.equals(attribute) ? "ehome.physical:Priority" : "ehome.common:Enablement";
		return ((TLEnumeration) TLModelUtil.findType(type)).getClassifier(name);
	}

}
