/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

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
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

/**
 * {@link ViewAction} exporting the input project (<code>ehome.project:Project</code>) as an ETS
 * project archive with {@link KnxprojExporter}.
 *
 * <p>
 * The result is the archive as binary data named after the project (<code>&lt;name&gt;.knxproj</code>),
 * for a dialog offering it for download. The archive is not signed, so ETS does not import it.
 * </p>
 */
public class ExportKnxprojAction implements ViewAction {

	/**
	 * The content type of a <code>.knxproj</code> archive (a zip file).
	 */
	public static final String CONTENT_TYPE = "application/zip";

	/**
	 * Configuration of {@link ExportKnxprojAction}.
	 */
	@TagName("export-knxproj")
	public interface Config extends PolymorphicConfiguration<ExportKnxprojAction> {

		@Override
		@ClassDefault(ExportKnxprojAction.class)
		Class<? extends ExportKnxprojAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ExportKnxprojAction} from configuration.
	 */
	@CalledByReflection
	public ExportKnxprojAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject project = (TLObject) input;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			KnxprojExporter.export(project, buffer);
		} catch (IOException | RuntimeException ex) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_FAILED__DETAIL.fill(String.valueOf(ex.getMessage())), ex);
		}
		return BinaryDataFactory.createBinaryData(buffer.toByteArray(), CONTENT_TYPE, fileName(project));
	}

	/**
	 * The file name of the export of the given project: its name (or its ID) with the extension
	 * <code>.knxproj</code>, without characters a file system may refuse.
	 */
	static String fileName(TLObject project) {
		Object name = project.tValueByName("name");
		String base = name == null || name.toString().isBlank() ? Values.extId(project) : name.toString();
		if (base == null || base.isBlank()) {
			base = "project";
		}
		return base.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + ".knxproj";
	}

}
