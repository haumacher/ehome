/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.KnowledgeBaseException;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;
import com.top_logic.xio.importer.binding.ModelBinding;

import de.haumacher.ehome.importer.Importer;

/**
 * {@link ViewAction} importing the uploaded ETS project files (<code>.knxproj</code>).
 *
 * <p>
 * The input is the uploaded file, or a collection of them. Each file is imported in a transaction
 * of its own. The result is the report of the import, a dictionary: <code>project</code> is the
 * project created by the last import (for a following <code>&lt;write-channel&gt;</code>),
 * <code>messages</code> the warnings and errors the import reported (each a dictionary with
 * <code>file</code>, <code>severity</code>, <code>level</code> and <code>message</code>), and <code>summary</code> a
 * sentence naming the imported projects and the number of messages. The messages do not change the
 * outcome: a file that cannot be imported fails the action.
 * </p>
 */
public class ImportEtsProjectAction implements ViewAction {

	/**
	 * Key of the report holding the project created by the last import.
	 */
	public static final String PROJECT = "project";

	/**
	 * Key of the report holding the sentence summarizing the import.
	 */
	public static final String SUMMARY = "summary";

	/**
	 * Key of the report holding the list of messages of the import.
	 */
	public static final String MESSAGES = "messages";

	/**
	 * Key of a message holding the name of the uploaded file it belongs to.
	 */
	public static final String FILE = "file";

	/**
	 * Key of a message holding its severity: {@value #SEVERITY_ERROR}, {@value #SEVERITY_WARNING}
	 * or {@value #SEVERITY_INFO}.
	 */
	public static final String SEVERITY = "severity";

	/**
	 * {@link #SEVERITY} of an error.
	 */
	public static final String SEVERITY_ERROR = "error";

	/**
	 * {@link #SEVERITY} of a warning.
	 */
	public static final String SEVERITY_WARNING = "warning";

	/**
	 * {@link #SEVERITY} of a note.
	 */
	public static final String SEVERITY_INFO = "info";

	/**
	 * Key of a message holding the label of its severity.
	 */
	public static final String LEVEL = "level";

	/**
	 * Key of a message holding its text, which includes its location in the project file.
	 */
	public static final String MESSAGE = "message";

	/**
	 * Configuration of {@link ImportEtsProjectAction}.
	 */
	@TagName("import-ets-project")
	public interface Config extends PolymorphicConfiguration<ImportEtsProjectAction> {

		@Override
		@ClassDefault(ImportEtsProjectAction.class)
		Class<? extends ImportEtsProjectAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ImportEtsProjectAction} from configuration.
	 */
	@CalledByReflection
	public ImportEtsProjectAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		ImportReport report = new ImportReport(I18NConstants.IMPORT_SUMMARY__IMPORTED_COUNT);
		if (input instanceof Collection<?> files) {
			for (Object file : files) {
				importFile(report, (BinaryData) file);
			}
		} else if (input instanceof BinaryData file) {
			importFile(report, file);
		}
		return report.toMap();
	}

	private static void importFile(ImportReport report, BinaryData data) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ModelBinding binding = new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false);

		Set<TLObject> before = new HashSet<>(projects());
		Importer importer = new Importer(binding);
		try (InputStream in = data.getStream()) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		} catch (ConfigurationException | IOException | XMLStreamException | KnowledgeBaseException ex) {
			throw new TopLogicException(I18NConstants.ERROR_IMPORT_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}

		TLObject project = projects().stream().filter(p -> !before.contains(p)).findFirst().orElse(null);
		report.addProject(data.getName(), project, importer.getMessages());
	}

	private static List<TLObject> projects() {
		TLClass type = (TLClass) TLModelUtil.findType("ehome.project:Project");
		return MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class);
	}

}
