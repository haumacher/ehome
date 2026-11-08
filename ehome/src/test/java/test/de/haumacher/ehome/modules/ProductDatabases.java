/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.modules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;

/**
 * The import of the real product databases of the module tests (haumacher/ehome #119) into the
 * database shared by the tests.
 *
 * <p>
 * Their master data (<code>knx_master.xml</code>) is left out: it is newer than that of the samples,
 * and its import would update the mask versions and datapoint types every later test reads (the tests
 * share one database, see <code>SharedData</code>). The master data of the samples, imported before,
 * holds what the programs refer to, but for a few datapoint types of newer master data.
 * </p>
 */
final class ProductDatabases {

	private static final String MASTER_DATA = "knx_master.xml";

	private ProductDatabases() {
		// Static only.
	}

	/**
	 * Imports the product database of the given file without its master data.
	 *
	 * @return The errors of the import, but those naming datapoint types the master data of the
	 *         samples does not know.
	 */
	static List<BufferingI18NLog.Entry> importWithoutMasterData(String file) throws Exception {
		return importWithoutMasterData(file, false);
	}

	/**
	 * Imports the product database of the given file without its master data, and, if requested,
	 * only the module structure of its programs (see {@link ModuleStructure}): a test of the
	 * structure of the modules does not read the rest, and a program may hold most of its size in it
	 * (the Zennio DALI BOX: 53 of its 79 MB are translations, half of its 20000 parameters are those of
	 * its channel independent part).
	 *
	 * @return The errors of the import, but those naming datapoint types the master data of the
	 *         samples does not know.
	 */
	static List<BufferingI18NLog.Entry> importWithoutMasterData(String file, boolean structureOnly)
			throws Exception {
		byte[] content = withoutMasterData(file, structureOnly);
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new ByteArrayInputStream(content)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importProductData(in);
				tx.commit();
			}
		}
		// The datapoint types newer than the samples' master data are not known: the objects naming them
		// are imported without them.
		return importer.getMessages().stream().filter(m -> m.getLevel() == Level.ERROR)
			.filter(m -> !String.valueOf(m.getMessage()).contains("DPST-")).toList();
	}

	private static byte[] withoutMasterData(String file, boolean structureOnly) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipInputStream in = new ZipInputStream(new FileInputStream(file));
				ZipOutputStream out = new ZipOutputStream(buffer)) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				if (MASTER_DATA.equals(entry.getName())) {
					continue;
				}
				out.putNextEntry(new ZipEntry(entry.getName()));
				if (structureOnly && entry.getName().endsWith(".xml")) {
					out.write(ModuleStructure.of(in.readAllBytes()));
				} else {
					in.transferTo(out);
				}
				out.closeEntry();
			}
		}
		return buffer.toByteArray();
	}

}
