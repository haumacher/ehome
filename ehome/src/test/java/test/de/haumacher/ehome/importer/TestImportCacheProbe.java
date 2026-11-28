/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

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

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.operational.Parameter;

/**
 * Probe of the remembered state of the re-import (haumacher/ehome #213): a part of a known program
 * edited between two imports of the same file, with an import of another program in between, keeps
 * the user's edit, exists once, and the known program is still skipped.
 */
@SuppressWarnings("javadoc")
public class TestImportCacheProbe extends BasicTestCase {

	private static final String DETAILS = "src/test/fixtures/TestDetails.knxproj";

	private static final String DYNAMIC = "src/test/fixtures/TestDynamic.knxproj";

	private static final String PROGRAM = "ehome.operational:Program";

	public void testEditedPartBetweenImports() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		Parameter edited = null;
		ResKey original = null;
		try {
			importProject(DETAILS);
			importProject(DETAILS); // the second import finds the known program
			String programId = SharedData.ids(DETAILS, "M-0008/M-0008_A-.*", "ApplicationProgram").iterator().next();

			Parameter victim = null;
			for (TLObject obj : instances("ehome.operational:Parameter")) {
				Parameter parameter = (Parameter) obj;
				if (parameter.getExtId() != null && parameter.getExtId().startsWith(programId + "_")) {
					victim = parameter;
					break;
				}
			}
			assertNotNull("A parameter of " + programId, victim);
			String victimId = victim.getExtId();
			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			ResKey userText = ResKey.text("Edited by the user");
			edited = victim;
			original = victim.getDescription();
			try (Transaction tx = kb.beginTransaction()) {
				victim.setDescription(userText);
				tx.commit();
			}

			importProject(DYNAMIC);
			Importer again = importProject(DETAILS);

			assertEquals(List.of(), again.getCreated(PROGRAM));
			assertEquals(1, again.getKnown(PROGRAM).size());
			long count = instances("ehome.operational:Parameter").stream()
				.filter(obj -> victimId.equals(((Parameter) obj).getExtId())).count();
			assertEquals("Parameter " + victimId + " once.", 1, count);
			assertTrue(victim.tValid());
			for (Locale locale : List.of(Locale.GERMAN, Locale.ENGLISH)) {
				assertEquals("The user's edit survives the re-import (" + locale + ").", "Edited by the user",
					Resources.getInstance(locale).getString(victim.getDescription()));
			}
			SharedData.assertOnce(PROGRAM, List.of(programId));
		} finally {
			if (edited != null && edited.tValid()) {
				// The program is shared: later tests read its texts.
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					edited.setDescription(original);
					tx.commit();
				}
			}
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private static Importer importProject(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		return importer;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestImportCacheProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
