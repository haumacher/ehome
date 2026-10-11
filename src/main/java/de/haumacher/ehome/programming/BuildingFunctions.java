/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;

/**
 * The TL-Script functions of the devices placed in the parts of a building, see
 * {@link BuildingDevices}.
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class. The functions are implemented
 * in Java: a view calling them names the devices (and, for the group addresses in use, the
 * connections and connectors of devices) in its <code>observed-types</code> (TL #29799).
 * </p>
 */
public class BuildingFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>buildingDevices(part)</code>: {@link BuildingDevices#devices(TLObject)}. */
		buildingDevices(1, true),
		/** <code>buildingConnections(part)</code>: {@link BuildingDevices#connections(TLObject)}. */
		buildingConnections(1, true),
		/** <code>buildingDeviceChoices(part)</code>: {@link BuildingDevices#choices(TLObject)}. */
		buildingDeviceChoices(1, true),
		/** <code>buildingPath(part)</code>: {@link BuildingDevices#path(TLObject)}. */
		buildingPath(1, true),
		/**
		 * <code>buildingPlace(device, part)</code>: {@link BuildingDevices#place(TLObject, TLObject)},
		 * the device as result.
		 */
		buildingPlace(2, false),
		/**
		 * <code>buildingInsertVeto(container, type)</code>:
		 * {@link BuildingDevices#insertVeto(TLObject, TLStructuredType)}.
		 */
		buildingInsertVeto(2, true);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link BuildingFunctions}.
	 */
	protected BuildingFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new BuildingFunctions(getName(), _function, arguments);
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
		TLObject obj = object(arguments[0]);
		if (_function == Function.buildingInsertVeto) {
			return BuildingDevices.insertVeto(obj,
				arguments[1] instanceof TLStructuredType type ? type : null);
		}
		if (obj == null) {
			return _function == Function.buildingPath || _function == Function.buildingPlace ? null : List.of();
		}
		switch (_function) {
			case buildingDevices:
				return BuildingDevices.devices(obj);
			case buildingConnections:
				return BuildingDevices.connections(obj);
			case buildingDeviceChoices:
				return BuildingDevices.choices(obj);
			case buildingPath:
				return BuildingDevices.path(obj);
			case buildingPlace:
				BuildingDevices.place(obj, object(arguments[1]));
				return obj;
		}
		return null;
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj && obj.tValid() ? obj : null;
	}

	/**
	 * Builder of a {@link BuildingFunctions} function: the {@link Function} named as the function
	 * is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<BuildingFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public BuildingFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new BuildingFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
