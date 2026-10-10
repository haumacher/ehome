/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.etsImport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.KnowledgeBaseException;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;
import com.top_logic.xio.importer.binding.ModelBinding;

import de.haumacher.ehome.importer.Importer;

/**
 * {@link ViewAction} importing the uploaded ETS project files (<code>.knxproj</code>).
 *
 * <p>
 * The input is the uploaded file, or a collection of them. Each file is imported in a transaction
 * of its own. The result is the project created by the last import, so that a following
 * <code>&lt;write-channel&gt;</code> selects it.
 * </p>
 */
public class ImportEtsProjectAction implements ViewAction {

	/**
	 * Configuration of {@link ImportEtsProjectAction}.
	 */
	@TagName("import-ets-project")
	public interface Config extends PolymorphicConfiguration<ImportEtsProjectAction> {

		@Override
		@ClassDefault(ImportEtsProjectAction.class)
		Class<? extends ImportEtsProjectAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ImportEtsProjectAction} from configuration.
	 */
	@CalledByReflection
	public ImportEtsProjectAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		Object result = null;
		if (input instanceof Collection<?> files) {
			for (Object file : files) {
				result = importFile((BinaryData) file);
			}
		} else if (input instanceof BinaryData file) {
			result = importFile(file);
		}
		return result;
	}

	private static TLObject importFile(BinaryData data) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ModelBinding binding = new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false);

		Set<TLObject> before = new HashSet<>(projects());
		try (InputStream in = data.getStream()) {
			try (Transaction tx = kb.beginTransaction()) {
				new Importer(binding).importStream(in);
				tx.commit();
			}
		} catch (ConfigurationException | IOException | XMLStreamException | KnowledgeBaseException ex) {
			throw new TopLogicException(I18NConstants.ERROR_IMPORT_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}

		return projects().stream().filter(p -> !before.contains(p)).findFirst().orElse(null);
	}

	private static List<TLObject> projects() {
		TLClass type = (TLClass) TLModelUtil.findType("ehome.project:Project");
		return MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class);
	}

}
