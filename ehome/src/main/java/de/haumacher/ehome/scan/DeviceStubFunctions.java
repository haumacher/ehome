/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

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

import de.haumacher.ehome.exporter.KnxprojExporter;

/**
 * The TL-Script functions of the stub of a device the catalog does not identify ({@link DeviceStubs},
 * haumacher/ehome #183): the attributes the stub computes, "Assign" in the devices of a project
 * (<code>projects/devices.view.xml</code>) and the note of the export as <code>.knxproj</code>, which
 * leaves the stubs out (<code>projects/export-knxproj.view.xml</code>).
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class DeviceStubFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>deviceStubManufacturer(stub)</code>: {@link DeviceStubs#manufacturer(TLObject)}. */
		deviceStubManufacturer(1, true),
		/** <code>deviceStubOrder(stub)</code>: {@link DeviceStubs#orderText(TLObject)}. */
		deviceStubOrder(1, true),
		/** <code>deviceStubAssign(stub)</code>: {@link DeviceStubs#assign(TLObject)}, the device replacing the stub. */
		deviceStubAssign(1, false),
		/**
		 * <code>knxprojLeftOut(project)</code>: the devices the export as <code>.knxproj</code> leaves
		 * out, named in a text ({@link KnxprojExporter#leftOutNote(TLObject)}); <code>null</code> if it
		 * leaves out none.
		 */
		knxprojLeftOut(1, true);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link DeviceStubFunctions}.
	 */
	protected DeviceStubFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new DeviceStubFunctions(getName(), _function, arguments);
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
		TLObject obj = arguments[0] instanceof TLObject value && value.tValid() ? value : null;
		switch (_function) {
			case deviceStubManufacturer:
				return DeviceStubs.isStub(obj) ? DeviceStubs.manufacturer(obj) : null;
			case deviceStubOrder:
				return DeviceStubs.isStub(obj) ? DeviceStubs.orderText(obj) : null;
			case deviceStubAssign:
				return DeviceStubs.assign(obj);
			case knxprojLeftOut: {
				ResKey note = obj == null ? null : KnxprojExporter.leftOutNote(obj);
				return note == null ? null : Resources.getInstance().getString(note);
			}
		}
		return null;
	}

	/**
	 * Builder of a {@link DeviceStubFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<DeviceStubFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public DeviceStubFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new DeviceStubFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
