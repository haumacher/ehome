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
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.calculation.DeviceCalculations;
import de.haumacher.ehome.calculation.DeviceValidations;
import de.haumacher.ehome.calculation.JavaScriptLanguage;
import de.haumacher.ehome.calculation.VbScriptLanguage;
import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ParameterValidation;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * The parameter validations of the manufacturer (haumacher/ehome #124) with the artificial fixture
 * <code>src/test/fixtures/TestValidations.knxproj</code> (see <code>make-fixtures.py</code>, which
 * describes its programs and devices), on the change path of the editor
 * (<code>DeviceSettings.applyValue</code>, which <code>deviceApplyValue</code> of the view calls): a
 * refusal with the manufacturer's text, an accepted value, <code>changed</code> and
 * <code>prevValue</code>, a JSON context, a validation per module instance, a script error and an
 * answer that is false, the order with a calculation (the validation first), VBScript validations, a
 * validation E-Home cannot evaluate, the import of values a validation refuses (kept and reported), and
 * the completion of a program an earlier import left without its validations.
 *
 * <p>
 * Runs in the database shared with the other tests and deletes its projects again; the programs of
 * the fixture stay. Two imports of the fixture.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestParameterValidations extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestValidations.knxproj";

	private static final String CHECK = "M-0008_A-FFFC-01-0000";

	private static final String VB = "M-0008_A-FFFD-01-0000";

	private static final String C = CHECK + "_";

	private static final String V = VB + "_";

	public void testValidations() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			Importer importer = importFixture();
			checkImport(importer);
			checkProgram();
			TLObject valid = device(before, 1);
			TLObject invalid = device(before, 2);
			TLObject basic = device(before, 3);
			checkKept(invalid);
			checkJavaScript(valid);
			checkModules(valid);
			checkOrder(valid);
			checkVbScript(basic);

			// Completion: a program an earlier import left without its validations.
			SharedData.deleteProjects(SharedData.newProjects(before));
			Program program = program(CHECK);
			try (Transaction tx = kb().beginTransaction()) {
				for (ParameterValidation validation : new ArrayList<>(program.getValidations())) {
					validation.tDelete();
				}
				for (ModuleDefinition definition : program.getModules()) {
					for (ParameterValidation validation : new ArrayList<>(definition.getValidations())) {
						validation.tDelete();
					}
				}
				program.setValidationsRead(false);
				tx.commit();
			}
			Importer again = importFixture();
			String completed = String.valueOf(I18NConstants.INFO_VALIDATIONS_COMPLETED__PROGRAM.fill(CHECK));
			assertTrue(again.getMessages().stream().anyMatch(
				m -> m.getLevel() == Level.INFO && String.valueOf(m.getMessage()).contains(completed)));
			assertTrue(again.getCreated("ehome.operational:Program").isEmpty());
			checkProgram();
			checkJavaScript(device(before, 1));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * No warning about the element ParameterValidations; the values a validation refuses are reported
	 * per device, a validation E-Home cannot evaluate with its program.
	 */
	private static void checkImport(Importer importer) {
		List<String> warnings = warnings(importer);
		assertTrue(warnings.toString(), warnings.stream().noneMatch(w -> w.contains("ParameterValidation")));
		assertEquals(warnings.toString(), 3, warnings.size());
		assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.contains("TEST Prüfgerät ungültig")
			&& w.contains("Abstand") && w.contains("Untergrenze muss unter der Obergrenze")));
		assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.contains("TEST Prüfgerät ungültig")
			&& w.contains("EinVorAus") && w.contains("Ein muss kleiner als Aus sein.")));
		assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.contains(VB) && w.contains("Fehlt")
			&& w.contains("PruefeFehlt")));
	}

	private static void checkProgram() {
		Program program = program(CHECK);
		assertTrue(program.getValidationsRead());
		assertEquals(List.of(C + "PV-1", C + "PV-2", C + "PV-3", C + "PV-4", C + "PV-5"),
			program.getValidations().stream().map(ParameterValidation::getExtId).toList());
		ParameterValidation distance = program.getValidations().get(0);
		assertEquals("Abstand", distance.getName());
		assertEquals("TEST_abstand", distance.getFunction());
		assertEquals("Untergrenze unter Obergrenze", distance.getInternalDescription());
		assertNull(distance.getArguments());
		assertEquals(List.of("Low", "High"),
			distance.getParameters().stream().map(DeviceCalculations::variable).toList());
		ParameterValidation brightness = program.getValidations().get(2);
		assertEquals("{\"Max\": 50}", brightness.getArguments());
		assertEquals("Without an alias: the name of the parameter.", List.of("Helligkeit"),
			brightness.getParameters().stream().map(DeviceCalculations::variable).toList());
		assertEquals(JavaScriptLanguage.INSTANCE, DeviceValidations.language(distance));
		ParameterValidation module = program.getModules().get(0).getValidations().get(0);
		assertEquals(C + "MD-1_PV-1", module.getExtId());
		assertNull(DeviceValidations.problem(module));

		Program vb = program(VB);
		assertEquals(VbScriptLanguage.INSTANCE, DeviceValidations.language(vb.getValidations().get(0)));
		assertNotNull(DeviceValidations.problem(vb.getValidations().get(2)));
	}

	/** The values of the file stay, also those a validation refuses. */
	private static void checkKept(TLObject invalid) {
		assertEquals(Long.valueOf(9), value(invalid, C + "P-1_R-1"));
		assertEquals(Long.valueOf(3), value(invalid, C + "P-2_R-1"));
		assertEquals(Long.valueOf(50), value(invalid, C + "MD-1_M-2_MI-1_P-1_R-1"));
		// A change that fixes the values is accepted: back to the default 1 of Untergrenze, below 3.
		apply(invalid, C + "P-1_R-1", null);
		assertEquals(Long.valueOf(1), value(invalid, C + "P-1_R-1"));
	}

	private static void checkJavaScript(TLObject device) {
		// Refused with the manufacturer's text, nothing changed; accepted.
		String refused = refused(device, C + "P-1_R-1", 8);
		assertTrue(refused, refused.contains("Untergrenze muss unter der Obergrenze") && refused.contains("Abstand"));
		assertEquals(Long.valueOf(2), value(device, C + "P-1_R-1"));
		apply(device, C + "P-1_R-1", Long.valueOf(7));
		assertEquals(Long.valueOf(7), value(device, C + "P-1_R-1"));
		apply(device, C + "P-1_R-1", Long.valueOf(2));

		// The text depends on changed; an enumeration by its value.
		String number = refused(device, C + "P-4_R-1", 200);
		assertTrue(number, number.contains("Nummer 200 liegt über 100"));
		apply(device, C + "P-3_R-1", Long.valueOf(1));
		apply(device, C + "P-4_R-1", Long.valueOf(200));
		String model = refused(device, C + "P-3_R-1", 0);
		assertTrue(model, model.contains("höchstens 100 Nummern"));
		assertEquals(1, ((EnumerationValue) DeviceSettings.value(device, view(device, C + "P-3_R-1"))).getValue());
		apply(device, C + "P-4_R-1", null);
		apply(device, C + "P-3_R-1", null);

		// The JSON context and prevValue.
		String brightness = refused(device, C + "P-5_R-1", 60);
		assertTrue(brightness, brightness.contains("Höchstens 50 (bisher 10)"));
		apply(device, C + "P-5_R-1", Long.valueOf(50));
		assertEquals(Long.valueOf(50), value(device, C + "P-5_R-1"));
		String previous = refused(device, C + "P-5_R-1", 51);
		assertTrue(previous, previous.contains("(bisher 50)"));
		apply(device, C + "P-5_R-1", null);

		// A script error refuses naming it; an answer that is false refuses without a text.
		String error = refused(device, C + "P-6_R-1", 7);
		assertTrue(error, error.contains("kaputt") && error.contains(C + "PV-4") && error.contains("failed"));
		String no = refused(device, C + "P-6_R-1", 8);
		assertTrue(no, no.contains("refuses") && no.contains(C + "PV-4"));
		assertEquals(Long.valueOf(0), value(device, C + "P-6_R-1"));
		apply(device, C + "P-6_R-1", Long.valueOf(3));
		apply(device, C + "P-6_R-1", null);
	}

	/** Per module instance, with the values of that instance. */
	private static void checkModules(TLObject device) {
		String refused = refused(device, C + "MD-1_M-1_MI-1_P-1_R-1", 25);
		assertTrue(refused, refused.contains("Ein muss kleiner als Aus sein.") && refused.contains("EinVorAus"));
		apply(device, C + "MD-1_M-2_MI-1_P-2_R-1", Long.valueOf(40));
		apply(device, C + "MD-1_M-2_MI-1_P-1_R-1", Long.valueOf(30));
		assertEquals(Long.valueOf(30), value(device, C + "MD-1_M-2_MI-1_P-1_R-1"));
		assertEquals(Long.valueOf(10), value(device, C + "MD-1_M-1_MI-1_P-1_R-1"));
		String other = refused(device, C + "MD-1_M-2_MI-1_P-2_R-1", 30);
		assertTrue(other, other.contains("Ein muss kleiner"));
	}

	/**
	 * The validation runs before the calculation the value feeds: a refused value computes nothing. The
	 * calculation has the same alias on both sides (Art), as programs in the field have it.
	 */
	private static void checkOrder(TLObject device) {
		String refused = refused(device, C + "P-7_R-1", 4);
		assertTrue(refused, refused.contains("Die Art 4 gibt es nicht."));
		assertEquals(Long.valueOf(0), value(device, C + "P-8_R-1"));
		apply(device, C + "P-7_R-1", Long.valueOf(3));
		assertEquals(Long.valueOf(3), value(device, C + "P-8_R-1"));
	}

	private static void checkVbScript(TLObject device) {
		String from = refused(device, V + "P-1_R-1", 20);
		assertTrue(from, from.contains("Von (20) darf nicht größer als Bis sein, bisher 1"));
		String to = refused(device, V + "P-2_R-1", 0);
		assertTrue(to, to.contains("Bis darf nicht kleiner als Von sein"));
		apply(device, V + "P-1_R-1", Long.valueOf(10));
		assertEquals(Long.valueOf(10), value(device, V + "P-1_R-1"));
		String limit = refused(device, V + "P-3_R-1", 60);
		assertTrue(limit, limit.contains("refuses") && limit.contains(V + "PV-2"));
		apply(device, V + "P-3_R-1", Long.valueOf(50));
		// A validation E-Home cannot evaluate: its parameter cannot be changed, with the reason.
		ResKey veto = DeviceSettings.editVeto(device, view(device, V + "P-4_R-1"));
		assertNotNull(veto);
		String text = Resources.getInstance(Locale.ENGLISH).getString(veto);
		assertTrue(text, text.contains("cannot be changed") && text.contains("PruefeFehlt"));
		String locked = refused(device, V + "P-4_R-1", 1);
		assertTrue(locked, locked.contains("cannot be changed"));
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

	private static Object value(TLObject device, String id) {
		Object result = DeviceSettings.value(device, view(device, id));
		return result instanceof EnumerationValue entry ? Long.valueOf(entry.getValue()) : result;
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
		throw new AssertionError("No placement " + id);
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
			.map(m -> Resources.getInstance(Locale.ENGLISH).getString((ResKey) m.getMessage())).toList();
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParameterValidations.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
