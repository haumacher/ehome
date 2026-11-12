/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.settings.OperatorSettings;

/**
 * The HomeControl controller the group monitor attaches to (haumacher/ehome #56): the project's
 * controller, any registered controller (<code>ehome.runtime:Controller</code>), or one entered ad
 * hoc by host, port and token. A registered controller connected to E-Home is attached over its
 * connection ({@link SocketBusLink}, haumacher/ehome #54) unless a token is entered for it, which
 * means its LAN address. Unless this installation reaches local networks (haumacher/ehome #150), only
 * the connection of a registered controller is used.
 *
 * <p>
 * Neither an entered controller nor an entered token is stored: the token lives in the
 * {@link ControllerClient} of the connection and is gone with it. A token entered for a registered
 * controller is used instead of the stored one for this connection only.
 * </p>
 *
 * @param name
 *        The name of the registered controller, <code>null</code> for one entered ad hoc.
 * @param client
 *        The controller's address and the token to log in with.
 * @param entered
 *        Whether the controller or its token was entered rather than taken from a registered
 *        controller: such a connection is always made anew, since the same address may now come with
 *        another token.
 */
public record ControllerTarget(String name, ControllerClient client, boolean entered) {

	/** The port of a controller's web API if none is entered. */
	public static final int DEFAULT_PORT = 80;

	/**
	 * What the monitor names the controller by ({@link ControllerBusLink#description(String, ControllerClient)}).
	 */
	public String description() {
		if (client.connection() != null) {
			return SocketBusLink.description(name);
		}
		return ControllerBusLink.description(name, client);
	}

	/**
	 * The controller the monitor of the given project attaches to.
	 *
	 * @param project
	 *        The project; its controller (<code>ehome.project:Project#controller</code>) is used if
	 *        neither a controller is chosen nor a host entered.
	 * @param controller
	 *        The registered controller chosen, <code>null</code> for none.
	 * @param host
	 *        The host name or address entered, <code>null</code> or empty for none. An entered host wins
	 *        over a chosen controller.
	 * @param port
	 *        The port entered (a number or its text), <code>null</code> for {@value #DEFAULT_PORT}.
	 * @param token
	 *        The token entered, <code>null</code> or empty for the stored token of a registered
	 *        controller; required with a host.
	 * @throws TopLogicException
	 *         If no controller is chosen and none entered, the port is invalid, or no token is at hand.
	 */
	public static ControllerTarget of(TLObject project, Object controller, Object host, Object port, Object token) {
		String enteredToken = blank(token) ? null : token.toString().trim();
		if (!blank(host)) {
			// A controller by host and token is reached over the local network (haumacher/ehome #150).
			OperatorSettings.checkLocalNetwork();
			String hostText = host.toString().trim();
			ControllerClient client = new ControllerClient(hostText, port(port), enteredToken, Deployer.TIMEOUT);
			if (enteredToken == null) {
				throw new TopLogicException(I18NConstants.ERROR_NO_AD_HOC_TOKEN__CONTROLLER.fill(client.address()));
			}
			return new ControllerTarget(null, client, true);
		}
		TLObject registered = controller instanceof TLObject chosen && chosen.tValid() ? chosen
			: project == null ? null : Deployer.controller(project);
		if (registered == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_CONTROLLER_CHOSEN);
		}
		String name = String.valueOf(registered.tValueByName("name"));
		ControllerClient client = Deployer.client(registered);
		if (client.connection() != null && enteredToken == null) {
			return new ControllerTarget(name, client, false);
		}
		if (!OperatorSettings.localNetwork()) {
			// Only the connection the controller opens itself is left (haumacher/ehome #150).
			if (ControllerConnections.controllerId(registered) != null && enteredToken == null) {
				throw new TopLogicException(Deployer.offline(registered));
			}
			OperatorSettings.checkLocalNetwork();
		}
		if (enteredToken != null) {
			Object registeredHost = registered.tValueByName("host");
			if (blank(registeredHost)) {
				throw new TopLogicException(
					de.haumacher.ehome.runtime.I18NConstants.ERROR_NO_HOST__CONTROLLER.fill(name));
			}
			Object registeredPort = registered.tValueByName("port");
			return new ControllerTarget(name, new ControllerClient(registeredHost.toString().trim(),
				registeredPort instanceof Number number ? number.intValue() : DEFAULT_PORT, enteredToken,
				client.timeout()), true);
		}
		if (client.token() == null) {
			if (ControllerConnections.controllerId(registered) != null) {
				throw new TopLogicException(Deployer.offline(registered));
			}
			throw new TopLogicException(I18NConstants.ERROR_NO_CONTROLLER_TOKEN__CONTROLLER.fill(name));
		}
		return new ControllerTarget(name, client, false);
	}

	/**
	 * Attaches the given monitor to this controller, unless it is attached to it already (a
	 * registered controller with its stored token).
	 *
	 * @throws TopLogicException
	 *         If the controller cannot be reached or refuses the token.
	 */
	public void attach(GroupMonitor monitor) {
		if (!entered && monitor.isConnectedTo(description())) {
			return;
		}
		BusLink link;
		try {
			if (client.connection() != null) {
				link = SocketBusLink.open(name, client.connection().controllerId(), client.timeout());
			} else {
				link = ControllerBusLink.open(name, client);
			}
		} catch (BusException ex) {
			throw new TopLogicException(
				I18NConstants.ERROR_CONTROLLER_CONNECT_FAILED__CONTROLLER_DETAIL.fill(description(), ex.getMessage()), ex);
		}
		monitor.refresh(ProjectIndex.of(monitor.getProject()));
		monitor.connect(link);
	}

	private static int port(Object port) {
		if (port == null || port.toString().isBlank()) {
			return DEFAULT_PORT;
		}
		int result;
		if (port instanceof Number number) {
			result = number.intValue();
		} else {
			try {
				result = Integer.parseInt(port.toString().trim());
			} catch (NumberFormatException ex) {
				throw new TopLogicException(I18NConstants.ERROR_INVALID_CONTROLLER_PORT__PORT.fill(port));
			}
		}
		if (result < 1 || result > 65535) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_CONTROLLER_PORT__PORT.fill(port));
		}
		return result;
	}

	private static boolean blank(Object value) {
		return value == null || value.toString().isBlank();
	}

}
