/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.device;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ProposeFunctionsAction;

/**
 * The wiring of the function proposal (<code>src/test/fixtures/TestFunctionWiring.knxproj</code>,
 * see <code>make-fixtures.py</code>).
 *
 * <ul>
 * <li>Rooms (haumacher/ehome #133): the actuators sit in the distribution board "Schaltschrank" in
 * the room "Flur". A distribution board is never the room of a function: the light whose status
 * object sends on its switching address has no sender but its own actuator, which does not count,
 * so it has no room (not the board); the blind no key operates neither. The temperature measured by
 * the key in "Wohnzimmer" and received by the heating actuator in the board (whose object has the
 * transmit flag set) is in "Wohnzimmer". The workshop light, switched by one key in "Werkstatt" and
 * one in "Flur", gets no room: the user decides; with the second key moved to "Werkstatt" as well,
 * it is there.</li>
 * <li>Kinds and roles (haumacher/ehome #134): the channel whose objects are a blind's (up/down
 * 1.008, slat/stop 1.007) is a blind with move and stop, although its addresses are typed 1.001,
 * and the function warns about the address; the stop of the other blind is its 1.008 object
 * "Kurzzeitbetrieb/Stop". The light whose status object sends on the switching address has no
 * state and warns about the wiring. The dimmer is named after its switching address.</li>
 * <li>Unused functions (haumacher/ehome #135) are named after the device's name and the channel,
 * the name followed by the individual address where devices share it, the individual address alone
 * for a device without a name; never the catalog text. A name too long is shortened in its device
 * part, once for the device (the same part in all its names, fitted to its longest channel name,
 * cut at a word boundary with an ellipsis). Two channels of one device with the same name get a number. The notification lists the
 * unused functions apart.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestFunctionWiring extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestFunctionWiring.knxproj";

	private static final List<String> EXPECTED = List.of(
		// #135: a device without a name by its individual address; two channels "Taste" numbered.
		"1.0.6: Taste (2) | button |  | Küche | unused",
		"1.0.6: Taste 1 | button |  | Küche | unused",
		"1.0.6: Taste 2 | button |  | Küche | unused",
		"1.0.6: Taste | button |  | Küche | unused",
		"1.0.6: Temperatur | sensor |  | Küche | unused",
		"1.0.6: Wippe 3 | button |  | Küche | unused",
		// #135: a channel whose name leaves no room for the device part: an individual address alone stays as
		// it is (no ellipsis), one after a shared name is kept behind the cut name; the channel cut at the end.
		"1.0.8: Taste kurz | button |  | Küche | unused",
		"1.0.8: Taste mit einem außergewöhnlich langen Namen für die | button |  | Küche | unused",
		// #134: a blind by its objects, although its addresses are typed 1.001; the key in "Wohnzimmer".
		"Jalousie Küche | blind | move=5/1/1, stop=5/1/2 | Wohnzimmer | used",
		// #134: named after the switching address, its main role.
		"Licht Esstisch | dimmer | command=5/2/1, levelCommand=5/2/3, level=5/2/4, step=5/2/2 | Wohnzimmer | used",
		// #134: no state, the status object sends on the command address; #133: not the distribution board.
		"Licht Flur | light | command=5/0/3 | - | used",
		// #133: one key in "Werkstatt", one in "Flur".
		"Licht Werkstatt | light | command=5/0/1, state=5/0/2 | - | used",
		// #134: the stop is the 1.008 object "Kurzzeitbetrieb/Stop"; #133: not the distribution board.
		"Rollladen Bad | blind | move=5/1/3, stop=5/1/4, position=5/1/6, positionCommand=5/1/5 | - | used",
		"TEST Aktor Verteiler: Kanal F | light |  | - | unused",
		"TEST Heizungsaktor: Kanal B | sensor |  | - | unused",
		// #135: three devices named "TEST Taster": the name followed by the individual address.
		"TEST Taster 1.0.3: Taste (2) | button |  | Wohnzimmer | unused",
		"TEST Taster 1.0.3: Taste 2 | button |  | Wohnzimmer | unused",
		"TEST Taster 1.0.3: Taste | button |  | Wohnzimmer | unused",
		"TEST Taster 1.0.4: Taste (2) | button |  | Werkstatt | unused",
		"TEST Taster 1.0.4: Taste 2 | button |  | Werkstatt | unused",
		"TEST Taster 1.0.4: Taste | button |  | Werkstatt | unused",
		"TEST Taster 1.0.4: Temperatur | sensor |  | Werkstatt | unused",
		"TEST Taster 1.0.4: Wippe 3 | button |  | Werkstatt | unused",
		"TEST Taster 1.0.5: Taste (2) | button |  | Flur | unused",
		"TEST Taster 1.0.5: Taste 2 | button |  | Flur | unused",
		"TEST Taster 1.0.5: Taste | button |  | Flur | unused",
		"TEST Taster 1.0.5: Temperatur | sensor |  | Flur | unused",
		"TEST Taster 1.0.5: Wippe 3 | button |  | Flur | unused",
		// #135: a name too long: the device part shortened once for the device, to fit its longest
		// channel ("Taste (2)", "Temperatur"), at a word boundary with an ellipsis.
		"TEST Taster mit einem sehr langen Namen, länger…: Taste (2) | button |  | Küche | unused",
		"TEST Taster mit einem sehr langen Namen, länger…: Taste 1 | button |  | Küche | unused",
		"TEST Taster mit einem sehr langen Namen, länger…: Taste 2 | button |  | Küche | unused",
		"TEST Taster mit einem sehr langen Namen, länger…: Taste | button |  | Küche | unused",
		"TEST Taster mit einem sehr langen Namen, länger…: Temperatur | sensor |  | Küche | unused",
		"TEST Taster mit einem sehr langen Namen, länger…: Wippe 3 | button |  | Küche | unused",
		// #135: two devices of the same name with a channel too long for it: the name cut, the address kept.
		"TEST… 1.0.10: Taste kurz | button |  | Küche | unused",
		"TEST… 1.0.10: Taste mit einem außergewöhnlich langen Namen | button |  | Küche | unused",
		"TEST… 1.0.9: Taste kurz | button |  | Küche | unused",
		"TEST… 1.0.9: Taste mit einem außergewöhnlich langen Namen f | button |  | Küche | unused",
		// #133: measured by the key in "Wohnzimmer", received by the heating actuator in the board.
		"Temperatur Wohnzimmer | sensor | value=5/3/1 | Wohnzimmer | used");

	public void testWiring() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);

			FunctionProposal.Run run = run(project);
			assertEquals(EXPECTED, TestFunctionLinks.table(project));
			assertEquals(39, run.added().size());
			assertEquals(6, run.addedInUse().size());
			assertEquals(33, run.addedUnused().size());
			for (TLObject function : run.added()) {
				String name = TestFunctionProposal.name(function);
				// Never the catalog text of the product ("TEST Taster Verdrahtung (künstliche Testdaten)").
				assertFalse(name, name.contains("künstlich") || name.contains("Verdrahtung"));
				assertTrue(name, bytes(name) <= 63);
			}
			Resources resources = Resources.getInstance();
			String blindWarning = resources.getString(
				I18NConstants.TYPE_CONTRADICTS_OBJECT__ADDRESS_ADDRESS_TYPE_OBJECT_OBJECT_TYPE.fill(
					DatapointNames.name(address(project, "5/1/1")), "1.001", "Kanal C: Jalousie Auf/Ab", "1.008"));
			String lightWarning = resources.getString(I18NConstants.STATUS_ON_COMMAND__OBJECT_ADDRESS.fill(
				"Kanal B: Status", DatapointNames.name(address(project, "5/0/3"))));
			assertEquals(Map.of("Jalousie Küche", List.of(blindWarning), "Licht Flur", List.of(lightWarning)),
				warnings(run));

			// The notification: the unused functions apart from those in use, then the warnings, in the order
			// of the channels.
			assertEquals(resources.getString(I18NConstants.PROPOSAL_ADDED__COUNT.fill(Integer.valueOf(6))) + " "
				+ resources.getString(I18NConstants.PROPOSAL_ADDED_UNUSED__COUNT.fill(Integer.valueOf(33))) + "\n"
				+ resources.getString(I18NConstants.PROPOSAL_WARNING__FUNCTION_WARNING.fill("Licht Flur", lightWarning))
				+ "\n"
				+ resources.getString(I18NConstants.PROPOSAL_WARNING__FUNCTION_WARNING.fill("Jalousie Küche", blindWarning)),
				ProposeFunctionsAction.summary(run));

			// Running again adds nothing; the warnings stay.
			FunctionProposal.Run again = run(project);
			assertEquals(List.of(), again.added());
			assertEquals(warnings(run), warnings(again));

			// The second key of the workshop light moved to "Werkstatt": two senders there.
			TLObject workshop = (TLObject) device(project, "1.0.4").tValueByName("location");
			TestFunctionProposal.update(device(project, "1.0.5"), "location", workshop);
			deleteFunctions(project);
			run(project);
			assertEquals("Werkstatt", room(project, "Licht Werkstatt"));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static String room(TLObject project, String name) {
		for (TLObject function : FunctionProposal.functions(project)) {
			if (name.equals(TestFunctionProposal.name(function))) {
				Object room = function.tValueByName("room");
				return room == null ? "-" : String.valueOf(((TLObject) room).tValueByName("name"));
			}
		}
		throw new AssertionError("No function " + name);
	}

	/**
	 * The warnings of the run by the name of their function, in the user's language.
	 */
	private static Map<String, List<String>> warnings(FunctionProposal.Run run) {
		Map<String, List<String>> result = new TreeMap<>();
		run.warnings().forEach((function, warnings) -> {
			List<String> texts = new ArrayList<>();
			for (ResKey warning : warnings) {
				texts.add(Resources.getInstance().getString(warning));
			}
			result.put(TestFunctionProposal.name(function), texts);
		});
		return result;
	}

	static int bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8).length;
	}

	private static FunctionProposal.Run run(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			FunctionProposal.Run result = FunctionProposal.run(project);
			tx.commit();
			return result;
		}
	}

	private static void deleteFunctions(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			List<TLObject> functions = FunctionProposal.functions(project);
			project.tUpdateByName("functions", List.of());
			functions.forEach(TLObject::tDelete);
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionWiring.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
