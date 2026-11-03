/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

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

/**
 * The TL-Script functions of the histories of the datapoints (haumacher/ehome #73), see
 * {@link HistoryConfig}.
 *
 * <p>
 * Each function is registered in <code>ehome.runtime.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class. Only
 * <code>runtimeHistorySetManual</code> and <code>runtimeHistoryReset</code> change data; the view's
 * commands calling them check the write access to the datapoint.
 * </p>
 */
public class HistoryFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>runtimeHistoryDerived(datapoint)</code>: {@link HistoryConfig#describeDerived(TLObject)}. */
		runtimeHistoryDerived(true),
		/** <code>runtimeHistoryEffective(datapoint)</code>: {@link HistoryConfig#describeEffective(TLObject)}. */
		runtimeHistoryEffective(true),
		/** <code>runtimeHistoryManual(datapoint)</code>: {@link HistoryConfig#isManual(TLObject)}. */
		runtimeHistoryManual(true),
		/** <code>runtimeHistoryUsage(project)</code>: {@link HistoryConfig#usage(TLObject)}. */
		runtimeHistoryUsage(true),
		/**
		 * <code>runtimeHistoryBytes(obj)</code>: the bytes of the history of a datapoint
		 * ({@link HistoryConfig#bytes(TLObject)}), or of the datapoints of a chart
		 * ({@link HistoryConfig#chartBytes(TLObject)}).
		 */
		runtimeHistoryBytes(true),
		/** <code>runtimeHistoryDatapoints(project)</code>: {@link HistoryConfig#datapoints(TLObject)}. */
		runtimeHistoryDatapoints(true),
		/** <code>runtimeHistoryCharts(project)</code>: {@link HistoryConfig#charts(TLObject)}. */
		runtimeHistoryCharts(true),
		/**
		 * <code>runtimeArchiveProblem(archive)</code>: what is wrong with an archive set by hand
		 * ({@link HistoryConfig#archiveProblem(TLObject, TLObject)}, else
		 * {@link HistoryConfig#archivesProblem(TLObject)} of its datapoint).
		 */
		runtimeArchiveProblem(true),
		/** <code>runtimeHistorySetManual(datapoint)</code>: {@link HistoryConfig#setManual(TLObject)}, no result. */
		runtimeHistorySetManual(false),
		/** <code>runtimeHistoryReset(datapoint)</code>: {@link HistoryConfig#reset(TLObject)}, no result. */
		runtimeHistoryReset(false);

		private final boolean _sideEffectFree;

		Function(boolean sideEffectFree) {
			_sideEffectFree = sideEffectFree;
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
		return _function._sideEffectFree;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (!(arguments[0] instanceof TLObject obj) || !obj.tValid()) {
			switch (_function) {
				case runtimeHistoryDatapoints:
				case runtimeHistoryCharts:
					return List.of();
				default:
					return null;
			}
		}
		switch (_function) {
			case runtimeHistoryDerived:
				return HistoryConfig.isDatapoint(obj) ? HistoryConfig.describeDerived(obj) : null;
			case runtimeHistoryEffective:
				return HistoryConfig.isDatapoint(obj) ? HistoryConfig.describeEffective(obj) : null;
			case runtimeHistoryManual:
				return Boolean.valueOf(HistoryConfig.isManual(obj));
			case runtimeHistoryUsage:
				return HistoryConfig.usage(obj);
			case runtimeHistoryBytes:
				return SearchExpression.toNumber(Pages.is(obj, "ChartElement") ? HistoryConfig.chartBytes(obj)
					: HistoryConfig.bytes(obj));
			case runtimeHistoryDatapoints:
				return HistoryConfig.datapoints(obj);
			case runtimeHistoryCharts:
				return HistoryConfig.charts(obj);
			case runtimeArchiveProblem: {
				TLObject datapoint = obj.tContainer();
				ResKey problem = HistoryConfig.archiveProblem(obj, datapoint);
				return problem != null || datapoint == null ? problem : HistoryConfig.archivesProblem(datapoint);
			}
			case runtimeHistorySetManual:
				HistoryConfig.setManual(obj);
				return null;
			case runtimeHistoryReset:
				HistoryConfig.reset(obj);
				return null;
		}
		return null;
	}

	/**
	 * Builder of a {@link HistoryFunctions} function: the {@link Function} named as the function is
	 * registered, of one argument.
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
			checkArgs(expr, args, 1, 1);
			return new HistoryFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
