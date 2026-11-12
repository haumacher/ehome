/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerOwnershipMigration;
import de.haumacher.ehome.connection.ProjectControllers;
import de.haumacher.ehome.runtime.Deployer;

/**
 * The migration of existing controllers into their projects (haumacher/ehome #151,
 * {@link ControllerOwnershipMigration}), on data as it was before: controllers on the LAN held by no
 * project and chosen as deployment target by any number of projects.
 *
 * <ul>
 * <li>One project deploys to a controller without a project: it moves into that project.</li>
 * <li>Two projects deploy to one: it moves into the first by name and is copied, with its token,
 * into the second, which deploys to its copy; the second's deployment names the copy.</li>
 * <li>No project deploys to one: it stays without a project.</li>
 * <li>A project deploys to an enrolled controller of another project: the target is removed, the
 * controller stays where it is.</li>
 * </ul>
 *
 * <p>
 * Runs on empty projects of its own: no import. The migration runs over the whole database, the
 * test asserts its own objects only.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestControllerOwnershipMigration extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	public void testMigration() {
		List<TLObject> projects = new ArrayList<>();
		TLObject orphan = null;
		try {
			TLObject single;
			TLObject shared;
			TLObject enrolled;
			TLObject first;
			TLObject second;
			TLObject alone;
			TLObject foreign;
			TLObject holder;
			TLObject deployment;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				alone = project("Migration 151 alone");
				first = project("Migration 151 first");
				second = project("Migration 151 second");
				holder = project("Migration 151 holder");
				foreign = project("Migration 151 foreign");
				projects.addAll(List.of(alone, first, second, holder, foreign));

				single = controller("Migration single", "192.0.2.11");
				alone.tUpdateByName("controller", single);

				shared = controller("Migration shared", "192.0.2.12");
				shared.tUpdateByName("token", TOKEN);
				shared.tUpdateByName("firmwareVersion", "1.2.3");
				// Created in the order second, first: the first by name gets the original.
				second.tUpdateByName("controller", shared);
				first.tUpdateByName("controller", shared);
				deployment = ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Deployment"));
				deployment.tUpdateByName("controller", shared);
				deployment.tUpdateByName("target", "Migration shared (192.0.2.12:80)");
				second.tUpdateByName("deployments", List.of(deployment));

				orphan = controller("Migration orphan", "192.0.2.13");

				enrolled = controller("Migration enrolled", null);
				enrolled.tUpdateByName("controllerId", "0123456789abcdef0123456789abcdef");
				holder.tUpdateByName("controllers", List.of(enrolled));
				holder.tUpdateByName("controller", enrolled);
				foreign.tUpdateByName("controller", enrolled);
				tx.commit();
			}

			List<String> report;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				report = ControllerOwnershipMigration.migrate();
				tx.commit();
			}

			// One project deploys to it: moved.
			assertSame(alone, single.tContainer());
			assertEquals(List.of(single), ProjectControllers.controllers(alone));
			assertSame(single, alone.tValueByName("controller"));
			assertSame(single, Deployer.controller(alone));
			assertTrue(report.toString(), report.contains(
				"Controller 'Migration single' moved into project 'Migration 151 alone' (haumacher/ehome #151)."));

			// Two projects deploy to it: moved into the first, copied into the second.
			assertSame(first, shared.tContainer());
			assertSame(shared, first.tValueByName("controller"));
			TLObject copy = (TLObject) second.tValueByName("controller");
			assertNotSame(shared, copy);
			assertSame(second, copy.tContainer());
			assertEquals(List.of(copy), ProjectControllers.controllers(second));
			assertEquals("Migration shared", copy.tValueByName("name"));
			assertEquals("192.0.2.12", copy.tValueByName("host"));
			assertEquals(Integer.valueOf(80), copy.tValueByName("port"));
			assertEquals("The copy keeps the token.", TOKEN, copy.tValueByName("token"));
			assertEquals("1.2.3", copy.tValueByName("firmwareVersion"));
			assertSame("The deployment of the second project names its copy.", copy,
				deployment.tValueByName("controller"));
			assertTrue(report.toString(), report.contains(
				"Controller 'Migration shared' copied into project 'Migration 151 second' (haumacher/ehome #151)."));

			// No project deploys to it: stays without a project.
			assertNull(orphan.tContainer());
			assertTrue(orphan.tValid());
			assertTrue(report.toString(), report.stream().anyMatch(line -> line.startsWith(
				"Controller 'Migration orphan' is deployed to by no project and stays without a project")));

			// An enrolled controller of another project: the foreign target is removed.
			assertSame(holder, enrolled.tContainer());
			assertSame(enrolled, holder.tValueByName("controller"));
			assertNull(foreign.tValueByName("controller"));
			assertTrue(ProjectControllers.controllers(foreign).isEmpty());

			// Running it again changes nothing.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				List<String> again = ControllerOwnershipMigration.migrate();
				tx.commit();
				assertFalse(again.toString(), again.stream().anyMatch(line -> line.contains("Migration 151")));
			}
			assertSame(copy, second.tValueByName("controller"));
			assertEquals(List.of(copy), ProjectControllers.controllers(second));
		} finally {
			SharedData.deleteProjects(projects);
			if (orphan != null) {
				SharedData.deleteProjects(List.of(orphan));
			}
		}
	}

	/**
	 * A project deploys to the controller of another project no more: the deployment refuses it with
	 * the reason, and nothing is sent (haumacher/ehome #151).
	 */
	public void testForeignTargetRefused() {
		List<TLObject> projects = new ArrayList<>();
		try {
			TLObject owner;
			TLObject other;
			TLObject controller;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				owner = project("Foreign 151 owner");
				other = project("Foreign 151 other");
				projects.addAll(List.of(owner, other));
				controller = ProjectControllers.create(owner, "Foreign 151", "192.0.2.21", null, TOKEN);
				// Written past the options, as an old database or a script could.
				other.tUpdateByName("controller", controller);
				tx.commit();
			}
			assertSame(controller, owner.tValueByName("controller"));
			assertSame(owner, controller.tContainer());
			assertNull(Deployer.controller(other));
			assertEquals(de.haumacher.ehome.connection.I18NConstants.ERROR_FOREIGN_CONTROLLER__CONTROLLER_PROJECT
				.fill("Foreign 151", "Foreign 151 owner"), Deployer.veto(other));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * A controller on the LAN is created in its project and is offered to no other: a second one with
	 * the same address in the same project is refused; another project may have its own with the
	 * same address.
	 */
	public void testCreateInProject() {
		List<TLObject> projects = new ArrayList<>();
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject one = project("Create 151 one");
				TLObject two = project("Create 151 two");
				projects.addAll(List.of(one, two));
				TLObject created = ProjectControllers.create(one, " ", " 192.0.2.31 ", "8080", " " + TOKEN + " ");
				assertEquals("192.0.2.31", created.tValueByName("name"));
				assertEquals("192.0.2.31", created.tValueByName("host"));
				assertEquals(Integer.valueOf(8080), created.tValueByName("port"));
				assertEquals(TOKEN, created.tValueByName("token"));
				assertSame(one, created.tContainer());
				assertSame("The first controller becomes the target.", created, one.tValueByName("controller"));
				assertFalse(((Collection<?>) two.tValueByName("controllers")).contains(created));

				assertRefused(de.haumacher.ehome.connection.I18NConstants.ERROR_CONTROLLER_ADDRESS_KNOWN__CONTROLLER
					.fill("192.0.2.31"), () -> ProjectControllers.create(one, "Again", "192.0.2.31", 8080, null));
				assertRefused(de.haumacher.ehome.connection.I18NConstants.ERROR_CONTROLLER_HOST_MISSING,
					() -> ProjectControllers.create(one, "No host", " ", null, null));
				assertRefused(de.haumacher.ehome.connection.I18NConstants.ERROR_CONTROLLER_PORT_INVALID__PORT.fill("x"),
					() -> ProjectControllers.create(one, "Bad port", "192.0.2.32", "x", null));

				TLObject second = ProjectControllers.create(one, "Second", "192.0.2.33", null, null);
				assertSame("A further controller does not take over the target.", created,
					one.tValueByName("controller"));
				assertEquals(Integer.valueOf(80), second.tValueByName("port"));

				TLObject other = ProjectControllers.create(two, "Same address", "192.0.2.31", 8080, null);
				assertSame(two, other.tContainer());

				assertRefused(de.haumacher.ehome.connection.I18NConstants.ERROR_CONTROLLER_HAS_PROJECT__CONTROLLER_PROJECT
					.fill("Same address", "Create 151 two"), () -> ProjectControllers.assign(other, one));
				tx.commit();
			}
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static void assertRefused(com.top_logic.basic.util.ResKey expected, Runnable action) {
		try {
			action.run();
			fail("Expected the refusal " + expected + ".");
		} catch (com.top_logic.util.error.TopLogicException ex) {
			assertEquals(expected, ex.getErrorKey());
		}
	}

	private static TLObject project(String name) {
		TLObject project = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
		project.tUpdateByName("name", name);
		return project;
	}

	private static TLObject controller(String name, String host) {
		TLObject controller = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
		controller.tUpdateByName("name", name);
		controller.tUpdateByName("host", host);
		return controller;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestControllerOwnershipMigration.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
