/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.security;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.tool.boundsec.wrap.Group;

/**
 * Accounts of the tests of access rights, created as the administration creates them: every account
 * is a member of the engine's default group "user" (the role <code>SecurityStructure.user</code> on the
 * security root, with which every account reads the shared master data and uses the assistant,
 * haumacher/ehome #152), and of the given group of E-Home.
 */
public class AccountFixture {

	/** The engine's group every new account joins (<code>securityStructureGroups.config.xml</code>). */
	public static final String USERS = "user";

	/**
	 * A new account of the given name in the given group, <code>null</code> for none besides "user"
	 * (an existing account of that name is replaced). Must be called in a transaction.
	 */
	public static Person person(String name, String group) {
		Person existing = Person.byName(name);
		if (existing != null) {
			existing.tDelete();
		}
		Person result =
			Person.create(kb(), name, TLSecurityDeviceManager.getInstance().getAuthenticationDevice("dbSecurity"));
		join(result, USERS);
		if (group != null) {
			join(result, group);
		}
		return result;
	}

	/**
	 * Adds the account to the group of the given name, which is created if it does not exist.
	 */
	public static void join(Person person, String groupName) {
		Group group = Group.getGroupByName(kb(), groupName);
		if (group == null) {
			group = Group.createGroup(groupName);
		}
		if (!group.getMembers().contains(person)) {
			group.addMember(person);
		}
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

}
