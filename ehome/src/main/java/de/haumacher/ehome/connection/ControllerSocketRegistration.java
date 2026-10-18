/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import com.top_logic.basic.Logger;

/**
 * Registers the controllers' WebSocket ({@link ControllerSocket}) at {@value #PATH} with the
 * container's Jakarta WebSocket implementation; a listener in <code>WEB-INF/web.xml</code>.
 *
 * <p>
 * The listener itself uses the servlet API only: in a container without the Jakarta WebSocket API
 * it logs an error and E-Home starts without the socket (the controllers cannot connect, the rest
 * works). Otherwise {@link ControllerSocketDeployment} does the registration.
 * </p>
 */
public class ControllerSocketRegistration implements ServletContextListener {

	/** The name of the path parameter of the controller's id. */
	public static final String ID = "id";

	/** The path of the socket below the context path. */
	public static final String PATH = ControllerServlet.PATH + "/{" + ID + "}/socket";

	private static final String API = "jakarta.websocket.server.ServerContainer";

	@Override
	public void contextInitialized(ServletContextEvent event) {
		ServletContext context = event.getServletContext();
		try {
			Class.forName(API, false, context.getClassLoader());
		} catch (ClassNotFoundException | LinkageError ex) {
			Logger.error("The servlet container has no Jakarta WebSocket API: controllers cannot connect to E-Home.",
				ControllerSocketRegistration.class);
			return;
		}
		ControllerSocketDeployment.deploy(context);
	}

}
