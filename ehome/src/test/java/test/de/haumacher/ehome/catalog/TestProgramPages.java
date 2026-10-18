/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.catalog;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ProgramPages;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;

/**
 * Test of {@link ProgramPages} (haumacher/ehome #20): the parameters and communication objects of
 * the Tastsensor of <code>samples/Test.knxproj</code> grouped by the page they are placed on, in the
 * order of the structure, the ones placed nowhere last, and the filter.
 */
public class TestProgramPages extends BasicTestCase {

	private static final String PROGRAM = "M-0008_A-10D7-11-A0E5-O000A";

	private static final String GENERIC = "0: Generic › ";

	public void testTastsensor() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> projectsBefore = new ArrayList<>(instances("ehome.project:Project"));
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		// The page titles are derived attributes, read with the rights of the reading user.
		context.setPerson(PersonManager.getManager().getRoot());
		try {
			importSample("samples/Test.knxproj");
			Program program = (Program) instances("ehome.operational:Program").stream()
				.filter(p -> PROGRAM.equals(p.tValueByName("extId"))).findFirst().get();
			checkPages(program);

			// A new parameter is placed nowhere: it is listed last. It is not committed, so that it takes
			// no number of the program's sequence (the tests share one database).
			try (Transaction tx = kb.beginTransaction()) {
				Parameter added = EhomeOperationalFactory.getInstance().createParameter();
				added.setName("Probe_Helligkeit (Testdaten)");
				added.setType(program.getParameters().iterator().next().getType());
				program.addParameter(added);
				checkUnplaced(program, added);
				tx.rollback();
			}
		} finally {
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
			try (Transaction tx = kb.beginTransaction()) {
				for (TLObject project : instances("ehome.project:Project")) {
					if (!projectsBefore.contains(project)) {
						project.tDelete();
					}
				}
				tx.commit();
			}
		}
	}

	private void checkPages(Program program) {
		ProgramPages pages = new ProgramPages(program);
		List<Map<String, Object>> rows = pages.parameterRows("", false);

		// Every parameter of the Tastsensor is placed.
		assertEquals(0, pages.getUnplacedParameters().size());
		assertEquals(0, pages.getUnplacedPorts().size());
		Set<Object> listed = new LinkedHashSet<>();
		for (Map<String, Object> row : rows) {
			listed.add(row.get(ProgramPages.DEFINITION));
		}
		assertEquals(program.getParameters().size(), listed.size());

		// The groups in the order of the structure, each one contiguous run of rows, the texts of
		// sub-blocks without the spaces ETS indents them with.
		List<String> groups = groups(rows);
		assertEquals(List.of(GENERIC + "Allgemein", GENERIC + "Bedienkonzept", GENERIC + "Sperren",
			GENERIC + "Alarmmeldungen", GENERIC + "Wippe 1 (Tasten 1/2)", GENERIC + "W1 - Status-LED",
			GENERIC + "Taste 1", GENERIC + "T1 - Status-LED", GENERIC + "T2 - Status-LED", GENERIC + "Taste 2"),
			groups.subList(0, 10));
		assertEquals(GENERIC + "Szenen", groups.get(groups.size() - 1));

		// The first group with its parameters in the order of the structure; the title parameter of
		// the block first.
		List<Map<String, Object>> first = rows.stream()
			.filter(r -> (GENERIC + "Allgemein").equals(r.get(ProgramPages.PAGE))).toList();
		assertEquals(33, first.size());
		assertEquals(List.of("Allgemein", "Hardware", "Müll", "Müll", "Downloaderkennung", "Applikationstyp"),
			first.subList(0, 6).stream().map(r -> ((Parameter) r.get(ProgramPages.DEFINITION)).getName()).toList());
		for (Map<String, Object> row : first) {
			ParameterView placement = (ParameterView) row.get(ProgramPages.PLACEMENT);
			assertSame(row.get(ProgramPages.DEFINITION), placement.getParameter());
		}

		// A parameter placed on several pages is listed on each, with the number of its placements.
		List<Map<String, Object>> brightness = rows.stream()
			.filter(r -> "Helligkeitswert_T1".equals(((Parameter) r.get(ProgramPages.DEFINITION)).getName()))
			.toList();
		assertEquals(List.of(GENERIC + "Wippe 1 (Tasten 1/2)", GENERIC + "Taste 1"),
			brightness.stream().map(r -> r.get(ProgramPages.PAGE)).toList());
		Parameter brightnessParameter = (Parameter) brightness.get(0).get(ProgramPages.DEFINITION);
		assertEquals(brightnessParameter.getViews().size(), ((Integer) brightness.get(0).get(ProgramPages.PLACEMENTS)).intValue());
		assertEquals(brightness.get(0), pages.rowOf(brightnessParameter));

		// The filter keeps the groups containing a hit, in the order of the structure.
		List<Map<String, Object>> hits = pages.parameterRows("helligkeit", false);
		assertEquals(List.of(GENERIC + "Allgemein", GENERIC + "Wippe 1 (Tasten 1/2)", GENERIC + "Taste 1",
			GENERIC + "Taste 2", GENERIC + "Wippe 2 (Tasten 3/4)", GENERIC + "Taste 3", GENERIC + "Taste 4",
			GENERIC + "Wippe 3 (Tasten 5/6)", GENERIC + "Taste 5", GENERIC + "Taste 6",
			GENERIC + "Sperren - Sperrfunktion 1", GENERIC + "Sperren - Sperrfunktion 2"), groups(hits));
		for (Map<String, Object> hit : hits) {
			Parameter parameter = (Parameter) hit.get(ProgramPages.DEFINITION);
			ParameterView placement = (ParameterView) hit.get(ProgramPages.PLACEMENT);
			String texts = (parameter.getName() + " " + parameter.tValueByName("description") + " "
				+ placement.tValueByName("description") + " " + parameter.getType().getName()).toLowerCase(Locale.ROOT);
			assertTrue(texts, texts.contains("helligkeit"));
		}
		assertEquals(List.of(), pages.parameterRows("Gibt es nicht", false));

		// The communication objects the same way.
		List<Map<String, Object>> ports = pages.portRows("", false);
		Set<Object> listedPorts = new LinkedHashSet<>();
		for (Map<String, Object> row : ports) {
			listedPorts.add(row.get(ProgramPages.DEFINITION));
		}
		assertEquals(program.getPorts().size(), listedPorts.size());
		assertEquals(GENERIC + "Allgemein", groups(ports).get(0));
		assertTrue(pages.portRows("Taste 1", false).stream()
			.anyMatch(r -> "Taste 1".equals(((Port) r.get(ProgramPages.DEFINITION)).getName())));
	}

	private void checkUnplaced(Program program, Parameter added) {
		ProgramPages pages = new ProgramPages(program);
		assertEquals(List.of(added), pages.getUnplacedParameters());
		List<Map<String, Object>> rows = pages.parameterRows(null, false);
		Map<String, Object> last = rows.get(rows.size() - 1);
		assertEquals(ProgramPages.notPlaced(), last.get(ProgramPages.PAGE));
		assertSame(added, last.get(ProgramPages.DEFINITION));
		assertNull(last.get(ProgramPages.PLACEMENT));
		assertEquals(Integer.valueOf(0), last.get(ProgramPages.PLACEMENTS));
		assertEquals(last, pages.rowOf(added));

		// Only the parameters placed nowhere, also with the filter.
		assertEquals(List.of(last), pages.parameterRows("", true));
		assertEquals(List.of(last), pages.parameterRows("Probe", false));
		assertEquals(ProgramPages.notPlaced(), groups(pages.parameterRows("helligkeit", false)).get(12));
		assertEquals(List.of(), pages.parameterRows("Allgemein", true));
	}

	/**
	 * The groups of the rows in the order they appear; fails if the rows of a group are not one
	 * contiguous run, since a table grouped by the page keeps the order of the first rows.
	 */
	private static List<String> groups(List<Map<String, Object>> rows) {
		List<String> result = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			String page = (String) row.get(ProgramPages.PAGE);
			if (result.isEmpty() || !result.get(result.size() - 1).equals(page)) {
				assertFalse("Group not contiguous: " + page, result.contains(page));
				result.add(page);
			}
		}
		return result;
	}

	/**
	 * The suite.
	 */
	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProgramPages.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
