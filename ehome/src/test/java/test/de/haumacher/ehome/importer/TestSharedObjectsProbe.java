/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.importer.TestEtsImport.withoutMasterData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

/**
 * Probe of the program parts made known on demand and the set additions stored at the end of an
 * import: imports alternating between two projects sharing the push-button program leave every
 * shared object once under its ETS ID, every element of a program's composite sets stored, and a
 * repeated import reports the same as the one before.
 */
@SuppressWarnings("javadoc")
public class TestSharedObjectsProbe extends BasicTestCase {

	private static final List<String> SHARED_MODULES = List.of("ehome.datapoint", "ehome.catalog", "ehome.operational");

	public void testAlternatingImports() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importSample("src/test/fixtures/TestDetails.knxproj");
			List<String> second = messages(importSample("src/test/fixtures/TestDetails.knxproj"));
			assertShared();
			importSample("samples/Test.knxproj");
			assertShared();
			List<String> third = messages(importSample("src/test/fixtures/TestDetails.knxproj"));
			assertEquals(second, third);
			assertShared();
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private static List<String> messages(List<BufferingI18NLog.Entry> entries) {
		List<String> result = new ArrayList<>();
		for (BufferingI18NLog.Entry entry : withoutMasterData(entries)) {
			result.add(entry.toString());
		}
		return result;
	}

	/**
	 * Every shared object exists once under its ETS ID, and the composite sets of every program hold
	 * exactly the objects contained in the program.
	 */
	private static void assertShared() {
		Map<TLObject, Integer> contained = new HashMap<>();
		for (TLModule module : ModelService.getApplicationModel().getModules()) {
			boolean shared = SHARED_MODULES.contains(module.getName());
			for (TLClass type : module.getClasses()) {
				if (type.isAbstract()) {
					continue;
				}
				boolean identified = shared && type.getPart("extId") != null;
				Map<String, TLObject> byId = new HashMap<>();
				for (TLObject obj : instances(TLModelUtil.qualifiedName(type))) {
					String id = identified ? (String) obj.tValueByName("extId") : null;
					if (id != null) {
						TLObject clash = byId.put(id, obj);
						assertNull("Shared object " + id + " of " + type + " twice.", clash);
					}
					TLObject container = obj.tContainer();
					if (container != null && "Program".equals(container.tType().getName())) {
						contained.merge(container, 1, Integer::sum);
					}
				}
			}
		}
		for (TLObject program : instances("ehome.operational:Program")) {
			int inSets = 0;
			for (TLStructuredTypePart part : program.tType().getAllParts()) {
				if (part instanceof TLReference reference && reference.isComposite() && !reference.isBackwards()
					&& !reference.isDerived()) {
					Object value = program.tValue(reference);
					inSets += value instanceof Collection<?> c ? c.size() : value == null ? 0 : 1;
				}
			}
			assertEquals("Parts of " + program.tValueByName("extId"), contained.getOrDefault(program, 0).intValue(),
				inSets);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestSharedObjectsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
