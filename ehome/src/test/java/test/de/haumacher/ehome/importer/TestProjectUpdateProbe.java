/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.roundtrip.KnxprojDiff;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ImportEtsProjectJob;
import de.haumacher.ehome.etsImport.UpdateFromEtsJob;
import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ProjectParts;
import de.haumacher.ehome.runtime.IdRenames;
import de.haumacher.ehome.runtime.RuleFunctions;

/**
 * Probe of the update of a project from ETS (haumacher/ehome #125): an update forth and back again
 * restores the first state, and an update with the very file a project was imported from changes
 * nothing, also for projects with constructs the update fixtures do not have (dynamic modules,
 * ETS 6 channel instances, building parts with devices).
 */
@SuppressWarnings("javadoc")
public class TestProjectUpdateProbe extends BasicTestCase {

	private static final String BASE = "src/test/fixtures/TestUpdateBase.knxproj";

	private static final String NEW = "src/test/fixtures/TestUpdateNew.knxproj";

	private static final String PROJECT_TYPE = "ehome.project:Project";

	/**
	 * Base, then the new file, then the base again: the project exports as a fresh import of the base,
	 * the group address that all three have is still the same object with its rule, and a further
	 * update with the base changes nothing.
	 */
	public void testForthAndBack() throws Exception {
		List<TLObject> before = instances(PROJECT_TYPE);
		List<TLObject> projects = new ArrayList<>();
		try {
			TLObject project = importProject(BASE, before, projects);
			TLObject ga3 = knx(project, "P-0125-0_GA-3");
			TLObject trigger;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject rule = RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				rule.tUpdateByName("identifier", "probe");
				rule.tUpdateByName("name", ResKey.text("Probe"));
				TLObject reaction = RuleFunctions.create(rule, "reactions", type("ehome.runtime:Reaction"));
				trigger = RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.tUpdateByName("datapoint", ga3);
				tx.commit();
			}

			update(project, NEW);
			assertSame(ga3, knx(project, "P-0125-0_GA-3"));
			update(project, BASE);
			assertSame(ga3, knx(project, "P-0125-0_GA-3"));
			assertSame(ga3, trigger.tValueByName("datapoint"));

			TLObject fresh = importProject(BASE, before, projects);
			List<KnxprojDiff.Difference> differences = new KnxprojDiff(new KnxprojDiff.Rules())
				.compare(KnxprojDiff.unzip(export(fresh)), KnxprojDiff.unzip(export(project)));
			assertEquals(KnxprojDiff.summary(differences, 10), List.of(), differences);

			String dump = dump(project);
			assertNoChanges(update(project, BASE));
			assertEquals(dump, dump(project));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * An update with the file a project was imported from reports no changes and leaves every stored
	 * value of the project as it was.
	 */
	public void testSameFile() throws Exception {
		for (String file : List.of(
				"src/test/fixtures/TestDetails.knxproj",
				"src/test/fixtures/TestEts6.knxproj",
				"src/test/fixtures/TestModules.knxproj")) {
			List<TLObject> before = instances(PROJECT_TYPE);
			List<TLObject> projects = new ArrayList<>();
			try {
				TLObject project = importProject(file, before, projects);
				String dump = dump(project);
				assertNoChanges(update(project, file));
				assertEquals(file, dump, dump(project));
				assertEquals(file, List.of(project), SharedData.newProjects(before));
			} finally {
				SharedData.deleteProjects(projects);
			}
		}
	}

	private static void assertNoChanges(Map<?, ?> report) {
		List<String> messages = new ArrayList<>();
		for (Object message : (List<?>) report.get(ImportEtsProjectJob.MESSAGES)) {
			messages.add(String.valueOf(((Map<?, ?>) message).get(ImportEtsProjectJob.MESSAGE)));
		}
		assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("no changes")));
	}

	private static TLObject importProject(String file, List<TLObject> before, List<TLObject> projects) throws Exception {
		importSample(file);
		List<TLObject> created = SharedData.newProjects(before);
		created.removeAll(projects);
		projects.addAll(created);
		assertEquals(1, created.size());
		return created.get(0);
	}

	private static Map<?, ?> update(TLObject project, String file) throws Exception {
		byte[] content = Files.readAllBytes(Path.of(file));
		return (Map<?, ?>) new RecordingJobMonitor().run(new UpdateFromEtsJob(null, null),
			List.of(BinaryDataFactory.createBinaryData(content, "application/zip", Path.of(file).getFileName().toString()),
				project));
	}

	private static TLObject knx(TLObject project, String extId) {
		for (TLObject obj : ProjectParts.objects(project)) {
			if (extId.equals(ExtIds.extId(obj))) {
				return obj;
			}
		}
		throw new AssertionError("No " + extId);
	}

	private static TLClass type(String name) {
		return (TLClass) TLModelUtil.findType(name);
	}

	private static byte[] export(TLObject project) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		KnxprojExporter.export(project, out);
		return out.toByteArray();
	}

	/**
	 * Every stored value of every object of the KNX part, by external ID or, without one, by object.
	 */
	private static String dump(TLObject project) {
		Map<String, String> result = new TreeMap<>();
		for (TLObject obj : ProjectParts.objects(project)) {
			StringBuilder values = new StringBuilder();
			for (TLStructuredTypePart part : ((TLStructuredType) obj.tType()).getAllParts()) {
				if (ProjectParts.isStored(part)) {
					Object value = obj.tValue(part);
					values.append(part.getName()).append('=')
						.append(value instanceof Collection<?> collection ? new ArrayList<>(collection) : value).append(';');
				}
			}
			result.put(obj.tId() + " " + ExtIds.extId(obj), values.toString());
		}
		return result.toString();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProjectUpdateProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			IdRenames.Module.INSTANCE));
	}

}
