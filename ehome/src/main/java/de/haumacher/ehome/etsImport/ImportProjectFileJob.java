/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.projectfile.ProjectFile;
import de.haumacher.ehome.projectfile.ProjectFileImport;

/**
 * The work of the import of project files (<code>&lt;name&gt;.ehome</code>, haumacher/ehome #117;
 * <code>&lt;start-job&gt;</code> body of <code>projects/overview.view.xml</code>): each file becomes a
 * new project.
 *
 * <p>
 * The job is started with the uploaded file or a collection of them. A file is read and checked
 * first (a file of another format or of a newer major version of the format is refused before
 * anything is imported); then its <code>.knxproj</code> is imported as the ETS import does, in the
 * phases of {@link ImportJobs#importFile}, and in the same transaction the E-Home part is added to
 * the project it created ({@link ProjectFileImport}). The result is the report of
 * {@link ImportEtsProjectJob}: the messages of both steps, among them that the controllers have to be
 * paired again and, where the installation has a project with the same ID, that both would deploy to
 * the same controllers.
 * </p>
 */
public class ImportProjectFileJob implements JobBody {

	/**
	 * Configuration of {@link ImportProjectFileJob}.
	 */
	public interface Config extends PolymorphicConfiguration<ImportProjectFileJob> {

		@Override
		@ClassDefault(ImportProjectFileJob.class)
		Class<? extends ImportProjectFileJob> getImplementationClass();
	}

	/**
	 * Creates a {@link ImportProjectFileJob} from configuration.
	 */
	@CalledByReflection
	public ImportProjectFileJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		// The cancel interrupt must not reach the database access (TL #30196).
		return Uninterruptible.run(() -> importFiles(job, arguments));
	}

	private static Object importFiles(JobMonitor job, List<Object> arguments) {
		ImportReport report = new ImportReport(I18NConstants.IMPORT_SUMMARY__IMPORTED_COUNT);
		for (BinaryData file : ImportJobs.files(arguments)) {
			importFile(job, report, file);
		}
		return report.toMap();
	}

	private static void importFile(JobMonitor job, ImportReport report, BinaryData data) {
		String name = data.getName() == null ? "" : data.getName();
		ProjectFileImport.Contents contents;
		try (InputStream in = data.getStream()) {
			contents = ProjectFileImport.read(in);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_IMPORT_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}
		List<TLObject> existing = ProjectFileImport.projectsWithId(contents.projectId());
		// Named as the uploaded file: the messages of the ETS import name it.
		BinaryData knxproj = BinaryDataFactory.createBinaryData(contents.knxproj(), ProjectFile.CONTENT_TYPE, name);

		ProjectFileImport projectFile = new ProjectFileImport();
		TLObject[] created = new TLObject[1];
		Importer importer = ImportJobs.importFile(job, knxproj, null, false, imported -> {
			created[0] = imported.getCreated(ProjectFile.PROJECT_TYPE).stream().findFirst().orElse(null);
			if (created[0] == null) {
				throw new TopLogicException(I18NConstants.ERROR_IMPORT_FAILED__DETAIL.fill(ProjectFile.KNXPROJ_ENTRY));
			}
			projectFile.apply(created[0], contents, existing);
			return I18NConstants.COMMIT_PROJECT_FILE_IMPORT__PROJECT.fill(MetaLabelProvider.INSTANCE.getLabel(created[0]));
		});
		List<BufferingI18NLog.Entry> messages = new ArrayList<>(importer.getMessages());
		messages.addAll(projectFile.getMessages());
		report.addProject(name, created[0], messages);
	}

}
