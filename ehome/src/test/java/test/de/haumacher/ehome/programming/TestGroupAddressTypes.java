/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.ProjectIndex;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Group addresses without a datapoint type (haumacher/ehome #46), with the KNX test system
 * (<code>samples/KNX-Testsystem.knxproj</code>): <code>0/0/28 Raumtemperatur</code> has no type of
 * its own, the Tastsensor's 2-byte object connected to it names none, and 2 bytes do not decide the
 * main type.
 *
 * <p>
 * The address is reported as untyped, the objects suggest the 2-byte types (9.001 among them), the
 * type is set with the scripts of the dialog (<code>projects/set-datapoint-type.view.xml</code>),
 * after which the address is typed everywhere (the runtime image, the group monitor, the derived
 * attribute of the form) and importing the project file again leaves the user's type alone.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressTypes extends BasicTestCase {

	private static final String DIALOG = "src/main/webapp/WEB-INF/views/projects/set-datapoint-type.view.xml";

	private static final String ADDRESSES = "src/main/webapp/WEB-INF/views/projects/addresses.view.xml";

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	public void testUntypedAddress() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			TLObject temperature = address(project, "0/0/28");
			assertEquals("Raumtemperatur", temperature.tValueByName("name"));

			// Reported as untyped.
			assertFalse(GroupAddressTypes.hasType(temperature));
			assertNull(GroupAddressTypes.effective(temperature));
			assertNull(derived(temperature, "effectiveDatapointType"));
			assertEquals("", GroupAddressTypes.label(temperature));
			List<TLObject> untyped = untyped(project);
			assertEquals(List.of(temperature), untyped);
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertEquals(1, image.getNotes().size());
			assertTrue(image.getNotes().get(0), image.getNotes().get(0).contains("0/0/28 Raumtemperatur"));
			assertFalse(image.getDatapointSources().contains(temperature));

			// A key's address is typed by the size of its 1-bit object: the main type 1.
			TLObject key = address(project, "0/0/1");
			assertEquals(Dpt.mainType(1), GroupAddressTypes.effective(key));
			assertEquals("1.*", GroupAddressTypes.label(key));
			assertEquals(1, derivedNumber(key).intValue());

			// The objects (2 bytes) suggest the 2-byte types, the dialog offers them.
			assertEquals(Integer.valueOf(16), GroupAddressTypes.objectSize(temperature));
			TLObject celsius = subtype("DPST-9-1");
			List<TLObject> suggestions = GroupAddressTypes.suggestions(temperature);
			assertTrue(suggestions.contains(celsius));
			assertFalse(suggestions.contains(subtype("DPST-1-1")));
			assertFalse(suggestions.contains(subtype("DPST-5-1")));
			QueryExecutor options = script(DIALOG, "options");
			assertEquals(suggestions, new ArrayList<>((Collection<?>) TestDeviceChanges.asRoot(Locale.ENGLISH,
				() -> options.execute(temperature, null))));
			List<?> all = (List<?>) TestDeviceChanges.asRoot(Locale.ENGLISH,
				() -> options.execute(temperature, Boolean.TRUE));
			assertTrue(all.size() > suggestions.size());
			assertTrue(all.contains(subtype("DPST-1-1")));

			// A type of another size is refused with the reason.
			assertNotNull(GroupAddressTypes.veto(temperature, subtype("DPST-1-1")));
			assertNull(GroupAddressTypes.veto(temperature, celsius));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				GroupAddressTypes.setType(temperature, subtype("DPST-1-1"));
				fail("A 1-bit type for 2-byte objects must be refused.");
			} catch (TopLogicException ex) {
				// Expected.
			}
			assertFalse(GroupAddressTypes.hasType(temperature));

			// Set by the script of the dialog's "Set" button.
			QueryExecutor set = script(DIALOG, "execute-script", "groupAddressSetType($a, $t)");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TestDeviceChanges.asRoot(Locale.ENGLISH, () -> set.execute(temperature, celsius));
				tx.commit();
			}
			assertEquals(List.of(celsius), temperature.tValueByName("datapointTypes"));
			assertTrue(GroupAddressTypes.hasType(temperature));
			assertEquals(new Dpt(9, 1), GroupAddressTypes.effective(temperature));
			assertEquals(celsius, derived(temperature, "effectiveDatapointType"));
			assertEquals(List.of(), untyped(project));
			assertEquals(new Dpt(9, 1), ProjectIndex.of(project).address(value(temperature)).dpt());
			RuntimeImage typed = RuntimeImage.build(project, 2, NOW);
			assertEquals(List.of(), typed.getNotes());
			assertTrue(typed.getDatapointSources().contains(temperature));
			Map<?, ?> datapoint = datapoint(typed, "0/0/28");
			assertEquals("9.001", ((Map<?, ?>) datapoint.get("knx")).get("dpt"));

			// Importing the project file again creates a project of its own: the user's type stays.
			List<TLObject> beforeReimport = instances("ehome.project:Project");
			importSample("samples/KNX-Testsystem.knxproj");
			List<TLObject> reimported = SharedData.newProjects(beforeReimport);
			projects.addAll(reimported);
			assertEquals(1, reimported.size());
			assertTrue(temperature.tValid());
			assertEquals(List.of(celsius), temperature.tValueByName("datapointTypes"));
			assertTrue(GroupAddressTypes.hasType(temperature));
			assertTrue(celsius.tValid());
			// The new project has the type the file gives: none.
			assertFalse(GroupAddressTypes.hasType(address(reimported.get(0), "0/0/28")));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static Number derivedNumber(TLObject address) {
		TLObject type = (TLObject) derived(address, "effectiveDatapointType");
		return (Number) type.tValueByName("number");
	}

	private static int value(TLObject address) {
		return ((GA) address.tValueByName("address")).getValue();
	}

	/**
	 * The untyped addresses of the project, as the tree of the group address view shows them with
	 * its switch "Only addresses without datapoint type" on.
	 */
	private static List<TLObject> untyped(TLObject project) throws Exception {
		QueryExecutor children = script(ADDRESSES, "children");
		List<TLObject> result = new ArrayList<>();
		collectUntyped(children, project, project, result);
		return result;
	}

	private static void collectUntyped(QueryExecutor children, TLObject project, TLObject node,
			List<TLObject> result) {
		for (Object child : (Collection<?>) TestDeviceChanges.asRoot(Locale.ENGLISH,
			() -> children.execute(project, Boolean.TRUE, node))) {
			TLObject part = (TLObject) child;
			if (part.tValueByName("address") instanceof GA) {
				assertFalse(GroupAddressTypes.hasType(part));
				result.add(part);
			} else {
				collectUntyped(children, project, part, result);
			}
		}
	}

	private static TLObject address(TLObject project, String text) {
		return SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
			.filter(a -> a.tValueByName("address") instanceof GA ga && ga.toString().equals(text))
			.findFirst().orElseThrow();
	}

	private static TLObject subtype(String extId) {
		return instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> extId.equals(t.tValueByName("extId"))).findFirst().orElseThrow();
	}

	private static Map<?, ?> datapoint(RuntimeImage image, String id) {
		for (Object entry : (List<?>) image.getJson().get("datapoints")) {
			if (id.equals(((Map<?, ?>) entry).get("id"))) {
				return (Map<?, ?>) entry;
			}
		}
		fail("No datapoint " + id);
		return null;
	}

	/**
	 * The script of the element with the given tag in the given view (its text, or its
	 * <code>function</code> attribute containing the given text).
	 */
	private static QueryExecutor script(String view, String tag, String... containing) throws Exception {
		NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(view))
			.getElementsByTagName(tag);
		for (int n = 0; n < nodes.getLength(); n++) {
			Element element = (Element) nodes.item(n);
			String text = element.hasAttribute("function") ? element.getAttribute("function") : element.getTextContent();
			if (containing.length == 0 || text.contains(containing[0])) {
				return QueryExecutor.compile(ExprFormat.INSTANCE.getValue(tag, text));
			}
		}
		fail("No script " + tag + " in " + view);
		return null;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupAddressTypes.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
