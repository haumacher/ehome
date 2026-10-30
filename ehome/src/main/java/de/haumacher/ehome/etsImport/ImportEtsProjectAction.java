/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;
import com.top_logic.xio.importer.binding.ModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.importer.PasswordRequiredException;

/**
 * {@link ViewAction} importing the uploaded ETS project files (<code>.knxproj</code>).
 *
 * <p>
 * The input is the uploaded file, a collection of them, or a dictionary with the uploaded file in
 * <code>file</code> and the project password in <code>password</code>. Each file is imported in a
 * transaction of its own. The result is the report of the import, a dictionary: <code>project</code> is the
 * project created by the last import (for a following <code>&lt;write-channel&gt;</code>),
 * <code>messages</code> the warnings and errors the import reported (each a dictionary with
 * <code>file</code>, <code>severity</code>, <code>level</code> and <code>message</code>), and <code>summary</code> a
 * sentence naming the imported projects and the number of messages. The messages do not change the
 * outcome: a file that cannot be imported fails the action.
 * </p>
 *
 * <p>
 * A single file whose project is protected by a password, uploaded without one, is not imported:
 * the result is a dictionary with <code>passwordRequired</code> set to <code>true</code>, the file
 * in <code>file</code> and the reason in <code>message</code>, so that the view can ask for the
 * password and import the file again. A wrong password fails the action with the reason.
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
	 * Key of the input and of the result holding the uploaded file.
	 */
	public static final String FILE_DATA = "file";

	/**
	 * Key of the input holding the project password.
	 */
	public static final String PASSWORD = "password";

	/**
	 * Key of the result telling that the uploaded project is protected by a password and was not
	 * imported.
	 */
	public static final String PASSWORD_REQUIRED = "passwordRequired";

	/**
	 * Configuration of {@link ImportEtsProjectAction}.
	 */
	@TagName("import-ets-project")
	public interface Config extends PolymorphicConfiguration<ImportEtsProjectAction>, WithResultActions {

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
				importFile(report, (BinaryData) file, null);
			}
		} else if (input instanceof BinaryData file) {
			try {
				importFile(report, file, null);
			} catch (PasswordRequiredException ex) {
				return passwordRequired(file, ex);
			}
		} else if (input instanceof Map<?, ?> request && request.get(FILE_DATA) instanceof BinaryData file) {
			Object password = request.get(PASSWORD);
			importFile(report, file, password == null ? null : password.toString());
		}
		return report.toMap();
	}

	private static Map<String, Object> passwordRequired(BinaryData file, PasswordRequiredException ex) {
		Map<String, Object> result = new HashMap<>();
		result.put(PASSWORD_REQUIRED, Boolean.TRUE);
		result.put(FILE_DATA, file);
		result.put(MESSAGE, Resources.getInstance().getString(ex.getErrorKey()));
		return result;
	}

	private static void importFile(ImportReport report, BinaryData data, String password) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ModelBinding binding = new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false);

		Set<TLObject> before = new HashSet<>(projects());
		Importer importer = new Importer(binding);
		try (InputStream in = data.getStream()) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in, password);
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
