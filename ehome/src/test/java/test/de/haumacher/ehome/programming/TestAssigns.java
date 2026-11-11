/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.importer.TestEtsImport;

import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Assign;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.I18NConstants;

/**
 * Assignments of the structure of a program (ETS: <code>Assign</code>) and switches with several
 * matching cases (haumacher/ehome #142), with the artificial fixture
 * <code>src/test/fixtures/TestAssigns.knxproj</code> (see <code>make-fixtures.py</code>).
 *
 * <p>
 * The key mode "Tasten" (P-2_R-1) decides on the pages "Taste 1" (cases "1 2") and "Taste 2" (case
 * "2") of one switch, which precedes the assignments in the order of the document. Under the default
 * device type the key mode is entered directly (default 2: both pages, objects 0..2); under the type
 * "4-fach" it takes the value of P-2_R-2 or the constant 0, under "1-fach" the value of P-2_R-3. The
 * memory of "Tasten" (0x159) in the download image holds the assigned value. A re-import adds no
 * assignment twice; a program imported without its assignments is completed by the next import.
 * </p>
 *
 * <p>
 * Runs in the database shared with the other tests and deletes its projects again; the program of the
 * fixture stays.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAssigns extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestAssigns.knxproj";

	private static final String P = "M-0008_A-FFE1-01-0000_";

	private static final int GERAET = 0x158;

	private static final int TASTEN = 0x159;

	public void testAssigns() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		List<TLObject> projects = new ArrayList<>();
		try {
			List<String> warnings = importFixture();
			projects.addAll(SharedData.newProjects(before));
			assertEquals(1, projects.size());
			assertTrue(warnings.toString(), warnings.stream().noneMatch(w -> w.contains("Assign")));
			assertEquals(List.of(
				"Tasten 1/2 := Tasten 1/2 (P-2_R-3)",
				"Tasten 1/2 := Tasten 1/2 (P-2_R-2)",
				"Tasten 1/2 := 0"), assigns(program()));

			// Default device type: the key mode is entered directly; its default 2 takes both cases.
			TLObject standard = device(projects, 1);
			DeviceConfiguration configuration = new DeviceConfiguration(standard);
			ParameterView target = view(P + "P-2_R-1");
			assertEquals("2", configuration.value(target));
			assertFalse(configuration.isAssigned(target));
			assertNull(DeviceSettings.editVeto(standard, target));
			assertEquals(Set.of("PB-1", "PB-2", "PB-3"), pages(configuration, standard));
			assertEquals(Set.of(0, 1, 2), objects(configuration));
			DownloadImage image = DownloadImage.compute(standard);
			assertEquals(Integer.valueOf(0), image.byteAt(GERAET));
			assertEquals(Integer.valueOf(2), image.byteAt(TASTEN));

			// Type "4-fach" with the source "Zwei-Tasten": the target takes 1, the page "Taste 2" and
			// its objects are out of effect (the switch on the target precedes the assignment).
			TLObject fourfold = device(projects, 2);
			ParameterView source = view(P + "P-2_R-2");
			configuration = new DeviceConfiguration(fourfold);
			assertEquals("1", configuration.value(target));
			assertTrue(configuration.isAssigned(target));
			assertSame(source, configuration.getAssignmentSource(target));
			assertEquals(1, entryValue(configuration.effectiveHolder(target)));
			assertEquals(Set.of("PB-1", "PB-3"), pages(configuration, fourfold));
			assertEquals(Set.of(0), objects(configuration));
			image = DownloadImage.compute(fourfold);
			assertEquals(Integer.valueOf(4), image.byteAt(GERAET));
			assertEquals(Integer.valueOf(1), image.byteAt(TASTEN));

			// The source changes the target, the pages and objects.
			set(fourfold, source, Long.valueOf(2));
			configuration = new DeviceConfiguration(fourfold);
			assertEquals("2", configuration.value(target));
			assertEquals(Set.of("PB-1", "PB-2", "PB-3"), pages(configuration, fourfold));
			assertEquals(Set.of(0, 1, 2), objects(configuration));
			assertEquals(Integer.valueOf(2), DownloadImage.compute(fourfold).byteAt(TASTEN));
			set(fourfold, source, Long.valueOf(0));
			configuration = new DeviceConfiguration(fourfold);
			assertEquals("0", configuration.value(target));
			assertEquals(Set.of("PB-3"), pages(configuration, fourfold));
			assertEquals(Set.of(), objects(configuration));
			assertEquals(Integer.valueOf(0), DownloadImage.compute(fourfold).byteAt(TASTEN));

			// A constant: the source is no longer in effect, the memory of "Tasten" holds the constant.
			set(fourfold, source, Long.valueOf(2));
			set(fourfold, view(P + "P-3_R-1"), Long.valueOf(1));
			configuration = new DeviceConfiguration(fourfold);
			assertEquals("0", configuration.value(target));
			assertNull(configuration.getAssignmentSource(target));
			assertFalse(configuration.getActiveViews().containsValue(source));
			assertTrue(configuration.effectiveHolder(target) instanceof Assign);
			assertEquals(Set.of("PB-3"), pages(configuration, fourfold));
			assertEquals(Integer.valueOf(0), DownloadImage.compute(fourfold).byteAt(TASTEN));

			// Type "1-fach": the target takes the default 1 of P-2_R-3; a setting of the target itself
			// does not count while it is assigned.
			set(standard, target, Long.valueOf(0));
			assertEquals(Set.of("PB-3"), pages(new DeviceConfiguration(standard), standard));
			set(standard, view(P + "P-1_R-1"), Long.valueOf(1));
			configuration = new DeviceConfiguration(standard);
			assertEquals("1", configuration.value(target));
			assertEquals(Set.of("PB-1", "PB-3"), pages(configuration, standard));
			assertEquals(Integer.valueOf(1), DownloadImage.compute(standard).byteAt(TASTEN));
			// The target is shown there once more: with the assigned value, read only, naming the source.
			assertTrue(configuration.isShown(target));
			assertEquals(1, entryValue(DeviceSettings.effectiveHolder(standard, target)));
			assertEquals(1, entryValue(DeviceSettings.effectiveValue(standard, target)));
			assertEquals(0, entryValue(DeviceSettings.value(standard, target)));
			ResKey veto = DeviceSettings.editVeto(standard, target);
			assertNotNull(veto);
			assertEquals(I18NConstants.ERROR_ASSIGNED_FROM__PARAMETER_SOURCE.fill(target, view(P + "P-2_R-3")), veto);
			try {
				set(standard, target, Long.valueOf(2), true);
				fail("An assigned value cannot be set.");
			} catch (TopLogicException ex) {
				assertEquals(veto, ex.getErrorKey());
			}

			// A re-import adds nothing to the known program.
			int contents = structureSize(program());
			List<String> again = importFixture();
			projects.addAll(SharedData.newProjects(before).stream().filter(p -> !projects.contains(p)).toList());
			assertTrue(again.toString(), again.stream().noneMatch(w -> w.contains("Assign")));
			assertEquals(contents, structureSize(program()));
			assertEquals(3, assigns(program()).size());

			// A program an earlier import left without its assignments is completed at their places.
			List<String> structure = structure(program());
			try (Transaction tx = kb().beginTransaction()) {
				for (Assign assign : allAssigns(program())) {
					assign.tDelete();
				}
				program().tUpdateByName("assignsRead", Boolean.FALSE);
				tx.commit();
			}
			assertEquals(0, assigns(program()).size());
			importFixture();
			List<TLObject> completed =
				SharedData.newProjects(before).stream().filter(p -> !projects.contains(p)).toList();
			projects.addAll(completed);
			assertEquals(Boolean.TRUE, program().tValueByName("assignsRead"));
			assertEquals(structure, structure(program()));
			// The device of the file with the source "Zwei-Tasten".
			TLObject imported = device(completed, 2);
			configuration = new DeviceConfiguration(imported);
			assertEquals("1", configuration.value(target));
			assertEquals(Set.of("PB-1", "PB-3"), pages(configuration, imported));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static Object entryValue(TLObject holder) {
		return entryValue(ParameterValues.value(holder));
	}

	private static Object entryValue(Object value) {
		return value instanceof EnumerationValue entry ? Integer.valueOf(entry.getValue()) : value;
	}

	private static void set(TLObject device, ParameterView view, Object value) {
		set(device, view, value, false);
	}

	/**
	 * Sets a value as the editor does (checked), or as a value the device had (unchecked).
	 */
	private static void set(TLObject device, ParameterView view, Object value, boolean checked) {
		try (Transaction tx = kb().beginTransaction()) {
			if (checked) {
				DeviceSettings.setValue(device, view, value);
			} else {
				DeviceSettings.restoreValue(device, view, value);
			}
			tx.commit();
		}
	}

	private static Set<String> pages(DeviceConfiguration configuration, Object page) {
		Set<String> result = new TreeSet<>();
		for (ConfigFragment inner : configuration.getPages(page)) {
			if (inner instanceof ParameterGroup group) {
				result.add(group.getExtId().substring(P.length()));
			}
			result.addAll(pages(configuration, inner));
		}
		return result;
	}

	private static Set<Integer> objects(DeviceConfiguration configuration) {
		Set<Integer> result = new TreeSet<>();
		for (Port port : configuration.getActiveInterfaces().keySet()) {
			result.add(Integer.valueOf(port.getIndex()));
		}
		return result;
	}

	private static Program program() {
		return (Program) instances("ehome.operational:Program").stream()
			.filter(p -> P.startsWith(((Program) p).getExtId() + "_")).findFirst().orElseThrow();
	}

	private static ParameterView view(String id) {
		for (Parameter parameter : program().getParameters()) {
			for (ParameterView view : parameter.getViews()) {
				if (id.equals(view.getExtId())) {
					return view;
				}
			}
		}
		throw new AssertionError("No parameter reference " + id);
	}

	/**
	 * The assignments of the program in the order of its structure, as their labels.
	 */
	private static List<String> assigns(Program program) {
		List<String> result = new ArrayList<>();
		for (Assign assign : allAssigns(program)) {
			ParameterView source = assign.getSource();
			result.add(label(assign.getParameter()) + " := "
				+ (source == null ? ParameterValues.encode(assign) : label(source) + " ("
					+ source.getExtId().substring(P.length()) + ")"));
		}
		return result;
	}

	private static String label(ParameterView view) {
		return Resources.getInstance().getString(view.getParameter().getDescription());
	}

	private static List<Assign> allAssigns(TLObject obj) {
		List<Assign> result = new ArrayList<>();
		for (Object element : children(obj)) {
			if (element instanceof Assign assign) {
				result.add(assign);
			}
			result.addAll(allAssigns((TLObject) element));
		}
		return result;
	}

	/**
	 * The structure of the program, each element by its type and position.
	 */
	private static List<String> structure(TLObject obj) {
		List<String> result = new ArrayList<>();
		structure(obj, "", result);
		return result;
	}

	private static void structure(TLObject obj, String indent, List<String> result) {
		for (Object element : children(obj)) {
			TLObject child = (TLObject) element;
			result.add(indent + child.tType().getName()
				+ (child instanceof Assign assign ? " " + assign.getParameter().getExtId() : ""));
			structure(child, indent + "  ", result);
		}
	}

	private static int structureSize(TLObject obj) {
		return structure(obj).size();
	}

	private static Collection<?> children(TLObject obj) {
		List<Object> result = new ArrayList<>();
		for (String attribute : List.of("contents", "alternatives")) {
			if (obj.tType().getPart(attribute) != null) {
				result.addAll((Collection<?>) obj.tValueByName(attribute));
			}
		}
		return result;
	}

	private static TLObject device(List<TLObject> projects, int address) {
		List<TLObject> devices = SharedData.within("ehome.physical:Device", projects).stream()
			.filter(d -> Integer.valueOf(address).equals(d.tValueByName("address"))).toList();
		assertEquals(1, devices.size());
		return devices.get(0);
	}

	private static List<String> importFixture() throws Exception {
		KnowledgeBase kb = kb();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(FIXTURE)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		return TestEtsImport.withoutMasterData(importer.getMessages()).stream()
			.filter(m -> m.getLevel() == Level.WARN || m.getLevel() == Level.ERROR)
			.map(m -> Resources.getInstance().getString((ResKey) m.getMessage())).toList();
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestAssigns.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
