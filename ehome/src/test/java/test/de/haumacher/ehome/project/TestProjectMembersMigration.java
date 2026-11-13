/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.project;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.security.AccountFixture;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;
import de.haumacher.ehome.project.ProjectMembersMigration;

/**
 * The migration of the existing projects to access per project (haumacher/ehome #152,
 * {@link ProjectMembersMigration}), on data as it was before: projects without members, accounts in
 * the groups <code>ehome.installers</code> and <code>ehome.viewers</code>.
 *
 * <ul>
 * <li>A project created by an account that still exists: that account becomes its owner.</li>
 * <li>A project whose creator was deleted, and one created without an account: no owner.</li>
 * <li>Every installer becomes an editor, every viewer a viewer of every project; the creator, an
 * installer as well, stays owner, and an account in both groups is an editor.</li>
 * <li>Run again, it changes nothing.</li>
 * </ul>
 *
 * <p>
 * The migration runs over the whole database in a transaction that the test rolls back, so the
 * groups and the members it gives exist only for the assertions; the projects and accounts are
 * created and deleted by the test.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestProjectMembersMigration extends BasicTestCase {

	private final List<TLObject> _projects = new ArrayList<>();

	private final List<Person> _persons = new ArrayList<>();

	public void testMigration() {
		Person creator = inTransaction(() -> person("migration152Creator", ProjectMembersMigration.INSTALLERS));
		Person gone = inTransaction(() -> person("migration152Gone", null));
		Person installer = inTransaction(() -> person("migration152Installer", ProjectMembersMigration.INSTALLERS));
		Person viewer = inTransaction(() -> person("migration152Viewer", ProjectMembersMigration.VIEWERS));
		Person both = inTransaction(() -> {
			Person result = person("migration152Both", ProjectMembersMigration.VIEWERS);
			AccountFixture.join(result, ProjectMembersMigration.INSTALLERS);
			return result;
		});
		Person outsider = inTransaction(() -> person("migration152Outsider", null));

		// Created by the accounts, in commits of their own, as before the migration: no members.
		TLObject owned = createdBy(creator, "Migration 152 owned");
		TLObject orphaned = createdBy(gone, "Migration 152 orphaned");
		TLObject system = inTransaction(() -> project("Migration 152 system"));
		inTransaction(() -> {
			gone.tDelete();
			return null;
		});
		for (TLObject project : _projects) {
			assertEquals(List.of(), new ArrayList<>(ProjectMembers.accounts(project, ProjectMembers.MEMBERS)));
		}

		try (Transaction tx = kb().beginTransaction()) {
			List<String> report = ProjectMembersMigration.migrate();

			assertEquals(Role.owner, ProjectMembers.role(owned, creator));
			assertEquals(List.of(), new ArrayList<>(ProjectMembers.accounts(orphaned, ProjectMembers.OWNERS)));
			assertEquals(List.of(), new ArrayList<>(ProjectMembers.accounts(system, ProjectMembers.OWNERS)));
			for (TLObject project : List.of(owned, orphaned, system)) {
				assertEquals(Role.editor, ProjectMembers.role(project, installer));
				assertEquals(Role.viewer, ProjectMembers.role(project, viewer));
				assertEquals(Role.editor, ProjectMembers.role(project, both));
				assertNull(ProjectMembers.role(project, outsider));
			}
			assertEquals("The creator is an installer and stays owner.", Role.owner,
				ProjectMembers.role(owned, creator));
			assertEquals(Role.editor, ProjectMembers.role(orphaned, creator));
			assertTrue(report.toString(), report.stream().anyMatch(
				line -> line.contains("'Migration 152 owned'") && line.contains("'migration152Creator' becomes its owner")));
			assertTrue(report.toString(), report.stream().anyMatch(
				line -> line.contains("'Migration 152 orphaned'") && line.contains("has no owner")));

			assertTrue("Run again, the migration has nothing to do for these projects.",
				ProjectMembersMigration.migrate().stream().noneMatch(line -> line.contains("become")));
			// Rolled back: nothing of the migration stays in the shared database.
		}
	}

	@Override
	protected void tearDown() throws Exception {
		inTransaction(() -> {
			for (Person person : _persons) {
				if (person.tValid()) {
					person.tDelete();
				}
			}
			return null;
		});
		SharedData.deleteProjects(_projects);
		_projects.clear();
		_persons.clear();
		super.tearDown();
	}

	private TLObject createdBy(Person person, String name) {
		return TLContextManager.inPersonContext(person, () -> inTransaction(() -> project(name)));
	}

	private TLObject project(String name) {
		TLObject result = ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType(ProjectMembers.PROJECT_TYPE));
		result.tUpdateByName("name", name);
		_projects.add(result);
		return result;
	}

	private Person person(String name, String group) {
		Person result = AccountFixture.person(name, group);
		_persons.add(result);
		return result;
	}

	private static <T> T inTransaction(java.util.function.Supplier<T> modification) {
		return ThreadContext.withoutSecurityChecks(() -> {
			try (Transaction tx = kb().beginTransaction()) {
				T result = modification.get();
				tx.commit();
				return result;
			}
		});
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProjectMembersMigration.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			TLSecurityDeviceManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE));
	}

}
