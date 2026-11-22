/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.ObjectFlags;

/**
 * The KNX datapoints the rules of a project read that the controller cannot read on start
 * (haumacher/ehome #216): after a restart such a rule is blind until the bus reports the value by
 * itself.
 *
 * <p>
 * A rule reads a datapoint where an expression of it names the datapoint (a condition, a value, a
 * duration, a trigger's condition, a log message: every read of its value). A group address the
 * controller can read on start has an object that transmits on it and answers reads
 * ({@link DatapointSettings#derive(TLObject)}). Where it has none, the connections of the address
 * tell why ({@link Cause}). On a group address exactly one object answers a read (one read flag
 * per address), so:
 * </p>
 * <ul>
 * <li>an object that has the read flag but does not send on the address is reported, and no second
 * read flag is added;</li>
 * <li>the only object sending on the address, without the read flag: E-Home sets the flag
 * ({@link #fix(TLObject)}), which goes out with the device's next download;</li>
 * <li>several objects sending on the address, none with the read flag: the user picks the one that
 * answers ({@link #choices(TLObject)}, {@link #choose(TLObject)});</li>
 * <li>objects connected without sending on the address, or no object at all: the user changes the
 * project.</li>
 * </ul>
 * <p>
 * An address whose read on start or persistence is set by the user is left alone.
 * </p>
 *
 * <p>
 * The problems of a project are computed once per state of its data from the rules as the image
 * exports them ({@link ImageCache}, {@link #problems(TLObject)}), or from the rules of an image
 * being built ({@link #problems(TLObject, List)}): one pass over the expressions of the project's
 * rules and over the connections of the addresses read; no other project is read.
 * </p>
 */
public final class RuleReads {

	/**
	 * Why nobody can be asked for the value of an address.
	 */
	public enum Cause {
		/** The only object sending on the address has no read flag. */
		NO_READ_FLAG,
		/** Several objects send on the address, none with the read flag. */
		SEVERAL_SENDERS,
		/** An object has the read flag, but does not send on the address. */
		READ_FLAG_NOT_SENDING,
		/** An object is connected to the address, but does not send on it. */
		NOT_SENDING,
		/** No object of the project is connected to the address. */
		NO_OBJECT;
	}

	/**
	 * A datapoint a rule reads that the controller cannot read on start.
	 *
	 * @param rule
	 *        The rule (<code>ehome.runtime:Rule</code>).
	 * @param address
	 *        The group address read.
	 * @param cause
	 *        Why nobody can be asked for its value.
	 * @param connections
	 *        The connections of the objects meant (<code>ehome.physical:Connection</code>): one, all
	 *        senders for {@link Cause#SEVERAL_SENDERS}, none for {@link Cause#NO_OBJECT}.
	 */
	public record Problem(TLObject rule, TLObject address, Cause cause, List<TLObject> connections) {

		/**
		 * Whether E-Home fixes the problem by setting the read flag of the only sender.
		 */
		public boolean fixable() {
			return cause == Cause.NO_READ_FLAG;
		}

		/**
		 * The message for the user, naming the rule, the datapoint and the objects.
		 */
		public ResKey message() {
			String rule = ruleName(rule());
			String datapoint = DatapointNames.name(address());
			switch (cause) {
				case NO_READ_FLAG:
					return I18NConstants.RULE_READS_NO_READ_FLAG__RULE_DATAPOINT_OBJECT_DEVICE.fill(rule, datapoint,
						objectName(connections().get(0)), deviceName(connections().get(0)));
				case SEVERAL_SENDERS: {
					Resources resources = Resources.getInstance();
					List<String> objects = new ArrayList<>();
					for (TLObject connection : connections()) {
						objects.add(resources.getString(I18NConstants.RULE_READS_OBJECT__OBJECT_DEVICE
							.fill(objectName(connection), deviceName(connection))));
					}
					return I18NConstants.RULE_READS_SEVERAL_SENDERS__RULE_DATAPOINT_OBJECTS.fill(rule, datapoint,
						String.join(", ", objects));
				}
				case READ_FLAG_NOT_SENDING:
					return I18NConstants.RULE_READS_READ_FLAG_NOT_SENDING__RULE_DATAPOINT_OBJECT_DEVICE.fill(rule,
						datapoint, objectName(connections().get(0)), deviceName(connections().get(0)));
				case NOT_SENDING:
					return I18NConstants.RULE_READS_NOT_SENDING__RULE_DATAPOINT_OBJECT_DEVICE.fill(rule, datapoint,
						objectName(connections().get(0)), deviceName(connections().get(0)));
				default:
					return I18NConstants.RULE_READS_NO_OBJECT__RULE_DATAPOINT.fill(rule, datapoint);
			}
		}
	}

	private static final String KEY = RuleReads.class.getName();

	private RuleReads() {
		// Static only.
	}

	/**
	 * The problems of the rules of the project, in the order of the rules, computed once per state
	 * of the data ({@link ImageCache}).
	 */
	public static List<Problem> problems(TLObject project) {
		if (project == null) {
			return List.of();
		}
		return ImageCache.get(project).derived(KEY, entry -> problems(project, entry.rules()));
	}

	/**
	 * The problems of the given rule.
	 */
	public static List<Problem> ofRule(TLObject rule) {
		List<Problem> result = new ArrayList<>();
		for (Problem problem : problems(RuntimeFunctions.project(rule))) {
			if (problem.rule() == rule) {
				result.add(problem);
			}
		}
		return result;
	}

	/**
	 * The problems of the given rule for the user, one line each; <code>null</code> for none.
	 */
	public static String text(TLObject rule) {
		List<String> lines = new ArrayList<>();
		Resources resources = Resources.getInstance();
		for (Problem problem : ofRule(rule)) {
			lines.add(resources.getString(problem.message()));
		}
		return lines.isEmpty() ? null : String.join("\n", lines);
	}

	/**
	 * Whether a problem of the given rule is fixed by setting the read flag of the only sender
	 * ({@link #fix(TLObject)}).
	 */
	public static boolean fixable(TLObject rule) {
		for (Problem problem : ofRule(rule)) {
			if (problem.fixable()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Sets the read flag of the only object that sends on an address the given rule reads, where it
	 * has none ({@link Cause#NO_READ_FLAG}). The flag is a setting of the device ({@link ObjectFlags}),
	 * so the device has a change for its next download. Must run in a transaction.
	 *
	 * @return The number of objects changed.
	 */
	public static int fix(TLObject rule) {
		Set<TLObject> connections = new LinkedHashSet<>();
		for (Problem problem : ofRule(rule)) {
			if (problem.fixable()) {
				connections.addAll(problem.connections());
			}
		}
		int result = 0;
		for (TLObject connection : connections) {
			result += choose(connection);
		}
		return result;
	}

	/**
	 * The objects the user picks from to answer reads, for each address the given rule reads on
	 * which several objects send, none with the read flag ({@link Cause#SEVERAL_SENDERS}): their
	 * connections, the addresses in the order of the problems.
	 */
	public static List<TLObject> choices(TLObject rule) {
		Set<TLObject> result = new LinkedHashSet<>();
		for (Problem problem : ofRule(rule)) {
			if (problem.cause() == Cause.SEVERAL_SENDERS) {
				result.addAll(problem.connections());
			}
		}
		return new ArrayList<>(result);
	}

	/**
	 * Sets the read flag of the object of the given connection, which then answers the reads of its
	 * address: only where the object sends on the address and no object connected to it has the read
	 * flag yet (exactly one object answers a read of a group address). Must run in a transaction.
	 *
	 * @param connection
	 *        The connection of the object to the address (<code>ehome.physical:Connection</code>).
	 * @return 1 where the flag was set, else 0.
	 */
	public static int choose(TLObject connection) {
		if (connection == null || !connection.tValid() || !DatapointSettings.transmits(connection)) {
			return 0;
		}
		// ehome.physical has no Java binding yet (haumacher/ehome #198): the target through its part.
		Object target = connection.tValue(TLModelUtil.findPart("ehome.physical:Connection#target"));
		if (!(target instanceof TLObject address) || !readers(address).isEmpty()) {
			return 0;
		}
		ObjectFlags.set(GroupAddressConnections.device(connection), GroupAddressConnections.object(connection),
			"canRead", Boolean.TRUE);
		return 1;
	}

	/**
	 * The connections to the address of the objects that have the read flag (whether they send on
	 * it or not, whether they are in effect or not).
	 */
	public static List<TLObject> readers(TLObject address) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject connection : GroupAddressConnections.connections(address)) {
			if (DatapointSettings.enabled("canRead", connection.tContainer(), GroupAddressConnections.object(connection))) {
				result.add(connection);
			}
		}
		return result;
	}

	/**
	 * The problems of the given rules of the project.
	 *
	 * @param rules
	 *        The rules of the project as the image exports them ({@link RuleExport#rules(TLObject)},
	 *        <code>null</code> for none), in the order of {@link RuleExport#orderedRules(TLObject)}.
	 */
	public static List<Problem> problems(TLObject project, List<Object> rules) {
		if (rules == null || rules.isEmpty()) {
			return List.of();
		}
		List<TLObject> ruleObjects = RuleExport.orderedRules(project);
		Map<String, TLObject> addresses = new HashMap<>();
		for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
			if (RuntimeImage.timeMasterId(project, address) == null) {
				addresses.putIfAbsent(GroupAddressIds.datapointId(address), address);
			}
		}
		Map<TLObject, List<Problem>> byAddress = new HashMap<>();
		List<Problem> result = new ArrayList<>();
		for (int n = 0; n < rules.size() && n < ruleObjects.size(); n++) {
			TLObject rule = ruleObjects.get(n);
			Set<String> names = new LinkedHashSet<>();
			reads(rules.get(n), names);
			for (String name : names) {
				TLObject address = addresses.get(name);
				if (address == null) {
					continue;
				}
				for (Problem problem : byAddress.computeIfAbsent(address, RuleReads::diagnose)) {
					result.add(new Problem(rule, address, problem.cause(), problem.connections()));
				}
			}
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * Why nobody can be asked for the value of the address after a restart, one problem per object
	 * meant (without a rule); none where the controller reads it on start or the user decided.
	 */
	private static List<Problem> diagnose(TLObject address) {
		GroupAddress settings = (GroupAddress) address;
		if (settings.getRuntimeReadOnStart() != null || settings.getRuntimePersist()) {
			return List.of();
		}
		if (DatapointSettings.derive(address).readOnStart()) {
			return List.of();
		}
		List<TLObject> connections = GroupAddressConnections.connections(address);
		List<TLObject> senders = new ArrayList<>();
		for (TLObject connection : connections) {
			if (DatapointSettings.transmits(connection)) {
				// Without the read flag, else the derivation would read it on start.
				senders.add(connection);
			}
		}
		List<Problem> result = new ArrayList<>();
		for (TLObject reader : readers(address)) {
			if (!senders.contains(reader)) {
				// The address has its read flag already, on an object that does not answer here.
				result.add(new Problem(null, address, Cause.READ_FLAG_NOT_SENDING, List.of(reader)));
			}
		}
		if (!result.isEmpty()) {
			return result;
		}
		if (senders.size() == 1) {
			return List.of(new Problem(null, address, Cause.NO_READ_FLAG, senders));
		}
		if (senders.size() > 1) {
			return List.of(new Problem(null, address, Cause.SEVERAL_SENDERS, List.copyOf(senders)));
		}
		for (TLObject connection : connections) {
			result.add(new Problem(null, address, Cause.NOT_SENDING, List.of(connection)));
		}
		if (!result.isEmpty()) {
			return result;
		}
		return List.of(new Problem(null, address, Cause.NO_OBJECT, List.of()));
	}

	/**
	 * Collects the names of the datapoints the expressions in the JSON of a rule read: the nodes
	 * <code>{"kind": "datapoint", "name": …}</code> of the expressions (a trigger on a datapoint names
	 * it as <code>datapoint</code>, which is no read of its value).
	 */
	static void reads(Object json, Set<String> result) {
		if (json instanceof Map<?, ?> map) {
			if ("datapoint".equals(map.get("kind")) && map.get("name") instanceof String name) {
				result.add(name);
			}
			for (Object value : map.values()) {
				reads(value, result);
			}
		} else if (json instanceof List<?> list) {
			for (Object value : list) {
				reads(value, result);
			}
		}
	}

	private static String ruleName(TLObject rule) {
		String name = ((Rule) rule).getName();
		return name == null || name.isBlank() ? String.valueOf(((Rule) rule).getIdentifier()) : name.trim();
	}

	private static String objectName(TLObject connection) {
		String text = GroupAddressConnections.objectText(connection);
		return text == null ? "?" : text;
	}

	private static String deviceName(TLObject connection) {
		return GroupAddressConnections.deviceLabel(GroupAddressConnections.device(connection));
	}

}
