/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.importer.TestEtsImport;
import test.de.haumacher.ehome.runtime.ControllerFixture;
import test.de.haumacher.ehome.view.ViewCommandScan;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.DefaultInstantiationContext;
import com.top_logic.basic.config.PropertyDescriptor;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.command.OpenDialogAction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.security.ModelAccessRights;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.BoundCommandGroup;
import com.top_logic.tool.boundsec.BoundObject;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.tool.boundsec.simple.CommandGroupRegistry;
import com.top_logic.tool.boundsec.simple.SimpleBoundCommandGroup;
import com.top_logic.tool.boundsec.wrap.Group;
import com.top_logic.tool.execution.ExecutableState;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.layout.view.ViewLoader;
import com.top_logic.layout.view.security.AccessControl;
import com.top_logic.layout.view.security.UnitAccess;

import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;
import de.haumacher.ehome.project.ProjectOwners;
import de.haumacher.ehome.connection.ProjectControllers;
import de.haumacher.ehome.security.I18NConstants;
import de.haumacher.ehome.template.Presets;
import de.haumacher.ehome.security.Administration;

/**
 * The access definition of E-Home (<code>WEB-INF/autoconf/ehome.security.config.xml</code>, haumacher/ehome
 * #48, #152) checked through the model access rights: on a project with a group address, a device, a
 * function, a deployment, a rule with a parameter, a reaction, a trigger and an action, a schedule
 * entry and an application datapoint, on a controller and its token, on a manufacturer with a catalog
 * and an application program, and on the singletons of the master data. The accounts: the owner, an
 * editor and a viewer of the project, an installer holding no role in it, an E-Home administrator
 * holding no role in it (haumacher/ehome #165), and an account in no group of E-Home (like every
 * account, in the engine's group "user"). The access checks the view commands declare (haumacher/ehome
 * #65) are evaluated for these accounts as the engine evaluates them for a button. No check needs the
 * technical administrator: every function of E-Home is checked for an account of E-Home.
 *
 * <p>
 * Access per project (haumacher/ehome #152): a user sees exactly the projects holding a role in; the
 * members of a project see each other's name and mail address and nothing else of each other; the
 * owners manage the members, and a project keeps at least one owner.
 * </p>
 *
 * <p>
 * The test builds the objects it checks itself (no import) and deletes them, and the accounts, again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAccessRights extends BasicTestCase {

	private static final BoundCommandGroup READ = SimpleBoundCommandGroup.READ;

	private static final BoundCommandGroup WRITE = SimpleBoundCommandGroup.WRITE;

	private static final BoundCommandGroup DELETE = SimpleBoundCommandGroup.DELETE;

	private static final String MEMBERS = "ehome.project:Project#members";

	private final List<TLObject> _created = new ArrayList<>();

	private final List<Person> _persons = new ArrayList<>();

	/** Owner of the project, member of ehome.installers. */
	private Person _owner;

	/** Editor of the project, in no group of E-Home. */
	private Person _editor;

	/** Viewer of the project, in no group of E-Home. */
	private Person _viewer;

	/** Member of ehome.installers without a role in the project. */
	private Person _installer;

	/** In no group of E-Home and without a role. */
	private Person _stranger;

	/** Member of ehome.administrators without a role in the project. */
	private Person _admin;

	private TLObject _project;

	private TLObject _groupRange;

	private TLObject _groupAddress;

	private TLObject _device;

	private TLObject _function;

	private TLObject _dismissal;

	private TLObject _deployment;

	private TLObject _controller;

	private TLObject _rule;

	private TLObject _reaction;

	private TLObject _trigger;

	private TLObject _action;

	private TLObject _parameter;

	private TLObject _schedule;

	private TLObject _applicationDatapoint;

	private TLObject _manufacturer;

	private TLObject _catalog;

	private TLObject _program;

	private TLObject _variant;

	private TLObject _template;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		inTransaction(() -> {
			_owner = person("ehomeTestOwner", "ehome.installers");
			_editor = person("ehomeTestEditor", null);
			_viewer = person("ehomeTestViewer", null);
			_installer = person("ehomeTestInstaller", "ehome.installers");
			_stranger = person("ehomeTestStranger", null);
			_admin = person("ehomeTestAdministrator", Administration.ADMINISTRATORS);

			_controller = create("ehome.runtime:Controller");
			_controller.tUpdateByName("name", "Access test");
			_controller.tUpdateByName("host", "localhost");
			_controller.tUpdateByName("token", "hct_0123456789abcdef0123456789abcdef");

			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Access test");
			ControllerFixture.own(_project, _controller);
			ProjectMembers.add(_project, _owner, Role.owner);
			ProjectMembers.add(_project, _editor, Role.editor);
			ProjectMembers.add(_project, _viewer, Role.viewer);

			_groupAddress = create("ehome.logical:GroupAddress");
			_groupAddress.tUpdateByName("name", "Light");
			_groupRange = create("ehome.logical:GroupRange");
			_groupRange.tUpdateByName("parts", List.of(_groupAddress));
			_project.tUpdateByName("groupAddresses", List.of(_groupRange));

			_manufacturer = create("ehome.catalog:Manufacturer");
			_catalog = create("ehome.catalog:Catalog");
			_manufacturer.tUpdateByName("catalogs", List.of(_catalog));
			_program = create("ehome.operational:Program");
			_manufacturer.tUpdateByName("programs", List.of(_program));
			TLObject product = create("ehome.catalog:Product");
			_variant = create("ehome.catalog:Variant");
			product.tUpdateByName("variants", List.of(_variant));
			TLObject assignment = create("ehome.catalog:ProgramAssignment");
			assignment.tUpdateByName("program", _program);
			product.tUpdateByName("programs", List.of(assignment));
			_manufacturer.tUpdateByName("products", List.of(product));
			_catalog.tUpdateByName("variants", List.of(_variant));
			TLObject manufacturers = singleton("ehome.catalog:ManufacturerRoot");
			List<Object> all = new ArrayList<>((Collection<?>) manufacturers.tValueByName("manufacturers"));
			all.add(_manufacturer);
			manufacturers.tUpdateByName("manufacturers", all);
			_template = create("ehome.template:DeviceTemplate");
			_template.tUpdateByName("program", _program);
			TLObject templates = singleton("ehome.template:DeviceTemplates");
			List<Object> allTemplates = new ArrayList<>((Collection<?>) templates.tValueByName("templates"));
			allTemplates.add(_template);
			templates.tUpdateByName("templates", allTemplates);

			_device = create("ehome.physical:Device");
			_device.tUpdateByName("type", _variant);
			_device.tUpdateByName("program", assignment);
			TLObject line = create("ehome.physical:Line");
			line.tUpdateByName("devices", List.of(_device));
			TLObject area = create("ehome.physical:Area");
			area.tUpdateByName("lines", List.of(line));
			_project.tUpdateByName("areas", List.of(area));

			_function = create("ehome.runtime:Function");
			_function.tUpdateByName("name", ResKey.builder().add(Locale.ENGLISH, "Light").build());
			_function.tUpdateByName("kind",
				((TLEnumeration) TLModelUtil.findType("ehome.runtime:FunctionKind")).getClassifier("light"));
			_project.tUpdateByName("functions", List.of(_function));

			_dismissal = create("ehome.runtime:DismissedProposal");
			_dismissal.tUpdateByName("kind",
				((TLEnumeration) TLModelUtil.findType("ehome.runtime:FunctionKind")).getClassifier("button"));
			_dismissal.tUpdateByName("device", _device);
			_dismissal.tUpdateByName("channel", "object:Taste 1");
			_project.tUpdateByName("dismissedProposals", List.of(_dismissal));

			_rule = create("ehome.runtime:Rule");
			_rule.tUpdateByName("identifier", "access");
			_rule.tUpdateByName("name", ResKey.builder().add(Locale.ENGLISH, "Access").build());
			_parameter = create("ehome.runtime:RuleParameter");
			_parameter.tUpdateByName("identifier", "p");
			_parameter.tUpdateByName("label", "P");
			_parameter.tUpdateByName("default", "1.0");
			_parameter.tUpdateByName("type",
				((TLEnumeration) TLModelUtil.findType("ehome.runtime:RuleParameterType")).getClassifier("real"));
			_rule.tUpdateByName("parameters", List.of(_parameter));
			_reaction = create("ehome.runtime:Reaction");
			_trigger = create("ehome.runtime:StartTrigger");
			_action = create("ehome.runtime:LogAction");
			_reaction.tUpdateByName("triggers", List.of(_trigger));
			_reaction.tUpdateByName("actions", List.of(_action));
			_rule.tUpdateByName("reactions", List.of(_reaction));
			_project.tUpdateByName("rules", List.of(_rule));
			_schedule = create("ehome.runtime:OnceSchedule");
			_schedule.tUpdateByName("identifier", "once");
			_schedule.tUpdateByName("label", ResKey.builder().add(Locale.ENGLISH, "Once").build());
			_project.tUpdateByName("schedules", List.of(_schedule));
			_applicationDatapoint = create("ehome.runtime:ApplicationDatapoint");
			_applicationDatapoint.tUpdateByName("identifier", "holiday");
			_applicationDatapoint.tUpdateByName("type",
				((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier("boolean"));
			_project.tUpdateByName("applicationDatapoints", List.of(_applicationDatapoint));

			_deployment = create("ehome.runtime:Deployment");
			_deployment.tUpdateByName("controller", _controller);
			_project.tUpdateByName("deployments", List.of(_deployment));
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
				if (person != null && person.tValid()) {
					person.tDelete();
				}
			}
		});
		_created.clear();
		_persons.clear();
		super.tearDown();
	}

	/**
	 * A user sees a project exactly when holding a role in it: its owner, editor and viewer read it
	 * with everything in it, an installer without a role and an account without a group not.
	 */
	public void testVisibilityByRole() {
		for (TLObject object : projectObjects()) {
			for (Person member : List.of(_owner, _editor, _viewer)) {
				assertTrue(member.getName() + ": " + label(object), allowed(member, object, READ));
			}
			for (Person outsider : List.of(_installer, _stranger)) {
				assertFalse(outsider.getName() + ": " + label(object), allowed(outsider, object, READ));
				assertFalse(outsider.getName() + ": " + label(object), allowed(outsider, object, WRITE));
			}
		}
		assertEquals(List.of(_project), as(_viewer, () -> visibleProjects()));
		assertEquals(List.of(), as(_installer, () -> visibleProjects()));
		assertEquals(List.of(), as(_stranger, () -> visibleProjects()));
	}

	/**
	 * An editor edits everything in the project, a viewer nothing; only the owner deletes the project
	 * and manages its members.
	 */
	public void testEditorEditsViewerNot() {
		for (TLObject object : projectObjects()) {
			assertTrue(label(object), allowed(_owner, object, WRITE));
			assertTrue(label(object), allowed(_editor, object, WRITE));
			assertFalse(label(object), allowed(_viewer, object, WRITE));
			assertFalse(label(object), allowed(_viewer, object, DELETE));
		}
		assertTrue(allowed(_editor, _controller, DELETE));
		assertTrue(allowed(_editor, _device, DELETE));
		assertTrue(rights().isAllowedCreate(_editor, _project, part("ehome.project:Project#controllers")));
		assertTrue(rights().isAllowedCreate(_editor, _groupRange, part("ehome.logical:GroupRange#parts")));
		assertTrue(rights().isAllowedCreate(_editor, _rule, part("ehome.runtime:Rule#reactions")));
		assertTrue(rights().isAllowedCreate(_editor, _project, part("ehome.project:Project#applicationDatapoints")));
		assertFalse(rights().isAllowedCreate(_viewer, _project, part("ehome.project:Project#controllers")));
		assertFalse(rights().isAllowedCreate(_viewer, _groupRange, part("ehome.logical:GroupRange#parts")));
		assertFalse(rights().isAllowedCreate(_viewer, _project, part("ehome.project:Project#schedules")));

		assertTrue(allowed(_owner, _project, DELETE));
		assertFalse(allowed(_editor, _project, DELETE));
		assertFalse(allowed(_viewer, _project, DELETE));
		assertTrue(allowed(_owner, _project, manageMembers()));
		assertFalse(allowed(_editor, _project, manageMembers()));
		assertFalse(allowed(_viewer, _project, manageMembers()));

		// Nobody writes the members through the model, not even the owner: the member commands do.
		for (String reference : List.of(MEMBERS, "ehome.project:Project#editors", "ehome.project:Project#owners")) {
			assertTrue(reference, rights().isAllowed(_viewer, _project, part(reference), READ));
			assertFalse(reference, rights().isAllowed(_owner, _project, part(reference), WRITE));
		}
	}

	/**
	 * A member of <code>ehome.installers</code> holds no role in a project of somebody else, but may
	 * create projects; nobody else may.
	 */
	public void testInstallerCreatesProjects() {
		assertTrue(rights().isAllowedCreate(_installer, type("ehome.project:Project"), (TLObject) null));
		assertTrue(rights().isAllowedCreate(_owner, type("ehome.project:Project"), (TLObject) null));
		for (Person person : List.of(_editor, _viewer, _stranger)) {
			assertFalse(person.getName(), rights().isAllowedCreate(person, type("ehome.project:Project"), (TLObject) null));
		}
		ViewCommandScan.Site upload = ViewCommandScan.command("projects/overview.view.xml", "ImportProjectFileJob");
		assertTrue(as(_installer, () -> upload.decide((Object) null)).isExecutable());
		assertHidden(as(_viewer, () -> upload.decide((Object) null)));
		assertHidden(as(_stranger, () -> upload.decide((Object) null)));
	}

	/**
	 * Whoever creates a project becomes its owner (<code>ProjectOwners</code>), and sees it; nobody
	 * else does.
	 */
	public void testCreatorIsOwner() {
		TLObject created = TLContextManager.inPersonContext(_installer, () -> {
			try (Transaction tx = kb().beginTransaction()) {
				TLObject result = create("ehome.project:Project");
				result.tUpdateByName("name", "Created by the installer");
				tx.commit();
				return result;
			}
		});
		assertEquals(List.of(_installer), new ArrayList<>(ProjectMembers.accounts(created, ProjectMembers.OWNERS)));
		assertEquals(Role.owner, ProjectMembers.role(created, _installer));
		assertTrue(allowed(_installer, created, READ));
		assertTrue(allowed(_installer, created, DELETE));
		assertFalse(allowed(_owner, created, READ));
		assertFalse(allowed(_viewer, created, READ));
	}

	/**
	 * The shared master data is read by every account, also one in no group of E-Home; an installer
	 * and an E-Home administrator edit the catalogs, nobody but an E-Home administrator the device
	 * templates (haumacher/ehome #165).
	 */
	public void testMasterDataReadByEveryAccount() {
		for (TLObject object : masterData()) {
			for (Person person : List.of(_stranger, _viewer, _editor, _installer, _admin)) {
				assertTrue(person.getName() + ": " + label(object), allowed(person, object, READ));
			}
			assertFalse(label(object), allowed(_stranger, object, WRITE));
		}
		assertTrue(allowed(_installer, _manufacturer, WRITE));
		assertTrue("Import creates manufacturers.",
			rights().isAllowedCreate(_installer, singleton("ehome.catalog:ManufacturerRoot"),
				part("ehome.catalog:ManufacturerRoot#manufacturers")));
		assertTrue(rights().isAllowedCreate(_installer, singleton("ehome.zigbee.catalog:ZigbeeCatalog"),
			part("ehome.zigbee.catalog:ZigbeeCatalog#types")));
		assertFalse(allowed(_editor, _manufacturer, WRITE));
		assertTrue(allowed(_admin, _manufacturer, WRITE));
		assertTrue(rights().isAllowedCreate(_admin, singleton("ehome.catalog:ManufacturerRoot"),
			part("ehome.catalog:ManufacturerRoot#manufacturers")));

		TLObject templates = singleton("ehome.template:DeviceTemplates");
		for (Person person : List.of(_installer, _owner, _editor, _viewer, _stranger)) {
			assertFalse(person.getName(), allowed(person, _template, WRITE));
			assertFalse(person.getName(), allowed(person, templates, WRITE));
			assertFalse(person.getName(),
				rights().isAllowedCreate(person, templates, part("ehome.template:DeviceTemplates#templates")));
		}
		assertTrue("An E-Home administrator writes a template.", allowed(_admin, _template, WRITE));
		assertTrue(allowed(_admin, templates, WRITE));
		assertTrue(rights().isAllowedCreate(_admin, templates, part("ehome.template:DeviceTemplates#templates")));
		assertTrue(allowed(_admin, _template, DELETE));
	}

	/**
	 * The presets of the device templates are saved, changed and deleted by whoever writes the
	 * templates (haumacher/ehome #146): an E-Home administrator, not an installer (haumacher/ehome
	 * #165).
	 */
	public void testAdministratorWritesPresets() {
		assertNull(as(_admin, () -> Presets.templateWriteVeto()));
		for (Person person : List.of(_installer, _owner, _editor, _stranger)) {
			assertEquals(person.getName(), de.haumacher.ehome.template.I18NConstants.ERROR_TEMPLATES_NOT_ALLOWED,
				as(person, () -> Presets.templateWriteVeto()));
		}
	}

	/**
	 * An E-Home administrator reads every project, also one holding no role in, with everything in
	 * it, manages its members and deletes it, but writes neither the project nor anything in it
	 * (haumacher/ehome #165): to edit it, the administrator becomes a member. An installer sees
	 * none of it.
	 */
	public void testAdministratorOnEveryProject() {
		assertNull(ProjectMembers.role(_project, _admin));
		for (TLObject object : projectObjects()) {
			assertTrue(label(object), allowed(_admin, object, READ));
			assertFalse(label(object), allowed(_admin, object, WRITE));
		}
		assertTrue(allowed(_admin, _project, DELETE));
		assertTrue(allowed(_admin, _project, manageMembers()));
		assertFalse("A part is deleted by writing the project.", allowed(_admin, _device, DELETE));
		assertFalse(rights().isAllowedCreate(_admin, _project, part("ehome.project:Project#controllers")));
		assertFalse("The enrollment code is the editors'.",
			rights().isAllowed(_admin, _project, part("ehome.project:Project#enrollmentCode"), READ));
		assertFalse(rights().isAllowed(_admin, _controller, part("ehome.runtime:Controller#token"), READ));
		assertFalse(rights().isAllowed(_admin, _project, part("ehome.project:Project#installationKey"), READ));
		assertFalse(allowed(_installer, _project, READ));
		assertEquals(List.of(_project), as(_admin, () -> visibleProjects()));

		// The administrator reads the name and mail address of the members, nothing else of them.
		inTransaction(() -> mail(_owner, "owner.admin@example.org"));
		assertTrue(rights().isAllowed(_admin, _owner, part("tl.accounts:Person#name"), READ));
		assertTrue(rights().isAllowed(_admin, (TLObject) _owner.getUser(), part("Contacts:PersonContact#email"), READ));
		assertFalse(rights().isAllowed(_admin, _owner, part("tl.accounts:Person#groups"), READ));
		assertFalse(rights().isAllowed(_admin, _owner, WRITE));
		assertFalse("No account outside a project or a group of E-Home.",
			rights().isAllowed(_admin, _stranger, part("tl.accounts:Person#name"), READ));

		// The member commands of the owner are the administrator's as well.
		ViewCommandScan.Site add = ViewCommandScan.command("projects/member-add.view.xml", "projectAddMember()");
		ViewCommandScan.Site role = ViewCommandScan.command("projects/member-role.view.xml", "projectSetRole()");
		ViewCommandScan.Site remove = ViewCommandScan.command("projects/members.view.xml", "projectRemoveMember()");
		assertTrue(as(_admin, () -> add.decide(_project)).isExecutable());
		assertTrue(as(_admin, () -> role.decide(_project, _viewer)).isExecutable());
		assertTrue(as(_admin, () -> remove.decide(_project, _viewer)).isExecutable());
		ViewCommandScan.Site scan = ViewCommandScan.command("projects/scan.view.xml", "ScanJob");
		assertFalse("Writing the project is the members'.", as(_admin, () -> scan.decide(_project)).isExecutable());
	}

	/**
	 * A project without an owner (created without a user, left by a migration): the E-Home
	 * administrator makes an account its owner, also the own one, and then edits it as an owner.
	 */
	public void testAdministratorRepairsProjectWithoutOwner() {
		TLObject[] orphan = new TLObject[1];
		inTransaction(() -> {
			orphan[0] = create("ehome.project:Project");
			orphan[0].tUpdateByName("name", "Access test without owner");
		});
		assertEquals(List.of(), new ArrayList<>(ProjectMembers.accounts(orphan[0], ProjectMembers.OWNERS)));
		assertTrue(allowed(_admin, orphan[0], manageMembers()));
		assertFalse(allowed(_owner, orphan[0], manageMembers()));
		ViewCommandScan.Site add = ViewCommandScan.command("projects/member-add.view.xml", "projectAddMember()");
		assertTrue(as(_admin, () -> add.decide(orphan[0])).isExecutable());
		assertFalse(as(_installer, () -> add.decide(orphan[0])).isExecutable());

		inTransaction(() -> as(_admin, () -> ProjectMembers.add(orphan[0], "ehomeTestAdministrator", Role.owner)));
		assertEquals(Role.owner, ProjectMembers.role(orphan[0], _admin));
		assertTrue(allowed(_admin, orphan[0], WRITE));
		inTransaction(() -> as(_admin, () -> ProjectMembers.add(orphan[0], _installer, Role.owner)));
		inTransaction(() -> as(_admin, () -> {
			ProjectMembers.leave(orphan[0]);
			return null;
		}));
		assertEquals(Role.owner, ProjectMembers.role(orphan[0], _installer));
		assertFalse("A member no more, the administrator reads the project only.", allowed(_admin, orphan[0], WRITE));
		assertTrue(allowed(_admin, orphan[0], READ));
	}

	/**
	 * A controller is a part of its project and is accessed where the project is (haumacher/ehome
	 * #151): the members read it, its editors write it, nobody else. A controller without a project
	 * (left by the migration of existing data) is accessed by nobody but an administrator.
	 */
	public void testControllerFollowsItsProject() {
		assertTrue(allowed(_viewer, _controller, READ));
		assertFalse(allowed(_viewer, _controller, WRITE));
		assertTrue(allowed(_editor, _controller, WRITE));
		assertFalse(allowed(_installer, _controller, READ));
		assertFalse(allowed(_stranger, _controller, READ));
		assertSame("The controller takes its access from its project.", _project, _controller.tContainer());
		assertTrue(rights().isAllowed(_viewer, _controller, part("ehome.runtime:Controller#name"), READ));

		TLObject[] orphan = new TLObject[1];
		inTransaction(() -> {
			orphan[0] = create("ehome.runtime:Controller");
			orphan[0].tUpdateByName("name", "Access test without project");
			orphan[0].tUpdateByName("host", "192.0.2.9");
		});
		for (Person person : List.of(_owner, _editor, _viewer, _installer, _stranger)) {
			assertFalse(person.getName(), allowed(person, orphan[0], READ));
			assertFalse(person.getName(), allowed(person, orphan[0], WRITE));
		}
		// The E-Home administrators read it (haumacher/ehome #165), nobody writes it through the model.
		assertTrue(allowed(_admin, orphan[0], READ));
		assertFalse(allowed(_admin, orphan[0], WRITE));
		assertFalse(rights().isAllowed(_admin, orphan[0], part("ehome.runtime:Controller#token"), READ));
		// On a controller of a project, the administrator holds what the project gives: reading.
		assertTrue(allowed(_admin, _controller, READ));
		assertFalse(allowed(_admin, _controller, WRITE));
		assertFalse(allowed(_admin, _controller, DELETE));

		// A controller on the LAN is created in a project by whoever writes the project.
		ViewCommandScan.Site create = ViewCommandScan.command("controllers/create-controller.view.xml", "controllerCreate()");
		assertTrue(as(_editor, () -> create.decide(_project)).isExecutable());
		assertFalse(as(_viewer, () -> create.decide(_project)).isExecutable());
		assertFalse(as(_installer, () -> create.decide(_project)).isExecutable());
		assertHidden(as(_stranger, () -> create.decide(_project)));

		// Only an E-Home administrator assigns or deletes a controller without a project.
		ViewCommandScan.Site assign = ViewCommandScan.command("controllers/assign-controller.view.xml", "controllerAssign()");
		ViewCommandScan.Site forget = ViewCommandScan.command("administration/controllers.view.xml", "ForgetControllerAction");
		assertTrue(as(_admin, () -> assign.decide(orphan[0], _project)).isExecutable());
		assertTrue(as(_admin, () -> forget.decide(orphan[0])).isExecutable());
		for (Person person : List.of(_owner, _viewer, _installer, _stranger)) {
			assertFalse(person.getName(), as(person, () -> assign.decide(orphan[0], _project)).isExecutable());
			assertFalse(person.getName(), as(person, () -> forget.decide(orphan[0])).isExecutable());
		}

		// The projects of a name are found by a query of the database.
		assertEquals(List.of(_project), Administration.projectsNamed(" Access test "));
		assertEquals(List.of(), Administration.projectsNamed("Access"));

		inTransaction(() -> as(_admin, () -> ProjectControllers.assign(orphan[0], _project)));
		assertSame(_project, orphan[0].tContainer());
		assertFalse("Now the project's business.", allowed(_admin, orphan[0], WRITE));
		assertTrue(allowed(_editor, orphan[0], WRITE));
	}

	/**
	 * An E-Home administrator manages who is an installer and who is an E-Home administrator
	 * (haumacher/ehome #165): adds an account to <code>ehome.installers</code> by its mail address
	 * or user name and removes it again; any other group is refused, a group of the engine as well
	 * as a made-up one. An installer is refused the commands.
	 */
	public void testAdministratorManagesGroups() {
		ViewCommandScan.Site add = ViewCommandScan.command("administration/group-add.view.xml", "ehomeGroupAdd()");
		ViewCommandScan.Site remove = ViewCommandScan.command("administration/group-members.view.xml", "ehomeGroupRemove()");
		Group installers = Administration.group(Administration.INSTALLERS);
		Group administrators = Administration.group(Administration.ADMINISTRATORS);
		assertNotNull(installers);
		assertNotNull(administrators);
		assertNull("No other group.", Administration.group(AccountFixture.USERS));

		assertTrue(as(_admin, () -> add.decide(installers)).isExecutable());
		assertTrue(as(_admin, () -> remove.decide(installers, _installer)).isExecutable());
		assertTrue(as(_admin, () -> add.decide(administrators)).isExecutable());
		for (Person person : List.of(_installer, _owner, _viewer)) {
			assertHidden(as(person, () -> add.decide(installers)));
			assertHidden(as(person, () -> remove.decide(installers, _installer)));
		}
		assertFalse("The engine's group is not read.", rights().isAllowed(_admin,
			Group.getGroupByName(kb(), AccountFixture.USERS), READ));
		assertTrue(allowed(_admin, installers, READ));
		assertTrue(rights().isAllowed(_admin, _installer, part("tl.accounts:Person#name"), READ));

		inTransaction(() -> mail(_stranger, "stranger.groups@example.org"));
		assertFalse(rights().isAllowedCreate(_stranger, type("ehome.project:Project"), (TLObject) null));
		inTransaction(() -> assertSame(_stranger,
			as(_admin, () -> Administration.addMember(installers, "Stranger.Groups@example.org"))));
		assertTrue(installers.getMembers().contains(_stranger));
		assertTrue("An installer now.", rights().isAllowedCreate(_stranger, type("ehome.project:Project"), (TLObject) null));
		assertRefused(I18NConstants.ERROR_ALREADY_IN_GROUP__ACCOUNT_GROUP.fill(_stranger, Administration.INSTALLERS),
			() -> Administration.addMember(Administration.INSTALLERS, _stranger));
		inTransaction(() -> as(_admin, () -> {
			Administration.removeMember(installers, _stranger);
			return null;
		}));
		assertFalse(installers.getMembers().contains(_stranger));
		assertRefused(I18NConstants.ERROR_NOT_IN_GROUP__ACCOUNT_GROUP.fill(_stranger, Administration.INSTALLERS),
			() -> Administration.removeMember(installers, _stranger));

		// Any other group is refused.
		Group users = Group.getGroupByName(kb(), AccountFixture.USERS);
		for (Object other : List.of(users, AccountFixture.USERS, "ehome.madeUp")) {
			String name = other instanceof Group group ? group.getName() : (String) other;
			assertRefused(I18NConstants.ERROR_NOT_EHOME_GROUP__GROUP.fill(name),
				() -> Administration.addMember(other, _stranger));
			assertRefused(I18NConstants.ERROR_NOT_EHOME_GROUP__GROUP.fill(name),
				() -> Administration.removeMember(other, _installer));
		}
		assertTrue(users.getMembers().contains(_installer));
		assertNull(Group.getGroupByName(kb(), "ehome.madeUp"));
	}

	/**
	 * The pages of the E-Home administrators (the group "E-Home administration" of the sidebar,
	 * <code>app.view.xml</code>) are withheld from everybody else (haumacher/ehome #165).
	 */
	public void testAdministrationPagesWithheld() throws Exception {
		AccessControl access = accessControl(ViewLoader.loadConfig(ViewLoader.fullPath("app.view.xml")),
			"ehomeAdministration", new IdentityHashMap<>());
		assertNotNull("The sidebar group of the E-Home administration.", access);
		UnitAccess unit = UnitAccess.create(new DefaultInstantiationContext(TestAccessRights.class), access);
		assertTrue(as(_admin, () -> unit.isOffered()));
		for (Person person : List.of(_installer, _owner, _viewer, _stranger)) {
			assertFalse(person.getName(), as(person, () -> unit.isOffered()));
		}
	}

	/**
	 * The access control of the unit with the given ID in the given configuration, <code>null</code> if
	 * there is none.
	 */
	private static AccessControl accessControl(Object value, String id, Map<Object, Boolean> seen) {
		if (!(value instanceof ConfigurationItem item)) {
			if (value instanceof Collection<?> values) {
				for (Object element : values) {
					AccessControl result = accessControl(element, id, seen);
					if (result != null) {
						return result;
					}
				}
			}
			return null;
		}
		if (seen.put(item, Boolean.TRUE) != null) {
			return null;
		}
		PropertyDescriptor idProperty = item.descriptor().getProperty("id");
		PropertyDescriptor accessProperty = item.descriptor().getProperty("access-control");
		if (idProperty != null && accessProperty != null && id.equals(item.value(idProperty))) {
			return (AccessControl) item.value(accessProperty);
		}
		for (PropertyDescriptor property : item.descriptor().getProperties()) {
			if (property.isDerived()) {
				continue;
			}
			AccessControl result = accessControl(item.value(property), id, seen);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	/**
	 * The values of an enumeration (a schedule's weekdays, its handling of missed firings) are read by
	 * every account: without that, the MCP tools leave them out of every object a user reads
	 * (haumacher/ehome #141, TL #30275).
	 */
	public void testClassifiersReadable() {
		for (String enumeration : List.of("ehome.runtime:Weekday", "ehome.runtime:MissedFiring")) {
			TLEnumeration type = (TLEnumeration) TLModelUtil.findType(enumeration);
			for (TLClassifier classifier : type.getClassifiers()) {
				for (Person person : List.of(_editor, _viewer, _stranger)) {
					assertTrue(enumeration, allowed(person, classifier, READ));
					assertFalse(enumeration, allowed(person, classifier, WRITE));
				}
			}
		}
	}

	public void testTokenUnreadable() {
		TLStructuredTypePart token = part("ehome.runtime:Controller#token");
		for (Person person : List.of(_owner, _editor, _viewer, _stranger)) {
			assertFalse(person.getName(), rights().isAllowed(person, _controller, token, READ));
		}
		assertTrue("An editor sets the token.", rights().isAllowed(_editor, _controller, token, WRITE));
		assertFalse(rights().isAllowed(_viewer, _controller, token, WRITE));
	}

	/**
	 * The signed binary image a deployment keeps (haumacher/ehome #176) is read by nobody through the
	 * model, the viewer least: it carries the authority of a deployment.
	 */
	public void testDeploymentImageUnreadable() {
		TLStructuredTypePart image = part("ehome.runtime:Deployment#image");
		for (Person person : List.of(_owner, _editor, _viewer, _stranger, _admin)) {
			assertFalse(person.getName(), rights().isAllowed(person, _deployment, image, READ));
		}
		assertTrue("The deployment reads in a project its viewer reads.",
			rights().isAllowed(_viewer, _deployment, READ));
	}

	public void testSecretAndCodeUnreadable() {
		TLStructuredTypePart secret = part("ehome.runtime:Controller#secret");
		for (Person person : List.of(_owner, _editor, _viewer, _stranger)) {
			assertFalse(person.getName(), rights().isAllowed(person, _controller, secret, READ));
		}
		TLStructuredTypePart code = part("ehome.project:Project#enrollmentCode");
		assertTrue("An owner reads the open enrollment code.", rights().isAllowed(_owner, _project, code, READ));
		assertTrue("An editor reads the open enrollment code.", rights().isAllowed(_editor, _project, code, READ));
		assertFalse("A viewer must not enroll a controller.", rights().isAllowed(_viewer, _project, code, READ));
		assertFalse(rights().isAllowed(_installer, _project, code, READ));
	}

	public void testDeviceValuesFollowTheDevice() {
		assertNull(as(_editor, () -> DeviceSettings.writeVeto(_device)));
		assertEquals(de.haumacher.ehome.programming.I18NConstants.ERROR_NOT_ALLOWED__DEVICE.fill(_device),
			as(_viewer, () -> DeviceSettings.writeVeto(_device)));
	}

	/**
	 * The bus scan (<code>ScanJob</code>) and the take-over of the devices found
	 * (<code>TakeOverJob</code>, haumacher/ehome #180) write the devices of the project, as does the
	 * command of the devices view opening the scan: allowed for an editor, refused for a viewer and
	 * for an installer without a role in the project.
	 */
	public void testScanCommand() {
		List<ViewCommandScan.Site> sites = new ArrayList<>();
		sites.add(ViewCommandScan.command("projects/scan.view.xml", "ScanJob"));
		for (ViewCommandScan.Site site : ViewCommandScan.commands("projects/scan.view.xml")) {
			if (site.writes().contains("TakeOverJob")) {
				sites.add(site);
			}
		}
		assertEquals("The scan, the take-over of the selected rows and of all rows.", 3, sites.size());
		sites.add(scanDialogCommand());
		for (ViewCommandScan.Site scan : sites) {
			assertTrue(scan.toString(), as(_editor, () -> scan.decide(_project)).isExecutable());
			assertFalse(scan.toString(), as(_viewer, () -> scan.decide(_project)).isExecutable());
			assertFalse(scan.toString(), as(_installer, () -> scan.decide(_project)).isExecutable());
			assertHidden(as(_stranger, () -> scan.decide(_project)));
		}
	}

	/** The command of the devices view opening the scan of the bus (haumacher/ehome #180). */
	private static ViewCommandScan.Site scanDialogCommand() {
		for (ViewCommandScan.Site site : ViewCommandScan.commands("projects/devices.view.xml")) {
			for (ConfigurationItem item : ViewCommandScan.items(site.config())) {
				if (item instanceof OpenDialogAction.Config dialog && "projects/scan.view.xml".equals(dialog.getDialogView().getPath())) {
					return site;
				}
			}
		}
		throw new AssertionError("No command opening the scan in the devices view.");
	}

	/**
	 * A dialog button reached through a checked command checks again: placing a device in a building
	 * part writes the device.
	 */
	public void testPlaceDeviceCommand() {
		ViewCommandScan.Site place = ViewCommandScan.command("projects/place-device.view.xml", "WithTransactionAction");
		assertFalse(as(_viewer, () -> place.decide(_device)).isExecutable());
		assertTrue(as(_editor, () -> place.decide(_device)).isExecutable());
	}

	/**
	 * The export of the runtime image signs it with the installation key: an editor's, not a
	 * viewer's. The export of the ETS project and of the project file only reads the project: a
	 * viewer's as well, not an outsider's.
	 */
	public void testExportCommands() {
		ViewCommandScan.Site image = ViewCommandScan.command("projects/deployment.view.xml", "ExportRuntimeImageAction");
		assertFalse(as(_viewer, () -> image.decide(_project)).isExecutable());
		assertTrue(as(_editor, () -> image.decide(_project)).isExecutable());

		for (String action : List.of("ExportKnxprojAction", "ExportProjectFileAction")) {
			ViewCommandScan.Site export = ViewCommandScan.command("projects/overview.view.xml", action);
			assertTrue(action, as(_viewer, () -> export.decide(_project)).isExecutable());
			assertFalse(action, as(_installer, () -> export.decide(_project)).isExecutable());
			assertHidden(as(_stranger, () -> export.decide(_project)));
		}
	}

	/**
	 * The update of a project from ETS (haumacher/ehome #125) writes the project: allowed for an
	 * editor, refused for a viewer and an outsider; so is the password dialog continuing it.
	 */
	public void testUpdateFromEtsCommand() {
		ViewCommandScan.Site update = ViewCommandScan.command("projects/overview.view.xml", "UpdateFromEtsJob");
		assertTrue(as(_editor, () -> update.decide(null, _project)).isExecutable());
		assertFalse(as(_viewer, () -> update.decide(null, _project)).isExecutable());
		assertFalse(as(_installer, () -> update.decide(null, _project)).isExecutable());
		assertHidden(as(_stranger, () -> update.decide(null, _project)));

		ViewCommandScan.Site password = ViewCommandScan.command("projects/update-password.view.xml", "UpdateFromEtsJob");
		assertTrue(as(_editor, () -> password.decide(Map.of(), "secret", _project)).isExecutable());
		assertFalse(as(_viewer, () -> password.decide(Map.of(), "secret", _project)).isExecutable());
	}

	/**
	 * The searches on the network read no object (haumacher/ehome #65, #152): the search for gateways
	 * of the page "Discover" serves setting up a project and is an installer's, who may create one;
	 * the search for controllers of a project's dialog is its editors'.
	 */
	public void testSearchCommands() {
		ViewCommandScan.Site search = ViewCommandScan.command("discover.view.xml", "DiscoverGatewaysAction");
		assertTrue(as(_installer, () -> search.decide()).isExecutable());
		for (Person person : List.of(_editor, _viewer, _stranger)) {
			assertHidden(as(person, () -> search.decide()));
		}

		ViewCommandScan.Site controllers =
			ViewCommandScan.command("controllers/discover-controllers.view.xml", "DiscoverControllersAction");
		assertTrue(as(_editor, () -> controllers.decide(_project)).isExecutable());
		assertFalse(as(_viewer, () -> controllers.decide(_project)).isExecutable());
		assertFalse(as(_installer, () -> controllers.decide(_project)).isExecutable());
	}

	/**
	 * The monitor's read writes a telegram to the bus through a TL-Script function of the app
	 * (<code>monitorRead</code>, haumacher/ehome #66): refused for a viewer, allowed for an editor.
	 * Clearing the viewer's own log only reads the project.
	 */
	public void testMonitorCommands() {
		ViewCommandScan.Site read = ViewCommandScan.command("projects/monitor.view.xml", "monitorRead()");
		assertFalse(as(_viewer, () -> read.decide(null, _project)).isExecutable());
		assertTrue(as(_editor, () -> read.decide(null, _project)).isExecutable());

		ViewCommandScan.Site clear = ViewCommandScan.command("projects/monitor.view.xml", "monitorClear()");
		assertTrue(as(_viewer, () -> clear.decide(null, _project)).isExecutable());
		assertHidden(as(_stranger, () -> clear.decide(null, _project)));
	}

	/**
	 * The commands of the members (haumacher/ehome #152) are the owner's: an editor and a viewer are
	 * refused them; leaving a project is every member's.
	 */
	public void testMemberCommandsAreTheOwners() {
		ViewCommandScan.Site add = ViewCommandScan.command("projects/member-add.view.xml", "projectAddMember()");
		ViewCommandScan.Site role = ViewCommandScan.command("projects/member-role.view.xml", "projectSetRole()");
		ViewCommandScan.Site remove = ViewCommandScan.command("projects/members.view.xml", "projectRemoveMember()");
		ViewCommandScan.Site leave = ViewCommandScan.command("projects/members.view.xml", "projectLeave()");
		assertTrue(as(_owner, () -> add.decide(_project)).isExecutable());
		assertTrue(as(_owner, () -> role.decide(_project, _viewer)).isExecutable());
		assertTrue(as(_owner, () -> remove.decide(_project, _viewer)).isExecutable());
		for (Person person : List.of(_editor, _viewer)) {
			assertFalse(person.getName(), as(person, () -> add.decide(_project)).isExecutable());
			assertFalse(person.getName(), as(person, () -> role.decide(_project, _viewer)).isExecutable());
			assertFalse(person.getName(), as(person, () -> remove.decide(_project, _viewer)).isExecutable());
			assertTrue(person.getName(), as(person, () -> leave.decide(_project)).isExecutable());
		}
		assertFalse(as(_installer, () -> add.decide(_project)).isExecutable());
		assertFalse(as(_installer, () -> leave.decide(_project)).isExecutable());
	}

	/**
	 * Adding a member by its mail address (in any case) or user name, the refusal of an address
	 * without an account, of a member added twice; changing a role, removing a member; the last owner
	 * stays, and leaving the project.
	 */
	public void testMemberOperations() {
		inTransaction(() -> {
			mail(_installer, "Installer.Access@Example.org");
			mail(_stranger, "stranger.access@example.org");
		});

		inTransaction(() -> assertSame(_installer,
			ProjectMembers.add(_project, " installer.access@example.ORG ", Role.editor.classifier())));
		assertEquals(Role.editor, ProjectMembers.role(_project, _installer));
		assertTrue(allowed(_installer, _project, WRITE));

		inTransaction(() -> assertSame(_stranger, ProjectMembers.add(_project, "ehomeTestStranger", "viewer")));
		assertEquals(Role.viewer, ProjectMembers.role(_project, _stranger));
		assertTrue(allowed(_stranger, _project, READ));
		assertFalse(allowed(_stranger, _project, WRITE));

		assertRefused(de.haumacher.ehome.project.I18NConstants.ERROR_NO_ACCOUNT,
			() -> ProjectMembers.add(_project, "nobody.access@example.org", Role.viewer));
		assertRefused(de.haumacher.ehome.project.I18NConstants.ERROR_ALREADY_MEMBER__ACCOUNT.fill(_viewer),
			() -> ProjectMembers.add(_project, _viewer, Role.editor));

		inTransaction(() -> ProjectMembers.setRole(_project, _stranger, Role.editor));
		assertTrue(allowed(_stranger, _project, WRITE));
		inTransaction(() -> ProjectMembers.remove(_project, _stranger));
		assertNull(ProjectMembers.role(_project, _stranger));
		assertFalse(allowed(_stranger, _project, READ));

		// The last owner neither loses the ownership nor leaves; with a second owner, the first may.
		assertRefused(de.haumacher.ehome.project.I18NConstants.ERROR_LAST_OWNER__ACCOUNT.fill(_owner),
			() -> ProjectMembers.setRole(_project, _owner, Role.editor));
		assertRefused(de.haumacher.ehome.project.I18NConstants.ERROR_LAST_OWNER__ACCOUNT.fill(_owner),
			() -> ProjectMembers.remove(_project, _owner));
		assertRefused(de.haumacher.ehome.project.I18NConstants.ERROR_LAST_OWNER__ACCOUNT.fill(_owner),
			() -> as(_owner, () -> {
				ProjectMembers.leave(_project);
				return null;
			}));
		inTransaction(() -> ProjectMembers.setRole(_project, _editor, Role.owner));
		inTransaction(() -> as(_owner, () -> {
			ProjectMembers.leave(_project);
			return null;
		}));
		assertNull(ProjectMembers.role(_project, _owner));
		assertFalse(allowed(_owner, _project, READ));
		assertTrue(allowed(_editor, _project, manageMembers()));
	}

	/**
	 * Members of a project see each other (haumacher/ehome #152, point 5a): a viewer reads the name
	 * and the mail address of the owner and of the other members, of no other account, and no
	 * further attribute of them. An owner of two projects is offered the members of the first when
	 * adding to the second. After the removal from the last shared project, the accounts do not see
	 * each other any more.
	 */
	public void testMembersSeeEachOther() {
		inTransaction(() -> {
			mail(_owner, "owner.access@example.org");
			mail(_stranger, "stranger.access@example.org");
		});
		for (Person member : List.of(_owner, _editor)) {
			assertTrue(member.getName(), rights().isDiscoverAllowed(_viewer, member));
			for (String attribute : List.of("name", "firstName", "lastName", "contact")) {
				assertTrue(attribute, rights().isAllowed(_viewer, member, part("tl.accounts:Person#" + attribute), READ));
			}
			for (String attribute : List.of("admin", "groups", "locale", "restrictedUser", "authDeviceID")) {
				assertFalse(attribute, rights().isAllowed(_viewer, member, part("tl.accounts:Person#" + attribute), READ));
			}
			assertFalse(rights().isAllowed(_viewer, member, READ));
			assertFalse(rights().isAllowed(_viewer, member, WRITE));
		}
		TLObject contact = (TLObject) _owner.getUser();
		assertTrue(rights().isAllowed(_viewer, contact, part("Contacts:PersonContact#email"), READ));
		assertFalse(rights().isAllowed(_viewer, contact, part("Contacts:PersonContact#phone"), READ));
		assertEquals("owner.access@example.org",
			as(_viewer, () -> eval("p -> $p.get(`tl.accounts:Person#contact`).get(`Contacts:PersonContact#email`)", _owner)));

		// No other account.
		assertFalse(rights().isDiscoverAllowed(_viewer, _stranger));
		assertFalse(rights().isAllowed(_viewer, _stranger, part("tl.accounts:Person#name"), READ));
		assertFalse(rights().isAllowed(_viewer, (TLObject) _stranger.getUser(), part("Contacts:PersonContact#email"), READ));
		assertFalse(rights().isDiscoverAllowed(_installer, _owner));

		// The own account stays readable (PersonalProfile, named again in the attribute grants).
		assertTrue(rights().isAllowed(_stranger, _stranger, part("tl.accounts:Person#name"), READ));
		assertTrue(rights().isAllowed(_stranger, (TLObject) _stranger.getUser(), part("Contacts:PersonContact#email"), READ));

		// The owner of a second project is offered the members of the first, nobody else.
		TLObject[] second = new TLObject[1];
		inTransaction(() -> {
			second[0] = create("ehome.project:Project");
			second[0].tUpdateByName("name", "Second access test");
			ProjectMembers.add(second[0], _owner, Role.owner);
		});
		// The options of "Add member" (projects/member-add.view.xml, haumacher/ehome #167): the members of the user's projects.
		String options = "p -> userProjects().flatMap(q -> $q.get(`" + MEMBERS + "`)).toSet()"
			+ ".filter(x -> !$p.get(`" + MEMBERS + "`).containsElement($x))";
		Collection<?> offered = SearchExpression.asCollection(as(_owner, () -> eval(options, second[0])));
		assertTrue(offered.contains(_viewer));
		assertTrue(offered.contains(_editor));
		assertFalse(offered.contains(_stranger));
		assertFalse(offered.contains(_installer));
		assertFalse("A member already.", offered.contains(_owner));

		// Shared in two projects: the removal from one keeps them visible, from the last not.
		inTransaction(() -> ProjectMembers.add(second[0], _viewer, Role.viewer));
		inTransaction(() -> ProjectMembers.remove(_project, _viewer));
		assertTrue(collaborator(_viewer, _owner));
		assertFalse(collaborator(_viewer, _editor));
		assertFalse(collaborator(_editor, _viewer));
		inTransaction(() -> ProjectMembers.remove(second[0], _viewer));
		assertFalse(collaborator(_viewer, _owner));
		assertFalse(collaborator(_owner, _viewer));
		// The decisions themselves are not asserted after the removal: the security storage answers a
		// role query with exactly the text of one asked before the removal with the earlier answer
		// (doc/tl-todo.md, "A role query answers from before a change of the stored roles"), while the
		// stored roles asked above are right.
	}

	/**
	 * The mail address of an account (haumacher/ehome #163): a user writes the own one (the tab
	 * "Profil" of the settings edits the contact of the account), nobody else's, not even of a member
	 * of a shared project, who reads it; an administrator writes every one (the page "Personen" of the
	 * administration). An address set this way adds the account to a project.
	 */
	public void testMailAddressOfAccount() {
		TLObject own = (TLObject) _stranger.getUser();
		TLObject owners = (TLObject) _owner.getUser();
		TLStructuredTypePart email = part("Contacts:PersonContact#email");
		assertTrue(rights().isAllowed(_stranger, own, email, WRITE));
		assertTrue("The other attributes of the own contact stay writable.",
			rights().isAllowed(_stranger, own, part("Contacts:PersonContact#firstname"), WRITE));
		assertTrue(rights().isAllowed(_viewer, owners, email, READ));
		assertFalse("A member does not write another member's address.", rights().isAllowed(_viewer, owners, email, WRITE));
		assertFalse(rights().isAllowed(_installer, own, email, WRITE));
		assertTrue(rights().isAllowed(PersonManager.getManager().getRoot(), own, email, WRITE));

		// Written as the form of the profile writes it: secured, as the user.
		String set = "c -> $c.set(`Contacts:PersonContact#email`, 'Stranger.Profile@Example.org')";
		try (Transaction tx = kb().beginTransaction()) {
			as(_stranger, () -> eval(set, own));
			tx.commit();
		}
		assertEquals("Stranger.Profile@Example.org", own.tValueByName("email"));
		String before = (String) owners.tValueByName("email");
		try (Transaction tx = kb().beginTransaction()) {
			as(_viewer, () -> eval(set, owners));
			tx.commit();
			fail("A member wrote the address of another account.");
		} catch (TopLogicException ex) {
			// Refused.
		}
		assertEquals(before, owners.tValueByName("email"));

		inTransaction(() -> assertSame(_stranger,
			ProjectMembers.add(_project, "stranger.profile@example.org", Role.viewer)));
		assertEquals(Role.viewer, ProjectMembers.role(_project, _stranger));
		inTransaction(() -> ProjectMembers.remove(_project, _stranger));
	}

	/**
	 * A refused command of an account that holds no role granting it on any object is hidden, not
	 * disabled (TL #30025).
	 */
	private static void assertHidden(ExecutableState state) {
		assertFalse(String.valueOf(state), state.isExecutable());
		assertTrue("Refused and hidden: " + state, state.isHidden());
	}

	private static void assertRefused(ResKey expected, Runnable operation) {
		try {
			inTransaction(operation);
			fail("Refusal expected: " + expected);
		} catch (TopLogicException ex) {
			assertEquals(expected, ex.getErrorKey());
		}
	}

	/**
	 * Probe: every object of every type of the E-Home modules in the database, after an ETS import,
	 * is readable and not writable for a viewer of the imported project, writable for its editor and
	 * not readable for an installer without a role in it (a type without grants and without an access
	 * parent would fail).
	 */
	public void testEveryTypeAfterImport() throws Exception {
		List<TLObject> before = TestEtsImport.instances("ehome.project:Project");
		TestEtsImport.importSample("samples/Test.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			inTransaction(() -> {
				for (TLObject project : projects) {
					ProjectMembers.add(project, _editor, Role.editor);
					ProjectMembers.add(project, _viewer, Role.viewer);
				}
			});
			List<String> problems = new ArrayList<>();
			int checked = 0;
			for (TLModule module : ModelService.getApplicationModel().getModules()) {
				if (!module.getName().startsWith("ehome.")) {
					continue;
				}
				for (TLClass type : module.getClasses()) {
					if (type.isAbstract()) {
						continue;
					}
					List<TLObject> objects = TestEtsImport.instances(TLModelUtil.qualifiedName(type));
					for (TLObject object : objects.subList(0, Math.min(3, objects.size()))) {
						// Only the imported project and the master data: other tests may leave objects behind.
						if (object.tType() != type || !SharedData.isWithin(object, projects) && !isMasterData(object)) {
							continue;
						}
						checked++;
						String name = label(object);
						if (!allowed(_viewer, object, READ)) {
							problems.add(name + ": viewer cannot read");
						}
						if (allowed(_viewer, object, WRITE)) {
							problems.add(name + ": viewer can write");
						}
						if (SharedData.isWithin(object, projects)) {
							if (!allowed(_editor, object, WRITE)) {
								problems.add(name + ": editor cannot write");
							}
							if (!allowed(_admin, object, READ)) {
								problems.add(name + ": E-Home administrator cannot read");
							}
							if (allowed(_admin, object, WRITE)) {
								problems.add(name + ": E-Home administrator without a role can write");
							}
							if (allowed(_installer, object, READ)) {
								problems.add(name + ": installer without a role can read");
							}
						}
					}
				}
			}
			assertEquals(List.of(), problems);
			assertTrue(String.valueOf(checked), checked > 30);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * Runs the test with the access checks on: a test runs in the system context, which bypasses
	 * them (TL #29798), also for the checks of an explicitly given person.
	 */
	@Override
	protected void runTest() throws Throwable {
		Throwable[] problem = { null };
		ThreadContext.withSecurityChecks(true, () -> {
			try {
				super.runTest();
			} catch (Throwable ex) {
				problem[0] = ex;
			}
		});
		if (problem[0] != null) {
			throw problem[0];
		}
	}

	/**
	 * Whether the given person holds <code>ehome.Collaborator</code> on the given account and on its
	 * contact, as the security storage holds the roles.
	 */
	private static boolean collaborator(Person person, Person account) {
		AccessManager manager = AccessManager.getInstance();
		boolean onAccount = manager.getRoles(person, account).stream().anyMatch(r -> "ehome.Collaborator".equals(r.getName()));
		boolean onContact = manager.getRoles(person, (BoundObject) account.getUser()).stream()
			.anyMatch(r -> "ehome.Collaborator".equals(r.getName()));
		assertEquals("Account and contact agree.", onAccount, onContact);
		return onAccount;
	}

	private static <T> T as(Person person, Supplier<T> computation) {
		return TLContextManager.inPersonContext(person, computation::get);
	}

	private static List<TLObject> visibleProjects() {
		List<TLObject> result = new ArrayList<>();
		for (Object project : SearchExpression.asCollection(eval("x -> all(`ehome.project:Project`)", null))) {
			result.add((TLObject) project);
		}
		result.removeIf(p -> !"Access test".equals(p.tValueByName("name")));
		return result;
	}

	/**
	 * Evaluates the given function secured, for the current user, as a script of a view does.
	 */
	private static Object eval(String function, Object argument) {
		try {
			return QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr", function)).execute(argument);
		} catch (com.top_logic.basic.config.ConfigurationException ex) {
			throw new AssertionError(ex);
		}
	}

	private List<TLObject> projectObjects() {
		return List.of(_project, _groupAddress, _device, _function, _dismissal, _deployment, _controller, _rule,
			_reaction, _trigger, _action, _parameter, _schedule, _applicationDatapoint);
	}

	private List<TLObject> masterData() {
		return List.of(_manufacturer, _catalog, _variant, _program, singleton("ehome.catalog:ManufacturerRoot"),
			singleton("ehome.datapoint:DatapointTypes"), singleton("ehome.operational:MaskVersions"),
			singleton("ehome.zigbee.catalog:ZigbeeCatalog"), singleton("ehome.template:DeviceTemplates"), _template);
	}

	/**
	 * Whether the object is a part of the shared master data: held, directly or further down, by one
	 * of the singletons of the master data.
	 */
	private boolean isMasterData(TLObject object) {
		TLObject root = object;
		while (root.tContainer() != null) {
			root = root.tContainer();
		}
		return masterData().contains(root);
	}

	private static String label(TLObject object) {
		return TLModelUtil.qualifiedName(object.tType());
	}

	private static boolean allowed(Person person, TLObject object, BoundCommandGroup operation) {
		return rights().isAllowed(person, object, operation);
	}

	private static BoundCommandGroup manageMembers() {
		return CommandGroupRegistry.resolve("ManageMembers");
	}

	private static ModelAccessRights rights() {
		return ModelAccessRights.getInstance();
	}

	private TLObject create(String typeName) {
		TLObject result = ModelService.getInstance().getFactory().createObject(type(typeName));
		_created.add(result);
		return result;
	}

	private static TLObject singleton(String typeName) {
		TLModule module = type(typeName).getModule();
		return module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
	}

	private static TLClass type(String typeName) {
		return (TLClass) TLModelUtil.findType(typeName);
	}

	private static TLStructuredTypePart part(String partName) {
		return (TLStructuredTypePart) TLModelUtil.findPart(partName);
	}

	private Person person(String name, String group) {
		Person result = AccountFixture.person(name, group);
		_persons.add(result);
		return result;
	}

	private static void mail(Person person, String address) {
		person.getUser().setEMail(address);
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestAccessRights.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, CommandGroupRegistry.Module.INSTANCE,
			ProjectOwners.Module.INSTANCE));
	}

}
