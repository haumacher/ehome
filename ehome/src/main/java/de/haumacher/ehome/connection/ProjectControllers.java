/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.settings.OperatorSettings;

/**
 * The controllers of a project (haumacher/ehome #151): every controller is a part of exactly one
 * project (<code>ehome.project:Project#controllers</code>), the one enrolled with the project's code
 * as well as the one on the LAN, reached by host and token. A project deploys to, monitors and reads
 * the Zigbee network of its own controllers only.
 *
 * <p>
 * A controller without a project is left only by the migration of existing data
 * ({@link ControllerOwnershipMigration}): one no project deployed to. Nobody but an administrator
 * sees it (it has no container to take its access from); the administrator assigns it to a project
 * ({@link #assign(TLObject, TLObject)}) or forgets it.
 * </p>
 */
public final class ProjectControllers {

	/** The type of a controller. */
	public static final String CONTROLLER_TYPE = "ehome.runtime:Controller";

	/** The composition of a project holding its controllers. */
	public static final String CONTROLLERS = "controllers";

	/** The reference of a project to the controller it deploys to. */
	public static final String TARGET = "controller";

	/** The port of a controller's web API if none is entered. */
	public static final int DEFAULT_PORT = 80;

	/**
	 * The attributes a copy of a controller does not take over ({@link #copy(TLObject)}): the identity
	 * of an enrolled controller's connection, which only one controller may have.
	 */
	private static final Set<String> NOT_COPIED = Set.of("controllerId", "secret");

	private ProjectControllers() {
		// Static helpers.
	}

	/**
	 * Whether the given controller is one of the project's own.
	 */
	public static boolean isOwn(TLObject project, TLObject controller) {
		return project != null && controller != null && controller.tValid() && controller.tContainer() == project;
	}

	/**
	 * Why the project cannot use the given controller, <code>null</code> if it is one of its own.
	 */
	public static ResKey foreignVeto(TLObject project, TLObject controller) {
		if (isOwn(project, controller)) {
			return null;
		}
		TLObject holder = controller == null || !controller.tValid() ? null : controller.tContainer();
		String name = controller == null || !controller.tValid() ? "" : String.valueOf(controller.tValueByName("name"));
		if (holder == null) {
			return I18NConstants.ERROR_CONTROLLER_WITHOUT_PROJECT__CONTROLLER.fill(name);
		}
		return I18NConstants.ERROR_FOREIGN_CONTROLLER__CONTROLLER_PROJECT.fill(name,
			String.valueOf(holder.tValueByName("name")));
	}

	/**
	 * The controllers of the project, in their order.
	 */
	public static List<TLObject> controllers(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		if (project != null && project.tValid() && project.tValueByName(CONTROLLERS) instanceof Collection<?> all) {
			for (Object entry : all) {
				if (entry instanceof TLObject controller && controller.tValid()) {
					result.add(controller);
				}
			}
		}
		return result;
	}

	/**
	 * Creates a controller on the LAN in the project, inside the caller's transaction: reached by
	 * host and port, authenticated by the token created on its console. It becomes the project's
	 * deployment target if the project has none.
	 *
	 * @param name
	 *        The name, <code>null</code> or empty for the host.
	 * @param host
	 *        The host name or address, mandatory.
	 * @param port
	 *        The port of the web API (a number or its text), <code>null</code> or empty for
	 *        {@value #DEFAULT_PORT}.
	 * @param token
	 *        The token, <code>null</code> or empty to set it later.
	 * @return The new controller.
	 * @throws TopLogicException
	 *         If the installation does not reach local networks (haumacher/ehome #150), the host is
	 *         missing, the port is invalid, or the project has a controller with this address already.
	 */
	public static TLObject create(TLObject project, Object name, Object host, Object port, Object token) {
		if (project == null || !project.tValid()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PROJECT);
		}
		// A controller by host and token is reached over the local network (haumacher/ehome #150).
		OperatorSettings.checkLocalNetwork();
		if (blank(host)) {
			throw new TopLogicException(I18NConstants.ERROR_CONTROLLER_HOST_MISSING);
		}
		String hostText = host.toString().trim();
		int portNumber = port(port);
		TLObject known = find(project, hostText, portNumber);
		if (known != null) {
			throw new TopLogicException(
				I18NConstants.ERROR_CONTROLLER_ADDRESS_KNOWN__CONTROLLER.fill(String.valueOf(known.tValueByName("name"))));
		}
		TLObject controller = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType(CONTROLLER_TYPE));
		controller.tUpdateByName("name", blank(name) ? hostText : name.toString().trim());
		controller.tUpdateByName("host", hostText);
		controller.tUpdateByName("port", Integer.valueOf(portNumber));
		if (!blank(token)) {
			controller.tUpdateByName("token", token.toString().trim());
		}
		add(project, controller);
		return controller;
	}

	/**
	 * The project's controller with the given host and port, <code>null</code> for none.
	 */
	public static TLObject find(TLObject project, String host, int port) {
		for (TLObject controller : controllers(project)) {
			Object otherHost = controller.tValueByName("host");
			Object otherPort = controller.tValueByName("port");
			if (otherHost != null && otherHost.toString().trim().equalsIgnoreCase(host)
				&& otherPort instanceof Number number && number.intValue() == port) {
				return controller;
			}
		}
		return null;
	}

	/**
	 * Assigns a controller without a project to the given project, inside the caller's transaction (an
	 * administrator's command, haumacher/ehome #151). It becomes the project's deployment target if
	 * the project has none.
	 *
	 * @throws TopLogicException
	 *         If the controller belongs to a project already.
	 */
	public static TLObject assign(TLObject controller, TLObject project) {
		if (controller == null || !controller.tValid()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_CONTROLLER_CHOSEN);
		}
		if (project == null || !project.tValid()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PROJECT);
		}
		TLObject holder = controller.tContainer();
		if (holder != null) {
			throw new TopLogicException(I18NConstants.ERROR_CONTROLLER_HAS_PROJECT__CONTROLLER_PROJECT.fill(
				String.valueOf(controller.tValueByName("name")), String.valueOf(holder.tValueByName("name"))));
		}
		add(project, controller);
		return controller;
	}

	/**
	 * Adds the controller to the project's controllers; it becomes the deployment target if the
	 * project has none.
	 */
	public static void add(TLObject project, TLObject controller) {
		List<Object> controllers = new ArrayList<>(controllers(project));
		controllers.add(controller);
		project.tUpdateByName(CONTROLLERS, controllers);
		if (!(project.tValueByName(TARGET) instanceof TLObject target) || !target.tValid()) {
			project.tUpdateByName(TARGET, controller);
		}
	}

	/**
	 * A new controller with the stored attributes of the given one, held by no project yet, inside the
	 * caller's transaction. The connection id and secret of an enrolled controller are not copied:
	 * only one controller may answer for a connection.
	 */
	static TLObject copy(TLObject controller) {
		TLObject result = ModelService.getInstance().getFactory().createObject((TLClass) controller.tType());
		for (TLStructuredTypePart part : controller.tType().getAllParts()) {
			if (part.isDerived() || NOT_COPIED.contains(part.getName())) {
				continue;
			}
			if (part instanceof TLReference reference && (reference.isBackwards() || reference.isComposite())) {
				continue;
			}
			Object value = controller.tValue(part);
			if (value != null) {
				result.tUpdate(part, value);
			}
		}
		return result;
	}

	private static int port(Object port) {
		if (blank(port)) {
			return DEFAULT_PORT;
		}
		int result;
		if (port instanceof Number number) {
			result = number.intValue();
		} else {
			try {
				result = Integer.parseInt(port.toString().trim());
			} catch (NumberFormatException ex) {
				throw new TopLogicException(I18NConstants.ERROR_CONTROLLER_PORT_INVALID__PORT.fill(port));
			}
		}
		if (result < 1 || result > 65535) {
			throw new TopLogicException(I18NConstants.ERROR_CONTROLLER_PORT_INVALID__PORT.fill(port));
		}
		return result;
	}

	private static boolean blank(Object value) {
		return value == null || value.toString().isBlank();
	}

}
