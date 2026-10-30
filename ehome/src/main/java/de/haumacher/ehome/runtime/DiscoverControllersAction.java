/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.config.annotation.defaults.IntDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.runtime.MdnsBrowser.Service;
import de.haumacher.ehome.runtime.MdnsBrowser.Target;

/**
 * {@link ViewAction} searching the local networks for HomeControl controllers (haumacher/ehome #42):
 * the instances of the DNS-SD service {@value #SERVICE_TYPE} that answer a multicast DNS query
 * ({@link MdnsBrowser}).
 *
 * <p>
 * The input is either nothing or a map with the entry <code>interface</code>: the name of the
 * network interface to search on (the loopback interface too); without it, the search runs on all
 * interfaces that are up, support multicast and are no loopback interface.
 * </p>
 *
 * <p>
 * The result is the list of controllers found, one map per controller with the keys
 * <code>name</code> (the host name without <code>.local</code>, e.g.
 * <code>homecontrol-a1b2c3</code>), <code>instance</code> (the instance name of the announcement),
 * <code>hostName</code>, <code>host</code> (the IPv4 address), <code>port</code> (of the web API; <code>portText</code> written without thousands separator),
 * <code>version</code> and <code>build</code> (the TXT attributes of the announcement, empty if
 * missing), to be displayed as rows of a table and turned into a controller
 * (<code>ehome.runtime:Controller</code>: name, host, port).
 * </p>
 */
public class DiscoverControllersAction implements ViewAction {

	/** The DNS-SD service type a controller announces (homecontrol <code>doc/network.md</code>). */
	public static final String SERVICE_TYPE = "_homecontrol._tcp.local";

	/**
	 * Configuration of {@link DiscoverControllersAction}.
	 */
	@TagName("discover-controllers")
	public interface Config extends PolymorphicConfiguration<DiscoverControllersAction>, WithResultActions {

		@Override
		@ClassDefault(DiscoverControllersAction.class)
		Class<? extends DiscoverControllersAction> getImplementationClass();

		/**
		 * Milliseconds to collect answers.
		 */
		@Name("timeout")
		@IntDefault(2000)
		int getTimeout();
	}

	private final Config _config;

	/**
	 * Creates a {@link DiscoverControllersAction} from configuration.
	 */
	@CalledByReflection
	public DiscoverControllersAction(InstantiationContext context, Config config) {
		_config = config;
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		Object interfaceName = input instanceof Map<?, ?> settings ? settings.get("interface") : null;
		try {
			List<Target> targets;
			if (interfaceName == null || interfaceName.toString().isBlank()) {
				targets = MdnsBrowser.localNetworks();
			} else {
				NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName.toString().trim());
				if (networkInterface == null) {
					throw new TopLogicException(I18NConstants.ERROR_DISCOVERY_UNKNOWN_INTERFACE__NAME.fill(interfaceName));
				}
				targets = List.of(new Target(MdnsBrowser.MDNS_GROUP, networkInterface));
			}
			if (targets.isEmpty()) {
				throw new TopLogicException(I18NConstants.ERROR_DISCOVERY_NO_NETWORK);
			}
			return rows(MdnsBrowser.browse(SERVICE_TYPE, _config.getTimeout(), targets));
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_DISCOVERY_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}
	}

	/**
	 * The rows of the controllers found, see {@link DiscoverControllersAction}.
	 */
	public static List<Map<String, Object>> rows(List<Service> services) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Service service : services) {
			String hostName = service.host();
			String name = hostName.toLowerCase(Locale.ROOT).endsWith(".local") ? hostName.substring(0, hostName.length() - 6)
				: hostName;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", name.isEmpty() ? service.instance() : name);
			row.put("instance", service.instance());
			row.put("hostName", hostName);
			row.put("host", service.address());
			row.put("port", Integer.valueOf(service.port()));
			row.put("portText", Integer.toString(service.port()));
			row.put("version", service.attributes().getOrDefault("version", ""));
			row.put("build", service.attributes().getOrDefault("build", ""));
			result.add(row);
		}
		return result;
	}

}
