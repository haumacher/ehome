/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.FileManager;
import com.top_logic.basic.Logger;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.db.schema.properties.DBProperties;
import com.top_logic.basic.module.ManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.TypedRuntimeModule;
import com.top_logic.basic.sql.ConnectionPool;
import com.top_logic.basic.sql.PooledConnection;
import com.top_logic.knowledge.service.KBUtils;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

/**
 * Seeds the Zigbee catalog ({@link ZigbeeCatalog}) with the device types E-Home ships: the catalog
 * files (<code>.json</code>, {@link ZigbeeCatalogJson}) in <code>WEB-INF/zigbee-catalog/</code>, each
 * once per database, as the engine loads its initial data (the start hook <code>InitialDataSetup</code>,
 * which reads only its own XML format).
 *
 * <p>
 * A file is loaded at the first start that finds it, and marked as loaded in the database's
 * properties (<code>ehome.zigbee-catalog.&lt;file&gt;</code>): a type the user deletes afterwards does
 * not come back with the next start, and a file a later release adds is loaded once. Loading creates
 * only the types the catalog does not know (by manufacturer and model); a known type stays as the user
 * keeps it. A file that cannot be read or is refused is logged with the reason and not marked, so the
 * next start tries again.
 * </p>
 */
@ServiceDependencies({
	ModelService.Module.class,
})
public class ZigbeeCatalogSeed extends ManagedClass {

	/** The directory of the shipped catalog files. */
	public static final String PATH = "/WEB-INF/zigbee-catalog/";

	private static final String SUFFIX = ".json";

	private static final String PROPERTY_PREFIX = "ehome.zigbee-catalog.";

	/**
	 * Configuration of {@link ZigbeeCatalogSeed}.
	 */
	public interface Config extends ServiceConfiguration<ZigbeeCatalogSeed> {
		// No options.
	}

	/**
	 * Creates a {@link ZigbeeCatalogSeed} from configuration.
	 */
	@CalledByReflection
	public ZigbeeCatalogSeed(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	protected void startUp() {
		super.startUp();
		seed();
	}

	/**
	 * Loads the shipped catalog files not loaded into this database yet.
	 *
	 * @return The names of the files loaded.
	 */
	public static List<String> seed() {
		List<String> loaded = new ArrayList<>();
		TLObject catalog = ZigbeeCatalog.root();
		if (catalog == null) {
			Logger.error("No Zigbee catalog: the model has not created the singleton of " + ZigbeeCatalog.MODULE + ".",
				ZigbeeCatalogSeed.class);
			return loaded;
		}
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ConnectionPool pool = KBUtils.getConnectionPool(kb);
		List<String> names = new ArrayList<>();
		PooledConnection readCon = pool.borrowReadConnection();
		try {
			for (String path : FileManager.getInstance().getResourcePaths(PATH)) {
				String name = path.substring(path.lastIndexOf('/') + 1);
				if (name.endsWith(SUFFIX) && DBProperties.getProperty(readCon, DBProperties.GLOBAL_PROPERTY,
					PROPERTY_PREFIX + name) == null) {
					names.add(name);
				}
			}
		} catch (SQLException ex) {
			Logger.error("Cannot tell which Zigbee catalog files are loaded.", ex, ZigbeeCatalogSeed.class);
			return loaded;
		} finally {
			pool.releaseReadConnection(readCon);
		}
		names.sort(null);
		for (String name : names) {
			String text;
			try (InputStream in = FileManager.getInstance().getStream(PATH + name)) {
				text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException ex) {
				Logger.error("Cannot read the Zigbee catalog file " + name + ".", ex, ZigbeeCatalogSeed.class);
				continue;
			}
			try (Transaction tx = kb.beginTransaction(I18NConstants.CATALOG_SEED__FILE.fill(name))) {
				ZigbeeCatalogJson.Result result = ZigbeeCatalogJson.importText(catalog, text, ZigbeeCatalogJson.Mode.KEEP);
				DBProperties.setProperty(KBUtils.getCurrentContext(kb).getConnection(), DBProperties.GLOBAL_PROPERTY,
					PROPERTY_PREFIX + name, "loaded");
				tx.commit();
				loaded.add(name);
				Logger.info("Zigbee catalog file " + name + " loaded: " + result.created() + " new device types, "
					+ result.unchanged() + " known.", ZigbeeCatalogSeed.class);
			} catch (RuntimeException | SQLException ex) {
				Logger.error("Cannot load the Zigbee catalog file " + name + ".", ex, ZigbeeCatalogSeed.class);
			}
		}
		return loaded;
	}

	/**
	 * Singleton holder for the {@link ZigbeeCatalogSeed}.
	 */
	public static final class Module extends TypedRuntimeModule<ZigbeeCatalogSeed> {

		/** The singleton {@link Module} instance. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton constructor.
		}

		@Override
		public Class<ZigbeeCatalogSeed> getImplementation() {
			return ZigbeeCatalogSeed.class;
		}
	}

}
