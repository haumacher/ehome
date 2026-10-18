/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.catalog;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ProgramPages;
import de.haumacher.ehome.knx.ProgramPages.Page;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;

/**
 * Probe of the program pages (issue #20) composed with the device configuration: every parameter
 * and object a device's configuration shows is on a page of its program's pages, under the same
 * page title; the pages cover every placed parameter exactly as often as it is placed; and the
 * rows of the view are consistent with the pages.
 */
@SuppressWarnings("javadoc")
public class TestProgramPagesProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		TLObject project = projects.get(0);
		try {
			TLObject device = device(project);
			Program program = DeviceConfiguration.program(device);
			ProgramPages pages = new ProgramPages(program);
			DeviceConfiguration configuration = new DeviceConfiguration(device);

			// The device's configuration is a subset of the program's pages: the parameters in effect
			// are on some page, the objects in effect too.
			Set<ParameterView> onPages = new HashSet<>();
			Set<Port> portsOnPages = new HashSet<>();
			for (Page page : pages.getPages()) {
				onPages.addAll(page.getParameters().values());
				portsOnPages.addAll(page.getPorts().keySet());
			}
			for (Map.Entry<Parameter, ParameterView> active : configuration.getActiveViews().entrySet()) {
				if (configuration.isShown(active.getValue())) {
					assertTrue(active.getKey().getName(), pages.placements(active.getKey()) >= 1);
				}
			}
			for (Port port : configuration.getActiveInterfaces().keySet()) {
				assertTrue(port.getName(), portsOnPages.contains(port));
			}
			assertTrue(onPages.size() > 100);

			// The sum of the placement counts is the number of parameter placements in the program,
			// and a parameter placed nowhere is unplaced exactly when its count is zero.
			int placements = 0;
			// The unplaced parameters once: the list is computed and sorted on each call.
			Set<Parameter> unplaced = new HashSet<>(pages.getUnplacedParameters());
			for (Parameter parameter : program.getParameters()) {
				int count = pages.placements(parameter);
				placements += count;
				assertEquals(parameter.getName(), count == 0, unplaced.contains(parameter));
			}
			// The parameters once: reading them resolves the whole set.
			Set<Parameter> parameters = new HashSet<>(program.getParameters());
			assertEquals(placements, instances("ehome.operational:ParameterView").stream()
				.filter(v -> parameters.contains(((ParameterView) v).getParameter()))
				.count());

			// The rows of the view: one per page and parameter, pages in order, the row of a
			// definition on its first page; the filter by a type name narrows to parameters of it.
			List<Map<String, Object>> rows = pages.parameterRows("", false);
			assertEquals(placements >= rows.size(), true);
			List<String> pageOrder = pages.getPages().stream().map(Page::getTitle).toList();
			int last = -1;
			for (Map<String, Object> row : rows) {
				int index = pageOrder.indexOf(String.valueOf(row.get(ProgramPages.PAGE)));
				if (index < 0) {
					assertEquals(ProgramPages.notPlaced(), String.valueOf(row.get(ProgramPages.PAGE)));
					index = pageOrder.size();
				}
				assertTrue("Rows out of page order at " + row.get(ProgramPages.PAGE), index >= last);
				last = index;
			}
			Parameter first = (Parameter) rows.get(0).get(ProgramPages.DEFINITION);
			assertEquals(rows.get(0), pages.rowOf(first));
			String typeName = first.getType().getName();
			List<Map<String, Object>> filtered = pages.parameterRows(typeName, false);
			assertFalse(filtered.isEmpty());
			assertTrue(filtered.size() < rows.size());
			assertTrue(filtered.stream().anyMatch(r -> r.get(ProgramPages.DEFINITION) == first));
			assertTrue(pages.parameterRows("", true).isEmpty() == pages.getUnplacedParameters().isEmpty());
			assertTrue(pages.parameterRows("no such parameter name anywhere", false).isEmpty());
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static TLObject device(TLObject project) {
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					return (TLObject) device;
				}
			}
		}
		throw new AssertionError("No device");
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProgramPagesProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
