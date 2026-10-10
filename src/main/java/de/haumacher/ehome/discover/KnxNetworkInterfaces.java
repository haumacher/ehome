/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.discover;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;

/**
 * TL-Script function listing the local network interfaces a KNX gateway search can use.
 *
 * <p>
 * Without argument, the result is the list of the names of the interfaces that are up. With an
 * interface name as argument, the result is the list of the IP addresses of that interface (all
 * local addresses for an empty argument).
 * </p>
 */
public class KnxNetworkInterfaces extends GenericMethod {

	/**
	 * Creates a {@link KnxNetworkInterfaces}.
	 */
	protected KnxNetworkInterfaces(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new KnxNetworkInterfaces(getName(), arguments);
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		// The interfaces of the machine change at runtime.
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		try {
			if (arguments.length == 0) {
				return interfaceNames();
			}
			String interfaceName = asString(arguments[0]);
			if (interfaceName.isEmpty()) {
				return addresses(Collections.list(NetworkInterface.getNetworkInterfaces()));
			}
			NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
			if (networkInterface == null) {
				return List.of();
			}
			return addresses(List.of(networkInterface));
		} catch (SocketException ex) {
			return List.of();
		}
	}

	private static List<String> interfaceNames() throws SocketException {
		List<String> result = new ArrayList<>();
		for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (networkInterface.isUp()) {
				result.add(networkInterface.getName());
			}
		}
		return result;
	}

	private static List<String> addresses(List<NetworkInterface> interfaces) {
		List<String> result = new ArrayList<>();
		for (NetworkInterface networkInterface : interfaces) {
			for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
				result.add(address.getHostAddress());
			}
		}
		return result;
	}

	/**
	 * Builder for {@link KnxNetworkInterfaces}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<KnxNetworkInterfaces> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public KnxNetworkInterfaces build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkMaxArgs(expr, args, 1);
			return new KnxNetworkInterfaces(getConfig().getName(), args);
		}
	}

}
