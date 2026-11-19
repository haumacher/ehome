/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.runtime.ControllerFixture;
import test.de.haumacher.ehome.security.AccountFixture;
import test.de.haumacher.ehome.view.ViewCommandScan;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.ViewLoader;
import com.top_logic.layout.view.channel.DerivedChannelConfig;
import com.top_logic.layout.view.element.ValueInputElement;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.tool.boundsec.simple.CommandGroupRegistry;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;
import de.haumacher.ehome.project.ProjectOwners;
import de.haumacher.ehome.security.Administration;

/**
 * The list of projects starts at the user (haumacher/ehome #167): a few hundred projects of which the
 * user is a member of three. The rows of the list (the channel <code>projects</code> of
 * <code>projects/overview.view.xml</code>, evaluated as the view evaluates it) are exactly the user's
 * projects, for an owner, an editor and a viewer the same as the former source (all projects filtered
 * by the rights); the technical administrator and an E-Home administrator, members of none, get an
 * empty list and the search, which finds projects by the beginning of their name with a query of the
 * database.
 *
 * <p>
 * That no other project is read is asserted structurally, not by time: no expression of any view of
 * the app reads all objects of the projects' types (<code>all()</code> of the project or of a type a
 * project holds, or of a generalization of one), and the functions of the row source are queries
 * starting at the account or in the database. A timing bound would hold for a few hundred projects
 * either way (milliseconds, noisy on a shared machine) and says nothing about ten thousand; the
 * structural check forbids the scan itself at any scale.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestUserProjects extends BasicTestCase {

	private static final String OVERVIEW = "projects/overview.view.xml";

	private static final String PROJECT = ProjectMembers.PROJECT_TYPE;

	/** Projects nobody of the test is a member of. */
	private static final int OTHERS = 300;

	private static final String OTHER_NAME = "Scale test ";

	private final List<TLObject> _created = new ArrayList<>();

	private final List<Person> _persons = new ArrayList<>();

	/** Owner of A, editor of B, viewer of C. */
	private Person _user;

	/** Owner of B and C. */
	private Person _owner;

	/** Editor of B only. */
	private Person _editor;

	/** Viewer of C only. */
	private Person _viewer;

	/** In the group of the E-Home administrators, member of no project. */
	private Person _admin;

	private TLObject _a;

	private TLObject _b;

	private TLObject _c;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		inTransaction(() -> {
			_user = person("ehomeScaleUser", null);
			_owner = person("ehomeScaleOwner", "ehome.installers");
			_editor = person("ehomeScaleEditor", null);
			_viewer = person("ehomeScaleViewer", null);
			_admin = person("ehomeScaleAdministrator", Administration.ADMINISTRATORS);
			for (int n = 0; n < OTHERS; n++) {
				project(OTHER_NAME + String.format("%03d", n));
			}
			_a = project("Member test A");
			_b = project("Member test B");
			_c = project("Member test C");
			ProjectMembers.add(_a, _user, Role.owner);
			ProjectMembers.add(_b, _owner, Role.owner);
			ProjectMembers.add(_b, _user, Role.editor);
			ProjectMembers.add(_b, _editor, Role.editor);
			ProjectMembers.add(_c, _owner, Role.owner);
			ProjectMembers.add(_c, _user, Role.viewer);
			ProjectMembers.add(_c, _viewer, Role.viewer);
		});
	}

	@Override
	protected void tearDown() throws Exception {
		inTransaction(() -> {
			Collections.reverse(_created);
			for (TLObject object : _created) {
				if (object.tValid()) {
					object.tDelete();
				}
			}
			for (Person person : _persons) {
				if (person.tValid()) {
					person.tDelete();
				}
			}
		});
		_created.clear();
		_persons.clear();
		super.tearDown();
	}

	/**
	 * The rows of the list: the user's three projects by their names; an owner, an editor and a viewer
	 * see the same as with the former source (all projects filtered by the rights).
	 */
	public void testRowsAreTheUsersProjects() {
		assertEquals(List.of(_a, _b, _c), as(_user, () -> rows(null)));
		assertEquals(List.of(_b, _c), as(_owner, () -> rows(null)));
		assertEquals(List.of(_b), as(_editor, () -> rows(null)));
		assertEquals(List.of(_c), as(_viewer, () -> rows(null)));
		for (Person person : List.of(_user, _owner, _editor, _viewer)) {
			assertEquals(person.getName(), as(person, () -> formerRows()), new HashSet<>(as(person, () -> rows(null))));
		}
		assertFalse("No search for a member.", as(_user, () -> administrator()));

		// A change of the members is the next evaluation's result.
		inTransaction(() -> ProjectMembers.remove(_c, _user));
		assertEquals(List.of(_a, _b), as(_user, () -> rows(null)));
	}

	/**
	 * A project the user creates is the user's by the listener making its creator the owner
	 * (ProjectOwners, haumacher/ehome #152), not by an explicit membership: it is in the user's list
	 * right after the commit, the projects of the others unchanged.
	 */
	public void testCreatedProjectListed() {
		TLObject created = as(_user, () -> {
			TLObject[] result = new TLObject[1];
			inTransaction(() -> result[0] = project("Member test D"));
			return result[0];
		});
		assertTrue("The creator owns it.", ((Project) created).getOwners().contains(_user));
		assertEquals(List.of(_a, _b, _c, created), as(_user, () -> rows(null)));
		assertEquals(List.of(_b, _c), as(_owner, () -> rows(null)));
	}

	/**
	 * The technical administrator and an E-Home administrator are members of no project: their list
	 * is empty, and they search. The search finds by the beginning of the name, at most
	 * {@link Administration#SEARCH_LIMIT} projects by their names; a member who is no administrator
	 * finds only own projects.
	 */
	public void testAdministratorsSearch() {
		Person root = PersonManager.getManager().getRoot();
		for (Person person : List.of(root, _admin)) {
			assertEquals(person.getName(), List.of(), as(person, () -> rows(null)));
			assertEquals(person.getName(), List.of(), as(person, () -> rows("")));
			assertTrue(person.getName(), as(person, () -> administrator()));

			List<TLObject> found = as(person, () -> rows(" " + OTHER_NAME + "01 "));
			assertEquals(names(10, 20), names(found));

			List<TLObject> limited = as(person, () -> rows(OTHER_NAME));
			assertEquals(names(0, Administration.SEARCH_LIMIT), names(limited));

			assertEquals(List.of(_b), as(person, () -> rows("Member test B")));
			assertEquals(List.of(), as(person, () -> rows("Nothing like it")));
		}
		assertEquals("A member finds only own projects.", List.of(_b),
			as(_editor, () -> Administration.projectsByName("Member test")));
		assertEquals(List.of(), as(_editor, () -> Administration.projectsByName(OTHER_NAME)));
	}

	/**
	 * The options of "Add member": the members of the user's other projects that are no members of
	 * this one, nobody else; for an E-Home administrator, who is no member, the same - nobody.
	 */
	public void testAccountsOffered() {
		Expr options = valueInput("projects/member-add.view.xml", "account").getOptions();
		assertEquals(Set.of(_viewer), offered(_owner, options, _b));
		assertEquals(Set.of(_owner, _editor, _viewer), offered(_user, options, _a));
		assertEquals("No other project.", Set.of(), offered(_viewer, options, _c));
		assertEquals(Set.of(), offered(_admin, options, _c));
	}

	/**
	 * The controllers without a project are found by a query of the database: exactly those of the
	 * former source (all controllers filtered by their container).
	 */
	public void testControllersWithoutProject() {
		TLObject[] controllers = new TLObject[2];
		inTransaction(() -> {
			controllers[0] = create("ehome.runtime:Controller");
			((Controller) controllers[0]).setName("Orphan");
			controllers[1] = create("ehome.runtime:Controller");
			((Controller) controllers[1]).setName("Owned");
			ControllerFixture.own(_a, controllers[1]);
		});
		List<TLObject> found = as(_admin, () -> Administration.controllersWithoutProject());
		assertTrue(found.contains(controllers[0]));
		assertFalse(found.contains(controllers[1]));
		Collection<?> former = SearchExpression.asCollection(as(_admin,
			() -> eval("x -> all(`ehome.runtime:Controller`).filter(c -> $c.container() == null)", null)));
		assertEquals(new HashSet<>(former), new HashSet<>(found));
		assertEquals("Nobody else reads them.", List.of(), as(_user, () -> Administration.controllersWithoutProject()));
	}

	/**
	 * No expression of a view of the app reads all objects of a type a project consists of: neither
	 * <code>all()</code> of the project, of a type a project holds (in its compositions, at any
	 * depth), nor of a generalization of one of them; nor all accounts or all conversations of the
	 * assistant (haumacher/ehome #172).
	 */
	public void testNoViewScansProjects() throws IOException {
		Set<TLClass> projectTypes = projectTypes();
		// Neither the objects that grow with the accounts (haumacher/ehome #172): the accounts and the
		// conversations of the assistant, read backwards from the account instead.
		for (String perAccount : List.of("tl.accounts:Person", "tl.ai.execution:AgentSession")) {
			projectTypes.addAll(TLModelUtil.getReflexiveTransitiveSpecializations((TLClass) TLModelUtil.findType(perAccount)));
		}
		List<String> scans = new ArrayList<>();
		try (Stream<Path> files = Files.walk(ViewCommandScan.VIEWS)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".view.xml")).sorted()::iterator) {
				String view = ViewCommandScan.VIEWS.relativize(file).toString().replace('\\', '/');
				for (ConfigurationItem item : ViewCommandScan.items(load(view))) {
					String type = scannedType(item, projectTypes);
					if (type != null) {
						scans.add(view + " (" + item.location() + "): all(`" + type + "`)");
					}
				}
			}
		}
		assertEquals("Expressions reading all objects of a project's type.", List.of(), scans);
	}

	/**
	 * The qualified name of the type whose objects the given part of an expression reads all of
	 * (<code>all(`type`)</code>), if it is a project type or a generalization of one; <code>null</code>
	 * for anything else. The expressions of a view are configuration items themselves, so every call
	 * of <code>all()</code> in a view is one of the items of the view.
	 */
	private static String scannedType(ConfigurationItem item, Set<TLClass> projectTypes) {
		if (!(item instanceof Expr.Method method) || !"all".equals(method.getName()) || method.getArgs().isEmpty()
			|| !(method.getArgs().get(0).getValue() instanceof Expr.TypeLiteral literal)) {
			return null;
		}
		String name = literal.getModule() + ":" + literal.getName();
		if (TLModelUtil.findType(name) instanceof TLClass clazz
			&& !Collections.disjoint(TLModelUtil.getReflexiveTransitiveSpecializations(clazz), projectTypes)) {
			return name;
		}
		return null;
	}

	/**
	 * The project and every class a project holds in its compositions, at any depth, with their
	 * specializations.
	 */
	private static Set<TLClass> projectTypes() {
		Set<TLClass> result = new LinkedHashSet<>();
		Deque<TLClass> open = new ArrayDeque<>(List.of((TLClass) TLModelUtil.findType(PROJECT)));
		while (!open.isEmpty()) {
			for (TLClass type : TLModelUtil.getReflexiveTransitiveSpecializations(open.pop())) {
				if (!result.add(type)) {
					continue;
				}
				for (TLStructuredTypePart part : type.getAllParts()) {
					if (part instanceof TLReference reference && reference.isComposite()
						&& reference.getType() instanceof TLClass target) {
						open.push(target);
					}
				}
			}
		}
		return result;
	}

	/**
	 * The rows of the list as the view computes them: the channel <code>projects</code> with the
	 * given search text.
	 */
	private static List<TLObject> rows(String search) {
		List<TLObject> result = new ArrayList<>();
		for (Object row : SearchExpression.asCollection(evalChannel("projects", search))) {
			result.add((TLObject) row);
		}
		return result;
	}

	/**
	 * Whether the view shows the search.
	 */
	private static boolean administrator() {
		return Boolean.TRUE.equals(evalChannel("administrator", null));
	}

	private static Object evalChannel(String name, String search) {
		for (ConfigurationItem item : ViewCommandScan.items(load(OVERVIEW))) {
			if (item instanceof DerivedChannelConfig channel && name.equals(channel.getName())) {
				return QueryExecutor.compile(channel.getExpr(), List.of("search")).execute(search);
			}
		}
		throw new AssertionError("No channel " + name + " in " + OVERVIEW);
	}

	/**
	 * The projects of the test the user sees with the former source of the rows.
	 */
	private static Set<Object> formerRows() {
		Set<Object> result = new HashSet<>(SearchExpression.asCollection(eval("x -> all(`" + PROJECT + "`)", null)));
		result.removeIf(p -> !String.valueOf(((Project) p).getName()).startsWith("Member test"));
		return result;
	}

	private Set<Object> offered(Person person, Expr options, TLObject project) {
		return as(person, () -> new HashSet<>(SearchExpression.asCollection(
			QueryExecutor.compile(options, List.of("project")).execute(project))));
	}

	private static ValueInputElement.Config valueInput(String view, String value) {
		for (ConfigurationItem item : ViewCommandScan.items(load(view))) {
			if (item instanceof ValueInputElement.Config input && value.equals(input.getValue().getChannelName())) {
				return input;
			}
		}
		throw new AssertionError("No input of " + value + " in " + view);
	}

	private static ConfigurationItem load(String view) {
		try {
			return ViewLoader.loadConfig(ViewLoader.fullPath(view));
		} catch (Exception ex) {
			throw new AssertionError("The view " + view + " does not load.", ex);
		}
	}

	private static List<String> names(int from, int to) {
		List<String> result = new ArrayList<>();
		for (int n = from; n < to; n++) {
			result.add(OTHER_NAME + String.format("%03d", n));
		}
		return result;
	}

	private static List<String> names(List<TLObject> projects) {
		List<String> result = new ArrayList<>();
		for (TLObject project : projects) {
			result.add(((Project) project).getName());
		}
		return result;
	}

	private static Object eval(String function, Object argument) {
		try {
			return QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr", function)).execute(argument);
		} catch (ConfigurationException ex) {
			throw new AssertionError(ex);
		}
	}

	private static <T> T as(Person person, Supplier<T> computation) {
		return TLContextManager.inPersonContext(person, computation::get);
	}

	private TLObject project(String name) {
		TLObject result = create(PROJECT);
		((Project) result).setName(name);
		return result;
	}

	private TLObject create(String typeName) {
		TLObject result =
			ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
		_created.add(result);
		return result;
	}

	private Person person(String name, String group) {
		Person result = AccountFixture.person(name, group);
		_persons.add(result);
		return result;
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	private static void inTransaction(Runnable modification) {
		ThreadContext.withoutSecurityChecks(() -> {
			try (Transaction tx = kb().beginTransaction()) {
				modification.run();
				tx.commit();
			}
		});
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestUserProjects.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, CommandGroupRegistry.Module.INSTANCE,
			ProjectOwners.Module.INSTANCE));
	}

}
