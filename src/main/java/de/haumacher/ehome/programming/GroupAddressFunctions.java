/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;

/**
 * The TL-Script functions reading the connections of a group address from the address's side, see
 * {@link GroupAddressConnections}. Connections are made and removed with the functions of
 * {@link DeviceFunctions} (<code>deviceConnect</code>, <code>deviceDisconnect</code>).
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class. Like those of
 * {@link DeviceFunctions}, the functions are implemented in Java: a view calling them names the
 * connections and connectors of devices in its <code>observed-types</code> (TL #29799).
 * </p>
 */
public class GroupAddressFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>groupAddressConnections(address)</code>: {@link GroupAddressConnections#connections(TLObject)}. */
		groupAddressConnections,
		/** <code>groupAddressSenders(address)</code>: {@link GroupAddressConnections#senders(TLObject)}. */
		groupAddressSenders,
		/** <code>groupAddressProblems(address)</code>: {@link GroupAddressConnections#problemText(TLObject)}. */
		groupAddressProblems,
		/** <code>groupAddressDevices(address)</code>: {@link GroupAddressConnections#devices(TLObject)}. */
		groupAddressDevices,
		/** <code>connectionLabel(connection)</code>: {@link GroupAddressConnections#label(TLObject)}. */
		connectionLabel,
		/** <code>connectionDevice(connection)</code>: {@link GroupAddressConnections#device(TLObject)}. */
		connectionDevice,
		/** <code>connectionObject(connection)</code>: {@link GroupAddressConnections#object(TLObject)}. */
		connectionObject,
		/** <code>connectionFunction(connection)</code>: {@link GroupAddressConnections#function(TLObject)}. */
		connectionFunction,
		/**
		 * <code>connectionDatapointType(connection)</code>:
		 * {@link GroupAddressConnections#datapointType(TLObject)}.
		 */
		connectionDatapointType,
		/** <code>deviceLabel(device)</code>: {@link GroupAddressConnections#deviceLabel(TLObject)}. */
		deviceLabel,
		/** <code>deviceObjects(device)</code>: {@link GroupAddressConnections#objects(TLObject)}. */
		deviceObjects,
		/**
		 * <code>existingObject(value)</code>: the value, <code>null</code> for an object deleted
		 * meanwhile (e.g. a selection whose object was deleted in another view).
		 */
		existingObject;
	}

	private final Function _function;

	/**
	 * Creates a {@link GroupAddressFunctions}.
	 */
	protected GroupAddressFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new GroupAddressFunctions(getName(), _function, arguments);
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
		return true;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (_function == Function.existingObject) {
			return arguments[0] instanceof TLObject obj && !obj.tValid() ? null : arguments[0];
		}
		if (!(arguments[0] instanceof TLObject obj) || !obj.tValid()) {
			return null;
		}
		switch (_function) {
			case groupAddressConnections:
				return GroupAddressConnections.connections(obj);
			case groupAddressSenders:
				return SearchExpression.toNumber(GroupAddressConnections.senders(obj));
			case groupAddressProblems:
				return GroupAddressConnections.problemText(obj);
			case groupAddressDevices:
				return GroupAddressConnections.devices(obj);
			case connectionLabel:
				return GroupAddressConnections.object(obj) == null ? null : GroupAddressConnections.label(obj);
			case connectionDevice:
				return GroupAddressConnections.device(obj);
			case connectionObject:
				return GroupAddressConnections.object(obj);
			case connectionFunction:
				return GroupAddressConnections.object(obj) == null ? null : GroupAddressConnections.function(obj);
			case connectionDatapointType:
				return GroupAddressConnections.object(obj) == null ? null
					: GroupAddressConnections.datapointType(obj);
			case deviceLabel:
				return GroupAddressConnections.deviceLabel(obj);
			case deviceObjects:
				return GroupAddressConnections.objects(obj);
			case existingObject:
				return obj;
		}
		return null;
	}

	/**
	 * Builder of a {@link GroupAddressFunctions} function: the {@link Function} named as the
	 * function is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<GroupAddressFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public GroupAddressFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, 1, 1);
			return new GroupAddressFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
