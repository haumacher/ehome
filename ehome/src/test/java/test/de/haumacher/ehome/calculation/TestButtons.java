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

import de.haumacher.ehome.calculation.DeviceButtons;
import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.operational.Button;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DeviceTexts;

/**
 * The buttons of the parameter dialog (ETS 6: <code>Button</code>, haumacher/ehome #158) with the
 * artificial fixture <code>src/test/fixtures/TestButtons.knxproj</code> (see <code>make-fixtures.py</code>,
 * which describes its program and device): the buttons read with their texts in German and English, at
 * their place in the page and under the conditions of the structure; a press copying a parameter, by ID and
 * by name, with the parameters of the handler as <code>context</code>; the validation of a value the handler
 * sets, whose refusal undoes the whole press; a button of a module resolving the IDs within its instance; a
 * member E-Home does not provide, found at import (warned, the button disabled with the reason) or only when
 * the handler runs (refused, nothing changed); a handler talking to the device; a second import adding no
 * buttons; the completion of a program an earlier import left without its buttons (in a block, in a case
 * of a switch, in a module), which then work as imported.
 *
 * <p>
 * Runs in the database shared with the other tests and deletes its projects again; the program of the
 * fixture stays. Four imports of the fixture.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestButtons extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestButtons.knxproj";

	private static final String PROGRAM = "M-0008_A-FFE2-01-0000";

	private static final String P = PROGRAM + "_";

	public void testButtons() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			Importer importer = importFixture();
			checkImport(importer);
			checkProgram();
			TLObject device = device(before);
			checkPage(device);
			checkCopy(device);
			checkRefusal(device);
			checkUnknownMembers(device);
			checkModules(device);

			// A second import of the same file adds no buttons.
			int buttons = buttons().size();
			Importer again = importFixture();
			assertTrue(again.getCreated("ehome.operational:Program").isEmpty());
			assertTrue(again.getCreated("ehome.operational:Button").isEmpty());
			assertEquals(buttons, buttons().size());
			assertEquals(7, DeviceButtons.all(program()).size());

			// A program an earlier import left without its buttons (before haumacher/ehome #158): the next
			// import of its file adds them at their places, once.
			SharedData.deleteProjects(SharedData.newProjects(before));
			Program program = program();
			try (Transaction tx = kb().beginTransaction()) {
				for (Button button : DeviceButtons.all(program)) {
					button.tDelete();
				}
				program.tUpdateByName("buttonsRead", null);
				tx.commit();
			}
			assertEquals(0, buttons().size());
			Importer completing = importFixture();
			String completed = String.valueOf(I18NConstants.INFO_BUTTONS_COMPLETED__PROGRAM_COUNT.fill(PROGRAM, 7));
			assertTrue(completing.getMessages().stream().anyMatch(
				m -> m.getLevel() == Level.INFO && String.valueOf(m.getMessage()).equals(completed)));
			assertTrue(completing.getCreated("ehome.operational:Program").isEmpty());
			assertEquals(Boolean.TRUE, program.tValueByName("buttonsRead"));
			checkImport(completing);
			checkProgram();
			TLObject completedDevice = device(before);
			checkPage(completedDevice);
			checkCopy(completedDevice);
			checkModules(completedDevice);
			importFixture();
			assertEquals(7, buttons().size());
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * No warning about the element Button; a handler E-Home cannot run is warned with its program, the
	 * button and the member.
	 */
	private static void checkImport(Importer importer) {
		List<String> warnings = warnings(importer);
		assertEquals(warnings.toString(), 2, warnings.size());
		assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.contains(PROGRAM)
			&& w.contains("Unbekannt") && w.contains(P + "B-3") && w.contains("device.GetParameterById(…).Text")));
		assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.contains(PROGRAM)
			&& w.contains("Gerät") && w.contains(P + "B-6") && w.contains("online.connect")));
	}

	private static void checkProgram() {
		Program program = program();
		List<Button> buttons = DeviceButtons.all(program);
		assertEquals(List.of(P + "B-1", P + "B-2", P + "B-3", P + "B-4", P + "B-6", P + "B-5", P + "MD-1_B-1"),
			buttons.stream().map(Button::getExtId).toList());
		Button copy = buttons.get(0);
		assertEquals("TEST_text", copy.getHandler());
		assertEquals("Text kopieren", Resources.getInstance(Locale.GERMAN).getString(copy.getText()));
		assertEquals("Copy text", Resources.getInstance(Locale.ENGLISH).getString(copy.getText()));
		assertEquals("{\"Plus\": 1}", buttons.get(1).getHandlerParameters());
		assertNull(DeviceButtons.problem(copy));
		assertNull(DeviceButtons.problem(buttons.get(1)));
		assertNull("Found only when it runs.", DeviceButtons.problem(buttons.get(3)));
		assertNull(DeviceButtons.problem(buttons.get(6)));
		String unknown = DeviceButtons.problem(buttons.get(2));
		assertTrue(unknown, unknown.contains("device.GetParameterById(…).Text"));
		String online = DeviceButtons.problem(buttons.get(4));
		assertTrue(online, online.contains("online.connect"));
	}

	/** At its place on the page, under the conditions of the structure. */
	private static void checkPage(TLObject device) {
		assertEquals(List.of(P + "P-1_R-1", P + "B-1", P + "P-2_R-1", P + "P-3_R-1", P + "B-2", P + "P-4_R-1",
			P + "B-3", P + "B-4", P + "B-6"), page(device));
		Button copy = button(device, P + "B-1");
		assertEquals(Resources.getInstance().getString(copy.getText()), DeviceTexts.label(device, copy));
		set(device, P + "P-3_R-1", Long.valueOf(7));
		assertTrue(page(device).contains(P + "B-5"));
		set(device, P + "P-3_R-1", null);
		assertFalse(page(device).contains(P + "B-5"));
	}

	/** A press copies; by ID and by name, with the parameters of the handler. */
	private static void checkCopy(TLObject device) {
		assertNull(DeviceButtons.veto(device, button(device, P + "B-1")));
		assertEquals(null, value(device, P + "P-2_R-1"));
		press(device, P + "B-1");
		assertEquals("Hallo", value(device, P + "P-2_R-1"));
		assertNotNull("A person's change: a setting.", DeviceSettings.setting(device, view(device, P + "P-2_R-1")));

		press(device, P + "B-2");
		assertEquals("Anzahl 5", value(device, P + "P-2_R-1"));
		assertEquals(Long.valueOf(6), value(device, P + "P-4_R-1"));
	}

	/** The validation of a value the handler sets refuses it: nothing of the press stays. */
	private static void checkRefusal(TLObject device) {
		set(device, P + "P-3_R-1", Long.valueOf(60));
		String refused = refused(device, P + "B-2");
		assertTrue(refused, refused.contains("Die Kopie darf höchstens 50 sein.") && refused.contains("Kopie")
			&& refused.contains("Anzahl kopieren"));
		assertEquals("Anzahl 5", value(device, P + "P-2_R-1"));
		assertEquals(Long.valueOf(6), value(device, P + "P-4_R-1"));
		set(device, P + "P-3_R-1", null);
	}

	/** A member E-Home does not provide: found at import, or refused when the handler runs. */
	private static void checkUnknownMembers(TLObject device) {
		ResKey veto = DeviceButtons.veto(device, button(device, P + "B-3"));
		assertNotNull(veto);
		String text = Resources.getInstance(Locale.ENGLISH).getString(veto);
		assertTrue(text, text.contains("cannot run") && text.contains("device.GetParameterById(…).Text"));
		String disabled = refused(device, P + "B-3");
		assertTrue(disabled, disabled.contains("cannot run"));

		String hidden = refused(device, P + "B-4");
		assertTrue(hidden, hidden.contains("Versteckt") && hidden.contains(".Text")
			&& hidden.contains("does not provide"));
		assertEquals("Anzahl 5", value(device, P + "P-2_R-1"));

		ResKey online = DeviceButtons.veto(device, button(device, P + "B-6"));
		assertNotNull(online);
		assertTrue(Resources.getInstance(Locale.ENGLISH).getString(online).contains("online.connect"));
	}

	/** The button of a module instance changes the parameters of its instance only. */
	private static void checkModules(TLObject device) {
		String first = P + "MD-1_M-1_MI-1_";
		String second = P + "MD-1_M-2_MI-1_";
		assertEquals(Long.valueOf(3), value(device, second + "P-1_R-1"));
		press(device, second + "B-1");
		assertEquals(Long.valueOf(3), value(device, second + "P-2_R-1"));
		assertEquals(Long.valueOf(0), value(device, first + "P-2_R-1"));
		press(device, first + "B-1");
		assertEquals(Long.valueOf(1), value(device, first + "P-2_R-1"));
		assertEquals(Long.valueOf(3), value(device, second + "P-2_R-1"));
	}

	private static List<String> page(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Object page = configuration.getShownItems().get(view(device, P + "P-1_R-1"));
		assertTrue(page instanceof ConfigFragment);
		List<String> result = new ArrayList<>();
		for (Option item : configuration.getItems(page)) {
			result.add(ModuleParts.partId(item));
		}
		return result;
	}

	private static void press(TLObject device, String id) {
		try (Transaction tx = kb().beginTransaction()) {
			DeviceButtons.press(device, button(device, id));
			tx.commit();
		}
	}

	/** The reason of a refused press; the transaction of the press is rolled back. */
	private static String refused(TLObject device, String id) {
		try (Transaction tx = kb().beginTransaction()) {
			DeviceButtons.press(device, button(device, id));
			tx.commit();
		} catch (TopLogicException ex) {
			return Resources.getInstance(Locale.ENGLISH).getString(ex.getErrorKey());
		}
		throw new AssertionError("Not refused: " + id);
	}

	private static void set(TLObject device, String id, Object value) {
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(device, view(device, id), value);
			tx.commit();
		}
	}

	private static Object value(TLObject device, String id) {
		return DeviceSettings.value(device, view(device, id));
	}

	private static ParameterView view(TLObject device, String id) {
		for (ParameterView view : new DeviceConfiguration(device).getActiveViews().values()) {
			if (id.equals(ModuleParts.partId(view))) {
				return view;
			}
		}
		throw new AssertionError("No placement " + id);
	}

	private static Button button(TLObject device, String id) {
		for (Option item : new DeviceConfiguration(device).getShownItems().keySet()) {
			if (item instanceof Button button && id.equals(ModuleParts.partId(button))) {
				return button;
			}
		}
		throw new AssertionError("No button " + id);
	}

	private static List<TLObject> buttons() {
		return instances("ehome.operational:Button").stream()
			.filter(b -> String.valueOf(b.tValueByName("extId")).startsWith(P)).toList();
	}

	private static Program program() {
		return (Program) instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(p.tValueByName("extId"))).findFirst().orElseThrow();
	}

	private static TLObject device(List<TLObject> before) {
		List<TLObject> devices = SharedData.within("ehome.physical:Device", SharedData.newProjects(before));
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestButtons.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
