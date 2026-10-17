/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.virtual.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.device.ProjectDevices;
import de.haumacher.ehome.virtual.device.SimulatedDevice;
import de.haumacher.ehome.virtual.server.VirtualInterface;
import de.haumacher.ehome.virtual.server.VirtualInterface.Endpoint;
import de.haumacher.ehome.virtual.trace.TraceStore;
import de.haumacher.ehome.virtual.trace.TraceStore.TraceInfo;

/**
 * The TL-Script functions of the virtual device (<code>virtual/virtual-device.view.xml</code>), each
 * registered in <code>WEB-INF/autoconf/ehome.virtual.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 */
public class VirtualFunctions extends GenericMethod {

	/** The default port of KNXnet/IP. */
	public static final int DEFAULT_PORT = 3671;

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>virtualStatus(refresh?)</code>: what runs, for a person; the argument only makes a
		 * view recompute it.
		 */
		virtualStatus(0, 1, true),
		/** <code>virtualRunning(refresh?)</code>: whether a virtual interface runs. */
		virtualRunning(0, 1, true),
		/**
		 * <code>virtualStartVeto(device, refresh?)</code>: why no virtual device can be started for the
		 * device now, <code>null</code> if one can.
		 */
		virtualStartVeto(1, 2, true),
		/**
		 * <code>virtualStart(device, address, port, rule, discovery)</code>: starts the virtual
		 * interface with the simulated device of the project device; the result is the status.
		 */
		virtualStart(5, 5, false),
		/** <code>virtualStopVeto(refresh?)</code>: why there is nothing to stop, <code>null</code> if a virtual interface runs. */
		virtualStopVeto(0, 1, true),
		/** <code>virtualStop()</code>: stops the virtual interface; the result is the status. */
		virtualStop(0, 1, false),
		/**
		 * <code>virtualTraces(refresh?)</code>: the recorded traces, the newest first, as rows with
		 * <code>name</code>, <code>device</code>, <code>mask</code>, <code>interface</code>,
		 * <code>started</code>, <code>services</code>.
		 */
		virtualTraces(0, 1, true),
		/** <code>virtualTraceFile(name)</code>: the trace of the given name as a file to download. */
		virtualTraceFile(1, 1, true),
		/** <code>virtualRules()</code>: the rules a device of mask 0700/0701 may answer its load states by. */
		virtualRules(0, 0, true);

		private final int _minArguments;

		private final int _maxArguments;

		private final boolean _sideEffectFree;

		Function(int minArguments, int maxArguments, boolean sideEffectFree) {
			_minArguments = minArguments;
			_maxArguments = maxArguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link VirtualFunctions}.
	 */
	protected VirtualFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new VirtualFunctions(getName(), _function, arguments);
	}

	@Override
	public Object getId() {
		return _function;
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean isSideEffectFree() {
		return _function._sideEffectFree;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		VirtualInterfaceManager manager = VirtualInterfaces.getInstance().manager();
		switch (_function) {
			case virtualStatus:
				return status(manager);
			case virtualRunning:
				return Boolean.valueOf(manager.running() != null);
			case virtualStartVeto: {
				ResKey veto = startVeto(manager, arguments[0]);
				return veto == null ? null : text(veto);
			}
			case virtualStart:
				start(manager, arguments);
				return status(manager);
			case virtualStopVeto:
				return manager.running() == null ? text(I18NConstants.ERROR_NOT_RUNNING) : null;
			case virtualStop:
				if (manager.running() == null) {
					throw new TopLogicException(I18NConstants.ERROR_NOT_RUNNING);
				}
				manager.stop();
				return status(manager);
			case virtualTraces:
				return traces(manager.traces());
			case virtualTraceFile:
				return traceFile(manager.traces(), arguments[0]);
			case virtualRules:
				return Arrays.asList(MappedLoadStateRule.values());
		}
		throw new IllegalStateException("Unknown function: " + _function);
	}

	private static ResKey startVeto(VirtualInterfaceManager manager, Object device) {
		VirtualInterface running = manager.running();
		if (running != null) {
			return I18NConstants.ERROR_ALREADY_RUNNING__NAME_ENDPOINT.fill(running.name(), running.endpoint().toString());
		}
		return ProjectDevices.veto(device instanceof TLObject object ? object : null);
	}

	private static void start(VirtualInterfaceManager manager, Object[] arguments) {
		ResKey veto = startVeto(manager, arguments[0]);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		TLObject device = (TLObject) arguments[0];
		String host = arguments[1] == null ? "" : arguments[1].toString().trim();
		if (host.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_LOCAL_ADDRESS);
		}
		int port = arguments[2] instanceof Number number ? number.intValue() : DEFAULT_PORT;
		if (port < 1 || port > 0xFFFF) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_PORT__PORT.fill(Integer.toString(port)));
		}
		MappedLoadStateRule rule =
			arguments[3] instanceof MappedLoadStateRule given ? given : MappedLoadStateRule.ECHO;
		boolean discovery = SearchExpression.asBoolean(arguments[4]);
		if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_IPV4__ADDRESS.fill(host));
		}
		InetAddress address;
		try {
			// A literal address: no name resolution.
			address = InetAddress.getByName(host);
		} catch (UnknownHostException ex) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_LOCAL__ADDRESS.fill(host), ex);
		}
		SimulatedDevice simulated = ProjectDevices.create(device, rule);
		Map<String, String> header = new LinkedHashMap<>();
		TLObject project = device;
		while (project.tContainer() != null) {
			project = project.tContainer();
		}
		header.put("project", String.valueOf(project.tValueByName("name")));
		manager.start(simulated, new Endpoint(address, port, discovery), header);
	}

	private static String status(VirtualInterfaceManager manager) {
		VirtualInterface running = manager.running();
		if (running == null) {
			return text(I18NConstants.STATUS_STOPPED);
		}
		return text(I18NConstants.STATUS_RUNNING__NAME_ENDPOINT_DEVICE_SERVICES.fill(running.name(),
			running.endpoint().toString(), running.device().name(), Integer.valueOf(running.recorder().size())));
	}

	private static List<Map<String, Object>> traces(TraceStore store) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (TraceInfo trace : store.list()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", trace.name());
			row.put("device", trace.header().get(VirtualInterfaceManager.HEADER_DEVICE));
			row.put("project", trace.header().get("project"));
			row.put("mask", trace.header().get(VirtualInterfaceManager.HEADER_MASK));
			row.put("interface", trace.header().get(VirtualInterfaceManager.HEADER_INTERFACE));
			row.put("started", trace.header().get(VirtualInterfaceManager.HEADER_STARTED));
			row.put("services", Integer.valueOf(trace.services()));
			result.add(row);
		}
		return result;
	}

	private static Object traceFile(TraceStore store, Object row) {
		Object name = row instanceof Map<?, ?> map ? map.get("name") : row;
		if (name == null) {
			return null;
		}
		File file = store.file(name.toString());
		if (file == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_TRACE__NAME.fill(name.toString()));
		}
		try {
			return BinaryDataFactory.createBinaryData(Files.readAllBytes(file.toPath()), "text/plain;charset=UTF-8", file.getName());
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_NO_TRACE__NAME.fill(name.toString()), ex);
		}
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	/**
	 * Builder of a {@link VirtualFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<VirtualFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public VirtualFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._minArguments, _function._maxArguments);
			return new VirtualFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
