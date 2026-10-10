/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.discover;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.BooleanDefault;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.config.annotation.defaults.IntDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.util.error.TopLogicException;

import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.knxnetip.Discoverer;
import tuwien.auto.calimero.knxnetip.servicetype.SearchResponse;
import tuwien.auto.calimero.knxnetip.util.DeviceDIB;

/**
 * {@link ViewAction} searching the local networks for KNXnet/IP gateways.
 *
 * <p>
 * The input is either nothing or a map with the search settings: <code>interface</code> (name of
 * the network interface to search on, all interfaces if empty), <code>local-host</code> (local
 * address to send from), <code>use-nat</code> and <code>use-multicast</code>; settings missing in
 * the map are taken from the configuration. The result is the list of gateways that answered, one
 * map per gateway (a gateway that answered several times is listed once)
 * with the keys <code>name</code>, <code>address</code>, <code>endpoint</code>, <code>mac</code>,
 * <code>serial</code> and <code>medium</code>, to be displayed as rows of a table.
 * </p>
 */
public class DiscoverGatewaysAction implements ViewAction {

	/**
	 * Configuration of {@link DiscoverGatewaysAction}.
	 */
	@TagName("discover-knx-gateways")
	public interface Config extends PolymorphicConfiguration<DiscoverGatewaysAction> {

		@Override
		@ClassDefault(DiscoverGatewaysAction.class)
		Class<? extends DiscoverGatewaysAction> getImplementationClass();

		/**
		 * Seconds to wait for answers.
		 */
		@Name("timeout")
		@IntDefault(10)
		int getTimeout();

		/**
		 * Whether to search through a NAT router.
		 */
		@Name("use-nat")
		boolean getUseNat();

		/**
		 * Whether to send the search request by multicast.
		 */
		@Name("use-multicast")
		@BooleanDefault(true)
		boolean getUseMulticast();
	}

	private final Config _config;

	/**
	 * Creates a {@link DiscoverGatewaysAction} from configuration.
	 */
	@CalledByReflection
	public DiscoverGatewaysAction(InstantiationContext context, Config config) {
		_config = config;
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		Map<?, ?> settings = input instanceof Map<?, ?> map ? map : Map.of();
		String interfaceName = text(settings.get("interface"));
		String localHost = text(settings.get("local-host"));
		boolean useNat = flag(settings.get("use-nat"), _config.getUseNat());
		boolean useMulticast = flag(settings.get("use-multicast"), _config.getUseMulticast());

		SearchResponse[] responses;
		try {
			InetAddress localAddress = localHost.isEmpty() ? null : InetAddress.getByName(localHost);
			Discoverer discoverer = new Discoverer(localAddress, 0, useNat, useMulticast);
			if (interfaceName.isEmpty()) {
				discoverer.startSearch(_config.getTimeout(), true);
			} else {
				NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
				if (networkInterface == null) {
					throw new TopLogicException(I18NConstants.UNKNOWN_INTERFACE__VALUE.fill(interfaceName));
				}
				discoverer.startSearch(networkInterface, _config.getTimeout(), true);
			}
			responses = discoverer.getSearchResponses();
		} catch (UnknownHostException ex) {
			throw new TopLogicException(I18NConstants.UNKNOWN_HOST__VALUE.fill(localHost), ex);
		} catch (SocketException | KNXException ex) {
			throw new TopLogicException(I18NConstants.DISCOVERY_FAILED__DETAIL.fill(ex.getMessage()), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return List.of();
		}

		// Calimero reports a gateway once per receiving socket and search request: without a chosen
		// interface it searches on every interface, and with multicast responses each of those
		// sockets receives every answer. Merge the answers by the identity of the gateway.
		Map<String, Map<String, Object>> gateways = new LinkedHashMap<>();
		for (SearchResponse response : responses) {
			DeviceDIB device = response.getDevice();
			String endpoint = response.getControlEndpoint().getAddress().getHostAddress() + ":"
				+ response.getControlEndpoint().getPort();
			String serial = device.getSerialNumberString();
			String key = serial.isEmpty() ? device.getMACAddressString() + "/" + endpoint : serial;
			if (gateways.containsKey(key)) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", device.getName());
			row.put("address", device.getAddress().toString());
			row.put("endpoint", endpoint);
			row.put("mac", device.getMACAddressString());
			row.put("serial", serial);
			row.put("medium", device.getKNXMediumString());
			gateways.put(key, row);
		}
		return new ArrayList<>(gateways.values());
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private static boolean flag(Object value, boolean defaultValue) {
		return value instanceof Boolean b ? b.booleanValue() : defaultValue;
	}

}
