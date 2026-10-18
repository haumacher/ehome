/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.List;
import java.util.Map;

/**
 * The kinds of functions of the runtime image and their roles, in the order the image writes them
 * (homecontrol <code>spec/runtime-image.md</code> 3.5). Each role is an attribute of
 * <code>ehome.runtime:Function</code> of the same name, referring to a datapoint of the project
 * (<code>ehome.logical:ProjectDatapoint</code>): a group address or an application datapoint
 * (haumacher/ehome #61).
 */
public final class FunctionKinds {

	/**
	 * The roles by kind; the first role of a kind is its main role, which the function cannot do
	 * without.
	 */
	public static final Map<String, List<String>> ROLES = Map.of(
		"light", List.of("command", "state"),
		"dimmer", List.of("levelCommand", "level", "command", "state"),
		"blind", List.of("move", "stop", "position", "positionCommand", "slat", "slatCommand"),
		"switch", List.of("command", "state"),
		"sensor", List.of("value"),
		"climate", List.of("temperature", "setpoint", "setpointCommand", "mode", "modeCommand", "heating"),
		"scene", List.of("command"),
		"button", List.of("press", "state"));

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
	 * The main role of the given kind, <code>null</code> for an unknown kind.
	 */
	public static String mainRole(String kind) {
		List<String> roles = roles(kind);
		return roles.isEmpty() ? null : roles.get(0);
	}

}
