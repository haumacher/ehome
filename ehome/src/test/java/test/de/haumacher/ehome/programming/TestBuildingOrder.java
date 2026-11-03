/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.asRoot;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.roundtrip.TestRoundTrip;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.BuildingDevices;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The building structure is ordered (haumacher/ehome #71), on the fixture
 * <code>TestDetails.knxproj</code>: the building "Haus" with the floor "Erdgeschoss", which holds the
 * rooms "Wohnzimmer" (the highest ID) and "Küche" and the distribution board "Verteiler", in this
 * order, which is neither that of their names nor that of their IDs.
 *
 * <p>
 * The import takes the order of the file; the user moves a part with the drop of the building tree
 * (<code>projects/buildings.view.xml</code>, its <code>refuse-if</code> and its script); the runtime
 * image lists the locations depth-first in the model's order, the <code>.knxproj</code> export
 * writes it, and the import of the export reads it back. An import of the same file again creates a
 * project of its own (there is no merge into an existing project, see
 * <code>TestRoundTrip#checkReimport</code>), so the order the user gave the first project stays.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBuildingOrder extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestDetails.knxproj";

	private static final String BUILDINGS_VIEW = "src/main/webapp/WEB-INF/views/projects/buildings.view.xml";

	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

	public void testOrder() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			check(projects.get(0), projects);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private void check(TLObject project, List<TLObject> projects) throws Exception {
		// The import takes the order of the file.
		TLObject house = single(list(project, "buildings"));
		TLObject floor = single(list(house, "parts"));
		assertEquals(List.of("Wohnzimmer", "Küche", "Verteiler"), names(list(floor, "parts")));
		TLObject living = list(floor, "parts").get(0);
		TLObject kitchen = list(floor, "parts").get(1);
		TLObject board = list(floor, "parts").get(2);
		assertEquals(List.of("Haus", "Erdgeschoss", "Wohnzimmer", "Küche", "Verteiler"), locations(project));
		assertEquals(List.of("Haus", "Erdgeschoss", "Wohnzimmer", "Küche", "Verteiler"), exported(project));

		QueryExecutor veto = script(dropAttribute("refuse-if"), "objects", "parent", "before");
		QueryExecutor move = script(moveScript(), "objects", "parent", "before");

		// Move the distribution board up before the living room: the image follows the new order.
		assertNull(asRoot(() -> veto.execute(List.of(board), floor, living)));
		drop(move, List.of(board), floor, living);
		assertEquals(List.of(board, living, kitchen), list(floor, "parts"));
		assertEquals(List.of("Haus", "Erdgeschoss", "Verteiler", "Wohnzimmer", "Küche"), locations(project));

		// Down again, to the end (no part to insert before): the kitchen now comes first, and the two
		// keep their order in the tree, not that of the selection.
		drop(move, List.of(living, board), floor, null);
		assertEquals(List.of(kitchen, board, living), list(floor, "parts"));
		drop(move, List.of(living), floor, board);
		assertEquals(List.of(kitchen, living, board), list(floor, "parts"));
		// A place among the devices of the floor (after its parts) is the end as well.
		TLObject device = BuildingDevices.devices(kitchen).get(0);
		drop(move, List.of(kitchen), floor, device);
		assertEquals(List.of(living, board, kitchen), list(floor, "parts"));
		assertEquals(List.of("Haus", "Erdgeschoss", "Wohnzimmer", "Verteiler", "Küche"), locations(project));

		// Places that cannot hold the part are refused with a reason, and the move does not happen.
		assertNotNull(asRoot(() -> veto.execute(List.of(kitchen), board, null)));
		assertNotNull(asRoot(() -> veto.execute(List.of(floor), kitchen, null)));
		assertNotNull(asRoot(() -> veto.execute(List.of(floor), floor, null)));
		assertNotNull(asRoot(() -> veto.execute(List.of(kitchen), project, null)));
		assertNotNull(asRoot(() -> veto.execute(List.of(device), floor, null)));
		assertNotNull(asRoot(() -> veto.execute(List.of(), floor, null)));
		try (Transaction tx = kb().beginTransaction()) {
			BuildingDevices.move(List.of(house), floor, null);
			fail("A part moved into its own sub-part must be refused.");
		} catch (TopLogicException ex) {
			assertNotNull(ex.getErrorKey());
		}
		assertEquals(List.of(living, board, kitchen), list(floor, "parts"));

		// Into another part: a second floor created by the user comes last, and takes the kitchen.
		TLObject upper;
		try (Transaction tx = kb().beginTransaction()) {
			upper = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.building:Floor"));
			upper.tUpdateByName("name", "Obergeschoss");
			house.tAdd(house.tType().getPart("parts"), upper);
			tx.commit();
		}
		assertEquals(List.of(floor, upper), list(house, "parts"));
		drop(move, List.of(kitchen), upper, null);
		assertEquals(List.of(living, board), list(floor, "parts"));
		assertEquals(List.of(kitchen), list(upper, "parts"));
		assertSame(upper, kitchen.tContainer());
		assertSame(kitchen, device.tValueByName("location"));
		// The floor of the user before the imported one.
		drop(move, List.of(upper), house, floor);
		assertEquals(List.of(upper, floor), list(house, "parts"));
		// Parts of different floors, selected against the tree's order, keep the tree's order.
		drop(move, List.of(board, kitchen), floor, living);
		assertEquals(List.of(kitchen, board, living), list(floor, "parts"));
		assertEquals(List.of(), list(upper, "parts"));
		drop(move, List.of(kitchen), upper, null);
		drop(move, List.of(board), floor, null);
		assertEquals(List.of(living, board), list(floor, "parts"));
		List<String> order = List.of("Haus", "Obergeschoss", "Küche", "Erdgeschoss", "Wohnzimmer", "Verteiler");
		assertEquals(order, locations(project));

		// The export writes the order, and its import reads it back.
		assertEquals(order, exported(project));
		TLObject reimported = TestRoundTrip.importProject(TestRoundTrip.export(project), projects);
		assertEquals(order, locations(reimported));

		// An import of the file again is a project of its own, in the order of the file; the order
		// the user gave the first one stays.
		List<TLObject> known = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> again = SharedData.newProjects(known);
		projects.addAll(again);
		assertEquals(1, again.size());
		assertEquals(List.of("Haus", "Erdgeschoss", "Wohnzimmer", "Küche", "Verteiler"), locations(again.get(0)));
		assertEquals(order, locations(project));
	}

	static void drop(QueryExecutor move, List<TLObject> parts, TLObject parent, TLObject before) {
		try (Transaction tx = kb().beginTransaction()) {
			asRoot(() -> move.execute(parts, parent, before));
			tx.commit();
		}
	}

	/**
	 * The labels of the locations of the runtime image of the project, in their order.
	 */
	static List<String> locations(TLObject project) {
		RuntimeImage image = asRoot(() -> RuntimeImage.build(project, 1, NOW));
		List<String> result = new ArrayList<>();
		for (Object location : (List<?>) image.getJson().get("locations")) {
			result.add((String) ((Map<?, ?>) location).get("label"));
		}
		return result;
	}

	/**
	 * The names of the building parts of the <code>.knxproj</code> export of the project, depth-first
	 * in the order of the file.
	 */
	private static List<String> exported(TLObject project) throws Exception {
		byte[] archive = TestRoundTrip.export(project);
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
				if (entry.getName().matches("P-[^/]+/0\\.xml")) {
					Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
						.parse(new ByteArrayInputStream(zip.readAllBytes()));
					NodeList parts = document.getElementsByTagName("BuildingPart");
					List<String> result = new ArrayList<>();
					for (int n = 0; n < parts.getLength(); n++) {
						result.add(((Element) parts.item(n)).getAttribute("Name"));
					}
					return result;
				}
			}
		}
		throw new AssertionError("The export has no installation.");
	}

	static List<String> names(List<TLObject> parts) {
		return parts.stream().map(p -> (String) p.tValueByName("name")).toList();
	}

	private static TLObject single(List<TLObject> values) {
		assertEquals(1, values.size());
		return values.get(0);
	}

	static List<TLObject> list(TLObject obj, String attribute) {
		List<TLObject> result = new ArrayList<>();
		for (Object value : (Collection<?>) obj.tValueByName(attribute)) {
			result.add((TLObject) value);
		}
		return result;
	}

	/**
	 * Compiles a script of a view, which uses its values as the given variables (in the order of the
	 * values on execution) without declaring them.
	 */
	static QueryExecutor script(String source, String... variables) throws Exception {
		return ViewScripts.compile(ExprFormat.INSTANCE.getValue("script", source), List.of(variables));
	}

	static String dropAttribute(String attribute) throws Exception {
		return ((Element) single(view().getElementsByTagName("drop"))).getAttribute(attribute);
	}

	/**
	 * The script of the drop of the building tree.
	 */
	static String moveScript() throws Exception {
		Element drop = (Element) single(view().getElementsByTagName("drop"));
		return ((Element) single(drop.getElementsByTagName("execute-script"))).getAttribute("function");
	}

	private static Object single(NodeList nodes) {
		assertEquals(1, nodes.getLength());
		return nodes.item(0);
	}

	private static Document view() throws Exception {
		assertTrue(Files.exists(Path.of(BUILDINGS_VIEW)));
		return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(BUILDINGS_VIEW));
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBuildingOrder.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
