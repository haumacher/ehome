/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.monitor.DatapointCodec;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;

/**
 * The runtime settings of the datapoint of a group address (<code>ehome.logical:GroupAddress</code>)
 * in the runtime image: what follows from the connected communication objects, and what the user set
 * for the address (haumacher/ehome #45).
 *
 * <p>
 * Derived: an object that receives from the address (write flag) without transmitting on it is an
 * actuator input, which the controller may command, so the datapoint is <i>writable</i>; an object
 * that transmits its value on the address and answers a read there (read flag) is a status or a
 * measured value, so the datapoint is <i>read on start</i>. Each object counts with its flags in
 * effect (the device's own, else those of the object's placement, else those of the object), and only
 * where it communicates and its connector is active.
 * </p>
 *
 * <p>
 * Set for the address (<code>runtimeWritable</code>, <code>runtimeReadOnStart</code>, both empty
 * for "derived"): override the derivation. <code>runtimeReadable</code>,
 * <code>runtimePersist</code>, <code>historyRetention</code>/<code>historySpacing</code> and
 * <code>runtimeUnit</code> have no derivation: unset, the image leaves them out (the unit then is
 * the datapoint type's).
 * </p>
 */
public final class DatapointSettings {

	/**
	 * The flags the connected objects give a group address's datapoint.
	 *
	 * @param writable
	 *        Whether an actuator input receives from the address.
	 * @param readOnStart
	 *        Whether an object reports its value on the address and answers reads.
	 */
	public record Derived(boolean writable, boolean readOnStart) {
		// A record.
	}

	private DatapointSettings() {
		// Static utility.
	}

	/**
	 * The flags the connected objects give the datapoint of the given address.
	 */
	public static Derived derive(TLObject address) {
		boolean receiver = false;
		boolean reporter = false;
		for (TLObject connection : GroupAddressConnections.connections(address)) {
			if (receives(connection)) {
				receiver = true;
			}
			if (transmits(connection) && enabled("canRead", connection)) {
				reporter = true;
			}
		}
		return new Derived(receiver, reporter);
	}

	/**
	 * Whether the object of the connection takes part in the communication: its connector is active
	 * and its communication flag is set.
	 */
	public static boolean communicates(TLObject connection) {
		TLObject connector = connection.tContainer();
		return !Boolean.FALSE.equals(connector.tValueByName("active")) && enabled("communication", connection);
	}

	/**
	 * Whether the object of the connection transmits its value on the connection's address: the
	 * address is its sending one and its transmit flag is set.
	 */
	public static boolean transmits(TLObject connection) {
		boolean send = connection.tValueByName("kind") instanceof TLClassifier kind && "send".equals(kind.getName());
		return communicates(connection) && send && enabled("transmission", connection);
	}

	/**
	 * The KNX object that transmits on the address (its device and name), <code>null</code> when no
	 * object sends on it (a block output bound to it would be a second source, haumacher/ehome #75).
	 */
	public static String sender(TLObject address) {
		for (TLObject connection : GroupAddressConnections.connections(address)) {
			if (transmits(connection)) {
				TLObject connector = connection.tContainer();
				TLObject device = connector.tContainer();
				Object name = connector.tValueByName("displayName");
				if (name == null || name.toString().isBlank()) {
					name = connector.tValueByName("text");
				}
				if (name instanceof com.top_logic.basic.util.ResKey key) {
					name = com.top_logic.util.Resources.getInstance().getString(key);
				}
				return (device == null ? "" : device.tValueByName("name") + ": ") + (name == null ? "?" : name);
			}
		}
		return null;
	}

	/**
	 * Whether the object of the connection is an input taking values from the address: its write flag
	 * is set and it does not transmit on the address.
	 */
	public static boolean receives(TLObject connection) {
		return communicates(connection) && enabled("canWrite", connection) && !transmits(connection);
	}

	private static boolean enabled(String flag, TLObject connection) {
		return enabled(flag, connection.tContainer(), GroupAddressConnections.object(connection));
	}

	/**
	 * Whether the given flag (<code>communication</code>, <code>canRead</code>,
	 * <code>canWrite</code>, <code>transmission</code>) of a communication object is set: the
	 * device's own (its connector), else the one of the object's placement, else the one of the
	 * object.
	 *
	 * @param connector
	 *        The device's connector of the object (<code>ehome.physical:Connector</code>),
	 *        <code>null</code> for an object the project has none for (one connected to no group
	 *        address).
	 * @param placed
	 *        The placement of the object in the device's program.
	 */
	public static boolean enabled(String flag, TLObject connector, Interface placed) {
		Port port = placed == null ? null : placed.getPort();
		for (TLObject holder : new TLObject[] { connector, placed, port }) {
			if (holder != null && holder.tValueByName(flag) instanceof TLClassifier classifier) {
				return "Enabled".equals(classifier.getName());
			}
		}
		return false;
	}

	/**
	 * Whether the datapoint of the address is writable in the image: the setting, else the
	 * derivation.
	 */
	public static boolean writable(TLObject address) {
		Object set = address.tValueByName("runtimeWritable");
		return set instanceof Boolean value ? value.booleanValue() : derive(address).writable();
	}

	/**
	 * Whether the datapoint of the address is read on start: the setting, else the derivation.
	 */
	public static boolean readOnStart(TLObject address) {
		Object set = address.tValueByName("runtimeReadOnStart");
		return set instanceof Boolean value ? value.booleanValue() : derive(address).readOnStart();
	}

	/**
	 * Whether the controller answers reads of the address.
	 */
	public static boolean readable(TLObject address) {
		return Boolean.TRUE.equals(address.tValueByName("runtimeReadable"));
	}

	/**
	 * Whether the controller keeps the datapoint's value across restarts.
	 */
	public static boolean persist(TLObject address) {
		return Boolean.TRUE.equals(address.tValueByName("runtimePersist"));
	}

	/**
	 * The history retention in seconds; <code>null</code> without history.
	 */
	public static Integer historyRetention(TLObject address) {
		return address.tValueByName("historyRetention") instanceof Number value ? Integer.valueOf(value.intValue())
			: null;
	}

	/**
	 * The history spacing in seconds; <code>null</code> without history.
	 */
	public static Integer historySpacing(TLObject address) {
		return address.tValueByName("historySpacing") instanceof Number value ? Integer.valueOf(value.intValue())
			: null;
	}

	/**
	 * The unit of the datapoint: the setting, else the one of its datapoint type;
	 * <code>null</code> for none (or one longer than the image allows). For an application datapoint
	 * (a role of a function may refer to one, haumacher/ehome #61) its unit.
	 */
	public static String unit(TLObject address) {
		if (ApplicationDatapoints.isApplicationDatapoint(address)) {
			Object unit = address.tValueByName("unit");
			return unit == null || unit.toString().isBlank() ? null : unit.toString().trim();
		}
		Object set = address.tValueByName("runtimeUnit");
		if (set != null && !set.toString().isBlank()) {
			return set.toString().trim();
		}
		Dpt dpt = GroupAddressTypes.effective(address);
		String unit = dpt == null ? null : DatapointCodec.unitSymbol(dpt);
		return unit != null && unit.getBytes(StandardCharsets.UTF_8).length <= 15 ? unit : null;
	}

	/**
	 * Where the flags writable and read on start of the address come from, for the user.
	 */
	public static ResKey flagsSource(TLObject address) {
		boolean writable = address.tValueByName("runtimeWritable") != null;
		boolean readOnStart = address.tValueByName("runtimeReadOnStart") != null;
		if (writable && readOnStart) {
			return I18NConstants.FLAGS_SET;
		}
		if (writable) {
			return I18NConstants.FLAGS_WRITABLE_SET;
		}
		if (readOnStart) {
			return I18NConstants.FLAGS_READ_ON_START_SET;
		}
		return I18NConstants.FLAGS_DERIVED;
	}

	/**
	 * What is wrong with the history settings of the address or application datapoint
	 * ({@link ApplicationDatapoints}, which has the same attributes); <code>null</code> if they fit.
	 */
	public static ResKey historyProblem(TLObject address) {
		Integer retention = historyRetention(address);
		Integer spacing = historySpacing(address);
		if (retention == null && spacing == null) {
			return null;
		}
		if (retention == null || spacing == null) {
			return I18NConstants.HISTORY_INCOMPLETE;
		}
		String type;
		if (ApplicationDatapoints.isApplicationDatapoint(address)) {
			type = ApplicationDatapoints.valueType(address);
		} else {
			Dpt dpt = GroupAddressTypes.effective(address);
			type = dpt == null ? null : ValueTypes.valueType(dpt.main(), dpt.isMainType() ? 0 : dpt.sub());
		}
		if (type != null && !ValueTypes.BOOLEAN.equals(type) && !ValueTypes.UNSIGNED.equals(type)
			&& !ValueTypes.SIGNED.equals(type) && !ValueTypes.REAL.equals(type)) {
			return I18NConstants.HISTORY_WRONG_TYPE__TYPE.fill(type);
		}
		if (retention.intValue() < spacing.intValue()) {
			return I18NConstants.HISTORY_RETENTION_BELOW_SPACING;
		}
		long samples = ImageValidator.samples(retention.longValue(), spacing.longValue());
		if (samples > ImageValidator.MAX_SAMPLES) {
			return I18NConstants.HISTORY_TOO_MANY_SAMPLES__SAMPLES_MAX.fill(Long.valueOf(samples),
				Integer.valueOf(ImageValidator.MAX_SAMPLES));
		}
		return null;
	}

}
