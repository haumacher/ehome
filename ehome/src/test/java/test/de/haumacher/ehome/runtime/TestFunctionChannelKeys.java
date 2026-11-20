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

import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.runtime.FunctionProposal;

/**
 * The function of a channel named by its objects is recognized whatever language its key was written
 * in (haumacher/ehome #211; <code>src/test/fixtures/TestFunctionChannelKeys.knxproj</code>, see
 * <code>make-fixtures.py</code>).
 *
 * <p>
 * The key of such a channel (<code>object:&lt;name&gt;</code>) is the object's name in the fallback
 * language, a text of the shared program. A project imported from another installation, or proposed
 * before the program's texts changed, stores the name in another language: in the house project the
 * functions said "object:Button 3" where the program named the object "Taste 3" in every language, and
 * a new proposal added each key channel again as an unused function next to the existing one (after its
 * device had been renamed, under another name). Here the key's object is "Taste 3" in German and
 * "Button 3" in English; the function proposed for it is given the key in the other language and its
 * device another name: the proposal adds nothing, and a dismissal keyed in the other language keeps the
 * channel away as well.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFunctionChannelKeys extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestFunctionChannelKeys.knxproj";

	public void testChannelKeyInOtherLanguage() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			List<TLObject> added = TestFunctionProposal.apply(project);
			assertEquals(1, added.size());
			Function function = (Function) added.get(0);
			assertTrue(FunctionProposal.unused(function));
			String key = function.getChannel();
			String other = other(key);

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				function.setChannel(other);
				function.getDevice().tUpdateByName("title", "TEST Taster umbenannt");
				tx.commit();
			}
			assertEquals(List.of(), TestFunctionProposal.apply(project));
			assertEquals(1, FunctionProposal.functions(project).size());

			// Dismissed under the key in the other language: the channel stays away.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertNotNull(FunctionProposal.dismiss(function));
				tx.commit();
			}
			assertEquals(List.of(), TestFunctionProposal.apply(project));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/** The key of the channel in the language other than the fallback language. */
	private static String other(String key) {
		Locale fallback = ResourcesModule.getInstance().getFallbackLocale();
		assertTrue(key, key.equals("object:Button 3") || key.equals("object:Taste 3"));
		String result = key.equals("object:Button 3") ? "object:Taste 3" : "object:Button 3";
		assertFalse(fallback + ": " + key, result.equals(key));
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionChannelKeys.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
