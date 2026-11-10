/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.importer.PasswordRequiredException;
import de.haumacher.ehome.importer.ProjectUpdate;
import de.haumacher.ehome.job.Uninterruptible;

/**
 * The work of the command "Update from ETS" (<code>&lt;start-job&gt;</code> body of
 * <code>projects/overview.view.xml</code> and <code>projects/update-password.view.xml</code>,
 * haumacher/ehome #125): updates a project from a new export of the same project in ETS, keeping
 * what E-Home alone holds ({@link ProjectUpdate}).
 *
 * <p>
 * The job is started with the uploaded file and the project to update, or with the request of the
 * password dialog (a dictionary with the file in <code>file</code> and the project in
 * <code>project</code>) and the project password. The file is imported as the ETS import does, in
 * the phases of {@link ImportJobs#importFile}, into a project of its own, which the update merges into
 * the project and deletes, in the same transaction: a refused update (another project, objects the
 * file removes that E-Home uses, …) fails the job with the reasons and changes nothing; so does a
 * cancelled one. The result is the report of {@link ImportEtsProjectJob}, with the project updated
 * in <code>project</code> and the changes per kind among the messages; a project protected by a
 * password, uploaded without one, gives the result of {@link ImportEtsProjectJob} asking for the
 * password, with the project in <code>project</code>.
 * </p>
 */
public class UpdateFromEtsJob implements JobBody {

	/**
	 * Configuration of {@link UpdateFromEtsJob}.
	 */
	public interface Config extends PolymorphicConfiguration<UpdateFromEtsJob> {

		@Override
		@ClassDefault(UpdateFromEtsJob.class)
		Class<? extends UpdateFromEtsJob> getImplementationClass();
	}

	private static final String PROJECT_TYPE = "ehome.project:Project";

	/**
	 * Creates a {@link UpdateFromEtsJob} from configuration.
	 */
	@CalledByReflection
	public UpdateFromEtsJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		// The cancel interrupt must not reach the database access (TL #30196).
		return Uninterruptible.run(() -> update(job, arguments));
	}

	private static Object update(JobMonitor job, List<Object> arguments) {
		TLObject project = project(arguments);
		if (project == null || !project.tValid()) {
			throw new TopLogicException(I18NConstants.ERROR_UPDATE_NO_PROJECT);
		}
		List<BinaryData> files = ImportJobs.files(arguments);
		if (files.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_UPDATE_NO_PROJECT_IN_FILE);
		}
		BinaryData file = files.get(0);
		String password = ImportEtsProjectJob.password(arguments);
		boolean upload = password == null && arguments.stream().noneMatch(Map.class::isInstance);
		ProjectUpdate[] update = new ProjectUpdate[1];
		Importer importer;
		try {
			importer = ImportJobs.importFile(job, file, password, false, imported -> {
				TLObject source = imported.getCreated(PROJECT_TYPE).stream().findFirst().orElse(null);
				if (source == null) {
					throw new TopLogicException(I18NConstants.ERROR_UPDATE_NO_PROJECT_IN_FILE);
				}
				job.message(I18NConstants.PROGRESS_UPDATING__PROJECT.fill(MetaLabelProvider.INSTANCE.getLabel(project)));
				update[0] = new ProjectUpdate(project, source);
				update[0].apply();
			});
		} catch (PasswordRequiredException ex) {
			if (upload) {
				Map<String, Object> result = ImportEtsProjectJob.passwordRequired(file, ex);
				result.put(ImportEtsProjectJob.PROJECT, project);
				return result;
			}
			throw ex;
		}
		List<BufferingI18NLog.Entry> messages = new ArrayList<>(importer.getMessages());
		messages.addAll(update[0].getMessages());
		ImportReport report = new ImportReport(I18NConstants.UPDATE_SUMMARY__UPDATED_COUNT);
		report.addProject(file.getName(), project, messages);
		return report.toMap();
	}

	/**
	 * The project to update among the arguments: the first project, or the one of the request of the
	 * password dialog.
	 */
	private static TLObject project(List<Object> arguments) {
		for (Object argument : arguments) {
			if (argument instanceof Map<?, ?> request && isProject(request.get(ImportEtsProjectJob.PROJECT))) {
				return (TLObject) request.get(ImportEtsProjectJob.PROJECT);
			}
			if (isProject(argument)) {
				return (TLObject) argument;
			}
		}
		return null;
	}

	private static boolean isProject(Object value) {
		return value instanceof TLObject obj && TLModelUtil.isCompatibleInstance(TLModelUtil.findType(PROJECT_TYPE), obj);
	}

}
