/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.Logger;
import com.top_logic.basic.annotation.InApp;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.control.IReactControl;
import com.top_logic.layout.react.control.table.TableViewControl;
import com.top_logic.layout.view.UIElement;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelInputs;
import com.top_logic.layout.view.channel.ViewChannel;
import com.top_logic.layout.view.command.CommandScope;
import com.top_logic.layout.view.command.ViewCommand;
import com.top_logic.layout.view.command.ViewCommandModel;
import com.top_logic.layout.view.element.TreeTableElement;
import com.top_logic.layout.view.table.ColumnDeclarations;
import com.top_logic.layout.view.table.ColumnResolution;
import com.top_logic.layout.view.table.TableExportCommand;
import com.top_logic.model.TLStructuredType;
import com.top_logic.tool.export.ExcelCellRenderer;

/**
 * A <code>&lt;tree-table&gt;</code> with the Excel export of a <code>&lt;table&gt;</code>,
 * declared as <code>&lt;export-tree-table&gt;</code> with an <code>&lt;export&gt;</code> of the
 * vocabulary of the table's (<code>download-name</code>, <code>sheet-name</code>, ...).
 *
 * <p>
 * The view layer offers the export only as the <code>&lt;export&gt;</code> of a
 * <code>&lt;table&gt;</code>, although the export writes a tree as an outline of the sheet (engine
 * gap, see <code>doc/tl-todo.md</code>). This element contributes the same command
 * ({@link TableExportCommand}) over the view of the tree table to the toolbar of the element
 * around it, as the first command of the export group, for as long as the tree table is displayed.
 * </p>
 */
@InApp
public class ExportTreeTableElement extends TreeTableElement {

	/**
	 * Configuration of an {@link ExportTreeTableElement}.
	 */
	@TagName("export-tree-table")
	public interface Config extends TreeTableElement.Config {

		/** Configuration name for {@link #getExport()}. */
		String EXPORT = "export";

		@Override
		@ClassDefault(ExportTreeTableElement.class)
		Class<? extends UIElement> getImplementationClass();

		/**
		 * The export of this tree table to Excel, offered as a command in the toolbar of the element
		 * the tree table is displayed in; unset, the tree table offers no export.
		 */
		@Name(EXPORT)
		TableExportCommand.Config getExport();
	}

	private final TableExportCommand _export;

	/**
	 * Creates an {@link ExportTreeTableElement} from configuration.
	 */
	@CalledByReflection
	public ExportTreeTableElement(InstantiationContext context, Config config) {
		super(context, config);
		TableExportCommand.Config export = config.getExport();
		_export = export == null ? null : new TableExportCommand(export);
	}

	@Override
	public IReactControl createControl(ViewContext context) {
		IReactControl result = super.createControl(context);
		if (_export == null || !(result instanceof TableViewControl<?> control)) {
			return result;
		}
		CommandScope scope = context.getScope(CommandScope.class);
		if (scope == null) {
			Logger.info("The <" + Config.EXPORT + "> of an <export-tree-table> needs an enclosing element with a "
				+ "toolbar, e.g. a <panel>, to be offered.", ExportTreeTableElement.class);
			return result;
		}
		List<ViewChannel> inputChannels = ChannelInputs.resolve(context, _config.getInputs());
		ViewCommand bound = _export.bind(control::getView, renderers(context),
			() -> ChannelInputs.arguments(inputChannels));
		ViewCommandModel model = ViewCommandModel.forCommand(context, bound, _export.getConfig());
		control.addAttachListener(() -> {
			model.attach(context.getModelScope());
			scope.addCommandFirst(model);
		});
		control.addDetachListener(() -> {
			scope.removeCommand(model);
			model.detach();
		});
		return result;
	}

	/**
	 * The export renderers of the declared columns (an <code>export-renderer</code>, or the one of
	 * the type of the column's values), by column name, as the table resolves them.
	 */
	private Supplier<Map<String, ExcelCellRenderer>> renderers(ViewContext context) {
		return () -> {
			TLStructuredType rowType = resolveRowType(List.of());
			return ColumnDeclarations.exportRenderers(
				ColumnDeclarations.resolve(columns(rowType), new ColumnResolution(rowType, context)));
		};
	}

}
