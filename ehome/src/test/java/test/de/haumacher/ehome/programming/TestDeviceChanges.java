/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceChanges.Entry;
import de.haumacher.ehome.programming.DeviceChanges.Reference;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;

/**
 * The changes of a device since its last download (haumacher/ehome #15), on the user's test system
 * (<code>samples/KNX-Testsystem.knxproj</code>, the actuator 1.3.5): the import is the reference of a
 * device ETS had downloaded, a download recorded through {@link DownloadService} moves it, a changed
 * value and a new connection are listed with the device's and the new state, the page of the value
 * is marked, and resetting one entry or all of them restores the state of the download. On the
 * Tastsensor 1.3.50, an object is connected and disconnected with the commands of an entry of a page
 * (haumacher/ehome #17).
 */
@SuppressWarnings("javadoc")
public class TestDeviceChanges extends BasicTestCase {

	private static final String ITEM_VIEW = "src/main/webapp/WEB-INF/views/projects/device-item.view.xml";

	private static final String CONNECT_VIEW = "src/main/webapp/WEB-INF/views/projects/connect-group-address.view.xml";

	public void testChanges() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(2, projects.size());
		TLObject project = projects.stream().filter(p -> "KNX-Testsystem".equals(p.tValueByName("name"))).findFirst()
			.orElseGet(() -> projects.stream().filter(p -> !"Test".equals(p.tValueByName("name"))).findFirst().get());
		TLObject other = projects.get(projects.get(0) == project ? 1 : 0);
		try {
			check(project, other);
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				for (TLObject created : projects) {
					created.tDelete();
				}
				tx.commit();
			}
		}
	}

	private void check(TLObject project, TLObject other) throws Exception {
		Map<Integer, TLObject> devices = devicesByAddress(project);
		TLObject actuator = devices.get(5);
		TLObject tastsensor = devices.get(50);

		// ETS had downloaded the actuator: the import is its reference, nothing is changed.
		assertNotNull(actuator.tValueByName("lastDownload"));
		assertNotNull(DeviceChanges.referenceRevision(actuator));
		assertEquals(0, DeviceChanges.sinceDownload(actuator).getCount());
		assertFalse(DeviceChanges.sinceDownload(actuator).isChanged(actuator));

		// A download recorded through the download service: the plan's revision is the reference.
		download(actuator);
		Object revision = actuator.tValueByName("downloadRevision");
		assertNotNull(revision);
		DeviceChanges afterDownload = DeviceChanges.sinceDownload(actuator);
		assertEquals(((Number) revision).longValue(), afterDownload.getRevision().getCommitNumber());
		assertEquals(0, afterDownload.getCount());

		// A value and a connection changed.
		DeviceConfiguration configuration = new DeviceConfiguration(actuator);
		ParameterView view = changeableChoice(configuration);
		Object page = configuration.getShownItems().get(view);
		String original = configuration.value(view);
		TLObject originalSetting = DeviceSettings.setting(actuator, view);
		assertNotNull(originalSetting);
		EnumerationValue changed = otherEntry(view, original);
		Interface placed = configuration.getActiveInterfaces().values().iterator().next();
		List<String> originalConnections = connectionKeys(DeviceSettings.connections(actuator, placed));
		TLObject address = unconnectedAddress(actuator, placed);
		TLObject connection;
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(actuator, view, changed);
			connection = DeviceSettings.connect(actuator, placed, address,
				(TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#receive"));
			tx.commit();
		}
		DeviceChanges changes = DeviceChanges.sinceDownload(actuator);
		assertEquals(2, changes.getCount());

		// The confirmation of a download names the number of changes.
		QueryExecutor confirmation = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr",
			"d -> downloadConfirmation($d).localize()"));
		String question = (String) asRoot(Locale.GERMAN, () -> confirmation.execute(actuator));
		assertTrue(question, question.contains("Änderungen seit dem letzten Download: 2."));
		Entry value = changes.getEntry(view);
		assertNotNull(value);
		assertEquals(original, value.referenceValue());
		assertEquals(encoded(view, changed), value.currentValue());
		assertSame(page, value.page());
		Entry wiring = changes.getEntry(placed);
		assertNotNull(wiring);
		assertTrue(wiring.isConnections());
		assertEquals(originalConnections, connectionKeys(wiring.referenceConnections()));
		List<String> expectedConnections = new ArrayList<>(originalConnections);
		expectedConnections.add(connectionKey(connection));
		expectedConnections.sort(null);
		assertEquals(expectedConnections, connectionKeys(wiring.currentConnections()));

		// The texts of the hints, the device's state.
		String hint = (String) asRoot(Locale.GERMAN, () -> changes.referenceText(placed));
		if (originalConnections.isEmpty()) {
			assertEquals("nicht verbunden", hint);
		} else {
			assertEquals(originalConnections.size(), hint.split(", ").length);
		}
		String valueHint = (String) asRoot(Locale.GERMAN, () -> changes.referenceText(view));
		assertNotNull(valueHint);
		assertFalse(valueHint.isEmpty());
		String newHint = DeviceChanges.connectionsText(wiring.currentConnections());
		assertTrue(newHint, newHint.contains(address.tValueByName("address").toString()));

		// The marks: the entry, its page and the pages above it, the device.
		assertTrue(changes.isChanged(view));
		assertTrue(changes.isChanged(actuator));
		if (page != actuator) {
			for (Object current = page; current != actuator; current = configuration.getParentPage(current)) {
				assertTrue(String.valueOf(current), changes.isChanged(current));
			}
		}
		for (Object unchanged : configuration.getPages(actuator)) {
			if (!isAbove(configuration, unchanged, page)) {
				assertFalse(String.valueOf(unchanged), changes.isChanged(unchanged));
			}
		}
		// The groups of the list: the page of the value, then the one of the object.
		List<Map<String, Object>> groups = changes.getGroups();
		assertFalse(groups.isEmpty());
		List<Object> listed = new ArrayList<>();
		for (Map<String, Object> group : groups) {
			assertNotNull(group.get("title"));
			listed.addAll((Collection<?>) group.get("items"));
		}
		assertEquals(List.of(view, placed).size(), listed.size());
		assertTrue(listed.contains(view));
		assertTrue(listed.contains(placed));

		// Against the defaults: the settings of the device, no connections.
		DeviceChanges defaults = DeviceChanges.of(actuator, Reference.DEFAULT);
		assertEquals(((Collection<?>) actuator.tValueByName("settings")).size(), defaults.getCount());
		assertNull(defaults.getEntry(placed));

		// Other devices are unaffected.
		assertEquals(0, DeviceChanges.sinceDownload(tastsensor).getCount());
		for (TLObject device : DeviceChanges.devices(other)) {
			DeviceChanges foreign = DeviceChanges.sinceDownload(device);
			assertTrue(foreign == null || foreign.getCount() == 0);
		}
		assertEquals(List.of(actuator), DeviceChanges.changedDevices(project));
		assertEquals(2, DeviceChanges.projectCount(project));
		assertNotNull(DeviceChanges.projectDiscardVeto(other));

		// Reset of the value alone.
		try (Transaction tx = kb().beginTransaction()) {
			changes.reset(view);
			tx.commit();
		}
		assertEquals(original, new DeviceConfiguration(actuator).value(view));
		assertSame("The setting is changed in place.", originalSetting, DeviceSettings.setting(actuator, view));
		DeviceChanges afterReset = DeviceChanges.sinceDownload(actuator);
		assertEquals(1, afterReset.getCount());
		assertNull(afterReset.getEntry(view));
		assertNotNull(afterReset.getEntry(placed));

		// The hint of the view: the device's state for a person.
		QueryExecutor hintScript = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr",
			viewAttribute(ITEM_VIEW, "derived-channel", "expr", "deviceReferenceHint")));
		String connectionsHint = (String) asRoot(Locale.GERMAN, () -> hintScript.execute(actuator, placed));
		assertTrue(connectionsHint, connectionsHint.startsWith("Gerät: "));
		assertNull(asRoot(Locale.GERMAN, () -> hintScript.execute(actuator, view)));

		// Change the value again, then discard all: values and connections as at the download.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(actuator, view, changed);
			tx.commit();
		}
		assertEquals(2, DeviceChanges.sinceDownload(actuator).getCount());
		try (Transaction tx = kb().beginTransaction()) {
			assertEquals(2, DeviceChanges.discard(actuator));
			tx.commit();
		}
		assertEquals(0, DeviceChanges.sinceDownload(actuator).getCount());
		assertEquals(original, new DeviceConfiguration(actuator).value(view));
		assertEquals(originalConnections, connectionKeys(DeviceSettings.connections(actuator, placed)));
		assertFalse(connection.tValid());
		assertNotNull(DeviceChanges.discardVeto(actuator));

		// Discarding on the project.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(actuator, view, changed);
			tx.commit();
		}
		assertNull(DeviceChanges.projectDiscardVeto(project));
		try (Transaction tx = kb().beginTransaction()) {
			assertEquals(1, DeviceChanges.discardProject(project));
			tx.commit();
		}
		assertEquals(0, DeviceChanges.projectCount(project));

		// A second download moves the reference: the change made before it is no change any more.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(actuator, view, changed);
			tx.commit();
		}
		assertEquals(1, DeviceChanges.sinceDownload(actuator).getCount());
		download(actuator);
		assertEquals(0, DeviceChanges.sinceDownload(actuator).getCount());
		assertEquals(encoded(view, changed), new DeviceConfiguration(actuator).value(view));

		checkPageConnection(tastsensor);
	}

	/**
	 * Connecting and disconnecting a communication object on a page of the configuration
	 * (haumacher/ehome #17): with the scripts of the dialog and of the entry, the connection appears
	 * in the communication objects of the device and at the group address, and disappears again.
	 */
	private void checkPageConnection(TLObject device) throws Exception {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Interface placed = (Interface) configuration.getShownItems().keySet().stream()
			.filter(Interface.class::isInstance).findFirst().orElseThrow();
		TLObject address = unconnectedAddress(device, placed);
		TLClassifier kind = DeviceSettings.defaultKind(device, placed);

		QueryExecutor connect = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("function",
			viewAttribute(CONNECT_VIEW, "execute-script", "function", "deviceConnect")));
		TLObject connection;
		try (Transaction tx = kb().beginTransaction()) {
			connection = (TLObject) connect.execute(device, placed, kind, address);
			tx.commit();
		}
		QueryExecutor entryConnections = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("items",
			viewAttribute(ITEM_VIEW, "object-list", "items", "deviceConnections")));
		assertTrue(((Collection<?>) asRoot(Locale.ENGLISH, () -> entryConnections.execute(device, placed))).contains(connection));
		assertTrue(DeviceSettings.interfaces(device).contains(placed));
		assertTrue(DeviceSettings.connections(device, placed).contains(connection));
		assertTrue(GroupAddressConnections.connections(address).contains(connection));
		assertTrue(DeviceChanges.sinceDownload(device).isChanged(placed));

		QueryExecutor remove = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("function",
			viewAttribute(ITEM_VIEW, "execute-script", "function", "deviceDisconnect")));
		try (Transaction tx = kb().beginTransaction()) {
			remove.execute(connection);
			tx.commit();
		}
		assertFalse(connection.tValid());
		assertFalse(((Collection<?>) asRoot(Locale.ENGLISH, () -> entryConnections.execute(device, placed))).contains(connection));
		assertFalse(GroupAddressConnections.connections(address).contains(connection));
		assertEquals(0, DeviceChanges.sinceDownload(device).getCount());
	}

	/** The attribute of the one element of the given name whose attribute contains the given text. */
	private static String viewAttribute(String file, String element, String attribute, String containing)
			throws Exception {
		NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(file))
			.getElementsByTagName(element);
		List<String> result = new ArrayList<>();
		for (int n = 0; n < nodes.getLength(); n++) {
			String value = ((Element) nodes.item(n)).getAttribute(attribute);
			if (value.contains(containing)) {
				result.add(value);
			}
		}
		assertEquals(result.toString(), 1, result.size());
		return result.get(0);
	}

	private static String encoded(ParameterView view, EnumerationValue entry) {
		return ParameterValues.encodeValue(view.getParameter().getType(), entry);
	}

	private static boolean isAbove(DeviceConfiguration configuration, Object page, Object inner) {
		for (Object current = inner; current != null; current = configuration.getParentPage(current)) {
			if (current == page) {
				return true;
			}
		}
		return false;
	}

	private static void download(TLObject device) throws InterruptedException {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		MockDevice mock = new MockDevice()
			.setProperty(0, 78, "00000000030d")
			.setProperty(3, 13, "0083000c21")
			.setState(1, 1).setState(2, 1).setState(3, 1);
		DownloadExecutor.Result result = DownloadService.download(device, plan, mock,
			new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
		assertTrue(result.failure(), result.success());
	}

	/**
	 * A shown, editable placement of an enumeration with another entry than its value, one the device
	 * has a setting for (so that the reference value is read from a historic setting).
	 */
	private static ParameterView changeableChoice(DeviceConfiguration configuration) {
		for (Option item : configuration.getShownItems().keySet()) {
			if (item instanceof ParameterView view && DeviceSettings.setting(configuration.getDevice(), view) != null && DeviceConfiguration.isEditable(view)
				&& view.getParameter().getType() instanceof EnumerationType type && type.getValues().size() > 1
				&& DeviceSettings.editVeto(configuration.getDevice(), view) == null) {
				return view;
			}
		}
		throw new AssertionError("No changeable choice.");
	}

	private static EnumerationValue otherEntry(ParameterView view, String value) {
		for (EnumerationValue entry : ((EnumerationType) view.getParameter().getType()).getValues()) {
			if (!ParameterValues.encodeValue(view.getParameter().getType(), entry).equals(value)) {
				return entry;
			}
		}
		throw new AssertionError("No other entry.");
	}

	private static TLObject unconnectedAddress(TLObject device, Interface placed) {
		List<Object> connected = new ArrayList<>();
		for (TLObject connection : DeviceSettings.connections(device, placed)) {
			connected.add(connection.tValueByName("target"));
		}
		for (TLObject address : DeviceSettings.groupAddresses(device)) {
			if (!connected.contains(address)) {
				return address;
			}
		}
		throw new AssertionError("No free group address.");
	}

	private static List<String> connectionKeys(List<TLObject> connections) {
		List<String> result = new ArrayList<>();
		for (TLObject connection : connections) {
			result.add(connectionKey(connection));
		}
		result.sort(null);
		return result;
	}

	private static String connectionKey(TLObject connection) {
		TLObject target = (TLObject) connection.tValueByName("target");
		return ((GA) target.tValueByName("address")).toString() + " "
			+ ((TLClassifier) connection.tValueByName("kind")).getName();
	}

	static Map<Integer, TLObject> devicesByAddress(TLObject project) {
		Map<Integer, TLObject> result = new HashMap<>();
		for (TLObject device : DeviceChanges.devices(project)) {
			result.put((Integer) device.tValueByName("address"), device);
		}
		return result;
	}

	static Object asRoot(Locale locale, Supplier<Object> supplier) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		try {
			return context.withLocale(locale, supplier);
		} finally {
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeviceChanges.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
