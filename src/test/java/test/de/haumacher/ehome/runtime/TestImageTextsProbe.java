/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Probe of the function labels (haumacher/ehome #51) with the demo project, whose programs have German
 * and English: every label of the image is a language map with non-empty texts of at most 63 bytes in
 * the application languages, and at least one function has both languages.
 */
@SuppressWarnings("javadoc")
public class TestImageTextsProbe extends BasicTestCase {

	public void testDemo() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/EHomeDemo.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TestFunctionProposal.apply(projects.get(0));
			RuntimeImage image = RuntimeImage.build(projects.get(0), 1, Instant.EPOCH);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			List<?> functions = (List<?>) image.getJson().get("functions");
			assertFalse(functions.isEmpty());
			boolean both = false;
			for (Object function : functions) {
				Object label = ((Map<?, ?>) function).get("label");
				assertTrue(String.valueOf(label), label instanceof Map);
				Map<?, ?> texts = (Map<?, ?>) label;
				assertTrue(texts.toString(), Set.of("de", "en").containsAll(texts.keySet()));
				assertFalse(texts.toString(), texts.isEmpty());
				for (Object text : texts.values()) {
					int bytes = text.toString().getBytes(StandardCharsets.UTF_8).length;
					assertTrue(texts.toString(), bytes > 0 && bytes <= 63);
				}
				both |= texts.size() == 2;
			}
			assertTrue(functions.toString(), both);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestImageTextsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
