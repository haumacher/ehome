/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;
import java.util.Locale;

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

import de.haumacher.ehome.runtime.FunctionProposal;

/**
 * Distinct names of the functions in use (haumacher/ehome #144), on
 * <code>src/test/fixtures/TestFunctionNames.knxproj</code> (see <code>make-fixtures.py</code>): lights
 * whose switching addresses all carry the name ETS gives them, "Schalten EIN/AUS".
 *
 * <p>
 * A function is named after the group address of its main role only where no other address of the
 * project an object is connected to has that name ("Licht Esstisch", although an address without
 * objects has it too); else after its channel's own name, where the device gives it one ("Licht Speisekammer", the text parameter of "Kanal E: {{0}}"); else after its room and its
 * channel ("Flur: Kanal C" and "Flur: Kanal D" in the same room, "Küche: Kanal B" in another); where
 * that is the name of another function too (the channels "Kanal A" of two actuators switched from
 * "Wohnzimmer"), after its device and channel, as an unused function is named (haumacher/ehome #135).
 * A second run keeps a name the user edited and renames a function still named as an earlier rule
 * named it (after the generic address).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFunctionNames extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestFunctionNames.knxproj";

	private static final List<String> EXPECTED = List.of(
		// Two lights in the same room.
		"Flur: Kanal C | light | command=9/0/3 | Flur | used",
		"Flur: Kanal D | light | command=9/0/4 | Flur | used",
		"Küche: Kanal B | light | command=9/0/2 | Küche | used",
		// The only connected address of its name.
		"Licht Esstisch | light | command=9/2/1 | Wohnzimmer | used",
		// The channel's own name.
		"Licht Speisekammer | light | command=9/0/5 | Küche | used",
		// "Wohnzimmer: Kanal A" twice: named after the devices, which have the same name.
		"TEST Schaltaktor Namen 1.0.1: Kanal A | light | command=9/0/1, state=9/1/1 | Wohnzimmer | used",
		"TEST Schaltaktor Namen 1.0.2: Kanal A | light | command=9/0/6 | Wohnzimmer | used",
		"TEST Schaltaktor Namen 1.0.2: Kanal C | light |  | - | unused",
		"TEST Schaltaktor Namen 1.0.2: Kanal D | light |  | - | unused",
		// The placeholder of the channel's text parameter left out where the device gives no value.
		"TEST Schaltaktor Namen 1.0.2: Kanal E | light |  | - | unused");

	public void testDistinctNames() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			run(project);
			assertEquals(EXPECTED, TestFunctionLinks.table(project));

			// An earlier proposal named the function after its address; the user named another one.
			TLObject flurC = function(project, "Flur: Kanal C");
			TLObject flurD = function(project, "Flur: Kanal D");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				flurC.tUpdateByName("name", FunctionProposal.text(ResKey.text("Schalten EIN/AUS")));
				flurD.tUpdateByName("name", FunctionProposal.text(ResKey.text("Licht Garderobe")));
				tx.commit();
			}
			FunctionProposal.Run again = run(project);
			assertEquals(List.of(), again.added());
			assertEquals(List.of(flurC), again.updated());
			assertEquals("Flur: Kanal C", TestFunctionProposal.name(flurC));
			assertEquals("Licht Garderobe", TestFunctionProposal.name(flurD));
			assertEquals(EXPECTED.stream().map(line -> line.replace("Flur: Kanal D |", "Licht Garderobe |")).sorted()
				.toList(), TestFunctionLinks.table(project));

			// Nothing changes on a third run.
			FunctionProposal.Run third = run(project);
			assertEquals(List.of(), third.added());
			assertEquals(List.of(), third.updated());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static FunctionProposal.Run run(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			FunctionProposal.Run result = FunctionProposal.run(project);
			tx.commit();
			return result;
		}
	}

	private static TLObject function(TLObject project, String name) {
		for (TLObject function : FunctionProposal.functions(project)) {
			if (name.equals(Resources.getInstance(Locale.GERMAN).getString((ResKey) function.tValueByName("name")))) {
				return function;
			}
		}
		fail("No function " + name + ".");
		return null;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionNames.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
