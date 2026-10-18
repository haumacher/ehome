/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.util.Collections;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

import com.top_logic.basic.Logger;

/**
 * The registration of the {@link ControllerSocket} with the container's Jakarta WebSocket
 * implementation (the {@link ServerContainer} the container puts into the servlet context), for
 * {@link ControllerSocketRegistration}, which loads this class only when the API is there.
 *
 * <p>
 * A container that runs the servlet container initializers of its WebSocket implementation (Tomcat,
 * Jetty with its WebSocket module and annotation scanning) has the {@link ServerContainer} ready. The
 * development start of TopLogic (Jetty without annotation scanning) runs none: there the initializer
 * of Jetty's implementation ({@value #JETTY_INITIALIZER}, a dependency of the start in
 * <code>ehome/pom.xml</code>) is run here, by its name. Without any implementation the controllers
 * cannot connect, which is logged as an error.
 * </p>
 */
public final class ControllerSocketDeployment {

	/** The servlet container initializer of Jetty's Jakarta WebSocket implementation. */
	public static final String JETTY_INITIALIZER =
		"org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer";

	private ControllerSocketDeployment() {
		// Static only.
	}

	/**
	 * Registers the {@link ControllerSocket} in the given context, logging why it cannot.
	 */
	static void deploy(ServletContext context) {
		ServerContainer container = container(context);
		if (container == null) {
			Logger.error("The servlet container has no WebSocket implementation: controllers cannot connect to E-Home.",
				ControllerSocketDeployment.class);
			return;
		}
		try {
			register(container);
			Logger.info("The controllers' WebSocket is at " + context.getContextPath() + ControllerSocketRegistration.PATH
				+ ".", ControllerSocketDeployment.class);
		} catch (DeploymentException ex) {
			Logger.error("The controllers' WebSocket could not be registered.", ex, ControllerSocketDeployment.class);
		}
	}

	/**
	 * Registers the {@link ControllerSocket} with the given container.
	 */
	public static void register(ServerContainer container) throws DeploymentException {
		container.addEndpoint(
			ServerEndpointConfig.Builder.create(ControllerSocket.class, ControllerSocketRegistration.PATH).build());
	}

	private static ServerContainer container(ServletContext context) {
		ServerContainer container = (ServerContainer) context.getAttribute(ServerContainer.class.getName());
		if (container != null) {
			return container;
		}
		Class<?> type;
		try {
			type = Class.forName(JETTY_INITIALIZER, true, context.getClassLoader());
		} catch (ClassNotFoundException | LinkageError ex) {
			return null;
		}
		try {
			ServletContainerInitializer initializer =
				(ServletContainerInitializer) type.getDeclaredConstructor().newInstance();
			initializer.onStartup(Collections.emptySet(), context);
		} catch (Exception ex) {
			Logger.error("Starting Jetty's WebSocket implementation failed.", ex, ControllerSocketDeployment.class);
			return null;
		}
		return (ServerContainer) context.getAttribute(ServerContainer.class.getName());
	}

}
