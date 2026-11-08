/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.projectfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.exporter.ExportKnxprojAction;

/**
 * {@link ViewAction} exporting the input project (<code>ehome.project:Project</code>) as project
 * file (<code>&lt;name&gt;.ehome</code>, {@link ProjectFile}) with {@link ProjectFileExport}.
 *
 * <p>
 * The result is the file as binary data named after the project, for a dialog offering it for
 * download. The file holds no secrets and no operating history.
 * </p>
 */
public class ExportProjectFileAction implements ViewAction {

	/**
	 * Configuration of {@link ExportProjectFileAction}.
	 */
	@TagName("export-ehome")
	public interface Config extends PolymorphicConfiguration<ExportProjectFileAction>, WithResultActions {

		@Override
		@ClassDefault(ExportProjectFileAction.class)
		Class<? extends ExportProjectFileAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ExportProjectFileAction} from configuration.
	 */
	@CalledByReflection
	public ExportProjectFileAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject project = (TLObject) input;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			ProjectFileExport.export(project, buffer);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_FAILED__DETAIL.fill(String.valueOf(ex.getMessage())), ex);
		}
		return BinaryDataFactory.createBinaryData(buffer.toByteArray(), ProjectFile.CONTENT_TYPE,
			ExportKnxprojAction.fileName(project, ProjectFile.EXTENSION));
	}

}
