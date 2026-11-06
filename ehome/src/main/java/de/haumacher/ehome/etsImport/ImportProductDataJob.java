/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.importer.Importer;

/**
 * The work of the product data import of the catalog (<code>&lt;start-job&gt;</code> body of
 * <code>catalogs.view.xml</code>, haumacher/ehome #84): imports the uploaded product files of
 * manufacturers (<code>.knxprod</code>) into the catalog.
 *
 * <p>
 * The job is started with the uploaded file, or a collection of them. Each file is imported in a
 * transaction of its own, in the phases of {@link ImportJobs#importFile}, with the mapping of the
 * project import (see {@link Importer#importProductData}): the manufacturers, catalogs, products and
 * application programs the catalog already holds are reused by their IDs, an application program
 * already known is skipped. The result is the report of the import, a dictionary like the one of
 * {@link ImportEtsProjectJob}: <code>manufacturer</code> is the manufacturer of the file imported
 * last, <code>summary</code> a sentence naming for each file the manufacturers, products and
 * application programs imported and those already known, and the number of messages,
 * <code>messages</code> the warnings and errors the import reported. A file that cannot be imported
 * (a project file, a file with baggage or encrypted entries) fails the job with the reason. A
 * cancelled job imports nothing of the file it is at.
 * </p>
 */
public class ImportProductDataJob implements JobBody {

	/**
	 * Key of the report holding the manufacturer of the product file imported last.
	 */
	public static final String MANUFACTURER = "manufacturer";

	private static final String MANUFACTURER_TYPE = "ehome.catalog:Manufacturer";

	private static final String PRODUCT_TYPE = "ehome.catalog:Product";

	private static final String PROGRAM_TYPE = "ehome.operational:Program";

	private static final String EXT_ID = "extId";

	/**
	 * Configuration of {@link ImportProductDataJob}.
	 */
	public interface Config extends PolymorphicConfiguration<ImportProductDataJob> {

		@Override
		@ClassDefault(ImportProductDataJob.class)
		Class<? extends ImportProductDataJob> getImplementationClass();
	}

	/**
	 * Creates a {@link ImportProductDataJob} from configuration.
	 */
	@CalledByReflection
	public ImportProductDataJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) {
		return ImportJobs.shielded(() -> importFiles(job, arguments));
	}

	private static Object importFiles(JobMonitor job, List<Object> arguments) {
		ImportReport report = new ImportReport(I18NConstants.PRODUCT_SUMMARY__IMPORTED_COUNT);
		for (BinaryData file : ImportJobs.files(arguments)) {
			importFile(job, report, file);
		}
		return report.toMap();
	}

	private static void importFile(JobMonitor job, ImportReport report, BinaryData data) {
		Importer importer = ImportJobs.importFile(job, data, null, true);

		Set<String> manufacturerIds = importer.getManufacturerIds();
		List<TLObject> newManufacturers = manufacturers(importer.getCreated(MANUFACTURER_TYPE), manufacturerIds);
		List<TLObject> knownManufacturers = manufacturers(importer.getKnown(MANUFACTURER_TYPE), manufacturerIds);

		List<String> contents = new ArrayList<>();
		describe(contents, I18NConstants.MANUFACTURERS_IMPORTED__NAMES, newManufacturers);
		describe(contents, I18NConstants.MANUFACTURERS_KNOWN__NAMES, knownManufacturers);
		describe(contents, I18NConstants.PRODUCTS_IMPORTED__NAMES, importer.getCreated(PRODUCT_TYPE));
		describe(contents, I18NConstants.PRODUCTS_KNOWN__NAMES, importer.getKnown(PRODUCT_TYPE));
		describe(contents, I18NConstants.PROGRAMS_IMPORTED__NAMES, importer.getCreated(PROGRAM_TYPE));
		describe(contents, I18NConstants.PROGRAMS_KNOWN__NAMES, importer.getKnown(PROGRAM_TYPE));

		String file = data.getName() == null ? "" : data.getName();
		String description = Resources.getInstance().getString(
			I18NConstants.PRODUCT_FILE__FILE_CONTENTS.fill(file, String.join("; ", contents)));
		TLObject manufacturer = !newManufacturers.isEmpty() ? newManufacturers.get(0)
			: !knownManufacturers.isEmpty() ? knownManufacturers.get(0) : null;
		report.addProductData(file, manufacturer, description, importer.getMessages());
	}

	/**
	 * The manufacturers among the given ones whose data the file holds (not those only listed in
	 * its master data).
	 */
	private static List<TLObject> manufacturers(List<TLObject> manufacturers, Set<String> ids) {
		return manufacturers.stream().filter(m -> ids.contains(m.tValueByName(EXT_ID))).toList();
	}

	private static void describe(List<String> contents, ResKey1 key, List<TLObject> objects) {
		if (objects.isEmpty()) {
			return;
		}
		List<String> labels = objects.stream().map(MetaLabelProvider.INSTANCE::getLabel).toList();
		contents.add(Resources.getInstance().getString(key.fill(String.join(", ", labels))));
	}

}
