/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.create;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.name;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.FunctionElement;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ProposeFunctionsAction;

/**
 * The function proposal with the option to overwrite the existing proposed functions
 * (haumacher/ehome #177), on the KNX test system (<code>samples/KNX-Testsystem.knxproj</code>).
 *
 * <p>
 * The functions are proposed, then the user makes a function with "New function", dismisses the
 * button "Taste6", puts "KanalA" on a control page, edits the name of "Taste2" and unbinds the state
 * of "KanalC"; the group addresses of channels A and B are renamed ("Licht Esstisch", "Licht Flur"),
 * and the link of channel A's scene input to its group address (0/0/36) is removed, so that the
 * proposal no longer gives the two scene functions.
 * </p>
 *
 * <p>
 * A run without the option changes no name. A run with it renames the lights after their new
 * addresses and "Mein Taster" back to "Taste2", restores the state of "KanalC", keeps the objects (the
 * page element still refers to the same function), regenerates the identifiers of the renamed ones,
 * leaves the function made by hand untouched and the dismissed button dismissed, and lists the scene
 * functions as no longer proposed without deleting them. The confirmation counts the functions it
 * overwrites; a second run with the option overwrites nothing.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFunctionOverwrite extends BasicTestCase {

	public void testOverwrite() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			Project project = (Project) projects.get(0);
			run(project, false);
			Map<String, Function> byName = byName(project);
			Function kanalA = byName.get("KanalA");
			Function kanalB = byName.get("KanalB");
			Function kanalC = byName.get("KanalC");
			Function taste2 = byName.get("Taste2");
			Function taste6 = byName.get("Taste6");
			List<TLObject> scenes = FunctionProposal.functions(project).stream()
				.filter(f -> "scene".equals(FunctionProposal.kind(f))).toList();
			assertEquals(2, scenes.size());
			Object stateC = kanalC.getState();
			assertNotNull(stateC);
			Object identifierC = kanalC.getIdentifier();

			// The user's part.
			Function own;
			FunctionElement element;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				own = (Function) create("ehome.runtime:Function");
				own.setNameKey(FunctionProposal.text(Map.of("de", "Klingel", "en", "Klingel")));
				own.setIdentifier("klingel");
				own.setKind(FunctionProposal.classifier("button"));
				own.setPress((GroupAddress) address(project, "0/0/6"));
				List<Function> functions = new ArrayList<>(project.getFunctions());
				functions.add(own);
				project.setFunctions(functions);

				element = (FunctionElement) create("ehome.runtime:FunctionElement");
				element.setFunction(kanalA);

				FunctionProposal.dismiss(taste6);

				taste2.setNameKey(FunctionProposal.text(Map.of("de", "Mein Taster", "en", "Mein Taster")));
				kanalC.setState(null);
				tx.commit();
			}
			rename((GroupAddress) address(project, "0/0/34"), "Licht Esstisch");
			rename((GroupAddress) address(project, "0/0/40"), "Licht Flur");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (TLObject connection : GroupAddressConnections.connections(address(project, "0/0/36"))) {
					DeviceSettings.disconnect(connection);
				}
				tx.commit();
			}
			List<String> names = names(project);

			// Without the option: no name changes, nothing is overwritten.
			FunctionProposal.Run plain = run(project, false);
			assertEquals(List.of(), plain.overwritten());
			assertEquals(List.of(), plain.renamed());
			assertEquals(List.of(), plain.stale());
			assertEquals(names, names(project));
			assertNull(kanalC.getState());

			// The confirmation counts what the option overwrites, without changing anything.
			Set<TLObject> overwritten = Set.of(kanalA, kanalB, kanalC, taste2);
			assertEquals(overwritten, Set.copyOf(FunctionProposal.overwrite(project).differing()));
			String confirmation = ProposeFunctionsAction.confirmation(project);
			assertTrue(confirmation, confirmation.startsWith(Resources.getInstance()
				.getString(I18NConstants.PROPOSAL_OVERWRITE_CONFIRM__COUNT.fill(Integer.valueOf(4))) + "\n"));
			assertEquals(names, names(project));

			// With the option.
			FunctionProposal.Run run = ProposeFunctionsAction.apply(project, true);
			assertEquals(List.of(), run.added());
			assertEquals(overwritten, Set.copyOf(run.overwritten()));
			Map<String, String> renames = new HashMap<>();
			for (FunctionProposal.Rename rename : run.renamed()) {
				renames.put(text(rename.before()), text(rename.after()));
			}
			assertEquals(Map.of("KanalA", "Licht Esstisch", "KanalB", "Licht Flur", "Mein Taster", "Taste2"), renames);
			assertEquals("Licht Esstisch", name(kanalA));
			assertEquals("licht-esstisch", kanalA.getIdentifier());
			assertEquals("Licht Flur", name(kanalB));
			assertEquals("licht-flur", kanalB.getIdentifier());
			assertEquals("Taste2", name(taste2));
			assertEquals("taste2", taste2.getIdentifier());
			// Not renamed: the identifier stays; the state is bound again.
			assertEquals(identifierC, kanalC.getIdentifier());
			assertSame(stateC, kanalC.getState());
			// The same objects: the page still shows the light.
			assertTrue(kanalA.tValid());
			assertSame(kanalA, element.getFunction());
			// Made by hand: untouched.
			assertEquals("Klingel", name(own));
			assertEquals("klingel", own.getIdentifier());
			assertSame(address(project, "0/0/6"), own.getPress());
			// Dismissed: not back, the dismissal stays.
			assertFalse(names(project).contains("Taste6"));
			assertEquals(1, project.getDismissedProposals().size());
			// No longer proposed: listed, kept.
			assertEquals(scenes, run.stale());
			for (TLObject scene : scenes) {
				assertTrue(scene.tValid());
				assertTrue(FunctionProposal.functions(project).contains(scene));
			}
			String summary = ProposeFunctionsAction.summary(run);
			assertTrue(summary, summary.contains("KanalA → Licht Esstisch"));
			assertTrue(summary, summary.contains(name(scenes.get(0))));

			// Nothing left to overwrite.
			assertEquals(List.of(), FunctionProposal.overwrite(project).differing());
			FunctionProposal.Run again = run(project, true);
			assertEquals(List.of(), again.added());
			assertEquals(List.of(), again.updated());
			assertEquals(List.of(), again.overwritten());
			assertEquals(scenes, again.stale());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static FunctionProposal.Run run(TLObject project, boolean overwrite) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			FunctionProposal.Run result = FunctionProposal.run(project, overwrite);
			tx.commit();
			return result;
		}
	}

	private static Map<String, Function> byName(Project project) {
		Map<String, Function> result = new HashMap<>();
		for (Function function : project.getFunctions()) {
			result.put(name(function), function);
		}
		return result;
	}

	private static List<String> names(Project project) {
		List<String> result = new ArrayList<>();
		for (Function function : project.getFunctions()) {
			result.add(name(function) + " " + function.getIdentifier());
		}
		result.sort(null);
		return result;
	}

	private static void rename(GroupAddress address, String name) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			address.setName(name);
			tx.commit();
		}
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionOverwrite.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
