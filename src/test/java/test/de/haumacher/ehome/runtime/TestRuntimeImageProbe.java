/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Probe of the runtime image (haumacher/ehome #39) composed with the ETS 6 import: the ETS 4 and the
 * ETS 6 export of the KNX test system give the same datapoints, the image is valid, and building it
 * twice gives the same bytes.
 */
@SuppressWarnings("javadoc")
public class TestRuntimeImageProbe extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	public void testEts4AndEts6() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> ets4 = SharedData.newProjects(before);
		List<TLObject> before6 = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem-ETS6.knxproj");
		List<TLObject> ets6 = SharedData.newProjects(before6);
		try {
			assertEquals(1, ets4.size());
			assertEquals(1, ets6.size());
			RuntimeImage image4 = RuntimeImage.build(ets4.get(0), 3, NOW);
			RuntimeImage image6 = RuntimeImage.build(ets6.get(0), 3, NOW);
			ImageReport report = image6.validate();
			assertTrue(report.toString(), report.isValid());
			assertEquals(image4.getJson().get("datapoints"), image6.getJson().get("datapoints"));
			assertEquals(image4.getNotes(), image6.getNotes());
			assertEquals(image6.getText(), RuntimeImage.build(ets6.get(0), 3, NOW).getText());
		} finally {
			SharedData.deleteProjects(ets4);
			SharedData.deleteProjects(ets6);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuntimeImageProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
