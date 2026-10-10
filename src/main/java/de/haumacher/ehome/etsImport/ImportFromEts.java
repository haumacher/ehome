/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.etsImport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.knowledge.service.KnowledgeBaseException;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.DisplayContext;
import com.top_logic.layout.form.FormHandler;
import com.top_logic.layout.form.values.edit.EditorFactory;
import com.top_logic.mig.html.layout.LayoutComponent;
import com.top_logic.tool.boundsec.AbstractCommandHandler;
import com.top_logic.tool.boundsec.HandlerResult;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;
import com.top_logic.xio.importer.binding.ModelBinding;

import de.haumacher.ehome.importer.Importer;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class ImportFromEts extends AbstractCommandHandler {

	/**
	 * Creates a {@link ImportFromEts} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public ImportFromEts(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public HandlerResult handleCommand(DisplayContext aContext, LayoutComponent aComponent, Object model,
			Map<String, Object> someArguments) {
		BinaryData data = ((UploadForm) model).getProjectData();
		
		ModelBinding binding =
			new ApplicationModelBinding(PersistencyLayer.getKnowledgeBase(), ModelService.getApplicationModel(), false);
		
		try (InputStream in = data.getStream()) {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				new Importer(binding).importStream(in);
				tx.commit();
			}
		} catch (ConfigurationException | IOException | XMLStreamException | KnowledgeBaseException ex) {
			return HandlerResult.error(I18NConstants.ERROR_IMPORT_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}
		
		aComponent.closeDialog();

		return HandlerResult.DEFAULT_RESULT;
	}

	@Override
	public Object getTargetModel(LayoutComponent component, Map<String, Object> arguments) {
		return EditorFactory.getModel((FormHandler) component);
	}

}
