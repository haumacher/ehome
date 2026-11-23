/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

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
 * The TL-Script functions of the histories of the datapoints (haumacher/ehome #73, #221), see
 * {@link HistoryConfig}.
 *
 * <p>
 * Each function is registered in <code>ehome.runtime.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class. None changes data.
 * </p>
 */
public class HistoryFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>runtimeHistoryEffective(datapoint)</code>: {@link HistoryConfig#describeEffective(TLObject)}. */
		runtimeHistoryEffective(1),
		/** <code>runtimeHistoryUsage(project)</code>: {@link HistoryConfig#usage(TLObject)}. */
		runtimeHistoryUsage(1),
		/** <code>runtimeHistoryRam(datapoint)</code>: {@link HistoryConfig#ramBytes(TLObject)}. */
		runtimeHistoryRam(1),
		/** <code>runtimeHistoryFlash(datapoint)</code>: {@link HistoryConfig#flashBytes(TLObject)}. */
		runtimeHistoryFlash(1),
		/** <code>runtimeHistoryDatapoints(project)</code>: {@link HistoryConfig#datapoints(TLObject)}. */
		runtimeHistoryDatapoints(1),
		/** <code>runtimeMeter(datapoint)</code>: {@link HistoryConfig#meter(TLObject)}. */
		runtimeMeter(1),
		/**
		 * <code>runtimeMeterProblem(value, datapoint)</code>: the constraint of <code>meter</code>,
		 * {@link HistoryConfig#meterProblem(Object, TLObject)}.
		 */
		runtimeMeterProblem(2),
		/**
		 * <code>runtimeRecordingProblem(value, datapoint)</code>: the constraint of
		 * <code>recording</code>, {@link HistoryConfig#recordingProblem(Object, TLObject)}.
		 */
		runtimeRecordingProblem(2);

		private final int _arguments;

		Function(int arguments) {
			_arguments = arguments;
		}
	}
	private final Function _function;

	/**
	 * Creates a {@link HistoryFunctions}.
	 */
	protected HistoryFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new HistoryFunctions(getName(), _function, arguments);
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
		switch (_function) {
			case runtimeMeterProblem:
				return arguments[1] instanceof TLObject datapoint ? HistoryConfig.meterProblem(arguments[0], datapoint) : null;
			case runtimeRecordingProblem:
				return arguments[1] instanceof TLObject datapoint ? HistoryConfig.recordingProblem(arguments[0], datapoint)
					: null;
			default:
				break;
		}
		if (!(arguments[0] instanceof TLObject obj) || !obj.tValid()) {
			switch (_function) {
				case runtimeHistoryDatapoints:
					return List.of();
				default:
					return null;
			}
		}
		switch (_function) {
			case runtimeHistoryEffective:
				return HistoryConfig.isDatapoint(obj) ? HistoryConfig.describeEffective(obj) : null;
			case runtimeHistoryUsage:
				return HistoryConfig.usage(obj);
			case runtimeHistoryRam:
				return SearchExpression.toNumber(HistoryConfig.ramBytes(obj));
			case runtimeHistoryFlash:
				return SearchExpression.toNumber(HistoryConfig.flashBytes(obj));
			case runtimeHistoryDatapoints:
				return HistoryConfig.datapoints(obj);
			case runtimeMeter:
				return Boolean.valueOf(HistoryConfig.meter(obj));
			default:
				return null;
		}
	}

	/**
	 * Builder of a {@link HistoryFunctions} function: the {@link Function} named as the function is
	 * registered, of its number of arguments.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<HistoryFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public HistoryFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new HistoryFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
