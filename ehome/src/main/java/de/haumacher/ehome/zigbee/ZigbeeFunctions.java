/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

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
 * The TL-Script functions of the Zigbee devices of a project (haumacher/ehome #40): the import of a
 * controller's export and the bindings of the devices' functions to datapoints.
 *
 * <p>
 * Each function is registered in <code>ehome.zigbee.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class ZigbeeFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>zigbeeImport(project, file)</code>: imports the uploaded export of a controller into
		 * the project ({@link ZigbeeImport#importFile(TLObject, Object)}); the sentence saying what was
		 * imported, followed by the notes of the import.
		 */
		zigbeeImport(2, false),
		/**
		 * <code>zigbeeCreateDatapoint(function)</code>: a new application datapoint bound to the
		 * function ({@link ZigbeeBindings#createDatapoint(TLObject)}).
		 */
		zigbeeCreateDatapoint(1, false),
		/**
		 * <code>zigbeeBind(function, datapoint)</code>: binds the application datapoint to the function
		 * ({@link ZigbeeBindings#bind(TLObject, TLObject)}); the datapoint.
		 */
		zigbeeBind(2, false),
		/**
		 * <code>zigbeeUnbind(function)</code>: removes the bindings of the function; its datapoints
		 * stay as datapoints of the application.
		 */
		zigbeeUnbind(1, false),
		/**
		 * <code>zigbeeBindVeto(function)</code>: why no datapoint can be created for or bound to the
		 * function, as text; <code>null</code> if one can.
		 */
		zigbeeBindVeto(1, true),
		/**
		 * <code>zigbeeUnbindVeto(function)</code>: why the function cannot be unbound, as text;
		 * <code>null</code> if it can.
		 */
		zigbeeUnbindVeto(1, true),
		/**
		 * <code>zigbeeCandidates(function)</code>: the application datapoints of the project that can
		 * be bound to the function: bound to no function, of a fitting value type.
		 */
		zigbeeCandidates(1, true),
		/**
		 * <code>zigbeeFunctionOptions(datapoint)</code>: the functions an application datapoint may be
		 * bound to, the options of its field.
		 */
		zigbeeFunctionOptions(1, true);

		private final int _arity;

		private final boolean _sideEffectFree;

		Function(int arity, boolean sideEffectFree) {
			_arity = arity;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link ZigbeeFunctions}.
	 */
	protected ZigbeeFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ZigbeeFunctions(getName(), _function, arguments);
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
		if (_function == Function.zigbeeFunctionOptions) {
			return ZigbeeBindings.functionOptions(arguments[0]);
		}
		if (!(arguments[0] instanceof TLObject obj) || !obj.tValid()) {
			return _function == Function.zigbeeCandidates ? List.of() : null;
		}
		switch (_function) {
			case zigbeeImport:
				return ZigbeeImport.importFile(obj, arguments[1]);
			case zigbeeCreateDatapoint:
				return ZigbeeBindings.createDatapoint(obj);
			case zigbeeBind:
				return arguments[1] instanceof TLObject datapoint ? ZigbeeBindings.bind(obj, datapoint) : null;
			case zigbeeUnbind:
				ZigbeeBindings.unbind(obj);
				return obj;
			case zigbeeBindVeto:
				return text(ZigbeeBindings.bindVeto(obj));
			case zigbeeUnbindVeto:
				return text(ZigbeeBindings.unbindVeto(obj));
			case zigbeeCandidates:
				return ZigbeeBindings.candidates(obj);
			default:
				return null;
		}
	}

	private static String text(ResKey key) {
		return key == null ? null : Resources.getInstance().getString(key);
	}

	/**
	 * Builder of a {@link ZigbeeFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ZigbeeFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ZigbeeFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arity, _function._arity);
			return new ZigbeeFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
