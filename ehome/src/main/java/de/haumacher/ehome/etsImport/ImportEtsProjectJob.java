/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.importer.PasswordRequiredException;

/**
 * The work of the ETS import (<code>&lt;start-job&gt;</code> body of
 * <code>projects/overview.view.xml</code> and <code>projects/import-password.view.xml</code>,
 * haumacher/ehome #84): imports the uploaded ETS project files (<code>.knxproj</code>).
 *
 * <p>
 * The job is started with the uploaded file, a collection of them, or the request of the password
 * dialog (a dictionary with the uploaded file in <code>file</code>), and with the project password as
 * a further text argument where one was entered. Each file is imported in a transaction of its own,
 * in the phases of {@link ImportJobs#importFile}, with the progress by the entries of the file. The
 * result is the report of the import, a dictionary: <code>project</code> is the project created by
 * the last import (for a following <code>&lt;write-channel&gt;</code>), <code>messages</code> the
 * warnings and errors the import reported (each a dictionary with <code>file</code>,
 * <code>severity</code>, <code>level</code> and <code>message</code>), and <code>summary</code> a
 * sentence naming the imported projects and the number of messages. The messages do not change the
 * outcome: a file that cannot be imported fails the job.
 * </p>
 *
 * <p>
 * A single uploaded file whose project is protected by a password, uploaded without one, is not
 * imported: the result is a dictionary with <code>passwordRequired</code> set to <code>true</code>,
 * the file in <code>file</code> and the reason in <code>message</code>, so that the view can ask for
 * the password and import the file again (a failing job would end the command instead). A wrong
 * password, or none in the request of the password dialog, fails the job with the reason, before
 * anything is imported.
 * </p>
 *
 * <p>
 * A cancelled job imports nothing of the file it is at; of a multi-file upload, the files imported
 * before stay.
 * </p>
 */
public class ImportEtsProjectJob implements JobBody {

	/**
	 * Key of the report holding the project created by the last import.
	 */
	public static final String PROJECT = "project";

	/** The type of the project an import creates. */
	static final String PROJECT_TYPE = "ehome.project:Project";

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
	 * Key of the request of the password dialog and of the result holding the uploaded file.
	 */
	public static final String FILE_DATA = "file";

	/**
	 * Key of the result telling that the uploaded project is protected by a password and was not
	 * imported.
	 */
	public static final String PASSWORD_REQUIRED = "passwordRequired";

	/**
	 * Configuration of {@link ImportEtsProjectJob}.
	 */
	public interface Config extends PolymorphicConfiguration<ImportEtsProjectJob> {

		@Override
		@ClassDefault(ImportEtsProjectJob.class)
		Class<? extends ImportEtsProjectJob> getImplementationClass();
	}

	/**
	 * Creates a {@link ImportEtsProjectJob} from configuration.
	 */
	@CalledByReflection
	public ImportEtsProjectJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		// The cancel interrupt must not reach the database access (TL #30196).
		return Uninterruptible.run(() -> importFiles(job, arguments));
	}

	private static Object importFiles(JobMonitor job, List<Object> arguments) {
		List<BinaryData> files = ImportJobs.files(arguments);
		String password = password(arguments);
		boolean upload = password == null && arguments.stream().noneMatch(Map.class::isInstance);
		ImportReport report = new ImportReport(I18NConstants.IMPORT_SUMMARY__IMPORTED_COUNT);
		for (BinaryData file : files) {
			try {
				importFile(job, report, file, password);
			} catch (PasswordRequiredException ex) {
				if (upload && files.size() == 1) {
					return passwordRequired(file, ex);
				}
				throw ex;
			}
		}
		return report.toMap();
	}

	/**
	 * The project password among the arguments: the first text.
	 */
	static String password(List<Object> arguments) {
		for (Object argument : arguments) {
			if (argument instanceof CharSequence text) {
				return text.toString();
			}
		}
		return null;
	}

	static Map<String, Object> passwordRequired(BinaryData file, PasswordRequiredException ex) {
		Map<String, Object> result = new HashMap<>();
		result.put(PASSWORD_REQUIRED, Boolean.TRUE);
		result.put(FILE_DATA, file);
		result.put(MESSAGE, Resources.getInstance().getString(ex.getErrorKey()));
		return result;
	}

	private static void importFile(JobMonitor job, ImportReport report, BinaryData data, String password) {
		Importer importer = ImportJobs.importFile(job, data, password, false);
		// The project the import created, not one created meanwhile in another session.
		TLObject project = importer.getCreated(PROJECT_TYPE).stream().findFirst().orElse(null);
		report.addProject(data.getName(), project, importer.getMessages());
	}

}
