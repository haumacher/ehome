/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.settings;

import com.top_logic.basic.config.ApplicationConfig;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.BooleanDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.util.error.TopLogicException;

/**
 * The settings the operator of an E-Home installation decides (haumacher/ehome #149, #150): whether
 * E-Home writes to the KNX bus, and whether the installation reaches the local networks of its server.
 *
 * <p>
 * Both are TopLogic configuration variables, set like every other setting (a JNDI entry
 * <code>tl/&lt;name&gt;</code> of E-Home's context from <code>/etc/ehome/ehome.env</code> in the Debian
 * package, a system property or an environment variable in a development start):
 * </p>
 * <ul>
 * <li><code>tl_ehome_programming_write_enabled</code> (alias <code>%EHOME_PROGRAMMING_WRITE_ENABLED%</code>,
 * default <code>false</code>): {@link Config#getProgrammingWriteEnabled()};</li>
 * <li><code>tl_ehome_local_network</code> (alias <code>%EHOME_LOCAL_NETWORK%</code>, default
 * <code>true</code>): {@link Config#getLocalNetwork()}.</li>
 * </ul>
 *
 * <p>
 * The aliases are defined in <code>WEB-INF/conf/ehomeConf.xml</code>, the configuration
 * {@link Config} in the <code>&lt;configs&gt;</code> of <code>WEB-INF/conf/ehomeConf.config.xml</code>.
 * </p>
 */
public final class OperatorSettings {

	/**
	 * The configuration of the operator settings.
	 */
	public interface Config extends ConfigurationItem {

		/** Configuration name of {@link #getProgrammingWriteEnabled()}. */
		String PROGRAMMING_WRITE_ENABLED = "programming-write-enabled";

		/** Configuration name of {@link #getLocalNetwork()}. */
		String LOCAL_NETWORK = "local-network";

		/**
		 * Whether E-Home writes to the KNX bus: downloads, the individual address, the programming
		 * LED. Off, every write is refused (<code>CalimeroWriter</code>, the one class writing to the
		 * bus).
		 */
		@Name(PROGRAMMING_WRITE_ENABLED)
		boolean getProgrammingWriteEnabled();

		/**
		 * @see #getProgrammingWriteEnabled()
		 */
		void setProgrammingWriteEnabled(boolean value);

		/**
		 * Whether this installation reaches the local networks of its server: the search for KNX
		 * gateways and its network interfaces, a connection to a gateway address (monitor,
		 * programming, scan), the search for controllers and a controller reached by host and token.
		 * Off (a hosted installation), all of them are refused; what goes over the connection an
		 * enrolled controller opens itself stays.
		 */
		@Name(LOCAL_NETWORK)
		@BooleanDefault(true)
		boolean getLocalNetwork();

		/**
		 * @see #getLocalNetwork()
		 */
		void setLocalNetwork(boolean value);
	}

	private OperatorSettings() {
		// Static access only.
	}

	/**
	 * The configuration of this installation.
	 */
	public static Config config() {
		return ApplicationConfig.getInstance().getConfig(Config.class);
	}

	/**
	 * Whether E-Home may write to the KNX bus, see {@link Config#getProgrammingWriteEnabled()}.
	 */
	public static boolean programmingWriteEnabled() {
		return config().getProgrammingWriteEnabled();
	}

	/**
	 * Whether this installation reaches local networks, see {@link Config#getLocalNetwork()}.
	 */
	public static boolean localNetwork() {
		return config().getLocalNetwork();
	}

	/**
	 * Why this installation does not reach local networks, <code>null</code> if it does.
	 */
	public static ResKey localNetworkVeto() {
		return localNetwork() ? null : I18NConstants.ERROR_NO_LOCAL_NETWORK;
	}

	/**
	 * Refuses unless this installation reaches local networks.
	 *
	 * @throws TopLogicException
	 *         With the reason {@link I18NConstants#ERROR_NO_LOCAL_NETWORK}, if it does not.
	 */
	public static void checkLocalNetwork() {
		ResKey veto = localNetworkVeto();
		if (veto != null) {
			throw new TopLogicException(veto);
		}
	}

}
