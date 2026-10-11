/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.annotation.InApp;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.control.table.TableViewControl;
import com.top_logic.layout.view.table.ColumnBinding;
import com.top_logic.layout.view.table.ColumnResolution;
import com.top_logic.layout.view.table.ColumnSetup;
import com.top_logic.layout.view.table.ComputedColumn;
import com.top_logic.table.Column;
import com.top_logic.table.Sort;
import com.top_logic.table.impl.DelegatingColumn;

/**
 * A <code>&lt;computed-column&gt;</code> that cannot be sorted, declared as
 * <code>&lt;row-order-column&gt;</code>: a table grouped by it keeps its groups in the order their
 * first rows appear in.
 *
 * <p>
 * A table orders its groups by the comparator of the grouping column (<code>ListRowSource</code>),
 * which for every kind of value the view layer derives a column for compares the values or their
 * display texts, so a table grouped by the page of a program lists the pages alphabetically. A
 * column without sort capability keeps the groups in the order of the rows, which is the order of
 * the program's structure (haumacher/ehome #20). The view layer offers no declaration of a column
 * that does not sort, so this declaration takes the column a <code>&lt;computed-column&gt;</code>
 * builds and removes its sort capability.
 * </p>
 */
@InApp
public class RowOrderColumn extends ComputedColumn {

	/**
	 * Configuration of a {@link RowOrderColumn}.
	 */
	@TagName("row-order-column")
	public interface Config extends ComputedColumn.Config {

		@Override
		@ClassDefault(RowOrderColumn.class)
		Class<? extends ComputedColumn> getImplementationClass();

	}

	/**
	 * Creates a {@link RowOrderColumn} from configuration.
	 */
	@CalledByReflection
	public RowOrderColumn(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public List<ColumnSetup> resolve(ColumnResolution scope) {
		List<ColumnSetup> result = new ArrayList<>();
		for (ColumnSetup setup : super.resolve(scope)) {
			ColumnBinding unsorted = new Unsorted(setup.binding());
			result.add(new ColumnSetup(setup.name(), setup.label(), setup.type(), setup.value(), setup.viewContext(),
				unsorted, setup.width(), setup.editing(), setup.aggregate(), setup.hiddenByDefault()));
		}
		return result;
	}

	/**
	 * The binding building the column of another binding without its sort capability.
	 */
	private record Unsorted(ColumnBinding inner) implements ColumnBinding {

		@Override
		public Column<Object, ?> createColumn(ColumnSetup setup) {
			return unsorted(inner.createColumn(setup));
		}

		@Override
		public void installUI(ColumnSetup setup, TableViewControl<?> control) {
			inner.installUI(setup, control);
		}

		private static <V> Column<Object, V> unsorted(Column<Object, V> column) {
			return new DelegatingColumn<>(column) {
				@Override
				public Optional<Sort<V>> sort() {
					return Optional.empty();
				}
			};
		}
	}

}
