/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Starts the threads of the {@link ControllerConnections} with the application and stops them at its
 * end (haumacher/ehome #81); a listener in <code>WEB-INF/web.xml</code> after the engine's, so that
 * its end comes before the knowledge base shuts down, and before the Zigbee live import's
 * ({@link de.haumacher.ehome.zigbee.ZigbeeLiveRegistration}), which ends first.
 *
 * <p>
 * The container calls {@link #contextDestroyed(ServletContextEvent)} also when the start of the
 * application failed: the threads stop in every case.
 * </p>
 */
public class ControllerConnectionsRegistration implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent event) {
		ControllerConnections.start();
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		ControllerConnections.stop();
	}

}
