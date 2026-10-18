/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.ProgramLanguages;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Probe of the runtime image (haumacher/ehome #39) composed with the ETS 6 import: the ETS 4 and the
 * ETS 6 export of the KNX test system give the same datapoints, the image is valid, and building it
 * twice gives the same bytes. The function proposal (#43) gives both the same functions in kind and
 * role bindings. Their labels (#51) are maps of the languages of the device's program to its texts:
 * where both files have a language, the labels are the same; the push-button sensor's program of the
 * ETS 6 file is German only, so its functions have no English label (not the German text standing in
 * for English).
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

			List<String> functions4 = functions(ets4.get(0));
			assertTrue(functions4.toString(), functions4.size() >= 10);
			assertEquals(functions4, functions(ets6.get(0)));
			RuntimeImage functions6 = RuntimeImage.build(ets6.get(0), 4, NOW);
			ImageReport withFunctions = functions6.validate();
			assertTrue(withFunctions.toString(), withFunctions.isValid());

			Map<String, Map<?, ?>> labels4 = labels(RuntimeImage.build(ets4.get(0), 4, NOW));
			Map<String, Map<?, ?>> labels6 = labels(functions6);
			assertEquals(labels4.keySet(), labels6.keySet());
			int germanOnly = 0;
			for (Map.Entry<String, Map<?, ?>> entry : labels6.entrySet()) {
				Map<?, ?> label4 = labels4.get(entry.getKey());
				Map<?, ?> label6 = entry.getValue();
				assertTrue(entry.getKey(), label6.containsKey("de"));
				for (Object language : label6.keySet()) {
					if (label4.containsKey(language)) {
						assertEquals(entry.getKey() + " " + language, label4.get(language), label6.get(language));
					}
				}
				if (!label6.containsKey("en")) {
					germanOnly++;
				}
			}
			assertTrue(labels6.toString(), germanOnly > 0);
			for (int n = 0; n < functions6.getFunctionSources().size(); n++) {
				TLObject function = functions6.getFunctionSources().get(n);
				Program program = DeviceConfiguration.program((TLObject) function.tValueByName("device"));
				Set<String> languages = ProgramLanguages.languages(program);
				Map<?, ?> label = (Map<?, ?>) ((Map<?, ?>) ((List<?>) functions6.getJson().get("functions")).get(n)).get("label");
				assertEquals(String.valueOf(label), languages, new TreeSet<>(label.keySet().stream().map(Object::toString).toList()));
			}
		} finally {
			SharedData.deleteProjects(ets4);
			SharedData.deleteProjects(ets6);
		}
	}

	/**
	 * The labels of the functions of the image, by their kind and role bindings.
	 */
	private static Map<String, Map<?, ?>> labels(RuntimeImage image) {
		Map<String, Map<?, ?>> result = new TreeMap<>();
		List<?> functions = (List<?>) image.getJson().get("functions");
		for (int n = 0; n < functions.size(); n++) {
			Object label = ((Map<?, ?>) functions.get(n)).get("label");
			assertTrue(String.valueOf(label), label instanceof Map<?, ?>);
			assertNull(result.put(key(image.getFunctionSources().get(n)), (Map<?, ?>) label));
		}
		return result;
	}

	private static String key(TLObject function) {
		String kind = FunctionProposal.kind(function);
		StringBuilder text = new StringBuilder(kind);
		for (String role : FunctionKinds.roles(kind)) {
			Object address = function.tValueByName(role);
			if (address != null) {
				text.append(' ').append(role).append('=').append(((TLObject) address).tValueByName("name"));
			}
		}
		return text.toString();
	}

	private static List<String> functions(TLObject project) {
		TestFunctionProposal.apply(project);
		List<String> result = new ArrayList<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			result.add(key(function));
		}
		result.sort(null);
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuntimeImageProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
