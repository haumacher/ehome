/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.calculation;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

import de.haumacher.ehome.calculation.DeviceCalculations;
import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ParameterCalculation;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.ProgramChanges;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * The parameter calculations of the manufacturer (haumacher/ehome #123) with the artificial fixture
 * <code>src/test/fixtures/TestCalculations.knxproj</code> (see <code>make-fixtures.py</code>, which
 * describes its programs and devices): LR when an L parameter changes (VBScript without aliases,
 * JavaScript with aliases, a function of the program's script, an enumeration by its value, a
 * calculation per module instance), RL where the R values are the source (an import whose L values
 * do not give its R values, a changed R value), the refusals (a script error, an endless loop, a
 * result out of range, a calculation E-Home cannot evaluate) with nothing changed, the R values in the
 * download image, a device planned or duplicated in E-Home, and the completion of a program an earlier
 * import left without its calculations.
 *
 * <p>
 * Runs in the database shared with the other tests and deletes its projects again; the programs of
 * the fixture stay. Two imports of the fixture.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestParameterCalculations extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestCalculations.knxproj";

	private static final String CALC = "M-0008_A-FFFA-01-0000";

	private static final String LOCKED = "M-0008_A-FFFB-01-0000";

	private static final String C = CALC + "_";

	private static final int SEGMENT = 0x100;

	public void testCalculations() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			Importer importer = importFixture();
			List<String> warnings = warnings(importer);
			assertEquals(warnings.toString(), 1, warnings.size());
			assertTrue(warnings.get(0), warnings.get(0).contains(LOCKED) && warnings.get(0).contains("Select"));

			checkProgram();
			TLObject consistent = device(before, 1);
			TLObject rOnly = device(before, 2);
			TLObject locked = device(before, 3);
			checkImported(consistent, rOnly, locked);
			checkImage(consistent);
			checkChanges(consistent, rOnly);
			checkRefusals(consistent);
			checkLocked(locked);
			checkPlanning(consistent);
			checkProgramChange(consistent, locked);
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * A program an earlier import left without its calculations is completed by an import of its file.
	 */
	public void testCompletion() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importFixture();
			SharedData.deleteProjects(SharedData.newProjects(before));
			Program program = program(CALC);
			try (Transaction tx = kb().beginTransaction()) {
				for (ParameterCalculation calculation : new ArrayList<>(program.getCalculations())) {
					calculation.tDelete();
				}
				for (ModuleDefinition definition : program.getModules()) {
					for (ParameterCalculation calculation : new ArrayList<>(definition.getCalculations())) {
						calculation.tDelete();
					}
				}
				program.setScript(null);
				program.setCalculationsRead(false);
				tx.commit();
			}
			Importer importer = importFixture();
			String completed = String.valueOf(I18NConstants.INFO_CALCULATIONS_COMPLETED__PROGRAM.fill(CALC));
			assertTrue(importer.getMessages().stream().anyMatch(
				m -> m.getLevel() == Level.INFO && String.valueOf(m.getMessage()).contains(completed)));
			assertTrue(importer.getCreated("ehome.operational:Program").isEmpty());
			checkProgram();
			// The devices imported with it are brought in line with the completed calculations.
			assertEquals(Long.valueOf(7230), value(device(before, 2), C + "P-1_R-1"));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private static void checkProgram() {
		Program program = program(CALC);
		assertTrue(program.getCalculationsRead());
		assertEquals(List.of(C + "PC-1", C + "PC-2", C + "PC-3"),
			program.getCalculations().stream().map(ParameterCalculation::getExtId).toList());
		ParameterCalculation s2hms = program.getCalculations().get(0);
		assertEquals("VBScript", s2hms.getLanguage());
		assertTrue(s2hms.getLrCode(), s2hms.getLrCode().contains("\n"));
		assertEquals(List.of("Dauer"), s2hms.getInputs().stream().map(DeviceCalculations::variable).toList());
		assertEquals(List.of("Dauer_h", "Dauer_min", "Dauer_s"),
			s2hms.getOutputs().stream().map(DeviceCalculations::variable).toList());
		ParameterCalculation scene = program.getCalculations().get(1);
		assertEquals(List.of("BlindScene"), scene.getOutputs().stream().map(DeviceCalculations::variable).toList());
		// The form with functions of the script.
		assertNull(scene.getLrCode());
		assertEquals("szeneLR", scene.getLrFunction());
		assertEquals("{\"Faktor\": 1}", scene.getLrParameters());
		assertEquals("szeneRL", scene.getRlFunction());
		assertTrue(program.getScript(), program.getScript().contains("function verdoppeln"));
		assertTrue(program.getModules().get(0).getScript().contains("function mal"));
		ParameterCalculation module = program.getModules().get(0).getCalculations().get(0);
		assertEquals("p1 = mal(p2, p3)", module.getRlCode());
		assertTrue(module.getLrCode(), module.getLrCode().contains("if (p2 < 1)"));
		assertEquals(C + "MD-1_PC-1", module.getExtId());
		assertEquals(List.of("p1", "p3"), module.getInputs().stream().map(DeviceCalculations::variable).toList());
		assertNull(DeviceCalculations.problem(module));
		assertNotNull(DeviceCalculations.problem(program(LOCKED).getCalculations().get(0)));
		assertNull(DeviceCalculations.problem(program(LOCKED).getCalculations().get(1)));
	}

	private static void checkImported(TLObject consistent, TLObject rOnly, TLObject locked) {
		// The values of the file, which fit the calculations.
		assertEquals(Long.valueOf(3725), value(consistent, C + "P-1_R-1"));
		assertEquals(List.of(1L, 2L, 5L), hms(consistent));
		assertEquals(Long.valueOf(2), value(consistent, C + "P-6_R-1"));
		assertEquals(Long.valueOf(55), value(consistent, C + "MD-1_M-1_MI-1_P-1_R-1"));

		// RL: the L values from the R values of the file.
		assertEquals(List.of(2L, 0L, 30L), hms(rOnly));
		assertEquals(Long.valueOf(7230), value(rOnly, C + "P-1_R-1"));
		assertEquals(Long.valueOf(70), value(rOnly, C + "MD-1_M-1_MI-1_P-1_R-1"));
		// The second instance has the inconsistent defaults of the program: RL from Stufe 0.
		assertEquals(Long.valueOf(0), value(rOnly, C + "MD-1_M-2_MI-1_P-1_R-1"));
		// The derived L values change nothing the device holds: it is as modified as the file says.
		assertEquals(consistent.tValueByName("lastModified"), rOnly.tValueByName("lastModified"));
		// PC-2 gives no L value for Szene 0: the file's values stay.
		assertEquals(Long.valueOf(0), value(rOnly, C + "P-6_R-1"));
		assertEquals(3, ((de.haumacher.ehome.model.operational.EnumerationValue) DeviceSettings.value(rOnly,
			view(rOnly, C + "P-5_R-1"))).getValue());

		assertEquals(Long.valueOf(1), value(locked, LOCKED + "_P-2_R-1"));
	}

	private static void checkImage(TLObject device) {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		DownloadImage image = plan.getImage();
		assertEquals(image.dump(), List.of(1, 2, 5, 2, 30), bytes(image, 0x58, 5));
		assertEquals(List.of(5, 5), bytes(image, 0x60, 2));
		assertEquals(List.of(5, 5), bytes(image, 0x68, 2));
	}

	private static void checkChanges(TLObject device, TLObject rOnly) {
		// The L parameters of working calculations and the other parameters can be changed.
		for (String id : List.of(C + "P-1_R-1", C + "P-5_R-1", C + "P-7_R-1", C + "MD-1_M-1_MI-1_P-1_R-1")) {
			assertNull(id, DeviceSettings.editVeto(device, view(device, id)));
		}
		// An R parameter says that a calculation computes it.
		String hint = Resources.getInstance(Locale.ENGLISH).getString(
			DeviceCalculations.hint(device, view(device, C + "P-2_R-1")));
		assertTrue(hint, hint.contains("Computed") && hint.contains("s2hms"));
		assertNull(DeviceCalculations.hint(device, view(device, C + "P-1_R-1")));

		// LR in VBScript.
		apply(device, C + "P-1_R-1", Long.valueOf(86399));
		assertEquals(List.of(23L, 59L, 59L), hms(device));
		// Back to the default: LR again.
		apply(device, C + "P-1_R-1", null);
		assertEquals(Long.valueOf(60), value(device, C + "P-1_R-1"));
		assertEquals(List.of(0L, 1L, 0L), hms(device));

		// RL when an R value changes (the read-back of a device): the L value follows.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.restoreValue(device, view(device, C + "P-2_R-1"), Long.valueOf(3));
			tx.commit();
		}
		assertEquals(Long.valueOf(3 * 3600 + 60), value(device, C + "P-1_R-1"));

		// JavaScript with aliases, a function of the script, an enumeration by its value; an R
		// parameter the run does not assign keeps its value.
		apply(rOnly, C + "P-5_R-1", Long.valueOf(1));
		assertEquals(Long.valueOf(0), value(rOnly, C + "P-6_R-1"));
		apply(rOnly, C + "P-5_R-1", Long.valueOf(3));
		assertEquals(Long.valueOf(2), value(rOnly, C + "P-6_R-1"));

		// Per module instance, a fraction truncated.
		apply(device, C + "MD-1_M-2_MI-1_P-1_R-1", Long.valueOf(99));
		assertEquals(Long.valueOf(9), value(device, C + "MD-1_M-2_MI-1_P-3_R-1"));
		assertEquals(Long.valueOf(9), value(device, C + "MD-1_M-2_MI-1_P-4_R-1"));
		assertEquals(Long.valueOf(5), value(device, C + "MD-1_M-1_MI-1_P-3_R-1"));
		apply(device, C + "MD-1_M-2_MI-1_P-1_R-1", Long.valueOf(5));
		assertEquals("p2 < 1 gives 1", Long.valueOf(1), value(device, C + "MD-1_M-2_MI-1_P-3_R-1"));
		assertEquals("p4 < 1 gives 0", Long.valueOf(0), value(device, C + "MD-1_M-2_MI-1_P-4_R-1"));

		DownloadImage image = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getImage();
		assertEquals(image.dump(), List.of(3, 1, 0), bytes(image, 0x58, 3));
		assertEquals(List.of(1, 0), bytes(image, 0x68, 2));
	}

	private static void checkRefusals(TLObject device) {
		String error = refused(device, C + "P-7_R-1", 7);
		assertTrue(error, error.contains("kaputt") && error.contains(C + "PC-3"));
		String loop = refused(device, C + "P-7_R-1", 9);
		assertTrue(loop, loop.contains("stopped") && loop.contains(C + "PC-3"));
		String range = refused(device, C + "P-7_R-1", 10);
		assertTrue(range, range.contains("300") && range.contains("FehlerR"));
		// Nothing changed.
		assertEquals(Long.valueOf(1), value(device, C + "P-7_R-1"));
		assertEquals(Long.valueOf(30), value(device, C + "P-8_R-1"));
		apply(device, C + "P-7_R-1", Long.valueOf(8));
		assertEquals(Long.valueOf(240), value(device, C + "P-8_R-1"));
	}

	private static void checkLocked(TLObject device) {
		ParameterView view = view(device, LOCKED + "_P-1_R-1");
		ResKey veto = DeviceSettings.editVeto(device, view);
		assertNotNull(veto);
		String text = Resources.getInstance(Locale.ENGLISH).getString(veto);
		assertTrue(text, text.contains("cannot be changed") && text.contains("Select"));
		assertEquals(veto, DeviceCalculations.hint(device, view));
		String refused = refused(device, LOCKED + "_P-1_R-1", 1);
		assertTrue(refused, refused.contains("cannot be changed"));
		assertEquals(Long.valueOf(1), value(device, LOCKED + "_P-2_R-1"));
		// The other calculation of the program, in VBScript with a function of its script, works.
		apply(device, LOCKED + "_P-3_R-1", Long.valueOf(3));
		assertEquals(Long.valueOf(180), value(device, LOCKED + "_P-4_R-1"));
	}

	private static void checkPlanning(TLObject imported) {
		TLObject planned;
		TLObject copy;
		try (Transaction tx = kb().beginTransaction()) {
			planned = DevicePlanning.create(imported.tContainer(), (TLObject) imported.tValueByName("type"),
				(TLObject) imported.tValueByName("program"), Integer.valueOf(10), "TEST geplant", null);
			copy = DevicePlanning.duplicate(imported);
			tx.commit();
		}
		// Szene from the default of Schwelle (the program's default 0 does not fit), Stufe of each
		// instance from Zeit 50 and Teiler 10 (default 0).
		assertEquals(Long.valueOf(2), value(planned, C + "P-6_R-1"));
		assertEquals(List.of(0L, 1L, 0L), hms(planned));
		assertEquals(Long.valueOf(5), value(planned, C + "MD-1_M-1_MI-1_P-3_R-1"));
		assertEquals(Long.valueOf(5), value(planned, C + "MD-1_M-2_MI-1_P-3_R-1"));
		assertEquals(hms(imported), hms(copy));
		assertEquals(value(imported, C + "MD-1_M-2_MI-1_P-3_R-1"), value(copy, C + "MD-1_M-2_MI-1_P-3_R-1"));
		assertEquals(value(imported, C + "P-8_R-1"), value(copy, C + "P-8_R-1"));
		assertEquals(Integer.valueOf(5), DownloadPlanner.plan(planned, DownloadPlan.Kind.FULL).getImage()
			.byteAt(SEGMENT + 0x60));

		// A program with a calculation E-Home cannot evaluate: a device of it is not planned.
		TLObject lockedAssignment = instances("ehome.catalog:ProgramAssignment").stream()
			.filter(a -> "M-0008_H-TEST.20RECHNUNG-1_HP-FFFB-01-0000".equals(a.tValueByName("extId"))).findFirst()
			.orElseThrow();
		try (Transaction tx = kb().beginTransaction()) {
			DevicePlanning.create(imported.tContainer(), (TLObject) imported.tValueByName("type"), lockedAssignment,
				Integer.valueOf(12), "TEST gesperrt geplant", null);
			fail("A device whose calculation cannot be evaluated was planned.");
		} catch (TopLogicException ex) {
			String text = Resources.getInstance(Locale.ENGLISH).getString(ex.getErrorKey());
			assertTrue(text, text.contains("cannot evaluate") && text.contains("Select"));
		}
	}

	/**
	 * A device whose program changes (the device form) gets the module instances and computed R values
	 * of the new program (ProgramChanges); a program whose calculation cannot be evaluated is refused.
	 */
	private static void checkProgramChange(TLObject consistent, TLObject locked) {
		Object calcAssignment = consistent.tValueByName("program");
		Object lockedAssignment = locked.tValueByName("program");
		try (Transaction tx = kb().beginTransaction()) {
			locked.tUpdateByName("program", calcAssignment);
			tx.commit();
		}
		assertEquals(Long.valueOf(2), value(locked, C + "P-6_R-1"));
		assertEquals(Long.valueOf(5), value(locked, C + "MD-1_M-2_MI-1_P-3_R-1"));
		try (Transaction tx = kb().beginTransaction()) {
			consistent.tUpdateByName("program", lockedAssignment);
			tx.commit();
			fail("A program whose calculation cannot be evaluated was set.");
		} catch (RuntimeException ex) {
			String text = String.valueOf(ex.getMessage()) + " " + (ex.getCause() == null ? "" : ex.getCause().getMessage());
			if (ex instanceof TopLogicException problem) {
				text += Resources.getInstance(Locale.ENGLISH).getString(problem.getErrorKey());
			}
			assertTrue(text, text.contains("Select") || text.contains("Auswahl"));
		}
		assertEquals(calcAssignment, consistent.tValueByName("program"));
	}

	private static String refused(TLObject device, String id, long value) {
		ResKey result;
		try (Transaction tx = kb().beginTransaction()) {
			result = DeviceSettings.applyValue(device, view(device, id), Long.valueOf(value));
			tx.commit();
		}
		assertNotNull("Not refused: " + id + " = " + value, result);
		return Resources.getInstance(Locale.ENGLISH).getString(result);
	}

	private static void apply(TLObject device, String id, Object value) {
		ResKey result;
		try (Transaction tx = kb().beginTransaction()) {
			result = DeviceSettings.applyValue(device, view(device, id), value);
			tx.commit();
		}
		if (result != null) {
			fail(Resources.getInstance(Locale.ENGLISH).getString(result));
		}
	}

	private static List<Long> hms(TLObject device) {
		return List.of((Long) value(device, C + "P-2_R-1"), (Long) value(device, C + "P-3_R-1"),
			(Long) value(device, C + "P-4_R-1"));
	}

	private static Object value(TLObject device, String id) {
		Object result = DeviceSettings.value(device, view(device, id));
		return result instanceof de.haumacher.ehome.model.operational.EnumerationValue entry
			? Long.valueOf(entry.getValue()) : result;
	}

	/**
	 * The placement in effect in the device with the given ID (of a module instance's part: as ETS
	 * names it in a device, see {@link ModuleParts#partId(TLObject)}).
	 */
	private static ParameterView view(TLObject device, String id) {
		for (ParameterView view : new DeviceConfiguration(device).getActiveViews().values()) {
			if (id.equals(ModuleParts.partId(view)) || id.equals(view.getExtId()) && ModuleParts.instance(view) == null) {
				return view;
			}
		}
		throw new AssertionError("No placement " + id + " in " + new DeviceConfiguration(device).getActiveViews()
			.values().stream().map(ModuleParts::partId).toList());
	}

	private static List<Integer> bytes(DownloadImage image, int offset, int count) {
		List<Integer> result = new ArrayList<>();
		for (int n = 0; n < count; n++) {
			result.add(image.byteAt(SEGMENT + offset + n));
		}
		return result;
	}

	private static Program program(String id) {
		return (Program) instances("ehome.operational:Program").stream()
			.filter(p -> id.equals(p.tValueByName("extId"))).findFirst().orElseThrow();
	}

	private static TLObject device(List<TLObject> before, int address) {
		List<TLObject> devices = SharedData.within("ehome.physical:Device", SharedData.newProjects(before)).stream()
			.filter(d -> Integer.valueOf(address).equals(d.tValueByName("address"))).toList();
		assertEquals(1, devices.size());
		return devices.get(0);
	}

	private static Importer importFixture() throws Exception {
		KnowledgeBase kb = kb();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(FIXTURE)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		return importer;
	}

	private static List<String> warnings(Importer importer) {
		return TestEtsImport.withoutMasterData(importer.getMessages()).stream()
			.filter(m -> m.getLevel() == Level.WARN || m.getLevel() == Level.ERROR)
			.map(m -> Resources.getInstance().getString((ResKey) m.getMessage())).toList();
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParameterCalculations.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			ProgramChanges.Module.INSTANCE));
	}

}
