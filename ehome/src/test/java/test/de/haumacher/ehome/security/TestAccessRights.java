/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.importer.TestEtsImport;
import test.de.haumacher.ehome.view.ViewCommandScan;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.ModelAccessRights;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.BoundCommandGroup;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.tool.boundsec.simple.SimpleBoundCommandGroup;
import com.top_logic.tool.boundsec.wrap.Group;
import com.top_logic.tool.execution.ExecutableState;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.I18NConstants;

/**
 * The access definition of E-Home (<code>WEB-INF/autoconf/ehome.security.config.xml</code>, haumacher/ehome
 * #48) checked through the model access rights for an installer, a viewer and an account without
 * an E-Home group: on a project with a group address, a device, a function, a deployment, a rule with a
 * parameter, a reaction, a trigger and an action, a schedule entry and an application datapoint, on a
 * controller and its token, on a manufacturer with a catalog and an application program, and on the
 * singletons of the master data. The access checks the view commands declare (haumacher/ehome #65) are
 * evaluated for these accounts as the engine evaluates them for a button: the bus scan, a dialog
 * placing a device, the export of a signed runtime image, the read-only export of a project and the
 * search for gateways on the network.
 *
 * <p>
 * The roles of E-Home are global: an account that is refused a command holds no role granting it on
 * any object, so the engine hides the command instead of disabling it (TL #30025).
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

	private static final BoundCommandGroup CREATE = SimpleBoundCommandGroup.CREATE;

	private static final BoundCommandGroup DELETE = SimpleBoundCommandGroup.DELETE;

	private final List<TLObject> _created = new ArrayList<>();

	private Person _installer;

	private Person _viewer;

	private Person _stranger;

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
			_installer = person("ehomeTestInstaller", "ehome.installers");
			_viewer = person("ehomeTestViewer", "ehome.viewers");
			_stranger = person("ehomeTestStranger", null);

			_controller = create("ehome.runtime:Controller");
			_controller.tUpdateByName("name", "Access test");
			_controller.tUpdateByName("host", "localhost");
			_controller.tUpdateByName("token", "hct_0123456789abcdef0123456789abcdef");

			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Access test");
			_project.tUpdateByName("controller", _controller);

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
			for (Person person : List.of(_installer, _viewer, _stranger)) {
				if (person != null && person.tValid()) {
					person.tDelete();
				}
			}
		});
		_created.clear();
		super.tearDown();
	}

	public void testInstallerReadsAndEditsEverything() {
		for (TLObject object : objects()) {
			assertTrue(label(object), allowed(_installer, object, READ));
			assertTrue(label(object), allowed(_installer, object, WRITE));
		}
		assertTrue(allowed(_installer, _project, DELETE));
		assertTrue(allowed(_installer, _controller, DELETE));
		assertTrue(allowed(_installer, _device, DELETE));
		assertTrue(rights().isAllowedCreate(_installer, type("ehome.project:Project"), (TLObject) null));
		assertTrue(rights().isAllowedCreate(_installer, type("ehome.runtime:Controller"), (TLObject) null));
		assertTrue("An installer creates a group address in a project.",
			rights().isAllowedCreate(_installer, _groupRange, part("ehome.logical:GroupRange#parts")));
		assertTrue("An installer adds a reaction to a rule.",
			rights().isAllowedCreate(_installer, _rule, part("ehome.runtime:Rule#reactions")));
		assertTrue("An installer adds a rule to a project.",
			rights().isAllowedCreate(_installer, _project, part("ehome.project:Project#rules")));
		assertTrue("An installer adds an application datapoint to a project.",
			rights().isAllowedCreate(_installer, _project, part("ehome.project:Project#applicationDatapoints")));
		assertTrue("Import creates manufacturers.",
			rights().isAllowedCreate(_installer, singleton("ehome.catalog:ManufacturerRoot"),
				part("ehome.catalog:ManufacturerRoot#manufacturers")));
		assertTrue("An installer adds a device type to the Zigbee catalog.",
			rights().isAllowedCreate(_installer, singleton("ehome.zigbee.catalog:ZigbeeCatalog"),
				part("ehome.zigbee.catalog:ZigbeeCatalog#types")));
		assertTrue("An installer creates a device template.",
			rights().isAllowedCreate(_installer, singleton("ehome.template:DeviceTemplates"),
				part("ehome.template:DeviceTemplates#templates")));
	}

	public void testViewerReadsOnly() {
		for (TLObject object : objects()) {
			assertTrue(label(object), allowed(_viewer, object, READ));
			assertFalse(label(object), allowed(_viewer, object, WRITE));
			assertFalse(label(object), allowed(_viewer, object, DELETE));
		}
		assertFalse(rights().isAllowedCreate(_viewer, type("ehome.project:Project"), (TLObject) null));
		assertFalse(rights().isAllowedCreate(_viewer, type("ehome.runtime:Controller"), (TLObject) null));
		assertFalse(rights().isAllowedCreate(_viewer, _groupRange, part("ehome.logical:GroupRange#parts")));
		assertFalse(rights().isAllowedCreate(_viewer, _rule, part("ehome.runtime:Rule#reactions")));
		assertFalse(rights().isAllowedCreate(_viewer, _project, part("ehome.project:Project#schedules")));
		assertFalse(rights().isAllowedCreate(_viewer, _project, part("ehome.project:Project#applicationDatapoints")));
		assertFalse(rights().isAllowedCreate(_viewer, singleton("ehome.catalog:ManufacturerRoot"),
			part("ehome.catalog:ManufacturerRoot#manufacturers")));
		assertFalse(rights().isAllowedCreate(_viewer, singleton("ehome.zigbee.catalog:ZigbeeCatalog"),
			part("ehome.zigbee.catalog:ZigbeeCatalog#types")));
		assertFalse(rights().isAllowedCreate(_viewer, singleton("ehome.template:DeviceTemplates"),
			part("ehome.template:DeviceTemplates#templates")));
		assertTrue(rights().isAllowed(_viewer, _controller, part("ehome.runtime:Controller#name"), READ));
	}

	public void testWithoutGroupNothing() {
		for (TLObject object : objects()) {
			assertFalse(label(object), allowed(_stranger, object, READ));
			assertFalse(label(object), allowed(_stranger, object, WRITE));
		}
		assertFalse(rights().isAllowedCreate(_stranger, type("ehome.project:Project"), (TLObject) null));
	}

	public void testTokenUnreadable() {
		TLStructuredTypePart token = part("ehome.runtime:Controller#token");
		assertFalse(rights().isAllowed(_viewer, _controller, token, READ));
		assertFalse(rights().isAllowed(_installer, _controller, token, READ));
		assertFalse(rights().isAllowed(_stranger, _controller, token, READ));
		assertTrue("An installer sets the token.", rights().isAllowed(_installer, _controller, token, WRITE));
		assertFalse(rights().isAllowed(_viewer, _controller, token, WRITE));
	}

	public void testSecretAndCodeUnreadable() {
		TLStructuredTypePart secret = part("ehome.runtime:Controller#secret");
		assertFalse(rights().isAllowed(_viewer, _controller, secret, READ));
		assertFalse(rights().isAllowed(_installer, _controller, secret, READ));
		assertFalse(rights().isAllowed(_stranger, _controller, secret, READ));
		TLStructuredTypePart code = part("ehome.project:Project#enrollmentCode");
		assertTrue("An installer reads the open enrollment code.", rights().isAllowed(_installer, _project, code, READ));
		assertFalse("A viewer must not enroll a controller.", rights().isAllowed(_viewer, _project, code, READ));
		assertFalse(rights().isAllowed(_stranger, _project, code, READ));
	}

	public void testDeviceValuesFollowTheDevice() {
		assertNull(as(_installer, () -> DeviceSettings.writeVeto(_device)));
		assertEquals(I18NConstants.ERROR_NOT_ALLOWED__DEVICE.fill(_device),
			as(_viewer, () -> DeviceSettings.writeVeto(_device)));
	}

	/**
	 * The bus scan (<code>ScanJob</code>) writes the devices of the project: refused for a viewer
	 * (hidden, the viewer may write nothing), allowed for an installer.
	 */
	public void testScanCommand() {
		ViewCommandScan.Site scan = ViewCommandScan.command("projects/scan.view.xml", "ScanJob");
		assertHidden(as(_viewer, () -> scan.decide(_project)));
		assertHidden(as(_stranger, () -> scan.decide(_project)));
		assertTrue(as(_installer, () -> scan.decide(_project)).isExecutable());
	}

	/**
	 * A dialog button reached through a checked command checks again: placing a device in a building
	 * part writes the device.
	 */
	public void testPlaceDeviceCommand() {
		ViewCommandScan.Site place = ViewCommandScan.command("projects/place-device.view.xml", "WithTransactionAction");
		assertHidden(as(_viewer, () -> place.decide(_device)));
		assertTrue(as(_installer, () -> place.decide(_device)).isExecutable());
	}

	/**
	 * The export of the runtime image signs it with the installation key: an installer's, not a
	 * viewer's. The export of the ETS project only reads it: a viewer's as well.
	 */
	public void testExportCommands() {
		ViewCommandScan.Site image = ViewCommandScan.command("projects/deployment.view.xml", "ExportRuntimeImageAction");
		assertHidden(as(_viewer, () -> image.decide(_project)));
		assertTrue(as(_installer, () -> image.decide(_project)).isExecutable());

		ViewCommandScan.Site knxproj = ViewCommandScan.command("projects/overview.view.xml", "ExportKnxprojAction");
		assertTrue(as(_viewer, () -> knxproj.decide(_project)).isExecutable());
		assertHidden(as(_stranger, () -> knxproj.decide(_project)));
	}

	/**
	 * The project file (haumacher/ehome #117): its export only reads the project and holds no
	 * secrets, so a viewer may export it; its import creates a project, which only an installer may.
	 */
	public void testProjectFileCommands() {
		ViewCommandScan.Site export = ViewCommandScan.command("projects/overview.view.xml", "ExportProjectFileAction");
		assertTrue(as(_viewer, () -> export.decide(_project)).isExecutable());
		assertTrue(as(_installer, () -> export.decide(_project)).isExecutable());
		assertHidden(as(_stranger, () -> export.decide(_project)));

		ViewCommandScan.Site upload = ViewCommandScan.command("projects/overview.view.xml", "ImportProjectFileJob");
		assertTrue(as(_installer, () -> upload.decide((Object) null)).isExecutable());
		assertHidden(as(_viewer, () -> upload.decide((Object) null)));
		assertHidden(as(_stranger, () -> upload.decide((Object) null)));
	}

	/**
	 * The search for gateways reads no object: Read on the security root, which both E-Home roles
	 * hold and an account without an E-Home group not (the command is hidden for it).
	 */
	public void testSearchCommand() {
		ViewCommandScan.Site search = ViewCommandScan.command("discover.view.xml", "DiscoverGatewaysAction");
		assertTrue(as(_viewer, () -> search.decide()).isExecutable());
		assertTrue(as(_installer, () -> search.decide()).isExecutable());
		assertTrue(as(_stranger, () -> search.decide()).isHidden());
	}

	/**
	 * The monitor's read writes a telegram to the bus through a TL-Script function of the app
	 * (<code>monitorRead</code>, haumacher/ehome #66): refused for a viewer, allowed for an installer.
	 * Clearing the viewer's own log only reads the project.
	 */
	public void testMonitorCommands() {
		ViewCommandScan.Site read = ViewCommandScan.command("projects/monitor.view.xml", "monitorRead()");
		assertHidden(as(_viewer, () -> read.decide(null, _project)));
		assertTrue(as(_installer, () -> read.decide(null, _project)).isExecutable());

		ViewCommandScan.Site clear = ViewCommandScan.command("projects/monitor.view.xml", "monitorClear()");
		assertTrue(as(_viewer, () -> clear.decide(null, _project)).isExecutable());
		assertHidden(as(_stranger, () -> clear.decide(null, _project)));
	}

	/**
	 * A refused command of an account that holds no role granting it on any object is hidden, not
	 * disabled (TL #30025).
	 */
	private static void assertHidden(ExecutableState state) {
		assertFalse(String.valueOf(state), state.isExecutable());
		assertTrue("Refused and hidden: " + state, state.isHidden());
	}

	/**
	 * Probe: every object of every type of the E-Home modules in the database, after an ETS import,
	 * is readable and not writable for a viewer and writable for an installer (a type without grants
	 * and without an access parent would fail).
	 */
	public void testEveryTypeAfterImport() throws Exception {
		List<TLObject> before = TestEtsImport.instances("ehome.project:Project");
		TestEtsImport.importSample("samples/Test.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
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
						if (object.tType() != type) {
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
						if (!allowed(_installer, object, WRITE)) {
							problems.add(name + ": installer cannot write");
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

	private static <T> T as(Person person, Supplier<T> computation) {
		return TLContextManager.inPersonContext(person, computation::get);
	}

	private List<TLObject> objects() {
		return List.of(_project, _groupAddress, _device, _function, _dismissal, _deployment, _controller, _rule, _reaction,
			_trigger, _action, _parameter, _schedule, _applicationDatapoint, _manufacturer,
			_catalog, _variant, _program, singleton("ehome.catalog:ManufacturerRoot"), singleton("ehome.datapoint:DatapointTypes"),
			singleton("ehome.operational:MaskVersions"), singleton("ehome.zigbee.catalog:ZigbeeCatalog"),
			singleton("ehome.template:DeviceTemplates"), _template);
	}

	private static String label(TLObject object) {
		return TLModelUtil.qualifiedName(object.tType());
	}

	private static boolean allowed(Person person, TLObject object, BoundCommandGroup operation) {
		return rights().isAllowed(person, object, operation);
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

	private static Person person(String name, String group) {
		Person existing = Person.byName(name);
		if (existing != null) {
			existing.tDelete();
		}
		Person result =
			Person.create(kb(), name, TLSecurityDeviceManager.getInstance().getAuthenticationDevice("dbSecurity"));
		if (group != null) {
			Group.getGroupByName(kb(), group).addMember(result);
		}
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestAccessRights.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE));
	}

}
