/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.top_logic.element.meta.AttributeOperations;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.wrap.Group;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.project.ProjectMembers;

/**
 * What the E-Home administrators do besides an installer (haumacher/ehome #165): manage who is an
 * installer and who is an E-Home administrator, and find a project by its name to assign a
 * controller without a project to.
 *
 * <p>
 * The membership of the two groups of E-Home (<code>ehome.installers</code>, <code>ehome.administrators</code>)
 * is written here, in Java, which the model access check does not cover: the commands calling it check
 * the operation <code>EHomeAdministration</code> on the security root (<code>ehome.security.config.xml</code>).
 * No other group is touched: a group of the engine, or any other, is refused, so the command gives no
 * account more than the two roles of E-Home (the technical administrator's flag is never touched). The
 * caller provides the transaction.
 * </p>
 */
public final class Administration {

	/** The group whose members create and import projects (role <code>ehome.Installer</code>). */
	public static final String INSTALLERS = "ehome.installers";

	/** The group of the E-Home administrators (role <code>ehome.Administrator</code>). */
	public static final String ADMINISTRATORS = "ehome.administrators";

	/** The groups whose members the E-Home administrators manage. */
	public static final Set<String> GROUPS = Set.of(INSTALLERS, ADMINISTRATORS);

	private Administration() {
		// Static methods only.
	}

	/**
	 * The group of E-Home with the given name, <code>null</code> for any other name or a group that
	 * does not exist.
	 */
	public static Group group(String name) {
		if (name == null || !GROUPS.contains(name)) {
			return null;
		}
		return Group.getGroupByName(kb(), name);
	}

	/**
	 * Adds the given account to the given group of E-Home.
	 *
	 * @param group
	 *        One of the two groups of E-Home, or its name; any other group is refused.
	 * @param account
	 *        The account, or its mail address or user name ({@link ProjectMembers#account(Object)}).
	 * @return The account added.
	 */
	public static Person addMember(Object group, Object account) {
		Group target = requireGroup(group);
		Person person = ProjectMembers.account(account);
		if (target.getMembers().contains(person)) {
			throw new TopLogicException(I18NConstants.ERROR_ALREADY_IN_GROUP__ACCOUNT_GROUP.fill(person, label(target)));
		}
		target.addMember(person);
		return person;
	}

	/**
	 * Removes the given account from the given group of E-Home.
	 *
	 * @param group
	 *        One of the two groups of E-Home, or its name; any other group is refused.
	 * @param account
	 *        The account, or its mail address or user name.
	 */
	public static void removeMember(Object group, Object account) {
		Group target = requireGroup(group);
		Person person = account instanceof Person given ? given : ProjectMembers.account(account);
		if (!target.getMembers().contains(person)) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_IN_GROUP__ACCOUNT_GROUP.fill(person, label(target)));
		}
		target.removeMember(person);
	}

	/**
	 * The projects with exactly the given name (leading and trailing blanks ignored), found by a query
	 * of the database, which reads no other project: the lookup of the dialog assigning a controller
	 * (haumacher/ehome #165, #167). Empty for an empty name.
	 */
	public static List<TLObject> projectsNamed(String name) {
		String text = name == null ? "" : name.trim();
		if (text.isEmpty()) {
			return new ArrayList<>();
		}
		TLClass type = (TLClass) TLModelUtil.findType(ProjectMembers.PROJECT_TYPE);
		return AttributeOperations.instancesWithValue(type, "name", text, TLObject.class);
	}

	private static Group requireGroup(Object group) {
		String name = group instanceof Group given ? given.getName() : group instanceof String text ? text.trim() : null;
		Group result = group(name);
		if (result == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_EHOME_GROUP__GROUP.fill(String.valueOf(name)));
		}
		return result;
	}

	private static String label(Group group) {
		return group.getName();
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

}
