/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.time.Instant;
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
import com.top_logic.util.Resources;

/**
 * The TL-Script functions of the E-Home connection (haumacher/ehome #54), registered in
 * <code>ehome.runtime.config.xml</code> under the names of the {@link Function} constants.
 */
public class ConnectionFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>controllerConnection(controller)</code>: the connection state of the controller as
		 * text ({@link Controllers#state(TLObject)}).
		 */
		controllerConnection,
		/**
		 * <code>controllerOnline(controller)</code>: whether the controller is connected now.
		 */
		controllerOnline,
		/**
		 * <code>enrollmentCodeState(project)</code>: whether the project has an open enrollment code
		 * and until when, as text, without the code; <code>null</code> for none
		 * ({@link Controllers#codeState(TLObject, Instant)}).
		 */
		enrollmentCodeState,
		/**
		 * <code>forgetConfirmation(controller)</code>: the question before forgetting the controller
		 * ({@link I18NConstants#CONFIRM_FORGET__CONTROLLER}).
		 */
		forgetConfirmation;
	}

	private final Function _function;

	/**
	 * Creates a {@link ConnectionFunctions}.
	 */
	protected ConnectionFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ConnectionFunctions(getName(), _function, arguments);
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
		if (!(arguments[0] instanceof TLObject obj) || !obj.tValid()) {
			return null;
		}
		switch (_function) {
			case controllerConnection:
				return Controllers.state(obj);
			case controllerOnline:
				return Boolean.valueOf(Controllers.online(obj));
			case enrollmentCodeState:
				return Controllers.codeState(obj, Instant.now());
			case forgetConfirmation:
				return Resources.getInstance()
					.getString(I18NConstants.CONFIRM_FORGET__CONTROLLER.fill(String.valueOf(obj.tValueByName("name"))));
			default:
				return null;
		}
	}

	/**
	 * Builder of a {@link ConnectionFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ConnectionFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ConnectionFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, 1, 1);
			return new ConnectionFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
