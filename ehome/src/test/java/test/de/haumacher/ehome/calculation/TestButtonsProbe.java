/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.calculation;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.calculation.DeviceButtons;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.operational.Button;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * Probe of the buttons of the parameter dialog (haumacher/ehome #158) beyond the cases of
 * {@link TestButtons}: a value the handler computes outside the range of its parameter's type (the type
 * check, not a validation) undoes the whole press; a button whose case of the structure is not taken
 * cannot be pressed.
 */
@SuppressWarnings("javadoc")
public class TestButtonsProbe extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestButtons.knxproj";

	private static final String PROGRAM = "M-0008_A-FFE2-01-0000";

	private static final String P = PROGRAM + "_";

	public void testProbe() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importFixture();
			List<TLObject> devices = SharedData.within("ehome.physical:Device", SharedData.newProjects(before));
			assertEquals(1, devices.size());
			TLObject device = devices.get(0);

			// Anzahl 100: B-2 sets Ziel to "Anzahl 100", then Kopie to 101, outside 0..100.
			set(device, P + "P-3_R-1", 100);
			Object ziel = value(device, P + "P-2_R-1");
			Object kopie = value(device, P + "P-4_R-1");
			try (Transaction tx = kb().beginTransaction()) {
				DeviceButtons.press(device, button(P + "B-2"));
				tx.commit();
				fail("A value outside the range of its type was accepted.");
			} catch (TopLogicException ex) {
				// Refused.
			}
			assertEquals(ziel, value(device, P + "P-2_R-1"));
			assertEquals(kopie, value(device, P + "P-4_R-1"));

			// Anzahl is not 7: B-5 in the case "when 7" is not on the page and must not run.
			Object before5 = value(device, P + "P-2_R-1");
			try (Transaction tx = kb().beginTransaction()) {
				DeviceButtons.press(device, button(P + "B-5"));
				tx.commit();
				fail("A button of a case not taken ran.");
			} catch (TopLogicException ex) {
				// Refused.
			}
			assertEquals(before5, value(device, P + "P-2_R-1"));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private static Button button(String extId) {
		Program program = (Program) instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(p.tValueByName("extId"))).findFirst().orElseThrow();
		return DeviceButtons.all(program).stream()
			.filter(b -> extId.equals(b.tValueByName("extId"))).findFirst().orElseThrow();
	}

	private static Object value(TLObject device, String id) {
		return DeviceSettings.value(device, view(device, id));
	}

	private static void set(TLObject device, String id, Object value) {
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(device, view(device, id), value);
			tx.commit();
		}
	}

	private static ParameterView view(TLObject device, String id) {
		for (ParameterView view : new DeviceConfiguration(device).getActiveViews().values()) {
			if (id.equals(ModuleParts.partId(view))) {
				return view;
			}
		}
		throw new AssertionError("No placement " + id);
	}

	private static void importFixture() throws Exception {
		KnowledgeBase kb = kb();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(FIXTURE)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestButtonsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
