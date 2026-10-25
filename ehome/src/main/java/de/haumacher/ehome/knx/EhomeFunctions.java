/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.util.Collection;
import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;

import de.haumacher.ehome.knx.ProgramStructure.Position;

/**
 * The TL-Script functions editing an application program, see {@link ProgramStructure}.
 *
 * <p>
 * The functions (registered in <code>ehomeConf.config.xml</code>):
 * </p>
 * <ul>
 * <li><code>structureInsertVeto(anchor, type)</code>: why an element of the type can be inserted
 * neither inside nor after the anchor, or nothing.</li>
 * <li><code>structureInsert(node, anchor, position)</code>: inserts the element (without a position
 * at the default one), results in it.</li>
 * <li><code>structureMoveVeto(nodes, container, before)</code>: why the elements cannot be moved into
 * the container before its element <code>before</code> (at its end for <code>null</code>), or
 * nothing.</li>
 * <li><code>structureMove(nodes, container, before)</code>: moves the elements there.</li>
 * <li><code>programDeleteVeto(obj)</code>: why the parameter, communication object, parameter type or
 * enumeration entry cannot be deleted, or nothing.</li>
 * <li><code>programDelete(obj)</code>: deletes it, a parameter or communication object with its
 * placements.</li>
 * </ul>
 */
public abstract class EhomeFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** See {@link ProgramStructure#insertVeto(TLObject, TLStructuredType)}. */
		structureInsertVeto(2, true),
		/** See {@link ProgramStructure#insert(TLObject, TLObject, Position)}. */
		structureInsert(3, false),
		/** See {@link ProgramStructure#moveVeto(Collection, TLObject, TLObject)}. */
		structureMoveVeto(3, true),
		/** See {@link ProgramStructure#move(Collection, TLObject, TLObject)}. */
		structureMove(3, false),
		/** See {@link ProgramStructure#deleteVeto(TLObject)}. */
		programDeleteVeto(1, true),
		/** See {@link ProgramStructure#delete(TLObject)}. */
		programDelete(1, false);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link EhomeFunctions}.
	 */
	protected EhomeFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
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
		switch (_function) {
			case structureInsertVeto:
				return ProgramStructure.insertVeto(object(arguments[0]), type(arguments[1]));
			case structureInsert:
				return ProgramStructure.insert(object(arguments[0]), object(arguments[1]), Position.of(arguments[2]));
			case structureMoveVeto:
				return ProgramStructure.moveVeto(collection(arguments[0]), object(arguments[1]), object(arguments[2]));
			case structureMove:
				ProgramStructure.move(collection(arguments[0]), object(arguments[1]), object(arguments[2]));
				return null;
			case programDeleteVeto:
				return ProgramStructure.deleteVeto(object(arguments[0]));
			case programDelete:
				ProgramStructure.delete(object(arguments[0]));
				return null;
		}
		return null;
	}

	private static TLObject object(Object value) {
		return value instanceof TLObject obj ? obj : null;
	}

	private static TLStructuredType type(Object value) {
		if (value instanceof TLStructuredType type) {
			return type;
		}
		if (value instanceof TLObject obj) {
			return obj.tType();
		}
		return null;
	}

	private static Collection<?> collection(Object value) {
		if (value == null) {
			return List.of();
		}
		return value instanceof Collection<?> collection ? collection : List.of(value);
	}

	/**
	 * Builder for an {@link EhomeFunctions} function.
	 */
	public abstract static class Builder<F extends EhomeFunctions> extends AbstractSimpleMethodBuilder<F> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config, Function function) {
			super(context, config);
			_function = function;
		}

		@Override
		public F build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return create(getConfig().getName(), args);
		}

		/**
		 * Creates the function expression.
		 */
		protected abstract F create(String name, SearchExpression[] args);
	}

	/**
	 * The function <code>structureInsertVeto</code>; a class of its own, because the script compiler finds the builder
	 * of a function by the class of its expression.
	 */
	public static final class StructureInsertVeto extends EhomeFunctions {

		StructureInsertVeto(String name, SearchExpression[] arguments) {
			super(name, Function.structureInsertVeto, arguments);
		}

		@Override
		public GenericMethod copy(SearchExpression[] arguments) {
			return new StructureInsertVeto(getName(), arguments);
		}

		/**
		 * Builder of {@link StructureInsertVeto}.
		 */
		public static final class Builder extends EhomeFunctions.Builder<StructureInsertVeto> {

			/**
			 * Creates a {@link Builder}.
			 */
			public Builder(InstantiationContext context, Config<?> config) {
				super(context, config, Function.structureInsertVeto);
			}

			@Override
			protected StructureInsertVeto create(String name, SearchExpression[] args) {
				return new StructureInsertVeto(name, args);
			}
		}
	}

	/**
	 * The function <code>structureInsert</code>; a class of its own, because the script compiler finds the builder
	 * of a function by the class of its expression.
	 */
	public static final class StructureInsert extends EhomeFunctions {

		StructureInsert(String name, SearchExpression[] arguments) {
			super(name, Function.structureInsert, arguments);
		}

		@Override
		public GenericMethod copy(SearchExpression[] arguments) {
			return new StructureInsert(getName(), arguments);
		}

		/**
		 * Builder of {@link StructureInsert}.
		 */
		public static final class Builder extends EhomeFunctions.Builder<StructureInsert> {

			/**
			 * Creates a {@link Builder}.
			 */
			public Builder(InstantiationContext context, Config<?> config) {
				super(context, config, Function.structureInsert);
			}

			@Override
			protected StructureInsert create(String name, SearchExpression[] args) {
				return new StructureInsert(name, args);
			}
		}
	}

	/**
	 * The function <code>structureMoveVeto</code>; a class of its own, because the script compiler finds the builder
	 * of a function by the class of its expression.
	 */
	public static final class StructureMoveVeto extends EhomeFunctions {

		StructureMoveVeto(String name, SearchExpression[] arguments) {
			super(name, Function.structureMoveVeto, arguments);
		}

		@Override
		public GenericMethod copy(SearchExpression[] arguments) {
			return new StructureMoveVeto(getName(), arguments);
		}

		/**
		 * Builder of {@link StructureMoveVeto}.
		 */
		public static final class Builder extends EhomeFunctions.Builder<StructureMoveVeto> {

			/**
			 * Creates a {@link Builder}.
			 */
			public Builder(InstantiationContext context, Config<?> config) {
				super(context, config, Function.structureMoveVeto);
			}

			@Override
			protected StructureMoveVeto create(String name, SearchExpression[] args) {
				return new StructureMoveVeto(name, args);
			}
		}
	}

	/**
	 * The function <code>structureMove</code>; a class of its own, because the script compiler finds the builder
	 * of a function by the class of its expression.
	 */
	public static final class StructureMove extends EhomeFunctions {

		StructureMove(String name, SearchExpression[] arguments) {
			super(name, Function.structureMove, arguments);
		}

		@Override
		public GenericMethod copy(SearchExpression[] arguments) {
			return new StructureMove(getName(), arguments);
		}

		/**
		 * Builder of {@link StructureMove}.
		 */
		public static final class Builder extends EhomeFunctions.Builder<StructureMove> {

			/**
			 * Creates a {@link Builder}.
			 */
			public Builder(InstantiationContext context, Config<?> config) {
				super(context, config, Function.structureMove);
			}

			@Override
			protected StructureMove create(String name, SearchExpression[] args) {
				return new StructureMove(name, args);
			}
		}
	}

	/**
	 * The function <code>programDeleteVeto</code>; a class of its own, because the script compiler finds the builder
	 * of a function by the class of its expression.
	 */
	public static final class ProgramDeleteVeto extends EhomeFunctions {

		ProgramDeleteVeto(String name, SearchExpression[] arguments) {
			super(name, Function.programDeleteVeto, arguments);
		}

		@Override
		public GenericMethod copy(SearchExpression[] arguments) {
			return new ProgramDeleteVeto(getName(), arguments);
		}

		/**
		 * Builder of {@link ProgramDeleteVeto}.
		 */
		public static final class Builder extends EhomeFunctions.Builder<ProgramDeleteVeto> {

			/**
			 * Creates a {@link Builder}.
			 */
			public Builder(InstantiationContext context, Config<?> config) {
				super(context, config, Function.programDeleteVeto);
			}

			@Override
			protected ProgramDeleteVeto create(String name, SearchExpression[] args) {
				return new ProgramDeleteVeto(name, args);
			}
		}
	}

	/**
	 * The function <code>programDelete</code>; a class of its own, because the script compiler finds the builder
	 * of a function by the class of its expression.
	 */
	public static final class ProgramDelete extends EhomeFunctions {

		ProgramDelete(String name, SearchExpression[] arguments) {
			super(name, Function.programDelete, arguments);
		}

		@Override
		public GenericMethod copy(SearchExpression[] arguments) {
			return new ProgramDelete(getName(), arguments);
		}

		/**
		 * Builder of {@link ProgramDelete}.
		 */
		public static final class Builder extends EhomeFunctions.Builder<ProgramDelete> {

			/**
			 * Creates a {@link Builder}.
			 */
			public Builder(InstantiationContext context, Config<?> config) {
				super(context, config, Function.programDelete);
			}

			@Override
			protected ProgramDelete create(String name, SearchExpression[] args) {
				return new ProgramDelete(name, args);
			}
		}
	}

}
