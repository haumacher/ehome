/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.settings;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.runtime.ControllerFixture;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.top_logic.basic.AliasManager;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ProjectControllers;
import de.haumacher.ehome.discover.DiscoverGatewaysAction;
import de.haumacher.ehome.monitor.CalimeroBusLink;
import de.haumacher.ehome.monitor.ConnectMonitorAction;
import de.haumacher.ehome.monitor.ControllerTarget;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.ProgrammingFunctions;
import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.DiscoverControllersAction;
import de.haumacher.ehome.runtime.MdnsBrowser;
import de.haumacher.ehome.scan.CalimeroScanBus;
import de.haumacher.ehome.scan.ScanFunctions;
import de.haumacher.ehome.settings.I18NConstants;
import de.haumacher.ehome.settings.OperatorSettings;

/**
 * The settings of the operator (haumacher/ehome #149, #150, {@link OperatorSettings}): writing to the
 * bus and reaching local networks are switched through the configuration, and every path behind the
 * switches refuses with the reason.
 *
 * <p>
 * A test changes the configuration of the running application ({@link OperatorSettings#config()})
 * and resets it afterwards. The gateway address <code>192.0.2.1</code> (TEST-NET-1) is never reached:
 * every path refuses before it connects.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestOperatorSettings extends BasicTestCase {

	private static final InetSocketAddress GATEWAY = InetSocketAddress.createUnresolved("192.0.2.1", 3671);

	private static final String GATEWAY_TEXT = "192.0.2.1:3671";

	/**
	 * Without a value of the variables: writing off, local networks reached. The configuration is the
	 * one of <code>ehomeConf.config.xml</code>, with the aliases of <code>ehomeConf.xml</code>.
	 */
	public void testDefaults() throws Exception {
		assertEquals("false", AliasManager.getInstance().getAlias("%EHOME_PROGRAMMING_WRITE_ENABLED%"));
		assertEquals("true", AliasManager.getInstance().getAlias("%EHOME_LOCAL_NETWORK%"));
		OperatorSettings.Config config = OperatorSettings.config();
		assertTrue("Configured, not a default instance.",
			config.valueSet(config.descriptor().getProperty(OperatorSettings.Config.PROGRAMMING_WRITE_ENABLED)));
		assertTrue("Configured, not a default instance.",
			config.valueSet(config.descriptor().getProperty(OperatorSettings.Config.LOCAL_NETWORK)));
		assertFalse(CalimeroWriter.writeEnabled());
		assertTrue(OperatorSettings.localNetwork());
		assertNull(OperatorSettings.localNetworkVeto());
		assertNull(eval("localNetworkVeto()"));
	}

	/**
	 * haumacher/ehome #149: "Herunterladen" and "Physikalische Adresse programmieren" are refused with
	 * the reason while writing is off, offered while it is on.
	 */
	public void testWriteSwitch() throws Exception {
		withProject(device -> {
			assertEquals(de.haumacher.ehome.programming.download.I18NConstants.ERROR_WRITE_DISABLED,
				ProgrammingFunctions.downloadVeto(device, DownloadPlan.Kind.FULL));
			assertEquals(de.haumacher.ehome.programming.download.I18NConstants.ERROR_WRITE_DISABLED,
				ProgrammingFunctions.downloadVeto(device, DownloadPlan.Kind.ADDRESS));
			assertRefused(de.haumacher.ehome.programming.download.I18NConstants.ERROR_WRITE_DISABLED,
				() -> CalimeroWriter.open(GATEWAY, 0x1101));

			writing(true, () -> {
				assertTrue(CalimeroWriter.writeEnabled());
				assertNull(ProgrammingFunctions.downloadVeto(device, DownloadPlan.Kind.FULL));
				assertNull(ProgrammingFunctions.downloadVeto(device, DownloadPlan.Kind.ADDRESS));
			});
			assertFalse(CalimeroWriter.writeEnabled());
		});
	}

	/**
	 * haumacher/ehome #150: with the setting off, every path into the local networks refuses with the
	 * reason; a stored gateway address stays as data.
	 */
	public void testLocalNetworkOff() throws Exception {
		withProject(device -> {
			TLObject project = root(device);
			TLObject controller = controller(project);
			try {
				localNetwork(false, () -> writing(true, () -> {
					assertEquals(I18NConstants.ERROR_NO_LOCAL_NETWORK, eval("localNetworkVeto()"));

					// The gateway search and the interfaces of the server.
					assertRefused(() -> new DiscoverGatewaysAction(null,
						TypedConfiguration.newConfigItem(DiscoverGatewaysAction.Config.class)).execute(null, null));
					assertRefused(() -> eval("knxNetworkInterfaces()"));

					// A connection to a gateway address: monitor, programming, scan.
					assertRefused(() -> CalimeroBusLink.open(GATEWAY));
					assertRefused(() -> CalimeroReader.open(GATEWAY, 0x1101));
					assertRefused(() -> CalimeroWriter.open(GATEWAY, 0x1101));
					assertRefused(() -> CalimeroScanBus.open(GATEWAY));

					// The project with its stored gateway: the address stays, using it is refused.
					assertEquals(GATEWAY_TEXT, project.tValueByName("gateway"));
					assertRefused(() -> new ConnectMonitorAction(null,
						TypedConfiguration.newConfigItem(ConnectMonitorAction.Config.class)).execute(null, project));
					assertEquals(I18NConstants.ERROR_NO_LOCAL_NETWORK,
						ProgrammingFunctions.downloadVeto(device, DownloadPlan.Kind.FULL));
					assertEquals(I18NConstants.ERROR_NO_LOCAL_NETWORK,
						ProgrammingFunctions.downloadVeto(device, DownloadPlan.Kind.ADDRESS));
					assertEquals(I18NConstants.ERROR_NO_LOCAL_NETWORK, ProgrammingFunctions.compareVeto(device));
					assertEquals(I18NConstants.ERROR_NO_LOCAL_NETWORK,
						ScanFunctions.scanVeto(project, 1, 1, false, false, false));
					assertEquals(GATEWAY_TEXT, project.tValueByName("gateway"));

					// The controller search.
					assertRefused(() -> new DiscoverControllersAction(null,
						TypedConfiguration.newConfigItem(DiscoverControllersAction.Config.class)).execute(null, null));
					assertRefused(() -> MdnsBrowser.browse("_homecontrol._tcp.local", 100, List.of()));
					assertRefused(() -> MdnsBrowser.localNetworks());

					// A controller by host and token: entered, or registered with its host and token.
					assertRefused(() -> ControllerTarget.of(project, null, "192.0.2.1", null, "token"));
					assertRefused(() -> ControllerTarget.of(project, controller, null, null, null));
					assertRefused(() -> ControllerTarget.of(project, controller, null, null, "token"));
					assertCallRefused(new ControllerClient("192.0.2.1", 80, "token", Duration.ofSeconds(1)));
					assertCallRefused(Deployer.client(controller));

					// Creating a controller on the LAN in the project (haumacher/ehome #151).
					assertRefused(() -> ProjectControllers.create(project, "LAN", "192.0.2.2", null, "token"));
				}));

				// On: as before.
				assertNull(eval("localNetworkVeto()"));
				assertNull(ProgrammingFunctions.compareVeto(device));
				assertNull(ScanFunctions.scanVeto(project, 1, 1, false, false, false));
				assertEquals("192.0.2.1:80", Deployer.client(controller).address());
				assertEquals("192.0.2.1:80", ControllerTarget.of(project, controller, null, null, null).client().address());
			} finally {
				SharedData.deleteProjects(List.of(controller));
			}
		});
	}

	/**
	 * haumacher/ehome #150: the views offer nothing that reaches into the local networks unless the
	 * installation does - every element triggering such a path (the gateway search, the dialogs
	 * searching controllers, the interfaces of the server, host, port and
	 * token of a controller) is shown only under <code>localNetworkVeto()</code>. That the views
	 * load is <code>TestViewsLoad</code>.
	 */
	public void testViewsGuarded() throws Exception {
		List<String> unguarded = new ArrayList<>();
		int triggers = 0;
		Path root = Path.of("src/main/webapp/WEB-INF/views");
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".view.xml"))::iterator) {
				Element view = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
				String name = root.relativize(file).toString().replace('\\', '/');
				for (Element trigger : triggers(view, name)) {
					triggers++;
					if (!guarded(trigger)) {
						unguarded.add(name + ": <" + trigger.getTagName() + ">");
					}
				}
			}
		}
		assertTrue("The scan finds the entries: " + triggers, triggers >= 10);
		assertEquals("Entries reaching into the local networks, offered regardless of the setting", List.of(),
			unguarded);
	}

	private static final Set<String> TRIGGER_TAGS = Set.of("discover-knx-gateways", "discover-controllers");

	/**
	 * The dialogs reaching only into the local networks. "Choose the gateway"
	 * (<code>projects/choose-gateway.view.xml</code>) is not among them since haumacher/ehome #148: it also
	 * offers the project's controller, which stays available; its search and its address are guarded
	 * inside the dialog (the <code>&lt;discover-knx-gateways&gt;</code> found there).
	 */
	private static final Set<String> TRIGGER_DIALOGS = Set.of("controllers/discover-controllers.view.xml",
		"controllers/create-controller.view.xml", "controllers/controller-token.view.xml");

	private static final Set<String> CONTROLLER_FIELDS = Set.of("host", "port", "token");

	private static List<Element> triggers(Element element, String view) {
		List<Element> result = new ArrayList<>();
		String tag = element.getTagName();
		boolean trigger = TRIGGER_TAGS.contains(tag)
			|| TRIGGER_DIALOGS.contains(element.getAttribute("dialog-view"))
			|| element.getAttribute("options").contains("knxNetworkInterfaces(")
			|| view.equals("projects/connect-controller.view.xml") && tag.equals("value-input")
				&& CONTROLLER_FIELDS.contains(element.getAttribute("value"));
		if (trigger) {
			result.add(element);
		}
		for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Element childElement) {
				result.addAll(triggers(childElement, view));
			}
		}
		return result;
	}

	/**
	 * Whether the element is shown only while the installation reaches local networks: inside a
	 * <code>&lt;visible-if&gt;</code> or a <code>&lt;case&gt;</code> deciding by
	 * <code>localNetworkVeto</code>, or in a command whose executability hides it by
	 * <code>localNetworkVeto()</code>.
	 */
	private static boolean guarded(Element element) {
		for (Node node = element; node instanceof Element ancestor; node = node.getParentNode()) {
			String tag = ancestor.getTagName();
			if (tag.equals("visible-if") && ancestor.getAttribute("input").contains("localNetworkVeto")
				&& ancestor.getAttribute("expr").contains("== null")) {
				return true;
			}
			if (tag.equals("case") && ancestor.getAttribute("test").equals("$localNetworkVeto == null")) {
				return true;
			}
			for (Node child = ancestor.getFirstChild(); child != null; child = child.getNextSibling()) {
				if (child instanceof Element rules && rules.getTagName().equals("executability")) {
					for (Node rule = rules.getFirstChild(); rule != null; rule = rule.getNextSibling()) {
						if (rule instanceof Element visible && visible.getTagName().equals("visible-if")
							&& visible.getAttribute("expr").equals("localNetworkVeto() == null")) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private interface Body {
		void run() throws Exception;
	}

	private interface DeviceBody {
		void run(TLObject device) throws Exception;
	}

	private interface Call {
		Object call() throws Exception;
	}

	/**
	 * Runs the body with the single device of <code>samples/Test.knxproj</code>, its project having
	 * the gateway {@link #GATEWAY_TEXT}.
	 */
	private static void withProject(DeviceBody body) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				projects.get(0).tUpdateByName("gateway", GATEWAY_TEXT);
				tx.commit();
			}
			List<TLObject> devices = SharedData.within("ehome.physical:Device", projects);
			assertEquals(1, devices.size());
			body.run(devices.get(0));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static TLObject controller(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject controller = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			controller.tUpdateByName("name", "TestOperatorSettings");
			controller.tUpdateByName("host", "192.0.2.1");
			controller.tUpdateByName("port", Integer.valueOf(80));
			controller.tUpdateByName("token", "token");
			ControllerFixture.own(project, controller);
			tx.commit();
			return controller;
		}
	}

	private static TLObject root(TLObject obj) {
		TLObject result = obj;
		while (result.tContainer() != null) {
			result = result.tContainer();
		}
		return result;
	}

	private static void writing(boolean value, Body body) throws Exception {
		OperatorSettings.Config config = OperatorSettings.config();
		boolean before = config.getProgrammingWriteEnabled();
		config.setProgrammingWriteEnabled(value);
		try {
			body.run();
		} finally {
			config.setProgrammingWriteEnabled(before);
		}
	}

	private static void localNetwork(boolean value, Body body) throws Exception {
		OperatorSettings.Config config = OperatorSettings.config();
		boolean before = config.getLocalNetwork();
		config.setLocalNetwork(value);
		try {
			body.run();
		} finally {
			config.setLocalNetwork(before);
		}
	}

	private static void assertRefused(Call call) {
		assertRefused(I18NConstants.ERROR_NO_LOCAL_NETWORK, call);
	}

	private static void assertRefused(ResKey reason, Call call) {
		try {
			Object result = call.call();
			fail("Refusal expected, got: " + result);
		} catch (Throwable ex) {
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof TopLogicException refusal && reason.equals(refusal.getErrorKey())) {
					return;
				}
			}
			throw new AssertionError("Refusal with " + reason + " expected: " + ex, ex);
		}
	}

	private static void assertCallRefused(ControllerClient client) {
		try {
			client.status();
			fail("Refusal expected.");
		} catch (IOException ex) {
			assertEquals(Resources.getInstance().getString(I18NConstants.ERROR_NO_LOCAL_NETWORK), ex.getMessage());
		}
	}

	private static Object eval(String expr) throws Exception {
		return QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr", expr)).execute();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestOperatorSettings.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
