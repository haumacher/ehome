/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.discover;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.basic.Logger;
import com.top_logic.basic.config.AbstractConfigurationValueProvider;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.InstanceFormat;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.Ref;
import com.top_logic.basic.config.annotation.defaults.BooleanDefault;
import com.top_logic.basic.func.Function0;
import com.top_logic.basic.func.Function1;
import com.top_logic.layout.form.values.edit.annotation.DisplayOrder;
import com.top_logic.layout.form.values.edit.annotation.Options;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
@DisplayOrder({
	DiscoverSettings.NETWORK_INTERFACE,
	DiscoverSettings.LOCAL_HOST,
	DiscoverSettings.USE_NAT,
	DiscoverSettings.USE_MULTICAST,
})
public interface DiscoverSettings extends ConfigurationItem {

	/**
	 * @see #getUseMulticast()
	 */
	String USE_MULTICAST = "use-multicast";

	String USE_NAT = "use-nat";

	String LOCAL_HOST = "local-host";

	String NETWORK_INTERFACE = "network-interface";

	/**
	 * TODO
	 *
	 * @return
	 */
	@Name(NETWORK_INTERFACE)
	@InstanceFormat
	@Format(NetworkInterfaceFormat.class)
	@Options(fun = LocalNetworks.class, args = {})
	NetworkInterface getNetworkInterface();

	@Name(LOCAL_HOST)
	@InstanceFormat
	@Format(InetAddressFormat.class)
	@Options(fun = LocalAddresses.class, args = { @Ref(NETWORK_INTERFACE) })
	InetAddress getLocalhost();

	@Name(USE_NAT)
	boolean getUseNat();

	/**
	 * TODO
	 *
	 * @return
	 */
	@Name(USE_MULTICAST)
	@BooleanDefault(true)
	boolean getUseMulticast();

	class LocalNetworks extends Function0<List<NetworkInterface>> {

		@Override
		public List<NetworkInterface> apply() {
			try {
				return toList(NetworkInterface.getNetworkInterfaces());
			} catch (SocketException ex) {
				return Collections.emptyList();
			}
		}

		private static <T> List<T> toList(Enumeration<? extends T> e) {
			ArrayList<T> result = new ArrayList<>();
			while (e.hasMoreElements()) {
				result.add(e.nextElement());
			}
			return result;
		}

	}

	class LocalAddresses extends Function1<List<InetAddress>, NetworkInterface> {

		@Override
		public List<InetAddress> apply(NetworkInterface network) {
			Set<InetAddress> result = new HashSet<InetAddress>();
			if (network == null) {
				addAllAddresses(result);
			} else {
				addAddresses(result, network);
			}
			if (result.isEmpty()) {
				try {
					result.add(InetAddress.getLocalHost());
				} catch (UnknownHostException ex) {
					Logger.error("Cannot resolve local host address.", ex, LocalAddresses.class);
				}
			}
			return new ArrayList<InetAddress>(result);
		}

		/** 
		 * TODO
		 *
		 * @param result
		 */
		private void addAllAddresses(Set<InetAddress> result) {
			try {
				Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
				while (interfaces.hasMoreElements()) {
					NetworkInterface network = interfaces.nextElement();
					addAddresses(result, network);
				}
			} catch (SocketException ex) {
				Logger.error("Cannot resolve local interfaces.", ex, LocalAddresses.class);
			}
		}

		/** 
		 * TODO
		 *
		 * @param result
		 * @param network
		 */
		private void addAddresses(Set<InetAddress> result, NetworkInterface network) {
			Enumeration<InetAddress> addresses = network.getInetAddresses();
			while (addresses.hasMoreElements()) {
				result.add(addresses.nextElement());
			}
		}

	}

	class InetAddressFormat extends AbstractConfigurationValueProvider<InetAddress> {

		/**
		 * Creates a {@link InetAddressFormat}.
		 *
		 * @param type
		 */
		public InetAddressFormat() {
			super(InetAddress.class);
		}

		@Override
		protected InetAddress getValueNonEmpty(String propertyName, CharSequence propertyValue)
				throws ConfigurationException {
			String hostName = propertyValue.toString();
			try {
				return InetAddress.getByName(hostName);
			} catch (UnknownHostException ex) {
				throw new ConfigurationException(I18NConstants.UNKNOWN_HOST__VALUE.fill(hostName), propertyName,
					propertyValue);
			}
		}

		@Override
		protected String getSpecificationNonNull(InetAddress configValue) {
			return configValue.getHostAddress();
		}

	}

	class NetworkInterfaceFormat extends AbstractConfigurationValueProvider<NetworkInterface> {

		/**
		 * Creates a {@link NetworkInterface}.
		 *
		 * @param type
		 */
		public NetworkInterfaceFormat() {
			super(NetworkInterface.class);
		}

		@Override
		protected NetworkInterface getValueNonEmpty(String propertyName, CharSequence propertyValue)
				throws ConfigurationException {
			String interfaceName = propertyValue.toString();
			try {
				return NetworkInterface.getByName(interfaceName);
			} catch (SocketException ex) {
				throw new ConfigurationException(I18NConstants.UNKNOWN_INTERFACE__VALUE.fill(interfaceName),
					propertyName, propertyValue);
			}
		}

		@Override
		protected String getSpecificationNonNull(NetworkInterface configValue) {
			return configValue.getName();
		}

	}

}
