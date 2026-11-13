/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.top_logic.basic.IdentifierUtil;
import com.top_logic.basic.Logger;
import com.top_logic.basic.SessionContext;
import com.top_logic.basic.TLID;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.objects.LifecycleAttributes;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Revision;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.wrap.Group;

import de.haumacher.ehome.project.ProjectMembers.Role;

/**
 * The data step of the migration <code>Issue_152_Project_members</code> (haumacher/ehome #152): the
 * existing projects keep the access they had through the global groups, now as members of each
 * project.
 *
 * <ul>
 * <li>The account that created a project (the author of its creating commit) becomes its owner, if it
 * still exists; otherwise the project has no owner, and administrators act for it.</li>
 * <li>Every member of the group <code>ehome.installers</code> becomes an editor of every project (an
 * owner stays owner).</li>
 * <li>Every member of the group <code>ehome.viewers</code> becomes a viewer of every project (a stronger
 * role stays).</li>
 * </ul>
 *
 * <p>
 * Runs once, in the started application after the migration, in a transaction of the migration
 * service; {@link #migrate()} is the logic, with what it did as one line per project.
 * </p>
 */
public class ProjectMembersMigration implements StartupAction {

	/** The group whose members were the installers. */
	public static final String INSTALLERS = "ehome.installers";

	/** The group whose members were the viewers. */
	public static final String VIEWERS = "ehome.viewers";

	@Override
	public void perform() {
		for (String line : migrate()) {
			Logger.info(line, ProjectMembersMigration.class);
		}
	}

	/**
	 * Gives every existing project its owner and the members of the two groups as editors and viewers,
	 * inside the caller's transaction.
	 *
	 * @return What was done, one line per project.
	 */
	public static List<String> migrate() {
		List<String> report = new ArrayList<>();
		List<TLObject> projects = new ArrayList<>(MetaElementUtil
			.getAllDirectInstancesOf((TLClass) TLModelUtil.findType(ProjectMembers.PROJECT_TYPE), TLObject.class));
		projects.sort(Comparator.comparing((TLObject p) -> String.valueOf(p.tValueByName("name")))
			.thenComparing(p -> p.tIdLocal()));
		Collection<Person> installers = members(INSTALLERS);
		Collection<Person> viewers = members(VIEWERS);
		for (TLObject project : projects) {
			String name = String.valueOf(project.tValueByName("name"));
			Person creator = creator(project);
			if (creator != null && ProjectMembers.ownIfUnowned(project, creator)) {
				report.add("Project '" + name + "': its creator '" + creator.getName()
					+ "' becomes its owner (haumacher/ehome #152).");
			} else if (ProjectMembers.accounts(project, ProjectMembers.OWNERS).isEmpty()) {
				report.add("Project '" + name
					+ "': its creator no longer exists, the project has no owner; administrators act for it (haumacher/ehome #152).");
			}
			List<String> editors = new ArrayList<>();
			for (Person installer : installers) {
				if (ProjectMembers.grant(project, installer, Role.editor)) {
					editors.add(installer.getName());
				}
			}
			if (!editors.isEmpty()) {
				report.add("Project '" + name + "': the installers " + editors + " become editors (haumacher/ehome #152).");
			}
			List<String> readers = new ArrayList<>();
			for (Person viewer : viewers) {
				if (ProjectMembers.grant(project, viewer, Role.viewer)) {
					readers.add(viewer.getName());
				}
			}
			if (!readers.isEmpty()) {
				report.add("Project '" + name + "': the viewers " + readers + " become viewers (haumacher/ehome #152).");
			}
		}
		return report;
	}

	/**
	 * The current account that created the project (the author of its creating commit),
	 * <code>null</code> if it was created without one or the account no longer exists.
	 */
	static Person creator(TLObject project) {
		Object author = project.tGetData(LifecycleAttributes.CREATOR);
		if (!(author instanceof String contextId) || !contextId.startsWith(SessionContext.PERSON_ID_PREFIX)) {
			return null;
		}
		TLID id = IdentifierUtil.fromExternalForm(contextId.substring(SessionContext.PERSON_ID_PREFIX.length()));
		Person creator = Person.byId(project.tKnowledgeBase(), Revision.CURRENT_REV, id);
		return creator != null && creator.tValid() ? creator : null;
	}

	/**
	 * The accounts in the group of the given name and its groups, sorted by name; none if there is no
	 * such group.
	 */
	private static Collection<Person> members(String groupName) {
		Group group = Group.getGroupByName(PersistencyLayer.getKnowledgeBase(), groupName);
		if (group == null) {
			return List.of();
		}
		List<Person> result = new ArrayList<>(group.getMembers(true));
		result.sort(Comparator.comparing(Person::getName));
		return result;
	}

}
