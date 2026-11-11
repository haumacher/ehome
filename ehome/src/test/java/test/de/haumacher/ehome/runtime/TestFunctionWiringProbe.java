/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.FunctionProposal;

/**
 * Probe of the function proposal (haumacher/ehome #133, #134, #135) over all function fixtures in one
 * database: no function is placed in a distribution board, the names of a project's functions are
 * unique, the unused functions of one device share one device part of their name, and a name the user
 * gave an unused function survives the next run.
 */
@SuppressWarnings("javadoc")
public class TestFunctionWiringProbe extends BasicTestCase {

	private static final List<String> FIXTURES = List.of(
		"src/test/fixtures/TestFunctionKeys.knxproj",
		"src/test/fixtures/TestFunctionLinks.knxproj",
		"src/test/fixtures/TestFunctionRooms.knxproj",
		"src/test/fixtures/TestFunctionWiring.knxproj");

	public void testAllFixtures() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		for (String fixture : FIXTURES) {
			importSample(fixture);
		}
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(FIXTURES.size(), projects.size());
			for (TLObject project : projects) {
				run(project);
				String projectName = String.valueOf(project.tValueByName("name"));
				Set<String> names = new HashSet<>();
				Map<TLObject, String> devicePart = new HashMap<>();
				for (TLObject function : FunctionProposal.functions(project)) {
					String name = TestFunctionProposal.name(function);
					assertTrue(projectName + ": duplicate name " + name, names.add(name));
					Object room = function.tValueByName("room");
					if (room instanceof TLObject location) {
						assertFalse(projectName + ": " + name + " placed in a distribution board",
							TLModelUtil.qualifiedName(location.tType()).equals("ehome.building:DistributionBoard"));
					}
					if (Boolean.TRUE.equals(function.tValueByName("unused"))
						&& function.tValueByName("device") instanceof TLObject device) {
						int colon = name.lastIndexOf(": ");
						assertTrue(name, colon > 0);
						String part = name.substring(0, colon);
						String known = devicePart.putIfAbsent(device, part);
						assertTrue(projectName + ": device part of " + name + " differs from " + known,
							known == null || known.equals(part));
					}
				}

				// A name the user gave an unused function survives the next run.
				for (TLObject function : FunctionProposal.functions(project)) {
					if (Boolean.TRUE.equals(function.tValueByName("unused"))) {
						TestFunctionProposal.update(function, "name", ResKey.text("Mein Name"));
						run(project);
						assertEquals("Mein Name", TestFunctionProposal.name(function));
						break;
					}
				}
			}
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static void run(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			FunctionProposal.run(project);
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionWiringProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
