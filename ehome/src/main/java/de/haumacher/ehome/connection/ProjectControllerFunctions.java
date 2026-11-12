/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

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
 * The TL-Script functions changing which project a controller belongs to (haumacher/ehome #151,
 * {@link ProjectControllers}), registered in <code>ehome.runtime.config.xml</code> under the names of
 * the {@link Function} constants. They write: a command calling them runs them in a transaction and
 * carries an access check of its own.
 */
public class ProjectControllerFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>controllerCreate(project, name, host, port, token)</code>: a new controller on the LAN in
		 * the project ({@link ProjectControllers#create(TLObject, Object, Object, Object, Object)}).
		 */
		controllerCreate(5),
		/**
		 * <code>controllerAssign(controller, project)</code>: a controller without a project becomes
		 * one of the project's ({@link ProjectControllers#assign(TLObject, TLObject)}).
		 */
		controllerAssign(2);

		private final int _arguments;

		Function(int arguments) {
			_arguments = arguments;
		}

		/**
		 * The number of arguments of the function.
		 */
		public int arguments() {
			return _arguments;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link ProjectControllerFunctions}.
	 */
	protected ProjectControllerFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ProjectControllerFunctions(getName(), _function, arguments);
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
		return false;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		switch (_function) {
			case controllerCreate:
				return ProjectControllers.create(object(arguments[0]), arguments[1], arguments[2], arguments[3],
					arguments[4]);
			case controllerAssign:
				return ProjectControllers.assign(object(arguments[0]), object(arguments[1]));
			default:
				return null;
		}
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj && obj.tValid() ? obj : null;
	}

	/**
	 * Builder of a {@link ProjectControllerFunctions} function: the {@link Function} named as the
	 * function is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ProjectControllerFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ProjectControllerFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function.arguments(), _function.arguments());
			return new ProjectControllerFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
