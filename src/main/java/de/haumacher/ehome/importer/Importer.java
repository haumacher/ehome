/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
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
import com.top_logic.basic.io.Content;
import com.top_logic.basic.io.binary.ClassRelativeBinaryContent;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.DefaultImportContext;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.binding.ModelBinding;
import com.top_logic.xio.importer.handlers.DispatchingImporter;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class Importer {

	private ModelBinding _binding;

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

					XMLStreamReader in = XMLStreamUtil.getDefaultInputFactory().createXMLStreamReader(entryIn);
					handler.importXml(context, in);
				}
			}
		}
	}

	/**
	 * Makes the datapoint types that already exist known under their ETS IDs.
	 *
	 * <p>
	 * Datapoint types are standardized and exist only once: the import creates them only if there
	 * are none yet, and references to them from the imported project resolve to the existing ones.
	 * </p>
	 */
	private void registerDatapointTypes() {
		TLModule module = ModelService.getApplicationModel().getModule("ehome.datapoint");
		TLObject root = module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
		for (Object mainType : (Collection<?>) root.tValueByName("types")) {
			register((TLObject) mainType);
			for (Object subtype : (Collection<?>) ((TLObject) mainType).tValueByName("subtypes")) {
				register((TLObject) subtype);
			}
		}
	}

	private void register(TLObject datapointType) {
		_binding.assignId(datapointType, (String) datapointType.tValueByName("extId"));
	}

	public void importStream(InputStream in)
			throws IOException, XMLStreamException, ConfigurationException {
		Log log = new SyserrProtocol();
		DefaultInstantiationContext context = new DefaultInstantiationContext(log);
		ConfigurationReader reader = new ConfigurationReader(context, Collections.singletonMap("handler",
			TypedConfiguration.getConfigurationDescriptor(DispatchingImporter.Config.class)));
		Content content = new ClassRelativeBinaryContent(Importer.class, "etsImporter.xml");
		reader.setSource(content);
		ConfigurationItem config = reader.read();
		Handler handler = (Handler) context.getInstance((PolymorphicConfiguration<?>) config);
		context.checkErrors();
		registerDatapointTypes();
		ZipInputStream zip = new ZipInputStream(in);
		try (ImportContext importContext = new DefaultImportContext(
			log.asI18NLog(ResourcesModule.getInstance().getBundle(Locale.ENGLISH)), _binding)) {
			importETS(zip, importContext, handler);
		}
	}

}
