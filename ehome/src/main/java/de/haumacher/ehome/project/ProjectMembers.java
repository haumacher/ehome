/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.top_logic.base.user.UserInterface;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;

/**
 * The members of a project and their roles (haumacher/ehome #152).
 *
 * <p>
 * A project names the accounts holding a role in it in three references: <code>members</code> (all
 * of them), <code>editors</code> and <code>owners</code>, two disjoint subsets of the members. A
 * member in neither subset is a viewer. The role rules of <code>ehome.security.config.xml</code> give
 * every member the role <code>ehome.Viewer</code>, the editors <code>ehome.Editor</code> and the owners
 * <code>ehome.Owner</code> on the project, and every member <code>ehome.Collaborator</code> on the
 * accounts of the other members (one rule over <code>members</code>, backwards and forwards).
 * </p>
 *
 * <p>
 * The operations here keep these invariants and at least one owner in a project that has one; they
 * refuse with a reason the user reads. They write through Java, which the model access check does not
 * cover: the commands calling them check the operation <code>ManageMembers</code> (the owner's) on the
 * project, leaving a project only <code>Read</code>. The caller provides the transaction.
 * </p>
 */
public class ProjectMembers {

	/** The type of a project. */
	public static final String PROJECT_TYPE = "ehome.project:Project";

	/** The enumeration of the roles. */
	public static final String ROLE_TYPE = "ehome.project:ProjectRole";

	/** All members of a project. */
	public static final String MEMBERS = "members";

	/** The members editing a project. */
	public static final String EDITORS = "editors";

	/** The members owning a project. */
	public static final String OWNERS = "owners";

	/**
	 * The role of a member in a project, named as the classifiers of <code>ehome.project:ProjectRole</code>.
	 */
	public enum Role {
		/** Everything, including the members and deleting the project. */
		owner,
		/** Edits the project. */
		editor,
		/** Reads the project. */
		viewer;

		/**
		 * The classifier of this role.
		 */
		public TLClassifier classifier() {
			return ((TLEnumeration) TLModelUtil.findType(ROLE_TYPE)).getClassifier(name());
		}

		/**
		 * The role given as a {@link Role}, a classifier of <code>ehome.project:ProjectRole</code> or its name,
		 * <code>null</code> for anything else.
		 */
		public static Role of(Object value) {
			if (value instanceof Role role) {
				return role;
			}
			String name = value instanceof TLClassifier classifier ? classifier.getName()
				: value instanceof String text ? text.trim() : null;
			if (name == null) {
				return null;
			}
			for (Role role : values()) {
				if (role.name().equals(name)) {
					return role;
				}
			}
			return null;
		}
	}

	/**
	 * The role of the given account in the given project, <code>null</code> if it is no member.
	 */
	public static Role role(TLObject project, TLObject account) {
		if (project == null || account == null || !accounts(project, MEMBERS).contains(account)) {
			return null;
		}
		if (accounts(project, OWNERS).contains(account)) {
			return Role.owner;
		}
		if (accounts(project, EDITORS).contains(account)) {
			return Role.editor;
		}
		return Role.viewer;
	}

	/**
	 * Adds the given account to the project with the given role.
	 *
	 * @param account
	 *        The account, or its mail address or user name (see {@link #account(Object)}).
	 * @return The account added.
	 */
	public static Person add(TLObject project, Object account, Object role) {
		Person person = account(account);
		Role newRole = requireRole(role);
		if (accounts(project, MEMBERS).contains(person)) {
			throw new TopLogicException(I18NConstants.ERROR_ALREADY_MEMBER__ACCOUNT.fill(person));
		}
		put(project, person, newRole);
		return person;
	}

	/**
	 * Gives a member of the project another role; the last owner keeps the ownership.
	 */
	public static Person setRole(TLObject project, Object account, Object role) {
		Person person = member(project, account);
		Role newRole = requireRole(role);
		if (newRole != Role.owner) {
			checkNotLastOwner(project, person);
		}
		put(project, person, newRole);
		return person;
	}

	/**
	 * Removes a member from the project; the last owner stays.
	 */
	public static void remove(TLObject project, Object account) {
		Person person = member(project, account);
		checkNotLastOwner(project, person);
		for (String reference : List.of(OWNERS, EDITORS, MEMBERS)) {
			removeFrom(project, reference, person);
		}
	}

	/**
	 * The current user leaves the project; the last owner stays.
	 */
	public static void leave(TLObject project) {
		Person user = TLContext.currentUser();
		if (user == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_USER);
		}
		remove(project, user);
	}

	/**
	 * Makes the given account the owner of a project that has no owner yet, e.g. its creator.
	 *
	 * @return Whether the account became the owner.
	 */
	public static boolean ownIfUnowned(TLObject project, Person account) {
		if (!addable(account) || !accounts(project, OWNERS).isEmpty()) {
			return false;
		}
		put(project, account, Role.owner);
		return true;
	}

	/**
	 * Gives the account the role in the project, unless it holds that role or a stronger one already
	 * (for the migration of existing projects).
	 *
	 * @return Whether the role of the account changed.
	 */
	public static boolean grant(TLObject project, Person account, Role role) {
		if (!addable(account)) {
			return false;
		}
		Role current = role(project, account);
		if (current != null && current.ordinal() <= role.ordinal()) {
			return false;
		}
		put(project, account, role);
		return true;
	}

	/**
	 * The account the given value names: an account itself, or a text naming the user name or the
	 * mail address of exactly one account (ignoring case). An administrator creates the accounts,
	 * an unknown address is refused with that reason.
	 */
	public static Person account(Object value) {
		if (value instanceof Person person) {
			if (!addable(person)) {
				throw new TopLogicException(I18NConstants.ERROR_NOT_ADDABLE__ACCOUNT.fill(person));
			}
			return person;
		}
		String text = value instanceof String string ? string.trim() : "";
		if (text.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_ACCOUNT_GIVEN);
		}
		Person byName = Person.byName(text);
		if (addable(byName)) {
			return byName;
		}
		List<Person> found = new ArrayList<>();
		String address = text.toLowerCase(Locale.ROOT);
		for (Person person : Person.all()) {
			if (addable(person) && address.equals(mail(person))) {
				found.add(person);
			}
		}
		if (found.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_ACCOUNT);
		}
		if (found.size() > 1) {
			throw new TopLogicException(I18NConstants.ERROR_AMBIGUOUS_ADDRESS__ADDRESS.fill(text));
		}
		return found.get(0);
	}

	/**
	 * The mail address of the account in lower case, <code>null</code> if it has none.
	 */
	private static String mail(Person person) {
		UserInterface user = person.getUser();
		String mail = user == null ? null : user.getEMail();
		return mail == null || mail.isBlank() ? null : mail.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Whether the account may hold a role in a project: a valid account other than the anonymous one.
	 */
	private static boolean addable(Person person) {
		return person != null && person.tValid() && !PersonManager.getManager().isAnonymous(person);
	}

	/**
	 * The values of one of the three references of a project.
	 */
	public static Set<Person> accounts(TLObject project, String reference) {
		Set<Person> result = new LinkedHashSet<>();
		if (project.tValueByName(reference) instanceof Collection<?> values) {
			for (Object value : values) {
				if (value instanceof Person person) {
					result.add(person);
				}
			}
		}
		return result;
	}

	private static Person member(TLObject project, Object account) {
		Person person = account instanceof Person given ? given : account(account);
		if (!accounts(project, MEMBERS).contains(person)) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_MEMBER__ACCOUNT.fill(person));
		}
		return person;
	}

	private static Role requireRole(Object role) {
		Role result = Role.of(role);
		if (result == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_ROLE);
		}
		return result;
	}

	private static void checkNotLastOwner(TLObject project, Person person) {
		Set<Person> owners = accounts(project, OWNERS);
		if (owners.size() == 1 && owners.contains(person)) {
			throw new TopLogicException(I18NConstants.ERROR_LAST_OWNER__ACCOUNT.fill(person));
		}
	}

	private static void put(TLObject project, Person person, Role role) {
		addTo(project, MEMBERS, person);
		if (role == Role.owner) {
			addTo(project, OWNERS, person);
		} else {
			removeFrom(project, OWNERS, person);
		}
		if (role == Role.editor) {
			addTo(project, EDITORS, person);
		} else {
			removeFrom(project, EDITORS, person);
		}
	}

	private static void addTo(TLObject project, String reference, Person person) {
		Set<Person> values = accounts(project, reference);
		if (values.add(person)) {
			project.tUpdateByName(reference, values);
		}
	}

	private static void removeFrom(TLObject project, String reference, Person person) {
		Set<Person> values = accounts(project, reference);
		if (values.remove(person)) {
			project.tUpdateByName(reference, values);
		}
	}

}
