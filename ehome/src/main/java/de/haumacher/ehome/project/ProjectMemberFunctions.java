/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.project;

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

import de.haumacher.ehome.project.ProjectMembers.Role;

/**
 * The TL-Script functions of the members of a project (haumacher/ehome #152, {@link ProjectMembers}),
 * registered in <code>ehome.project.config.xml</code> under the names of the {@link Function}
 * constants. All but <code>projectRole</code> write: a command calling them runs them in a transaction
 * and checks the operation <code>ManageMembers</code> on the project (leaving a project: <code>Read</code>).
 */
public class ProjectMemberFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>projectRole(project, account)</code>: the role of the account in the project, a classifier
		 * of <code>ehome.project:ProjectRole</code>, <code>null</code> for an account that is no member.
		 */
		projectRole(2, true),
		/**
		 * <code>projectAddMember(project, account, role)</code>: adds the account (or the account with
		 * the given mail address or user name) with the role; the account.
		 */
		projectAddMember(3, false),
		/**
		 * <code>projectSetRole(project, account, role)</code>: gives the member another role; the account.
		 */
		projectSetRole(3, false),
		/**
		 * <code>projectRemoveMember(project, account)</code>: removes the member.
		 */
		projectRemoveMember(2, false),
		/**
		 * <code>projectLeave(project)</code>: the current user leaves the project.
		 */
		projectLeave(1, false);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}

		/**
		 * The number of arguments of the function.
		 */
		public int arguments() {
			return _arguments;
		}

		/**
		 * Whether the function only reads.
		 */
		public boolean sideEffectFree() {
			return _sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link ProjectMemberFunctions}.
	 */
	protected ProjectMemberFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ProjectMemberFunctions(getName(), _function, arguments);
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
		return _function.sideEffectFree();
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		TLObject project = object(arguments[0]);
		if (project == null) {
			return null;
		}
		switch (_function) {
			case projectRole: {
				Role role = ProjectMembers.role(project, object(arguments[1]));
				return role == null ? null : role.classifier();
			}
			case projectAddMember:
				return ProjectMembers.add(project, arguments[1], arguments[2]);
			case projectSetRole:
				return ProjectMembers.setRole(project, arguments[1], arguments[2]);
			case projectRemoveMember:
				ProjectMembers.remove(project, arguments[1]);
				return null;
			case projectLeave:
				ProjectMembers.leave(project);
				return null;
			default:
				return null;
		}
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj && obj.tValid() ? obj : null;
	}

	/**
	 * Builder of a {@link ProjectMemberFunctions} function: the {@link Function} named as the function
	 * is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ProjectMemberFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ProjectMemberFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function.arguments(), _function.arguments());
			return new ProjectMemberFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
