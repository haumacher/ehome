/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.discover;

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
 * The input is ignored. The result is the list of gateways that answered, one map per gateway
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
		SearchResponse[] responses;
		try {
			Discoverer discoverer = new Discoverer(null, 0, _config.getUseNat(), _config.getUseMulticast());
			discoverer.startSearch(_config.getTimeout(), true);
			responses = discoverer.getSearchResponses();
		} catch (KNXException ex) {
			throw new TopLogicException(I18NConstants.DISCOVERY_FAILED__DETAIL.fill(ex.getMessage()), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return List.of();
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (SearchResponse response : responses) {
			DeviceDIB device = response.getDevice();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", device.getName());
			row.put("address", device.getAddress().toString());
			row.put("endpoint", response.getControlEndpoint().getAddress().getHostAddress() + ":"
				+ response.getControlEndpoint().getPort());
			row.put("mac", device.getMACAddressString());
			row.put("serial", device.getSerialNumberString());
			row.put("medium", device.getKNXMediumString());
			result.add(row);
		}
		return result;
	}

}
