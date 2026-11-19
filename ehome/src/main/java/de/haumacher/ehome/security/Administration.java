/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.security;

import static com.top_logic.knowledge.search.ExpressionFactory.allOf;
import static com.top_logic.knowledge.search.ExpressionFactory.and;
import static com.top_logic.knowledge.search.ExpressionFactory.attribute;
import static com.top_logic.knowledge.search.ExpressionFactory.destination;
import static com.top_logic.knowledge.search.ExpressionFactory.eqBinary;
import static com.top_logic.knowledge.search.ExpressionFactory.filter;
import static com.top_logic.knowledge.search.ExpressionFactory.flex;
import static com.top_logic.knowledge.search.ExpressionFactory.ge;
import static com.top_logic.knowledge.search.ExpressionFactory.literal;
import static com.top_logic.knowledge.search.ExpressionFactory.lt;
import static com.top_logic.knowledge.search.ExpressionFactory.map;
import static com.top_logic.knowledge.search.ExpressionFactory.order;
import static com.top_logic.knowledge.search.ExpressionFactory.params;
import static com.top_logic.knowledge.search.ExpressionFactory.queryResolved;
import static com.top_logic.knowledge.search.ExpressionFactory.reference;
import static com.top_logic.knowledge.search.ExpressionFactory.revisionArgs;
import static com.top_logic.knowledge.search.ExpressionFactory.substraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import com.top_logic.basic.col.CloseableIterator;
import com.top_logic.dob.MOAttribute;
import com.top_logic.dob.attr.MOPrimitive;
import com.top_logic.dob.ex.UnknownTypeException;
import com.top_logic.dob.meta.MOClass;
import com.top_logic.element.meta.AttributeOperations;
import com.top_logic.element.meta.kbbased.WrapperMetaAttributeUtil;
import com.top_logic.element.meta.kbbased.storage.ColumnStorage;
import com.top_logic.element.meta.kbbased.storage.LinkStorage;
import com.top_logic.knowledge.search.BranchParam;
import com.top_logic.knowledge.search.Expression;
import com.top_logic.knowledge.search.RangeParam;
import com.top_logic.knowledge.search.SetExpression;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.wrap.Group;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.model.project.Project;
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

	/** The most projects {@link #projectsByName(String)} lists. */
	public static final int SEARCH_LIMIT = 50;

	/** The type of a controller. */
	public static final String CONTROLLER_TYPE = "ehome.runtime:Controller";

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

	/**
	 * The projects whose name begins with the given text (leading and trailing blanks ignored), by
	 * their names, at most {@link #SEARCH_LIMIT}: the search of the E-Home administrators in the list
	 * of projects (haumacher/ehome #167). Found by a range query of the database on the name (from the
	 * text up to the text followed by the last character), which reads only the projects found; the
	 * database compares as its collation does (H2: case-sensitive), and a project found is kept when
	 * its name begins with the text ignoring case. Only the projects the current user may discover are
	 * returned. Empty for an empty text.
	 */
	public static List<TLObject> projectsByName(String prefix) {
		String text = prefix == null ? "" : prefix.trim();
		List<TLObject> result = new ArrayList<>();
		if (text.isEmpty()) {
			return result;
		}
		TLClass type = (TLClass) TLModelUtil.findType(ProjectMembers.PROJECT_TYPE);
		KnowledgeBase kb = kb();
		for (Entry<String, Set<TLClass>> entry : AttributeOperations.typesByTableName(type).entrySet()) {
			MOClass table = table(kb, entry.getKey());
			TLStructuredTypePart attribute = entry.getValue().iterator().next().getPartOrFail("name");
			if (!(attribute.getStorageImplementation() instanceof ColumnStorage storage)) {
				throw new IllegalStateException("The name of a project is not stored with the project: " + attribute);
			}
			String storageAttribute = storage.getStorageAttribute();
			MOAttribute column = table.getAttributeOrNull(storageAttribute);
			Expression name = column != null ? attribute(column) : flex(MOPrimitive.STRING, storageAttribute);
			SetExpression query = filter(AttributeOperations.tableQuery(table, entry.getValue()),
				and(ge(name, literal(text)), lt(name, literal(text + Character.MAX_VALUE))));
			try (CloseableIterator<TLObject> projects = kb.searchStream(
				queryResolved(BranchParam.single, RangeParam.head, params(), query, order(name), TLObject.class),
				revisionArgs().setStopRow(SEARCH_LIMIT))) {
				while (projects.hasNext()) {
					TLObject project = projects.next();
					Object value = ((Project) project).getName();
					if (value instanceof String projectName && projectName.regionMatches(true, 0, text, 0, text.length())) {
						result.add(project);
					}
				}
			}
		}
		result.sort(ProjectMembers.BY_NAME);
		if (result.size() > SEARCH_LIMIT) {
			result = new ArrayList<>(result.subList(0, SEARCH_LIMIT));
		}
		return discoverable(result);
	}

	/**
	 * The controllers that belong to no project (haumacher/ehome #151, #165): only the migration of
	 * existing data leaves them (<code>ControllerOwnershipMigration</code>). Found by one query of the
	 * database (haumacher/ehome #167): the controllers whose identifier is not the destination of a
	 * link of the composition <code>ehome.project:Project#controllers</code>; the database compares
	 * the identifiers, the application loads only the controllers found. Only the controllers the
	 * current user may discover are returned.
	 */
	public static List<TLObject> controllersWithoutProject() {
		TLReference controllers = (TLReference) TLModelUtil.findPart(ProjectMembers.PROJECT_TYPE + "#controllers");
		if (!(controllers.getStorageImplementation() instanceof LinkStorage<?> storage)) {
			throw new IllegalStateException("The controllers of a project are not stored as links: " + controllers);
		}
		KnowledgeBase kb = kb();
		String linkTable = storage.getTable();
		SetExpression links = allOf(table(kb, linkTable));
		if (!storage.monomophicTable()) {
			links = filter(links, eqBinary(reference(linkTable, WrapperMetaAttributeUtil.META_ATTRIBUTE_ATTR),
				literal(controllers.getDefinition().tHandle())));
		}
		SetExpression contained = map(links, destination());
		List<TLObject> result = new ArrayList<>();
		TLClass type = (TLClass) TLModelUtil.findType(CONTROLLER_TYPE);
		for (Entry<String, Set<TLClass>> entry : AttributeOperations.typesByTableName(type).entrySet()) {
			SetExpression query =
				substraction(AttributeOperations.tableQuery(table(kb, entry.getKey()), entry.getValue()), contained);
			for (TLObject controller : kb.search(queryResolved(query, TLObject.class))) {
				if (controller.tContainer() == null) {
					result.add(controller);
				}
			}
		}
		return discoverable(result);
	}

	@SuppressWarnings("unchecked")
	private static List<TLObject> discoverable(List<TLObject> objects) {
		return new ArrayList<>((List<TLObject>) SearchExpression.filterSecurity(objects));
	}

	private static MOClass table(KnowledgeBase kb, String name) {
		try {
			return (MOClass) kb.getMORepository().getType(name);
		} catch (UnknownTypeException ex) {
			throw new IllegalStateException("No table " + name + ".", ex);
		}
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
