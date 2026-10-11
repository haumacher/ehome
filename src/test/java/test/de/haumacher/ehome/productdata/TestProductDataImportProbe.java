/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import com.top_logic.xio.importer.binding.ApplicationModelBinding;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * Probe of the product data import (issue #8) composed with the rest: a device created in the app
 * from a product that came from a product file alone, without any project, has a complete
 * configuration, a download image without problems and an executable plan; a parameter created in
 * that program continues the numbering of the file; and the product file imported after the
 * program was created from scratch changes nothing.
 */
@SuppressWarnings("javadoc")
public class TestProductDataImportProbe extends BasicTestCase {

	private static final String FIXTURES = "src/test/fixtures/";

	private static final String NEW_PROGRAM = "M-0008_A-10D7-11-A0E5-O00F0";

	private static final String NEW_VARIANT = "M-0008_H-8.20.2F.2F.20TEST-1_P-5133.2000";

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		importProduct(FIXTURES + "TestNewProduct.knxprod");
		Program program = (Program) byExtId("ehome.operational:Program", NEW_PROGRAM);
		TLObject variant = byExtId("ehome.catalog:Variant", NEW_VARIANT);
		TLObject product = (TLObject) variant.tValueByName("product");
		TLObject assignment = ((Collection<?>) product.tValueByName("programs")).stream()
			.map(TLObject.class::cast)
			.filter(a -> a.tValueByName("program") == program)
			.findFirst().orElseThrow();

		// A host project for the device: the Test project's line.
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		TLObject project = projects.get(0);
		Parameter created = null;
		try {
			TLObject line = (TLObject) ((Collection<?>) ((TLObject) ((Collection<?>) project.tValueByName("areas"))
				.iterator().next()).tValueByName("lines")).iterator().next();
			TLObject device;
			try (Transaction tx = kb.beginTransaction()) {
				device = create("ehome.physical:Device");
				device.tUpdateByName("address", 77);
				device.tUpdateByName("type", variant);
				device.tUpdateByName("program", assignment);
				add(line, "devices", device);
				tx.commit();
			}

			// The device is configured by the program from the product file alone.
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			assertSame(program, configuration.getProgram());
			assertTrue(configuration.getActiveViews().size() > 100);
			DownloadImage image = DownloadImage.compute(device);
			assertEquals(image.getProblems().toString(), 0, image.getProblems().size());
			assertNotNull(image.getAddressTable());
			assertEquals(77, image.getAddressTable().data()[2] & 0xFF);
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertTrue(plan.getProblems().toString(), plan.isExecutable());
			assertTrue(plan.bytesWritten() > 0);

			// A parameter created in the imported program continues the file's numbering.
			long imported = program.getParameters().stream()
				.mapToLong(p -> ExtIds.Kind.PARAMETER.number(ExtIds.extId(p)))
				.max().orElseThrow();
			try (Transaction tx = kb.beginTransaction()) {
				created = EhomeOperationalFactory.getInstance().createParameter();
				created.setName("Probe");
				created.setType(program.getParameterTypes().iterator().next());
				program.addParameter(created);
				tx.commit();
			}
			assertEquals(NEW_PROGRAM + "_P-" + (imported + 1), created.getExtId());

			// The product file again: nothing changes, the created parameter stays.
			int parameters = program.getParameters().size();
			int programs = instances("ehome.operational:Program").size();
			importProduct(FIXTURES + "TestNewProduct.knxprod");
			assertEquals(parameters, program.getParameters().size());
			assertEquals(programs, instances("ehome.operational:Program").size());
			assertTrue(created.tValid());
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				if (created != null && created.tValid()) {
					created.tDelete();
				}
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static void importProduct(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importProductData(in);
				tx.commit();
			}
		}
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = instances(typeName).stream()
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
		assertEquals(extId, 1, result.size());
		return result.get(0);
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	private static void add(TLObject container, String reference, TLObject element) {
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValueByName(reference));
		elements.add(element);
		container.tUpdateByName(reference, elements);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProductDataImportProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
