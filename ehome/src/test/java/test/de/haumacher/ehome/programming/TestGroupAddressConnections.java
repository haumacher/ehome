/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
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
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The group address tree of a project shows the connections of an address (haumacher/ehome #14),
 * on the user's test system (<code>samples/KNX-Testsystem.knxproj</code>): the Tastsensor 1.3.50
 * sends with its object 0 "Taste 1" on 0/0/1, the actuator 1.3.5 with its object 0 "Channel A" on
 * 0/0/34 (P-0563/0.xml, the connectors of the ComObjectInstanceRefs O-0_R-81 and O-0_R-10000).
 *
 * <p>
 * The children and labels are computed by the scripts of the view
 * (<code>projects/addresses.view.xml</code>), the connection by the script of the dialog
 * <code>projects/connect-object.view.xml</code>: an object connected from the address appears
 * under the address, among the connections of its device and in the device's download image;
 * removing it restores all three.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressConnections extends BasicTestCase {

	private static final String ADDRESSES_VIEW = "src/main/webapp/WEB-INF/views/projects/addresses.view.xml";

	private static final String CONNECT_VIEW = "src/main/webapp/WEB-INF/views/projects/connect-object.view.xml";

	public void testConnections() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			check(project);
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private void check(TLObject project) throws Exception {
		Map<Integer, TLObject> devices = devicesByAddress(project);
		TLObject tastsensor = devices.get(50);
		TLObject actuator = devices.get(5);
		TLObject ga1 = groupAddress(tastsensor, 1);
		TLObject ga34 = groupAddress(tastsensor, 34);

		QueryExecutor children = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("children",
			viewScript(ADDRESSES_VIEW, "children")));
		QueryExecutor label = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("label",
			viewAttribute(ADDRESSES_VIEW, "nodeContent", "label")));

		// 0/0/1: the Tastsensor sends with its object 0.
		List<Object> under1 = children(children, project, ga1);
		assertEquals(1, under1.size());
		TLObject sender = (TLObject) under1.get(0);
		assertSame(tastsensor, GroupAddressConnections.device(sender));
		assertEquals(0, GroupAddressConnections.object(sender).getPort().getIndex());
		assertEquals("send", kind(sender));
		assertEquals("1.3.50", GroupAddressConnections.addressText(tastsensor));
		String senderLabel = (String) asRoot(Locale.GERMAN, () -> label.execute(sender));
		assertTrue(senderLabel, senderLabel.endsWith(" (1.3.50) · 0 Taste 1 · Senden"));
		assertEquals(1, GroupAddressConnections.senders(ga1));
		assertTrue(GroupAddressConnections.problems(ga1).toString(), GroupAddressConnections.problems(ga1).isEmpty());
		// A range or an address has the label of its own, the device is no node any more.
		assertNull(asRoot(Locale.GERMAN, () -> label.execute(ga1)));
		assertTrue(children(children, project, sender).isEmpty());

		// 0/0/34: the actuator sends with its object 0.
		List<Object> under34 = children(children, project, ga34);
		assertEquals(1, under34.size());
		TLObject actuatorConnection = (TLObject) under34.get(0);
		assertSame(actuator, GroupAddressConnections.device(actuatorConnection));
		Interface channelA = GroupAddressConnections.object(actuatorConnection);
		assertEquals(0, channelA.getPort().getIndex());
		assertEquals("send", kind(actuatorConnection));
		String actuatorLabel = (String) asRoot(Locale.ENGLISH, () -> label.execute(actuatorConnection));
		assertTrue(actuatorLabel, actuatorLabel.endsWith(" (1.3.5) · 0 Channel A · Send"));

		// The two devices share no address.
		for (Object node : under1) {
			assertNotSame(actuator, GroupAddressConnections.device((TLObject) node));
		}

		// The dialog offers the devices of the project and the objects in effect of the chosen one.
		assertEquals(List.of(devices.get(1), actuator, tastsensor), GroupAddressConnections.devices(ga1));
		assertTrue(GroupAddressConnections.objects(actuator).contains(channelA));

		// Connect the actuator's object 0 to 0/0/1 from the address: it receives (it sends to 0/0/34).
		DownloadImage originalImage = DownloadImage.compute(actuator);
		int originalAssociations = originalImage.getAssociationTable().data()[0] & 0xFF;
		TLClassifier receive = DeviceSettings.defaultKind(actuator, channelA);
		assertEquals("receive", receive.getName());
		QueryExecutor connect = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("function",
			viewAttribute(CONNECT_VIEW, "execute-script", "function")));
		TLObject connection;
		try (Transaction tx = kb().beginTransaction()) {
			connection = (TLObject) asRoot(Locale.ENGLISH, () -> connect.execute(actuator, ga1, receive, channelA));
			tx.commit();
		}
		List<Object> connected = children(children, project, ga1);
		assertEquals(Arrays.asList(sender, connection), connected);
		assertEquals("receive", kind(connection));
		assertTrue(DeviceSettings.connections(actuator, channelA).contains(connection));
		assertEquals(1, GroupAddressConnections.senders(ga1));
		String connectedLabel = (String) asRoot(Locale.GERMAN, () -> label.execute(connection));
		assertTrue(connectedLabel, connectedLabel.endsWith(" (1.3.5) · 0 Kanal A · Empfangen")
			|| connectedLabel.endsWith(" (1.3.5) · 0 Channel A · Empfangen"));
		DownloadImage wired = DownloadImage.compute(actuator);
		assertEquals(wired.getProblems().toString(), 0, wired.getProblems().size());
		assertEquals(originalAssociations + 1, wired.getAssociationTable().data()[0] & 0xFF);
		assertTrue(association(wired.getAssociationTable(), 0, groupAddressIndex(wired.getAddressTable(), 1)));

		// Remove it with the operation of the view's command: the address and the image are as before.
		QueryExecutor remove = QueryExecutor.compile(ExprFormat.INSTANCE.getValue("function",
			viewScript(ADDRESSES_VIEW, "function")));
		Object selected;
		try (Transaction tx = kb().beginTransaction()) {
			// The result is filtered by the rights of the reading user.
			selected = asRoot(Locale.ENGLISH, () -> remove.execute(ga1, connection));
			tx.commit();
		}
		assertSame(ga1, selected);
		assertFalse(connection.tValid());
		assertEquals(List.of(sender), children(children, project, ga1));
		assertFalse(DeviceSettings.connections(actuator, channelA).stream().anyMatch(c -> c.tValueByName("target") == ga1));
		assertTrue(sameBytes(originalImage, DownloadImage.compute(actuator)));

		// An object of another size at the address is reported.
		Interface other = GroupAddressConnections.objects(tastsensor).stream()
			.filter(i -> (i.getSize() != null ? i.getSize() : i.getPort().getSize()) != 1)
			.findFirst().orElseThrow(() -> new AssertionError("No object of another size than 1 bit"));
		TLObject mismatch;
		try (Transaction tx = kb().beginTransaction()) {
			mismatch = DeviceSettings.connect(tastsensor, other, ga1,
				(TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#receive"));
			tx.commit();
		}
		assertFalse(GroupAddressConnections.problems(ga1).isEmpty());
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.disconnect(mismatch);
			tx.commit();
		}
		assertTrue(GroupAddressConnections.problems(ga1).isEmpty());
	}

	private static String kind(TLObject connection) {
		return ((TLClassifier) connection.tValueByName("kind")).getName();
	}

	private static List<Object> children(QueryExecutor children, TLObject project, TLObject node) {
		return new ArrayList<>((Collection<?>) asRoot(Locale.ENGLISH, () -> children.execute(project, Boolean.FALSE, node)));
	}

	private static TLObject groupAddress(TLObject device, int number) {
		return DeviceSettings.groupAddresses(device).stream()
			.filter(a -> ((GA) a.tValueByName("address")).getValue() == number)
			.findFirst().orElseThrow(() -> new AssertionError("No group address " + number));
	}

	/** The position of the group address in the address table (1 for the first after the device's own). */
	private static int groupAddressIndex(Block addressTable, int groupAddress) {
		byte[] data = addressTable.data();
		for (int n = 1; n < (data[0] & 0xFF); n++) {
			if ((((data[1 + 2 * n] & 0xFF) << 8) | (data[2 + 2 * n] & 0xFF)) == groupAddress) {
				return n;
			}
		}
		throw new AssertionError("The address table has no entry for " + groupAddress);
	}

	private static boolean association(Block associationTable, int object, int addressIndex) {
		byte[] data = associationTable.data();
		for (int n = 0; n < (data[0] & 0xFF); n++) {
			if ((data[1 + 2 * n] & 0xFF) == addressIndex && (data[2 + 2 * n] & 0xFF) == object) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameBytes(DownloadImage a, DownloadImage b) {
		List<Block> x = a.getBlocks();
		List<Block> y = b.getBlocks();
		if (x.size() != y.size()) {
			return false;
		}
		for (int n = 0; n < x.size(); n++) {
			if (x.get(n).address() != y.get(n).address() || !Arrays.equals(x.get(n).data(), y.get(n).data())) {
				return false;
			}
		}
		return true;
	}

	private static Map<Integer, TLObject> devicesByAddress(TLObject project) {
		Map<Integer, TLObject> result = new HashMap<>();
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					result.put((Integer) ((TLObject) device).tValueByName("address"), (TLObject) device);
				}
			}
		}
		assertEquals(3, result.size());
		return result;
	}

	private static String viewScript(String file, String element) throws Exception {
		NodeList nodes = elements(file, element);
		assertEquals(1, nodes.getLength());
		return ((Element) nodes.item(0)).getTextContent();
	}

	private static String viewAttribute(String file, String element, String attribute) throws Exception {
		NodeList nodes = elements(file, element);
		assertEquals(1, nodes.getLength());
		return ((Element) nodes.item(0)).getAttribute(attribute);
	}

	private static NodeList elements(String file, String element) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(file))
			.getElementsByTagName(element);
	}

	/**
	 * The value computed by the supplier as the technical administrator, in the given language
	 * (derived attributes are computed with the rights of the reading user, see
	 * {@link test.de.haumacher.ehome.importer.TestEtsImport#derived(TLObject, String)}).
	 */
	private static Object asRoot(Locale locale, Supplier<Object> supplier) {
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupAddressConnections.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
