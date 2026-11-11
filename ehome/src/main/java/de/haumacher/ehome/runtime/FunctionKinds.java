/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The kinds of functions of the runtime image and their roles, in the order the image writes them
 * (homecontrol <code>spec/runtime-image.md</code> 3.5). Each role is an attribute of
 * <code>ehome.runtime:Function</code> of the same name, referring to a datapoint of the project
 * (<code>ehome.logical:ProjectDatapoint</code>): a group address or an application datapoint
 * (haumacher/ehome #61).
 */
public final class FunctionKinds {

	/**
	 * The roles by kind; the first role of a kind is its main role, which names the function. A
	 * function cannot do without its {@link #requiredRoles(String) required roles}: the main role, for
	 * a dimmer its switching <code>command</code> or its <code>levelCommand</code> (schema 1.6,
	 * haumacher/ehome #134).
	 */
	public static final Map<String, List<String>> ROLES = Map.of(
		"light", List.of("command", "state"),
		"dimmer", List.of("command", "state", "levelCommand", "level", "step"),
		"blind", List.of("move", "stop", "position", "positionCommand", "slat", "slatCommand", "atTop", "atBottom"),
		"switch", List.of("command", "state"),
		"sensor", List.of("value"),
		"climate", List.of("temperature", "setpoint", "setpointCommand", "mode", "modeCommand", "heating",
			"heatingLevel"),
		"scene", List.of("command"),
		"button", List.of("press", "state"));

	/**
	 * The roles of which a dimmer needs at least one (homecontrol <code>spec/runtime-image.md</code>
	 * 3.5, schema 1.6): its switching command, else its level command, which an image of 1.5 required.
	 */
	private static final List<String> DIMMER_REQUIRED = List.of("command", "levelCommand");

	/**
	 * The command roles by kind: the roles through which the controller writes the datapoint
	 * (italic in homecontrol <code>spec/runtime-image.md</code> 3.5), in the order of
	 * {@link #ROLES}.
	 */
	public static final Map<String, List<String>> COMMAND_ROLES = Map.of(
		"light", List.of("command"),
		"dimmer", List.of("levelCommand", "command", "step"),
		"blind", List.of("move", "stop", "positionCommand", "slatCommand"),
		"switch", List.of("command"),
		"climate", List.of("setpointCommand", "modeCommand"),
		"scene", List.of("command"));

	private FunctionKinds() {
		// Static utility.
	}

	/**
	 * The roles of the given kind; none for an unknown kind.
	 */
	public static List<String> roles(String kind) {
		List<String> roles = kind == null ? null : ROLES.get(kind);
		return roles == null ? List.of() : roles;
	}

	/**
	 * The {@link #COMMAND_ROLES command roles} of the given kind; none for a kind without (a
	 * sensor, a button) or an unknown kind.
	 */
	public static List<String> commandRoles(String kind) {
		List<String> roles = kind == null ? null : COMMAND_ROLES.get(kind);
		return roles == null ? List.of() : roles;
	}

	/**
	 * The main role of the given kind, <code>null</code> for an unknown kind.
	 */
	public static String mainRole(String kind) {
		List<String> roles = roles(kind);
		return roles.isEmpty() ? null : roles.get(0);
	}

	/**
	 * The roles of which a function of the given kind needs at least one, in their order: its main
	 * role; for a dimmer its <code>command</code> or its <code>levelCommand</code>. Empty for an
	 * unknown kind.
	 */
	public static List<String> requiredRoles(String kind) {
		if ("dimmer".equals(kind)) {
			return DIMMER_REQUIRED;
		}
		String main = mainRole(kind);
		return main == null ? List.of() : List.of(main);
	}

	/**
	 * The first of the {@link #requiredRoles(String) required roles} of the kind that the given
	 * datapoints by role bind, <code>null</code> for none: the role by which the function is known
	 * (its name, its unit, the key of a proposal).
	 *
	 * @param bound
	 *        The datapoint of a role, <code>null</code> for an unbound one.
	 */
	public static String boundMainRole(String kind, Function<String, Object> bound) {
		for (String role : requiredRoles(kind)) {
			if (bound.apply(role) != null) {
				return role;
			}
		}
		return null;
	}

}
