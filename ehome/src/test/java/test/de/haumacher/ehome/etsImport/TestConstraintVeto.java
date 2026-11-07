/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.etsImport;

import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ConstraintVeto;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.zigbee.I18NConstants;
import de.haumacher.ehome.zigbee.ZigbeeCatalog;

/**
 * {@link ConstraintVeto}: the problems of a commit the model constraints veto, which the import
 * names instead of the engine's bare "values fail their checks" (a production import failed with
 * nothing else). The veto here is a real one, a Zigbee catalog entry whose datapoint type does not
 * fit its function; another exception is not a veto.
 */
@SuppressWarnings("javadoc")
public class TestConstraintVeto extends BasicTestCase {

	public void testProblems() throws Exception {
		TLObject catalog;
		TLObject entry;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			catalog = create(ZigbeeCatalog.CATALOG);
			TLObject type = create(ZigbeeCatalog.DEVICE_TYPE);
			type.tUpdateByName("manufacturer", "Test");
			type.tUpdateByName("model", "VETO-1");
			type.tUpdateByName("name", FunctionProposal.text(ResKey.text("Veto device")));
			entry = create(ZigbeeCatalog.FUNCTION_ENTRY);
			entry.tUpdateByName("endpoint", Integer.valueOf(1));
			entry.tUpdateByName("function", "temperature");
			type.tUpdateByName("functions", List.of(entry));
			catalog.tUpdateByName("types", List.of(type));
			tx.commit();
		}
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				entry.tUpdateByName("type",
					((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier("boolean"));
				tx.commit();
				fail("The misfit type was stored.");
			} catch (TopLogicException ex) {
				String misfit = Resources.getInstance()
					.getString(I18NConstants.CATALOG_TYPE_MISFIT__TYPE_FUNCTION_VALUE_TYPE.fill("boolean", "temperature", "real"));
				List<String> problems = ConstraintVeto.problems(ex);
				assertNotNull("Not recognized as a veto: " + ex.getErrorKey(), problems);
				assertEquals(1, problems.size());
				assertTrue(problems.get(0), problems.get(0).contains(misfit));
			}
			assertNull(ConstraintVeto.problems(new TopLogicException(ResKey.text("Something else"))));
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				catalog.tDelete();
				tx.commit();
			}
		}
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestConstraintVeto.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
