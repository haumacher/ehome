/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

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

import de.haumacher.ehome.etsImport.importer.format.ObjectSizeFormat;
import de.haumacher.ehome.model.operational.Program;

/**
 * The TL-Script functions listing the parameters and communication objects of an application
 * program by the page they are placed on, see {@link ProgramPages}, and the display of the size of
 * a communication object.
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class. The functions are implemented
 * in Java, so a view calling them names the types whose changes recompute them in
 * <code>observed-types</code>.
 * </p>
 */
public class ProgramPageFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>programParameterRows(program, filter, unplacedOnly)</code>: the parameters by page,
		 * see {@link ProgramPages#parameterRows(String, boolean)}.
		 */
		programParameterRows(3),

		/**
		 * <code>programPortRows(program, filter, unplacedOnly)</code>: the communication objects by
		 * page, see {@link ProgramPages#portRows(String, boolean)}.
		 */
		programPortRows(3),

		/**
		 * <code>programRowOf(definition)</code>: the first row of a parameter or communication
		 * object, see {@link ProgramPages#rowOf(com.top_logic.model.TLObject)}.
		 */
		programRowOf(1),

		/**
		 * <code>programObjectSize(bits)</code>: the size of a communication object with its unit,
		 * see {@link ObjectSizeFormat#text(Integer)}.
		 */
		programObjectSize(1);

		private final int _arguments;

		Function(int arguments) {
			_arguments = arguments;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link ProgramPageFunctions}.
	 */
	protected ProgramPageFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ProgramPageFunctions(getName(), _function, arguments);
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
		if (_function == Function.programObjectSize) {
			return arguments[0] instanceof Number bits ? ObjectSizeFormat.text(Integer.valueOf(bits.intValue())) : null;
		}
		if (_function == Function.programRowOf) {
			if (arguments[0] instanceof TLObject definition && definition.tValid()
				&& definition.tContainer() instanceof Program program) {
				return new ProgramPages(program).rowOf(definition);
			}
			return null;
		}
		if (!(arguments[0] instanceof Program program) || !program.tValid()) {
			return List.of();
		}
		String filter = arguments[1] == null ? null : asString(arguments[1]);
		boolean unplacedOnly = Boolean.TRUE.equals(asBoolean(arguments[2]));
		ProgramPages pages = new ProgramPages(program);
		switch (_function) {
			case programParameterRows:
				return pages.parameterRows(filter, unplacedOnly);
			case programPortRows:
				return pages.portRows(filter, unplacedOnly);
			case programRowOf:
			case programObjectSize:
				break;
		}
		return List.of();
	}

	/**
	 * Builder of a {@link ProgramPageFunctions} function: the {@link Function} named as the function
	 * is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ProgramPageFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ProgramPageFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new ProgramPageFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
