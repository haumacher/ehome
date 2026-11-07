/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;

/**
 * Probe of the linked function proposal (haumacher/ehome #98) on two projects besides its own
 * fixture: <code>TestDetails.knxproj</code> (keys of a device placed in a room, an inactive object,
 * untyped addresses, keys connected to nothing) and <code>TestDynamic.knxproj</code> (a channel shown
 * by a parameter). In each, a second run adds no function (no duplicate of a linked or an unused
 * one) and renames none; a name the user changed in German only survives a third run, also where
 * its English text is still the generated one.
 */
@SuppressWarnings("javadoc")
public class TestFunctionLinksProbe extends BasicTestCase {

	public void testDetails() throws Exception {
		probe("src/test/fixtures/TestDetails.knxproj");
	}

	public void testDynamic() throws Exception {
		probe("src/test/fixtures/TestDynamic.knxproj");
	}

	private void probe(String fixture) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(fixture);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			List<TLObject> added = TestFunctionProposal.apply(project);
			assertFalse(fixture, added.isEmpty());
			List<String> table = TestFunctionLinks.table(project);

			// No two functions for one channel and kind, nor for one main role and kind.
			Set<String> channels = new HashSet<>();
			Set<String> mains = new HashSet<>();
			for (TLObject function : added) {
				String kind = FunctionProposal.kind(function);
				assertTrue(table.toString(), channels.add(kind + " " + function.tValueByName("device") + " "
					+ function.tValueByName("channel") + " " + function.tValueByName("scene")));
				if (!FunctionProposal.unused(function)) {
					assertTrue(table.toString(), mains.add(kind + " "
						+ function.tValueByName(FunctionKinds.mainRole(kind)) + " "
						+ function.tValueByName("scene")));
				}
			}

			// A second run adds and renames nothing.
			Map<TLObject, Object> names = new HashMap<>();
			for (TLObject function : added) {
				names.put(function, function.tValueByName("name"));
			}
			assertEquals(new FunctionProposal.Run(List.of(), List.of()), run(project));
			assertEquals(table, TestFunctionLinks.table(project));
			for (TLObject function : added) {
				assertEquals(names.get(function), function.tValueByName("name"));
			}

			// The user renames each function in German only; a third run keeps every name.
			Map<TLObject, String> english = new HashMap<>();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (TLObject function : added) {
					String en = text(function, Locale.ENGLISH);
					english.put(function, en);
					function.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Umbenannt " + en)
						.add(Locale.ENGLISH, en).build());
				}
				tx.commit();
			}
			assertEquals(new FunctionProposal.Run(List.of(), List.of()), run(project));
			for (TLObject function : new ArrayList<>(added)) {
				assertEquals("Umbenannt " + english.get(function), text(function, Locale.GERMAN));
				assertEquals(english.get(function), text(function, Locale.ENGLISH));
			}
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

	private static String text(TLObject function, Locale locale) {
		return Resources.getInstance(locale).getString((ResKey) function.tValueByName("name"));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionLinksProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
