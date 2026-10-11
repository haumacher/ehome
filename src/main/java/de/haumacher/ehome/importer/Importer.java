/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.Log;
import com.top_logic.basic.Logger;
import com.top_logic.basic.SyserrProtocol;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.ConfigurationReader;
import com.top_logic.basic.config.DefaultInstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.Content;
import com.top_logic.basic.io.binary.ClassRelativeBinaryContent;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.DefaultImportContext;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.binding.ModelBinding;
import com.top_logic.xio.importer.handlers.DispatchingImporter;
import com.top_logic.xio.importer.handlers.Handler;

import de.haumacher.ehome.knx.ExtIds;

/**
 * Imports an ETS project file (<code>.knxproj</code>) or a product file of a manufacturer
 * (<code>.knxprod</code>) into the model, as described in
 * <code>etsImporter.xml</code>, and collects the messages of the import (see
 * {@link #getMessages()}).
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class Importer {

	private static final String EXT_ID = "extId";

	/**
	 * Prefix of the system ID of an entry of the project archive, see {@link #getMessages()}.
	 */
	public static final String ENTRY_SCHEME = "knxproj:";

	/**
	 * Prefix of the folder of a manufacturer's data in an archive, and of the manufacturer's ID.
	 */
	private static final String MANUFACTURER_PREFIX = "M-";

	/**
	 * Prefix of the folder (and the signature) of a project in an archive.
	 */
	private static final String PROJECT_PREFIX = "P-";

	/**
	 * Name of the folder holding the baggage of a product file.
	 */
	private static final String BAGGAGES = "Baggages";

	private JoiningModelBinding _binding;

	private Set<String> _manufacturers = new LinkedHashSet<>();

	private BufferingI18NLog _messages = new BufferingI18NLog();

	/**
	 * Creates a {@link Importer}.
	 *
	 */
	public Importer(ModelBinding binding) {
		_binding = new JoiningModelBinding(binding);
	}

	/**
	 * Imports the XML entries of the given archive with the given handler.
	 *
	 * @param productData
	 *        Whether the archive is a product file (<code>.knxprod</code>), which may hold no project,
	 *        no baggage, and must hold the data of a manufacturer.
	 */
	public void importETS(ZipInputStream zip, ImportContext context, Handler handler, boolean productData)
			throws XMLStreamException, IOException {
		FilterInputStream entryIn = new FilterInputStream(zip) {
			@Override
			public void close() throws IOException {
				zip.closeEntry();
			}
		};
		_manufacturers = new LinkedHashSet<>();
		String last = null;
		ZipEntry entry;
		while ((entry = nextEntry(zip, last)) != null) {
			String name = entry.getName();
			last = name;
			String[] path = name.split("/");
			if (path.length > 1 && path[0].startsWith(MANUFACTURER_PREFIX)) {
				_manufacturers.add(path[0]);
			}
			if (productData) {
				checkProductEntry(name, path);
			}
			if (!entry.isDirectory()) {
				if (name.endsWith(".xml")) {
					Logger.info("Reading '" + name + "'.", Importer.class);

					// The entry name is the system ID, so that a message of the import names the file of
					// the archive. As an absolute URI: the parser resolves a relative one against the
					// working directory of the server.
					XMLStreamReader in = XMLStreamUtil.getDefaultInputFactory()
						.createXMLStreamReader(ENTRY_SCHEME + name, entryIn);
					handler.importXml(context, in);
				}
			}
		}
		if (productData && _manufacturers.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_MANUFACTURER_DATA);
		}
	}

	/**
	 * The next entry of the archive, refusing an archive that cannot be read, in particular one with
	 * an encrypted (password protected) entry.
	 */
	private static ZipEntry nextEntry(ZipInputStream zip, String last) throws IOException {
		try {
			return zip.getNextEntry();
		} catch (ZipException ex) {
			// The JDK reads no encrypted entry and says so only in the message of the exception.
			String message = ex.getMessage();
			if (message != null && message.contains("encrypted")) {
				throw new TopLogicException(
					I18NConstants.ERROR_ENCRYPTED_ENTRY__AFTER.fill(last == null ? "-" : last), ex);
			}
			throw new TopLogicException(I18NConstants.ERROR_INVALID_ARCHIVE__DETAIL.fill(message), ex);
		}
	}

	/**
	 * Refuses an entry a product file must not hold: the part of a project, or baggage (pictures,
	 * help and plug-in files ETS installs with a product).
	 */
	private static void checkProductEntry(String name, String[] path) {
		if (path[0].startsWith(PROJECT_PREFIX)) {
			throw new TopLogicException(I18NConstants.ERROR_PROJECT_IN_PRODUCT_DATA__ENTRY.fill(name));
		}
		for (String segment : path) {
			if (BAGGAGES.equals(segment)) {
				throw new TopLogicException(I18NConstants.ERROR_BAGGAGES_IN_PRODUCT_DATA__ENTRY.fill(name));
			}
		}
	}

	/**
	 * The modules holding the data ETS shares between projects: the datapoint types and the
	 * manufacturer data (catalogs, hardware, application programs).
	 */
	private static final List<String> SHARED_MODULES =
		List.of("ehome.datapoint", "ehome.catalog", "ehome.operational");

	/**
	 * Makes the objects of the data shared between projects that already exist known under their
	 * ETS IDs.
	 *
	 * <p>
	 * This data exists only once: the import reuses the objects that exist (or skips them, see
	 * {@link UnlessKnown}), creates only the missing ones, and references from the imported
	 * project resolve to the existing ones.
	 * </p>
	 */
	private void registerSharedObjects() {
		for (String moduleName : SHARED_MODULES) {
			TLModule module = ModelService.getApplicationModel().getModule(moduleName);
			for (TLClass type : module.getClasses()) {
				if (type.getPart(EXT_ID) == null) {
					continue;
				}
				for (TLObject obj : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
					String id = (String) obj.tValueByName(EXT_ID);
					if (id != null) {
						_binding.assignId(obj, id);
					}
				}
			}
		}
	}

	/**
	 * The messages the last {@link #importStream(InputStream)} reported: the warnings and errors of
	 * the import context, each with its location in the project file (the entry of the archive, line
	 * and column).
	 *
	 * <p>
	 * They are written to the application log as well. A message does not change the outcome of the
	 * import: a value that does not fit its type, for instance, is kept as text and reported.
	 * </p>
	 */
	public List<BufferingI18NLog.Entry> getMessages() {
		return _messages.getEntries();
	}

	/**
	 * Imports the ETS project file (<code>.knxproj</code>) read from the given stream.
	 *
	 * @see #getMessages()
	 */
	public void importStream(InputStream in)
			throws IOException, XMLStreamException, ConfigurationException {
		importStream(in, false);
	}

	/**
	 * Imports the product file (<code>.knxprod</code>) of a manufacturer read from the given stream:
	 * the master data, catalogs, hardware and application programs, with the same mapping as a
	 * project file, without a project.
	 *
	 * <p>
	 * A product file holding a project, baggage, an encrypted entry, or no data of a manufacturer is
	 * refused with a {@link TopLogicException} naming the reason.
	 * </p>
	 *
	 * @see #getMessages()
	 * @see #getManufacturerIds()
	 * @see #getCreated(String)
	 * @see #getKnown(String)
	 */
	public void importProductData(InputStream in)
			throws IOException, XMLStreamException, ConfigurationException {
		importStream(in, true);
	}

	private void importStream(InputStream in, boolean productData)
			throws IOException, XMLStreamException, ConfigurationException {
		_messages = new BufferingI18NLog();
		Log log = new SyserrProtocol();
		DefaultInstantiationContext context = new DefaultInstantiationContext(log);
		ConfigurationReader reader = new ConfigurationReader(context, Collections.singletonMap("handler",
			TypedConfiguration.getConfigurationDescriptor(DispatchingImporter.Config.class)));
		Content content = new ClassRelativeBinaryContent(Importer.class, "etsImporter.xml");
		reader.setSource(content);
		ConfigurationItem config = reader.read();
		Handler handler = (Handler) context.getInstance((PolymorphicConfiguration<?>) config);
		context.checkErrors();
		registerSharedObjects();
		_binding.clearCreated();
		ZipInputStream zip = new ZipInputStream(in);
		try (ImportContext importContext = new DefaultImportContext(
			log.asI18NLog(ResourcesModule.getInstance().getBundle(Locale.ENGLISH)).tee(_messages), _binding)) {
			importETS(zip, importContext, handler, productData);
		}
		// Numbers the app allocates for new objects lie above those of the imported ones.
		ExtIds.raiseImported(_binding.getCreated());
	}

	/**
	 * The IDs of the manufacturers whose data (the folders <code>M-xxxx/</code>) the last import read.
	 */
	public Set<String> getManufacturerIds() {
		return _manufacturers;
	}

	/**
	 * The objects of the given type the last import created, in the order of their creation.
	 *
	 * @param typeName
	 *        The qualified name of the type, e.g. <code>ehome.operational:Program</code>.
	 */
	public List<TLObject> getCreated(String typeName) {
		return ofType(_binding.getCreated(), typeName);
	}

	/**
	 * The objects of the given type that existed before the last import, and which it found under
	 * their IDs: reused, skipped as known, or referenced.
	 *
	 * @param typeName
	 *        The qualified name of the type, e.g. <code>ehome.operational:Program</code>.
	 */
	public List<TLObject> getKnown(String typeName) {
		Set<Object> created = new HashSet<>(_binding.getCreated());
		return ofType(_binding.getResolved().stream().filter(obj -> !created.contains(obj)).toList(), typeName);
	}

	private List<TLObject> ofType(Collection<?> objects, String typeName) {
		List<TLObject> result = new ArrayList<>();
		for (Object obj : objects) {
			if (obj instanceof TLObject tlObject && _binding.isInstanceOf(tlObject, typeName)) {
				result.add(tlObject);
			}
		}
		return result;
	}

}
