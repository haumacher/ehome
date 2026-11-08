/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLFormObjectBase;
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
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.GroupAddressTypes;

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
		 * <code>deploymentConfirmation(project)</code>: the question asked before the project is
		 * deployed, with what differs from the image its controller stores
		 * ({@link ImageComparison#confirmation(TLObject, Instant)}).
		 */
		deploymentConfirmation,
		/**
		 * <code>unloadConfirmation(project)</code>: the question asked before the image of the
		 * project's controller is unloaded, with what the controller runs
		 * ({@link ControllerStatus#drift(TLObject)}).
		 */
		unloadConfirmation,
		/**
		 * <code>runtimeGroupAddresses(obj)</code>: the group addresses of the project of the given
		 * object (a project, a function), by their address: the options of a function's roles and of
		 * the time master.
		 */
		runtimeGroupAddresses,
		/**
		 * <code>runtimeDatapoints(obj)</code>: the datapoints of the project of the given object (a
		 * function), the options of a function's roles (haumacher/ehome #61): its group addresses by
		 * their address, then its application datapoints in their order.
		 */
		runtimeDatapoints,
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
		 * <code>runtimeHistoryProblem(datapoint)</code>: what is wrong with the history settings of the
		 * group address or application datapoint; <code>null</code> if they fit ({@link DatapointSettings#historyProblem}).
		 */
		runtimeHistoryProblem,
		/**
		 * <code>runtimeTimeMasterProblem(address, project, attribute)</code>: the constraint of the
		 * time master's addresses (<code>timeMasterTime</code>, <code>timeMasterDate</code>,
		 * <code>timeMasterDateTime</code>), see {@link #timeMasterConstraint(Object, Object, String)}.
		 */
		runtimeTimeMasterProblem,
		/**
		 * <code>runtimeDatapointIdProblem(text, datapoint)</code>: why the text cannot be the id of
		 * the application datapoint ({@link ApplicationDatapoints#idProblem(Object, Object)});
		 * <code>null</code> if it can.
		 */
		runtimeDatapointIdProblem,
		/**
		 * <code>runtimeParameterIdProblem(text, parameter)</code>: why the text cannot be the id of
		 * the rule parameter ({@link RuleExport#parameterIdProblem(Object, Object)});
		 * <code>null</code> if it can.
		 */
		runtimeParameterIdProblem,
		/**
		 * <code>runtimeUnitProblem(text)</code>: why the text cannot be the unit of a datapoint;
		 * <code>null</code> for an empty text or one that can.
		 */
		runtimeUnitProblem,
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
		datapointFlagsSource,
		/**
		 * <code>functionRedundancy(function)</code>: why the function is redundant on the controller,
		 * as text; <code>null</code> if it is not ({@link FunctionProposal#redundancy(TLObject)},
		 * haumacher/ehome #92).
		 */
		functionRedundancy,
		/**
		 * <code>deleteConfirmation(obj)</code>: the question asked before the object is deleted,
		 * naming the parts of the project that refer to it, such as the functions, rules and
		 * schedule entries naming a datapoint ({@link Referers#deleteConfirmation(TLObject)},
		 * haumacher/ehome #94).
		 */
		deleteConfirmation;
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
			case runtimeTimeMasterProblem:
				return timeMasterConstraint(argument, arguments[1], String.valueOf(arguments[2]));
			case runtimeDatapointIdProblem:
				return ApplicationDatapoints.idProblem(argument, arguments[1]);
			case runtimeParameterIdProblem:
				return RuleExport.parameterIdProblem(argument, arguments[1]);
			case runtimeUnitProblem:
				return problem(argument, ApplicationDatapoints::unitProblem);
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
			case deploymentConfirmation:
				return Deployer.veto(obj) != null ? resources.getString(Deployer.veto(obj))
					: ImageComparison.confirmation(obj, Instant.now());
			case unloadConfirmation:
				return Deployer.veto(obj) != null ? resources.getString(Deployer.veto(obj))
					: resources.getString(I18NConstants.CONFIRM_UNLOAD__CONTROLLER
						.fill(Deployer.controller(obj).tValueByName("name"))) + "\n\n" + ControllerStatus.drift(obj);
			case runtimeGroupAddresses:
				return groupAddresses(project(obj));
			case runtimeDatapoints:
				return roleDatapoints(project(obj));
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
			case functionRedundancy:
				return FunctionProposal.redundancy(obj);
			case deleteConfirmation:
				return Referers.deleteConfirmation(obj);
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
		if (text.length() > 127) {
			return I18NConstants.INVALID_TIME_ZONE_TOO_LONG;
		}
		PosixTz.Problem problem = PosixTz.check(text);
		return problem == null ? null : I18NConstants.INVALID_TIME_ZONE__PROBLEM.fill(problem.message(text));
	}

	/**
	 * Why the text is no host name; <code>null</code> if it is one.
	 */
	public static ResKey hostNameProblem(String text) {
		if (text.length() > 127) {
			return I18NConstants.INVALID_HOST_NAME_TOO_LONG;
		}
		ImageValidator.HostProblem problem = ImageValidator.hostNameError(text);
		return problem == null ? null : problem.message(text);
	}

	/**
	 * The datapoint main type the time master sends on the address of the given attribute of a
	 * project; <code>0</code> for another attribute.
	 */
	public static int timeMasterType(String attribute) {
		switch (attribute) {
			case "timeMasterTime":
				return 10;
			case "timeMasterDate":
				return 11;
			case "timeMasterDateTime":
				return 19;
			default:
				return 0;
		}
	}

	/**
	 * Why the group address entered in a form for the given time master attribute of a project is
	 * refused (haumacher/ehome #53): its datapoint type does not fit
	 * ({@link #timeMasterProblem(TLObject, int)}).
	 *
	 * <p>
	 * The check refuses the address when it is set: in a form (the project is the form's editing
	 * buffer, {@link TLFormObjectBase}) whose value differs from the stored one. An address stored
	 * earlier whose datapoint type changed afterwards is not refused (the check would then refuse every
	 * change of the project, the deployment's included); the export names it in its notes. A check of
	 * the stored project, which the knowledge base runs on every commit, refuses nothing for the same
	 * reason.
	 * </p>
	 */
	public static ResKey timeMasterConstraint(Object value, Object project, String attribute) {
		if (!(value instanceof TLObject address) || !(project instanceof TLFormObjectBase form)) {
			return null;
		}
		TLObject stored = form.getEditedObject();
		if (stored != null && stored.tValid() && stored.tValueByName(attribute) == value) {
			return null;
		}
		return timeMasterProblem(address, timeMasterType(attribute));
	}

	/**
	 * Why the group address cannot be the time master's address of the given datapoint main type:
	 * its datapoint type (its own or the one its connected objects give,
	 * {@link GroupAddressTypes#effective(TLObject)}) has another main type; <code>null</code> if it
	 * has none or the given one.
	 */
	public static ResKey timeMasterProblem(TLObject address, int mainType) {
		Dpt dpt = GroupAddressTypes.effective(address);
		if (dpt == null || dpt.main() == mainType) {
			return null;
		}
		return I18NConstants.INVALID_TIME_MASTER_TYPE__TYPE_EXPECTED.fill(dpt.code(), mainType + ".001");
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
	 * The datapoints of the project a role of a function can refer to: its group addresses by their
	 * address, then its application datapoints in their order (haumacher/ehome #61).
	 */
	public static List<TLObject> datapoints(TLObject project) {
		List<TLObject> result = groupAddresses(project);
		if (project != null) {
			result.addAll(ApplicationDatapoints.of(project));
		}
		return result;
	}

	/**
	 * The datapoints a role of a function can refer to: {@link #datapoints(TLObject)}, then the
	 * datapoints of the project's virtual devices (haumacher/ehome #75).
	 */
	public static List<TLObject> roleDatapoints(TLObject project) {
		List<TLObject> result = datapoints(project);
		result.addAll(BlockInstances.datapoints(project));
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
			int arity = _function == Function.runtimeTimeMasterProblem ? 3
				: _function == Function.runtimeDatapointIdProblem || _function == Function.runtimeParameterIdProblem ? 2 : 1;
			checkArgs(expr, args, arity, arity);
			return new RuntimeFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
