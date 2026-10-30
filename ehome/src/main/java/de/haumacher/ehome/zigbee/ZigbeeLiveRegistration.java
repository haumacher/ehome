/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Starts {@link ZigbeeLive} with the application and stops it at its end; a listener in
 * <code>WEB-INF/web.xml</code>, as the controllers' socket is registered
 * ({@link de.haumacher.ehome.connection.ControllerSocket}).
 *
 * <p>
 * Starting registers a listener of the controller connections only: nothing of the knowledge base is
 * touched before a controller sends a Zigbee event.
 * </p>
 */
public class ZigbeeLiveRegistration implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent event) {
		ZigbeeLive.start();
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		ZigbeeLive.stop();
	}

}
