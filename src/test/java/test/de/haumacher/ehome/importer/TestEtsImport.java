/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package test.de.haumacher.ehome.importer;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.operational.Program;

/**
 * Test case for {@link Importer} importing the ETS sample project <code>samples/Test.knxproj</code>.
 */
@SuppressWarnings("javadoc")
public class TestEtsImport extends BasicTestCase {

	public void testImportSample() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ApplicationModelBinding binding = new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false);
		try (InputStream in = new FileInputStream("samples/Test.knxproj")) {
			try (Transaction tx = kb.beginTransaction()) {
				new Importer(binding).importStream(in);
				tx.commit();
			}
		}

		List<TLObject> projects = instances("ehome.project:Project");
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		assertEquals("Test", project.tValueByName("name"));
		assertEquals("P-01A0", project.tValueByName("extId"));

		List<TLObject> programs = instances("ehome.operational:Program");
		assertFalse("No application program imported.", programs.isEmpty());
		for (TLObject program : programs) {
			assertInstanceof("Generated binding not used.", program, Program.class);
		}
	}

	private static List<TLObject> instances(String typeName) {
		TLClass type = (TLClass) TLModelUtil.findType(typeName);
		return MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestEtsImport.class, ModelService.Module.INSTANCE));
	}

}
