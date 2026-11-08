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

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;

/**
 * The TL-Script functions reading and changing the configuration of a device, see
 * {@link DeviceConfiguration} and {@link DeviceSettings}.
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 *
 * <p>
 * A view calling these functions in a channel, a list or a tree names the types whose changes
 * recompute them (<code>observed-types</code>: the settings, connectors and connections of devices).
 * </p>
 */
public class DeviceFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>deviceValue(device, view)</code>: {@link DeviceSettings#value(TLObject, ParameterView)}. */
		deviceValue(2, true),
		/**
		 * <code>deviceValueHolder(device, view)</code>:
		 * {@link DeviceConfiguration#valueHolder(TLObject, ParameterView)}.
		 */
		deviceValueHolder(2, true),
		/** <code>deviceSetting(device, view)</code>: {@link DeviceSettings#setting(TLObject, ParameterView)}. */
		deviceSetting(2, true),
		/** <code>deviceEditVeto(device, view)</code>: {@link DeviceSettings#editVeto(TLObject, ParameterView)}. */
		deviceEditVeto(2, true),
		/**
		 * <code>deviceSetValue(device, view, value)</code>:
		 * {@link DeviceSettings#setValue(TLObject, ParameterView, Object)}.
		 */
		deviceSetValue(3, false),
		/**
		 * <code>deviceApplyValue(device, view, value)</code>:
		 * {@link DeviceSettings#applyValue(TLObject, ParameterView, Object)}.
		 */
		deviceApplyValue(3, false),
		/** <code>deviceResetSetting(setting)</code>: {@link DeviceSettings#reset(TLObject)}. */
		deviceResetSetting(1, false),
		/** <code>devicePages(device, page)</code>: {@link DeviceConfiguration#getPages(Object)}. */
		devicePages(2, true),
		/** <code>deviceParentPage(device, page)</code>: {@link DeviceConfiguration#getParentPage(Object)}. */
		deviceParentPage(2, true),
		/** <code>deviceItems(device, page)</code>: {@link DeviceConfiguration#getItems(Object)}. */
		deviceItems(2, true),
		/** <code>deviceText(device, part)</code>: {@link DeviceTexts#label(TLObject, Object)}. */
		deviceText(2, true),
		/** <code>deviceSelectedCases(device)</code>: {@link DeviceConfiguration#getSelectedCases()}. */
		deviceSelectedCases(1, true),
		/** <code>deviceInterfaces(device)</code>: {@link DeviceSettings#interfaces(TLObject)}. */
		deviceInterfaces(1, true),
		/**
		 * <code>deviceConnections(device, interface)</code>:
		 * {@link DeviceSettings#connections(TLObject, Interface)}.
		 */
		deviceConnections(2, true),
		/**
		 * <code>deviceDefaultConnectionKind(device, interface)</code>:
		 * {@link DeviceSettings#defaultKind(TLObject, Interface)}.
		 */
		deviceDefaultConnectionKind(2, true),
		/** <code>deviceGroupAddresses(device)</code>: {@link DeviceSettings#groupAddresses(TLObject)}. */
		deviceGroupAddresses(1, true),
		/**
		 * <code>deviceConnect(device, interface, groupAddress, kind)</code>:
		 * {@link DeviceSettings#connect(TLObject, Interface, TLObject, TLObject)}.
		 */
		deviceConnect(4, false),
		/** <code>deviceDisconnect(connection)</code>: {@link DeviceSettings#disconnect(TLObject)}. */
		deviceDisconnect(1, false);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link DeviceFunctions}.
	 */
	protected DeviceFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new DeviceFunctions(getName(), _function, arguments);
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
		TLObject device = object(arguments[0]);
		if (device == null) {
			return null;
		}
		switch (_function) {
			case deviceValue:
				// A number is a double in TL-Script.
				return arguments[1] instanceof ParameterView view ? toNumber(DeviceSettings.value(device, view)) : null;
			case deviceValueHolder:
				return arguments[1] instanceof ParameterView view ? DeviceConfiguration.valueHolder(device, view)
					: null;
			case deviceSetting:
				return arguments[1] instanceof ParameterView view ? DeviceSettings.setting(device, view) : null;
			case deviceEditVeto:
				return arguments[1] instanceof ParameterView view ? DeviceSettings.editVeto(device, view) : null;
			case deviceSetValue:
				return DeviceSettings.setValue(device, (ParameterView) arguments[1], arguments[2]);
			case deviceApplyValue:
				return DeviceSettings.applyValue(device, (ParameterView) arguments[1], arguments[2]);
			case deviceResetSetting:
				DeviceSettings.reset(device);
				return null;
			case devicePages:
				return new DeviceConfiguration(device).getPages(arguments[1]);
			case deviceParentPage:
				return new DeviceConfiguration(device).getParentPage(arguments[1]);
			case deviceItems:
				return new DeviceConfiguration(device).getItems(arguments[1]);
			case deviceText:
				return arguments[1] == null ? null : DeviceTexts.label(device, arguments[1]);
			case deviceSelectedCases:
				return new DeviceConfiguration(device).getSelectedCases();
			case deviceInterfaces:
				return DeviceSettings.interfaces(device);
			case deviceConnections:
				return arguments[1] instanceof Interface placed ? DeviceSettings.connections(device, placed) : List.of();
			case deviceDefaultConnectionKind:
				return arguments[1] instanceof Interface placed ? DeviceSettings.defaultKind(device, placed) : null;
			case deviceGroupAddresses:
				return DeviceSettings.groupAddresses(device);
			case deviceConnect:
				return DeviceSettings.connect(device, (Interface) arguments[1], object(arguments[2]),
					object(arguments[3]));
			case deviceDisconnect:
				DeviceSettings.disconnect(device);
				return null;
		}
		return null;
	}

	private static Object toNumber(Object value) {
		return value instanceof Number number ? SearchExpression.toNumber(number.doubleValue()) : value;
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj ? obj : null;
	}

	/**
	 * Builder of a {@link DeviceFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<DeviceFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public DeviceFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new DeviceFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
