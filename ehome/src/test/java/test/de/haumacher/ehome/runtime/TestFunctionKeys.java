/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.apply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The keys of a push-button sensor sending on the inputs of an actuator (haumacher/ehome #92,
 * <code>src/test/fixtures/TestFunctionKeys.knxproj</code>): a key sending on the address an actuator
 * channel receives as its first is linked with the channel into one function of the actuator's
 * kind, named after the address (haumacher/ehome #98), and gives no button of its own. A key sending
 * on the central object of the actuator (which gets no function) or on an address only a status LED
 * receives stays a button. The push-button sensor's temperature, connected to no address, is an
 * unused sensor.
 */
@SuppressWarnings("javadoc")
public class TestFunctionKeys extends BasicTestCase {

	public void testKeys() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestFunctionKeys.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);

			// "Taste 1" switches the light, "Taste 2" the dimmer, "Wippe 3" moves and stops the blind:
			// linked with the actuator's channels, no button for them; "Taste 4" (central) and "Taste 5"
			// (status LED only) are buttons.
			assertEquals(2, FunctionProposal.propose(project).stream().filter(p -> "button".equals(p.kind())).count());
			List<TLObject> added = apply(project);
			assertEquals(List.of(
				"blind move=2/1/1, stop=2/1/2, position=2/1/4, positionCommand=2/1/3, slat=2/1/6, slatCommand=2/1/5",
				"button press=1/0/0",
				"button press=1/1/7",
				"dimmer levelCommand=1/1/4, level=1/1/5, command=1/1/2, step=1/1/3",
				"light command=1/1/1, state=1/1/8",
				"sensor unused"), summary(project));
			assertEquals(6, added.size());
			// Named after the address of the main role.
			assertEquals("Licht", TestFunctionProposal.name(function(project, "light", "1/1/1")));
			assertEquals("Dimmer Wert", TestFunctionProposal.name(function(project, "dimmer", "1/1/4")));
			assertEquals("Jalousie auf/ab", TestFunctionProposal.name(function(project, "blind", "2/1/1")));
			assertEquals("Zentral", TestFunctionProposal.name(function(project, "button", "1/0/0")));
			TLObject key5 = function(project, "button", "1/1/7");
			assertEquals("Melder", TestFunctionProposal.name(key5));
			for (TLObject function : added) {
				assertNull(FunctionProposal.redundancy(function));
			}

			// Again: nothing added.
			assertEquals(List.of(), apply(project));

			// The light dismissed: its key is no button either, it still operates the actuator's channel.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				FunctionProposal.dismiss(function(project, "light", "1/1/1"));
				tx.commit();
			}
			assertEquals(List.of(), apply(project));
			assertEquals(5, FunctionProposal.functions(project).size());

			// A function made by hand commanding the address of "Taste 5": the existing button stays, with
			// a warning naming the address, the role and the function; the proposal adds and removes
			// nothing.
			TLObject own;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				own = TestFunctionProposal.create("ehome.runtime:Function");
				own.tUpdateByName("name", FunctionProposal.text(Map.of("de", "Melderlicht", "en", "Detector light")));
				own.tUpdateByName("kind", FunctionProposal.classifier("light"));
				own.tUpdateByName("command", address(project, "1/1/7"));
				List<TLObject> functions = FunctionProposal.functions(project);
				functions.add(own);
				project.tUpdateByName("functions", functions);
				tx.commit();
			}
			String warning = FunctionProposal.redundancy(key5);
			assertEquals("1/1/7 Melder is the datapoint \"Command\" of the function \"" + TestFunctionProposal.name(own)
				+ "\": the controller offers what the button does there already. Delete the button; the proposal does not"
				+ " add it again.", warning);
			assertNull(FunctionProposal.redundancy(own));
			assertEquals(List.of(), apply(project));
			assertEquals(6, FunctionProposal.functions(project).size());
			assertTrue(key5.tValid());

			// The button gone (not dismissed): the existing light keeps the proposal from adding it again.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				List<TLObject> functions = FunctionProposal.functions(project);
				functions.remove(key5);
				project.tUpdateByName("functions", functions);
				key5.tDelete();
				tx.commit();
			}
			assertEquals(List.of(), apply(project));

			// Without the light, the key is a button again.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				List<TLObject> functions = FunctionProposal.functions(project);
				functions.remove(own);
				project.tUpdateByName("functions", functions);
				own.tDelete();
				tx.commit();
			}
			List<TLObject> again = apply(project);
			assertEquals(1, again.size());
			assertEquals("Melder", TestFunctionProposal.name(again.get(0)));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static TLObject function(TLObject project, String kind, String main) {
		for (TLObject function : FunctionProposal.functions(project)) {
			if (kind.equals(FunctionProposal.kind(function))
				&& function.tValueByName(FunctionKinds.mainRole(kind)) instanceof TLObject address
				&& TestFunctionProposal.text(address).equals(main)) {
				return function;
			}
		}
		throw new AssertionError("No " + kind + " on " + main);
	}

	/**
	 * The kinds and role bindings of the project's functions, the roles in the order of their kind
	 * ("unused" for an unused function), sorted.
	 */
	static List<String> summary(TLObject project) {
		List<String> result = new ArrayList<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			List<String> roles = new ArrayList<>();
			for (String role : FunctionKinds.roles(FunctionProposal.kind(function))) {
				if (function.tValueByName(role) instanceof TLObject address) {
					roles.add(role + "=" + RuntimeImage.threeLevel(((GA) address.tValueByName("address")).getValue()));
				}
			}
			result.add(FunctionProposal.kind(function) + " "
				+ (FunctionProposal.unused(function) ? "unused" : String.join(", ", roles)));
		}
		Collections.sort(result);
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionKeys.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
