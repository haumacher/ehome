/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.asRoot;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.util.model.ModelService;

/**
 * Probe of the ordered building structure (haumacher/ehome #71): drops the tree can produce that
 * the order test does not make - a part dropped before itself, a selection in the reverse of the
 * tree's order, a selection holding the part it is dropped before.
 */
@SuppressWarnings("javadoc")
public class TestBuildingOrderProbe extends BasicTestCase {

	public void testOddDrops() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestDetails.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject project = projects.get(0);
			TLObject floor = TestBuildingOrder.list(TestBuildingOrder.list(project, "buildings").get(0), "parts").get(0);
			List<TLObject> parts = TestBuildingOrder.list(floor, "parts");
			TLObject living = parts.get(0);
			TLObject kitchen = parts.get(1);
			TLObject board = parts.get(2);
			QueryExecutor veto = TestBuildingOrder.script(TestBuildingOrder.dropAttribute("refuse-if"), "objects",
				"parent", "before");
			QueryExecutor move = TestBuildingOrder.script(TestBuildingOrder.moveScript(), "objects", "parent", "before");

			// Before itself: nothing changes (or the drop is refused), the part is never lost.
			Object self = asRoot(() -> veto.execute(List.of(kitchen), floor, kitchen));
			if (self == null) {
				TestBuildingOrder.drop(move, List.of(kitchen), floor, kitchen);
			}
			assertEquals(List.of(living, kitchen, board), TestBuildingOrder.list(floor, "parts"));

			// A selection in reverse order, before the first part: the parts keep the tree's order.
			TestBuildingOrder.drop(move, List.of(board, kitchen), floor, living);
			assertEquals(List.of(kitchen, board, living), TestBuildingOrder.list(floor, "parts"));

			// A selection that holds the part it is dropped before.
			Object reason = asRoot(() -> veto.execute(List.of(living, kitchen), floor, kitchen));
			if (reason == null) {
				TestBuildingOrder.drop(move, List.of(living, kitchen), floor, kitchen);
			}
			List<TLObject> after = TestBuildingOrder.list(floor, "parts");
			assertEquals(3, after.size());
			assertTrue(after.containsAll(List.of(living, kitchen, board)));
			assertEquals(after.stream().map(p -> (String) p.tValueByName("name")).toList().subList(1, 3),
				TestBuildingOrder.locations(project).subList(3, 5));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBuildingOrderProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
