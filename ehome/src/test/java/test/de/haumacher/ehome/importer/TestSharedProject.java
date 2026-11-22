/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;

import junit.framework.AssertionFailedError;
import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.KnowledgeBaseException;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.logical.EhomeLogicalFactory;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.EhomeProjectFactory;
import de.haumacher.ehome.model.project.Project;

/**
 * The project shared by the tests that only read it (haumacher/ehome #213, see
 * {@link SharedData#sharedProject(String)}): imported once, and guarded against every kind of
 * change.
 */
@SuppressWarnings("javadoc")
public class TestSharedProject extends BasicTestCase {

	private static final String SAMPLE = "samples/Test.knxproj";

	/**
	 * The same project on every call, without a further import.
	 */
	public void testOnce() throws Exception {
		TLObject project = SharedData.sharedProject(SAMPLE);
		int projects = instances("ehome.project:Project").size();
		assertSame(project, SharedData.sharedProject(SAMPLE));
		assertEquals(projects, instances("ehome.project:Project").size());
	}

	/**
	 * A commit that changes, creates, links or deletes an object of the shared project, or deletes the
	 * project, is refused and counted; the project stays as imported. A commit elsewhere passes.
	 */
	public void testGuard() throws Exception {
		Project project = (Project) SharedData.sharedProject(SAMPLE);
		GroupAddress address = (GroupAddress) SharedData.composition(project).stream()
			.filter(GroupAddress.class::isInstance).findFirst().get();
		String name = address.getName();
		int types = address.getDatapointTypes().size();
		int parts = SharedData.composition(project).size();
		TLObject type = instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> !address.getDatapointTypes().contains(t)).findFirst().get();
		int vetoes = SharedData.sharedProjectVetoes();

		assertRefused(() -> address.setName("Changed"));
		assertRefused(() -> address.addDatapointType(type));
		assertRefused(() -> project.addGroupAddresse(EhomeLogicalFactory.getInstance().createGroupAddress()));
		assertDeletionRefused(project);
		assertRefused(() -> project.tDelete());
		assertEquals(vetoes + 5, SharedData.sharedProjectVetoes());

		assertTrue(project.tValid());
		assertEquals(name, address.getName());
		assertEquals(types, address.getDatapointTypes().size());
		assertEquals(parts, SharedData.composition(project).size());

		// Elsewhere: a project of its own is created and deleted.
		List<TLObject> before = instances("ehome.project:Project");
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			Project own = EhomeProjectFactory.getInstance().createProject();
			own.setName("Not shared");
			tx.commit();
		}
		SharedData.deleteProjects(SharedData.newProjects(before));
		assertEquals(vetoes + 5, SharedData.sharedProjectVetoes());
	}

	/**
	 * A test that changed the shared project fails, also if it caught the failed commit.
	 */
	public void testFailsTheTest() throws Throwable {
		SharedProjectTestCase changing = new SharedProjectTestCase() {
			@Override
			protected void runTest() throws Throwable {
				GroupAddress address = (GroupAddress) SharedData.composition(sharedProject(SAMPLE)).stream()
					.filter(GroupAddress.class::isInstance).findFirst().get();
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					address.setName("Changed");
					tx.commit();
				} catch (KnowledgeBaseException ex) {
					// Swallowed: the test must fail anyway.
				}
			}
		};
		changing.setName("changing");
		try {
			changing.runBare();
		} catch (AssertionFailedError ex) {
			assertTrue(ex.getMessage(), ex.getMessage().startsWith("The test changed a shared project: "));
			return;
		}
		fail("A test changing the shared project must fail.");
	}

	/**
	 * Deletes a part of the given project whose deletion the model allows (a part others refer to
	 * mandatorily is refused by the model before the guard), which the guard refuses.
	 */
	private static void assertDeletionRefused(TLObject project) {
		for (TLObject part : SharedData.composition(project)) {
			if (part == project) {
				continue;
			}
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				part.tDelete();
				tx.commit();
			} catch (KnowledgeBaseException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("shared project"));
				return;
			} catch (RuntimeException ex) {
				// Refused by the model: another part.
				continue;
			}
			fail("Deletion of a part of the shared project not refused.");
		}
		fail("No part of the shared project to delete.");
	}

	private static void assertRefused(Runnable change) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			change.run();
			tx.commit();
		} catch (KnowledgeBaseException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("shared project"));
			return;
		}
		fail("Change of the shared project not refused.");
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestSharedProject.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
