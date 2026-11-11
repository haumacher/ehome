/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The check of the roles of a function against the objects connected to their group addresses
 * (haumacher/ehome #137): a role bound to a group address on which no object in effect does what
 * the role needs never works, although the image takes it.
 *
 * <ul>
 * <li>A command role ({@link FunctionKinds#commandRoles(String)}: the controller writes the
 * datapoint) needs an object that takes values from the address
 * ({@link DatapointSettings#accepts(TLObject)}); without one the command reaches no device.</li>
 * <li>Every other role (a state, a measured value, a key's press) needs an object that sends on the
 * address ({@link DatapointSettings#transmits(TLObject)}); without one the function never shows a
 * value.</li>
 * </ul>
 *
 * <p>
 * Only objects in effect count ({@link GroupAddressConnections#inEffect(TLObject)}): an object
 * switched off by its device's parameters neither sends nor receives, though it is still connected.
 * A group address without any connection is not checked: it is a datapoint of the controller alone
 * (one rules or blocks write), not one a device was meant to serve. A role bound to an application
 * datapoint is not checked either: no KNX object serves it.
 * </p>
 *
 * <p>
 * The function list shows the problems as the function's warning (<code>functionWarning</code>,
 * {@link RuntimeFunctions}), a deployment adds them to its message ({@link Deployer}), and the
 * assistant's tools call {@link #problems(TLObject)} for a function they create.
 * </p>
 */
public final class FunctionRoleCheck {

	private FunctionRoleCheck() {
		// Static only.
	}

	/**
	 * The problems of the roles of the given function (<code>ehome.runtime:Function</code>), in the
	 * order of its roles; empty if every role bound to a group address is served.
	 */
	public static List<ResKey> problems(TLObject function) {
		List<ResKey> result = new ArrayList<>();
		String kind = FunctionProposal.kind(function);
		List<String> commands = FunctionKinds.commandRoles(kind);
		for (String role : FunctionKinds.roles(kind)) {
			ResKey problem = problem(function, role, commands.contains(role));
			if (problem != null) {
				result.add(problem);
			}
		}
		return result;
	}

	/**
	 * The problem of one role of the function, <code>null</code> if it is served (or not checked, see
	 * the class comment).
	 *
	 * @param command
	 *        Whether the role is one through which the controller writes the datapoint.
	 */
	private static ResKey problem(TLObject function, String role, boolean command) {
		if (!(function.tValueByName(role) instanceof TLObject address)
			|| !ExtIds.isA(address, "ehome.logical:GroupAddress")) {
			return null;
		}
		List<TLObject> connections = GroupAddressConnections.connections(address);
		if (connections.isEmpty()) {
			return null;
		}
		boolean switchedOff = false;
		for (TLObject connection : connections) {
			if (command ? DatapointSettings.accepts(connection) : DatapointSettings.transmits(connection)) {
				return null;
			}
			switchedOff |= !GroupAddressConnections.inEffect(connection);
		}
		String name = MetaLabelProvider.INSTANCE.getLabel(function);
		String datapoint = DatapointNames.name(address);
		if (command) {
			return switchedOff ? I18NConstants.FUNCTION_ROLE_NO_RECEIVER_SWITCHED_OFF__FUNCTION_ROLE_ADDRESS.fill(name, role,
				datapoint) : I18NConstants.FUNCTION_ROLE_NO_RECEIVER__FUNCTION_ROLE_ADDRESS.fill(name, role, datapoint);
		}
		return switchedOff ? I18NConstants.FUNCTION_ROLE_NO_SENDER_SWITCHED_OFF__FUNCTION_ROLE_ADDRESS.fill(name, role,
			datapoint) : I18NConstants.FUNCTION_ROLE_NO_SENDER__FUNCTION_ROLE_ADDRESS.fill(name, role, datapoint);
	}

	/**
	 * The {@link #problems(TLObject) problems} of the function as one text for a person (one line
	 * each), <code>null</code> if there are none.
	 */
	public static String problemText(TLObject function) {
		return text(problems(function));
	}

	/**
	 * The {@link #problems(TLObject) problems} of the functions of the project that the runtime image
	 * takes (not the unused ones, {@link FunctionProposal#unused(TLObject)}), in the order of the
	 * functions: the warnings of a deployment.
	 */
	public static List<ResKey> warnings(TLObject project) {
		List<ResKey> result = new ArrayList<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			if (!FunctionProposal.unused(function)) {
				result.addAll(problems(function));
			}
		}
		return result;
	}

	/**
	 * The given problems as one text, one line each; <code>null</code> for none.
	 */
	static String text(List<ResKey> problems) {
		if (problems.isEmpty()) {
			return null;
		}
		Resources resources = Resources.getInstance();
		StringBuilder result = new StringBuilder();
		for (ResKey problem : problems) {
			if (result.length() > 0) {
				result.append('\n');
			}
			result.append(resources.getString(problem));
		}
		return result.toString();
	}

}
