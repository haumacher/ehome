/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.connection.ControllerEndpoint;
import test.de.haumacher.ehome.connection.FakeBusController;
import test.de.haumacher.ehome.importer.SharedData;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.basic.util.ResKey;
import com.top_logic.element.config.annotation.TLOptions;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.search.providers.OptionsByExpression;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.BusSessionLink;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Controllers;
import de.haumacher.ehome.connection.I18NConstants;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.ProgrammingFunctions;
import de.haumacher.ehome.scan.ScanFunctions;
import de.haumacher.ehome.settings.OperatorSettings;

/**
 * The path of a project to the bus (haumacher/ehome #148, {@link BusPath}): the gateway or the
 * project's connected controller, chosen in "Choose the gateway" (the script of
 * <code>projects/choose-gateway.view.xml</code>, exclusive: the one chosen is set, the other
 * cleared), named in the confirmation of a download, and refused where the controller cannot carry a
 * bus session (not connected, firmware without it, a session open, another project's, on the LAN
 * only). With the installation not reaching local networks, the controller stays the path; writing
 * stays behind its switch on this path too; forgetting the controller clears the choice.
 */
@SuppressWarnings("javadoc")
public class TestProgrammingPath extends BasicTestCase {

	private static final String DIALOG = "src/main/webapp/WEB-INF/views/projects/choose-gateway.view.xml";

	private static final String SCAN = "src/main/webapp/WEB-INF/views/projects/scan.view.xml";

	private static final String CONTROLLER = "homecontrol-path";

	public void testPath() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = new ArrayList<>(SharedData.newProjects(before));
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		TLObject other = ControllerEndpoint.project("Path test, another project");
		projects.add(other);
		FakeBusController fake = null;
		FakeBusController foreign = null;
		try {
			TLObject device = SharedData.within("ehome.physical:Device", List.of(project)).get(0);
			Use use = useScript();

			// Nothing chosen.
			assertNull(BusPath.describe(project));
			ResKey none = de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_BUS_PATH;
			assertEquals(none, BusPath.veto(device));
			assertEquals(none, ProgrammingFunctions.compareVeto(device));
			assertEquals(none, ScanFunctions.scanVeto(project, 1, 1, false, false, false));
			assertRefused(null, () -> use.execute(project, null, null, null));

			// The gateway.
			use(use, project, "192.0.2.1", null);
			assertEquals("192.0.2.1", project.tValueByName("gateway"));
			assertEquals("Gateway 192.0.2.1:3671", BusPath.describe(device));
			assertNull(BusPath.veto(device));
			assertTrue(BusPath.of(device) instanceof BusPath.Gateway);

			// The controller: offered when enrolled, chosen instead of the gateway.
			fake = FakeBusController.connect(project, CONTROLLER, true);
			TLObject controller = fake.controller();
			assertTrue(options(project).contains(controller));
			assertEquals("The dialog opens with the current gateway.", "192.0.2.1", init("address", project));
			assertNull(init("selectedController", project));
			assertRefused(null, () -> use.execute(project, "192.0.2.9", null, controller));
			assertEquals("A new gateway and a new controller together: nothing changes.", "192.0.2.1",
				project.tValueByName("gateway"));
			// The address still pre-filled with the current gateway, the controller selected.
			use(use, project, "192.0.2.1", controller);
			assertNull("Exclusive: the gateway is cleared.", project.tValueByName("gateway"));
			assertEquals(controller, project.tValueByName(BusPath.PROGRAMMING_CONTROLLER));
			assertEquals("Controller " + CONTROLLER, BusPath.describe(device));
			assertNull(BusPath.veto(device));
			BusPath path = BusPath.of(device);
			assertTrue(path.viaController());
			assertSame(fake.connection(), ((BusPath.Controller) path).connection());
			assertNull(ProgrammingFunctions.compareVeto(device));
			assertNull(ScanFunctions.scanVeto(project, 1, 1, false, false, false));
			assertEquals(de.haumacher.ehome.scan.I18NConstants.ERROR_GATEWAY_THROUGH_CONTROLLER,
				ScanFunctions.scanVeto(project, null, null, false, true, false));
			assertNull(init("address", project));
			assertEquals("The dialog opens with the current controller.", controller, init("selectedController", project));

			// "Suchen" follows the checkboxes (its disabled-if over the dialog's channels).
			QueryExecutor scan = scanCheck();
			assertNull("through the controller, the gateway unchecked", scan.execute(project, 1, 3, null, null, null));
			assertNull(scan.execute(project, 1, 3, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE));
			assertEquals("the gateway checked", de.haumacher.ehome.scan.I18NConstants.ERROR_GATEWAY_THROUGH_CONTROLLER,
				scan.execute(project, 1, 3, null, Boolean.TRUE, null));
			assertEquals(de.haumacher.ehome.scan.I18NConstants.ERROR_NOTHING_TO_SCAN,
				scan.execute(project, null, null, null, null, null));

			// The confirmation names the controller.
			QueryExecutor confirmation = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr",
				"d -> downloadConfirmation($d)"));
			assertEquals(de.haumacher.ehome.programming.download.I18NConstants.CONFIRM_DOWNLOAD__DEVICE_ADDRESS_PATH_CHANGES
				.fill(device.tValueByName("name"), "1.0.1", "Controller " + CONTROLLER,
					de.haumacher.ehome.programming.download.I18NConstants.CHANGES_NO_DOWNLOAD),
				TestDeviceChanges.asRoot(Locale.ENGLISH, () -> confirmation.execute(device)));

			// One switch: writing off refuses before anything is sent to the controller.
			int sent = fake.busMessages().size();
			assertEquals(de.haumacher.ehome.programming.download.I18NConstants.ERROR_WRITE_DISABLED,
				ProgrammingFunctions.downloadVeto(device, DownloadPlan.Kind.FULL));
			assertRefused(de.haumacher.ehome.programming.download.I18NConstants.ERROR_WRITE_DISABLED,
				() -> CalimeroWriter.open(BusPath.of(device), 0x1101));
			assertEquals(sent, fake.busMessages().size());
			writing(() -> {
				assertNull(ProgrammingFunctions.downloadVeto(device, DownloadPlan.Kind.FULL));
				CalimeroWriter.open(BusPath.of(device), 0x1101).close();
			});
			fake.sync();
			assertEquals(List.of("open", "close"), fake.busMessages().subList(sent, fake.busMessages().size())
				.stream().map(m -> m.get("op")).toList());

			// An installation not reaching local networks keeps the controller (haumacher/ehome #150).
			localNetwork(() -> {
				assertNull(BusPath.veto(device));
				assertNull(ProgrammingFunctions.compareVeto(device));
				assertNull(ScanFunctions.scanVeto(project, 1, 1, false, false, false));
			});

			// The refusals of the controller, where the commands are.
			fake.status(false);
			assertEquals(I18NConstants.ERROR_BUS_FIRMWARE__CONTROLLER.fill(CONTROLLER),
				ProgrammingFunctions.compareVeto(device));
			fake.status(true);
			BusSessionLink session = BusSessionLink.open(fake.connection(), CONTROLLER);
			try {
				assertEquals(I18NConstants.ERROR_BUS_SESSION_OPEN__CONTROLLER.fill(CONTROLLER),
					ProgrammingFunctions.compareVeto(device));
				assertEquals(I18NConstants.ERROR_BUS_SESSION_OPEN__CONTROLLER.fill(CONTROLLER),
					ScanFunctions.scanVeto(project, 1, 1, false, false, false));
			} finally {
				session.close();
			}

			// The controller still selected, an address entered: the gateway; and back.
			use(use, project, "192.0.2.5", controller);
			assertEquals("192.0.2.5", project.tValueByName("gateway"));
			assertNull(project.tValueByName(BusPath.PROGRAMMING_CONTROLLER));
			assertEquals("192.0.2.5", init("address", project));
			use(use, project, "192.0.2.5", controller);
			assertEquals(controller, project.tValueByName(BusPath.PROGRAMMING_CONTROLLER));
			assertNull(project.tValueByName("gateway"));
			// Unchanged: stays.
			use(use, project, null, controller);
			assertEquals(controller, project.tValueByName(BusPath.PROGRAMMING_CONTROLLER));

			// Another project's controller is never the path; a controller on the LAN only is no option.
			foreign = FakeBusController.connect(other, "homecontrol-foreign", true);
			assertFalse(options(project).contains(foreign.controller()));
			set(project, foreign.controller());
			assertEquals(de.haumacher.ehome.connection.I18NConstants.ERROR_FOREIGN_CONTROLLER__CONTROLLER_PROJECT
				.fill("homecontrol-foreign", other.tValueByName("name")), BusPath.veto(device));
			TLObject lan = lanController(project);
			assertFalse(options(project).contains(lan));
			set(project, lan);
			assertEquals(I18NConstants.ERROR_BUS_NOT_ENROLLED__CONTROLLER.fill("LAN"), BusPath.veto(device));

			// Not connected: refused with its last contact.
			set(project, controller);
			fake.disconnect("network down");
			ControllerConnections.awaitKnowledgeBase();
			assertEquals(BusSessionLink.offline(controller), BusPath.veto(device));
			assertNotNull(controller.tValueByName("lastContact"));

			// Forgotten: nothing takes its place.
			Controllers.forget(controller);
			ControllerConnections.awaitKnowledgeBase();
			assertNull(project.tValueByName(BusPath.PROGRAMMING_CONTROLLER));
			assertEquals(none, BusPath.veto(device));
		} finally {
			if (fake != null) {
				fake.stop();
			}
			if (foreign != null) {
				foreign.stop();
			}
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * "Use" of the dialog: the decision of the channel <code>choice</code> over the dialog's channels,
	 * refused with its reason (the command's disabled-if) or carried out by the command's script.
	 */
	interface Use {
		Object execute(TLObject project, String address, Object selectedGateway, TLObject selectedController);
	}

	private static Use useScript() throws Exception {
		Element choiceChannel = element(DIALOG, "derived-channel", "name", "choice");
		List<String> inputs = List.of(choiceChannel.getAttribute("inputs").split("\\s*,\\s*"));
		assertEquals(List.of("project", "address", "selectedGateway", "selectedController"), inputs);
		String choiceText = choiceChannel.getElementsByTagName("expr").item(0).getTextContent();
		QueryExecutor choice = ViewScripts.compile(ExprFormat.INSTANCE.getValue("expr", choiceText), inputs);

		Element veto = element(DIALOG, "disabled-if", "expr", "$choice");
		Element command = (Element) veto.getParentNode().getParentNode();
		assertEquals(List.of("project", "choice"), List.of(command.getAttribute("inputs").split("\\s*,\\s*")));
		QueryExecutor refusal = ViewScripts.compile(ExprFormat.INSTANCE.getValue("expr", veto.getAttribute("expr")),
			List.of("project", "choice"));

		NodeList functions = command.getElementsByTagName("function");
		assertEquals(1, functions.getLength());
		QueryExecutor apply = ViewScripts.compile(
			ExprFormat.INSTANCE.getValue("function", functions.item(0).getTextContent()), List.of("project", "choice"));
		return (project, address, gateway, controller) -> {
			Object decided = choice.execute(project, address, gateway, controller);
			Object reason = refusal.execute(project, decided);
			if (reason != null && !Boolean.FALSE.equals(reason)) {
				throw new TopLogicException(reason instanceof ResKey key ? key : ResKey.text(String.valueOf(reason)));
			}
			return apply.execute(project, decided);
		};
	}

	/** The initialization of a channel of the dialog, for the given project. */
	private static Object init(String channel, TLObject project) throws Exception {
		Element element = element(DIALOG, "channel", "name", channel);
		assertEquals("project", element.getAttribute("inputs"));
		QueryExecutor init = ViewScripts.compile(ExprFormat.INSTANCE.getValue("init", element.getAttribute("init")),
			List.of("project"));
		return TestDeviceChanges.asRoot(Locale.ENGLISH, () -> init.execute(project));
	}

	/** The disabled-if of "Suchen" of the scan dialog over the command's inputs. */
	private static QueryExecutor scanCheck() throws Exception {
		Element rule = element(SCAN, "disabled-if", "expr", "scanVeto(");
		Element command = (Element) rule.getParentNode().getParentNode();
		List<String> inputs = List.of(command.getAttribute("inputs").split("\\s*,\\s*"));
		// The last input only re-decides the rule when a bus session or a controller changes (haumacher/ehome #161).
		assertEquals(List.of("project", "area", "line", "routers", "gatewayDevice", "programming", "busState"), inputs);
		inputs = inputs.subList(0, 6);
		return ViewScripts.compile(ExprFormat.INSTANCE.getValue("expr", rule.getAttribute("expr")), inputs);
	}

	private static Element element(String file, String tag, String attribute, String containing) throws Exception {
		NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(file))
			.getElementsByTagName(tag);
		List<Element> result = new ArrayList<>();
		for (int n = 0; n < nodes.getLength(); n++) {
			Element element = (Element) nodes.item(n);
			if (element.getAttribute(attribute).contains(containing)) {
				result.add(element);
			}
		}
		assertEquals(file + " " + tag + " " + containing, 1, result.size());
		return result.get(0);
	}

	private static void use(Use use, TLObject project, String address, TLObject controller) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TestDeviceChanges.asRoot(Locale.ENGLISH, () -> use.execute(project, address, null, controller));
			tx.commit();
		}
	}

	private static Collection<?> options(TLObject project) throws Exception {
		TLStructuredTypePart part =
			(TLStructuredTypePart) TLModelUtil.findPart("ehome.project:Project#" + BusPath.PROGRAMMING_CONTROLLER);
		OptionsByExpression.Config<?> config =
			(OptionsByExpression.Config<?>) part.getAnnotation(TLOptions.class).getGenerator();
		return SearchExpression.asList(QueryExecutor.compile(config.getFunction()).execute(project));
	}

	private static void set(TLObject project, TLObject controller) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project.tUpdateByName(BusPath.PROGRAMMING_CONTROLLER, controller);
			tx.commit();
		}
	}

	private static TLObject lanController(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject controller = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			controller.tUpdateByName("name", "LAN");
			controller.tUpdateByName("host", "192.0.2.2");
			List<Object> controllers = new ArrayList<>((Collection<?>) project.tValueByName("controllers"));
			controllers.add(controller);
			project.tUpdateByName("controllers", controllers);
			tx.commit();
			return controller;
		}
	}

	private interface Body {
		void run() throws Exception;
	}

	private interface Call {
		Object call() throws Exception;
	}

	private static void writing(Body body) throws Exception {
		OperatorSettings.Config config = OperatorSettings.config();
		boolean before = config.getProgrammingWriteEnabled();
		config.setProgrammingWriteEnabled(true);
		try {
			body.run();
		} finally {
			config.setProgrammingWriteEnabled(before);
		}
	}

	private static void localNetwork(Body body) throws Exception {
		OperatorSettings.Config config = OperatorSettings.config();
		boolean before = config.getLocalNetwork();
		config.setLocalNetwork(false);
		try {
			body.run();
		} finally {
			config.setLocalNetwork(before);
		}
	}

	/**
	 * The call is refused with the given reason; <code>null</code>: with any refusal of a script
	 * (<code>throw()</code>).
	 */
	private static void assertRefused(ResKey reason, Call call) {
		try {
			Object result = call.call();
			fail("Refusal expected, got: " + result);
		} catch (Throwable ex) {
			if (reason == null) {
				return;
			}
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof TopLogicException refusal && reason.equals(refusal.getErrorKey())) {
					return;
				}
			}
			throw new AssertionError("Refusal with " + reason + " expected: " + ex, ex);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProgrammingPath.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
