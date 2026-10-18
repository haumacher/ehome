/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.GA;

/**
 * The TL-Script functions of the controllers, the deployment of a project (haumacher/ehome #39), its
 * functions (#43) and the runtime settings of its datapoints and of the project (#45).
 *
 * <p>
 * Each function is registered in <code>ehome.runtime.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 * </p>
 */
public class RuntimeFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>controllerTokenHint(controller)</code>: whether the controller has a token, without
		 * the token itself.
		 */
		controllerTokenHint,
		/** <code>controllerAddress(controller)</code>: <code>host:port</code>. */
		controllerAddress,
		/**
		 * <code>deploymentVeto(project)</code>: why the project cannot be deployed now, as text;
		 * <code>null</code> if it can ({@link Deployer#veto(TLObject)}).
		 */
		deploymentVeto,
		/**
		 * <code>nextImageRevision(project)</code>: the revision the next deployment carries.
		 */
		nextImageRevision,
		/**
		 * <code>runtimeGroupAddresses(obj)</code>: the group addresses of the project of the given
		 * object (a project, a function), by their address: the options of a function's roles and of
		 * the time master.
		 */
		runtimeGroupAddresses,
		/**
		 * <code>runtimeBuildingParts(obj)</code>: the parts of the buildings of the project of the
		 * given object, the options of a function's room.
		 */
		runtimeBuildingParts,
		/**
		 * <code>runtimeIdentifierProblem(text)</code>: why the text is no identifier of the runtime
		 * image; <code>null</code> for an empty text or one that is.
		 */
		runtimeIdentifierProblem,
		/**
		 * <code>runtimeTimeZoneProblem(text)</code>: why the text is no POSIX TZ string;
		 * <code>null</code> for an empty text or one that is.
		 */
		runtimeTimeZoneProblem,
		/**
		 * <code>runtimeHostNameProblem(text)</code>: why the text is no host name;
		 * <code>null</code> for an empty text or one that is.
		 */
		runtimeHostNameProblem,
		/**
		 * <code>runtimeHistoryProblem(address)</code>: what is wrong with the history settings of the
		 * group address; <code>null</code> if they fit ({@link DatapointSettings#historyProblem}).
		 */
		runtimeHistoryProblem,
		/**
		 * <code>datapointWritable(address)</code>: whether the datapoint of the group address is
		 * writable in the runtime image ({@link DatapointSettings#writable(TLObject)}).
		 */
		datapointWritable,
		/**
		 * <code>datapointReadOnStart(address)</code>: whether the datapoint of the group address is
		 * read on start ({@link DatapointSettings#readOnStart(TLObject)}).
		 */
		datapointReadOnStart,
		/**
		 * <code>datapointUnit(address)</code>: the unit of the datapoint of the group address
		 * ({@link DatapointSettings#unit(TLObject)}).
		 */
		datapointUnit,
		/**
		 * <code>datapointFlagsSource(address)</code>: where the flags writable and read on start come
		 * from, as text ({@link DatapointSettings#flagsSource(TLObject)}).
		 */
		datapointFlagsSource;
	}

	private final Function _function;

	/**
	 * Creates a {@link RuntimeFunctions}.
	 */
	protected RuntimeFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new RuntimeFunctions(getName(), _function, arguments);
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
		Object argument = arguments[0];
		switch (_function) {
			case runtimeIdentifierProblem:
				return problem(argument, RuntimeFunctions::identifierProblem);
			case runtimeTimeZoneProblem:
				return problem(argument, RuntimeFunctions::timeZoneProblem);
			case runtimeHostNameProblem:
				return problem(argument, RuntimeFunctions::hostNameProblem);
			default:
				break;
		}
		if (!(argument instanceof TLObject obj) || !obj.tValid()) {
			return null;
		}
		Resources resources = Resources.getInstance();
		switch (_function) {
			case controllerTokenHint: {
				Object token = obj.tValueByName("token");
				return resources.getString(token == null || token.toString().isBlank() ? I18NConstants.TOKEN_NOT_SET
					: I18NConstants.TOKEN_SET);
			}
			case controllerAddress:
				return Deployer.client(obj).address();
			case deploymentVeto: {
				ResKey veto = Deployer.veto(obj);
				return veto == null ? null : resources.getString(veto);
			}
			case nextImageRevision:
				return SearchExpression.toNumber(Deployer.nextRevision(obj));
			case runtimeGroupAddresses:
				return groupAddresses(project(obj));
			case runtimeBuildingParts:
				return buildingParts(project(obj));
			case runtimeHistoryProblem:
				return DatapointSettings.historyProblem(obj);
			case datapointWritable:
				return Boolean.valueOf(DatapointSettings.writable(obj));
			case datapointReadOnStart:
				return Boolean.valueOf(DatapointSettings.readOnStart(obj));
			case datapointUnit:
				return DatapointSettings.unit(obj);
			case datapointFlagsSource:
				return resources.getString(DatapointSettings.flagsSource(obj));
			default:
				return null;
		}
	}

	private static ResKey problem(Object value, java.util.function.Function<String, ResKey> check) {
		if (value == null || value.toString().isBlank()) {
			return null;
		}
		return check.apply(value.toString());
	}

	/**
	 * Why the text is no identifier of a location or function of the runtime image;
	 * <code>null</code> if it is one.
	 */
	public static ResKey identifierProblem(String text) {
		return text.matches("[A-Za-z0-9_.\\-]{1,32}") ? null : I18NConstants.INVALID_IDENTIFIER;
	}

	/**
	 * Why the text is no POSIX TZ string; <code>null</code> if it is one.
	 */
	public static ResKey timeZoneProblem(String text) {
		String error = text.length() > 127 ? "longer than 127 characters" : PosixTz.check(text);
		return error == null ? null : I18NConstants.INVALID_TIME_ZONE__DETAIL.fill(error);
	}

	/**
	 * Why the text is no host name; <code>null</code> if it is one.
	 */
	public static ResKey hostNameProblem(String text) {
		String error = text.length() > 127 ? "longer than 127 characters" : ImageValidator.hostNameError(text);
		return error == null ? null : I18NConstants.INVALID_HOST_NAME__DETAIL.fill(error);
	}

	/**
	 * The project the given object belongs to (itself for a project), <code>null</code> for none.
	 */
	public static TLObject project(TLObject obj) {
		for (TLObject node = obj; node != null; node = node.tContainer()) {
			if ("Project".equals(node.tType().getName()) && "ehome.project".equals(node.tType().getModule().getName())) {
				return node;
			}
		}
		return null;
	}

	/**
	 * The group addresses of the project, by their address.
	 */
	public static List<TLObject> groupAddresses(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		if (project != null) {
			collect(project.tValueByName("groupAddresses"), "ehome.logical:GroupAddress", result);
		}
		result.sort(Comparator.comparingInt(
			(TLObject a) -> a.tValueByName("address") instanceof GA ga ? ga.getValue() : Integer.MAX_VALUE));
		return result;
	}

	/**
	 * The parts of the project's buildings, the buildings first, each followed by its parts.
	 */
	public static List<TLObject> buildingParts(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		if (project != null) {
			collect(project.tValueByName("buildings"), null, result);
		}
		return result;
	}

	private static void collect(Object nodes, String typeName, List<TLObject> result) {
		if (!(nodes instanceof Collection<?> list)) {
			return;
		}
		for (Object element : list) {
			if (!(element instanceof TLObject node)) {
				continue;
			}
			if (typeName == null || TLModelUtil.isGeneralization((TLStructuredType) TLModelUtil.findType(typeName),
				node.tType())) {
				result.add(node);
			}
			collect(node.tValueByName("parts"), typeName, result);
		}
	}

	/**
	 * Builder of a {@link RuntimeFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<RuntimeFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public RuntimeFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, 1, 1);
			return new RuntimeFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
