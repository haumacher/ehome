/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.commit;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.Revision;
import com.top_logic.knowledge.wrap.WrapperHistoryUtils;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ImportEtsProjectJob;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ProposeFunctionsAction;

/**
 * The commit messages E-Home leaves in the change log (haumacher/ehome #143): the ETS import of a
 * project and a run of the function proposal, read back from the revisions they committed, in English
 * and German.
 */
@SuppressWarnings("javadoc")
public class TestCommitMessages extends BasicTestCase {

	private static final String SAMPLE = "samples/Test.knxproj";

	public void testImportAndProposal() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			byte[] file = Files.readAllBytes(new File(SAMPLE).toPath());
			new RecordingJobMonitor().run(new ImportEtsProjectJob(null, null),
				List.of(BinaryDataFactory.createBinaryData(file, "application/zip", "Test.knxproj")));
			List<TLObject> created = SharedData.newProjects(before);
			assertEquals(1, created.size());
			TLObject project = created.get(0);
			assertEquals("Test", project.tValueByName("name"));

			ResKey importMessage = WrapperHistoryUtils.getCreateRevision(project).getLog();
			assertEquals("ETS import: Test", text(importMessage, Locale.ENGLISH));
			assertEquals("ETS-Import: Test", text(importMessage, Locale.GERMAN));

			FunctionProposal.Run run = ProposeFunctionsAction.apply(project);
			List<TLObject> added = new ArrayList<>(run.addedInUse());
			added.addAll(run.addedUnused());
			assertFalse("The proposal adds functions to the sample.", added.isEmpty());
			Revision proposal = WrapperHistoryUtils.getCreateRevision(added.get(0));
			assertEquals("Functions proposed for Test: " + run.addedInUse().size() + " new, "
				+ run.addedUnused().size() + " unused, " + run.updated().size() + " updated",
				text(proposal.getLog(), Locale.ENGLISH));
			assertEquals("Funktionen für Test vorgeschlagen: " + run.addedInUse().size() + " neu, "
				+ run.addedUnused().size() + " unbenutzt, " + run.updated().size() + " ergänzt",
				text(proposal.getLog(), Locale.GERMAN));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private static String text(ResKey message, Locale locale) {
		return Resources.getInstance(locale).getString(message);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestCommitMessages.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
