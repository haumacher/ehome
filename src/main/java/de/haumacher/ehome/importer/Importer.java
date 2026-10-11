/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
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
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.DefaultImportContext;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.binding.ModelBinding;
import com.top_logic.xio.importer.handlers.DispatchingImporter;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * Imports an ETS project file (<code>.knxproj</code>) into the model, as described in
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

	private ModelBinding _binding;

	private BufferingI18NLog _messages = new BufferingI18NLog();

	/**
	 * Creates a {@link Importer}.
	 *
	 */
	public Importer(ModelBinding binding) {
		_binding = new JoiningModelBinding(binding);
	}

	public void importETS(ZipInputStream zip, ImportContext context, Handler handler)
			throws XMLStreamException, IOException {
		FilterInputStream entryIn = new FilterInputStream(zip) {
			@Override
			public void close() throws IOException {
				zip.closeEntry();
			}
		};
		ZipEntry entry;
		while ((entry = zip.getNextEntry()) != null) {
			if (!entry.isDirectory()) {
				if (entry.getName().endsWith(".xml")) {
					Logger.info("Reading '" + entry.getName() + "'.", Importer.class);

					// The entry name is the system ID, so that a message of the import names the file of
					// the archive. As an absolute URI: the parser resolves a relative one against the
					// working directory of the server.
					XMLStreamReader in = XMLStreamUtil.getDefaultInputFactory()
						.createXMLStreamReader(ENTRY_SCHEME + entry.getName(), entryIn);
					handler.importXml(context, in);
				}
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
		ZipInputStream zip = new ZipInputStream(in);
		try (ImportContext importContext = new DefaultImportContext(
			log.asI18NLog(ResourcesModule.getInstance().getBundle(Locale.ENGLISH)).tee(_messages), _binding)) {
			importETS(zip, importContext, handler);
		}
	}

}
