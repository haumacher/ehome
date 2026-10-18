/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.Resources;

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
 *
 * <p>
 * The functions <code>groupAddressRanges</code>, <code>groupAddressDefaultRange</code>,
 * <code>groupAddressNextFree</code>, <code>groupAddressCreateProblem</code> and
 * <code>groupAddressCreate</code> create a group address from the connect dialog, see
 * {@link GroupAddresses}.
 * </p>
 *
 * <p>
 * The functions <code>groupAddressHasType</code>, <code>groupAddressEffectiveType</code>,
 * <code>groupAddressObjectSize</code>, <code>groupAddressTypeChoices</code>,
 * <code>groupAddressTypeVeto</code>, <code>groupAddressSetType</code> and
 * <code>groupAddressUntypedParts</code> read and set the datapoint type of an address, see
 * {@link GroupAddressTypes}.
 * </p>
 */
public class GroupAddressFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>groupAddressConnections(address)</code>: {@link GroupAddressConnections#connections(TLObject)}. */
		groupAddressConnections(1, true),
		/** <code>groupAddressSenders(address)</code>: {@link GroupAddressConnections#senders(TLObject)}. */
		groupAddressSenders(1, true),
		/** <code>groupAddressProblems(address)</code>: {@link GroupAddressConnections#problemText(TLObject)}. */
		groupAddressProblems(1, true),
		/** <code>groupAddressDevices(address)</code>: {@link GroupAddressConnections#devices(TLObject)}. */
		groupAddressDevices(1, true),
		/** <code>connectionLabel(connection)</code>: {@link GroupAddressConnections#label(TLObject)}. */
		connectionLabel(1, true),
		/** <code>connectionDevice(connection)</code>: {@link GroupAddressConnections#device(TLObject)}. */
		connectionDevice(1, true),
		/** <code>connectionObject(connection)</code>: {@link GroupAddressConnections#object(TLObject)}. */
		connectionObject(1, true),
		/** <code>connectionFunction(connection)</code>: {@link GroupAddressConnections#function(TLObject)}. */
		connectionFunction(1, true),
		/**
		 * <code>connectionDatapointType(connection)</code>:
		 * {@link GroupAddressConnections#datapointType(TLObject)}.
		 */
		connectionDatapointType(1, true),
		/** <code>deviceLabel(device)</code>: {@link GroupAddressConnections#deviceLabel(TLObject)}. */
		deviceLabel(1, true),
		/** <code>deviceObjects(device)</code>: {@link GroupAddressConnections#objects(TLObject)}. */
		deviceObjects(1, true),
		/**
		 * <code>existingObject(value)</code>: the value, <code>null</code> for an object deleted
		 * meanwhile (e.g. a selection whose object was deleted in another view).
		 */
		existingObject(1, true),
		/** <code>groupAddressRanges(obj)</code>: {@link GroupAddresses#ranges(TLObject)}. */
		groupAddressRanges(1, true),
		/** <code>groupAddressParts(node)</code>: {@link GroupAddresses#parts(TLObject)}. */
		groupAddressParts(1, true),
		/**
		 * <code>groupAddressDefaultRange(obj, lastAddress)</code>:
		 * {@link GroupAddresses#defaultRange(TLObject, TLObject)}.
		 */
		groupAddressDefaultRange(2, true),
		/** <code>groupAddressNextFree(range)</code>: {@link GroupAddresses#nextFree(TLObject)}. */
		groupAddressNextFree(1, true),
		/**
		 * <code>groupAddressCreateProblem(range, address)</code>: the reason of
		 * {@link GroupAddresses#veto(TLObject, Object)} as text, <code>null</code> if the address can
		 * be created.
		 */
		groupAddressCreateProblem(2, true),
		/**
		 * <code>groupAddressCreate(range, address, name, datapointType)</code>:
		 * {@link GroupAddresses#create(TLObject, Object, String, TLObject)}.
		 */
		groupAddressCreate(4, false),
		/** <code>groupAddressHasType(address)</code>: {@link GroupAddressTypes#hasType(TLObject)}. */
		groupAddressHasType(1, true),
		/** <code>groupAddressEffectiveType(address)</code>: {@link GroupAddressTypes#effectiveType(TLObject)}. */
		groupAddressEffectiveType(1, true),
		/** <code>groupAddressObjectSize(address)</code>: {@link GroupAddressTypes#objectSize(TLObject)}. */
		groupAddressObjectSize(1, true),
		/**
		 * <code>groupAddressTypeChoices(address, all)</code>:
		 * {@link GroupAddressTypes#choices(TLObject, boolean)}.
		 */
		groupAddressTypeChoices(2, true),
		/**
		 * <code>groupAddressTypeVeto(address, type)</code>: the reason of
		 * {@link GroupAddressTypes#veto(TLObject, TLObject)} as text, <code>null</code> if the type can
		 * be set.
		 */
		groupAddressTypeVeto(2, true),
		/**
		 * <code>groupAddressSetType(address, type)</code>:
		 * {@link GroupAddressTypes#setType(TLObject, TLObject)}.
		 */
		groupAddressSetType(2, false),
		/** <code>groupAddressUntypedParts(node)</code>: {@link GroupAddressTypes#untypedParts(TLObject)}. */
		groupAddressUntypedParts(1, true);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
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
		return _function._sideEffectFree;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		switch (_function) {
			case existingObject:
				return arguments[0] instanceof TLObject obj && !obj.tValid() ? null : arguments[0];
			case groupAddressCreate:
				return GroupAddresses.create(object(arguments[0]), arguments[1],
					arguments[2] == null ? null : arguments[2].toString(), object(arguments[3]));
			case groupAddressCreateProblem: {
				ResKey veto = GroupAddresses.veto(object(arguments[0]), arguments[1]);
				return veto == null ? null : Resources.getInstance().getString(veto);
			}
			case groupAddressHasType:
				return Boolean.valueOf(object(arguments[0]) != null && GroupAddressTypes.hasType(object(arguments[0])));
			default:
				break;
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
			case groupAddressRanges:
				return GroupAddresses.ranges(obj);
			case groupAddressParts:
				return GroupAddresses.parts(obj);
			case groupAddressDefaultRange:
				return GroupAddresses.defaultRange(obj, object(arguments[1]));
			case groupAddressNextFree:
				return GroupAddresses.nextFree(obj);
			case groupAddressEffectiveType:
				return GroupAddressTypes.effectiveType(obj);
			case groupAddressObjectSize: {
				Integer size = GroupAddressTypes.objectSize(obj);
				return size == null ? null : SearchExpression.toNumber(size.intValue());
			}
			case groupAddressTypeChoices:
				return GroupAddressTypes.choices(obj, Boolean.TRUE.equals(SearchExpression.asBoolean(arguments[1])));
			case groupAddressTypeVeto: {
				ResKey veto = GroupAddressTypes.veto(obj, object(arguments[1]));
				return veto == null ? null : Resources.getInstance().getString(veto);
			}
			case groupAddressSetType:
				return GroupAddressTypes.setType(obj, object(arguments[1]));
			case groupAddressUntypedParts:
				return GroupAddressTypes.untypedParts(obj);
			case existingObject:
			case groupAddressCreateProblem:
			case groupAddressCreate:
			case groupAddressHasType:
				return obj;
		}
		return null;
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj && obj.tValid() ? obj : null;
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
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new GroupAddressFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
