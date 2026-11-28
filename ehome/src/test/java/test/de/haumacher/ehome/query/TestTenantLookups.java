/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.runtime.ControllerFixture;
import test.de.haumacher.ehome.security.AccountFixture;
import test.de.haumacher.ehome.view.ViewCommandScan;

import com.top_logic.ai.core.execution.model.AgentSession;
import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.PropertyDescriptor;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.ViewLoader;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Controllers;
import de.haumacher.ehome.connection.Enrollment;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.I18NConstants;
import de.haumacher.ehome.knx.ProgramStructure;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.physical.Connector;
import de.haumacher.ehome.model.physical.ParameterSetting;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.Deployment;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.projectfile.ProjectFileImport;
import de.haumacher.ehome.query.Queries;
import de.haumacher.ehome.runtime.StateDatapoints;
import de.haumacher.ehome.zigbee.ZigbeeController;

/**
 * The lookups that no longer read all projects, controllers, accounts or rules (haumacher/ehome
 * #172): each one among several objects of its type finds exactly the right ones, and nothing for an
 * unknown key. That they are queries of the database, not scans, is the code's (<code>Queries</code>,
 * <code>AttributeOperations.instancesWithValue</code>, backwards references); the tests assert that
 * the result is the same as the scan's was.
 */
@SuppressWarnings("javadoc")
public class TestTenantLookups extends BasicTestCase {

	private static final String PROJECT = ProjectMembers.PROJECT_TYPE;

	private static final String CONTROLLER = "ehome.runtime:Controller";

	private final List<TLObject> _created = new ArrayList<>();

	private final List<Person> _persons = new ArrayList<>();

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
	 * <code>Enrollment.project(code)</code>: the project with the open code, by a query on
	 * <code>enrollmentCode</code>.
	 */
	public void testEnrollmentCode() {
		List<TLObject> projects = new ArrayList<>();
		inTransaction(() -> {
			for (String code : List.of("TEST-AAAA", "TEST-BBBB", "TEST-CCCC")) {
				Project project = project("Enrollment " + code);
				project.setEnrollmentCode(code);
				projects.add(project);
			}
			project("Enrollment without code");
		});
		assertSame(projects.get(0), Enrollment.project("TEST-AAAA"));
		assertSame(projects.get(1), Enrollment.project("TEST-BBBB"));
		assertSame(projects.get(2), Enrollment.project("TEST-CCCC"));
		assertNull(Enrollment.project("TEST-DDDD"));
		assertNull("Case matters.", Enrollment.project("test-aaaa"));
		assertNull(Enrollment.project(null));
	}

	/**
	 * <code>ControllerConnections.find(id)</code>: the controller with the id, by a query on
	 * <code>controllerId</code>.
	 */
	public void testControllerId() {
		List<TLObject> controllers = new ArrayList<>();
		inTransaction(() -> {
			TLObject project = project("Controller ids");
			for (String id : List.of("172a", "172b", "172c")) {
				TLObject controller = controller(id);
				ControllerFixture.own(project, controller);
				controllers.add(controller);
			}
			ControllerFixture.own(project, controller(null));
		});
		assertSame(controllers.get(0), ControllerConnections.find("172a"));
		assertSame(controllers.get(1), ControllerConnections.find("172b"));
		assertSame(controllers.get(2), ControllerConnections.find("172c"));
		assertNull(ControllerConnections.find("172d"));
		assertNull(ControllerConnections.find(null));
	}

	/**
	 * <code>ZigbeeController.projects(controller)</code>: the projects deploying to the controller,
	 * read backwards from it.
	 */
	public void testProjectsOfController() {
		Controller[] c = new Controller[3];
		Project[] p = new Project[3];
		inTransaction(() -> {
			for (int n = 0; n < 3; n++) {
				p[n] = project("Zigbee " + n);
				c[n] = controller("172z" + n);
			}
			ControllerFixture.own(p[0], c[0]);
			ControllerFixture.own(p[1], c[1]);
			// A second project deploying to the first project's controller.
			p[2].setController(c[0]);
		});
		assertEquals(Set.of(p[0], p[2]), new HashSet<>(ZigbeeController.projects(c[0])));
		assertEquals(List.of(p[1]), ZigbeeController.projects(c[1]));
		assertEquals("Its project deploys elsewhere.", List.of(), ZigbeeController.projects(c[2]));
	}

	/**
	 * <code>Controllers.forget(controller)</code>: the projects deploying to it, programming through
	 * it and the deployments naming it lose it, read backwards from it; others are untouched.
	 */
	public void testForgetController() throws InterruptedException {
		Controller[] c = new Controller[3];
		Project[] p = new Project[2];
		Deployment[] d = new Deployment[2];
		inTransaction(() -> {
			p[0] = project("Forget 0");
			p[1] = project("Forget 1");
			c[0] = controller("172f0");
			c[1] = controller("172f1");
			c[2] = controller("172f2");
			ControllerFixture.own(p[0], c[0]);
			ControllerFixture.own(p[0], c[1]);
			ControllerFixture.own(p[1], c[2]);
			p[0].setProgrammingController(c[0]);
			p[1].setProgrammingController(c[2]);
			d[0] = deployment(p[0], c[0]);
			d[1] = deployment(p[1], c[2]);
		});
		Controllers.forget(c[0]);
		ControllerConnections.awaitKnowledgeBase();
		assertFalse(c[0].tValid());
		assertSame("The project's only other controller takes its place.", c[1], p[0].getController());
		assertNull(p[0].getProgrammingController());
		assertNull(d[0].getController());
		assertSame(c[2], p[1].getController());
		assertSame(c[2], p[1].getProgrammingController());
		assertSame(c[2], d[1].getController());
	}

	/**
	 * <code>ProjectFileImport.projectsWithId(id)</code> and the numbering of project IDs: the projects
	 * with an ID by a query, the highest number of a project ID by the database's order, a new ID
	 * above it and free.
	 */
	public void testProjectIds() {
		long before = ExtIds.highestProjectNumber();
		String first = "P-" + ExtIds.hex4(before + 1);
		String third = "P-" + ExtIds.hex4(before + 3);
		List<TLObject> projects = new ArrayList<>();
		inTransaction(() -> {
			for (String id : List.of(first, third, third, third + "-0", "p-" + ExtIds.hex4(before + 5))) {
				Project project = project("Project id " + id);
				project.setExtId(id);
				projects.add(project);
			}
		});
		assertEquals(List.of(projects.get(0)), ProjectFileImport.projectsWithId(first));
		assertEquals(Set.of(projects.get(1), projects.get(2)), new HashSet<>(ProjectFileImport.projectsWithId(third)));
		assertEquals(List.of(), ProjectFileImport.projectsWithId("P-" + ExtIds.hex4(before + 2)));
		assertEquals(List.of(), ProjectFileImport.projectsWithId(null));
		assertEquals("Neither the suffixed nor the lower-case ID counts.", before + 3, ExtIds.highestProjectNumber());

		TLObject[] created = new TLObject[1];
		inTransaction(() -> created[0] = project("Project id new"));
		String id = ExtIds.extId(created[0]);
		assertTrue(id, ExtIds.Kind.PROJECT.number(id) > before + 3);
		assertEquals(List.of(created[0]), ProjectFileImport.projectsWithId(id));
		try {
			ExtIds.members(null, ExtIds.Kind.PROJECT);
			fail("The projects are never listed.");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	/**
	 * <code>StateDatapoints.missing()</code>: the rules without a state datapoint, by a query; the
	 * listener giving each rule its datapoint does not run in this test, so the rules created here
	 * have none until given one.
	 */
	public void testRulesWithoutStateDatapoint() {
		List<TLObject> rules = new ArrayList<>();
		inTransaction(() -> {
			for (int n = 0; n < 3; n++) {
				Rule rule = create("ehome.runtime:Rule");
				rule.setNameKey(ResKey.text("Rule 172 " + n));
				rule.setIdentifier("rule172_" + n);
				rules.add(rule);
			}
			StateDatapoints.ensure(rules.get(1));
		});
		List<TLObject> missing = StateDatapoints.missing();
		assertTrue(missing.contains(rules.get(0)));
		assertFalse(missing.contains(rules.get(1)));
		assertTrue(missing.contains(rules.get(2)));
		inTransaction(() -> {
			StateDatapoints.ensure(rules.get(0));
			StateDatapoints.ensure(rules.get(2));
		});
		missing = StateDatapoints.missing();
		for (TLObject rule : rules) {
			assertFalse(missing.contains(rule));
		}
	}

	/**
	 * <code>ProgramStructure.deleteVeto</code>: the use of a program's element by devices is an
	 * existence check; a user inside the program is named, one outside is not.
	 */
	public void testDeleteVeto() {
		TLObject[] o = new TLObject[10];
		inTransaction(() -> {
			Program program = create("ehome.operational:Program");
			EnumerationType type = create("ehome.operational:EnumerationType");
			type.setName("Type 172");
			// An entry held by a parameter of the program, one set by a device, one unused.
			o[0] = entry("Held", 0);
			o[1] = entry("Set", 1);
			o[2] = entry("Unused", 2);
			type.setValues(List.of((EnumerationValue) o[0], (EnumerationValue) o[1], (EnumerationValue) o[2]));
			o[3] = parameterPlaced(type, "Holder 172");
			((Parameter) o[3]).setChoice((EnumerationValue) o[0]);
			program.addParameter((Parameter) o[3]);

			// A parameter whose placement a device sets (with the second entry), one whose placement
			// nobody sets.
			o[4] = parameterPlaced(type, "Set 172");
			o[5] = parameterPlaced(type, "Unset 172");
			ParameterSetting setting = create("ehome.physical:ParameterSetting");
			setting.setParameter((ParameterView) ExtIds.list(o[4], "views").get(0));
			setting.setChoice((EnumerationValue) o[1]);

			// A communication object whose placement a device uses, one unused.
			o[6] = portPlaced();
			o[7] = portPlaced();
			this.<Connector> create("ehome.physical:Connector").setPort((Interface) ExtIds.list(o[6], "interfaces").get(0));
		});
		ResKey held = ProgramStructure.deleteVeto(o[0]);
		assertEquals(I18NConstants.ERROR_ENTRY_IN_USE__ENTRY_HOLDER.fill(o[0], o[3]), held);
		assertTrue(english(held), english(held).contains("Holder 172"));
		assertEquals(I18NConstants.ERROR_ENTRY_SET_ELSEWHERE__ENTRY.fill(o[1]), ProgramStructure.deleteVeto(o[1]));
		assertNull(ProgramStructure.deleteVeto(o[2]));
		assertEquals(I18NConstants.ERROR_PARAMETER_SET_BY_DEVICES__PARAMETER.fill(o[4]), ProgramStructure.deleteVeto(o[4]));
		assertNull(ProgramStructure.deleteVeto(o[5]));
		assertEquals(I18NConstants.ERROR_OBJECT_USED_BY_DEVICES__OBJECT.fill(o[6]), ProgramStructure.deleteVeto(o[6]));
		assertNull(ProgramStructure.deleteVeto(o[7]));

		TLReference choice = (TLReference) TLModelUtil.findPart("ehome.operational:ParameterValue#choice");
		assertSame(o[3], Queries.firstReferer(o[0], choice));
		assertTrue(Queries.hasReferer(o[1], choice));
		assertFalse(Queries.hasReferer(o[2], choice));
	}

	/**
	 * <code>ProjectMembers.account(text)</code>: the account by its user name, or by the mail
	 * address of its contact ignoring case, found by a query of the contacts.
	 */
	public void testAccountByMail() {
		Person[] a = new Person[4];
		inTransaction(() -> {
			a[0] = person("ehomeLookupA", "a172@example.org");
			a[1] = person("ehomeLookupB", "B172@Example.org");
			a[2] = person("ehomeLookupC", "shared172@example.org");
			a[3] = person("ehomeLookupD", "Shared172@example.org");
		});
		assertSame(a[0], ProjectMembers.account("a172@example.org"));
		assertSame(a[0], ProjectMembers.account("  A172@EXAMPLE.ORG "));
		assertSame(a[1], ProjectMembers.account("b172@example.org"));
		assertSame("The user name first.", a[2], ProjectMembers.account("ehomeLookupC"));
		assertEquals(List.of(), ProjectMembers.byMail("c172@example.org"));
		assertEquals(Set.of(a[2], a[3]), new HashSet<>(ProjectMembers.byMail("shared172@example.org")));
		try {
			ProjectMembers.account("unknown172@example.org");
			fail("No such account.");
		} catch (TopLogicException expected) {
			assertEquals(de.haumacher.ehome.project.I18NConstants.ERROR_NO_ACCOUNT, expected.getErrorKey());
		}
		try {
			ProjectMembers.account("shared172@example.org");
			fail("Two accounts have the address.");
		} catch (TopLogicException expected) {
			// Ambiguous.
		}
	}

	/**
	 * The conversations of the chat rail (<code>app.view.xml</code>, evaluated as the view does): the
	 * chats of the current user, read backwards from the account, newest first; no background session,
	 * nobody else's.
	 */
	public void testChatRail() {
		Person[] u = new Person[2];
		TLObject[] s = new TLObject[5];
		inTransaction(() -> {
			u[0] = person("ehomeChatA", null);
			u[1] = person("ehomeChatB", null);
			s[0] = session(u[0], "CHAT", 1000);
			s[1] = session(u[0], "CHAT", 3000);
			s[2] = session(u[0], "BACKGROUND", 2000);
			s[3] = session(u[1], "CHAT", 4000);
			s[4] = session(null, "CHAT", 5000);
		});
		Expr rows = chatRows();
		assertEquals(List.of(s[1], s[0]), as(u[0], () -> eval(rows)));
		assertEquals(List.of(s[3]), as(u[1], () -> eval(rows)));
	}

	private static Expr chatRows() {
		ConfigurationItem view;
		try {
			view = ViewLoader.loadConfig(ViewLoader.fullPath("app.view.xml"));
		} catch (Exception ex) {
			throw new AssertionError("The view app.view.xml does not load.", ex);
		}
		for (ConfigurationItem item : ViewCommandScan.items(view)) {
			PropertyDescriptor rows = item.descriptor().getProperty("rows");
			PropertyDescriptor observed = item.descriptor().getProperty("observed-types");
			if (rows != null && observed != null
				&& String.valueOf(item.value(observed)).contains("tl.ai.execution:AgentSession")) {
				return (Expr) item.value(rows);
			}
		}
		throw new AssertionError("No list of the conversations in app.view.xml.");
	}

	private static List<Object> eval(Expr expr) {
		return new ArrayList<>(SearchExpression.asCollection(QueryExecutor.compile(expr).execute()));
	}

	private TLObject session(Person owner, String kind, long created) {
		AgentSession result = create("tl.ai.execution:AgentSession");
		result.setOwner(owner);
		result.setKind(((TLEnumeration) TLModelUtil.findType("tl.ai.execution:SessionKind")).getClassifier(kind));
		result.setCreatedAt(new Date(created));
		return result;
	}

	private Project project(String name) {
		Project result = create(PROJECT);
		result.setName(name);
		return result;
	}

	private Controller controller(String id) {
		Controller result = create(CONTROLLER);
		result.setName("Controller " + id);
		result.setControllerId(id);
		return result;
	}

	private Deployment deployment(Project project, Controller controller) {
		Deployment result = create("ehome.runtime:Deployment");
		result.setController(controller);
		project.addDeployment(result);
		return result;
	}

	private EnumerationValue entry(String name, int value) {
		EnumerationValue result = create("ehome.operational:EnumerationValue");
		result.setNameKey(ResKey.text(name));
		result.setValue(value);
		return result;
	}

	private Parameter parameterPlaced(ParameterType type, String name) {
		Parameter result = create("ehome.operational:Parameter");
		result.setName(name);
		result.setType(type);
		this.<ParameterView> create("ehome.operational:ParameterView").setParameter(result);
		return result;
	}

	private Port portPlaced() {
		Port result = create("ehome.operational:Port");
		this.<Interface> create("ehome.operational:Interface").setPort(result);
		return result;
	}

	@SuppressWarnings("unchecked")
	private <T extends TLObject> T create(String typeName) {
		TLObject result =
			ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
		_created.add(result);
		return (T) result;
	}

	private Person person(String name, String mail) {
		Person result = AccountFixture.person(name, null);
		if (mail != null) {
			result.getUser().setEMail(mail);
		}
		_persons.add(result);
		return result;
	}

	private static String english(ResKey key) {
		return Resources.getInstance(Locale.ENGLISH).getString(key);
	}

	private static <T> T as(Person person, Supplier<T> computation) {
		return TLContextManager.inPersonContext(person, computation::get);
	}

	private static void inTransaction(Runnable modification) {
		ThreadContext.withoutSecurityChecks(() -> {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				modification.run();
				tx.commit();
			}
		});
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestTenantLookups.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE));
	}

}
