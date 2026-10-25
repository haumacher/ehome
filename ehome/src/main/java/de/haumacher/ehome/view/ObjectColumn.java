/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.annotation.InApp;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.ResourceProvider;
import com.top_logic.layout.react.control.table.CellControlFactory;
import com.top_logic.layout.react.control.table.ReactResourceCellControl;
import com.top_logic.layout.react.control.table.TableViewControl;
import com.top_logic.layout.view.table.ColumnBinding;
import com.top_logic.layout.view.table.ColumnResolution;
import com.top_logic.layout.view.table.ColumnSetup;
import com.top_logic.layout.view.table.ComputedColumn;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.table.CellContent;
import com.top_logic.table.Column;
import com.top_logic.table.ColumnFilter;
import com.top_logic.table.Sort;
import com.top_logic.table.filter.TextColumnFilter;
import com.top_logic.table.impl.DelegatingColumn;
import com.top_logic.util.TLCollator;

/**
 * A <code>&lt;computed-column&gt;</code> showing an object as a node of a <code>&lt;tree&gt;</code>
 * shows it, declared as <code>&lt;object-column&gt;</code>: the icon of the object, a label
 * computed by the TL-Script function <code>text</code> (the object's own label where it gives
 * nothing) and a CSS class computed by <code>css-class</code>.
 *
 * <pre>
 * &lt;object-column name="name" text="x -&gt; if($x.instanceOf(`ehome.physical:Device`), deviceLabel($x), null)"&gt;
 *     &lt;value&gt;x -&gt; $x&lt;/value&gt;
 * &lt;/object-column&gt;
 * </pre>
 *
 * <p>
 * It is the label column of a <code>&lt;tree-table&gt;</code> replacing a tree whose nodes had a
 * label or a mark of their own (a <code>nodeContent</code> with {@link ScriptLabelControlProvider}):
 * a column of the view layer displays its values by their type alone, as an object by its own
 * label, and a tree table has neither a node display nor a CSS class per row (engine gap, see
 * <code>doc/tl-todo.md</code>). The column is sorted, filtered, searched and exported by the label
 * it shows. Both functions take the cell value (the result of <code>value</code>) as their only
 * argument.
 * </p>
 */
@InApp
public class ObjectColumn extends ComputedColumn {

	/**
	 * Configuration of an {@link ObjectColumn}.
	 */
	@TagName("object-column")
	public interface Config extends ComputedColumn.Config {

		/** Configuration name for {@link #getText()}. */
		String TEXT = "text";

		/** Configuration name for {@link #getCssClass()}. */
		String CSS_CLASS = "css-class";

		@Override
		@ClassDefault(ObjectColumn.class)
		Class<? extends ComputedColumn> getImplementationClass();

		/**
		 * Function computing the label of a cell value; <code>null</code> for the value's own
		 * label. Unset, every cell shows the label of its value.
		 */
		@Name(TEXT)
		@Nullable
		Expr getText();

		/**
		 * Function computing the CSS class of a cell value; <code>null</code> for none.
		 */
		@Name(CSS_CLASS)
		@Nullable
		Expr getCssClass();
	}

	private final ResourceProvider _resources;

	/**
	 * Creates an {@link ObjectColumn} from configuration.
	 */
	@CalledByReflection
	public ObjectColumn(InstantiationContext context, Config config) {
		super(context, config);
		_resources = ScriptLabelControlProvider.resources(function(config.getText()), function(config.getCssClass()));
	}

	private static Function<Object, Object> function(Expr expr) {
		if (expr == null) {
			return null;
		}
		QueryExecutor executor = QueryExecutor.compile(expr);
		return executor::execute;
	}

	@Override
	public List<ColumnSetup> resolve(ColumnResolution scope) {
		List<ColumnSetup> result = new ArrayList<>();
		for (ColumnSetup setup : super.resolve(scope)) {
			ColumnBinding display = new Display(setup.binding(), _resources);
			result.add(new ColumnSetup(setup.name(), setup.label(), setup.type(), setup.value(), setup.viewContext(),
				display, setup.width(), setup.editing(), setup.aggregate(), setup.comparator(), setup.hiddenByDefault(),
				setup.export()));
		}
		return result;
	}

	/**
	 * The binding building the column of another binding with the display of {@link ObjectColumn}.
	 */
	private record Display(ColumnBinding inner, ResourceProvider resources) implements ColumnBinding {

		@Override
		public Column<Object, ?> createColumn(ColumnSetup setup) {
			return display(inner.createColumn(setup));
		}

		@Override
		public void installUI(ColumnSetup setup, TableViewControl<?> control) {
			inner.installUI(setup, control);
		}

		@Override
		public Comparator<?> order(Comparator<Object> cellOrder) {
			return inner.order(cellOrder);
		}

		private <V> Column<Object, V> display(Column<Object, V> column) {
			Function<V, String> text = value -> value == null ? "" : resources.getLabel(value);
			Sort<V> sort = () -> Comparator.comparing(text, new TLCollator());
			ColumnFilter<V> filter = new TextColumnFilter<>(text);
			return new DelegatingColumn<>(column) {
				@Override
				public CellContent renderCell(Object row) {
					V value = value(row);
					if (value == null) {
						return CellContent.empty();
					}
					return new CellContent.Raw(
						(CellControlFactory) context -> new ReactResourceCellControl(context, value, resources, true,
							true, false));
				}

				@Override
				public String searchText(Object row) {
					return text.apply(value(row));
				}

				@Override
				public Object exportValue(Object row) {
					return text.apply(value(row));
				}

				@Override
				public Optional<Sort<V>> sort() {
					return Optional.of(sort);
				}

				@Override
				public Optional<ColumnFilter<V>> filter() {
					return Optional.of(filter);
				}
			};
		}
	}

}
