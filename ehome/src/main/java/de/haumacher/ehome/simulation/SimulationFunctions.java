/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.DeviceChanges;

/**
 * The TL-Script functions of the simulation view (<code>projects/simulation.view.xml</code>), each
 * registered in <code>WEB-INF/autoconf/ehome.simulation.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class.
 *
 * <p>
 * The first argument is the project. The functions changing the simulation return its new state
 * ({@link #state(TLObject)}), which the view writes to its channel; those that change the parameter
 * overrides write them to the project.
 * </p>
 */
public class SimulationFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>simulationState(project, refresh?)</code>: <code>project</code>, <code>running</code>, <code>status</code>
		 * and, while it runs, the snapshot of {@link ProjectSimulation#snapshot()}.
		 */
		simulationState(1, 2, true),
		/** <code>simulationStartVeto(project, state?)</code>: why no simulation can start, <code>null</code> if one can. */
		simulationStartVeto(1, 2, true),
		/** <code>simulationRunVeto(project, state?)</code>: why there is no simulation to work with, <code>null</code> if one runs. */
		simulationRunVeto(1, 2, true),
		/** <code>simulationStart(project, start?)</code>: starts the simulation, its clock at the given date (now if empty). */
		simulationStart(1, 2, false),
		/** <code>simulationStop(project)</code>: stops the simulation. */
		simulationStop(1, 1, false),
		/** <code>simulationAdvance(project, duration)</code>: runs the clock forward by the duration (<code>30min</code>). */
		simulationAdvance(2, 2, false),
		/** <code>simulationStep(project)</code>: runs the clock to the next timer, schedule firing or midnight. */
		simulationStep(1, 1, false),
		/** <code>simulationSend(project, datapoint, value)</code>: a device of the installation sends the value. */
		simulationSend(3, 3, false),
		/** <code>simulationCommand(project, datapoint, value)</code>: commands the datapoint as a controller's UI does. */
		simulationCommand(3, 3, false),
		/** <code>simulationSetParameter(project, key, value)</code>: sets a parameter (<code>rule/parameter</code>). */
		simulationSetParameter(3, 3, false),
		/** <code>simulationResetParameter(project, key)</code>: removes the override of a parameter. */
		simulationResetParameter(2, 2, false),
		/** <code>simulationRun(project, speed)</code>: lets the clock run by itself at 1, 10 or 60 times real time. */
		simulationRun(2, 2, false),
		/** <code>simulationPause(project)</code>: stops the clock running by itself. */
		simulationPause(1, 1, false),
		/** <code>simulationSpeedVeto(project, speed, state?)</code>: why the clock cannot run at the speed, <code>null</code> if it can. */
		simulationSpeedVeto(2, 3, true),
		/** <code>simulationPauseVeto(project, state?)</code>: why there is no clock to pause, <code>null</code> if it runs by itself. */
		simulationPauseVeto(1, 2, true),
		/**
		 * <code>simulationFollowerOptions(follower)</code>: the devices of the project of an object
		 * follower (<code>ehome.runtime:ObjectFollower#device</code>).
		 */
		simulationFollowerOptions(1, 1, true);

		private final int _minArguments;

		private final int _maxArguments;

		private final boolean _sideEffectFree;

		Function(int minArguments, int maxArguments, boolean sideEffectFree) {
			_minArguments = minArguments;
			_maxArguments = maxArguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link SimulationFunctions}.
	 */
	protected SimulationFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new SimulationFunctions(getName(), _function, arguments);
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
		if (_function == Function.simulationFollowerOptions) {
			return followerDevices(arguments[0]);
		}
		TLObject project = arguments[0] instanceof TLObject object && object.tValid() ? object : null;
		Simulations simulations = Simulations.getInstance();
		ProjectSimulation running = simulations.get(project);
		switch (_function) {
			case simulationState:
				return state(project);
			case simulationStartVeto:
				if (project == null) {
					return text(I18NConstants.ERROR_NO_PROJECT);
				}
				return running != null ? text(I18NConstants.ERROR_ALREADY_RUNNING) : null;
			case simulationRunVeto:
				return running == null ? text(I18NConstants.ERROR_NOT_RUNNING) : null;
			case simulationStart: {
				if (project == null) {
					throw new TopLogicException(I18NConstants.ERROR_NO_PROJECT);
				}
				long start = (arguments.length > 1 && arguments[1] instanceof Date date ? date.getTime()
					: System.currentTimeMillis()) / 1000;
				simulations.start(project, start);
				return state(project);
			}
			case simulationStop:
				if (project == null) {
					throw new TopLogicException(I18NConstants.ERROR_NO_PROJECT);
				}
				simulations.stop(project);
				return state(project);
			case simulationSpeedVeto: {
				if (running == null) {
					return text(I18NConstants.ERROR_NOT_RUNNING);
				}
				int speed = speed(arguments[1]);
				return running.simulation().speed() == speed ? text(I18NConstants.ERROR_CLOCK_AT_SPEED) : null;
			}
			case simulationPauseVeto:
				if (running == null) {
					return text(I18NConstants.ERROR_NOT_RUNNING);
				}
				return running.simulation().speed() == 0 ? text(I18NConstants.ERROR_CLOCK_NOT_RUNNING) : null;
			default:
				break;
		}
		if (running == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_RUNNING);
		}
		switch (_function) {
			case simulationAdvance:
				running.advance(string(arguments[1]));
				break;
			case simulationStep:
				running.step();
				break;
			case simulationSend:
				running.send(datapoint(arguments[1]), string(arguments[2]));
				break;
			case simulationCommand:
				running.command(datapoint(arguments[1]), string(arguments[2]));
				break;
			case simulationSetParameter:
				running.setParameter(parameter(arguments[1]), string(arguments[2]));
				break;
			case simulationResetParameter:
				running.resetParameter(parameter(arguments[1]));
				break;
			case simulationRun:
				running.run(speed(arguments[1]));
				break;
			case simulationPause:
				running.pause();
				break;
			default:
				throw new IllegalStateException("Unknown function: " + _function);
		}
		return state(project);
	}

	/**
	 * The state of the simulation of the project as the view shows it.
	 */
	public static Map<String, Object> state(TLObject project) {
		Map<String, Object> result = new LinkedHashMap<>();
		ProjectSimulation running = project == null ? null : Simulations.getInstance().get(project);
		result.put("project", project);
		result.put("running", Boolean.valueOf(running != null));
		if (running == null) {
			result.put("status", text(I18NConstants.STATUS_NOT_RUNNING));
			result.put("next", null);
			result.put("speed", Integer.valueOf(0));
			result.put("clock", null);
			result.put("rules", List.of());
			result.put("parameters", List.of());
			result.put("datapoints", List.of());
			result.put("zigbee", List.of());
			result.put("timeline", List.of());
			return result;
		}
		Map<String, Object> snapshot = running.snapshot();
		result.putAll(snapshot);
		int speed = ((Integer) snapshot.get("speed")).intValue();
		result.put("clock", text(speed == 0 ? I18NConstants.STATUS_CLOCK_PAUSED
			: speed == 1 ? I18NConstants.STATUS_CLOCK_REAL_TIME : I18NConstants.STATUS_CLOCK_FASTER__SPEED.fill(speed)));
		result.put("status", text(I18NConstants.STATUS_RUNNING__TIME_ENDPOINT_DEVICES.fill(snapshot.get("time"),
			snapshot.get("endpoint"), snapshot.get("devices"))));
		Object next = snapshot.get("next");
		result.put("next", text(next == null ? I18NConstants.STATUS_NO_NEXT_EVENT : I18NConstants.STATUS_NEXT_EVENT__TIME.fill(next)));
		return result;
	}

	/** The speeds the clock runs at by itself. */
	private static final List<Integer> SPEEDS = List.of(Integer.valueOf(1), Integer.valueOf(10), Integer.valueOf(60));

	private static int speed(Object value) {
		Integer speed = value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
		if (speed == null || !SPEEDS.contains(speed)) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_SPEED__SPEED.fill(value));
		}
		return speed.intValue();
	}

	private static List<TLObject> followerDevices(Object follower) {
		if (!(follower instanceof TLObject object) || !object.tValid() || object.tContainer() == null) {
			return List.of();
		}
		return new ArrayList<>(DeviceChanges.devices(object.tContainer()));
	}

	private static String string(Object value) {
		return value == null ? null : value.toString();
	}

	private static String datapoint(Object value) {
		Object id = value instanceof Map<?, ?> row ? row.get("id") : value;
		if (id == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_DATAPOINT);
		}
		return id.toString();
	}

	private static String parameter(Object value) {
		Object key = value instanceof Map<?, ?> row ? row.get("key") : value;
		if (key == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PARAMETER);
		}
		return key.toString();
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	/**
	 * Builder of a {@link SimulationFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<SimulationFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public SimulationFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._minArguments, _function._maxArguments);
			return new SimulationFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
