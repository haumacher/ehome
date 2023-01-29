/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
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
import com.top_logic.basic.xml.XMLStreamUtil;

import de.haumacher.ehome.importer.binding.DefaultImportContext;
import de.haumacher.ehome.importer.binding.ImportContext;
import de.haumacher.ehome.importer.binding.ModelBinding;
import de.haumacher.ehome.importer.handlers.DispatchingImporter;
import de.haumacher.ehome.importer.handlers.Handler;

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
		_binding = binding;
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
		ZipInputStream zip = new ZipInputStream(in);
		try (ImportContext importContext = new DefaultImportContext(log, null, _binding)) {
			importETS(zip, importContext, handler);
		}
	}

}
