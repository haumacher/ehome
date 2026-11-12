/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.KnowledgeBaseException;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.ArchiveContents;
import de.haumacher.ehome.importer.ImportProgress;
import de.haumacher.ehome.importer.Importer;

/**
 * The import of one uploaded file as a step of an import job (haumacher/ehome #84), shared by
 * {@link ImportEtsProjectJob}, {@link ImportProjectFileJob} and {@link ImportProductDataJob}.
 *
 * <p>
 * The file goes through the phases the <code>&lt;start-job&gt;</code> of the view declares:
 * {@value #PHASE_READ} (the contents of the file are counted and a protected project is opened, so a
 * missing or wrong password is refused here), {@value #PHASE_IMPORT} (the entries, with the progress
 * by entry and a message naming what is imported) and {@value #PHASE_SAVE} (the commit, which has no
 * measure). The file is imported in a transaction of its own: a job cancelled before the commit
 * (an {@link com.top_logic.basic.AbortExecutionException} from a report of the job) rolls it back, so
 * nothing of the file is imported.
 * </p>
 */
final class ImportJobs {

	/**
	 * Name of the phase reading the contents of a file.
	 */
	static final String PHASE_READ = "read";

	/**
	 * Name of the phase importing the entries of a file.
	 */
	static final String PHASE_IMPORT = "import";

	/**
	 * Name of the phase committing the import of a file.
	 */
	static final String PHASE_SAVE = "save";

	private ImportJobs() {
		// Static helpers.
	}

	/**
	 * The uploaded files among the given arguments of a job: the first file, collection of files,
	 * or dictionary holding a file under {@value ImportEtsProjectJob#FILE_DATA}.
	 */
	static List<BinaryData> files(List<Object> arguments) {
		for (Object argument : arguments) {
			if (argument instanceof BinaryData file) {
				return List.of(file);
			}
			if (argument instanceof Map<?, ?> request && request.get(ImportEtsProjectJob.FILE_DATA) instanceof BinaryData file) {
				return List.of(file);
			}
			if (argument instanceof Collection<?> collection && !collection.isEmpty()
				&& collection.stream().allMatch(BinaryData.class::isInstance)) {
				List<BinaryData> result = new ArrayList<>();
				for (Object file : collection) {
					result.add((BinaryData) file);
				}
				return result;
			}
		}
		return List.of();
	}

	/**
	 * Imports the given file in a transaction of its own, reporting to the given job.
	 *
	 * @param job
	 *        The monitor of the job.
	 * @param data
	 *        The uploaded file.
	 * @param password
	 *        The project password, <code>null</code> if none was given; ignored for a product file.
	 * @param productData
	 *        Whether the file is a product file (<code>.knxprod</code>) instead of a project file.
	 * @return The importer, which tells what was imported.
	 * @throws de.haumacher.ehome.importer.PasswordRequiredException
	 *         If the project of the file is protected and no password was given.
	 * @throws TopLogicException
	 *         If the file cannot be imported, with the reason.
	 */
	static Importer importFile(JobMonitor job, BinaryData data, String password, boolean productData) {
		String file = fileName(data);
		return importFile(job, data, password, productData, importer -> {
			if (productData) {
				return I18NConstants.COMMIT_PRODUCT_DATA_IMPORT__FILE.fill(file);
			}
			TLObject project = importer.getCreated(ImportEtsProjectJob.PROJECT_TYPE).stream().findFirst().orElse(null);
			return I18NConstants.COMMIT_ETS_IMPORT__PROJECT
				.fill(project == null ? file : MetaLabelProvider.INSTANCE.getLabel(project));
		});
	}

	/**
	 * Imports the given file as {@link #importFile(JobMonitor, BinaryData, String, boolean)} does,
	 * and runs the given step in the transaction of the import, before its commit.
	 *
	 * @param then
	 *        What to import besides, given the importer of the file (the import of a project file
	 *        adds the E-Home part to the project of its <code>.knxproj</code>); its result is the
	 *        commit message of the import, saying what was imported in the user's terms
	 *        (haumacher/ehome #143).
	 */
	static Importer importFile(JobMonitor job, BinaryData data, String password, boolean productData,
			Function<Importer, ResKey> then) {
		String file = fileName(data);
		job.beginPhase(PHASE_READ);
		job.indeterminate();
		job.message(I18NConstants.PROGRESS_READING__FILE.fill(file));
		ArchiveContents contents;
		try (InputStream in = data.getStream()) {
			contents = ArchiveContents.read(in, productData ? null : password, productData);
		} catch (IOException | XMLStreamException ex) {
			throw new TopLogicException(I18NConstants.ERROR_IMPORT_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}

		job.beginPhase(PHASE_IMPORT);
		job.progress(0, contents.getEntries());
		ImportProgress progress = (done, total, message) -> {
			job.progress(done, total);
			job.message(message);
		};

		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = data.getStream()) {
			try (Transaction tx = kb.beginTransaction(I18NConstants.COMMIT_ETS_IMPORT__PROJECT.fill(file))) {
				if (productData) {
					importer.importProductData(in, contents, progress);
				} else {
					importer.importStream(in, password, contents, progress);
				}
				tx.setCommitMessage(then.apply(importer));
				// The last point a cancel is answered at. The commit has none; a cancel during it ends
				// the job as cancelled although the file is saved (TL #30194, doc/tl-todo.md).
				job.beginPhase(PHASE_SAVE);
				job.indeterminate();
				job.message(I18NConstants.PROGRESS_SAVING);
				tx.commit();
			}
		} catch (ConfigurationException | IOException | XMLStreamException | KnowledgeBaseException ex) {
			throw new TopLogicException(I18NConstants.ERROR_IMPORT_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}
		return importer;
	}

	/**
	 * The name of the given uploaded file, empty if it has none.
	 */
	static String fileName(BinaryData data) {
		return data.getName() == null ? "" : data.getName();
	}

}
