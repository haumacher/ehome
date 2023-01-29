/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.etsImport;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.Logger;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.dob.DataObjectException;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.KnowledgeBaseException;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.DisplayContext;
import com.top_logic.layout.form.FormHandler;
import com.top_logic.layout.form.values.edit.EditorFactory;
import com.top_logic.mig.html.layout.LayoutComponent;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.AbstractCommandHandler;
import com.top_logic.tool.boundsec.HandlerResult;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.importer.binding.ModelBinding;

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
		
		ModelBinding binding = new ModelBinding() {
			
			KnowledgeBase _kb = PersistencyLayer.getKnowledgeBase();

			TLModel _model = ModelService.getApplicationModel();

			private Map<String, Object> _objects = new HashMap<String, Object>();

			@Override
			public void setReference(Object obj, String reference, Object target) {
				setValue(obj, reference, target);
			}

			@Override
			public void setProperty(Object obj, String property, Object applicationValue) {
				setValue(obj, property, applicationValue);
			}
			
			@Override
			public Object resolveObject(String id) {
				return _objects.get(id);
			}
			
			@Override
			public Object eval(Expr expr, Object... args) {
				QueryExecutor executor = QueryExecutor.compile(_kb, _model, expr);
				return executor.execute(args);
			}
			
			@Override
			public Object createObject(String modelType, String id) {
				try {
					TLClass type = (TLClass) TLModelUtil.findType(_model, modelType);
					TLObject result = ModelService.getInstance().getFactory().createObject(type, null, null);
					assignId(result, id);
					return result;
				} catch (ConfigurationException | DataObjectException ex) {
					// TODO
					throw new RuntimeException(ex);
				}
			}
			
			@Override
			public void assignId(Object value, String id) {
				if (id != null) {
					Object clash = _objects.put(id, value);
					if (clash != null && clash != value) {
						Logger.warn("Inconsistent assignment to ID '" + id + "': " + clash + ", vs. " + value,
							ImportFromEts.class);
					}
				}
			}

			@Override
			public void addValue(Object obj, String part, Object element) {
				TLObject self = (TLObject) obj;
				TLStructuredTypePart referencePart = self.tType().getPartOrFail(part);
				Collection<?> currentValue = (Collection<?>) self.tValue(referencePart);
				List<Object> newValue = new ArrayList<>(currentValue);
				newValue.add(element);
				self.tUpdate(referencePart, newValue);
			}
			
			@Override
			public boolean isInstanceOf(Object obj, String type) {
				try {
					TLType expectedType = TLModelUtil.findType(_model, type);
					return TLModelUtil.isCompatibleInstance(expectedType, (TLObject) obj);
				} catch (ConfigurationException ex) {
					// TODO
					throw new RuntimeException(ex);
				}
			}

			private void setValue(Object obj, String partName, Object value) {
				TLObject self = (TLObject) obj;
				TLStructuredTypePart part = self.tType().getPartOrFail(partName);
				Object oldValue = self.tValue(part);
				if (oldValue != null
					&& (!(oldValue instanceof Collection<?>) || !((Collection<?>) oldValue).isEmpty())) {
					// Assuming that null and empty are potential default values.

					Logger.warn("Overriding value of part '" + part + "' with value '" + value + "', old value was '"
						+ oldValue + "'.", ImportFromEts.class);
				}
				self.tUpdate(part, value);
			}
		};
		
		try (InputStream in = data.getStream()) {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				new Importer(binding).importStream(in);
				tx.commit();
			}
		} catch (ConfigurationException | IOException | XMLStreamException | KnowledgeBaseException ex) {
			return HandlerResult.error(I18NConstants.ERROR_IMPORT_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}
		
		return HandlerResult.DEFAULT_RESULT;
	}

	@Override
	public Object getTargetModel(LayoutComponent component) {
		return EditorFactory.getModel((FormHandler) component);
	}

}
