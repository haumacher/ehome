/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Collection;
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
 * The TL-Script functions of the control pages (haumacher/ehome #72), see {@link Pages}.
 *
 * <p>
 * Each function is registered in <code>ehome.runtime.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class. Only <code>pageInsert</code> and
 * <code>pageMove</code> change data; the view's commands calling them check the write access to the
 * project.
 * </p>
 */
public class PageFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>pageOptions(obj, kind)</code>: {@link Pages#options(TLObject, String)}. */
		pageOptions(2, true),
		/** <code>pageChildren(node)</code>: {@link Pages#children(Object)}. */
		pageChildren(1, true),
		/** <code>pageLabel(node)</code>: {@link Pages#label(Object)}. */
		pageLabel(1, true),
		/** <code>pageProblem(node)</code>: {@link Pages#problem(Object)}. */
		pageProblem(1, true),
		/** <code>pageTargetLabel(target)</code>: {@link Pages#targetLabel(TLObject)}. */
		pageTargetLabel(1, true),
		/**
		 * <code>pageImageId(obj)</code>: {@link Pages#imageId(TLObject)} of the target, or of the target of
		 * an element, series or reference line.
		 */
		pageImageId(1, true),
		/** <code>pageValueType(datapoint)</code>: {@link Pages#valueType(TLObject)}. */
		pageValueType(1, true),
		/** <code>pageHasHistory(datapoint)</code>: {@link Pages#hasHistory(TLObject)}. */
		pageHasHistory(1, true),
		/** <code>pageIdentifierProblem(text, page)</code>: {@link Pages#identifierProblem(Object, Object)}. */
		pageIdentifierProblem(2, true),
		/** <code>pageSeriesProblem(datapoint)</code>: {@link Pages#seriesProblem(Object)}. */
		pageSeriesProblem(1, true),
		/** <code>pageConsolidationProblem(consolidation, series)</code>: {@link Pages#consolidationProblem(Object, Object)}. */
		pageConsolidationProblem(2, true),
		/** <code>pageAxisProblem(chart, axis)</code>: {@link Pages#axisProblem(Object, String)}. */
		pageAxisProblem(2, true),
		/** <code>pageReferenceProblem(reference)</code>: {@link Pages#referenceProblem(Object)}. */
		pageReferenceProblem(1, true),
		/** <code>pageInsertVeto(project, selected, kind)</code>: {@link Pages#insertVeto(TLObject, TLObject, String)}. */
		pageInsertVeto(3, true),
		/**
		 * <code>pageInsert(project, selected, kind, targets)</code>:
		 * {@link Pages#insert(TLObject, TLObject, String, Collection)}, the last object added.
		 */
		pageInsert(4, false),
		/** <code>pageMoveVeto(objects, parent, before)</code>: {@link Pages#moveVeto(Collection, TLObject, TLObject)}. */
		pageMoveVeto(3, true),
		/** <code>pageMove(objects, parent, before)</code>: {@link Pages#move(Collection, TLObject, TLObject)}, no result. */
		pageMove(3, false);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link PageFunctions}.
	 */
	protected PageFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new PageFunctions(getName(), _function, arguments);
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
		Object first = arguments[0];
		switch (_function) {
			case pageOptions:
				return object(first) == null ? List.of() : Pages.options(object(first), String.valueOf(arguments[1]));
			case pageChildren:
				return Pages.children(first);
			case pageLabel:
				return Pages.label(first);
			case pageProblem:
				return Pages.problem(first);
			case pageTargetLabel:
				return Pages.targetLabel(object(first));
			case pageImageId: {
				TLObject node = object(first);
				boolean element = Pages.is(node, "PageElement") || Pages.is(node, "ChartSeries")
					|| Pages.is(node, "ChartReference");
				return Pages.imageId(element ? Pages.target(node) : node);
			}
			case pageValueType:
				return Pages.valueType(object(first));
			case pageHasHistory:
				return Boolean.valueOf(Pages.hasHistory(object(first)));
			case pageIdentifierProblem:
				return Pages.identifierProblem(first, arguments[1]);
			case pageSeriesProblem:
				return Pages.seriesProblem(first);
			case pageConsolidationProblem:
				return Pages.consolidationProblem(first, arguments[1]);
			case pageAxisProblem:
				return Pages.axisProblem(first, String.valueOf(arguments[1]));
			case pageReferenceProblem:
				return Pages.referenceProblem(first);
			case pageInsertVeto:
				return Pages.insertVeto(object(first), object(arguments[1]), String.valueOf(arguments[2]));
			case pageInsert:
				return Pages.insert(object(first), object(arguments[1]), String.valueOf(arguments[2]),
					collection(arguments[3]));
			case pageMoveVeto:
				return Pages.moveVeto(collection(first), object(arguments[1]), object(arguments[2]));
			case pageMove:
				Pages.move(collection(first), object(arguments[1]), object(arguments[2]));
				return null;
		}
		return null;
	}

	private static Collection<?> collection(Object value) {
		if (value instanceof Collection<?> collection) {
			return collection;
		}
		return value == null ? List.of() : List.of(value);
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj && obj.tValid() ? obj : null;
	}

	/**
	 * Builder of a {@link PageFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<PageFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public PageFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new PageFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
